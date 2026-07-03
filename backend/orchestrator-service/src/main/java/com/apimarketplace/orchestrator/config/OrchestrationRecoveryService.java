package com.apimarketplace.orchestrator.config;

import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.execution.StateSnapshot;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.async.PendingAgentRegistry;
import com.apimarketplace.orchestrator.execution.v2.services.UnifiedSignalService;
import com.apimarketplace.orchestrator.repository.SignalWaitRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.WorkflowStreamingService;
import com.apimarketplace.orchestrator.services.events.WorkflowRunTerminatedEvent;
import com.apimarketplace.orchestrator.services.state.StateSnapshotService;
import com.apimarketplace.orchestrator.services.streaming.redis.WorkflowRedisPublisher;
import com.apimarketplace.orchestrator.trigger.EpochConcurrencyLimiter;
import com.apimarketplace.orchestrator.trigger.ErrorTriggerDispatchService;
import jakarta.annotation.PostConstruct;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Periodic recovery service that detects and fails zombie workflow runs.
 *
 * <p>A "zombie" run is one stuck in {@link RunStatus#RUNNING} with no progress
 * (i.e., {@code updated_at} older than the zombie threshold derived from
 * {@code workflow.execution.max-execution-minutes + 2 min grace}). This typically
 * happens when an orchestrator instance crashes mid-execution and no other instance
 * picks up the orphaned run.
 *
 * <p>Runs every 30 seconds, protected by ShedLock so only one instance in the
 * cluster executes the scan. Only active when {@code scaling.backend=redis}.
 *
 * @see OrchestratorScalingConfig
 */
@Service
@ConditionalOnProperty(name = "scaling.backend", havingValue = "redis")
public class OrchestrationRecoveryService {

    private static final Logger logger = LoggerFactory.getLogger(OrchestrationRecoveryService.class);

    /** Fallback when no WorkflowExecutionConfig is available (tests). Mirrors max-execution-minutes (125) + 2 min grace. */
    private static final Duration DEFAULT_ZOMBIE_THRESHOLD = Duration.ofMinutes(127);

    private final WorkflowRunRepository runRepository;
    private final SignalWaitRepository signalWaitRepository;
    private final Clock clock;

    /**
     * Derived from {@code workflow.execution.max-execution-minutes} + 2-minute grace.
     * Initialized in {@link #initZombieThreshold()} after Spring wires all dependencies.
     */
    private Duration zombieThreshold = DEFAULT_ZOMBIE_THRESHOLD;

    // Optional collaborators - present when scaling.backend=redis (same condition as this
    // service), but kept @Autowired(required=false) so unit tests can construct the service
    // with a minimal dependency set and verify cleanup behaviour in isolation.
    @Autowired(required = false)
    private UnifiedSignalService unifiedSignalService;

    @Autowired(required = false)
    private WorkflowRedisPublisher workflowRedisPublisher;

    /**
     * Optional async-agent registry. Wired only when the agent queue is enabled
     * ({@code scaling.agent.queue.enabled=true}) - the registry bean is unconditional
     * but is only meaningfully populated when async dispatch is on. Marked optional so
     * the recovery service stays healthy in deployments where the queue is off.
     */
    @Autowired(required = false)
    private PendingAgentRegistry pendingAgentRegistry;

    @Autowired(required = false)
    private EpochConcurrencyLimiter epochConcurrencyLimiter;

    @Autowired(required = false)
    private WorkflowStreamingService streamingService;

    /**
     * Dispatches FAILED runs to user-configured error-handler workflows. Optional so
     * the recovery service still functions in tests / minimal deployments without an
     * error-trigger fan-out wired in.
     */
    @Autowired(required = false)
    private ErrorTriggerDispatchService errorTriggerDispatchService;

    @Autowired(required = false)
    private StateSnapshotService stateSnapshotService;

    @Autowired(required = false)
    private ApplicationEventPublisher eventPublisher;

    /**
     * Lifecycle gate - when the instance just restarted (WARMING window), the zombie
     * scan returns early to give {@link com.apimarketplace.orchestrator.execution.v2.async.AgentRecoveryService#recoverOnStartup}
     * time to replay in-flight ack records and bump {@code workflow_runs.updated_at}.
     *
     * <p>Directly addresses the 2026-05-22 21:06:08 UTC false-positive where this
     * scanner marked a legitimately-in-flight run FAILED 5 min after a JVM crash -
     * the in-flight ack records had been GETDEL'd pre-crash so the
     * {@code hasAnyPendingForRun} check returned false, but the actual work was
     * recoverable via the new {@code RedisInFlightStore} replay path.
     */
    @Autowired(required = false)
    private com.apimarketplace.orchestrator.lifecycle.OrchestratorLifecycleGate lifecycleGate;

    @Autowired(required = false)
    private WorkflowExecutionConfig executionConfig;

    public OrchestrationRecoveryService(WorkflowRunRepository runRepository,
                                         SignalWaitRepository signalWaitRepository,
                                         Clock clock) {
        this.runRepository = runRepository;
        this.signalWaitRepository = signalWaitRepository;
        this.clock = clock;
    }

    @PostConstruct
    void initZombieThreshold() {
        if (executionConfig != null && executionConfig.getMaxExecutionMinutes() > 0) {
            long gracePeriodMinutes = 2;
            zombieThreshold = Duration.ofMinutes(executionConfig.getMaxExecutionMinutes() + gracePeriodMinutes);
        }
        logger.info("[Recovery] Zombie threshold = {} min (max-execution-minutes={}, grace=2 min)",
                zombieThreshold.toMinutes(),
                executionConfig != null ? executionConfig.getMaxExecutionMinutes() : "N/A");
    }

    // Test-only setters - keep collaborators field-injected to avoid bloating the constructor
    // signature with optional dependencies. Unit tests inject mocks via these setters; in
    // production they come from Spring field injection.
    void setUnifiedSignalService(UnifiedSignalService unifiedSignalService) {
        this.unifiedSignalService = unifiedSignalService;
    }

    void setWorkflowRedisPublisher(WorkflowRedisPublisher workflowRedisPublisher) {
        this.workflowRedisPublisher = workflowRedisPublisher;
    }

    void setPendingAgentRegistry(PendingAgentRegistry pendingAgentRegistry) {
        this.pendingAgentRegistry = pendingAgentRegistry;
    }

    void setEpochConcurrencyLimiter(EpochConcurrencyLimiter epochConcurrencyLimiter) {
        this.epochConcurrencyLimiter = epochConcurrencyLimiter;
    }

    void setStreamingService(WorkflowStreamingService streamingService) {
        this.streamingService = streamingService;
    }

    void setErrorTriggerDispatchService(ErrorTriggerDispatchService errorTriggerDispatchService) {
        this.errorTriggerDispatchService = errorTriggerDispatchService;
    }

    void setStateSnapshotService(StateSnapshotService stateSnapshotService) {
        this.stateSnapshotService = stateSnapshotService;
    }

    void setEventPublisher(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    void setExecutionConfig(WorkflowExecutionConfig executionConfig) {
        this.executionConfig = executionConfig;
    }

    Duration getZombieThreshold() {
        return zombieThreshold;
    }

    /**
     * Scan for zombie RUNNING runs and mark them as FAILED.
     *
     * <p>A run qualifies as a zombie when:
     * <ol>
     *   <li>Status is {@link RunStatus#RUNNING}</li>
     *   <li>{@code updated_at} is older than 5 minutes ago</li>
     *   <li>No active blocking signals (USER_APPROVAL, WAIT_TIMER, WEBHOOK_WAIT, blocking
     *       INTERFACE_SIGNAL) are registered for the run</li>
     *   <li>No async agent execution is in flight in {@link PendingAgentRegistry}</li>
     * </ol>
     *
     * <p>The async-agent check is the post-horizontal-scaling counterpart of the legacy
     * {@code AGENT_EXECUTION} blocking signal: when {@code scaling.agent.queue.enabled=true}
     * the offloaded agent is tracked via {@code PendingAgentRegistry} (with Redis side-store
     * for restart safety) instead of {@code workflow_signal_waits}. Skipping it here is what
     * keeps a 10-minute LLM call from being mistaken for a zombie at the 5-minute mark.
     *
     * <p>ShedLock guarantees at-most-one execution across the cluster.
     * Lock held for max 25 seconds (well under the 30-second cycle).
     */
    @Scheduled(fixedDelay = 30_000)
    @SchedulerLock(name = "recovery_zombie_runs", lockAtMostFor = "PT25S")
    @Transactional
    public void recoverZombieRuns() {
        // Lifecycle gate (post-2026-05-22 21:06:08 UTC fix): the WARMING window after a
        // JVM restart is the exact case where this scanner false-positives. In-flight
        // ack records were GETDEL'd pre-crash, hasAnyPendingForRun returns false here,
        // and the run gets flipped to FAILED even though AgentRecoveryService.recoverOnStartup
        // is about to replay the orphan via the in-flight store. Skip the scan until the
        // gate exits WARMING (60 s default, well past the typical replay window).
        if (lifecycleGate != null && lifecycleGate.isWarming()) {
            logger.info("[Recovery] Skipping zombie scan - instance WARMING until {}",
                lifecycleGate.warmingUntil().orElse(null));
            return;
        }

        Instant cutoff = clock.instant().minus(zombieThreshold);

        List<WorkflowRunEntity> zombies = runRepository
                .findByStatusAndUpdatedAtBefore(RunStatus.RUNNING, cutoff);

        if (zombies.isEmpty()) {
            return;
        }

        logger.warn("[Recovery] Found {} candidate zombie RUNNING run(s) with no activity since {}",
                zombies.size(), cutoff);

        Instant now = clock.instant();
        int recovered = 0;
        for (WorkflowRunEntity run : zombies) {
            // Skip runs that are legitimately progressing or waiting:
            //   (1) blocking signals (USER_APPROVAL up to 24h, WAIT_TIMER up to days,
            //       WEBHOOK_WAIT, blocking INTERFACE_SIGNAL) - tracked in workflow_signal_waits
            //   (2) async agent in flight - tracked in PendingAgentRegistry since
            //       horizontal scaling moved AGENT_EXECUTION out of the signal table.
            //       LLM read timeout is 600s, so a single agent call can legitimately
            //       exceed the 5-minute zombie threshold without the run being dead.
            String runId = run.getRunIdPublic();
            try {
                if (signalWaitRepository.hasBlockingSignals(runId)) {
                    logger.debug("[Recovery] Skipping run {} - has active blocking signals", runId);
                    continue;
                }
            } catch (Exception e) {
                // On signal check failure, skip this run to avoid false zombie kills
                logger.warn("[Recovery] Signal check failed for run {}, skipping - {}", runId, e.getMessage());
                continue;
            }

            if (pendingAgentRegistry != null) {
                try {
                    if (pendingAgentRegistry.hasAnyPendingForRun(runId)) {
                        logger.debug("[Recovery] Skipping run {} - async agent execution in flight", runId);
                        continue;
                    }
                } catch (Exception e) {
                    // Same fail-safe policy as the signal check: a registry hiccup must never
                    // produce a false-positive zombie kill of a live run.
                    logger.warn("[Recovery] Pending-agent check failed for run {}, skipping - {}", runId, e.getMessage());
                    continue;
                }
            }

            Instant lastUpdated = run.getUpdatedAt();
            run.setStatus(RunStatus.FAILED);
            run.setEndedAt(now);
            run.setUpdatedAt(now);
            recovered++;
            logger.warn("[Recovery] Marking run {} (workflow={}) as FAILED - last activity at {}",
                    runId,
                    run.getWorkflow() != null ? run.getWorkflow().getId() : "unknown",
                    lastUpdated);
        }

        if (recovered > 0) {
            List<WorkflowRunEntity> failedRuns = zombies.stream()
                    .filter(r -> r.getStatus() == RunStatus.FAILED)
                    .toList();
            runRepository.saveAll(failedRuns);
            logger.info("[Recovery] Recovered {} zombie run(s)", recovered);

            // Side-effect cleanup + SSE broadcast for every run we just transitioned to
            // FAILED. Mirrors the pattern in WorkflowResumeService.stopWorkflow:
            //   - cancel signals (timers, approvals, awaiting-signal nodes)
            //   - set Redis agent-cancel flag (interrupts mid-stream LLM calls)
            //   - drop pending async agent entries (no late-arriving traversal)
            //   - release epoch concurrency permits
            //   - emit a workflowStatus SSE so the frontend leaves "RUNNING"
            // Each side-effect is isolated in its own try/catch so a single failure
            // (e.g., Redis down) doesn't block the rest of the cleanup or the next run.
            for (WorkflowRunEntity run : failedRuns) {
                cleanupAfterForceFail(run);
            }
        }
    }

    /**
     * Post-fail cleanup for a single zombie run. Each side-effect is independent and
     * isolated - one failure cannot block another. The DB-touching parts run inside
     * the enclosing {@code @Transactional} method; the Redis writes and the SSE
     * broadcast are deferred to {@code afterCommit} so a Redis hiccup cannot roll
     * back the FAILED status update, and so SSE subscribers reading the run row
     * always see the post-commit state (FAILED) instead of pre-commit (RUNNING).
     */
    private void cleanupAfterForceFail(WorkflowRunEntity run) {
        String runId = run.getRunIdPublic();
        String reason = "Run marked failed by recovery service - no activity for >"
                + zombieThreshold.toMinutes() + "m";

        // Close active epochs in the StateSnapshot so the UI doesn't show a ghost
        // "running" epoch. This is a DB write that must happen within the current
        // @Transactional context (before the after-commit hook fires).
        if (stateSnapshotService != null) {
            try {
                StateSnapshot snapshot = stateSnapshotService.getSnapshot(runId);
                if (snapshot != null) {
                    for (String triggerId : snapshot.getDags().keySet()) {
                        stateSnapshotService.closeAllActiveEpochs(runId, triggerId);
                    }
                    logger.info("[Recovery] Closed active epochs for runId={}", runId);
                }
            } catch (Exception e) {
                logger.warn("[Recovery] Failed to close active epochs for runId={}: {}", runId, e.getMessage());
            }
        }

        // Publish WorkflowRunTerminatedEvent so NotificationEmitter creates a user-facing
        // notification. The event is published in-transaction; the @TransactionalEventListener
        // in NotificationEmitter fires AFTER_COMMIT to guarantee the run is persisted as FAILED
        // before the notification row is written.
        if (eventPublisher != null) {
            try {
                eventPublisher.publishEvent(new WorkflowRunTerminatedEvent(
                        run.getId(),
                        run.getWorkflow() != null ? run.getWorkflow().getId() : null,
                        RunStatus.FAILED,
                        run.getPlanVersion()));
                logger.info("[Recovery] Published WorkflowRunTerminatedEvent for runId={}", runId);
            } catch (Exception e) {
                logger.warn("[Recovery] Failed to publish terminated event for runId={}: {}", runId, e.getMessage());
            }
        }

        // Snapshot collaborators + execution payload BEFORE the after-commit hook,
        // so the deferred lambda doesn't dereference fields that could have been
        // swapped. WorkflowExecution must be built while the entity is still managed.
        UnifiedSignalService signalService = this.unifiedSignalService;
        WorkflowRedisPublisher redisPublisher = this.workflowRedisPublisher;
        PendingAgentRegistry registry = this.pendingAgentRegistry;
        EpochConcurrencyLimiter limiter = this.epochConcurrencyLimiter;
        WorkflowStreamingService sse = this.streamingService;
        ErrorTriggerDispatchService errorDispatcher = this.errorTriggerDispatchService;
        // Build the execution payload up front: it is needed both for the SSE event
        // and for the error-trigger dispatch, and must be constructed while the run
        // entity is still managed (the after-commit hook runs post-flush).
        boolean needsExecution = sse != null || errorDispatcher != null;
        WorkflowExecution execution = needsExecution ? buildExecutionFor(run) : null;
        // ErrorTriggerDispatchService.dispatchWorkflowFailure gates on FAILED status
        // (requireTerminalStatus=true). The run row was just flipped to FAILED above
        // - propagate that to the execution so the dispatcher accepts it.
        if (execution != null) {
            execution.setStatus(RunStatus.FAILED);
        }

        Runnable hook = () -> {
            // Set agent cancel signal in Redis FIRST so RunCancellationGuard rejects
            // any in-flight async result that arrives during the rest of the cleanup.
            // Without this ordering, late results between cancelByRun and
            // setAgentCancelSignal would slip through the guard (signal still false)
            // and drive successors on a force-FAILed run.
            if (redisPublisher != null) {
                try {
                    redisPublisher.setAgentCancelSignal(runId);
                } catch (Exception e) {
                    logger.warn("[Recovery] Failed to set agent cancel signal for runId={}: {}",
                            runId, e.getMessage());
                }
            }

            if (signalService != null) {
                try {
                    signalService.cancelByRun(runId);
                } catch (Exception e) {
                    logger.warn("[Recovery] Failed to cancel signals for runId={}: {}", runId, e.getMessage());
                }
            }

            if (registry != null) {
                try {
                    registry.removeByRunId(runId);
                } catch (Exception e) {
                    logger.warn("[Recovery] Failed to remove pending agent entries for runId={}: {}",
                            runId, e.getMessage());
                }
            }

            if (limiter != null) {
                try {
                    limiter.cleanup(runId);
                } catch (Exception e) {
                    logger.warn("[Recovery] Failed to release epoch concurrency permits for runId={}: {}",
                            runId, e.getMessage());
                }
            }

            if (sse != null) {
                if (execution != null) {
                    try {
                        sse.sendWorkflowStatusEvent(execution, RunStatus.FAILED, reason);
                    } catch (Exception e) {
                        logger.warn("[Recovery] Failed to send streaming event for runId={}: {}",
                                runId, e.getMessage());
                    }
                } else {
                    logger.warn("[Recovery] Skipping streaming event for runId={} - no plan on run row", runId);
                }
            }

            // Dispatch user-configured error-handler workflows (Slack notifications,
            // retry-on-failure pipelines, etc.). Without this the watchdog's force-FAIL
            // is silent to anyone who wired an "error" trigger to this workflow - the
            // legacy V2WorkflowFinalizer.finalizeWithError path handles this for normal
            // failures, but the watchdog bypasses it (no WorkflowExecution in memory),
            // so we must call the dispatcher ourselves.
            if (errorDispatcher != null && execution != null) {
                try {
                    errorDispatcher.dispatchWorkflowFailure(execution);
                } catch (Exception e) {
                    logger.warn("[Recovery] Failed to dispatch error-trigger workflow for runId={}: {}",
                            runId, e.getMessage());
                }
            }
        };

        // Phase A2 (archi-refoundation 2026-05-04) - uses the extracted helper so
        // the same in-tx-or-now contract drives all three callers (SnapshotService.markDirty,
        // UnifiedSignalService.resolveSignal, this watchdog hook). DRY plan §0.3.
        com.apimarketplace.orchestrator.services.transaction.TransactionalHelper.runAfterCommitOrNow(hook);
    }

    /**
     * Builds a minimal {@link WorkflowExecution} suitable for status-event broadcasting.
     * The streaming layer reads {@code runId}, {@code plan.id}, and
     * {@link com.apimarketplace.orchestrator.domain.workflow.ExecutionStatistics} (which
     * walks the plan's execution graph) - so a plan IS required. Returns {@code null}
     * if the run row has no frozen plan; the caller logs and skips the SSE in that
     * case rather than masking the issue with a partial payload.
     */
    private WorkflowExecution buildExecutionFor(WorkflowRunEntity run) {
        Map<String, Object> planMap = run.getPlan();
        if (planMap == null || planMap.isEmpty()) {
            return null;
        }
        try {
            String workflowId = run.getWorkflow() != null && run.getWorkflow().getId() != null
                    ? run.getWorkflow().getId().toString()
                    : null;
            WorkflowPlan plan = workflowId != null
                    ? WorkflowPlan.fromMap(planMap, workflowId, run.getTenantId())
                    : WorkflowPlan.fromMap(planMap, run.getTenantId());
            WorkflowExecution execution = new WorkflowExecution(
                    run.getRunIdPublic(), plan, Collections.emptyMap());
            if (run.getId() != null) {
                execution.setWorkflowRunId(run.getId());
            }
            return execution;
        } catch (Exception e) {
            logger.warn("[Recovery] Failed to rebuild plan for runId={}: {}",
                    run.getRunIdPublic(), e.getMessage());
            return null;
        }
    }
}
