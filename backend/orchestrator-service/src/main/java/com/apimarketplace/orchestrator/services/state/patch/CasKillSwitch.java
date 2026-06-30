package com.apimarketplace.orchestrator.services.state.patch;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Gauge;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Plan v4 §1.11 - kill-switches for the CAS dispatcher.
 *
 * <p>Two protection layers:
 *
 * <ol>
 *   <li><b>Pub/sub global kill-switch</b> - subscribes to Redis channel
 *       {@code feature_flags:cas-state-snapshot:flip}. Operators can publish
 *       any message to instantly disable CAS cluster-wide (sub-second
 *       propagation, no redeploy). When the kill-switch is active, the
 *       dispatcher (StateSnapshotService.tryCasPath) falls through to the
 *       pessimistic-lock path. Auto-resets after {@link #PUBSUB_DISABLE_DURATION}
 *       so operators can validate stability post-fix and the switch doesn't
 *       latch indefinitely.</li>
 *   <li><b>Local fast circuit breaker</b> - tumbling 10s window of CAS
 *       outcomes via {@link #recordCasSuccess()} / {@link #recordCasConflict()}
 *       calls from the dispatcher. If conflict rate &gt; 50% over a window
 *       of ≥ 20 attempts, replica-local CAS disabled for 60s. Auto-recovers
 *       after the cooldown.</li>
 * </ol>
 *
 * <p>The {@code pg_stat_activity} lock-wait watcher mentioned in plan §1.11
 * is deferred - it requires a dedicated scheduled DB query and operator
 * runbook integration. The LOCAL circuit breaker covers the same failure
 * mode (sustained conflict rate = sustained DB contention) with less
 * surface area.
 *
 * <p>{@code RedisMessageListenerContainer} is provided by
 * {@code RedisCacheConfig}; injection is {@code required=false} so narrow
 * Spring tests boot without Redis.
 */
@Component
public class CasKillSwitch {

    private static final Logger log = LoggerFactory.getLogger(CasKillSwitch.class);

    /** Pub/sub channel for global CAS flip. */
    static final String CHANNEL = "feature_flags:cas-state-snapshot:flip";

    /** Pub/sub kill-switch auto-reset duration - operators must validate before re-enable. */
    static final Duration PUBSUB_DISABLE_DURATION = Duration.ofMinutes(15);

    /** Local circuit breaker sliding window. */
    static final Duration WINDOW = Duration.ofSeconds(10);

    /** Minimum samples in window before the circuit breaker can trip. */
    static final int MIN_SAMPLES = 20;

    /** Conflict-rate trigger (50% per plan §1.11 §19). */
    static final double CONFLICT_RATE_THRESHOLD = 0.50;

    /** Local circuit-breaker cooldown after trip. */
    static final Duration CIRCUIT_COOLDOWN = Duration.ofSeconds(60);

    @Autowired(required = false)
    private RedisMessageListenerContainer listenerContainer;

    private final AtomicBoolean pubsubKilled = new AtomicBoolean(false);
    private final AtomicReference<Instant> pubsubKilledAt = new AtomicReference<>(Instant.MIN);

    private final AtomicBoolean circuitOpen = new AtomicBoolean(false);
    private final AtomicReference<Instant> circuitOpenedAt = new AtomicReference<>(Instant.MIN);

    /**
     * Tumbling-window counters fully reset on every {@link #checkCircuitBreaker}
     * tick (the 10s tick reads + zeros both counters via {@code getAndSet(0)}).
     * Cheap O(1) tracking; not a true sliding window (no overlap, no per-sample
     * timestamp, no decay) - adequate for "is the conflict rate alarmingly high
     * in the most recent 10s bucket".
     */
    private final AtomicLong successInWindow = new AtomicLong(0);
    private final AtomicLong conflictInWindow = new AtomicLong(0);
    private final AtomicReference<Instant> windowStart = new AtomicReference<>(Instant.now());

    private final Counter pubsubKillCounter;
    private final Counter circuitTripCounter;
    private MessageListener registeredListener;  // captured for @PreDestroy cleanup

    public CasKillSwitch(MeterRegistry meterRegistry) {
        this.pubsubKillCounter = Counter.builder("orchestrator.cas_kill_switch.pubsub_kill_count")
                .description("Plan v4 §1.11 - global pub/sub kill-switch triggered (operator action)")
                .register(meterRegistry);
        this.circuitTripCounter = Counter.builder("orchestrator.cas_kill_switch.circuit_trip_count")
                .description("Plan v4 §1.11 - local fast circuit breaker tripped (>50% conflict rate over 10s)")
                .register(meterRegistry);
        // Audit S5 - windowStart-age gauge. Detects stuck @Scheduled tick: if
        // the value climbs above ~12s sustained, the circuit-breaker check
        // isn't running, which means the circuit can't trip even under abuse.
        Gauge.builder("orchestrator.cas_kill_switch.window_age_seconds", windowStart,
                        ref -> java.time.Duration.between(ref.get(), java.time.Instant.now()).toSeconds())
                .description("Plan v4 audit S5 - age in seconds of the current circuit-breaker window. "
                        + "Healthy: 0-10s. Alert when sustained > 12s (indicates @Scheduled tick stuck).")
                .register(meterRegistry);
    }

    @PreDestroy
    void unsubscribe() {
        if (listenerContainer != null && registeredListener != null) {
            try {
                listenerContainer.removeMessageListener(registeredListener);
            } catch (RuntimeException ex) {
                log.debug("[CasKillSwitch] listener removal threw at shutdown (ignorable): {}", ex.getMessage());
            }
        }
    }

    @PostConstruct
    void subscribe() {
        if (listenerContainer == null) {
            log.info("[CasKillSwitch] RedisMessageListenerContainer not wired (narrow test context) - pub/sub kill-switch disabled");
            return;
        }
        MessageListener listener = (message, pattern) -> {
            String body = message.getBody() == null ? "" : new String(message.getBody());
            log.warn("[CasKillSwitch] received pub/sub kill signal: '{}' - disabling CAS cluster-wide for {}",
                    body, PUBSUB_DISABLE_DURATION);
            pubsubKilled.set(true);
            pubsubKilledAt.set(Instant.now());
            pubsubKillCounter.increment();
        };
        listenerContainer.addMessageListener(listener, new PatternTopic(CHANNEL));
        this.registeredListener = listener;  // captured for @PreDestroy cleanup (audit S2)
        log.info("[CasKillSwitch] subscribed to Redis channel '{}' for global kill signal", CHANNEL);
    }

    /**
     * The dispatcher consults this method before attempting CAS. Returns
     * {@code true} when EITHER the pub/sub kill-switch is active OR the
     * local circuit breaker has tripped. In that case, the dispatcher
     * skips the CAS path and falls through to the pessimistic-lock path
     * directly.
     */
    public boolean isCasDisabled() {
        return isPubsubKilled() || isCircuitOpen();
    }

    boolean isPubsubKilled() {
        if (!pubsubKilled.get()) return false;
        Instant killedAt = pubsubKilledAt.get();
        if (Duration.between(killedAt, Instant.now()).compareTo(PUBSUB_DISABLE_DURATION) > 0) {
            // Auto-reset after the cooldown so operators can validate stability.
            if (pubsubKilled.compareAndSet(true, false)) {
                log.info("[CasKillSwitch] pub/sub kill-switch auto-reset after {}", PUBSUB_DISABLE_DURATION);
            }
            return false;
        }
        return true;
    }

    boolean isCircuitOpen() {
        if (!circuitOpen.get()) return false;
        Instant openedAt = circuitOpenedAt.get();
        if (Duration.between(openedAt, Instant.now()).compareTo(CIRCUIT_COOLDOWN) > 0) {
            if (circuitOpen.compareAndSet(true, false)) {
                log.info("[CasKillSwitch] local circuit breaker reset after {} cooldown", CIRCUIT_COOLDOWN);
            }
            return false;
        }
        return true;
    }

    /** Called by the dispatcher on each CAS attempt that committed (rowCount=1). */
    public void recordCasSuccess() {
        successInWindow.incrementAndGet();
    }

    /** Called by the dispatcher on each CAS attempt that lost (rowCount=0). */
    public void recordCasConflict() {
        conflictInWindow.incrementAndGet();
    }

    /**
     * Check the sliding window every {@link #WINDOW} period. If
     * conflict-rate &gt; threshold over ≥ {@link #MIN_SAMPLES} samples,
     * trip the local circuit breaker. Reset counters for the next window.
     */
    @Scheduled(fixedDelay = 10_000)
    void checkCircuitBreaker() {
        long success = successInWindow.getAndSet(0);
        long conflict = conflictInWindow.getAndSet(0);
        windowStart.set(Instant.now());
        long total = success + conflict;
        if (total < MIN_SAMPLES) {
            return;  // not enough samples to make a statistically meaningful decision
        }
        double conflictRate = (double) conflict / total;
        if (conflictRate > CONFLICT_RATE_THRESHOLD) {
            if (circuitOpen.compareAndSet(false, true)) {
                circuitOpenedAt.set(Instant.now());
                circuitTripCounter.increment();
                log.error("[CasKillSwitch] LOCAL CIRCUIT BREAKER TRIPPED - conflict rate {} > {} over {} samples. "
                                + "CAS disabled for {} on this replica.",
                        String.format("%.2f%%", conflictRate * 100),
                        String.format("%.0f%%", CONFLICT_RATE_THRESHOLD * 100),
                        total, CIRCUIT_COOLDOWN);
            }
        }
    }

    /** Test hook - force pub/sub kill state. */
    void __testForcePubsubKill(boolean killed) {
        pubsubKilled.set(killed);
        pubsubKilledAt.set(Instant.now());
    }

    /** Test hook - force circuit state. */
    void __testForceCircuitOpen(boolean open) {
        circuitOpen.set(open);
        circuitOpenedAt.set(Instant.now());
    }
}
