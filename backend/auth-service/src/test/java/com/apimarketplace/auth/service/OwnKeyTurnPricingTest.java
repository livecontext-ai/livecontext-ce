package com.apimarketplace.auth.service;

import com.apimarketplace.common.credit.ModelTier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("OwnKeyTurnPricing - flat fee per turn on the tenant's own key, by model tier")
class OwnKeyTurnPricingTest {

    private static OwnKeyTurnPricing defaults() {
        return new OwnKeyTurnPricing(new BigDecimal("1"), new BigDecimal("2"), new BigDecimal("5"),
                new BigDecimal("10"), new BigDecimal("2"));
    }

    @Test
    @DisplayName("each tier maps to its configured fee; an unknown or null tier takes the unknown fee")
    void feeByTier() {
        OwnKeyTurnPricing pricing = defaults();

        assertThat(pricing.creditsPerTurn(ModelTier.BUDGET)).isEqualByComparingTo("1");
        assertThat(pricing.creditsPerTurn(ModelTier.MID)).isEqualByComparingTo("2");
        assertThat(pricing.creditsPerTurn(ModelTier.HIGH)).isEqualByComparingTo("5");
        assertThat(pricing.creditsPerTurn(ModelTier.TOP)).isEqualByComparingTo("10");
        assertThat(pricing.creditsPerTurn(ModelTier.UNKNOWN)).isEqualByComparingTo("2");
        assertThat(pricing.creditsPerTurn(null)).isEqualByComparingTo("2");
    }

    @Test
    @DisplayName("a negative or missing fee is a configuration error, never a silent zero")
    void rejectsBadConfig() {
        assertThatThrownBy(() -> new OwnKeyTurnPricing(new BigDecimal("-1"), BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ONE, BigDecimal.ONE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("credits-per-turn.budget");
        assertThatThrownBy(() -> new OwnKeyTurnPricing(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                null, BigDecimal.ONE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("credits-per-turn.top");
    }

    @Test
    @DisplayName("zero is allowed: a tier can be made free without disabling the route")
    void zeroAllowed() {
        OwnKeyTurnPricing free = new OwnKeyTurnPricing(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO);
        assertThat(free.creditsPerTurn(ModelTier.TOP)).isEqualByComparingTo("0");
    }
}
