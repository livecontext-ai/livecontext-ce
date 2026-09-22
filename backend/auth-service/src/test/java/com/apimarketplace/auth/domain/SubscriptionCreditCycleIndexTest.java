package com.apimarketplace.auth.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The monthly credit cycle of a yearly subscription is anchored on {@code currentPeriodStart}
 * (V498), so its index is only meaningful for the period it was counted in. These tests pin
 * the single place that keeps that true: moving the anchor restarts the count.
 */
@DisplayName("Subscription - credit cycle index is anchored on the billing period")
class SubscriptionCreditCycleIndexTest {

    private static final LocalDateTime PERIOD_START = LocalDateTime.of(2026, 9, 14, 23, 53, 9);

    @Test
    @DisplayName("a new subscription starts at cycle 0, the period-start grant")
    void startsAtZero() {
        assertThat(new Subscription().getCreditCycleIndex()).isZero();
    }

    @Test
    @DisplayName("an unset index reads as 0 rather than null")
    void nullReadsAsZero() {
        Subscription sub = new Subscription();
        sub.setCreditCycleIndex(null);

        assertThat(sub.getCreditCycleIndex()).isZero();
    }

    @Test
    @DisplayName("moving the period start restarts the cycle count (the yearly renewal re-arms the drips)")
    void movingThePeriodStartResetsTheIndex() {
        Subscription sub = new Subscription();
        sub.setCurrentPeriodStart(PERIOD_START);
        sub.setCreditCycleIndex(11);

        sub.setCurrentPeriodStart(PERIOD_START.plusMonths(12));

        // A stale 11 after the renewal would make every cycle of the new year look granted.
        assertThat(sub.getCreditCycleIndex()).isZero();
    }

    @Test
    @DisplayName("re-setting the same period start keeps the index (Stripe re-sends the unchanged period on every update)")
    void sameValueKeepsTheIndex() {
        Subscription sub = new Subscription();
        sub.setCurrentPeriodStart(PERIOD_START);
        sub.setCreditCycleIndex(4);

        sub.setCurrentPeriodStart(PERIOD_START);

        assertThat(sub.getCreditCycleIndex()).isEqualTo(4);
    }

    @Test
    @DisplayName("moving only the period end leaves the index alone - the anchor is the start")
    void movingThePeriodEndDoesNotReset() {
        Subscription sub = new Subscription();
        sub.setCurrentPeriodStart(PERIOD_START);
        sub.setCurrentPeriodEnd(PERIOD_START.plusMonths(12));
        sub.setCreditCycleIndex(4);

        sub.setCurrentPeriodEnd(PERIOD_START.plusMonths(13));

        assertThat(sub.getCreditCycleIndex()).isEqualTo(4);
    }
}
