package com.apimarketplace.orchestrator.execution.v2.services;

import com.apimarketplace.common.scaling.redis.RedisTimer;
import com.apimarketplace.common.scaling.timer.DistributedTimer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Timer scheduler for signal expiration, backed by {@link DistributedTimer}.
 *
 * Schedules timers to fire at the exact {@code expiresAt} instant, eliminating
 * the need for frequent DB polling. Each signal with an expiration gets a
 * corresponding timer that fires a callback on expiry.
 *
 * Edge cases handled:
 * - expiresAt in the past: fires immediately (delay=0)
 * - Signal resolved externally: timer cancelled via {@link #cancel(Long)}
 * - Server restart: {@link #recoverPendingTimers(List)} re-schedules from DB
 * - Missed timers: {@link SignalRecoveryService} re-schedules all pending timers at startup
 *
 * Thread-safe: delegates to {@link DistributedTimer} implementation.
 *
 * @see UnifiedSignalService
 * @see SignalRecoveryService
 */
@Component
public class SignalTimerScheduler implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(SignalTimerScheduler.class);
    private static final String TIMER_PREFIX = "signal:";

    private final DistributedTimer distributedTimer;

    /** Tracks active timer IDs for count monitoring and bulk cancel on destroy. */
    private final Set<String> activeTimerIds = ConcurrentHashMap.newKeySet();

    /** Callback invoked when a timer fires. Set by UnifiedSignalService to avoid circular dependency. */
    private Consumer<Long> expirationCallback;

    public SignalTimerScheduler(DistributedTimer distributedTimer) {
        this.distributedTimer = distributedTimer;
    }

    /**
     * Set the callback invoked when a timer expires.
     * Called by UnifiedSignalService during @PostConstruct.
     */
    public void setExpirationCallback(Consumer<Long> callback) {
        this.expirationCallback = callback;
    }

    /**
     * Wire up the orphan timer handler for cross-instance timer resolution.
     * When a RedisTimer instance claims a timer but has no local callback
     * (because it was scheduled by a different instance), this handler
     * parses the signalId and fires the same expiration callback.
     */
    @PostConstruct
    void wireOrphanHandler() {
        if (distributedTimer instanceof RedisTimer redisTimer) {
            redisTimer.setOrphanTimerHandler(timerId -> {
                // timerId format: "signal:{signalId}"
                if (timerId.startsWith(TIMER_PREFIX)) {
                    try {
                        long signalId = Long.parseLong(timerId.substring(TIMER_PREFIX.length()));
                        log.info("[SignalTimer] Orphan timer fired for signal {} (cross-instance)", signalId);
                        if (expirationCallback != null) {
                            expirationCallback.accept(signalId);
                        }
                    } catch (NumberFormatException e) {
                        log.warn("[SignalTimer] Unparseable orphan timerId: {}", timerId);
                    }
                }
            });
            log.info("[SignalTimer] Orphan timer handler wired for cross-instance resolution");
        }
    }

    /**
     * Schedule a timer to fire at the given instant.
     * If expiresAt is null, does nothing. If already past, fires immediately (delay=0).
     */
    public void schedule(Long signalId, Instant expiresAt) {
        if (expiresAt == null) {
            return;
        }

        long delayMs = Math.max(0, Duration.between(Instant.now(), expiresAt).toMillis());
        String timerId = toTimerId(signalId);

        activeTimerIds.add(timerId);
        distributedTimer.schedule(timerId, Duration.ofMillis(delayMs), () -> onTimerFired(signalId));

        log.debug("[SignalTimer] Scheduled timer for signal {} with delay {}ms", signalId, delayMs);
    }

    /**
     * Cancel a scheduled timer (signal resolved or cancelled externally).
     */
    public void cancel(Long signalId) {
        String timerId = toTimerId(signalId);
        boolean cancelled = distributedTimer.cancel(timerId);
        if (cancelled) {
            activeTimerIds.remove(timerId);
            log.debug("[SignalTimer] Cancelled timer for signal {}", signalId);
        }
    }

    /**
     * Cancel all timers for a set of signal IDs.
     * Used when cancelling signals by run or DAG+epoch.
     */
    public void cancelAll(Collection<Long> signalIds) {
        for (Long id : signalIds) {
            cancel(id);
        }
    }

    /**
     * Re-schedule pending timers from DB (startup recovery).
     */
    public void recoverPendingTimers(List<SignalTimerInfo> pendingTimers) {
        int scheduled = 0;
        for (SignalTimerInfo info : pendingTimers) {
            if (info.expiresAt() != null) {
                schedule(info.signalId(), info.expiresAt());
                scheduled++;
            }
        }
        log.info("[SignalTimer] Recovered {} pending timers from DB", scheduled);
    }

    /**
     * Info record for recovery - avoids coupling to SignalWaitEntity.
     */
    public record SignalTimerInfo(Long signalId, Instant expiresAt) {}

    /**
     * Get the number of active in-memory timers (for monitoring/debugging).
     */
    public int activeTimerCount() {
        return activeTimerIds.size();
    }

    @Override
    public void destroy() {
        log.info("[SignalTimer] Shutting down with {} active timers", activeTimerIds.size());
        for (String timerId : activeTimerIds) {
            distributedTimer.cancel(timerId);
        }
        activeTimerIds.clear();
    }

    private void onTimerFired(Long signalId) {
        activeTimerIds.remove(toTimerId(signalId));
        if (expirationCallback != null) {
            try {
                expirationCallback.accept(signalId);
            } catch (Exception e) {
                log.error("[SignalTimer] Error firing timer for signal {}: {}", signalId, e.getMessage(), e);
            }
        }
    }

    private static String toTimerId(Long signalId) {
        return TIMER_PREFIX + signalId;
    }
}
