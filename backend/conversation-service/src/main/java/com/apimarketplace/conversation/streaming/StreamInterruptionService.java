package com.apimarketplace.conversation.streaming;

import com.apimarketplace.conversation.entity.Conversation;
import com.apimarketplace.conversation.entity.Stream;
import com.apimarketplace.conversation.repository.ConversationRepository;
import com.apimarketplace.conversation.service.ConversationHistoryService;
import com.apimarketplace.conversation.service.StreamService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Rescues streams whose producer died (agent-service pod drain/shutdown, lost heartbeat,
 * TTL timeout) by persisting the partial content accumulated in Redis BEFORE the stream
 * trace is discarded.
 * <p>
 * Mirrors the user-initiated path in {@code StreamStopHandler.stopStream()}: read the full
 * buffered content, save it as an assistant message, notify connected UIs via pub/sub, then
 * mark the stream INTERRUPTED and free the Redis keys.
 * <p>
 * Every step is best-effort: a failure in one step (e.g. pub/sub down) must not prevent the
 * following steps - the whole point is to lose as little as possible when infrastructure
 * is already degraded.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StreamInterruptionService {

    /**
     * Atomic claim key: several rescuers can race on the same stream (agent-service drain via
     * finalizeStream, the TTL reconciliation scan, and multiple conversation-service replicas).
     * SETNX on this key elects exactly one of them; the 5-minute TTL self-heals if the winner
     * dies mid-rescue.
     */
    private static final String INTERRUPT_CLAIM_KEY_PREFIX = "stream:interrupt:claim:";

    private static final Duration INTERRUPT_CLAIM_TTL = Duration.ofMinutes(5);

    private final StreamStateService stateService;
    private final StreamPubSubService pubSubService;
    private final ConversationHistoryService conversationHistoryService;
    private final StreamService streamService;
    private final ConversationRepository conversationRepository;
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * Interrupts a stream: saves the partial content as an assistant message, publishes a
     * stopped event so any connected UI updates, marks the stream INTERRUPTED and cleans up.
     *
     * @param streamId the Redis stream id
     * @param reason   human-readable interruption reason (stored on the DB row)
     * @return true if the stream was processed (partial rescued + state finalized),
     *         false if there was nothing to do (no Redis trace, already terminal, or another
     *         rescuer holds the interrupt claim)
     */
    public boolean interrupt(String streamId, String reason) {
        // Atomic claim - interrupt() is check-then-act and can be raced by the agent-service
        // drain, the TTL scan, and sibling replicas, each saving the partial a second time.
        // SETNX makes exactly one caller proceed; losers back off.
        try {
            Boolean claimed = stringRedisTemplate.opsForValue()
                    .setIfAbsent(INTERRUPT_CLAIM_KEY_PREFIX + streamId, "1", INTERRUPT_CLAIM_TTL);
            if (Boolean.FALSE.equals(claimed)) {
                log.debug("[INTERRUPT] Stream {} already claimed by another rescuer - skipping", streamId);
                return false;
            }
        } catch (Exception e) {
            // Best-effort: if Redis cannot arbitrate the claim, proceed anyway - a duplicated
            // partial save is far better than losing the content entirely.
            log.warn("[INTERRUPT] Interrupt claim failed for stream {} - proceeding without claim: {}",
                    streamId, e.getMessage());
        }

        StreamMetadata metadata;
        try {
            metadata = stateService.getMetadata(streamId).block();
        } catch (Exception e) {
            log.warn("[INTERRUPT] Failed to read metadata for stream {}: {}", streamId, e.getMessage());
            return false;
        }

        if (metadata == null) {
            log.debug("[INTERRUPT] No Redis trace for stream {} - nothing to rescue", streamId);
            return false;
        }
        if (metadata.state() != null && metadata.state().isTerminal()) {
            log.debug("[INTERRUPT] Stream {} already terminal ({}) - skipping", streamId, metadata.state());
            return false;
        }

        String conversationId = metadata.conversationId();
        String userId = resolveUserId(streamId, conversationId);

        // 1. Rescue the partial content accumulated in Redis (same pattern as StreamStopHandler)
        String partialContent = null;
        try {
            partialContent = stateService.getFullContent(streamId).block();
            if (partialContent != null && !partialContent.trim().isEmpty()) {
                conversationHistoryService.addMessage(
                        conversationId, "assistant", partialContent, metadata.model(),
                        Instant.now().toString(), null, userId
                );
                log.info("[INTERRUPT] Partial content saved for stream {}: {} chars (reason: {})",
                        streamId, partialContent.length(), reason);
            }
        } catch (Exception e) {
            log.error("[INTERRUPT] Failed to save partial content for stream {}: {}", streamId, e.getMessage());
        }

        // 2. Notify any connected UI so it stops showing a spinner and renders the partial
        try {
            pubSubService.publishStopped(streamId, partialContent != null ? partialContent : "").block();
        } catch (Exception e) {
            log.warn("[INTERRUPT] Failed to publish stopped event for stream {}: {}", streamId, e.getMessage());
        }

        // 3. Mark INTERRUPTED in Redis then delete - the content is saved, free the keys.
        //    Delete is best-effort: Redis TTL will reclaim the keys anyway if it fails.
        try {
            stateService.updateState(streamId, StreamState.INTERRUPTED).block();
            stateService.delete(streamId).subscribe(
                    deleted -> {},
                    e -> log.warn("[INTERRUPT] Redis cleanup failed for stream {}: {}", streamId, e.getMessage())
            );
        } catch (Exception e) {
            log.warn("[INTERRUPT] Failed to finalize Redis state for stream {}: {}", streamId, e.getMessage());
        }

        // 4. Reflect the interruption on the DB row if one exists (streams registered via
        //    registerStream now have one; legacy external streams may not)
        try {
            streamService.markStreamAsInterrupted(streamId, reason);
        } catch (Exception e) {
            log.warn("[INTERRUPT] Failed to mark DB row interrupted for stream {}: {}", streamId, e.getMessage());
        }

        return true;
    }

    /**
     * Resolves the userId to attribute the rescued assistant message to.
     * Preference order: DB stream row → conversation owner → "system".
     * Externally-registered streams store userId="internal" in Redis, which is not a real
     * user - so the Redis metadata userId is deliberately NOT used here.
     */
    private String resolveUserId(String streamId, String conversationId) {
        try {
            Optional<Stream> dbStream = streamService.getStreamByStreamId(streamId);
            if (dbStream.isPresent() && dbStream.get().getUserId() != null) {
                return dbStream.get().getUserId();
            }
        } catch (Exception e) {
            log.debug("[INTERRUPT] DB stream lookup failed for {}: {}", streamId, e.getMessage());
        }
        try {
            if (conversationId != null) {
                return conversationRepository.findById(conversationId)
                        .map(Conversation::getUserId)
                        .orElse("system");
            }
        } catch (Exception e) {
            log.debug("[INTERRUPT] Conversation lookup failed for {}: {}", conversationId, e.getMessage());
        }
        return "system";
    }
}
