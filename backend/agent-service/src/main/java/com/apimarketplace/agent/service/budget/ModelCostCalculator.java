package com.apimarketplace.agent.service.budget;

import com.apimarketplace.agent.domain.TokenUsageConventions;
import com.apimarketplace.common.credit.LlmCacheTokens;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Computes the monetary cost of an LLM call from token counts and provider rates.
 *
 * <p>Mirrors the formula used by {@code ModelPricingService} in auth-service so that
 * agent-service can perform local pre-iteration estimates without an HTTP round trip.
 * Rates are provided at construction time (typically loaded once per execution from
 * a pricing snapshot or AgentEntity config).</p>
 *
 * <p>Formula:
 * <pre>
 *   inputCost  = inputRate  * promptTokens     / 1000
 *   outputCost = outputRate * completionTokens / 1000
 *   total      = inputCost + outputCost + fixedCost
 * </pre>
 *
 * <p><strong>Unit convention (see V80 migration):</strong> {@code inputRate} /
 * {@code outputRate} are USD per 1M tokens (the provider list price). The formula
 * divides by 1 000, which turns USD per 1M into <em>credits</em> where
 * {@code 1 credit = $0.001}. A claude-3-opus-class rate of {@code 15 / 75} therefore
 * represents 15 credits per 1k input tokens / 75 credits per 1k output tokens -
 * equivalently 15 000 / 75 000 credits per 1M (= $15 / $75 list price per 1M).</p>
 *
 * <p>{@link #contextWindow} / {@link #maxOutputTokens} (V162) drive the
 * {@link #worstCaseSingleIter()} upper bound used by budget guards to close the
 * step-function projection bug. Both may be {@code null} for legacy/unseeded models.</p>
 *
 * <p>{@link #cacheReadRate} / {@link #cacheWriteRate} price one cached token the way the
 * ledger does ({@code ModelPricingService.cacheRates}, published in the pricing snapshot).
 * {@link #computeCost(String, long, long, LlmCacheTokens)} is the cache-aware cost the
 * guards use; before 2026-09-30 they priced every prompt token at the input rate, and a
 * Claude Code turn (mostly cache reads) was projected at about five times its debit and
 * killed early. When the snapshot publishes NO cache rate (an auth-service older than the
 * change) the cache-aware methods fall back to the exact pre-change formula rather than
 * guessing a price, so a mixed-version rollout never moves a guard.</p>
 */
public final class ModelCostCalculator {

    private static final BigDecimal THOUSAND = new BigDecimal("1000");

    private final BigDecimal inputRate;
    private final BigDecimal outputRate;
    private final BigDecimal fixedCost;
    private final Integer contextWindow;
    private final Integer maxOutputTokens;
    private final BigDecimal cacheReadRate;
    private final BigDecimal cacheWriteRate;
    /** Whether the snapshot published at least one cache rate for this model. */
    private final boolean cacheRatesKnown;

    public ModelCostCalculator(BigDecimal inputRate, BigDecimal outputRate, BigDecimal fixedCost,
                                Integer contextWindow, Integer maxOutputTokens,
                                BigDecimal cacheReadRate, BigDecimal cacheWriteRate) {
        this.inputRate = inputRate != null ? inputRate : BigDecimal.ZERO;
        this.outputRate = outputRate != null ? outputRate : BigDecimal.ZERO;
        this.fixedCost = fixedCost != null ? fixedCost : BigDecimal.ZERO;
        this.contextWindow = contextWindow;
        this.maxOutputTokens = maxOutputTokens;
        // Unknown (or non-positive, which would make cached input free) = the input rate.
        boolean readKnown = cacheReadRate != null && cacheReadRate.signum() > 0;
        boolean writeKnown = cacheWriteRate != null && cacheWriteRate.signum() > 0;
        this.cacheReadRate = readKnown ? cacheReadRate : this.inputRate;
        this.cacheWriteRate = writeKnown ? cacheWriteRate : this.inputRate;
        this.cacheRatesKnown = readKnown || writeKnown;
    }

    /** Constructor for callers without cache rates: the cache-aware methods keep the pre-change formula. */
    public ModelCostCalculator(BigDecimal inputRate, BigDecimal outputRate, BigDecimal fixedCost,
                                Integer contextWindow, Integer maxOutputTokens) {
        this(inputRate, outputRate, fixedCost, contextWindow, maxOutputTokens, null, null);
    }

    /** Backward-compat constructor for callers that only know about rates. */
    public ModelCostCalculator(BigDecimal inputRate, BigDecimal outputRate, BigDecimal fixedCost) {
        this(inputRate, outputRate, fixedCost, null, null);
    }

    /**
     * Cost of a single call given its prompt and completion token counts.
     */
    public BigDecimal computeCost(long promptTokens, long completionTokens) {
        BigDecimal inputCost = inputRate
            .multiply(BigDecimal.valueOf(promptTokens))
            .divide(THOUSAND, 6, RoundingMode.HALF_UP);
        BigDecimal outputCost = outputRate
            .multiply(BigDecimal.valueOf(completionTokens))
            .divide(THOUSAND, 6, RoundingMode.HALF_UP);
        return inputCost.add(outputCost).add(fixedCost);
    }

    /**
     * Cost of the tokens a {@code provider} reported, each input class at its own price:
     * the counters are split by {@link TokenUsageConventions#inputBreakdown} (the one place
     * that knows whether a provider's prompt total contains its cache), then plain input is
     * priced at {@link #inputRate}, cache writes at {@link #cacheWriteRate} and cache reads
     * at {@link #cacheReadRate}. With no cache counters, or no published cache rate, this is
     * exactly {@link #computeCost(long, long)}.
     */
    public BigDecimal computeCost(String provider, long promptTokens, long completionTokens,
                                  LlmCacheTokens cache) {
        if (!cacheRatesKnown) {
            return computeCost(promptTokens, completionTokens);
        }
        TokenUsageConventions.Breakdown input = inputBreakdown(provider, promptTokens, cache);
        return price(input.plainInput(), input.cacheWrite(), input.cacheRead(), cacheReadRate, completionTokens);
    }

    /**
     * What the same call costs if it misses the cache: every token it read from the cache is
     * written again, at {@link #cacheWriteRate} (1.25x input on Anthropic; the input rate for
     * providers billed on a cached subset). A cache read is ~0.1x input, so a guard that
     * projected the next call only from the last call's cache mix would let a single miss
     * (a 5-minute cache expiry during a long tool call) overdraw the balance by about ten
     * times its projection. With no published cache rate this is the pre-change formula.
     */
    public BigDecimal computeCacheMissCost(String provider, long promptTokens, long completionTokens,
                                           LlmCacheTokens cache) {
        if (!cacheRatesKnown) {
            return computeCost(promptTokens, completionTokens);
        }
        TokenUsageConventions.Breakdown input = inputBreakdown(provider, promptTokens, cache);
        return price(input.plainInput(), input.cacheWrite(), input.cacheRead(), cacheWriteRate, completionTokens);
    }

    /**
     * What a guard reserves for the next call missing the cache: {@link #computeCacheMissCost}
     * for a provider whose prompt total carries its cached tokens, zero for the direct
     * Anthropic API.
     *
     * <p>The split keeps the reservation where the guards always had one. Before 2026-09-30
     * every guard priced the prompt total at the input rate and projected {@code 2 x} the
     * last call: for Claude Code and the subset reporters that total contains the cache, so
     * the projection already covered a miss (and the cache-aware branches alone would not).
     * The Anthropic API's prompt total is the fresh input only, so the guards never reserved
     * anything for its cache; reserving a full miss there now would stop existing runs much
     * earlier (about 190 credits of headroom per 58k-token Sonnet context) for a risk the
     * accepted one-iteration overshoot already covers. Its projection keeps the other
     * branches, which now price the cache it spends.
     */
    public BigDecimal cacheMissReserve(String provider, long promptTokens, long completionTokens,
                                       LlmCacheTokens cache) {
        if (!TokenUsageConventions.promptTotalCarriesCachedTokens(provider)) {
            return BigDecimal.ZERO;
        }
        return computeCacheMissCost(provider, promptTokens, completionTokens, cache);
    }

    private static TokenUsageConventions.Breakdown inputBreakdown(String provider, long promptTokens,
                                                                  LlmCacheTokens cache) {
        LlmCacheTokens c = cache != null ? cache : NO_CACHE;
        return TokenUsageConventions.inputBreakdown(provider, promptTokens,
            orZero(c.cacheCreationTokens()), orZero(c.cacheReadTokens()), orZero(c.cachedTokens()));
    }

    /** plain x input + writes x write rate + reads x {@code readRate}, then output and fixed cost. */
    private BigDecimal price(long plainInput, long cacheWrite, long cacheRead, BigDecimal readRate,
                             long completionTokens) {
        BigDecimal inputCost = inputRate.multiply(BigDecimal.valueOf(plainInput))
            .add(cacheWriteRate.multiply(BigDecimal.valueOf(cacheWrite)))
            .add(readRate.multiply(BigDecimal.valueOf(cacheRead)))
            .divide(THOUSAND, 6, RoundingMode.HALF_UP);
        BigDecimal outputCost = outputRate
            .multiply(BigDecimal.valueOf(completionTokens))
            .divide(THOUSAND, 6, RoundingMode.HALF_UP);
        return inputCost.add(outputCost).add(fixedCost);
    }

    private static final LlmCacheTokens NO_CACHE = new LlmCacheTokens(0, 0, 0, 0);

    private static long orZero(Integer value) {
        return value != null ? value : 0L;
    }

    public BigDecimal inputRate() { return inputRate; }
    public BigDecimal cacheReadRate() { return cacheReadRate; }
    public BigDecimal cacheWriteRate() { return cacheWriteRate; }
    public boolean cacheRatesKnown() { return cacheRatesKnown; }
    public BigDecimal outputRate() { return outputRate; }
    public BigDecimal fixedCost() { return fixedCost; }
    public Integer contextWindow() { return contextWindow; }
    public Integer maxOutputTokens() { return maxOutputTokens; }

    /**
     * Absolute upper bound on the cost of a single iteration: cost when prompt fills
     * the context window and the model emits its full max output. Used by guards as
     * {@code consumed + worstCaseSingleIter() > balance} to close the step-function
     * projection bug (moving averages dilute sudden context bursts; this bound is
     * invariant to growth pattern).
     *
     * <p>Returns {@code null} when either {@link #contextWindow} or
     * {@link #maxOutputTokens} is unknown - caller policy determines what null means
     * (legacy guards: fall back to growth projection only; flag-on guards: fail-closed
     * via {@code BUDGET_GUARD_REQUIRE_CTX_WINDOW}).</p>
     */
    public BigDecimal worstCaseSingleIter() {
        if (contextWindow == null || maxOutputTokens == null) return null;
        // Defensive: the prompt cannot exceed (contextWindow - maxOutputTokens),
        // but providers do not enforce this strictly and the worst case is what we
        // want to bound. Use full contextWindow for prompt to stay conservative.
        return computeCost(contextWindow.longValue(), maxOutputTokens.longValue());
    }

    /** Returns {@code true} when all three rates are zero (no cost estimation possible). */
    public boolean isZero() {
        return inputRate.signum() == 0 && outputRate.signum() == 0 && fixedCost.signum() == 0;
    }

    /** Zero-cost calculator: returns {@link BigDecimal#ZERO} for any token count. */
    public static ModelCostCalculator zero() {
        return new ModelCostCalculator(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null, null);
    }
}
