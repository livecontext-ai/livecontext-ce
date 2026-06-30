package com.apimarketplace.orchestrator.services.agent;

import com.apimarketplace.agent.client.dto.execution.ConversationMessageDto;

import java.util.List;

/**
 * Result of classification execution.
 */
public record ClassifyResult(
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
) {
    public static ClassifyResult success(String category, double confidence, String reasoning,
                                         long duration, String provider, String model,
                                         int tokens, int promptTokens, int completionTokens,
                                         String systemPrompt, List<ConversationMessageDto> messages,
                                         String userPrompt) {
        return new ClassifyResult(true, category, confidence, reasoning, null,
            duration, provider, model, tokens, promptTokens, completionTokens,
            systemPrompt, messages, userPrompt);
    }

    public static ClassifyResult failure(String error, long duration, String provider) {
        return new ClassifyResult(false, null, 0, null, error, duration, provider, null, 0, 0, 0,
            null, null, null);
    }
}
