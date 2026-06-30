package com.apimarketplace.trigger.client.dto;

/**
 * Request DTO for updating workflow reference on a standalone webhook.
 */
public record WorkflowReferenceRequest(
    String workflowId,
    String workflowName,
    String triggerId
) {}
