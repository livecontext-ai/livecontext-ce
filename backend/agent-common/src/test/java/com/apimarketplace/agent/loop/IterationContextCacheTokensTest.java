package com.apimarketplace.agent.loop;

import com.apimarketplace.common.credit.LlmCacheTokens;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The cache counters an {@link IterationContext} carries to the budget guards (2026-09-30):
 * without them a guard cannot tell a cheap cache read from a plain input token.
 */
@DisplayName("IterationContext - cache counters")
class IterationContextCacheTokensTest {

    @Test
    @DisplayName("callers without cache counters get NO_CACHE, never null")
    void legacyConstructorsCarryNoCache() {
        IterationContext eleven = new IterationContext("t", "a", "anthropic", "m", 2, 1, 100, 10, 100, 10, 5L);
        IterationContext nine = new IterationContext("t", "a", "anthropic", "m", 2, 1, 100, 10, 5L);

        assertThat(eleven.cacheTokensSoFar()).isEqualTo(IterationContext.NO_CACHE);
        assertThat(eleven.lastIterationCacheTokens()).isEqualTo(IterationContext.NO_CACHE);
        assertThat(nine.cacheTokensSoFar()).isEqualTo(IterationContext.NO_CACHE);
    }

    @Test
    @DisplayName("null cache counters are normalised to NO_CACHE")
    void nullCacheIsNone() {
        IterationContext ctx = new IterationContext("t", "a", "anthropic", "m", 2, 1, 100, 10, 100, 10, 5L, null, null);

        assertThat(ctx.cacheTokensSoFar()).isEqualTo(IterationContext.NO_CACHE);
        assertThat(ctx.lastIterationCacheTokens()).isEqualTo(IterationContext.NO_CACHE);
    }

    @Test
    @DisplayName("the per-iteration cache average divides every counter, like the prompt average")
    void averageDividesEveryCounter() {
        IterationContext ctx = new IterationContext("t", "a", "claude-code", "m", 5, 4,
            201_052, 47, 58_181, 5, 5L,
            new LlmCacheTokens(18_971, 182_073, 8, null),
            IterationContext.NO_CACHE);

        assertThat(ctx.avgCacheTokensPerIteration())
            .isEqualTo(new LlmCacheTokens(4_742, 45_518, 2, 0));
    }

    @Test
    @DisplayName("no completed iteration averages to NO_CACHE")
    void averageBeforeFirstIterationIsNone() {
        IterationContext ctx = new IterationContext("t", "a", "claude-code", "m", 1, 0, 0, 0, 0, 0, 0L,
            new LlmCacheTokens(1, 2, 3, 0), IterationContext.NO_CACHE);

        assertThat(ctx.avgCacheTokensPerIteration()).isEqualTo(IterationContext.NO_CACHE);
    }
}
