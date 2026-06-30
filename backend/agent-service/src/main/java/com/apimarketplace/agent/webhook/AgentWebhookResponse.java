package com.apimarketplace.agent.webhook;

import java.util.Map;

/**
 * Response object for agent webhook calls.
 */
public record AgentWebhookResponse(
    String status,
    String message,
    String conversationId,
    String agentId,
    String agentName,
    Object response,
    Map<String, Object> metadata
) {
    /**
     * Status values:
     * - success: Agent responded successfully
     * - accepted: Request accepted, processing async
     * - error: Error during processing
     * - unauthorized: Authentication failed
     * - not_found: Webhook token not found
     * - inactive: Webhook is disabled
     * - method_not_allowed: HTTP method not allowed
     */

    public static AgentWebhookResponse success(String conversationId, String agentId, String agentName, Object response) {
        return new AgentWebhookResponse("success", "Agent responded successfully", conversationId, agentId, agentName, response, null);
    }

    public static AgentWebhookResponse accepted(String conversationId, String agentId, String agentName) {
        return new AgentWebhookResponse("accepted", "Request accepted, processing", conversationId, agentId, agentName, null, null);
    }

    public static AgentWebhookResponse error(String message) {
        return new AgentWebhookResponse("error", message, null, null, null, null, null);
    }

    public static AgentWebhookResponse unauthorized(String message) {
        return new AgentWebhookResponse("unauthorized", message, null, null, null, null, null);
    }

    public static AgentWebhookResponse notFound() {
        return new AgentWebhookResponse("not_found", "Webhook not found", null, null, null, null, null);
    }

    public static AgentWebhookResponse inactive() {
        return new AgentWebhookResponse("inactive", "Webhook is disabled", null, null, null, null, null);
    }

    public static AgentWebhookResponse methodNotAllowed(String allowedMethod) {
        return new AgentWebhookResponse("method_not_allowed", "Method not allowed. Expected: " + allowedMethod, null, null, null, null, null);
    }
}
