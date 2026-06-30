package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.ModelPricing;
import com.apimarketplace.auth.repository.ModelPricingRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
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
    // is the safety fallback only. See the project docs.
    private static final BigDecimal DEFAULT_CLOUD_LLM_BILLING_MULTIPLIER = new BigDecimal("1.8");
    // Default fallback: mid-tier model (~$1/1M input, $4/1M output).
    // Stored token rates are provider USD per 1M tokens. calculateCost returns
    // billable credits after the managed-cloud LLM multiplier when the row is LLM-billed.
    private static final BigDecimal DEFAULT_INPUT_RATE = new BigDecimal("1.0");
    private static final BigDecimal DEFAULT_OUTPUT_RATE = new BigDecimal("4.0");

    // Cache-token billing weights - relative to the model's input rate, mirroring what
    // the providers actually charge (so the cloud multiplier is the pricing multiplier):
    // Anthropic: cache write 1.25x (5-min TTL), cache read 0.1x. OpenAI: cached prompt
    // tokens (subset of prompt_tokens) ~0.5x. Gemini: cached content ~0.25x.
    // DeepSeek: cache hit ~0.1x.
    private static final BigDecimal DEFAULT_ANTHROPIC_CACHE_WRITE_MULTIPLIER = new BigDecimal("1.25");
    private static final BigDecimal DEFAULT_ANTHROPIC_CACHE_READ_MULTIPLIER = new BigDecimal("0.1");
    private static final BigDecimal DEFAULT_OPENAI_CACHED_MULTIPLIER = new BigDecimal("0.5");
    private static final BigDecimal DEFAULT_GEMINI_CACHED_MULTIPLIER = new BigDecimal("0.25");
    private static final BigDecimal DEFAULT_DEEPSEEK_CACHED_MULTIPLIER = new BigDecimal("0.1");

    private final ModelPricingRepository pricingRepository;
    private final Map<String, ModelPricing> pricingCache = new ConcurrentHashMap<>();
    private final BigDecimal cloudLlmBillingMultiplier;
    private final BigDecimal anthropicCacheWriteMultiplier;
    private final BigDecimal anthropicCacheReadMultiplier;
    private final BigDecimal openaiCachedMultiplier;
    private final BigDecimal geminiCachedMultiplier;
    private final BigDecimal deepseekCachedMultiplier;

    @Autowired
    public ModelPricingService(
            ModelPricingRepository pricingRepository,
            @Value("${billing.llm.cloud-multiplier:1.8}") BigDecimal cloudLlmBillingMultiplier,
            @Value("${billing.llm.anthropic-cache-write-multiplier:1.25}") BigDecimal anthropicCacheWriteMultiplier,
            @Value("${billing.llm.anthropic-cache-read-multiplier:0.1}") BigDecimal anthropicCacheReadMultiplier,
            @Value("${billing.llm.openai-cached-multiplier:0.5}") BigDecimal openaiCachedMultiplier,
            @Value("${billing.llm.gemini-cached-multiplier:0.25}") BigDecimal geminiCachedMultiplier,
            @Value("${billing.llm.deepseek-cached-multiplier:0.1}") BigDecimal deepseekCachedMultiplier) {
        this.pricingRepository = pricingRepository;
        this.cloudLlmBillingMultiplier = cloudLlmBillingMultiplier != null
                ? cloudLlmBillingMultiplier
                : DEFAULT_CLOUD_LLM_BILLING_MULTIPLIER;
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
     * Legacy 2-arg constructor kept for existing test call-sites. Cache weights
     * fall back to the documented provider-true defaults.
     */
    public ModelPricingService(ModelPricingRepository pricingRepository, BigDecimal cloudLlmBillingMultiplier) {
        this(pricingRepository, cloudLlmBillingMultiplier,
                DEFAULT_ANTHROPIC_CACHE_WRITE_MULTIPLIER, DEFAULT_ANTHROPIC_CACHE_READ_MULTIPLIER,
                DEFAULT_OPENAI_CACHED_MULTIPLIER, DEFAULT_GEMINI_CACHED_MULTIPLIER,
                DEFAULT_DEEPSEEK_CACHED_MULTIPLIER);
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
        ModelPricing pricing = getPricing(provider, model);

        BigDecimal inputCost = pricing.getInputRate()
                .multiply(billableInputTokens(provider, usage))
                .divide(THOUSAND, 6, RoundingMode.HALF_UP);

        BigDecimal outputCost = pricing.getOutputRate()
                .multiply(billableOutputTokens(provider, usage))
                .divide(THOUSAND, 6, RoundingMode.HALF_UP);

        BigDecimal providerCost = inputCost.add(outputCost).add(pricing.getFixedCost());
        return applyCloudLlmBillingMultiplier(provider, model, providerCost);
    }

    /**
     * Billable input tokens as a (possibly fractional) token count weighted by the
     * provider's cache discounts. See {@link LlmTokenBreakdown} for the per-family
     * field semantics. Unknown families ignore cache fields (legacy behavior).
     */
    private BigDecimal billableInputTokens(String provider, LlmTokenBreakdown usage) {
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
                return base
                        .add(write.multiply(anthropicCacheWriteMultiplier))
                        .add(read.multiply(anthropicCacheReadMultiplier));
            }
            case OPENAI -> {
                return discountCachedSubset(prompt,
                        BigDecimal.valueOf(Math.max(0, usage.cachedTokens())), openaiCachedMultiplier);
            }
            case DEEPSEEK -> {
                return discountCachedSubset(prompt,
                        BigDecimal.valueOf(Math.max(0, usage.cachedTokens())), deepseekCachedMultiplier);
            }
            case GOOGLE_API, GOOGLE_CLI -> {
                // Direct API reports cached content in cachedTokens; the gemini-cli
                // bridge maps it into cacheReadTokens. Either way it is a subset of
                // promptTokens - take the larger of the two (never both populated).
                int cached = Math.max(Math.max(0, usage.cachedTokens()), Math.max(0, usage.cacheReadTokens()));
                return discountCachedSubset(prompt, BigDecimal.valueOf(cached), geminiCachedMultiplier);
            }
            default -> {
                return prompt;
            }
        }
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

    /** {@code prompt - cached + cached*multiplier}, with {@code cached} clamped to prompt. */
    private static BigDecimal discountCachedSubset(BigDecimal prompt, BigDecimal cached, BigDecimal multiplier) {
        BigDecimal clamped = cached.min(prompt);
        return prompt.subtract(clamped).add(clamped.multiply(multiplier));
    }

    /**
     * Provider families for token-accounting semantics. Matching is on the billing
     * {@code provider} string stamped by the executors (bridge providers keep their
     * CLI name, e.g. {@code claude-code}, direct API providers their vendor name).
     */
    enum ProviderFamily {
        ANTHROPIC_API, ANTHROPIC_CLI, OPENAI, DEEPSEEK, GOOGLE_API, GOOGLE_CLI, OTHER;

        static ProviderFamily of(String provider) {
            String p = provider != null ? provider.toLowerCase() : "";
            return switch (p) {
                case "claude-code" -> ANTHROPIC_CLI;
                case "anthropic", "claude" -> ANTHROPIC_API;
                // OpenAI-compatible vendors (OpenAICompatibleProviderFactory registers them under
                // these literal instance names - the billing provider string). They return the
                // OpenAI usage shape incl. prompt_tokens_details.cached_tokens, so their cached
                // input MUST be billed at the OPENAI 0.5x cache rate. Without these cases they fell
                // to OTHER, whose billableInputTokens ignores cachedTokens → cached input was
                // over-billed at full input rate. ("openai-compatible" is a legacy/dead alias the
                // factory never emits; kept for back-compat.)
                case "openai", "codex", "azure-openai", "openai-compatible",
                     "xai", "openrouter", "zai", "perplexity", "cohere" -> OPENAI;
                case "deepseek" -> DEEPSEEK;
                case "google", "gemini" -> GOOGLE_API;
                case "gemini-cli" -> GOOGLE_CLI;
                default -> OTHER;
            };
        }
    }

    public BigDecimal applyCloudLlmBillingMultiplier(String provider, String model, BigDecimal amount) {
        BigDecimal safeAmount = amount != null ? amount : BigDecimal.ZERO;
        if (!usesCloudLlmBillingMultiplier(provider, model)) {
            return safeAmount;
        }
        return safeAmount.multiply(cloudLlmBillingMultiplier)
                .setScale(6, RoundingMode.HALF_UP);
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
        String key = provider + ":" + model;
        ModelPricing cached = pricingCache.get(key);
        if (cached != null) {
            return cached.getId() != null;
        }
        return pricingRepository.findCurrentPricing(provider, model).isPresent();
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
        return pricingCache.computeIfAbsent(key, k -> {
            Optional<ModelPricing> pricing = pricingRepository.findCurrentPricing(provider, model);
            if (pricing.isEmpty()) {
                log.warn("No pricing found for {}/{}. Using default rates.", provider, model);
                ModelPricing defaultPricing = new ModelPricing();
                defaultPricing.setProvider(provider);
                defaultPricing.setModel(model);
                defaultPricing.setInputRate(DEFAULT_INPUT_RATE);
                defaultPricing.setOutputRate(DEFAULT_OUTPUT_RATE);
                defaultPricing.setFixedCost(BigDecimal.ZERO);
                return defaultPricing;
            }
            return pricing.get();
        });
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
        Optional<ModelPricing> existing = pricingRepository.findCurrentPricing(provider, model);
        if (existing.isPresent()) {
            ModelPricing pricing = existing.get();
            pricing.setInputRate(inputRate);
            pricing.setOutputRate(outputRate);
            if (providerKind != null && !providerKind.isBlank()) {
                pricing.setProviderKind(providerKind);
            }
            pricingRepository.save(pricing);
            log.info("Updated pricing for {}/{}: input={}, output={}, kind={}",
                    provider, model, inputRate, outputRate, pricing.getProviderKind());
        } else {
            ModelPricing pricing = new ModelPricing();
            pricing.setProvider(provider);
            pricing.setModel(model);
            pricing.setInputRate(inputRate);
            pricing.setOutputRate(outputRate);
            pricing.setFixedCost(BigDecimal.ZERO);
            pricing.setEffectiveFrom(LocalDate.now());
            pricing.setIsActive(true);
            if (providerKind != null && !providerKind.isBlank()) {
                pricing.setProviderKind(providerKind);
            }
            pricingRepository.save(pricing);
            log.info("Created pricing for {}/{}: input={}, output={}, kind={}",
                    provider, model, inputRate, outputRate, pricing.getProviderKind());
        }
        refreshCache();
    }

    public void refreshCache() {
        pricingCache.clear();
    }

    public List<ModelPricing> getAllActivePricing() {
        return pricingRepository.findByIsActiveTrue();
    }
}
