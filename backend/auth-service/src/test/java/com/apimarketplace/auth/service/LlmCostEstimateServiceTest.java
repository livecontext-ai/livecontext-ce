package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.ModelPricing;
import com.apimarketplace.auth.repository.ModelPricingRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The picker computes its own figure from these coefficients, so they have to
 * reproduce what {@link ModelPricingService#calculateCost} will actually debit.
 * That equivalence is the whole reason the endpoint may publish two numbers
 * instead of 779 rows, and it is what this test holds.
 *
 * <p>It also holds the shape of the payload: the multiplier is folded into the
 * coefficients precisely so that no field on the wire is the platform's margin,
 * and a well-meaning refactor that "helpfully" added it back would undo that
 * without breaking anything else.
 */
class LlmCostEstimateServiceTest {

    private static final BigDecimal INPUT_RATE = new BigDecimal("2");
    private static final BigDecimal OUTPUT_RATE = new BigDecimal("10");
    /** Claude Sonnet 5's real cache prices, which V491 mirrored into the catalogue. */
    private static final BigDecimal CACHE_WRITE_RATE = new BigDecimal("2.5");
    private static final BigDecimal CACHE_READ_RATE = new BigDecimal("0.2");

    private ModelPricingService pricingService(BigDecimal multiplier) {
        ModelPricingRepository repository = mock(ModelPricingRepository.class);
        ModelPricing pricing = new ModelPricing();
        pricing.setInputRate(INPUT_RATE);
        pricing.setOutputRate(OUTPUT_RATE);
        pricing.setCacheWriteRate(CACHE_WRITE_RATE);
        pricing.setCacheReadRate(CACHE_READ_RATE);
        pricing.setFixedCost(BigDecimal.ZERO);
        when(repository.findCurrentPricing(anyString(), anyString())).thenReturn(Optional.of(pricing));
        return new ModelPricingService(repository, multiplier);
    }

    private CreditService meteredCreditService(boolean unlimited) {
        CreditService creditService = mock(CreditService.class);
        when(creditService.isUnlimited()).thenReturn(unlimited);
        return creditService;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> profile(Map<String, Object> basis, LlmCostProfile which) {
        Map<String, Object> profiles = (Map<String, Object>) basis.get("profiles");
        return (Map<String, Object>) profiles.get(which.key());
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("the basis carries the per-provider factor, so an overridden provider is not under-quoted")
    void basisCarriesProviderScales() {
        // The coefficients fold in the GLOBAL lever and name no provider. A provider billed
        // on its own lever therefore needs a correction published alongside them, or every
        // surface that renders this basis quotes a price the ledger will not debit.
        ModelPricingRepository repository = mock(ModelPricingRepository.class);
        ModelPricing pricing = new ModelPricing();
        pricing.setInputRate(INPUT_RATE);
        pricing.setOutputRate(OUTPUT_RATE);
        pricing.setFixedCost(BigDecimal.ZERO);
        when(repository.findCurrentPricing(anyString(), anyString()))
                .thenReturn(Optional.of(pricing));
        ModelPricingService overridden =
                new ModelPricingService(repository, new BigDecimal("2.0"), "acme=10");

        Map<String, Object> basis =
                new LlmCostEstimateService(overridden, meteredCreditService(false), null, null, null, null).buildBasis();

        @SuppressWarnings("unchecked")
        Map<String, BigDecimal> scales = (Map<String, BigDecimal>) basis.get("providerScales");
        assertThat(scales).hasEntrySatisfying("acme",
                scale -> assertThat(scale).isEqualByComparingTo("5"));
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("an install with no override publishes an empty factor map, leaving the payload as it was")
    void basisCarriesNoScalesWhenNothingIsOverridden() {
        Map<String, Object> basis = new LlmCostEstimateService(
                pricingService(new BigDecimal("2.0")), meteredCreditService(false), null, null, null, null).buildBasis();

        @SuppressWarnings("unchecked")
        Map<String, BigDecimal> scales = (Map<String, BigDecimal>) basis.get("providerScales");
        assertThat(scales).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(LlmCostProfile.class)
    @DisplayName("the published coefficients reproduce the cost the ledger would debit, at the multiplier the platform actually sells at")
    void coefficientsReproduceTheBilledCostAtTheLiveMultiplier(LlmCostProfile which) {
        // The estimate a picker shows and the figure the ledger debits are the same
        // number, and this is where that is true rather than approximately true. Run at
        // the PRODUCTION multiplier specifically: the arbitrary-value case below proves
        // the folding is multiplier-agnostic, but a rounding or scale defect that only
        // appears at the value actually shipped would pass it and reach a user.
        ModelPricingService livePricing = pricingService(new BigDecimal("1.333333"));
        LlmCostEstimateService liveService =
                new LlmCostEstimateService(livePricing, meteredCreditService(false), null, null, null, null);

        assertThat(estimateFrom(liveService, which).doubleValue())
                .as("estimate and bill must agree at the shipped multiplier")
                .isCloseTo(livePricing.calculateCost("anthropic", "claude-sonnet-5",
                                which.breakdownFor("anthropic")).doubleValue(),
                        org.assertj.core.data.Offset.offset(0.0001));

        // And at an arbitrary one, which is what proves the equivalence is structural.
        BigDecimal multiplier = new BigDecimal("1.11");
        ModelPricingService pricing = pricingService(multiplier);
        LlmCostEstimateService service = new LlmCostEstimateService(pricing, meteredCreditService(false), null, null, null, null);

        BigDecimal billed = pricing.calculateCost("anthropic", "claude-sonnet-5", which.breakdownFor("anthropic"));
        assertThat(estimateFrom(service, which).doubleValue())
                .isCloseTo(billed.doubleValue(), org.assertj.core.data.Offset.offset(0.0001));
    }

    /**
     * The client's multiply-add over the four published coefficients, on the reference
     * model. Its cache rates are the family fallback (CACHE_WRITE_RATE / CACHE_READ_RATE
     * are Claude Sonnet 5's real prices, which equal the Anthropic weights), so this is
     * the arithmetic a browser does, not a restatement of the server's.
     */
    private BigDecimal estimateFrom(LlmCostEstimateService service, LlmCostProfile which) {
        Map<String, Object> published = profile(service.buildBasis(), which);
        return INPUT_RATE.multiply((BigDecimal) published.get("inputCoefficient"))
                .add(CACHE_WRITE_RATE.multiply((BigDecimal) published.get("cacheWriteCoefficient")))
                .add(CACHE_READ_RATE.multiply((BigDecimal) published.get("cacheReadCoefficient")))
                .add(OUTPUT_RATE.multiply((BigDecimal) published.get("outputCoefficient")));
    }

    @Test
    @DisplayName("the coefficients move with the margin lever, so a client can never hold a stale one")
    void coefficientsFollowTheMarginLever() {
        Map<String, Object> cheap = profile(
                new LlmCostEstimateService(pricingService(new BigDecimal("1.11")), meteredCreditService(false), null, null, null, null)
                        .buildBasis(), LlmCostProfile.CHAT_CONVERSATION);
        Map<String, Object> dear = profile(
                new LlmCostEstimateService(pricingService(new BigDecimal("1.8")), meteredCreditService(false), null, null, null, null)
                        .buildBasis(), LlmCostProfile.CHAT_CONVERSATION);

        assertThat((BigDecimal) dear.get("inputCoefficient"))
                .isGreaterThan((BigDecimal) cheap.get("inputCoefficient"));
    }

    @Test
    @DisplayName("names no margin on the wire: coefficients only, never the multiplier itself")
    void neverPublishesTheMultiplier() {
        Map<String, Object> basis =
                new LlmCostEstimateService(pricingService(new BigDecimal("1.11")), meteredCreditService(false), null, null, null, null)
                        .buildBasis();

        assertThat(basis).doesNotContainKey("multiplier");
        assertThat(basis.toString()).doesNotContain("1.11");
        assertThat(profile(basis, LlmCostProfile.CHAT_CONVERSATION)).containsOnlyKeys(
                "inputCoefficient", "cacheWriteCoefficient", "cacheReadCoefficient", "outputCoefficient");
    }

    @Test
    @DisplayName("publishes a cache fallback per provider, which is what lets a browser price a model the catalogue has no cache price for")
    void publishesTheCacheFallbackPerProvider() {
        // Without this a client has exactly one option for such a model - the full input
        // rate - and a conversation spends most of its tokens on cache reads, the class
        // that costs a fortieth of input on Anthropic. The picker would over-state its
        // largest term tenfold while the ledger debited the real figure.
        Map<String, Object> basis =
                new LlmCostEstimateService(pricingService(new BigDecimal("1.11")), meteredCreditService(false), null, null, null, null)
                        .buildBasis();

        @SuppressWarnings("unchecked")
        Map<String, Object> fallback = (Map<String, Object>) basis.get("cacheFallback");
        // The FULL key set, not a sample: the OpenAI-compatible vendors were added to the
        // family map to stop their cached input being billed at full rate, and a test that
        // asserted only a handful of keys would let one be dropped again in silence - on
        // both sides at once, since the client now resolves through this same list.
        assertThat(fallback).containsOnlyKeys(
                "anthropic", "claude", "claude-code",
                "openai", "codex", "azure-openai", "openai-compatible",
                "xai", "openrouter", "zai", "perplexity", "cohere",
                "qwen", "moonshot", "minimax",
                "deepseek", "google", "gemini", "gemini-cli", "*");

        @SuppressWarnings("unchecked")
        Map<String, Object> anthropic = (Map<String, Object>) fallback.get("anthropic");
        assertThat((BigDecimal) anthropic.get("cacheWriteWeight")).isEqualByComparingTo("1.25");
        assertThat((BigDecimal) anthropic.get("cacheReadWeight")).isEqualByComparingTo("0.1");
        assertThat(anthropic.get("modelCacheWritePriceApplies")).isEqualTo(true);

        // Off Anthropic nothing prices a cache WRITE: a first send is plain prompt input,
        // so the weight is 1 and a model's published write price must not be quoted.
        @SuppressWarnings("unchecked")
        Map<String, Object> openai = (Map<String, Object>) fallback.get("openai");
        assertThat((BigDecimal) openai.get("cacheWriteWeight")).isEqualByComparingTo("1");
        assertThat((BigDecimal) openai.get("cacheReadWeight")).isEqualByComparingTo("0.5");
        assertThat(openai.get("modelCacheWritePriceApplies")).isEqualTo(false);

        // EVERY key's family, not a sample of them. Moving one provider between families
        // changes what its users are billed, and it would otherwise pass every test here:
        // containsOnlyKeys still matches, and the client/ledger parity test reads the same
        // map on both sides, so the quote follows the mistake instead of exposing it.
        Map<String, String> expectedReadWeight = Map.ofEntries(
                Map.entry("anthropic", "0.1"), Map.entry("claude", "0.1"),
                Map.entry("claude-code", "0.1"),
                Map.entry("openai", "0.5"), Map.entry("codex", "0.5"),
                Map.entry("azure-openai", "0.5"), Map.entry("openai-compatible", "0.5"),
                Map.entry("xai", "0.5"), Map.entry("openrouter", "0.5"),
                Map.entry("zai", "0.5"), Map.entry("perplexity", "0.5"),
                Map.entry("cohere", "0.5"),
                Map.entry("qwen", "0.5"), Map.entry("moonshot", "0.5"),
                Map.entry("minimax", "0.5"),
                Map.entry("deepseek", "0.1"),
                Map.entry("google", "0.25"), Map.entry("gemini", "0.25"),
                Map.entry("gemini-cli", "0.25"),
                Map.entry("*", "1"));
        expectedReadWeight.forEach((provider, weight) ->
                assertThat(readWeight(fallback, provider))
                        .as("cache-read weight published for %s", provider)
                        .isEqualByComparingTo(weight));

        // Only the Anthropic families are billed off a cache-write counter at all.
        fallback.forEach((provider, entry) -> {
            boolean isAnthropicFamily = provider.startsWith("claude") || provider.equals("anthropic");
            assertThat(((Map<?, ?>) entry).get("modelCacheWritePriceApplies"))
                    .as("model cache-write price applies for %s", provider)
                    .isEqualTo(isAnthropicFamily);
        });

        // An unknown vendor has no discount to guess with, so both classes cost input.
        @SuppressWarnings("unchecked")
        Map<String, Object> unknown = (Map<String, Object>) fallback.get("*");
        assertThat((BigDecimal) unknown.get("cacheWriteWeight")).isEqualByComparingTo("1");
        assertThat((BigDecimal) unknown.get("cacheReadWeight")).isEqualByComparingTo("1");
    }

    @SuppressWarnings("unchecked")
    private static BigDecimal readWeight(Map<String, Object> fallback, String provider) {
        return (BigDecimal) ((Map<String, Object>) fallback.get(provider)).get("cacheReadWeight");
    }

    @Test
    @DisplayName("the published weights are the ones this install is CONFIGURED with, not the code defaults")
    void theCacheFallbackFollowsTheConfiguredWeights() {
        // The failure this exists for: hard-coding 1.25/0.1/0.5/0.25/0.1 into the
        // published payload would keep every other test green while an operator's
        // override silently stopped reaching the browser. The client would then quote a
        // rate this install does not charge, which is worse than publishing nothing.
        ModelPricingRepository repository = mock(ModelPricingRepository.class);
        ModelPricing pricing = new ModelPricing();
        pricing.setInputRate(INPUT_RATE);
        pricing.setOutputRate(OUTPUT_RATE);
        pricing.setFixedCost(BigDecimal.ZERO);
        when(repository.findCurrentPricing(anyString(), anyString())).thenReturn(Optional.of(pricing));
        ModelPricingService configured = new ModelPricingService(repository, new BigDecimal("2.0"),
                new BigDecimal("1.4"), new BigDecimal("0.2"),
                new BigDecimal("0.6"), new BigDecimal("0.3"), new BigDecimal("0.15"));

        Map<String, Object> basis =
                new LlmCostEstimateService(configured, meteredCreditService(false), null, null, null, null).buildBasis();
        @SuppressWarnings("unchecked")
        Map<String, Object> fallback = (Map<String, Object>) basis.get("cacheFallback");

        assertThat(readWeight(fallback, "anthropic")).isEqualByComparingTo("0.2");
        assertThat(readWeight(fallback, "openai")).isEqualByComparingTo("0.6");
        assertThat(readWeight(fallback, "gemini")).isEqualByComparingTo("0.3");
        assertThat(readWeight(fallback, "deepseek")).isEqualByComparingTo("0.15");
        @SuppressWarnings("unchecked")
        Map<String, Object> anthropic = (Map<String, Object>) fallback.get("anthropic");
        assertThat((BigDecimal) anthropic.get("cacheWriteWeight")).isEqualByComparingTo("1.4");
    }

    @Test
    @DisplayName("the cache fallback carries relative prices, never the margin")
    void theCacheFallbackIsNotAMarginLeak() {
        // The weights are the providers' own published ratios and are the same at every
        // margin. If one ever moved with the lever it would BE the lever, on the wire.
        Map<String, Object> cheap = new LlmCostEstimateService(
                pricingService(new BigDecimal("1.11")), meteredCreditService(false), null, null, null, null).buildBasis();
        Map<String, Object> dear = new LlmCostEstimateService(
                pricingService(new BigDecimal("1.8")), meteredCreditService(false), null, null, null, null).buildBasis();

        // Asserted non-empty first: equality alone is satisfied by two absent payloads,
        // so this test would have gone green on a change that dropped the fallback.
        assertThat((Map<?, ?>) cheap.get("cacheFallback")).isNotEmpty();
        assertThat(cheap.get("cacheFallback")).isEqualTo(dear.get("cacheFallback"));
    }

    @Test
    @DisplayName("publishes every profile, so a surface can price the shape of work it configures")
    void publishesEveryProfile() {
        Map<String, Object> basis =
                new LlmCostEstimateService(pricingService(new BigDecimal("1.11")), meteredCreditService(false), null, null, null, null)
                        .buildBasis();

        @SuppressWarnings("unchecked")
        Map<String, Object> profiles = (Map<String, Object>) basis.get("profiles");
        assertThat(profiles).containsOnlyKeys(
                "chatConversation", "guardrailCheck", "classifyStep");
    }

    @Test
    @DisplayName("an install that does not meter credits gets no coefficients at all")
    void disabledWhereCreditsAreNotMetered() {
        // CE bills at provider cost with no margin and has no wallet. A credit
        // figure there would be a number about nothing, so nothing is published.
        Map<String, Object> basis =
                new LlmCostEstimateService(pricingService(new BigDecimal("1.0")), meteredCreditService(true), null, null, null, null)
                        .buildBasis();

        assertThat(basis).containsEntry("enabled", false);
        assertThat((Map<?, ?>) basis.get("profiles")).isEmpty();
        // Exactly two keys, so the fallback weights do not follow the coefficients out of
        // a self-hosted binary either: they are operator levers, and CE has no estimate
        // to resolve with them.
        assertThat(basis).containsOnlyKeys("enabled", "profiles");
    }
}
