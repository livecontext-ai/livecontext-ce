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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Regression tests for the cache-token billing incoherence (2026-06-11):
 *
 * <ul>
 *   <li>The claude-code bridge reported {@code promptTokens = input + cache_creation +
 *       cache_read} and ALL of it was billed at full input rate - cache reads (which
 *       Anthropic sells at 0.1x) were over-billed up to 10x before margin.</li>
 *   <li>The direct Anthropic API path reported {@code promptTokens = input only} and
 *       cache write/read tokens were not billed at all (under-billing).</li>
 *   <li>Gemini thinking tokens (thoughtsTokenCount, additive output) and OpenAI/DeepSeek
 *       cached prompt subsets were billed at the wrong rate or not at all.</li>
 * </ul>
 *
 * The fix bills every token class at the provider's true relative price so the cloud
 * multiplier is the pricing multiplier. These tests would fail on the pre-fix code.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ModelPricingService cache-aware billing")
class ModelPricingServiceCacheAwareBillingTest {

    @Mock
    private ModelPricingRepository pricingRepository;

    /** Zero-margin service (multiplier 1.0) - cost == provider list price in credits. */
    private ModelPricingService zeroMarginService;

    @BeforeEach
    void setUp() {
        zeroMarginService = new ModelPricingService(pricingRepository, BigDecimal.ONE);
    }

    private void mockPricing(String provider, String model, String inputRate, String outputRate) {
        ModelPricing pricing = new ModelPricing();
        pricing.setProvider(provider);
        pricing.setModel(model);
        pricing.setInputRate(new BigDecimal(inputRate));
        pricing.setOutputRate(new BigDecimal(outputRate));
        pricing.setFixedCost(BigDecimal.ZERO);
        when(pricingRepository.findCurrentPricing(provider, model)).thenReturn(Optional.of(pricing));
    }

    private static LlmTokenBreakdown breakdown(int prompt, int completion,
                                               int cacheWrite, int cacheRead,
                                               int cached, int reasoning) {
        return new LlmTokenBreakdown(prompt, completion, cacheWrite, cacheRead, cached, reasoning);
    }

    @Test
    @DisplayName("OpenAI-compatible vendors (xai/openrouter/zai/perplexity/cohere) bill cached input at the OPENAI 0.5x rate, not the OTHER full rate (regression: family routing fell to OTHER → cached input over-billed)")
    void openAiCompatibleVendorsDiscountCachedInputLikeOpenAi() {
        // prompt=1000 with 800 cached → OPENAI billable input = 1000 - 800 + 800*0.5 = 600
        // → inputCost = 1.0 * 600/1000 = 0.6. Pre-fix these vendors routed to OTHER, whose
        // billableInputTokens ignores cachedTokens → billable input = 1000 → cost 1.0 (the cached
        // input billed at full rate). These are the literal instance names the
        // OpenAICompatibleProviderFactory registers and stamps as the billing provider string.
        for (String vendor : java.util.List.of("xai", "openrouter", "zai", "perplexity", "cohere")) {
            mockPricing(vendor, "model-x", "1.0", "4.0");
            BigDecimal cost = zeroMarginService.calculateCost(vendor, "model-x", breakdown(1000, 0, 0, 0, 800, 0));
            assertThat(cost)
                .as("%s cached input must be discounted at OPENAI 0.5x, not billed at full input rate", vendor)
                .isEqualByComparingTo("0.6");
        }
    }

    @Nested
    @DisplayName("Anthropic family")
    class AnthropicFamily {

        @Test
        @DisplayName("claude-code: cache reads billed at 0.1x and writes at 1.25x, not full input rate (real prod row)")
        void claudeCodeCacheReadsNotBilledAtFullRate() {
            // Real agent_executions row (2026-05-21, claude-opus-4-6 @ 5/25 USD per 1M):
            // prompt=131195 INCLUDING write=45390 + read=85800 (plain input = 5), completion=677.
            // Pre-fix billing: 5*131.195 + 25*0.677 = 672.90 credits.
            // True provider cost: base 5 + 45390*1.25 + 85800*0.1 = 65322.5 weighted input tokens
            //   => 5*65.3225 + 25*0.677 = 326.6125 + 16.925 = 343.5375 credits.
            mockPricing("claude-code", "claude-opus-4-6", "5.0", "25.0");

            BigDecimal cost = zeroMarginService.calculateCost("claude-code", "claude-opus-4-6",
                    breakdown(131195, 677, 45390, 85800, 0, 0));

            assertThat(cost).isEqualByComparingTo(new BigDecimal("343.5375"));
            // Sanity: strictly below the pre-fix full-rate amount.
            assertThat(cost).isLessThan(new BigDecimal("672.90"));
        }

        @Test
        @DisplayName("anthropic direct API: additive cache write/read tokens are billed (were free pre-fix)")
        void anthropicDirectApiBillsAdditiveCacheTokens() {
            // Direct API promptTokens EXCLUDE cache: input=1000, write=2000, read=10000, output=500.
            // billable input = 1000 + 2000*1.25 + 10000*0.1 = 4500 tokens
            // cost = 3*4.5 + 15*0.5 = 13.5 + 7.5 = 21.0 (pre-fix: 10.5 - cache was free)
            mockPricing("anthropic", "claude-sonnet-4-6", "3.0", "15.0");

            BigDecimal cost = zeroMarginService.calculateCost("anthropic", "claude-sonnet-4-6",
                    breakdown(1000, 500, 2000, 10000, 0, 0));

            assertThat(cost).isEqualByComparingTo(new BigDecimal("21.0"));
        }

        @Test
        @DisplayName("claude-code: base input clamps at 0 when cache counters exceed promptTokens")
        void claudeCodeBaseInputClampsAtZero() {
            // Defensive: write+read > prompt must not produce a negative base.
            // billable input = max(0, 100-200-300) + 200*1.25 + 300*0.1 = 0 + 250 + 30 = 280
            // cost = 5*0.28 = 1.40
            mockPricing("claude-code", "claude-opus-4-6", "5.0", "25.0");

            BigDecimal cost = zeroMarginService.calculateCost("claude-code", "claude-opus-4-6",
                    breakdown(100, 0, 200, 300, 0, 0));

            assertThat(cost).isEqualByComparingTo(new BigDecimal("1.40"));
        }
    }

    @Nested
    @DisplayName("OpenAI / DeepSeek cached prompt subsets")
    class CachedSubsets {

        @Test
        @DisplayName("openai: cached prompt tokens (subset of promptTokens) billed at 0.5x")
        void openaiCachedSubsetBilledAtHalfRate() {
            // prompt=10000 of which cached=8000; completion=1000.
            // billable input = 2000 + 8000*0.5 = 6000 => 2.5*6 + 10*1 = 25.0 (pre-fix 35.0)
            mockPricing("openai", "gpt-4o", "2.5", "10.0");

            BigDecimal cost = zeroMarginService.calculateCost("openai", "gpt-4o",
                    breakdown(10000, 1000, 0, 0, 8000, 0));

            assertThat(cost).isEqualByComparingTo(new BigDecimal("25.0"));
        }

        @Test
        @DisplayName("openai: cached subset is clamped to promptTokens")
        void openaiCachedSubsetClampedToPrompt() {
            // cached=500 > prompt=100 - clamp to 100: billable = 100*0.5 = 50 tokens
            mockPricing("openai", "gpt-4o", "2.0", "8.0");

            BigDecimal cost = zeroMarginService.calculateCost("openai", "gpt-4o",
                    breakdown(100, 0, 0, 0, 500, 0));

            assertThat(cost).isEqualByComparingTo(new BigDecimal("0.10"));
        }

        @Test
        @DisplayName("deepseek: cache hits billed at 0.1x")
        void deepseekCacheHitsBilledAtTenth() {
            // prompt=10000 of which cached=9000; completion=100.
            // billable input = 1000 + 900 = 1900 => 0.28*1.9 + 0.42*0.1 = 0.532 + 0.042 = 0.574
            mockPricing("deepseek", "deepseek-chat", "0.28", "0.42");

            BigDecimal cost = zeroMarginService.calculateCost("deepseek", "deepseek-chat",
                    breakdown(10000, 100, 0, 0, 9000, 0));

            assertThat(cost).isEqualByComparingTo(new BigDecimal("0.574"));
        }
    }

    @Nested
    @DisplayName("Google family")
    class GoogleFamily {

        @Test
        @DisplayName("gemini: thoughts (reasoning) tokens are additive output and now billed")
        void geminiThoughtsBilledAsOutput() {
            // prompt=10000 with cached=4000 (subset, 0.25x); completion=1000 + thoughts=500.
            // billable input = 6000 + 4000*0.25 = 7000 => 1.25*7 = 8.75
            // billable output = 1500 => 10*1.5 = 15 - total 23.75 (pre-fix: 22.5, thoughts free)
            mockPricing("google", "gemini-2.5-pro", "1.25", "10.0");

            BigDecimal cost = zeroMarginService.calculateCost("google", "gemini-2.5-pro",
                    breakdown(10000, 1000, 0, 0, 4000, 500));

            assertThat(cost).isEqualByComparingTo(new BigDecimal("23.75"));
        }

        @Test
        @DisplayName("gemini-cli: cached content reported in cacheReadTokens is treated as a prompt subset")
        void geminiCliCacheReadTreatedAsSubset() {
            // The gemini-cli bridge maps cached_content into cacheReadInputTokens.
            // billable input = 6000 + 4000*0.25 = 7000 => 1.25*7 = 8.75; output 10*1 = 10.
            // Reasoning is NOT billed additively on the CLI path (its output_tokens may
            // already include thoughts - adding 500 here would risk double-billing).
            mockPricing("gemini-cli", "gemini-2.5-pro", "1.25", "10.0");

            BigDecimal cost = zeroMarginService.calculateCost("gemini-cli", "gemini-2.5-pro",
                    breakdown(10000, 1000, 0, 4000, 0, 500));

            assertThat(cost).isEqualByComparingTo(new BigDecimal("18.75"));
        }
    }

    @Nested
    @DisplayName("Margin, defaults and unknown providers")
    class MarginAndDefaults {

        @Test
        @DisplayName("cloud multiplier applies AFTER cache weighting - margin stays exactly 1.8x of provider cost")
        void multiplierAppliesAfterCacheWeighting() {
            ModelPricingService withMargin =
                    new ModelPricingService(pricingRepository, new BigDecimal("1.8"));
            mockPricing("claude-code", "claude-opus-4-6", "5.0", "25.0");

            LlmTokenBreakdown usage = breakdown(131195, 677, 45390, 85800, 0, 0);
            BigDecimal billed = withMargin.calculateCost("claude-code", "claude-opus-4-6", usage);

            // 343.5375 * 1.8 = 618.3675
            assertThat(billed).isEqualByComparingTo(new BigDecimal("618.3675"));
        }

        @Test
        @DisplayName("zero margin (multiplier 1.0) bills exactly the provider list price")
        void zeroMarginBillsProviderListPrice() {
            ModelPricingService margin = new ModelPricingService(pricingRepository, new BigDecimal("1.8"));
            mockPricing("anthropic", "claude-opus-4-6", "5.0", "25.0");

            LlmTokenBreakdown usage = breakdown(2000, 1000, 0, 0, 0, 0);
            BigDecimal zeroMargin = zeroMarginService.calculateCost("anthropic", "claude-opus-4-6", usage);
            BigDecimal withMargin = margin.calculateCost("anthropic", "claude-opus-4-6", usage);

            // List price: 5*2 + 25*1 = 35 credits; margin = exactly 1.8x of it.
            assertThat(zeroMargin).isEqualByComparingTo(new BigDecimal("35"));
            assertThat(withMargin).isEqualByComparingTo(new BigDecimal("63.00"));
        }

        @Test
        @DisplayName("unknown provider family ignores cache counters (legacy formula)")
        void unknownProviderIgnoresCacheCounters() {
            mockPricing("mistral", "mistral-large-latest", "2.0", "6.0");

            BigDecimal withCache = zeroMarginService.calculateCost("mistral", "mistral-large-latest",
                    breakdown(1000, 500, 100, 200, 300, 50));
            BigDecimal withoutCache = zeroMarginService.calculateCost("mistral", "mistral-large-latest",
                    breakdown(1000, 500, 0, 0, 0, 0));

            assertThat(withCache).isEqualByComparingTo(withoutCache);
            assertThat(withCache).isEqualByComparingTo(new BigDecimal("5.0"));
        }

        @Test
        @DisplayName("all-zero cache breakdown is exactly the legacy 2-field formula")
        void allZeroBreakdownEqualsLegacyFormula() {
            mockPricing("claude-code", "claude-opus-4-6", "5.0", "25.0");

            BigDecimal viaBreakdown = zeroMarginService.calculateCost("claude-code", "claude-opus-4-6",
                    LlmTokenBreakdown.of(4000, 2000));
            BigDecimal viaLegacy = zeroMarginService.calculateCost("claude-code", "claude-opus-4-6", 4000, 2000);

            assertThat(viaBreakdown).isEqualByComparingTo(viaLegacy);
            assertThat(viaLegacy).isEqualByComparingTo(new BigDecimal("70"));
        }

        @Test
        @DisplayName("free model (0/0 rates) costs 0 even with large cache counters")
        void freeModelStaysFreeWithCache() {
            mockPricing("claude-code", "free-model", "0", "0");

            BigDecimal cost = zeroMarginService.calculateCost("claude-code", "free-model",
                    breakdown(1_000_000, 50_000, 400_000, 500_000, 0, 0));

            assertThat(cost).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @ParameterizedTest(name = "{0} rates {1}/{2}: same cache-heavy turn costs {3}")
        @CsvSource({
                // provider claude-code, prompt=20000 (write=8000, read=10000, base=2000), completion=1000
                // billable input = 2000 + 8000*1.25 + 10000*0.1 = 13000 tokens
                "claude-opus-4-6,    5.0,  25.0, 90.000",   // 5*13 + 25*1
                "claude-sonnet-4-6,  3.0,  15.0, 54.000",   // 3*13 + 15*1
                "claude-haiku-4-5,   1.0,   5.0, 18.000",   // 1*13 + 5*1
        })
        @DisplayName("different model price points scale the same cache-heavy turn proportionally")
        void differentModelPricePoints(String model, String inputRate, String outputRate, String expected) {
            mockPricing("claude-code", model, inputRate, outputRate);

            BigDecimal cost = zeroMarginService.calculateCost("claude-code", model,
                    breakdown(20000, 1000, 8000, 10000, 0, 0));

            assertThat(cost).isEqualByComparingTo(new BigDecimal(expected));
        }
    }
}
