package com.apimarketplace.trigger.client.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

public record StandaloneFormEndpointRequest(
        @NotBlank @Size(max = 255) String name,
        @Size(max = 1000) String description,
        String workflowId,
        String workflowName,
        List<Map<String, Object>> formConfig,
        String successMessage,
        String sourceNodeId,
        String triggerId
) {
}
