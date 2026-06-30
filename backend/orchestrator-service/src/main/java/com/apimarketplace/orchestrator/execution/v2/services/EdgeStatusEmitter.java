package com.apimarketplace.orchestrator.execution.v2.services;

import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.execution.v2.constants.ExecutionMetadataKeys;
import com.apimarketplace.orchestrator.execution.v2.nodes.ExecutionNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;
import com.apimarketplace.orchestrator.services.streaming.EdgeStatusService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Service responsible for emitting edge status events during workflow execution.
 *
 * <p>This service manages:
 * <ul>
 *   <li>Emitting incoming edge events (RUNNING status)</li>
 *   <li>Emitting outgoing edge events (RUNNING → COMPLETED)</li>
 *   <li>Special handling for DecisionNode edges (selected vs skipped branches)</li>
 * </ul>
 *
 * <p>Part of the V2ExecutionEventService refactoring - extracted for Single Responsibility.
 *
 * @see NodeCompletionService for node completion events
 * @see V2SkipPropagationService for skip propagation
 */
@Service
public class EdgeStatusEmitter {

    private static final Logger logger = LoggerFactory.getLogger(EdgeStatusEmitter.class);

    private final EdgeStatusService edgeStatusService;
    private final V2SkipPropagationService skipPropagationService;

    public EdgeStatusEmitter(
            EdgeStatusService edgeStatusService,
            V2SkipPropagationService skipPropagationService) {
        this.edgeStatusService = edgeStatusService;
        this.skipPropagationService = skipPropagationService;
    }

    /**
     * Emit edge events for node's incoming connections.
     *
     * <p>NOTE: In V2 tree-based execution, we do NOT mark all incoming edges as RUNNING
     * because the node doesn't know which parent actually triggered it.
     *
     * <p>Example: mcp:step3 has two parents (mcp:step2 via IF_A, mcp:list_bases via IF_B)
     * If we mark all incoming edges RUNNING, both edges get marked even though
     * only one path was actually taken for this item.
     *
     * <p>Edge RUNNING/COMPLETED status is handled by the SOURCE node via emitOutgoingEdges().
     *
     * @param execution The workflow execution
     * @param node The execution node
     * @param itemIndex The item index being processed
     */
    public void emitIncomingEdges(
            WorkflowExecution execution,
            ExecutionNode node,
            int itemIndex) {

        String targetId = node.getNodeId();
        logger.debug("📊 [EdgeStatus] Node starting: nodeId={}, itemIndex={} (incoming edges handled by source)",
            targetId, itemIndex);

        // NOTE: Do NOT call markIncomingEdgesRunning here!
        // See explanation above - the source node handles edge status via emitOutgoingEdges()
    }

    /**
     * Emit edge events for node's outgoing connections, with optional skip propagation suppression.
     *
     * <p>For DecisionNode: emits COMPLETED for selected branch, SKIPPED for other branches.
     * <p>For other nodes: emits COMPLETED for all successors.
     *
     * <p>When {@code suppressSkipPropagation} is true, edge statuses (COMPLETED/SKIPPED) are
     * still emitted, but skip propagation to target nodes is disabled. This is used when
     * Decision/Switch nodes execute per-item in split context: each item may select a different
     * branch, so propagating skip for one item's non-selected branch would incorrectly mark
     * the target node as SKIPPED even though another item selected that branch.
     *
     * @param execution The workflow execution
     * @param node The execution node
     * @param itemIndex The item index being processed
     * @param iteration The current loop iteration (null if not in a loop)
     * @param result The node execution result
     * @param suppressSkipPropagation if true, do not propagate skip to target nodes
     * @param epoch The epoch for epoch-scoped mutations
     * @param triggerId The trigger ID for epoch-scoped mutations (null for flat fallback)
     */
    public void emitOutgoingEdges(
            WorkflowExecution execution,
            ExecutionNode node,
            int itemIndex,
            Integer iteration,
            NodeExecutionResult result,
            boolean suppressSkipPropagation,
            int epoch,
            String triggerId) {
        emitOutgoingEdges(execution, node, itemIndex, iteration, result, suppressSkipPropagation, epoch, triggerId, false);
    }

    /**
     * Emit outgoing edges with explicit split scope.
     *
     * <p>When {@code splitScope=true}, skip propagation writes per-item step_data but does NOT
     * update EpochState.skippedNodeIds or the global stepResult. This prevents one split item's
     * no-match routing from globally marking downstream nodes as SKIPPED and blocking other items.
     */
    public void emitOutgoingEdges(
            WorkflowExecution execution,
            ExecutionNode node,
            int itemIndex,
            Integer iteration,
            NodeExecutionResult result,
            boolean suppressSkipPropagation,
            int epoch,
            String triggerId,
            boolean splitScope) {

        // Early validation - fail fast if required parameters are missing
        if (execution == null || node == null) {
            logger.warn("📊 [EdgeStatus] Skipping emitOutgoingEdges: execution={}, node={}",
                execution != null, node != null);
            return;
        }

        String sourceId = node.getNodeId();

        // Unified handling for all branching nodes (Decision, Option, Approval, Classify/Guardrail)
        // Uses polymorphic isBranchingNode() from ExecutionNode interface
        if (node.isBranchingNode()) {
            emitBranchingNodeEdges(execution, node, itemIndex, iteration, result, suppressSkipPropagation, epoch, triggerId, splitScope);
            return;
        }

        // ExitNode has no outgoing edges - branch ends here
        if (node.isExitNode()) {
            logger.info("🚪 [EdgeStatus] ExitNode reached - no outgoing edges: {}", sourceId);
            return;
        }

        // StopOnErrorNode has no outgoing edges - entire workflow stops here
        if (node.isStopOnErrorNode()) {
            logger.info("🛑 [EdgeStatus] StopOnErrorNode reached - no outgoing edges: {}", sourceId);
            return;
        }

        // EndNode has no outgoing edges - workflow completion marker
        if (node.isEndNode()) {
            logger.info("🏁 [EdgeStatus] EndNode reached - no outgoing edges: {}", sourceId);
            return;
        }

        // Special handling for Split - emit edges for ALL child items
        if (node.isSplitNode() && result != null) {
            int itemCount = 0;
            boolean isSplitHandled = false;

            // New simplified split system: uses "item_count" in output with "terminated" flag
            if (ExecutionMetadataKeys.isTerminated(result.output())) {
                Integer count = ExecutionMetadataKeys.getItemCount(result.output());
                if (count != null) {
                    itemCount = count;
                }
                isSplitHandled = true;
                logger.info("📊 [EdgeStatus] Simplified Split completed: {} items, emitting edges from {}",
                    itemCount, sourceId);
            }
            // Legacy split system: uses "spawn_items" in metadata
            else if (result.metadata() != null) {
                Object spawnItems = result.metadata().get("spawn_items");
                if (spawnItems instanceof List<?> items) {
                    itemCount = items.size();
                    isSplitHandled = true;
                    logger.info("📊 [EdgeStatus] Legacy Split completed: {} items, emitting edges from {}",
                        itemCount, sourceId);
                }
            }

            if (isSplitHandled) {
                if (itemCount > 0) {
                    // Get body nodes directly using polymorphic getBodyNodes()
                    // getBodyNodes() is now in ExecutionNode interface
                    List<ExecutionNode> bodyNodes = node.getBodyNodes();
                    if (bodyNodes == null || bodyNodes.isEmpty()) {
                        // Fallback to successors if no body nodes configured
                        bodyNodes = node.getNextNodes(result);
                        logger.warn("📊 [EdgeStatus] Split has no body nodes, using successors: nodeId={}", sourceId);
                    }

                    logger.info("📊 [EdgeStatus] Split emitting {} edges to {} body nodes from {}",
                        itemCount, bodyNodes.size(), sourceId);

                    for (int childIndex = 0; childIndex < itemCount; childIndex++) {
                        int childItemIndex = itemIndex * 1000 + childIndex;
                        for (ExecutionNode successor : bodyNodes) {
                            String targetId = successor.getNodeId();
                            edgeStatusService.markEdgeCompleted(execution, sourceId, targetId, childItemIndex, null);
                            logger.debug("📊 [EdgeStatus] Split edge COMPLETED: {} -> {}, childItemIndex={}",
                                sourceId, targetId, childItemIndex);
                        }
                    }
                } else {
                    // Empty split (0 items): mark outgoing edges as SKIPPED and propagate skip to body nodes
                    // No items to process means body nodes and their descendants are skipped
                    // Get body nodes using polymorphic getBodyNodes()
                    // getBodyNodes() is now in ExecutionNode interface
                    List<ExecutionNode> bodyNodes = node.getBodyNodes();
                    if (bodyNodes == null || bodyNodes.isEmpty()) {
                        bodyNodes = node.getNextNodes(result);
                        logger.warn("📊 [EdgeStatus] Empty Split has no body nodes, using successors: nodeId={}", sourceId);
                    }
                    logger.info("📊 [EdgeStatus] Empty Split (0 items): marking {} body nodes as SKIPPED from {}",
                        bodyNodes.size(), sourceId);
                    for (ExecutionNode successor : bodyNodes) {
                        String targetId = successor.getNodeId();
                        // Mark edge as SKIPPED
                        edgeStatusService.markEdgeSkipped(execution, sourceId, targetId, itemIndex, null);
                        // Propagate SKIPPED status to body node and all its descendants
                        logger.info("📊 [EdgeStatus] Propagating SKIPPED to split body node: {}", targetId);
                        if (iteration != null && iteration > 0) {
                            skipPropagationService.persistAndPropagateSkip(execution, successor, sourceId, itemIndex, iteration, epoch, triggerId, false);
                        } else {
                            skipPropagationService.persistAndPropagateSkip(execution, successor, sourceId, itemIndex, epoch, triggerId);
                        }
                    }
                }
                // Empty or non-empty, split edge handling is complete
                return;
            }
            // If not handled by either system, fall through to default edge emission
        }

        // Default: emit edges for selected successors only (RUNNING then COMPLETED)
        List<ExecutionNode> nextNodes = node.getNextNodes(result);

        // If node was SKIPPED (e.g., no items routed in split context),
        // mark outgoing edges as SKIPPED - the node didn't execute, so edges aren't traversed.
        if (result != null && result.isSkipped()) {
            List<ExecutionNode> allSuccessors = node.getSuccessors();
            if (allSuccessors.isEmpty()) {
                allSuccessors = nextNodes;
            }
            for (ExecutionNode successor : allSuccessors) {
                String targetId = successor.getNodeId();
                edgeStatusService.markEdgeSkipped(execution, sourceId, targetId, itemIndex, iteration);
                logger.info("🔗 [EdgeStatus] Skipped node edge SKIPPED: {} -> {}, itemIndex={}", sourceId, targetId, itemIndex);
            }
            return;
        }

        // If node FAILED, mark only the direct outgoing edges as SKIPPED here.
        //
        // ─────────────────────────────────────────────────────────────────────────────
        // CRITICAL CONTRACT (do not break - see prod incident run_<id>,
        // workflow f084a6f5, May 2026): this block is the SINGLE writer for the direct
        // failed-node → successor edge in StateSnapshot. V2SkipPropagationService.
        // markEdgeToMergeSkipped used to also mark this edge, causing a +2 increment on
        // a single failure (StateSnapshot.EdgeCounts.increment is non-idempotent). The
        // fix removed the cascade-side write and now relies on this branch as the only
        // direct-edge writer. If you remove or short-circuit this branch, the failed→merge
        // edge will silently fall to skipped:0 (no convergence-check covers it).
        // ─────────────────────────────────────────────────────────────────────────────
        //
        // Node-level cascade (writing SKIPPED rows for descendants and updating
        // EpochState.skippedNodeIds in StateSnapshot) is the responsibility of the
        // calling engine, via V2SkipPropagationService.cascadeFailureToSuccessors(...).
        // Both UnifiedExecutionEngine (sync, see :531-549 / :976-994) and
        // AgentAsyncCompletionService (async, in deliverUnderLock + traverseSuccessorsPerItem)
        // invoke that routine after FAILED persistence so descendants get full SKIPPED
        // rows + EpochState mutation + recursive edge cascade.
        //
        // This method only handles direct edges so the frontend EdgeCounts update
        // immediately, and it deliberately does NOT recurse - calling
        // skipPropagationService.persistAndPropagateSkip() here would double-cascade.
        if (result != null && result.isFailure()) {
            List<ExecutionNode> allSuccessors = node.getSuccessors();
            if (allSuccessors.isEmpty()) {
                allSuccessors = nextNodes; // Fallback for nodes without successor tracking
            }

            logger.info("📊 [EdgeStatus] Node FAILED, marking {} direct outgoing edges as SKIPPED (node-level cascade is engine's responsibility): nodeId={}, itemIndex={}",
                allSuccessors.size(), sourceId, itemIndex);

            for (ExecutionNode successor : allSuccessors) {
                String targetId = successor.getNodeId();
                edgeStatusService.markEdgeSkipped(execution, sourceId, targetId, itemIndex, iteration);
                logger.info("🔗 [EdgeStatus] Failed node edge SKIPPED: {} -> {}, itemIndex={}, iteration={}",
                    sourceId, targetId, itemIndex, iteration);
            }
            return;
        }

        logger.info("📊 [EdgeStatus] Emitting {} edge events from nodeId={}, type={}, iteration={}, targetIds={}",
            nextNodes.size(), sourceId, node.getType(), iteration,
            nextNodes.stream().map(ExecutionNode::getNodeId).toList());

        // Emit edge events for each successor
        for (ExecutionNode successor : nextNodes) {
            String targetId = successor.getNodeId();

            // Mark edge as RUNNING first (transitional state before child executes)
            // Pass iteration so RUNNING and COMPLETED use the same key
            edgeStatusService.markEdgeRunning(execution, sourceId, targetId, itemIndex, iteration);

            // Then mark edge as COMPLETED (source finished, child will start)
            // Pass iteration so edges inside loops count item × iteration
            edgeStatusService.markEdgeCompleted(execution, sourceId, targetId, itemIndex, iteration);

            logger.debug("🔗 [EdgeStatus] Edge event: {} -> {}, itemIndex={}, iteration={}",
                sourceId, targetId, itemIndex, iteration);
        }
    }

    /**
     * Unified method for emitting edge events for branching nodes, with optional skip suppression.
     *
     * <p>Works with DecisionNode, OptionNode, UserApprovalNode, and AgentNode (classify/guardrail).
     *
     * <p>Uses port-qualified source IDs (e.g., "agent:classify:category_0") so that
     * each branch edge has a unique key, even when multiple branches share the same target.
     * This is critical for classify nodes where all categories may point to the same node.
     *
     * <p>When {@code suppressSkipPropagation} is true, edges are marked as SKIPPED but
     * target nodes are NOT marked as SKIPPED in the database. This is critical for
     * Decision/Switch nodes executing per-item in split context where different items
     * select different branches.
     *
     * @param execution The workflow execution
     * @param node The branching node (must satisfy isBranchingNode())
     * @param itemIndex The item index being processed
     * @param iteration The current loop iteration (null if not in a loop)
     * @param result The node execution result
     * @param suppressSkipPropagation if true, do not propagate skip to target nodes
     * @param epoch The epoch for epoch-scoped mutations
     * @param triggerId The trigger ID for epoch-scoped mutations (null for flat fallback)
     */
    public void emitBranchingNodeEdges(
            WorkflowExecution execution,
            ExecutionNode node,
            int itemIndex,
            Integer iteration,
            NodeExecutionResult result,
            boolean suppressSkipPropagation,
            int epoch,
            String triggerId) {
        emitBranchingNodeEdges(execution, node, itemIndex, iteration, result, suppressSkipPropagation, epoch, triggerId, false);
    }

    public void emitBranchingNodeEdges(
            WorkflowExecution execution,
            ExecutionNode node,
            int itemIndex,
            Integer iteration,
            NodeExecutionResult result,
            boolean suppressSkipPropagation,
            int epoch,
            String triggerId,
            boolean splitScope) {

        // Early validation
        if (execution == null || node == null) {
            logger.warn("🌿 [BranchingEdges] Skipping: execution={}, node={}",
                execution != null, node != null);
            return;
        }

        String sourceId = node.getNodeId();
        String nodeTypeName = node.getType().name();

        logger.info("🌿 [BranchingEdges] START: sourceId={}, type={}, itemIndex={}, iteration={}, splitScope={}",
            sourceId, nodeTypeName, itemIndex, iteration, splitScope);

        // Get port-based branch targets and selected port
        java.util.Map<String, java.util.List<ExecutionNode>> branchTargets = node.getBranchTargetsByPort();
        String selectedPort = node.getSelectedPort(result);

        // Use port-qualified emission when port info is available
        if (!branchTargets.isEmpty() && selectedPort != null) {
            emitPortQualifiedEdges(execution, node, sourceId, nodeTypeName,
                branchTargets, selectedPort, itemIndex, iteration, result, suppressSkipPropagation, epoch, triggerId, splitScope);
        } else {
            // Fallback: target-based emission when port info is not available
            emitTargetBasedEdges(execution, node, sourceId, nodeTypeName,
                itemIndex, iteration, result, suppressSkipPropagation, epoch, triggerId, splitScope);
        }

        logger.info("🌿 [BranchingEdges] END: sourceId={}, itemIndex={}, iteration={}", sourceId, itemIndex, iteration);
    }

    /**
     * Port-qualified edge emission: iterates over (port, targets) pairs.
     * Each port becomes part of the source ID (e.g., "agent:classify:category_0"),
     * ensuring unique edge keys even when multiple ports share the same target.
     */
    private void emitPortQualifiedEdges(
            WorkflowExecution execution,
            ExecutionNode node,
            String sourceId,
            String nodeTypeName,
            java.util.Map<String, java.util.List<ExecutionNode>> branchTargets,
            String selectedPort,
            int itemIndex,
            Integer iteration,
            NodeExecutionResult result,
            boolean suppressSkipPropagation,
            int epoch,
            String triggerId,
            boolean splitScope) {

        // Get selected node IDs for skip propagation guard
        List<ExecutionNode> selectedNodes = node.getNextNodes(result);
        Set<String> selectedNodeIds = new HashSet<>();
        for (ExecutionNode s : selectedNodes) {
            selectedNodeIds.add(s.getNodeId());
        }

        // shouldPropagateSkipOnBranching() may return false for classify nodes (because
        // different split items can select different categories, so the skip is per-item).
        // BUT the per-item argument only applies inside a split. Two cases force propagation:
        //  - NO category matched at all (selectedNodes empty) -> ALL children must be skipped; and
        //  - NOT in a split (splitScope=false) -> there is exactly ONE item, so the unselected
        //    branches MUST be persisted SKIPPED, otherwise a downstream merge waits for them
        //    forever (the Ops Health Judge prod orphan: classify->merge log_verdict never ran).
        // In a split (splitScope=true) we keep the existing per-item behavior unchanged.
        boolean noBranchSelected = selectedNodeIds.isEmpty();
        boolean shouldPropagate = (node.shouldPropagateSkipOnBranching() || noBranchSelected || !splitScope) && !suppressSkipPropagation;

        // Track which targets already had skip propagated (avoid duplicate propagation)
        Set<String> propagatedTargets = new HashSet<>();

        logger.info("📊 [EdgeStatus] {} port-qualified edges: sourceId={}, selectedPort={}, ports={}, itemIndex={}",
            nodeTypeName, sourceId, selectedPort, branchTargets.keySet(), itemIndex);

        for (java.util.Map.Entry<String, java.util.List<ExecutionNode>> entry : branchTargets.entrySet()) {
            String port = entry.getKey();
            java.util.List<ExecutionNode> targets = entry.getValue();
            String portQualifiedSource = sourceId + ":" + port;
            boolean isSelected = port.equals(selectedPort);

            for (ExecutionNode target : targets) {
                String targetId = target.getNodeId();

                if (isSelected) {
                    edgeStatusService.markEdgeRunning(execution, portQualifiedSource, targetId, itemIndex, iteration);
                    edgeStatusService.markEdgeCompleted(execution, portQualifiedSource, targetId, itemIndex, iteration);
                    logger.info("🔗 [EdgeStatus] {} edge COMPLETED: {} -> {}, itemIndex={}, iteration={}",
                        nodeTypeName, portQualifiedSource, targetId, itemIndex, iteration);
                } else {
                    edgeStatusService.markEdgeSkipped(execution, portQualifiedSource, targetId, itemIndex, iteration);
                    logger.info("🔗 [EdgeStatus] {} edge SKIPPED: {} -> {}, itemIndex={}, iteration={}",
                        nodeTypeName, portQualifiedSource, targetId, itemIndex, iteration);

                    // Propagate skip to descendants (once per target, only if target isn't also selected)
                    if (shouldPropagate && !selectedNodeIds.contains(targetId) && propagatedTargets.add(targetId)) {
                        if (iteration != null && iteration > 0) {
                            skipPropagationService.persistAndPropagateSkip(execution, target, sourceId, itemIndex, iteration, epoch, triggerId, splitScope);
                        } else {
                            skipPropagationService.persistAndPropagateSkip(execution, target, sourceId, itemIndex, epoch, triggerId, splitScope);
                        }
                    }
                }
            }
        }
    }

    /**
     * Legacy target-based edge emission: uses sourceId without port.
     * Used as fallback when getBranchTargetsByPort() or getSelectedPort() are not available.
     */
    private void emitTargetBasedEdges(
            WorkflowExecution execution,
            ExecutionNode node,
            String sourceId,
            String nodeTypeName,
            int itemIndex,
            Integer iteration,
            NodeExecutionResult result,
            boolean suppressSkipPropagation,
            int epoch,
            String triggerId,
            boolean splitScope) {

        List<ExecutionNode> selectedNodes = node.getNextNodes(result);
        Set<String> selectedNodeIds = new HashSet<>();
        for (ExecutionNode successor : selectedNodes) {
            selectedNodeIds.add(successor.getNodeId());
        }

        List<ExecutionNode> skippedNodes = node.getSkippedChildNodes(result);

        logger.info("📊 [EdgeStatus] {} legacy edges: sourceId={}, selectedCount={}, skippedCount={}, itemIndex={}",
            nodeTypeName, sourceId, selectedNodes.size(), skippedNodes.size(), itemIndex);

        // Emit COMPLETED edges for selected branch
        for (ExecutionNode successor : selectedNodes) {
            String targetId = successor.getNodeId();
            edgeStatusService.markEdgeRunning(execution, sourceId, targetId, itemIndex, iteration);
            edgeStatusService.markEdgeCompleted(execution, sourceId, targetId, itemIndex, iteration);
            logger.info("🔗 [EdgeStatus] {} edge COMPLETED: {} -> {}, itemIndex={}, iteration={}",
                nodeTypeName, sourceId, targetId, itemIndex, iteration);
        }

        // Emit SKIPPED edges for non-selected branches.
        // Propagate the skip to descendants when the node opts in, OR no branch was selected,
        // OR we are NOT in a split (splitScope=false -> single item, so unselected branches must
        // be persisted SKIPPED for a downstream merge to resolve - the Ops Health Judge orphan
        // fix). In a split (splitScope=true) per-item behavior is preserved. See port-qualified path.
        boolean noBranchSelectedLegacy = selectedNodeIds.isEmpty();
        boolean shouldPropagate = (node.shouldPropagateSkipOnBranching() || noBranchSelectedLegacy || !splitScope) && !suppressSkipPropagation;

        for (ExecutionNode skipped : skippedNodes) {
            String targetId = skipped.getNodeId();
            if (!selectedNodeIds.contains(targetId)) {
                edgeStatusService.markEdgeSkipped(execution, sourceId, targetId, itemIndex, iteration);
                logger.info("🔗 [EdgeStatus] {} edge SKIPPED: {} -> {}, itemIndex={}, iteration={}",
                    nodeTypeName, sourceId, targetId, itemIndex, iteration);

                if (shouldPropagate) {
                    if (iteration != null && iteration > 0) {
                        skipPropagationService.persistAndPropagateSkip(execution, skipped, sourceId, itemIndex, iteration, epoch, triggerId, splitScope);
                    } else {
                        skipPropagationService.persistAndPropagateSkip(execution, skipped, sourceId, itemIndex, epoch, triggerId, splitScope);
                    }
                }
            }
        }
    }
}
