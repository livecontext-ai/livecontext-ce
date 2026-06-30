package com.apimarketplace.agent.service.budget;

import com.apimarketplace.agent.domain.AgentStopReason;
import com.apimarketplace.agent.loop.GuardResult;
import com.apimarketplace.agent.loop.IterationContext;
import com.apimarketplace.common.credit.CreditConsumptionClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression test for the -11 305 credits incident (V162).
 *
 * <p>A chat conversation through claude-opus-4-6 (bridge claude-code) consumed
 * -11 305,02 credits in a single conversation: 32 iterations, 2,16M cumulative
 * prompt tokens, last LLM call alone billing ~10 000 credits. The pre-V162 guard
 * projected the next iteration as {@code avgPromptTokens × inputRate} which dilutes
 * step-function bursts (the actual failure mode here: cruise at ~70k tokens/iter
 * for 30 iters, then iter 32 explodes to 2M+ via massive context injection).</p>
 *
 * <p>The fix: projection becomes {@code max(growthProj, lastDeltaProj × safety,
 * worstCaseSingleIter)}. {@code worstCaseSingleIter = computeCost(contextWindow,
 * maxOutputTokens)} is invariant to growth pattern - it bounds what a single
 * iteration <em>can</em> cost regardless of trajectory.</p>
 *
 * <p><strong>This test fails on the pre-V162 guard (avg-only projection allows
 * the iter 32 explosion) and passes on the post-V162 guard (worstCase trips well
 * before iter 32).</strong> Per CLAUDE.md "Bug fix → regression test" - every fix
 * ships with a failing-pre-fix / passing-post-fix unit test that names the bug.</p>
 */
@DisplayName("TenantBudgetGuard regression - -11305 incident (V162)")
class TenantBudgetGuardRegressionMinus11305Test {

    /**
     * claude-opus-4-6 published rates (per 1M tokens) at the time of the incident.
     * Stored in {@code auth.model_pricing} via V120 / V162.
     */
    private static final BigDecimal OPUS_INPUT_RATE = new BigDecimal("15.0");
    private static final BigDecimal OPUS_OUTPUT_RATE = new BigDecimal("75.0");

    /**
     * 200K context window + 64K max output - the V162-seeded values for the
     * canonical Anthropic Opus 4.6 row in {@code auth.model_pricing}. worstCase
     * = 200000×15/1000 + 64000×75/1000 = 3000 + 4800 = 7800 credits - already
     * above any "fits-in-a-pocket" balance, which is exactly what makes this
     * guard reliable: any single Opus iteration could plausibly cost more than
     * most users have, so the guard trips immediately rather than betting on
     * average usage.
     *
     * <p>(The 1M variants exist as {@code claude-sonnet-4-6-1m} / Anthropic
     * beta header territory; V162 does not seed those, the canonical 200K row
     * is what production hits for the incident scenario.)</p>
     */
    private static final Integer OPUS_CONTEXT_WINDOW = 200_000;
    private static final Integer OPUS_MAX_OUTPUT = 64_000;

    @Test
    @DisplayName("DENIES via worstCase projection at iter 5 cruise BEFORE the burst lands")
    void deniesAtCruiseBeforeBurstViaWorstCase() {
        // The bug failure mode: cruise iters look cheap, average projects cheaply,
        // pre-V162 guard ALLOWS each iter, then iter N suddenly bursts to 2M tokens
        // and bills ~10000 credits (the -11305 incident). Post-V162: worstCase is
        // invariant to growth pattern - Opus 200K ctx × 64K out = 7800 credits per
        // iter (V162-seeded values), so any user balance < 7800 trips the guard at
        // the FIRST cruise iter before the burst can land.
        //
        // Pick conditions where pre-V162 avg-projection would ALLOW (cumulative
        // consumption is low) but post-V162 worstCase still DENIES.
        ModelCostCalculator opusCalculator = new ModelCostCalculator(
            OPUS_INPUT_RATE, OPUS_OUTPUT_RATE, BigDecimal.ZERO,
            OPUS_CONTEXT_WINDOW, OPUS_MAX_OUTPUT);

        CreditConsumptionClient creditClient = mock(CreditConsumptionClient.class);
        // Balance 12000. Numbers (rates per 1K tokens, see ModelCostCalculator):
        //   cumulative consumed = 350k × 15/1k + 3.5k × 75/1k = 5250 + 262 = ~5512 credits
        //   avg next iter cost  = 70k × 15/1k + 0.7k × 75/1k  =   1050 +  52 = ~1102 credits
        //   worstCase           = 200k × 15/1k + 64k × 75/1k  = 3000 + 4800 = 7800 credits
        // Pre-V162: 5512 + 1102 = 6614 << 12000 → ALLOW → bug reproduces, burst lands.
        // Post-V162: max(growth, lastDelta×2, worstCase=7800) = 7800.
        //            5512 + 7800 = 13312 > 12000 → DENY via worstCase branch.
        when(creditClient.fetchBalance("tenant-1")).thenReturn(new BigDecimal("12000"));

        TenantBudgetGuard guard = new TenantBudgetGuard(creditClient, opusCalculator);

        // 5 cruise iterations completed at ~70k prompt + 700 completion each.
        IterationContext duringCruise = new IterationContext(
            "tenant-1", "agent-1", "claude-code", "claude-opus-4-6",
            6, 5,
            350_000L,            // cumulative prompt
            3_500L,              // cumulative completion
            70_000L,             // last delta prompt (cruise pace)
            700L,                // last delta completion
            30_000L);

        GuardResult result = guard.check(duringCruise);

        // Pre-V162: avg = 70k × 15/1k + 0.7k × 75/1k ≈ 1102 credits → consumed (5512) + 1102 = 6614 << 12000 → ALLOW.
        // Post-V162: worstCase = 200K × 15/1k + 64K × 75/1k = 3000 + 4800 = 7800 credits
        //          → consumed (5512) + 7800 = 13312 > 12000 → DENY via worstCase.
        assertThat(result.proceed())
            .as("V162 worstCase must close the burst window; pre-V162 avg-projection allowed this")
            .isFalse();
        assertThat(result.stopReason()).isEqualTo(AgentStopReason.BUDGET_EXHAUSTED);
        assertThat(result.scope()).isEqualTo("tenant");
        // The deny message must surface worstCase so on-call sees the real branch.
        assertThat(result.denialReason()).containsIgnoringCase("worstCase");
    }

    @Test
    @DisplayName("DENIES on iter 1 when balance can't even cover one Opus call")
    void deniesOnIterOneWhenBalanceCannotCoverOneOpusCall() {
        // User has 500 credits, wants to start a fresh Opus conversation. WorstCase
        // = 7800 credits (200K ctx × 15/1k + 64K out × 75/1k) >> balance. Must
        // reject before the API is touched - not let the user start, run for 30
        // iters, then wake up at -7000+.
        ModelCostCalculator opusCalculator = new ModelCostCalculator(
            OPUS_INPUT_RATE, OPUS_OUTPUT_RATE, BigDecimal.ZERO,
            OPUS_CONTEXT_WINDOW, OPUS_MAX_OUTPUT);

        CreditConsumptionClient creditClient = mock(CreditConsumptionClient.class);
        when(creditClient.fetchBalance("tenant-1")).thenReturn(new BigDecimal("500"));

        TenantBudgetGuard guard = new TenantBudgetGuard(creditClient, opusCalculator);

        IterationContext freshConversation = new IterationContext(
            "tenant-1", "agent-1", "claude-code", "claude-opus-4-6",
            1, 0, 0L, 0L, 0L, 0L, 0L);

        GuardResult result = guard.check(freshConversation);

        assertThat(result.proceed())
            .as("balance 500 cannot cover Opus worstCase 7800 - must DENY iter 1")
            .isFalse();
        assertThat(result.scope()).isEqualTo("tenant");
    }

    @Test
    @DisplayName("DENIES at realistic incident balance 2000 BEFORE the burst lands")
    void deniesAtRealisticIncidentBalance() {
        // Closer reproduction of the actual incident: balance ~2000 (the user was
        // around 2308 when the 32nd iter exploded). Pre-V162 every iter was
        // projected via avg ≈ 1050 credits, allowing each cruise iter through and
        // letting iter 32 burst for ~10000 credits. Post-V162: worstCase = 7800
        // (200K × 15/1k + 64K × 75/1k) dominates, denying iter 1 when balance < 7800.
        ModelCostCalculator opusCalculator = new ModelCostCalculator(
            OPUS_INPUT_RATE, OPUS_OUTPUT_RATE, BigDecimal.ZERO,
            OPUS_CONTEXT_WINDOW, OPUS_MAX_OUTPUT);

        CreditConsumptionClient creditClient = mock(CreditConsumptionClient.class);
        when(creditClient.fetchBalance("tenant-1")).thenReturn(new BigDecimal("2000"));

        TenantBudgetGuard guard = new TenantBudgetGuard(creditClient, opusCalculator);

        // Fresh conversation - no prior iters. This is the first iter the user fires.
        IterationContext freshConversation = new IterationContext(
            "tenant-1", "agent-1", "claude-code", "claude-opus-4-6",
            1, 0, 0L, 0L, 0L, 0L, 0L);

        GuardResult result = guard.check(freshConversation);

        // Pre-V162: nextProjected = computeCost(0, 0) = 0; consumed (0) + 0 = 0 < 2000 → ALLOW.
        // Post-V162: max(0, 0, worstCase=7800) = 7800; 0 + 7800 = 7800 > 2000 → DENY.
        assertThat(result.proceed())
            .as("balance 2000 < Opus worstCase 7800 - must DENY before any iteration runs")
            .isFalse();
        assertThat(result.scope()).isEqualTo("tenant");
        assertThat(result.denialReason()).containsIgnoringCase("worstCase");
    }

    @Test
    @DisplayName("PROCEEDS on Sonnet (smaller worstCase) with same balance that blocks Opus")
    void proceedsOnSonnetWithSameBalanceThatBlocksOpus() {
        // Sonnet 200K ctx + 8K out at 3/15 USD per 1M ≈ 720 credits worstCase.
        // Same 500-credit balance - STILL blocks (720 > 500), but illustrates the
        // formula scales by model.
        ModelCostCalculator sonnetCalculator = new ModelCostCalculator(
            new BigDecimal("3.0"), new BigDecimal("15.0"), BigDecimal.ZERO,
            200_000, 8192);

        CreditConsumptionClient creditClient = mock(CreditConsumptionClient.class);
        // Bump balance to 1500 - comfortably above Sonnet worstCase.
        when(creditClient.fetchBalance("tenant-1")).thenReturn(new BigDecimal("1500"));

        TenantBudgetGuard guard = new TenantBudgetGuard(creditClient, sonnetCalculator);

        IterationContext freshConversation = new IterationContext(
            "tenant-1", "agent-1", "anthropic", "claude-3-5-sonnet-20241022",
            1, 0, 0L, 0L, 0L, 0L, 0L);

        GuardResult result = guard.check(freshConversation);

        assertThat(result.proceed())
            .as("Sonnet worstCase (~720) fits in 1500-credit balance - should ALLOW")
            .isTrue();
    }
}
