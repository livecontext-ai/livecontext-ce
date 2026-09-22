package com.apimarketplace.agent.client.dto.execution;

import com.apimarketplace.agent.domain.UsageInfo;
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
    String userPrompt,

    /**
     * Cache and reasoning counters, or {@code null} when the provider reported none.
     * Appended rather than inserted, with the previous arity kept below so every
     * existing caller compiles untouched. See {@link ClassifyResponseDto#cacheUsage()}
     * for what carrying it repairs - guardrail is the same node shape and had the same
     * defect.
     */
    UsageInfo cacheUsage,

    /**
     * Whose API key the call ran on ({@code PLATFORM} / {@code OWN_KEY}), or {@code null}
     * when unpinned. See {@link ClassifyResponseDto#keyRoute()}.
     */
    String keyRoute
) {
    /** Previous arity: cache counters, no key route. */
    public GuardrailResponseDto(boolean success, boolean passed, List<String> violations,
                                Map<String, Object> details, String sanitized, String error,
                                long durationMs, String provider, String model, int tokensUsed,
                                int promptTokens, int completionTokens, String systemPrompt,
                                List<ConversationMessageDto> conversationMessages, String userPrompt,
                                UsageInfo cacheUsage) {
        this(success, passed, violations, details, sanitized, error, durationMs, provider, model,
                tokensUsed, promptTokens, completionTokens, systemPrompt, conversationMessages,
                userPrompt, cacheUsage, null);
    }

    /** The same response, pinned to the key route the call actually ran on. */
    public GuardrailResponseDto withKeyRoute(String keyRoute) {
        return new GuardrailResponseDto(success, passed, violations, details, sanitized, error,
                durationMs, provider, model, tokensUsed, promptTokens, completionTokens,
                systemPrompt, conversationMessages, userPrompt, cacheUsage, keyRoute);
    }

    /** Previous arity: no cache counters reported. */
    public GuardrailResponseDto(boolean success, boolean passed, List<String> violations,
                                Map<String, Object> details, String sanitized, String error,
                                long durationMs, String provider, String model, int tokensUsed,
                                int promptTokens, int completionTokens, String systemPrompt,
                                List<ConversationMessageDto> conversationMessages, String userPrompt) {
        this(success, passed, violations, details, sanitized, error, durationMs, provider, model,
                tokensUsed, promptTokens, completionTokens, systemPrompt, conversationMessages,
                userPrompt, null);
    }
}
