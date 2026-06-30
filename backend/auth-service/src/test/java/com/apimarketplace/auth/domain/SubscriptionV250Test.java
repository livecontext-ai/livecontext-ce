package com.apimarketplace.auth.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class SubscriptionV250Test {

    @Test
    @DisplayName("paygRemainingCredits defaults to ZERO on new Subscription instance")
    void paygDefaultsToZero() {
        Subscription sub = new Subscription();
        assertThat(sub.getPaygRemainingCredits()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("getRemainingCredits null-coerces to ZERO - symmetry with paygRemainingCredits, guards getTotalBalance NPE")
    void remainingCreditsNullCoercesToZero() {
        Subscription sub = new Subscription();
        sub.setRemainingCredits(null);

        assertThat(sub.getRemainingCredits()).isEqualByComparingTo(BigDecimal.ZERO);
        // getTotalBalance must not NPE on null remainingCredits - explicit guard
        assertThat(sub.getTotalBalance()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("setPaygRemainingCredits(null) coerces to ZERO - guards against legacy callers passing null")
    void setPaygNullCoercesToZero() {
        Subscription sub = new Subscription();
        sub.setPaygRemainingCredits(new BigDecimal("42.5000"));

        sub.setPaygRemainingCredits(null);

        assertThat(sub.getPaygRemainingCredits()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("getTotalBalance sums remaining_credits + payg_remaining_credits - V250 2-bucket model")
    void getTotalBalanceSumsBothBuckets() {
        Subscription sub = new Subscription();
        sub.setRemainingCredits(new BigDecimal("25.0000"));
        sub.setPaygRemainingCredits(new BigDecimal("7.4200"));

        assertThat(sub.getTotalBalance()).isEqualByComparingTo(new BigDecimal("32.4200"));
    }

    @Test
    @DisplayName("getTotalBalance returns sub bucket alone when payg is zero - backward-compat with pre-V250 callers")
    void getTotalBalanceWithZeroPayg() {
        Subscription sub = new Subscription();
        sub.setRemainingCredits(new BigDecimal("100.0000"));
        // payg defaults to ZERO - do not set

        assertThat(sub.getTotalBalance()).isEqualByComparingTo(new BigDecimal("100.0000"));
    }

    @Test
    @DisplayName("getTotalBalance returns payg bucket alone when sub is zero - PAYG-only user pre-Stripe-Team")
    void getTotalBalanceWithZeroSub() {
        Subscription sub = new Subscription();
        sub.setRemainingCredits(BigDecimal.ZERO);
        sub.setPaygRemainingCredits(new BigDecimal("50.0000"));

        assertThat(sub.getTotalBalance()).isEqualByComparingTo(new BigDecimal("50.0000"));
    }

    @Test
    @DisplayName("getTotalBalance returns raw sum when both buckets negative - delinquency invariant (delinquent ⇒ rc + payg ≤ 0) is signum-evaluated in CreditService")
    void getTotalBalanceHandlesNegativeBuckets() {
        Subscription sub = new Subscription();
        sub.setRemainingCredits(new BigDecimal("-5.0000"));
        sub.setPaygRemainingCredits(new BigDecimal("-3.0000"));

        // Aggregator returns the raw sum; the delinquency-clearing branch in
        // CreditService is responsible for comparing signum against ZERO.
        assertThat(sub.getTotalBalance()).isEqualByComparingTo(new BigDecimal("-8.0000"));
    }
}
