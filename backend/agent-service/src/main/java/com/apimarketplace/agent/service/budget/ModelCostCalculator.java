package com.apimarketplace.agent.service.budget;

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
 */
public final class ModelCostCalculator {

    private static final BigDecimal THOUSAND = new BigDecimal("1000");

    private final BigDecimal inputRate;
    private final BigDecimal outputRate;
    private final BigDecimal fixedCost;
    private final Integer contextWindow;
    private final Integer maxOutputTokens;

    public ModelCostCalculator(BigDecimal inputRate, BigDecimal outputRate, BigDecimal fixedCost,
                                Integer contextWindow, Integer maxOutputTokens) {
        this.inputRate = inputRate != null ? inputRate : BigDecimal.ZERO;
        this.outputRate = outputRate != null ? outputRate : BigDecimal.ZERO;
        this.fixedCost = fixedCost != null ? fixedCost : BigDecimal.ZERO;
        this.contextWindow = contextWindow;
        this.maxOutputTokens = maxOutputTokens;
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

    public BigDecimal inputRate() { return inputRate; }
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
