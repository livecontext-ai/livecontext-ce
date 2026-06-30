package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.ModelPricing;
import com.apimarketplace.auth.repository.ModelPricingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for {@link ModelPricingService#calculateUnitCost} - the per-UNIT
 * (e.g. per-image) billing path. It was previously only ever <em>mocked</em> in
 * {@code CreditService*Test}, so its real body had no direct test. Unlike
 * {@link ModelPricingService#calculateCost}, this path is deliberately NOT scaled
 * by 1000 and NOT multiplied by the cloud LLM margin: image seeds store the whole
 * per-image credit cost in {@code input_rate}. These tests pin that contract and
 * the dangerous default-rate fail-open the Javadoc warns about.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ModelPricingService.calculateUnitCost")
class ModelPricingServiceCalculateUnitCostTest {

    @Mock
    private ModelPricingRepository pricingRepository;

    private ModelPricingService service;

    @BeforeEach
    void setUp() {
        // Zero-margin (multiplier 1.0): proves calculateUnitCost ignores the cloud
        // multiplier entirely - even at 1.0 the result is pure inputRate*units+fixed.
        service = new ModelPricingService(pricingRepository, BigDecimal.ONE);
    }

    private void mockPricing(String provider, String model, String inputRate, String fixedCost) {
        ModelPricing pricing = new ModelPricing();
        pricing.setProvider(provider);
        pricing.setModel(model);
        pricing.setInputRate(new BigDecimal(inputRate));
        pricing.setOutputRate(new BigDecimal("4.0"));
        pricing.setFixedCost(new BigDecimal(fixedCost));
        when(pricingRepository.findCurrentPricing(provider, model)).thenReturn(Optional.of(pricing));
    }

    @ParameterizedTest(name = "units={0} → ZERO without a pricing lookup")
    @ValueSource(ints = {0, -1, -100})
    @DisplayName("non-positive units short-circuit to ZERO before any pricing lookup")
    void nonPositiveUnitsReturnZero(int units) {
        BigDecimal cost = service.calculateUnitCost("openai", "gpt-image-1-low", units);

        assertThat(cost).isEqualByComparingTo(BigDecimal.ZERO);
        // The early return must avoid the repository entirely (no default-row warning, no query).
        verifyNoInteractions(pricingRepository);
    }

    @Test
    @DisplayName("a known row charges inputRate*units with NO /1000 scaling")
    void knownRowChargesInputRateTimesUnits() {
        mockPricing("openai", "gpt-image-1-low", "10", "0");

        BigDecimal cost = service.calculateUnitCost("openai", "gpt-image-1-low", 3);

        // 10 credits/image * 3 = 30 - NOT 0.03: this path does NOT divide by 1000 like calculateCost.
        assertThat(cost).isEqualByComparingTo("30");
    }

    @Test
    @DisplayName("never applies the cloud LLM multiplier - even with a 1.8x service on a non-image model")
    void ignoresCloudMultiplierEvenForNonImageModel() {
        // A non-1.0 multiplier AND a non-image model (gpt-4o) - the exact case where
        // calculateCost() WOULD apply the 1.8x margin. calculateUnitCost must NOT:
        // it returns inputRate*units unscaled. (With the @BeforeEach 1.0 service + image
        // models, a regression adding the multiplier would be invisible - this catches it.)
        ModelPricingService margined = new ModelPricingService(pricingRepository, new BigDecimal("1.8"));
        mockPricing("openai", "gpt-4o", "10", "0");

        BigDecimal cost = margined.calculateUnitCost("openai", "gpt-4o", 3);

        // 10 * 3 = 30, NOT 54 (no x1.8).
        assertThat(cost).isEqualByComparingTo("30");
    }

    @Test
    @DisplayName("a non-zero fixed cost is added once on top of inputRate*units")
    void addsFixedCostOnce() {
        mockPricing("openai", "gpt-image-1-high", "80", "5");

        BigDecimal cost = service.calculateUnitCost("openai", "gpt-image-1-high", 2);

        // 80*2 + 5 = 165.
        assertThat(cost).isEqualByComparingTo("165");
    }

    @Test
    @DisplayName("an unknown model fails open to the default input rate (the documented hazard)")
    void unknownModelFallsOpenToDefaultRate() {
        when(pricingRepository.findCurrentPricing("mystery", "unpriced-model"))
                .thenReturn(Optional.empty());

        BigDecimal cost = service.calculateUnitCost("mystery", "unpriced-model", 7);

        // DEFAULT_INPUT_RATE = 1.0 per unit, fixedCost 0 → 7. This is the silent
        // under-charge the Javadoc warns callers to guard against via hasPricing().
        assertThat(cost).isEqualByComparingTo("7");
    }
}
