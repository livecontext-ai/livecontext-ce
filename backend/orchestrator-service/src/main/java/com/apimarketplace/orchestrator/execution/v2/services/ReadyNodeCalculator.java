package com.apimarketplace.orchestrator.execution.v2.services;

import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import com.apimarketplace.orchestrator.execution.v2.constants.ExecutionMetadataKeys;
import com.apimarketplace.orchestrator.execution.v2.engine.*;
import com.apimarketplace.orchestrator.execution.v2.nodes.BaseNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.ExecutionNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;
import com.apimarketplace.orchestrator.services.resume.MergeNodeAnalyzer;
import com.apimarketplace.orchestrator.services.state.StateSnapshotService;
import com.apimarketplace.orchestrator.validation.DAGIndependenceValidator;
import com.apimarketplace.orchestrator.utils.WorkflowUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.Set;

/**
 * Service responsible for calculating which nodes are ready to execute.
 *
 * Extracted from UnifiedExecutionEngine to improve code organization and testability.
 * Handles the complex logic of determining node readiness based on:
 * - Predecessor completion
 * - Merge node rules (ALL predecessors must be resolved)
 * - Skip propagation
 * - Back-edge and split state
 */
@Service
public class ReadyNodeCalculator {

    private static final Logger logger = LoggerFactory.getLogger(ReadyNodeCalculator.class);

    private final MergeNodeAnalyzer mergeNodeAnalyzer;
    private final StateSnapshotService stateSnapshotService;
    private final DAGIndependenceValidator dagIndependenceValidator;

    public ReadyNodeCalculator(MergeNodeAnalyzer mergeNodeAnalyzer, StateSnapshotService stateSnapshotService,
                               DAGIndependenceValidator dagIndependenceValidator) {
        this.mergeNodeAnalyzer = mergeNodeAnalyzer;
        this.stateSnapshotService = stateSnapshotService;
        this.dagIndependenceValidator = dagIndependenceValidator;
    }

    /**
     * Get the initial ready nodes for step-by-step mode.
     * Called when starting a workflow in step-by-step mode.
     *
     * <p>In multi-workflow mode, ALL triggers are returned as ready nodes.
     * The user can choose which workflow to start by selecting a trigger.</p>
     *
     * @param tree The execution tree
     * @return Set of node IDs that are ready to execute (all trigger nodes)
     */
    public Set<String> getInitialReadyNodes(ExecutionTree tree) {
        Set<String> readyNodes = new HashSet<>();
        for (ExecutionNode root : tree.getRootNodes()) {
            readyNodes.add(root.getNodeId());
        }
        if (tree.hasMultipleWorkflows()) {
            logger.info("[ReadyNodeCalculator] Multi-workflow mode: {} triggers ready: {}",
                readyNodes.size(), readyNodes);
        }
        return readyNodes;
    }

    /**
     * Calculate which nodes are ready to execute after a node completion.
     *
     * <p>A node is ready if:
     * <ul>
     *   <li>All its predecessors have completed (or been skipped)</li>
     *   <li>It hasn't been executed yet</li>
     * </ul>
     *
     * <p>In multi-workflow mode, traverses from ALL root nodes (triggers)
     * to find ready nodes across all independent workflows.</p>
     *
     * @param context The current execution context
     * @param tree    The execution tree
     * @return Set of node IDs that are ready to execute
     */
    public Set<String> calculateReadyNodes(ExecutionContext context, ExecutionTree tree) {
        Set<String> readyNodes = new HashSet<>();
        Set<String> visited = new HashSet<>();

        // Phase H (archi-refoundation 2026-05-04) - INFO → DEBUG: per-call traces.
        // Called after each step completion → ~14% of orchestrator log volume in prod
        // (ORCH_OPS #1c). Useful for debug sessions, noise in steady-state operation.
        logger.debug("[ReadyNodeCalculator] calculateReadyNodes: completedNodes in context stepOutputs={}",
            context.getAllStepOutputs().keySet());

        // Traverse from ALL root nodes (all triggers)
        for (ExecutionNode root : tree.getRootNodes()) {
            logger.debug("[ReadyNodeCalculator] calculateReadyNodes: traversing from root={}",
                root.getNodeId());
            collectReadyNodes(root, context, tree, readyNodes, visited);
        }

        logger.debug("[ReadyNodeCalculator] calculateReadyNodes returning {} ready nodes: {}",
            readyNodes.size(), readyNodes);
        return readyNodes;
    }

    /**
     * Recursively collect ready nodes from the tree.
     */
    private void collectReadyNodes(
            ExecutionNode node,
            ExecutionContext context,
            ExecutionTree tree,
            Set<String> readyNodes,
            Set<String> visited) {

        if (node == null || visited.contains(node.getNodeId())) {
            return;
        }
        visited.add(node.getNodeId());

        String nodeId = node.getNodeId();
        boolean isCompleted = context.isCompleted(nodeId);
        boolean isStarted = context.isStarted(nodeId);

        logger.info("[ReadyNodeCalculator] collectReadyNodes: nodeId={}, type={}, isCompleted={}, isStarted={}",
            nodeId, node.getType(), isCompleted, isStarted);

        // If this node hasn't completed yet
        if (!isCompleted && !isStarted) {
            // STEP_BY_STEP mode: triggers should NOT become ready again after execution
            // Check StateSnapshot (SINGLE SOURCE OF TRUTH) for THIS SPECIFIC trigger's completion
            // IMPORTANT: Only check THIS trigger, not all nodes - otherwise firing DAG1's trigger
            // would block DAG2's trigger from being ready (multi-DAG support)
            if (node.isTriggerNode() && hasTriggerBeenExecutedInEpoch(nodeId, context.runId())) {
                logger.info("[ReadyNodeCalculator] STEP_BY_STEP mode: trigger {} not ready (already executed in this epoch)",
                    nodeId);
                return;
            }
            checkAndAddReadyNode(node, nodeId, context, tree, readyNodes);
            return;
        }

        // If this node completed, check its successors
        if (isCompleted) {
            processCompletedNode(node, nodeId, context, tree, readyNodes, visited);
        }
    }

    /**
     * Check if a specific trigger has already been executed in the current epoch.
     * Uses StateSnapshot per-DAG per-epoch state (SINGLE SOURCE OF TRUTH).
     *
     * <p>Checks BOTH completed AND failed states: a trigger that fired and failed
     * should NOT become ready again in the same epoch (prevents infinite re-execution
     * in SBS mode). The user must fire a new epoch to retry.
     *
     * <p>IMPORTANT: Only checks THIS trigger's epoch, not all nodes.
     * In multi-DAG mode, firing trigger A should NOT prevent trigger B from being ready.
     *
     * @param triggerId The trigger node ID to check
     * @param runId The workflow run ID
     * @return true if this specific trigger has been completed or failed in its current epoch
     */
    private boolean hasTriggerBeenExecutedInEpoch(String triggerId, String runId) {
        if (stateSnapshotService == null) {
            return false;
        }
        // Check per-DAG completed AND failed sets (scoped to trigger's current epoch)
        var snapshot = stateSnapshotService.getSnapshot(runId);
        var epochState = snapshot.getEpochState(triggerId);
        boolean hasExecuted = epochState.getCompletedNodeIds().contains(triggerId)
                           || epochState.getFailedNodeIds().contains(triggerId);
        logger.debug("[ReadyNodeCalculator] hasTriggerBeenExecutedInEpoch: triggerId={}, runId={}, hasExecuted={}",
            triggerId, runId, hasExecuted);
        return hasExecuted;
    }

    /**
     * Check if an unstarted node can execute and add to ready set.
     * For merge nodes (nodes with multiple incoming edges), we must ensure
     * ALL predecessors are resolved (completed OR skipped) before marking as ready.
     */
    private void checkAndAddReadyNode(ExecutionNode node, String nodeId, ExecutionContext context,
                                       ExecutionTree tree, Set<String> readyNodes) {
        // Check if this is a merge node (has multiple incoming edges)
        boolean isMergeNode = mergeNodeAnalyzer.isMergeNode(tree.getPlan(), nodeId);

        if (isMergeNode) {
            // For merge nodes, check ALL predecessors are resolved (completed OR skipped)
            List<String> predecessors = mergeNodeAnalyzer.findPredecessorsFromEdges(tree.getPlan(), nodeId);

            // Multi-trigger DAG: filter out trigger predecessors from OTHER triggers in the same dagGroup.
            // When trigger:webhook fires, mcp:step1 may have both trigger:webhook and trigger:manual as predecessors.
            // We should only wait for trigger:webhook (the one that fired), not trigger:manual.
            predecessors = filterForeignTriggerPredecessors(predecessors, context, tree);

            logger.info("[ReadyNodeCalculator] Merge node {} has {} predecessors (after filter): {}", nodeId, predecessors.size(), predecessors);

            boolean allPredecessorsResolved = predecessors.stream()
                .allMatch(pred -> {
                    String normalizedPred = WorkflowUtils.normalizeStepIdSafe(pred);
                    boolean isResolved = context.isCompleted(normalizedPred) ||
                                         context.isCompleted(pred) ||
                                         isNodeSkipped(normalizedPred, context) ||
                                         isNodeSkipped(pred, context);

                    // Also check if the predecessor is a sub-node (like loop::condition_checker)
                    // and its parent loop is completed
                    if (!isResolved && pred.contains("::")) {
                        String parentId = pred.substring(0, pred.indexOf("::"));
                        isResolved = context.isCompleted(parentId);
                        if (isResolved) {
                            logger.info("[ReadyNodeCalculator] Merge node {} - predecessor {} resolved via parent {} completion",
                                nodeId, pred, parentId);
                        }
                    }

                    // Check if the predecessor is a port-qualified edge ref (e.g., "agent:classify:category_0",
                    // "core:decision:if", "core:fork:branch_0"). When a branching node completes, only the
                    // SELECTED port edge is marked COMPLETED in context. Resolve non-selected ports by checking
                    // if the parent branching node itself is completed.
                    // NOTE: For merge nodes, findPredecessorsFromEdges already strips ports via EdgeRefParser,
                    // so this block serves as a safety net in case predecessors are ever port-qualified here.
                    if (!isResolved) {
                        var edgeRef = com.apimarketplace.orchestrator.utils.EdgeRefParser.parse(pred);
                        if (edgeRef != null && edgeRef.port() != null && !edgeRef.port().isEmpty()) {
                            String parentId = edgeRef.nodeType() + ":" + edgeRef.nodeLabel();
                            isResolved = context.isCompleted(parentId) ||
                                         context.isCompleted(WorkflowUtils.normalizeStepIdSafe(parentId));
                            if (isResolved) {
                                logger.info("[ReadyNodeCalculator] Merge node {} - predecessor {} resolved via branching parent {} completion",
                                    nodeId, pred, parentId);
                            }
                        }
                    }

                    if (!isResolved) {
                        logger.info("[ReadyNodeCalculator] Merge node {} - predecessor {} NOT resolved", nodeId, pred);
                    }
                    return isResolved;
                });

            if (allPredecessorsResolved) {
                logger.info("[ReadyNodeCalculator] Merge node {} is READY (all {} predecessors resolved)", nodeId, predecessors.size());
                readyNodes.add(nodeId);
            } else {
                logger.info("[ReadyNodeCalculator] Merge node {} NOT ready (waiting for predecessors)", nodeId);
            }
        } else {
            // Regular node - use default canExecute logic
            boolean canExec = node.canExecute(context);
            List<String> predecessors = node.getPredecessorIds();
            logger.info("[ReadyNodeCalculator] collectReadyNodes: nodeId={} not completed/started, canExecute={}, predecessors={}",
                nodeId, canExec, predecessors);
            if (canExec) {
                logger.info("[ReadyNodeCalculator] Node {} is ready (can execute, not started)", nodeId);
                readyNodes.add(nodeId);
            } else {
                // Log WHY the node can't execute - which predecessors are not completed
                for (String pred : predecessors) {
                    boolean predCompleted = context.isCompleted(pred);
                    logger.info("[ReadyNodeCalculator] Node {} canExecute=false: predecessor '{}' completed={}",
                        nodeId, pred, predCompleted);
                }
            }
        }
    }

    /**
     * Check if a node was SKIPPED. Reads {@link ExecutionContext#isSkipped} (canonical
     * NodeStatus signal). Output-presence is NOT a reliable discriminator because
     * SKIPPED nodes can carry a status envelope output after storage rehydration -
     * see prod run {@code 656a4aed-…} where a SKIPPED {@code mcp:apply_tech}'s
     * rehydrated context contained {@code {output:{_status:"SKIPPED",…}}} and the
     * downstream {@code table:record_tech} was wrongly scheduled.
     */
    private boolean isNodeSkipped(String nodeId, ExecutionContext context) {
        return context.isSkipped(nodeId);
    }

    /**
     * Process a completed node to find ready successors.
     */
    private void processCompletedNode(ExecutionNode node, String nodeId, ExecutionContext context,
                                      ExecutionTree tree, Set<String> readyNodes, Set<String> visited) {
        logger.info("[ReadyNodeCalculator] processCompletedNode: nodeId={}, type={}", nodeId, node.getType());

        // If node FAILED, do NOT traverse to regular successors - they were skipped.
        // Exception: merge nodes downstream still need to be evaluated because they wait for
        // ALL predecessors to resolve (COMPLETED, FAILED, or SKIPPED all count as "resolved").
        if (context.isFailed(nodeId)) {
            logger.info("[ReadyNodeCalculator] Node {} FAILED - checking successors for merge nodes", nodeId);
            // Walk immediate successors (graph edges) to discover merge nodes that may now be ready.
            // Use BaseNode.getSuccessors() because getAllChildNodes() only works for branching nodes
            // (Decision, Switch, Fork, etc.) - regular MCP steps return empty from getAllChildNodes().
            List<ExecutionNode> successors = (node instanceof BaseNode baseNode)
                ? baseNode.getSuccessors()
                : node.getAllChildNodes();
            for (ExecutionNode child : successors) {
                String childId = child.getNodeId();
                boolean childIsMerge = mergeNodeAnalyzer.isMergeNode(tree.getPlan(), childId);
                if (childIsMerge && !readyNodes.contains(childId) && !context.isCompleted(childId)) {
                    logger.info("[ReadyNodeCalculator] Found merge node {} downstream of failed node {}, evaluating readiness", childId, nodeId);
                    // Don't add to visited - other paths may also need to reach this merge node.
                    // checkAndAddReadyNode is idempotent (readyNodes is a Set).
                    // Guard with !isCompleted to prevent re-adding a merge that already executed.
                    checkAndAddReadyNode(child, childId, context, tree, readyNodes);
                }
            }
            return;
        }

        // SKIPPED gate - canonical signal from NodeStatus, not output-presence.
        // Output-presence is unreliable: the SKIPPED completion path persists a status
        // envelope ({output:{_status:"SKIPPED",…}, statusValue:"skipped", …}) into
        // storage. After V2StepByStepContextManager rehydrates the context, the step
        // output is non-empty and the legacy outputOpt.isEmpty() discriminator would
        // wrongly treat it as COMPLETED, traverse successors, and trigger downstream
        // execution against a phantom predecessor.
        // Repro: prod run 656a4aed-… epoch 2 item 0, mcp:apply_tech SKIPPED →
        // table:record_tech wrongly executed and INSERTed a row with the wrong label.
        //
        // Symmetric with the FAILED gate above: regular successors are not traversed
        // from a SKIPPED node, but downstream merge nodes still need a readiness check
        // because merge waits on ALL predecessors and SKIPPED counts as "resolved".
        // Without this, a routing skip from Decision/Switch (which deliberately does
        // not set CASCADE_SKIP_TO_SUCCESSORS) would leave a merge stranded when this
        // calculator visits the SKIPPED predecessor first.
        if (context.isSkipped(nodeId)) {
            logger.info("[ReadyNodeCalculator] Node {} was SKIPPED, NOT traversing regular successors", nodeId);
            List<ExecutionNode> skippedSuccessors = (node instanceof BaseNode baseNode)
                ? baseNode.getSuccessors()
                : node.getAllChildNodes();
            for (ExecutionNode child : skippedSuccessors) {
                String childId = child.getNodeId();
                boolean childIsMerge = mergeNodeAnalyzer.isMergeNode(tree.getPlan(), childId);
                if (childIsMerge && !readyNodes.contains(childId) && !context.isCompleted(childId)) {
                    logger.info("[ReadyNodeCalculator] Found merge node {} downstream of skipped node {}, evaluating readiness", childId, nodeId);
                    checkAndAddReadyNode(child, childId, context, tree, readyNodes);
                }
            }
            return;
        }

        Optional<Object> outputOpt = context.getStepOutput(nodeId);
        logger.info("[ReadyNodeCalculator] Checking output for nodeId={}, outputPresent={}", nodeId, outputOpt.isPresent());

        if (outputOpt.isEmpty()) {
            // Cross-epoch carve-out: a node completed in a prior epoch is "isCompleted"
            // in ExecutionState but its output isn't in the current epoch's stepOutputs.
            // FAILED and SKIPPED both returned earlier in this method, so the only
            // terminal status reachable here is COMPLETED.
            // Use the snapshot completed set (epoch-scoped, parallel-safe) to confirm the
            // node was a real COMPLETED in some epoch before traversing with a synthetic
            // empty result.
            boolean completedInSnapshot = isCompletedInSnapshot(nodeId, context);
            if (!completedInSnapshot) {
                logger.info("[ReadyNodeCalculator] Node {} has no output and not in snapshot, NOT traversing successors", nodeId);
                return;
            }
            logger.info("[ReadyNodeCalculator] Node {} completed in snapshot but no output in current epoch, traversing successors", nodeId);
            NodeExecutionResult syntheticResult = NodeExecutionResult.success(nodeId, Map.of());
            processSuccessors(node, nodeId, syntheticResult, context, tree, readyNodes, visited);
            return;
        }

        logger.info("[ReadyNodeCalculator] Node {} has output, building NodeExecutionResult...", nodeId);
        NodeExecutionResult result = buildNodeExecutionResult(nodeId, context);

        logger.info("[ReadyNodeCalculator] collectReadyNodes: nodeId={}, type={}, processing successors",
            nodeId, node.getType());

        logger.info("[ReadyNodeCalculator] Node {} completed, processing successors", nodeId);
        processSuccessors(node, nodeId, result, context, tree, readyNodes, visited);
    }

    /**
     * Check if a node is completed in the StateSnapshot.
     * Uses epoch-scoped check when context has DAG coordinates (parallel epoch safe),
     * falls back to flat view when no epoch info is available.
     */
    private boolean isCompletedInSnapshot(String nodeId, ExecutionContext context) {
        if (stateSnapshotService == null) {
            return false;
        }
        try {
            String triggerId = context.triggerId();
            int epoch = context.epoch();
            if (triggerId != null && epoch > 0) {
                // Epoch-scoped: only check THIS epoch's completed set (parallel epoch safe)
                Set<String> completedNodes = stateSnapshotService.getCompletedNodeIds(context.runId(), triggerId, epoch);
                return completedNodes.contains(nodeId);
            }
            // Fallback: flat view (union across all DAGs)
            Set<String> completedNodes = stateSnapshotService.getCompletedNodeIds(context.runId());
            return completedNodes.contains(nodeId);
        } catch (Exception e) {
            logger.debug("[ReadyNodeCalculator] Could not check snapshot for nodeId={}: {}", nodeId, e.getMessage());
            return false;
        }
    }

    /**
     * Build NodeExecutionResult from context step output.
     *
     * IMPORTANT: The stored output may be wrapped in an "output" key (legacy format).
     * We need to unwrap it to access fields like selected_branch_index directly.
     */
    private NodeExecutionResult buildNodeExecutionResult(String nodeId, ExecutionContext context) {
        Optional<Object> resultOpt = context.getStepOutput(nodeId);
        if (resultOpt.isEmpty()) {
            logger.warn("[ReadyNodeCalculator] collectReadyNodes: nodeId={} is completed but has NO output in context!", nodeId);
            return NodeExecutionResult.success(nodeId, Map.of());
        }

        Object stored = resultOpt.get();
        if (stored instanceof NodeExecutionResult ner) {
            logger.info("[ReadyNodeCalculator] collectReadyNodes: nodeId={} has NodeExecutionResult output", nodeId);
            return ner;
        } else if (stored instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> outputMap = (Map<String, Object>) stored;

            // CRITICAL: Unwrap if the output is nested under "output" key
            // Legacy format: {output: {selected_branch_index: 0, ...}}
            // Expected format: {selected_branch_index: 0, ...}
            Map<String, Object> unwrapped = unwrapOutput(outputMap);

            logger.info("[ReadyNodeCalculator] collectReadyNodes: nodeId={} has Map output with keys: {} (unwrapped: {})",
                nodeId, outputMap.keySet(), unwrapped.keySet());
            return NodeExecutionResult.success(nodeId, unwrapped);
        } else {
            logger.warn("[ReadyNodeCalculator] collectReadyNodes: nodeId={} has unknown output type: {}", nodeId, stored.getClass().getName());
            return NodeExecutionResult.success(nodeId, Map.of());
        }
    }

    /**
     * Unwrap output if it's nested under "output" key.
     * This handles the legacy format where data is stored as {output: {...}}.
     * Uses the extended variant with branch detection for decision/switch node support.
     * Delegates to {@link OutputUnwrapper#unwrapOutputWithBranchDetection(Map)}.
     */
    private Map<String, Object> unwrapOutput(Map<String, Object> outputMap) {
        return OutputUnwrapper.unwrapOutputWithBranchDetection(outputMap);
    }

    /**
     * Handle successors of a completed node.
     */
    private void processSuccessors(ExecutionNode node, String nodeId, NodeExecutionResult result,
                                   ExecutionContext context, ExecutionTree tree,
                                   Set<String> readyNodes, Set<String> visited) {
        logger.info("[ReadyNodeCalculator] processSuccessors: nodeId={}, getting next nodes...", nodeId);
        List<ExecutionNode> nextNodes = node.getNextNodes(result);
        logger.info("[ReadyNodeCalculator] node.getNextNodes() returned {} nodes: {}",
            nextNodes.size(), nextNodes.stream().map(ExecutionNode::getNodeId).toList());

        // Branching nodes (Decision, Switch, Option, Approval, Classify, Guardrail)
        // with no selection info: use getAllChildNodes() to find the executed path.
        //
        // For ALL branching nodes (isBranchingNode()=true), only traverse children
        // that are already completed/started to avoid re-enabling skipped branches.
        //
        // Non-branching nodes (Fork, Loop, regular) traverse all children as before.
        //
        // IMPORTANT: If the branching node has a valid result with output and getNextNodes()
        // returned empty, this means "no branch selected" by design (e.g. classify with no
        // matching category). We must NOT fall through to the executed-children fallback,
        // which is only for state reconstruction when selection info is missing from the result.
        if (nextNodes.isEmpty()) {
            boolean branchingWithValidResult = node.isBranchingNode()
                && result != null && result.output() != null && !result.output().isEmpty();
            if (branchingWithValidResult) {
                // Branching node explicitly returned no successors - respect that decision.
                // All children should be skipped (handled by getSkippedChildNodes).
                logger.info("[ReadyNodeCalculator] collectReadyNodes: {} {} returned empty nextNodes with valid output - no branch selected, skipping all children",
                    node.getType(), nodeId);
            } else {
                List<ExecutionNode> allChildren = node.getAllChildNodes();
                if (!allChildren.isEmpty()) {
                    if (node.isBranchingNode()) {
                        // Branching node without valid result: reconstruct from executed path.
                        // IMPORTANT: SKIPPED children represent non-selected branches and must NOT
                        // be treated as the "executed path". Only follow children that are genuinely
                        // completed (with output) or currently running/started.
                        List<ExecutionNode> executedChildren = allChildren.stream()
                            .filter(child -> {
                                String childId = child.getNodeId();
                                boolean completedOrStarted = context.isCompleted(childId) || context.isStarted(childId);
                                // Exclude skipped children - they are non-selected branches
                                boolean skipped = isNodeSkipped(childId, context);
                                return completedOrStarted && !skipped;
                            })
                            .toList();
                        if (!executedChildren.isEmpty()) {
                            nextNodes = executedChildren;
                            logger.info("[ReadyNodeCalculator] collectReadyNodes: {} {} has no selection info, following {} executed child nodes (out of {} total)",
                                node.getType(), nodeId, executedChildren.size(), allChildren.size());
                        } else {
                            // No child executed yet - traverse all to determine readiness
                            nextNodes = allChildren;
                            logger.info("[ReadyNodeCalculator] collectReadyNodes: {} {} has no selection info and no executed children, traversing ALL {} child nodes",
                                node.getType(), nodeId, nextNodes.size());
                        }
                    } else {
                        nextNodes = allChildren;
                        logger.info("[ReadyNodeCalculator] collectReadyNodes: {} {} has no selection info, traversing ALL {} child nodes to find executed path",
                            node.getType(), nodeId, nextNodes.size());
                    }
                }
            }
        }

        // Classify/guardrail agent in split context: traverse ALL branch targets.
        // Even with one split item, non-selected targets must become ready so
        // SplitAwareNodeExecutor can persist SKIPPED rows for those branches.
        if (node.isAgentNode()) {
            Map<String, Object> output = result.output();
            if (output != null) {
                String nodeType = ExecutionMetadataKeys.getNodeType(output);
                Object splitItemCount = output.get(ExecutionMetadataKeys.SPLIT_ITEM_COUNT);
                if ("CLASSIFY".equals(nodeType) && hasSplitItems(splitItemCount)) {
                    // Get ALL category targets using polymorphic interface method
                    Collection<ExecutionNode> allCategoryTargets = node.getAllCategoryTargetNodes();
                    if (!allCategoryTargets.isEmpty()) {
                        nextNodes = new ArrayList<>(allCategoryTargets);
                        logger.info("[ReadyNodeCalculator] Classify {} in split context (itemCount={}), traversing ALL {} category targets: {}",
                            nodeId, splitItemCount, nextNodes.size(),
                            nextNodes.stream().map(ExecutionNode::getNodeId).toList());
                    }
                } else if ("GUARDRAIL".equals(nodeType) && hasSplitItems(splitItemCount)) {
                    List<ExecutionNode> allGuardrailTargets = collectGuardrailTargets(node);
                    if (!allGuardrailTargets.isEmpty()) {
                        nextNodes = allGuardrailTargets;
                        logger.info("[ReadyNodeCalculator] Guardrail {} in split context (itemCount={}), traversing ALL {} guardrail targets: {}",
                            nodeId, splitItemCount, nextNodes.size(),
                            nextNodes.stream().map(ExecutionNode::getNodeId).toList());
                    }
                }
            }
        }

        // Decision node in split context: traverse ALL branch targets
        // When a Decision executes in split context, each item may select a different branch.
        // The summary result only contains the LAST item's selection, so getNextNodes() returns
        // only one branch. We need to traverse ALL branches so that successor nodes for every
        // selected branch become ready and get executed by SplitAwareNodeExecutor with
        // getRoutedItemIndices filtering.
        if (node.isDecisionNode()) {
            Map<String, Object> output = result.output();
            if (output != null) {
                Object splitItemCount = output.get(ExecutionMetadataKeys.SPLIT_ITEM_COUNT);
                if (hasSplitItems(splitItemCount)) {
                    List<ExecutionNode> allBranches = node.getAllChildNodes();
                    if (!allBranches.isEmpty()) {
                        nextNodes = new ArrayList<>(allBranches);
                        logger.info("[ReadyNodeCalculator] Decision {} in split context (itemCount={}), traversing ALL {} branch targets: {}",
                            nodeId, splitItemCount, nextNodes.size(),
                            nextNodes.stream().map(ExecutionNode::getNodeId).toList());
                    }
                }
            }
        }

        // Switch node in split context: same logic as Decision - traverse ALL case targets
        if (node.isSwitchNode()) {
            Map<String, Object> output = result.output();
            if (output != null) {
                Object splitItemCount = output.get(ExecutionMetadataKeys.SPLIT_ITEM_COUNT);
                if (hasSplitItems(splitItemCount)) {
                    List<ExecutionNode> allBranches = node.getAllChildNodes();
                    if (!allBranches.isEmpty()) {
                        nextNodes = new ArrayList<>(allBranches);
                        logger.info("[ReadyNodeCalculator] Switch {} in split context (itemCount={}), traversing ALL {} case targets: {}",
                            nodeId, splitItemCount, nextNodes.size(),
                            nextNodes.stream().map(ExecutionNode::getNodeId).toList());
                    }
                }
            }
        }

        // Option node in split context: same logic as Decision/Switch.
        // Each item can select a different choice, while the aggregate result only carries
        // one selected_choice_index. Traverse all choice targets and let SplitAwareNodeExecutor
        // filter each target by selected_branch=choice_N.
        if (node.isOptionNode()) {
            Map<String, Object> output = result.output();
            if (output != null) {
                Object splitItemCount = output.get(ExecutionMetadataKeys.SPLIT_ITEM_COUNT);
                if (hasSplitItems(splitItemCount)) {
                    List<ExecutionNode> allBranches = node.getAllChildNodes();
                    if (!allBranches.isEmpty()) {
                        nextNodes = new ArrayList<>(allBranches);
                        logger.info("[ReadyNodeCalculator] Option {} in split context (itemCount={}), traversing ALL {} choice targets: {}",
                            nodeId, splitItemCount, nextNodes.size(),
                            nextNodes.stream().map(ExecutionNode::getNodeId).toList());
                    }
                }
            }
        }

        // Approval node in split context: same logic as Decision - traverse ALL branch targets.
        // Each split item may be approved or rejected independently. The summary result only
        // contains the LAST item's selected port, so getNextNodes() may return the wrong branch.
        // We need to traverse ALL branches (approved + rejected) so SplitAwareNodeExecutor can
        // filter by getRoutedItemIndices per branch.
        // split_item_count is injected into the resolved signal output by SignalResumeService.
        if (node.isApprovalNode()) {
            Map<String, Object> output = result.output();
            if (output != null) {
                Object splitItemCount = output.get(ExecutionMetadataKeys.SPLIT_ITEM_COUNT);
                if (hasSplitItems(splitItemCount)) {
                    List<ExecutionNode> allBranches = node.getAllChildNodes();
                    if (!allBranches.isEmpty()) {
                        nextNodes = new ArrayList<>(allBranches);
                        logger.info("[ReadyNodeCalculator] Approval {} in split context (itemCount={}), traversing ALL {} branch targets: {}",
                            nodeId, splitItemCount, nextNodes.size(),
                            nextNodes.stream().map(ExecutionNode::getNodeId).toList());
                    }
                }
            }
        }

        logger.info("[ReadyNodeCalculator] collectReadyNodes: Node {} (type={}) completed, traversing {} successors: {}",
            nodeId, node.getType(), nextNodes.size(),
            nextNodes.stream().map(ExecutionNode::getNodeId).toList());

        if (nextNodes.isEmpty()) {
            logger.info("[ReadyNodeCalculator] No successors found for nodeId={}, this might be the last node", nodeId);
        }

        for (ExecutionNode child : nextNodes) {
            logger.info("[ReadyNodeCalculator] Recursively calling collectReadyNodes for child nodeId={}", child.getNodeId());
            collectReadyNodes(child, context, tree, readyNodes, visited);
        }
    }

    private boolean hasSplitItems(Object splitItemCount) {
        return splitItemCount instanceof Number count && count.intValue() > 0;
    }

    private List<ExecutionNode> collectGuardrailTargets(ExecutionNode node) {
        Map<String, List<ExecutionNode>> branchTargets = node.getBranchTargetsByPort();
        if (branchTargets == null || branchTargets.isEmpty()) {
            return node.getAllChildNodes();
        }

        Set<ExecutionNode> targets = new LinkedHashSet<>();
        List<ExecutionNode> passTargets = branchTargets.get("pass");
        if (passTargets != null) {
            targets.addAll(passTargets);
        }
        List<ExecutionNode> failTargets = branchTargets.get("fail");
        if (failTargets != null) {
            targets.addAll(failTargets);
        }
        return new ArrayList<>(targets);
    }

    /**
     * Filter out trigger predecessors that belong to OTHER triggers in the same dagGroup.
     *
     * <p>In a multi-trigger DAG, a node like mcp:step1 may have edges from both
     * trigger:webhook and trigger:manual. When trigger:webhook fires, mcp:step1 should
     * only wait for trigger:webhook to complete - not trigger:manual (which hasn't fired).
     *
     * <p>This method keeps:
     * <ul>
     *   <li>All non-trigger predecessors (always required)</li>
     *   <li>The trigger predecessor that matches the current context's triggerId</li>
     *   <li>All trigger predecessors that are NOT in the same dagGroup as another trigger predecessor</li>
     * </ul>
     *
     * @param predecessors All predecessors of the node
     * @param context Current execution context (contains the triggerId that fired)
     * @param tree The execution tree (contains the plan)
     * @return Filtered predecessors list
     */
    private List<String> filterForeignTriggerPredecessors(List<String> predecessors, ExecutionContext context,
                                                           ExecutionTree tree) {
        if (predecessors.size() <= 1 || tree.getPlan() == null) {
            return predecessors;
        }

        // Quick check: are there any trigger predecessors?
        List<String> triggerPreds = predecessors.stream()
            .filter(p -> p.startsWith("trigger:"))
            .toList();

        if (triggerPreds.size() <= 1) {
            // 0 or 1 trigger predecessor - no filtering needed
            return predecessors;
        }

        // Multiple trigger predecessors: check if they share the same DAG
        String currentTriggerId = context.triggerId();
        var plan = tree.getPlan();

        // Check if these trigger predecessors share the same DAG (overlapping descendants)
        boolean hasSharedTriggers = false;
        for (int i = 0; i < triggerPreds.size() && !hasSharedTriggers; i++) {
            for (int j = i + 1; j < triggerPreds.size(); j++) {
                if (plan.areTriggersInSameDagGroup(triggerPreds.get(i), triggerPreds.get(j))) {
                    hasSharedTriggers = true;
                    break;
                }
            }
        }

        if (!hasSharedTriggers) {
            // Triggers don't share a DAG - keep all (standard behavior)
            return predecessors;
        }

        // Multi-trigger DAG: only keep the trigger that actually fired (from context)
        // Keep all non-trigger predecessors + only the current trigger
        List<String> filtered = new ArrayList<>();
        for (String pred : predecessors) {
            if (!pred.startsWith("trigger:")) {
                filtered.add(pred);
            } else if (pred.equals(currentTriggerId)) {
                filtered.add(pred);
            } else if (currentTriggerId != null && plan.areTriggersInSameDagGroup(pred, currentTriggerId)) {
                // Same dagGroup as current trigger - skip (foreign trigger in same group)
                logger.debug("[ReadyNodeCalculator] Filtering out foreign trigger predecessor {} (same dagGroup as {})",
                    pred, currentTriggerId);
            } else {
                // Different dagGroup - keep
                filtered.add(pred);
            }
        }

        if (filtered.size() != predecessors.size()) {
            logger.info("[ReadyNodeCalculator] Multi-trigger DAG: filtered predecessors from {} to {} (currentTrigger={})",
                predecessors, filtered, currentTriggerId);
        }

        return filtered;
    }

}
