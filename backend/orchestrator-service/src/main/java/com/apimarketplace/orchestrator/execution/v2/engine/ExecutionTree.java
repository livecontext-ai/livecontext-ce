package com.apimarketplace.orchestrator.execution.v2.engine;

import com.apimarketplace.orchestrator.domain.workflow.ExecutionMode;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.nodes.ExecutionNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Execution tree built from a WorkflowPlan.
 * Supports multiple root nodes (triggers) for multi-workflow execution.
 *
 * <p>Each root node represents an independent workflow that can be executed
 * separately in step-by-step mode. Orphan nodes (not connected to any trigger)
 * are automatically ignored during execution.</p>
 */
public record ExecutionTree(
    String runId,
    String workflowRunId,
    String tenantId,
    WorkflowPlan plan,
    List<ExecutionNode> rootNodes,
    ExecutionMode executionMode,
    // PR15 - workspace identity threaded from the WorkflowRunEntity columns
    // (organization_id / organization_role) at tree-build time. Propagates into
    // every ExecutionContext created by UnifiedExecutionEngine.executeItem so
    // node executors (AgentNode, MCP nodes, future credential resolvers) read
    // the active workspace from the context - replacing the legacy
    // run.metadata['__orgId__'] re-read at AgentNode:1747. null/null = personal.
    String organizationId,
    String organizationRole
) {

    /** Legacy 6-arg constructor - pre-PR15 callers default org to null. */
    public ExecutionTree(
            String runId,
            String workflowRunId,
            String tenantId,
            WorkflowPlan plan,
            List<ExecutionNode> rootNodes,
            ExecutionMode executionMode) {
        this(runId, workflowRunId, tenantId, plan, rootNodes, executionMode, null, null);
    }

    public String getRunId() {
        return runId;
    }

    public String getWorkflowRunId() {
        return workflowRunId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getOrganizationId() {
        return organizationId;
    }

    public String getOrganizationRole() {
        return organizationRole;
    }

    public WorkflowPlan getPlan() {
        return plan;
    }

    /**
     * Get all root nodes (triggers) in this execution tree.
     * Each root represents an independent workflow.
     *
     * @return List of root nodes (never null, may be empty)
     */
    public List<ExecutionNode> getRootNodes() {
        return rootNodes != null ? rootNodes : List.of();
    }

    /**
     * Get the primary root node (first trigger).
     * Kept for backward compatibility with single-workflow code.
     *
     * @return The first root node, or null if no roots exist
     */
    public ExecutionNode getRootNode() {
        return rootNodes != null && !rootNodes.isEmpty() ? rootNodes.get(0) : null;
    }

    /**
     * Check if this tree has multiple independent workflows (multiple triggers).
     *
     * @return true if there are multiple root nodes
     */
    public boolean hasMultipleWorkflows() {
        return rootNodes != null && rootNodes.size() > 1;
    }

    public ExecutionMode getExecutionMode() {
        return executionMode != null ? executionMode : ExecutionMode.AUTOMATIC;
    }

    /**
     * Check if this execution tree is running in step-by-step mode.
     */
    public boolean isStepByStepMode() {
        return executionMode != null && executionMode.isStepByStep();
    }

    /**
     * Returns a new ExecutionTree with the specified execution mode.
     * Follows immutable record pattern for V2 best practices.
     */
    public ExecutionTree withExecutionMode(ExecutionMode mode) {
        return new ExecutionTree(runId, workflowRunId, tenantId, plan, rootNodes, mode,
                organizationId, organizationRole);
    }

    /**
     * Builder for creating execution trees.
     */
    public static class Builder {
        private String runId;
        private String workflowRunId;
        private String tenantId;
        private WorkflowPlan plan;
        private List<ExecutionNode> rootNodes = new ArrayList<>();
        private ExecutionMode executionMode = ExecutionMode.AUTOMATIC;
        private String organizationId;
        private String organizationRole;

        public Builder runId(String runId) {
            this.runId = runId;
            return this;
        }

        public Builder workflowRunId(String workflowRunId) {
            this.workflowRunId = workflowRunId;
            return this;
        }

        public Builder tenantId(String tenantId) {
            this.tenantId = tenantId;
            return this;
        }

        public Builder plan(WorkflowPlan plan) {
            this.plan = plan;
            return this;
        }

        /**
         * Set a single root node (backward compatibility).
         */
        public Builder rootNode(ExecutionNode rootNode) {
            this.rootNodes = new ArrayList<>();
            if (rootNode != null) {
                this.rootNodes.add(rootNode);
            }
            return this;
        }

        /**
         * Set multiple root nodes (multi-workflow support).
         */
        public Builder rootNodes(List<ExecutionNode> rootNodes) {
            this.rootNodes = rootNodes != null ? new ArrayList<>(rootNodes) : new ArrayList<>();
            return this;
        }

        /**
         * Add a root node to the list.
         */
        public Builder addRootNode(ExecutionNode rootNode) {
            if (rootNode != null) {
                this.rootNodes.add(rootNode);
            }
            return this;
        }

        public Builder executionMode(ExecutionMode executionMode) {
            this.executionMode = executionMode != null ? executionMode : ExecutionMode.AUTOMATIC;
            return this;
        }

        /** PR15 - set the workspace org id for the execution tree. */
        public Builder organizationId(String organizationId) {
            this.organizationId = organizationId;
            return this;
        }

        /** PR15 - set the workspace org role for the execution tree. */
        public Builder organizationRole(String organizationRole) {
            this.organizationRole = organizationRole;
            return this;
        }

        public ExecutionTree build() {
            return new ExecutionTree(runId, workflowRunId, tenantId, plan, rootNodes, executionMode,
                    organizationId, organizationRole);
        }
    }

    public static Builder builder() {
        return new Builder();
    }
}
