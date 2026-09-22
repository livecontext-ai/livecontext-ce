package com.apimarketplace.orchestrator.services.agent;

import com.apimarketplace.agent.client.dto.execution.ConversationMessageDto;
import com.apimarketplace.agent.domain.UsageInfo;

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
    String userPrompt,

    /**
     * Cache and reasoning counters, or {@code null} when the provider reported none.
     * Mirrors the field of the same name on the response DTO; appended rather than
     * inserted, with the previous arity kept below so no existing caller changes.
     * See {@link ClassifyResult#cacheUsage()} for what carrying it repairs.
     */
    UsageInfo cacheUsage,

    /** Whose key the call ran on ({@code PLATFORM} / {@code OWN_KEY}), {@code null} when unpinned. */
    String keyRoute
) {
    public static GuardrailResult success(boolean passed, List<String> violations,
                                          Map<String, Object> details, String sanitized,
                                          long duration, String provider, String model,
                                          int tokens, int promptTokens, int completionTokens,
                                          String systemPrompt, List<ConversationMessageDto> messages,
                                          String userPrompt) {
        return success(passed, violations, details, sanitized, duration, provider, model,
            tokens, promptTokens, completionTokens, systemPrompt, messages, userPrompt, null);
    }

    /** With the cache counters the node is billed on. */
    public static GuardrailResult success(boolean passed, List<String> violations,
                                          Map<String, Object> details, String sanitized,
                                          long duration, String provider, String model,
                                          int tokens, int promptTokens, int completionTokens,
                                          String systemPrompt, List<ConversationMessageDto> messages,
                                          String userPrompt, UsageInfo cacheUsage) {
        return success(passed, violations, details, sanitized, duration, provider, model,
            tokens, promptTokens, completionTokens, systemPrompt, messages, userPrompt, cacheUsage, null);
    }

    /** With the cache counters and the key route the node is billed on. */
    public static GuardrailResult success(boolean passed, List<String> violations,
                                          Map<String, Object> details, String sanitized,
                                          long duration, String provider, String model,
                                          int tokens, int promptTokens, int completionTokens,
                                          String systemPrompt, List<ConversationMessageDto> messages,
                                          String userPrompt, UsageInfo cacheUsage, String keyRoute) {
        return new GuardrailResult(true, passed, violations, details, sanitized, null,
            duration, provider, model, tokens, promptTokens, completionTokens,
            systemPrompt, messages, userPrompt, cacheUsage, keyRoute);
    }

    public static GuardrailResult failure(String error, long duration, String provider) {
        return new GuardrailResult(false, false, List.of(), Map.of(), null, error,
            duration, provider, null, 0, 0, 0, null, null, null, null, null);
    }
}
