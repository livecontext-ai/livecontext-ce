package com.apimarketplace.agent.client.dto.execution;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * DTO for classification execution response from agent-service.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ClassifyResponseDto(
    boolean success,
    String selectedCategory,
    double confidence,
    String reasoning,
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
