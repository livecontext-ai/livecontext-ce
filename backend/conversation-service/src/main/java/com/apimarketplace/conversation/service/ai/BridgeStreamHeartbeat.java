package com.apimarketplace.conversation.service.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code stream:hb:{streamId}} liveness heartbeats for bridge runs dispatched DIRECTLY
 * from conversation-service ({@link ConversationAgentService}'s streaming
 * {@code executeViaBridge} and the sync schedule/webhook bridge branch).
 *
 * <p>Why: those dispatches bypass agent-service entirely, so neither of the existing
 * heartbeat writers covers them (agent-service's {@code ActiveStreamRegistry} only sees
 * runs executed by its own worker). Their conversation {@code Stream} row's
 * {@code updated_at} is frozen at creation (the bridge publishes chunks straight to
 * Redis; nothing saves the JPA row mid-run), and {@link
 * com.apimarketplace.conversation.service.StreamTTLService}'s absolute-timeout pass
 * spares only live-heartbeat streams - so without this component a HEALTHY direct
 * bridge run was interrupted at the TTL (~10 min in cloud) even while actively
 * streaming.
 *
 * <p>Key contract mirrors agent-service's {@code ActiveStreamRegistry} exactly (same
 * prefix, 120s TTL, 30s refresh; written synchronously at register so an active stream
 * is never observable without its heartbeat). No interrupt handles here: the dispatch
 * is a blocking in-request HTTP call - on pod death the key lapses and the reaper
 * rescues the partial content exactly as it did before this protection existed.
 *
 * <p>All Redis writes are best-effort: a Redis hiccup must never break a running
 * execution.
 */
@Slf4j
@Component
public class BridgeStreamHeartbeat {

    static final String HEARTBEAT_KEY_PREFIX = "stream:hb:";
    static final Duration HEARTBEAT_TTL = Duration.ofSeconds(120);
    static final long HEARTBEAT_INTERVAL_MS = 30_000;

    /** Minimum interval between heartbeat-failure warnings (avoid log spam while Redis is down). */
    private static final long WARN_THROTTLE_MS = 60_000;

    private final StringRedisTemplate redisTemplate;
    private final Set<String> activeStreams = ConcurrentHashMap.newKeySet();
    private volatile long lastHeartbeatWarnAtMs = 0;

    public BridgeStreamHeartbeat(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /** Start heartbeating a stream (no-op on null). The first key write is synchronous. */
    public void register(String streamId) {
        if (streamId == null) return;
        activeStreams.add(streamId);
        writeHeartbeat(streamId);
    }

    /**
     * Stop heartbeating and delete the key (best-effort - the 120s TTL is the real
     * cleanup; this just keeps Redis from carrying stale liveness for a stream that
     * already terminated).
     */
    public void unregister(String streamId) {
        if (streamId == null) return;
        activeStreams.remove(streamId);
        try {
            redisTemplate.delete(HEARTBEAT_KEY_PREFIX + streamId);
        } catch (Exception e) {
            log.debug("[BRIDGE_HB] Failed to delete heartbeat key for stream {} (TTL will expire it): {}",
                streamId, e.getMessage());
        }
    }

    /** Number of streams currently heartbeating on this instance. */
    public int size() {
        return activeStreams.size();
    }

    /** 30s refresh tick; TTL (120s) is 4x the interval so one missed tick never orphans a healthy stream. */
    @Scheduled(fixedDelay = HEARTBEAT_INTERVAL_MS)
    public void refreshHeartbeats() {
        for (String streamId : activeStreams) {
            writeHeartbeat(streamId);
        }
    }

    private void writeHeartbeat(String streamId) {
        try {
            redisTemplate.opsForValue().set(HEARTBEAT_KEY_PREFIX + streamId, "1", HEARTBEAT_TTL);
        } catch (Exception e) {
            long now = System.currentTimeMillis();
            if (now - lastHeartbeatWarnAtMs >= WARN_THROTTLE_MS) {
                lastHeartbeatWarnAtMs = now;
                log.warn("[BRIDGE_HB] Failed to write heartbeat for stream {} (throttled warning): {}",
                    streamId, e.getMessage());
            }
        }
    }
}
