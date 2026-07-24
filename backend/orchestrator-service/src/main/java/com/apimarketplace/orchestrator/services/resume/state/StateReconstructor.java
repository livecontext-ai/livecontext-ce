package com.apimarketplace.orchestrator.services.resume.state;

import com.apimarketplace.common.storage.service.StorageService;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.WorkflowStepDataEntity;
import com.apimarketplace.orchestrator.domain.execution.DagState;
import com.apimarketplace.orchestrator.domain.execution.EpochState;
import com.apimarketplace.orchestrator.domain.execution.StateSnapshot;
import com.apimarketplace.orchestrator.domain.execution.StatusCounts;
import com.apimarketplace.orchestrator.domain.workflow.*;
import com.apimarketplace.orchestrator.persistence.WorkflowStepDataRepository;
import com.apimarketplace.orchestrator.services.StepAggregationService;
import com.apimarketplace.orchestrator.services.WorkflowRunStatusService;
import com.apimarketplace.orchestrator.services.resume.WorkflowRunState;
import com.apimarketplace.orchestrator.services.resume.cache.WorkflowCacheManager;
import com.apimarketplace.orchestrator.services.state.StateSnapshotService;
import com.apimarketplace.orchestrator.services.streaming.state.RunStateStore;
import com.apimarketplace.orchestrator.services.streaming.state.RunningNodeTracker;
import com.apimarketplace.orchestrator.domain.execution.SignalWaitEntity;
import com.apimarketplace.orchestrator.execution.v2.services.UnifiedSignalService;
import com.apimarketplace.orchestrator.trigger.TriggerType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/**
 * Service responsible for reconstructing workflow state for API responses.
 *
 * <p><b>REFACTORED:</b> Uses StateSnapshot as SINGLE SOURCE OF TRUTH.
 * StateSnapshot is stored in workflow_runs.state_snapshot (JSONB) and contains
 * all execution state accumulated across epochs.
 *
 * <p><b>From StateSnapshot (single source of truth):</b>
 * <ul>
 *   <li>Node status sets: completedNodeIds, failedNodeIds, skippedNodeIds, runningNodeIds, readyNodeIds</li>
 *   <li>NodeCounts (statusCounts): accumulated success/failure/skipped counts per node</li>
 *   <li>Decision branches taken</li>
 *   <li>Loop state</li>
 *   <li>Split state</li>
 * </ul>
 *
 * <p><b>From workflow_step_data (for detail view only):</b>
 * <ul>
 *   <li>Step outputs/inputs - for displaying execution details</li>
 *   <li>Error messages, HTTP status</li>
 *   <li>Timestamps (startTime, endTime)</li>
 * </ul>
 *
 * <p>This eliminates the need for expensive queries and recalculations.
 * StateSnapshot is updated atomically on each node completion and preserved across epochs.
 */
@Service
public class StateReconstructor {

    private static final Logger logger = LoggerFactory.getLogger(StateReconstructor.class);

    // Dependencies
    private final WorkflowStepDataRepository stepDataRepository;
    private final StorageService storageService;
    private final WorkflowRunStatusService workflowRunStatusService;
    private final RunStateStore runStateStore;
    private final WorkflowCacheManager cacheManager;
    private final StateSnapshotService stateSnapshotService;
    private final RunningNodeTracker runningNodeTracker;
    private final UnifiedSignalService unifiedSignalService;
    private final StepAggregationService stepAggregationService;

    // Lazy-initialized builders (only those still needed after refactoring)
    private volatile StateDataLoader dataLoader;
    private volatile StateReconstructorHelper helper;
    private volatile StatusCountsBuilder statusCountsBuilder;
    private volatile StepStateBuilder stepStateBuilder;
    private volatile EdgeStateBuilder edgeStateBuilder;

    public StateReconstructor(
            WorkflowStepDataRepository stepDataRepository,
            StorageService storageService,
            WorkflowRunStatusService workflowRunStatusService,
            RunStateStore runStateStore,
            WorkflowCacheManager cacheManager,
            StateSnapshotService stateSnapshotService,
            RunningNodeTracker runningNodeTracker,
            UnifiedSignalService unifiedSignalService,
            StepAggregationService stepAggregationService) {
        this.stepDataRepository = stepDataRepository;
        this.storageService = storageService;
        this.workflowRunStatusService = workflowRunStatusService;
        this.runStateStore = runStateStore;
        this.cacheManager = cacheManager;
        this.stateSnapshotService = stateSnapshotService;
        this.runningNodeTracker = runningNodeTracker;
        this.unifiedSignalService = unifiedSignalService;
        this.stepAggregationService = stepAggregationService;
    }

    // ========================================================================
    // DATA HOLDER RECORDS
    // ========================================================================

    /**
     * Data holder for prepared step data (output display).
     */
    public record StepDataPreparation(
        List<WorkflowStepDataEntity> stepEntities,
        Map<String, List<WorkflowStepDataEntity>> stepsByAlias
    ) {}

    /**
     * Controls whether step output payloads are dereferenced from storage during
     * reconstruction (used by {@link com.apimarketplace.orchestrator.services.resume.state.StepStateBuilder}).
     *
     * <p>FULL: load every step's output blob (storage round-trip per COMPLETED entity).
     * Used by engine paths that need full output payloads in {@code StepState.output}.
     *
     * <p>AGENT_AND_INTERFACE_ONLY: load output blobs ONLY for {@code agent:} and
     * {@code interface:} aliases (panels/modals read them at refresh). Skip
     * mcp/trigger/core/table outputs. Used by the REST {@code /state} endpoint
     * to avoid N storage round-trips on long runs.
     */
    public enum OutputLoadMode { FULL, AGENT_AND_INTERFACE_ONLY }


    // ========================================================================
    // LAZY INITIALIZATION
    // ========================================================================

    private StateDataLoader getDataLoader() {
        StateDataLoader local = dataLoader;
        if (local == null) {
            synchronized (this) {
                local = dataLoader;
                if (local == null) {
                    dataLoader = local = new StateDataLoader(stepDataRepository);
                }
            }
        }
        return local;
    }

    private StateReconstructorHelper getHelper() {
        StateReconstructorHelper local = helper;
        if (local == null) {
            synchronized (this) {
                local = helper;
                if (local == null) {
                    helper = local = new StateReconstructorHelper(storageService, workflowRunStatusService, runStateStore, cacheManager);
                }
            }
        }
        return local;
    }

    private StatusCountsBuilder getStatusCountsBuilder() {
        StatusCountsBuilder local = statusCountsBuilder;
        if (local == null) {
            synchronized (this) {
                local = statusCountsBuilder;
                if (local == null) {
                    statusCountsBuilder = local = new StatusCountsBuilder(getHelper());
                }
            }
        }
        return local;
    }

    private StepStateBuilder getStepStateBuilder() {
        StepStateBuilder local = stepStateBuilder;
        if (local == null) {
            synchronized (this) {
                local = stepStateBuilder;
                if (local == null) {
                    stepStateBuilder = local = new StepStateBuilder(getHelper(), getStatusCountsBuilder());
                }
            }
        }
        return local;
    }

    private EdgeStateBuilder getEdgeStateBuilder() {
        EdgeStateBuilder local = edgeStateBuilder;
        if (local == null) {
            synchronized (this) {
                local = edgeStateBuilder;
                if (local == null) {
                    edgeStateBuilder = local = new EdgeStateBuilder(getHelper(), stateSnapshotService);
                }
            }
        }
        return local;
    }

    // ========================================================================
    // MAIN RECONSTRUCTION METHOD
    // ========================================================================

    /**
     * Reconstructs the complete state of a workflow run.
     *
     * <p>REFACTORED: Uses StateSnapshot as SINGLE SOURCE OF TRUTH for:
     * <ul>
     *   <li>Node status sets (completed, failed, skipped, running, ready)</li>
     *   <li>StatusCounts per node (accumulated across all epochs)</li>
     *   <li>Decision branches taken</li>
     *   <li>Loop state</li>
     * </ul>
     *
     * <p>Still loads from workflow_step_data:
     * <ul>
     *   <li>Step outputs/inputs (for detail view)</li>
     *   <li>Error messages, HTTP status</li>
     * </ul>
     *
     * @param runEntity The workflow run entity
     * @param plan The workflow plan
     * @return The reconstructed workflow run state
     */
    public WorkflowRunState reconstructState(WorkflowRunEntity runEntity, WorkflowPlan plan) {
        return reconstructStateInternal(runEntity, plan, OutputLoadMode.FULL);
    }

    /**
     * REST-only reconstruction: skip step output blobs for non-rendered aliases
     * (mcp/trigger/core/table). Used by GET /state to avoid N storage round-trips
     * on long runs.
     *
     * <p>DO NOT call from engine paths (resume, signal, rerun, cron, async, cache
     * rebuild). Engine paths need full output payloads in {@code StepState.output}
     * for the WS UI. Use {@link #reconstructState(WorkflowRunEntity, WorkflowPlan)}
     * there.
     */
    public WorkflowRunState reconstructStateForApi(WorkflowRunEntity runEntity, WorkflowPlan plan) {
        return reconstructStateInternal(runEntity, plan, OutputLoadMode.AGENT_AND_INTERFACE_ONLY);
    }

    private WorkflowRunState reconstructStateInternal(WorkflowRunEntity runEntity, WorkflowPlan plan, OutputLoadMode mode) {
        String runId = runEntity.getRunIdPublic();
        // [Phase E TEMP] Capture business caller (skip Spring proxy + this class' own frames)
        // so we can attribute reconstructState volume across trigger fire, async completion,
        // REST, SBS. StackWalker is lazy and cheaper than Thread.getStackTrace().
        String callerHint = StackWalker.getInstance()
            .walk(frames -> frames
                .filter(f -> {
                    String cn = f.getClassName();
                    return !cn.equals(StateReconstructor.class.getName())
                        && !cn.contains("CGLIB$$")
                        && !cn.contains("$$SpringCGLIB$$")
                        && !cn.startsWith("org.springframework.aop")
                        && !cn.startsWith("org.springframework.cglib")
                        && !cn.startsWith("jdk.internal.reflect")
                        && !cn.startsWith("java.lang.reflect");
                })
                .findFirst()
                .map(f -> simpleClass(f.getClassName()) + "." + f.getMethodName() + ":" + f.getLineNumber())
                .orElse("?"));
        logger.info("[ReconstructState] Starting for runId: {} mode={} calledFrom={}", runId, mode, callerHint);

        ExecutionGraph graph = plan.getExecutionGraph();

        // =====================================================================
        // 1. READ FROM STATE_SNAPSHOT (SINGLE SOURCE OF TRUTH)
        // =====================================================================
        // Optimization: use entity's stateSnapshot directly instead of extra DB query.
        // Cache-aware variant short-circuits Jackson when this is the Nth read inside
        // the same @Transactional (split fan-out, async barrier seal).
        StateSnapshot stateSnapshot = stateSnapshotService.parseSnapshotForRun(runId, runEntity.getStateSnapshot());
        logger.info("[ReconstructState] Parsed StateSnapshot from entity for runId={}, version={}",
            runId, stateSnapshot.getVersion());

        // Node status sets - directly from StateSnapshot
        Set<String> completedStepIds = new HashSet<>(stateSnapshot.getCompletedNodeIds());
        Set<String> failedStepIds = new HashSet<>(stateSnapshot.getFailedNodeIds());
        Set<String> skippedStepIds = new HashSet<>(stateSnapshot.getSkippedNodeIds());
        Set<String> runningStepIds = new HashSet<>(stateSnapshot.getRunningNodeIds());
        Set<String> readySteps = new HashSet<>(stateSnapshot.getReadyNodeIds());

        // A terminal run has zero running nodes by definition - the same invariant
        // SnapshotService enforces for the WS snapshot path. Suppress the transient
        // Redis running overlay below for terminal runs so a stale entry (a dropped
        // markCompleted, or a missed overlay purge) can never paint a node "running"
        // on a finished run via the REST refresh.
        boolean runTerminal = runEntity.getStatus() != null && runEntity.getStatus().isTerminal();

        // P2.2 site 3b - overlay Redis running before the fresh-trigger-recalc gate.
        // Pre-P2.3 elision: JSONB still carries runningNodeIds, so this is additive
        // (Redis ∪ JSONB). Post-elision: JSONB runningNodeIds is empty and Redis is
        // the only authoritative source; without this overlay, the gate at line 295
        // would see empty-running-and-empty-ready-and-empty-completed and recalc
        // triggers, potentially re-firing already-running nodes.
        //
        // The protective conjunction at line 295 prevents recalc when ANY of
        // ready/completed is non-empty. The running-empty path remains a fail-OPEN
        // gate today (Redis-down + elision-on at first-running-node moment would
        // re-fire `calculateReadyTriggers`). P2.3 must convert this read to
        // fail-CLOSED (use `getRunningCountsOrThrow` and defer recalc on Redis
        // exception) before flipping the elide flag for any tenant.
        // runningNodeTracker is constructor-mandatory (final field); no null-guard needed.
        // getRunningCountsAcrossEpochs is itself fail-OPEN (returns empty Map on Redis-down),
        // so this loop is a safe no-op when Redis is unavailable.
        Map<String, Integer> redisAggregate = runTerminal
            ? Map.of()
            : runningNodeTracker.getRunningCountsAcrossEpochs(runId);
        for (Map.Entry<String, Integer> entry : redisAggregate.entrySet()) {
            if (entry.getValue() != null && entry.getValue() > 0) {
                runningStepIds.add(entry.getKey());
            }
        }

        // New epoch or fresh start: calculate ready triggers
        if (readySteps.isEmpty() && completedStepIds.isEmpty() && runningStepIds.isEmpty()) {
            readySteps = calculateReadyTriggers(plan);
            logger.info("[ReconstructState] Calculated ready triggers for new epoch: {}", readySteps);
        }

        // Include awaiting signal nodes in running set
        Set<String> awaitingSignalNodeIds = stateSnapshot.getAwaitingSignalNodeIds();
        if (awaitingSignalNodeIds != null && !awaitingSignalNodeIds.isEmpty()) {
            runningStepIds.addAll(awaitingSignalNodeIds);
            logger.info("[ReconstructState] awaitingSignalNodeIds: {}", awaitingSignalNodeIds);
        }

        // Decision branches - directly from StateSnapshot
        Map<String, Set<String>> decisionBranches = stateSnapshot.getDecisionBranches();

        // StatusCounts - convert NodeCounts from StateSnapshot to StatusCounts format
        Map<String, StatusCounts> stepStatusCounts = convertNodeCountsToStatusCounts(stateSnapshot.getNodes());

        logger.info("[ReconstructState] From StateSnapshot: completed={}, failed={}, skipped={}, running={}, ready={}",
            completedStepIds.size(), failedStepIds.size(), skippedStepIds.size(),
            runningStepIds.size(), readySteps.size());

        // =====================================================================
        // 2. LOAD FROM WORKFLOW_STEP_DATA (FOR OUTPUTS/INPUTS ONLY)
        // =====================================================================
        int currentEpoch = extractCurrentEpoch(runEntity);
        Map<String, Object> metadata = runEntity.getMetadata();

        // Load step data for output/input details and accumulated status counts
        StepDataPreparation dataPrep = getDataLoader().loadAndPrepareStepData(runEntity, currentEpoch, metadata);
        List<WorkflowStepDataEntity> stepEntities = dataPrep.stepEntities();
        Map<String, List<WorkflowStepDataEntity>> stepsByAlias = dataPrep.stepsByAlias();

        // StateSnapshot is the single source of truth for counts.
        // NodeCounts/EdgeCounts are incremented atomically on each node completion and
        // ACCUMULATE forever (resetDag() preserves them by design - badge totals across
        // epochs/reruns). Per-node *status* therefore never trusts them over the current
        // epoch state (see StepStateBuilder.deriveStatusFromCounts guards).
        // No overwrite from step_data - stepStatusCounts above is authoritative.
        logger.info("[ReconstructState] Using StateSnapshot as source of truth for statusCounts ({} nodes)",
            stepStatusCounts.size());

        // =====================================================================
        // 3. BUILD STATES FOR API RESPONSE
        // =====================================================================

        // Compute per-node awaiting signal count from active epochs + active DB signals.
        // In split context, a single epoch has N active signals for the same nodeId.
        // Epoch-based counting alone returns 1; we need the actual per-item count
        // so the frontend item selector shows the correct number of pending items.
        List<SignalWaitEntity> activeSignals = loadActiveSignals(runId);
        Map<String, Integer> awaitingSignalCounts = computeAwaitingSignalCounts(stateSnapshot, activeSignals);

        // =====================================================================
        // 3a. MERGE REDIS RUNNING OVERLAY (transient async agent state)
        // =====================================================================
        // RunningNodeTracker (Redis) tracks nodes dispatched to async workers that
        // haven't completed yet. StateSnapshot JSONB doesn't know about these because
        // the synchronous execution finished (dispatched to queue) before the async
        // result arrived. Without this merge, the REST API response loses the
        // "running" status for async nodes, causing the frontend Safety refresh
        // to overwrite the correct WS-delivered running state.
        Set<String> terminalNodeIds = new HashSet<>();
        terminalNodeIds.addAll(completedStepIds);
        terminalNodeIds.addAll(failedStepIds);
        terminalNodeIds.addAll(skippedStepIds);

        // Post-P2.3.1: writers populate per-epoch keys ({runId}:{epoch}) only.
        // SCAN every per-epoch key + legacy fallback for pre-P2.3.1 in-flight runs.
        Map<String, Integer> redisRunningCounts = runTerminal
            ? Map.of()
            : runningNodeTracker.getRunningCountsAcrossEpochs(runId);
        Set<String> pureRunningNodeIds = new HashSet<>(stateSnapshot.getRunningNodeIds());
        for (Map.Entry<String, Integer> entry : redisRunningCounts.entrySet()) {
            String nodeId = entry.getKey();
            int count = entry.getValue();
            if (count > 0 && !terminalNodeIds.contains(nodeId)) {
                // Add to running sets so StepStateBuilder post-processes PENDING → RUNNING
                pureRunningNodeIds.add(nodeId);
                runningStepIds.add(nodeId);
                // Merge running count into stepStatusCounts so the frontend badge is correct
                StatusCounts existing = stepStatusCounts.get(nodeId);
                if (existing != null) {
                    stepStatusCounts.put(nodeId, new StatusCounts(
                        count, existing.getCompleted(), existing.getFailed(), existing.getSkipped(), existing.getTotal()));
                } else {
                    stepStatusCounts.put(nodeId, new StatusCounts(count, 0, 0, 0, count));
                }
            }
        }
        if (!redisRunningCounts.isEmpty()) {
            logger.info("[ReconstructState] Merged Redis running overlay: {} entries, pureRunningNodeIds={}",
                redisRunningCounts.size(), pureRunningNodeIds);
        }

        // Build step states with outputs (uses stepsByAlias for details).
        // OutputLoadMode controls whether output blobs are dereferenced from storage:
        // FULL → all aliases load output (engine path). AGENT_AND_INTERFACE_ONLY → only
        // agent:/interface: aliases load output (REST /state path).
        List<WorkflowRunState.StepState> stepStates = getStepStateBuilder().buildStepStates(
            plan, graph, stepsByAlias, completedStepIds, failedStepIds, skippedStepIds, readySteps, stepStatusCounts,
            awaitingSignalNodeIds, awaitingSignalCounts, stateSnapshot.getNodes(), pureRunningNodeIds, mode,
            stateSnapshot.getPartialFailedNodeIds());

        // DISPLAY-path only: reconcile the all-epochs node colour with the spawn-aware
        // multi-epoch aggregate. A node with no terminal result in the CURRENT epoch reconstructs
        // as SKIPPED (current epoch skipped it, last epoch retained) or PENDING (a reusable-trigger
        // run at WAITING_TRIGGER prunes closed epochs, so the flat sets are empty) even though it
        // COMPLETED/FAILED in an earlier epoch - the flat node sets only cover retained epochs and
        // accumulated NodeCounts can't tell a genuine prior-epoch success from a superseded rerun
        // spawn. StepAggregationService can (latest spawn per coordinate), so reconcile a current
        // PENDING/SKIPPED to the cumulative aggregate (COMPLETED/FAILED/PARTIAL_SUCCESS/SKIPPED) -
        // making the canvas match the StepTable. Applied for reconstructStateForApi only (authed
        // /state full=false AND marketplace showcase); engine paths (FULL) keep current-epoch
        // semantics untouched (and read the flat sets, never step status). See
        // StepStateBuilder.deriveStatusFromCounts (the rerun guard).
        //
        // Suppressed during a mid-rerun reset window (an ACTIVE epoch whose DAG has been
        // rerun, i.e. its spawn counter > 0): a step-rerun resets its subgraph to PENDING at
        // the NEW spawn before anything re-executes, but the spawn-aware aggregate still keeps
        // the OLD spawn's rows (they are the latest rows that exist), so the overlay would
        // resurrect the exact stale COMPLETED/SKIPPED statuses the reset just cleared
        // (Phase A rerun fix #2). While the epoch is active the current-epoch reconstruction
        // is authoritative; once the epoch closes (run completes / WAITING_TRIGGER prune)
        // the overlay resumes and the aggregate again matches reality.
        if (mode == OutputLoadMode.AGENT_AND_INTERFACE_ONLY
                && !isMidRerunResetWindow(stateSnapshot, runEntity.getMetadata())) {
            stepStates = applyMultiEpochAggregateStatus(runId, stepStates);
        }

        // Build edge states from StateSnapshot edge counts
        List<WorkflowRunState.EdgeState> edgeStates = getEdgeStateBuilder().buildEdgeStates(
            runId, plan, completedStepIds, failedStepIds, skippedStepIds, stepStatusCounts);

        // Loop state - convert from StateSnapshot
        Map<String, Object> loops = convertLoopStateToMap(stateSnapshot.getLoops());

        // Determine overall status
        RunStatus overallStatus = getHelper().determineOverallStatus(
            runEntity, completedStepIds, failedStepIds, readySteps, plan);

        // Log summary for debugging
        logStateSummary(runId, stepStates, edgeStates, readySteps);

        Map<String, Object> planMap = runEntity.getPlan();
        ExecutionMode executionMode = runEntity.getExecutionMode();

        // Interface snapshots are now fetched via frontend polling the render endpoint
        List<Map<String, Object>> interfaces = List.of();

        return new WorkflowRunState(
            runId,
            runEntity.getWorkflow() != null ? runEntity.getWorkflow().getId().toString() : null,
            overallStatus,
            executionMode,
            runEntity.getStartedAt(),
            getHelper().isPaused(runId, runEntity) ? Instant.now() : null,
            planMap,
            stepStates,
            edgeStates,
            readySteps,
            completedStepIds,
            failedStepIds,
            skippedStepIds,
            runningStepIds,
            loops,
            interfaces
        );
    }

    /**
     * True while some DAG of the run is inside a rerun reset window: it has at least one
     * ACTIVE (non-closed) epoch AND has been rerun at least once (spawn counter > 0).
     *
     * <p>In that window the reset subgraph is PENDING at the new spawn with no rows written
     * yet, so the "latest spawn per coordinate" aggregate still reports the superseded
     * spawn's terminal statuses. Reconciling against it would undo the reset on the canvas
     * (stale COMPLETED on the previously-taken branch of a flipped decision). Once every
     * epoch is closed the flat view and the aggregate agree again and the overlay is safe.
     *
     * <p>The spawn counter that PRODUCTION writes lives in run metadata
     * ({@code metadata.dagCurrentSpawn}, per-trigger map, written by
     * {@code TriggerEpochManager.incrementSpawn} on the {@code StepRerunService} path) -
     * NOT in the snapshot: no production path calls {@code DagState.advanceSpawn()}, so
     * {@code DagState.currentSpawn} stays 0 on real runs. Read the metadata first and keep
     * the snapshot field as a fallback so both writers are honoured. The metadata counter
     * is never reset, so a once-rerun DAG suppresses the overlay for the in-flight part of
     * every later epoch too - deliberate: while an epoch is executing, the current-epoch
     * view is authoritative and must never be overwritten by superseded-spawn aggregates.
     * Package-private + static for DB-free unit tests.
     */
    static boolean isMidRerunResetWindow(StateSnapshot snapshot, Map<String, Object> runMetadata) {
        for (Map.Entry<String, DagState> entry : snapshot.getDags().entrySet()) {
            DagState dag = entry.getValue();
            if (dag.hasActiveEpochs() && dagSpawnCounter(runMetadata, entry.getKey(), dag) > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Current rerun-spawn counter for one DAG: the production value from run metadata
     * ({@code dagCurrentSpawn[triggerId]}) when present, else the snapshot's
     * {@code DagState.currentSpawn}.
     */
    private static int dagSpawnCounter(Map<String, Object> runMetadata, String triggerId, DagState dag) {
        if (runMetadata != null
                && runMetadata.get("dagCurrentSpawn") instanceof Map<?, ?> spawnByTrigger
                && spawnByTrigger.get(triggerId) instanceof Number spawn) {
            return Math.max(spawn.intValue(), dag.getCurrentSpawn());
        }
        return dag.getCurrentSpawn();
    }

    /**
     * Reconcile each step whose current-epoch status carries no terminal result (PENDING or
     * SKIPPED) with the spawn-aware multi-epoch aggregate, so the all-epochs canvas shows the
     * cumulative outcome (matching the StepTable) instead of the last/retained epoch's view.
     *
     * <p>Only queried when at least one step is PENDING or SKIPPED (most single-epoch or
     * in-flight runs skip the query entirely). The reconciliation is per-alias and only touches
     * PENDING/SKIPPED steps: a current COMPLETED/FAILED/PARTIAL_SUCCESS/RUNNING/AWAITING_SIGNAL
     * stands. A rerun-deactivated branch (aggregate also SKIPPED) is untouched; a node that never
     * ran in any epoch has no aggregate entry and stays PENDING.
     */
    private List<WorkflowRunState.StepState> applyMultiEpochAggregateStatus(
            String runId, List<WorkflowRunState.StepState> stepStates) {
        boolean anyPendingOrSkipped = false;
        for (WorkflowRunState.StepState s : stepStates) {
            if (s.status() == RunStatus.PENDING || s.status() == RunStatus.SKIPPED) {
                anyPendingOrSkipped = true;
                break;
            }
        }
        if (!anyPendingOrSkipped) {
            return stepStates;
        }

        Map<String, RunStatus> aggByAlias = stepAggregationService.getAggregatedStatusByAlias(runId);
        if (aggByAlias.isEmpty()) {
            return stepStates;
        }

        List<WorkflowRunState.StepState> result = new ArrayList<>(stepStates.size());
        for (WorkflowRunState.StepState s : stepStates) {
            RunStatus reconciled = aggregateDisplayStatus(s.status(), aggByAlias.get(s.stepAlias()));
            if (reconciled != s.status()) {
                logger.info("[ReconstructState] multi-epoch overlay: {} {} -> {} (aggregate)",
                    s.stepAlias(), s.status(), reconciled);
                result.add(s.withStatus(reconciled));
            } else {
                result.add(s);
            }
        }
        return result;
    }

    /**
     * Resolve the all-epochs DISPLAY status for a step from its current-epoch status and the
     * spawn-aware multi-epoch aggregate. Only a current PENDING or SKIPPED (no terminal result
     * this epoch) is reconciled, and only to a terminal aggregate
     * (COMPLETED/FAILED/PARTIAL_SUCCESS/SKIPPED); every other case returns the current status,
     * so live states (RUNNING/AWAITING_SIGNAL) and current-epoch terminals are preserved and a
     * never-run node (null aggregate) stays PENDING. Package-private + static for DB-free unit tests.
     */
    static RunStatus aggregateDisplayStatus(RunStatus current, RunStatus aggregate) {
        if (aggregate == null
                || (current != RunStatus.PENDING && current != RunStatus.SKIPPED)) {
            return current;
        }
        return switch (aggregate) {
            case COMPLETED, FAILED, PARTIAL_SUCCESS, SKIPPED -> aggregate;
            default -> current;
        };
    }

    /**
     * Convert NodeCounts (from StateSnapshot) to StatusCounts (for API response).
     *
     * <p>Field mapping:
     * <ul>
     *   <li>NodeCounts.running → StatusCounts.running</li>
     *   <li>NodeCounts.completed → StatusCounts.success</li>
     *   <li>NodeCounts.failed → StatusCounts.failure</li>
     *   <li>NodeCounts.skipped → StatusCounts.skipped</li>
     * </ul>
     */
    private Map<String, StatusCounts> convertNodeCountsToStatusCounts(Map<String, StateSnapshot.NodeCounts> nodeCounts) {
        Map<String, StatusCounts> result = new HashMap<>();
        for (Map.Entry<String, StateSnapshot.NodeCounts> entry : nodeCounts.entrySet()) {
            StateSnapshot.NodeCounts nc = entry.getValue();
            StatusCounts sc = new StatusCounts(
                nc.running(),
                nc.completed(),  // completed → success
                nc.failed(),     // failed → failure
                nc.skipped(),
                nc.total()
            );
            result.put(entry.getKey(), sc);
        }
        logger.debug("[ReconstructState] Converted {} NodeCounts to StatusCounts", result.size());
        return result;
    }

    /**
     * Count how many active signals each node has awaiting resolution.
     * Uses epoch-based counts as baseline, then enhances with actual per-item
     * counts from active DB signals. In split context, a single epoch may have
     * N active signals for the same nodeId (one per split item).
     */
    private Map<String, Integer> computeAwaitingSignalCounts(StateSnapshot snapshot, List<SignalWaitEntity> activeSignals) {
        // Start with epoch-based counts as baseline
        Map<String, Integer> counts = new HashMap<>();
        for (DagState dag : snapshot.getDags().values()) {
            for (int epochNum : dag.getActiveEpochs()) {
                EpochState epoch = dag.getEpochs().get(epochNum);
                if (epoch == null) continue;
                for (String nodeId : epoch.getAwaitingSignalNodeIds()) {
                    counts.merge(nodeId, 1, Integer::sum);
                }
            }
        }

        // Enhance with actual per-item counts from active signals.
        // In split context, a single epoch has N active signals for the same nodeId.
        if (activeSignals != null && !activeSignals.isEmpty()) {
            Map<String, Integer> signalCounts = new HashMap<>();
            for (SignalWaitEntity signal : activeSignals) {
                signalCounts.merge(signal.getNodeId(), 1, Integer::sum);
            }
            for (Map.Entry<String, Integer> entry : signalCounts.entrySet()) {
                // Use the higher count: signal-based (per-item) vs epoch-based (per-epoch)
                counts.merge(entry.getKey(), entry.getValue(), Math::max);
            }
        }

        return counts;
    }

    private List<SignalWaitEntity> loadActiveSignals(String runId) {
        if (unifiedSignalService == null) return List.of();
        try {
            return unifiedSignalService.getActiveSignals(runId);
        } catch (Exception e) {
            logger.debug("[ReconstructState] Could not load active signals for runId={}: {}", runId, e.getMessage());
            return List.of();
        }
    }

    /**
     * Convert LoopState records to Map format for API compatibility.
     */
    private Map<String, Object> convertLoopStateToMap(Map<String, StateSnapshot.LoopState> loops) {
        Map<String, Object> result = new HashMap<>();
        for (Map.Entry<String, StateSnapshot.LoopState> entry : loops.entrySet()) {
            StateSnapshot.LoopState ls = entry.getValue();
            Map<String, Object> loopMap = new HashMap<>();
            loopMap.put("currentIndex", ls.currentIndex());
            loopMap.put("totalItems", ls.totalItems());
            loopMap.put("status", ls.status());
            result.put(entry.getKey(), loopMap);
        }
        return result;
    }

    // ========================================================================
    // HELPER METHODS
    // ========================================================================

    /**
     * Extract current epoch from run entity metadata.
     */
    private int extractCurrentEpoch(WorkflowRunEntity runEntity) {
        Map<String, Object> metadata = runEntity.getMetadata();
        if (metadata != null && metadata.containsKey("currentEpoch")) {
            Object epochValue = metadata.get("currentEpoch");
            if (epochValue instanceof Number) {
                return ((Number) epochValue).intValue();
            }
        }
        return 0;
    }

    /**
     * Log state summary for debugging.
     */
    private void logStateSummary(String runId, List<WorkflowRunState.StepState> stepStates,
                                  List<WorkflowRunState.EdgeState> edgeStates, Set<String> readySteps) {
        logger.debug("=== STEP-BY-STEP STATE SUMMARY for runId={} ===", runId);
        logger.debug("--- NODE STATUSES ---");
        for (var step : stepStates) {
            logger.debug("  NODE: {} | status={} | canExecute={}", step.stepId(), step.status(), step.canExecute());
        }
        logger.debug("--- EDGE STATUSES ---");
        for (var edge : edgeStates) {
            logger.debug("  EDGE: {} -> {} | status={} | completed={} | skipped={}",
                edge.from(), edge.to(), edge.status(), edge.completedCount(), edge.skippedCount());
        }
        logger.debug("--- READY STEPS: {} ---", readySteps);
        logger.debug("=== END STATE SUMMARY ===");
    }

    /**
     * Calculate ready triggers for a fresh epoch state.
     *
     * <p>This is called when the StateSnapshot has empty readyNodeIds AND empty
     * completedStepIds/runningStepIds - indicating a fresh workflow or epoch reset
     * where no nodes have executed yet.
     *
     * <p>Reusable triggers (webhook, manual, chat, form, datasource) are ready to fire.
     *
     * @param plan The workflow plan
     * @return Set of ready trigger IDs
     */
    private Set<String> calculateReadyTriggers(WorkflowPlan plan) {
        Set<String> readyTriggers = new HashSet<>();

        if (plan.getTriggers() != null) {
            for (var trigger : plan.getTriggers()) {
                // Reusable triggers are ready to fire
                if (TriggerType.isReusableTriggerType(trigger.type())) {
                    readyTriggers.add(trigger.getNormalizedKey());
                }
            }
        }

        return readyTriggers;
    }

    /** Strip package prefix from a fully-qualified class name for compact log output. */
    private static String simpleClass(String fqcn) {
        if (fqcn == null) return "?";
        int dot = fqcn.lastIndexOf('.');
        return dot < 0 ? fqcn : fqcn.substring(dot + 1);
    }
}
