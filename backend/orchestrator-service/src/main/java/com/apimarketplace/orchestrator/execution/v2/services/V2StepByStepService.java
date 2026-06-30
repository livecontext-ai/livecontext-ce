package com.apimarketplace.orchestrator.execution.v2.services;

import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.cache.ExecutionCacheManager;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionTree;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionTreeBuilder;
import com.apimarketplace.orchestrator.execution.v2.engine.StepByStepExecutionResult;
import com.apimarketplace.orchestrator.execution.v2.engine.TriggerItem;
import com.apimarketplace.orchestrator.execution.v2.engine.UnifiedExecutionEngine;
import com.apimarketplace.orchestrator.execution.v2.nodes.ExecutionNode;
import com.apimarketplace.orchestrator.execution.v2.scheduler.V2StepByStepScheduler;
import com.apimarketplace.orchestrator.domain.workflow.ExecutionMode;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.context.ReadinessContextCache;
import com.apimarketplace.orchestrator.services.state.StateSnapshotService;
import com.apimarketplace.orchestrator.services.streaming.state.RunningNodeTracker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * V2 Step-by-step execution service.
 *
 * Implements the "Execute One Node" pattern:
 * - Each node execution is a separate API call
 * - State is persisted to DB after each node
 * - Workflow can resume after page reload/server restart
 * - No threads blocking in memory
 *
 * Delegates to:
 * - {@link V2StepByStepContextManager} for context and cache management
 * - {@link V2TriggerLoadingService} for trigger loading logic
 */
@Slf4j
@Service
public class V2StepByStepService {

    private final UnifiedExecutionEngine executionEngine;
    private final ExecutionTreeBuilder treeBuilder;
    private final V2ExecutionEventService eventService;
    private final V2StepByStepScheduler stepByStepScheduler;
    private final V2StepByStepContextManager contextManager;
    private final V2TriggerLoadingService triggerLoadingService;
    private final WorkflowRunRepository workflowRunRepository;
    private final ExecutionCacheManager executionCacheManager;
    private final StateSnapshotService stateSnapshotService;

    // P2.2 site 5 - optional Redis overlay for the idempotency fast-path. Pre-elision
    // the JSONB running set still drives the alreadyRunning check; post-elision we
    // need Redis. Optional injection preserves the existing constructor for tests.
    @Autowired(required = false)
    private RunningNodeTracker runningNodeTracker;

    // Piste 4 - Caffeine cache for the readiness hot loop. Optional injection so the
    // legacy 9-arg constructor used by tests keeps compiling.
    @Autowired(required = false)
    private ReadinessContextCache readinessCache;

    public V2StepByStepService(
            @Lazy UnifiedExecutionEngine executionEngine,
            ExecutionTreeBuilder treeBuilder,
            V2ExecutionEventService eventService,
            V2StepByStepScheduler stepByStepScheduler,
            V2StepByStepContextManager contextManager,
            V2TriggerLoadingService triggerLoadingService,
            WorkflowRunRepository workflowRunRepository,
            ExecutionCacheManager executionCacheManager,
            StateSnapshotService stateSnapshotService) {
        this.executionEngine = executionEngine;
        this.treeBuilder = treeBuilder;
        this.eventService = eventService;
        this.stepByStepScheduler = stepByStepScheduler;
        this.contextManager = contextManager;
        this.triggerLoadingService = triggerLoadingService;
        this.workflowRunRepository = workflowRunRepository;
        this.executionCacheManager = executionCacheManager;
        this.stateSnapshotService = stateSnapshotService;
    }

    /**
     * Initialize a workflow for step-by-step execution.
     *
     * This builds the execution tree and returns the initial ready nodes
     * (typically just the trigger node).
     */
    /**
     * Pre-cache trigger payload so it's available as triggerData when the trigger node executes.
     * Called by ReusableTriggerService before executeNode() to bridge the gap between
     * the HTTP payload and the V2 execution context.
     */
    public void cacheTriggerPayload(String runId, Map<String, Object> payload) {
        if (payload != null && !payload.isEmpty()) {
            contextManager.cacheTriggerItems(runId, java.util.List.of(new java.util.HashMap<>(payload)));
            log.info("[V2StepByStep] Cached trigger payload for runId={}, keys={}", runId, payload.keySet());
        }
    }

    /**
     * Pre-cache trigger payload for a specific epoch.
     *
     * <p>Reusable triggers can fire concurrently against the same run (for example
     * a sub-workflow called from split items). Run-scoped trigger payload caching is
     * not enough there: fire N+1 can overwrite fire N before the trigger node builds
     * its context. The epoch-scoped cache keeps each fire's payload isolated while
     * preserving the run-level fallback for legacy call sites.</p>
     */
    public void cacheTriggerPayload(String runId, int epoch, Map<String, Object> payload) {
        if (payload != null && !payload.isEmpty()) {
            contextManager.cacheTriggerItems(runId, epoch, java.util.List.of(new java.util.HashMap<>(payload)));
            log.info("[V2StepByStep] Cached trigger payload for runId={}, epoch={}, keys={}",
                runId, epoch, payload.keySet());
        }
    }

    /**
     * Pre-cache trigger-scoped execution metadata as globalData for one epoch.
     * This is used by internal callers such as SubWorkflowNode; the values are
     * intentionally kept out of triggerData so trigger outputs remain user data.
     */
    public void cacheTriggerGlobalData(String runId, int epoch, Map<String, Object> globalData) {
        if (globalData != null && !globalData.isEmpty()) {
            contextManager.updateGlobalData(runId, epoch, new java.util.HashMap<>(globalData));
            log.info("[V2StepByStep] Cached trigger globalData for runId={}, epoch={}, keys={}",
                runId, epoch, globalData.keySet());
        }
    }

    public Map<String, Object> initializeStepByStep(
            WorkflowExecution execution,
            WorkflowPlan plan) {
        return initializeStepByStep(execution, plan, null);
    }

    /**
     * Initialize step-by-step execution with an explicit trigger ID.
     *
     * When triggerId is provided, the initial ready nodes are stored under
     * the real trigger's DagState instead of the "trigger:default" sentinel.
     * This ensures that subsequent epoch resets (which target the real trigger)
     * properly clear the state from epoch 1.
     */
    public Map<String, Object> initializeStepByStep(
            WorkflowExecution execution,
            WorkflowPlan plan,
            String triggerId) {

        String runId = execution.getRunId();
        String workflowRunId = execution.getWorkflowRunId() != null
            ? execution.getWorkflowRunId().toString()
            : runId;
        String tenantId = plan.getTenantId();

        log.info("[V2StepByStep] Initializing step-by-step execution: runId={}, triggerId={}", runId, triggerId);

        var runEntityOpt = workflowRunRepository.findByRunIdPublic(runId);
        WorkflowRunEntity runEntity = runEntityOpt != null ? runEntityOpt.orElse(null) : null;

        // Build execution tree. Reusable triggers execute through the SBS engine
        // even in automatic mode, so this path must carry the same workspace scope
        // as WorkflowExecutionServiceV2's automatic tree build.
        ExecutionTree tree = treeBuilder.build(
            runId,
            workflowRunId,
            tenantId,
            plan,
            runEntity != null ? runEntity.getOrgId() : null,
            runEntity != null ? runEntity.getOrgRole() : null);
        tree = tree.withExecutionMode(ExecutionMode.STEP_BY_STEP);

        // Initialize event service with step-by-step mode
        eventService.initializeExecution(execution, true);

        // Get initial ready nodes (trigger node)
        Set<String> readyNodes = executionEngine.getInitialReadyNodes(tree);

        // Persist initial ready nodes to StateSnapshot so that StepByStepExecutor and
        // ReusableTriggerService can determine whether trigger nodes are still READY
        // and set the correct run status (WAITING_TRIGGER vs PAUSED).
        stateSnapshotService.initializeSnapshot(runId);
        if (triggerId != null) {
            // DAG-scoped: stores under real trigger, avoiding "trigger:default" sentinel
            stateSnapshotService.updateReadyNodes(runId, triggerId, 0, readyNodes);
        } else {
            stateSnapshotService.updateReadyNodes(runId, readyNodes);
        }

        // Emit step-by-step ready event
        eventService.emitStepByStepReady(execution, readyNodes, null, false);

        log.info("[V2StepByStep] Initialized: runId={}, readyNodes={}", runId, readyNodes);

        Map<String, Object> result = new HashMap<>();
        result.put("runId", runId);
        result.put("readyNodes", readyNodes);
        result.put("workflowComplete", false);
        result.put("mode", "STEP_BY_STEP");
        return result;
    }

    /**
     * Execute a single node in step-by-step mode with an explicit epoch and triggerId.
     *
     * Used for parallel epoch execution where the caller knows which epoch AND
     * which trigger this execution belongs to. The triggerId is threaded through
     * to the context manager to avoid guessing in multi-trigger shared DAGs.
     *
     * @param runId the workflow run ID
     * @param nodeId the node to execute
     * @param itemId the item ID
     * @param epoch the explicit epoch for this execution
     * @param triggerId the trigger that initiated this execution (for DAG-scoped context)
     * @return the execution result
     */
    public StepByStepExecutionResult executeNode(
            String runId,
            String nodeId,
            String itemId,
            int epoch,
            String triggerId) {

        log.info("[V2StepByStep] Executing node with explicit epoch+triggerId: runId={}, nodeId={}, itemId={}, epoch={}, triggerId={}",
                runId, nodeId, itemId, epoch, triggerId);
        return executeNodeInternal(runId, nodeId, itemId, epoch, triggerId, null);
    }

    /**
     * Execute a single node, reusing a {@link ExecutionCacheManager.LoadedExecution} that
     * the caller already loaded. Skips the inner {@code reconstructState} when a non-null
     * preload is supplied - the engine reads fresh state from {@link StateSnapshotService}
     * regardless, so the preloaded {@code tree}/{@code plan}/execution wrapper stay valid
     * even after the caller persisted the previous step.
     *
     * <p>Hot path for {@code AgentAsyncCompletionService.executeReadyNodesLoop} where the
     * caller already paid the {@code reconstructState} cost in {@code rebuildExecution} -
     * without this overload, every successor in a per-item async delivery re-pays it.
     */
    public StepByStepExecutionResult executeNode(
            String runId,
            String nodeId,
            String itemId,
            int epoch,
            String triggerId,
            ExecutionCacheManager.LoadedExecution preloaded) {

        log.info("[V2StepByStep] Executing node (preloaded={}): runId={}, nodeId={}, itemId={}, epoch={}, triggerId={}",
                preloaded != null, runId, nodeId, itemId, epoch, triggerId);
        return executeNodeInternal(runId, nodeId, itemId, epoch, triggerId, preloaded);
    }

    /**
     * Execute a single node in step-by-step mode with an explicit epoch.
     *
     * Used for parallel epoch execution where the caller knows which epoch
     * this execution belongs to.
     *
     * @param runId the workflow run ID
     * @param nodeId the node to execute
     * @param itemId the item ID
     * @param epoch the explicit epoch for this execution
     * @return the execution result
     */
    public StepByStepExecutionResult executeNode(
            String runId,
            String nodeId,
            String itemId,
            int epoch) {

        log.info("[V2StepByStep] Executing node with explicit epoch: runId={}, nodeId={}, itemId={}, epoch={}",
                runId, nodeId, itemId, epoch);
        return executeNodeInternal(runId, nodeId, itemId, epoch, null, null);
    }

    /**
     * Execute a single node in step-by-step mode.
     *
     * This is the main entry point for step-by-step execution.
     * Each call executes exactly ONE node and returns immediately.
     */
    public StepByStepExecutionResult executeNode(
            String runId,
            String nodeId,
            String itemId) {

        log.info("[V2StepByStep] Executing node: runId={}, nodeId={}, itemId={}", runId, nodeId, itemId);
        return executeNodeInternal(runId, nodeId, itemId, -1, null, null);
    }

    /**
     * Internal method for node execution. When explicitEpoch >= 0, it is used
     * directly for coordinate resolution instead of being inferred from metadata.
     * When explicitTriggerId is non-null, it is used directly for DAG-scoped
     * context resolution (avoids guessing in multi-trigger shared DAGs).
     * When {@code preloaded} is non-null, reuses its tree/execution and skips the
     * {@code loadTreeAndExecution} call (the caller already paid that cost).
     */
    private StepByStepExecutionResult executeNodeInternal(
            String runId,
            String nodeId,
            String itemId,
            int explicitEpoch,
            String explicitTriggerId,
            ExecutionCacheManager.LoadedExecution preloaded) {

        log.info("[V2StepByStep] Executing node: runId={}, nodeId={}, itemId={}, explicitEpoch={}, explicitTriggerId={}",
                runId, nodeId, itemId, explicitEpoch, explicitTriggerId);

        // Phase 1.3 (2026-04-29) - idempotency guard: when this method is called from
        // both AgentAsyncCompletionService.traverseSuccessorsPerItem (Phase 1) AND
        // executeReadyNodesLoop's global sweep, the same successor can be dispatched
        // twice. The global sweep already has alreadyDispatched filtering as a primary
        // defense; this guard is the cluster-wide / restart-safe defense - multi-instance
        // races where two orchestrator instances both call executeNode for the same
        // (runId, nodeId, epoch). When the StateSnapshot already has the key in
        // completedNodeIds or runningNodeIds, return success-skipped immediately.
        // For trigger:* and split-aware nodes (per-item subdivisible scope) the guard
        // does NOT apply: triggers may fire multiple times in a single SBS session, and
        // SplitAwareNodeExecutor handles per-item idempotency via getRoutedItemIndices.
        if (explicitEpoch >= 0 && explicitTriggerId != null && !nodeId.startsWith("trigger:")) {
            try {
                com.apimarketplace.orchestrator.domain.execution.StateSnapshot snap = stateSnapshotService.getSnapshot(runId);
                if (snap != null) {
                    var dag = snap.getDagState(explicitTriggerId);
                    if (dag != null) {
                        var es = dag.getEpochState(explicitEpoch);
                        if (es != null) {
                            // Use raw nodeId - caller already passes the normalized key in
                            // SBS callsites. Safe lookup; false positive impact is minimal
                            // (a no-op skip log line).
                            boolean alreadyComplete = es.getCompletedNodeIds().contains(nodeId);
                            // P2.2 site 5: combine JSONB running (pre-elision) + Redis
                            // running (post-elision authoritative). The slow-path
                            // claimNodeForExecution row-lock on readyNodeIds is the
                            // actual at-most-once gate - this fast-path is just an
                            // optimization that avoids the slow-path round-trip for
                            // the common already-running re-claim case.
                            boolean alreadyRunning = es.getRunningNodeIds().contains(nodeId);
                            if (!alreadyRunning && runningNodeTracker != null) {
                                Map<String, Integer> redisRunning =
                                        runningNodeTracker.getRunningCountsAcrossEpochs(runId);
                                Integer count = redisRunning.get(nodeId);
                                if (count != null && count > 0) {
                                    alreadyRunning = true;
                                }
                            }
                            // Skip ONLY for non-split contexts. Split-aware nodes track
                            // per-item state separately and may legitimately re-execute
                            // with different itemIndex values.
                            if ((alreadyComplete || alreadyRunning) && !isSplitAwareNode(es, nodeId)) {
                                log.info("[V2StepByStep] Idempotent skip: nodeId={}, runId={}, epoch={}, status=already-{}",
                                    nodeId, runId, explicitEpoch, alreadyRunning ? "running" : "completed");
                                // Build a no-op success NodeExecutionResult so callers see a benign
                                // outcome instead of double-executing. Empty readyNodes - the
                                // original execution path is responsible for its own readiness updates.
                                com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult skipResult =
                                    com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult.success(
                                        nodeId, java.util.Map.of("idempotent_skip", true), 0L);
                                return new StepByStepExecutionResult(null, skipResult, Set.of(), false);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                // Defensive - guard must never break execution
                log.debug("[V2StepByStep] Idempotency guard skipped (error reading snapshot): runId={}, error={}",
                    runId, e.getMessage());
            }
        }

        // Single-pass load: tree + execution from DB (1 reconstructState).
        // When preloaded is supplied (e.g. from AgentAsyncCompletionService.executeReadyNodesLoop
        // immediately after rebuildExecution), the caller already paid the reconstructState
        // cost and we reuse the wrapper. Engine reads fresh state from StateSnapshotService
        // regardless, so the wrapper's identity-stable parts (tree/plan/runId) stay valid.
        ExecutionCacheManager.LoadedExecution loaded = ensureInitialized(runId, preloaded);
        ExecutionTree tree = loaded.tree();
        WorkflowExecution execution = loaded.execution();

        // Parse item index
        int itemIndex = parseItemIndex(itemId);

        // Handle trigger nodes
        if (nodeId.startsWith("trigger:")) {
            StepByStepExecutionResult waitingResult = handleTriggerNode(runId, nodeId, itemId, itemIndex, tree, execution);
            if (waitingResult != null) {
                return waitingResult;
            }
        }

        // Get or create execution context for this item (nodeId for per-DAG epoch resolution)
        String contextKey = runId + ":" + itemId;
        ExecutionContext context;
        if (explicitEpoch >= 0) {
            context = contextManager.getOrCreateContextWithTriggerData(contextKey, tree, itemId, itemIndex, nodeId, explicitEpoch, explicitTriggerId);
        } else {
            context = contextManager.getOrCreateContextWithTriggerData(contextKey, tree, itemId, itemIndex, nodeId);
        }

        // Create trigger item
        TriggerItem item = TriggerItem.create(itemId, itemIndex, context.triggerData());

        // Execute the single node
        StepByStepExecutionResult result;
        try {
            result = executionEngine.executeSingleNode(
                nodeId, tree, context, execution, eventService, item
            );
        } catch (com.apimarketplace.orchestrator.execution.v2.engine.WorkflowStoppedException stopped) {
            // StopOnError node - entire workflow must stop.
            // The node's output was already persisted by executeNodeCore.
            // Return failure with empty ready nodes so the run transitions to FAILED.
            log.info("🛑 [V2StepByStep] StopOnError stopped workflow: nodeId={}, errorMessage={}",
                stopped.getNodeId(), stopped.getErrorMessage());
            return new StepByStepExecutionResult(context, stopped.getResult(), Set.of(), false);
        }

        // Cache globalData from the result context (e.g., BackEdgeState for loop iterations).
        // Step outputs are always loaded fresh from DB, but globalData is ephemeral
        // and must be preserved across calls for loop iteration tracking.
        // Exclude transient keys (like BACK_EDGE_RESET_NODES_KEY) that are single-use signals.
        if (result.context() != null && !result.context().getGlobalDataKeys().isEmpty()) {
            Map<String, Object> globalData = new java.util.HashMap<>();
            for (String key : result.context().getGlobalDataKeys()) {
                // Skip transient back-edge reset key -- it's consumed once below, not cached
                if (com.apimarketplace.orchestrator.execution.v2.engine.BackEdgeHandler.BACK_EDGE_RESET_NODES_KEY.equals(key)) {
                    continue;
                }
                result.context().getGlobalData(key).ifPresent(val -> globalData.put(key, val));
            }
            if (explicitEpoch >= 0) {
                contextManager.updateGlobalData(runId, explicitEpoch, globalData);
            } else {
                contextManager.updateGlobalData(runId, globalData);
            }
        }

        // Back-edge iteration: if the BackEdgeHandler reset loop body subgraph nodes,
        // also reset them in StateSnapshot. Without this, the next getReadyNodes() call
        // reconstructs context from DB and sees body nodes as COMPLETED (stale),
        // preventing subsequent loop iterations from executing.
        //
        // Uses removeNodesFromDag (DAG-scoped) instead of resetDagSnapshot (all-DAGs) to
        // avoid reactivating unrelated DAGs (e.g., trigger:default sentinel) which would
        // prevent the run from transitioning to WAITING_TRIGGER after execution completes.
        if (result.context() != null) {
            final String backEdgeTriggerId = context.triggerId();
            result.context().getGlobalData(
                com.apimarketplace.orchestrator.execution.v2.engine.BackEdgeHandler.BACK_EDGE_RESET_NODES_KEY
            ).ifPresent(val -> {
                @SuppressWarnings("unchecked")
                Set<String> resetNodeIds = (Set<String>) val;
                if (!resetNodeIds.isEmpty()) {
                    if (backEdgeTriggerId != null) {
                        stateSnapshotService.removeNodesFromDag(runId, backEdgeTriggerId, resetNodeIds);
                    } else {
                        // Fallback: use generic reset if triggerId not available
                        stateSnapshotService.resetDagSnapshot(runId, resetNodeIds);
                    }
                    log.info("[V2StepByStep] Reset {} loop body nodes in StateSnapshot for back-edge iteration: runId={}, triggerId={}, nodes={}",
                        resetNodeIds.size(), runId, backEdgeTriggerId, resetNodeIds);
                }
            });
        }

        // Remove pending status for executed node (if it was pending from Split spawn)
        if (result.isSuccess() && itemId != null && itemId.contains(".")) {
            stepByStepScheduler.removePending(runId, itemId, nodeId);
        }

        // Process ready nodes: persist to DB and mark split items as pending
        Set<String> baseReadyNodes = new java.util.HashSet<>();
        for (String readyNode : result.readyNodes()) {
            if (readyNode.contains("@")) {
                // Split context node: "mcp:step@0.1" -> base="mcp:step", itemId="0.1"
                String[] parts = readyNode.split("@", 2);
                baseReadyNodes.add(parts[0]);
                stepByStepScheduler.markAsPending(runId, parts[1], parts[0]);
            } else {
                baseReadyNodes.add(readyNode);
            }
        }
        // SBS mode: when a DAG completes (no ready nodes, no signal), re-add its trigger
        // to readySteps so the user can fire it again. Without this, the trigger stays in
        // completedSteps only, and the frontend hides the fire button.
        // Epochs stay open in SBS (closed only on next trigger fire), so the trigger must
        // explicitly be re-added to readySteps to signal "ready for re-fire".
        String triggerId = context.triggerId();
        int epoch = context.epoch();
        if (baseReadyNodes.isEmpty() && triggerId != null
                && result.isTerminal()
                && tree.executionMode() == ExecutionMode.STEP_BY_STEP) {
            baseReadyNodes.add(triggerId);
            log.info("[V2StepByStep] SBS: DAG complete, re-adding trigger to readySteps: runId={}, triggerId={}", runId, triggerId);
        }

        // Persist to StateSnapshot (SINGLE SOURCE OF TRUTH for page refresh)
        // Use DAG-scoped merge to correctly partition ready nodes per-trigger
        if (triggerId != null) {
            stateSnapshotService.mergeReadyNodesAfterExecution(runId, triggerId, epoch, nodeId, baseReadyNodes);
        } else {
            // Fallback: flat merge for backward compatibility (single-trigger workflows)
            stateSnapshotService.mergeReadyNodesAfterExecution(runId, nodeId, baseReadyNodes);
        }

        // Merge ready nodes from TWO sources:
        // 1. Engine's ReadyNodeCalculator (sees current execution context - always correct for this DAG)
        // 2. StateSnapshot (SINGLE SOURCE OF TRUTH across all DAGs - includes other DAGs' ready nodes)
        // The union handles both:
        //   - Multi-DAG: snapshot has other DAGs' ready nodes that the engine doesn't see
        //   - Snapshot stale read: engine has fresh ready nodes that the snapshot might not reflect yet
        Set<String> snapshotReady = stateSnapshotService.getReadyNodeIds(runId);
        Set<String> mergedReadyNodes = new java.util.HashSet<>(baseReadyNodes);
        mergedReadyNodes.addAll(snapshotReady);
        if (!baseReadyNodes.isEmpty() && snapshotReady.isEmpty()) {
            log.warn("[V2StepByStep] Snapshot readyNodeIds empty but engine found ready nodes: runId={}, engineReady={}, snapshotReady={}",
                runId, baseReadyNodes, snapshotReady);
        }
        boolean actualWorkflowComplete = mergedReadyNodes.isEmpty();

        // Pending (non-terminal): node is still in flight - AWAITING_SIGNAL (approval/timer/async
        // agent queue), RUNNING, COLLECTING, WAITING_TRIGGER, etc. The workflow is NOT complete.
        // The corresponding resume path (SignalResumeService, AgentResultListener, …) will handle
        // resumption when the underlying yield resolves.
        //
        // Historically this was gated on isAwaitingSignal() only, which missed async-queue yields
        // and allowed terminal RunStatus stamping + trigger-refire loops downstream. Gating on the
        // canonical isPending() predicate covers every non-terminal case.
        if (result.isPending()) {
            actualWorkflowComplete = false;
            log.info("[V2StepByStep] Node pending ({}), workflow NOT complete: runId={}, nodeId={}",
                result.nodeResult() != null ? result.nodeResult().status() : "unknown", runId, nodeId);
        }

        if (result.workflowComplete() && !actualWorkflowComplete) {
            log.info("[V2StepByStep] Multi-DAG: overriding workflowComplete=false because merged snapshot has ready nodes: runId={}, mergedReady={}",
                runId, mergedReadyNodes);
        }

        // Interface notification is handled centrally by StepCompletionOrchestrator.
        // No duplicate call needed here.

        // Emit step-by-step ready event with merged ready nodes from snapshot
        // Note: Snapshot is already sent via SnapshotService in emitNodeComplete()
        eventService.emitStepByStepReady(
            execution,
            mergedReadyNodes,
            nodeId,
            actualWorkflowComplete
        );

        // If workflow complete, handle status update and emit events
        // Note: For reusable triggers in AUTO mode, epoch management is handled by ReusableTriggerService
        // We only handle it here for STEP_BY_STEP mode when user manually completes the workflow
        if (actualWorkflowComplete) {
            log.info("[V2StepByStep] Workflow complete: runId={}", runId);

            // Check execution mode to determine if we should handle epoch management here
            ExecutionMode executionMode = tree.executionMode();

            // Get the reusable trigger ID (null if none)
            String reusableTriggerId = getReusableTriggerId(tree);

            if (reusableTriggerId != null && executionMode == ExecutionMode.STEP_BY_STEP) {
                // STEP_BY_STEP mode with reusable trigger: stay COMPLETED, NO auto-reset
                // User can manually rerun individual nodes or manually trigger a new epoch via UI
                final String ctxTriggerId = triggerId; // from context (per-DAG)
                workflowRunRepository.findByRunIdPublic(runId).ifPresent(runEntity -> {
                    // Set status to COMPLETED (not WAITING_TRIGGER)
                    // This keeps both nodes in their completed state, allowing individual rerun
                    RunStatus finalStatus = result.isSuccess() ? RunStatus.COMPLETED : RunStatus.FAILED;
                    runEntity.setStatus(finalStatus);
                    runEntity.setEndedAt(Instant.now());
                    runEntity.setUpdatedAt(Instant.now());

                    workflowRunRepository.save(runEntity);

                    log.info("[V2StepByStep] STEP_BY_STEP reusable trigger - workflow {}: runId={}, triggerId={}",
                        finalStatus, runId, ctxTriggerId);
                });

                // Emit workflow complete (no auto-reset in SBS mode - user controls reruns)
                eventService.emitWorkflowComplete(execution, result.isSuccess(), "Workflow completed");

            } else if (reusableTriggerId != null) {
                // AUTO mode with reusable trigger: ReusableTriggerService handles epoch management
                eventService.emitWorkflowComplete(execution, result.isSuccess(), "Workflow completed");
                log.info("[V2StepByStep] AUTO mode reusable trigger - skipping epoch management (handled by ReusableTriggerService): runId={}",
                    runId);

            } else {
                // Normal workflow (no reusable trigger): persist COMPLETED/FAILED status
                final String ctxTriggerIdFinal = triggerId; // from context (per-DAG)
                workflowRunRepository.findByRunIdPublic(runId).ifPresent(runEntity -> {
                    RunStatus finalStatus = result.isSuccess() ? RunStatus.COMPLETED : RunStatus.FAILED;
                    runEntity.setStatus(finalStatus);
                    runEntity.setEndedAt(Instant.now());
                    runEntity.setUpdatedAt(Instant.now());

                    workflowRunRepository.save(runEntity);
                    log.info("[V2StepByStep] Persisted workflow status: runId={}, status={}, triggerId={}",
                        runId, finalStatus, ctxTriggerIdFinal);
                });
                eventService.emitWorkflowComplete(execution, result.isSuccess(), "Workflow completed");
            }
        }

        log.info("[V2StepByStep] Node executed: nodeId={}, status={}, terminal={}, success={}, engineReady={}, snapshotReady={}, mergedReady={}, complete={}",
            nodeId,
            result.nodeResult() != null ? result.nodeResult().status() : "unknown",
            result.isTerminal(), result.isSuccess(),
            baseReadyNodes, snapshotReady, mergedReadyNodes, actualWorkflowComplete);

        // Return result with merged ready nodes so all callers (StepByStepExecutor,
        // ReusableTriggerService) get the correct multi-DAG ready set
        return new StepByStepExecutionResult(
            result.context(), result.nodeResult(), mergedReadyNodes, actualWorkflowComplete);
    }

    /**
     * Execute all pending split items for a node.
     *
     * When a Split node spawns N items, each downstream node gets N pending items.
     * This method executes all of them and returns a combined result.
     *
     * @param runId The workflow run ID
     * @param stepId The step to execute
     * @param pendingItemIds The pending item IDs from the split
     * @return Combined result with aggregated ready nodes
     */
    public SplitExecutionResult executeSplitItems(String runId, String stepId, Set<String> pendingItemIds) {
        log.info("[V2StepByStep] ========== SPLIT MULTI-ITEM EXECUTION ==========");
        log.info("[V2StepByStep] Executing {} pending Split items for step {}: {}",
            pendingItemIds.size(), stepId, pendingItemIds);

        java.util.List<StepByStepExecutionResult> results = new java.util.ArrayList<>();
        Set<String> finalReadyNodes = new java.util.LinkedHashSet<>();
        boolean allSuccess = true;
        boolean anyWorkflowComplete = false;

        for (String pendingItemId : pendingItemIds) {
            log.info("[V2StepByStep] Executing Split item: stepId={}, itemId={}", stepId, pendingItemId);

            // Remove pending status before execution
            stepByStepScheduler.removePending(runId, pendingItemId, stepId);

            StepByStepExecutionResult result = executeNode(runId, stepId, pendingItemId);
            results.add(result);
            finalReadyNodes.addAll(result.readyNodes());
            // A split item is a failure ONLY when its node ended in FAILED state.
            // SKIPPED items (legitimate switch/decision/option routing - this item went
            // to the OTHER branch) MUST NOT mark the cycle as failed, otherwise
            // `lastCycleResult` becomes "failed" on a perfectly valid run where every
            // item routed correctly. Matches n8n / Airflow / Temporal semantics: a
            // branching skip is data-flow control, not a failure. Cascade-skips
            // (downstream of a real FAILED) are already covered by the upstream
            // node's own isFailure() - no need to double-count here.
            //
            // Pending yields (RUNNING / AWAITING_SIGNAL) return isFailed()==false too,
            // so they don't trip this gate - matching the previous `isTerminal()` guard.
            if (result.isFailed()) {
                allSuccess = false;
            }
            if (result.workflowComplete()) {
                anyWorkflowComplete = true;
            }
        }

        log.info("[V2StepByStep] Split execution complete: executed={}, allSuccess={}, readyNodes={}",
            results.size(), allSuccess, finalReadyNodes);
        log.info("[V2StepByStep] ========== SPLIT MULTI-ITEM EXECUTION DONE ==========");

        return new SplitExecutionResult(allSuccess, results.size(), finalReadyNodes, anyWorkflowComplete);
    }

    /**
     * Result of executing multiple split items for a single step.
     */
    public record SplitExecutionResult(
        boolean allSuccess,
        int itemsExecuted,
        Set<String> readyNodes,
        boolean anyWorkflowComplete
    ) {}

    /**
     * Parse item index from itemId.
     * For parallel split items (e.g., "0.1", "0.2"), extract the last segment.
     */
    private int parseItemIndex(String itemId) {
        int itemIndex = 0;
        try {
            if (itemId != null && itemId.contains(".")) {
                String[] parts = itemId.split("\\.");
                itemIndex = Integer.parseInt(parts[parts.length - 1]);
                log.debug("[V2StepByStep] Child item detected: itemId={}, parsedIndex={}", itemId, itemIndex);
            } else {
                itemIndex = Integer.parseInt(itemId);
            }
        } catch (NumberFormatException e) {
            log.debug("[V2StepByStep] Could not parse itemIndex from itemId={}, using default 0", itemId);
        }
        return itemIndex;
    }

    /**
     * Handle trigger node execution - check for reusable triggers and waiting states.
     * Returns a waiting result if the trigger requires waiting, null otherwise.
     *
     * <p>In STEP_BY_STEP mode, clicking on a trigger IS the trigger event - execute immediately.
     * In AUTO mode, reusable triggers (manual, webhook, etc.) wait for external events.
     */
    private StepByStepExecutionResult handleTriggerNode(
            String runId, String nodeId, String itemId, int itemIndex,
            ExecutionTree tree, WorkflowExecution execution) {

        // Check if this is a reusable trigger
        if (triggerLoadingService.isReusableTrigger(tree.plan(), nodeId)) {
            String triggerType = triggerLoadingService.getTriggerType(tree.plan(), nodeId);

            // Datasource triggers load data immediately (no waiting)
            if ("datasource".equalsIgnoreCase(triggerType)) {
                log.info("[V2StepByStep] Datasource trigger detected, loading data immediately: runId={}, nodeId={}",
                    runId, nodeId);
            } else {
                // In STEP_BY_STEP mode, clicking on trigger IS the trigger event - execute immediately
                // No need to wait for external events
                if (tree.executionMode() == ExecutionMode.STEP_BY_STEP) {
                    log.info("[V2StepByStep] STEP_BY_STEP mode: executing trigger immediately: runId={}, nodeId={}",
                        runId, nodeId);
                    // Fall through to load trigger items and execute
                } else {
                    // AUTO mode: check if trigger was already received
                    var runEntityOpt = workflowRunRepository.findByRunIdPublic(runId);
                    boolean triggerAlreadyReceived = runEntityOpt
                        .map(run -> run.getStatus() == RunStatus.RUNNING ||
                                    run.getStatus() == RunStatus.WAITING_TRIGGER)
                        .orElse(false);

                    if (!triggerAlreadyReceived) {
                        log.info("[V2StepByStep] Reusable trigger detected, setting WAITING_TRIGGER: runId={}, nodeId={}",
                            runId, nodeId);

                        // Update run status to WAITING_TRIGGER
                        runEntityOpt.ifPresent(runEntity -> {
                            runEntity.setStatus(RunStatus.WAITING_TRIGGER);
                            runEntity.setUpdatedAt(Instant.now());
                            workflowRunRepository.save(runEntity);
                        });

                        // Get or create execution context for the result (nodeId for per-DAG epoch)
                        String contextKey = runId + ":" + itemId;
                        ExecutionContext context = contextManager.getOrCreateContextWithTriggerData(contextKey, tree, itemId, itemIndex, nodeId);

                        // Emit waiting event
                        eventService.emitStepByStepReady(execution, Set.of(), nodeId, false);

                        // Return waiting result
                        return StepByStepExecutionResult.waitingForTrigger(context, nodeId, "Waiting for trigger event");
                    } else {
                        log.info("[V2StepByStep] Trigger already received, continuing execution: runId={}, nodeId={}",
                            runId, nodeId);
                    }
                }
            }
        }

        // For datasource triggers or non-reusable triggers, load trigger items
        triggerLoadingService.loadTriggerItemsIfNeeded(runId, tree, itemIndex, nodeId, execution);
        return null;
    }

    /**
     * Get the current ready nodes for a workflow.
     *
     * <p>Multi-DAG aware: iterates all triggers (root nodes) and calculates
     * ready nodes per-DAG with correct per-DAG epoch/spawn coordinates,
     * then unions the results.
     */
    public Set<String> getReadyNodes(String runId, String itemId) {
        return getReadyNodes(runId, itemId, -1);
    }

    /**
     * Get ready nodes for a specific epoch.
     *
     * <p>When explicitEpoch >= 0, loads step outputs only from that epoch,
     * ensuring parallel epoch isolation (nodes completed in other epochs
     * are not visible to the ReadyNodeCalculator). Use -1 for no epoch filter.
     */
    public Set<String> getReadyNodes(String runId, String itemId, int explicitEpoch) {
        return getReadyNodes(runId, itemId, explicitEpoch, null);
    }

    /**
     * Variant that reuses a caller-supplied {@link ExecutionCacheManager.LoadedExecution}
     * to skip the inner tree fetch ({@code contextManager.getTree} → {@code reconstructState}).
     * Used by hot loops (e.g. {@code AgentAsyncCompletionService.executeReadyNodesLoop})
     * where the caller already loaded the tree.
     */
    public Set<String> getReadyNodes(String runId, String itemId, int explicitEpoch,
                                      ExecutionCacheManager.LoadedExecution preloaded) {
        ExecutionTree tree = (preloaded != null && preloaded.tree() != null)
            ? preloaded.tree()
            : contextManager.getTree(runId);

        if (tree == null) {
            log.warn("[V2StepByStep] Cannot get ready nodes - tree is NULL for runId={}", runId);
            return Set.of();
        }

        int itemIndex = parseItemIndex(itemId);
        Set<String> allReadyNodes = new java.util.LinkedHashSet<>();

        // Iterate all triggers (root nodes) and calculate ready nodes per-DAG.
        // The context build is wrapped in ReadinessContextCache (Piste 4) so a multi-trigger
        // DAG with N triggers doesn't pay N identical DB loads. TTL is short (500ms) and
        // NodeCompletionService invalidates on every node completion, so a re-traversal
        // after any state change sees fresh data. The execution path (executeNode) bypasses
        // this cache by design - idempotency on re-execution must reflect DB truth.
        for (var root : tree.getRootNodes()) {
            String triggerId = root.getNodeId();
            String contextKey = runId + ":" + itemId + ":dag:" + triggerId;
            if (explicitEpoch >= 0) {
                contextKey += ":epoch:" + explicitEpoch;
            }
            // Cache the per-DAG context only for readiness - execution always re-reads DB.
            ExecutionContext context = loadContextForReadiness(
                runId, itemId, itemIndex, triggerId, explicitEpoch, tree, contextKey);
            Set<String> dagReadyNodes = executionEngine.calculateReadyNodes(context, tree);
            allReadyNodes.addAll(dagReadyNodes);
        }

        // SBS mode: re-add completed triggers so they remain available for re-fire.
        // In SBS, epochs stay open (closed only on next trigger fire), so a trigger that
        // has already fired should still appear in readySteps for the user to fire again.
        // The ReadyNodeCalculator skips completed triggers, so we explicitly re-add them.
        if (tree.isStepByStepMode()) {
            var snapshot = stateSnapshotService.getSnapshot(runId);
            for (var root : tree.getRootNodes()) {
                String tId = root.getNodeId();
                var epochState = snapshot.getEpochState(tId);
                if (epochState.getCompletedNodeIds().contains(tId) && !allReadyNodes.contains(tId)) {
                    allReadyNodes.add(tId);
                    log.info("[V2StepByStep] SBS: re-adding completed trigger to readyNodes: runId={}, triggerId={}", runId, tId);
                }
            }
        }

        log.debug("[V2StepByStep] Per-DAG ready node calculation: runId={}, triggers={}, readyNodes={}, epoch={}",
            runId, tree.getRootNodes().size(), allReadyNodes, explicitEpoch);

        return allReadyNodes;
    }

    /**
     * Load (or fetch from {@link ReadinessContextCache}) the per-DAG execution context for
     * a readiness traversal. Key = {@code runId | epoch | spawn=0 | itemIndex | triggerId}.
     *
     * <p>Spawn=0 is hardcoded because readiness is computed on the canonical (non-rerun)
     * timeline. Rerun-aware contexts would not be cacheable across triggers anyway since
     * spawn is per-node, not per-DAG.
     */
    private ExecutionContext loadContextForReadiness(String runId, String itemId, int itemIndex,
                                                     String triggerId, int explicitEpoch,
                                                     ExecutionTree tree, String contextKey) {
        java.util.function.Supplier<ExecutionContext> loader = () -> {
            if (explicitEpoch >= 0) {
                return contextManager.getOrCreateContextWithTriggerData(
                    contextKey, tree, itemId, itemIndex, triggerId, explicitEpoch);
            }
            return contextManager.getOrCreateContextWithTriggerData(
                contextKey, tree, itemId, itemIndex, triggerId);
        };
        if (readinessCache == null) {
            return loader.get();
        }
        int epochForKey = explicitEpoch >= 0 ? explicitEpoch : 0;
        String cacheKey = ReadinessContextCache.key(runId, epochForKey, 0, itemIndex, triggerId);
        return readinessCache.getOrLoad(cacheKey, loader);
    }

    /**
     * Ensure the execution tree and workflow execution are initialized.
     * Loads both in a single DB pass via {@link ExecutionCacheManager#loadTreeAndExecution(String)}.
     *
     * This enables step-by-step execution to work after:
     * - Page reload
     * - Server restart
     * - Long delays between steps
     *
     * @return LoadedExecution containing both tree and execution (reuse in caller to avoid redundant loads)
     * @throws IllegalStateException if run or plan not found in database
     */
    private ExecutionCacheManager.LoadedExecution ensureInitialized(String runId) {
        return ensureInitialized(runId, null);
    }

    /**
     * Variant that reuses a caller-supplied {@code LoadedExecution} when present, skipping
     * the inner {@code loadTreeAndExecution} (the heavy {@code reconstructState}).
     *
     * <p>When {@code preloaded != null}, event-service and snapshot initialisations are
     * SKIPPED - the caller's first call (with {@code preloaded=null}) already triggered
     * them. Re-firing on every node would flood the snapshot publisher with initial
     * placeholder events, priming {@code lastPublishedSeq} and causing real node-completion
     * snapshots to be skipped (the May 25 SSE-goes-silent bug).
     */
    private ExecutionCacheManager.LoadedExecution ensureInitialized(
            String runId, ExecutionCacheManager.LoadedExecution preloaded) {
        ExecutionCacheManager.LoadedExecution loaded;
        if (preloaded != null && preloaded.tree() != null && preloaded.execution() != null) {
            loaded = preloaded;
            log.debug("[V2StepByStep] ensureInitialized: reusing caller-preloaded execution for runId={}", runId);
        } else {
            loaded = executionCacheManager.loadTreeAndExecution(runId);
            if (loaded == null || loaded.execution() == null) {
                throw new IllegalStateException("Workflow execution not found: " + runId);
            }
            if (loaded.tree() == null) {
                throw new IllegalStateException("Failed to build execution tree for runId: " + runId);
            }
        }

        // Only initialize streaming/snapshot on the FIRST call (when no preloaded).
        // With preloaded, the caller already triggered init on the first executeNode.
        // Re-emitting initializeExecution on every node floods the snapshot publisher
        // and primes lastPublishedSeq with stale values, causing real snapshots to skip.
        if (preloaded == null) {
            eventService.initializeExecution(loaded.execution(), true);
            stateSnapshotService.initializeSnapshot(runId);
        }

        log.debug("[V2StepByStep] Ensured tree and execution are loaded: runId={}", runId);
        return loaded;
    }

    /**
     * Check if a workflow run has been initialized for step-by-step execution.
     */
    public boolean isInitialized(String runId) {
        return contextManager.hasTree(runId);
    }

    // =========================================================================
    // CORE NODE EXECUTION DATA EXTRACTION
    // =========================================================================

    /**
     * Data extracted from a V2 execution result for core node (decision/switch) responses.
     * Used to build backward-compatible HTTP responses in StepByStepController.
     */
    public record CoreExecutionData(
        String selectedBranch,
        Set<String> skippedBranches,
        List<?> evaluations
    ) {}

    /**
     * Extract decision-specific data from a V2 execution result.
     *
     * After {@link #executeNode} runs a core node (decision/switch), this method
     * extracts the selected branch target, skipped branch targets, and evaluation details
     * from the execution tree and result.
     *
     * @param runId The workflow run ID
     * @param nodeId The core node ID (e.g. "core:check_status")
     * @param result The result from executeNode()
     * @return CoreExecutionData with destination node keys and evaluations
     */
    public CoreExecutionData extractCoreExecutionData(
            String runId, String nodeId, StepByStepExecutionResult result) {

        if (result == null || result.nodeResult() == null) {
            return new CoreExecutionData(null, Set.of(), List.of());
        }

        // Load tree from DB (same as executeNode does via ensureInitialized)
        ExecutionCacheManager.LoadedExecution loaded = executionCacheManager.loadTreeAndExecution(runId);
        if (loaded == null || loaded.tree() == null) {
            log.warn("[V2StepByStep] Cannot extract core data - tree not found: runId={}", runId);
            return new CoreExecutionData(null, Set.of(), List.of());
        }

        ExecutionNode node = executionEngine.findNodeFromAllRoots(loaded.tree(), nodeId);
        if (node == null) {
            log.warn("[V2StepByStep] Cannot extract core data - node not found: nodeId={}", nodeId);
            return new CoreExecutionData(null, Set.of(), List.of());
        }

        // Get destination node keys from tree structure using polymorphic methods
        List<ExecutionNode> nextNodes = node.getNextNodes(result.nodeResult());
        List<ExecutionNode> skippedNodes = node.getSkippedChildNodes(result.nodeResult());

        String selectedBranch = nextNodes.isEmpty() ? null : nextNodes.get(0).getNodeId();
        Set<String> skippedBranchIds = skippedNodes.stream()
            .map(ExecutionNode::getNodeId)
            .collect(Collectors.toSet());

        // Extract evaluations from the node result output (set by DecisionNode/SwitchNode)
        Object evaluations = result.nodeResult().output().get("evaluations");
        List<?> evaluationList = evaluations instanceof List ? (List<?>) evaluations : List.of();

        log.info("[V2StepByStep] Core execution data: nodeId={}, selectedBranch={}, skipped={}, evaluations={}",
            nodeId, selectedBranch, skippedBranchIds, evaluationList.size());

        return new CoreExecutionData(selectedBranch, skippedBranchIds, evaluationList);
    }

    /**
     * Clean up resources for a workflow.
     */
    public void cleanup(String runId) {
        log.info("[V2StepByStep] Cleaning up: runId={}", runId);

        contextManager.cleanup(runId);
        stepByStepScheduler.cleanup(runId);
        eventService.cleanupExecution(runId);
    }

    /**
     * Get the first reusable trigger ID from the execution tree.
     * Returns null if no reusable trigger exists.
     */
    private String getReusableTriggerId(ExecutionTree tree) {
        if (tree == null || tree.plan() == null || tree.plan().getTriggers() == null) {
            return null;
        }

        // Check each trigger in the plan
        for (var trigger : tree.plan().getTriggers()) {
            String nodeId = trigger.getNormalizedKey();
            if (triggerLoadingService.isReusableTrigger(tree.plan(), nodeId)) {
                return nodeId;
            }
        }
        return null;
    }

    /**
     * Heuristic: is this node split-aware (per-item subdivisible)? Today we recognize
     * split-aware nodes via the presence of a {@code SplitState} for the node in
     * {@code EpochState.splits}. Belt-and-suspenders default: when in doubt, return
     * true (skip the idempotency guard) so we never short-circuit a legitimate per-item
     * re-execution.
     */
    private boolean isSplitAwareNode(com.apimarketplace.orchestrator.domain.execution.EpochState es, String nodeId) {
        if (es == null) return true;
        try {
            return es.getSplits() != null && es.getSplits().containsKey(nodeId);
        } catch (Exception e) {
            return true;
        }
    }
}
