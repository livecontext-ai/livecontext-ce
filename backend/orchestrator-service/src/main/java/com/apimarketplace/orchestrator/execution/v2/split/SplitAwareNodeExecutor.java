package com.apimarketplace.orchestrator.execution.v2.split;

import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.execution.v2.async.SplitCoalesceTracker;
import com.apimarketplace.orchestrator.execution.v2.constants.ExecutionMetadataKeys;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.TriggerItem;
import com.apimarketplace.orchestrator.execution.v2.nodes.ExecutionNode;
import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;
import com.apimarketplace.orchestrator.execution.v2.services.NodeCompletionService;
import com.apimarketplace.orchestrator.execution.v2.services.EdgeStatusEmitter;
import com.apimarketplace.orchestrator.execution.v2.services.V2ExecutionEventService;
import com.apimarketplace.orchestrator.persistence.WorkflowStepDataRepository;
import com.apimarketplace.orchestrator.services.state.StateSnapshotService;
import com.apimarketplace.orchestrator.services.streaming.SnapshotService;
import com.apimarketplace.orchestrator.services.streaming.state.RunningNodeTracker;
import com.apimarketplace.orchestrator.utils.EdgeRefParser;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;

/**
 * Handles execution of the FIRST node directly after a split.
 *
 * <p>This executor ONLY applies to the immediate successor of a split node.
 * After executing that node N times, it launches N parallel graph traversals
 * for the remaining nodes, each with its own item context.
 *
 * <p>Flow:
 * <pre>
 * split (3 items)
 *     │
 *     ▼
 * getEmailDetail ───► SplitAwareNodeExecutor executes 3 times
 *     │               Each result persisted with item_index 0, 1, 2
 *     │
 *     │   Graph "splits" - N parallel traversals launched:
 *     │
 *     ├── [item 0] processResult ─► ... ─► merge
 *     ├── [item 1] processResult ─► ... ─► merge
 *     └── [item 2] processResult ─► ... ─► merge
 *                                          │
 *                                          ▼
 *                                     sendSummary (1x)
 * </pre>
 *
 * <p>Each parallel traversal has:
 * - Its own item_index (sub-item index: 0, 1, 2)
 * - The previous node's result in stepOutputs for template resolution
 * - Access to {{mcp:getemaildetail.output.xxx}} for its specific item
 *
 * <p>The Merge node then waits for all N sub-items (scoped to workflow item)
 * before executing once and closing the split scope.
 */
@Service
public class SplitAwareNodeExecutor {

    private static final Logger logger = LoggerFactory.getLogger(SplitAwareNodeExecutor.class);

    private final SplitContextManager contextManager;
    private final NodeCompletionService nodeCompletionService;
    private final EdgeStatusEmitter edgeStatusEmitter;
    private final SnapshotService snapshotService;
    private final WorkflowStepDataRepository stepDataRepository;
    private final StateSnapshotService stateSnapshotService;
    private final ExecutorService executorService;

    /**
     * Optional: handles AWAITING_SIGNAL + ASYNC_RUNNING dispatch (WS events + agent queue enqueue).
     * Null when agent queue is not active or in test contexts.
     */
    private V2ExecutionEventService eventService;

    /**
     * Optional: barrier used to coalesce per-item async agent completions back into
     * a single downstream traversal. Null when no async path is wired (tests / sync mode).
     */
    private SplitCoalesceTracker splitCoalesceTracker;

    /**
     * Optional: Redis-backed running count tracker. Used to set the correct running
     * count for split-async nodes (N items instead of 1).
     */
    private RunningNodeTracker runningNodeTracker;

    /**
     * Optional: skip-cascade service used by {@link #persistSkippedItemRecords} to
     * propagate per-item SKIPPED status to downstream descendants when an apply_X
     * node has 0 routed items (all items were classified to a different branch).
     * Without this, ReadyNodeCalculator never enqueues record_X (predecessor has 0
     * COMPLETED items) → no step_data rows for descendants → frontend inspector
     * shows them as never-ran and downstream merge/aggregate nodes stay PENDING.
     * Null in unit tests that don't wire the full skip subsystem.
     */
    private com.apimarketplace.orchestrator.execution.v2.services.V2SkipPropagationService skipPropagationService;

    @Autowired
    public SplitAwareNodeExecutor(
            SplitContextManager contextManager,
            NodeCompletionService nodeCompletionService,
            EdgeStatusEmitter edgeStatusEmitter,
            SnapshotService snapshotService,
            WorkflowStepDataRepository stepDataRepository,
            StateSnapshotService stateSnapshotService) {
        this.contextManager = contextManager;
        this.nodeCompletionService = nodeCompletionService;
        this.edgeStatusEmitter = edgeStatusEmitter;
        this.snapshotService = snapshotService;
        this.stepDataRepository = stepDataRepository;
        this.stateSnapshotService = stateSnapshotService;
        this.executorService = Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors() * 2
        );
    }

    /**
     * Constructor for testing.
     */
    public SplitAwareNodeExecutor(
            SplitContextManager contextManager,
            NodeCompletionService nodeCompletionService,
            EdgeStatusEmitter edgeStatusEmitter,
            SnapshotService snapshotService,
            WorkflowStepDataRepository stepDataRepository,
            StateSnapshotService stateSnapshotService,
            ExecutorService executorService) {
        this.contextManager = contextManager;
        this.nodeCompletionService = nodeCompletionService;
        this.edgeStatusEmitter = edgeStatusEmitter;
        this.snapshotService = snapshotService;
        this.stepDataRepository = stepDataRepository;
        this.stateSnapshotService = stateSnapshotService;
        this.executorService = executorService;
    }

    @Autowired(required = false)
    public void setEventService(V2ExecutionEventService eventService) {
        this.eventService = eventService;
    }

    @Autowired(required = false)
    public void setSplitCoalesceTracker(SplitCoalesceTracker splitCoalesceTracker) {
        this.splitCoalesceTracker = splitCoalesceTracker;
    }

    @Autowired(required = false)
    public void setRunningNodeTracker(RunningNodeTracker runningNodeTracker) {
        this.runningNodeTracker = runningNodeTracker;
    }

    @Autowired(required = false)
    public void setSkipPropagationService(
            com.apimarketplace.orchestrator.execution.v2.services.V2SkipPropagationService skipPropagationService) {
        this.skipPropagationService = skipPropagationService;
    }

    /**
     * Generic per-node execution policy application for the split fan-out: the
     * policy (retry / backoff / continue-on-failure) applies PER ITEM - each item
     * retries independently on its own worker thread (the backoff blocks only that
     * worker, never sibling items), and the engine-level wrapper never retries the
     * fan-out summary (split_already_persisted guard in NodePolicyRunner). Default
     * instance keeps plain unit-test construction working; Spring overrides via setter.
     */
    private com.apimarketplace.orchestrator.execution.v2.engine.NodePolicyRunner nodePolicyRunner =
            new com.apimarketplace.orchestrator.execution.v2.engine.NodePolicyRunner();

    @Autowired(required = false)
    public void setNodePolicyRunner(
            com.apimarketplace.orchestrator.execution.v2.engine.NodePolicyRunner nodePolicyRunner) {
        if (nodePolicyRunner != null) {
            this.nodePolicyRunner = nodePolicyRunner;
        }
    }

    /**
     * Optional: durable per-item output store used to backfill {@link #injectPredecessorPerItemOutputs}
     * when the in-memory {@link SplitContext#resultsByNode} is missing a predecessor slot for the current
     * item (cross-pod async/signal resume where {@code restoreContext} rebuilt items only, a restart that
     * emptied the cache, or a read before an async barrier sealed). Mirrors the durable fallback
     * {@code SplitAggregateHandler.resolvePerItemResults} already uses for the {@code core:aggregate} node,
     * generalised to the branch-rejoin merge / decision / switch / fork / loop per-item read path. Null in
     * unit tests that don't exercise the fallback → warm-path behavior is byte-identical.
     */
    private com.apimarketplace.orchestrator.services.StepOutputService stepOutputService;

    @Autowired(required = false)
    public void setStepOutputService(
            com.apimarketplace.orchestrator.services.StepOutputService stepOutputService) {
        this.stepOutputService = stepOutputService;
    }

    /**
     * Per-item skip reason for {@code nodePolicy.executeOnce}: in a split context the
     * node executes for item index 0 only; every other item is marked SKIPPED with
     * this reason through the SAME per-item skip pipeline as branch-unrouted items
     * (step_data rows + NodeCounts + edge counts), so downstream merge/aggregate
     * readiness stays coherent.
     */
    static final String EXECUTE_ONCE_SKIP_REASON =
        "executeOnce policy: node executes for the first split item only";

    /**
     * Executes ONE node body under the node's per-attempt timeout policy
     * ({@code nodePolicy.timeoutMs} via {@code NodePolicyRunner.callWithTimeout}).
     * This is the LEAF wrapping point for every non-fan-out execution path of this
     * executor (no split context / control nodes / chained nodes inside split
     * traversals); the per-item fan-out wraps its own invoker the same way. The
     * fan-out COORDINATION and its summary are intentionally never bounded -
     * the timeout applies per node body, per attempt.
     *
     * <p>Default policy (timeoutMs=0) is a same-thread passthrough: results AND
     * exceptions are byte-identical to calling {@code node.execute(ctx)} directly.
     */
    private NodeExecutionResult executeNodeBody(ExecutionNode node, ExecutionContext ctx) {
        com.apimarketplace.orchestrator.domain.workflow.NodePolicy policy =
            nodePolicyRunner.resolve(ctx != null ? ctx.plan() : null, node.getNodeId());
        if (!policy.hasTimeout()) {
            return node.execute(ctx);
        }
        try {
            return nodePolicyRunner.callWithTimeout(policy, node.getNodeId(), () -> node.execute(ctx));
        } catch (RuntimeException | Error e) {
            throw e; // node.execute only throws unchecked - preserve legacy propagation
        } catch (Exception e) {
            // Defensive: callWithTimeout's checked rethrow path (InterruptedException
            // on the waiting thread). Surface unchanged semantics to callers that
            // historically only saw unchecked exceptions from node.execute.
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    /**
     * Optional: run-scoped state_snapshot CAS coalescer. When a session is
     * opened around the split fan-out, the N per-item completions of one run
     * coalesce into fewer state_snapshot flushes (and same-path ASSIGNs compose
     * via re-mutate-on-flush). Null in unit tests / when the bean is disabled.
     */
    private com.apimarketplace.orchestrator.services.state.patch.RunCoalescingService runCoalescingService;

    @Autowired(required = false)
    public void setRunCoalescingService(
            com.apimarketplace.orchestrator.services.state.patch.RunCoalescingService runCoalescingService) {
        this.runCoalescingService = runCoalescingService;
    }

    /**
     * Feature flag gating whether a coalescing session is OPENED around the
     * split fan-out. Default OFF - when OFF, behavior is exactly today's (no
     * session opened, every per-item completion takes the direct CAS /
     * pessimistic path). Toggle at runtime via {@code ORCH_OPTIM_COALESCE_SPLIT}.
     */
    @org.springframework.beans.factory.annotation.Value("${orchestrator.optim.coalesce-split:false}")
    private boolean coalesceSplitEnabled;

    /**
     * Functional interface for traversing successor nodes.
     * The engine provides this callback to allow parallel traversals.
     */
    @FunctionalInterface
    public interface SuccessorTraverser {
        /**
         * Traverse to a successor node with the given context.
         *
         * @param successor the successor node to traverse to
         * @param context the execution context (with item's result)
         * @param subItemIndex the sub-item index within the split
         * @return the resulting context after traversal
         */
        ExecutionContext traverse(ExecutionNode successor, ExecutionContext context, int subItemIndex);
    }

    /**
     * Executes a node, handling split context if present.
     * Legacy method without successor traversal - successors handled by engine.
     */
    public NodeExecutionResult execute(
            ExecutionNode node,
            ExecutionContext context,
            String runId,
            Map<String, ExecutionNode> nodeMap) {
        return execute(node, context, runId, nodeMap, null, null, 0, null);
    }

    /**
     * Executes a node, handling split context if present.
     *
     * <p>If this is the first node after a split:
     * <ol>
     *   <li>Execute the node N times (once per split item)</li>
     *   <li>Persist each result individually with item_index</li>
     *   <li>Launch N parallel traversals for successors</li>
     *   <li>Return with metadata to prevent engine from re-traversing</li>
     * </ol>
     *
     * @param node the node to execute
     * @param context the execution context
     * @param runId the workflow run ID
     * @param nodeMap map of all nodes
     * @param execution the workflow execution (for persistence)
     * @param triggerItem the trigger item
     * @param workflowItemIndex the workflow item index (from trigger)
     * @param successorTraverser callback to traverse successors (null = engine handles)
     * @return the execution result
     */
    public NodeExecutionResult execute(
            ExecutionNode node,
            ExecutionContext context,
            String runId,
            Map<String, ExecutionNode> nodeMap,
            WorkflowExecution execution,
            TriggerItem triggerItem,
            int workflowItemIndex,
            SuccessorTraverser successorTraverser) {

        String nodeId = node.getNodeId();

        // Skip split-aware execution for control flow nodes
        // Exceptions that need per-item execution in split scope:
        // - Decision/Switch: evaluate conditions per item
        // - Merge: branch-rejoin merges (not caught by SplitMergeHandler) execute per item
        // - Loop: evaluate condition and iterate independently per item
        // - Fork: dispatch branches independently per item
        boolean forcePerItem = false;
        if (shouldSkipSplitHandling(node)) {
            boolean isRoutingNode = node.isDecisionNode() || node.isSwitchNode();
            // A merge node reaching here means SplitMergeHandler.isSplitMerge() returned false
            // (branch-rejoin, not split-aggregation). It should execute per-item.
            boolean isBranchRejoinMerge = node.isMergeNode();
            // Loop nodes must execute per-item: each item has its own iteration cycle
            boolean isLoopInSplit = node.isLoopNode();
            // Fork nodes must execute per-item: each item dispatches to all branches
            boolean isForkInSplit = node.isForkNode();
            if (!isRoutingNode && !isBranchRejoinMerge && !isLoopInSplit && !isForkInSplit) {
                logger.debug("[SplitAware] Skipping split handling for node type: nodeId={}, type={}",
                    nodeId, node.getType());
                return executeNodeBody(node, context);
            }

            // Check if node is actually in a split scope
            Optional<SplitContext> routingSplitCtx = findSplitContextWithFallback(
                runId, nodeId, workflowItemIndex, nodeMap, node);
            if (routingSplitCtx.isEmpty()) {
                // Not in split scope - normal execution
                logger.debug("[SplitAware] Node not in split scope, executing normally: nodeId={}, type={}",
                    nodeId, node.getType());
                return executeNodeBody(node, context);
            }

            // Node identified as needing per-item execution - bypass isDirectSuccessor check below
            forcePerItem = true;
            String reason = isRoutingNode ? "routing node"
                : isBranchRejoinMerge ? "branch-rejoin merge"
                : isLoopInSplit ? "loop in split"
                : "fork in split";
            logger.info("[SplitAware] {} in split scope, will execute per-item: nodeId={}, type={}",
                reason, nodeId, node.getType());
        }

        // Check for active split context (scoped to workflow item)
        Optional<SplitContext> splitContextOpt = findSplitContextWithFallback(
            runId, nodeId, workflowItemIndex, nodeMap, node);

        if (splitContextOpt.isEmpty()) {
            // No split context - normal execution. NOTE: nodePolicy.executeOnce is a
            // NO-OP here by design - outside a split the node executes once anyway
            // (context.itemIndex() is the WORKFLOW item index, not a split item index).
            logger.debug("[SplitAware] No split context, executing normally: nodeId={}", nodeId);
            return executeNodeBody(node, context);
        }

        // Check if this is a DIRECT successor of the split (first node after split)
        SplitContext splitContext = splitContextOpt.get();
        boolean isDirectSuccessor = isDirectSplitSuccessor(node, splitContext, nodeMap);

        // In auto mode: only direct successors execute N times (indirect ones are already in split traversals)
        // In step-by-step mode: ALL nodes in split scope must execute N times (no split traversals)
        // Exception: nodes with forcePerItem (routing, merge, loop, fork) always execute per-item
        boolean isStepByStepMode = (successorTraverser == null);

        if (!isDirectSuccessor && !isStepByStepMode && !forcePerItem) {
            // Auto mode + not direct successor + not forced: already in a split traversal, execute once
            //
            // executeOnce policy on a CHAINED node inside per-item split traversals:
            // each traversal carries its own split item index (enrichContextWithItem
            // stamped it via withItemIndex). The node executes for item 0 only; every
            // other item's traversal terminates here with a cascading SKIPPED (same
            // terminal-skip semantic as "no items to process"), so descendants get
            // per-item SKIPPED rows and downstream merges still converge (SKIPPED is
            // a resolved state for merge readiness).
            com.apimarketplace.orchestrator.domain.workflow.NodePolicy chainedPolicy =
                nodePolicyRunner.resolve(context.plan(), nodeId);
            if (chainedPolicy.executeOnce() && context.itemIndex() != 0) {
                logger.info("[SplitAware] executeOnce: suppressing chained execution for split item {}: nodeId={}",
                    context.itemIndex(), nodeId);
                return NodeExecutionResult.skippedWithCascade(nodeId, EXECUTE_ONCE_SKIP_REASON);
            }
            NodeExecutionResult result = executeNodeBody(node, context);
            // Store this per-item result in the SplitContext so a downstream aggregate (or any
            // SplitContext reader) sees N distinct values for chained nodes - not just the
            // immediate split successor. Without this, SplitAggregateHandler.evaluateConfiguredFields
            // reads only one node's per-item slots and falls back to a stale single value for the
            // others, yielding "same value N times" in the aggregate output.
            recordChainedDownstreamResult(runId, splitContext, workflowItemIndex, nodeId, context.itemIndex(), result);
            return result;
        }

        String reason = isDirectSuccessor ? "direct successor"
            : forcePerItem ? "forced per-item (control flow in split)"
            : "step-by-step mode";
        logger.info("[SplitAware] In split scope ({}), executing for {} items: nodeId={}, split={}",
            reason, splitContext.itemCount(), nodeId, splitContext.splitNodeId());

        // Execute for all items and launch parallel traversals
        return executeForAllItemsAndTraverse(
            node, context, splitContext, runId,
            execution, triggerItem, workflowItemIndex, successorTraverser);
    }

    /**
     * Checks if this node is a DIRECT successor of the split node.
     * Returns true only for the first node after split, not subsequent nodes.
     */
    private boolean isDirectSplitSuccessor(
            ExecutionNode node,
            SplitContext splitContext,
            Map<String, ExecutionNode> nodeMap) {

        // Extract the original split node ID (without item index suffix)
        String splitKey = splitContext.splitNodeId();
        // Key format: "core:processmessages:0" -> extract "core:processmessages"
        String splitNodeId = splitKey.contains(":")
            ? splitKey.substring(0, splitKey.lastIndexOf(':'))
            : splitKey;

        // Check if split is a direct predecessor (getPredecessorIds now in ExecutionNode interface)
        List<String> predecessors = node.getPredecessorIds();
        return predecessors.contains(splitNodeId) || predecessors.contains(splitKey);
    }

    /**
     * Executes the node for all split items and launches parallel traversals.
     *
     * IMPORTANT: Persistence happens AFTER parallel execution completes, on the main thread,
     * to ensure proper Spring transaction context and avoid connection leaks.
     *
     * For nodes after Classify/Decision, only items that were routed to this node are executed.
     */
    private NodeExecutionResult executeForAllItemsAndTraverse(
            ExecutionNode node,
            ExecutionContext context,
            SplitContext splitContext,
            String runId,
            WorkflowExecution execution,
            TriggerItem triggerItem,
            int workflowItemIndex,
            SuccessorTraverser successorTraverser) {

        String nodeId = node.getNodeId();
        List<Object> items = splitContext.items();
        int itemCount = items.size();

        if (itemCount == 0) {
            logger.info("[SplitAware] Empty split, marking node as SKIPPED: nodeId={}", nodeId);
            return NodeExecutionResult.skippedWithCascade(nodeId, "No items in split");
        }

        // Determine which items should be executed
        // For nodes after Classify/Decision, only execute items that were routed to this node
        Set<Integer> routedItemIndices = getRoutedItemIndices(node, runId, itemCount, context.epoch(), splitContext.splitNodeId());

        // nodePolicy.executeOnce - generic per-node policy: in a split, execute for
        // split item index 0 ONLY; every other routed item is suppressed and persisted
        // SKIPPED with an explicit executeOnce reason through the SAME pipeline as
        // branch-unrouted items (see the persist step after the execution loop).
        // STRICT index-0 rule: if branch routing sent item 0 to another node, this
        // node executes for NO item (deterministic; consistent with the chained-path
        // guard where parallel traversals can't coordinate a "lowest routed index").
        // Loop iterations are NOT filtered - this is a split-item filter only.
        com.apimarketplace.orchestrator.domain.workflow.NodePolicy fanOutPolicy =
            nodePolicyRunner.resolve(context.plan(), nodeId);
        final Set<Integer> routedBeforeExecuteOnce = routedItemIndices;
        Set<Integer> executeOnceSuppressed = Set.of();
        if (fanOutPolicy.executeOnce() && !routedItemIndices.isEmpty()) {
            if (routedItemIndices.contains(0)) {
                Set<Integer> suppressed = new HashSet<>(routedItemIndices);
                suppressed.remove(0);
                executeOnceSuppressed = suppressed;
                routedItemIndices = Set.of(0);
            } else {
                executeOnceSuppressed = routedItemIndices;
                routedItemIndices = Set.of();
            }
            logger.info("[SplitAware] executeOnce: restricting split execution to item 0: nodeId={}, routed={}, suppressed={}",
                nodeId, routedBeforeExecuteOnce, executeOnceSuppressed);
        }

        if (routedItemIndices.isEmpty()) {
            // Empty branch of a Classify/Decision split: no items routed to this node.
            // Must mark the node as SKIPPED instead of returning a bare empty result,
            // otherwise the engine skips emitNodeComplete (SPLIT_ALREADY_PERSISTED path)
            // and running=1 is never cleared → ReadyNodeCalculator loops forever.
            // Per-item SKIPPED records (items 1..N-1) mirror the mixed-routing case
            // at L517-518 for UI statusCounts consistency; item 0 is handled by the
            // engine's node-level emitNodeComplete on the returned skipped result.
            // executeOnce edge case: the policy suppressed every routed item because
            // split item 0 was routed to another branch (strict index-0 rule). The node
            // executes for NO item; reuse this terminal-skip path with an honest reason.
            boolean emptyBecauseExecuteOnce = !executeOnceSuppressed.isEmpty();
            String perItemSkipReason = emptyBecauseExecuteOnce
                ? EXECUTE_ONCE_SKIP_REASON
                : "Not routed to this branch";
            logger.info("[SplitAware] No items to execute ({}), marking node as SKIPPED: nodeId={}, totalItems={}",
                emptyBecauseExecuteOnce ? "executeOnce: item 0 routed elsewhere" : "no items routed to this branch",
                nodeId, itemCount);
            boolean explicitChoiceBranchTarget = isExplicitChoiceBranchTarget(node);
            if (execution != null && nodeCompletionService != null && explicitChoiceBranchTarget) {
                persistSkippedItemRecords(
                    execution, node, Set.of(0), itemCount, context.epoch(), context.triggerId(),
                    false, true, perItemSkipReason);
                cascadeExternallyHandledSkippedItems(
                    execution, node, Set.of(0), context.epoch(), context.triggerId());
            }
            // Bare skipped (no engine-level cascade) on purpose. For explicit
            // choice-branch targets, the block above materializes per-item
            // descendant skips under split scope, but it does not set the global
            // CASCADE_SKIP_TO_SUCCESSORS flag on this result.
            // routedItemIndices is
            // derived from a DB lookup of predecessor step_data (see
            // getTransitiveRoutedItemIndices), so a transient persistence failure on
            // the predecessor (e.g. V261 NOT NULL transactional abort) returns an
            // empty set and lands us here EVEN WHEN items were really processed.
            // Cascading from this state mass-marks every descendant SKIPPED in
            // NodeCounts/EpochState - explosive counter inflation observed in prod
            // run 656a4aed-… epochs 7-10 (workflow_epochs showed parse_headers
            // SKIPPED=36 across four epochs because get_content step_data writes
            // failed and the cascade fanned the empty-routing signal downstream).
            //
            // The original data-corruption bug (SKIPPED apply_tech envelope wrongly
            // treated as COMPLETED by ReadyNodeCalculator) is already closed by the
            // consumer-side ExecutionContext.isSkipped gate; this producer-side
            // cascade was a defense-in-depth addition that proved to amplify
            // unrelated persistence regressions and is intentionally reverted.
            // Empty-by-construction splits (itemCount == 0) keep skippedWithCascade
            // - those don't depend on predecessor step_data and are unambiguous.
            String skipReason = emptyBecauseExecuteOnce
                ? EXECUTE_ONCE_SKIP_REASON + " (split item 0 was routed to another branch)"
                : "No items routed to this branch";
            Map<String, Object> skippedMetadata = Map.of(
                "skip_reason", skipReason,
                ExecutionMetadataKeys.DEFER_SKIPPED_AGGREGATE_EVENT, Boolean.TRUE
            );
            return new NodeExecutionResult(
                nodeId,
                NodeStatus.SKIPPED,
                skippedMetadata,
                Optional.of(skipReason),
                skippedMetadata,
                0
            );
        }

        logger.info("[SplitAware] Executing for {} items (routed): nodeId={}, totalItems={}, routedIndices={}",
            routedItemIndices.size(), nodeId, itemCount, routedItemIndices);

        long startTime = System.currentTimeMillis();
        boolean canPersist = execution != null && nodeCompletionService != null;
        boolean canTraverseSuccessors = successorTraverser != null;

        // Durable per-item backfill source for the read path (injectPredecessorPerItemOutputs).
        // Loaded ONCE here (reused for every item) for any node that re-resolves a PRIOR per-item
        // predecessor and would therefore silently collapse to a single "item 0" value when the
        // in-memory SplitContext.resultsByNode is missing a slot (cross-pod async/signal resume,
        // restart, or read-before-async-seal). Two independent shapes qualify:
        //   1. a cross-item consumer - branch-rejoin merge, aggregate, decision, switch, fork or
        //      loop that folds/re-resolves every predecessor per item; AND
        //   2. a PLAIN per-item successor (mcp/agent/code/transform/interface) that reads a
        //      NON-adjacent predecessor - i.e. one whose in-split predecessor is not the split node
        //      itself (readsNonSplitPredecessor). A DIRECT split successor is excluded: its per-item
        //      values come from {{item}} / the split's current_item, never a prior per-item node, so
        //      its legitimately-empty map needs no backfill and must stay query-free (loading durable
        //      for every split's FIRST node would tax the hot path for nothing).
        // Shape 2 closes the residual proven on a restart resume: a plain transform reading a
        // non-adjacent predecessor across an approval collapsed all rows to item 0's value once the
        // resultsByNode singleton was wiped (JVM restart / cross-pod).
        //
        // WARM-PATH SKIP (mirrors SplitMergeHandler.mergeDurablePerItem): when the in-memory
        // resultsByNode is already dense for every routed item, memory can serve every slot, so the
        // durable read would be pure waste (a healthy single-pod run must stay query-free). The
        // collapse cases this fix targets all leave resultsByNode empty or sparse: restoreContext
        // rebuilds items only (cross-pod/restart), and an unsealed async barrier leaves gaps -
        // inMemorySlotsComplete returns false for exactly those, so the durable read fires only when
        // it can actually help. Memory always wins over the backfill (injectPredecessorPerItemOutputs
        // only fills nodes ABSENT from memory), so an over-eager load can never corrupt a good value.
        // Null-tolerant: unset stepOutputService (unit tests) → no backfill, behavior unchanged.
        final Map<String, Map<Integer, Object>> durableEpochOutputs =
            (stepOutputService != null
                    && !inMemorySlotsComplete(splitContext, routedItemIndices)
                    && (isCrossItemPerItemConsumer(node)
                        || readsNonSplitPredecessor(node, splitContext.splitNodeId())))
                ? loadDurableEpochOutputs(context)
                : null;

        // Execute for each routed item in parallel (execution only, no persistence)
        List<CompletableFuture<ItemExecutionResult>> futures = new ArrayList<>();

        for (int i = 0; i < itemCount; i++) {
            // Skip items that were not routed to this node
            if (!routedItemIndices.contains(i)) {
                logger.debug("[SplitAware] Skipping item {} - not routed to nodeId={}", i, nodeId);
                continue;
            }

            final int subItemIndex = i;
            final Object item = items.get(subItemIndex);
            // Capture orgId before crossing into ForkJoinPool.commonPool - those
            // threads don't carry the request ThreadLocal, so without
            // runWithOrgScope the V261 NOT NULL constraint on storage.storage
            // (and downstream workflow_step_data) fires when a SKIPPED_NODE
            // INSERT runs from the split worker.
            final String orgIdForWorker = context.organizationId();

            // Use ForkJoinPool.commonPool() to avoid deadlock when nested parallel executions occur.
            // The common pool uses work-stealing, so blocked threads can still execute tasks.
            CompletableFuture<ItemExecutionResult> future = CompletableFuture.supplyAsync(() -> {
                ItemExecutionResult[] resultHolder = new ItemExecutionResult[1];
                com.apimarketplace.common.web.TenantResolver.runWithOrgScope(orgIdForWorker, () -> {
                    // Per-item failed attempts collected by the policy runner - persisted on
                    // the main thread (transactional context) before the final item result.
                    List<NodeExecutionResult> failedAttempts = new ArrayList<>();
                    try {
                        // Create context enriched with item data
                        ExecutionContext itemContext = enrichContextWithItem(
                            context, item, subItemIndex, items, splitContext, durableEpochOutputs);

                        // Execute the node under its per-node policy - applied PER ITEM.
                        // Default policy is a pure passthrough (node.execute, exceptions
                        // propagate to the catch below exactly as before). With retries,
                        // each attempt re-executes THIS item only; the backoff sleeps on
                        // this worker thread and never delays sibling items.
                        // timeoutMs bounds each ATTEMPT of THIS item only (callWithTimeout
                        // wraps the node body inside the retry loop) - the fan-out
                        // coordination and its summary are never bounded.
                        com.apimarketplace.orchestrator.domain.workflow.NodePolicy itemPolicy =
                            nodePolicyRunner.resolve(itemContext.plan(), nodeId);
                        NodeExecutionResult result = nodePolicyRunner.run(itemPolicy, nodeId,
                            () -> nodePolicyRunner.callWithTimeout(itemPolicy, nodeId,
                                () -> node.execute(itemContext)),
                            (annotatedFailure, attempt, maxAttempts) -> failedAttempts.add(
                                addItemIndexToResult(annotatedFailure, subItemIndex, itemCount)));

                        // Add item_index and split_item_count to output for persistence.
                        // split_item_count is critical for ReadyNodeCalculator to detect split context
                        // when loading Decision/Switch results from DB (enables all-branch traversal).
                        NodeExecutionResult resultWithIndex = addItemIndexToResult(result, subItemIndex, itemCount);

                        // Return result with context for later persistence and traversal
                        resultHolder[0] = new ItemExecutionResult(subItemIndex, resultWithIndex, null, itemContext, failedAttempts);
                    } catch (Exception e) {
                        logger.error("[SplitAware] Item execution failed: nodeId={}, subItem={}, error={}",
                            nodeId, subItemIndex, e.getMessage(), e);
                        resultHolder[0] = new ItemExecutionResult(subItemIndex, null, e, null, failedAttempts);
                    }
                });
                return resultHolder[0];
            }, ForkJoinPool.commonPool());

            futures.add(future);
        }

        // Wait for all executions to complete with timeout to prevent indefinite blocking
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .get(30, TimeUnit.MINUTES);
        } catch (Exception e) {
            logger.error("[SplitAware] Error waiting for item executions: {}", e.getMessage(), e);
            futures.forEach(f -> f.cancel(true));
            throw new RuntimeException("Split item execution failed or timed out", e);
        }

        // Pre-pass: gather completed ItemExecutionResults and count async-running items.
        // The barrier MUST be registered before any item is dispatched to the worker queue
        // - a fast worker could otherwise deliver a result to AgentAsyncCompletionService
        // before this method has had a chance to register the expected total.
        List<ItemExecutionResult> completedItemResults = new ArrayList<>(futures.size());
        int asyncItemCount = 0;
        for (CompletableFuture<ItemExecutionResult> future : futures) {
            try {
                ItemExecutionResult itemResult = future.get();
                completedItemResults.add(itemResult);
                if (itemResult != null && itemResult.result != null && itemResult.result.isAsyncRunning()) {
                    asyncItemCount++;
                }
            } catch (Exception e) {
                logger.error("[SplitAware] Error retrieving item future: {}", e.getMessage(), e);
                completedItemResults.add(null);
            }
        }

        boolean asyncDeferred = asyncItemCount > 0 && splitCoalesceTracker != null && execution != null;
        if (asyncDeferred) {
            // Register the barrier BEFORE dispatching any item to the queue.
            int barrierEpoch = context.epoch();
            splitCoalesceTracker.register(runId, nodeId, barrierEpoch, asyncItemCount);
            logger.info("[SplitAware] Registered async coalesce barrier: nodeId={}, runId={}, epoch={}, totalAsync={}",
                nodeId, runId, barrierEpoch, asyncItemCount);

            // Set the running count to the actual number of async items under
            // the per-epoch Redis key (P2.3.1). emitNodeStart already marked
            // the node with count=1 under (runId, barrierEpoch). For split-async
            // execution with N items, each completion calls markCompleted (-1).
            // Without this override, the count starts at 1 instead of N, so the
            // first completion drops it to 0 and the remaining N-1 completions
            // never update the count - the frontend never sees real-time progress
            // (running stays at 1, then jumps to completed).
            if (runningNodeTracker != null) {
                runningNodeTracker.setRunningCount(runId, barrierEpoch, nodeId, asyncItemCount);
                logger.info("[SplitAware] Set running count to {}: nodeId={}, runId={}, epoch={}",
                        asyncItemCount, nodeId, runId, barrierEpoch);
                // Send snapshot immediately so the frontend sees the correct count before
                // any async result arrives. Without this, the count stays at 1 until the
                // first completion triggers a snapshot.
                snapshotService.sendSnapshotImmediate(runId);
            }
        }

        // Process items: persist+traverse sync ones, dispatch async ones, accumulate errors.
        List<NodeExecutionResult> results = new ArrayList<>();
        // Parallel list of absolute split-item indices for each entry in `results`.
        // storePerItemResultsInContext uses this to rebuild a sparse list aligned with
        // splitContext.itemCount() - without it, downstream nodes that look up a
        // predecessor result by absolute item index (injectPredecessorPerItemOutputs)
        // get an out-of-bounds miss whenever a switch/decision routes only a SUBSET
        // of items to this node (e.g. asset_type=stock routes items [2,3] of a 4-item
        // split, the dense list had size 2 and indices 2/3 fell off the end).
        List<Integer> resultAbsoluteIndices = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        boolean hasFailure = false;
        int awaitingSignalCount = 0;

        // Audit bug #2 activation - open a coalescing session around the per-item
        // completion region so the N per-item markNodeCompleted writes of THIS run
        // coalesce into fewer state_snapshot CAS flushes (and same-path ASSIGNs
        // compose via re-mutate-on-flush). Gated behind orchestrator.optim.
        // coalesce-split (default OFF) → when OFF, no session is opened and every
        // per-item completion takes today's direct CAS / pessimistic path.
        // try/finally guarantees closeCoalescing even on throw (the flush+evict).
        boolean coalescingOpened = false;
        if (coalesceSplitEnabled && runCoalescingService != null && runId != null) {
            coalescingOpened = runCoalescingService.openCoalescing(runId) != null;
            if (coalescingOpened) {
                logger.debug("[SplitAware] Opened coalescing session for split fan-out: runId={}, nodeId={}",
                    runId, nodeId);
            }
        }
        long duration;
        try {
        for (ItemExecutionResult itemResult : completedItemResults) {
            try {
                if (itemResult == null) {
                    hasFailure = true;
                    errors.add("Error getting result: future returned null");
                    continue;
                }
                if (itemResult.error != null) {
                    hasFailure = true;
                    errors.add("Item " + itemResult.index + ": " + itemResult.error.getMessage());
                    continue;
                }
                if (itemResult.result == null) {
                    continue;
                }

                // No silent attempts: each NON-final failed attempt of this item is surfaced
                // through the ATTEMPT-AWARE pipeline (emitNodeFailedAttempt): WS step event
                // (+ per-item step_data row in non-loop contexts), annotated with
                // policy_attempt / policy_max_attempts - but WITHOUT StateSnapshot / edge /
                // workflow_epochs mutation and WITHOUT billing. Only the final per-item
                // result below goes through persistItemResult (full pipeline: counts, edges,
                // billing). 2026-06-10 audit items 2/3/4 - full contract incl. the loop
                // WS-only asymmetry on NodeCompletionService.emitNodeFailedAttempt.
                if (canPersist && itemResult.context != null
                        && itemResult.failedAttempts != null && !itemResult.failedAttempts.isEmpty()) {
                    for (NodeExecutionResult failedAttempt : itemResult.failedAttempts) {
                        nodeCompletionService.emitNodeFailedAttempt(
                            execution, node, failedAttempt, triggerItem, itemResult.index, itemResult.context);
                    }
                }

                // Async-running items: dispatch to the worker queue and return - completion
                // will arrive later via AgentAsyncCompletionService, which uses the coalesce
                // barrier to drive successors only after all items have completed.
                if (itemResult.result.isAsyncRunning()) {
                    if (eventService != null && execution != null) {
                        logger.info("[SplitAware] Item yielded ASYNC_RUNNING, dispatching: nodeId={}, subItem={}, correlationId={}",
                            nodeId, itemResult.index,
                            itemResult.result.metadata() != null
                                ? itemResult.result.metadata().get(NodeExecutionResult.ASYNC_CORRELATION_ID)
                                : "n/a");
                        eventService.emitNodeAsyncRunning(
                            execution, node, itemResult.result, triggerItem, itemResult.index, itemResult.context);
                    } else {
                        logger.warn("[SplitAware] Item yielded ASYNC_RUNNING but no eventService/execution available - agent task will not be enqueued: nodeId={}, subItem={}",
                            nodeId, itemResult.index);
                    }
                    // Do NOT persistItemResult / traverse: the barrier handles both later.
                    continue;
                }

                // AWAITING_SIGNAL items must NOT go through completeStep - convertToStepResult
                // would persist them as FAILED. Dispatch the WS/queue event and skip the
                // persist+traverse pipeline; the signal subsystem will resume them later.
                // Check BEFORE adding to results to keep the results list clean for createSummaryResult.
                if (itemResult.result.isAwaitingSignal()) {
                    awaitingSignalCount++;
                    if (eventService != null && execution != null) {
                        logger.info("[SplitAware] Item yielded AWAITING_SIGNAL, dispatching: nodeId={}, subItem={}",
                            nodeId, itemResult.index);
                        eventService.emitNodeAwaitingSignal(
                            execution, node, itemResult.result, triggerItem, itemResult.index, itemResult.context);
                    }
                    continue;
                }

                results.add(itemResult.result);
                resultAbsoluteIndices.add(itemResult.index);
                if (itemResult.result.isFailure()) {
                    hasFailure = true;
                    errors.add("Item " + itemResult.index + ": " +
                        itemResult.result.errorMessage().orElse("Unknown error"));
                }

                // Terminal sync result - persist + traverse on the main thread (transactional context).
                if (canPersist && itemResult.context != null) {
                    persistItemResult(execution, node, itemResult.result, triggerItem, itemResult.index, itemResult.context);
                }

                // continueOnFailure policy: this item's final result is FAILED (recorded as
                // such above) but its policy asks traversal to proceed - same reuse of the
                // SKIPPED-with-error continuation semantic as the engine's traverseTree.
                boolean continueOnFailureItem = itemResult.result.isFailure()
                    && ExecutionMetadataKeys.isContinueOnFailure(itemResult.result.metadata());

                if (canTraverseSuccessors && (itemResult.result.isSuccess() || continueOnFailureItem)
                        && itemResult.context != null) {
                    ExecutionContext contextWithResult = itemResult.context.withResult(nodeId, itemResult.result);
                    List<ExecutionNode> successors = node.getNextNodes(itemResult.result);
                    if (successors.isEmpty() && continueOnFailureItem) {
                        // getNextNodes filters successors on failure - restore them, mirroring
                        // UnifiedExecutionEngine's continue-on-failure continuation (step 6b).
                        successors = node.getSuccessors();
                    }
                    for (ExecutionNode successor : successors) {
                        logger.debug("[SplitAware] Launching traversal for successor: nodeId={}, subItem={}",
                            successor.getNodeId(), itemResult.index);
                        successorTraverser.traverse(successor, contextWithResult, itemResult.index);
                    }
                }
            } catch (Exception e) {
                hasFailure = true;
                errors.add("Error processing item: " + e.getMessage());
            }
        }

        duration = System.currentTimeMillis() - startTime;

        // Persist SKIPPED records for items that were NOT routed to this node.
        // This provides correct statusCounts (e.g., high_value: success=2, skipped=3).
        // IMPORTANT: Only persist SKIPPED step data records - do NOT mark the node as SKIPPED
        // in the StateSnapshot, because the node DID execute for other items (routed ones).
        // Two disjoint per-item skip sets, each with its honest reason:
        //  - branch-unrouted items (complement of the PRE-executeOnce routed set);
        //  - executeOnce-suppressed items (routed here, suppressed by the policy).
        if (canPersist && routedBeforeExecuteOnce.size() < itemCount) {
            persistSkippedItemRecords(execution, node, routedBeforeExecuteOnce, itemCount,
                context.epoch(), context.triggerId());
        }
        if (canPersist && !executeOnceSuppressed.isEmpty()) {
            Set<Integer> executedOrUnrouted = new HashSet<>();
            for (int i = 0; i < itemCount; i++) {
                if (!executeOnceSuppressed.contains(i)) {
                    executedOrUnrouted.add(i);
                }
            }
            persistSkippedItemRecords(execution, node, executedOrUnrouted, itemCount,
                context.epoch(), context.triggerId(), true, false, EXECUTE_ONCE_SKIP_REASON);
        }
        } finally {
            // Drain + evict the coalescing session: the remaining queued
            // mutations flush on close, futures complete before we return.
            if (coalescingOpened) {
                runCoalescingService.closeCoalescing(runId);
            }
        }

        // If ALL items yielded AWAITING_SIGNAL (e.g., UserApproval in split context),
        // return an AWAITING_SIGNAL summary so the engine yields instead of falsely
        // marking the node as COMPLETED. Without this, the auto mode loop would re-execute
        // the node on the next iteration, creating duplicate signals.
        if (awaitingSignalCount > 0 && results.isEmpty() && !hasFailure) {
            logger.info("[SplitAware] All {} items yielded AWAITING_SIGNAL: nodeId={}, runId={}",
                awaitingSignalCount, nodeId, runId);
            // Extract signal type from the first AWAITING_SIGNAL result
            String signalType = null;
            for (ItemExecutionResult ir : completedItemResults) {
                if (ir != null && ir.result != null && ir.result.isAwaitingSignal()
                        && ir.result.metadata() != null) {
                    Object st = ir.result.metadata().get("signal_type");
                    if (st != null) { signalType = st.toString(); break; }
                }
            }
            Map<String, Object> output = new HashMap<>();
            output.put("split_item_count", awaitingSignalCount);
            output.put("split_awaiting_signal", true);
            if (signalType != null) {
                output.put("signal_type", signalType);
            }
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("split_execution", true);
            metadata.put("split_item_count", awaitingSignalCount);
            metadata.put(ExecutionMetadataKeys.SPLIT_ALREADY_PERSISTED, true);
            if (signalType != null) {
                metadata.put("signal_type", signalType);
            }
            return new NodeExecutionResult(
                nodeId,
                NodeStatus.AWAITING_SIGNAL,
                output,
                Optional.empty(),
                metadata,
                System.currentTimeMillis() - startTime
            );
        }

        // If any items were dispatched to the async worker queue, yield with an
        // asyncRunning summary so the engine doesn't traverse successors. The coalesce
        // barrier in AgentAsyncCompletionService will store per-item outputs and drive
        // the successor traversal once all items have arrived.
        if (asyncDeferred) {
            logger.info("[SplitAware] Deferred async split: nodeId={}, runId={}, totalAsync={}, syncResults={}, syncFailures={}",
                nodeId, runId, asyncItemCount, results.size(), errors.size());
            // Persist the per-item outputs from any non-async items immediately so they're
            // visible to subsequent nodes - only the async ones get stored later.
            storePerItemResultsInContext(runId, splitContext, workflowItemIndex, nodeId,
                results, resultAbsoluteIndices);
            return createDeferredAsyncSummary(nodeId, asyncItemCount, results.size(), errors);
        }

        // CRITICAL: Store per-item outputs in SplitContext for subsequent nodes
        // This enables step-by-step mode template resolution (e.g., {{mcp:getemaildetail.output.snippet}})
        storePerItemResultsInContext(runId, splitContext, workflowItemIndex, nodeId,
            results, resultAbsoluteIndices);

        logger.info("[SplitAware] Completed {} items: nodeId={}, failures={}, duration={}ms, successorsHandled={}",
            itemCount, nodeId, errors.size(), duration, canTraverseSuccessors);

        // Return result with metadata
        // If we handled successors, set flag to prevent engine from re-traversing
        return createSummaryResult(nodeId, results, errors, hasFailure, duration, canTraverseSuccessors);
    }

    /**
     * Build the asyncRunning summary returned when one or more split items were
     * dispatched to the worker queue. The engine yields on this result; the per-item
     * completions arrive via {@code AgentAsyncCompletionService} and the
     * {@link SplitCoalesceTracker} drives the successor traversal once all items
     * have arrived.
     *
     * <p>The result carries {@code SPLIT_ALREADY_PERSISTED} so the engine does not
     * re-emit per-item events, plus a {@code split_async_pending} marker for
     * observability/tests.</p>
     */
    private NodeExecutionResult createDeferredAsyncSummary(
            String nodeId, int asyncItemCount, int syncItemCount, List<String> errors) {
        Map<String, Object> output = new HashMap<>();
        output.put("split_async_pending", asyncItemCount);
        output.put("split_sync_persisted", syncItemCount);
        if (!errors.isEmpty()) {
            output.put("split_errors", errors);
        }
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("split_execution", true);
        metadata.put("split_async_pending", asyncItemCount);
        metadata.put(ExecutionMetadataKeys.SPLIT_ALREADY_PERSISTED, true);
        // Marker keys mirror NodeExecutionResult.asyncRunning so isAsyncRunning() returns true.
        metadata.put(NodeExecutionResult.ASYNC_RUNNING_MARKER, Boolean.TRUE);
        return new NodeExecutionResult(
            nodeId,
            NodeStatus.RUNNING,
            output,
            Optional.empty(),
            metadata,
            0L
        );
    }

    /**
     * Stores a single per-item result for a chained downstream node executing inside a
     * parallel split traversal (auto mode). Mirrors {@link #storePerItemResultsInContext}
     * which is called for the immediate split successor (batched after N items execute);
     * here each item lands its own slot one at a time via
     * {@link SplitContextManager#storeItemResult}.
     *
     * <p>Skips storing on null/failed/no-output results - those cases are best handled
     * downstream (skip propagation, error cascade) rather than poisoning the aggregate
     * with empty slots.
     */
    private void recordChainedDownstreamResult(
            String runId,
            SplitContext splitContext,
            int workflowItemIndex,
            String nodeId,
            int itemIndex,
            NodeExecutionResult result) {
        if (result == null || result.output() == null || !result.isSuccess()) {
            // Skip storing on null/failed/no-output results: those are handled by the
            // skip-cascade machinery (V2SkipPropagationService.cascadeFailureToSuccessors)
            // and the per-item routing filter (getRoutedItemIndices via predecessor-completion
            // queries). Slotting an empty/error here would leak it into every aggregate field
            // for items that did succeed. AWAITING_SIGNAL / async in-flight results also fall
            // here and are correct: the resume path re-traverses the chained node and lands a
            // COMPLETED, which records the slot then.
            return;
        }
        // Use the EXACT scoped key already resolved on the SplitContext (e.g. "core:inner_loop:0/s1")
        // so nested splits with sibling /sN keys at the same workflowItemIndex don't collide via
        // findScopedKey's first-match scan.
        contextManager.storeItemResultByScopedKey(
            runId,
            splitContext.splitNodeId(),
            nodeId,
            itemIndex,
            splitContext.itemCount(),
            result.output()
        );
    }

    /**
     * Stores per-item results in SplitContext for subsequent nodes.
     *
     * <p>In step-by-step mode, subsequent nodes need access to this node's per-item outputs
     * for template resolution. This method stores them in SplitContext.resultsByNode.
     *
     * <p><b>Index alignment:</b> downstream lookups (
     * {@link #injectPredecessorPerItemOutputs}, template resolution under split scope)
     * address results by ABSOLUTE split item index in {@code [0, itemCount)}. The
     * {@code results} list, however, is dense over routed items only - when a switch
     * or decision routes a subset (e.g. asset_type=stock routes items [2,3] of a
     * 4-item split), the dense list has size 2 and a request for absolute index 2
     * falls off the end. To honor the contract documented on
     * {@link SplitContext#withResults} ("one per item"), pad the stored list to
     * {@code splitContext.itemCount()} with {@code null} at non-routed slots and the
     * actual output at each routed absolute index. The {@code resultAbsoluteIndices}
     * parameter carries the per-result absolute index parallel to {@code results}.
     */
    private void storePerItemResultsInContext(
            String runId,
            SplitContext splitContext,
            int workflowItemIndex,
            String nodeId,
            List<NodeExecutionResult> results,
            List<Integer> resultAbsoluteIndices) {

        if (results.isEmpty()) {
            return;
        }

        // Build a sparse list of size itemCount, with each routed result placed
        // at its absolute split index. Skipped slots stay null.
        int itemCount = splitContext.itemCount();
        // Defensive bound: don't shrink below the largest known index, in case
        // the caller passed a result with an index beyond splitContext.itemCount()
        // (shouldn't happen, but matches SplitContext.withResultAtIndex semantic).
        int maxAbs = -1;
        for (Integer idx : resultAbsoluteIndices) {
            if (idx != null && idx > maxAbs) maxAbs = idx;
        }
        int targetSize = Math.max(itemCount, maxAbs + 1);
        List<Object> outputs = new ArrayList<>(targetSize);
        for (int i = 0; i < targetSize; i++) {
            outputs.add(null);
        }
        for (int k = 0; k < results.size(); k++) {
            NodeExecutionResult result = results.get(k);
            Integer absIdx = k < resultAbsoluteIndices.size() ? resultAbsoluteIndices.get(k) : null;
            if (absIdx == null || absIdx < 0 || absIdx >= targetSize) {
                continue;
            }
            if (result != null && result.output() != null) {
                outputs.set(absIdx, result.output());
            }
        }

        // Store in SplitContext
        contextManager.storeResults(
            runId,
            extractBaseSplitKey(splitContext.splitNodeId()),
            workflowItemIndex,
            nodeId,
            outputs
        );

        logger.debug("[SplitAware] Stored {} per-item results (sparse over {} slots) for node: nodeId={}, splitContext={}",
            results.size(), outputs.size(), nodeId, splitContext.splitNodeId());
    }

    /**
     * Enriches the execution context with item data for split iteration.
     * This is the method that creates the RUNTIME CONTEXT layer for split nodes.
     *
     * <p>current_item and current_index are injected HERE, not persisted to the database.
     * They exist only in the in-memory ExecutionContext for the duration of each parallel
     * branch execution. Body nodes can access them via templates like:
     * <ul>
     *   <li>{@code {{core:<split_label>.output.current_item}}} - the full item object</li>
     *   <li>{@code {{core:<split_label>.output.current_item.field}}} - a specific field</li>
     *   <li>{@code {{core:<split_label>.output.current_index}}} - 0-based index</li>
     * </ul>
     *
     * <p>This method injects the current item into BOTH:
     * <ul>
     *   <li>globalData - for direct access via {{item}}, {{index}}</li>
     *   <li>stepOutputs - for template access via {{core:split.output.current_item}}
     *       (delegated to {@link #injectCurrentItemIntoStepOutputs})</li>
     * </ul>
     *
     * <p>CRITICAL for step-by-step mode: Also injects predecessor's per-item outputs
     * from SplitContext.resultsByNode, enabling templates like
     * {{mcp:getemaildetail.output.snippet}} to resolve to the correct item's output.
     *
     * @see com.apimarketplace.orchestrator.services.persistence.schema.SplitOutputSchemaMapper
     *      for the persisted layer (items, item_count, split_id, spawn_reason, terminated)
     *
     * <p><b>TODO (DEFERRED - nested split composite item_index, 2026-05-08):</b>
     * {@code .withItemIndex(subItemIndex)} on line below overwrites the OUTER split's
     * itemIndex when a node sits inside a nested split (outer × inner). The DB column
     * {@code item_index} stores only one integer, so storage rows for nested-split bodies
     * lose the outer index. {@code RunContextService.loadRunContextForItem} can therefore
     * not distinguish outer-item-K-inner-item-J from outer-item-J-inner-item-K. A composite
     * key (e.g., "{outer}/{inner}" string column or a separate parent_item_index column)
     * is required for full nested-split correctness. Tracked alongside the Daily Email
     * Digest split fix.
     */
    ExecutionContext enrichContextWithItem(  // package-private for tests
            ExecutionContext context,
            Object item,
            int subItemIndex,
            List<Object> items,
            SplitContext splitContext) {
        // Back-compat overload (used by unit tests): no durable backfill source.
        return enrichContextWithItem(context, item, subItemIndex, items, splitContext, null);
    }

    ExecutionContext enrichContextWithItem(  // package-private for tests
            ExecutionContext context,
            Object item,
            int subItemIndex,
            List<Object> items,
            SplitContext splitContext,
            Map<String, Map<Integer, Object>> durableEpochOutputs) {

        String scopedKey = splitContext.splitNodeId(); // e.g., "core:processmessages:0"

        // Extract base split node ID (remove workflow item index suffix)
        // "core:processmessages:0" -> "core:processmessages"
        String baseSplitKey = extractBaseSplitKey(scopedKey);

        // Update item identity so downstream nodes (signals, persistence) use the correct sub-item index
        // Store item data in global context for direct template access
        ExecutionContext enriched = context
            .withItemIndex(subItemIndex)
            .withGlobalData("item", item)
            .withGlobalData("index", subItemIndex)
            .withGlobalData("items", items)
            .withGlobalData(scopedKey + ".current_item", item)
            .withGlobalData(scopedKey + ".current_index", subItemIndex)
            .withGlobalData("current_split_id", scopedKey);

        // CRITICAL: Also inject current_item into the split node's stepOutputs
        // This enables templates like {{core:processmessages.output.current_item.id}}
        enriched = injectCurrentItemIntoStepOutputs(enriched, baseSplitKey, item, subItemIndex);

        // CRITICAL for step-by-step mode: Inject predecessor's per-item outputs
        // This enables templates like {{mcp:getemaildetail.output.snippet}} to resolve
        // to the correct item's output (not just the last item's summary)
        enriched = injectPredecessorPerItemOutputs(enriched, splitContext, subItemIndex,
            durableEpochOutputs, baseSplitKey);

        // CRITICAL for split×loop topology: Re-apply BackEdgeHandler loop core overrides
        // AFTER injectPredecessorPerItemOutputs.
        //
        // The split's resultsByNode snapshot of a loop core is FROZEN at LoopNode's
        // initial execution (iteration=0). injectPredecessorPerItemOutputs above
        // overwrites the loop core entry with that stale snapshot. Without this
        // re-apply, downstream nodes inside split×loop scope (transform, mcp, code,
        // agent) see iteration=0 regardless of the actual iteration counter - even
        // though V2StepByStepContextManager.applyLoopCoreOutputOverrides correctly
        // stamped the live counter into the outer context. Audit 2026-05-08 (3 Opus
        // agents) traced this clobber as the root cause of the split×loop iteration
        // drift bug.
        enriched = reapplyLoopCoreOverrides(enriched);

        logger.debug("[SplitAware] Enriched context: subItem={}, scopedKey={}, baseKey={}, predecessorResults={}",
            subItemIndex, scopedKey, baseSplitKey, splitContext.getAllResults().keySet());

        return enriched;
    }

    /**
     * Re-applies BackEdgeHandler's loop core output overrides into stepOutputs after
     * predecessor per-item injection. The override map (keyed under
     * {@link com.apimarketplace.orchestrator.execution.v2.engine.BackEdgeHandler#LOOP_CORE_OUTPUT_OVERRIDES_KEY}
     * in globalData) carries the LIVE iteration counter for each loop core; the
     * per-item snapshot in {@link SplitContext#resultsByNode} is frozen at iteration=0.
     *
     * <p>This re-apply is generic - works for transform/mcp/code/agent/any node that
     * resolves SpEL templates against {@code context.stepOutputs()}.
     */
    @SuppressWarnings("unchecked")
    ExecutionContext reapplyLoopCoreOverrides(ExecutionContext context) {  // package-private for tests
        // Guard for unit-test contexts that may not have ExecutionState wired.
        if (context == null || context.state() == null) {
            return context;
        }
        Object raw = context.getGlobalData(
            com.apimarketplace.orchestrator.execution.v2.engine.BackEdgeHandler.LOOP_CORE_OUTPUT_OVERRIDES_KEY)
            .orElse(null);
        if (!(raw instanceof Map<?, ?> overrides) || overrides.isEmpty()) {
            return context;
        }
        ExecutionContext out = context;
        for (Map.Entry<?, ?> e : overrides.entrySet()) {
            if (e.getKey() instanceof String key && e.getValue() instanceof Map<?, ?> val) {
                // withStepOutput now writes BOTH the full key (core:my_loop) AND the bare
                // alias (my_loop) via StepOutputsWriter - no need for a manual companion
                // call. The split×loop iteration counter stays consistent across all
                // template access styles ($input.my_loop / {{core:my_loop.iteration}}).
                out = out.withStepOutput(key, (Map<String, Object>) val);
            }
        }
        return out;
    }

    /**
     * Injects per-item outputs from predecessor nodes stored in SplitContext.
     *
     * <p>In step-by-step mode, when node B runs after node A in a split:
     * - A's per-item results were stored in SplitContext.resultsByNode
     * - B needs A's result for the same item index for template resolution
     * - This method injects A's per-item output into stepOutputs
     */
    @SuppressWarnings("unchecked")
    private ExecutionContext injectPredecessorPerItemOutputs(
            ExecutionContext context,
            SplitContext splitContext,
            int subItemIndex,
            Map<String, Map<Integer, Object>> durableEpochOutputs,
            String baseSplitKey) {

        Map<String, List<Object>> allResults = splitContext.getAllResults();
        boolean hasDurable = durableEpochOutputs != null && !durableEpochOutputs.isEmpty();
        if (allResults.isEmpty() && !hasDurable) {
            return context;
        }

        Map<String, Object> newOutputs = new HashMap<>(context.getAllStepOutputs());
        boolean modified = false;
        // Node keys served from the in-memory SplitContext for THIS item. The durable backfill
        // below only fills what the warm cache is missing, so a live in-memory value always wins.
        Set<String> injectedFromMemory = new HashSet<>();

        for (Map.Entry<String, List<Object>> entry : allResults.entrySet()) {
            String nodeId = entry.getKey();
            List<Object> results = entry.getValue();

            if (results != null && subItemIndex < results.size()) {
                Object itemResult = results.get(subItemIndex);
                if (itemResult != null) {
                    injectPerItemStepOutput(newOutputs, nodeId, itemResult);
                    injectedFromMemory.add(nodeId);
                    modified = true;

                    logger.debug("[SplitAware] Injected predecessor result: nodeId={}, subItem={}, outputKeys={}",
                        nodeId, subItemIndex, itemResult instanceof Map ? ((Map<?, ?>) itemResult).keySet() : "non-map");
                }
            }
        }

        // Durable backfill: for any node with a persisted per-item output for THIS item whose
        // in-memory slot was missing/null (cross-pod async/signal resume where restoreContext
        // rebuilt items only, a restart that emptied the cache, or a read before the async barrier
        // sealed), inject the durable value so the reader resolves per item instead of collapsing
        // to the base context's single "item 0" value. Skips the split node's own key (its
        // current_item was already injected and must not be clobbered) and any node already served
        // from memory. Same durable source as SplitAggregateHandler.resolvePerItemResults - now
        // generalised to the branch-rejoin merge / decision / switch / fork / loop read path.
        if (hasDurable) {
            for (Map.Entry<String, Map<Integer, Object>> durEntry : durableEpochOutputs.entrySet()) {
                String nodeId = durEntry.getKey();
                if (injectedFromMemory.contains(nodeId) || nodeId.equals(baseSplitKey)) {
                    continue;
                }
                Map<Integer, Object> byItem = durEntry.getValue();
                Object itemResult = byItem != null ? byItem.get(subItemIndex) : null;
                if (itemResult != null) {
                    injectPerItemStepOutput(newOutputs, nodeId, itemResult);
                    modified = true;
                    logger.info("[SplitAware] Recovered per-item predecessor output from the durable store "
                            + "(in-memory SplitContext slot absent): nodeId={}, subItem={}", nodeId, subItemIndex);
                }
            }
        }

        if (!modified) {
            return context;
        }

        // PR15 - preserve organizationId / organizationRole when rebuilding the
        // context for split per-item injection. Without this thread, every
        // split branch silently demotes to personal scope.
        return new ExecutionContext(
            context.runId(),
            context.workflowRunId(),
            context.tenantId(),
            context.itemId(),
            context.itemIndex(),
            context.triggerId(),
            context.epoch(),
            context.spawn(),
            context.triggerData(),
            newOutputs,
            context.state(),
            context.plan(),
            context.organizationId(),
            context.organizationRole()
        );
    }

    /**
     * Wraps a per-item node output in the {@code {output, httpstatus:200}} envelope and writes it
     * under both the full node key and its bare alias. Shared by the in-memory and durable-backfill
     * branches of {@link #injectPredecessorPerItemOutputs} so both produce identical stepOutput shapes.
     *
     * <p>writeWithAlias mirrors RunContextService.buildPerItemContext alias semantics (full key + bare
     * alias atomically), preventing the Daily Email Digest bug where every per-item invocation saw
     * item 0's alias entry (prod run 6c67cb76 epoch 3, 2026-05-08). See StepOutputsWriter for the bug class.
     */
    private void injectPerItemStepOutput(Map<String, Object> newOutputs, String nodeId, Object itemResult) {
        Map<String, Object> wrapper = new HashMap<>();
        wrapper.put("output", itemResult);
        wrapper.put("httpstatus", 200);
        com.apimarketplace.orchestrator.services.context.StepOutputsWriter.writeWithAlias(
            newOutputs, nodeId, wrapper);
    }

    /**
     * True for the split-scope node types that re-resolve EACH predecessor per item and therefore
     * silently collapse to a single "item 0" value when the in-memory {@link SplitContext#resultsByNode}
     * is missing a slot: branch-rejoin / implicit merges, aggregates, and the routing/control nodes
     * ({@code decision}/{@code switch}/{@code fork}/{@code loop}) that run {@code forcePerItem}. Only
     * these pay the one durable-store read per fan-out; the common direct-successor / plain chained
     * fan-out keeps its warm, query-free path.
     *
     * <p>This is only ONE of the two shapes that trigger the durable backfill. A PLAIN per-item
     * successor (mcp/agent/code/transform/interface) that reads a NON-adjacent predecessor is the
     * other, gated by {@link #readsNonSplitPredecessor} - it collapses the same way but is not a
     * "cross-item consumer" by type. Keep this predicate strictly type-based; the plain-successor
     * case is deliberately handled by the separate graph-shape predicate so the FIRST node after a
     * split (a direct successor, legitimately empty map) never pays a durable read.
     */
    boolean isCrossItemPerItemConsumer(ExecutionNode node) {  // package-private for tests
        return node.isMergeNode()
            || node.isImplicitMerge()
            || node.isAggregateNode()
            || node.isDecisionNode()
            || node.isSwitchNode()
            || node.isForkNode()
            || node.isLoopNode();
    }

    /**
     * True when {@code node} reads at least one predecessor that is NOT the split node itself, i.e.
     * it is not a DIRECT split successor. This is the gate for the plain-successor collapse residual:
     * a mcp/agent/code/transform/interface node whose per-item output re-resolves a PRIOR per-item
     * predecessor (anything other than the split) silently collapses to the base "item 0" value when
     * the in-memory {@link SplitContext#resultsByNode} is missing that predecessor's slot after a
     * cross-pod / restart resume (proven: a plain transform reading a non-adjacent predecessor across
     * an approval returned item 0's value for every row once the resultsByNode singleton was wiped).
     *
     * <p>A DIRECT split successor is excluded: its only in-split predecessor is the split node, and
     * its per-item values come from {@code {{item}}} / the split's current_item, never a prior
     * per-item node - so its legitimately-empty map has nothing to backfill and must stay query-free.
     * The split id is reduced to its base node key with {@link #extractBaseSplitKey} (the same helper
     * the routing/aggregate paths use, so a scoped {@code core:per_tag:0} normalizes identically);
     * each predecessor id, which may carry an edge port ({@code core:gate:approved}), is reduced with
     * {@link EdgeRefParser#getNodeKey}. Null-tolerant.
     */
    boolean readsNonSplitPredecessor(ExecutionNode node, String splitNodeId) {  // package-private for tests
        if (node == null || splitNodeId == null) {
            return false;
        }
        String splitKey = extractBaseSplitKey(splitNodeId);
        if (splitKey == null) {
            splitKey = splitNodeId;
        }
        for (String predId : node.getPredecessorIds()) {
            if (predId == null) {
                continue;
            }
            String predKey = EdgeRefParser.getNodeKey(predId);
            if (predKey == null) {
                predKey = predId;
            }
            if (!splitKey.equals(predKey)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when the in-memory {@link SplitContext#resultsByNode} can already serve every routed item
     * from RAM, so a durable read would be wasted work. "Complete" means the cache is non-empty AND
     * every node currently in it has a non-null slot for every routed item index. An empty cache
     * (restoreContext rebuilt items only after a cross-pod/restart) or any missing/short/null slot
     * (unsealed async barrier) returns {@code false} - exactly the states where the durable backfill
     * recovers a real per-item value instead of the collapsed "item 0" value.
     *
     * <p>Deliberate residual: a node that ran entirely on another pod is absent from the map rather
     * than present-with-null. When the rest of the map is dense this returns {@code true} and skips
     * the read; in practice the absent-node case coincides with an empty/sparse map (a resumed pod
     * starts from a rebuilt-items-only context), so the observed collapse still triggers a load. The
     * merge path ({@link SplitMergeHandler#mergeDurablePerItem}) closes even that residual by scoping
     * against the split-subgraph ancestors, which this executor path lacks the node map to compute.
     */
    boolean inMemorySlotsComplete(SplitContext splitContext, Set<Integer> routedItemIndices) {  // package-private for tests
        if (splitContext == null) {
            return false;
        }
        Map<String, List<Object>> allResults = splitContext.getAllResults();
        if (allResults.isEmpty()) {
            return false; // nothing cached → let the durable backfill try
        }
        for (List<Object> results : allResults.values()) {
            for (Integer i : routedItemIndices) {
                if (results == null || i == null || i >= results.size() || results.get(i) == null) {
                    return false; // a gap for a routed item → not warm
                }
            }
        }
        return true;
    }

    /**
     * Loads the durable per-item outputs for the whole epoch ({@code stepKey → itemIndex → output}),
     * used to backfill {@link #injectPredecessorPerItemOutputs}. Null-tolerant and best-effort: a
     * missing service or a store failure degrades to "no backfill" (warm behavior), never a throw
     * that would abort the split fan-out.
     */
    Map<String, Map<Integer, Object>> loadDurableEpochOutputs(ExecutionContext context) {  // package-private for tests
        if (stepOutputService == null || context == null) {
            return null;
        }
        try {
            return stepOutputService.loadPerItemOutputsByStepKey(
                context.runId(), context.epoch(), context.tenantId());
        } catch (Exception e) {
            logger.warn("[SplitAware] Durable per-item epoch load failed (run={}, epoch={}): {} - continuing without backfill",
                context.runId(), context.epoch(), e.getMessage());
            return null;
        }
    }

    /**
     * Extracts the base split key from a scoped key.
     * "core:processmessages:0" -> "core:processmessages"
     * "core:inner_loop:0/s1" -> "core:inner_loop"
     */
    private String extractBaseSplitKey(String scopedKey) {
        return SplitContextManager.extractBaseSplitNodeId(scopedKey);
    }

    /**
     * Injects current_item and current_index into the split node's stepOutputs (RUNTIME CONTEXT layer).
     *
     * <p>These values are NOT persisted to the database. They are injected into the
     * in-memory ExecutionContext so that body nodes within the split can resolve
     * templates like {@code {{core:processmessages.output.current_item.id}}} or
     * {@code {{core:processmessages.output.current_index}}}.
     *
     * <p>This complements the persisted layer (items, item_count, split_id, etc.)
     * managed by SplitOutputSchemaMapper. Together, the two layers give body nodes
     * access to both the full split metadata and the per-branch item context.
     *
     * @see com.apimarketplace.orchestrator.services.persistence.schema.SplitOutputSchemaMapper
     */
    @SuppressWarnings("unchecked")
    private ExecutionContext injectCurrentItemIntoStepOutputs(
            ExecutionContext context,
            String splitNodeId,
            Object item,
            int subItemIndex) {

        // Get existing step outputs
        Map<String, Object> allOutputs = context.getAllStepOutputs();
        Map<String, Object> newOutputs = new HashMap<>(allOutputs);

        // Get or create the split node's output wrapper
        Map<String, Object> splitWrapper = (Map<String, Object>) newOutputs.get(splitNodeId);
        if (splitWrapper == null) {
            splitWrapper = new HashMap<>();
            splitWrapper.put("output", new HashMap<String, Object>());
            splitWrapper.put("httpstatus", 200);
        } else {
            // Clone to avoid modifying the original
            splitWrapper = new HashMap<>(splitWrapper);
        }

        // Get or create the output map
        Map<String, Object> splitOutput = (Map<String, Object>) splitWrapper.get("output");
        if (splitOutput == null) {
            splitOutput = new HashMap<>();
        } else {
            splitOutput = new HashMap<>(splitOutput);
        }

        // Inject current_item and current_index
        splitOutput.put("current_item", item);
        splitOutput.put("current_index", subItemIndex);

        // Update the wrapper - writeWithAlias also publishes under the bare alias
        // ("core:split_emails" → "split_emails") so CodeNode's $input.<alias>.current_item
        // resolves to THIS branch's item, not the outer item 0 snapshot from
        // V2StepByStepContextManager. See StepOutputsWriter for the bug class.
        splitWrapper.put("output", splitOutput);
        com.apimarketplace.orchestrator.services.context.StepOutputsWriter.writeWithAlias(
            newOutputs, splitNodeId, splitWrapper);

        // Create new context with updated stepOutputs.
        // PR15 - preserve org context across the split sub-context rebuild.
        return new ExecutionContext(
            context.runId(),
            context.workflowRunId(),
            context.tenantId(),
            context.itemId(),
            context.itemIndex(),
            context.triggerId(),
            context.epoch(),
            context.spawn(),
            context.triggerData(),
            newOutputs,
            context.state(),
            context.plan(),
            context.organizationId(),
            context.organizationRole()
        );
    }

    /**
     * Adds item_index and split_item_count to the result output for persistence.
     * split_item_count is needed by ReadyNodeCalculator to detect that a Decision/Switch
     * node was executed inside a Split context, so it traverses ALL branches (not just
     * the last item's selected branch).
     */
    private NodeExecutionResult addItemIndexToResult(NodeExecutionResult result, int subItemIndex, int totalItemCount) {
        Map<String, Object> output = result.output() != null
            ? new HashMap<>(result.output())
            : new HashMap<>();
        output.put("item_index", subItemIndex);
        output.put("split_item_count", totalItemCount);

        return new NodeExecutionResult(
            result.nodeId(),
            result.status(),
            output,
            result.errorMessage(),
            result.metadata(),
            result.durationMs()
        );
    }

    /**
     * Persists an individual item result and emits edge events.
     *
     * <p>All nodes (including Decision, Switch, Classify) go through the standard
     * persistence path via NodeCompletionService, which applies OutputSchemaMapper
     * for consistent DB format.
     */
    private void persistItemResult(
            WorkflowExecution execution,
            ExecutionNode node,
            NodeExecutionResult result,
            TriggerItem triggerItem,
            int subItemIndex,
            ExecutionContext context) {
        try {
            if (nodeCompletionService == null) {
                return;
            }

            // Extract iteration first (null for non-loop context)
            Integer iteration = nodeCompletionService.extractCurrentIteration(context, node, result);

            // All nodes go through the standard persistence path:
            // DB persistence (with OutputSchemaMapper) → StateSnapshot update → streaming event
            nodeCompletionService.emitNodeComplete(
                execution, node, result, triggerItem, subItemIndex, context);
            logger.debug("[SplitAware] Persisted item result: nodeId={}, subItem={}",
                node.getNodeId(), subItemIndex);

            // Emit edge events (COMPLETED for selected branches, SKIPPED for others)
            // IMPORTANT: For branching nodes in split context, suppress skip propagation.
            // Each item may select a different branch, so propagating skip for one item's
            // non-selected branch would incorrectly mark the target node as SKIPPED even
            // though another item selected that branch.
            //
            // Route through eventService.emitItemOutgoingEdgesInSplit so the
            // per-epoch workflow_epochs row gets written for body-node edges
            // (begin batch → emit → flush → recordEdgeEpochCounts). Calling
            // edgeStatusEmitter.emitOutgoingEdges directly fell into
            // EdgeStatusService "immediate mode" which only wrote to the
            // top-level state_snapshot.edges map and skipped the per-epoch
            // table - leaving the epoch viewer blind to body-node outgoing
            // edges (check_memory→is_new, is_new:if→exit, etc.).
            // (Pre-existing bug surfaced 2026-05-08 by user-reported gap.)
            boolean suppressSkipPropagation = (result != null && result.isFailure() && node.isBranchingNode())
                || node.isDecisionNode()
                || node.isSwitchNode()
                || node.isOptionNode();
            if (eventService != null) {
                eventService.emitItemOutgoingEdgesInSplit(execution, node, subItemIndex, iteration,
                    result, suppressSkipPropagation, context.epoch(), context.triggerId());
                logger.debug("[SplitAware] Emitted edge events via eventService: nodeId={}, subItem={}, suppressSkipPropagation={}",
                    node.getNodeId(), subItemIndex, suppressSkipPropagation);
            } else if (edgeStatusEmitter != null) {
                // Fallback for unit tests that don't wire the eventService.
                // Loses per-epoch recording (acceptable in tests; flagged in
                // logs so it surfaces if it ever lands in production).
                edgeStatusEmitter.emitOutgoingEdges(execution, node, subItemIndex, iteration, result, suppressSkipPropagation,
                    context.epoch(), context.triggerId(), true);
                logger.warn("[SplitAware] eventService null - emitted via fallback, per-epoch edge recording SKIPPED: nodeId={}, subItem={}",
                    node.getNodeId(), subItemIndex);
            }

            if (result != null && result.isFailure() && execution != null && skipPropagationService != null) {
                try {
                    skipPropagationService.cascadeFailureToSuccessors(
                        execution, node, subItemIndex, context.epoch(), context.triggerId(),
                        /*perItemScope=*/ true, "split_failure");
                } catch (Exception cascadeEx) {
                    logger.warn("[SplitAware] Failure cascade failed for split item: runId={}, nodeId={}, itemIndex={}, error={}",
                        execution.getRunId(), node.getNodeId(), subItemIndex, cascadeEx.getMessage());
                }
            }

        } catch (Exception e) {
            logger.error("[SplitAware] Failed to persist: nodeId={}, subItem={}, error={}",
                node.getNodeId(), subItemIndex, e.getMessage(), e);
        }
    }

    /**
     * Creates the summary result after all items complete.
     */
    private NodeExecutionResult createSummaryResult(
            String nodeId,
            List<NodeExecutionResult> results,
            List<String> errors,
            boolean hasFailure,
            long duration,
            boolean successorsHandled) {

        // Use the last successful result's output (real MCP response, no wrapper)
        Map<String, Object> output = new HashMap<>();
        for (int i = results.size() - 1; i >= 0; i--) {
            if (results.get(i).isSuccess() && results.get(i).output() != null) {
                output = new HashMap<>(results.get(i).output());
                break;
            }
        }

        // Add split metadata
        output.put("split_item_count", results.size());

        if (!errors.isEmpty()) {
            output.put("split_errors", errors);
        }

        // Phase 2.B (2026-04-29 prod-incident fix): partial failure means
        // ≥1 success and ≥1 failure. Global status = COMPLETED so downstream
        // readiness gates don't short-circuit on isFailed(). Marker in OUTPUT
        // (queryable, survives schema mappers as part of output) AND in METADATA
        // (signals StepCompletionOrchestrator to also mark partialFailedNodeIds).
        boolean allFailed = hasFailure && results.stream().noneMatch(NodeExecutionResult::isSuccess);
        boolean partialFailure = hasFailure && !allFailed;
        if (partialFailure) {
            output.put(ExecutionMetadataKeys.SPLIT_PARTIAL_FAILURE, true);
            output.put(ExecutionMetadataKeys.SPLIT_FAILED_ITEM_INDICES, failedIndicesOf(results));
            output.put(ExecutionMetadataKeys.SPLIT_COMPLETED_ITEM_INDICES, completedIndicesOf(results));
        }

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("split_execution", true);
        metadata.put("split_item_count", results.size());
        metadata.put(ExecutionMetadataKeys.SPLIT_ALREADY_PERSISTED, true);
        if (successorsHandled) {
            metadata.put(ExecutionMetadataKeys.SPLIT_SUCCESSORS_HANDLED, true);
        }

        return new NodeExecutionResult(
            nodeId,
            allFailed ? NodeStatus.FAILED : NodeStatus.COMPLETED,
            output,
            allFailed
                ? Optional.of("All items failed: " + String.join("; ", errors))
                : Optional.empty(),
            metadata,
            duration
        );
    }

    private static java.util.List<Integer> failedIndicesOf(List<NodeExecutionResult> results) {
        java.util.List<Integer> out = new java.util.ArrayList<>();
        for (int i = 0; i < results.size(); i++) {
            NodeExecutionResult r = results.get(i);
            if (r != null && r.isFailure()) out.add(i);
        }
        return out;
    }

    private static java.util.List<Integer> completedIndicesOf(List<NodeExecutionResult> results) {
        java.util.List<Integer> out = new java.util.ArrayList<>();
        for (int i = 0; i < results.size(); i++) {
            NodeExecutionResult r = results.get(i);
            if (r != null && r.isSuccess()) out.add(i);
        }
        return out;
    }

    /**
     * Creates an empty result for split with no items.
     */
    private NodeExecutionResult createEmptyResult(String nodeId) {
        Map<String, Object> output = new HashMap<>();
        output.put("split_item_count", 0);
        output.put("message", "No items to process");

        return new NodeExecutionResult(
            nodeId,
            NodeStatus.COMPLETED,
            output,
            Optional.empty(),
            Map.of(
                "split_execution", true,
                "split_item_count", 0,
                ExecutionMetadataKeys.SPLIT_ALREADY_PERSISTED, true,
                ExecutionMetadataKeys.SPLIT_SUCCESSORS_HANDLED, true
            ),
            0
        );
    }

    /**
     * Persists SKIPPED step data records for items that were not routed to this node.
     *
     * <p>When a Decision/Switch node routes items to different branches, each branch node
     * needs SKIPPED records for the items that went to other branches. This ensures correct
     * statusCounts (e.g., "high_value: success=2, skipped=3").
     *
     * <p>IMPORTANT: This only creates step data records marked as SKIPPED. It does NOT mark
     * the node as SKIPPED in the StateSnapshot, because the node DID execute successfully
     * for the routed items.
     *
     * @param execution The workflow execution
     * @param node The node being executed
     * @param routedItemIndices Indices of items that were routed (executed) for this node
     * @param totalItems Total number of items in the split
     */
    private void persistSkippedItemRecords(
            WorkflowExecution execution,
            ExecutionNode node,
            Set<Integer> routedItemIndices,
            int totalItems,
            int epoch,
            String triggerId) {
        persistSkippedItemRecords(execution, node, routedItemIndices, totalItems, epoch, triggerId,
            true, false, "Not routed to this branch");
    }

    private void persistSkippedItemRecords(
            WorkflowExecution execution,
            ExecutionNode node,
            Set<Integer> routedItemIndices,
            int totalItems,
            int epoch,
            String triggerId,
            boolean emitBranchAggregateEvent,
            boolean cascadeSkippedItemsToDescendants) {
        persistSkippedItemRecords(execution, node, routedItemIndices, totalItems, epoch, triggerId,
            emitBranchAggregateEvent, cascadeSkippedItemsToDescendants, "Not routed to this branch");
    }

    /**
     * @param skipReason per-item skip reason written on each SKIPPED step_data row -
     *        {@code "Not routed to this branch"} for branch routing, or
     *        {@link #EXECUTE_ONCE_SKIP_REASON} for executeOnce-suppressed items.
     */
    private void persistSkippedItemRecords(
            WorkflowExecution execution,
            ExecutionNode node,
            Set<Integer> routedItemIndices,
            int totalItems,
            int epoch,
            String triggerId,
            boolean emitBranchAggregateEvent,
            boolean cascadeSkippedItemsToDescendants,
            String skipReason) {

        String nodeId = node.getNodeId();
        String nodeLabel = com.apimarketplace.orchestrator.utils.LabelNormalizer.extractLabel(nodeId);
        if (nodeLabel == null) nodeLabel = nodeId;

        int skippedCount = totalItems - routedItemIndices.size();
        if (skippedCount <= 0) return;

        // Create SKIPPED step data records for each non-routed item.
        //
        // Two-pass design (2026-05-20 follow-up to the V263 fail-loud surfacing):
        //  - Pass 1: emitNodeSkippedForItem writes this node's own step_data row with
        //            reason "Not routed to this branch" (per-item scope, no global
        //            StateSnapshot mutation - other items may still execute through
        //            this node for the chosen branch).
        //  - Pass 2: cascadeFailureToSuccessors recurses through non-merge descendants
        //            and writes per-item SKIPPED step_data rows + emits SKIPPED edges,
        //            but only when the whole explicit branch has 0 routed items. For
        //            mixed-routing branches, descendants are traversed with the same
        //            routed subset and will materialize their own skipped records; cascading
        //            from the parent branch as well double-counts descendant skips.
        //
        // cascadeFailureToSuccessors handles merge nodes specifically (global edge
        // marking + convergence check via markEdgeToMergeSkipped). Visited Set inside
        // emitSkippedOutgoingEdgesRecursive guards against cycles. Step_data writes
        // are idempotent via INSERT … ON CONFLICT DO NOTHING on
        // idx_workflow_step_data_unique_v6, so re-cascading the same (node, item,
        // epoch) tuple is a no-op rather than a duplicate row.
        for (int i = 0; i < totalItems; i++) {
            if (routedItemIndices.contains(i)) {
                continue; // This item was routed and executed, skip
            }

            try {
                nodeCompletionService.emitNodeSkippedForItem(
                    execution, node, i, skipReason, epoch, triggerId);
                logger.debug("[SplitAware] Persisted SKIPPED record for non-routed item: runId={}, nodeId={}, itemIndex={}, epoch={}, triggerId={}",
                    execution.getRunId(), nodeId, i, epoch, triggerId);
            } catch (Exception e) {
                logger.warn("[SplitAware] Failed to persist SKIPPED record: runId={}, nodeId={}, itemIndex={}, error={}",
                    execution.getRunId(), nodeId, i, e.getMessage());
            }

            // Pass 2 - cascade per-item SKIP to descendants. Wrapped in its own
            // try/catch so a cascade failure on one item doesn't strand subsequent
            // items' Pass 1 writes (the apply_X SKIPPED row IS the load-bearing
            // record for that item; the cascade is best-effort observability).
            //
            // Cost note (audit E follow-up, 2026-05-20): this is O(itemCount ×
            // descendants × branches) per persistSkippedItemRecords call. For
            // L'Éveil-shape workflows with N≤10 items × 6 unrouted branches × ~4
            // descendants ≈ 240 cascade walks per fire - acceptable on the cron path.
            // For larger N (>100 items) the walks dominate the trigger fire latency;
            // see [[project_skip_cascade_all_items_unrouted_2026_05_20]] for the
            // batch-refactor follow-up (single cascade with Set<Integer> itemIndices
            // + insertBatchIgnoringDuplicates instead of per-item single-row INSERTs).
            if (cascadeSkippedItemsToDescendants && skipPropagationService != null) {
                try {
                    skipPropagationService.cascadeFailureToSuccessors(
                        execution, node, i, epoch, triggerId, /*perItemScope=*/ true,
                        "split_unrouted");
                } catch (Exception e) {
                    logger.warn("[SplitAware] Skip cascade failed for non-routed item: runId={}, nodeId={}, itemIndex={}, error={}",
                        execution.getRunId(), nodeId, i, e.getMessage());
                }
            }
        }

        // Increment NodeCounts.skipped by the per-epoch count AND emit ONE aggregated
        // step event per affected node with the post-increment counts.
        //
        // 2026-05-21 fix (Gmail Auto-Labeler frontend no-badge bug):
        //   Pre-fix, the per-item emit inside the loop above reads snapshotCounts
        //   BEFORE this increment - every event in the loop carries SKIPPED=0.
        //   The frontend's NodeStatusBadge renders nothing when all counters are
        //   zero, so apply_X nodes (and their record_*/collect_*/build_*/send_*
        //   descendants) showed no badge.
        //   batchIncrementSkippedCountsAndEmit increments + emits a fresh
        //   aggregated event that the frontend's statusUpdater merges into
        //   node.data.statusCounts → badge appears with the real SKIPPED count.
        //
        // For no-routed branches, also mirrors descendant counts:
        // V2SkipPropagationService.cascadeFailureToSuccessors writes descendant step_data
        // rows but explicitly defers NodeCounts increment to this method. Mixed-routing
        // branches skip that mirror because descendants are traversed and counted by
        // their own persistSkippedItemRecords call.
        if (nodeCompletionService != null) {
            try {
                if (emitBranchAggregateEvent) {
                    nodeCompletionService.batchIncrementSkippedCountsAndEmit(
                        execution, nodeId, nodeLabel, skippedCount, epoch, triggerId);
                } else {
                    nodeCompletionService.batchIncrementSkippedCounts(
                        execution.getRunId(), nodeId, skippedCount);
                }
            } catch (Exception e) {
                logger.warn("[SplitAware] Failed to batch-increment skipped counts: nodeId={}, count={}, emit={}, error={}",
                    nodeId, skippedCount, emitBranchAggregateEvent, e.getMessage());
            }

            if (cascadeSkippedItemsToDescendants) {
                // Mirror for cascaded descendants. The cascade (per-item) already wrote
                // step_data rows + edge SKIPs for each descendant N times; here we
                // increment NodeCounts ONCE per descendant by the same skippedCount
                // (one increment per non-routed item that flowed through them).
                Set<String> cascadedDescendants = collectNonMergeDescendants(node);
                for (String descId : cascadedDescendants) {
                    String descLabel = com.apimarketplace.orchestrator.utils.LabelNormalizer.extractLabel(descId);
                    if (descLabel == null) descLabel = descId;
                    try {
                        nodeCompletionService.batchIncrementSkippedCountsAndEmit(
                            execution, descId, descLabel, skippedCount, epoch, triggerId);
                    } catch (Exception e) {
                        logger.warn("[SplitAware] Failed to batch-increment+emit cascaded descendant: descId={}, count={}, error={}",
                            descId, skippedCount, e.getMessage());
                    }
                }
                if (!cascadedDescendants.isEmpty()) {
                    logger.info("[SplitAware] Batched skipped-count increment + emit for {} cascaded descendants of nodeId={}, count={}",
                        cascadedDescendants.size(), nodeId, skippedCount);
                }
            }
        }

        // Increment EdgeCounts for outgoing edges of this node for non-routed items.
        // Without this, edges downstream of skipped nodes show skipped=0 even though
        // the node itself has skipped items (e.g., wait→wait_copy stays at skipped=0
        // while wait has skipped=3).
        //
        // Two-write contract (audit MF-1, 2026-05-08):
        //   1. state_snapshot.edges (top-level)         - via recordEdgeStatusesBatch
        //   2. workflow_epochs (per-epoch row, EDGE)    - via eventService.recordSkipEdgesPerEpoch
        // Without (2) the frontend epoch selector shows these SKIPPED edges as
        // blank for the specific epoch even though they're recorded at the run level.
        if (stateSnapshotService != null) {
            List<ExecutionNode> successors = node.getSuccessors();
            if (!successors.isEmpty()) {
                Map<String, Map.Entry<String, Integer>> edgeIncrements = new HashMap<>();
                for (ExecutionNode successor : successors) {
                    String edgeKey = nodeId + "->" + successor.getNodeId();
                    edgeIncrements.put(edgeKey, Map.entry("SKIPPED", skippedCount));
                }
                try {
                    stateSnapshotService.recordEdgeStatusesBatch(execution.getRunId(), edgeIncrements);
                    if (eventService != null) {
                        eventService.recordSkipEdgesPerEpoch(execution, edgeIncrements, epoch, triggerId);
                    }
                    logger.info("[SplitAware] Incremented SKIPPED edge counts for {} outgoing edges x {} items: nodeId={}, epoch={}",
                        successors.size(), skippedCount, nodeId, epoch);
                } catch (Exception e) {
                    logger.warn("[SplitAware] Failed to increment skipped edge counts: nodeId={}, error={}",
                        nodeId, e.getMessage());
                }
            }
        }

        logger.info("[SplitAware] Persisted {} SKIPPED records for non-routed items: nodeId={}, routed={}, total={}",
            skippedCount, nodeId, routedItemIndices.size(), totalItems);
    }

    private void cascadeExternallyHandledSkippedItems(
            WorkflowExecution execution,
            ExecutionNode node,
            Set<Integer> itemIndices,
            int epoch,
            String triggerId) {

        if (execution == null || node == null || itemIndices == null || itemIndices.isEmpty()
                || skipPropagationService == null) {
            return;
        }

        for (Integer itemIndex : itemIndices) {
            if (itemIndex == null || itemIndex < 0) {
                continue;
            }
            try {
                skipPropagationService.cascadeFailureToSuccessors(
                    execution, node, itemIndex, epoch, triggerId, /*perItemScope=*/ true,
                    "split_unrouted");
            } catch (Exception e) {
                logger.warn("[SplitAware] Skip cascade failed for externally handled source item: runId={}, nodeId={}, itemIndex={}, error={}",
                    execution.getRunId(), node.getNodeId(), itemIndex, e.getMessage());
            }
        }

        int skippedCount = itemIndices.size();
        if (nodeCompletionService != null) {
            Set<String> cascadedDescendants = collectNonMergeDescendants(node);
            for (String descId : cascadedDescendants) {
                String descLabel = com.apimarketplace.orchestrator.utils.LabelNormalizer.extractLabel(descId);
                if (descLabel == null) descLabel = descId;
                try {
                    nodeCompletionService.batchIncrementSkippedCountsAndEmit(
                        execution, descId, descLabel, skippedCount, epoch, triggerId);
                } catch (Exception e) {
                    logger.warn("[SplitAware] Failed to batch-increment externally handled descendant: descId={}, count={}, error={}",
                        descId, skippedCount, e.getMessage());
                }
            }
        }

        if (eventService != null) {
            List<ExecutionNode> successors = node.getSuccessors();
            if (!successors.isEmpty()) {
                Map<String, Map.Entry<String, Integer>> edgeIncrements = new HashMap<>();
                for (ExecutionNode successor : successors) {
                    String edgeKey = node.getNodeId() + "->" + successor.getNodeId();
                    edgeIncrements.put(edgeKey, Map.entry("SKIPPED", skippedCount));
                }
                try {
                    // cascadeFailureToSuccessors already emits and records the direct edge in
                    // state_snapshot.edges. Only add the per-epoch edge row here.
                    eventService.recordSkipEdgesPerEpoch(execution, edgeIncrements, epoch, triggerId);
                } catch (Exception e) {
                    logger.warn("[SplitAware] Failed to record externally handled skipped edge epoch counts: nodeId={}, error={}",
                        node.getNodeId(), e.getMessage());
                }
            }
        }
    }

    private boolean isExplicitChoiceBranchTarget(ExecutionNode node) {
        if (node == null || node.getPredecessorIds() == null) {
            return false;
        }
        for (String predecessorId : node.getPredecessorIds()) {
            EdgeRefParser.EdgeRef ref = EdgeRefParser.parse(predecessorId);
            String port = ref != null ? ref.port() : extractPortFallback(predecessorId);
            if (isChoiceBranchPort(port)) {
                return true;
            }
        }
        return false;
    }

    private String extractPortFallback(String predecessorId) {
        if (predecessorId == null) {
            return null;
        }
        String[] parts = predecessorId.split(":");
        return parts.length >= 3 ? parts[parts.length - 1] : null;
    }

    private boolean isChoiceBranchPort(String port) {
        if (port == null || port.isBlank()) {
            return false;
        }
        return "if".equals(port)
            || "else".equals(port)
            || port.startsWith("elseif_")
            || port.startsWith("case_")
            || "default".equals(port)
            || "approved".equals(port)
            || "rejected".equals(port)
            || "timeout".equals(port)
            || port.startsWith("category_")
            || "pass".equals(port)
            || "fail".equals(port);
    }

    /**
     * Collects all NON-MERGE descendants reachable from {@code node} via BFS, mirroring
     * the cascade-walk policy in {@link
     * com.apimarketplace.orchestrator.execution.v2.services.V2SkipPropagationService#cascadeFailureToSuccessors}:
     * merge nodes are NOT traversed (they have convergence semantics - their counts are
     * not mirrored from upstream skips). Used by {@link #persistSkippedItemRecords} to
     * mirror the cascade's per-item step_data writes with a single batched NodeCounts
     * increment + aggregated emit per descendant.
     *
     * <p>Returns an ordered set so log/event order is deterministic. Excludes the
     * source {@code node} itself - the caller handles the branch node separately.
     */
    private Set<String> collectNonMergeDescendants(ExecutionNode node) {
        Set<String> visited = new LinkedHashSet<>();
        Deque<ExecutionNode> queue = new ArrayDeque<>();
        // Seed with direct successors, NOT the source node (caller handles it).
        for (ExecutionNode succ : node.getSuccessors()) {
            queue.offer(succ);
        }
        while (!queue.isEmpty()) {
            ExecutionNode current = queue.poll();
            String currentId = current.getNodeId();
            // Skip merge nodes (per V2SkipPropagationService policy at line 655).
            // Their counts are handled by the merge convergence path, not by
            // mirroring upstream skip counts.
            if (current.isMergeNode() || current.isImplicitMerge()) {
                continue;
            }
            if (!visited.add(currentId)) {
                continue; // Already enqueued.
            }
            for (ExecutionNode succ : current.getSuccessors()) {
                queue.offer(succ);
            }
        }
        return visited;
    }

    /**
     * Executes a nested split node: a split that is inside another split's scope.
     *
     * <p>This method handles the case where an inner split node must be executed
     * N times (once per parent split item). For each parent item, it:
     * <ol>
     *   <li>Enriches the context with the parent item data</li>
     *   <li>Calls SplitNodeExecutor to create the inner split context (with unique scoped key)</li>
     *   <li>Gets the inner split's items and traverses successors for each</li>
     * </ol>
     *
     * <p>This produces N*M total executions for the nodes inside the inner split,
     * where N is the number of parent items and M is the number of inner items.
     *
     * @param node the inner split node
     * @param context the execution context (with parent split data in global data)
     * @param runId the workflow run ID
     * @param nodeMap map of all nodes
     * @param execution the workflow execution (for persistence)
     * @param triggerItem the trigger item
     * @param workflowItemIndex the workflow item index (from trigger)
     * @param successorTraverser callback to traverse successors after inner split creates context
     * @param splitNodeExecutor the executor to create inner split contexts
     * @return the execution result
     */
    public NodeExecutionResult executeNestedSplit(
            ExecutionNode node,
            ExecutionContext context,
            String runId,
            Map<String, ExecutionNode> nodeMap,
            com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution execution,
            com.apimarketplace.orchestrator.execution.v2.engine.TriggerItem triggerItem,
            int workflowItemIndex,
            SuccessorTraverser successorTraverser,
            SplitNodeExecutor splitNodeExecutor) {

        String nodeId = node.getNodeId();

        // Find the parent split context to get parent items
        Optional<SplitContext> parentContextOpt = contextManager.findActiveContext(
            runId, nodeId, workflowItemIndex, nodeMap);

        // Fallback: if BFS didn't find it (e.g., incomplete nodeMap in auto mode),
        // check predecessor IDs directly against registered split contexts
        if (parentContextOpt.isEmpty()) {
            for (String predId : node.getPredecessorIds()) {
                String basePredId = predId;
                EdgeRefParser.EdgeRef ref = EdgeRefParser.parse(predId);
                if (ref != null && ref.port() != null && !ref.port().isEmpty()) {
                    basePredId = ref.nodeType() + ":" + ref.nodeLabel();
                }
                Optional<SplitContext> predContext = contextManager.getContext(runId, basePredId, workflowItemIndex);
                if (predContext.isPresent()) {
                    parentContextOpt = predContext;
                    logger.info("[SplitAware] Found parent split context via predecessor: nodeId={}, predecessor={}",
                        nodeId, basePredId);
                    break;
                }
            }
        }

        if (parentContextOpt.isEmpty()) {
            logger.warn("[SplitAware] No parent split context found for nested split: nodeId={}", nodeId);
            // Fallback: execute as a normal split
            return splitNodeExecutor.execute(
                runId, nodeId, node.getListExpression(), node.getSplitMaxItems(), workflowItemIndex, context);
        }

        SplitContext parentContext = parentContextOpt.get();
        List<Object> parentItems = parentContext.items();
        int parentItemCount = parentItems.size();

        logger.info("[SplitAware] Executing nested split for {} parent items: nodeId={}, parentSplit={}",
            parentItemCount, nodeId, parentContext.splitNodeId());

        if (parentItemCount == 0) {
            logger.info("[SplitAware] Nested split with 0 parent items, marking as SKIPPED: nodeId={}", nodeId);
            return NodeExecutionResult.skippedWithCascade(nodeId, "No parent items for nested split");
        }

        // Step-by-step mode (successorTraverser == null): create a single flat context
        // with N*M items (cross-product of parent items x inner items). The successor
        // node will find this one context and execute for all N*M items.
        if (successorTraverser == null) {
            return executeNestedSplitFlat(node, context, runId, nodeId, workflowItemIndex,
                parentContext, splitNodeExecutor);
        }

        // Auto mode (successorTraverser != null): create N separate scoped contexts
        // and traverse successors for each.
        boolean canPersist = execution != null && nodeCompletionService != null;
        long startTime = System.currentTimeMillis();

        // Execute the inner split once per parent item, each time creating a uniquely-keyed context
        List<CompletableFuture<NodeExecutionResult>> futures = new ArrayList<>();

        // Capture orgId for ForkJoinPool worker threads (V261 NOT NULL - see
        // also executeForAllItemsAndTraverse). Without re-binding the scope,
        // inserts into storage.storage/workflow_step_data fail.
        final String orgIdForWorker = context.organizationId();

        for (int i = 0; i < parentItemCount; i++) {
            final int subItemIndex = i;
            final Object parentItem = parentItems.get(subItemIndex);

            CompletableFuture<NodeExecutionResult> future = CompletableFuture.supplyAsync(() -> {
                NodeExecutionResult[] resultHolder = new NodeExecutionResult[1];
                com.apimarketplace.common.web.TenantResolver.runWithOrgScope(orgIdForWorker, () -> {
                    try {
                        // Enrich context with parent item data (same as executeForAllItemsAndTraverse)
                        ExecutionContext itemContext = enrichContextWithItem(
                            context, parentItem, subItemIndex, parentItems, parentContext);

                        // Execute the inner split with the enriched context
                        // This creates a context with a unique scoped key (e.g., core:inner_loop:0/s0)
                        NodeExecutionResult splitResult = splitNodeExecutor.execute(
                            runId, nodeId, node.getListExpression(), node.getSplitMaxItems(),
                            workflowItemIndex, itemContext);

                        if (splitResult.isSuccess()) {
                            // Traverse successors with the enriched context
                            ExecutionContext contextWithResult = itemContext.withResult(nodeId, splitResult);
                            List<ExecutionNode> successors = node.getNextNodes(splitResult);
                            for (ExecutionNode successor : successors) {
                                logger.debug("[SplitAware] Nested split: traversing successor {} for parent item {}",
                                    successor.getNodeId(), subItemIndex);
                                successorTraverser.traverse(successor, contextWithResult, subItemIndex);
                            }
                        }

                        resultHolder[0] = addItemIndexToResult(splitResult, subItemIndex, parentItemCount);
                    } catch (Exception e) {
                        logger.error("[SplitAware] Nested split item failed: nodeId={}, parentItem={}, error={}",
                            nodeId, subItemIndex, e.getMessage(), e);
                        resultHolder[0] = NodeExecutionResult.failure(nodeId, e.getMessage());
                    }
                });
                return resultHolder[0];
            }, java.util.concurrent.ForkJoinPool.commonPool());

            futures.add(future);
        }

        // Wait for all parent item executions to complete
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .get(30, java.util.concurrent.TimeUnit.MINUTES);
        } catch (Exception e) {
            logger.error("[SplitAware] Error waiting for nested split executions: {}", e.getMessage(), e);
            futures.forEach(f -> f.cancel(true));
            throw new RuntimeException("Nested split execution failed or timed out", e);
        }

        // Collect results
        List<NodeExecutionResult> results = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        boolean hasFailure = false;

        for (CompletableFuture<NodeExecutionResult> future : futures) {
            try {
                NodeExecutionResult result = future.get();
                results.add(result);
                if (result.isFailure()) {
                    hasFailure = true;
                    errors.add(result.errorMessage().orElse("Unknown error"));
                }
            } catch (Exception e) {
                hasFailure = true;
                errors.add("Error getting result: " + e.getMessage());
            }
        }

        long duration = System.currentTimeMillis() - startTime;

        logger.info("[SplitAware] Nested split completed: nodeId={}, parentItems={}, duration={}ms, failures={}",
            nodeId, parentItemCount, duration, errors.size());

        // Return with successors-handled flag to prevent engine from re-traversing
        return createSummaryResult(nodeId, results, errors, hasFailure, duration, true);
    }

    /**
     * Handles nested split in step-by-step mode by creating a single flat context
     * with N*M items (cross-product of parent items x inner items).
     *
     * <p>For each parent item, enriches the context and evaluates the inner split
     * expression. All inner items from all parent items are collected into one
     * flat list, and a single SplitContext is created. This allows the successor
     * node (e.g., mcp:process) to find one context with all N*M items and execute
     * that many times.
     *
     * @param node the inner split node
     * @param context the execution context
     * @param runId the workflow run ID
     * @param nodeId the inner split node ID
     * @param workflowItemIndex the workflow item index
     * @param parentContext the parent split context with N parent items
     * @param splitNodeExecutor the executor to evaluate inner split expression
     * @return the execution result
     */
    private NodeExecutionResult executeNestedSplitFlat(
            ExecutionNode node,
            ExecutionContext context,
            String runId,
            String nodeId,
            int workflowItemIndex,
            SplitContext parentContext,
            SplitNodeExecutor splitNodeExecutor) {

        List<Object> parentItems = parentContext.items();
        int parentItemCount = parentItems.size();
        long startTime = System.currentTimeMillis();

        logger.info("[SplitAware] Nested split (flat mode): nodeId={}, parentItems={}", nodeId, parentItemCount);

        // Collect all inner items across all parent items
        List<Object> allInnerItems = new ArrayList<>();

        for (int i = 0; i < parentItemCount; i++) {
            Object parentItem = parentItems.get(i);

            // Enrich context with parent item data so inner expression can use {{item}}
            ExecutionContext itemContext = enrichContextWithItem(
                context, parentItem, i, parentItems, parentContext);

            // Execute the inner split to evaluate the expression and get inner items.
            // This creates a scoped context, but we'll replace it with a flat one.
            NodeExecutionResult splitResult = splitNodeExecutor.execute(
                runId, nodeId, node.getListExpression(), node.getSplitMaxItems(),
                workflowItemIndex, itemContext);

            if (splitResult.isFailure()) {
                logger.error("[SplitAware] Nested split expression failed for parent item {}: nodeId={}", i, nodeId);
                continue;
            }

            // Extract the inner items from the split result
            @SuppressWarnings("unchecked")
            List<Object> innerItems = splitResult.output() != null
                ? (List<Object>) splitResult.output().get("items")
                : null;

            if (innerItems != null && !innerItems.isEmpty()) {
                allInnerItems.addAll(innerItems);
                logger.debug("[SplitAware] Nested split flat: parent item {} contributed {} inner items",
                    i, innerItems.size());
            }
        }

        logger.info("[SplitAware] Nested split flat: total items={} ({}x inner per parent), nodeId={}",
            allInnerItems.size(), parentItemCount, nodeId);

        // Remove all scoped contexts that were created by splitNodeExecutor.execute()
        // and create ONE flat context with all N*M items
        contextManager.removeContext(runId, nodeId, workflowItemIndex);

        if (allInnerItems.isEmpty()) {
            contextManager.createContext(runId, nodeId, workflowItemIndex, allInnerItems);
            logger.info("[SplitAware] Nested split flat produced 0 inner items, marking as SKIPPED: nodeId={}", nodeId);
            return NodeExecutionResult.skippedWithCascade(nodeId, "No inner items for nested split");
        }

        SplitContext flatContext = contextManager.createContext(runId, nodeId, workflowItemIndex, allInnerItems);

        long duration = System.currentTimeMillis() - startTime;

        logger.info("[SplitAware] Nested split flat context created: nodeId={}, contextKey={}, totalItems={}, duration={}ms",
            nodeId, flatContext.splitNodeId(), allInnerItems.size(), duration);

        // Build a success result similar to what SplitNodeExecutor returns
        Map<String, Object> output = new HashMap<>();
        output.put("node_type", "SPLIT");
        output.put("split_id", nodeId);
        output.put("item_count", allInnerItems.size());
        output.put("spawn_reason", "nested_split_flat");
        output.put("terminated", true);
        output.put("items", allInnerItems);

        return new NodeExecutionResult(
            nodeId,
            NodeStatus.COMPLETED,
            output,
            Optional.empty(),
            Map.of(),
            duration
        );
    }

    /**
     * Finds an active SplitContext for a node, with multiple fallback strategies.
     *
     * <p>In auto mode, the nodeMap may be incomplete (only containing the current node),
     * which prevents the BFS in {@link SplitContextManager#findActiveContext} from
     * traversing predecessors to find the split ancestor.
     *
     * <p>Fallback strategies (tried in order):
     * <ol>
     *   <li>BFS-based lookup via SplitContextManager (works when nodeMap is complete)</li>
     *   <li>Direct predecessor ID check against registered split contexts</li>
     *   <li>Scan all registered contexts for this run (final fallback for indirect successors)</li>
     * </ol>
     *
     * @param runId the workflow run ID
     * @param nodeId the node to find context for
     * @param workflowItemIndex the workflow item index
     * @param nodeMap map of nodes (may be incomplete in auto mode)
     * @param node the execution node (for accessing predecessorIds)
     * @return the active SplitContext, or empty if not in a split scope
     */
    private Optional<SplitContext> findSplitContextWithFallback(
            String runId,
            String nodeId,
            int workflowItemIndex,
            Map<String, ExecutionNode> nodeMap,
            ExecutionNode node) {

        // Strategy 1: BFS-based lookup (works when nodeMap is complete)
        Optional<SplitContext> splitCtx = contextManager.findActiveContext(
            runId, nodeId, workflowItemIndex, nodeMap);
        if (splitCtx.isPresent()) {
            return splitCtx;
        }

        // Strategy 2: Check predecessor IDs directly against registered split contexts.
        // This handles auto mode where the nodeMap only contains the current node.
        for (String predId : node.getPredecessorIds()) {
            String basePredId = predId;
            EdgeRefParser.EdgeRef ref = EdgeRefParser.parse(predId);
            if (ref != null && ref.port() != null && !ref.port().isEmpty()) {
                basePredId = ref.nodeType() + ":" + ref.nodeLabel();
            }
            Optional<SplitContext> predContext = contextManager.getContext(runId, basePredId, workflowItemIndex);
            if (predContext.isPresent()) {
                logger.info("[SplitAware] Found split context via predecessor fallback: nodeId={}, predecessor={}",
                    nodeId, basePredId);
                return predContext;
            }
        }

        // Strategy 3: Scan ALL registered contexts for this run.
        // This handles cases where the node is an indirect successor of the split
        // (e.g., Split -> Decision -> MCP, and MCP's predecessor is "core:decision" not "core:split").
        // We check if any ancestor in the predecessor chain has a registered context.
        Map<String, SplitContext> allContexts = contextManager.getAllContexts(runId);
        if (!allContexts.isEmpty()) {
            // Build a set of all predecessor base IDs (without ports)
            Set<String> predecessorBaseIds = new HashSet<>();
            for (String predId : node.getPredecessorIds()) {
                EdgeRefParser.EdgeRef ref = EdgeRefParser.parse(predId);
                if (ref != null && ref.port() != null && !ref.port().isEmpty()) {
                    predecessorBaseIds.add(ref.nodeType() + ":" + ref.nodeLabel());
                } else {
                    predecessorBaseIds.add(predId);
                }
            }

            // Check each registered context to see if its base split node ID is a predecessor
            for (Map.Entry<String, SplitContext> entry : allContexts.entrySet()) {
                String contextKey = entry.getKey();
                String baseSplitId = SplitContextManager.extractBaseSplitNodeId(contextKey);
                if (predecessorBaseIds.contains(baseSplitId)) {
                    logger.info("[SplitAware] Found split context via scan fallback: nodeId={}, contextKey={}, baseSplitId={}",
                        nodeId, contextKey, baseSplitId);
                    return Optional.of(entry.getValue());
                }
            }
        }

        return Optional.empty();
    }

    /**
     * Determines if a node should skip split handling.
     * Uses polymorphic skipsSplitHandling() method instead of NodeType checks.
     */
    private boolean shouldSkipSplitHandling(ExecutionNode node) {
        return node.skipsSplitHandling();
    }

    /**
     * Checks if a node is within a split scope.
     */
    public boolean isInSplitScope(String runId, String nodeId, int workflowItemIndex, Map<String, ExecutionNode> nodeMap) {
        return contextManager.isInSplitScope(runId, nodeId, workflowItemIndex, nodeMap);
    }

    /**
     * Legacy method - uses itemIndex 0.
     */
    public boolean isInSplitScope(String runId, String nodeId, Map<String, ExecutionNode> nodeMap) {
        return isInSplitScope(runId, nodeId, 0, nodeMap);
    }

    /**
     * Shuts down the executor service gracefully.
     * Called automatically by Spring on application shutdown.
     */
    @PreDestroy
    public void shutdown() {
        logger.info("🛑 Shutting down SplitAwareNodeExecutor");
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(30, TimeUnit.SECONDS)) {
                logger.warn("⚠️ SplitAwareNodeExecutor executor did not terminate in 30s, forcing shutdown");
                executorService.shutdownNow();
                if (!executorService.awaitTermination(10, TimeUnit.SECONDS)) {
                    logger.error("❌ SplitAwareNodeExecutor executor did not terminate after force shutdown");
                }
            }
        } catch (InterruptedException e) {
            logger.warn("⚠️ Shutdown interrupted, forcing immediate shutdown");
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
        logger.info("✅ SplitAwareNodeExecutor shutdown complete");
    }

    /**
     * Record to hold item execution result with context for later persistence.
     */
    private record ItemExecutionResult(
            int index, NodeExecutionResult result, Exception error, ExecutionContext context,
            List<NodeExecutionResult> failedAttempts) {

        /** Back-compat shape (no policy attempts). */
        private ItemExecutionResult(int index, NodeExecutionResult result, Exception error, ExecutionContext context) {
            this(index, result, error, context, List.of());
        }
    }

    /**
     * Determines which item indices should be executed for this node.
     *
     * <p>For nodes after Classify/Decision nodes, only items that were routed to this
     * specific node should be executed. This is determined by:
     * 1. Checking if any predecessor has a port (e.g., "agent:classifyemail:category_promotions")
     * 2. If so, querying the database to find which items selected that branch
     * 3. Returning only those item indices
     *
     * <p>For nodes without Classify/Decision predecessors, all items are returned.
     *
     * @param node The node to execute
     * @param runId The workflow run ID
     * @param totalItems Total number of items in the split context
     * @return Set of item indices that should be executed
     */
    private Set<Integer> getRoutedItemIndices(ExecutionNode node, String runId, int totalItems, int epoch, String splitNodeId) {
        // Default: all items
        Set<Integer> allItems = new HashSet<>();
        for (int i = 0; i < totalItems; i++) {
            allItems.add(i);
        }

        // If repository is not available (testing), return all items
        if (stepDataRepository == null) {
            return allItems;
        }

        // Check if this node has a Classify/Decision/Switch predecessor with a port
        // getPredecessorIds now available via ExecutionNode interface
        List<String> predecessors = node.getPredecessorIds();
        if (predecessors.isEmpty()) {
            return allItems;
        }

        // Collect items from ALL predecessor ports (union).
        // A node connected to multiple ports of the same branching node (e.g., all categories
        // of a Classify, or all cases of a Switch) must receive items from ALL those branches.
        // Each branch only routes a subset of items, but together they cover all classified items.
        Set<Integer> aggregatedIndices = new HashSet<>();
        boolean foundBranchPredecessor = false;

        for (String predecessorId : predecessors) {
            EdgeRefParser.EdgeRef ref = EdgeRefParser.parse(predecessorId);
            if (ref == null) {
                continue;
            }

            String port = ref.port();
            if (port == null || port.isEmpty()) {
                continue;
            }

            foundBranchPredecessor = true;

            // This predecessor has a port - it's a Decision/Classify/Switch branch
            String branchNodeKey = ref.nodeType() + ":" + ref.nodeLabel();

            logger.info("[SplitAware] Detected branch predecessor: nodeId={}, predecessorId={}, branchNode={}, port={}, epoch={}",
                node.getNodeId(), predecessorId, branchNodeKey, port, epoch);

            // Query database to find which items selected this branch
            try {
                List<Integer> routedIndices = stepDataRepository.findItemIndicesBySelectedBranchAndEpoch(
                    runId, branchNodeKey, port, epoch);

                if (routedIndices != null && !routedIndices.isEmpty()) {
                    aggregatedIndices.addAll(routedIndices);
                    logger.info("[SplitAware] Found {} items routed to branch '{}': nodeId={}, indices={}",
                        routedIndices.size(), port, node.getNodeId(), routedIndices);
                } else {
                    logger.debug("[SplitAware] No items found for branch '{}': nodeId={}, branchNode={}",
                        port, node.getNodeId(), branchNodeKey);
                }
            } catch (Exception e) {
                logger.error("[SplitAware] Error querying routed items: nodeId={}, error={}",
                    node.getNodeId(), e.getMessage(), e);
                // Fall back to all items on error
                return allItems;
            }
        }

        if (foundBranchPredecessor) {
            logger.info("[SplitAware] Aggregated {} items from all branch predecessors: nodeId={}, indices={}",
                aggregatedIndices.size(), node.getNodeId(), aggregatedIndices);
            return aggregatedIndices;
        }

        // No direct branch predecessor - check transitive routing.
        // If a linear predecessor only completed for a subset of items (because IT was routed),
        // this node should inherit the same subset. Example:
        //   user_approval:approved -> wait -> wait_copy
        // wait_copy has no port predecessor, but wait only ran for approved items.
        return getTransitiveRoutedItemIndices(node, runId, totalItems, epoch, allItems, splitNodeId);
    }

    /**
     * Inherit routing from predecessors that only completed for a subset of items.
     *
     * <p>When a node's direct predecessors have no port (linear edges), the node wouldn't
     * normally be filtered by {@code getRoutedItemIndices}. But if the predecessor itself
     * only completed for a subset of items (because IT was routed via a branching ancestor),
     * this node should inherit that same subset.
     *
     * <p><b>Timing assumption:</b> This method is only called during execution, after
     * {@code ReadyNodeCalculator} has confirmed all predecessors are completed. Therefore
     * all predecessor step data is guaranteed to be persisted before this query runs.
     *
     * <p><b>Linear vs merge-like aggregation.</b> A node with multiple linear predecessors
     * may be one of two shapes:
     * <ul>
     *   <li><b>Linear chain</b> (default): each predecessor narrows the routing. Items must
     *   have completed on EVERY predecessor - semantically an INTERSECTION.</li>
     *   <li><b>Merge-like</b> ({@code isMergeNode()} or {@code isImplicitMerge()}): the node
     *   re-joins branches that route DISJOINT subsets of items. An item that completed on
     *   ANY branch must reach the merge - semantically a UNION.</li>
     * </ul>
     *
     * <p>Pre-fix the code always intersected. For a switch with 4 items routed [2,3] to one
     * branch and [0,1] to the other, the rejoin merge saw {@code intersection([2,3],[0,1])
     * = ∅} and was incorrectly marked SKIPPED for all 4 items (prod
     * {@code run_<id>}, "Hourly Price Alert Monitor" 2026-05-14).
     *
     * <p>Logic:
     * <ul>
     *   <li>If predecessor completed 0 items: linear → no item can pass, return Set.of();
     *   merge-like → that branch is empty, OTHER preds may still contribute, continue.</li>
     *   <li>If predecessor completed fewer than totalItems → contribute its subset.</li>
     *   <li>If predecessor completed ALL items → no filtering from this pred; for merge-like
     *   we can short-circuit to allItems since union with the full set is the full set.</li>
     *   <li>Multiple predecessors: INTERSECTION (linear) or UNION (merge-like).</li>
     *   <li>If a merge-like node observed predecessors but none contributed items, return
     *   Set.of() - there is genuinely nothing to do.</li>
     *   <li>DB error: fall back to allItems (safe default).</li>
     * </ul>
     */
    /**
     * Public entrypoint for callers outside this executor (e.g.
     * {@link SplitAggregateHandler}) that need to know which items routed
     * through a node's predecessors. Wraps the private helper with an
     * {@code allItems} = {@code 0..totalItems-1} pre-computed set and
     * uses the DB-backed {@code findCompletedItemIndicesByEpoch} query
     * (more resilient than in-memory split context, which is lost on
     * restart).
     *
     * <p>Same UNION-for-merge-like semantics as
     * {@link #getTransitiveRoutedItemIndices} (see {@code fd47604c1}).
     */
    public Set<Integer> resolveRoutedItemIndices(
            ExecutionNode node, String runId, int totalItems, int epoch, String splitNodeId) {
        if (totalItems <= 0) return Set.of();
        Set<Integer> allItems = new HashSet<>();
        for (int i = 0; i < totalItems; i++) allItems.add(i);
        return getTransitiveRoutedItemIndices(node, runId, totalItems, epoch, allItems, splitNodeId);
    }

    private Set<Integer> getTransitiveRoutedItemIndices(ExecutionNode node, String runId, int totalItems, int epoch, Set<Integer> allItems, String splitNodeId) {
        List<String> predecessors = node.getPredecessorIds();
        // Merge-like: explicit core:merge OR any node with multiple predecessors (implicit
        // merge - including a CRUD/MCP/code node fed by two branches of a decision/switch).
        // For these, downstream item routing is the UNION of per-branch completions; for
        // linear nodes the INTERSECTION still applies.
        boolean useUnion = node.isMergeNode() || node.isImplicitMerge();
        Set<Integer> inherited = null;
        // Set to true the moment a predecessor produces a definitive answer (even an empty
        // one in merge-like mode). Used at the end to differentiate "all preds completed
        // every item → no filtering needed" from "all preds completed 0 items → nothing
        // to do" for merge-like nodes.
        boolean sawPredecessorData = false;

        // Extract the base split node ID (without item index suffix).
        // splitNodeId format: "core:split_items:0" -> base is "core:split_items"
        // Handles nested splits (e.g., "core:split:0/s1") and null input.
        String baseSplitId = extractBaseSplitKey(splitNodeId);

        for (String predecessorId : predecessors) {
            EdgeRefParser.EdgeRef ref = EdgeRefParser.parse(predecessorId);
            if (ref == null) continue;

            String predKey = ref.nodeType() + ":" + ref.nodeLabel();

            // Skip the split node itself - it's the source of items, not a filter.
            // A split completes once (itemIndex=0), so querying its completions would
            // incorrectly restrict routing to just item 0.
            if (predKey.equals(baseSplitId)) {
                continue;
            }

            try {
                List<Integer> completedIndices = stepDataRepository.findCompletedItemIndicesByEpoch(
                    runId, predKey, epoch);

                if (completedIndices == null || completedIndices.isEmpty()) {
                    if (useUnion) {
                        // Merge-like: an empty branch doesn't block - another branch may
                        // still have routed items. Mark that we've SEEN this predecessor
                        // so the all-empty case at the end returns Set.of() instead of
                        // allItems.
                        sawPredecessorData = true;
                        logger.info("[SplitAware] Merge-like node: predecessor {} completed 0 items, continuing union over remaining preds: nodeId={}, epoch={}",
                            predKey, node.getNodeId(), epoch);
                        continue;
                    }
                    // Linear: any single pred completing 0 items means no item can pass
                    // through this chain.
                    logger.info("[SplitAware] Predecessor {} completed 0 items: nodeId={}, epoch={}",
                        predKey, node.getNodeId(), epoch);
                    return Set.of();
                }

                sawPredecessorData = true;

                if (completedIndices.size() < totalItems) {
                    Set<Integer> predSet = new HashSet<>(completedIndices);
                    if (inherited == null) {
                        // Copy so subsequent retainAll/addAll mutations don't leak back
                        // into the caller's set.
                        inherited = new HashSet<>(predSet);
                    } else if (useUnion) {
                        inherited.addAll(predSet); // merge-like: union across branches
                    } else {
                        // Linear-with-multiple-preds INTERSECTION. Note: given the current
                        // BaseNode.isImplicitMerge() = (predecessorIds.size() > 1) contract,
                        // a node reaching this branch with `inherited != null` would have
                        // had multiple preds, hence useUnion=true. The retainAll line is
                        // therefore unreachable today but kept as a defensive linear-mode
                        // contract anchor in case isImplicitMerge() is ever narrowed.
                        inherited.retainAll(predSet);
                    }
                    logger.info("[SplitAware] Transitive routing from predecessor {}: nodeId={}, completedItems={}, epoch={}, mode={}",
                        predKey, node.getNodeId(), completedIndices, epoch, useUnion ? "UNION" : "INTERSECTION");
                } else if (useUnion) {
                    // Merge-like + this pred covered every item → union is already total.
                    // Short-circuit; remaining preds cannot reduce a full set.
                    logger.info("[SplitAware] Merge-like node: predecessor {} covers all items, short-circuit to allItems: nodeId={}",
                        predKey, node.getNodeId());
                    return allItems;
                }
            } catch (Exception e) {
                logger.error("[SplitAware] Error querying predecessor completions: nodeId={}, pred={}, error={}",
                    node.getNodeId(), predKey, e.getMessage(), e);
                return allItems;
            }
        }

        if (inherited == null) {
            // No predecessor produced a partial subset. For merge-like nodes that means
            // either (a) at least one pred completed all items (we'd have short-circuited
            // to allItems above) or (b) all preds completed 0 items. Case (a) is already
            // handled, so reaching here in merge-like mode with sawPredecessorData=true
            // is case (b) - genuinely nothing routed.
            if (useUnion && sawPredecessorData) {
                logger.info("[SplitAware] Merge-like node: all predecessors completed 0 items: nodeId={}",
                    node.getNodeId());
                return Set.of();
            }
            return allItems;
        }

        if (inherited.isEmpty()) {
            logger.info("[SplitAware] {} of predecessor routing is empty: nodeId={}",
                useUnion ? "Union" : "Intersection", node.getNodeId());
            return Set.of();
        }

        logger.info("[SplitAware] Inherited transitive routing: nodeId={}, routedIndices={}, mode={}",
            node.getNodeId(), inherited, useUnion ? "UNION" : "INTERSECTION");
        return inherited;
    }
}
