package com.apimarketplace.orchestrator.services.agent;

import com.apimarketplace.agent.client.dto.execution.ConversationMessageDto;

import java.util.List;
import java.util.Map;

/**
 * Result of guardrail validation.
 */
public record GuardrailResult(
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
) {
    public static GuardrailResult success(boolean passed, List<String> violations,
                                          Map<String, Object> details, String sanitized,
                                          long duration, String provider, String model,
                                          int tokens, int promptTokens, int completionTokens,
                                          String systemPrompt, List<ConversationMessageDto> messages,
                                          String userPrompt) {
        return new GuardrailResult(true, passed, violations, details, sanitized, null,
            duration, provider, model, tokens, promptTokens, completionTokens,
            systemPrompt, messages, userPrompt);
    }

    public static GuardrailResult failure(String error, long duration, String provider) {
        return new GuardrailResult(false, false, List.of(), Map.of(), null, error,
            duration, provider, null, 0, 0, 0, null, null, null);
    }
}
