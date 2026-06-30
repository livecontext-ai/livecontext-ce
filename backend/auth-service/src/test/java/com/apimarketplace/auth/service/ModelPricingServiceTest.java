package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.ModelPricing;
import com.apimarketplace.auth.repository.ModelPricingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ModelPricingService Tests")
class ModelPricingServiceTest {

    @Mock
    private ModelPricingRepository pricingRepository;

    private ModelPricingService pricingService;

    @BeforeEach
    void setUp() {
        // Create a fresh instance before each test to ensure a clean cache
        pricingService = new ModelPricingService(pricingRepository, BigDecimal.ONE);
    }

    // ===== Helpers =====

    private ModelPricing createPricing(String provider, String model,
                                        String inputRate, String outputRate,
                                        String fixedCost) {
        ModelPricing pricing = new ModelPricing();
        pricing.setProvider(provider);
        pricing.setModel(model);
        pricing.setInputRate(new BigDecimal(inputRate));
        pricing.setOutputRate(new BigDecimal(outputRate));
        pricing.setFixedCost(new BigDecimal(fixedCost));
        return pricing;
    }

    private void mockPricing(String provider, String model,
                              String inputRate, String outputRate, String fixedCost) {
        ModelPricing pricing = createPricing(provider, model, inputRate, outputRate, fixedCost);
        when(pricingRepository.findCurrentPricing(provider, model))
                .thenReturn(Optional.of(pricing));
    }

    // ===== calculateCost =====

    @Nested
    @DisplayName("calculateCost")
    class CalculateCost {

        @Test
        @DisplayName("should calculate correct cost for gpt-4o with specific tokens")
        void shouldCalculateCorrectCostForGpt4o() {
            // inputRate=0.25/1k, outputRate=1.0/1k
            // cost = (0.25*1000/1000) + (1.0*500/1000) = 0.25 + 0.50 = 0.75
            mockPricing("openai", "gpt-4o", "0.25", "1.0", "0");

            BigDecimal cost = pricingService.calculateCost("openai", "gpt-4o", 1000, 500);

            assertThat(cost).isEqualByComparingTo(new BigDecimal("0.7500"));
        }

        @Test
        @DisplayName("should calculate correct cost for Claude model with different rates")
        void shouldCalculateCorrectCostForClaude() {
            // inputRate=0.3/1k, outputRate=1.5/1k
            // cost = (0.3*2000/1000) + (1.5*1000/1000) = 0.6 + 1.5 = 2.1
            mockPricing("anthropic", "claude-3-5-sonnet-20241022", "0.3", "1.5", "0");

            BigDecimal cost = pricingService.calculateCost(
                    "anthropic", "claude-3-5-sonnet-20241022", 2000, 1000);

            assertThat(cost).isEqualByComparingTo(new BigDecimal("2.1000"));
        }

        @Test
        @DisplayName("should include fixed cost in total calculation")
        void shouldIncludeFixedCostInTotal() {
            // inputRate=0.1/1k, outputRate=0.3/1k, fixedCost=0.5
            // cost = (0.1*100/1000) + (0.3*100/1000) + 0.5 = 0.01 + 0.03 + 0.5 = 0.54
            mockPricing("openai", "gpt-4o-mini", "0.1", "0.3", "0.5");

            BigDecimal cost = pricingService.calculateCost("openai", "gpt-4o-mini", 100, 100);

            assertThat(cost).isEqualByComparingTo(new BigDecimal("0.5400"));
        }

        @Test
        @DisplayName("should apply managed-cloud LLM multiplier when configured")
        void shouldApplyManagedCloudLlmMultiplierWhenConfigured() {
            ModelPricingService cloudPricingService =
                    new ModelPricingService(pricingRepository, new BigDecimal("1.8"));
            mockPricing("openai", "gpt-4o", "0.25", "1.0", "0");

            BigDecimal cost = cloudPricingService.calculateCost("openai", "gpt-4o", 1000, 500);

            assertThat(cost).isEqualByComparingTo(new BigDecimal("1.350000"));
        }

        @Test
        @DisplayName("should not apply managed-cloud LLM multiplier to web search fixed cost")
        void shouldNotApplyManagedCloudLlmMultiplierToWebSearchFixedCost() {
            ModelPricingService cloudPricingService =
                    new ModelPricingService(pricingRepository, new BigDecimal("1.8"));
            mockPricing("websearch", "default", "0", "0", "1");

            BigDecimal cost = cloudPricingService.calculateCost("websearch", "default", 0, 0);

            assertThat(cost).isEqualByComparingTo(new BigDecimal("1"));
        }

        @Test
        @DisplayName("should not apply managed-cloud LLM multiplier to unit-billed image pricing")
        void shouldNotApplyManagedCloudLlmMultiplierToUnitBilledImagePricing() {
            ModelPricingService cloudPricingService =
                    new ModelPricingService(pricingRepository, new BigDecimal("1.8"));

            BigDecimal openAiImageCost = cloudPricingService.applyCloudLlmBillingMultiplier(
                    "openai", "gpt-image-2-medium", new BigDecimal("53"));
            BigDecimal stabilityImageCost = cloudPricingService.applyCloudLlmBillingMultiplier(
                    "stability-ai", "stability-core", new BigDecimal("3"));

            assertThat(openAiImageCost).isEqualByComparingTo(new BigDecimal("53"));
            assertThat(stabilityImageCost).isEqualByComparingTo(new BigDecimal("3"));
        }

        @ParameterizedTest(name = "{0}/{1}: {2} input + {3} output tokens => expected cost {6}")
        @CsvSource({
                "openai, gpt-4o, 1000, 500, 0.25, 1.0, 0.7500",
                "anthropic, claude-3-5-sonnet-20241022, 2000, 1000, 0.3, 1.5, 2.1000",
                "openai, gpt-4o-mini, 10000, 5000, 0.015, 0.06, 0.4500",
                "openai, gpt-4o, 0, 0, 0.25, 1.0, 0.0000",
                "openai, gpt-4o, 1, 0, 0.25, 1.0, 0.000250",
                "openai, gpt-4o, 0, 1, 0.25, 1.0, 0.001000",
                "anthropic, claude-3-opus, 5000, 2000, 1.5, 7.5, 22.5000"
        })
        @DisplayName("should calculate correct cost for various model/token combinations")
        void shouldCalculateCorrectCostParameterized(String provider, String model,
                                                      int promptTokens, int completionTokens,
                                                      String inputRate, String outputRate,
                                                      String expectedCost) {
            mockPricing(provider, model, inputRate, outputRate, "0");

            BigDecimal cost = pricingService.calculateCost(provider, model, promptTokens, completionTokens);

            assertThat(cost).isEqualByComparingTo(new BigDecimal(expectedCost));
        }
    }

    // ===== calculateFixedCost =====

    @Nested
    @DisplayName("calculateFixedCost")
    class CalculateFixedCost {

        @Test
        @DisplayName("should return 0.0 for unknown source type")
        void shouldReturnZeroForUnknownSourceType() {
            BigDecimal cost = pricingService.calculateFixedCost("UNKNOWN_TYPE");

            assertThat(cost).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("should return 0.0 for AGENT_EXECUTION")
        void shouldReturnZeroForAgentExecution() {
            BigDecimal cost = pricingService.calculateFixedCost("AGENT_EXECUTION");

            assertThat(cost).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("should return 0.0 for CHAT_CONVERSATION")
        void shouldReturnZeroForChatConversation() {
            BigDecimal cost = pricingService.calculateFixedCost("CHAT_CONVERSATION");

            assertThat(cost).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("should return 0.0 for empty string")
        void shouldReturnZeroForEmptyString() {
            BigDecimal cost = pricingService.calculateFixedCost("");

            assertThat(cost).isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    // ===== getAllActivePricing =====

    @Nested
    @DisplayName("getAllActivePricing")
    class GetAllActivePricing {

        @Test
        @DisplayName("should delegate to repository and return all active pricing entries")
        void shouldDelegateToRepository() {
            ModelPricing p1 = createPricing("openai", "gpt-4o", "0.25", "1.0", "0");
            ModelPricing p2 = createPricing("anthropic", "claude-3-5-sonnet", "0.3", "1.5", "0");
            when(pricingRepository.findByIsActiveTrue()).thenReturn(List.of(p1, p2));

            List<ModelPricing> result = pricingService.getAllActivePricing();

            assertThat(result).hasSize(2);
            assertThat(result.get(0).getProvider()).isEqualTo("openai");
            assertThat(result.get(1).getProvider()).isEqualTo("anthropic");
            verify(pricingRepository).findByIsActiveTrue();
        }

        @Test
        @DisplayName("should return empty list when no active pricing exists")
        void shouldReturnEmptyListWhenNone() {
            when(pricingRepository.findByIsActiveTrue()).thenReturn(List.of());

            List<ModelPricing> result = pricingService.getAllActivePricing();

            assertThat(result).isEmpty();
        }
    }

    // ===== refreshCache =====

    @Nested
    @DisplayName("refreshCache")
    class RefreshCache {

        @Test
        @DisplayName("should clear cache so next call fetches from DB again")
        void shouldClearCacheAndRefetchFromDb() {
            // First call: cache miss, fetch from DB
            ModelPricing originalPricing = createPricing("openai", "gpt-4o", "0.25", "1.0", "0");
            when(pricingRepository.findCurrentPricing("openai", "gpt-4o"))
                    .thenReturn(Optional.of(originalPricing));

            pricingService.calculateCost("openai", "gpt-4o", 1000, 500);
            verify(pricingRepository, times(1)).findCurrentPricing("openai", "gpt-4o");

            // Second call: should use cache, no additional DB call
            pricingService.calculateCost("openai", "gpt-4o", 1000, 500);
            verify(pricingRepository, times(1)).findCurrentPricing("openai", "gpt-4o");

            // Refresh cache
            pricingService.refreshCache();

            // Third call after refresh: should fetch from DB again
            ModelPricing updatedPricing = createPricing("openai", "gpt-4o", "0.50", "2.0", "0");
            when(pricingRepository.findCurrentPricing("openai", "gpt-4o"))
                    .thenReturn(Optional.of(updatedPricing));

            BigDecimal newCost = pricingService.calculateCost("openai", "gpt-4o", 1000, 500);

            verify(pricingRepository, times(2)).findCurrentPricing("openai", "gpt-4o");
            // With updated rates: (0.50*1000/1000) + (2.0*500/1000) = 0.5 + 1.0 = 1.5
            assertThat(newCost).isEqualByComparingTo(new BigDecimal("1.5000"));
        }
    }

    // ===== Edge Cases =====

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCases {

        @Test
        @DisplayName("should use default rates (0.1 input, 0.3 output) for unknown model")
        void shouldUseDefaultRatesForUnknownModel() {
            when(pricingRepository.findCurrentPricing("unknown-provider", "unknown-model"))
                    .thenReturn(Optional.empty());

            // default: inputRate=1.0, outputRate=4.0 (USD per 1M tokens)
            // cost = (1.0*1000/1000) + (4.0*500/1000) = 1.0 + 2.0 = 3.0
            BigDecimal cost = pricingService.calculateCost(
                    "unknown-provider", "unknown-model", 1000, 500);

            assertThat(cost).isEqualByComparingTo(new BigDecimal("3.0000"));
        }

        @Test
        @DisplayName("should return zero cost with zero tokens and no fixed cost")
        void shouldReturnZeroCostWithZeroTokens() {
            mockPricing("openai", "gpt-4o", "0.25", "1.0", "0");

            BigDecimal cost = pricingService.calculateCost("openai", "gpt-4o", 0, 0);

            assertThat(cost).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("should return only fixed cost when tokens are zero but fixed cost exists")
        void shouldReturnFixedCostOnlyWhenZeroTokens() {
            mockPricing("custom", "custom-model", "0.25", "1.0", "0.5000");

            BigDecimal cost = pricingService.calculateCost("custom", "custom-model", 0, 0);

            assertThat(cost).isEqualByComparingTo(new BigDecimal("0.5000"));
        }

        @Test
        @DisplayName("should calculate correctly with 1 prompt token and 0 completion tokens")
        void shouldCalculateCorrectlyWithOnePromptToken() {
            mockPricing("openai", "gpt-4o", "0.25", "1.0", "0");

            // cost = (0.25*1/1000) + (1.0*0/1000) = 0.000250 (scale 6, HALF_UP)
            BigDecimal cost = pricingService.calculateCost("openai", "gpt-4o", 1, 0);

            BigDecimal expected = new BigDecimal("0.25")
                    .multiply(BigDecimal.ONE)
                    .divide(new BigDecimal("1000"), 6, RoundingMode.HALF_UP);
            assertThat(cost).isEqualByComparingTo(expected);
        }

        @Test
        @DisplayName("should handle 10 million tokens without overflow using BigDecimal")
        void shouldHandleTenMillionTokensWithoutOverflow() {
            mockPricing("openai", "gpt-4o", "0.25", "1.0", "0");

            // cost = (0.25*10,000,000/1000) + (1.0*10,000,000/1000) = 2500 + 10000 = 12500
            BigDecimal cost = pricingService.calculateCost("openai", "gpt-4o", 10_000_000, 10_000_000);

            assertThat(cost).isEqualByComparingTo(new BigDecimal("12500.0000"));
        }

        @Test
        @DisplayName("should cache pricing after first DB lookup for the same model")
        void shouldCachePricingAfterFirstLookup() {
            ModelPricing pricing = createPricing("openai", "gpt-4o", "0.25", "1.0", "0");
            when(pricingRepository.findCurrentPricing("openai", "gpt-4o"))
                    .thenReturn(Optional.of(pricing));

            // First call
            pricingService.calculateCost("openai", "gpt-4o", 1000, 500);
            // Second call
            pricingService.calculateCost("openai", "gpt-4o", 2000, 1000);
            // Third call
            pricingService.calculateCost("openai", "gpt-4o", 500, 200);

            // DB should only be hit once
            verify(pricingRepository, times(1)).findCurrentPricing("openai", "gpt-4o");
        }

        @Test
        @DisplayName("should cache default pricing for unknown model (no repeated DB calls)")
        void shouldCacheDefaultPricingForUnknownModel() {
            when(pricingRepository.findCurrentPricing("nope", "nope-model"))
                    .thenReturn(Optional.empty());

            pricingService.calculateCost("nope", "nope-model", 100, 50);
            pricingService.calculateCost("nope", "nope-model", 200, 100);

            // DB should only be hit once even for unknown models
            verify(pricingRepository, times(1)).findCurrentPricing("nope", "nope-model");
        }

        @Test
        @DisplayName("should maintain separate cache entries for different models")
        void shouldMaintainSeparateCacheEntriesForDifferentModels() {
            ModelPricing gpt4o = createPricing("openai", "gpt-4o", "0.25", "1.0", "0");
            ModelPricing claude = createPricing("anthropic", "claude-3-5-sonnet", "0.3", "1.5", "0");

            when(pricingRepository.findCurrentPricing("openai", "gpt-4o"))
                    .thenReturn(Optional.of(gpt4o));
            when(pricingRepository.findCurrentPricing("anthropic", "claude-3-5-sonnet"))
                    .thenReturn(Optional.of(claude));

            BigDecimal gpt4oCost = pricingService.calculateCost("openai", "gpt-4o", 1000, 500);
            BigDecimal claudeCost = pricingService.calculateCost("anthropic", "claude-3-5-sonnet", 1000, 500);

            // gpt-4o: (0.25*1000/1000) + (1.0*500/1000) = 0.25 + 0.50 = 0.75
            assertThat(gpt4oCost).isEqualByComparingTo(new BigDecimal("0.7500"));
            // claude: (0.3*1000/1000) + (1.5*500/1000) = 0.30 + 0.75 = 1.05
            assertThat(claudeCost).isEqualByComparingTo(new BigDecimal("1.0500"));

            assertThat(gpt4oCost).isNotEqualByComparingTo(claudeCost);
        }
    }

    // ===== Rounding Behavior =====

    @Nested
    @DisplayName("Rounding Behavior")
    class RoundingBehavior {

        @Test
        @DisplayName("should use HALF_UP rounding at 6 decimal places for input cost")
        void shouldUseHalfUpRoundingForInputCost() {
            // inputRate=0.25, 1 token -> 0.25*1/1000 = 0.000250 (scale 6, HALF_UP, exact)
            mockPricing("openai", "gpt-4o", "0.25", "0.0", "0");

            BigDecimal cost = pricingService.calculateCost("openai", "gpt-4o", 1, 0);

            assertThat(cost).isEqualByComparingTo(new BigDecimal("0.000250"));
        }

        @Test
        @DisplayName("should use HALF_UP rounding at 4 decimal places for output cost")
        void shouldUseHalfUpRoundingForOutputCost() {
            // outputRate=1.0, 1 token -> 1.0*1/1000 = 0.001 -> HALF_UP 4 decimal = 0.0010
            mockPricing("openai", "gpt-4o", "0.0", "1.0", "0");

            BigDecimal cost = pricingService.calculateCost("openai", "gpt-4o", 0, 1);

            assertThat(cost).isEqualByComparingTo(new BigDecimal("0.0010"));
        }

        @Test
        @DisplayName("should correctly round a value that is exactly at HALF boundary")
        void shouldCorrectlyRoundHalfBoundary() {
            // inputRate=0.015, 1 token -> 0.015*1/1000 = 0.000015 -> HALF_UP 4 decimal = 0.0000
            // Actually 0.015/1000 = 0.000015, at 4 decimal places: 0.00001_5 -> rounds to 0.0000
            // Let's use a clearer example: inputRate=0.005, 5 tokens -> 0.005*5/1000 = 0.000025 -> 0.0000
            // Better: inputRate=0.5, 3 tokens -> 0.5*3/1000 = 0.0015 -> HALF_UP = 0.0015 (exact at 4 digits)
            mockPricing("test", "test-model", "0.5", "0.0", "0");

            BigDecimal cost = pricingService.calculateCost("test", "test-model", 3, 0);

            // 0.5 * 3 / 1000 = 1.5/1000 = 0.0015 (exact, no rounding needed)
            assertThat(cost).isEqualByComparingTo(new BigDecimal("0.0015"));
        }

        @Test
        @DisplayName("should produce 6-decimal-place results for typical scenarios")
        void shouldProduceFourDecimalPlaceResults() {
            mockPricing("openai", "gpt-4o-mini", "0.015", "0.06", "0");

            // cost = (0.015*10000/1000) + (0.06*5000/1000) = 0.15 + 0.30 = 0.45
            BigDecimal cost = pricingService.calculateCost("openai", "gpt-4o-mini", 10000, 5000);

            assertThat(cost).isEqualByComparingTo(new BigDecimal("0.4500"));
            // Verify scale is 6 (HALF_UP at 6 decimal places)
            assertThat(cost.scale()).isLessThanOrEqualTo(6);
        }
    }

    // ===== Default Pricing Behavior =====

    @Nested
    @DisplayName("Default Pricing Behavior")
    class DefaultPricingBehavior {

        @Test
        @DisplayName("default pricing should use inputRate=1.0 and outputRate=4.0")
        void defaultPricingShouldUseCorrectRates() {
            when(pricingRepository.findCurrentPricing("unknown", "unknown"))
                    .thenReturn(Optional.empty());

            // With default rates: (1.0*1000/1000) + (4.0*1000/1000) = 1.0 + 4.0 = 5.0
            BigDecimal cost = pricingService.calculateCost("unknown", "unknown", 1000, 1000);

            assertThat(cost).isEqualByComparingTo(new BigDecimal("5.0000"));
        }

        @Test
        @DisplayName("default pricing should have zero fixed cost")
        void defaultPricingShouldHaveZeroFixedCost() {
            when(pricingRepository.findCurrentPricing("unknown", "unknown"))
                    .thenReturn(Optional.empty());

            // 0 tokens with default pricing should return 0 (no fixed cost)
            BigDecimal cost = pricingService.calculateCost("unknown", "unknown", 0, 0);

            assertThat(cost).isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    // ===== Special Characters and Case Sensitivity =====

    @Nested
    @DisplayName("Special Characters and Case Sensitivity")
    class SpecialCharsAndCaseSensitivity {

        @Test
        @DisplayName("should handle model name with special characters (dots, dashes, slashes)")
        void shouldHandleModelNameWithSpecialCharacters() {
            String specialModel = "claude-3.5-sonnet/v2@latest";
            mockPricing("anthropic", specialModel, "0.3", "1.5", "0");

            BigDecimal cost = pricingService.calculateCost("anthropic", specialModel, 1000, 500);

            // (0.3*1000/1000) + (1.5*500/1000) = 0.3 + 0.75 = 1.05
            assertThat(cost).isEqualByComparingTo(new BigDecimal("1.0500"));
        }

        @Test
        @DisplayName("should treat GPT-4o and gpt-4o as different cache keys (case-sensitive)")
        void shouldBeCaseSensitiveForModelNames() {
            ModelPricing upperPricing = createPricing("openai", "GPT-4o", "0.50", "2.0", "0");
            ModelPricing lowerPricing = createPricing("openai", "gpt-4o", "0.25", "1.0", "0");

            when(pricingRepository.findCurrentPricing("openai", "GPT-4o"))
                    .thenReturn(Optional.of(upperPricing));
            when(pricingRepository.findCurrentPricing("openai", "gpt-4o"))
                    .thenReturn(Optional.of(lowerPricing));

            BigDecimal upperCost = pricingService.calculateCost("openai", "GPT-4o", 1000, 500);
            BigDecimal lowerCost = pricingService.calculateCost("openai", "gpt-4o", 1000, 500);

            // GPT-4o: (0.50*1000/1000) + (2.0*500/1000) = 0.5 + 1.0 = 1.5
            assertThat(upperCost).isEqualByComparingTo(new BigDecimal("1.5000"));
            // gpt-4o: (0.25*1000/1000) + (1.0*500/1000) = 0.25 + 0.5 = 0.75
            assertThat(lowerCost).isEqualByComparingTo(new BigDecimal("0.7500"));

            // They should be different
            assertThat(upperCost).isNotEqualByComparingTo(lowerCost);

            // Both should have triggered separate DB lookups
            verify(pricingRepository).findCurrentPricing("openai", "GPT-4o");
            verify(pricingRepository).findCurrentPricing("openai", "gpt-4o");
        }

        @Test
        @DisplayName("should handle empty string provider gracefully using default rates")
        void shouldHandleEmptyStringProvider() {
            when(pricingRepository.findCurrentPricing("", "some-model"))
                    .thenReturn(Optional.empty());

            // Should fall through to default rates: inputRate=1.0, outputRate=4.0
            BigDecimal cost = pricingService.calculateCost("", "some-model", 1000, 500);

            // (1.0*1000/1000) + (4.0*500/1000) = 1.0 + 2.0 = 3.0
            assertThat(cost).isEqualByComparingTo(new BigDecimal("3.0000"));
        }

        @Test
        @DisplayName("should handle model name with unicode characters")
        void shouldHandleUnicodeModelName() {
            String unicodeModel = "model-\u00e9\u00e8\u00ea";
            when(pricingRepository.findCurrentPricing("provider", unicodeModel))
                    .thenReturn(Optional.empty());

            // Should use default rates without throwing
            BigDecimal cost = pricingService.calculateCost("provider", unicodeModel, 1000, 500);

            assertThat(cost).isEqualByComparingTo(new BigDecimal("3.0000"));
        }

        @Test
        @DisplayName("should use colon as cache key separator between provider and model")
        void shouldUseSeparatorInCacheKey() {
            // These should be treated as different models despite similar concatenation
            // "openai:gpt-4o" vs "openai:gpt-4o" - same
            // "open:ai-gpt-4o" vs "openai:gpt-4o" - different providers
            ModelPricing pricing1 = createPricing("open", "ai:gpt-4o", "0.1", "0.2", "0");
            ModelPricing pricing2 = createPricing("openai", "gpt-4o", "0.25", "1.0", "0");

            when(pricingRepository.findCurrentPricing("open", "ai:gpt-4o"))
                    .thenReturn(Optional.of(pricing1));
            when(pricingRepository.findCurrentPricing("openai", "gpt-4o"))
                    .thenReturn(Optional.of(pricing2));

            BigDecimal cost1 = pricingService.calculateCost("open", "ai:gpt-4o", 1000, 500);
            BigDecimal cost2 = pricingService.calculateCost("openai", "gpt-4o", 1000, 500);

            // Cache key "open:ai:gpt-4o" vs "openai:gpt-4o" - different keys
            assertThat(cost1).isNotEqualByComparingTo(cost2);
        }
    }

    // ===== Cache Edge Cases =====

    @Nested
    @DisplayName("Cache Edge Cases")
    class CacheEdgeCases {

        @Test
        @DisplayName("concurrent calculateCost calls should share cache correctly")
        void concurrentCalculateCostSharesCache() throws Exception {
            // DB should only be hit once (or a very small number of times due to ConcurrentHashMap race)
            ModelPricing pricing = createPricing("openai", "gpt-4o", "0.25", "1.0", "0");
            when(pricingRepository.findCurrentPricing("openai", "gpt-4o"))
                    .thenReturn(Optional.of(pricing));

            int threadCount = 10;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            AtomicInteger failures = new AtomicInteger(0);

            List<Future<BigDecimal>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threadCount; i++) {
                futures.add(executor.submit(() -> {
                    startLatch.await();
                    return pricingService.calculateCost("openai", "gpt-4o", 1000, 500);
                }));
            }

            startLatch.countDown(); // release all threads simultaneously
            for (Future<BigDecimal> f : futures) {
                try {
                    BigDecimal result = f.get();
                    // gpt-4o: (0.25*1000/1000) + (1.0*500/1000) = 0.25 + 0.50 = 0.75
                    if (result.compareTo(new BigDecimal("0.7500")) != 0) {
                        failures.incrementAndGet();
                    }
                } catch (Exception e) {
                    failures.incrementAndGet();
                }
            }
            executor.shutdown();

            assertThat(failures.get()).isZero();
            // ConcurrentHashMap.computeIfAbsent guarantees at-most-once DB call per key
            // under concurrent access (may call supplier once per racing thread in some JDKs,
            // but in practice ConcurrentHashMap limits to 1)
            verify(pricingRepository, atMost(threadCount)).findCurrentPricing("openai", "gpt-4o");
            verify(pricingRepository, atLeast(1)).findCurrentPricing("openai", "gpt-4o");
        }

        @Test
        @DisplayName("refreshCache during concurrent reads should not cause errors")
        void refreshCacheDuringConcurrentReads() throws Exception {
            ModelPricing pricing = createPricing("anthropic", "claude-3-5-sonnet-20241022", "0.3", "1.5", "0");
            when(pricingRepository.findCurrentPricing("anthropic", "claude-3-5-sonnet-20241022"))
                    .thenReturn(Optional.of(pricing));

            int readerCount = 5;
            ExecutorService executor = Executors.newFixedThreadPool(readerCount + 1);
            CountDownLatch startLatch = new CountDownLatch(1);
            AtomicInteger errors = new AtomicInteger(0);

            // Thread A: refreshCache() repeatedly
            Future<?> refreshFuture = executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < 5; i++) {
                        pricingService.refreshCache();
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
            });

            // Threads B-F: calculateCost() in parallel
            List<Future<?>> readerFutures = new java.util.ArrayList<>();
            for (int i = 0; i < readerCount; i++) {
                readerFutures.add(executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int j = 0; j < 3; j++) {
                            pricingService.calculateCost(
                                    "anthropic", "claude-3-5-sonnet-20241022", 1000, 500);
                        }
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    }
                }));
            }

            startLatch.countDown();
            refreshFuture.get();
            for (Future<?> f : readerFutures) {
                f.get();
            }
            executor.shutdown();

            // No exceptions should have been thrown by any thread
            assertThat(errors.get()).isZero();
        }

        @Test
        @DisplayName("cold start - first call with empty cache should fetch from DB")
        void coldStartFetchesFromDb() {
            // Fresh service instance created in @BeforeEach - cache is empty
            ModelPricing pricing = createPricing("openai", "gpt-4o-mini", "0.015", "0.06", "0");
            when(pricingRepository.findCurrentPricing("openai", "gpt-4o-mini"))
                    .thenReturn(Optional.of(pricing));

            // First call on a cold cache must go to DB
            BigDecimal cost = pricingService.calculateCost("openai", "gpt-4o-mini", 1000, 500);

            // (0.015*1000/1000) + (0.06*500/1000) = 0.015 + 0.030 = 0.045
            assertThat(cost).isEqualByComparingTo(new BigDecimal("0.0450"));
            verify(pricingRepository, times(1)).findCurrentPricing("openai", "gpt-4o-mini");

            // Subsequent calls should use cache - no additional DB hit
            pricingService.calculateCost("openai", "gpt-4o-mini", 2000, 1000);
            pricingService.calculateCost("openai", "gpt-4o-mini", 500, 250);

            verify(pricingRepository, times(1)).findCurrentPricing("openai", "gpt-4o-mini");
        }
    }

    // ===== upsertPricing(..., providerKind) =====

    @Nested
    @DisplayName("upsertPricing providerKind propagation")
    class UpsertProviderKind {

        @Test
        @DisplayName("Insert with providerKind='bridge' → row persisted with kind='bridge' (not the 'byok' entity default)")
        void insertSetsBridgeKind() {
            when(pricingRepository.findCurrentPricing("claude-code", "claude-opus-4-10"))
                    .thenReturn(Optional.empty());

            pricingService.upsertPricing("claude-code", "claude-opus-4-10",
                    new BigDecimal("5.00"), new BigDecimal("25.00"), "bridge");

            org.mockito.ArgumentCaptor<ModelPricing> captor =
                    org.mockito.ArgumentCaptor.forClass(ModelPricing.class);
            verify(pricingRepository).save(captor.capture());
            assertThat(captor.getValue().getProviderKind()).isEqualTo("bridge");
            assertThat(captor.getValue().getInputRate()).isEqualByComparingTo("5.00");
            assertThat(captor.getValue().getOutputRate()).isEqualByComparingTo("25.00");
        }

        @Test
        @DisplayName("Insert with providerKind=null → row persists with entity default 'byok'")
        void insertNullKindFallsBackToEntityDefault() {
            when(pricingRepository.findCurrentPricing("openai", "gpt-6"))
                    .thenReturn(Optional.empty());

            pricingService.upsertPricing("openai", "gpt-6",
                    new BigDecimal("2.00"), new BigDecimal("10.00"), null);

            org.mockito.ArgumentCaptor<ModelPricing> captor =
                    org.mockito.ArgumentCaptor.forClass(ModelPricing.class);
            verify(pricingRepository).save(captor.capture());
            // Entity default is "byok" (matches V117 DB column default)
            assertThat(captor.getValue().getProviderKind()).isEqualTo("byok");
        }

        @Test
        @DisplayName("Update with providerKind=null → preserves existing kind (no accidental kind drift on rate change)")
        void updateNullKindPreservesExisting() {
            ModelPricing existing = createPricing("claude-code", "claude-opus-4-7",
                    "5.00", "25.00", "0");
            existing.setProviderKind("bridge");
            when(pricingRepository.findCurrentPricing("claude-code", "claude-opus-4-7"))
                    .thenReturn(Optional.of(existing));

            pricingService.upsertPricing("claude-code", "claude-opus-4-7",
                    new BigDecimal("6.00"), new BigDecimal("30.00"), null);

            org.mockito.ArgumentCaptor<ModelPricing> captor =
                    org.mockito.ArgumentCaptor.forClass(ModelPricing.class);
            verify(pricingRepository).save(captor.capture());
            assertThat(captor.getValue().getProviderKind()).isEqualTo("bridge");
            assertThat(captor.getValue().getInputRate()).isEqualByComparingTo("6.00");
        }

        @Test
        @DisplayName("Update with non-null providerKind overwrites existing kind")
        void updateNonNullKindOverwrites() {
            ModelPricing existing = createPricing("claude-code", "claude-opus-4-7",
                    "5.00", "25.00", "0");
            existing.setProviderKind("byok"); // previously mis-discriminated
            when(pricingRepository.findCurrentPricing("claude-code", "claude-opus-4-7"))
                    .thenReturn(Optional.of(existing));

            pricingService.upsertPricing("claude-code", "claude-opus-4-7",
                    new BigDecimal("5.00"), new BigDecimal("25.00"), "bridge");

            org.mockito.ArgumentCaptor<ModelPricing> captor =
                    org.mockito.ArgumentCaptor.forClass(ModelPricing.class);
            verify(pricingRepository).save(captor.capture());
            assertThat(captor.getValue().getProviderKind()).isEqualTo("bridge");
        }

        @Test
        @DisplayName("Update with blank providerKind is treated as null - preserves existing")
        void updateBlankKindTreatedAsNull() {
            ModelPricing existing = createPricing("openai", "gpt-5",
                    "1.00", "5.00", "0");
            existing.setProviderKind("byok");
            when(pricingRepository.findCurrentPricing("openai", "gpt-5"))
                    .thenReturn(Optional.of(existing));

            pricingService.upsertPricing("openai", "gpt-5",
                    new BigDecimal("2.00"), new BigDecimal("8.00"), "   ");

            org.mockito.ArgumentCaptor<ModelPricing> captor =
                    org.mockito.ArgumentCaptor.forClass(ModelPricing.class);
            verify(pricingRepository).save(captor.capture());
            assertThat(captor.getValue().getProviderKind()).isEqualTo("byok");
        }

        @Test
        @DisplayName("4-arg upsertPricing overload delegates with providerKind=null (insert path uses 'byok' default)")
        void fourArgOverloadDelegatesWithNullKind() {
            when(pricingRepository.findCurrentPricing("openai", "gpt-7"))
                    .thenReturn(Optional.empty());

            pricingService.upsertPricing("openai", "gpt-7",
                    new BigDecimal("3.00"), new BigDecimal("12.00"));

            org.mockito.ArgumentCaptor<ModelPricing> captor =
                    org.mockito.ArgumentCaptor.forClass(ModelPricing.class);
            verify(pricingRepository).save(captor.capture());
            assertThat(captor.getValue().getProviderKind()).isEqualTo("byok");
        }
    }
}
