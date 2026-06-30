package com.apimarketplace.agent.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Builder;

/**
 * Token usage information from LLM response.
 */
@Builder
public record UsageInfo(
    /**
     * Number of tokens in the prompt
     */
    Integer promptTokens,

    /**
     * Number of tokens in the completion
     */
    Integer completionTokens,

    /**
     * Total tokens used
     */
    Integer totalTokens,

    /**
     * Claude: tokens used to create cache entries
     */
    Integer cacheCreationInputTokens,

    /**
     * Claude: tokens read from cache
     */
    Integer cacheReadInputTokens,

    /**
     * OpenAI: cached prompt tokens (prompt_tokens_details.cached_tokens)
     */
    Integer cachedTokens,

    /**
     * OpenAI: reasoning tokens (completion_tokens_details.reasoning_tokens)
     */
    Integer reasoningTokens,

    /**
     * Gemini: thinking output tokens (usageMetadata.thoughtsTokenCount).
     * Separate output field - NOT part of completionTokens, counts toward output cost.
     */
    Integer thoughtsTokenCount,

    /**
     * Gemini: cached content hits (usageMetadata.cachedContentTokenCount).
     * Subset of promptTokens that were served from a cachedContent reference.
     */
    Integer cachedContentTokenCount
) {
    /**
     * Calculate total if not provided
     */
    @JsonIgnore
    public int getTotal() {
        if (totalTokens != null) {
            return totalTokens;
        }
        int prompt = promptTokens != null ? promptTokens : 0;
        int completion = completionTokens != null ? completionTokens : 0;
        return prompt + completion;
    }

    /**
     * Create empty usage info
     */
    public static UsageInfo empty() {
        return UsageInfo.builder()
            .promptTokens(0)
            .completionTokens(0)
            .totalTokens(0)
            .cacheCreationInputTokens(0)
            .cacheReadInputTokens(0)
            .cachedTokens(0)
            .reasoningTokens(0)
            .thoughtsTokenCount(0)
            .cachedContentTokenCount(0)
            .build();
    }

    /**
     * Add usage from another UsageInfo
     */
    public UsageInfo add(UsageInfo other) {
        if (other == null) {
            return this;
        }
        return UsageInfo.builder()
            .promptTokens(addNullable(promptTokens, other.promptTokens))
            .completionTokens(addNullable(completionTokens, other.completionTokens))
            .totalTokens(addNullable(totalTokens, other.totalTokens))
            .cacheCreationInputTokens(addNullable(cacheCreationInputTokens, other.cacheCreationInputTokens))
            .cacheReadInputTokens(addNullable(cacheReadInputTokens, other.cacheReadInputTokens))
            .cachedTokens(addNullable(cachedTokens, other.cachedTokens))
            .reasoningTokens(addNullable(reasoningTokens, other.reasoningTokens))
            .thoughtsTokenCount(addNullable(thoughtsTokenCount, other.thoughtsTokenCount))
            .cachedContentTokenCount(addNullable(cachedContentTokenCount, other.cachedContentTokenCount))
            .build();
    }

    private static Integer addNullable(Integer a, Integer b) {
        if (a == null && b == null) return null;
        return (a != null ? a : 0) + (b != null ? b : 0);
    }
}
