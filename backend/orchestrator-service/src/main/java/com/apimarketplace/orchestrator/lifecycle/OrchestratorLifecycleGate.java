package com.apimarketplace.orchestrator.lifecycle;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Per-instance lifecycle gate with three states: {@link State#WARMING},
 * {@link State#READY}, {@link State#DRAINING}.
 *
 * <h2>Why this exists</h2>
 *
 * <p>Prod incident 2026-05-22 21:01 UTC:
 * <ol>
 *   <li>Orchestrator JVM hit OOM at 21:01:08, exited via {@code +ExitOnOutOfMemoryError}.</li>
 *   <li>systemd restarted at 21:01:19. New JVM up by 21:01:38.</li>
 *   <li>At 21:06:08, {@code OrchestrationRecoveryService.recoverZombieRuns} fired and
 *       marked the in-flight run FAILED - last activity (21:00:55) was &gt;5 min ago and
 *       the zombie scanner had no awareness that the JVM had just restarted.</li>
 * </ol>
 *
 * <p>This gate provides a {@code WARMING} window after startup during which the zombie
 * scanner returns early and {@link com.apimarketplace.orchestrator.schedule.ScheduleExecutorService}
 * skips the current tick. Long enough to let
 * {@link com.apimarketplace.orchestrator.execution.v2.async.AgentRecoveryService#recoverOnStartup}
 * replay any in-flight ack records and bump {@code workflow_runs.updated_at} so the run
 * no longer looks like a zombie by the time the gate exits.
 *
 * <p>The {@code DRAINING} state is set on {@code @PreDestroy} so a planned restart
 * refuses new schedule fires + new manual workflow starts via the
 * {@link OrchestratorLifecycleGateFilter}, while the
 * {@link AgentDrainCoordinator} waits for in-flight async work to settle.
 *
 * <h2>Storage</h2>
 *
 * <p>Local read path uses a {@code volatile State} for cheap hot-path checks (no Redis
 * round-trip). Redis mirror at {@code lc:orch:lifecycle:state:{instanceId}} is for ops
 * probes + future multi-replica visibility (today the cluster is single-active via
 * ShedLock, but the LB needs per-instance state).
 */
// Explicit bean name so AgentDrainCoordinator's @DependsOn("orchestratorLifecycleGate")
// resolves under BOTH the default name generator (microservices) and the CE monolith's
// FullyQualifiedBeanNameGenerator (which would otherwise name this bean by FQN).
@Component("orchestratorLifecycleGate")
public class OrchestratorLifecycleGate {

    private static final Logger logger = LoggerFactory.getLogger(OrchestratorLifecycleGate.class);

    public enum State { WARMING, READY, DRAINING }

    private static final String KEY_STATE_PREFIX = "lc:orch:lifecycle:state:";
    private static final String KEY_WARM_UNTIL_PREFIX = "lc:orch:lifecycle:warm_until:";
    private static final String KEY_DRAIN_STARTED_PREFIX = "lc:orch:lifecycle:drain_started_at:";
    /** Mirror TTL - 10 min sliding, refreshed on each transition. */
    private static final Duration MIRROR_TTL = Duration.ofMinutes(10);

    private final StringRedisTemplate redisTemplate;
    private final String instanceId;
    private final Duration warmingDuration;
    private final Clock clock;

    private volatile State state = State.WARMING;
    private volatile Instant warmUntil;
    private volatile Instant drainStartedAt;

    public OrchestratorLifecycleGate(
            @Autowired(required = false) StringRedisTemplate redisTemplate,
            @Value("${scaling.instance-id:}") String configuredId,
            @Value("${orchestrator.lifecycle.warming-duration:PT60S}") Duration warmingDuration,
            Clock clock) {
        this.redisTemplate = redisTemplate;
        this.instanceId = (configuredId == null || configuredId.isBlank())
            ? "orch-" + Integer.toHexString(System.identityHashCode(this))
            : configuredId;
        this.warmingDuration = warmingDuration;
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    @PostConstruct
    void enterWarming() {
        Instant until = clock.instant().plus(warmingDuration);
        this.state = State.WARMING;
        this.warmUntil = until;
        publish(State.WARMING, "warm_until", until.toEpochMilli());
        logger.info("[LifecycleGate] WARMING for {} (instance={}), warm_until={}",
            warmingDuration, instanceId, until);
    }

    /**
     * Flip to DRAINING. Called explicitly by {@link AgentDrainCoordinator} via its
     * {@code @PreDestroy} chain - not via @PreDestroy on this bean directly, so the
     * destroy ordering (gate first, drain coordinator second) is enforced by Spring
     * {@code @DependsOn} rather than by registration order.
     */
    public synchronized void enterDraining() {
        if (state == State.DRAINING) return;
        Instant now = clock.instant();
        this.state = State.DRAINING;
        this.drainStartedAt = now;
        publish(State.DRAINING, "drain_started_at", now.toEpochMilli());
        logger.warn("[LifecycleGate] DRAINING (instance={}), drain_started_at={}", instanceId, now);
    }

    /**
     * Internal helper for the scheduled tick that checks if the warming window has expired.
     * Idempotent - calling after expiry is a no-op.
     */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 10_000)
    void tickExitWarmingIfExpired() {
        if (state != State.WARMING) return;
        Instant until = this.warmUntil;
        if (until != null && clock.instant().isAfter(until)) {
            synchronized (this) {
                if (state == State.WARMING) {
                    state = State.READY;
                    publish(State.READY, null, 0L);
                    logger.info("[LifecycleGate] WARMING → READY (instance={})", instanceId);
                }
            }
        }
    }

    /** True iff currently in the post-boot warming window. Cheap volatile read. */
    public boolean isWarming() {
        return state == State.WARMING;
    }

    /** True iff currently draining (a planned shutdown is in progress). Cheap volatile read. */
    public boolean isDraining() {
        return state == State.DRAINING;
    }

    /** True iff fully ready: neither warming nor draining. Cheap volatile read. */
    public boolean isReady() {
        return state == State.READY;
    }

    public State currentState() {
        return state;
    }

    public Optional<Instant> warmingUntil() {
        return Optional.ofNullable(warmUntil);
    }

    public Optional<Instant> drainStartedAt() {
        return Optional.ofNullable(drainStartedAt);
    }

    public String instanceId() {
        return instanceId;
    }

    /**
     * Mirror state + optional timestamp into Redis. Best-effort: a Redis hiccup is
     * logged and swallowed so transition local state still flips. The mirror is for
     * ops probes / future LB integration, not the load-bearing read path.
     */
    private void publish(State newState, String tsKeySuffix, long tsValue) {
        if (redisTemplate == null) return;
        try {
            String stateKey = KEY_STATE_PREFIX + instanceId;
            redisTemplate.opsForValue().set(stateKey, newState.name(), MIRROR_TTL.toMillis(), TimeUnit.MILLISECONDS);
            if (tsKeySuffix != null) {
                String tsKey;
                if ("warm_until".equals(tsKeySuffix)) tsKey = KEY_WARM_UNTIL_PREFIX + instanceId;
                else if ("drain_started_at".equals(tsKeySuffix)) tsKey = KEY_DRAIN_STARTED_PREFIX + instanceId;
                else return;
                redisTemplate.opsForValue().set(tsKey, String.valueOf(tsValue), MIRROR_TTL.toMillis(), TimeUnit.MILLISECONDS);
            }
        } catch (Exception e) {
            logger.warn("[LifecycleGate] Redis publish failed for state {} (instance={}): {}",
                newState, instanceId, e.getMessage());
        }
    }

    @PreDestroy
    void onShutdown() {
        // Last-chance: ensure the Redis mirror reflects DRAINING even if enterDraining
        // wasn't called (test contexts, abnormal teardown). No-op when already DRAINING.
        if (state != State.DRAINING) {
            enterDraining();
        }
    }
}
