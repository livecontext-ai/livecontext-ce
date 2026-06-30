package com.apimarketplace.orchestrator.services.interfaces;

import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.services.state.NodeId;
import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import com.apimarketplace.orchestrator.services.state.WorkflowGraph;

import java.util.List;
import java.util.Map;

/**
 * Interface for workflow state management.
 * Defines the contract for tracking node statuses during workflow execution.
 *
 * Implementations handle:
 * - Node status transitions (PENDING → READY → RUNNING → COMPLETED/FAILED/SKIPPED)
 * - Edge status derivation
 * - Ready node propagation
 * - Decision branch tracking
 *
 * @see com.apimarketplace.orchestrator.services.state.WorkflowStateManager
 */
public interface StateManager {

    // ==================== Initialization ====================

    /**
     * Initialize state for a new workflow run.
     *
     * @param runId Unique identifier for this run
     * @param plan The workflow plan to execute
     */
    void initialize(String runId, WorkflowPlan plan);

    /**
     * Initialize state with an existing graph.
     *
     * @param runId Unique identifier for this run
     * @param graph Pre-built workflow graph
     */
    void initializeWithGraph(String runId, WorkflowGraph graph);

    // ==================== Node Status Operations ====================

    /**
     * Get the current status of a node.
     *
     * @param nodeId The node identifier
     * @return Current status, or PENDING if not found
     */
    NodeStatus getStatus(NodeId nodeId);

    /**
     * Get the current status of a node by its key.
     *
     * @param nodeKey The node key (e.g., "mcp:fetch_data")
     * @return Current status, or PENDING if not found
     */
    NodeStatus getStatus(String nodeKey);

    /**
     * Mark a node as RUNNING.
     *
     * @param nodeId The node to mark
     */
    void markRunning(NodeId nodeId);

    /**
     * Mark a node as COMPLETED with a result.
     *
     * @param nodeId The node to mark
     * @param result The execution result
     */
    void markCompleted(NodeId nodeId, Object result);

    /**
     * Mark a node as SKIPPED.
     *
     * @param nodeId The node to mark
     */
    void markSkipped(NodeId nodeId);

    /**
     * Mark a node as FAILED with an error.
     *
     * @param nodeId The node to mark
     * @param error The failure cause
     */
    void markFailed(NodeId nodeId, Throwable error);

    // ==================== Query Operations ====================

    /**
     * Get all nodes that are ready for execution.
     *
     * @return List of ready node IDs
     */
    List<NodeId> getReadyNodes();

    /**
     * Get all nodes with a specific status.
     *
     * @param status The status to filter by
     * @return List of matching node IDs
     */
    List<NodeId> getNodesByStatus(NodeStatus status);

    /**
     * Get the result of a completed node.
     *
     * @param nodeId The node identifier
     * @return The result, or null if not completed
     */
    Object getResult(NodeId nodeId);

    /**
     * Get all node statuses.
     *
     * @return Map of node ID to status
     */
    Map<NodeId, NodeStatus> getAllStatuses();

    // ==================== Decision Branch Tracking ====================

    /**
     * Record which branch was taken for a decision node.
     *
     * @param decisionId The decision node ID
     * @param branchPort The selected branch port (e.g., "if", "else", "elseif_0")
     */
    void recordDecisionBranch(NodeId decisionId, String branchPort);

    /**
     * Get the branch taken for a decision node.
     * Returns the first branch if multiple are recorded (for backward compatibility).
     *
     * @param decisionId The decision node ID
     * @return The branch port, or null if not yet decided
     */
    String getDecisionBranch(NodeId decisionId);

    /**
     * Get all branches taken for a decision node.
     * In forEach context, multiple items can select different branches.
     *
     * @param decisionId The decision node ID
     * @return All selected branch ports, or null if not yet decided
     */
    default java.util.Set<String> getDecisionBranches(NodeId decisionId) {
        String branch = getDecisionBranch(decisionId);
        return branch != null ? java.util.Set.of(branch) : null;
    }

    // ==================== Workflow State ====================

    /**
     * Check if the workflow has completed (all nodes resolved).
     *
     * @return true if complete
     */
    boolean isWorkflowComplete();

    /**
     * Check if the workflow has any failures.
     *
     * @return true if any node failed
     */
    boolean hasFailures();

    /**
     * Get the workflow graph.
     *
     * @return The graph structure
     */
    WorkflowGraph getGraph();

    /**
     * Get the current run ID.
     *
     * @return The run identifier
     */
    String getRunId();
}
