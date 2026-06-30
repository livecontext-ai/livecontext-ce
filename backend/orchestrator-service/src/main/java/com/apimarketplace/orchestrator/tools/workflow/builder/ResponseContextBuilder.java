package com.apimarketplace.orchestrator.tools.workflow.builder;

import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds context information for workflow builder responses.
 * Handles variable accessibility, node reachability, and condition validation.
 *
 * Extracted from ResponseOptimizer for Single Responsibility Principle.
 */
@Component
public class ResponseContextBuilder {

    // Mirrors TemplateEngine.EXPRESSION_PATTERN - handles SpEL string literals containing `}`.
    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\{\\{((?:'(?:[^'\\\\]|\\\\.)*'|[^}|])+?)(?:\\|[^}]*)?}}");

    /**
     * Get all accessible nodes (predecessors) from a given node.
     * Uses simplified graph traversal without full WorkflowPlan conversion.
     *
     * @param session Workflow builder session
     * @param nodeId Current node ID
     * @return Set of accessible node IDs (predecessors)
     */
    public Set<String> getAccessibleNodes(WorkflowBuilderSession session, String nodeId) {
        Set<String> accessible = new HashSet<>();
        Queue<String> queue = new LinkedList<>();
        queue.add(nodeId);

        while (!queue.isEmpty()) {
            String current = queue.poll();

            // Find all predecessors (edges pointing TO current)
            for (Map<String, Object> edge : session.getEdges()) {
                String from = (String) edge.get("from");
                String to = (String) edge.get("to");

                if (to != null && to.equals(current) && from != null && !accessible.contains(from)) {
                    accessible.add(from);
                    queue.add(from);
                }
            }
        }

        return accessible;
    }

    /**
     * Get all accessible variables from predecessors.
     * Includes: trigger columns, step outputs, agent outputs, loop variables.
     *
     * @param session Workflow builder session
     * @param nodeId Current node ID
     * @return Map of node ID to available variables
     */
    public Map<String, Object> getAccessibleVariables(WorkflowBuilderSession session, String nodeId) {
        Map<String, Object> variables = new LinkedHashMap<>();
        Set<String> accessible = getAccessibleNodes(session, nodeId);

        // Add trigger variables
        addTriggerVariables(session, accessible, variables);

        // Add step/agent variables
        addStepVariables(session, accessible, variables);

        // Add loop/split variables
        addControlNodeVariables(session, accessible, variables);

        return variables;
    }

    private void addTriggerVariables(WorkflowBuilderSession session, Set<String> accessible,
                                     Map<String, Object> variables) {
        for (Map<String, Object> trigger : session.getTriggers()) {
            String label = (String) trigger.get("label");
            String normalizedLabel = WorkflowBuilderSession.normalizeLabel(label);
            String triggerId = "trigger:" + normalizedLabel;
            String triggerType = (String) trigger.get("type");

            if (accessible.contains(triggerId) || accessible.isEmpty()) {
                Map<String, String> triggerVars = new LinkedHashMap<>();

                // Get outputs from schema (columns)
                // For datasource triggers, the schema is built by TriggerCreator.buildTriggerSchema
                // with safe nested paths (output.row.<col>) AND event_meta keys + batch-scan
                // (data, count). We pass the schema's referenceSyntax through unchanged so the
                // agent never gets a colliding top-level reference like {{...output.status}}.
                var schema = session.getNodeSchemas().get(triggerId);
                if (schema != null && schema.getOutputs() != null) {
                    schema.getOutputs().forEach((k, v) ->
                        triggerVars.put(k, "{{trigger:" + normalizedLabel + ".output." + k + "}}"));
                }

                // Add trigger-level variables (not in .output, these are metadata)
                if ("datasource".equals(triggerType)) {
                    triggerVars.put("_itemIndex", "{{trigger:" + normalizedLabel + ".itemIndex}}");
                    triggerVars.put("_totalItems", "{{trigger:" + normalizedLabel + ".totalItems}}");
                    triggerVars.put("_iteration_hint",
                        "Event-driven (production): one fire = one row, read columns via .output.row.<col>. " +
                        "Batch-scan (workflow execute test): read .output.data and chain core:split - same shape as find_rows.items.");
                }
                triggerVars.put("_triggeredAt", "{{trigger:" + normalizedLabel + ".triggeredAt}}");

                // Use original label as key for LLM readability
                variables.put(label, triggerVars);
            }
        }
    }

    private void addStepVariables(WorkflowBuilderSession session, Set<String> accessible,
                                  Map<String, Object> variables) {
        for (Map<String, Object> step : session.getMcps()) {
            String label = (String) step.get("label");
            String normalizedLabel = WorkflowBuilderSession.normalizeLabel(label);
            boolean isAgent = Boolean.TRUE.equals(step.get("isAgent"));
            String stepId = (isAgent ? "agent:" : "mcp:") + normalizedLabel;

            if (accessible.contains(stepId)) {
                var schema = session.getNodeSchemas().get(stepId);
                if (schema != null && schema.getReferenceSyntax() != null) {
                    // Use original label as key for LLM readability
                    variables.put(label, schema.getReferenceSyntax());
                } else {
                    String prefix = isAgent ? "agent" : "mcp";
                    // Use original label as key for LLM readability
                    variables.put(label, Map.of(
                        "output", "{{" + prefix + ":" + normalizedLabel + ".output}}",
                        "response", "{{" + prefix + ":" + normalizedLabel + ".response}}"
                    ));
                }
            }
        }
    }

    private void addControlNodeVariables(WorkflowBuilderSession session, Set<String> accessible,
                                         Map<String, Object> variables) {
        for (Map<String, Object> coreNode : session.getCores()) {
            String type = (String) coreNode.get("type");
            String label = (String) coreNode.get("label");
            String normalizedLabel = WorkflowBuilderSession.normalizeLabel(label);
            String coreId = "core:" + normalizedLabel;

            if (!accessible.contains(coreId)) continue;

            // Use original label as key for LLM readability
            if ("loop".equals(type)) {
                variables.put(label, Map.of(
                    "iteration", "{{core:" + normalizedLabel + ".output.iteration}}"
                ));
            } else if ("split".equals(type)) {
                Map<String, String> splitVars = new java.util.LinkedHashMap<>();
                // Layer 2: Runtime context (body nodes only)
                splitVars.put("current_item", "{{core:" + normalizedLabel + ".output.current_item}} (RUNTIME - per-branch item)");
                splitVars.put("current_item.field", "{{core:" + normalizedLabel + ".output.current_item.field}} (RUNTIME - access item field)");
                splitVars.put("current_index", "{{core:" + normalizedLabel + ".output.current_index}} (RUNTIME - 0-based index)");
                // Layer 1: Persisted outputs (accessible everywhere)
                splitVars.put("items", "{{core:" + normalizedLabel + ".output.items}} (PERSISTED - full list)");
                splitVars.put("item_count", "{{core:" + normalizedLabel + ".output.item_count}} (PERSISTED - total count)");
                variables.put(label, splitVars);
            }
        }
    }

    /**
     * Validate SpEL references in conditions against accessible variables.
     *
     * @param conditions List of condition maps
     * @param accessibleVars Map of accessible variables
     * @param session Workflow builder session
     * @return List of warnings for unknown references
     */
    public List<String> validateConditionReferences(
            List<Map<String, Object>> conditions,
            Map<String, Object> accessibleVars,
            WorkflowBuilderSession session) {

        List<String> warnings = new ArrayList<>();
        Set<String> knownPatterns = buildKnownPatterns(accessibleVars);

        for (Map<String, Object> condition : conditions) {
            String expression = (String) condition.get("condition");
            if (expression == null) continue;

            Matcher matcher = VARIABLE_PATTERN.matcher(expression);
            while (matcher.find()) {
                String reference = matcher.group(1);
                if (!isKnownReference(reference, knownPatterns, session)) {
                    warnings.add("Unknown reference: {{" + reference + "}}");
                }
            }
        }

        return warnings;
    }

    private Set<String> buildKnownPatterns(Map<String, Object> accessibleVars) {
        Set<String> patterns = new HashSet<>();
        for (var entry : accessibleVars.entrySet()) {
            if (entry.getValue() instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, String> vars = (Map<String, String>) entry.getValue();
                for (String varRef : vars.values()) {
                    Matcher m = VARIABLE_PATTERN.matcher(varRef);
                    if (m.find()) {
                        patterns.add(m.group(1));
                    }
                }
            }
        }
        return patterns;
    }

    private boolean isKnownReference(String reference, Set<String> knownPatterns,
                                     WorkflowBuilderSession session) {
        // Direct match
        if (knownPatterns.contains(reference)) return true;

        // Check if it's a node output pattern (node:label.field)
        String[] parts = reference.split("\\.", 2);
        if (parts.length >= 1) {
            String nodeRef = parts[0];
            // Check if node exists
            if (session.nodeExists(nodeRef)) return true;
            // Check resolved reference
            String resolved = session.resolveNodeReference(nodeRef);
            if (session.nodeExists(resolved)) return true;
        }

        return false;
    }
}
