package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.ModelPricing;
import com.apimarketplace.auth.repository.ModelPricingRepository;
import com.apimarketplace.common.credit.ModelTier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * The two numbers an own-key turn needs from pricing: the model's price band (for the flat
 * fee) and the tokens at the provider's LIST price (the estimate of the provider's charge),
 * which is the platform bill BEFORE the cloud multiplier.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ModelPricingService - tierOf and providerCost")
class ModelPricingServiceTierAndProviderCostTest {

    @Mock private ModelPricingRepository pricingRepository;
    private ModelPricingService pricingService;

    @BeforeEach
    void setUp() {
        // Cloud multiplier 2: the platform bills twice the provider's list price.
        pricingService = new ModelPricingService(pricingRepository, new BigDecimal("2.0"));
    }

    private static ModelPricing persisted(String provider, String model, String in, String out) {
        ModelPricing p = new ModelPricing();
        p.setProvider(provider);
        p.setModel(model);
        p.setInputRate(new BigDecimal(in));
        p.setOutputRate(new BigDecimal(out));
        p.setFixedCost(BigDecimal.ZERO);
        ReflectionTestUtils.setField(p, "id", 7);   // a real row, not the default-fallback stub
        return p;
    }

    @Test
    @DisplayName("tierOf classifies a priced model on its output rate with the platform-wide bands")
    void tierOfPricedModel() {
        when(pricingRepository.findCurrentPricing("anthropic", "claude-opus")).thenReturn(Optional.of(persisted("anthropic", "claude-opus", "15", "75")));
        when(pricingRepository.findCurrentPricing("openai", "gpt-mini")).thenReturn(Optional.of(persisted("openai", "gpt-mini", "0.75", "4.50")));
        when(pricingRepository.findCurrentPricing("deepseek", "deepseek-chat")).thenReturn(Optional.of(persisted("deepseek", "deepseek-chat", "0.28", "0.42")));

        assertThat(pricingService.tierOf("anthropic", "claude-opus")).isEqualTo(ModelTier.TOP);
        assertThat(pricingService.tierOf("openai", "gpt-mini")).isEqualTo(ModelTier.MID);
        assertThat(pricingService.tierOf("deepseek", "deepseek-chat")).isEqualTo(ModelTier.BUDGET);
    }

    @Test
    @DisplayName("tierOf is UNKNOWN for a model with no pricing row: the default mid-tier fallback rates must not classify it")
    void tierOfUnknownModel() {
        when(pricingRepository.findCurrentPricing("acme", "mystery")).thenReturn(Optional.empty());

        assertThat(pricingService.tierOf("acme", "mystery")).isEqualTo(ModelTier.UNKNOWN);
        assertThat(pricingService.tierOf(null, "x")).isEqualTo(ModelTier.UNKNOWN);
    }

    @Test
    @DisplayName("providerCost is the list-price bill; calculateCost is that times the cloud multiplier")
    void providerCostIsBeforeTheMultiplier() {
        when(pricingRepository.findCurrentPricing("anthropic", "claude-sonnet")).thenReturn(Optional.of(persisted("anthropic", "claude-sonnet", "3", "15")));
        LlmTokenBreakdown usage = LlmTokenBreakdown.of(10_000, 1_000);

        // 10k input at $3/1M = 30 credits, 1k output at $15/1M = 15 credits (1 credit = $0.001).
        assertThat(pricingService.providerCost("anthropic", "claude-sonnet", usage)).isEqualByComparingTo("45");
        assertThat(pricingService.calculateCost("anthropic", "claude-sonnet", usage)).isEqualByComparingTo("90");
    }
}
