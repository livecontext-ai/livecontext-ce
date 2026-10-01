package com.apimarketplace.agent.service.budget;

import com.apimarketplace.agent.loop.GuardResult;
import com.apimarketplace.agent.loop.IterationContext;
import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.common.credit.LlmCacheTokens;
import com.apimarketplace.common.credit.PricingSnapshotClient;
import com.apimarketplace.common.credit.PricingSnapshotClient.PricingRates;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Cache-aware budget guards (2026-09-30).
 *
 * <p>The guards priced every prompt token at the model's input rate. A Claude-shaped turn
 * is mostly cache reads (billed at 0.1x), so it was projected at about five times its real
 * debit: on prod a free account's chat turn that the ledger debited 112.44 credits was
 * projected at 536.77 + 310.43 and killed after three or four model calls. These tests use
 * that turn and the prod snapshot rates (claude-sonnet-5-5 at 2.0 / 10.0 / 0.2 read /
 * 2.5 write per 1M, times the 4/3 cloud multiplier).
 */
@DisplayName("Cache-aware budget guards")
class CacheAwareBudgetGuardTest {

    private static final ModelCostCalculator SONNET = new ModelCostCalculator(
        new BigDecimal("2.666667"), new BigDecimal("13.333333"), BigDecimal.ZERO, null, null,
        new BigDecimal("0.266667"), new BigDecimal("3.333333"));

    private static final LlmCacheTokens TURN_CACHE = new LlmCacheTokens(18_971, 182_073, 0, 0);
    /** The 4th call alone: 58,181 prompt = 2 plain + 1,057 writes + 57,122 reads. */
    private static final LlmCacheTokens LAST_CALL_CACHE = new LlmCacheTokens(1_057, 57_122, 0, 0);

    /** The killed turn after its 4th model call, as the loop hands it to a guard. */
    private static IterationContext killedTurn(String provider, long prompt, long lastPrompt,
                                               LlmCacheTokens cache,
                                               LlmCacheTokens lastCache) {
        return new IterationContext("141", null, provider, "claude-sonnet-5-5", 9, 8,
            prompt, 47, lastPrompt, 5, 17_000L, cache, lastCache);
    }

    @Nested
    @DisplayName("ModelCostCalculator")
    class Calculator {

        @Test
        @DisplayName("claude-code: the turn costs what the ledger debited (112.44), not 536.77")
        void claudeCodeTurnMatchesLedger() {
            BigDecimal cost = SONNET.computeCost("claude-code", 201_052, 47, TURN_CACHE);

            assertThat(cost).isCloseTo(new BigDecimal("112.4374"), within(new BigDecimal("0.001")));
        }

        @Test
        @DisplayName("anthropic API: the cache counters are added beside the 8-token prompt")
        void anthropicAddsCacheBesidePrompt() {
            BigDecimal cost = SONNET.computeCost("anthropic", 8, 47, TURN_CACHE);

            assertThat(cost).isCloseTo(new BigDecimal("112.4374"), within(new BigDecimal("0.001")));
        }

        @Test
        @DisplayName("subset provider: the cached part is priced at the cache-read rate (GPT Luna turn, 5.1094)")
        void subsetCachedAtCacheReadRate() {
            ModelCostCalculator luna = new ModelCostCalculator(
                new BigDecimal("0.133333"), new BigDecimal("0.666667"), BigDecimal.ZERO, null, null,
                new BigDecimal("0.013333"), new BigDecimal("0.133333"));

            BigDecimal cost = luna.computeCost("codex", 115_668, 336, new LlmCacheTokens(0, 0, 87_808, 0));

            assertThat(cost).isCloseTo(new BigDecimal("5.1094"), within(new BigDecimal("0.001")));
        }

        private final ModelCostCalculator olderSnapshot = new ModelCostCalculator(
            new BigDecimal("2.666667"), new BigDecimal("13.333333"), BigDecimal.ZERO, null, null,
            null, BigDecimal.ZERO);

        @Test
        @DisplayName("no published cache rate (auth-service older than the change): claude-code keeps the pre-change formula")
        void olderSnapshotKeepsThePreChangeFormulaForClaudeCode() {
            assertThat(olderSnapshot.cacheRatesKnown()).isFalse();
            assertThat(olderSnapshot.computeCost("claude-code", 201_052, 47, TURN_CACHE))
                .isEqualByComparingTo(olderSnapshot.computeCost(201_052, 47));
        }

        @Test
        @DisplayName("no published cache rate: direct Anthropic keeps ignoring its cache counters, as before (no 10x window mid-rollout)")
        void olderSnapshotKeepsThePreChangeFormulaForAnthropic() {
            // Pricing the additive cache at the input rate would project the cache reads 10x
            // too high on the direct-API path for as long as the rollout lasts.
            assertThat(olderSnapshot.computeCost("anthropic", 8, 47, TURN_CACHE))
                .isEqualByComparingTo(olderSnapshot.computeCost(8, 47));
            assertThat(olderSnapshot.computeCacheMissCost("anthropic", 8, 47, TURN_CACHE))
                .isEqualByComparingTo(olderSnapshot.computeCost(8, 47));
        }

        @Test
        @DisplayName("cache miss: the last call with everything it read written again (claude-code: 194.00)")
        void cacheMissRewritesTheReads() {
            // 2 plain x 2.666667 + (1,057 + 57,122) x 3.333333, + 5 output x 13.333333, per 1000.
            BigDecimal miss = SONNET.computeCacheMissCost("claude-code", 58_181, 5, LAST_CALL_CACHE);

            assertThat(miss).isCloseTo(new BigDecimal("194.0020"), within(new BigDecimal("0.01")));
            assertThat(miss).isGreaterThan(SONNET.computeCost("claude-code", 58_181, 5, LAST_CALL_CACHE));
        }

        @Test
        @DisplayName("the miss reservation is the miss cost where the prompt total carries the cache, zero for the Anthropic API")
        void cacheMissReserveFollowsThePromptShape() {
            assertThat(SONNET.cacheMissReserve("claude-code", 58_181, 5, LAST_CALL_CACHE))
                .isEqualByComparingTo(SONNET.computeCacheMissCost("claude-code", 58_181, 5, LAST_CALL_CACHE));
            // The pre-change guard never reserved anything for the Anthropic API's additive
            // cache; a full-miss reservation there would stop existing direct runs far earlier.
            assertThat(SONNET.cacheMissReserve("anthropic", 2, 5, LAST_CALL_CACHE)).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("cache miss on a subset provider is the whole prompt at the input rate (no write premium)")
        void cacheMissOnSubsetProviderIsThePromptAtInputRate() {
            ModelCostCalculator luna = new ModelCostCalculator(
                new BigDecimal("0.133333"), new BigDecimal("0.666667"), BigDecimal.ZERO, null, null,
                new BigDecimal("0.013333"), new BigDecimal("0.133333"));

            assertThat(luna.computeCacheMissCost("codex", 115_668, 336, new LlmCacheTokens(0, 0, 87_808, 0)))
                .isEqualByComparingTo(luna.computeCost(115_668, 336));
        }

        @Test
        @DisplayName("no cache counters is exactly the prompt/completion formula")
        void noCacheIsLegacyFormula() {
            assertThat(SONNET.computeCost("claude-code", 1_000, 1_000, null))
                .isEqualByComparingTo(SONNET.computeCost(1_000, 1_000));
        }
    }

    @Nested
    @DisplayName("TenantBudgetGuard")
    class Tenant {

        private TenantBudgetGuard guardWithBalance(String balance) {
            CreditConsumptionClient credit = mock(CreditConsumptionClient.class);
            when(credit.fetchLlmSpendableBalance(any(), any(), any())).thenReturn(new BigDecimal(balance));
            return new TenantBudgetGuard(credit, SONNET);
        }

        @Test
        @DisplayName("regression 2026-09-30: the killed Claude Code turn proceeds at 733.57 credits once the cache is priced")
        void killedTurnProceeds() {
            GuardResult r = guardWithBalance("733.5726")
                .check(killedTurn("claude-code", 201_052, 58_181, TURN_CACHE, LAST_CALL_CACHE));

            assertThat(r.proceed()).as(r.denialReason()).isTrue();
        }

        @Test
        @DisplayName("the same turn without cache counters is the prod kill (would be exceeded)")
        void withoutCacheCountersItIsTheProdKill() {
            GuardResult r = guardWithBalance("733.5726").check(killedTurn("claude-code", 201_052, 58_181,
                IterationContext.NO_CACHE, IterationContext.NO_CACHE));

            assertThat(r.proceed()).isFalse();
            assertThat(r.denialReason()).contains("would be exceeded").contains("run=536.76");
        }

        @Test
        @DisplayName("cache-aware pricing still stops a run the balance cannot cover")
        void stillDeniesWhenTheBalanceIsShort() {
            GuardResult r = guardWithBalance("130")
                .check(killedTurn("claude-code", 201_052, 58_181, TURN_CACHE, LAST_CALL_CACHE));

            assertThat(r.proceed()).isFalse();
            assertThat(r.denialReason()).contains("would be exceeded").contains("run=112.43");
        }

        @Test
        @DisplayName("keeps room for one cache miss: 250 left covers 112.44 + 2 x 18.8, not a 194 rewrite")
        void keepsHeadroomForACacheMiss() {
            GuardResult r = guardWithBalance("250")
                .check(killedTurn("claude-code", 201_052, 58_181, TURN_CACHE, LAST_CALL_CACHE));

            assertThat(r.proceed()).isFalse();
            assertThat(r.denialReason()).contains("cacheMiss=194.00");
        }

        @Test
        @DisplayName("direct Anthropic: a cache the prompt count cannot see still exhausts the balance")
        void anthropicCacheExhaustsTheBalance() {
            GuardResult r = guardWithBalance("110")
                .check(killedTurn("anthropic", 8, 8, TURN_CACHE, LAST_CALL_CACHE));

            assertThat(r.proceed()).isFalse();
            assertThat(r.denialReason()).contains("exhausted");
        }
    }

    @Nested
    @DisplayName("AgentBudgetGuard")
    class Agent {

        @Test
        @DisplayName("agent guard keeps room for one cache miss too: 250 does not cover 112.44 + 194")
        void agentGuardKeepsHeadroomForACacheMiss() {
            AgentBudgetGuard guard = new AgentBudgetGuard(new BigDecimal("250"), BigDecimal.ZERO, SONNET);

            GuardResult r = guard.check(killedTurn("claude-code", 201_052, 58_181, TURN_CACHE, LAST_CALL_CACHE));

            assertThat(r.proceed()).isFalse();
            assertThat(r.denialReason()).contains("cacheMiss=194.00");
        }

        @Test
        @DisplayName("an agent budget of 350 covers the killed turn plus one cache miss once the cache is priced")
        void agentBudgetCoversTheTurn() {
            AgentBudgetGuard guard = new AgentBudgetGuard(new BigDecimal("350"), BigDecimal.ZERO, SONNET);

            GuardResult r = guard.check(killedTurn("claude-code", 201_052, 58_181, TURN_CACHE, LAST_CALL_CACHE));

            assertThat(r.proceed()).as(r.denialReason()).isTrue();
        }

        @Test
        @DisplayName("the same agent budget denies the turn when the cache is priced at the input rate (536.77)")
        void agentBudgetDeniesWithoutCache() {
            AgentBudgetGuard guard = new AgentBudgetGuard(new BigDecimal("350"), BigDecimal.ZERO, SONNET);

            GuardResult r = guard.check(killedTurn("claude-code", 201_052, 58_181,
                IterationContext.NO_CACHE, IterationContext.NO_CACHE));

            assertThat(r.proceed()).isFalse();
        }
    }

    @Test
    @DisplayName("GuardChainFactory builds the calculator with the snapshot's cache rates")
    void factoryWiresSnapshotCacheRates() {
        PricingSnapshotClient snapshot = mock(PricingSnapshotClient.class);
        when(snapshot.getRates("claude-code", "claude-sonnet-5-5")).thenReturn(Optional.of(new PricingRates(
            new BigDecimal("2.666667"), new BigDecimal("13.333333"), BigDecimal.ZERO, null, null,
            new BigDecimal("0.266667"), new BigDecimal("3.333333"))));
        GuardChainFactory factory = new GuardChainFactory(null, null, snapshot);

        ModelCostCalculator calc = factory.resolveCalculator("claude-code", "claude-sonnet-5-5");

        assertThat(calc.cacheReadRate()).isEqualByComparingTo("0.266667");
        assertThat(calc.cacheWriteRate()).isEqualByComparingTo("3.333333");
    }
}
