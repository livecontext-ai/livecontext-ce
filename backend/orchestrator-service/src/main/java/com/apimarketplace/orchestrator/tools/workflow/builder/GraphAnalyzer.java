package com.apimarketplace.orchestrator.tools.workflow.builder;

import com.apimarketplace.orchestrator.domain.workflow.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * GraphAnalyzer - Calculate variable accessibility in workflow graphs.
 *
 * PRINCIPLE: A node can reference ONLY its predecessors (ancestors in the execution graph).
 *
 * Example:
 * <pre>
 * trigger:webhook
 *     ↓
 * mcp:fetch_api
 *     ↓
 * agent:analyze
 *     ↓
 * mcp:save_results
 * </pre>
 *
 * From mcp:save_results:
 * - ✅ Can access: trigger:webhook, mcp:fetch_api, agent:analyze
 * - ❌ Cannot access: mcp:send_email (not a predecessor)
 */
@Slf4j
@Component
public class GraphAnalyzer {

    /**
     * Get all accessible node IDs from a given node (predecessors in the graph).
     *
     * Uses breadth-first traversal to find all ancestors.
     *
     * @param plan WorkflowPlan
     * @param nodeId Current node ID
     * @return Set of accessible node IDs (predecessors)
     */
    public Set<String> getAccessibleNodes(WorkflowPlan plan, String nodeId) {
        Set<String> accessible = new HashSet<>();
        Queue<String> queue = new LinkedList<>();
        queue.add(nodeId);

        log.debug("Computing accessible nodes from: {}", nodeId);

        while (!queue.isEmpty()) {
            String current = queue.poll();

            // Find all predecessors (edges pointing TO current)
            for (Edge edge : plan.getEdges()) {
                // V2: All edges have from/to fields, skip if to is null (malformed edge)
                if (edge.to() != null && edge.to().equals(current) && !accessible.contains(edge.from())) {
                    accessible.add(edge.from());
                    queue.add(edge.from());
                    log.trace("Found predecessor: {} -> {}", edge.from(), current);
                }
            }
        }

        log.debug("Accessible nodes from {}: {}", nodeId, accessible);
        return accessible;
    }

    /**
     * Get available variables (output schemas) for a given node.
     *
     * Returns a map of: nodeId -> {outputField -> description}
     *
     * @param plan WorkflowPlan
     * @param currentNodeId Current node ID
     * @return Map of accessible variables
     */
    public Map<String, Map<String, String>> getAvailableVariables(WorkflowPlan plan, String currentNodeId) {
        Set<String> accessibleNodeIds = getAccessibleNodes(plan, currentNodeId);
        Map<String, Map<String, String>> variables = new LinkedHashMap<>();

        log.debug("Computing available variables for: {}", currentNodeId);

        for (String nodeId : accessibleNodeIds) {
            Map<String, String> outputSchema = getNodeOutputSchema(plan, nodeId);
            if (!outputSchema.isEmpty()) {
                variables.put(nodeId, outputSchema);
            }
        }

        log.debug("Available variables for {}: {} nodes", currentNodeId, variables.size());
        return variables;
    }

    /**
     * Get the output schema for a specific node.
     *
     * Returns a map of: outputField -> description
     *
     * @param plan WorkflowPlan
     * @param nodeId Node ID
     * @return Output schema
     */
    private Map<String, String> getNodeOutputSchema(WorkflowPlan plan, String nodeId) {
        log.trace("Getting output schema for node: {}", nodeId);

        // Trigger nodes
        if (nodeId.startsWith("trigger:")) {
            Trigger trigger = findTrigger(plan, nodeId);
            if (trigger != null) {
                return getTriggerOutputSchema(trigger);
            }
        }

        // Step nodes
        if (nodeId.startsWith("mcp:")) {
            Step step = findStep(plan, nodeId);
            if (step != null) {
                return getStepOutputSchema(step);
            }
        }

        // Agent nodes (same as step for graph purposes)
        if (nodeId.startsWith("agent:")) {
            Step agent = findStep(plan, nodeId);  // Agents are stored as steps
            if (agent != null) {
                return Map.of(
                    "output", "Agent response (structured or text)",
                    "text", "Agent response as text",
                    "json", "Agent response as JSON (if structured)"
                );
            }
        }

        // Loop nodes
        if (nodeId.startsWith("core:")) {
            Core loop = findCore(plan, nodeId);
            if (loop != null) {
                return getLoopOutputSchema(loop);
            }
        }

        // Decision nodes (typically don't have outputs, they route)
        if (nodeId.startsWith("core:")) {
            return Map.of(
                "branch_taken", "Which branch was taken",
                "condition_result", "Result of the condition evaluation"
            );
        }

        log.trace("No output schema found for node: {}", nodeId);
        return Map.of();
    }

    /**
     * Get trigger output schema.
     */
    private Map<String, String> getTriggerOutputSchema(Trigger trigger) {
        Map<String, String> schema = new LinkedHashMap<>();

        schema.put("timestamp", "Execution timestamp");

        // Schedule trigger
        if ("schedule".equals(trigger.type())) {
            schema.put("scheduled_time", "Scheduled execution time");
        }

        // Webhook trigger
        if ("webhook".equals(trigger.type())) {
            schema.put("body", "Webhook request body");
            schema.put("headers", "Webhook request headers");
            schema.put("query", "Webhook query parameters");
        }

        // Tables trigger
        if ("tables".equals(trigger.type())) {
            schema.put("row", "Row data that triggered the workflow");
            schema.put("row_id", "ID of the row");
            schema.put("operation", "Operation type (INSERT, UPDATE, DELETE)");
        }

        return schema;
    }

    /**
     * Get step output schema.
     */
    private Map<String, String> getStepOutputSchema(Step step) {
        Map<String, String> schema = new LinkedHashMap<>();

        // Infer type from step id and other fields
        String stepId = step.id() != null ? step.id().toLowerCase() : "";

        // CRUD step (V2: crud operations are in Step with type like "crud-create-row")
        if (stepId.startsWith("crud/") || step.dataSourceId() != null || step.isCrudStep()) {
            schema.put("result", "Query/operation result");
            schema.put("rows", "Rows returned (for select)");
            schema.put("row_id", "ID of inserted/updated row");
            schema.put("affected_count", "Number of affected rows");
        }
        // Transform step (V2: transforms are in Cores, not Steps - legacy check by stepId only)
        else if ("__transform__".equals(stepId)) {
            schema.put("output", "Transformed data");
        }
        // Wait step (V2: waits are in Cores, not Steps - legacy check by stepId only)
        else if ("__wait__".equals(stepId)) {
            schema.put("output", "Input data passed through after delay");
        }
        // Generic MCP step
        else {
            schema.put("output", "Step execution result");
            schema.put("status", "Execution status");
        }

        return schema;
    }

    /**
     * Get loop output schema.
     */
    private Map<String, String> getLoopOutputSchema(Core loop) {
        return Map.of(
            "index", "Current iteration index",
            "currentItem", "Current item being processed",
            "iteration_count", "Total number of iterations",
            "outputs", "Array of outputs from each iteration"
        );
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // FIND HELPERS
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Find a trigger by ID.
     */
    private Trigger findTrigger(WorkflowPlan plan, String triggerId) {
        return plan.getTriggers().stream()
            .filter(t -> t.getNormalizedKey().equals(triggerId) || t.id().equals(triggerId))
            .findFirst()
            .orElse(null);
    }

    /**
     * Find a step by ID.
     */
    private Step findStep(WorkflowPlan plan, String stepId) {
        return plan.getMcps().stream()
            .filter(s -> s.getNormalizedKey().equals(stepId) || s.id().equals(stepId))
            .findFirst()
            .orElse(null);
    }

    /**
     * Find a control node by its normalized key (e.g., "core:check_status").
     */
    private Core findCore(WorkflowPlan plan, String nodeId) {
        return plan.getCores().stream()
            .filter(c -> c.getNormalizedKey().equals(nodeId))
            .findFirst()
            .orElse(null);
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // VALIDATION HELPERS
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Check if a reference (e.g., "{{mcp:fetch.output.response}}") is valid from a given node.
     *
     * @param plan WorkflowPlan
     * @param currentNodeId Current node ID
     * @param reference Reference string (e.g., "mcp:fetch.output.response")
     * @return true if valid
     */
    public boolean isReferenceValid(WorkflowPlan plan, String currentNodeId, String reference) {
        // Extract node ID from reference (e.g., "mcp:fetch.output.response" -> "mcp:fetch")
        String referencedNodeId = extractNodeIdFromReference(reference);
        if (referencedNodeId == null) {
            return false;
        }

        // Check if referenced node is accessible
        Set<String> accessible = getAccessibleNodes(plan, currentNodeId);
        return accessible.contains(referencedNodeId);
    }

    /**
     * Extract node ID from a reference string.
     *
     * Examples:
     * - "{{mcp:fetch.output.response}}" -> "mcp:fetch"
     * - "mcp:fetch.output.response" -> "mcp:fetch"
     * - "{{trigger:webhook.body}}" -> "trigger:webhook"
     *
     * @param reference Reference string
     * @return Node ID or null
     */
    private String extractNodeIdFromReference(String reference) {
        if (reference == null) {
            return null;
        }

        // Remove {{}} if present
        String cleaned = reference.replace("{{", "").replace("}}", "").trim();

        // Extract node ID (before first dot)
        int dotIndex = cleaned.indexOf('.');
        if (dotIndex > 0) {
            return cleaned.substring(0, dotIndex);
        }

        // No dot found - might be just the node ID
        if (cleaned.contains(":")) {
            return cleaned;
        }

        return null;
    }
}
