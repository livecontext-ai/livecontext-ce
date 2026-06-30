package com.apimarketplace.orchestrator.tools.workflow.builder.response;

import com.apimarketplace.orchestrator.tools.workflow.builder.ResponseContextBuilder;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Builds optimized responses for control flow nodes (decision, loop, split).
 * Handles branching and iteration node responses with variable validation.
 *
 * @see com.apimarketplace.orchestrator.tools.workflow.builder.ResponseOptimizer
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ControlNodeResponseBuilder {

    private final ResponseContextBuilder contextBuilder;

    /**
     * Build response for decision node.
     */
    public Map<String, Object> buildDecisionResponse(
            WorkflowBuilderSession session,
            String nodeId,
            String label,
            List<Map<String, Object>> conditions
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        // Standard envelope (consistent across all node types)
        result.put("status", "OK");
        result.put("node_type", "decision");
        result.put("node_id", nodeId);
        result.put("logical_id", session.getLogicalId(nodeId));
        result.put("message", "Decision '" + label + "' added with " + conditions.size() + " branches.");

        // JIT: Variables available for conditions
        Map<String, Object> accessibleVars = contextBuilder.getAccessibleVariables(session, nodeId);
        if (!accessibleVars.isEmpty()) {
            result.put("available_variables", accessibleVars);
            result.put("condition_syntax", "Use {{expression}} in conditions, e.g., {{mcp:name.output.status == 'success'}}");
        }

        // Validate condition references and warn for unknown variables
        List<String> conditionWarnings = contextBuilder.validateConditionReferences(conditions, accessibleVars, session);
        if (!conditionWarnings.isEmpty()) {
            result.put("condition_warnings", conditionWarnings);
        }

        String logicalId = session.getLogicalIdOrFail(nodeId);
        String baseNum = logicalId.replace("#", "");

        // Build branch list with clear IDs and actual port names
        // Tolerant: accept multiple key names since LLMs may use different formats
        List<Map<String, Object>> branches = new ArrayList<>();
        List<String> availablePorts = new ArrayList<>();
        int elseifIdx = 0;
        for (int i = 0; i < conditions.size(); i++) {
            Map<String, Object> cond = conditions.get(i);
            String condLabel = (String) cond.get("label");
            if (condLabel == null) condLabel = (String) cond.get("name");
            if (condLabel == null) condLabel = (String) cond.get("condition");
            if (condLabel == null) condLabel = (String) cond.get("expression");
            if (condLabel == null) condLabel = "Branch " + (i + 1);

            // Compute the actual port name for this branch
            String condType = (String) cond.get("type");
            String port;
            if (i == 0 || "if".equals(condType)) {
                port = "if";
            } else if ("else".equals(condType) || "default".equalsIgnoreCase((String) cond.get("condition"))
                       || "default".equalsIgnoreCase((String) cond.get("expression"))) {
                port = "else";
            } else {
                port = "elseif_" + elseifIdx;
                elseifIdx++;
            }

            String branchId = "#" + baseNum + (char)('a' + i);
            Map<String, Object> branch = new LinkedHashMap<>();
            branch.put("branch_id", branchId);
            branch.put("label", condLabel);
            branch.put("port", port);
            branch.put("connect_after", label + ":" + port);
            branch.put("status", "EMPTY - needs step");
            branches.add(branch);
            availablePorts.add(port);
        }
        result.put("branches", branches);

        // Next step guidance - connect to each branch with actual ports
        List<String> examples = availablePorts.stream()
            .map(p -> label + ":" + p)
            .toList();
        Map<String, Object> next = new LinkedHashMap<>();
        next.put("per_branch", "workflow(action='add_node', type='...', label='...', params={...}, connect_after='" + label + ":<port>')");
        next.put("available_ports", availablePorts);
        next.put("examples", examples);
        next.put("get_params", "workflow(action='help', topics=['agent', 'mcp', ...]) for required params");
        result.put("NEXT", next);

        return result;
    }

    /**
     * Build response for loop node.
     */
    public Map<String, Object> buildLoopResponse(
            WorkflowBuilderSession session,
            String nodeId,
            String label,
            String loopCondition
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        // Standard envelope (consistent across all node types)
        result.put("status", "OK");
        result.put("node_type", "loop");
        result.put("node_id", nodeId);
        result.put("logical_id", session.getLogicalId(nodeId));
        result.put("message", "Loop '" + label + "' added.");
        result.put("condition", loopCondition);

        // CRITICAL: Connection guidance
        result.put("CONNECTION", Map.of(
            "INSIDE_loop", "Use connect_after='" + label + "' to add steps IN loop (every iteration)",
            "OUTSIDE_loop", "Use connect_after_loop='" + label + "' to add steps AFTER loop (one time)"
        ));

        // JIT: Variables available for loop condition + body
        Map<String, Object> accessibleVars = contextBuilder.getAccessibleVariables(session, nodeId);
        if (!accessibleVars.isEmpty()) {
            result.put("available_variables", accessibleVars);
        }

        // Validate loop condition references (wrap in a list for reuse of validateConditionReferences)
        if (loopCondition != null && !loopCondition.equals("true") && !loopCondition.isBlank()) {
            List<Map<String, Object>> conditionAsList = List.of(Map.of("condition", loopCondition, "label", "loop condition"));
            List<String> conditionWarnings = contextBuilder.validateConditionReferences(conditionAsList, accessibleVars, session);
            if (!conditionWarnings.isEmpty()) {
                result.put("condition_warnings", conditionWarnings);
            }
        }

        // Available variable
        String normalizedLabel = WorkflowBuilderSession.normalizeLabel(label);
        result.put("loop_variable", "{{core:" + normalizedLabel + ".output.iteration}} - iteration counter (1, 2, 3, ...)");

        // Next step guidance
        result.put("NEXT", Map.of(
            "inside_loop", "workflow(action='add_node', type='...', label='...', params={...}, connect_after='" + label + "')",
            "after_loop", "workflow(action='add_node', type='...', label='...', params={...}, connect_after_loop='" + label + "')",
            "get_params", "workflow(action='help', topics=['agent', 'mcp', ...]) for required params"
        ));

        return result;
    }

    /**
     * Build response for split node.
     */
    public Map<String, Object> buildSplitResponse(
            WorkflowBuilderSession session,
            String nodeId,
            String label,
            String list,
            int maxItems
    ) {
        Map<String, Object> result = new LinkedHashMap<>();

        // === RÉSULTAT ===
        result.put("status", "OK");
        result.put("node_type", "split");
        result.put("node_id", nodeId);
        result.put("logical_id", session.getLogicalId(nodeId));
        result.put("message", "Split '" + label + "' added.");
        result.put("list", list);
        result.put("maxItems", maxItems);

        // Execution mode
        result.put("execution", "PARALLEL - all items processed simultaneously (max " + maxItems + ")");

        // Variables (contextual)
        String normalizedLabel = WorkflowBuilderSession.normalizeLabel(label);
        Map<String, String> exitVars = new LinkedHashMap<>();
        exitVars.put("current_item", "{{core:" + normalizedLabel + ".output.current_item}} (runtime, in body nodes)");
        exitVars.put("current_index", "{{core:" + normalizedLabel + ".output.current_index}} (runtime, in body nodes)");
        exitVars.put("items", "{{core:" + normalizedLabel + ".output.items}}");
        exitVars.put("item_count", "{{core:" + normalizedLabel + ".output.item_count}}");
        exitVars.put("split_id", "{{core:" + normalizedLabel + ".output.split_id}}");
        exitVars.put("spawn_reason", "{{core:" + normalizedLabel + ".output.spawn_reason}}");
        exitVars.put("terminated", "{{core:" + normalizedLabel + ".output.terminated}}");
        result.put("exit_variables", exitVars);

        // JIT: Variables available before split
        Map<String, Object> accessibleVars = contextBuilder.getAccessibleVariables(session, nodeId);
        if (!accessibleVars.isEmpty()) {
            result.put("available_variables", accessibleVars);
        }

        // Next step guidance - body nodes use current_item/current_index for per-item access
        Map<String, String> nextGuidance = new LinkedHashMap<>();
        nextGuidance.put("after_split", "workflow(action='add_node', type='...', label='...', params={data: '{{core:" + normalizedLabel + ".output.current_item}}'}, connect_after='" + label + "')");
        nextGuidance.put("current_item_syntax", "{{core:" + normalizedLabel + ".output.current_item}} (the item for this parallel branch)");
        nextGuidance.put("current_item_field", "{{core:" + normalizedLabel + ".output.current_item.fieldName}} (access a field)");
        nextGuidance.put("index_syntax", "{{core:" + normalizedLabel + ".output.current_index}} (0-based index)");
        nextGuidance.put("items_syntax", "{{core:" + normalizedLabel + ".output.items}} (full list, for reference)");
        nextGuidance.put("get_params", "workflow(action='help', topics=['agent', 'mcp', ...]) for required params");
        result.put("NEXT", nextGuidance);

        return result;
    }
}
