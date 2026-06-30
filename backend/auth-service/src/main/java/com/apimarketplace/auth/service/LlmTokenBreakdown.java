package com.apimarketplace.auth.service;

/**
 * Full token breakdown of one LLM execution, as reported by the provider.
 *
 * <p>Semantics differ per provider family - {@link ModelPricingService} interprets
 * the fields when computing the billable cost:
 * <ul>
 *   <li><b>Anthropic API</b> ({@code provider=anthropic}): {@code promptTokens} EXCLUDES
 *       cache tokens; {@code cacheCreationTokens}/{@code cacheReadTokens} are additive.</li>
 *   <li><b>Claude Code bridge</b> ({@code provider=claude-code}): {@code promptTokens}
 *       INCLUDES cache tokens (the bridge sums input + cache_creation + cache_read);
 *       {@code cacheCreationTokens}/{@code cacheReadTokens} are also reported separately.</li>
 *   <li><b>OpenAI / Codex / DeepSeek</b>: {@code cachedTokens} is a SUBSET of
 *       {@code promptTokens}; {@code reasoningTokens} is a subset of {@code completionTokens}.</li>
 *   <li><b>Google / Gemini</b>: cached-content tokens are a SUBSET of {@code promptTokens}
 *       (reported in {@code cacheReadTokens} by the gemini-cli bridge, {@code cachedTokens}
 *       otherwise); {@code reasoningTokens} (thoughts) are ADDITIVE output tokens,
 *       NOT included in {@code completionTokens}.</li>
 * </ul>
 */
public record LlmTokenBreakdown(
        int promptTokens,
        int completionTokens,
        int cacheCreationTokens,
        int cacheReadTokens,
        int cachedTokens,
        int reasoningTokens) {

    /** Breakdown with no cache/reasoning detail - behaves exactly like the legacy 2-field cost. */
    public static LlmTokenBreakdown of(int promptTokens, int completionTokens) {
        return new LlmTokenBreakdown(promptTokens, completionTokens, 0, 0, 0, 0);
    }
}
