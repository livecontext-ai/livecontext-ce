package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.ModelPricing;
import com.apimarketplace.auth.repository.ModelPricingRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The model picker shows what a model will cost BEFORE it is called, and it
 * computes that number itself from the model's list rates plus what
 * {@code GET /api/credits/estimate-basis} publishes. That arithmetic is only
 * honest if it lands on the same figure {@link ModelPricingService#calculateCost}
 * will later debit.
 *
 * <p>This test is the contract between the two, and {@link #clientEstimate} is the
 * client's arithmetic written out in Java: change {@code model-cost-estimate.ts}
 * without changing it here (or the reverse) and the picker quotes a price nobody is
 * charged.
 *
 * <h2>What stopped being true on 2026-09-16, and why that is not a defect</h2>
 * The profiles used to carry no cache tokens, which bought one property: every
 * provider family priced the same workload identically, so two models could be
 * compared by their rates alone. It also priced every cached token at the full input
 * rate, and a real chat turn writes tens of thousands of tokens into the cache at
 * 1.25x input and re-reads them at a fortieth of it, so the quote came out at about
 * half of a real message.
 *
 * <p>Pricing the cache correctly means the families diverge, because they really do
 * charge differently for it. The property worth pinning is therefore not "every
 * family agrees" - that was an artefact of not charging for the cache - but "the
 * estimate equals the bill, on each family, for the workload as that family reports
 * it". That is {@link #estimateEqualsTheBillOnEveryFamily}.
 */
class LlmCostProfileFormulaTest {

    private static final BigDecimal INPUT_RATE = new BigDecimal("2.000000");
    private static final BigDecimal OUTPUT_RATE = new BigDecimal("10.000000");

    /**
     * Deliberately NOT the multiplier the platform sells at today. The profiles were
     * measured while this was the lever, and re-pricing them is an exact scale, so
     * moving this constant to 2.0 would break the shapes this class guards without
     * telling anyone anything true.
     */
    private static final BigDecimal MEASUREMENT_ERA_MULTIPLIER = new BigDecimal("1.11");

    /**
     * A model with no cache price of its own: the case the published fallback exists
     * for, and the case 32% of the catalogue is in.
     */
    private ModelPricingService serviceWithoutCachePrices() {
        return service(null, null);
    }

    private ModelPricingService service(BigDecimal cacheWriteRate, BigDecimal cacheReadRate) {
        ModelPricingRepository repository = mock(ModelPricingRepository.class);
        ModelPricing pricing = new ModelPricing();
        pricing.setInputRate(INPUT_RATE);
        pricing.setOutputRate(OUTPUT_RATE);
        pricing.setFixedCost(BigDecimal.ZERO);
        pricing.setCacheWriteRate(cacheWriteRate);
        pricing.setCacheReadRate(cacheReadRate);
        when(repository.findCurrentPricing(anyString(), anyString())).thenReturn(Optional.of(pricing));
        return new ModelPricingService(repository, MEASUREMENT_ERA_MULTIPLIER);
    }

    /**
     * The arithmetic the browser is allowed to do, written out once: four rates, four
     * coefficients, one multiply-add. {@code cacheWriteRate}/{@code cacheReadRate} are
     * what the client resolved from the model's catalogue row and the published
     * fallback; {@code caches=false} is a model with no prompt caching at all, where
     * every prompt token is simply sent again at the input rate.
     */
    private static BigDecimal clientEstimate(LlmCostProfile profile, String provider,
                                             ModelPricingService pricingService,
                                             BigDecimal modelCacheWriteRate,
                                             BigDecimal modelCacheReadRate,
                                             boolean caches) {
        Map<String, ModelPricingService.CacheRateFallback> published = pricingService.cacheRateFallbacks();
        ModelPricingService.CacheRateFallback fallback = published.getOrDefault(
                provider, published.get(ModelPricingService.DEFAULT_CACHE_FALLBACK_KEY));

        // Zero is NO price on both sides: the ledger's rateFor tests signum() > 0,
        // because a stored 0 would make cached input free. The client tests the same
        // thing, and this mirror has to, or the one case where they could disagree
        // would be the one case no test looks at.
        BigDecimal publishedWrite = published(modelCacheWriteRate);
        BigDecimal publishedRead = published(modelCacheReadRate);

        BigDecimal writeRate;
        BigDecimal readRate;
        if (!caches) {
            writeRate = INPUT_RATE;
            readRate = INPUT_RATE;
        } else {
            writeRate = fallback.modelCacheWritePriceApplies() && publishedWrite != null
                    ? publishedWrite
                    : INPUT_RATE.multiply(fallback.cacheWriteWeight());
            readRate = publishedRead != null
                    ? publishedRead
                    : INPUT_RATE.multiply(fallback.cacheReadWeight());
        }

        return INPUT_RATE.multiply(BigDecimal.valueOf(profile.inputTokens()))
                .add(writeRate.multiply(BigDecimal.valueOf(profile.cacheWriteTokens())))
                .add(readRate.multiply(BigDecimal.valueOf(profile.cacheReadTokens())))
                .add(OUTPUT_RATE.multiply(BigDecimal.valueOf(profile.outputTokens())))
                .divide(new BigDecimal("1000"), 6, RoundingMode.HALF_UP)
                .multiply(MEASUREMENT_ERA_MULTIPLIER);
    }

    /** A stored cache rate is a price only when it is positive, exactly as the ledger reads it. */
    private static BigDecimal published(BigDecimal storedRate) {
        return storedRate != null && storedRate.signum() > 0 ? storedRate : null;
    }

    @ParameterizedTest
    @EnumSource(LlmCostProfile.class)
    @DisplayName("every profile costs, on the reference model, exactly what the client's four-term formula quotes")
    void profileMatchesSharedFormula(LlmCostProfile profile) {
        ModelPricingService service = serviceWithoutCachePrices();

        BigDecimal billed = service.calculateCost("anthropic", "claude-sonnet-5", profile.breakdownFor("anthropic"));

        assertThat(billed).isEqualByComparingTo(
                clientEstimate(profile, "anthropic", service, null, null, true));
    }

    @ParameterizedTest
    @CsvSource({
            "anthropic", "claude", "claude-code", "openai", "codex", "azure-openai",
            "xai", "openrouter", "zai", "perplexity", "cohere",
            "deepseek", "google", "gemini", "gemini-cli", "some-unknown-vendor",
    })
    @DisplayName("on every provider family the estimate is the bill, for a model whose cache price the catalogue does not publish")
    void estimateEqualsTheBillOnEveryFamily(String provider) {
        // The workload is ONE conversation. What differs per family is the convention it
        // has to be described in (breakdownFor) and the rate each token class is charged
        // at (cacheRateFallbacks) - and the client is handed both, which is the whole
        // reason the fallback weights are published rather than kept server-side.
        ModelPricingService service = serviceWithoutCachePrices();

        BigDecimal billed = service.calculateCost(provider, "any-model",
                LlmCostProfile.CHAT_CONVERSATION.breakdownFor(provider));

        assertThat(billed).isEqualByComparingTo(
                clientEstimate(LlmCostProfile.CHAT_CONVERSATION, provider, service, null, null, true));
    }

    @ParameterizedTest
    @ValueSource(strings = {"anthropic", "claude-code", "openai", "deepseek", "gemini", "some-unknown-vendor"})
    @DisplayName("a model that publishes its own cache prices is estimated at them, on every family that charges for that class")
    void estimateFollowsTheModelsOwnCachePrices(String provider) {
        // V491 put per-model cache prices in the catalogue precisely so a model is priced
        // by its own tariff rather than by its family's average. A picker that kept using
        // the family weight here would quote a stale price for 68% of the catalogue.
        BigDecimal modelWrite = new BigDecimal("7.000000");
        BigDecimal modelRead = new BigDecimal("0.300000");
        ModelPricingService service = service(modelWrite, modelRead);

        BigDecimal billed = service.calculateCost(provider, "any-model",
                LlmCostProfile.CHAT_CONVERSATION.breakdownFor(provider));

        assertThat(billed).isEqualByComparingTo(
                clientEstimate(LlmCostProfile.CHAT_CONVERSATION, provider, service, modelWrite, modelRead, true));
    }

    @ParameterizedTest
    @ValueSource(strings = {"openai", "codex", "deepseek", "gemini", "gemini-cli", "some-unknown-vendor"})
    @DisplayName("outside Anthropic a published cache-WRITE price is never charged, so the estimate must not quote it")
    void aCacheWritePriceIsIgnoredOffAnthropic(String provider) {
        // Only the Anthropic families are billed off a cache-creation counter. Everywhere
        // else a first send is plain prompt input, so a model row carrying a cache-write
        // price (the catalogue mirrors what the feed publishes, charged or not) must not
        // move the figure at all.
        BigDecimal read = new BigDecimal("0.300000");
        ModelPricingService withWritePrice = service(new BigDecimal("7.000000"), read);
        ModelPricingService withoutWritePrice = service(null, read);

        BigDecimal billedWith = withWritePrice.calculateCost(provider, "any-model",
                LlmCostProfile.CHAT_CONVERSATION.breakdownFor(provider));
        BigDecimal billedWithout = withoutWritePrice.calculateCost(provider, "any-model",
                LlmCostProfile.CHAT_CONVERSATION.breakdownFor(provider));

        assertThat(billedWith).isEqualByComparingTo(billedWithout);
        assertThat(billedWith).isEqualByComparingTo(
                clientEstimate(LlmCostProfile.CHAT_CONVERSATION, provider, withWritePrice,
                        new BigDecimal("7.000000"), read, true));
    }

    @ParameterizedTest
    @ValueSource(strings = {"anthropic", "claude-code", "openai", "gemini", "some-unknown-vendor"})
    @DisplayName("a stored cache rate of zero is no price at all, on both sides, because a cached token is never free")
    void aZeroCacheRateIsNotAPrice(String provider) {
        // The mirror that fills these columns refuses to store a 0 for exactly this
        // reason, and the ledger's rateFor tests signum() > 0. A client that read 0 as a
        // real price would quote 138,200 cache-read tokens at nothing - a 34%
        // understatement on the one seed row that carries one - while the ledger charged
        // the family rate. This is the single case where the two sides could disagree,
        // so it is the case that gets its own test.
        ModelPricingService service = service(BigDecimal.ZERO, BigDecimal.ZERO);

        BigDecimal billed = service.calculateCost(provider, "any-model",
                LlmCostProfile.CHAT_CONVERSATION.breakdownFor(provider));

        assertThat(billed).isEqualByComparingTo(
                clientEstimate(LlmCostProfile.CHAT_CONVERSATION, provider, service,
                        BigDecimal.ZERO, BigDecimal.ZERO, true));
        // And a zero behaves as an ABSENT price, not as a free one: asserting only the
        // equality above would pass if both sides had gone free together.
        assertThat(billed).isEqualByComparingTo(
                serviceWithoutCachePrices().calculateCost(provider, "any-model",
                        LlmCostProfile.CHAT_CONVERSATION.breakdownFor(provider)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"anthropic", "openai", "gemini", "some-unknown-vendor"})
    @DisplayName("a model with no prompt caching is priced with every prompt token at its input rate")
    void aModelThatDoesNotCacheIsPricedAtTheInputRate(String provider) {
        // The counts describe a cached conversation. On a model that cannot cache, the
        // same conversation is simply re-sent in full every turn - no write, no read, no
        // discount - so every prompt token costs the input rate. That is the FORM the
        // two-coefficient contract had before cache tokens existed, and it is the
        // property asserted here; it is NOT the same figure, because the workload below
        // is 177,810 prompt tokens where the cache-free profile carried 15,000.
        ModelPricingService service = serviceWithoutCachePrices();

        BigDecimal billed = service.calculateCost(provider, "any-model",
                LlmCostProfile.CHAT_CONVERSATION.breakdownWithoutCache());

        assertThat(billed).isEqualByComparingTo(
                clientEstimate(LlmCostProfile.CHAT_CONVERSATION, provider, service, null, null, false));

        BigDecimal cacheFree = INPUT_RATE
                .multiply(BigDecimal.valueOf(LlmCostProfile.CHAT_CONVERSATION.promptTokensTotal()))
                .add(OUTPUT_RATE.multiply(BigDecimal.valueOf(LlmCostProfile.CHAT_CONVERSATION.outputTokens())))
                .divide(new BigDecimal("1000"), 6, RoundingMode.HALF_UP)
                .multiply(MEASUREMENT_ERA_MULTIPLIER);
        assertThat(billed).isEqualByComparingTo(cacheFree);
    }

    @Test
    @DisplayName("caching a conversation is what makes it cheaper, so the cached estimate stays well under the uncached one")
    void cachingIsWhatMakesTheConversationCheaper() {
        // If this ever inverts, the profile's proportions have drifted into a shape where
        // the platform would be quoting caching as a penalty - which would mean the
        // measurement, not the arithmetic, has gone wrong.
        ModelPricingService service = serviceWithoutCachePrices();

        BigDecimal cached = service.calculateCost("anthropic", "claude-sonnet-5",
                LlmCostProfile.CHAT_CONVERSATION.breakdownFor("anthropic"));
        BigDecimal uncached = service.calculateCost("anthropic", "claude-sonnet-5",
                LlmCostProfile.CHAT_CONVERSATION.breakdownWithoutCache());

        assertThat(cached.doubleValue()).isLessThan(uncached.doubleValue());
    }

    @Test
    @DisplayName("a step profile stays an order of magnitude under a conversation, which is why they are separate profiles at all")
    void profilesKeepTheirOrderOfMagnitude() {
        // The ordering used to be a four-way chain headed by an agent conversation. That
        // profile is gone: there is ONE conversation unit now, priced with its cache, and
        // the surfaces that used to quote the agent figure quote it instead.
        //
        // What is left is the property that made several profiles necessary in the first
        // place. A classify step is two orders of magnitude under a conversation, and THAT
        // is the gap a single "average" figure would have flattened into a number true of
        // nobody.
        ModelPricingService service = serviceWithoutCachePrices();

        BigDecimal chat = service.calculateCost("anthropic", "claude-sonnet-5",
                LlmCostProfile.CHAT_CONVERSATION.breakdownFor("anthropic"));
        BigDecimal guardrail = service.calculateCost("anthropic", "claude-sonnet-5",
                LlmCostProfile.GUARDRAIL_CHECK.breakdownFor("anthropic"));
        BigDecimal classify = service.calculateCost("anthropic", "claude-sonnet-5",
                LlmCostProfile.CLASSIFY_STEP.breakdownFor("anthropic"));

        assertThat(chat.doubleValue()).isGreaterThan(guardrail.doubleValue());
        assertThat(guardrail.doubleValue()).isGreaterThan(classify.doubleValue() * 5);
    }

    @Test
    @DisplayName("the profiles still reproduce the measured medians the pricing page publishes")
    void profilesReproduceTheMeasuredMedians() {
        // Drift here silently makes the pricing page and the model picker disagree about
        // the same model. Figures are on the reference model (Claude Sonnet 5, $2 / $10
        // per 1M) at the multiplier of the measurement era; the page publishes them scaled
        // to the current lever, which has been 1.11, then 2.0, and is 1.333333 since
        // 2026-09-17. The scale is exact because these are token counts: a pricing
        // decision changes what a token costs, never how many of them a unit of work
        // sends.
        //
        // CHAT is the 2026-09-16 measurement: 62 Anthropic chat turns over 30 days, median
        // 6 plain input, 24,601 cache writes, 85,816 cache reads and 2,112 output tokens,
        // scaled to a short exchange (x1.6106, see LlmCostProfile). The guardrail and
        // classify figures are still the 2026-09-03 medians.
        //
        // Sonnet 5's real cache prices (0.1x read, 1.25x write) are exactly the family
        // weights this fixture falls back to, so the reference figure is the same whether
        // or not the catalogue has filled its cache columns - which is why it can be
        // quoted on a page without naming a mirror state.
        ModelPricingService service = serviceWithoutCachePrices();

        assertThat(service.calculateCost("anthropic", "claude-sonnet-5",
                LlmCostProfile.CHAT_CONVERSATION.breakdownFor("anthropic")).doubleValue())
                .isCloseTo(178d, org.assertj.core.data.Offset.offset(5d));
        assertThat(service.calculateCost("anthropic", "claude-sonnet-5",
                LlmCostProfile.GUARDRAIL_CHECK.breakdownFor("anthropic")).doubleValue())
                .isCloseTo(37d, org.assertj.core.data.Offset.offset(5d));
        assertThat(service.calculateCost("anthropic", "claude-sonnet-5",
                LlmCostProfile.CLASSIFY_STEP.breakdownFor("anthropic")).doubleValue())
                .isCloseTo(3d, org.assertj.core.data.Offset.offset(1d));
    }

    @Test
    @DisplayName("the headline figure is about 933 credits on Claude Fable 5.1, the unit the platform publishes")
    void theHeadlineFigureIsAboutNineHundredOnTheFlagship() {
        // This is the number a user actually reads, on the model most of them pick, and it
        // is a product decision rather than an arithmetic one: the profile's PROPORTIONS
        // are the measurement and must not be tuned, but its SCALE sets the unit of work
        // the picker quotes. Pinning it here means a re-scale is a deliberate edit with a
        // test to change, not a side effect of a re-measurement.
        //
        // The margin moved 1.11 -> 2.0 on 2026-09-16 and this figure deliberately did not:
        // the unit shrank to a short exchange so the published price of a conversation
        // stayed where users knew it.
        //
        // The cut to 1.333333 on 2026-09-17 did the OPPOSITE, and deliberately: a cut
        // exists so a user pays less for the same work, so the unit was left alone and
        // the figure fell with the lever, 1,400 -> 933. Re-scaling the profile to hold
        // 1,400 would have kept the price the user sees while halving what they get for
        // it, which is the cut not reaching anybody.
        //
        // This REVERSES an earlier guard, and says so rather than quietly dropping it: a
        // test on this branch pinned the picker's unit at under 600 credits on this same
        // model, from a five-turn plain chat with no tool call and no cache. That unit
        // priced a workload the pickers are not used for - every one of them sits beside
        // a configured agent - so it under-quoted by roughly half. The bound moved up
        // because the unit moved to the work being configured, not because the price did.
        ModelPricingRepository repository = mock(ModelPricingRepository.class);
        ModelPricing fable = new ModelPricing();
        fable.setInputRate(new BigDecimal("10.000000"));
        fable.setOutputRate(new BigDecimal("50.000000"));
        fable.setCacheWriteRate(new BigDecimal("12.500000"));
        fable.setCacheReadRate(new BigDecimal("0.250000"));
        fable.setFixedCost(BigDecimal.ZERO);
        when(repository.findCurrentPricing(anyString(), anyString())).thenReturn(Optional.of(fable));
        ModelPricingService atShippedMargin = new ModelPricingService(repository, new BigDecimal("1.333333"));

        BigDecimal credits = atShippedMargin.calculateCost("anthropic", "claude-fable-5-1",
                LlmCostProfile.CHAT_CONVERSATION.breakdownFor("anthropic"));

        assertThat(credits.doubleValue()).isCloseTo(933d, org.assertj.core.data.Offset.offset(35d));
    }
}
