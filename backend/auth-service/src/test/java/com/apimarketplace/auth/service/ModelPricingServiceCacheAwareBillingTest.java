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
        mockPricing(provider, model, inputRate, outputRate, null, null);
    }

    /** Pricing row carrying the model's OWN cache prices (V491); null = not known. */
    private void mockPricing(String provider, String model, String inputRate, String outputRate,
                             String cacheReadRate, String cacheWriteRate) {
        ModelPricing pricing = new ModelPricing();
        pricing.setProvider(provider);
        pricing.setModel(model);
        pricing.setInputRate(new BigDecimal(inputRate));
        pricing.setOutputRate(new BigDecimal(outputRate));
        pricing.setCacheReadRate(cacheReadRate != null ? new BigDecimal(cacheReadRate) : null);
        pricing.setCacheWriteRate(cacheWriteRate != null ? new BigDecimal(cacheWriteRate) : null);
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

    /**
     * V491: a cache token is billed at the MODEL's own price, not at a per-family
     * constant. The five constants were a 2024-era approximation; measured against the
     * production catalog, 195 of the 239 models carrying a feed cache price were billed
     * at the wrong one. The family multiplier survives only as the fallback for a model
     * whose cache price is unknown.
     */
    @Nested
    @DisplayName("Per-model cache rates (V491)")
    class PerModelCacheRates {

        @Test
        @DisplayName("anthropic: a model whose cache read is cheaper than the family constant is billed at ITS price, not 0.1x input")
        void anthropicModelCacheReadRateWinsOverTheFamilyMultiplier() {
            // claude-fable-5-1 real prices: input 10, cache read 0.25 (= 0.025x, not 0.1x).
            mockPricing("anthropic", "claude-fable-5-1", "10.0", "50.0", "0.25", "12.5");

            BigDecimal cost = zeroMarginService.calculateCost("anthropic", "claude-fable-5-1",
                    breakdown(2_000, 5_000, 20_000, 2_000_000, 0, 0));

            // 2000*10 + 20000*12.5 + 2000000*0.25 + 5000*50
            // = 20k + 250k + 500k + 250k = 1,020,000 / 1000 = 1020 credits.
            assertThat(cost).isEqualByComparingTo(new BigDecimal("1020"));
        }

        @Test
        @DisplayName("anthropic: the same turn on the family multiplier costs 2520 - the fix is a 2.47x cut, not a rounding difference")
        void anthropicFamilyMultiplierOverBilledTheSameTurn() {
            mockPricing("anthropic", "claude-fable-5-1", "10.0", "50.0");

            BigDecimal legacy = zeroMarginService.calculateCost("anthropic", "claude-fable-5-1",
                    breakdown(2_000, 5_000, 20_000, 2_000_000, 0, 0));

            // 2000*10 + 20000*(10*1.25) + 2000000*(10*0.1) + 5000*50 = 2,520,000 / 1000.
            assertThat(legacy).isEqualByComparingTo(new BigDecimal("2520"));
        }

        @Test
        @DisplayName("openai: cached prompt subset billed at the model's own cache price instead of the 0.5x family constant")
        void openAiCachedSubsetUsesTheModelRate() {
            // gpt-5.6-sol real prices: input 4.0, cached 0.4 (= 0.1x, not the 0.5x constant).
            mockPricing("openai", "gpt-5.6-sol", "4.0", "20.0", "0.4", null);

            BigDecimal cost = zeroMarginService.calculateCost("openai", "gpt-5.6-sol",
                    breakdown(1_000_000, 10_000, 0, 0, 900_000, 0));

            // 100000*4 + 900000*0.4 + 10000*20 = 400k + 360k + 200k = 960,000 / 1000.
            assertThat(cost).isEqualByComparingTo(new BigDecimal("960"));
        }

        // These two used qwen/moonshot/minimax as the example of a family this service
        // does not know. They stopped being that on 2026-09-17: they report the OpenAI
        // usage shape, so they were moved into the OPENAI family and their cached subset
        // is now discounted 0.5x rather than billed at full input rate. That membership is
        // pinned in ModelPricingServiceTest.OpenAICompatibleCacheBilling.
        //
        // The property below is unchanged and still worth holding, so it keeps its shape
        // and takes a vendor that really has no family: whatever the platform adds next,
        // before anyone has classified it.
        @Test
        @DisplayName("a family this service does not know still honours a cache price the catalogue publishes")
        void unknownFamilyHonoursAKnownCacheRate() {
            mockPricing("some-new-vendor", "its-model", "0.6", "2.5", "0.06", null);

            BigDecimal cost = zeroMarginService.calculateCost("some-new-vendor", "its-model",
                    breakdown(1_000_000, 10_000, 0, 0, 900_000, 0));

            // 100000*0.6 + 900000*0.06 + 10000*2.5 = 60k + 54k + 25k = 139,000 / 1000.
            assertThat(cost).isEqualByComparingTo(new BigDecimal("139"));
        }

        @Test
        @DisplayName("a family this service does not know, with NO cache price, keeps billing its cached subset at full input rate (pre-V491 behaviour)")
        void unknownFamilyWithoutARateIsUnchanged() {
            // No family means no discount to guess with. Guessing one would hand a
            // reduction to a vendor nobody has checked reports a cached subset at all.
            mockPricing("some-new-vendor", "its-model", "0.6", "2.5");

            BigDecimal cost = zeroMarginService.calculateCost("some-new-vendor", "its-model",
                    breakdown(1_000_000, 10_000, 0, 0, 900_000, 0));

            // 1000000*0.6 + 10000*2.5 = 600k + 25k = 625,000 / 1000.
            assertThat(cost).isEqualByComparingTo(new BigDecimal("625"));
        }

        @Test
        @DisplayName("moonshot is no longer that example: it reads as OpenAI, so its cached subset is discounted")
        void moonshotNowReadsAsAnOpenAiFamily() {
            // The regression this pins is the one that actually broke: a test using
            // moonshot to mean "unknown" changed meaning the day moonshot got a family,
            // and said so only in CI. Naming the membership here makes the next such move
            // fail on the sentence that is wrong rather than on an unrelated figure.
            mockPricing("moonshot", "kimi-k2.6", "0.6", "2.5");

            BigDecimal cost = zeroMarginService.calculateCost("moonshot", "kimi-k2.6",
                    breakdown(1_000_000, 10_000, 0, 0, 900_000, 0));

            // 100000*0.6 + 900000*(0.5*0.6) + 10000*2.5 = 60k + 270k + 25k = 355,000 / 1000.
            assertThat(cost).isEqualByComparingTo(new BigDecimal("355"));
        }

        @Test
        @DisplayName("gemini: cached content billed at the model rate, whichever of the two fields the reporter used")
        void geminiCachedContentUsesTheModelRate() {
            mockPricing("google", "gemini-3-pro-preview", "2.0", "12.0", "0.2", null);

            BigDecimal viaCached = zeroMarginService.calculateCost("google", "gemini-3-pro-preview",
                    breakdown(1_000_000, 1_000, 0, 0, 800_000, 0));
            BigDecimal viaCacheRead = zeroMarginService.calculateCost("google", "gemini-3-pro-preview",
                    breakdown(1_000_000, 1_000, 0, 800_000, 0, 0));

            // 200000*2 + 800000*0.2 + 1000*12 = 400k + 160k + 12k = 572,000 / 1000.
            assertThat(viaCached).isEqualByComparingTo(new BigDecimal("572"));
            assertThat(viaCacheRead).isEqualByComparingTo(viaCached);
        }

        @Test
        @DisplayName("claude-code: the bridge's inclusive prompt total is still stripped before the model rates apply")
        void anthropicCliStillStripsTheInclusivePrompt() {
            mockPricing("claude-code", "claude-fable-5-1", "10.0", "50.0", "0.25", "12.5");

            BigDecimal cost = zeroMarginService.calculateCost("claude-code", "claude-fable-5-1",
                    breakdown(2_022_000, 5_000, 20_000, 2_000_000, 0, 0));

            // The same turn as the anthropic case, reported inclusively: identical bill.
            assertThat(cost).isEqualByComparingTo(new BigDecimal("1020"));
        }

        @Test
        @DisplayName("a zero stored cache rate means UNKNOWN, never free - it falls back to the family multiplier")
        void nonPositiveStoredRateFallsBackInsteadOfBillingNothing() {
            mockPricing("anthropic", "claude-fable-5-1", "10.0", "50.0", "0", "0");

            BigDecimal cost = zeroMarginService.calculateCost("anthropic", "claude-fable-5-1",
                    breakdown(2_000, 5_000, 20_000, 2_000_000, 0, 0));

            // Falls back to 1.25x / 0.1x - the pre-V491 figure, not a free cache.
            assertThat(cost).isEqualByComparingTo(new BigDecimal("2520"));
        }

        @Test
        @DisplayName("deepseek: the model's own cache price wins over the 0.1x family constant, like every other family")
        void deepSeekCachedSubsetUsesTheModelRate() {
            // deepseek-v4-pro really reads cache at 0.044 / 1.32 = 0.033x, a third of the
            // family constant. Listing this family explicitly because it is the one whose
            // fallback happens to be closest to the truth, which is how it stays untested.
            mockPricing("deepseek", "deepseek-v4-pro", "1.32", "3.96", "0.044", null);

            BigDecimal cost = zeroMarginService.calculateCost("deepseek", "deepseek-v4-pro",
                    breakdown(1_000_000, 10_000, 0, 0, 900_000, 0));

            // 100000*1.32 + 900000*0.044 + 10000*3.96 = 132k + 39.6k + 39.6k = 211,200 / 1000.
            assertThat(cost).isEqualByComparingTo(new BigDecimal("211.2"));
        }

        @Test
        @DisplayName("the code default is 1.333333 = 25% per request - the one margin copy that no config file pins")
        void codeDefaultMultiplierIsTheDeclaredMargin() {
            // application.yml and values-prod.yaml are both pinned by EconomicsConfigPinTest.
            // The Java constant is the third copy and the one that decides the bill if a
            // deployment ever loses its configuration, so it is pinned here rather than
            // nowhere. Read through the public accessor, not by reflection: what matters is
            // the value the service would actually bill with.
            ModelPricingService defaulted = new ModelPricingService(pricingRepository, null);

            assertThat(defaulted.getCloudLlmBillingMultiplier()).isEqualByComparingTo("1.333333");
        }

        @Test
        @DisplayName("the @Value default and the Java constant are ONE spelling, so a margin change cannot move only one")
        void propertyDefaultAndCodeDefaultCannotDiverge() {
            // The defect this closes, found by a sweep and not by a test. The margin had
            // TWO defaults in this file: the constant above, and a separate literal inside
            // the constructor's @Value. The test above resolves the constant by passing
            // null, so it never reads the annotation - which means moving the margin could
            // (and did) update the constant while the PROPERTY default went on charging the
            // old rate wherever configuration was absent.
            //
            // Reading the annotation is the only way to see it: a Spring context test would
            // bind application.yml and never exercise the default at all.
            // Selected by @Autowired, not by parameter count: the service carries several
            // convenience overloads for tests, and counting parameters picked whichever one
            // happened to have that arity. Adding an unannotated 7-arg overload was enough
            // to make this test read the wrong constructor and report a lost @Value.
            java.lang.reflect.Constructor<?> injected = java.util.Arrays
                    .stream(ModelPricingService.class.getDeclaredConstructors())
                    .filter(c -> c.isAnnotationPresent(
                            org.springframework.beans.factory.annotation.Autowired.class))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("the injected constructor has moved"));

            String expression = java.util.Arrays.stream(injected.getParameterAnnotations()[1])
                    .filter(a -> a instanceof org.springframework.beans.factory.annotation.Value)
                    .map(a -> ((org.springframework.beans.factory.annotation.Value) a).value())
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("the margin parameter lost its @Value"));

            assertThat(expression)
                    .as("the property default must be the same spelling as the code default")
                    .isEqualTo("${billing.llm.cloud-multiplier:"
                            + ModelPricingService.DEFAULT_CLOUD_LLM_BILLING_MULTIPLIER_VALUE + "}");

            // Same contract for the per-provider overrides, which are a margin decision
            // too. Found by scanning rather than by parameter index: counting positions is
            // what made this test read the wrong constructor when an overload was added,
            // and it would silently stop checking anything if a parameter moved again.
            java.util.List<String> valueExpressions = java.util.Arrays.stream(injected.getParameterAnnotations())
                    .flatMap(java.util.Arrays::stream)
                    .filter(a -> a instanceof org.springframework.beans.factory.annotation.Value)
                    .map(a -> ((org.springframework.beans.factory.annotation.Value) a).value())
                    .toList();

            assertThat(valueExpressions)
                    .as("the per-provider override must bind with the shared code default, not a blank one")
                    .contains("${billing.llm.provider-multipliers:"
                            + ModelPricingService.DEFAULT_PROVIDER_MULTIPLIERS_VALUE + "}");
        }

        @Test
        @DisplayName("the cloud multiplier still applies exactly once, on top of the model-rate cost")
        void cloudMultiplierStillAppliesOnceOnTop() {
            mockPricing("anthropic", "claude-fable-5-1", "10.0", "50.0", "0.25", "12.5");
            ModelPricingService margined = new ModelPricingService(pricingRepository, new BigDecimal("1.11"));

            BigDecimal cost = margined.calculateCost("anthropic", "claude-fable-5-1",
                    breakdown(2_000, 5_000, 20_000, 2_000_000, 0, 0));

            assertThat(cost).isEqualByComparingTo(new BigDecimal("1132.200000"));
        }
    }
}
