package com.apimarketplace.agent.service.budget;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for the V80 pricing scale: rates are USD per 1M tokens and the
 * {@code /1000} in {@link ModelCostCalculator#computeCost(long, long)} converts to
 * credits where {@code 1 credit = $0.001}.
 *
 * <p>Bug #1 in TASK_TEST_ERRORS.md was rooted in a pre-V80 test suite that used rates
 * at the "per 1k tokens" scale (1000× too small), which let unknown-model fallback
 * slip under TenantBudgetGuard thresholds. These tests lock in the correct scale.</p>
 */
@DisplayName("ModelCostCalculator (V80 scale)")
class ModelCostCalculatorTest {

    @Test
    @DisplayName("claude-sonnet-4 rates: 1M input tokens → 3000 credits (= $3 list price)")
    void sonnetOneMillionInputTokens() {
        // claude-sonnet-4-6: $3 / 1M input, $15 / 1M output
        ModelCostCalculator calc = new ModelCostCalculator(
                new BigDecimal("3.00"),
                new BigDecimal("15.00"),
                BigDecimal.ZERO);

        BigDecimal cost = calc.computeCost(1_000_000L, 0L);

        // rate × tokens / 1000  = 3 × 1_000_000 / 1000 = 3000 credits (= $3.00)
        assertThat(cost).isEqualByComparingTo(new BigDecimal("3000"));
    }

    @Test
    @DisplayName("claude-opus-class fallback (15/75 per 1M): 1k input tokens → 15 credits")
    void opusFallbackOneThousandInputTokens() {
        ModelCostCalculator calc = new ModelCostCalculator(
                new BigDecimal("15.00"),
                new BigDecimal("75.00"),
                BigDecimal.ZERO);

        // rate × tokens / 1000 = 15 × 1000 / 1000 = 15 credits (= $0.015 at 1 credit = $0.001)
        BigDecimal cost = calc.computeCost(1_000L, 0L);
        assertThat(cost).isEqualByComparingTo(new BigDecimal("15"));
    }

    @Test
    @DisplayName("Full opus call (1k input + 1k output) returns 90 credits (15 + 75)")
    void opusFullCall() {
        ModelCostCalculator calc = new ModelCostCalculator(
                new BigDecimal("15.00"),
                new BigDecimal("75.00"),
                BigDecimal.ZERO);

        BigDecimal cost = calc.computeCost(1_000L, 1_000L);

        assertThat(cost).isEqualByComparingTo(new BigDecimal("90"));
    }

    @Test
    @DisplayName("Fixed cost is added as-is (already in credits)")
    void fixedCostAddedAsIs() {
        ModelCostCalculator calc = new ModelCostCalculator(
                new BigDecimal("3.00"),
                new BigDecimal("15.00"),
                new BigDecimal("10"));

        // 1k input @ 3/1M = 3 credits, 0 output, + 10 fixed = 13
        BigDecimal cost = calc.computeCost(1_000L, 0L);
        assertThat(cost).isEqualByComparingTo(new BigDecimal("13"));
    }

    @Test
    @DisplayName("Zero tokens → cost equals fixedCost")
    void zeroTokensYieldsFixedCost() {
        ModelCostCalculator calc = new ModelCostCalculator(
                new BigDecimal("3.00"),
                new BigDecimal("15.00"),
                new BigDecimal("5"));

        BigDecimal cost = calc.computeCost(0L, 0L);

        assertThat(cost).isEqualByComparingTo(new BigDecimal("5"));
    }

    @Test
    @DisplayName("Null rates in constructor default to ZERO (no NPE)")
    void nullRatesTreatedAsZero() {
        ModelCostCalculator calc = new ModelCostCalculator(null, null, null);

        assertThat(calc.isZero()).isTrue();
        assertThat(calc.computeCost(1_000_000L, 1_000_000L)).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("zero() factory produces an isZero() calculator")
    void zeroFactoryIsZero() {
        ModelCostCalculator calc = ModelCostCalculator.zero();

        assertThat(calc.isZero()).isTrue();
        assertThat(calc.computeCost(1_000L, 1_000L)).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("isZero() is false when any rate is non-zero")
    void isZeroFalseWhenAnyRateNonZero() {
        assertThat(new ModelCostCalculator(
                BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("1")).isZero()).isFalse();
        assertThat(new ModelCostCalculator(
                new BigDecimal("0.01"), BigDecimal.ZERO, BigDecimal.ZERO).isZero()).isFalse();
        assertThat(new ModelCostCalculator(
                BigDecimal.ZERO, new BigDecimal("0.01"), BigDecimal.ZERO).isZero()).isFalse();
    }

    @Test
    @DisplayName("Scale invariant: 200k input at opus (15/1M) = 3000 credits")
    void twoHundredKInputOpus() {
        // Regression guard against the pre-V80 mistake where this would have been
        // computed as 3 credits (1000× too small), making budget guards toothless.
        ModelCostCalculator calc = new ModelCostCalculator(
                new BigDecimal("15.00"),
                new BigDecimal("75.00"),
                BigDecimal.ZERO);

        BigDecimal cost = calc.computeCost(200_000L, 0L);

        assertThat(cost).isEqualByComparingTo(new BigDecimal("3000"));
    }
}
