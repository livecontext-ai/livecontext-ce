package com.apimarketplace.trigger.client.dto;

import java.util.Map;
import java.util.UUID;

/**
 * Request DTO for logging a webhook call.
 */
public record WebhookCallLogRequest(
    UUID webhookId,
    String requestMethod,
    Map<String, Object> requestHeaders,
    Map<String, Object> requestPayload,
    String responseStatus,
    int workflowsTriggered
) {}
