package com.apimarketplace.conversation.controller.internal;

import com.apimarketplace.agent.domain.ToolCall;
import com.apimarketplace.agent.domain.ToolDefinition;
import com.apimarketplace.agent.domain.ToolResult;
import com.apimarketplace.conversation.service.ai.ConversationToolExecutionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * Internal endpoint for agent-service to execute conversation-specific tools.
 * When conversation-service dispatches agent execution to agent-service (Phase 6),
 * agent-service calls back here for tools that require conversation DB access:
 * - set_conversation_title
 * - get_tool_result
 * - request_credential
 */
@Slf4j
@RestController
@RequestMapping("/api/internal/conversation")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "deployment.mode", havingValue = "microservice", matchIfMissing = true)
public class ConversationToolExecutionController {

    private final ConversationToolExecutionService toolExecutionService;

    /**
     * Execute a conversation-specific tool.
     * Called by agent-service's RemoteToolExecutionService when __toolCallbackUrl__ is set.
     */
    @SuppressWarnings("unchecked")
    @PostMapping("/tools/execute")
    public ResponseEntity<Map<String, Object>> executeTool(@RequestBody Map<String, Object> request) {
        String toolName = (String) request.get("tool");
        String toolCallId = (String) request.get("toolCallId");
        String tenantId = (String) request.get("tenantId");
        Map<String, Object> parameters = (Map<String, Object>) request.get("parameters");

        log.info("Received tool execution callback from agent-service: tool={}, tenantId={}", toolName, tenantId);

        // Build ToolCall from request
        ToolCall toolCall = ToolCall.builder()
            .id(toolCallId)
            .toolName(toolName)
            .arguments(parameters != null ? parameters : Map.of())
            .build();

        // Build minimal ToolDefinition (conversation tools don't need apiSlug/toolSlug)
        ToolDefinition toolDefinition = ToolDefinition.builder()
            .name(toolName)
            .build();

        // Build credentials map from forwarded fields
        Map<String, Object> credentials = buildCredentials(request);

        // Execute tool via existing service
        ToolResult result = toolExecutionService.executeTool(toolCall, toolDefinition, tenantId, credentials);

        // Convert to response map
        Map<String, Object> response = new HashMap<>();
        response.put("success", result.success());
        if (result.content() != null) {
            response.put("result", result.content());
        }
        if (result.error() != null) {
            response.put("error", result.error());
        }
        if (result.metadata() != null && !result.metadata().isEmpty()) {
            response.put("metadata", result.metadata());
        }

        return ResponseEntity.ok(response);
    }

    /**
     * Reconstruct credentials map from forwarded request fields.
     * Agent-service forwards conversationId, turnId, and __-prefixed credential keys.
     */
    private Map<String, Object> buildCredentials(Map<String, Object> request) {
        Map<String, Object> credentials = new HashMap<>();

        // Direct fields
        if (request.get("conversationId") != null) {
            credentials.put("conversationId", request.get("conversationId"));
        }
        if (request.get("turnId") != null) {
            credentials.put("turnId", request.get("turnId"));
        }

        // Reconstruct __-prefixed credential keys from forwarded fields
        Map<String, String> reverseMapping = Map.ofEntries(
            Map.entry("agentId", "__agentId__"),
            Map.entry("allowedToolIds", "__allowedToolIds__"),
            Map.entry("allowedWorkflowIds", "__allowedWorkflowIds__"),
            Map.entry("allowedApplicationIds", "__allowedApplicationIds__"),
            Map.entry("allowedTableIds", "__allowedTableIds__"),
            Map.entry("allowedInterfaceIds", "__allowedInterfaceIds__"),
            Map.entry("allowedAgentIds", "__allowedAgentIds__"),
            Map.entry("allowedFileIds", "__allowedFileIds__"),
            Map.entry("approvedServices", "__approvedServices__"),
            Map.entry("orgId", "__orgId__"),
            Map.entry("orgRole", "__orgRole__"),
            Map.entry("viewingWorkflowId", "__viewingWorkflowId__"),
            Map.entry("viewingWorkflowName", "__viewingWorkflowName__"),
            Map.entry("streamId", "__streamId__")
        );

        for (Map.Entry<String, String> entry : reverseMapping.entrySet()) {
            Object value = request.get(entry.getKey());
            if (value != null) {
                credentials.put(entry.getValue(), value);
            }
        }

        return credentials;
    }
}
