package com.apimarketplace.conversation.service;

import com.apimarketplace.conversation.entity.Stream;
import com.apimarketplace.conversation.repository.StreamRepository;
import com.apimarketplace.conversation.config.StreamConfig;
import com.apimarketplace.conversation.streaming.StreamInterruptionService;
import com.apimarketplace.conversation.streaming.StreamMetadata;
import com.apimarketplace.conversation.streaming.StreamStateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Service for managing Stream TTL (Time To Live)
 * V3 Architecture: Uses Redis for stream state management.
 * Automatically handles stream timeouts and cleanup.
 * <p>
 * Two detection layers run in the same scan:
 * <ul>
 *   <li><b>Heartbeat loss (fast, ~2 min):</b> agent-service refreshes {@code stream:hb:{streamId}}
 *       every 30s with a 120s TTL. A registered agent stream older than the grace period with no
 *       heartbeat key means the producer pod died → rescue partial content immediately.</li>
 *   <li><b>Absolute timeout (fallback, 30 min):</b> any ACTIVE stream not updated for
 *       {@code stream.ttl.timeout-minutes} AND without a live heartbeat is interrupted -
 *       covers direct-chat streams, orchestrator ("workflow") streams, and streams produced
 *       by older agent-service versions that don't publish heartbeats. A stream whose
 *       heartbeat is still alive is skipped: the producer is still working.</li>
 * </ul>
 * Both layers go through {@link StreamInterruptionService} so the partial content buffered in
 * Redis is saved to conversation history BEFORE the stream trace is discarded.
 */
@Service
@ConditionalOnProperty(name = "deployment.mode", havingValue = "microservice", matchIfMissing = true)
public class StreamTTLService {

    private static final Logger logger = LoggerFactory.getLogger(StreamTTLService.class);

    /**
     * Grace period before heartbeat-loss detection kicks in. The heartbeat key has a 120s TTL,
     * so a stream younger than this may simply not have missed a beat yet.
     */
    private static final long HEARTBEAT_GRACE_MINUTES = 2;

    /** Heartbeat key written by agent-service (30s refresh, 120s TTL). */
    private static final String HEARTBEAT_KEY_PREFIX = "stream:hb:";

    /**
     * Providers whose streams are produced by a remote agent loop AND whose producer actually
     * publishes the {@code stream:hb:{streamId}} heartbeat key. Today this is ONLY agent-service
     * (provider "remote-agent", via the internal registerStream endpoint).
     * <p>
     * "workflow" (orchestrator-service) is deliberately NOT in this set: the orchestrator does
     * not (yet) emit heartbeats, so heartbeat-loss detection would falsely interrupt every
     * healthy workflow stream older than the 2-minute grace period. Its streams remain covered
     * by the absolute 30-minute timeout, which now rescues the partial content before deleting
     * the trace. Follow-up: once the orchestrator publishes heartbeats, add "workflow" back here
     * to get fast (~2 min) crash detection for workflow streams too.
     * <p>
     * Direct-chat streams created by ChatStreamingService/ChatStreamInitializer store the real
     * LLM provider name (openai, anthropic, …) and never publish a heartbeat - they MUST NOT be
     * subject to heartbeat-loss detection, only to the absolute timeout fallback.
     * <p>
     * BRIDGE conversation runs (provider "claude-code"/"codex"/…) DO hold a heartbeat while
     * dispatched - agent-service's ActiveStreamRegistry covers worker-executed bridge runs
     * (queued chats, execution-link routes) and conversation-service's
     * {@link com.apimarketplace.conversation.service.ai.BridgeStreamHeartbeat} covers the
     * DIRECT dispatches (interactive chat on a picked CLI provider, sync schedule/webhook).
     * They stay OUT of this set on purpose: their Redis provider is the CLI slug, which is
     * indistinguishable from a hypothetical heartbeat-less stream of the same provider, so
     * they get only the absolute-pass protection (live heartbeat → skip), never fast-loss
     * detection.
     */
    private static final Set<String> HEARTBEAT_MONITORED_PROVIDERS = Set.of("remote-agent");

    @Autowired
    private StreamRepository streamRepository;

    @Autowired
    private StreamStateService stateService;

    @Autowired
    private StreamInterruptionService streamInterruptionService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private StreamConfig streamConfig;

    /**
     * Scheduled task that runs every 5 minutes to check for expired streams
     */
    @Scheduled(fixedRateString = "${stream.ttl.scan-interval-minutes:5}", timeUnit = java.util.concurrent.TimeUnit.MINUTES)
    @SchedulerLock(name = "stream_ttl_scan", lockAtMostFor = "PT4M")
    @Transactional
    public void processExpiredStreams() {
        try {
            logger.debug("🕐 [STREAM TTL] Starting TTL scan...");

            // Process timeout streams
            int timeoutCount = processTimeoutStreams();

            // V3: Redis handles its own TTL - no need to clean up in-memory maps

            if (timeoutCount > 0) {
                logger.info("🕐 [STREAM TTL] TTL scan completed - Timeout: {}", timeoutCount);
            } else {
                logger.debug("🕐 [STREAM TTL] TTL scan completed - No streams processed");
            }

        } catch (Exception e) {
            logger.error("❌ [STREAM TTL] Error during TTL scan: {}", e.getMessage(), e);
        }
    }

    /**
     * Process streams that should be marked as TIMEOUT.
     * Runs the absolute-timeout pass first, then the heartbeat-loss fast pass.
     */
    @Transactional
    public int processTimeoutStreams() {
        // Streams already finalized in this scan - prevents the heartbeat pass (whose
        // candidate list is a superset of the timeout pass) from double-processing.
        Set<String> handledStreamIds = new HashSet<>();

        int processedCount = processAbsoluteTimeouts(handledStreamIds);
        processedCount += processHeartbeatLostStreams(handledStreamIds);
        return processedCount;
    }

    /**
     * Absolute timeout fallback: ACTIVE streams not updated for timeoutMinutes.
     * Streams whose {@code stream:hb:{streamId}} heartbeat key is still alive are SKIPPED:
     * a live heartbeat means the producer is still working (a legitimate agent execution can
     * run 65+ minutes) - killing it on wall-clock age alone would truncate a live run.
     * Rescue partial content via StreamInterruptionService; legacy error+delete only if
     * the rescue path cannot handle the stream (no Redis trace, or rescue threw).
     */
    private int processAbsoluteTimeouts(Set<String> handledStreamIds) {
        LocalDateTime timeoutThreshold = LocalDateTime.now().minusMinutes(streamConfig.getTimeoutMinutes());

        // Find active streams that haven't been updated for more than timeoutMinutes
        List<Stream> expiredStreams = streamRepository.findActiveStreamsOlderThan(timeoutThreshold);

        if (expiredStreams.isEmpty()) {
            logger.debug("🕐 [STREAM TTL] No streams to timeout");
            return 0;
        }

        int processedCount = 0;
        for (Stream stream : expiredStreams) {
            String reason = "Stream timeout after " + streamConfig.getTimeoutMinutes() + " minutes of inactivity";
            try {
                // Live heartbeat = producer still alive → not a timeout, leave the stream running.
                Boolean heartbeatAlive = stringRedisTemplate.hasKey(HEARTBEAT_KEY_PREFIX + stream.getStreamId());
                if (Boolean.TRUE.equals(heartbeatAlive)) {
                    logger.debug("🕐 [STREAM TTL] Stream {} past absolute timeout but heartbeat is alive - skipping (long-running execution)",
                            stream.getStreamId());
                    continue;
                }

                // Rescue first: saves the partial content from Redis to conversation history,
                // publishes a stopped event, and marks the Redis state INTERRUPTED.
                boolean rescued = false;
                try {
                    rescued = streamInterruptionService.interrupt(stream.getStreamId(), reason);
                } catch (Exception e) {
                    logger.warn("⚠️ [STREAM TTL] Rescue failed for stream {} - falling back to legacy timeout: {}",
                            stream.getStreamId(), e.getMessage());
                }

                if (rescued) {
                    ensureDbRowInterrupted(stream, reason);
                    handledStreamIds.add(stream.getStreamId());
                    processedCount++;
                    logger.info("⏰ [STREAM TTL] Stream {} interrupted with partial-content rescue (inactive since {})",
                            stream.getStreamId(), stream.getUpdatedAt());
                    continue;
                }

                // Legacy fallback: no Redis trace to rescue (or rescue threw) - mark as timeout
                stream.setStatus(Stream.StreamStatus.ERROR);
                stream.setErrorMessage(reason);
                stream.setCompletedAt(LocalDateTime.now());
                streamRepository.save(stream);

                // V3: Mark stream as error in Redis, then delete to clean up secondary indexes
                stateService.error(stream.getStreamId(), reason)
                        .then(stateService.delete(stream.getStreamId()))
                        .subscribe();

                handledStreamIds.add(stream.getStreamId());
                processedCount++;
                logger.info("⏰ [STREAM TTL] Stream {} marked as timeout (inactive since {})",
                        stream.getStreamId(), stream.getUpdatedAt());

            } catch (Exception e) {
                logger.error("❌ [STREAM TTL] Error processing timeout for stream {}: {}",
                        stream.getStreamId(), e.getMessage());
            }
        }

        return processedCount;
    }

    /**
     * Heartbeat-loss fast detection: registered agent streams (provider "remote-agent" -
     * see {@link #HEARTBEAT_MONITORED_PROVIDERS} for why "workflow" is excluded) older than the
     * grace period whose {@code stream:hb:{streamId}} key has expired. The agent-service
     * refreshes that key every 30s with a 120s TTL, so its absence past the grace period means
     * the producer pod is gone - interrupt immediately instead of waiting for the 30-minute
     * absolute timeout.
     * <p>
     * Streams that DO have a heartbeat are left alive even past the timeout grace - a long
     * tool-call is legitimate as long as the producer keeps beating. Non-agent chat streams
     * (real LLM provider in metadata, no heartbeat by design) are excluded and keep the
     * absolute timeout as their only guard.
     */
    private int processHeartbeatLostStreams(Set<String> handledStreamIds) {
        LocalDateTime graceThreshold = LocalDateTime.now().minusMinutes(HEARTBEAT_GRACE_MINUTES);
        List<Stream> candidates = streamRepository.findActiveStreamsOlderThan(graceThreshold);

        if (candidates.isEmpty()) {
            return 0;
        }

        int processedCount = 0;
        for (Stream stream : candidates) {
            String streamId = stream.getStreamId();
            if (handledStreamIds.contains(streamId)) {
                continue; // Already finalized by the absolute-timeout pass
            }
            try {
                StreamMetadata metadata = stateService.getMetadata(streamId).block();
                if (metadata == null) {
                    // No Redis trace (e.g. Redis TTL already reclaimed it) - nothing to rescue
                    // here; the absolute-timeout pass will eventually close the DB row.
                    continue;
                }
                if (!isHeartbeatMonitored(metadata)) {
                    // Direct chat stream (no heartbeat by design) - absolute timeout only
                    continue;
                }

                Boolean heartbeatAlive = stringRedisTemplate.hasKey(HEARTBEAT_KEY_PREFIX + streamId);
                if (Boolean.TRUE.equals(heartbeatAlive)) {
                    // Producer is alive - long tool-call is legitimate, leave the stream running
                    continue;
                }

                boolean rescued = streamInterruptionService.interrupt(streamId, "Agent heartbeat lost");
                if (rescued) {
                    ensureDbRowInterrupted(stream, "Agent heartbeat lost");
                    handledStreamIds.add(streamId);
                    processedCount++;
                    logger.info("💔 [STREAM TTL] Stream {} interrupted - agent heartbeat lost (last update {})",
                            streamId, stream.getUpdatedAt());
                }
            } catch (Exception e) {
                logger.error("❌ [STREAM TTL] Error during heartbeat check for stream {}: {}",
                        streamId, e.getMessage());
            }
        }

        return processedCount;
    }

    /**
     * Heartbeat detection only applies to streams whose producer actually publishes heartbeats
     * (today: agent-service, provider "remote-agent"). Orchestrator ("workflow") and direct
     * chat streams (real LLM provider name) never publish heartbeats and would be falsely
     * killed - see {@link #HEARTBEAT_MONITORED_PROVIDERS}.
     */
    private boolean isHeartbeatMonitored(StreamMetadata metadata) {
        return metadata.provider() != null
                && HEARTBEAT_MONITORED_PROVIDERS.contains(metadata.provider());
    }

    /**
     * Makes sure the DB row ends up INTERRUPTED after a successful rescue.
     * StreamInterruptionService updates the row via its own lookup; the entity held by this
     * scan may be a stale copy, so re-mark it if needed (idempotent).
     */
    private void ensureDbRowInterrupted(Stream stream, String reason) {
        if (stream.getStatus() != Stream.StreamStatus.INTERRUPTED) {
            stream.markAsInterrupted(reason);
            streamRepository.save(stream);
        }
    }

}
