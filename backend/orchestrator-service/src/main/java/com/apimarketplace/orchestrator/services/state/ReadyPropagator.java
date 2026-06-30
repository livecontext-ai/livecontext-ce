package com.apimarketplace.orchestrator.services.state;

import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Handles ready state propagation for workflow nodes.
 *
 * Extracted from WorkflowStateManager for Single Responsibility Principle.
 * This class is responsible for:
 * - Propagating COMPLETED status to successors
 * - Propagating SKIPPED status to successors
 * - Calculating when nodes should become READY
 * - Handling special ready logic for Decision and Fork nodes
 *
 * @see WorkflowStateManager
 */
public class ReadyPropagator {

    private static final Logger logger = LoggerFactory.getLogger(ReadyPropagator.class);

    private final WorkflowGraph graph;
    private final StatusProvider statusProvider;
    private final StatusUpdater statusUpdater;
    private final DecisionBranchProvider decisionBranchProvider;

    /**
     * Callback interface for getting node status.
     */
    public interface StatusProvider {
        NodeStatus getStatus(NodeId nodeId);
    }

    /**
     * Callback interface for updating node status.
     */
    public interface StatusUpdater {
        void transitionTo(NodeId nodeId, NodeStatus status);
        void markSkipped(NodeId nodeId);
    }

    /**
     * Callback interface for getting/checking decision branch info.
     */
    public interface DecisionBranchProvider {
        /**
         * Get all selected branches for a decision/classify node.
         * In split context, multiple items can select different branches.
         *
         * @param decisionId The decision/classify node ID
         * @return Set of all selected branch ports (e.g., "if", "category_0", "category_1"), or null if no branches recorded
         */
        java.util.Set<String> getDecisionBranches(NodeId decisionId);
        void recalculateReadyState(NodeId nodeId);
    }

    public ReadyPropagator(WorkflowGraph graph,
                           StatusProvider statusProvider,
                           StatusUpdater statusUpdater,
                           DecisionBranchProvider decisionBranchProvider) {
        this.graph = graph;
        this.statusProvider = statusProvider;
        this.statusUpdater = statusUpdater;
        this.decisionBranchProvider = decisionBranchProvider;
    }

    /**
     * Propagate completion to successor nodes.
     * Called after a node is marked COMPLETED.
     */
    public void propagateCompletionToSuccessors(WorkflowNode completedNode) {
        for (NodeId successorId : completedNode.successors()) {
            WorkflowNode successor = graph.getNodeOrNull(successorId);
            if (successor == null) {
                continue;
            }

            if (successor.isDecision()) {
                // Decision nodes evaluate immediately when ready
                handleDecisionBecameReady(successorId);
            } else if (successor.isFork()) {
                // Fork nodes evaluate immediately when ready (activate ALL branches)
                handleForkBecameReady(successorId);
            } else {
                // Other nodes: check if ready
                recalculateReadyState(successorId);
            }
        }
    }

    /**
     * Propagate failure to successor nodes.
     * Called after a node is marked FAILED.
     *
     * <p>For merge nodes (explicit or implicit), failure of a predecessor should trigger
     * a recalculation of the merge's readiness. FAILED counts as "resolved" for merge
     * purposes, so if all other predecessors are also resolved, the merge can proceed.</p>
     *
     * <p>For single-predecessor nodes, a failed predecessor means the successor should
     * be skipped (the execution path is broken).</p>
     */
    public void propagateFailureToSuccessors(WorkflowNode failedNode) {
        for (NodeId successorId : failedNode.successors()) {
            WorkflowNode successor = graph.getNodeOrNull(successorId);
            if (successor == null) {
                continue;
            }

            // Already terminal? Skip
            if (statusProvider.getStatus(successorId).isTerminal()) {
                continue;
            }

            if (successor.isMergeNode() || successor.predecessors().size() > 1) {
                // Merge node: recalculate readiness (FAILED counts as resolved)
                logger.info("[propagateFailureToSuccessors] Recalculating merge node {} after predecessor {} FAILED",
                    successorId, failedNode.id());
                recalculateReadyState(successorId);
            } else {
                // Single-predecessor node: skip it (execution path is broken)
                logger.info("[propagateFailureToSuccessors] Skipping successor {} because predecessor {} FAILED",
                    successorId, failedNode.id());
                statusUpdater.markSkipped(successorId);
            }
        }
    }

    /**
     * Recalculate whether a node should become READY.
     */
    public void recalculateReadyState(NodeId nodeId) {
        NodeStatus currentStatus = statusProvider.getStatus(nodeId);

        // Only recalculate if node is PENDING
        if (currentStatus != NodeStatus.PENDING) {
            return;
        }

        WorkflowNode node = graph.getNode(nodeId);
        ReadyCheckResult result = checkIfReady(node);

        switch (result) {
            case MAKE_READY -> statusUpdater.transitionTo(nodeId, NodeStatus.READY);
            case MAKE_SKIPPED -> statusUpdater.markSkipped(nodeId);
            case STAY_PENDING -> { /* no change */ }
        }
    }

    /**
     * Recalculate ready states for all PENDING nodes.
     * Used during state reconstruction.
     */
    public void recalculateAllPendingNodes() {
        List<NodeId> order;
        try {
            // Process nodes in topological order to ensure correct propagation
            order = graph.topologicalSort();
        } catch (IllegalStateException e) {
            // Graph has cycles (e.g., loops) - fall back to iterating all nodes
            // This is expected for workflows with loop structures
            logger.debug("[ReadyPropagator] Graph has cycles, using unordered iteration: {}", e.getMessage());
            order = new java.util.ArrayList<>(graph.getAllNodeIds());
        }

        for (NodeId nodeId : order) {
            if (statusProvider.getStatus(nodeId) == NodeStatus.PENDING) {
                recalculateReadyState(nodeId);
            }
        }
    }

    /**
     * Check if a node should become READY based on its type.
     */
    public ReadyCheckResult checkIfReady(WorkflowNode node) {
        return switch (node.type()) {
            case TRIGGER -> ReadyCheckResult.STAY_PENDING;  // Handled in initialize()
            case MCP, AGENT -> checkExecutableNodeReady(node);
            case DECISION -> checkDecisionReady(node);
            case LOOP -> checkLoopReady(node);
            case FORK -> checkForkReady(node);
            case MERGE -> checkMergeNodeReady(node);
            case AGGREGATE -> checkMergeNodeReady(node);  // Aggregate behaves like merge - waits for all items
        };
    }

    /**
     * Check if a step or agent node should become READY.
     *
     * <p>NOTE: The parentLoop-based split handling is deprecated.
     * The new simplified split system uses SplitContextManager.findActiveContext()
     * to dynamically detect split scope. Split nodes complete immediately after
     * spawning items, and SplitAwareNodeExecutor handles parallel item execution.
     *
     */
    private ReadyCheckResult checkExecutableNodeReady(WorkflowNode node) {
        if (node.isMergeNode()) {
            return checkMergeNodeReady(node);
        }

        // Simple node: all predecessors must be terminal
        List<NodeId> predecessors = node.predecessors();
        List<NodeStatus> predStatuses = predecessors.stream()
                .map(statusProvider::getStatus)
                .toList();

        // IMPORTANT: If no predecessors, this node should stay PENDING
        // (it's likely an orphan node or a trigger-only dependency)
        if (predStatuses.isEmpty()) {
            logger.debug("[checkExecutableNodeReady] {} has no predecessors, staying PENDING", node.id());
            return ReadyCheckResult.STAY_PENDING;
        }

        boolean allTerminal = predStatuses.stream().allMatch(NodeStatus::isTerminal);

        if (!allTerminal) {
            logger.debug("[checkExecutableNodeReady] {} waiting for predecessors: {} -> {}",
                node.id(), predecessors, predStatuses);
            return ReadyCheckResult.STAY_PENDING;
        }

        // If ALL predecessors are SKIPPED or FAILED (none COMPLETED), this step is SKIPPED.
        // A single-predecessor node with a FAILED predecessor cannot proceed.
        boolean allSkippedOrFailed = predStatuses.stream()
                .allMatch(s -> s == NodeStatus.SKIPPED || s == NodeStatus.FAILED);
        if (allSkippedOrFailed) {
            logger.info("[checkExecutableNodeReady] {} ALL predecessors SKIPPED or FAILED: {} -> {} -> SKIPPED",
                node.id(), predecessors, predStatuses);
            return ReadyCheckResult.MAKE_SKIPPED;
        }

        // Check if any predecessor is a Classify node with branch selection
        // Classify behaves like Decision - only the selected category's successors should be READY
        // In split context, multiple items can select different branches - ALL selected branches should be READY
        for (NodeId predId : predecessors) {
            WorkflowNode predNode = graph.getNodeOrNull(predId);
            if (predNode == null) continue;

            // Check if this predecessor is an AGENT with saved decision branches (i.e., a Classify node)
            if (predNode.type() == WorkflowNode.NodeType.AGENT) {
                java.util.Set<String> savedBranches = decisionBranchProvider.getDecisionBranches(predId);
                logger.info("[checkExecutableNodeReady] {} checking AGENT predecessor {}: savedBranches={}, portSuccessors={}",
                    node.id(), predId, savedBranches, predNode.portSuccessors());

                if (savedBranches != null && !savedBranches.isEmpty()) {
                    // Filter to category branches only
                    java.util.Set<String> categoryBranches = savedBranches.stream()
                        .filter(b -> b.startsWith("category_"))
                        .collect(java.util.stream.Collectors.toSet());

                    if (!categoryBranches.isEmpty()) {
                        // This is a Classify node - check if THIS node is ANY of the selected targets
                        boolean isSelectedTarget = false;
                        for (String savedBranch : categoryBranches) {
                            NodeId selectedTarget = predNode.getSuccessorForPort(savedBranch);
                            if (selectedTarget != null && selectedTarget.equals(node.id())) {
                                isSelectedTarget = true;
                                logger.info("[checkExecutableNodeReady] {} predecessor {} is Classify with branch={}, this node IS a selected target -> MAKE_READY",
                                    node.id(), predId, savedBranch);
                                break;
                            }
                        }

                        if (!isSelectedTarget) {
                            // This node is NOT any of the selected category's targets - SKIP it
                            logger.info("[checkExecutableNodeReady] {} predecessor {} is Classify with branches={}, this node is NOT in any selected target -> MAKE_SKIPPED",
                                node.id(), predId, categoryBranches);
                            return ReadyCheckResult.MAKE_SKIPPED;
                        }
                    }
                }
            }
        }

        // At least one predecessor COMPLETED -> READY
        logger.info("[checkExecutableNodeReady] {} all loop scope checks passed, predecessors resolved: {} -> {} -> MAKE_READY",
            node.id(), predecessors, predStatuses);
        return ReadyCheckResult.MAKE_READY;
    }

    /**
     * Check if a merge node should become READY.
     * Always uses ALL strategy: waits for ALL predecessors to be resolved (COMPLETED, SKIPPED, or FAILED).
     *
     * <p>FAILED predecessors are treated as "resolved" for merge purposes to prevent deadlocks.
     * A merge becomes READY when all predecessors are terminal and at least one is COMPLETED.
     * If all predecessors are SKIPPED or FAILED (none COMPLETED), the merge is SKIPPED.</p>
     */
    private ReadyCheckResult checkMergeNodeReady(WorkflowNode node) {
        List<NodeStatus> predStatuses = node.predecessors().stream()
                .map(statusProvider::getStatus)
                .toList();

        long completedCount = predStatuses.stream()
                .filter(s -> s == NodeStatus.COMPLETED).count();
        long skippedCount = predStatuses.stream()
                .filter(s -> s == NodeStatus.SKIPPED).count();
        long failedCount = predStatuses.stream()
                .filter(s -> s == NodeStatus.FAILED).count();
        long resolvedCount = completedCount + skippedCount + failedCount;
        long pendingCount = predStatuses.size() - resolvedCount;

        // ALL resolved but none completed (all skipped/failed) -> SKIPPED
        if (resolvedCount == predStatuses.size() && completedCount == 0) {
            logger.info("[checkMergeNodeReady] {} all predecessors resolved but none COMPLETED (skipped={}, failed={}) -> MAKE_SKIPPED",
                node.id(), skippedCount, failedCount);
            return ReadyCheckResult.MAKE_SKIPPED;
        }

        // ALL strategy: READY when all predecessors are resolved and at least one COMPLETED
        if (pendingCount == 0 && completedCount >= 1) {
            if (failedCount > 0) {
                logger.info("[checkMergeNodeReady] {} all predecessors resolved with {} COMPLETED, {} FAILED, {} SKIPPED -> MAKE_READY",
                    node.id(), completedCount, failedCount, skippedCount);
            }
            return ReadyCheckResult.MAKE_READY;
        }

        return ReadyCheckResult.STAY_PENDING;
    }

    /**
     * Check if a decision node should become READY.
     */
    private ReadyCheckResult checkDecisionReady(WorkflowNode node) {
        // Decision has single predecessor
        if (node.predecessors().isEmpty()) {
            return ReadyCheckResult.STAY_PENDING;
        }

        NodeId predecessor = node.predecessors().get(0);
        NodeStatus predStatus = statusProvider.getStatus(predecessor);

        if (predStatus == NodeStatus.COMPLETED) {
            return ReadyCheckResult.MAKE_READY;
        }
        if (predStatus == NodeStatus.SKIPPED) {
            return ReadyCheckResult.MAKE_SKIPPED;
        }

        return ReadyCheckResult.STAY_PENDING;
    }

    /**
     * Check if a loop node should become READY.
     */
    private ReadyCheckResult checkLoopReady(WorkflowNode node) {
        // Same logic as executable node for initial entry
        return checkExecutableNodeReady(node);
    }

    /**
     * Check if a fork node should become READY.
     * Fork nodes wait for predecessors, then activate ALL their successors.
     */
    private ReadyCheckResult checkForkReady(WorkflowNode node) {
        // Fork nodes: wait for ALL predecessors to be terminal (like merge)
        // Then ALL successors become READY
        if (node.predecessors().isEmpty()) {
            return ReadyCheckResult.STAY_PENDING;
        }

        List<NodeStatus> predStatuses = node.predecessors().stream()
                .map(statusProvider::getStatus)
                .toList();

        boolean allTerminal = predStatuses.stream().allMatch(NodeStatus::isTerminal);
        if (!allTerminal) {
            return ReadyCheckResult.STAY_PENDING;
        }

        // If ALL predecessors are SKIPPED, this fork is SKIPPED
        boolean allSkipped = predStatuses.stream()
                .allMatch(s -> s == NodeStatus.SKIPPED);
        if (allSkipped) {
            return ReadyCheckResult.MAKE_SKIPPED;
        }

        // At least one predecessor is COMPLETED -> fork can proceed
        return ReadyCheckResult.MAKE_READY;
    }

    /**
     * Handle a decision node becoming READY - evaluate immediately.
     * In step-by-step mode, decisions auto-evaluate but their branches wait for user action.
     */
    public void handleDecisionBecameReady(NodeId decisionId) {
        NodeStatus currentStatus = statusProvider.getStatus(decisionId);

        // Only process if PENDING
        if (currentStatus != NodeStatus.PENDING) {
            return;
        }

        // Check if the decision should become READY
        WorkflowNode decisionNode = graph.getNode(decisionId);
        ReadyCheckResult result = checkDecisionReady(decisionNode);

        if (result == ReadyCheckResult.MAKE_SKIPPED) {
            statusUpdater.markSkipped(decisionId);
            return;
        }

        if (result != ReadyCheckResult.MAKE_READY) {
            return;
        }

        // Transition to READY, then immediately to COMPLETED (decisions auto-evaluate)
        statusUpdater.transitionTo(decisionId, NodeStatus.READY);
        statusUpdater.transitionTo(decisionId, NodeStatus.COMPLETED);

        // Check if we have saved branch selections (from persistence)
        // In split context, multiple items can select different branches
        java.util.Set<String> selectedBranches = decisionBranchProvider.getDecisionBranches(decisionId);
        String selectedBranch = (selectedBranches != null && !selectedBranches.isEmpty())
            ? selectedBranches.iterator().next()  // For regular decisions, use first branch
            : null;

        if (selectedBranches != null && !selectedBranches.isEmpty() && decisionNode.hasPortSuccessors()) {
            // Collect all selected targets from all branches
            java.util.Set<NodeId> selectedTargets = new java.util.HashSet<>();
            java.util.Set<String> matchedPorts = new java.util.HashSet<>();

            for (String branch : selectedBranches) {
                // First, check if branch is a PORT name
                NodeId selectedTargetViaPort = decisionNode.getSuccessorForPort(branch);

                if (selectedTargetViaPort != null) {
                    // branch is a port name - use it directly
                    logger.info("[ReadyPropagator] Decision {} using saved port '{}' -> target {}",
                        decisionId, branch, selectedTargetViaPort);
                    selectedTargets.add(selectedTargetViaPort);
                    matchedPorts.add(branch);
                } else {
                    // branch might be a target node - find it
                    NodeId selectedTargetId = NodeId.tryParse(branch);

                    for (Map.Entry<String, NodeId> entry : decisionNode.portSuccessors().entrySet()) {
                        NodeId targetId = entry.getValue();
                        if (targetId.equals(selectedTargetId) ||
                            targetId.toKey().equals(branch) ||
                            targetId.label().equals(branch)) {
                            logger.info("[ReadyPropagator] Decision {} using saved target '{}' -> matched: {}",
                                decisionId, branch, targetId);
                            selectedTargets.add(targetId);
                            matchedPorts.add(entry.getKey());
                            break;
                        }
                    }
                }
            }

            // Activate all selected targets
            for (NodeId target : selectedTargets) {
                decisionBranchProvider.recalculateReadyState(target);
            }

            // Mark all OTHER branches as SKIPPED (those not in any selected branch)
            for (Map.Entry<String, NodeId> entry : decisionNode.portSuccessors().entrySet()) {
                NodeId targetId = entry.getValue();
                if (!selectedTargets.contains(targetId) &&
                    statusProvider.getStatus(targetId) == NodeStatus.PENDING) {
                    statusUpdater.markSkipped(targetId);
                }
            }
        } else if (selectedBranch != null) {
            // No port successors but we have a saved selection - try to match against successors
            NodeId selectedTargetId = NodeId.tryParse(selectedBranch);
            NodeId matchedTarget = null;

            for (NodeId successorId : decisionNode.successors()) {
                if (successorId.equals(selectedTargetId) ||
                    successorId.toKey().equals(selectedBranch) ||
                    successorId.label().equals(selectedBranch)) {
                    matchedTarget = successorId;
                    break;
                }
            }

            if (matchedTarget != null) {
                decisionBranchProvider.recalculateReadyState(matchedTarget);
                for (NodeId successorId : decisionNode.successors()) {
                    if (!successorId.equals(matchedTarget) &&
                        statusProvider.getStatus(successorId) == NodeStatus.PENDING) {
                        statusUpdater.markSkipped(successorId);
                    }
                }
            }
        } else if (decisionNode.hasPortSuccessors()) {
            // No saved branch, but we have port successors
            // Default to 'if' branch (first condition)
            NodeId ifTarget = decisionNode.getSuccessorForPort("if");
            if (ifTarget != null) {
                logger.info("[ReadyPropagator] Decision {} defaulting to 'if' branch -> {}",
                    decisionId, ifTarget);
                decisionBranchProvider.recalculateReadyState(ifTarget);

                // Skip other branches
                for (Map.Entry<String, NodeId> entry : decisionNode.portSuccessors().entrySet()) {
                    if (!"if".equals(entry.getKey())) {
                        NodeId targetId = entry.getValue();
                        if (statusProvider.getStatus(targetId) == NodeStatus.PENDING) {
                            statusUpdater.markSkipped(targetId);
                        }
                    }
                }
            } else {
                // No 'if' port, fall back to first successor
                List<NodeId> successors = decisionNode.successors();
                if (!successors.isEmpty()) {
                    decisionBranchProvider.recalculateReadyState(successors.get(0));
                    for (int i = 1; i < successors.size(); i++) {
                        statusUpdater.markSkipped(successors.get(i));
                    }
                }
            }
        } else {
            // Fallback: no port successors, use old logic (first successor)
            List<NodeId> successors = decisionNode.successors();
            if (!successors.isEmpty()) {
                logger.debug("[ReadyPropagator] Decision {} has no port mapping, using first successor",
                    decisionId);
                decisionBranchProvider.recalculateReadyState(successors.get(0));
                for (int i = 1; i < successors.size(); i++) {
                    statusUpdater.markSkipped(successors.get(i));
                }
            }
        }
    }

    /**
     * Handle a fork node becoming READY - evaluate immediately.
     * Fork nodes activate ALL their successors (unlike Decision which selects one).
     */
    public void handleForkBecameReady(NodeId forkId) {
        NodeStatus currentStatus = statusProvider.getStatus(forkId);

        // Only process if PENDING
        if (currentStatus != NodeStatus.PENDING) {
            return;
        }

        // Check if the fork should become READY
        WorkflowNode forkNode = graph.getNode(forkId);
        ReadyCheckResult result = checkForkReady(forkNode);

        if (result == ReadyCheckResult.MAKE_SKIPPED) {
            statusUpdater.markSkipped(forkId);
            return;
        }

        if (result != ReadyCheckResult.MAKE_READY) {
            return;
        }

        // Transition to READY, then immediately to COMPLETED (forks auto-evaluate)
        statusUpdater.transitionTo(forkId, NodeStatus.READY);
        statusUpdater.transitionTo(forkId, NodeStatus.COMPLETED);

        logger.info("[ReadyPropagator] Fork {} completed - activating ALL successors", forkId);

        // Fork: activate ALL successors (parallel branching)
        // Unlike Decision which selects ONE branch, Fork activates ALL branches
        for (NodeId successorId : forkNode.successors()) {
            logger.info("[ReadyPropagator] Fork {} activating successor: {}", forkId, successorId);
            decisionBranchProvider.recalculateReadyState(successorId);
        }

        // Also activate port successors if any (branch_0, branch_1, etc.)
        if (forkNode.hasPortSuccessors()) {
            for (Map.Entry<String, NodeId> entry : forkNode.portSuccessors().entrySet()) {
                NodeId targetId = entry.getValue();
                logger.info("[ReadyPropagator] Fork {} activating port {} -> {}", forkId, entry.getKey(), targetId);
                decisionBranchProvider.recalculateReadyState(targetId);
            }
        }
    }

    /**
     * Propagate skip to successor nodes.
     * Called after a node is marked SKIPPED.
     */
    public void propagateSkipToSuccessors(WorkflowNode skippedNode) {
        for (NodeId successorId : skippedNode.successors()) {
            if (shouldSkipSuccessor(successorId)) {
                statusUpdater.markSkipped(successorId);  // Recursive call
            }
        }
    }

    /**
     * Determine if a successor should be skipped.
     *
     * <p>For merge nodes (explicit or implicit), a node should only be skipped
     * if ALL predecessors are SKIPPED. An implicit merge is any node with
     * multiple predecessors, even if it is not an explicit MERGE type node.
     * This ensures that convergence points after decision branches are not
     * prematurely skipped when only one branch is skipped but another is
     * still active.
     */
    private boolean shouldSkipSuccessor(NodeId successorId) {
        WorkflowNode successor = graph.getNodeOrNull(successorId);
        if (successor == null) {
            return false;
        }

        // Already terminal? Don't skip again
        if (statusProvider.getStatus(successorId).isTerminal()) {
            return false;
        }

        // Merge node (explicit or implicit): skip only if ALL predecessors are skipped.
        // isMergeNode() checks predecessors.size() > 1, which covers both explicit
        // MERGE type nodes and implicit merges (any node with multiple incoming edges).
        // We also check predecessors().size() > 1 as a defensive fallback in case
        // isMergeNode() is overridden or the node type changes the semantics.
        if (successor.isMergeNode() || successor.predecessors().size() > 1) {
            return successor.predecessors().stream()
                    .allMatch(pred -> statusProvider.getStatus(pred) == NodeStatus.SKIPPED);
        }

        // Single-predecessor node: skip if ANY predecessor is skipped
        // (because there's only one path to it)
        return successor.predecessors().stream()
                .anyMatch(pred -> statusProvider.getStatus(pred) == NodeStatus.SKIPPED);
    }

    /**
     * Result of checking if a node should become READY.
     */
    public enum ReadyCheckResult {
        MAKE_READY,
        MAKE_SKIPPED,
        STAY_PENDING
    }
}
