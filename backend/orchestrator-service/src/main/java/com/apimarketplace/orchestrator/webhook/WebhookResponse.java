package com.apimarketplace.orchestrator.webhook;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Response DTO for webhook invocations.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WebhookResponse(
    String executionId,
    String status,
    String message,
    Object result
) {
    /**
     * Create an accepted (async) response.
     */
    public static WebhookResponse accepted(String executionId) {
        return new WebhookResponse(executionId, "accepted", "Workflow execution started", null);
    }

    /**
     * Create a completed (sync) response with result.
     */
    public static WebhookResponse completed(String executionId, Object result) {
        return new WebhookResponse(executionId, "completed", "Workflow execution completed", result);
    }

    /**
     * Create an error response.
     */
    public static WebhookResponse error(String message) {
        return new WebhookResponse(null, "error", message, null);
    }

    /**
     * Create a not found response.
     */
    public static WebhookResponse notFound() {
        return new WebhookResponse(null, "not_found", "Webhook not found or disabled", null);
    }

    /**
     * Create a not active response (no run waiting for trigger).
     */
    public static WebhookResponse notActive() {
        return new WebhookResponse(null, "not_active", "Webhook not active - no run waiting for trigger", null);
    }

    /**
     * Create an insufficient credits response.
     */
    public static WebhookResponse insufficientCredits() {
        return new WebhookResponse(null, "insufficient_credits",
                "Insufficient credits to execute this webhook. Please upgrade your plan at /app/settings/pricing", null);
    }

    /**
     * Create a rate limited response.
     */
    public static WebhookResponse rateLimited() {
        return new WebhookResponse(null, "rate_limited", "Too many webhook calls - please wait", null);
    }

    /**
     * Create a triggered response (run resumed from WAITING_TRIGGER).
     */
    public static WebhookResponse triggered(String executionId) {
        return new WebhookResponse(executionId, "triggered", "Workflow run triggered successfully", null);
    }
}
