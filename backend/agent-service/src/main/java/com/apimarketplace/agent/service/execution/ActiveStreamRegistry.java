package com.apimarketplace.agent.service.execution;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory registry of LLM streams currently executing on THIS agent-service instance.
 *
 * <p>Two responsibilities:
 * <ul>
 *   <li><b>Graceful drain</b> - each active stream registers an interruption handle
 *       (see {@link ConversationRedisStreamingCallback.ConversationCallback#interruptFromShutdown()}).
 *       When the shutdown drain window expires, {@link AgentQueueWorkerService} calls
 *       {@link #interruptAll()} so every surviving stream is finalized as INTERRUPTED and
 *       its partial content is persisted by conversation-service.</li>
 *   <li><b>Liveness heartbeat</b> - a Redis key {@code stream:hb:{streamId}} with a 120s TTL
 *       is refreshed every 30s for each active stream. The conversation-service reconciler
 *       treats an active stream WITHOUT this key as orphaned (the owning agent instance died
 *       hard, e.g. SIGKILL) and finalizes it on our behalf. Because the absence of the key is
 *       the death signal, the key MUST exist from the moment the stream becomes active - it is
 *       therefore written synchronously inside {@link #register(String, Runnable)}, not only
 *       at the next scheduled tick (a stream killed within the first 30s would otherwise look
 *       orphaned-but-never-alive, or worse, never be reconciled).</li>
 * </ul>
 *
 * <p>All Redis writes are best-effort: a Redis hiccup must never break a running execution,
 * so failures are swallowed and surfaced through a throttled warning only.
 */
@Slf4j
@Component
public class ActiveStreamRegistry {

    static final String HEARTBEAT_KEY_PREFIX = "stream:hb:";
    static final Duration HEARTBEAT_TTL = Duration.ofSeconds(120);
    static final long HEARTBEAT_INTERVAL_MS = 30_000;

    /** Minimum interval between heartbeat-failure warnings (avoid log spam while Redis is down). */
    private static final long WARN_THROTTLE_MS = 60_000;

    private final StringRedisTemplate redisTemplate;
    private final ConcurrentHashMap<String, Runnable> activeStreams = new ConcurrentHashMap<>();
    private volatile long lastHeartbeatWarnAtMs = 0;

    public ActiveStreamRegistry(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Register a stream as actively executing on this instance.
     *
     * <p>The heartbeat key is written immediately (not just at the next scheduled tick):
     * the conversation-service reconciler uses the key's absence as the "owning agent is
     * dead" signal, so an active stream must never be observable without its heartbeat.
     *
     * @param streamId        the stream ID (ignored when null)
     * @param interruptHandle handle invoked by {@link #interruptAll()} on shutdown drain expiry
     */
    public void register(String streamId, Runnable interruptHandle) {
        if (streamId == null || interruptHandle == null) return;
        activeStreams.put(streamId, interruptHandle);
        writeHeartbeat(streamId);
    }

    /**
     * Remove a stream from the registry (terminal path reached: completed, errored, or
     * interrupted) and delete its {@code stream:hb:{streamId}} liveness key (best-effort -
     * the TTL is the real cleanup; this just keeps Redis from carrying up to 120s of
     * stale liveness for a stream that already terminated).
     */
    public void unregister(String streamId) {
        if (streamId == null) return;
        activeStreams.remove(streamId);
        try {
            redisTemplate.delete(HEARTBEAT_KEY_PREFIX + streamId);
        } catch (Exception e) {
            log.debug("[STREAM_REGISTRY] Failed to delete heartbeat key for stream {} (TTL will expire it): {}",
                streamId, e.getMessage());
        }
    }

    /** Number of streams currently registered as active on this instance. */
    public int size() {
        return activeStreams.size();
    }

    /** Snapshot of the active stream IDs. */
    public Set<String> activeStreamIds() {
        return Set.copyOf(activeStreams.keySet());
    }

    /**
     * Invoke every registered interruption handle (shutdown drain expired).
     * Each handle is isolated in its own try/catch so one failing stream
     * never prevents the remaining survivors from being finalized.
     */
    public void interruptAll() {
        for (Map.Entry<String, Runnable> entry : activeStreams.entrySet()) {
            try {
                entry.getValue().run();
            } catch (Exception e) {
                log.warn("[STREAM_REGISTRY] Failed to interrupt stream {}: {}", entry.getKey(), e.getMessage());
            }
        }
    }

    /**
     * Liveness heartbeat: refresh {@code stream:hb:{streamId}} for every active stream.
     * TTL (120s) is 4x the refresh interval so a single missed tick (GC pause, Redis
     * blip) does not make a healthy stream look orphaned to the reconciler.
     *
     * <p>Two callers:
     * <ul>
     *   <li>the {@code @Scheduled} 30s tick during normal operation;</li>
     *   <li>{@code AgentQueueWorkerService}'s shutdown drain loop, explicitly on every
     *       ~30s slice - Spring 6.1+ stops the {@code ThreadPoolTaskScheduler} on
     *       {@code ContextClosedEvent} (before any lifecycle stop), so the scheduled tick
     *       is already dead during the drain and the streams still finishing there would
     *       otherwise look orphaned after the 120s TTL.</li>
     * </ul>
     */
    @Scheduled(fixedDelay = HEARTBEAT_INTERVAL_MS)
    public void refreshHeartbeats() {
        for (String streamId : activeStreams.keySet()) {
            writeHeartbeat(streamId);
        }
    }

    /** Best-effort heartbeat write - a Redis failure must never break a running execution. */
    private void writeHeartbeat(String streamId) {
        try {
            redisTemplate.opsForValue().set(HEARTBEAT_KEY_PREFIX + streamId, "1", HEARTBEAT_TTL);
        } catch (Exception e) {
            long now = System.currentTimeMillis();
            if (now - lastHeartbeatWarnAtMs >= WARN_THROTTLE_MS) {
                lastHeartbeatWarnAtMs = now;
                log.warn("[STREAM_REGISTRY] Failed to write heartbeat for stream {} (throttled warning): {}",
                    streamId, e.getMessage());
            }
        }
    }
}
