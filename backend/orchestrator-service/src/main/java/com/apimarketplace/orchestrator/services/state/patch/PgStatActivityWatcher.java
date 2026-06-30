package com.apimarketplace.orchestrator.services.state.patch;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Plan v4 §1.11 - pg_stat_activity watcher.
 *
 * <p>Polls Postgres {@code pg_stat_activity} every 30s for the p99 row-lock
 * wait time on workflow_runs UPDATE statements. When sustained &gt; 500ms
 * for 1 minute → PUBLISH on Redis channel
 * {@code feature_flags:cas-state-snapshot:flip} to trigger the cluster-wide
 * pub/sub kill-switch in {@link CasKillSwitch}.
 *
 * <p>Complements the local fast circuit breaker (CasKillSwitch's internal
 * 10s sliding-window): the local breaker catches CAS retry-conflict storms;
 * this watcher catches the symmetric failure mode where row-lock waits are
 * long but CAS itself isn't conflicting (e.g., pessimistic-path holders are
 * slow). Both are needed for full coverage.
 *
 * <p>Skipped if no JDBC template + Redis present (narrow test contexts).
 * The watcher is read-only - failure modes (DB blip, Redis blip) just
 * leave the breaker unchanged.
 */
@Component
public class PgStatActivityWatcher {

    private static final Logger log = LoggerFactory.getLogger(PgStatActivityWatcher.class);

    /** Plan §1.11 - 30s poll period, 1min sustained, 500ms threshold. */
    static final int POLL_INTERVAL_MS = 30_000;
    static final long LOCK_WAIT_THRESHOLD_MS = 500;
    static final int SUSTAINED_TICKS = 2;  // 2 × 30s = 1 min

    /**
     * Approximate "p99 row-lock wait" via the {@code wait_event_type='Lock'}
     * sessions for UPDATE on workflow_runs. Postgres doesn't expose true
     * p99 in pg_stat_activity (it's an instantaneous view), so we compute
     * the max age across all currently-waiting sessions. Under sustained
     * contention, the longest-waiting session approximates the p99.
     */
    /**
     * Audit B S2 / D IG4 - tighten the query to avoid self-induced false
     * positives. The legacy pessimistic-fallback path also issues UPDATE on
     * workflow_runs and holds a row lock; a single slow legitimate write
     * (multi-MB state_snapshot) for >500ms over 2 ticks (60s) would
     * incorrectly fire the kill-switch. Exclude:
     *   - This connection's own sessions (pid = pg_backend_pid())
     *   - Idle-in-transaction sessions (not actually waiting on a lock)
     * Restrict to: real Lock wait + state='active' + state_change-based
     * wait duration. The CAS-vs-pessimistic SQL signature distinction is
     * left for a future refinement once we have stable production data.
     */
    private static final String LOCK_WAIT_QUERY = """
            SELECT COALESCE(MAX(EXTRACT(EPOCH FROM (NOW() - state_change)) * 1000), 0)::bigint AS max_wait_ms
            FROM pg_stat_activity
            WHERE wait_event_type = 'Lock'
              AND state = 'active'
              AND pid != pg_backend_pid()
              AND query ILIKE '%UPDATE%workflow_runs%state_snapshot%'
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final boolean enabled;

    private final AtomicInteger sustainedTicks = new AtomicInteger(0);
    private final AtomicLong lastObservedWaitMs = new AtomicLong(0);
    private final AtomicReference<Instant> lastFlipAt = new AtomicReference<>(Instant.MIN);

    private final Counter pollOkCounter;
    private final Counter pollErrorCounter;
    private final Counter flipPublishedCounter;

    public PgStatActivityWatcher(
            NamedParameterJdbcTemplate jdbc,
            StringRedisTemplate redis,
            MeterRegistry meterRegistry,
            @Value("${orchestrator.optim.pg-stat-watcher:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.enabled = enabled;
        this.pollOkCounter = Counter.builder("orchestrator.pg_stat_watcher.poll_ok_count")
                .description("Plan v4 §1.11 - successful pg_stat_activity polls")
                .register(meterRegistry);
        this.pollErrorCounter = Counter.builder("orchestrator.pg_stat_watcher.poll_error_count")
                .description("Plan v4 §1.11 - pg_stat_activity poll failures (DB unreachable, perm denied, etc.)")
                .register(meterRegistry);
        this.flipPublishedCounter = Counter.builder("orchestrator.pg_stat_watcher.flip_published_count")
                .description("Plan v4 §1.11 - pub/sub kill-switch flips published (sustained lock-wait > 500ms)")
                .register(meterRegistry);
        Gauge.builder("orchestrator.pg_stat_watcher.last_observed_wait_ms", lastObservedWaitMs,
                        AtomicLong::get)
                .description("Plan v4 §1.11 - last observed max row-lock wait (ms) on workflow_runs UPDATEs")
                .register(meterRegistry);
        log.info("[PgStatActivityWatcher] watcher {} (threshold={}ms, sustainedTicks={}, pollInterval={}ms)",
                enabled ? "ENABLED" : "DISABLED",
                LOCK_WAIT_THRESHOLD_MS, SUSTAINED_TICKS, POLL_INTERVAL_MS);
    }

    @Scheduled(fixedDelay = POLL_INTERVAL_MS)
    public void pollLockWaits() {
        if (!enabled) return;
        long maxWaitMs;
        try {
            Long result = jdbc.queryForObject(LOCK_WAIT_QUERY, new MapSqlParameterSource(), Long.class);
            maxWaitMs = result == null ? 0L : result;
            pollOkCounter.increment();
        } catch (DataAccessException ex) {
            pollErrorCounter.increment();
            log.debug("[PgStatActivityWatcher] pg_stat_activity poll failed: {} (next tick in {}ms)",
                    ex.getMessage(), POLL_INTERVAL_MS);
            return;
        }
        lastObservedWaitMs.set(maxWaitMs);

        if (maxWaitMs > LOCK_WAIT_THRESHOLD_MS) {
            int ticks = sustainedTicks.incrementAndGet();
            if (ticks >= SUSTAINED_TICKS) {
                publishKillSwitch(maxWaitMs);
                sustainedTicks.set(0);  // reset so we don't spam-publish every tick
            } else {
                log.warn("[PgStatActivityWatcher] lock-wait spike observed: maxWaitMs={} (tick {}/{} before flip)",
                        maxWaitMs, ticks, SUSTAINED_TICKS);
            }
        } else {
            // Under-threshold tick resets the counter - must be sustained over consecutive ticks
            sustainedTicks.set(0);
        }
    }

    private void publishKillSwitch(long maxWaitMs) {
        Instant lastFlip = lastFlipAt.get();
        Instant now = Instant.now();
        // Anti-spam: don't re-publish within 5min of the previous publish
        if (lastFlip != Instant.MIN && now.isBefore(lastFlip.plus(java.time.Duration.ofMinutes(5)))) {
            log.debug("[PgStatActivityWatcher] kill-switch already published recently - skipping");
            return;
        }
        try {
            String message = String.format("pg_stat_watcher: sustained lock-wait %dms > %dms threshold",
                    maxWaitMs, LOCK_WAIT_THRESHOLD_MS);
            redis.convertAndSend(CasKillSwitch.CHANNEL, message);
            flipPublishedCounter.increment();
            lastFlipAt.set(now);
            log.error("[PgStatActivityWatcher] PUBLISHED kill-switch - sustained lock-wait {}ms > {}ms",
                    maxWaitMs, LOCK_WAIT_THRESHOLD_MS);
        } catch (RuntimeException ex) {
            log.warn("[PgStatActivityWatcher] failed to publish kill-switch (Redis down?): {}",
                    ex.getMessage());
        }
    }

    /** Test hook - current sustained tick count. */
    int __testGetSustainedTicks() {
        return sustainedTicks.get();
    }

    /** Test hook - last observed lock-wait. */
    long __testGetLastObservedWaitMs() {
        return lastObservedWaitMs.get();
    }
}
