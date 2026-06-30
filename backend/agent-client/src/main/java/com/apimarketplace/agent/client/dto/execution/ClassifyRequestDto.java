package com.apimarketplace.agent.client.dto.execution;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * DTO for requesting classification execution on agent-service.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ClassifyRequestDto(
    String content,
    String prompt,
    List<CategoryDto> categories,
    String provider,
    String model,
    Double temperature,
    Integer maxTokens,
    String tenantId,
    String agentEntityId
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CategoryDto(
        String label,
        String description
    ) {}
}
