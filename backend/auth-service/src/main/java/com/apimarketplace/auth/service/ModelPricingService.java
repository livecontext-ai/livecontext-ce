package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.ModelPricing;
import com.apimarketplace.auth.repository.ModelPricingRepository;
import com.apimarketplace.common.credit.ModelTier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ModelPricingService {

    private static final Logger log = LoggerFactory.getLogger(ModelPricingService.class);
    private static final BigDecimal THOUSAND = new BigDecimal("1000");
    // Primary pricing lever. Overridable via property billing.llm.cloud-multiplier
    // (declared in auth-service application.yml); the PROD-effective value is pinned
    // explicitly in deploy/helm/livecontext/values-prod.yaml (services.auth.env) so
    // margin is declared and diff-tracked, not an implicit code default. This constant
    // is the safety fallback only. 1.333333 = 25% gross margin on LLM/conversation
    // billing (1.8 until 2026-09-03, 1.11 until 2026-09-16, 2.0 until 2026-09-17).
    // Six 3s rather than four: AgentSurfaceBillingMarginTest reads the margin back as
    // 1 - 1/m at six decimal places, and only this spelling lands on 25.0000% exactly.
    // See the project docs.
    // ONE spelling, used by both the @Value default below and the null-safety fallback.
    // They were two separate literals until 2026-09-17, and a margin change that moved
    // the constant alone left the property default still charging the old rate on any
    // deployment whose configuration did not set it - silently, and untested, because
    // the test for the code default constructs the service with a null instead of
    // resolving the annotation. A String constant is what lets an annotation share it.
    static final String DEFAULT_CLOUD_LLM_BILLING_MULTIPLIER_VALUE = "1.333333";
    /**
     * Shipped per-provider overrides, as ONE spelling shared by the annotation default
     * below and by {@code application.yml} (pinned equal by {@code EconomicsConfigPinTest}).
     *
     * <p><b>Empty, and that is the shipped policy.</b> Every provider bills at
     * {@link #DEFAULT_CLOUD_LLM_BILLING_MULTIPLIER_VALUE}, the platform's one margin, the
     * decision provider included. It carried {@code typesafe=10} between 2026-09-18 and
     * 2026-09-20: the argument was that a percentage of a cost base 48 times smaller earns
     * a margin that rounds to nothing, which is arithmetically true (about 0.016 credit on
     * a classification against 0.87 on the LLM path). It was dropped because the same
     * multiplier is what the user pays, and a decision model whose whole point is that it
     * costs almost nothing should not be the one row billed at four times the house rate.
     * The margin is now uniform and the price advantage is passed on in full.
     *
     * <p>A blank code default beside a populated YAML one is the same divergence the
     * multiplier above was repaired for: the two would disagree about a margin, and the
     * one that applies depends on which configuration a deployment happens to load. CE
     * neutralises this key explicitly in {@code application-ce.yml}, because a cloud
     * revenue lever must never be inherited by a self-hosted install.
     *
     * <p>The MECHANISM is kept, tested and empty rather than deleted: it is the only place
     * a provider-specific margin can be expressed, and re-adding it later as code would be
     * a billing change where today it is one configuration value. An empty value is not a
     * disabled feature, it is the statement that no provider currently needs one.
     */
    static final String DEFAULT_PROVIDER_MULTIPLIERS_VALUE = "";
    private static final BigDecimal DEFAULT_CLOUD_LLM_BILLING_MULTIPLIER =
            new BigDecimal(DEFAULT_CLOUD_LLM_BILLING_MULTIPLIER_VALUE);
    // Default fallback: mid-tier model (~$1/1M input, $4/1M output).
    // Stored token rates are provider USD per 1M tokens. calculateCost returns
    // billable credits after the managed-cloud LLM multiplier when the row is LLM-billed.
    private static final BigDecimal DEFAULT_INPUT_RATE = new BigDecimal("1.0");
    private static final BigDecimal DEFAULT_OUTPUT_RATE = new BigDecimal("4.0");

    // FALLBACK cache-token weights, relative to the model's input rate, for a model whose
    // OWN cache price is unknown (auth.model_pricing.cache_read_rate / cache_write_rate,
    // mirrored from the catalog feed by V491, wins whenever it is set). They mirror what
    // the providers charged when they were written, and are only ever an approximation of
    // a per-model price - which is why they stopped being the tariff:
    // Anthropic: cache write 1.25x (5-min TTL), cache read 0.1x. OpenAI: cached prompt
    // tokens (subset of prompt_tokens) ~0.5x. Gemini: cached content ~0.25x.
    // DeepSeek: cache hit ~0.1x.
    private static final BigDecimal DEFAULT_ANTHROPIC_CACHE_WRITE_MULTIPLIER = new BigDecimal("1.25");
    private static final BigDecimal DEFAULT_ANTHROPIC_CACHE_READ_MULTIPLIER = new BigDecimal("0.1");
    private static final BigDecimal DEFAULT_OPENAI_CACHED_MULTIPLIER = new BigDecimal("0.5");
    private static final BigDecimal DEFAULT_GEMINI_CACHED_MULTIPLIER = new BigDecimal("0.25");
    private static final BigDecimal DEFAULT_DEEPSEEK_CACHED_MULTIPLIER = new BigDecimal("0.1");
    // No family = no discount to guess with, so an unpriced model in an unknown family
    // bills its cached subset at full input rate (the pre-V491 behaviour). It is only a
    // fallback: a model whose catalog row carries cache_read_rate is billed at that rate.
    private static final BigDecimal OTHER_CACHED_MULTIPLIER = BigDecimal.ONE;

    /**
     * How long a cached pricing row is trusted before it is re-read (V494).
     *
     * <p>The cache used to live until {@link #refreshCache()}, which only ever runs on
     * the replica that handled the write. That was tolerable while the row only carried
     * RATES (a stale rate bills slightly wrong for a moment and reconciliation squares
     * it). It stopped being tolerable when the row started carrying {@code free_tier},
     * which is an ACCESS gate: an admin opening a model to the free tier refreshed one
     * replica, and every other one kept REFUSING that model, indefinitely, with nothing
     * to show for it but a user who is told they cannot run a model the admin opened.
     * A minute of staleness is the bound; it is not a distributed invalidation, and it
     * does not pretend to be.
     */
    private static final long PRICING_CACHE_TTL_MILLIS = 60_000L;

    private final ModelPricingRepository pricingRepository;
    private final Map<String, CachedPricing> pricingCache = new ConcurrentHashMap<>();
    /**
     * System time, replaceable so the TTL can be tested without sleeping through it.
     * Volatile because it is read on the billing hot path by every request thread while
     * a test may swap it.
     */
    private volatile Clock clock = Clock.systemUTC();

    /** A resolved row and the instant it stops being trusted. */
    private record CachedPricing(ModelPricing pricing, long expiresAtMillis) {
        boolean isFresh(long nowMillis) {
            return nowMillis < expiresAtMillis;
        }
    }
    private final BigDecimal cloudLlmBillingMultiplier;
    private final BigDecimal anthropicCacheWriteMultiplier;
    private final BigDecimal anthropicCacheReadMultiplier;
    private final BigDecimal openaiCachedMultiplier;
    private final BigDecimal geminiCachedMultiplier;
    private final BigDecimal deepseekCachedMultiplier;
    /**
     * Per-provider override of {@link #cloudLlmBillingMultiplier}, keyed by lowercase
     * provider name. Empty by default: every provider bills at the global lever unless
     * it is named here. See {@link #resolveCloudLlmBillingMultiplier}.
     */
    private final Map<String, BigDecimal> providerBillingMultipliers;

    @Autowired
    public ModelPricingService(
            ModelPricingRepository pricingRepository,
            @Value("${billing.llm.cloud-multiplier:" + DEFAULT_CLOUD_LLM_BILLING_MULTIPLIER_VALUE + "}")
            BigDecimal cloudLlmBillingMultiplier,
            @Value("${billing.llm.anthropic-cache-write-multiplier:1.25}") BigDecimal anthropicCacheWriteMultiplier,
            @Value("${billing.llm.anthropic-cache-read-multiplier:0.1}") BigDecimal anthropicCacheReadMultiplier,
            @Value("${billing.llm.openai-cached-multiplier:0.5}") BigDecimal openaiCachedMultiplier,
            @Value("${billing.llm.gemini-cached-multiplier:0.25}") BigDecimal geminiCachedMultiplier,
            @Value("${billing.llm.deepseek-cached-multiplier:0.1}") BigDecimal deepseekCachedMultiplier,
            @Value("${billing.llm.provider-multipliers:" + DEFAULT_PROVIDER_MULTIPLIERS_VALUE + "}")
            String providerMultipliersCsv) {
        this.pricingRepository = pricingRepository;
        this.providerBillingMultipliers = parseProviderMultipliers(providerMultipliersCsv);
        // Non-positive is refused, not merely null-checked. It was always nonsense as a
        // margin (zero would make every call free, negative would credit the user), and
        // since getProviderBillingScales DIVIDES by it, a configured zero would also throw
        // on every estimate-basis request. Falling back to the shipped default keeps the
        // service up and bills the standard margin, which is the same direction the
        // per-provider parser fails in.
        this.cloudLlmBillingMultiplier =
                cloudLlmBillingMultiplier != null && cloudLlmBillingMultiplier.signum() > 0
                ? cloudLlmBillingMultiplier
                : DEFAULT_CLOUD_LLM_BILLING_MULTIPLIER;
        if (cloudLlmBillingMultiplier != null && cloudLlmBillingMultiplier.signum() <= 0) {
            log.warn("Ignoring non-positive billing.llm.cloud-multiplier '{}'; billing at the "
                    + "default {}", cloudLlmBillingMultiplier, DEFAULT_CLOUD_LLM_BILLING_MULTIPLIER);
        }
        this.anthropicCacheWriteMultiplier = anthropicCacheWriteMultiplier != null
                ? anthropicCacheWriteMultiplier : DEFAULT_ANTHROPIC_CACHE_WRITE_MULTIPLIER;
        this.anthropicCacheReadMultiplier = anthropicCacheReadMultiplier != null
                ? anthropicCacheReadMultiplier : DEFAULT_ANTHROPIC_CACHE_READ_MULTIPLIER;
        this.openaiCachedMultiplier = openaiCachedMultiplier != null
                ? openaiCachedMultiplier : DEFAULT_OPENAI_CACHED_MULTIPLIER;
        this.geminiCachedMultiplier = geminiCachedMultiplier != null
                ? geminiCachedMultiplier : DEFAULT_GEMINI_CACHED_MULTIPLIER;
        this.deepseekCachedMultiplier = deepseekCachedMultiplier != null
                ? deepseekCachedMultiplier : DEFAULT_DEEPSEEK_CACHED_MULTIPLIER;
    }

    /**
     * Cache-weight constructor without per-provider overrides, kept so existing
     * call-sites that tune only the cache weights stay compiling. Every provider then
     * bills at the global lever.
     */
    public ModelPricingService(
            ModelPricingRepository pricingRepository,
            BigDecimal cloudLlmBillingMultiplier,
            BigDecimal anthropicCacheWriteMultiplier,
            BigDecimal anthropicCacheReadMultiplier,
            BigDecimal openaiCachedMultiplier,
            BigDecimal geminiCachedMultiplier,
            BigDecimal deepseekCachedMultiplier) {
        this(pricingRepository, cloudLlmBillingMultiplier,
                anthropicCacheWriteMultiplier, anthropicCacheReadMultiplier,
                openaiCachedMultiplier, geminiCachedMultiplier, deepseekCachedMultiplier, "");
    }

    /**
     * Legacy 2-arg constructor kept for existing test call-sites. Cache weights
     * fall back to the documented provider-true defaults.
     */
    public ModelPricingService(ModelPricingRepository pricingRepository, BigDecimal cloudLlmBillingMultiplier) {
        this(pricingRepository, cloudLlmBillingMultiplier, "");
    }

    /**
     * Test/overload entry point that sets the per-provider overrides without repeating
     * the five cache weights. {@code providerMultipliersCsv} takes the same
     * {@code provider=multiplier,provider=multiplier} spelling as the property.
     */
    public ModelPricingService(ModelPricingRepository pricingRepository,
                                BigDecimal cloudLlmBillingMultiplier,
                                String providerMultipliersCsv) {
        this(pricingRepository, cloudLlmBillingMultiplier,
                DEFAULT_ANTHROPIC_CACHE_WRITE_MULTIPLIER, DEFAULT_ANTHROPIC_CACHE_READ_MULTIPLIER,
                DEFAULT_OPENAI_CACHED_MULTIPLIER, DEFAULT_GEMINI_CACHED_MULTIPLIER,
                DEFAULT_DEEPSEEK_CACHED_MULTIPLIER, providerMultipliersCsv);
    }

    public BigDecimal calculateCost(String provider, String model, int promptTokens, int completionTokens) {
        return calculateCost(provider, model, LlmTokenBreakdown.of(promptTokens, completionTokens));
    }

    /**
     * Cache-aware cost. Bills each token class at the provider's true relative price
     * (cache reads/writes, cached prompt subsets, additive thinking output) so the
     * multiplier is the pricing multiplier. With an all-zero cache breakdown this
     * is exactly the legacy {@code inputRate*prompt + outputRate*completion} formula.
     */
    public BigDecimal calculateCost(String provider, String model, LlmTokenBreakdown usage) {
        return applyCloudLlmBillingMultiplier(provider, model, providerCost(provider, model, usage));
    }

    /**
     * The tokens at the provider's LIST price, in credits (1 credit = $0.001), BEFORE the
     * cloud multiplier: what the provider itself bills for this usage. Shown to a user who
     * ran the turn on their OWN key as the estimate of their provider's charge; never a
     * platform debit.
     */
    public BigDecimal providerCost(String provider, String model, LlmTokenBreakdown usage) {
        ModelPricing pricing = getPricing(provider, model);

        BigDecimal inputCost = weightedInputRateTokens(provider, usage, pricing)
                .divide(THOUSAND, 6, RoundingMode.HALF_UP);

        BigDecimal outputCost = pricing.getOutputRate()
                .multiply(billableOutputTokens(provider, usage))
                .divide(THOUSAND, 6, RoundingMode.HALF_UP);

        return inputCost.add(outputCost).add(pricing.getFixedCost());
    }

    /**
     * The model's price band, from its output rate with the platform-wide rule
     * ({@link ModelTier#classify}); {@link ModelTier#UNKNOWN} for a model this service has
     * no pricing row for (never the mid-tier default rates: an unknown model must not be
     * billed as if it were known).
     */
    public ModelTier tierOf(String provider, String model) {
        if (!hasPricing(provider, model)) {
            return ModelTier.UNKNOWN;
        }
        return ModelTier.classify(getPricing(provider, model).getOutputRate());
    }

    /**
     * The input side of the bill, as {@code sum(rate_of_class * tokens_of_class)} still
     * expressed in USD-per-1M units (the caller divides by 1000 once, so the arithmetic
     * is identical to the single-division form it replaces).
     *
     * <p>Each class is priced at the MODEL's own rate ({@code cache_read_rate} /
     * {@code cache_write_rate}, mirrored from the catalog feed by V491) and only falls
     * back to {@code inputRate} times the provider-family multiplier when the model has
     * no known cache price. That fallback is why this is a no-op for a row the mirror
     * could not fill, and why a brand-new provider is never worse off than before.
     *
     * <p>See {@link LlmTokenBreakdown} for the per-family field semantics: Anthropic
     * reports cache tokens ADDITIVELY (outside {@code promptTokens}), everyone else
     * reports a cached SUBSET of it.
     */
    private BigDecimal weightedInputRateTokens(String provider, LlmTokenBreakdown usage, ModelPricing pricing) {
        BigDecimal inputRate = pricing.getInputRate();
        BigDecimal prompt = BigDecimal.valueOf(Math.max(0, usage.promptTokens()));
        ProviderFamily family = ProviderFamily.of(provider);
        switch (family) {
            case ANTHROPIC_API, ANTHROPIC_CLI -> {
                BigDecimal write = BigDecimal.valueOf(Math.max(0, usage.cacheCreationTokens()));
                BigDecimal read = BigDecimal.valueOf(Math.max(0, usage.cacheReadTokens()));
                // claude-code bridge promptTokens already include cache tokens - strip
                // them to get the plain-input base (clamped at 0 defensively).
                BigDecimal base = family == ProviderFamily.ANTHROPIC_CLI
                        ? prompt.subtract(write).subtract(read).max(BigDecimal.ZERO)
                        : prompt;
                return base.multiply(inputRate)
                        .add(write.multiply(rateFor(pricing.getCacheWriteRate(), inputRate, anthropicCacheWriteMultiplier)))
                        .add(read.multiply(rateFor(pricing.getCacheReadRate(), inputRate, anthropicCacheReadMultiplier)));
            }
            case OPENAI -> {
                return weightedCachedSubset(prompt, cachedSubset(usage, false), inputRate,
                        rateFor(pricing.getCacheReadRate(), inputRate, openaiCachedMultiplier));
            }
            case DEEPSEEK -> {
                return weightedCachedSubset(prompt, cachedSubset(usage, false), inputRate,
                        rateFor(pricing.getCacheReadRate(), inputRate, deepseekCachedMultiplier));
            }
            case GOOGLE_API, GOOGLE_CLI -> {
                // Direct API reports cached content in cachedTokens; the gemini-cli
                // bridge maps it into cacheReadTokens. Either way it is a subset of
                // promptTokens - take the larger of the two (never both populated).
                return weightedCachedSubset(prompt, cachedSubset(usage, true), inputRate,
                        rateFor(pricing.getCacheReadRate(), inputRate, geminiCachedMultiplier));
            }
            default -> {
                // No family, so no multiplier to guess with: an unpriced model keeps the
                // legacy "cached input costs full rate" behaviour (OTHER_CACHED_MULTIPLIER
                // = 1). But when the catalog DOES know this model's cache price - qwen,
                // moonshot, minimax and every future OpenAI-compatible vendor - the cached
                // subset is billed at it instead of at full input rate.
                return weightedCachedSubset(prompt, cachedSubset(usage, false), inputRate,
                        rateFor(pricing.getCacheReadRate(), inputRate, OTHER_CACHED_MULTIPLIER));
            }
        }
    }

    /**
     * The model's own per-1M rate for a cache class, or {@code inputRate * multiplier}
     * when the catalog has no price for it. A non-positive stored rate is treated as
     * unknown: 0 would make cached input free, and V491 writes NULL rather than 0 for
     * exactly that reason, so this is defence in depth against a bad mirror row.
     */
    private static BigDecimal rateFor(BigDecimal modelRate, BigDecimal inputRate, BigDecimal fallbackMultiplier) {
        if (modelRate != null && modelRate.signum() > 0) {
            return modelRate;
        }
        return inputRate.multiply(fallbackMultiplier);
    }

    /** The cached-subset counter, clamped at 0. Google reports it under either name. */
    private static BigDecimal cachedSubset(LlmTokenBreakdown usage, boolean alsoCacheRead) {
        int cached = Math.max(0, usage.cachedTokens());
        if (alsoCacheRead) {
            cached = Math.max(cached, Math.max(0, usage.cacheReadTokens()));
        }
        return BigDecimal.valueOf(cached);
    }

    /**
     * Billable output tokens. The Gemini direct API reports thinking (thoughts) as a
     * separate additive output counter NOT included in candidatesTokenCount - bill it
     * at the output rate. OpenAI reasoning tokens are already inside completionTokens.
     *
     * <p>GOOGLE_CLI (gemini-cli bridge) is intentionally NOT additive: the CLI's
     * output_tokens counter may already include thoughts, and adding reasoning on top
     * would double-bill. Until that is proven otherwise, the CLI path errs on the
     * cheap-for-the-user side (thoughts unbilled when output_tokens excludes them).
     */
    private BigDecimal billableOutputTokens(String provider, LlmTokenBreakdown usage) {
        BigDecimal completion = BigDecimal.valueOf(Math.max(0, usage.completionTokens()));
        if (ProviderFamily.of(provider) == ProviderFamily.GOOGLE_API) {
            return completion.add(BigDecimal.valueOf(Math.max(0, usage.reasoningTokens())));
        }
        return completion;
    }

    /**
     * {@code (prompt - cached) * inputRate + cached * cachedRate}, with {@code cached}
     * clamped to prompt (it is a subset of it, and a provider that over-reports must
     * not drive the plain-input term negative).
     */
    private static BigDecimal weightedCachedSubset(BigDecimal prompt, BigDecimal cached,
                                                   BigDecimal inputRate, BigDecimal cachedRate) {
        BigDecimal clamped = cached.min(prompt);
        return prompt.subtract(clamped).multiply(inputRate).add(clamped.multiply(cachedRate));
    }

    /**
     * Provider families for token-accounting semantics. Matching is on the billing
     * {@code provider} string stamped by the executors (bridge providers keep their
     * CLI name, e.g. {@code claude-code}, direct API providers their vendor name).
     */
    enum ProviderFamily {
        ANTHROPIC_API, ANTHROPIC_CLI, OPENAI, DEEPSEEK, GOOGLE_API, GOOGLE_CLI, OTHER;

        /**
         * Every billing provider string this service reads in a family other than
         * {@link #OTHER}, which is now every provider that reports a cached subset at
         * all. {@code mistral} is deliberately absent: it has its own provider rather
         * than the OpenAI-compatible factory and never populates {@code cachedTokens},
         * so there is nothing there to weight.
         *
         * <p>It is a map rather than a switch because the same list has a second reader:
         * {@link ModelPricingService#cacheRateFallbacks()} publishes one entry per known
         * provider so a pre-flight estimate resolves a cache rate exactly as this class
         * does. A switch cannot be enumerated, so the list would have been written twice
         * and the copy would drift the first time a vendor was added.
         *
         * <p>OpenAI-compatible vendors (OpenAICompatibleProviderFactory registers them
         * under these literal instance names - the billing provider string) return the
         * OpenAI usage shape incl. prompt_tokens_details.cached_tokens, so their cached
         * input MUST be billed at the OPENAI 0.5x cache rate. Without them they fell to
         * OTHER, whose input weighting ignores cachedTokens → cached input was over-billed
         * at full input rate. ("openai-compatible" is a legacy/dead alias the factory never
         * emits; kept for back-compat.)
         *
         * <p>{@code qwen}, {@code moonshot} and {@code minimax} joined them on
         * 2026-09-17, which is the same defect arriving late: they were added to the
         * factory after the first four were classified here, and inherited OTHER by
         * omission rather than by decision. The fix only reaches a model whose catalogue
         * row has NO cache price of its own - 28 of their 59 seed rows - because since
         * V491 a published rate wins over any family weight. For those 28 it HALVES what
         * a cached token costs the user. Nothing else in the class branches on this
         * distinction: OPENAI and OTHER read the same cached-subset counter, and differ
         * only in the multiplier applied when the price is unknown.
         */
        static final Map<String, ProviderFamily> BY_PROVIDER = Map.ofEntries(
                Map.entry("claude-code", ANTHROPIC_CLI),
                Map.entry("anthropic", ANTHROPIC_API),
                Map.entry("claude", ANTHROPIC_API),
                Map.entry("openai", OPENAI),
                Map.entry("codex", OPENAI),
                Map.entry("azure-openai", OPENAI),
                Map.entry("openai-compatible", OPENAI),
                Map.entry("xai", OPENAI),
                Map.entry("openrouter", OPENAI),
                Map.entry("zai", OPENAI),
                Map.entry("perplexity", OPENAI),
                Map.entry("cohere", OPENAI),
                Map.entry("qwen", OPENAI),
                Map.entry("moonshot", OPENAI),
                Map.entry("minimax", OPENAI),
                Map.entry("deepseek", DEEPSEEK),
                Map.entry("google", GOOGLE_API),
                Map.entry("gemini", GOOGLE_API),
                Map.entry("gemini-cli", GOOGLE_CLI));

        static ProviderFamily of(String provider) {
            String p = provider != null ? provider.toLowerCase() : "";
            return BY_PROVIDER.getOrDefault(p, OTHER);
        }
    }

    /**
     * The provider string a client uses when its model's own provider is not one this
     * service knows. Published as a key of {@link #cacheRateFallbacks()} beside the real
     * ones, so the client has a defined answer instead of a rule of its own.
     */
    public static final String DEFAULT_CACHE_FALLBACK_KEY = "*";

    /**
     * How a cache rate is resolved for a model billed under one provider, expressed so a
     * pre-flight estimate can reproduce it without restating any of it.
     *
     * <p>{@code cacheReadWeight} and {@code cacheWriteWeight} multiply the model's INPUT
     * rate and apply only when the catalogue publishes no price for that class - the
     * model's own rate wins wherever it exists, which is what V491 made true.
     *
     * <p>{@code modelCacheWritePriceApplies} is the part that is NOT a fallback. Only the
     * Anthropic family is billed off a cache-write counter at all; for every other family
     * a first-send is plain prompt input, so its published cache-WRITE price is never
     * charged and an estimate that used it would quote a price nobody pays. Those
     * families therefore carry {@code cacheWriteWeight = 1} (the input rate) and
     * {@code false} here.
     *
     * <p>What publishing these does and does not give away. They are cost-basis
     * approximations of what a provider charges for a cached token relative to a plain
     * one, and they are set to the providers' own published ratios. They do NOT carry
     * {@code billing.llm.cloud-multiplier}: the margin stays folded into the estimate
     * coefficients and is never a named field on the wire. They are still operator
     * levers ({@code billing.llm.*-cache*-multiplier}), so an install that moved one away
     * from the provider's true ratio would be publishing that choice - which is the
     * accepted cost of a browser resolving a rate the same way the ledger does.
     */
    public record CacheRateFallback(BigDecimal cacheWriteWeight,
                                    BigDecimal cacheReadWeight,
                                    boolean modelCacheWritePriceApplies) {}

    /**
     * One {@link CacheRateFallback} per known billing provider, plus
     * {@value #DEFAULT_CACHE_FALLBACK_KEY} for everything else.
     *
     * <p>Exposed for {@link LlmCostEstimateService}: without it a browser cannot price a
     * cached token for a model whose catalogue row has no cache price, and its only other
     * option is the model's full input rate - which over-states the cheapest token class
     * of a chat profile by up to ten times.
     */
    public Map<String, CacheRateFallback> cacheRateFallbacks() {
        Map<String, CacheRateFallback> byProvider = new LinkedHashMap<>();
        for (Map.Entry<String, ProviderFamily> entry : ProviderFamily.BY_PROVIDER.entrySet()) {
            byProvider.put(entry.getKey(), cacheRateFallback(entry.getValue()));
        }
        byProvider.put(DEFAULT_CACHE_FALLBACK_KEY, cacheRateFallback(ProviderFamily.OTHER));
        return byProvider;
    }

    private CacheRateFallback cacheRateFallback(ProviderFamily family) {
        return switch (family) {
            case ANTHROPIC_API, ANTHROPIC_CLI -> new CacheRateFallback(
                    anthropicCacheWriteMultiplier, anthropicCacheReadMultiplier, true);
            case OPENAI -> new CacheRateFallback(BigDecimal.ONE, openaiCachedMultiplier, false);
            case DEEPSEEK -> new CacheRateFallback(BigDecimal.ONE, deepseekCachedMultiplier, false);
            case GOOGLE_API, GOOGLE_CLI -> new CacheRateFallback(BigDecimal.ONE, geminiCachedMultiplier, false);
            case OTHER -> new CacheRateFallback(BigDecimal.ONE, OTHER_CACHED_MULTIPLIER, false);
        };
    }

    /**
     * The effective managed-cloud billing multiplier.
     *
     * <p>Exposed so a pre-flight COST ESTIMATE can be published without any other
     * layer restating the number: the margin lever has exactly one home
     * ({@code billing.llm.cloud-multiplier}, pinned per-environment and guarded by
     * {@code EconomicsConfigPinTest}), and a picker that showed a second, stale copy
     * of it would quote a price the ledger does not charge.
     */
    public BigDecimal getCloudLlmBillingMultiplier() {
        return cloudLlmBillingMultiplier;
    }

    /**
     * How much a provider's bill differs from what {@link #getCloudLlmBillingMultiplier}
     * alone would predict, as a factor per provider. Empty when no provider is overridden.
     *
     * <p><b>Why a factor and not the multiplier itself.</b> The published estimate is a
     * table of per-profile COEFFICIENTS with the global lever already folded in, and it
     * carries no provider. Adding a second multiplier to that payload would be the stale
     * copy this class's contract forbids; a factor is derived from the one lever and says
     * exactly what it means, which is "multiply the coefficient answer by this for this
     * provider". A provider that is not overridden is absent, and absent means 1.
     *
     * <p>Without it a picker quoting an overridden provider under-quotes it by the full
     * ratio, which is the invariant {@code LlmCostEstimateService} exists to hold: the
     * estimate a picker renders is the figure the ledger will debit.
     */
    public Map<String, BigDecimal> getProviderBillingScales() {
        if (providerBillingMultipliers.isEmpty()) {
            return Map.of();
        }
        Map<String, BigDecimal> scales = new LinkedHashMap<>();
        providerBillingMultipliers.forEach((provider, multiplier) ->
                scales.put(provider, multiplier.divide(cloudLlmBillingMultiplier, 6, RoundingMode.HALF_UP)));
        return Map.copyOf(scales);
    }

    /**
     * Whether this model is one of those a cloud admin opened to the FREE plan.
     *
     * <p>Mirror of the catalog's {@code free_tier_enabled}, kept in
     * {@code auth.model_pricing.free_tier} by agent-service's pricing sync. Billing
     * itself is unaffected: every plan is billed at the same multiplier, and what
     * this flag gates is ACCESS (which models a free account may run) alongside the
     * admin-configured usage allowance.
     *
     * <p><b>Fails closed.</b> An unknown {@code (provider, model)} resolves through
     * {@link #getPricing}'s synthetic default row, whose flag is FALSE, so a model
     * missing from the billing mirror is simply not on the free tier.
     */
    public boolean isFreeTierModel(String provider, String model) {
        if (provider == null || model == null) {
            return false;
        }
        return Boolean.TRUE.equals(getPricing(provider, model).getFreeTier());
    }

    public BigDecimal applyCloudLlmBillingMultiplier(String provider, String model, BigDecimal amount) {
        BigDecimal safeAmount = amount != null ? amount : BigDecimal.ZERO;
        if (!usesCloudLlmBillingMultiplier(provider, model)) {
            return safeAmount;
        }
        return safeAmount.multiply(resolveCloudLlmBillingMultiplier(provider))
                .setScale(6, RoundingMode.HALF_UP);
    }

    /**
     * The margin lever that applies to {@code provider}: its own override when one is
     * configured, the global {@code billing.llm.cloud-multiplier} otherwise.
     *
     * <p><b>Nothing is overridden as shipped</b> ({@link #DEFAULT_PROVIDER_MULTIPLIERS_VALUE}
     * is empty), so in practice this returns the global lever for every provider and the
     * platform has ONE margin. What follows is the case the mechanism exists for, kept
     * because it is the reasoning a future operator needs before setting a value here.
     *
     * <p>The global lever is a PERCENTAGE, so a percentage of a provider cost that has
     * collapsed by an order of magnitude is a margin that rounds to nothing. A decision
     * model (TypeSafe's Jev) prices its input at $0.042 per 1M tokens against Claude
     * Sonnet 5's $2.00 and bills no output at all, so the classification that costs 2.6
     * credits at the provider costs about 0.048 on Jev: at the global lever that is 0.016
     * credit of margin per call against 0.87 on the LLM path. Raising the lever for one
     * provider is the honest place to correct that, rather than a fixed per-call fee,
     * because it keeps the multiplier the platform's ONE margin mechanism exactly as
     * {@code billing.llm.cloud-multiplier}'s contract states. It is not set today: the
     * same multiplier is what the user pays, and the deliberate choice is to pass a
     * cheaper engine's saving on rather than take a wider cut on it.
     *
     * <p>An unknown provider, a blank configuration, or a malformed entry all resolve to
     * the global lever: the failure direction is the platform's standard margin, never a
     * free call.
     */
    BigDecimal resolveCloudLlmBillingMultiplier(String provider) {
        if (provider == null) {
            return cloudLlmBillingMultiplier;
        }
        return providerBillingMultipliers.getOrDefault(
                provider.toLowerCase(), cloudLlmBillingMultiplier);
    }

    /**
     * Parses {@code billing.llm.provider-multipliers}, spelled
     * {@code provider=multiplier,provider=multiplier} so it travels as one environment
     * variable alongside the other {@code BILLING_LLM_*} levers.
     *
     * <p>A malformed pair, or one whose multiplier is not strictly positive, is logged
     * and DROPPED rather than failing the service: the provider then bills at the global
     * lever, which is the platform's normal margin. Failing startup over a billing typo
     * would take auth-service down and with it every login; billing one provider at the
     * standard rate is the smaller, reversible harm. A zero or negative value is rejected
     * because it would zero out or invert the charge, which no configuration should be
     * able to do by accident.
     */
    private static Map<String, BigDecimal> parseProviderMultipliers(String csv) {
        if (csv == null || csv.isBlank()) {
            return Map.of();
        }
        Map<String, BigDecimal> parsed = new LinkedHashMap<>();
        for (String pair : csv.split(",")) {
            String entry = pair.trim();
            if (entry.isEmpty()) {
                continue;
            }
            int separator = entry.indexOf('=');
            if (separator <= 0 || separator == entry.length() - 1) {
                log.warn("Ignoring malformed billing.llm.provider-multipliers entry '{}' "
                        + "(expected provider=multiplier); that provider bills at the global lever", entry);
                continue;
            }
            String providerName = entry.substring(0, separator).trim().toLowerCase();
            String rawMultiplier = entry.substring(separator + 1).trim();
            try {
                BigDecimal multiplier = new BigDecimal(rawMultiplier);
                if (multiplier.signum() <= 0) {
                    log.warn("Ignoring non-positive billing.llm.provider-multipliers value '{}' for provider '{}'; "
                            + "that provider bills at the global lever", rawMultiplier, providerName);
                    continue;
                }
                parsed.put(providerName, multiplier);
            } catch (NumberFormatException e) {
                log.warn("Ignoring unparseable billing.llm.provider-multipliers value '{}' for provider '{}'; "
                        + "that provider bills at the global lever", rawMultiplier, providerName);
            }
        }
        if (!parsed.isEmpty()) {
            log.info("Per-provider LLM billing multipliers active: {}", parsed);
        }
        return Map.copyOf(parsed);
    }

    /**
     * Per-unit cost helper for tools that bill linearly per discrete output
     * (image generation, future PDF generation, …) rather than per token.
     *
     * <p>Unlike {@link #calculateCost} (which divides by 1000 because LLM
     * rates are quoted per 1M tokens), {@code calculateUnitCost} treats
     * {@code input_rate} as the per-unit cost directly:
     * <pre>cost = input_rate × units + fixed_cost</pre>
     *
     * <p>Image-generation seeds in V141 store the per-image credit cost in
     * {@code input_rate} (e.g. {@code gpt-image-1-low → 10}, {@code -high → 80})
     * and leave {@code fixed_cost = 0}.
     *
     * <p>Callers in pre-flight gates MUST first verify {@link #hasPricing}:
     * the silent default-rate fallback used by {@link #calculateCost} is
     * dangerous here because the defaults ({@code 1.0 / 4.0 USD per 1M tokens})
     * are meaningless for image billing and would massively under-charge.
     */
    public BigDecimal calculateUnitCost(String provider, String model, int units) {
        if (units <= 0) {
            return BigDecimal.ZERO;
        }
        ModelPricing pricing = getPricing(provider, model);
        return pricing.getInputRate()
                .multiply(BigDecimal.valueOf(units))
                .add(pricing.getFixedCost());
    }

    /**
     * Whether a pricing row exists in the DB for {@code (provider, model)}.
     *
     * <p>{@link #calculateCost} silently falls back to {@link #DEFAULT_INPUT_RATE} /
     * {@link #DEFAULT_OUTPUT_RATE} when no row is found. That fail-open is fine for
     * post-flight reconciliation (the ledger still records usage at <i>some</i> rate
     * rather than zero), but it is <b>not safe</b> for the pre-flight budget gate:
     * a user can send a turn that resolves to an unknown model, pass the gate at
     * default rates, run the LLM at a much higher real rate, and then the post-flight
     * debit fails with 402 - the exact incident this whole fix exists to prevent.
     *
     * <p>Callers of {@link #calculateCost} in gate paths MUST check {@code hasPricing}
     * first and reject when {@code false}.
     */
    public boolean hasPricing(String provider, String model) {
        if (provider == null || provider.isBlank() || model == null || model.isBlank()) {
            return false;
        }
        // Goes through the same cache as every other read, so an unknown model - the
        // case this method exists for - does not hit the database on every pre-flight.
        // A synthetic default row has no id: it stands for "no pricing", and saying
        // otherwise would let the budget gate through at default rates.
        return getPricing(provider, model).getId() != null;
    }

    public BigDecimal calculateFixedCost(String sourceType) {
        return BigDecimal.ZERO;
    }

    private boolean usesCloudLlmBillingMultiplier(String provider, String model) {
        String normalizedProvider = provider != null ? provider.toLowerCase() : "";
        if ("websearch".equals(normalizedProvider) || "stability-ai".equals(normalizedProvider)) {
            return false;
        }

        String normalizedModel = model != null ? model.toLowerCase() : "";
        return !normalizedModel.contains("image")
                && !normalizedModel.startsWith("dall-e");
    }

    private ModelPricing getPricing(String provider, String model) {
        String key = provider + ":" + model;
        long now = clock.millis();
        CachedPricing cached = pricingCache.get(key);
        if (cached != null && cached.isFresh(now)) {
            return cached.pricing();
        }
        // compute(), not get-then-put: it holds the bin lock for this key, so a cold
        // cache under load issues ONE read per model instead of one per in-flight
        // request. That single-flight property came free with the computeIfAbsent this
        // replaced, and losing it silently would only show up as a load spike after a
        // deploy. The mapping function does a single indexed read and touches no other
        // key, which is what makes it safe to run under that lock.
        return pricingCache.compute(key, (k, existing) ->
                existing != null && existing.isFresh(now)
                        ? existing
                        : new CachedPricing(loadPricing(provider, model), now + PRICING_CACHE_TTL_MILLIS)
        ).pricing();
    }

    private ModelPricing loadPricing(String provider, String model) {
        Optional<ModelPricing> pricing = pricingRepository.findCurrentPricing(provider, model);
        if (pricing.isPresent()) {
            return pricing.get();
        }
        log.warn("No pricing found for {}/{}. Using default rates.", provider, model);
        ModelPricing defaultPricing = new ModelPricing();
        defaultPricing.setProvider(provider);
        defaultPricing.setModel(model);
        defaultPricing.setInputRate(DEFAULT_INPUT_RATE);
        defaultPricing.setOutputRate(DEFAULT_OUTPUT_RATE);
        defaultPricing.setFixedCost(BigDecimal.ZERO);
        return defaultPricing;
    }

    @Transactional
    public void upsertPricing(String provider, String model, BigDecimal inputRate, BigDecimal outputRate) {
        upsertPricing(provider, model, inputRate, outputRate, null);
    }

    /**
     * @param providerKind optional; when {@code null} keep existing kind on update, or fall
     *                     back to the column default ("byok") on insert. Callers that know
     *                     the catalog origin - admin UI saving a bridge model, bundle apply
     *                     reconciling a bridge row - should pass "bridge" so the billing
     *                     mirror carries the right discriminator.
     */
    @Transactional
    public void upsertPricing(String provider, String model, BigDecimal inputRate, BigDecimal outputRate,
                              String providerKind) {
        upsertPricing(provider, model, inputRate, outputRate, providerKind, null, null, null);
    }

    /**
     * Rate variant carrying the model's own cache prices (V491).
     *
     * @param cacheReadRate  provider USD per 1M cached-input tokens, or {@code null} when
     *                       the catalog does not know it. {@code null} LEAVES an existing
     *                       value in place rather than clearing it: the sync is fired from
     *                       several paths and a caller that simply has nothing to say about
     *                       the cache must not silently demote the row back to the family
     *                       multiplier.
     * @param cacheWriteRate provider USD per 1M cache-creation tokens, same null semantics.
     */
    @Transactional
    public void upsertPricing(String provider, String model, BigDecimal inputRate, BigDecimal outputRate,
                              String providerKind, BigDecimal cacheReadRate, BigDecimal cacheWriteRate) {
        upsertPricing(provider, model, inputRate, outputRate, providerKind,
                cacheReadRate, cacheWriteRate, null);
    }

    /**
     * @param freeTier optional mirror of the catalog's {@code free_tier_enabled};
     *                 {@code null} keeps the stored value, so a caller that does not
     *                 know the flag (a legacy sync) never silently clears it.
     */
    @Transactional
    public void upsertPricing(String provider, String model, BigDecimal inputRate, BigDecimal outputRate,
                              String providerKind, BigDecimal cacheReadRate, BigDecimal cacheWriteRate,
                              Boolean freeTier) {
        Optional<ModelPricing> existing = pricingRepository.findCurrentPricing(provider, model);
        if (existing.isPresent()) {
            ModelPricing pricing = existing.get();
            pricing.setInputRate(inputRate);
            pricing.setOutputRate(outputRate);
            if (providerKind != null && !providerKind.isBlank()) {
                pricing.setProviderKind(providerKind);
            }
            if (cacheReadRate != null) {
                pricing.setCacheReadRate(cacheReadRate);
            }
            if (cacheWriteRate != null) {
                pricing.setCacheWriteRate(cacheWriteRate);
            }
            if (freeTier != null) {
                pricing.setFreeTier(freeTier);
            }
            pricingRepository.save(pricing);
            log.info("Updated pricing for {}/{}: input={}, output={}, cacheRead={}, cacheWrite={}, kind={}",
                    provider, model, inputRate, outputRate,
                    pricing.getCacheReadRate(), pricing.getCacheWriteRate(), pricing.getProviderKind());
        } else {
            ModelPricing pricing = new ModelPricing();
            pricing.setProvider(provider);
            pricing.setModel(model);
            pricing.setInputRate(inputRate);
            pricing.setOutputRate(outputRate);
            pricing.setCacheReadRate(cacheReadRate);
            pricing.setCacheWriteRate(cacheWriteRate);
            pricing.setFixedCost(BigDecimal.ZERO);
            pricing.setEffectiveFrom(LocalDate.now());
            pricing.setIsActive(true);
            if (providerKind != null && !providerKind.isBlank()) {
                pricing.setProviderKind(providerKind);
            }
            pricing.setFreeTier(freeTier);
            pricingRepository.save(pricing);
            log.info("Created pricing for {}/{}: input={}, output={}, cacheRead={}, cacheWrite={}, kind={}",
                    provider, model, inputRate, outputRate, cacheReadRate, cacheWriteRate,
                    pricing.getProviderKind());
        }
        refreshCache();
    }

    /**
     * Drops every cached row on THIS replica. The write path calls it so an admin's
     * own next read is correct immediately; the other replicas catch up within
     * {@link #PRICING_CACHE_TTL_MILLIS}.
     */
    public void refreshCache() {
        pricingCache.clear();
    }

    /** Test seam for {@link #PRICING_CACHE_TTL_MILLIS}: lets a test move time instead of waiting. */
    void setClock(Clock clock) {
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    public List<ModelPricing> getAllActivePricing() {
        return pricingRepository.findByIsActiveTrue();
    }
}
