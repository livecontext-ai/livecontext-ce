package com.apimarketplace.orchestrator.execution.v2.state;

import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Immutable execution state.
 * Tracks the status and results of all nodes in the workflow.
 * All mutations return a new state instance.
 */
public record ExecutionState(
    Map<String, NodeState> nodeStates,
    Map<String, Object> globalData
) {

    public static ExecutionState create() {
        return new ExecutionState(
            new ConcurrentHashMap<>(),
            new ConcurrentHashMap<>()
        );
    }

    public NodeStatus getNodeStatus(String nodeId) {
        NodeState state = nodeStates.get(nodeId);
        return state != null ? state.status() : NodeStatus.PENDING;
    }

    public Optional<NodeState> getNodeState(String nodeId) {
        return Optional.ofNullable(nodeStates.get(nodeId));
    }

    public boolean isCompleted(String nodeId) {
        NodeStatus status = getNodeStatus(nodeId);
        return status == NodeStatus.COMPLETED
            || status == NodeStatus.FAILED
            || status == NodeStatus.SKIPPED;
    }

    public boolean isSuccess(String nodeId) {
        return getNodeStatus(nodeId) == NodeStatus.COMPLETED;
    }

    public boolean isFailed(String nodeId) {
        return getNodeStatus(nodeId) == NodeStatus.FAILED;
    }

    public boolean isSkipped(String nodeId) {
        return getNodeStatus(nodeId) == NodeStatus.SKIPPED;
    }

    /**
     * Checks if a node has started execution (not PENDING).
     * A node is "started" if it's running, completed, failed, or skipped.
     */
    public boolean isStarted(String nodeId) {
        return getNodeStatus(nodeId) != NodeStatus.PENDING;
    }

    /**
     * Records a node execution result.
     * Returns a new ExecutionState with updated node state.
     */
    public ExecutionState recordResult(String nodeId, NodeExecutionResult result) {
        Map<String, NodeState> newStates = new HashMap<>(nodeStates);
        newStates.put(nodeId, NodeState.from(result));

        return new ExecutionState(newStates, globalData);
    }

    /**
     * Records start of node execution.
     */
    public ExecutionState recordStart(String nodeId) {
        Map<String, NodeState> newStates = new HashMap<>(nodeStates);
        newStates.put(nodeId, NodeState.running(nodeId));

        return new ExecutionState(newStates, globalData);
    }

    /**
     * Stores global data accessible to all nodes.
     */
    public ExecutionState withGlobalData(String key, Object value) {
        Map<String, Object> newData = new HashMap<>(globalData);
        newData.put(key, value);

        return new ExecutionState(nodeStates, newData);
    }

    public Optional<Object> getGlobalData(String key) {
        return Optional.ofNullable(globalData.get(key));
    }

    /**
     * Get all global data keys (for iteration scanning).
     */
    public java.util.Set<String> getGlobalDataKeys() {
        return globalData.keySet();
    }

    /**
     * Returns a new ExecutionState with the specified nodes removed from nodeStates.
     * Used by BackEdgeHandler to reset nodes before re-execution in a back-edge iteration.
     *
     * @param nodeIds Set of node IDs to remove
     * @return New ExecutionState without those nodes
     */
    public ExecutionState withoutNodes(Set<String> nodeIds) {
        if (nodeIds == null || nodeIds.isEmpty()) {
            return this;
        }

        Map<String, NodeState> newStates = new HashMap<>(nodeStates);
        nodeIds.forEach(newStates::remove);

        return new ExecutionState(newStates, globalData);
    }

    /**
     * Merges this state with another state from a parallel branch.
     * Used after Fork parallel execution to combine results from all branches.
     *
     * @param other The state from another parallel branch
     * @return A new ExecutionState containing results from both branches
     */
    public ExecutionState merge(ExecutionState other) {
        if (other == null) {
            return this;
        }

        // Merge node states (combine results from both branches)
        Map<String, NodeState> mergedNodeStates = new HashMap<>(this.nodeStates);
        for (Map.Entry<String, NodeState> entry : other.nodeStates.entrySet()) {
            String nodeId = entry.getKey();
            NodeState otherState = entry.getValue();
            // Only add if not already present or if other is more "advanced"
            // (e.g., COMPLETED > RUNNING > PENDING)
            if (!mergedNodeStates.containsKey(nodeId) ||
                isMoreAdvanced(otherState.status(), mergedNodeStates.get(nodeId).status())) {
                mergedNodeStates.put(nodeId, otherState);
            }
        }

        // Merge global data (other takes precedence for same keys)
        Map<String, Object> mergedGlobalData = new HashMap<>(this.globalData);
        mergedGlobalData.putAll(other.globalData);

        return new ExecutionState(mergedNodeStates, mergedGlobalData);
    }

    /**
     * Checks if status1 is more "advanced" than status2.
     * Order: PENDING < RUNNING < COMPLETED/FAILED/SKIPPED
     */
    private boolean isMoreAdvanced(NodeStatus status1, NodeStatus status2) {
        return statusPriority(status1) > statusPriority(status2);
    }

    /**
     * Advancement priority of a node status: terminal (COMPLETED/FAILED/SKIPPED) &gt;
     * in-flight (RUNNING/WAITING_TRIGGER/COLLECTING/AWAITING_SIGNAL) &gt; not-started
     * (PENDING/READY). Shared with {@code ExecutionContext.merge}'s stepOutputs
     * collision protocol so context-merge and state-merge use ONE advancement order
     * (completed-over-running).
     */
    public static int statusPriority(NodeStatus status) {
        return switch (status) {
            case PENDING, READY -> 0;
            case RUNNING, WAITING_TRIGGER, COLLECTING, AWAITING_SIGNAL -> 1;
            case COMPLETED, FAILED, SKIPPED -> 2;
        };
    }
}
