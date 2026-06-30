package com.apimarketplace.orchestrator.heartbeat;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Plan v4 §10 - Postgres-backed instance heartbeat for signal-wait failover.
 *
 * <p>Maintains a row in {@code orchestrator.instance_lease} (V182) representing
 * this replica's liveness. The lease is bumped every 10s with a 30s expiry; if
 * the row goes stale (lease_until &lt; NOW), peer replicas may steal the
 * signal_waits this instance was holding via the atomic CTE in
 * {@link #stealStaleSignals()}. This collapses worst-case signal-recovery
 * latency from ~5min (legacy poll-on-RUNNING heuristic) to ~30s.
 *
 * <p><b>Bootstrap self-cleanup (audit C M4):</b> on startup, the UPSERT bumps
 * the {@code generation} counter; any signal_waits left claimed by this
 * {@code instance_id} at an OLDER generation are pre-crash orphans (kill -9 /
 * OOM-killer window before SIGTERM hook ran). The startup releases them with
 * a 5s retry_after cooldown so peers (or this same instance) can re-pick.
 *
 * <p><b>Circuit breaker:</b> 3 consecutive heartbeat statement timeouts →
 * {@link LeaseShutdownCoordinator#drainAndExit(int)} self-evicts (Spring
 * graceful shutdown + 5s drain + {@code System.exit(2)}). Systemd
 * {@code Restart=on-failure} respawns; bootstrap then completes the
 * generation-bump + orphan-cleanup cycle.
 *
 * <p><b>STALE_OWNERSHIP rejection:</b> when a resume sees
 * {@code claimed_generation != current_generation} for its claimed_by, it
 * calls {@link #releaseSignalAsStale(long, long)} which atomically NULLs
 * the claim ONLY IF still pointed at us at the rejected generation (anti
 * peer-clobber race - audit C M3).
 *
 * <p><b>Feature flag:</b> {@code orchestrator.optim.heartbeat-failover=false}
 * disables the service entirely (no bean wired). Default OFF until V182
 * has soaked in prod for 1 week + canary deploy validated.
 */
@Component
@ConditionalOnProperty(name = "orchestrator.optim.heartbeat-failover", havingValue = "true")
public class InstanceHeartbeatService {

    private static final Logger log = LoggerFactory.getLogger(InstanceHeartbeatService.class);

    /** Plan §10 - 10s heartbeat / 30s lease / 2s statement_timeout / 3 timeouts → self-evict / 5s drain. */
    static final int HEARTBEAT_INTERVAL_MS = 10_000;
    static final int LEASE_TTL_SECONDS = 30;
    static final int STATEMENT_TIMEOUT_MS = 2_000;
    static final int CIRCUIT_BREAKER_TIMEOUT_THRESHOLD = 3;
    static final int DRAIN_SECONDS = 5;
    /** Plan §10 - 10s steal cadence. With 30s lease, worst-case recovery is ~30s + 10s = 40s. */
    static final int STEAL_INTERVAL_MS = 10_000;
    static final int STEAL_BATCH_LIMIT = 100;

    private final NamedParameterJdbcTemplate jdbc;
    private final LeaseShutdownCoordinator shutdownCoordinator;
    private final Counter heartbeatOkCounter;
    private final Counter heartbeatTimeoutCounter;
    private final Counter stealOkCounter;
    private final Counter staleReleaseCounter;
    private final Counter orphanCleanupCounter;

    private final String instanceId;
    /** Audit S1 - volatile for cross-thread visibility between @Scheduled threads (heartbeat,
     * steal poller) and SignalResumeService.getCurrentGeneration() reader. */
    private volatile long currentGeneration;
    /** Audit S1 - volatile for visibility with circuit-breaker reads. */
    private volatile int consecutiveTimeouts;

    public InstanceHeartbeatService(
            NamedParameterJdbcTemplate jdbc,
            LeaseShutdownCoordinator shutdownCoordinator,
            MeterRegistry meterRegistry,
            @Value("${scaling.instance-id:}") String configuredId) {
        this.jdbc = jdbc;
        this.shutdownCoordinator = shutdownCoordinator;
        this.instanceId = configuredId.isBlank()
                ? "orch-" + UUID.randomUUID().toString().substring(0, 8)
                : configuredId;
        this.heartbeatOkCounter = Counter.builder("orchestrator.heartbeat.ok_count")
                .description("Plan v4 §10 - successful heartbeat UPDATEs on instance_lease")
                .register(meterRegistry);
        this.heartbeatTimeoutCounter = Counter.builder("orchestrator.heartbeat.timeout_count")
                .description("Plan v4 §10 - heartbeat statement timeouts (2s); 3-consecutive → self-evict")
                .register(meterRegistry);
        this.stealOkCounter = Counter.builder("orchestrator.heartbeat.steal_count")
                .description("Plan v4 §10 - orphan signals stolen from peer instances via CTE atomic steal")
                .register(meterRegistry);
        this.staleReleaseCounter = Counter.builder("orchestrator.heartbeat.stale_release_count")
                .description("Plan v4 §10 - signal_waits released via STALE_OWNERSHIP rejection")
                .register(meterRegistry);
        this.orphanCleanupCounter = Counter.builder("orchestrator.heartbeat.bootstrap_orphan_cleanup_count")
                .description("Plan v4 §10 - pre-crash orphan signals cleaned up at bootstrap self-cleanup")
                .register(meterRegistry);
    }

    @PostConstruct
    void init() {
        log.info("[InstanceHeartbeatService] initialized for instance_id={} (plan v4 §10)", instanceId);
    }

    /**
     * Bootstrap on Spring context refresh: UPSERT into instance_lease with
     * generation++ and self-cleanup of orphan claims pointing at us with
     * stale generation. Runs once per JVM start.
     */
    @EventListener(ContextRefreshedEvent.class)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void bootstrap() {
        // Audit B M3 - fail-OPEN on DB unreachable so Spring context refresh
        // never blocks JVM startup on a transient DB blip. Without this guard,
        // a sustained DB outage produces a tight respawn loop (systemd
        // Restart=on-failure → bootstrap throws → context fails → respawn).
        // The @Scheduled heartbeat already retries gracefully; bootstrap
        // failure just means we start with currentGeneration=0 (skip-on-zero
        // guard in scheduledStealStaleSignals prevents claim writes against
        // a stale generation).
        try {
            String upsertSql = """
                    INSERT INTO orchestrator.instance_lease (instance_id, lease_until, generation, last_id)
                    VALUES (:id, NOW() + (:ttlSec * INTERVAL '1 second'), 1, 0)
                    ON CONFLICT (instance_id) DO UPDATE
                    SET lease_until = NOW() + (:ttlSec * INTERVAL '1 second'),
                        generation  = orchestrator.instance_lease.generation + 1,
                        updated_at  = NOW()
                    RETURNING generation
                    """;
            Long newGen = jdbc.queryForObject(upsertSql,
                    new MapSqlParameterSource("id", instanceId).addValue("ttlSec", LEASE_TTL_SECONDS),
                    Long.class);
            this.currentGeneration = newGen == null ? 1 : newGen;

            // Self-cleanup: any signal_waits left claimed by us at an older
            // generation are pre-crash orphans (kill -9 / OOM-killer between
            // claim and SIGTERM hook). Release with 5s retry_after cooldown.
            String cleanupSql = """
                    UPDATE orchestrator.workflow_signal_waits
                    SET claimed_by = NULL,
                        retry_after = NOW() + INTERVAL '5 seconds'
                    WHERE claimed_by = :id AND claimed_generation < :newGen
                    """;
            int cleaned = jdbc.update(cleanupSql,
                    new MapSqlParameterSource("id", instanceId).addValue("newGen", currentGeneration));
            if (cleaned > 0) {
                orphanCleanupCounter.increment(cleaned);
                log.info("[InstanceHeartbeatService] bootstrap self-cleanup: released {} orphan signal_wait(s) "
                        + "for instance_id={} (newGeneration={})", cleaned, instanceId, currentGeneration);
            }
        } catch (org.springframework.dao.DataAccessException ex) {
            log.error("[InstanceHeartbeatService] bootstrap failed (DB unreachable?) - JVM starts in degraded state. "
                    + "Heartbeat will retry; orphan cleanup deferred. Error: {}",
                    ex.getMessage());
            // currentGeneration stays at default 0 → skip-on-zero guard in
            // scheduledStealStaleSignals prevents claims against stale gen.
        }
    }

    @Scheduled(fixedDelay = HEARTBEAT_INTERVAL_MS)
    @Transactional(timeout = 2)  // Spring sets statement_timeout = 2s for all statements in this tx (plan §10)
    public void heartbeat() {
        try {
            int rows = jdbc.update(
                    """
                            UPDATE orchestrator.instance_lease
                            SET lease_until = NOW() + (:ttlSec * INTERVAL '1 second'),
                                generation  = generation + 1,
                                updated_at  = NOW()
                            WHERE instance_id = :id
                            """,
                    new MapSqlParameterSource("id", instanceId)
                            .addValue("ttlSec", LEASE_TTL_SECONDS));
            if (rows == 0) {
                log.warn("[InstanceHeartbeatService] heartbeat hit 0 rows for instance_id={} "
                        + "- row may have been cleaned up (peer steal). Forcing bootstrap re-init.", instanceId);
                bootstrap();
            } else {
                heartbeatOkCounter.increment();
                consecutiveTimeouts = 0;
                currentGeneration += 1;  // mirror DB-side bump
            }
        } catch (QueryTimeoutException timeout) {
            heartbeatTimeoutCounter.increment();
            consecutiveTimeouts += 1;
            log.warn("[InstanceHeartbeatService] heartbeat timeout ({}/{}) for instance_id={}: {}",
                    consecutiveTimeouts, CIRCUIT_BREAKER_TIMEOUT_THRESHOLD, instanceId, timeout.getMessage());
            if (consecutiveTimeouts >= CIRCUIT_BREAKER_TIMEOUT_THRESHOLD) {
                log.error("[InstanceHeartbeatService] circuit breaker tripped - {} consecutive heartbeat timeouts. "
                        + "Initiating self-evict (drain {}s then exit(2)).", consecutiveTimeouts, DRAIN_SECONDS);
                shutdownCoordinator.drainAndExit(DRAIN_SECONDS);
            }
        } catch (RuntimeException ex) {
            // Don't trip circuit breaker on non-timeout errors (e.g. transient
            // network blip, deadlock). Just log and continue.
            log.warn("[InstanceHeartbeatService] heartbeat error for instance_id={}: {} (not counted toward circuit breaker)",
                    instanceId, ex.getMessage());
        }
    }

    /**
     * Plan v4 §10 wire-up (2026-05-11) - @Scheduled poller activates the
     * orphan-signal-recovery contract. Every replica polls every 10s;
     * {@code FOR UPDATE SKIP LOCKED} inside the CTE prevents multiple
     * replicas from stealing the same row. No ShedLock - plan §10
     * "Every replica polls" mirrors the keyset poller (#9) semantics.
     *
     * <p>Skips when {@code currentGeneration == 0} (bootstrap not yet
     * completed) so the steal SQL never runs against a zero generation
     * (which would write claimed_generation=0 and trigger immediate
     * STALE_OWNERSHIP rejection on the resume side).
     */
    @Scheduled(fixedDelay = STEAL_INTERVAL_MS)
    public void scheduledStealStaleSignals() {
        if (currentGeneration == 0) {
            return;
        }
        try {
            stealStaleSignals(STEAL_BATCH_LIMIT);
        } catch (RuntimeException ex) {
            // Don't propagate - let next tick try again. DB blips, deadlocks
            // (with SKIP LOCKED unlikely but defensive), pool exhaustion all
            // land here.
            log.warn("[InstanceHeartbeatService] scheduled steal failed: {} (next tick in {}ms)",
                    ex.getMessage(), STEAL_INTERVAL_MS);
        }
    }

    /**
     * Plan v4 §10 atomic-steal CTE. Picks up to {@code limit} signal_waits
     * whose claimed_by points at an instance whose lease has expired (or no
     * lease at all), respecting the {@code retry_after} cooldown stamped by
     * prior STALE_OWNERSHIP rejections.
     *
     * <p>Uses NOT EXISTS form (audit C M1) because LEFT JOIN with FOR UPDATE
     * on the nullable side is rejected by Postgres.
     *
     * @return number of stolen rows
     */
    @Transactional
    public int stealStaleSignals() {
        return stealStaleSignals(100);
    }

    /** Test seam - configurable limit. */
    @Transactional
    public int stealStaleSignals(int limit) {
        String sql = """
                WITH stale AS (
                  SELECT sw.id
                  FROM orchestrator.workflow_signal_waits sw
                  WHERE sw.claimed_by IS NOT NULL
                    AND NOT EXISTS (
                      SELECT 1 FROM orchestrator.instance_lease il
                      WHERE il.instance_id = sw.claimed_by
                        AND il.lease_until > NOW()
                    )
                    AND COALESCE(sw.retry_after, '1970-01-01'::timestamptz) <= NOW()
                  ORDER BY sw.id
                  LIMIT :limit
                  FOR UPDATE SKIP LOCKED
                ), my_gen AS (
                  SELECT generation FROM orchestrator.instance_lease WHERE instance_id = :myId
                )
                UPDATE orchestrator.workflow_signal_waits sw
                SET claimed_by = :myId,
                    claimed_generation = (SELECT generation FROM my_gen),
                    retry_after = NULL
                FROM stale
                WHERE sw.id = stale.id
                """;
        int stolen = jdbc.update(sql,
                new MapSqlParameterSource("myId", instanceId).addValue("limit", limit));
        if (stolen > 0) {
            stealOkCounter.increment(stolen);
            log.info("[InstanceHeartbeatService] stole {} stale signal_wait(s)", stolen);
        }
        return stolen;
    }

    /**
     * Plan v4 §10 STALE_OWNERSHIP rejection - anti peer-clobber race
     * (audit C M3). The WHERE clause filters on {@code claimed_by =
     * :myInstance AND claimed_generation = :rejectedGeneration} so we only
     * NULL the claim if it's STILL ours at the rejected generation. A
     * concurrent peer steal that updated claimed_by between our read and
     * our UPDATE leaves the row alone.
     *
     * @return true if the claim was released; false if a peer beat us to it
     */
    @Transactional
    public boolean releaseSignalAsStale(long signalWaitId, long rejectedGeneration) {
        String sql = """
                UPDATE orchestrator.workflow_signal_waits
                SET claimed_by = NULL,
                    retry_after = NOW() + INTERVAL '5 seconds'
                WHERE id = :id
                  AND claimed_by = :myId
                  AND claimed_generation = :rejectedGen
                """;
        int rows = jdbc.update(sql,
                new MapSqlParameterSource("id", signalWaitId)
                        .addValue("myId", instanceId)
                        .addValue("rejectedGen", rejectedGeneration));
        if (rows == 1) {
            staleReleaseCounter.increment();
            return true;
        }
        return false;
    }

    /**
     * Graceful shutdown - DELETE our row from instance_lease so peers
     * see it gone immediately instead of waiting 30s for the lease to
     * expire. Disabled in tests via the missing bean (the service is
     * @ConditionalOnProperty).
     */
    @PreDestroy
    public void shutdown() {
        try {
            int rows = jdbc.update(
                    "DELETE FROM orchestrator.instance_lease WHERE instance_id = :id",
                    new MapSqlParameterSource("id", instanceId));
            log.info("[InstanceHeartbeatService] graceful shutdown - deleted {} lease row(s) for instance_id={}",
                    rows, instanceId);
        } catch (RuntimeException ex) {
            log.warn("[InstanceHeartbeatService] shutdown DELETE failed (peer will reclaim after lease expiry): {}",
                    ex.getMessage());
        }
    }

    public String getInstanceId() {
        return instanceId;
    }

    public long getCurrentGeneration() {
        return currentGeneration;
    }
}
