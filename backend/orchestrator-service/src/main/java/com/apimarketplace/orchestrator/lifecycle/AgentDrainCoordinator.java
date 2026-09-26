package com.apimarketplace.orchestrator.lifecycle;

import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.execution.v2.async.PendingAgentRegistry;
import com.apimarketplace.orchestrator.execution.v2.async.RedisInFlightStore;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.trigger.queue.ExecutionQueue;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
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
 * (which causes {@code ScheduleExecutorService} to skip ticks, the
 * {@link OrchestratorLifecycleGateFilter} to return 503 on new starts, and the Redis
 * execution-queue workers of this instance to stop dequeuing), then waits up to
 * {@code orchestrator.lifecycle.drain-timeout} for the work THIS instance owns to reach
 * zero for three consecutive observations (steady-state):
 *
 * <ul>
 *   <li>{@link PendingAgentRegistry#size()} - async agents this instance dispatched and is
 *       waiting on (the registry map is per-JVM)</li>
 *   <li>{@link RedisInFlightStore#localSize()} - agent results this instance consumed and is
 *       still delivering</li>
 *   <li>{@link ExecutionQueue#getLocalActiveExecutions()} - trigger executions running on
 *       this instance's queue workers, counted from before a worker checks the drain gate</li>
 *   <li>{@link LocalRunExecutionTracker#activeCount()} - runs started on the ForkJoin common
 *       pool ({@code POST /execute}, {@code POST /{workflowId}/runs/{runId}/start}), which
 *       nothing else awaits at shutdown</li>
 * </ul>
 *
 * <p>Work that is NOT counted here is awaited elsewhere: a Spring {@code ThreadPoolTaskExecutor}
 * bean (signal resume, step-by-step) holds its own lifecycle stop until its running tasks end,
 * and a request thread is covered by Tomcat's graceful shutdown.
 *
 * <h2>Why only local work</h2>
 *
 * <p>The first version also waited on {@code countByStatus(RUNNING)} and
 * {@link RedisInFlightStore#size()}. Both are CLUSTER-wide: the first counts every replica's
 * runs plus any RUNNING row left stuck by an old crash, the second scans a keyspace shared by
 * every replica that still holds the entries a dead replica left for replay. Neither can be
 * brought to zero by this instance finishing its work, so on a multi-replica deployment (or
 * with a single stuck row) every rolling restart waited the full drain timeout and then
 * logged a TIMEOUT ERROR with {@code in_flight_agents=0}. Another replica's work finishes on
 * that replica, and an in-flight entry left by a dead one is replayed by
 * {@code AgentRecoveryService.replayInFlightEntries} on the next startup whether or not this
 * instance waits. The RUNNING count did protect one thing, by accident: a run this instance
 * was executing on the common pool kept that count above zero. That run is now counted
 * explicitly by {@link LocalRunExecutionTracker}. The cluster figures are still printed on
 * timeout, as context for ops.
 *
 * <p>On timeout with local work still pending, emits a structured ERROR log with the
 * unfinished work so ops can correlate the orphan with the next instance's startup-recovery
 * log line.
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
    private final ObjectProvider<ExecutionQueue> executionQueue;
    private final LocalRunExecutionTracker runTracker;
    private final Duration drainTimeout;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean drained = new AtomicBoolean(false);

    public AgentDrainCoordinator(
            OrchestratorLifecycleGate gate,
            PendingAgentRegistry registry,
            WorkflowRunRepository runRepository,
            @Autowired(required = false) RedisInFlightStore inFlightStore,
            ObjectProvider<ExecutionQueue> executionQueue,
            LocalRunExecutionTracker runTracker,
            @Value("${orchestrator.lifecycle.drain-timeout:PT60S}") Duration drainTimeout,
            Clock clock) {
        this.gate = gate;
        this.registry = registry;
        this.runRepository = runRepository;
        this.inFlightStore = inFlightStore;
        // Resolved lazily at drain time, so this bean does not change the creation (and hence
        // destruction) order of the queue.
        this.executionQueue = executionQueue;
        this.runTracker = runTracker;
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
            LocalWork work = observeLocalWork();
            if (work.isIdle()) {
                if (++idleObservations >= 3) {
                    logger.info("[Drain] Steady state reached after {} ms - exiting cleanly",
                        clock.instant().toEpochMilli() - started.toEpochMilli());
                    return;
                }
            } else {
                idleObservations = 0;
                logger.info("[Drain] Waiting - in_flight_agents={}, in_flight_staged={}, local_executions={}, in_process_runs={}, elapsed={} ms, deadline_in={} ms",
                    work.pendingAgents(), work.stagedDeliveries(), work.executions(), work.inProcessRuns(),
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

    /** Work owned by THIS instance. Every figure is a local read: no Redis, no database. */
    record LocalWork(int pendingAgents, int stagedDeliveries, int executions, int inProcessRuns) {
        boolean isIdle() {
            return pendingAgents == 0 && stagedDeliveries == 0 && executions == 0 && inProcessRuns == 0;
        }
    }

    LocalWork observeLocalWork() {
        int staged = inFlightStore != null ? inFlightStore.localSize() : 0;
        ExecutionQueue queue = executionQueue != null ? executionQueue.getIfAvailable() : null;
        int executions = queue != null ? queue.getLocalActiveExecutions() : 0;
        int inProcessRuns = runTracker != null ? runTracker.activeCount() : 0;
        return new LocalWork(registry.size(), staged, executions, inProcessRuns);
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
        LocalWork work = observeLocalWork();
        long elapsed = clock.instant().toEpochMilli() - started.toEpochMilli();
        if (work.isIdle()) {
            // The last local item finished between the final observation and the deadline:
            // nothing of ours is left, so this is not an orphan and not an ERROR.
            logger.info("[Drain] Local work finished at the deadline after {} ms - exiting cleanly", elapsed);
            return;
        }
        // Cluster-wide figures: context for ops only, never a reason to wait (see class javadoc).
        long clusterRunning = safeCountRunning();
        int clusterStaged = safeClusterStagedSize();
        logger.error("[Drain] TIMEOUT after {} ms - in_flight_agents={}, in_flight_staged={}, local_executions={}, in_process_runs={}, cluster_RUNNING_runs={}, cluster_in_flight_store_size={}, drain_timeout={} ms. Orphans should be replayed on the next instance's startup-recovery cycle (AgentRecoveryService.replayInFlightEntries).",
            elapsed, work.pendingAgents(), work.stagedDeliveries(), work.executions(), work.inProcessRuns(),
            clusterRunning, clusterStaged, drainTimeout.toMillis());
    }

    private int safeClusterStagedSize() {
        if (inFlightStore == null) return -1;
        try {
            return inFlightStore.size();
        } catch (Exception e) {
            logger.warn("[Drain] in-flight store size failed: {}", e.getMessage());
            return -1;
        }
    }
}
