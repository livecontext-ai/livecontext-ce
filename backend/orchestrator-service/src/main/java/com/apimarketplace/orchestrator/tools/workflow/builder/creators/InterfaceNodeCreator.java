package com.apimarketplace.orchestrator.tools.workflow.builder.creators;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.interfaces.client.InterfaceClient;
import com.apimarketplace.interfaces.client.dto.InterfaceDto;
import com.apimarketplace.orchestrator.tools.interface_.InterfaceNodeConfig;
import com.apimarketplace.orchestrator.tools.workflow.WorkflowHelpProvider;
import com.apimarketplace.orchestrator.tools.workflow.builder.SmartDefaultsEngine;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSession;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSessionStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.apimarketplace.agent.tools.ToolErrorCode;

/**
 * Creates interface nodes in the workflow builder.
 * Interface nodes display HTML templates linked to workflow outputs.
 *
 * Uses {@link InterfaceNodeConfig} for type-safe parameter extraction,
 * centralizing all string key access for interface node configuration.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InterfaceNodeCreator extends CreatorBase {

    private final WorkflowBuilderSessionStore sessionStore;
    private final InterfaceClient interfaceClient;
    private final SmartDefaultsEngine smartDefaultsEngine;
    private final WorkflowHelpProvider workflowHelpProvider;

    /**
     * Add an interface node to display workflow outputs visually.
     * The interface must be created first with interface(action='create', ...).
     *
     * Usage: workflow(action='add_node', type='interface', label='Dashboard',
     *        params={interface_id: '<uuid>'}, connect_after='ProcessData')
     */
    public ToolExecutionResult executeAddInterface(WorkflowBuilderSession session, Map<String, Object> parameters) {
        // 1. Validate label
        String label = getFirstString(parameters, "label", "name");
        var labelError = validateLabel(label, "interface");
        if (labelError != null) return labelError;

        if (session.getTriggers().isEmpty()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "TRIGGER REQUIRED FIRST: Cannot add interface without a trigger. " +
                "Create a trigger first: workflow(action='add_node', type='form', label='...', params={...})");
        }

        // 2. Extract typed config - rejects non-string action_mapping values with an
        // agent-actionable message (instead of silently coercing via Map.toString()).
        InterfaceNodeConfig config;
        try {
            config = InterfaceNodeConfig.fromParams(parameters);
        } catch (IllegalArgumentException e) {
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, e.getMessage());
        }

        // 2a. Validate interface_id format and existence
        if (config.interfaceId() == null) {
            Map<String, Object> helpContent = workflowHelpProvider.getHelp("interface");
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "INTERFACE: 'interface_id' is required.\n" +
                "1. Create the template first: interface(action='create', name='...', description='...', html_template='...', css_template='...', js_template='...')\n" +
                "2. Add to workflow: workflow(action='add_node', type='interface', label='...', params={interface_id: '<uuid>', variable_mapping: {...}}, connect_after='...')",
                Map.of("interface_workflow_help", helpContent));
        }

        // 2b. Validate interface_id is a valid UUID
        try {
            UUID.fromString(config.interfaceId());
        } catch (IllegalArgumentException e) {
            return ToolExecutionResult.failure(ToolErrorCode.INVALID_PARAMETER_VALUE, "INTERFACE: 'interface_id' must be a valid UUID. Got: '" + config.interfaceId() + "'.\n" +
                "Use the UUID returned by interface(action='create', ...) - e.g. '550e8400-e29b-41d4-a716-446655440000'.");
        }

        // 2c. Verify the interface exists in the database
        InterfaceDto existingInterface = interfaceClient.getInterface(
            UUID.fromString(config.interfaceId()), session.getTenantId());
        if (existingInterface == null) {
            return ToolExecutionResult.failure(ToolErrorCode.RESOURCE_NOT_FOUND, "INTERFACE: No interface found with id '" + config.interfaceId() + "'.\n" +
                "The interface may have been deleted. Create a new one: interface(action='create', name='...', html_template='...', css_template='...', js_template='...')\n" +
                "Or list existing interfaces: interface(action='list')");
        }

        // 2d. Warn (non-blocking) if action_mapping references nodes that don't exist yet
        List<String> actionMappingWarnings = new ArrayList<>(checkActionMappingReferences(config, session));

        // 3. Build node
        String normalizedLabel = WorkflowBuilderSession.normalizeLabel(label);
        String nodeId = NodeType.INTERFACE.buildNodeId(normalizedLabel);
        var existsError = validateNodeNotExists(session, nodeId, label);
        if (existsError != null) return existsError;

        String connectAfter = resolveConnectAfter(parameters, session);

        // 2e. Warn (non-blocking) if action_mapping references triggers from a different DAG
        if (connectAfter != null && config.actionMapping() != null && !config.actionMapping().isEmpty()) {
            String resolvedConnectAfter = session.resolveNodeReference(connectAfter);
            // Collect trigger labels already flagged as non-existent to avoid double warnings
            Set<String> alreadyFlagged = extractFlaggedTriggerLabels(actionMappingWarnings);
            actionMappingWarnings.addAll(
                CreatorBase.checkCrossDagReferences(config.actionMapping(), resolvedConnectAfter, session, alreadyFlagged));
        }
        var connectError = validateConnectAfter(connectAfter, session);
        if (connectError != null) return connectError;

        // 4. Build node map using typed config
        Map<String, Integer> position = calculatePosition(session, NodeType.INTERFACE);
        Map<String, Object> node = config.toNodeMap(label, position);

        // 4b. Apply smart defaults (LLM chooses WHAT, backend chooses HOW)
        smartDefaultsEngine.applyInterfaceDefaults(node);

        // 5. Add to interfaces array and finalize
        session.getInterfaces().add(node);
        // Single-entry invariant: adding a new entry page demotes any previous one,
        // exactly like the canvas builder does. Must run BEFORE finalizeNode persists.
        List<String> demotedEntries = Boolean.TRUE.equals(node.get("isEntryInterface"))
                ? session.enforceSingleEntryInterface(node)
                : List.of();
        if (connectAfter != null) createSimpleEdge(session, connectAfter, nodeId);
        finalizeNode(session, sessionStore, NodeType.INTERFACE, nodeId, node, connectAfter);

        // 6. Build response with template variable mapping info
        Map<String, Object> extras = config.toExtras();
        if (!demotedEntries.isEmpty()) {
            extras.put("entry_interface_moved", "This interface is now the app's entry page; "
                + "is_entry_interface was cleared on: " + demotedEntries + " (an app has ONE entry page).");
        }
        addTemplateMappingInfo(extras, config, label, session);

        // 6b. Include action_mapping warnings (non-blocking) so LLM can fix later
        if (!actionMappingWarnings.isEmpty()) {
            extras.put("ACTION_MAPPING_WARNING",
                "Some action_mapping references point to nodes that do not exist yet: " +
                actionMappingWarnings + ". The interface was created, but these bindings won't work " +
                "until the referenced nodes exist. Use workflow(action='modify', node='" + label +
                "', params={action_mapping: {...}}) to fix later.");
        }

        // 6c. Enumerate the EXACT action_mapping value tokens this workflow currently supports.
        // Turns the agent's task from "guess/recall the grammar" into "pick from a list" - eliminates
        // hallucinated value shapes (e.g. {trigger:..., mapping:{...}}).
        extras.put("available_action_targets", InterfaceNodeCreator.buildAvailableActionTargets(session));

        return buildResponse("interface", nodeId, label, normalizedLabel, connectAfter,
            extras, config.toSavedParams());
    }

    /**
     * Build the literal list of valid action_mapping value tokens for the current workflow:
     * one entry per existing trigger (with its appropriate event suffix), one per existing
     * interface label (with :navigate), plus the control tokens (__continue, __pagination:*).
     * The agent copy-pastes one of these as the value of an action_mapping entry - no need to
     * memorise the grammar or normalise labels.
     *
     * <p>Package-private for direct unit testing without mocking the full creator stack.
     */
    static Map<String, Object> buildAvailableActionTargets(WorkflowBuilderSession session) {
        List<String> triggers = new ArrayList<>();
        for (Map<String, Object> trigger : session.getTriggers()) {
            Object triggerLabel = trigger.get("label");
            Object triggerType = trigger.get("type");
            if (triggerLabel == null) continue;
            String normalized = WorkflowBuilderSession.normalizeLabel(triggerLabel.toString());
            String event = mapTriggerTypeToEvent(triggerType);
            triggers.add("trigger:" + normalized + ":" + event);
        }

        List<String> interfaces = new ArrayList<>();
        for (Map<String, Object> iface : session.getInterfaces()) {
            Object ifaceLabel = iface.get("label");
            if (ifaceLabel == null) continue;
            String normalized = WorkflowBuilderSession.normalizeLabel(ifaceLabel.toString());
            interfaces.add("interface:" + normalized + ":navigate");
        }

        List<String> control = List.of(
            "__continue",
            "__pagination:next",
            "__pagination:prev",
            "__pagination:first",
            "__pagination:last"
        );

        Map<String, Object> targets = new LinkedHashMap<>();
        targets.put("triggers", triggers);
        targets.put("interfaces", interfaces);
        targets.put("control", control);
        targets.put("usage", "Pick ONE token from these lists as the VALUE of each action_mapping entry. " +
            "Keys MUST start with '#' (CSS selector matching the HTML element id). " +
            "Same-DAG rule applies - if you connect this interface to a specific DAG, only triggers/interfaces " +
            "in that DAG will fire reliably (out-of-DAG references are flagged as warnings).");
        return targets;
    }

    /**
     * Map a trigger node's declared type to the action_mapping event suffix the agent must emit.
     * Defaults to 'submit' for unknown/legacy types - matches the historical behaviour of form triggers.
     */
    private static String mapTriggerTypeToEvent(Object triggerType) {
        if (triggerType == null) return "submit";
        String t = triggerType.toString().toLowerCase(Locale.ROOT);
        if (t.contains("chat") || t.contains("message")) return "message";
        if (t.contains("manual") || t.contains("button") || t.contains("click")) return "click";
        return "submit";
    }

    // ==================== Helpers ====================

    /** Regex to find &lt;form&gt; or &lt;button&gt; tags with an id attribute. */
    private static final Pattern INTERACTIVE_ID_PATTERN = Pattern.compile(
        "<(form|button)\\s+[^>]*?id\\s*=\\s*[\"']([^\"']+)[\"']",
        Pattern.CASE_INSENSITIVE
    );

    /**
     * Fetch template variables from the interface entity and check mapping status.
     * Also detects interactive HTML elements (forms/buttons with IDs) that need action_mapping.
     */
    private void addTemplateMappingInfo(Map<String, Object> extras, InterfaceNodeConfig config,
                                         String label, WorkflowBuilderSession session) {
        try {
            UUID uuid = UUID.fromString(config.interfaceId());
            InterfaceDto iface = interfaceClient.getInterface(uuid, session.getTenantId());
            if (iface != null) {

                // 0. Warn if template is empty
                String html = iface.getHtmlTemplate();
                if (html == null || html.isBlank()) {
                    extras.put("TEMPLATE_WARNING",
                        "HTML template is EMPTY - the interface will display nothing. " +
                        "Update it: interface(action='update', id='" + config.interfaceId() + "', " +
                        "html_template='<div>{{variable|default}}</div>', css_template='...', js_template='...')");
                }

                // 1. Template variable info
                List<String> templateVars = iface.getTemplateVariables();
                if (templateVars != null && !templateVars.isEmpty()) {
                    extras.put("template_variables", templateVars);

                    // 1b. Check variable_mapping alignment
                    if (config.variableMapping() != null && !config.variableMapping().isEmpty()) {
                        List<String> unmapped = templateVars.stream()
                            .filter(v -> !config.variableMapping().containsKey(v))
                            .toList();
                        List<String> extraKeys = config.variableMapping().keySet().stream()
                            .filter(k -> !templateVars.contains(k))
                            .toList();
                        if (!unmapped.isEmpty() || !extraKeys.isEmpty()) {
                            StringBuilder sb = new StringBuilder("variable_mapping keys don't match template_variables. ");
                            if (!unmapped.isEmpty()) sb.append("NOT mapped: ").append(unmapped).append(". ");
                            if (!extraKeys.isEmpty()) sb.append("NOT in template: ").append(extraKeys).append(". ");
                            sb.append("Fix: variable_mapping keys MUST match template_variables exactly.");
                            extras.put("MAPPING_WARNING", sb.toString());
                        }
                    }
                }

                // 2. Check for interactive elements needing action_mapping
                boolean hasActionMapping = config.actionMapping() != null && !config.actionMapping().isEmpty();
                if (!hasActionMapping && iface.getHtmlTemplate() != null) {
                    Map<String, String> interactiveElements = extractInteractiveElements(iface.getHtmlTemplate());
                    if (!interactiveElements.isEmpty()) {
                        StringBuilder suggestion = new StringBuilder();
                        suggestion.append("HTML template has interactive elements with IDs that need action_mapping:\n");
                        for (Map.Entry<String, String> elem : interactiveElements.entrySet()) {
                            String id = elem.getKey();
                            String tag = elem.getValue();
                            String actionType = "form".equalsIgnoreCase(tag) ? "submit" : "click";
                            suggestion.append("  '#").append(id).append("' (").append(tag)
                                .append(") → needs 'trigger:<trigger_label>:").append(actionType).append("'\n");
                        }
                        suggestion.append("\nFix: workflow(action='modify', node='").append(label)
                            .append("', params={action_mapping: {");

                        boolean first = true;
                        for (Map.Entry<String, String> elem : interactiveElements.entrySet()) {
                            if (!first) suggestion.append(", ");
                            first = false;
                            String id = elem.getKey();
                            String tag = elem.getValue();
                            String actionType = "form".equalsIgnoreCase(tag) ? "submit" : "click";
                            suggestion.append("'#").append(id).append("': 'trigger:<trigger_label>:").append(actionType).append("'");
                        }
                        suggestion.append("}})");

                        extras.put("ACTION_MAPPING_NEEDED", suggestion.toString());
                    }
                }
            } else {
                extras.put("note", "Interface created. Use variable_mapping to display data, or add action_mapping to make it an interactive app.");
            }
        } catch (Exception e) {
            log.warn("Could not fetch interface {} for template variables: {}", config.interfaceId(), e.getMessage());
            extras.put("note", "Interface created. Use variable_mapping to display data, or add action_mapping to make it an interactive app.");
        }
    }

    /**
     * Extract interactive HTML elements (forms/buttons) that have id attributes.
     * @return Map of elementId → tagName (e.g. "contact-form" → "form", "send-btn" → "button")
     */
    private static Map<String, String> extractInteractiveElements(String html) {
        Map<String, String> elements = new LinkedHashMap<>();
        Matcher matcher = INTERACTIVE_ID_PATTERN.matcher(html);
        while (matcher.find()) {
            String tag = matcher.group(1).toLowerCase();
            String id = matcher.group(2);
            elements.put(id, tag);
        }
        return elements;
    }

    /**
     * Check action_mapping references and return warnings for any that don't exist yet.
     * Non-blocking: the interface node is always created, even with invalid references.
     * The LLM can fix references later via modify action.
     *
     * Supported formats:
     * - trigger:label:submit   (form trigger)
     * - trigger:label:click    (manual trigger)
     * - trigger:label:message  (chat trigger)
     * - interface:label:navigate (interface node)
     */
    private List<String> checkActionMappingReferences(InterfaceNodeConfig config, WorkflowBuilderSession session) {
        if (config.actionMapping() == null || config.actionMapping().isEmpty()) {
            return List.of();
        }

        // Collect existing trigger keys (e.g. "trigger:input_form", "trigger:start")
        Set<String> existingTriggerKeys = new HashSet<>();
        for (Map<String, Object> trigger : session.getTriggers()) {
            Object triggerLabel = trigger.get("label");
            if (triggerLabel != null) {
                String normalized = WorkflowBuilderSession.normalizeLabel(triggerLabel.toString());
                existingTriggerKeys.add("trigger:" + normalized);
            }
        }

        // Collect existing interface keys (e.g. "interface:dashboard", "interface:settings_page")
        Set<String> existingInterfaceKeys = new HashSet<>();
        for (Map<String, Object> iface : session.getInterfaces()) {
            Object ifaceLabel = iface.get("label");
            if (ifaceLabel != null) {
                String normalized = WorkflowBuilderSession.normalizeLabel(ifaceLabel.toString());
                existingInterfaceKeys.add("interface:" + normalized);
            }
        }

        List<String> invalidRefs = new ArrayList<>();
        for (Map.Entry<String, String> entry : config.actionMapping().entrySet()) {
            String value = entry.getValue();
            if (value == null) continue;

            // Skip validation for built-in pagination actions (no node reference needed)
            if (value.startsWith("__pagination:")) continue;

            String[] parts = value.split(":");
            if (parts.length < 3) continue;

            String prefix = parts[0];
            String label = parts[1];
            String nodeKey = prefix + ":" + label;

            if ("trigger".equals(prefix)) {
                if (!existingTriggerKeys.contains(nodeKey)) {
                    invalidRefs.add(entry.getKey() + " -> " + value + " (trigger '" + label + "' not found)");
                }
            } else if ("interface".equals(prefix)) {
                if (!existingInterfaceKeys.contains(nodeKey)) {
                    invalidRefs.add(entry.getKey() + " -> " + value + " (interface '" + label + "' not found)");
                }
            }
        }

        if (!invalidRefs.isEmpty()) {
            log.warn("[InterfaceNodeCreator] action_mapping has unresolved references: {}", invalidRefs);
        }

        return invalidRefs;
    }

    /**
     * Extract trigger labels already flagged as non-existent from existing warnings.
     * Used to avoid double warnings (non-existent + cross-DAG) for the same trigger.
     */
    private static Set<String> extractFlaggedTriggerLabels(List<String> existingWarnings) {
        Set<String> labels = new HashSet<>();
        for (String warning : existingWarnings) {
            // Warnings have format: "selector -> trigger:label:action (trigger 'label' not found)"
            int triggerIdx = warning.indexOf("trigger '");
            if (triggerIdx >= 0) {
                int start = triggerIdx + "trigger '".length();
                int end = warning.indexOf("'", start);
                if (end > start) {
                    labels.add(warning.substring(start, end));
                }
            }
        }
        return labels;
    }

    private static String getFirstString(Map<String, Object> params, String... keys) {
        for (String key : keys) {
            Object value = params.get(key);
            if (value instanceof String s && !s.isBlank()) return s;
        }
        return null;
    }

    private ToolExecutionResult buildResponse(String nodeType, String nodeId, String label,
                                               String normalizedLabel, String connectAfter,
                                               Map<String, Object> extras, Map<String, Object> savedParams) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "success");
        response.put("node_type", nodeType);
        response.put("node_id", nodeId);
        response.put("label", label);
        response.putAll(extras);

        if (savedParams != null && !savedParams.isEmpty()) {
            response.put("saved_params", savedParams);
        }

        response.put("connection", Map.of(
            "status", connectAfter != null ? "connected" : "orphaned",
            "connected_after", connectAfter != null ? connectAfter : "none"
        ));

        response.put("NEXT", Map.of(
            "pattern", "workflow(action='add_node', type='...', label='...', connect_after='" + label + "')"
        ));

        return ToolExecutionResult.success(response);
    }
}
