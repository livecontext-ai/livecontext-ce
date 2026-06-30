package com.apimarketplace.common.credit;

/**
 * Optional cache/reasoning token counters attached to a credit consumption call so
 * auth-service can bill each token class at the provider's true relative price
 * (Anthropic cache read 0.1x / write 1.25x, OpenAI cached 0.5x, Gemini cached 0.25x
 * + additive thoughts, DeepSeek cache hit 0.1x) instead of full input rate.
 *
 * <p>All fields nullable - {@code null} (or an all-zero instance) keeps the legacy
 * prompt+completion-only billing.
 */
public record LlmCacheTokens(
        Integer cacheCreationTokens,
        Integer cacheReadTokens,
        Integer cachedTokens,
        Integer reasoningTokens) {

    /** True when at least one counter is positive - otherwise sending it is a no-op. */
    public boolean hasAny() {
        return positive(cacheCreationTokens) || positive(cacheReadTokens)
                || positive(cachedTokens) || positive(reasoningTokens);
    }

    private static boolean positive(Integer v) {
        return v != null && v > 0;
    }
}
