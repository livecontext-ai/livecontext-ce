package com.apimarketplace.orchestrator.tools.workflow.builder.creators;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.orchestrator.tools.workflow.builder.ResponseOptimizer;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSession;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSessionStore;
import com.apimarketplace.orchestrator.utils.LabelNormalizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import com.apimarketplace.agent.tools.ToolErrorCode;

/**
 * Handles guardrail node creation for the workflow builder.
 * Guardrail nodes validate content against safety rules (PII, toxicity, keywords, etc.)
 *
 * Parameters (from guardrail.md):
 * - content: string (required) - Content to validate
 * - rules: object (required) - Key=ruleId, Value=description of what to check
 * - action: string (optional) - flag/block/redact (default: flag)
 * - prompt: string (optional) - Custom instruction for validation
 * - provider: string (optional, e.g. "openai", "anthropic", "google", "mistral", "deepseek")
 * - model: string (optional)
 * - temperature: number (optional, 0-1)
 *
 * Extracted from WorkflowBuilderCreator for SOLID compliance.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GuardrailCreator extends CreatorBase {

    private static final String EXAMPLE = "workflow(action='add_node', type='guardrail', label='Check PII', " +
        "params={input: '{{trigger:form.output.message}}', rules: {pii: 'Block emails and phones', toxicity: 'Block offensive content'}}, " +
        "connect_after='Contact Form')";

    private static final List<String> VALID_ACTIONS = List.of("flag", "block", "redact");

    private final WorkflowBuilderSessionStore sessionStore;
    private final ResponseOptimizer responseOptimizer;

    /**
     * Execute add_guardrail action.
     * Creates a guardrail node that validates content against specified rules.
     */
    public ToolExecutionResult executeAddGuardrail(WorkflowBuilderSession session, Map<String, Object> parameters) {
        // 1. Validate label
        String label = getLabel(parameters);
        var labelError = validateLabel(label, "guardrail");
        if (labelError != null) return labelError;

        // 2. MANDATORY: Trigger must exist
        if (session.getTriggers().isEmpty()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "TRIGGER REQUIRED FIRST. Create a trigger first: " +
                "workflow(action='add_node', type='form', label='...', params={...})");
        }

        // 3. Validate required fields: input/content, rules
        // Accept both 'input' (canonical per V221) and 'content' (legacy)
        String content = safeString(parameters.get("input"));
        if (content == null || content.isBlank()) {
            content = safeString(parameters.get("content"));
        }
        if (content == null || content.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "'input' is required - the text to validate. MUST use {{type:label.output.field}} to reference data. Example: " + EXAMPLE);
        }

        // 4. Validate rules: object with key=ruleId, value=description
        @SuppressWarnings("unchecked")
        Map<String, String> rules = parseRules(parameters.get("rules"));
        if (rules == null || rules.isEmpty()) {
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "'rules' is required as object: {ruleId: 'description'}. Example: " + EXAMPLE);
        }

        // 5. Validate optional action parameter
        String action = safeString(parameters.get("action"));
        if (action != null && !action.isBlank() && !VALID_ACTIONS.contains(action.toLowerCase())) {
            return ToolExecutionResult.failure(ToolErrorCode.INVALID_ENUM_VALUE, "'action' must be one of: flag, block, redact. Got: '" + action + "'. Example: " + EXAMPLE);
        }
        if (action == null || action.isBlank()) {
            action = "flag"; // default
        }

        // 6. Generate node ID and check uniqueness
        String normalizedLabel = WorkflowBuilderSession.normalizeLabel(label);
        String nodeId = "agent:" + normalizedLabel;
        var existsError = validateNodeNotExists(session, nodeId, label);
        if (existsError != null) return existsError;

        // 7. Resolve connect_after
        String connectAfter = resolveConnectAfter(parameters, session);
        var connectAfterError = validateConnectAfter(connectAfter, session);
        if (connectAfterError != null) return connectAfterError;

        // 8. Build guardrail node
        // Note: isAgent=true is required because guardrail uses "agent:" prefix
        // and SessionNodeFinder.findNode() filters mcps by isAgent for agent: keys
        Map<String, Object> guardrailNode = new LinkedHashMap<>();
        guardrailNode.put("id", UUID.randomUUID().toString());
        guardrailNode.put("type", "guardrail");
        guardrailNode.put("label", label);
        guardrailNode.put("isAgent", true);       // Required for agent: prefix lookup
        guardrailNode.put("isGuardrail", true);   // Marks this as a guardrail node
        guardrailNode.put("content", content);
        guardrailNode.put("rules", rules);
        guardrailNode.put("action", action.toLowerCase());
        guardrailNode.put("position", calculatePosition(session, NodeType.MCP));

        // Build guardrailOutputs for pass/fail ports (like classifyOutputs for classify)
        // This enables edges from agent:label:pass and agent:label:fail to target nodes
        List<Map<String, Object>> guardrailOutputs = new ArrayList<>();
        guardrailOutputs.add(Map.of(
            "id", nodeId + "-output-pass",
            "label", "Pass",
            "port", "pass"
        ));
        guardrailOutputs.add(Map.of(
            "id", nodeId + "-output-fail",
            "label", "Fail",
            "port", "fail"
        ));
        guardrailNode.put("guardrailOutputs", guardrailOutputs);

        // Optional parameters
        String prompt = safeString(parameters.get("prompt"));
        if (prompt != null && !prompt.isBlank()) {
            guardrailNode.put("prompt", prompt);
        }
        String provider = safeString(parameters.get("provider"));
        if (provider != null && !provider.isBlank()) {
            guardrailNode.put("provider", provider);
        }
        String model = safeString(parameters.get("model"));
        if (model != null && !model.isBlank()) {
            guardrailNode.put("model", model);
        }
        Object tempObj = parameters.get("temperature");
        if (tempObj instanceof Number temp) {
            guardrailNode.put("temperature", temp.doubleValue());
        }

        // 9. Parent loop handling removed (while-loop cleanup)
        String parentLoopId = null;

        // 10. Add to session (deep-normalize all variable references) and create edge
        session.getMcps().add(LabelNormalizer.normalizeVariableReferencesDeep(guardrailNode));
        createEdgeIfNeeded(session, connectAfter, nodeId);

        // 11. Store schema with correct output names
        Map<String, String> outputs = Map.of(
            "passed", "boolean",
            "violations", "array",
            "details", "object",
            "sanitized", "string"
        );
        Map<String, String> refs = Map.of(
            "passed", "{{agent:" + normalizedLabel + ".output.passed}}",
            "violations", "{{agent:" + normalizedLabel + ".output.violations}}",
            "details", "{{agent:" + normalizedLabel + ".output.details}}",
            "sanitized", "{{agent:" + normalizedLabel + ".output.sanitized}}"
        );
        session.getNodeSchemas().put(nodeId, WorkflowBuilderSession.NodeSchema.builder()
                .nodeId(nodeId)
                .nodeType("guardrail")
                .label(label)
                .outputs(outputs)
                .referenceSyntax(refs)
                .build());

        // 12. Finalize
        boolean isOrphaned = finalizeNode(session, sessionStore, NodeType.MCP, nodeId, guardrailNode, connectAfter);

        // 13. Check for data variables
        boolean hasVariables = content.contains("{{");

        // 14. Extract rule IDs for response
        List<String> ruleIds = new ArrayList<>(rules.keySet());

        // 15. Build response with warnings
        Map<String, Object> response = responseOptimizer.buildGuardrailResponse(session, nodeId, label,
            connectAfter, parentLoopId, ruleIds, refs, hasVariables);

        // Show saved params so LLM knows what was actually stored
        Map<String, Object> savedParams = new LinkedHashMap<>();
        savedParams.put("input", content);
        savedParams.put("rules", rules);
        savedParams.put("action", guardrailNode.get("action"));
        response.put("saved_params", savedParams);

        // 16. Progressive validation - check for orphan nodes
        int totalNodes = session.getTriggers().size() + session.getMcps().size() + session.getCores().size();
        if (totalNodes >= 3) {
            List<String> orphans = session.findOrphanNodes().stream()
                .filter(id -> !id.equals(nodeId))
                .toList();
            if (!orphans.isEmpty()) {
                Map<String, Object> validation = new LinkedHashMap<>();
                validation.put("other_orphan_nodes", orphans.stream()
                    .map(id -> Map.of("id", id, "logical_id", session.getLogicalId(id)))
                    .toList());
                validation.put("hint", "Other nodes are disconnected. Use workflow(action='connect', from='...', to='...')");
                response.put("progressive_validation", validation);
            }
        }

        return ToolExecutionResult.success(response);
    }

    /**
     * Parse rules from object format: {ruleId: "description", ...}
     */
    @SuppressWarnings("unchecked")
    private Map<String, String> parseRules(Object rulesObj) {
        if (rulesObj == null) return null;
        if (!(rulesObj instanceof Map<?, ?> map)) return null;
        if (map.isEmpty()) return null;

        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = safeString(entry.getKey());
            String value = safeString(entry.getValue());
            if (key != null && !key.isBlank()) {
                result.put(key, value != null ? value : "");
            }
        }
        return result.isEmpty() ? null : result;
    }
}
