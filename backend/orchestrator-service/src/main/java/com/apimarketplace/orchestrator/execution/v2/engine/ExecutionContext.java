package com.apimarketplace.orchestrator.execution.v2.engine;

import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;
import com.apimarketplace.orchestrator.execution.v2.state.ExecutionState;
import com.apimarketplace.orchestrator.services.context.StepOutputsWriter;
import com.apimarketplace.orchestrator.utils.ExecutionConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Immutable execution context for a single item traversal.
 * Contains all data needed to execute nodes.
 *
 * <p><b>DAG Coordinates:</b> Every context carries explicit {@code triggerId},
 * {@code epoch}, and {@code spawn} fields identifying which DAG execution this
 * context belongs to. These are used for per-DAG state tracking.
 *
 * All mutations return a new ExecutionContext instance.
 */
public record ExecutionContext(
    // Workflow identity
    String runId,
    String workflowRunId,
    String tenantId,

    // Item identity
    String itemId,
    int itemIndex,

    // DAG coordinates (explicit, not in globalData)
    String triggerId,
    int epoch,
    int spawn,

    // Execution data
    Map<String, Object> triggerData,
    Map<String, Object> stepOutputs,  // Read-only view of step outputs

    // Execution state (immutable)
    ExecutionState state,

    // Configuration
    WorkflowPlan plan,

    // PR15 - workspace identity (org context). Sourced from
    // WorkflowRunEntity.organization_id / .organization_role at context boot
    // and threaded through every with*() mutator. null/null = personal scope.
    //
    // Strict-isolation downstream contract: nodes that resolve credentials,
    // storage, agent dispatch, etc. MUST consult these fields rather than
    // re-reading the legacy metadata['__orgId__'] stash at run.getOrgId().
    // The AgentNode credentials.__orgId__ workaround at AgentNode:1747 is
    // deprecated by this field and will be removed in PR20 once downstream
    // callers adopt the ExecutionContext-based contract.
    //
    // Placed at the END of the record param list so existing 13-arg
    // `new ExecutionContext(...)` call sites keep compiling - they reach the
    // 15-arg canonical constructor via the compact 13-arg overload below.
    String organizationId,
    String organizationRole
) {

    /**
     * Legacy 13-arg constructor - pre-PR15 call sites that don't know about
     * org context land here and get nulls (personal scope by default).
     * PR15 source-of-truth at runtime comes from V2StepByStepService boot
     * which uses the canonical 15-arg constructor with org fields populated
     * from the {@code workflow_runs} row.
     */
    public ExecutionContext(
            String runId,
            String workflowRunId,
            String tenantId,
            String itemId,
            int itemIndex,
            String triggerId,
            int epoch,
            int spawn,
            Map<String, Object> triggerData,
            Map<String, Object> stepOutputs,
            ExecutionState state,
            WorkflowPlan plan) {
        this(runId, workflowRunId, tenantId, itemId, itemIndex,
                triggerId, epoch, spawn, triggerData, stepOutputs, state, plan,
                null, null);
    }

    private static final Logger logger = LoggerFactory.getLogger(ExecutionContext.class);

    /**
     * Creates a new execution context for an item with explicit DAG coordinates.
     */
    public static ExecutionContext create(
            String runId,
            String workflowRunId,
            String tenantId,
            String itemId,
            int itemIndex,
            String triggerId,
            int epoch,
            int spawn,
            Map<String, Object> triggerData,
            WorkflowPlan plan) {

        return new ExecutionContext(
            runId,
            workflowRunId,
            tenantId,
            itemId,
            itemIndex,
            triggerId,
            epoch,
            spawn,
            new HashMap<>(triggerData),
            new HashMap<>(),
            ExecutionState.create(),
            plan
        );
    }

    /**
     * Creates a new execution context without DAG coordinates (legacy compat).
     * Defaults to triggerId=null, epoch=0, spawn=0.
     * @deprecated Use the full create() with DAG coordinates.
     */
    public static ExecutionContext create(
            String runId,
            String workflowRunId,
            String tenantId,
            String itemId,
            int itemIndex,
            Map<String, Object> triggerData,
            WorkflowPlan plan) {

        return create(runId, workflowRunId, tenantId, itemId, itemIndex,
                null, 0, 0, triggerData, plan);
    }

    /**
     * Returns the DAG coordinates for this context.
     */
    public DagCoordinates dagCoordinates() {
        return DagCoordinates.of(triggerId, epoch, spawn);
    }

    /**
     * Returns a new context with a different triggerId.
     */
    public ExecutionContext withTriggerId(String newTriggerId) {
        return new ExecutionContext(runId, workflowRunId, tenantId, itemId, itemIndex,
                newTriggerId, epoch, spawn, triggerData, stepOutputs, state, plan,
                organizationId, organizationRole);
    }

    /**
     * Returns a new context with a different epoch.
     */
    public ExecutionContext withEpoch(int newEpoch) {
        return new ExecutionContext(runId, workflowRunId, tenantId, itemId, itemIndex,
                triggerId, newEpoch, spawn, triggerData, stepOutputs, state, plan,
                organizationId, organizationRole);
    }

    /**
     * Returns a new context with a different spawn.
     */
    public ExecutionContext withSpawn(int newSpawn) {
        return new ExecutionContext(runId, workflowRunId, tenantId, itemId, itemIndex,
                triggerId, epoch, newSpawn, triggerData, stepOutputs, state, plan,
                organizationId, organizationRole);
    }

    /**
     * Returns a new context with updated DAG coordinates.
     */
    public ExecutionContext withDagCoordinates(String newTriggerId, int newEpoch, int newSpawn) {
        return new ExecutionContext(runId, workflowRunId, tenantId, itemId, itemIndex,
                newTriggerId, newEpoch, newSpawn, triggerData, stepOutputs, state, plan,
                organizationId, organizationRole);
    }

    /**
     * PR15 - returns a new context with org workspace identity. Used by
     * {@code V2StepByStepService} at execution boot to stamp the run's
     * organization_id / organization_role onto the context. Null/null = personal
     * scope.
     */
    public ExecutionContext withOrganization(String newOrganizationId, String newOrganizationRole) {
        return new ExecutionContext(runId, workflowRunId, tenantId, itemId, itemIndex,
                triggerId, epoch, spawn, triggerData, stepOutputs, state, plan,
                newOrganizationId, newOrganizationRole);
    }

    /**
     * Checks if a node has completed (success, failure, or skipped).
     */
    public boolean isCompleted(String nodeId) {
        return state.isCompleted(nodeId);
    }

    /**
     * Checks if a node completed successfully.
     */
    public boolean isSuccess(String nodeId) {
        return state.isSuccess(nodeId);
    }

    /**
     * Checks if a node completed with FAILED status.
     */
    public boolean isFailed(String nodeId) {
        return state.isFailed(nodeId);
    }

    /**
     * Checks if a node was SKIPPED (e.g., decision branch not taken, no items routed in split).
     *
     * <p>Canonical SKIPPED discriminator - read this instead of inferring SKIPPED from
     * {@code !isSuccess() && !isFailed()} or from output-presence heuristics. Symmetric with
     * {@link #isSuccess} and {@link #isFailed}.
     */
    public boolean isSkipped(String nodeId) {
        return state.isSkipped(nodeId);
    }

    /**
     * Checks if a node has started execution (but might not have completed).
     */
    public boolean isStarted(String nodeId) {
        return state.isStarted(nodeId);
    }

    /**
     * Gets the output of a step.
     */
    public Optional<Object> getStepOutput(String stepId) {
        return Optional.ofNullable(stepOutputs.get(stepId));
    }

    /**
     * Gets all step outputs (for template resolution).
     */
    public Map<String, Object> getAllStepOutputs() {
        return new HashMap<>(stepOutputs);
    }

    /**
     * Records a node execution result.
     * Returns a new ExecutionContext with updated state and outputs.
     */
    public ExecutionContext withResult(String nodeId, NodeExecutionResult result) {
        logger.info("[ExecutionContext] withResult: nodeId={}, status={}, durationMs={}, outputNull={}, outputEmpty={}, outputKeys={}, errorMessage={}, runId={}, itemId={}, itemIndex={}",
            nodeId, result.status(), result.durationMs(),
            result.output() == null, result.output() != null && result.output().isEmpty(),
            result.output() != null ? result.output().keySet() : "null",
            result.errorMessage().orElse("none"),
            runId, itemId, itemIndex);

        // Update state
        ExecutionState newState = state.recordResult(nodeId, result);

        // Update step outputs if result has output
        Map<String, Object> newOutputs = new HashMap<>(stepOutputs);
        if (result.output() != null && !result.output().isEmpty()) {
            logger.info("[ExecutionContext] Result has output for nodeId={}, outputKeys={}, outputSize={}", nodeId, result.output().keySet(), result.output().size());

            // Build enriched output structure with httpstatus
            Map<String, Object> apiOutput = new java.util.LinkedHashMap<>(result.output());

            // Extract http_status from output if present, otherwise default to 200
            Object httpStatusObj = apiOutput.remove(ExecutionConstants.KEY_HTTP_STATUS);
            int httpStatusCode = 200;
            if (httpStatusObj instanceof Number) {
                httpStatusCode = ((Number) httpStatusObj).intValue();
            }

            // Add httpstatus object with code and error
            Map<String, Object> httpstatus = new java.util.LinkedHashMap<>();
            httpstatus.put("code", httpStatusCode);
            httpstatus.put("error", result.errorMessage().orElse(""));
            apiOutput.put("httpstatus", httpstatus);

            // Wrap in output structure for expression resolution: {{mcp:label.output.xxx}}
            Map<String, Object> wrappedOutput = new java.util.LinkedHashMap<>();
            wrappedOutput.put("output", apiOutput);

            // writeWithAlias writes BOTH the full nodeId key AND its bare alias
            // (mcp:foo → foo) atomically. This is the contract the inline duplications
            // kept forgetting - bug class fixed structurally rather than by discipline.
            StepOutputsWriter.writeWithAlias(newOutputs, nodeId, wrappedOutput);
            logger.info("[ExecutionContext] Added output to stepOutputs for nodeId={}, httpStatusCode={}", nodeId, httpStatusCode);
        } else {
            logger.info("[ExecutionContext] Result has NO output or empty output for nodeId={}, will NOT add to stepOutputs", nodeId);
        }

        logger.info("[ExecutionContext] Returning new context with {} stepOutputs (before: {})", newOutputs.size(), stepOutputs.size());

        return new ExecutionContext(
            runId, workflowRunId, tenantId,
            itemId, itemIndex,
            triggerId, epoch, spawn,
            triggerData, newOutputs,
            newState, plan,
            organizationId, organizationRole
        );
    }

    /**
     * Records the start of node execution.
     */
    public ExecutionContext withStart(String nodeId) {
        ExecutionState newState = state.recordStart(nodeId);

        return new ExecutionContext(
            runId, workflowRunId, tenantId,
            itemId, itemIndex,
            triggerId, epoch, spawn,
            triggerData, stepOutputs,
            newState, plan,
            organizationId, organizationRole
        );
    }

    /**
     * Stores global data accessible to all nodes.
     */
    public ExecutionContext withGlobalData(String key, Object value) {
        ExecutionState newState = state.withGlobalData(key, value);

        return new ExecutionContext(
            runId, workflowRunId, tenantId,
            itemId, itemIndex,
            triggerId, epoch, spawn,
            triggerData, stepOutputs,
            newState, plan,
            organizationId, organizationRole
        );
    }

    /**
     * Returns a new context with updated item identity (for split sub-items).
     */
    public ExecutionContext withItemIndex(int newItemIndex) {
        return new ExecutionContext(
            runId, workflowRunId, tenantId,
            String.valueOf(newItemIndex), newItemIndex,
            triggerId, epoch, spawn,
            triggerData, stepOutputs,
            state, plan,
            organizationId, organizationRole
        );
    }

    public Optional<Object> getGlobalData(String key) {
        return state.getGlobalData(key);
    }

    /**
     * Get all global data keys (for iteration scanning).
     */
    public java.util.Set<String> getGlobalDataKeys() {
        return state.getGlobalDataKeys();
    }

    /**
     * Returns a new ExecutionContext with a step output directly set.
     * Used by BackEdgeHandler to update loop node output each iteration.
     */
    public ExecutionContext withStepOutput(String nodeId, Map<String, Object> output) {
        Map<String, Object> newOutputs = new HashMap<>(stepOutputs);
        // Writes BOTH full-key (mcp:foo) and bare alias (foo) - see StepOutputsWriter
        // for the bug-class this prevents.
        StepOutputsWriter.writeWithAlias(newOutputs, nodeId, output);
        return new ExecutionContext(
            runId, workflowRunId, tenantId,
            itemId, itemIndex,
            triggerId, epoch, spawn,
            triggerData, newOutputs,
            state, plan,
            organizationId, organizationRole
        );
    }

    /**
     * Returns a new ExecutionContext without the specified nodes removed from
     * stepOutputs and ExecutionState. Used by BackEdgeHandler to reset
     * nodes in the subgraph before re-execution.
     */
    public ExecutionContext withoutNodes(Set<String> nodeIds) {
        if (nodeIds == null || nodeIds.isEmpty()) {
            return this;
        }

        // Remove from step outputs
        Map<String, Object> newOutputs = new HashMap<>(stepOutputs);
        nodeIds.forEach(newOutputs::remove);

        // Remove from execution state
        ExecutionState newState = state.withoutNodes(nodeIds);

        return new ExecutionContext(
            runId, workflowRunId, tenantId,
            itemId, itemIndex,
            triggerId, epoch, spawn,
            triggerData, newOutputs,
            newState, plan,
            organizationId, organizationRole
        );
    }

    /**
     * Merges this context with another context from a parallel branch.
     * Used after Fork parallel execution to combine results from all branches.
     *
     * <p><b>stepOutputs collision protocol.</b> Parallel branches write disjoint
     * nodeIds by construction: every node executes exactly once, in exactly one
     * branch (merge nodes are deferred + atomically claimed by the join). Keys
     * shared by both sides therefore either (a) come from the common pre-fork
     * prefix - identical values, fast-pathed by the equality check - or (b) are
     * a RUNNING-vs-terminal race on the same node (one side recorded only the
     * start, the other the result). On a genuine value conflict the side whose
     * recorded node status is more advanced wins (completed-over-running,
     * same advancement order as {@link ExecutionState#merge}); ties keep the
     * incoming side (legacy {@code putAll} semantics) and the overlap is logged
     * as a contract violation.
     */
    public ExecutionContext merge(ExecutionContext other) {
        if (other == null) {
            return this;
        }

        // Merge step outputs (combine outputs from both branches)
        Map<String, Object> mergedOutputs = new HashMap<>(this.stepOutputs);
        for (Map.Entry<String, Object> entry : other.stepOutputs.entrySet()) {
            String key = entry.getKey();
            Object existing = mergedOutputs.get(key);
            Object incoming = entry.getValue();
            if (existing == null || existing == incoming || existing.equals(incoming)) {
                mergedOutputs.put(key, incoming);
                continue;
            }
            // Same key, different values - should not happen for branch-local nodes
            // (disjoint by construction). Resolve by node-state advancement.
            int existingPriority = statusPriorityForOutputKey(key, this.state);
            int incomingPriority = statusPriorityForOutputKey(key, other.state);
            boolean keepIncoming = incomingPriority >= existingPriority;
            if (keepIncoming) {
                mergedOutputs.put(key, incoming);
            }
            logger.warn("[ExecutionContext] Branch-merge stepOutputs overlap on '{}' (branches should write disjoint nodeIds): "
                    + "existingStatusPriority={}, incomingStatusPriority={} → keeping {} side (completed-over-running), runId={}",
                key, existingPriority, incomingPriority, keepIncoming ? "incoming" : "existing", runId);
        }

        // Merge execution states
        ExecutionState mergedState = this.state.merge(other.state);

        return new ExecutionContext(
            runId, workflowRunId, tenantId,
            itemId, itemIndex,
            triggerId, epoch, spawn,
            triggerData, mergedOutputs,
            mergedState, plan,
            // Org context is identity-bound to the workflow run, not the
            // execution branch - preserve own (both branches share it).
            organizationId, organizationRole
        );
    }

    /**
     * Resolves the node-state advancement priority for a stepOutputs key.
     * Keys are either full nodeIds ({@code mcp:foo}) or bare aliases ({@code foo})
     * - see {@link StepOutputsWriter#writeWithAlias}. Aliases have no direct
     * NodeState entry, so fall back to any prefixed nodeId whose bare alias
     * matches; this keeps the merge decision identical for a full-key/alias pair.
     */
    private static int statusPriorityForOutputKey(String key, ExecutionState state) {
        com.apimarketplace.orchestrator.domain.execution.NodeStatus direct = state.getNodeStatus(key);
        if (direct != com.apimarketplace.orchestrator.domain.execution.NodeStatus.PENDING) {
            return ExecutionState.statusPriority(direct);
        }
        for (String nodeId : state.nodeStates().keySet()) {
            if (key.equals(StepOutputsWriter.bareAlias(nodeId))) {
                return ExecutionState.statusPriority(state.getNodeStatus(nodeId));
            }
        }
        return ExecutionState.statusPriority(com.apimarketplace.orchestrator.domain.execution.NodeStatus.PENDING);
    }
}
