package com.apimarketplace.agent.client.dto.execution;

import com.apimarketplace.agent.domain.UsageInfo;
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
    String userPrompt,

    /**
     * Cache and reasoning counters, or {@code null} when the provider reported none.
     *
     * <p><b>Appended, never inserted.</b> Adding a component rewrites a record's
     * canonical constructor, so the previous arity is kept below and every existing
     * caller compiles untouched. Only a caller with cache counters to carry uses the
     * long form.
     *
     * <p><b>Why it had to exist.</b> Without it this response carried prompt and
     * completion alone, and the orchestrator bills a classify node from those two
     * numbers. A run moved onto a CLI bridge by a model execution link therefore
     * reported the bridge's INCLUSIVE prompt total under the billed provider's label
     * with no cache line at all, so the whole context was charged at full input rate:
     * 6.1x its cost, measured. The obvious repair - stripping the cache back out of the
     * prompt - is worse, because with nowhere to put it the cache leaves the bill
     * entirely. Carrying it is the fix, and this field is the transport.
     */
    UsageInfo cacheUsage,

    /**
     * Probability mass over EVERY declared category, or {@code null} when the engine
     * reported none (the LLM path never does).
     *
     * <p><b>Appended, never inserted</b>, per the note above: the previous arities stay
     * below and every existing caller compiles untouched.
     *
     * <p>A decision model scores all the categories at once, so the runner-up and the
     * margin between the top two are known facts rather than something a model was asked
     * to narrate. That is strictly more than {@code confidence} carries, and it is what
     * lets a workflow route a close call to a human instead of acting on a 0.34 / 0.33
     * split it could not otherwise see.
     */
    java.util.Map<String, Double> probabilities,

    /**
     * Whose API key the call ran on ({@code PLATFORM} / {@code OWN_KEY}), or {@code null}
     * when unpinned. Appended for the same reason as {@link #cacheUsage}: the orchestrator
     * bills this node from the response, and an own-key turn is billed a flat fee, not the
     * token rate.
     */
    String keyRoute
) {
    /** Previous arity: cache counters, no probabilities. */
    public ClassifyResponseDto(boolean success, String selectedCategory, double confidence,
                               String reasoning, String error, long durationMs, String provider,
                               String model, int tokensUsed, int promptTokens, int completionTokens,
                               String systemPrompt, List<ConversationMessageDto> conversationMessages,
                               String userPrompt, UsageInfo cacheUsage) {
        this(success, selectedCategory, confidence, reasoning, error, durationMs, provider, model,
                tokensUsed, promptTokens, completionTokens, systemPrompt, conversationMessages,
                userPrompt, cacheUsage, null, null);
    }

    /**
     * Previous arity: probabilities reported, no key route. Both branches appended a
     * component to this record, so every caller written against either one still compiles.
     */
    public ClassifyResponseDto(boolean success, String selectedCategory, double confidence,
                               String reasoning, String error, long durationMs, String provider,
                               String model, int tokensUsed, int promptTokens, int completionTokens,
                               String systemPrompt, List<ConversationMessageDto> conversationMessages,
                               String userPrompt, UsageInfo cacheUsage,
                               java.util.Map<String, Double> probabilities) {
        this(success, selectedCategory, confidence, reasoning, error, durationMs, provider, model,
                tokensUsed, promptTokens, completionTokens, systemPrompt, conversationMessages,
                userPrompt, cacheUsage, probabilities, null);
    }

    /** The same response, pinned to the key route the call actually ran on. */
    public ClassifyResponseDto withKeyRoute(String keyRoute) {
        return new ClassifyResponseDto(success, selectedCategory, confidence, reasoning, error,
                durationMs, provider, model, tokensUsed, promptTokens, completionTokens,
                systemPrompt, conversationMessages, userPrompt, cacheUsage, probabilities, keyRoute);
    }

    /** Previous arity: no cache counters reported. */
    public ClassifyResponseDto(boolean success, String selectedCategory, double confidence,
                               String reasoning, String error, long durationMs, String provider,
                               String model, int tokensUsed, int promptTokens, int completionTokens,
                               String systemPrompt, List<ConversationMessageDto> conversationMessages,
                               String userPrompt) {
        this(success, selectedCategory, confidence, reasoning, error, durationMs, provider, model,
                tokensUsed, promptTokens, completionTokens, systemPrompt, conversationMessages,
                userPrompt, null);
    }
}
