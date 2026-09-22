package com.apimarketplace.common.credit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ModelTier - one price-band rule for catalog, billing and UI")
class ModelTierTest {

    @Test
    @DisplayName("bands on output price per 1M: <1.50 budget, <5 mid, <15 high, >=15 top, null unknown")
    void bands() {
        assertThat(ModelTier.classify(null)).isEqualTo(ModelTier.UNKNOWN);
        assertThat(ModelTier.classify(new BigDecimal("0.42"))).isEqualTo(ModelTier.BUDGET);
        assertThat(ModelTier.classify(new BigDecimal("1.49"))).isEqualTo(ModelTier.BUDGET);
        assertThat(ModelTier.classify(new BigDecimal("1.50"))).isEqualTo(ModelTier.MID);
        assertThat(ModelTier.classify(new BigDecimal("4.99"))).isEqualTo(ModelTier.MID);
        assertThat(ModelTier.classify(new BigDecimal("5.00"))).isEqualTo(ModelTier.HIGH);
        assertThat(ModelTier.classify(new BigDecimal("14.99"))).isEqualTo(ModelTier.HIGH);
        assertThat(ModelTier.classify(new BigDecimal("15.00"))).isEqualTo(ModelTier.TOP);
        assertThat(ModelTier.classify(new BigDecimal("75"))).isEqualTo(ModelTier.TOP);
    }

    @Test
    @DisplayName("keys round-trip with the stored catalog spelling; anything else is unknown")
    void keys() {
        for (ModelTier tier : ModelTier.values()) {
            assertThat(ModelTier.fromKey(tier.key())).isEqualTo(tier);
            assertThat(ModelTier.fromKey(" " + tier.key().toUpperCase() + " ")).isEqualTo(tier);
        }
        assertThat(ModelTier.fromKey("premium")).isEqualTo(ModelTier.UNKNOWN);
        assertThat(ModelTier.fromKey(null)).isEqualTo(ModelTier.UNKNOWN);
    }
}
