package com.apimarketplace.orchestrator.services.agent;

import com.apimarketplace.agent.client.dto.execution.ConversationMessageDto;
import com.apimarketplace.agent.domain.UsageInfo;

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
    String userPrompt,

    /**
     * Cache and reasoning counters, or {@code null} when the provider reported none.
     * Mirrors the field of the same name on the response DTO; appended rather than
     * inserted, with the previous arity kept below so no existing caller changes.
     * Carrying it is what lets AgentNode bill this node on the cache line instead of
     * at full input rate - 6.1x its cost over a model execution link, measured.
     */
    UsageInfo cacheUsage,

    /**
     * Probability per declared category, or {@code null} when the engine reported none.
     * Mirrors the response DTO field of the same name; appended rather than inserted, with
     * the previous arities kept below.
     *
     * <p>Only a decision model fills it. What it buys the workflow is the margin between
     * the top two categories, which a single {@code confidence} number cannot express: a
     * 0.98 / 0.01 win and a 0.34 / 0.33 coin-flip are different situations and the second
     * one is usually worth routing to a human.
     */
    java.util.Map<String, Double> probabilities,

    /** Whose key the call ran on ({@code PLATFORM} / {@code OWN_KEY}), {@code null} when unpinned. */
    String keyRoute
) {
    public static ClassifyResult success(String category, double confidence, String reasoning,
                                         long duration, String provider, String model,
                                         int tokens, int promptTokens, int completionTokens,
                                         String systemPrompt, List<ConversationMessageDto> messages,
                                         String userPrompt) {
        return success(category, confidence, reasoning, duration, provider, model, tokens,
            promptTokens, completionTokens, systemPrompt, messages, userPrompt, null, null);
    }

    /** With the cache counters the node is billed on. */
    public static ClassifyResult success(String category, double confidence, String reasoning,
                                         long duration, String provider, String model,
                                         int tokens, int promptTokens, int completionTokens,
                                         String systemPrompt, List<ConversationMessageDto> messages,
                                         String userPrompt, UsageInfo cacheUsage) {
        return success(category, confidence, reasoning, duration, provider, model, tokens,
            promptTokens, completionTokens, systemPrompt, messages, userPrompt, cacheUsage, null);
    }

    /** With the probabilities a decision model reports AND the key route the node is billed on. */
    public static ClassifyResult success(String category, double confidence, String reasoning,
                                         long duration, String provider, String model, int tokens,
                                         int promptTokens, int completionTokens, String systemPrompt,
                                         List<ConversationMessageDto> messages,
                                         String userPrompt, UsageInfo cacheUsage,
                                         java.util.Map<String, Double> probabilities, String keyRoute) {
        return new ClassifyResult(true, category, confidence, reasoning, null,
            duration, provider, model, tokens, promptTokens, completionTokens,
            systemPrompt, messages, userPrompt, cacheUsage, probabilities, keyRoute);
    }

    /** With the per-category probabilities a decision model reports. */
    public static ClassifyResult success(String category, double confidence, String reasoning,
                                         long duration, String provider, String model,
                                         int tokens, int promptTokens, int completionTokens,
                                         String systemPrompt, List<ConversationMessageDto> messages,
                                         String userPrompt, UsageInfo cacheUsage,
                                         java.util.Map<String, Double> probabilities) {
        return new ClassifyResult(true, category, confidence, reasoning, null,
            duration, provider, model, tokens, promptTokens, completionTokens,
            systemPrompt, messages, userPrompt, cacheUsage, probabilities, null);
    }

    public static ClassifyResult failure(String error, long duration, String provider) {
        return new ClassifyResult(false, null, 0, null, error, duration, provider, null, 0, 0, 0,
            null, null, null, null, null, null);
    }
}
