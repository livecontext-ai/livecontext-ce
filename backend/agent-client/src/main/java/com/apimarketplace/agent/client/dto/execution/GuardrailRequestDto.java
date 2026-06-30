package com.apimarketplace.agent.client.dto.execution;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * DTO for requesting guardrail validation on agent-service.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GuardrailRequestDto(
    String content,
    String prompt,
    List<RuleDto> rules,
    String action,
    String provider,
    String model,
    Double temperature,
    Integer maxTokens,
    String tenantId,
    String agentEntityId
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RuleDto(
        String id,
        String description
    ) {}
}
