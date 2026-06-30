package com.apimarketplace.common.scaling.redis;

import com.apimarketplace.common.scaling.timer.DistributedTimer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Redis-backed distributed timer using sorted sets.
 *
 * <p>Timers are stored as sorted set members (timerId) with score = executeAt epoch ms.
 * A local poller periodically claims due timers via {@code timer_claim_due.lua} and
 * executes their callbacks if registered on this instance.
 *
 * <p>Each instance polls independently. The Lua claim script atomically removes entries
 * from the ZSET, so each timer is claimed by exactly one instance. If the claiming
 * instance holds the callback (i.e., it was the one that scheduled the timer), the
 * callback executes. If not (e.g., scheduling instance crashed), the timer fires as
 * a no-op - signal expiry is separately handled by OrchestrationRecoveryService.
 *
 * <p>Failure strategy: FAIL-LOCAL - on Redis failure, falls back to in-memory
 * {@link java.util.concurrent.ScheduledExecutorService}.
 */
public class RedisTimer implements DistributedTimer {

    private static final Logger log = LoggerFactory.getLogger(RedisTimer.class);

    private static final String TIMER_ZSET_KEY = "orch:timer:signals";
    private static final String TIMER_PAYLOAD_KEY = "orch:timer:payloads";
    private static final long KEY_TTL_SECONDS = 86400; // 24h
    private static final int CLAIM_BATCH_SIZE = 50;
    private static final long POLL_INTERVAL_MS = 1000;

    private final StringRedisTemplate redisTemplate;
    private final MeterRegistry meterRegistry;
    private final ConcurrentHashMap<String, Runnable> callbacks = new ConcurrentHashMap<>();

    /**
     * Optional handler for timers claimed by this instance but with no local callback
     * (the scheduling instance is a different one in a multi-instance cluster).
     * Receives the timerId so the application can publish a cross-instance notification.
     */
    private volatile java.util.function.Consumer<String> orphanTimerHandler;

    private final DefaultRedisScript<Long> scheduleScript;
    private final DefaultRedisScript<Long> cancelScript;
    @SuppressWarnings("rawtypes")
    private final DefaultRedisScript<List> claimDueScript;

    private final ScheduledExecutorService poller;
    private volatile boolean running = true;

    public RedisTimer(StringRedisTemplate redisTemplate, MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.meterRegistry = meterRegistry;
        this.scheduleScript = LuaScriptLoader.load("timer_schedule.lua", Long.class);
        this.cancelScript = LuaScriptLoader.load("timer_cancel.lua", Long.class);
        this.claimDueScript = LuaScriptLoader.load("timer_claim_due.lua", List.class);

        this.poller = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "redis-timer-poller");
            t.setDaemon(true);
            return t;
        });
        this.poller.scheduleWithFixedDelay(this::pollDueTimers,
                POLL_INTERVAL_MS, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    @Override
    public void schedule(String timerId, Duration delay, Runnable callback) {
        long executeAtMs = Instant.now().plus(delay).toEpochMilli();

        try {
            Long result = Timer.builder("scaling.redis.timer.schedule")
                    .register(meterRegistry)
                    .record(() -> redisTemplate.execute(scheduleScript,
                            Arrays.asList(TIMER_ZSET_KEY, TIMER_PAYLOAD_KEY),
                            timerId,
                            String.valueOf(executeAtMs),
                            timerId,
                            String.valueOf(KEY_TTL_SECONDS)));

            if (result != null && result == 0L) {
                // Timer already existed - update the score (executeAt) and callback
                redisTemplate.opsForZSet().add(TIMER_ZSET_KEY, timerId, executeAtMs);
            }

            // Register callback AFTER successful Redis write to avoid race
            callbacks.put(timerId, callback);
            log.debug("[RedisTimer] Scheduled timer '{}' in {}ms", timerId, delay.toMillis());
        } catch (Exception e) {
            log.error("[RedisTimer] schedule failed (FAIL-LOCAL): timerId={}, error={}",
                    timerId, e.getMessage());
            // FAIL-LOCAL: schedule locally
            callbacks.put(timerId, callback);
            poller.schedule(() -> {
                Runnable cb = callbacks.remove(timerId);
                if (cb != null) cb.run();
            }, delay.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public boolean cancel(String timerId) {
        callbacks.remove(timerId);
        try {
            Long result = Timer.builder("scaling.redis.timer.cancel")
                    .register(meterRegistry)
                    .record(() -> redisTemplate.execute(cancelScript,
                            Arrays.asList(TIMER_ZSET_KEY, TIMER_PAYLOAD_KEY),
                            timerId));
            boolean cancelled = result != null && result == 1L;
            log.debug("[RedisTimer] Cancel timer '{}' -> {}", timerId, cancelled);
            return cancelled;
        } catch (Exception e) {
            log.warn("[RedisTimer] cancel failed: timerId={}, error={}", timerId, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean isActive(String timerId) {
        try {
            Double score = redisTemplate.opsForZSet().score(TIMER_ZSET_KEY, timerId);
            return score != null;
        } catch (Exception e) {
            log.warn("[RedisTimer] isActive failed: timerId={}", timerId);
            return callbacks.containsKey(timerId);
        }
    }

    /**
     * Poller: claims due timers and executes callbacks directly.
     * Only one instance claims each timer (Lua atomically removes from ZSET).
     * If this instance holds the callback, it is executed. Otherwise, it is a no-op
     * (signal expiry is handled by OrchestrationRecoveryService as a safety net).
     */
    @SuppressWarnings("unchecked")
    private void pollDueTimers() {
        if (!running) return;
        try {
            List<Object> result = redisTemplate.execute(claimDueScript,
                    Arrays.asList(TIMER_ZSET_KEY, TIMER_PAYLOAD_KEY),
                    String.valueOf(CLAIM_BATCH_SIZE));

            if (result == null || result.isEmpty()) return;

            // Result is flat: [timerId1, score1, payload1, timerId2, score2, payload2, ...]
            for (int i = 0; i + 2 < result.size(); i += 3) {
                String timerId = result.get(i).toString();
                executeCallback(timerId);
            }
        } catch (Exception e) {
            log.warn("[RedisTimer] pollDueTimers failed (will retry): {}", e.getMessage());
        }
    }

    /**
     * Set a handler for orphan timers - timers claimed by this instance but whose
     * callback was registered on a different instance (multi-instance scenario).
     *
     * <p>This allows the application to publish a cross-instance notification
     * (e.g., via Redis pub/sub) so the instance that owns the RunContext
     * can process the timer expiration.
     *
     * @param handler receives the timerId (e.g., "signal:timer:42")
     */
    public void setOrphanTimerHandler(java.util.function.Consumer<String> handler) {
        this.orphanTimerHandler = handler;
    }

    /**
     * Execute the callback for a claimed timer.
     */
    private void executeCallback(String timerId) {
        Runnable callback = callbacks.remove(timerId);
        if (callback != null) {
            try {
                callback.run();
                log.debug("[RedisTimer] Timer '{}' callback executed", timerId);
            } catch (Exception e) {
                log.error("[RedisTimer] Timer '{}' callback failed: {}", timerId, e.getMessage(), e);
            }
        } else {
            // No local callback - this timer was scheduled by another instance.
            // Notify via orphan handler if configured (cross-instance pub/sub).
            var handler = orphanTimerHandler;
            if (handler != null) {
                try {
                    handler.accept(timerId);
                    log.info("[RedisTimer] Timer '{}' delegated to orphan handler (cross-instance)", timerId);
                } catch (Exception e) {
                    log.warn("[RedisTimer] Orphan handler failed for timer '{}': {}", timerId, e.getMessage());
                }
            } else {
                log.debug("[RedisTimer] Timer '{}' claimed but no local callback - " +
                        "scheduling instance may have crashed, relying on recovery service", timerId);
            }
        }
    }

    /**
     * Shutdown the poller. Called by Spring via RedisScalingConfiguration @PreDestroy.
     */
    public void shutdown() {
        running = false;
        poller.shutdownNow();
        callbacks.clear();
    }
}
