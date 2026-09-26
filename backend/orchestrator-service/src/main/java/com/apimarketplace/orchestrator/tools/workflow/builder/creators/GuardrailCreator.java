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
 * - rules: object {ruleId: description} (judged by the model) OR array of typed rules
 *   [{type, action, config}] (keyword_filter, regex_pattern, length_check, pii_detection,
 *   custom and competitor_mention are checked without a model)
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

    private static final String TYPED_EXAMPLE = "rules: [{type: 'keyword_filter', action: 'block', "
        + "config: {keywordsExpression: 'refund, chargeback', mode: 'block'}}, "
        + "{type: 'pii_detection', action: 'sanitize', config: {piiTypes: ['email', 'phone']}}]";

    static final List<String> RULE_TYPES = List.of("pii_detection", "toxic_language", "prompt_injection",
        "keyword_filter", "regex_pattern", "length_check", "topic_restriction", "competitor_mention", "custom");
    static final List<String> RULE_ACTIONS = List.of("block", "flag", "sanitize");
    static final List<String> PII_TYPES = List.of("email", "phone", "ssn", "credit_card", "address");

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
        // 'text' completes the set NodeParamsValidator.PARAM_ALIASES accepts for guardrail's
        // input - it validated, then failed here with "'input' is required".
        String content = firstNonBlank(parameters, "input", "content", "text");
        if (content == null || content.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "'input' is required - the text to validate. MUST use {{type:label.output.field}} to reference data. Example: " + EXAMPLE);
        }

        // 4. Validate rules: object {ruleId: description}, or an array of typed rules
        Object rules;
        List<String> ruleIds;
        if (parameters.get("rules") instanceof List<?> list) {
            List<Map<String, Object>> typed = new ArrayList<>();
            String typedError = parseTypedRules(list, typed);
            if (typedError != null) {
                return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, typedError + " Example: " + TYPED_EXAMPLE);
            }
            rules = typed;
            ruleIds = typed.stream().map(r -> String.valueOf(r.get("id"))).toList();
        } else {
            Map<String, String> described = parseRules(parameters.get("rules"));
            if (described == null || described.isEmpty()) {
                return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED,
                    "'rules' is required: either an object {ruleId: 'description'} (judged by the model) or an "
                        + "array of typed rules. Example: " + EXAMPLE + " or " + TYPED_EXAMPLE);
            }
            rules = described;
            ruleIds = new ArrayList<>(described.keySet());
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

        // Optional parameters - same alias set the validator accepts for the prompt.
        String prompt = firstNonBlank(parameters, "prompt", "system_prompt", "instruction");
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
     * Validates and normalises the typed rule array. Returns an agent-facing error, or null when
     * every rule is valid (then {@code out} holds the normalised rules).
     */
    @SuppressWarnings("unchecked")
    static String parseTypedRules(List<?> list, List<Map<String, Object>> out) {
        if (list.isEmpty()) {
            return "'rules' is an empty array; give at least one rule.";
        }
        Set<String> ids = new HashSet<>();
        int index = 0;
        for (Object item : list) {
            index++;
            if (!(item instanceof Map<?, ?> raw)) {
                return "rules[" + (index - 1) + "] must be an object {type, action, config}.";
            }
            Map<String, Object> rule = new LinkedHashMap<>((Map<String, Object>) raw);
            String type = rule.get("type") != null ? String.valueOf(rule.get("type")) : null;
            if (type == null || !RULE_TYPES.contains(type)) {
                return "rules[" + (index - 1) + "].type must be one of " + RULE_TYPES + ", got '" + type + "'.";
            }
            String action = rule.get("action") != null ? String.valueOf(rule.get("action")).toLowerCase() : "block";
            if (!RULE_ACTIONS.contains(action)) {
                return "rules[" + (index - 1) + "].action must be one of " + RULE_ACTIONS + ", got '" + action + "'.";
            }
            rule.put("action", action);
            Map<String, Object> config = rule.get("config") instanceof Map<?, ?> c
                ? new LinkedHashMap<>((Map<String, Object>) c) : new LinkedHashMap<>();
            String configError = validateConfig(type, config, index - 1);
            if (configError != null) {
                return configError;
            }
            rule.put("config", config);
            String id = rule.get("id") != null && !String.valueOf(rule.get("id")).isBlank()
                ? String.valueOf(rule.get("id")) : type + "_" + index;
            if (!ids.add(id)) {
                return "rules[" + (index - 1) + "].id '" + id + "' is used twice; rule ids must be unique.";
            }
            rule.put("id", id);
            out.add(rule);
        }
        return null;
    }

    private static String validateConfig(String type, Map<String, Object> config, int i) {
        String at = "rules[" + i + "].config";
        switch (type) {
            case "keyword_filter" -> {
                if (blank(config.get("keywordsExpression"))) {
                    return at + ".keywordsExpression is required for keyword_filter: a comma-separated list, a list, or a {{...}} reference.";
                }
                Object mode = config.get("mode");
                if (mode != null && !List.of("block", "allow").contains(String.valueOf(mode))) {
                    return at + ".mode must be 'block' (fail when a keyword appears) or 'allow' (fail when none appears).";
                }
            }
            case "regex_pattern" -> {
                Object pattern = config.get("pattern");
                if (blank(pattern)) {
                    return at + ".pattern is required for regex_pattern.";
                }
                String text = String.valueOf(pattern);
                if (!text.contains("{{")) {
                    try {
                        java.util.regex.Pattern.compile(text);
                    } catch (java.util.regex.PatternSyntaxException e) {
                        return at + ".pattern does not compile: " + e.getDescription() + ".";
                    }
                }
                Object mode = config.get("mode");
                if (mode != null && !List.of("require", "block").contains(String.valueOf(mode))) {
                    return at + ".mode must be 'require' (the content must match) or 'block' (the content must not contain a match).";
                }
            }
            case "length_check" -> {
                if (blank(config.get("minLength")) && blank(config.get("maxLength"))) {
                    return at + " needs minLength, maxLength or both (in characters).";
                }
            }
            case "custom" -> {
                if (blank(config.get("expression"))) {
                    return at + ".expression is required for custom: a boolean expression where #input is the content, true = valid (e.g. #length(#input) > 10).";
                }
            }
            case "topic_restriction", "competitor_mention" -> {
                if (blank(config.get("topicsExpression"))) {
                    return at + ".topicsExpression is required for " + type + ": a comma-separated list, a list, or a {{...}} reference.";
                }
            }
            case "pii_detection" -> {
                if (config.get("piiTypes") instanceof List<?> piiTypes) {
                    for (Object pii : piiTypes) {
                        if (!PII_TYPES.contains(String.valueOf(pii))) {
                            return at + ".piiTypes entries must be among " + PII_TYPES + ", got '" + pii + "'.";
                        }
                    }
                } else if (config.get("piiTypes") != null) {
                    return at + ".piiTypes must be a list among " + PII_TYPES + ".";
                }
            }
            default -> {
                // toxic_language, prompt_injection: judged by the model, no config needed.
            }
        }
        return null;
    }

    private static boolean blank(Object value) {
        if (value == null) return true;
        if (value instanceof List<?> l) return l.isEmpty();
        return String.valueOf(value).isBlank();
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
