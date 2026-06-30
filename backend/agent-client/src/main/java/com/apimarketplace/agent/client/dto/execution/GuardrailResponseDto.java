package com.apimarketplace.agent.client.dto.execution;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * DTO for guardrail validation response from agent-service.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GuardrailResponseDto(
    boolean success,
    boolean passed,
    List<String> violations,
    Map<String, Object> details,
    String sanitized,
    String error,
    long durationMs,
    String provider,
    String model,
    int tokensUsed,
    int promptTokens,
    int completionTokens,
    String systemPrompt,
    List<ConversationMessageDto> conversationMessages,
    String userPrompt
) {}
