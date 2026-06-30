package com.apimarketplace.trigger.client.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * Request DTO for creating/updating standalone webhooks.
 */
public record StandaloneWebhookRequest(
    @NotBlank(message = "Name is required")
    @Size(max = 255, message = "Name must not exceed 255 characters")
    String name,

    @Size(max = 1000, message = "Description must not exceed 1000 characters")
    String description,

    String httpMethod,
    String authType,
    Map<String, String> authConfig,
    String workflowId,
    String workflowName,
    String sourceNodeId
) {
    public StandaloneWebhookRequest {
        if (httpMethod == null || httpMethod.isBlank()) httpMethod = "POST";
        if (authType == null || authType.isBlank()) authType = "none";
    }
}
