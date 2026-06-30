package com.apimarketplace.orchestrator.tools.workflow.builder.creators;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.datasource.client.DataSourceClient;
import com.apimarketplace.datasource.client.dto.ColumnMappingSpecDto;
import com.apimarketplace.datasource.client.dto.DataSourceDto;
import com.apimarketplace.orchestrator.tools.workflow.builder.ResponseOptimizer;
import com.apimarketplace.orchestrator.tools.workflow.builder.SmartDefaultsEngine;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSession;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSessionStore;
import com.apimarketplace.orchestrator.utils.LabelNormalizer;
import com.apimarketplace.trigger.client.TriggerClient;
import com.apimarketplace.trigger.client.dto.StandaloneChatEndpointDto;
import com.apimarketplace.trigger.client.dto.StandaloneChatEndpointRequest;
import com.apimarketplace.trigger.client.dto.StandaloneFormEndpointDto;
import com.apimarketplace.trigger.client.dto.StandaloneFormEndpointRequest;
import com.apimarketplace.trigger.client.dto.StandaloneWebhookDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import com.apimarketplace.agent.tools.ToolErrorCode;

/**
 * Creates trigger nodes for workflows.
 *
 * IMPORTANT: WorkflowBuilderProvider already merges all parameters into a flat map
 * before calling this creator. This means:
 * - params={x: 1, y: 2} is flattened to root level
 * - type='form' is converted to trigger_type='form'
 *
 * So we just read directly from parameters - no re-extraction needed.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TriggerCreator extends CreatorBase {

    private final WorkflowBuilderSessionStore sessionStore;
    private final DataSourceClient dataSourceClient;
    private final SmartDefaultsEngine smartDefaultsEngine;
    private final ResponseOptimizer responseOptimizer;
    private final TriggerClient triggerClient;

    /**
     * Execute add_trigger action.
     * Parameters are already flattened by WorkflowBuilderProvider.
     */
    public ToolExecutionResult executeAddTrigger(WorkflowBuilderSession session, Map<String, Object> parameters, String tenantId) {
        // 1. Validate label (required)
        String label = getString(parameters, "label", "name");
        if (label == null || label.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "label is REQUIRED. Example: workflow(action='add_node', type='form', label='Contact Form', params={...})");
        }

        // 2. Check for duplicate labels (multi-trigger requires unique labels)
        String normalizedLabel = WorkflowBuilderSession.normalizeLabel(label);
        for (Map<String, Object> existing : session.getTriggers()) {
            String existingLabel = (String) existing.get("label");
            if (existingLabel != null && normalizedLabel.equals(WorkflowBuilderSession.normalizeLabel(existingLabel))) {
                return ToolExecutionResult.failure(ToolErrorCode.RESOURCE_ALREADY_EXISTS, "Trigger with label '" + existingLabel + "' already exists. Each trigger must have a unique label. " +
                    "Use a different label for your new trigger.");
            }
        }

        // 2b. Cross-prefix collision: a non-trigger node may already carry this
        // normalized label. Triggers sort FIRST in the label resolver, so a new
        // trigger "Foo" would shadow an existing mcp/agent/core "Foo" - making the
        // other node unaddressable by label. Reject here too (the loop above only
        // guards trigger-vs-trigger). See CreatorBase.validateNodeNotExists.
        String crossPrefixClash = session.validateUniqueLabel(label, "trigger");
        if (crossPrefixClash != null) {
            return ToolExecutionResult.failure(ToolErrorCode.RESOURCE_ALREADY_EXISTS, crossPrefixClash);
        }

        // 3. Get trigger type (required)
        // WorkflowBuilderProvider sets trigger_type when user sends type='form', 'chat', etc.
        String type = getString(parameters, "trigger_type", "type");
        if (type == null || type.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "trigger_type is required. Available: manual, chat, webhook, schedule, table, workflow, form");
        }

        // Validate trigger type is one of the allowed subtypes
        var validTypes = Set.of("manual", "chat", "webhook", "schedule", "table", "datasource", "workflow", "form", "error");
        if (!validTypes.contains(type.toLowerCase())) {
            return ToolExecutionResult.failure(ToolErrorCode.INVALID_PARAMETER_VALUE, "Invalid trigger type: '" + type + "'. Available: manual, chat, webhook, schedule, table, workflow, form, error.\n" +
                "Example: workflow(action='add_node', type='form', label='My Form', params={...})");
        }
        type = type.toLowerCase();

        // Normalize "table" -> "datasource" internally
        if ("table".equals(type)) {
            type = "datasource";
        }

        // 4. Type-specific validation
        String datasourceId = getString(parameters, "table_id", "datasource_id", "id");
        if ("datasource".equals(type) && (datasourceId == null || datasourceId.isBlank())) {
            return buildTableIdRequiredError(tenantId);
        }

        String workflowId = getString(parameters, "workflow_id", "workflowId");
        if ("workflow".equals(type) && (workflowId == null || workflowId.isBlank())) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "workflow_id is required for workflow triggers. Example: workflow(action='add_node', type='workflow', label='After Parent', params={workflow_id: '<uuid>'})");
        }

        // Error triggers also need a parent workflow id (which is stored under the
        // trigger's `id` field in the plan - see buildTriggerNode below - so the
        // dispatcher's WorkflowRepository.findByErrorTrigger can match on it).
        // Accept multiple aliases so the agent can use the natural name.
        String errorParentWorkflowId = getString(parameters, "parent_workflow_id", "parentWorkflowId", "workflow_id", "workflowId", "id");
        if ("error".equals(type) && (errorParentWorkflowId == null || errorParentWorkflowId.isBlank())) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "parent_workflow_id is required for error triggers - the workflow whose failure should fire this handler. " +
                "Example: workflow(action='add_node', type='error', label='On Payment Failure', params={parent_workflow_id: '<uuid>'}). " +
                "After workflow(action='finish'), bootstrap the seed run with workflow(action='execute', id='<this_workflow_id>') - " +
                "it returns status='BOOTSTRAPPED' with the seed run id (no fire happens; error triggers are system-only). " +
                "Without that seed run, the dispatcher silently drops parent failures.");
        }
        // Reuse the workflowId variable so buildTriggerNode picks the parent workflow id
        // for error triggers without changing its signature.
        if ("error".equals(type)) {
            workflowId = errorParentWorkflowId;
        }

        // 5a. Validate schedule cron expression
        if ("schedule".equals(type)) {
            String schedule = getString(parameters, "schedule", "cron");
            String cronError = validateScheduleCron(schedule);
            if (cronError != null) {
                return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, cronError);
            }
        }

        // 5b. Validate form fields (if form trigger). validateFormFields also
        // mutates the params Map in place: replaces `fields` with a mutable
        // canonical list (auto-filled ids, coerced select options) so the
        // downstream buildTriggerNode + buildTriggerSchema see the same
        // canonical shape the inspector and PublicFormRenderer key on.
        if ("form".equals(type)) {
            String fieldError = validateFormFields(parameters);
            if (fieldError != null) {
                return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, fieldError);
            }
        }

        // 5c. Validate datasource trigger event_types + filter (explicit failure on bad values)
        if ("datasource".equals(type)) {
            String dsError = validateDatasourceTriggerParams(parameters);
            if (dsError != null) {
                return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, dsError);
            }
        }

        // 5d. Validate chat trigger chatMatch shape (explicit failure on bad values)
        if ("chat".equals(type)) {
            String chatError = validateChatMatch(parameters);
            if (chatError != null) {
                return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, chatError);
            }
        }

        // 6. Build trigger node
        String nodeId = "trigger:" + normalizedLabel;

        Map<String, Object> triggerNode = buildTriggerNode(parameters, label, type, datasourceId, workflowId, session);

        // Apply smart defaults (e.g., schedule strategy)
        triggerNode = smartDefaultsEngine.applyTriggerDefaults(triggerNode);

        // 6. Add type-specific input configuration
        addTypeSpecificInput(triggerNode, parameters, type);

        // 6b. Auto-create standalone endpoint (like frontend does on drag-and-drop)
        if ("webhook".equals(type)) {
            autoCreateStandaloneWebhook(triggerNode, nodeId, label, session, tenantId);
        } else if ("chat".equals(type)) {
            autoCreateStandaloneChatEndpoint(triggerNode, nodeId, label, session, tenantId);
        } else if ("form".equals(type)) {
            autoCreateStandaloneFormEndpoint(triggerNode, nodeId, label, session, tenantId);
        }

        // 7. Add to session
        session.getTriggers().add(triggerNode);

        // 8. Build and store schema
        Map<String, String> outputs = new LinkedHashMap<>();
        Map<String, String> referenceSyntax = new LinkedHashMap<>();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> formFields = (List<Map<String, Object>>) parameters.get("fields");
        buildTriggerSchema(nodeId, type, datasourceId, getMap(parameters, "inputSchema"), formFields, outputs, referenceSyntax, tenantId);

        session.getNodeSchemas().put(nodeId, WorkflowBuilderSession.NodeSchema.builder()
                .nodeId(nodeId)
                .nodeType("trigger")
                .label(label)
                .outputs(outputs)
                .referenceSyntax(referenceSyntax)
                .build());

        session.setLastAddedNodeId(nodeId);
        session.recordAction("add_trigger", nodeId, "trigger", new LinkedHashMap<>(triggerNode));
        sessionStore.save(session);

        Map<String, Object> response = responseOptimizer.buildTriggerResponse(session, nodeId, label, type, datasourceId, outputs, referenceSyntax);

        // Show saved params so LLM knows what was actually stored
        Map<String, Object> savedParams = new LinkedHashMap<>();
        savedParams.put("type", type);
        if (triggerNode.get("params") != null) {
            savedParams.put("params", triggerNode.get("params"));
        }
        if (datasourceId != null) {
            savedParams.put("table_id", datasourceId);
        }
        response.put("saved_params", savedParams);

        // Override guidance with actual URL if auto-created
        String webhookUrl = (String) triggerNode.get("standaloneWebhookUrl");
        if (webhookUrl != null) {
            response.put("webhook", Map.of(
                "url", webhookUrl,
                "webhook_id", triggerNode.get("standaloneWebhookId"),
                "note", "Webhook URL is ready to use immediately"
            ));
        }

        String chatUrl = (String) triggerNode.get("standaloneChatUrl");
        if (chatUrl != null) {
            response.put("chat_endpoint", Map.of(
                "url", chatUrl,
                "endpoint_id", triggerNode.get("standaloneChatEndpointId"),
                "note", "Chat endpoint URL is ready to use immediately"
            ));
        }

        String formUrl = (String) triggerNode.get("standaloneFormUrl");
        if (formUrl != null) {
            response.put("form_endpoint", Map.of(
                "url", formUrl,
                "endpoint_id", triggerNode.get("standaloneFormEndpointId"),
                "note", "Form endpoint URL is ready to use immediately"
            ));
        }

        return ToolExecutionResult.success(response);
    }

    // ==================== Private Helpers ====================

    /**
     * Auto-create a standalone webhook for a webhook trigger.
     * Mirrors the frontend behavior: webhook is created immediately so the URL is available
     * without needing to save the workflow first.
     */
    @SuppressWarnings("unchecked")
    private void autoCreateStandaloneWebhook(Map<String, Object> triggerNode, String nodeId,
                                              String label, WorkflowBuilderSession session, String tenantId) {
        try {
            Map<String, Object> params = (Map<String, Object>) triggerNode.get("params");
            String httpMethod = params != null ? (String) params.getOrDefault("httpMethod", "POST") : "POST";
            String authType = params != null ? (String) params.getOrDefault("authType", "none") : "none";

            // Thread session.getOrgId() so webhook + webhook_tokens land in
            // the active workspace (V215 column). Audit 2026-05-16 - MCP-built
            // standalone webhooks were NULL-orged previously.
            StandaloneWebhookDto webhook = triggerClient.createStandaloneWebhook(
                    tenantId, label, null, httpMethod, authType, null, session.getOrgId());

            if (webhook == null) {
                log.warn("Failed to auto-create standalone webhook for trigger {} (null response)", nodeId);
                return;
            }

            // Store webhookId in params (so syncWebhookToken skips token generation)
            if (params == null) {
                params = new LinkedHashMap<>();
                triggerNode.put("params", params);
            }
            params.put("webhookId", webhook.getId().toString());

            // Construct webhook URL
            String webhookUrl = "/webhook/" + webhook.getToken();

            // Store on trigger node (matches frontend standaloneWebhookId/Url/Token)
            triggerNode.put("standaloneWebhookId", webhook.getId().toString());
            triggerNode.put("standaloneWebhookUrl", webhookUrl);
            triggerNode.put("standaloneWebhookToken", webhook.getToken());

            // Also cache the URL in session webhookTokens (for WorkflowBuilderViewer.describe)
            session.getWebhookTokens().put(nodeId, webhook.getToken());

            log.info("Auto-created standalone webhook '{}' ({}) for trigger {}",
                    webhook.getName(), webhook.getId(), nodeId);
        } catch (Exception e) {
            log.warn("Failed to auto-create standalone webhook for trigger {}: {}", nodeId, e.getMessage());
            // Non-fatal - webhook URL will show placeholder until save
        }
    }

    /**
     * Auto-create a standalone chat endpoint for a chat trigger.
     */
    private void autoCreateStandaloneChatEndpoint(Map<String, Object> triggerNode, String nodeId,
                                                   String label, WorkflowBuilderSession session, String tenantId) {
        try {
            String triggerId = LabelNormalizer.triggerKey(label);
            StandaloneChatEndpointRequest request = new StandaloneChatEndpointRequest(
                    label, null, null, null, null, null, null, true, null, triggerId);
            // Audit 2026-05-17 round-3 - thread session.getOrgId() so the chat
            // endpoint row carries organization_id (workspace-aware visibility +
            // fire-path scope guard, mirror of webhook fix from audit round-1).
            String orgId = session != null ? session.getOrgId() : null;
            StandaloneChatEndpointDto endpoint = triggerClient.createChatEndpoint(tenantId, null, request, orgId);

            if (endpoint == null) {
                log.warn("Failed to auto-create standalone chat endpoint for trigger {} (null response)", nodeId);
                return;
            }

            // Store endpoint ID in params
            @SuppressWarnings("unchecked")
            Map<String, Object> params = (Map<String, Object>) triggerNode.get("params");
            if (params == null) {
                params = new LinkedHashMap<>();
                triggerNode.put("params", params);
            }
            params.put("chatEndpointId", endpoint.getId().toString());

            // Store on trigger node for response enrichment
            triggerNode.put("standaloneChatEndpointId", endpoint.getId().toString());
            triggerNode.put("standaloneChatUrl", endpoint.getChatUrl());
            triggerNode.put("standaloneChatToken", endpoint.getToken());

            log.info("Auto-created standalone chat endpoint '{}' ({}) for trigger {}",
                    endpoint.getName(), endpoint.getId(), nodeId);
        } catch (Exception e) {
            log.warn("Failed to auto-create standalone chat endpoint for trigger {}: {}", nodeId, e.getMessage());
        }
    }

    /**
     * Auto-create a standalone form endpoint for a form trigger.
     */
    @SuppressWarnings("unchecked")
    private void autoCreateStandaloneFormEndpoint(Map<String, Object> triggerNode, String nodeId,
                                                   String label, WorkflowBuilderSession session, String tenantId) {
        try {
            Map<String, Object> params = (Map<String, Object>) triggerNode.get("params");
            List<Map<String, Object>> formConfig = params != null
                    ? (List<Map<String, Object>>) params.get("fields") : null;
            String successMessage = params != null ? (String) params.get("successMessage") : null;

            String triggerId = LabelNormalizer.triggerKey(label);
            StandaloneFormEndpointRequest request = new StandaloneFormEndpointRequest(
                    label, null, null, null, formConfig, successMessage, null, triggerId);
            // Audit 2026-05-17 round-3 - same fix as chat-endpoint above.
            String orgId = session != null ? session.getOrgId() : null;
            StandaloneFormEndpointDto endpoint = triggerClient.createFormEndpoint(tenantId, null, request, orgId);

            if (endpoint == null) {
                log.warn("Failed to auto-create standalone form endpoint for trigger {} (null response)", nodeId);
                return;
            }

            // Store endpoint ID in params
            if (params == null) {
                params = new LinkedHashMap<>();
                triggerNode.put("params", params);
            }
            params.put("formEndpointId", endpoint.getId().toString());

            // Store on trigger node for response enrichment
            triggerNode.put("standaloneFormEndpointId", endpoint.getId().toString());
            triggerNode.put("standaloneFormUrl", endpoint.getFormUrl());
            triggerNode.put("standaloneFormToken", endpoint.getToken());

            log.info("Auto-created standalone form endpoint '{}' ({}) for trigger {}",
                    endpoint.getName(), endpoint.getId(), nodeId);
        } catch (Exception e) {
            log.warn("Failed to auto-create standalone form endpoint for trigger {}: {}", nodeId, e.getMessage());
        }
    }

    /**
     * Build the base trigger node structure.
     */
    private Map<String, Object> buildTriggerNode(Map<String, Object> parameters, String label, String type,
                                                   String datasourceId, String workflowId,
                                                   WorkflowBuilderSession session) {
        Map<String, Object> node = new LinkedHashMap<>();

        // ID: workflow + error triggers store the parent workflow id here (the dispatcher
        // queries on this field). Table triggers store the datasource id. Everything else
        // gets a random UUID - the id is opaque for non-link triggers.
        String triggerId = ("workflow".equals(type) || "error".equals(type)) ? workflowId
            : (datasourceId != null ? datasourceId : UUID.randomUUID().toString());
        node.put("id", triggerId);
        node.put("label", label);
        node.put("type", type);
        node.put("strategy", parameters.getOrDefault("strategy", "receive_one"));

        // Empty position - frontend auto-layout (Dagre) handles positioning
        node.put("position", Map.of());

        if (datasourceId != null) {
            node.put("datasource_id", datasourceId);
        }

        // Persist interface_id if provided (used by frontend to link trigger to interface node)
        String interfaceId = getString(parameters, "interface_id", "interfaceId");
        if (interfaceId != null) {
            node.put("interface_id", interfaceId);
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> inputSchema = (Map<String, Object>) parameters.get("inputSchema");
        if (inputSchema != null) {
            node.put("inputSchema", inputSchema);
        }

        return node;
    }

    /**
     * Validate schedule cron expression.
     * Only standard 5-field cron expressions are accepted (minimum frequency: every minute).
     * Interval formats (e.g., "5m", "1h") are rejected.
     *
     * @return error message or null if valid
     */
    private String validateScheduleCron(String cron) {
        if (cron == null || cron.isBlank()) {
            return null; // will use default
        }

        String trimmed = cron.trim();

        // Reject interval format (e.g., "5m", "1h", "30s", "1d")
        if (trimmed.matches("\\d+[smhdw]")) {
            return "Invalid schedule format: '" + trimmed + "'. Only cron expressions are supported.\n" +
                "Use a standard 5-field cron expression: minute hour day-of-month month day-of-week\n" +
                "Examples:\n" +
                "  '* * * * *'     → every minute (minimum frequency)\n" +
                "  '*/5 * * * *'   → every 5 minutes\n" +
                "  '0 * * * *'     → every hour\n" +
                "  '0 9 * * *'     → every day at 9:00\n" +
                "  '0 9 * * 1-5'   → weekdays at 9:00";
        }

        // Validate as cron expression using basic validation
        // (ScheduleCronParser moved to trigger-service, basic validation here)
        String[] parts = trimmed.split("\\s+");
        if (parts.length != 5) {
            return "Invalid cron expression: '" + trimmed + "'.\n" +
                "Use a standard 5-field cron: minute hour day-of-month month day-of-week\n" +
                "Examples:\n" +
                "  '*/5 * * * *'   → every 5 minutes\n" +
                "  '0 * * * *'     → every hour\n" +
                "  '0 9 * * *'     → every day at 9:00\n" +
                "  '0 0 1 * *'     → first day of each month at midnight";
        }

        // Strict step-range validation - mirrors trigger-service ScheduleCronParser
        // (the canonical validator). Spring's CronExpression SILENTLY collapses a */N step
        // whose N exceeds the field's range (e.g. */120 in the minute field, max 59) down to
        // "the start of the field" (minute 0), so the published schedule NEVER fires. Without
        // this guard the basic 5-field check above accepts */120, the agent ships a dead
        // schedule, and the inspector later flags "Invalid cron". Reject it here so the agent
        // gets a clear, actionable error at build time. The trigger-service parser remains the
        // defence-in-depth check at schedule-row creation.
        String stepError = scheduleCronStepRangeError(parts);
        if (stepError != null) {
            return stepError;
        }

        return null;
    }

    /** Min value per 5-field cron position (minute, hour, day-of-month, month, day-of-week). */
    private static final int[] CRON_FIELD_MIN = { 0, 0, 1, 1, 0 };
    /** Max value per 5-field cron position. */
    private static final int[] CRON_FIELD_MAX = { 59, 23, 31, 12, 7 };
    private static final String[] CRON_FIELD_NAME = { "minute", "hour", "day-of-month", "month", "day-of-week" };

    /**
     * Reject any {@code *&#47;N} (or list member {@code a,*&#47;N}) whose step N falls outside the
     * field's range. Mirrors {@code ScheduleCronParser.stepValuesWithinFieldRange} in
     * trigger-service. Returns an actionable error message, or null when every step is sane.
     */
    private String scheduleCronStepRangeError(String[] parts) {
        for (int i = 0; i < parts.length && i < CRON_FIELD_MAX.length; i++) {
            int span = CRON_FIELD_MAX[i] - CRON_FIELD_MIN[i] + 1;
            for (String member : parts[i].split(",")) {
                int slash = member.indexOf('/');
                if (slash < 0) continue;
                int step;
                try {
                    step = Integer.parseInt(member.substring(slash + 1).trim());
                } catch (NumberFormatException e) {
                    return "Invalid cron: step '" + member + "' in the " + CRON_FIELD_NAME[i]
                        + " field is not a number.";
                }
                if (step <= 0 || step > span) {
                    return "Invalid cron: step '*/" + step + "' in the " + CRON_FIELD_NAME[i]
                        + " field exceeds its range (max " + CRON_FIELD_MAX[i] + "). A step larger than the\n" +
                        "field silently collapses to a single value, so the schedule would never fire.\n" +
                        "Put a multi-hour interval in the HOUR field instead, e.g.:\n" +
                        "  '0 */2 * * *'   → every 2 hours\n" +
                        "  '0 */6 * * *'   → every 6 hours\n" +
                        "  '0 0 */2 * *'   → every 2 days at midnight";
                }
            }
        }
        return null;
    }

    /**
     * Add type-specific input configuration (schedule, form, etc.)
     */
    @SuppressWarnings("unchecked")
    private void addTypeSpecificInput(Map<String, Object> triggerNode, Map<String, Object> parameters, String type) {
        if ("schedule".equals(type)) {
            Map<String, Object> input = new LinkedHashMap<>();

            // Cron expression (only standard 5-field cron accepted)
            String schedule = getString(parameters, "schedule", "cron");
            input.put("cron", schedule != null ? schedule.trim() : "0 * * * *");

            // Timezone
            input.put("timezone", parameters.getOrDefault("timezone", "UTC"));

            // Enabled
            input.put("enabled", parameters.getOrDefault("enabled", true));

            // Max executions
            Object maxExec = parameters.get("maxExecutions");
            if (maxExec == null) maxExec = parameters.get("max_executions");
            if (maxExec != null) {
                input.put("maxExecutions", toInt(maxExec));
            }

            triggerNode.put("params", input);

        } else if ("form".equals(type)) {
            Map<String, Object> input = new LinkedHashMap<>();

            // Form title
            String formTitle = getString(parameters, "form_title", "formTitle", "title");
            if (formTitle != null) input.put("formTitle", formTitle);

            // Form description
            String formDesc = getString(parameters, "form_description", "formDescription", "description");
            if (formDesc != null) input.put("formDescription", formDesc);

            // Submit button
            input.put("submitButtonText", parameters.getOrDefault("submit_button_text",
                parameters.getOrDefault("submitButtonText", "Submit")));

            // Auth type
            input.put("authType", parameters.getOrDefault("auth_type",
                parameters.getOrDefault("authType", "none")));

            // Fields - normalize invalid types (e.g., "string" → "text")
            List<Map<String, Object>> fields = (List<Map<String, Object>>) parameters.get("fields");
            if (fields != null) {
                fields = normalizeFormFieldTypes(fields);
            }
            input.put("fields", fields != null ? fields : new ArrayList<>());

            triggerNode.put("params", input);

        } else if ("webhook".equals(type)) {
            Map<String, Object> input = new LinkedHashMap<>();

            // HTTP method (default: POST)
            String httpMethod = getString(parameters, "httpMethod", "http_method", "method");
            input.put("httpMethod", httpMethod != null ? httpMethod.toUpperCase() : "POST");

            // Auth type (default: none)
            String authType = getString(parameters, "authType", "auth_type");
            input.put("authType", authType != null ? authType.toLowerCase() : "none");

            // Auth-specific params based on authType
            String resolvedAuth = (String) input.get("authType");
            if ("basic".equals(resolvedAuth)) {
                String user = getString(parameters, "basicUsername", "basic_username", "username");
                if (user != null) input.put("basicUsername", user);
                String pass = getString(parameters, "basicPassword", "basic_password", "password");
                if (pass != null) input.put("basicPassword", pass);
            } else if ("header".equals(resolvedAuth)) {
                String headerName = getString(parameters, "authHeaderName", "auth_header_name", "headerName");
                if (headerName != null) input.put("authHeaderName", headerName);
                String headerValue = getString(parameters, "authHeaderValue", "auth_header_value", "headerValue");
                if (headerValue != null) input.put("authHeaderValue", headerValue);
            } else if ("jwt".equals(resolvedAuth)) {
                String secretKey = getString(parameters, "jwtSecretKey", "jwt_secret_key", "secretKey");
                if (secretKey != null) input.put("jwtSecretKey", secretKey);
                String algorithm = getString(parameters, "jwtAlgorithm", "jwt_algorithm", "algorithm");
                if (algorithm != null) input.put("jwtAlgorithm", algorithm);
            }

            triggerNode.put("params", input);

        } else if ("datasource".equals(type)) {
            Map<String, Object> input = new LinkedHashMap<>();

            // event_types: which row changes fire the trigger
            // Valid: "row_created", "row_updated", "row_deleted"
            // Default: all three
            Object rawEvents = parameters.get("event_types");
            if (rawEvents == null) rawEvents = parameters.get("eventTypes");
            input.put("event_types", normalizeEventTypes(rawEvents));

            // filter: optional {column, operator, value} - fires only when matched
            Object rawFilter = parameters.get("filter");
            if (rawFilter instanceof Map<?, ?> m) {
                input.put("filter", new LinkedHashMap<String, Object>((Map<String, Object>) m));
            }

            triggerNode.put("params", input);

        } else if ("chat".equals(type)) {
            // chatMatch lives at trigger top-level (not inside params) to match the
            // exporter shape in frontend/.../triggerProcessor.ts#buildChatMatchConfig
            // and how WorkflowPlanParser reads it: data.get("chatMatch").
            // Normalization accepted: both {type: 'startsWith'} and {type: 'starts_with'}
            // - ChatMatchConfig.fromMap handles both. We just forward the map through.
            Object rawChatMatch = parameters.get("chatMatch");
            if (rawChatMatch instanceof Map<?, ?> chatMatchMap) {
                triggerNode.put("chatMatch", new LinkedHashMap<String, Object>((Map<String, Object>) chatMatchMap));
            }
            // Absent chatMatch: Trigger record constructor defaults to ANY - no node field needed.
        }
    }

    /**
     * Accepted chatMatch types (both backend canonical and frontend aliases).
     * Mirrors {@link com.apimarketplace.orchestrator.domain.workflow.ChatMatchConfig#fromMap}.
     */
    private static final Set<String> VALID_CHAT_MATCH_TYPES =
        Set.of("any", "starts_with", "startswith", "ends_with", "endswith",
               "contains", "equals", "regex");

    /**
     * Validate chat trigger chatMatch parameter.
     *
     * <ul>
     *   <li>chatMatch is optional (defaults to ANY in the domain record).</li>
     *   <li>If provided, must be an object with a known {@code type}.</li>
     *   <li>For non-ANY types, {@code value} is required.</li>
     *   <li>For regex, {@code value} must be a compilable {@link java.util.regex.Pattern}.</li>
     * </ul>
     */
    @SuppressWarnings("unchecked")
    private String validateChatMatch(Map<String, Object> parameters) {
        Object raw = parameters.get("chatMatch");
        if (raw == null) return null; // Optional - defaults to ANY.

        if (!(raw instanceof Map<?, ?> map)) {
            return "chatMatch must be an object. Example: chatMatch={type: 'starts_with', value: '/help', caseSensitive: false}. " +
                "Allowed types: any, starts_with, ends_with, contains, equals, regex.";
        }

        Object typeObj = map.get("type");
        String typeStr = typeObj == null ? "any" : typeObj.toString().trim().toLowerCase(Locale.ROOT);
        if (!VALID_CHAT_MATCH_TYPES.contains(typeStr)) {
            return "Invalid chatMatch.type '" + typeObj + "'. Allowed: any, starts_with, ends_with, contains, equals, regex. " +
                "(Frontend aliases 'startsWith' / 'endsWith' are also accepted.)";
        }

        boolean isAny = "any".equals(typeStr);
        Object valueObj = map.get("value");
        String value = valueObj == null ? null : valueObj.toString();
        if (!isAny && (value == null || value.isEmpty())) {
            return "chatMatch.value is required for type '" + typeStr + "'. " +
                "Only type='any' takes no value. Example: chatMatch={type: 'starts_with', value: '/help'}.";
        }

        if ("regex".equals(typeStr)) {
            try {
                java.util.regex.Pattern.compile(value);
            } catch (java.util.regex.PatternSyntaxException e) {
                return "Invalid regex pattern '" + value + "' in chatMatch.value: " + e.getDescription() + ".";
            }
        }
        return null;
    }

    private static final Set<String> VALID_EVENT_TYPES =
        Set.of("row_created", "row_updated", "row_deleted");

    /**
     * Accepted wire forms for the filter operator. The standard form is {@code =}; the
     * frontend displays {@code ==} in the UI dropdown (a single {@code =} reads as an
     * assignment) but persists {@code =}. {@code ==} is accepted too so agent/API
     * callers mirroring the UI label don't get rejected. {@link
     * com.apimarketplace.trigger.service.DatasourceFilterEvaluator} treats them as
     * equivalent at match time.
     */
    private static final Set<String> VALID_FILTER_OPERATORS =
        Set.of("=", "==", "!=", ">", ">=", "<", "<=", "in", "not_in", "contains", "starts_with", "ends_with", "is_null", "is_not_null");

    /**
     * Validate datasource trigger event_types + filter shape.
     * Unknown event_type values or a malformed filter return a human-readable error string.
     *
     * @return error message, or null if valid
     */
    @SuppressWarnings("unchecked")
    private String validateDatasourceTriggerParams(Map<String, Object> parameters) {
        Object rawEvents = parameters.get("event_types");
        if (rawEvents == null) rawEvents = parameters.get("eventTypes");
        if (rawEvents != null) {
            List<String> asList = new ArrayList<>();
            if (rawEvents instanceof List<?> list) {
                for (Object o : list) if (o != null) asList.add(o.toString());
            } else if (rawEvents instanceof String s) {
                for (String part : s.split(",")) asList.add(part);
            } else {
                asList.add(rawEvents.toString());
            }
            for (String v : asList) {
                String normalized = v == null ? "" : v.trim().toLowerCase();
                if (!VALID_EVENT_TYPES.contains(normalized)) {
                    return "Invalid event_type '" + v + "' for datasource trigger. " +
                        "Allowed: row_created, row_updated, row_deleted. " +
                        "Omit the field to subscribe to all three.";
                }
            }
        }

        Object rawFilter = parameters.get("filter");
        if (rawFilter != null) {
            if (!(rawFilter instanceof Map<?, ?> filterMap)) {
                return "filter must be an object with shape {column: <name>, operator: <op>, value: <...>}. " +
                    "Omit the field to fire on every row change.";
            }
            Object column = filterMap.get("column");
            Object operator = filterMap.get("operator");
            if (!(column instanceof String c) || c.isBlank()) {
                return "filter.column is required (string). " +
                    "Example: filter={column: 'status', operator: '=', value: 'active'}";
            }
            if (!(operator instanceof String op) || op.isBlank()) {
                return "filter.operator is required. Allowed: " + String.join(", ", VALID_FILTER_OPERATORS);
            }
            String normalizedOp = op.trim().toLowerCase();
            if (!VALID_FILTER_OPERATORS.contains(normalizedOp)) {
                return "Invalid filter.operator '" + op + "'. Allowed: " + String.join(", ", VALID_FILTER_OPERATORS);
            }
            boolean nullOp = "is_null".equals(normalizedOp) || "is_not_null".equals(normalizedOp);
            if (!nullOp && !filterMap.containsKey("value")) {
                return "filter.value is required for operator '" + op + "'. " +
                    "Only is_null / is_not_null take no value.";
            }
        }
        return null;
    }

    /**
     * Normalize event_types input to a deduplicated list of valid event names.
     * Accepts List, array, comma-separated String. Unknown values are dropped.
     * Returns the full default set if input is null/empty.
     */
    @SuppressWarnings("unchecked")
    private List<String> normalizeEventTypes(Object raw) {
        List<String> defaults = List.of("row_created", "row_updated", "row_deleted");
        if (raw == null) return defaults;

        List<String> parsed = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object o : list) if (o != null) parsed.add(o.toString());
        } else if (raw instanceof String s) {
            for (String part : s.split(",")) parsed.add(part);
        } else {
            parsed.add(raw.toString());
        }

        List<String> result = new ArrayList<>();
        for (String v : parsed) {
            String normalized = v == null ? "" : v.trim().toLowerCase();
            if (VALID_EVENT_TYPES.contains(normalized) && !result.contains(normalized)) {
                result.add(normalized);
            }
        }
        return result.isEmpty() ? defaults : result;
    }

    /**
     * Build trigger schema based on type.
     */
    @SuppressWarnings("unchecked")
    private void buildTriggerSchema(String nodeId, String type, String datasourceId,
                                    Map<String, Object> inputSchema,
                                    List<Map<String, Object>> formFields,
                                    Map<String, String> outputs, Map<String, String> referenceSyntax,
                                    String tenantId) {
        if (inputSchema != null && !inputSchema.isEmpty()) {
            for (String field : inputSchema.keySet()) {
                outputs.put(field, String.valueOf(inputSchema.get(field)));
                referenceSyntax.put(field, "{{" + nodeId + ".output." + field + "}}");
            }
        } else if ("form".equals(type)) {
            // Show individual form field outputs when fields are defined
            if (formFields != null && !formFields.isEmpty()) {
                for (Map<String, Object> field : formFields) {
                    String fieldName = field.get("name") instanceof String s ? s : null;
                    if (fieldName != null && !fieldName.isBlank()) {
                        String fieldType = field.get("type") instanceof String s ? s : "string";
                        outputs.put(fieldName, fieldType);
                        referenceSyntax.put(fieldName, "{{" + nodeId + ".output." + fieldName + "}}");
                    }
                }
            } else {
                // No fields defined yet - show generic formData
                outputs.put("formData", "object");
                referenceSyntax.put("formData", "{{" + nodeId + ".output.formData}}");
            }
            outputs.put("submittedAt", "string");
            referenceSyntax.put("submittedAt", "{{" + nodeId + ".output.submittedAt}}");
        } else if ("workflow".equals(type)) {
            // Actual fields from WorkflowTriggerOutputSchemaMapper:
            // - triggeredAt: ISO timestamp
            // - {parentOutputs}: dynamic fields flattened from parent workflow's terminal node outputs
            outputs.put("triggeredAt", "string");
            referenceSyntax.put("triggeredAt", "{{" + nodeId + ".output.triggeredAt}}");
            outputs.put("{parentOutputs}", "dynamic (parent workflow outputs flattened to root level)");
        } else if ("error".equals(type)) {
            // Fields emitted by ErrorTriggerDispatchService.buildErrorPayload - kept in
            // sync with the seeded node_type_documentation row for type='error'.
            outputs.put("parentWorkflowId", "string (UUID of the parent workflow that failed)");
            referenceSyntax.put("parentWorkflowId", "{{" + nodeId + ".output.parentWorkflowId}}");
            outputs.put("parentRunId", "string (run id of the parent execution that failed)");
            referenceSyntax.put("parentRunId", "{{" + nodeId + ".output.parentRunId}}");
            outputs.put("status", "string ('FAILED' or 'PARTIAL_SUCCESS')");
            referenceSyntax.put("status", "{{" + nodeId + ".output.status}}");
            outputs.put("errorMessage", "string (the failure message)");
            referenceSyntax.put("errorMessage", "{{" + nodeId + ".output.errorMessage}}");
            outputs.put("triggeredAt", "string (ISO timestamp when this handler fired)");
            referenceSyntax.put("triggeredAt", "{{" + nodeId + ".output.triggeredAt}}");
            outputs.put("failedSteps", "number");
            referenceSyntax.put("failedSteps", "{{" + nodeId + ".output.failedSteps}}");
            outputs.put("completedSteps", "number");
            referenceSyntax.put("completedSteps", "{{" + nodeId + ".output.completedSteps}}");
            outputs.put("totalSteps", "number");
            referenceSyntax.put("totalSteps", "{{" + nodeId + ".output.totalSteps}}");
            outputs.put("skippedSteps", "number");
            referenceSyntax.put("skippedSteps", "{{" + nodeId + ".output.skippedSteps}}");
        } else if (datasourceId != null && "datasource".equals(type)) {
            try {
                Long dsId = Long.parseLong(datasourceId);
                DataSourceDto ds = dataSourceClient.getDataSource(dsId, tenantId);
                if (ds != null) {
                    // Stable event-meta paths - same on every fire, no collision risk:
                    outputs.put("event_type", "string ('row_created'|'row_updated'|'row_deleted')");
                    referenceSyntax.put("event_type", "{{" + nodeId + ".output.event_type}}");
                    outputs.put("row_id", "number (PK of the row that fired)");
                    referenceSyntax.put("row_id", "{{" + nodeId + ".output.row_id}}");
                    outputs.put("row", "object (the full current row - use .row.<col> to read columns)");
                    referenceSyntax.put("row", "{{" + nodeId + ".output.row}}");
                    outputs.put("previous_row", "object|null (only on row_updated, null otherwise)");
                    referenceSyntax.put("previous_row", "{{" + nodeId + ".output.previous_row}}");

                    // Per-column refs use the SAFE nested path output.row.<col>:
                    // top-level flatten exists too but collides on reserved names
                    // (status, count, data, source, ...) and is silently shadowed.
                    Map<String, ColumnMappingSpecDto> mappingSpec = ds.mappingSpec();
                    if (mappingSpec != null) {
                        for (Map.Entry<String, ColumnMappingSpecDto> entry : mappingSpec.entrySet()) {
                            String field = entry.getKey();
                            String fieldType = entry.getValue().type() != null
                                ? entry.getValue().type().name().toLowerCase() : "text";
                            outputs.put("row." + field, fieldType);
                            referenceSyntax.put("row." + field, "{{" + nodeId + ".output.row." + field + "}}");
                        }
                    }

                    // Batch-scan testing path (workflow(action='execute') without a real row event):
                    // emits {data:[{id, data:{...columns}}, ...], count, hasMore, ...} like find_rows.
                    // Chain core:split with input={{...output.data}} for per-row processing.
                    outputs.put("data", "array (batch-scan only) - list of {id, data:{...columns}} like find_rows.items");
                    referenceSyntax.put("data", "{{" + nodeId + ".output.data}}");
                    outputs.put("count", "number (batch-scan only) - total rows in this scan");
                    referenceSyntax.put("count", "{{" + nodeId + ".output.count}}");
                }
            } catch (NumberFormatException e) {
                log.warn("Invalid datasource_id format: {}", datasourceId);
            }
        }
    }

    /**
     * Build error when table_id is missing.
     */
    private ToolExecutionResult buildTableIdRequiredError(String tenantId) {
        List<DataSourceDto> tables = dataSourceClient.getDataSourcesByTenant(tenantId);

        Map<String, Object> error = new LinkedHashMap<>();
        error.put("status", "ERROR");
        error.put("error", "table_id is required for table triggers");

        if (tables.isEmpty()) {
            error.put("available_tables", List.of());
            error.put("hint", "No tables exist. Create one: table(action='create', name='my_table', columns=[...])");
        } else {
            error.put("available_tables", tables.stream()
                .map(t -> Map.of("id", t.id(), "name", t.name()))
                .toList());
            error.put("hint", "Use: workflow(action='add_node', type='table', label='...', params={table_id: <id>})");
        }

        return ToolExecutionResult.success(error);
    }

    // ==================== Form Field Validation & Normalization ====================

    /**
     * Valid form field types, normalized to lowercase. Validation lowercases
     * the incoming {@code field.type} before checking against this set, so
     * agents can submit either camelCase ({@code checkboxGroup}) or all-lower
     * ({@code checkboxgroup}) - both are accepted. The original case is
     * preserved when persisted; the frontend FieldType enum keys on the
     * camelCase form.
     */
    private static final Set<String> VALID_FIELD_TYPES = Set.of(
        "text", "email", "password", "number", "textarea", "select", "multiselect",
        "checkbox", "checkboxgroup", "radio", "date", "datetime", "time",
        "file", "url", "tel", "hidden"
    );

    private static final Map<String, String> FIELD_TYPE_ALIASES = Map.of(
        "string", "text",
        "str", "text",
        "int", "number",
        "integer", "number",
        "bool", "checkbox",
        "boolean", "checkbox",
        "phone", "tel"
    );

    /**
     * Field types whose {@code options} array must be coerced to the canonical
     * {@code [{id, label, value}]} shape. Lowercased to match the resolved
     * type post {@link #FIELD_TYPE_ALIASES} application.
     */
    private static final Set<String> OPTION_BEARING_TYPES = Set.of(
        "select", "multiselect", "radio", "checkboxgroup"
    );

    /**
     * Validate form fields structure AND coerce loose shapes (string-array
     * options shorthand, missing field/option ids) into the canonical builder
     * shape so the persisted plan, the inspector, and the public form
     * renderer all read the same objects.
     *
     * <p>Replaces {@code parameters.get("fields")} with a fully-mutable,
     * canonical list. Callers must NOT cache the pre-call reference. We take
     * the {@code parameters} Map (rather than the field list directly)
     * because both the input list AND its inner field maps may be immutable
     * ({@code List.of(Map.of(...))} from JSON deserialization or test
     * fixtures); rebuilding upfront avoids scattering immutability checks.</p>
     *
     * <p>Returns an error message or {@code null} if valid.</p>
     */
    @SuppressWarnings("unchecked")
    private String validateFormFields(Map<String, Object> parameters) {
        Object rawFields = parameters.get("fields");
        if (!(rawFields instanceof List<?> rawList) || rawList.isEmpty()) {
            return null; // fields are optional
        }

        // Build a mutable, canonical list up-front. Inner field maps may also
        // be immutable (Map.of(...) in tests / deser), so copy each one too.
        List<Map<String, Object>> fields = new ArrayList<>(rawList.size());
        for (Object raw : rawList) {
            if (raw instanceof Map<?, ?> m) {
                fields.add(new LinkedHashMap<>((Map<String, Object>) m));
            } else {
                // Non-map entries land here; the per-field validation below
                // will catch them via the missing-name check.
                fields.add(new LinkedHashMap<>());
            }
        }
        parameters.put("fields", fields);

        Set<String> seenNames = new HashSet<>();
        List<String> errors = new ArrayList<>();

        for (int i = 0; i < fields.size(); i++) {
            Map<String, Object> field = fields.get(i);

            // name is required
            Object nameObj = field.get("name");
            if (!(nameObj instanceof String name) || name.isBlank()) {
                errors.add("field[" + i + "]: 'name' is required");
                continue;
            }

            // no duplicate names
            if (!seenNames.add(name)) {
                errors.add("field '" + name + "': duplicate name");
            }

            // type must be valid (after alias resolution)
            String resolvedType = null;
            Object typeObj = field.get("type");
            if (typeObj instanceof String fieldType) {
                String lower = fieldType.toLowerCase();
                if (!VALID_FIELD_TYPES.contains(lower) && !FIELD_TYPE_ALIASES.containsKey(lower)) {
                    errors.add("field '" + name + "': type '" + fieldType + "' is invalid. " +
                        "Valid: text, email, number, textarea, select, checkbox, date, datetime, time, " +
                        "file, url, tel, password, radio, multiselect, checkboxGroup, hidden");
                } else {
                    resolvedType = FIELD_TYPE_ALIASES.getOrDefault(lower, lower);
                }
            }

            // Stable id (the inspector keys React lists on field.id; without
            // one, edits collapse onto a single sibling). Auto-fill so LLM
            // callers don't have to know about it.
            Object idObj = field.get("id");
            if (!(idObj instanceof String idStr) || idStr.isBlank()) {
                field.put("id", "field-" + i);
            }

            // Coerce options shape for select/multiselect/radio/checkboxGroup.
            // Accept the string shorthand (["a", "b"]) and the canonical
            // [{label, value}] form. Reject anything else explicitly so the
            // agent gets a useful error instead of a silently-empty UI.
            if (resolvedType != null && OPTION_BEARING_TYPES.contains(resolvedType)) {
                String optionsError = coerceFieldOptions(field, name);
                if (optionsError != null) {
                    errors.add(optionsError);
                }
            }
        }

        if (errors.isEmpty()) {
            return null;
        }

        return "Form field validation failed:\n  - " + String.join("\n  - ", errors) +
            "\n\nExample: fields: [{name: 'email', type: 'email', label: 'Email', required: true}, " +
            "{name: 'tier', type: 'select', label: 'Tier', required: true, " +
            "options: [{label: 'Free', value: 'free'}, {label: 'Pro', value: 'pro'}]}]";
    }

    /**
     * Coerce {@code field.options} for select-like fields into the canonical
     * {@code [{id, label, value}]} shape and mutate the field map in place.
     *
     * <p>Accepts:
     * <ul>
     *   <li>String shorthand: {@code options: ["a", "b"]} →
     *       {@code [{id:"opt-0", label:"a", value:"a"}, ...]}</li>
     *   <li>Object form: {@code [{label, value}]} (and {@code id} when present)</li>
     * </ul>
     * Rejects mixed-shape arrays with empty {@code label} or {@code value} so
     * the LLM gets a clear error instead of a UI that silently drops options.</p>
     *
     * @return an error message string for {@code validateFormFields} to surface,
     *         or {@code null} when the field is valid (and now canonical).
     */
    private String coerceFieldOptions(Map<String, Object> field, String fieldName) {
        Object opts = field.get("options");
        if (opts == null) {
            return "field '" + fieldName + "' is select/multiselect/radio/checkboxGroup but has no 'options'. " +
                "Provide an array - strings or {label, value} objects both accepted.";
        }
        if (!(opts instanceof List<?> rawList)) {
            return "field '" + fieldName + "': 'options' must be an array, got " + opts.getClass().getSimpleName();
        }
        if (rawList.isEmpty()) {
            return "field '" + fieldName + "': 'options' is empty - provide at least one option.";
        }

        List<Map<String, Object>> coerced = new ArrayList<>(rawList.size());
        for (int j = 0; j < rawList.size(); j++) {
            Object item = rawList.get(j);
            if (item instanceof String s) {
                if (s.isBlank()) {
                    return "field '" + fieldName + "': options[" + j + "] is an empty string.";
                }
                Map<String, Object> normalized = new LinkedHashMap<>();
                normalized.put("id", "opt-" + j);
                normalized.put("label", s);
                normalized.put("value", s);
                coerced.add(normalized);
            } else if (item instanceof Map<?, ?> rawMap) {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) rawMap;
                Object label = m.get("label");
                Object value = m.get("value");
                if (!(label instanceof String labelStr) || labelStr.isBlank()) {
                    return "field '" + fieldName + "': options[" + j + "] is missing a non-empty 'label'.";
                }
                if (!(value instanceof String valueStr) || valueStr.isBlank()) {
                    return "field '" + fieldName + "': options[" + j + "] is missing a non-empty 'value'.";
                }
                Map<String, Object> normalized = new LinkedHashMap<>();
                Object existingId = m.get("id");
                normalized.put("id",
                    (existingId instanceof String idStr && !idStr.isBlank()) ? idStr : "opt-" + j);
                normalized.put("label", labelStr);
                normalized.put("value", valueStr);
                coerced.add(normalized);
            } else {
                return "field '" + fieldName + "': options[" + j + "] must be a string or {label, value} object, " +
                    "got " + (item == null ? "null" : item.getClass().getSimpleName());
            }
        }

        field.put("options", coerced);
        return null;
    }

    /**
     * Normalize form field types: auto-correct known aliases like "string" → "text".
     */
    private List<Map<String, Object>> normalizeFormFieldTypes(List<Map<String, Object>> fields) {
        List<Map<String, Object>> normalized = new ArrayList<>();
        for (Map<String, Object> field : fields) {
            Map<String, Object> f = new LinkedHashMap<>(field);
            Object typeObj = f.get("type");
            if (typeObj instanceof String fieldType) {
                String lower = fieldType.toLowerCase();
                if (!VALID_FIELD_TYPES.contains(lower)) {
                    String corrected = FIELD_TYPE_ALIASES.getOrDefault(lower, "text");
                    f.put("type", corrected);
                }
            } else {
                f.put("type", "text");
            }
            normalized.add(f);
        }
        return normalized;
    }

    // ==================== Utility Methods ====================

    // getString inherited from CreatorBase

    /**
     * Get map from parameters.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> getMap(Map<String, Object> params, String key) {
        Object value = params.get(key);
        return value instanceof Map ? (Map<String, Object>) value : null;
    }

    /**
     * Convert to int safely.
     */
    private Integer toInt(Object value) {
        if (value instanceof Number n) return n.intValue();
        if (value instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {}
        }
        return null;
    }
}
