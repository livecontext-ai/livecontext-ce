package com.apimarketplace.orchestrator.tools.workflow.builder.response;

import com.apimarketplace.orchestrator.tools.workflow.builder.ResponseContextBuilder;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Builds optimized responses for agent, guardrail, and classify nodes.
 * Handles LLM-powered nodes with variable validation and warnings.
 *
 * @see com.apimarketplace.orchestrator.tools.workflow.builder.ResponseOptimizer
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentResponseBuilder {

    private final ResponseContextBuilder contextBuilder;

    /**
     * Build response for agent with full business logic.
     * Token-optimized version of WorkflowBuilderCreator.buildAgentResponse().
     */
    public Map<String, Object> buildAgentResponse(
            WorkflowBuilderSession session,
            String nodeId,
            String label,
            String connectAfter,
            String parentLoopId,
            Map<String, Object> agentNode,
            Map<String, String> refs,
            List<String> missingToolInputs,
            Map<String, String> suggestedInputs,
            List<String> availableColumns,
            boolean hasAnyVariables
    ) {
        Map<String, Object> result = new LinkedHashMap<>();

        // Standard envelope (consistent across all node types)
        result.put("status", "OK");
        result.put("node_type", "agent");
        result.put("node_id", nodeId);
        result.put("logical_id", session.getLogicalId(nodeId));
        boolean autoConnected = connectAfter != null && !connectAfter.isBlank();
        result.put("message", "Agent '" + label + "' added" + (autoConnected ? "" : " (ORPHANED - use connect_after='<label>' next time!)"));

        // JIT: All accessible variables from predecessors
        Map<String, Object> accessibleVars = contextBuilder.getAccessibleVariables(session, nodeId);
        if (!accessibleVars.isEmpty()) {
            result.put("available_variables", accessibleVars);
            result.put("input_syntax", "Use {{...}} in agent prompt or input mapping");
        }

        result.put("outputs", refs);

        // Missing tool inputs (JIT - only when there are missing inputs)
        if (missingToolInputs != null && !missingToolInputs.isEmpty()) {
            result.put("missing_inputs", missingToolInputs);
            if (suggestedInputs != null) {
                result.put("suggested", suggestedInputs);
            }
        }

        // Connection info (JIT - ALWAYS clarify connection behavior)
        String logicalId = session.getLogicalId(nodeId);
        Map<String, Object> connectionInfo = new LinkedHashMap<>();

        if (connectAfter != null && !connectAfter.isBlank()) {
            // Connected via explicit connect_after parameter
            String fromLogical = session.getLogicalId(connectAfter);
            connectionInfo.put("status", "CONNECTED");
            connectionInfo.put("from", fromLogical != null ? fromLogical : connectAfter);
            connectionInfo.put("how", "Explicit connect_after parameter");
            connectionInfo.put("for_fork", "To add parallel branches: workflow(action='connect', from='" + (fromLogical != null ? fromLogical : connectAfter) + "', to='...')");
            connectionInfo.put("for_merge", "To converge branches: workflow(action='connect', from='" + logicalId + "', to='mcp:target')");
        } else {
            // Not connected - ORPHANED (connect_after not specified)
            connectionInfo.put("status", "ORPHANED - NOT CONNECTED");
            connectionInfo.put("how", "No connect_after specified (MANDATORY for all nodes except first trigger)");
            connectionInfo.put("to_connect", "Use workflow(action='connect', from='<source label>', to=" + logicalId + ") to link this agent");
            // Generic reminder about connect_after
            connectionInfo.put("NEXT_TIME", "Always use connect_after='<node label>' when adding nodes");
        }

        if (!connectionInfo.isEmpty()) {
            result.put("connection", connectionInfo);
        }

        // JIT: Loop context (if inside loop)
        if (parentLoopId != null && !parentLoopId.isBlank()) {
            result.put("🔁_LOOP_CONTEXT", Map.of(
                "status", "Inside loop body - executes on EVERY iteration",
                "close_loop", "When body is complete, close it: workflow(action='connect', from='" + label + "', to='" + parentLoopId + ":iterate')",
                "to_exit", "To add steps AFTER loop (one-time): use connect_after_loop='" + parentLoopId + "'"
            ));
        }

        // Next step guidance with generic example
        String normalizedLabel = WorkflowBuilderSession.normalizeLabel(label);
        result.put("NEXT", Map.of(
            "pattern", "workflow(action='add_node', type='...', label='...', params={...}, connect_after='" + label + "')",
            "this_agent_output", "{{agent:" + normalizedLabel + ".output.response}}",
            "get_params", "workflow(action='help', topics=['mcp', 'decision', 'insert_row', ...]) for required params"
        ));

        return result;
    }

    /**
     * Build response for guardrail node.
     */
    public Map<String, Object> buildGuardrailResponse(
            WorkflowBuilderSession session,
            String nodeId,
            String label,
            String connectAfter,
            String parentLoopId,
            List<String> rules,
            Map<String, String> refs,
            boolean hasVariables
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        String logicalId = session.getLogicalId(nodeId);

        // Standard envelope
        result.put("status", "OK");
        result.put("node_type", "guardrail");
        result.put("node_id", nodeId);
        result.put("logical_id", logicalId);
        boolean autoConnected = connectAfter != null && !connectAfter.isBlank();
        result.put("message", "Guardrail '" + label + "' added" + (autoConnected ? "" : " (ORPHANED - use connect_after='<label>' next time!)"));

        // JIT: All accessible variables from predecessors
        Map<String, Object> accessibleVars = contextBuilder.getAccessibleVariables(session, nodeId);
        if (!accessibleVars.isEmpty()) {
            result.put("available_variables", accessibleVars);
        }

        // Rules and outputs
        result.put("rules", rules);
        result.put("outputs", refs);

        // GUARDRAIL PORTS - pass and fail branches (like classify categories)
        String normalizedLabel = WorkflowBuilderSession.normalizeLabel(label);
        List<Map<String, Object>> guardrailPorts = new ArrayList<>();
        guardrailPorts.add(Map.of(
            "port", "pass",
            "label", "Pass",
            "description", "Content passed all validation rules",
            "connect_syntax", "connect_after='" + nodeId + ":pass'"
        ));
        guardrailPorts.add(Map.of(
            "port", "fail",
            "label", "Fail",
            "description", "Content failed one or more validation rules",
            "connect_syntax", "connect_after='" + nodeId + ":fail'"
        ));
        result.put("guardrail_ports", guardrailPorts);
        result.put("routing_note", "GUARDRAIL routes to EXACTLY ONE port (pass or fail) based on validation result");

        // Connection info
        Map<String, Object> connectionInfo = new LinkedHashMap<>();
        if (connectAfter != null && !connectAfter.isBlank()) {
            String fromLogical = session.getLogicalId(connectAfter);
            connectionInfo.put("status", "CONNECTED");
            connectionInfo.put("from", fromLogical != null ? fromLogical : connectAfter);
        } else {
            connectionInfo.put("status", "ORPHANED - NOT CONNECTED");
            connectionInfo.put("to_connect", "Use workflow(action='connect', from='<source label>', to='" + logicalId + "')");
        }
        result.put("connection", connectionInfo);

        // Next step guidance - show how to connect to pass/fail ports
        result.put("NEXT", Map.of(
            "connect_to_ports", List.of(
                "workflow(action='add_node', type='...', label='Handle Pass', connect_after='" + nodeId + ":pass')",
                "workflow(action='add_node', type='...', label='Handle Fail', connect_after='" + nodeId + ":fail')"
            ),
            "pattern", "workflow(action='add_node', type='...', label='...', connect_after='" + nodeId + ":pass|fail')",
            "this_node_output", "{{agent:" + normalizedLabel + ".output.passed}}, {{agent:" + normalizedLabel + ".output.violations}}",
            "get_params", "workflow(action='help', topics=['decision', 'mcp', ...]) for required params"
        ));

        return result;
    }

    /**
     * Build response for classify node.
     * Categories include both label and description: [{label: "billing", description: "Payment issues"}, ...]
     */
    public Map<String, Object> buildClassifyResponse(
            WorkflowBuilderSession session,
            String nodeId,
            String label,
            String connectAfter,
            String parentLoopId,
            List<Map<String, String>> categories,
            Map<String, String> refs,
            boolean hasVariables
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        String logicalId = session.getLogicalId(nodeId);

        // Standard envelope
        result.put("status", "OK");
        result.put("node_type", "classify");
        result.put("node_id", nodeId);
        result.put("logical_id", logicalId);
        boolean autoConnected = connectAfter != null && !connectAfter.isBlank();
        result.put("message", "Classify '" + label + "' added" + (autoConnected ? "" : " (ORPHANED - use connect_after='<label>' next time!)"));

        // JIT: All accessible variables from predecessors
        Map<String, Object> accessibleVars = contextBuilder.getAccessibleVariables(session, nodeId);
        if (!accessibleVars.isEmpty()) {
            result.put("available_variables", accessibleVars);
        }

        // Categories with full info (label + description) and outputs
        result.put("categories", categories);
        result.put("branches_count", categories.size());
        result.put("outputs", refs);

        // CATEGORY PORTS - like Fork branches, each category creates an output port
        String normalizedLabel = WorkflowBuilderSession.normalizeLabel(label);
        List<Map<String, Object>> categoryPorts = new ArrayList<>();
        for (int i = 0; i < categories.size(); i++) {
            Map<String, String> cat = categories.get(i);
            categoryPorts.add(Map.of(
                "port", "category_" + i,
                "label", cat.get("label"),
                "description", cat.get("description") != null ? cat.get("description") : "",
                "connect_syntax", "connect_after='" + nodeId + ":category_" + i + "'"
            ));
        }
        result.put("category_ports", categoryPorts);
        result.put("routing_note", "CLASSIFY routes to EXACTLY ONE category per execution (like decision)");

        // Connection info
        Map<String, Object> connectionInfo = new LinkedHashMap<>();
        if (connectAfter != null && !connectAfter.isBlank()) {
            String fromLogical = session.getLogicalId(connectAfter);
            connectionInfo.put("status", "CONNECTED");
            connectionInfo.put("from", fromLogical != null ? fromLogical : connectAfter);
        } else {
            connectionInfo.put("status", "ORPHANED - NOT CONNECTED");
            connectionInfo.put("to_connect", "Use workflow(action='connect', from='<source label>', to='" + logicalId + "')");
        }
        result.put("connection", connectionInfo);

        // Next step guidance - show how to connect to each category
        List<String> connectExamples = new ArrayList<>();
        for (int i = 0; i < Math.min(categories.size(), 3); i++) {
            String catLabel = categories.get(i).get("label");
            connectExamples.add("workflow(action='add_node', type='...', label='Handle " + catLabel + "', connect_after='" + nodeId + ":category_" + i + "')");
        }

        result.put("NEXT", Map.of(
            "connect_to_categories", connectExamples,
            "pattern", "workflow(action='add_node', type='...', label='...', connect_after='" + nodeId + ":category_N')",
            "this_node_output", "{{agent:" + normalizedLabel + ".output.selected_category}}, {{agent:" + normalizedLabel + ".output.confidence}}",
            "note", "Each category_N port leads to a different execution path based on AI classification"
        ));

        return result;
    }
}
