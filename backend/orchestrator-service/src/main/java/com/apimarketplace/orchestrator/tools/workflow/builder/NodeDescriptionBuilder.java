package com.apimarketplace.orchestrator.tools.workflow.builder;

import com.apimarketplace.datasource.client.DataSourceClient;
import com.apimarketplace.datasource.client.dto.DataSourceDto;
import com.apimarketplace.interfaces.client.InterfaceClient;
import com.apimarketplace.interfaces.client.dto.InterfaceDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Builds detailed descriptions for workflow nodes.
 * Handles type-specific configuration extraction and modify examples generation.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NodeDescriptionBuilder {

    private final DataSourceClient dataSourceClient;
    private final InterfaceClient interfaceClient;

    /**
     * Result containing node config, modifiable fields (with values), and help topic.
     */
    public record DescriptionResult(
            Map<String, Object> config,
            Map<String, ModifiableField> modifiableFields,
            String helpTopic,
            String warning
    ) {
        public static DescriptionResult empty() {
            return new DescriptionResult(new LinkedHashMap<>(), new LinkedHashMap<>(), null, null);
        }
    }

    /**
     * Describes a modifiable field with its current value and the param key for modify.
     */
    public record ModifiableField(
            Object currentValue,
            String paramKey,  // Key to use in params={} for modify
            String description
    ) {}

    /**
     * Build description for a node based on its type.
     */
    public DescriptionResult buildDescription(String nodeId, Map<String, Object> node, String tenantId) {
        if (nodeId.startsWith("trigger:")) {
            return buildTriggerDescription(node);
        } else if (nodeId.startsWith("agent:")) {
            return buildAgentDescription(node);
        } else if (nodeId.startsWith("mcp:")) {
            return buildStepDescription(node, tenantId);
        } else if (nodeId.startsWith("core:")) {
            return buildCoreDescription(node);
        } else if (nodeId.startsWith("interface:")) {
            return buildInterfaceDescription(node, tenantId);
        }
        return DescriptionResult.empty();
    }

    /**
     * Build modify examples for a node based on its type.
     *
     * HARMONIZED: Uses same params={} syntax as ADD for consistency.
     * The node is identified by label (not #id).
     */
    public Map<String, String> buildModifyExamples(String nodeId, String label,
                                                      Map<String, Object> node, WorkflowBuilderSession session) {
        Map<String, String> examples = new LinkedHashMap<>();

        // Build contextual variable references from session
        String stepRef = findContextualStepRef(session, nodeId);
        String triggerRef = findContextualTriggerRef(session);

        if (nodeId.startsWith("core:") && isSplitNode(nodeId)) {
            examples.put("change_items",
                "workflow(action='modify', node='" + label + "', params={items: '{{" + stepRef + ".output.items}}'})");
            examples.put("limit_items",
                "workflow(action='modify', node='" + label + "', params={maxItems: 100})");
        } else if (nodeId.startsWith("core:") && isDecisionNode(nodeId)) {
            examples.put("change_conditions",
                "workflow(action='modify', node='" + label + "', params={conditions: [{condition: '{{...}} == \"ok\"', label: 'Success'}, {condition: 'default', label: 'Other'}]})");
        } else if (nodeId.startsWith("agent:")) {
            examples.put("change_prompt",
                "workflow(action='modify', node='" + label + "', params={prompt: 'New task: analyze {{" + stepRef + ".output}}'})");
            examples.put("change_model",
                "workflow(action='modify', node='" + label + "', params={model: 'gpt-4', temperature: 0.7})");
        } else if (nodeId.startsWith("mcp:")) {
            examples.put("change_params",
                "workflow(action='modify', node='" + label + "', params={to: '{{" + triggerRef + ".output.email}}', subject: 'Hello'})");
        } else if (nodeId.startsWith("trigger:")) {
            if (isWebhookTrigger(node)) {
                examples.put("change_http_method",
                    "workflow(action='modify', node='" + label + "', params={httpMethod: 'GET'})");
                examples.put("add_auth",
                    "workflow(action='modify', node='" + label + "', params={authType: 'header', authHeaderName: 'X-API-Key', authHeaderValue: 'secret'})");
                examples.put("set_input_schema",
                    "workflow(action='modify', node='" + label + "', params={inputSchema: {name: 'string', amount: 'number'}})");
            } else {
                examples.put("change_schedule",
                    "workflow(action='modify', node='" + label + "', params={schedule: {type: 'cron', expression: '0 9 * * *'}})");
            }
        } else if (nodeId.startsWith("interface:")) {
            examples.put("change_variable_mapping",
                "workflow(action='modify', node='" + label + "', params={variable_mapping: {'title': '{{" + stepRef + ".output.name}}'}})");
            // Build contextual action_mapping example using existing triggers
            String actionMappingExample = buildContextualActionMappingExample(session);
            examples.put("add_action_mapping",
                "workflow(action='modify', node='" + label + "', params={action_mapping: " + actionMappingExample + "})");
        }

        return examples;
    }

    /**
     * Build a contextual action_mapping example using actual triggers from the session.
     * Falls back to a generic placeholder if no triggers exist.
     */
    private String buildContextualActionMappingExample(WorkflowBuilderSession session) {
        if (session == null || session.getTriggers().isEmpty()) {
            return "{'#btn': 'trigger:<existing_trigger_label>:click'}";
        }

        // Pick the first trigger to build a real example
        Map<String, Object> firstTrigger = session.getTriggers().get(0);
        String triggerLabel = (String) firstTrigger.get("label");
        String triggerType = (String) firstTrigger.getOrDefault("type", "manual");

        if (triggerLabel == null) {
            return "{'#btn': 'trigger:<existing_trigger_label>:click'}";
        }

        String normalized = WorkflowBuilderSession.normalizeLabel(triggerLabel);

        // Map trigger type to appropriate action type and selector
        String actionType;
        String selector;
        switch (triggerType) {
            case "form" -> { actionType = "submit"; selector = "#form"; }
            case "chat" -> { actionType = "message"; selector = "#chat"; }
            default -> { actionType = "click"; selector = "#btn"; }
        }

        return "{'" + selector + "': 'trigger:" + normalized + ":" + actionType + "'}";
    }

    // ==================== Type-specific builders ====================

    private DescriptionResult buildTriggerDescription(Map<String, Object> node) {
        Map<String, Object> config = new LinkedHashMap<>();
        Map<String, ModifiableField> modifiableFields = new LinkedHashMap<>();

        String triggerType = (String) node.getOrDefault("type", "datasource");
        config.put("trigger_type", "datasource".equals(triggerType) ? "table" : triggerType);

        Object tableId = node.getOrDefault("table_id", node.get("datasource_id"));
        if (tableId != null) {
            config.put("table_id", tableId);
        }
        modifiableFields.put("table_id", new ModifiableField(tableId, "table_id", "Table ID for datasource trigger"));

        Object strategy = node.get("strategy");
        if (strategy != null) {
            config.put("strategy", strategy);
        }
        modifiableFields.put("strategy", new ModifiableField(strategy, "strategy", "Processing strategy (e.g., 'all', 'new')"));

        Object schedule = node.get("schedule");
        if (schedule != null) {
            config.put("schedule", schedule);
        }
        modifiableFields.put("schedule", new ModifiableField(schedule, "schedule", "Schedule config: {type, expression}"));

        // Webhook-specific config
        if ("webhook".equals(triggerType)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> params = (Map<String, Object>) node.get("params");
            if (params != null) {
                String httpMethod = (String) params.getOrDefault("httpMethod", "POST");
                config.put("httpMethod", httpMethod);
                modifiableFields.put("httpMethod", new ModifiableField(httpMethod, "httpMethod",
                        "HTTP method: GET, POST, PUT, DELETE, PATCH"));

                String authType = (String) params.getOrDefault("authType", "none");
                config.put("authType", authType);
                modifiableFields.put("authType", new ModifiableField(authType, "authType",
                        "Authentication: none, basic, header, jwt"));
            } else {
                config.put("httpMethod", "POST");
                config.put("authType", "none");
                modifiableFields.put("httpMethod", new ModifiableField("POST", "httpMethod",
                        "HTTP method: GET, POST, PUT, DELETE, PATCH"));
                modifiableFields.put("authType", new ModifiableField("none", "authType",
                        "Authentication: none, basic, header, jwt"));
            }

            // Show inputSchema if present
            Object inputSchema = node.get("inputSchema");
            if (inputSchema != null) {
                config.put("inputSchema", inputSchema);
            }
            modifiableFields.put("inputSchema", new ModifiableField(
                    node.get("inputSchema"), "inputSchema", "Expected JSON schema for webhook payload"));
        }

        return new DescriptionResult(config, modifiableFields, "trigger", null);
    }

    private DescriptionResult buildAgentDescription(Map<String, Object> node) {
        Map<String, Object> config = new LinkedHashMap<>();
        Map<String, ModifiableField> modifiableFields = new LinkedHashMap<>();

        // Core fields
        Object prompt = node.get("prompt");
        addIfPresent(config, node, "prompt");
        modifiableFields.put("prompt", new ModifiableField(prompt, "prompt", "Agent task/instruction with {{variables}}"));

        Object systemPrompt = node.get("systemPrompt");
        if (systemPrompt != null) config.put("systemPrompt", systemPrompt);
        modifiableFields.put("systemPrompt", new ModifiableField(systemPrompt, "systemPrompt", "System context for the agent"));

        // Model config
        Object model = node.get("model");
        Object provider = node.get("provider");
        addIfPresent(config, node, "model");
        addIfPresent(config, node, "provider");
        modifiableFields.put("model", new ModifiableField(model, "model", "LLM model (e.g., 'gpt-4', 'claude-3')"));
        modifiableFields.put("provider", new ModifiableField(provider, "provider", "LLM provider (e.g., 'openai', 'anthropic')"));

        // Parameters
        Object temperature = node.get("temperature");
        Object maxTokens = node.get("maxTokens");
        addIfPresent(config, node, "temperature");
        addIfPresent(config, node, "maxTokens");
        modifiableFields.put("temperature", new ModifiableField(temperature, "temperature", "Creativity (0.0-1.0)"));
        modifiableFields.put("maxTokens", new ModifiableField(maxTokens, "maxTokens", "Max response tokens"));

        // Tools and iterations
        Object tools = node.get("tools");
        Object maxIterations = node.get("maxIterations");
        addIfPresent(config, node, "tools");
        addIfPresent(config, node, "maxIterations");
        modifiableFields.put("tools", new ModifiableField(tools, "tools", "List of tool UUIDs the agent can use"));
        modifiableFields.put("maxIterations", new ModifiableField(maxIterations, "maxIterations", "Max tool call iterations"));

        // Input mapping
        Object input = node.get("input");
        addIfPresent(config, node, "input");
        modifiableFields.put("input", new ModifiableField(input, "input", "Input variable mappings"));

        return new DescriptionResult(config, modifiableFields, "agent", null);
    }

    private DescriptionResult buildStepDescription(Map<String, Object> node, String tenantId) {
        Map<String, Object> config = new LinkedHashMap<>();
        Map<String, ModifiableField> modifiableFields = new LinkedHashMap<>();
        String warning = null;

        // Tool ID
        Object toolId = node.get("id");
        addIfPresent(config, node, "id");
        modifiableFields.put("id", new ModifiableField(toolId, "id", "Tool UUID from catalog(action='search')"));

        // Tool parameters
        Object params = node.get("params");
        addIfPresent(config, node, "params");
        modifiableFields.put("params", new ModifiableField(params, "params", "Tool parameters with {{...}} variables"));

        // Expose dataSourceId for CRUD steps
        Object dataSourceIdObj = node.get("dataSourceId");
        if (dataSourceIdObj == null) {
            dataSourceIdObj = node.get("data_source_id");
        }
        if (dataSourceIdObj != null) {
            Long dsId = dataSourceIdObj instanceof Number
                ? ((Number) dataSourceIdObj).longValue()
                : Long.parseLong(dataSourceIdObj.toString());
            config.put("table_id", dsId);
            modifiableFields.put("dataSourceId", new ModifiableField(dsId, "dataSourceId", "Table ID for CRUD operations"));

            // Check if datasource still exists
            DataSourceDto ds = dataSourceClient.getDataSource(dsId, tenantId);
            if (ds != null) {
                config.put("table_name", ds.name());
                config.put("table_status", "ACTIVE");
            } else {
                config.put("table_status", "DELETED");
                String label = (String) node.get("label");
                warning = "The linked table (id=" + dsId + ") has been DELETED. " +
                    "Use workflow(action='modify', node='" + label + "', params={dataSourceId: <new_table_id>}) to fix.";
            }
        }

        return new DescriptionResult(config, modifiableFields, "mcp", warning);
    }

    @SuppressWarnings("unchecked")
    private DescriptionResult buildCoreDescription(Map<String, Object> node) {
        Map<String, Object> config = new LinkedHashMap<>();
        Map<String, ModifiableField> modifiableFields = new LinkedHashMap<>();
        String helpTopic = "decision";
        String type = (String) node.get("type");

        // Check node type by examining fields
        if (node.containsKey("decisionConditions")) {
            // Sanitize: strip dashed IDs, add computed port names
            List<Map<String, Object>> rawConditions = (List<Map<String, Object>>) node.get("decisionConditions");
            List<Map<String, Object>> sanitized = sanitizeBranchList(rawConditions, "decision");
            config.put("conditions", sanitized);
            modifiableFields.put("conditions", new ModifiableField(sanitized, "conditions",
                "Array of [{condition: '{{...}}', label: '...'}]. Use 'default' for else branch. " +
                "Ports: if, elseif_0, elseif_1, ..., else"));
            helpTopic = "decision";
        } else if (node.containsKey("switchCases")) {
            List<Map<String, Object>> rawCases = (List<Map<String, Object>>) node.get("switchCases");
            List<Map<String, Object>> sanitized = sanitizeBranchList(rawCases, "switch");
            addIfPresent(config, node, "switchExpression");
            config.put("cases", sanitized);
            modifiableFields.put("expression", new ModifiableField(node.get("switchExpression"), "expression",
                "Expression to match against case values: {{mcp:x.output.status}}"));
            modifiableFields.put("cases", new ModifiableField(sanitized, "cases",
                "Array of [{type: 'case', label: '...', value: '...'}, {type: 'default', label: '...'}]. Ports: case_0, case_1, ..., default"));
            helpTopic = "switch";
        } else if (node.containsKey("optionChoices")) {
            List<Map<String, Object>> rawChoices = (List<Map<String, Object>>) node.get("optionChoices");
            List<Map<String, Object>> sanitized = sanitizeBranchList(rawChoices, "option");
            config.put("choices", sanitized);
            modifiableFields.put("choices", new ModifiableField(sanitized, "choices",
                "Array of [{label: '...', expression: '...'}]. Ports: choice_0, choice_1, ..."));
            helpTopic = "option";
        } else if (node.containsKey("forkOutputs")) {
            List<Map<String, Object>> rawOutputs = (List<Map<String, Object>>) node.get("forkOutputs");
            List<Map<String, Object>> sanitized = sanitizeBranchList(rawOutputs, "fork");
            config.put("branches", sanitized);
            config.put("execution", "ALL branches execute in parallel");
            helpTopic = "fork";
        } else if ("loop".equals(type)) {
            addIfPresent(config, node, "condition");
            addIfPresent(config, node, "maxIterations");
            config.put("ports", List.of("body", "exit"));
            modifiableFields.put("condition", new ModifiableField(node.get("condition"), "condition",
                "Loop while condition is true: {{mcp:x.output.hasMore}}"));
            modifiableFields.put("maxIterations", new ModifiableField(node.get("maxIterations"), "maxIterations",
                "Max iterations to prevent infinite loops"));
            helpTopic = "loop";
        } else if ("approval".equals(type)) {
            addIfPresent(config, node, "message");
            Object approval = node.get("approval");
            if (approval != null) config.put("approval", approval);
            config.put("ports", List.of("approved", "rejected", "timeout"));
            helpTopic = "approval";
        } else if (node.containsKey("list") || node.containsKey("listExpression") || node.containsKey("splitStrategy")) {
            // Accept "list" (stored key), "items" (primary), "listExpression" (legacy)
            Object items = node.get("list");  // stored as "list" for backward compatibility
            if (items == null) items = node.get("listExpression");
            Object maxItems = node.get("maxItems");
            config.put("items", items);  // Show as "items" in config (primary name)
            addIfPresent(config, node, "maxItems");
            modifiableFields.put("items", new ModifiableField(items, "items",
                "Expression returning array to iterate: {{mcp:x.output.items}}"));
            modifiableFields.put("maxItems", new ModifiableField(maxItems, "maxItems",
                "Max items to process in parallel"));
            helpTopic = "split";
        } else if ("merge".equals(type)) {
            config.put("behavior", "AND mode - waits for ALL predecessors to complete");
            helpTopic = "merge";
        } else if ("transform".equals(type)) {
            addIfPresent(config, node, "expression");
            modifiableFields.put("expression", new ModifiableField(node.get("expression"), "expression",
                "SpEL expression to transform data"));
            helpTopic = "transform";
        } else if ("wait".equals(type)) {
            addIfPresent(config, node, "duration");
            addIfPresent(config, node, "durationMs");
            modifiableFields.put("duration", new ModifiableField(
                node.getOrDefault("duration", node.get("durationMs")), "duration",
                "Wait duration (e.g., '5s', '1m', '2h')"));
            helpTopic = "wait";
        }

        return new DescriptionResult(config, modifiableFields, helpTopic, null);
    }

    /**
     * Sanitize a branch/condition list for LLM-facing output.
     * Strips internal dashed IDs and adds computed port names.
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> sanitizeBranchList(List<Map<String, Object>> items, String nodeType) {
        if (items == null || items.isEmpty()) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        int elseifIdx = 0;
        int caseIdx = 0;

        for (int i = 0; i < items.size(); i++) {
            Map<String, Object> item = items.get(i);
            Map<String, Object> sanitized = new LinkedHashMap<>();

            // Copy user-visible fields (no "id")
            if (item.containsKey("label")) sanitized.put("label", item.get("label"));
            if (item.containsKey("expression")) sanitized.put("expression", item.get("expression"));
            if (item.containsKey("value")) sanitized.put("value", item.get("value"));
            if (item.containsKey("type")) sanitized.put("type", item.get("type"));

            // Compute port name based on node type
            String condType = (String) item.get("type");
            switch (nodeType) {
                case "decision" -> {
                    if ("if".equals(condType) || i == 0) {
                        sanitized.put("port", "if");
                    } else if ("else".equals(condType)) {
                        sanitized.put("port", "else");
                    } else {
                        sanitized.put("port", "elseif_" + elseifIdx);
                        elseifIdx++;
                    }
                }
                case "switch" -> {
                    if ("default".equals(condType)) {
                        sanitized.put("port", "default");
                    } else {
                        sanitized.put("port", "case_" + caseIdx);
                        caseIdx++;
                    }
                }
                case "option" -> sanitized.put("port", "choice_" + i);
                case "fork" -> sanitized.put("port", "branch_" + i);
            }

            result.add(sanitized);
        }
        return result;
    }

    private DescriptionResult buildInterfaceDescription(Map<String, Object> node, String tenantId) {
        Map<String, Object> config = new LinkedHashMap<>();
        Map<String, ModifiableField> modifiableFields = new LinkedHashMap<>();

        String interfaceId = (String) node.get("id");
        addIfPresent(config, node, "id");
        addIfPresent(config, node, "label");

        // Fetch template variables from the catalog entity so the agent knows
        // which variable names the HTML template expects.
        // The variable_mapping keys MUST match these names exactly.
        List<String> templateVars = null;
        boolean templateEmpty = false;
        if (interfaceId != null && tenantId != null) {
            try {
                InterfaceDto iface = interfaceClient.getInterface(UUID.fromString(interfaceId), tenantId);
                if (iface != null) {
                    // Check if HTML template is empty
                    String html = iface.getHtmlTemplate();
                    templateEmpty = (html == null || html.isBlank());

                    if (iface.getTemplateVariables() != null && !iface.getTemplateVariables().isEmpty()) {
                        templateVars = iface.getTemplateVariables();
                        config.put("template_variables", templateVars);
                    }
                }
            } catch (Exception e) {
                log.debug("Could not fetch interface template variables for {}: {}", interfaceId, e.getMessage());
            }
        }

        Object variableMapping = node.get("variableMapping");
        if (variableMapping == null) variableMapping = node.get("variable_mapping");
        if (variableMapping != null) {
            config.put("variable_mapping", variableMapping);
            modifiableFields.put("variable_mapping", new ModifiableField(variableMapping, "variable_mapping",
                "Map template variables to workflow expressions. Keys MUST match template_variables exactly: {'title': '{{mcp:step.output.name}}'}"));
        }

        // Warn if variable_mapping keys don't match template_variables
        String warning = null;
        final List<String> finalTemplateVars = templateVars;
        if (finalTemplateVars != null && variableMapping instanceof Map) {
            @SuppressWarnings("unchecked")
            Set<String> mappingKeys = ((Map<String, ?>) variableMapping).keySet();
            List<String> unmapped = finalTemplateVars.stream()
                .filter(v -> !mappingKeys.contains(v))
                .toList();
            List<String> extraKeys = mappingKeys.stream()
                .filter(k -> !finalTemplateVars.contains(k))
                .toList();
            if (!unmapped.isEmpty() || !extraKeys.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                if (!unmapped.isEmpty()) {
                    sb.append("Template variables NOT mapped (will show defaults): ").append(unmapped).append(". ");
                }
                if (!extraKeys.isEmpty()) {
                    sb.append("Mapping keys NOT in template (will be ignored): ").append(extraKeys).append(". ");
                }
                sb.append("Fix: variable_mapping keys must match template_variables exactly.");
                warning = sb.toString();
            }
        }

        // Warn if template is empty
        if (templateEmpty) {
            String emptyMsg = "HTML template is EMPTY. The interface will display nothing. " +
                "Update it: interface(action='update', id='" + interfaceId + "', " +
                "html_template='<div>{{variable|default}}</div>', css_template='...', js_template='...')";
            warning = (warning != null) ? warning + " " + emptyMsg : emptyMsg;
        }

        Object actionMapping = node.get("actionMapping");
        if (actionMapping == null) actionMapping = node.get("action_mapping");
        if (actionMapping != null) {
            config.put("action_mapping", actionMapping);
            modifiableFields.put("action_mapping", new ModifiableField(actionMapping, "action_mapping",
                "Map CSS selectors to triggers: {'#btn': 'trigger:label:click'}. Types: click, submit, message, navigate"));
        }

        return new DescriptionResult(config, modifiableFields, "interface", warning);
    }

    // ==================== Helper methods ====================

    /**
     * Find a contextual step reference from the session to use in examples.
     * Prefers a predecessor of the current node, falls back to any MCP/agent step.
     */
    private String findContextualStepRef(WorkflowBuilderSession session, String currentNodeId) {
        if (session == null) return "mcp:<step_label>";

        // Try to find a predecessor of the current node
        List<Map<String, Object>> incoming = session.getIncomingConnections(currentNodeId);
        for (Map<String, Object> edge : incoming) {
            String from = (String) edge.get("from");
            if (from != null && (from.startsWith("mcp:") || from.startsWith("agent:") || from.startsWith("trigger:"))) {
                return from;
            }
        }

        // Fall back to any MCP step in the session
        for (Map<String, Object> step : session.getMcps()) {
            String stepLabel = (String) step.get("label");
            if (stepLabel != null) {
                boolean isAgent = Boolean.TRUE.equals(step.get("isAgent"));
                String prefix = isAgent ? "agent:" : "mcp:";
                return prefix + WorkflowBuilderSession.normalizeLabel(stepLabel);
            }
        }

        return "mcp:<step_label>";
    }

    /**
     * Find a contextual trigger reference from the session to use in examples.
     */
    private String findContextualTriggerRef(WorkflowBuilderSession session) {
        if (session == null || session.getTriggers().isEmpty()) return "trigger:<trigger_label>";

        Map<String, Object> firstTrigger = session.getTriggers().get(0);
        String triggerLabel = (String) firstTrigger.get("label");
        if (triggerLabel == null) return "trigger:<trigger_label>";

        return "trigger:" + WorkflowBuilderSession.normalizeLabel(triggerLabel);
    }

    private void addIfPresent(Map<String, Object> config, Map<String, Object> node, String key) {
        if (node.containsKey(key)) {
            config.put(key, node.get(key));
        }
    }

    private boolean isSplitNode(String nodeId) {
        return nodeId.contains("split") || nodeId.contains("for_each");
    }

    private boolean isDecisionNode(String nodeId) {
        return nodeId.contains("decision") || nodeId.contains("if") || nodeId.contains("condition");
    }

    private boolean isWebhookTrigger(Map<String, Object> node) {
        return "webhook".equals(node.get("type"));
    }

    /**
     * Get the display type label for a node.
     */
    public String getNodeTypeLabel(String nodeId) {
        if (nodeId.startsWith("trigger:")) return "TRIGGER";
        if (nodeId.startsWith("mcp:")) return "MCP";
        if (nodeId.startsWith("agent:")) return "AGENT";
        if (nodeId.startsWith("core:")) return "CORE";
        if (nodeId.startsWith("interface:")) return "INTERFACE";
        if (nodeId.startsWith("table:")) return "TABLE";
        if (nodeId.startsWith("note:")) return "NOTE";
        return "NODE";
    }
}
