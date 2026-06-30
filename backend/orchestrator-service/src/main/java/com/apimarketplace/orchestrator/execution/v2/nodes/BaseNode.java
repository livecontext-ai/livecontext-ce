package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.ServiceRegistry;
import com.apimarketplace.orchestrator.execution.v2.template.V2TemplateAdapter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Base implementation of ExecutionNode with common functionality.
 * Concrete node types extend this class.
 *
 * <p>Overrides {@link ExecutionNode#acceptServices(ServiceRegistry)} to receive
 * services polymorphically. Subclasses should override to pull additional
 * services they need.
 *
 * Note: Event emission, persistence, and metrics are handled by
 * V2ExecutionEventService in UnifiedExecutionEngine.traverseTree(),
 * not by individual nodes.
 */
public abstract class BaseNode implements ExecutionNode {

    protected final String nodeId;
    protected final NodeType type;
    protected final List<ExecutionNode> successors;
    protected final List<String> predecessorIds;  // For implicit merge detection

    // Services for node execution
    protected com.apimarketplace.orchestrator.services.interfaces.ToolsGateway toolsGateway;
    protected V2TemplateAdapter templateAdapter;
    // Used by file-producing nodes to resolve a sentinel epoch-0 to the run's real
    // current epoch when stamping a stored file (see resolveStorageEpoch).
    protected com.apimarketplace.orchestrator.repository.WorkflowRunRepository workflowRunRepository;

    protected BaseNode(String nodeId, NodeType type) {
        this.nodeId = nodeId;
        this.type = type;
        this.successors = new ArrayList<>();
        this.predecessorIds = new ArrayList<>();
    }

    /**
     * Sets ToolsGateway service for step execution.
     */
    public void setToolsGateway(com.apimarketplace.orchestrator.services.interfaces.ToolsGateway toolsGateway) {
        this.toolsGateway = toolsGateway;
    }

    /**
     * Sets the template adapter for SpEL template resolution.
     */
    public void setTemplateAdapter(V2TemplateAdapter templateAdapter) {
        this.templateAdapter = templateAdapter;
    }

    /**
     * Accepts services from the registry.
     * Base implementation injects toolsGateway and templateAdapter.
     * Subclasses should override to pull additional services they need.
     *
     * @param registry The service registry containing all available services
     */
    @Override
    public void acceptServices(ServiceRegistry registry) {
        this.toolsGateway = registry.getToolsGateway();
        this.templateAdapter = registry.getTemplateAdapter();
        this.workflowRunRepository = registry.getWorkflowRunRepository();
    }

    /**
     * Resolves the epoch to stamp on a file a workflow file-producer node persists to
     * {@code storage.storage}.
     *
     * <p><b>Why this is not simply {@code context.epoch()}.</b> {@code epoch == 0} is the
     * "unresolved" sentinel in the engine: a node reached via a deferred dispatch path
     * (signal-resume, async agent completion) can carry a {@code context.epoch()} of 0 when
     * the signal/pending it resumes from was registered before the first trigger fire, even
     * though the run has since fired to a real epoch (1, 2, ...). The execution context
     * deliberately keeps that 0 so it loads predecessor outputs / reconstructs state from the
     * epoch where that data actually lives - changing the context epoch would break readiness
     * and template resolution. But the FILE coordinate must not inherit the spurious 0, or the
     * stored file lands under a phantom {@code epoch 0} that never fired and the run's file
     * browser misattributes it.
     *
     * <p>This mirrors exactly what {@code StepDataPersistenceService} already does for the
     * node's {@code workflow_step_data} row
     * ({@code (explicitEpoch > 0) ? explicitEpoch : getCurrentEpochFromRun(...)}), so the file
     * row and its step-data row always agree on the epoch bucket.
     *
     * <p>Returns {@code context.epoch()} verbatim when it is already {@code > 0} (the common
     * inline-fire path), or when the run's current epoch cannot be resolved (best-effort:
     * repository absent in unit tests, or a genuine epoch-0 run that never fired).
     */
    protected int resolveStorageEpoch(ExecutionContext context) {
        int epoch = context.epoch();
        if (epoch > 0) {
            return epoch;
        }
        if (workflowRunRepository == null || context.runId() == null) {
            return epoch;
        }
        try {
            Integer resolved = workflowRunRepository.findByRunIdPublic(context.runId())
                .map(run -> {
                    Map<String, Object> metadata = run.getMetadata();
                    Object value = metadata != null ? metadata.get("currentEpoch") : null;
                    return (value instanceof Number n) ? n.intValue() : null;
                })
                .orElse(null);
            if (resolved != null && resolved > 0) {
                return resolved;
            }
        } catch (Exception e) {
            // Best-effort: never fail a file write because the epoch could not be re-resolved.
        }
        return epoch;
    }

    /**
     * Resolves {{template}} placeholders in a string using the current execution context.
     * Returns the original string if templateAdapter is unavailable or resolution fails.
     */
    protected String resolveTemplateString(String template, ExecutionContext context) {
        if (template == null || templateAdapter == null) return template;
        try {
            java.util.Map<String, Object> toResolve = java.util.Map.of("__v__", template);
            java.util.Map<String, Object> resolved = templateAdapter.resolveTemplates(toResolve, context);
            Object value = resolved.get("__v__");
            return value != null ? value.toString() : template;
        } catch (Exception e) {
            return template;
        }
    }

    @Override
    public String getNodeId() {
        return nodeId;
    }

    @Override
    public NodeType getType() {
        return type;
    }

    /**
     * Default implementation: can execute if all dependencies are completed.
     *
     * <p>Handles port-based predecessors (e.g., "core:check_item:if") by also checking
     * the base node ID (e.g., "core:check_item") for completion. This is needed because
     * Decision branch targets have predecessors with ports for split-aware routing,
     * but the execution state tracks completion by the base node ID.
     */
    @Override
    public boolean canExecute(ExecutionContext context) {
        // Get dependencies from plan if available
        List<String> dependencies = getDependencies(context);

        // Multi-trigger shared sink: a node with multiple trigger predecessors converges
        // several triggers onto the same DAG (auto-detected as one DAG group). Each trigger
        // fires its OWN epoch - only the current trigger's edge can be completed in this
        // epoch. The other trigger predecessors never fire in this epoch by design, so
        // require only the current trigger's edge (plus all non-trigger dependencies).
        //
        // This mirrors ReadyNodeCalculator.filterForeignTriggerPredecessors. Without this
        // filter, the engine's canExecute returned false → the node was marked SKIPPED
        // ("Prerequisites not met or condition false") even though ReadyNodeCalculator
        // considered it ready - causing shared sinks (wait, transform, mcp, etc.) to skip
        // on every trigger fire in a multi-trigger workflow.
        long triggerDepCount = dependencies.stream()
            .filter(dep -> dep != null && dep.startsWith("trigger:"))
            .count();
        List<String> effectiveDependencies = dependencies;
        String currentTriggerId = context.triggerId();
        if (triggerDepCount > 1 && currentTriggerId != null) {
            effectiveDependencies = dependencies.stream()
                .filter(dep -> dep == null || !dep.startsWith("trigger:") || dep.equals(currentTriggerId))
                .toList();
        }

        // Can execute if all (effective) dependencies are completed.
        // For port-based predecessors (e.g., "core:decision:if"), also check base node ID.
        return effectiveDependencies.stream()
            .allMatch(dep -> {
                if (context.isCompleted(dep)) {
                    return true;
                }
                // Try stripping port: "core:check_item:if" -> "core:check_item"
                com.apimarketplace.orchestrator.utils.EdgeRefParser.EdgeRef ref =
                    com.apimarketplace.orchestrator.utils.EdgeRefParser.parse(dep);
                if (ref != null && ref.port() != null && !ref.port().isEmpty()) {
                    String baseNodeId = ref.nodeType() + ":" + ref.nodeLabel();
                    return context.isCompleted(baseNodeId);
                }
                return false;
            });
    }

    /**
     * Get dependencies for this node.
     * Returns predecessorIds if set (for implicit merge support).
     * Override in subclasses if needed.
     */
    protected List<String> getDependencies(ExecutionContext context) {
        return predecessorIds;  // Return predecessors for implicit merge check
    }

    /**
     * Adds a predecessor node ID.
     * Used for implicit merge detection - nodes with multiple predecessors
     * must wait for all of them to complete.
     */
    public void addPredecessor(String predecessorId) {
        if (!this.predecessorIds.contains(predecessorId)) {
            this.predecessorIds.add(predecessorId);
        }
    }

    /**
     * Sets all predecessor IDs at once.
     */
    public void setPredecessors(List<String> predecessorIds) {
        this.predecessorIds.clear();
        this.predecessorIds.addAll(predecessorIds);
    }

    /**
     * Returns the predecessor node IDs.
     */
    public List<String> getPredecessorIds() {
        return predecessorIds;
    }

    /**
     * Checks if this node is an implicit merge (has multiple predecessors).
     */
    public boolean isImplicitMerge() {
        return predecessorIds.size() > 1;
    }

    /**
     * Lifecycle callback after node execution.
     * Default implementation is empty - event emission, persistence, and metrics
     * are handled by V2ExecutionEventService in UnifiedExecutionEngine.
     * Subclasses can override for node-specific cleanup or side effects.
     */
    @Override
    public void onComplete(ExecutionContext context, NodeExecutionResult result) {
        // Default: no-op
        // V2ExecutionEventService handles all lifecycle events
    }

    /**
     * Default implementation: return all successors if result is success.
     * If the node failed, return empty list - successors should not execute.
     * Override in subclasses that need conditional flow (Decision, Loop).
     */
    @Override
    public List<ExecutionNode> getNextNodes(NodeExecutionResult result) {
        // If this node failed, do not return successors - they should be skipped
        if (result != null && result.isFailure()) {
            return List.of();
        }
        return successors;
    }

    /**
     * Adds a successor node.
     */
    public void addSuccessor(ExecutionNode successor) {
        this.successors.add(successor);
    }

    /**
     * Sets all successors at once.
     */
    public void setSuccessors(List<ExecutionNode> successors) {
        this.successors.clear();
        this.successors.addAll(successors);
    }

    @Override
    public Map<String, Object> getMetadata() {
        return Map.of(
            "nodeId", nodeId,
            "type", type.name(),
            "successorCount", successors.size()
        );
    }

    /**
     * Returns all direct successors of this node (regardless of conditional logic).
     * This is used for skip propagation to traverse all downstream nodes.
     */
    public List<ExecutionNode> getSuccessors() {
        return successors;
    }

    /**
     * Enriches the node output map with the four mandatory metadata keys required by
     * {@code StepDataPersistenceService} for correct persistence to {@code workflow_step_data}:
     * <ul>
     *   <li>{@code node_type} - the {@link NodeType} name (used by enrichEntityWithNodeTypeFields)</li>
     *   <li>{@code item_index} - the per-item index from the execution context (PRIMARY persistence key)</li>
     *   <li>{@code itemIndex} - camelCase legacy alias kept for backwards compatibility</li>
     *   <li>{@code item_id} - the per-item id from the execution context</li>
     * </ul>
     *
     * <p>Use this helper to never forget mandatory metadata. Forgetting {@code item_index}
     * causes {@code StepDataPersistenceService.recordStep()} to drop the row silently.
     *
     * <p>This method is <b>idempotent</b>: keys already present in {@code result} are left
     * untouched, so a node that needs a custom {@code node_type} string (e.g. SPLIT, CLASSIFY)
     * can set it before calling this helper.
     *
     * @param result  the mutable output map produced by the node (must not be null)
     * @param context the current execution context
     * @return the same map instance, enriched in place
     */
    protected Map<String, Object> enrichWithMetadata(Map<String, Object> result, ExecutionContext context) {
        if (result == null) return result;
        result.putIfAbsent("node_type", this.type.name());
        result.putIfAbsent("item_index", context.itemIndex());
        result.putIfAbsent("itemIndex", context.itemIndex());
        result.putIfAbsent("item_id", context.itemId());
        return result;
    }

    /**
     * Convenience: enrich the result with mandatory metadata then wrap in a successful
     * {@link NodeExecutionResult}. Use this to never forget the mandatory keys.
     *
     * @param result  the node output map
     * @param context the current execution context
     * @return a success NodeExecutionResult with metadata-enriched output
     */
    protected NodeExecutionResult successWithMetadata(Map<String, Object> result, ExecutionContext context) {
        return NodeExecutionResult.success(nodeId, enrichWithMetadata(result, context));
    }

}
