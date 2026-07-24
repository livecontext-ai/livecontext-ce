package com.apimarketplace.orchestrator.trigger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the epoch budget-gate decision used in
 * {@link ReusableTriggerService#executeTriggerInternal}: once a run's
 * accumulated cost reaches the workflow budget, no NEW epoch may open.
 */
@DisplayName("ReusableTriggerService.isBudgetExceeded - epoch budget gate")
class ReusableTriggerBudgetGateTest {

    @Test
    @DisplayName("null budget never blocks (unlimited)")
    void nullBudgetNeverBlocks() {
        assertThat(ReusableTriggerService.isBudgetExceeded(new BigDecimal("999999"), null)).isFalse();
    }

    @Test
    @DisplayName("zero / negative budget never blocks (treated as unlimited)")
    void nonPositiveBudgetNeverBlocks() {
        assertThat(ReusableTriggerService.isBudgetExceeded(new BigDecimal("5"), BigDecimal.ZERO)).isFalse();
        assertThat(ReusableTriggerService.isBudgetExceeded(new BigDecimal("5"), new BigDecimal("-1"))).isFalse();
    }

    @Test
    @DisplayName("fresh run (spent 0) always passes its first fire")
    void freshRunPasses() {
        assertThat(ReusableTriggerService.isBudgetExceeded(BigDecimal.ZERO, new BigDecimal("10"))).isFalse();
        assertThat(ReusableTriggerService.isBudgetExceeded(null, new BigDecimal("10"))).isFalse();
    }

    @Test
    @DisplayName("spent below budget passes")
    void belowBudgetPasses() {
        assertThat(ReusableTriggerService.isBudgetExceeded(new BigDecimal("9.9999"), new BigDecimal("10"))).isFalse();
    }

    @Test
    @DisplayName("spent exactly at budget blocks (>= semantics)")
    void atBudgetBlocks() {
        assertThat(ReusableTriggerService.isBudgetExceeded(new BigDecimal("10.0000"), new BigDecimal("10"))).isTrue();
    }

    @Test
    @DisplayName("spent above budget blocks")
    void aboveBudgetBlocks() {
        assertThat(ReusableTriggerService.isBudgetExceeded(new BigDecimal("10.0001"), new BigDecimal("10"))).isTrue();
    }
}
