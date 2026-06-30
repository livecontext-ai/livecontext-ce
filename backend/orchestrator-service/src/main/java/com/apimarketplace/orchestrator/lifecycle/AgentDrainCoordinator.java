package com.apimarketplace.orchestrator.lifecycle;

import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.execution.v2.async.PendingAgentRegistry;
import com.apimarketplace.orchestrator.execution.v2.async.RedisInFlightStore;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @PreDestroy drain loop. Flips the {@link OrchestratorLifecycleGate} to {@code DRAINING}
 * (which causes {@code ScheduleExecutorService} to skip ticks + the
 * {@link OrchestratorLifecycleGateFilter} to return 503 on new starts), then waits up to
 * {@code orchestrator.lifecycle.drain-timeout} (default 60 s) for two conditions to hold
 * for three consecutive observations (steady-state):
 *
 * <ul>
 *   <li>{@link PendingAgentRegistry#size()} == 0 - no async agents in flight</li>
 *   <li>{@link WorkflowRunRepository#countByStatus}{@code (RUNNING)} == 0 - no run is
 *       actively executing</li>
 * </ul>
 *
 * <p>On timeout, emits a structured ERROR log with the unfinished work so ops can
 * correlate the orphan with the next instance's startup-recovery log line.
 *
 * <h2>Ordering</h2>
 *
 * <p>Spring destroys beans in REVERSE creation order. {@code @DependsOn} chain:
 * <pre>
 *   OrchestratorLifecycleGate (creates first, destroys LAST)
 *      ↑
 *   AgentDrainCoordinator (creates depending on gate, destroys BEFORE gate)
 * </pre>
 *
 * <p>So at @PreDestroy time on this coordinator: gate is still alive (we can call
 * {@link OrchestratorLifecycleGate#enterDraining()}), and the gate's own @PreDestroy
 * runs AFTER ours.
 */
@Component
@DependsOn("orchestratorLifecycleGate")
public class AgentDrainCoordinator implements SmartLifecycle {

    private static final Logger logger = LoggerFactory.getLogger(AgentDrainCoordinator.class);

    /**
     * Phase ceiling - higher than the default of
     * {@code Integer.MAX_VALUE - 100} used by {@code RedisMessageListenerContainer} so we
     * stop FIRST in the {@code SmartLifecycle.stop()} cycle. Without this, the listener
     * container stops before our drain begins → {@code registry.size()} cannot decrement
     * because new pub/sub results from the worker can no longer reach {@code onAgentResult}.
     *
     * <p>Post-audit-fix (2026-05-23): the prior version used only {@code @PreDestroy}
     * which runs AFTER all SmartLifecycle beans have stopped - same shape as the prior
     * silent-drain bug. The {@code @PreDestroy} below is kept as a belt-and-braces
     * fallback for non-Spring test contexts where stop() is never called.
     */
    static final int STOP_PHASE = Integer.MAX_VALUE;

    private final OrchestratorLifecycleGate gate;
    private final PendingAgentRegistry registry;
    private final WorkflowRunRepository runRepository;
    private final RedisInFlightStore inFlightStore;
    private final Duration drainTimeout;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean drained = new AtomicBoolean(false);

    public AgentDrainCoordinator(
            OrchestratorLifecycleGate gate,
            PendingAgentRegistry registry,
            WorkflowRunRepository runRepository,
            @Autowired(required = false) RedisInFlightStore inFlightStore,
            @Value("${orchestrator.lifecycle.drain-timeout:PT60S}") Duration drainTimeout,
            Clock clock) {
        this.gate = gate;
        this.registry = registry;
        this.runRepository = runRepository;
        this.inFlightStore = inFlightStore;
        this.drainTimeout = drainTimeout;
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    // ─── SmartLifecycle implementation ──────────────────────────────────────────────────
    // Spring stops SmartLifecycle beans in reverse phase order during context close.
    // STOP_PHASE = Integer.MAX_VALUE places us at the very top - stops FIRST, before
    // RedisMessageListenerContainer (default phase Integer.MAX_VALUE - 100). This means
    // the pub/sub listener is still alive when we run the drain loop, so
    // AgentResultMessage delivery from the worker can still decrement registry.size().

    @Override
    public int getPhase() {
        return STOP_PHASE;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public void start() {
        running.set(true);
    }

    @Override
    public void stop(Runnable callback) {
        try {
            drainAndAwait();
        } finally {
            running.set(false);
            callback.run();
        }
    }

    @Override
    public void stop() {
        try {
            drainAndAwait();
        } finally {
            running.set(false);
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    // ─── Drain loop (callable from both SmartLifecycle.stop and @PreDestroy fallback) ──

    /**
     * Belt-and-braces {@code @PreDestroy} fallback. Spring runs {@code @PreDestroy} after
     * the SmartLifecycle stop phase, so if {@code stop()} was already invoked the
     * {@code drained} flag short-circuits this call. Kept for non-Spring test contexts
     * where stop() is never called.
     */
    @PreDestroy
    void preDestroyFallback() {
        if (!drained.get()) {
            drainAndAwait();
        }
    }

    void drainAndAwait() {
        if (!drained.compareAndSet(false, true)) {
            return; // already drained
        }
        gate.enterDraining();

        Instant started = clock.instant();
        Instant deadline = started.plus(drainTimeout);
        long sleepMs = 200L;
        int idleObservations = 0;

        while (clock.instant().isBefore(deadline)) {
            int inFlight = registry.size();
            int staged = inFlightStore != null ? inFlightStore.size() : 0;
            long running = safeCountRunning();
            if (inFlight == 0 && staged == 0 && running == 0) {
                if (++idleObservations >= 3) {
                    logger.info("[Drain] Steady state reached after {} ms - exiting cleanly",
                        clock.instant().toEpochMilli() - started.toEpochMilli());
                    return;
                }
            } else {
                idleObservations = 0;
                logger.info("[Drain] Waiting - in_flight_agents={}, in_flight_staged={}, RUNNING runs={}, elapsed={} ms, deadline_in={} ms",
                    inFlight, staged, running,
                    clock.instant().toEpochMilli() - started.toEpochMilli(),
                    deadline.toEpochMilli() - clock.instant().toEpochMilli());
            }
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                // Log unfinished work BEFORE returning so ops can still correlate the
                // interruption with the next instance's startup recovery (prior version
                // just returned silently on SIGTERM escalation).
                logger.warn("[Drain] Interrupted - emitting unfinished-work snapshot before exit");
                logUnfinishedWork(started);
                return;
            }
            sleepMs = Math.min(sleepMs * 2L, 2000L);
        }

        // Timeout: log unfinished work in a structured single line so ops can grep for it
        // and correlate with the next instance's startup-recovery log.
        logUnfinishedWork(started);
    }

    private long safeCountRunning() {
        try {
            return runRepository.countByStatus(RunStatus.RUNNING);
        } catch (Exception e) {
            // Hikari may already be tearing down. Fail open - let the drain exit on timeout
            // rather than throwing from the PreDestroy chain.
            logger.warn("[Drain] countByStatus(RUNNING) failed: {}", e.getMessage());
            return 0L;
        }
    }

    private void logUnfinishedWork(Instant started) {
        int inFlight = registry.size();
        long running = safeCountRunning();
        int inFlightStaged = inFlightStore != null ? inFlightStore.size() : -1;
        long elapsed = clock.instant().toEpochMilli() - started.toEpochMilli();
        logger.error("[Drain] TIMEOUT after {} ms - in_flight_agents={}, RUNNING_runs={}, in_flight_store_size={}, drain_timeout={} ms. Orphans should be replayed on the next instance's startup-recovery cycle (AgentRecoveryService.replayInFlightEntries).",
            elapsed, inFlight, running, inFlightStaged, drainTimeout.toMillis());
    }
}
