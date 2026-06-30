package com.apimarketplace.trigger.client.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record StandaloneChatEndpointRequest(
        @NotBlank @Size(max = 255) String name,
        @Size(max = 1000) String description,
        String workflowId,
        String workflowName,
        String welcomeMessage,
        String model,
        String provider,
        Boolean memoryEnabled,
        String sourceNodeId,
        String triggerId
) {
}
