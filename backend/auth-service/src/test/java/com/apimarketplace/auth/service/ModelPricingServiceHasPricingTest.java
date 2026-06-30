package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.ModelPricing;
import com.apimarketplace.auth.repository.ModelPricingRepository;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Focused test suite for {@link ModelPricingService#hasPricing(String, String)}.
 *
 * <p>This method backs the chat budget gate - it is the single authoritative signal
 * that tells {@code CreditController.checkChatBudget} whether it can safely trust
 * {@link ModelPricingService#calculateCost}. If {@code hasPricing} returns a false
 * positive (says pricing exists when it doesn't), the controller would call
 * {@code calculateCost} which would silently fall back to default mid-tier rates
 * and let a frontier-model turn through the gate. That is the regression these
 * tests lock down.
 */
@DisplayName("ModelPricingService.hasPricing")
@ExtendWith(MockitoExtension.class)
class ModelPricingServiceHasPricingTest {

    @Mock
    private ModelPricingRepository pricingRepository;

    private ModelPricingService pricingService;

    @BeforeEach
    void setUp() {
        pricingService = new ModelPricingService(pricingRepository, BigDecimal.ONE);
    }

    private ModelPricing persistedPricing(String provider, String model) {
        ModelPricing p = new ModelPricing();
        p.setProvider(provider);
        p.setModel(model);
        p.setInputRate(new BigDecimal("15.0"));
        p.setOutputRate(new BigDecimal("75.0"));
        p.setFixedCost(BigDecimal.ZERO);
        // Simulate persistence: id is assigned by the DB. hasPricing uses id != null
        // to distinguish a real cached row from a cached default-fallback stub.
        ReflectionTestUtils.setField(p, "id", 42);
        return p;
    }

    @Test
    @DisplayName("returns false for null provider without touching the repo")
    void falseForNullProvider() {
        assertThat(pricingService.hasPricing(null, "gpt-4")).isFalse();
        verifyNoInteractions(pricingRepository);
    }

    @Test
    @DisplayName("returns false for blank provider without touching the repo")
    void falseForBlankProvider() {
        assertThat(pricingService.hasPricing("  ", "gpt-4")).isFalse();
        verifyNoInteractions(pricingRepository);
    }

    @Test
    @DisplayName("returns false for null model without touching the repo")
    void falseForNullModel() {
        assertThat(pricingService.hasPricing("openai", null)).isFalse();
        verifyNoInteractions(pricingRepository);
    }

    @Test
    @DisplayName("returns false for blank model without touching the repo")
    void falseForBlankModel() {
        assertThat(pricingService.hasPricing("openai", "")).isFalse();
        verifyNoInteractions(pricingRepository);
    }

    @Test
    @DisplayName("returns true when the repository has a persisted row")
    void trueWhenRowExists() {
        when(pricingRepository.findCurrentPricing("openai", "gpt-4"))
                .thenReturn(Optional.of(persistedPricing("openai", "gpt-4")));

        assertThat(pricingService.hasPricing("openai", "gpt-4")).isTrue();
    }

    @Test
    @DisplayName("returns false when the repository has no row - the regression guard")
    void falseWhenRowMissing() {
        // Regression: before this method, CreditController.checkChatBudget went
        // straight to calculateCost, which fell back to default rates on miss and
        // let frontier-model turns through the gate. Missing row must be a hard
        // deny from the budget path.
        when(pricingRepository.findCurrentPricing("bridge", "claude-sonnet-4-6"))
                .thenReturn(Optional.empty());

        assertThat(pricingService.hasPricing("bridge", "claude-sonnet-4-6")).isFalse();
    }

    @Test
    @DisplayName("returns true from cache after calculateCost loaded a persisted row")
    void trueAfterCalculateCostPopulatedCache() {
        when(pricingRepository.findCurrentPricing("openai", "gpt-4"))
                .thenReturn(Optional.of(persistedPricing("openai", "gpt-4")));

        // Warm the cache through calculateCost (the normal hot path).
        pricingService.calculateCost("openai", "gpt-4", 100, 200);
        // Now hasPricing MUST serve from cache - no second repo hit.
        assertThat(pricingService.hasPricing("openai", "gpt-4")).isTrue();
        verify(pricingRepository, times(1)).findCurrentPricing("openai", "gpt-4");
    }

    @Test
    @DisplayName("returns false from cache when calculateCost stored a default-fallback stub")
    void falseWhenCacheHoldsDefaultFallback() {
        // calculateCost() caches a fallback entity with id=null when the repo is empty.
        // hasPricing() must NOT treat that fallback as a real pricing row - doing so
        // would reintroduce the fail-open path that motivated this fix.
        when(pricingRepository.findCurrentPricing("unknown", "weird-model"))
                .thenReturn(Optional.empty());

        pricingService.calculateCost("unknown", "weird-model", 100, 200);
        assertThat(pricingService.hasPricing("unknown", "weird-model")).isFalse();
    }
}
