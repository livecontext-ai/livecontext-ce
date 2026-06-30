package com.apimarketplace.agent.service.budget;

import com.apimarketplace.agent.domain.AgentStopReason;
import com.apimarketplace.agent.loop.GuardResult;
import com.apimarketplace.agent.loop.IterationContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AgentBudgetGuard")
class AgentBudgetGuardTest {

    private static final ModelCostCalculator CHEAP_RATES = new ModelCostCalculator(
        new BigDecimal("0.001"),  // input rate per 1000 tokens
        new BigDecimal("0.003"),  // output rate per 1000 tokens
        BigDecimal.ZERO
    );

    private static IterationContext ctx(int upcoming, long prompt, long completion) {
        return new IterationContext(
            "tenant-1", "agent-1", "openai", "gpt-test",
            upcoming, upcoming - 1, prompt, completion, 100L
        );
    }

    @Nested
    @DisplayName("disabled state")
    class Disabled {

        @Test
        @DisplayName("null budget always allows")
        void nullBudget() {
            AgentBudgetGuard guard = new AgentBudgetGuard(null, BigDecimal.ZERO, CHEAP_RATES);
            assertThat(guard.check(ctx(1, 0, 0)).proceed()).isTrue();
            assertThat(guard.check(ctx(50, 1_000_000, 1_000_000)).proceed()).isTrue();
        }

        @Test
        @DisplayName("zero budget always allows")
        void zeroBudget() {
            AgentBudgetGuard guard = new AgentBudgetGuard(BigDecimal.ZERO, BigDecimal.ZERO, CHEAP_RATES);
            assertThat(guard.check(ctx(1, 0, 0)).proceed()).isTrue();
        }

        @Test
        @DisplayName("negative budget always allows")
        void negativeBudget() {
            AgentBudgetGuard guard = new AgentBudgetGuard(new BigDecimal("-1"), BigDecimal.ZERO, CHEAP_RATES);
            assertThat(guard.check(ctx(1, 100, 100)).proceed()).isTrue();
        }
    }

    @Nested
    @DisplayName("first iteration")
    class FirstIteration {

        @Test
        @DisplayName("allows first iteration even when consumedBeforeRun is near budget")
        void allowsFirstIterationNoHistory() {
            // Budget = 1.00, already consumed 0.99. First iteration → no history → projection 0.
            AgentBudgetGuard guard = new AgentBudgetGuard(
                new BigDecimal("1.00"), new BigDecimal("0.99"), CHEAP_RATES);

            GuardResult result = guard.check(ctx(1, 0, 0));
            // run cost = 0, consumed = 0.99, next projected = 0 → total 0.99 < 1.00 → allow
            assertThat(result.proceed()).isTrue();
        }

        @Test
        @DisplayName("V162: skips worstCaseSingleIter on iteration 1 (mirrors JS twin)")
        void skipsWorstCaseOnFirstIteration() {
            // Sub-agent budget=5000 on Opus (worstCase ≈ 19800). Without the iter-1 skip,
            // the agent guard would deny EVERY Opus sub-agent before any work happens.
            // The tenant guard owns the upfront ceiling; the agent guard's role is
            // "stop runaway loops" once history exists. P3 fix mirrors JS twin
            // (mcp/bridge/lib/budgetGuards.js AgentBudgetGuard, `iters >= 2`).
            ModelCostCalculator opus = new ModelCostCalculator(
                new BigDecimal("15.0"), new BigDecimal("75.0"), BigDecimal.ZERO,
                200_000, 64_000);
            AgentBudgetGuard guard = new AgentBudgetGuard(
                new BigDecimal("5000"), BigDecimal.ZERO, opus);

            // Iter 1, no history.
            GuardResult result = guard.check(ctx(1, 0, 0));

            assertThat(result.proceed())
                .as("iter 1 must skip worstCase projection (would otherwise self-deny every Opus sub-agent)")
                .isTrue();
        }

        @Test
        @DisplayName("V162: skips projection on iter 2 also (mirror JS iters >= 2)")
        void skipsProjectionOnIterTwo() {
            // Symmetric with JS twin: with only 1 completed iter, lastDelta == growth
            // and lastDelta×2 self-denies iters with cost > budget/3. Wait until 2
            // completed iters before projecting. Iter 2 (1 completed) MUST proceed
            // even if worstCase exceeds budget - same lenient behavior as JS.
            ModelCostCalculator opus = new ModelCostCalculator(
                new BigDecimal("15.0"), new BigDecimal("75.0"), BigDecimal.ZERO,
                200_000, 64_000);
            AgentBudgetGuard guard = new AgentBudgetGuard(
                new BigDecimal("5000"), BigDecimal.ZERO, opus);

            // Iter 2 (1 iteration completed at 100k prompt, 1000 completion).
            // run cost so far = 100×15 + 1×75 = 1575 credits < 5000.
            // No projection because iterationsCompleted < 2 → ALLOW.
            IterationContext iter2 = new IterationContext(
                "tenant-1", "agent-1", "claude-code", "claude-opus-4-6",
                2, 1, 100_000L, 1000L, 100_000L, 1000L, 100L);

            GuardResult result = guard.check(iter2);

            assertThat(result.proceed())
                .as("iter 2 (1 completed) must skip projection - mirror JS twin")
                .isTrue();
        }

        @Test
        @DisplayName("V162: applies worstCaseSingleIter from iter 3 onwards (2 completed)")
        void appliesWorstCaseFromIterThree() {
            // From iter 3 onward (2 completed iters, enough samples for the
            // projection to be trusted), the worstCase ceiling kicks in.
            ModelCostCalculator opus = new ModelCostCalculator(
                new BigDecimal("15.0"), new BigDecimal("75.0"), BigDecimal.ZERO,
                200_000, 64_000);
            AgentBudgetGuard guard = new AgentBudgetGuard(
                new BigDecimal("5000"), BigDecimal.ZERO, opus);

            // Iter 3 (2 iters completed at 100k prompt + 1000 completion each).
            // run cost so far = 200×15 + 2×75 = 3150 credits.
            // worstCase = 200000×15/1000 + 64000×75/1000 = 3000 + 4800 = 7800.
            // total = 0 + 0 + 3150 + 7800 = 10950 > 5000 → DENY via worstCase.
            IterationContext iter3 = new IterationContext(
                "tenant-1", "agent-1", "claude-code", "claude-opus-4-6",
                3, 2, 200_000L, 2000L, 100_000L, 1000L, 100L);

            GuardResult result = guard.check(iter3);

            assertThat(result.proceed())
                .as("iter 3 onward must apply worstCase ceiling")
                .isFalse();
            assertThat(result.denialReason()).containsIgnoringCase("worstCase");
        }
    }

    @Nested
    @DisplayName("mid-execution denial")
    class MidExecution {

        @Test
        @DisplayName("denies when run + projection would exceed budget")
        void deniesOverBudget() {
            // Budget = 0.10, no history consumption.
            AgentBudgetGuard guard = new AgentBudgetGuard(
                new BigDecimal("0.10"), BigDecimal.ZERO, CHEAP_RATES);

            // After 5 iterations averaging 10000 prompt + 5000 completion tokens each:
            // run cost = 0.001 * 50000/1000 + 0.003 * 25000/1000 = 0.05 + 0.075 = 0.125
            // already over budget - should deny.
            IterationContext ctx = ctx(6, 50_000, 25_000);
            GuardResult result = guard.check(ctx);

            assertThat(result.proceed()).isFalse();
            assertThat(result.stopReason()).isEqualTo(AgentStopReason.BUDGET_EXHAUSTED);
            assertThat(result.scope()).isEqualTo("agent");
            assertThat(result.denialReason()).contains("agent budget");
        }

        @Test
        @DisplayName("includes consumedBeforeRun in projection")
        void includesHistoricalConsumption() {
            // Budget = 1.00, already consumed 0.95.
            // Even a tiny upcoming iteration should push over the limit.
            AgentBudgetGuard guard = new AgentBudgetGuard(
                new BigDecimal("1.00"), new BigDecimal("0.95"), CHEAP_RATES);

            // After 2 completed iters of 20000 prompt / 10000 completion average:
            // run cost = 0.001*40000/1000 + 0.003*20000/1000 = 0.04 + 0.06 = 0.10
            // consumed (0.95) + run (0.10) = 1.05 > 1.00 → deny
            GuardResult result = guard.check(ctx(3, 40_000, 20_000));
            assertThat(result.proceed()).isFalse();
            assertThat(result.scope()).isEqualTo("agent");
        }
    }
}
