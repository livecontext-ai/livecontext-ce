package com.apimarketplace.orchestrator.execution.v2.services;

import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import com.apimarketplace.orchestrator.domain.workflow.StepExecutionResult;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.execution.v2.nodes.BaseNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.ExecutionNode;
import com.apimarketplace.orchestrator.services.completion.SkipContext;
import com.apimarketplace.orchestrator.services.completion.StepCompletionOrchestrator;
import com.apimarketplace.orchestrator.services.streaming.EdgeStatusService;
import com.apimarketplace.orchestrator.utils.EdgeRefParser;
import com.apimarketplace.orchestrator.utils.LabelNormalizer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Service responsible for propagating skip status through workflow nodes.
 *
 * <p>This service manages:
 * <ul>
 *   <li>Persisting skipped nodes to database</li>
 *   <li>Emitting SKIPPED edge events</li>
 *   <li>Recursively propagating skip to downstream nodes</li>
 * </ul>
 *
 * <p>Part of the V2ExecutionEventService refactoring - extracted for Single Responsibility.
 *
 * <p><b>DEPRECATION NOTICE:</b> This service is being replaced by automatic skip propagation
 * in {@link com.apimarketplace.orchestrator.services.state.WorkflowStateManager}.
 * The new system uses a push-based model where calling {@code markSkipped()} on a node
 * automatically propagates skip status to all unreachable downstream nodes.
 *
 * <p>Migration strategy:
 * <ul>
 *   <li>Phase 1: WorkflowStateManager tracks skipped nodes in parallel</li>
 *   <li>Phase 2: Verify skip propagation matches between both systems</li>
 *   <li>Phase 3: Switch to WorkflowStateManager for skip propagation</li>
 *   <li>Phase 4: Remove this class once migration is complete</li>
 * </ul>
 *
 * @see EdgeStatusEmitter for edge status emission
 * @see NodeCompletionService for node completion events
 */
@Service
public class V2SkipPropagationService {

    private static final Logger logger = LoggerFactory.getLogger(V2SkipPropagationService.class);

    /** Cascade source tag - sync engine ({@link com.apimarketplace.orchestrator.execution.v2.engine.UnifiedExecutionEngine}). */
    public static final String SOURCE_SYNC = "sync";
    /** Cascade source tag - async agent path ({@link com.apimarketplace.orchestrator.execution.v2.async.AgentAsyncCompletionService}). */
    public static final String SOURCE_ASYNC = "async";

    private static final String METRIC_CASCADE_DESCENDANTS = "orchestrator.skip.cascade.descendants";
    private static final String METRIC_CASCADE_DURATION = "orchestrator.skip.cascade.duration";

    private final StepCompletionOrchestrator stepCompletionOrchestrator;
    private final EdgeStatusService edgeStatusService;
    private final MeterRegistry meterRegistry;

    public V2SkipPropagationService(
            StepCompletionOrchestrator stepCompletionOrchestrator,
            EdgeStatusService edgeStatusService,
            MeterRegistry meterRegistry) {
        this.stepCompletionOrchestrator = stepCompletionOrchestrator;
        this.edgeStatusService = edgeStatusService;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Persist a skipped node with explicit epoch and triggerId for parallel epoch isolation.
     */
    public void persistAndPropagateSkip(
            WorkflowExecution execution,
            ExecutionNode skippedNode,
            String skipSourceNodeId,
            int itemIndex,
            int epoch,
            String triggerId) {
        persistAndPropagateSkip(execution, skippedNode, skipSourceNodeId, itemIndex, 0, epoch, triggerId, false);
    }

    /**
     * Persist a skipped node with per-item vs. global scope.
     *
     * <p><b>perItemScope = false (default):</b> full global skip - updates EpochState's
     * {@code skippedNodeIds} and writes a global stepResult. Used outside split context.
     *
     * <p><b>perItemScope = true:</b> per-item skip - records step_data for the given
     * {@code itemIndex} only, does NOT touch EpochState and does NOT write a global
     * stepResult. Required inside a split: different items may select different
     * branches, so globally marking {@code mcp:apply_tech} as SKIPPED because item 3
     * went to an unwired port would block items 1, 2, 4… from executing their
     * correctly-routed branch.
     */
    public void persistAndPropagateSkip(
            WorkflowExecution execution,
            ExecutionNode skippedNode,
            String skipSourceNodeId,
            int itemIndex,
            int epoch,
            String triggerId,
            boolean perItemScope) {
        persistAndPropagateSkip(execution, skippedNode, skipSourceNodeId, itemIndex, 0, epoch, triggerId, perItemScope);
    }

    public void persistAndPropagateSkip(
            WorkflowExecution execution,
            ExecutionNode skippedNode,
            String skipSourceNodeId,
            int itemIndex,
            Integer iteration,
            int epoch,
            String triggerId,
            boolean perItemScope) {

        String nodeId = skippedNode.getNodeId();
        String nodeLabel = extractLabel(nodeId);
        String skipReason = "Skipped by " + skipSourceNodeId + " - branch not selected";
        int effectiveIteration = iteration != null ? iteration : 0;

        // Cell 9B guard: when the skipped node is ITSELF a merge reached DIRECTLY by an
        // unselected branch port (e.g. switch case_0 -> log_verdict, decision else -> join), do
        // NOT commit it SKIPPED here. Another predecessor (the selected branch) may still
        // complete and make the merge run - committing SKIPPED unconditionally over-skips the
        // merge (and everything downstream). Defer to the predecessor-aware merge handler, which
        // marks only the incoming edge skipped and skips the merge ONLY when ALL predecessors are
        // resolved and none COMPLETED. Global/non-split path only; the per-item split path keeps
        // its existing per-item behavior.
        if (!perItemScope && (skippedNode.isMergeNode() || skippedNode.isImplicitMerge())) {
            handleMergeNodeSuccessor(execution, skippedNode, skipSourceNodeId, nodeId,
                itemIndex, effectiveIteration, new java.util.HashSet<>(), epoch, triggerId);
            return;
        }

        logger.info("📭 [SkipPropagation] Persisting skipped node: nodeId={}, skipReason={}, itemIndex={}, iteration={}, epoch={}, triggerId={}, nodeType={}, perItemScope={}",
            nodeId, skipReason, itemIndex, effectiveIteration, epoch, triggerId, skippedNode.getClass().getSimpleName(), perItemScope);

        if (perItemScope) {
            // Per-item path: record step_data only. No EpochState mutation, no global
            // stepResult - the node may still execute for other items in the split.
            // Note: NodeCounts are NOT incremented here. In split context they are handled
            // by SplitAwareNodeExecutor.persistSkippedItemRecords (which calls
            // batchIncrementSkippedCounts once with the total count of non-routed items).
            // Incrementing here would double-count items that hit a no-match branch
            // (noBranchSelected=true) because those are ALSO counted by persistSkippedItemRecords.
            if (effectiveIteration > 0) {
                stepCompletionOrchestrator.completeSkippedStepWithoutStateUpdate(
                    execution, nodeId, nodeLabel, skipReason, skipSourceNodeId, itemIndex, effectiveIteration, epoch, triggerId);
            } else {
                stepCompletionOrchestrator.completeSkippedStepWithoutStateUpdate(
                    execution, nodeId, nodeLabel, skipReason, skipSourceNodeId, itemIndex, epoch, triggerId);
            }
        } else {
            // Global path: full skip (existing behavior for non-split callers).
            // Use StepCompletionOrchestrator for skipped nodes - single entry point for:
            // 1. DB persistence
            // 2. In-memory state update (epoch-scoped when triggerId available)
            // 3. Streaming event emission
            SkipContext ctx = SkipContext.of(
                execution, nodeId, nodeLabel, skipReason, skipSourceNodeId, itemIndex, effectiveIteration, epoch);
            stepCompletionOrchestrator.completeSkipped(ctx, triggerId);

            // Keep in-memory execution state in sync with DB (root-cause fix for merge skip detection).
            // StepCompletionOrchestrator writes to DB/StateSnapshot but does NOT update
            // WorkflowExecution.stepResults. Without this, execution.getStepResult() returns null
            // for DFS-skipped nodes, causing checkAndSkipMergeIfNoSuccessfulPredecessor to fail.
            //
            // Guard: don't clobber a concurrently-completed result. If another path already
            // wrote a COMPLETED stepResult for this node (parallel branches converging), keep
            // it. Set.add idempotency in EpochState handles the StateSnapshot side; this guard
            // protects the in-memory ConcurrentHashMap. (One of four guards across this service:
            // also at handleMergeNodeSuccessor:382, :475 and emitSkipAndPropagate:529.)
            if (execution.getStepResult(nodeId) == null) {
                execution.setStepResult(nodeId, StepExecutionResult.skipped(nodeId, skipReason));
            }
        }

        // Emit SKIPPED edges from this node to its direct successors (per-item or global,
        // edge counters are always tracked per item so this is safe in both modes).
        // Pass epoch/triggerId so recursive descendants also use epoch-scoped mutations
        emitSkippedOutgoingEdges(execution, skippedNode, itemIndex, effectiveIteration, epoch, triggerId, perItemScope);

        // NOTE: Do NOT recursively propagate skip to successors!
        // Successors might be reachable from other branches (convergence nodes).
        // The execution engine handles this by checking dependencies.
    }

    /**
     * Emit SKIPPED edge events from a skipped node to all its direct successors.
     * Epoch-scoped for parallel epoch isolation.
     */
    private void emitSkippedOutgoingEdges(
            WorkflowExecution execution,
            ExecutionNode skippedNode,
            int itemIndex,
            int epoch,
            String triggerId) {
        emitSkippedOutgoingEdges(execution, skippedNode, itemIndex, 0, epoch, triggerId, false);
    }

    /**
     * Emit SKIPPED edge events with explicit per-item vs. global scope.
     */
    private void emitSkippedOutgoingEdges(
            WorkflowExecution execution,
            ExecutionNode skippedNode,
            int itemIndex,
            int iteration,
            int epoch,
            String triggerId,
            boolean perItemScope) {
        emitSkippedOutgoingEdgesRecursive(execution, skippedNode, itemIndex, iteration, new HashSet<>(), epoch, triggerId, perItemScope);
    }

    /**
     * Recursively emit SKIPPED edge events from a skipped node to all its descendants.
     *
     * <p>This ensures that when a node is skipped, ALL downstream edges are also marked as SKIPPED.
     *
     * @param execution The workflow execution
     * @param skippedNode The skipped node
     * @param itemIndex The item index being processed
     * @param visited Set of already visited node IDs to prevent infinite loops in cyclic graphs
     * @param epoch The epoch for epoch-scoped mutations
     * @param triggerId The trigger ID for epoch-scoped mutations (null for flat fallback)
     */
    private void emitSkippedOutgoingEdgesRecursive(
            WorkflowExecution execution,
            ExecutionNode skippedNode,
            int itemIndex,
            Set<String> visited,
            int epoch,
            String triggerId) {
        emitSkippedOutgoingEdgesRecursive(execution, skippedNode, itemIndex, 0, visited, epoch, triggerId, false);
    }

    private void emitSkippedOutgoingEdgesRecursive(
            WorkflowExecution execution,
            ExecutionNode skippedNode,
            int itemIndex,
            int iteration,
            Set<String> visited,
            int epoch,
            String triggerId,
            boolean perItemScope) {

        String sourceId = skippedNode.getNodeId();

        logger.info("🌀 [SkipRecursive] ENTER: sourceId={}, itemIndex={}, visitedSize={}, visited={}, perItemScope={}",
            sourceId, itemIndex, visited.size(), visited, perItemScope);

        // Avoid revisiting the same node (prevents infinite loops)
        if (!visited.add(sourceId)) {
            logger.info("🌀 [SkipRecursive] ALREADY VISITED: sourceId={}, skipping", sourceId);
            return;
        }

        logger.info("🌀 [SkipRecursive] ADDED to visited: sourceId={}, visitedSize now={}", sourceId, visited.size());

        // Get successors based on node type
        List<ExecutionNode> successors = collectSuccessors(
            execution, skippedNode, sourceId, itemIndex, visited);

        logger.info("🌀 [SkipRecursive] Found {} successors for: {}", successors.size(), sourceId);

        // Process each successor
        processSuccessors(execution, successors, sourceId, itemIndex, iteration, visited, epoch, triggerId, perItemScope);

        logger.info("🌀 [SkipRecursive] EXIT: sourceId={}, itemIndex={}", sourceId, itemIndex);
    }

    /**
     * Collect successors based on node type.
     * Uses polymorphic getAllChildNodes() for branching nodes (Decision, UserApproval, etc.)
     */
    private List<ExecutionNode> collectSuccessors(
            WorkflowExecution execution,
            ExecutionNode skippedNode,
            String sourceId,
            int itemIndex,
            Set<String> visited) {

        List<ExecutionNode> successors = childrenForCascade(skippedNode);
        logger.info("📭 [SkipPropagation] Node {} has {} successors: {}",
            sourceId, successors.size(),
            successors.stream().map(ExecutionNode::getNodeId).toList());
        return successors;
    }

    /**
     * Branching nodes expose port targets through getAllChildNodes(), while linear nodes
     * expose direct successors through getSuccessors().
     */
    private List<ExecutionNode> childrenForCascade(ExecutionNode node) {
        List<ExecutionNode> children = node.getAllChildNodes();
        if (!children.isEmpty()) {
            return children;
        }
        return node.getSuccessors();
    }

    /**
     * Process all successors - emit skip edges and propagate recursively.
     */
    private void processSuccessors(
            WorkflowExecution execution,
            List<ExecutionNode> successors,
            String sourceId,
            int itemIndex,
            int iteration,
            Set<String> visited,
            int epoch,
            String triggerId,
            boolean perItemScope) {

        if (successors.isEmpty()) {
            logger.debug("📭 [SkipPropagation] No successors for skipped node: nodeId={}", sourceId);
            return;
        }

        logger.info("📭 [SkipPropagation] Emitting SKIPPED edges from skipped node: sourceId={}, successorCount={}, itemIndex={}, perItemScope={}",
            sourceId, successors.size(), itemIndex, perItemScope);

        for (ExecutionNode successor : successors) {
            processSuccessor(execution, successor, sourceId, itemIndex, iteration, visited, epoch, triggerId, perItemScope);
        }
    }

    /**
     * Process a single successor node.
     */
    private void processSuccessor(
            WorkflowExecution execution,
            ExecutionNode successor,
            String sourceId,
            int itemIndex,
            int iteration,
            Set<String> visited,
            int epoch,
            String triggerId,
            boolean perItemScope) {

        String targetId = successor.getNodeId();

        // MergeNodes (explicit or implicit) need special handling:
        // Don't block on visited - multiple branches may need to mark their edges as SKIPPED.
        // When ALL predecessors are resolved (skipped/completed/failed), skip the merge and continue.
        if (successor.isMergeNode() || successor.isImplicitMerge()) {
            // Merge handling is global by design (predecessor-convergence tracking relies on
            // global stepResult). Per-item scope does not apply to merge skip logic.
            handleMergeNodeSuccessor(execution, successor, sourceId, targetId, itemIndex, iteration, visited, epoch, triggerId);
            return;
        }

        if (visited.contains(targetId)) {
            return;
        }

        // Regular successor - emit skip edge, complete as skipped, and propagate
        emitSkipAndPropagate(execution, successor, sourceId, targetId, itemIndex, iteration, visited, epoch, triggerId, perItemScope);
    }

    /**
     * Convergence-only handler invoked from {@link #cascadeFailureToSuccessors} when a FAILED
     * node has a merge successor. Checks whether all merge predecessors are resolved and, if
     * none COMPLETED, skips the merge and propagates downstream.
     *
     * <p><b>Edge marking is NOT done here.</b> The direct {@code failedNode → mergeNode} edge
     * has already been marked SKIPPED by {@code EdgeStatusEmitter.emitOutgoingEdges} (failure
     * branch at {@code EdgeStatusEmitter.java:267-282}) which runs inside
     * {@code V2ExecutionEventService.emitNodeComplete} BEFORE the engine invokes the cascade
     * (sync: {@code UnifiedExecutionEngine:534/967}; async: {@code AgentAsyncCompletionService}
     * via {@code emitDownstreamEvents → cascadeFailureToSuccessors}). Re-marking here used to
     * cause a double-increment in {@code state_snapshot.edges} (one batched flush from the
     * emitter + one immediate write from this method, both targeting the same edge key with
     * the same {@code (itemIndex, iteration)}, on a non-idempotent counter).
     */
    public void markEdgeToMergeSkipped(
            WorkflowExecution execution,
            ExecutionNode mergeNode,
            String sourceId,
            int itemIndex,
            int epoch,
            String triggerId) {
        String mergeNodeId = mergeNode.getNodeId();
        logger.info("🔗 [SkipPropagation] Convergence check on MergeNode (edge already marked by EdgeStatusEmitter): {} -> {}, itemIndex={}",
            sourceId, mergeNodeId, itemIndex);

        // Check if ALL predecessors are now resolved
        checkAndSkipMergeIfNoSuccessfulPredecessor(
            execution, mergeNode, sourceId, mergeNodeId, itemIndex, epoch, triggerId);
    }

    /**
     * Check if a merge node should be skipped because none of its predecessors COMPLETED.
     * If all predecessors are resolved (FAILED/SKIPPED) with none COMPLETED,
     * the merge is skipped and propagation continues downstream.
     */
    private void checkAndSkipMergeIfNoSuccessfulPredecessor(
            WorkflowExecution execution,
            ExecutionNode mergeNode,
            String sourceId,
            String mergeNodeId,
            int itemIndex,
            int epoch,
            String triggerId) {

        List<String> predecessorIds = (mergeNode instanceof BaseNode baseNode)
            ? baseNode.getPredecessorIds()
            : List.of();

        if (predecessorIds.isEmpty()) {
            return;
        }

        boolean allResolved = true;
        boolean anyCompleted = false;
        for (String predId : predecessorIds) {
            // Resolve port-qualified predecessor refs (e.g. "core:gate:if") to the bare node id
            // the step results are keyed by ("core:gate") - see predecessorNodeId().
            StepExecutionResult result = execution.getStepResult(predecessorNodeId(predId));
            if (result == null) {
                allResolved = false;
                break;
            }
            if (result.status() == NodeStatus.COMPLETED) {
                anyCompleted = true;
            }
        }

        logger.info("🔗 [SkipPropagation] Merge {} predecessor check: predecessors={}, allResolved={}, anyCompleted={}",
            mergeNodeId, predecessorIds, allResolved, anyCompleted);

        if (allResolved && !anyCompleted) {
            // All predecessors resolved but none COMPLETED → skip the merge
            logger.info("🔗 [SkipPropagation] Merge {} ALL predecessors FAILED/SKIPPED - skipping merge and propagating", mergeNodeId);
            String targetLabel = extractLabel(mergeNodeId);
            String skipReason = "All predecessors failed or skipped";
            // 2026-05-21 CRITICAL 1 fix - same chain as emitSkipAndPropagate
            // global path: the 7-arg overload drops triggerId and buckets the
            // workflow_epochs row under "trigger:default". Use the 8-arg form.
            if (triggerId != null) {
                stepCompletionOrchestrator.completeSkippedStep(
                    execution, mergeNodeId, targetLabel, skipReason, sourceId, itemIndex, epoch, triggerId);
            } else {
                stepCompletionOrchestrator.completeSkippedStep(
                    execution, mergeNodeId, targetLabel, skipReason, sourceId, itemIndex);
            }

            // Keep in-memory execution state in sync with DB.
            // Guard against clobbering a concurrent COMPLETED (see persistAndPropagateSkip:129).
            if (execution.getStepResult(mergeNodeId) == null) {
                execution.setStepResult(mergeNodeId, StepExecutionResult.skipped(mergeNodeId, skipReason));
            }

            // Propagate skip to downstream nodes
            // Don't pre-add mergeNodeId - emitSkippedOutgoingEdgesRecursive will add it
            // and then process successors. Pre-adding would cause immediate exit.
            Set<String> visited = new HashSet<>();
            emitSkippedOutgoingEdgesRecursive(execution, mergeNode, itemIndex, visited, epoch, triggerId);
        }
    }

    /**
     * Handle MergeNode successor - mark edge as skipped, then check if ALL predecessors
     * are now resolved. If every predecessor is SKIPPED, skip the merge itself and
     * continue propagation to its successors.
     *
     * <p>Do NOT add the merge to the visited set on first encounter - a second branch
     * may still need to mark its edge. Only add to visited once the merge is actually
     * skipped (preventing double-skip).
     */
    private void handleMergeNodeSuccessor(
            WorkflowExecution execution,
            ExecutionNode mergeNode,
            String sourceId,
            String targetId,
            int itemIndex,
            int iteration,
            Set<String> visited,
            int epoch,
            String triggerId) {

        // 1. Always mark the incoming edge as SKIPPED
        if (iteration > 0) {
            edgeStatusService.markEdgeSkipped(execution, sourceId, targetId, itemIndex, iteration);
        } else {
            edgeStatusService.markEdgeSkipped(execution, sourceId, targetId, itemIndex);
        }
        logger.info("🔗 [SkipPropagation] Edge to MergeNode SKIPPED: {} -> {}, itemIndex={}",
            sourceId, targetId, itemIndex);

        // 2. If we already processed (skipped) this merge on a previous branch, stop
        if (visited.contains(targetId)) {
            logger.info("🔗 [SkipPropagation] Merge {} already skipped by earlier branch, edge marked only", targetId);
            return;
        }

        // 3. Check if ALL predecessors of the merge are now resolved
        List<String> predecessorIds = (mergeNode instanceof BaseNode baseNode)
            ? baseNode.getPredecessorIds()
            : List.of();

        if (predecessorIds.isEmpty()) {
            logger.info("🔗 [SkipPropagation] Merge {} has no predecessorIds, not propagating skip", targetId);
            return;
        }

        boolean allResolved = true;
        boolean anyCompleted = false;
        for (String predId : predecessorIds) {
            // Resolve port-qualified predecessor refs (e.g. "agent:risk_screen:pass") to the bare
            // node id ("agent:risk_screen") that both stepResults and the visited set are keyed by.
            String predNodeId = predecessorNodeId(predId);
            // Check both: in-memory execution results AND the visited set.
            // The visited set tracks nodes skipped during THIS DFS traversal,
            // which may not yet be reflected in execution.getStepResult() because
            // completeSkippedStep() persists to DB/StateSnapshot but does NOT
            // update the in-memory WorkflowExecution.stepResults map.
            StepExecutionResult result = execution.getStepResult(predNodeId);
            boolean skippedInThisDfs = visited.contains(predNodeId);

            if (result == null && !skippedInThisDfs) {
                allResolved = false;
                break;
            }
            if (result != null && result.status() == NodeStatus.COMPLETED) {
                anyCompleted = true;
            }
        }

        logger.info("🔗 [SkipPropagation] Merge {} predecessors={}, allResolved={}, anyCompleted={}",
            targetId, predecessorIds, allResolved, anyCompleted);

        if (!allResolved) {
            // Not all predecessors resolved yet - wait for the remaining branches
            logger.info("🔗 [SkipPropagation] Merge {} waiting for more predecessors", targetId);
            return;
        }

        if (!anyCompleted) {
            // ALL predecessors are FAILED/SKIPPED (none COMPLETED) → skip the merge and propagate
            logger.info("🔗 [SkipPropagation] Merge {} ALL predecessors FAILED/SKIPPED - skipping merge and propagating", targetId);
            String targetLabel = extractLabel(targetId);
            String skipReason = "All predecessors failed or skipped";
            // 2026-05-21 CRITICAL 1 fix - third drift site on the merge-skip
            // path (see emitSkipAndPropagate:561 + checkAndSkipMergeIfNoSuccessfulPredecessor:406).
            // The 7-arg overload drops triggerId; 8-arg threads it through.
            if (triggerId != null) {
                if (iteration > 0) {
                    stepCompletionOrchestrator.completeSkippedStep(
                        execution, targetId, targetLabel, skipReason, sourceId, itemIndex, iteration, epoch, triggerId);
                } else {
                    stepCompletionOrchestrator.completeSkippedStep(
                        execution, targetId, targetLabel, skipReason, sourceId, itemIndex, epoch, triggerId);
                }
            } else {
                stepCompletionOrchestrator.completeSkippedStep(
                    execution, targetId, targetLabel, skipReason, sourceId, itemIndex);
            }

            // Keep in-memory execution state in sync with DB.
            // Guard against clobbering a concurrent COMPLETED (see persistAndPropagateSkip:129).
            if (execution.getStepResult(targetId) == null) {
                execution.setStepResult(targetId, StepExecutionResult.skipped(targetId, skipReason));
            }

            emitSkippedOutgoingEdgesRecursive(execution, mergeNode, itemIndex, visited, epoch, triggerId);
        } else {
            // At least one predecessor COMPLETED - merge should execute normally
            logger.info("🔗 [SkipPropagation] Merge {} has COMPLETED predecessor - merge will execute normally", targetId);
            visited.add(targetId);
        }
    }

    /**
     * Emit skip edge, complete node as skipped, and propagate to descendants.
     * Uses epoch-scoped completeSkippedStep when triggerId is available.
     */
    private void emitSkipAndPropagate(
            WorkflowExecution execution,
            ExecutionNode successor,
            String sourceId,
            String targetId,
            int itemIndex,
            int iteration,
            Set<String> visited,
            int epoch,
            String triggerId,
            boolean perItemScope) {

        String targetLabel = extractLabel(targetId);
        String skipReason = "Predecessor " + sourceId + " was skipped";

        logger.info("🔥 [SkipPropagate] START: {} -> {}, itemIndex={}, visitedSize={}, perItemScope={}",
            sourceId, targetId, itemIndex, visited.size(), perItemScope);

        logger.info("🔥 [SkipPropagate] EMIT SKIP EDGE: {} -> {}, itemIndex={}", sourceId, targetId, itemIndex);
        if (iteration > 0) {
            edgeStatusService.markEdgeSkipped(execution, sourceId, targetId, itemIndex, iteration);
        } else {
            edgeStatusService.markEdgeSkipped(execution, sourceId, targetId, itemIndex);
        }
        logger.info("🔗 [SkipPropagation] Skipped node edge SKIPPED: {} -> {}, itemIndex={}",
            sourceId, targetId, itemIndex);

        logger.info("🔥 [SkipPropagate] COMPLETE SKIPPED STEP: {}", targetId);
        if (perItemScope) {
            // Per-item: persist step_data for this item only, no EpochState or global stepResult mutation.
            // The node may still execute for other items in the split.
            // NodeCounts handled by SplitAwareNodeExecutor.persistSkippedItemRecords - see
            // comment in persistAndPropagateSkip above.
            if (iteration > 0) {
                stepCompletionOrchestrator.completeSkippedStepWithoutStateUpdate(
                    execution, targetId, targetLabel, skipReason, sourceId, itemIndex, iteration, epoch, triggerId);
            } else {
                stepCompletionOrchestrator.completeSkippedStepWithoutStateUpdate(
                    execution, targetId, targetLabel, skipReason, sourceId, itemIndex, epoch, triggerId);
            }
        } else {
            // Global path - use epoch+triggerId-scoped version when triggerId is
            // available so the workflow_epochs row lands under the correct DAG.
            //
            // 2026-05-21 CRITICAL 1 fix (e2e audit): pre-fix this branch called
            // the 7-arg completeSkippedStep(...,epoch) which falls through to
            // completeSkipped(ctx) → completeSkipped(ctx, null) and writes the
            // workflow_epochs row under "trigger:default" instead of the real
            // trigger. Symptom: per-epoch UI view stale for every cascaded skip.
            // The 8-arg overload threads triggerId all the way through.
            if (triggerId != null) {
                if (iteration > 0) {
                    stepCompletionOrchestrator.completeSkippedStep(
                        execution, targetId, targetLabel, skipReason, sourceId, itemIndex, iteration, epoch, triggerId);
                } else {
                    stepCompletionOrchestrator.completeSkippedStep(
                        execution, targetId, targetLabel, skipReason, sourceId, itemIndex, epoch, triggerId);
                }
            } else {
                stepCompletionOrchestrator.completeSkippedStep(
                    execution, targetId, targetLabel, skipReason, sourceId, itemIndex);
            }
            // Keep in-memory execution state in sync with DB.
            // Guard against clobbering a concurrent COMPLETED (see persistAndPropagateSkip:129).
            if (execution.getStepResult(targetId) == null) {
                execution.setStepResult(targetId, StepExecutionResult.skipped(targetId, skipReason));
            }
        }

        logger.info("🔥 [SkipPropagate] RECURSE to descendants of: {}", targetId);
        emitSkippedOutgoingEdgesRecursive(execution, successor, itemIndex, iteration, visited, epoch, triggerId, perItemScope);

        logger.info("🔥 [SkipPropagate] END: {} -> {}, itemIndex={}", sourceId, targetId, itemIndex);
    }

    /**
     * Cascade SKIPPED status to all direct successors of a node that just FAILED.
     *
     * <p>For each direct successor of {@code failedNode}:
     * <ul>
     *   <li>If the successor is a (explicit or implicit) merge → call
     *       {@link #markEdgeToMergeSkipped} which delegates to
     *       {@link #checkAndSkipMergeIfNoSuccessfulPredecessor}. <b>Merge handling is
     *       global by design</b> regardless of {@code perItemScope}: the merge
     *       analyzer reads {@code execution.getStepResult(predId)} which is the
     *       global view. A per-item-only skip therefore does NOT advance a
     *       downstream-of-split merge - known limitation, mirrors sync engine.</li>
     *   <li>Otherwise → call {@link #persistAndPropagateSkip} which recurses through
     *       descendants, persists SKIPPED rows in {@code workflow_step_data}
     *       (idempotent via {@code INSERT … ON CONFLICT DO NOTHING} on
     *       {@code idx_workflow_step_data_unique_v6}), and updates
     *       {@code EpochState.skippedNodeIds} in StateSnapshot (Set, idempotent).</li>
     * </ul>
     *
     * <p><b>Streaming events:</b> each cascaded SKIPPED node emits {@code step.skipped}
     * via {@code eventPublisher.emitStep} inside {@code completeSkippedStep} /
     * {@code completeSkippedStepWithoutStateUpdate}.
     *
     * <p><b>Telemetry:</b> increments {@code orchestrator.skip.cascade.descendants}
     * counter (with tag {@code source=sync|async}) and records cascade duration via
     * {@code orchestrator.skip.cascade.duration} timer. Counter is monotonic by
     * Micrometer contract; under Redis at-least-once re-delivery the counter may
     * over-count compared to distinct DB rows (which stay 1× via {@code ON CONFLICT
     * DO NOTHING}). This drift is cosmetic and accepted.
     *
     * <p><b>Idempotency layers:</b> DB upsert + {@code EpochState.skippedNodeIds} Set
     * + per-call {@code visited} set inside {@code emitSkippedOutgoingEdgesRecursive}
     * + {@code if (getStepResult(id) == null)} guards at four sites in this service
     * preventing in-memory clobber.
     *
     * <p><b>Null safety:</b> if {@code failedNode} is {@code null} the method logs
     * and returns without throwing - mirror of the wiring guards in
     * {@code AgentAsyncCompletionService}.
     *
     * @param failedNode    The failed node whose successors should be cascaded.
     *                      May be {@code null} (no-op + log).
     * @param perItemScope  {@code true} when called from inside a split context for a
     *                      single failed item (per-item descendants only);
     *                      {@code false} for global linear failure.
     * @param source        {@link #SOURCE_SYNC} when called by
     *                      {@code UnifiedExecutionEngine}, {@link #SOURCE_ASYNC} when
     *                      called by {@code AgentAsyncCompletionService}. Used as the
     *                      {@code source} Micrometer tag.
     */
    public void cascadeFailureToSuccessors(
            WorkflowExecution execution,
            ExecutionNode failedNode,
            int itemIndex,
            int epoch,
            String triggerId,
            boolean perItemScope,
            String source) {

        if (failedNode == null) {
            logger.warn("[SkipCascade] cascadeFailureToSuccessors invoked with null failedNode (no-op): itemIndex={}, epoch={}, triggerId={}, source={}",
                itemIndex, epoch, triggerId, source);
            return;
        }

        String failedNodeId = failedNode.getNodeId();
        List<ExecutionNode> successors = perItemScope
            ? childrenForCascade(failedNode)
            : failedNode.getSuccessors();

        long startNs = System.nanoTime();
        int cascadedCount = 0;
        try {
            for (ExecutionNode successor : successors) {
                // Merge nodes must NOT be skipped directly - they have their own
                // shouldSkip() logic and need all predecessors resolved before
                // deciding. Only mark the edge as skipped + check convergence.
                // Global by design: perItemScope is intentionally ignored for merges.
                if (successor.isMergeNode() || successor.isImplicitMerge()) {
                    markEdgeToMergeSkipped(
                        execution, successor, failedNodeId, itemIndex, epoch, triggerId);
                } else {
                    persistAndPropagateSkip(
                        execution, successor, failedNodeId, itemIndex, epoch, triggerId, perItemScope);
                }
                cascadedCount++;
            }
            logger.info("[SkipCascade] Propagating SKIPPED to {} downstream nodes from failed nodeId={}, itemIndex={}, epoch={}, triggerId={}, perItemScope={}, source={}",
                cascadedCount, failedNodeId, itemIndex, epoch, triggerId, perItemScope, source);
            if (meterRegistry != null && cascadedCount > 0) {
                Counter.builder(METRIC_CASCADE_DESCENDANTS)
                    .description("Number of descendants cascaded as SKIPPED after a node failure")
                    .tags(Tags.of("source", source))
                    .register(meterRegistry)
                    .increment(cascadedCount);
            }
        } finally {
            if (meterRegistry != null) {
                Timer.builder(METRIC_CASCADE_DURATION)
                    .description("Wall-clock duration of a single failure-cascade invocation")
                    .tags(Tags.of("source", source))
                    .register(meterRegistry)
                    .record(System.nanoTime() - startNs, TimeUnit.NANOSECONDS);
            }
        }
    }

    /**
     * Extract label from node ID (e.g., "mcp:my_step" -> "my_step").
     * Delegates to {@link LabelNormalizer#extractLabel(String)}.
     *
     * @param nodeId The node ID
     * @return The extracted label
     */
    private String extractLabel(String nodeId) {
        if (nodeId == null) {
            return "unknown";
        }
        String label = LabelNormalizer.extractLabel(nodeId);
        return label != null ? label : "unknown";
    }

    /**
     * Resolve a merge predecessor reference to its bare node id. When a merge is fed DIRECTLY by a
     * branching node's port, {@code getPredecessorIds()} returns PORT-QUALIFIED refs (e.g.
     * {@code "core:gate:if"}, {@code "agent:risk_screen:pass"}), but step results and the visited
     * set are keyed by the bare node id ({@code "core:gate"} / {@code "agent:risk_screen"}). Without
     * resolving, the skip-cascade convergence check looks up a port-qualified key that is never
     * present, treats the predecessor as unresolved, and the merge waits forever (run hangs). Non
     * port-qualified refs and unparseable refs are returned unchanged.
     */
    private static String predecessorNodeId(String predecessorRef) {
        EdgeRefParser.EdgeRef ref = EdgeRefParser.parse(predecessorRef);
        return ref != null ? ref.nodeType() + ":" + ref.nodeLabel() : predecessorRef;
    }
}
