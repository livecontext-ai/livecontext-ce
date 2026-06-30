package com.apimarketplace.agent.service.budget;

import com.apimarketplace.agent.domain.AgentStopReason;
import com.apimarketplace.agent.loop.GuardResult;
import com.apimarketplace.agent.loop.IterationContext;
import com.apimarketplace.agent.loop.PreIterationGuard;
import com.apimarketplace.common.credit.CreditConsumptionClient;

import java.math.BigDecimal;

/**
 * Tenant-level budget guard.
 *
 * <p>Wraps {@link CreditConsumptionClient} to enforce the tenant/subscription credit
 * balance before each iteration. Uses {@code fetchBalance()} (cached client-side) plus
 * a local cost projection to refuse iterations that would push the tenant into
 * negative balance.</p>
 *
 * <p>Tenant guard is intended to be the <em>first</em> guard in the chain because it
 * dominates: when the macro budget is gone, the agent budget is moot.</p>
 *
 * <p><strong>Defense-in-depth (V162 / -11305 incident fix):</strong></p>
 * <ul>
 *   <li><b>Layer A (this guard)</b> - early reject before reservation roundtrip.
 *       Uses {@code projectedNext = max(growthProj, lastDeltaProj × 2.0,
 *       worstCaseSingleIter)} as the projection. Growth captures steady ramp-up;
 *       worstCase caps the absolute per-iteration upper bound. Step-function
 *       bursts (cruise → 21× burst) that a pure-average projection misses are
 *       caught by the worstCase branch.</li>
 *   <li><b>Layer B (JS bridge twin)</b> - perimeter ceiling on the bridge side
 *       via {@code mcp/bridge/lib/budgetGuards.js}. Same formula as A; cross-lang
 *       parity verified by {@code shared/contracts/budget-guard-fixtures.json}.</li>
 * </ul>
 * <p>An atomic per-turn reservation (Layer C) was prototyped on conversation-service
 * but reverted: A+B alone close the incident on the bridge path, and a small
 * overshoot (≤ 1 iteration) is acceptable in exchange for a simpler call path.
 * The {@code tryReserveMarkup} infrastructure on auth-service remains in use by
 * the workflow run-init path, just not for chat turns.</p>
 *
 * <p>Reports scope {@code "tenant"} on denial.</p>
 */
public final class TenantBudgetGuard implements PreIterationGuard {

    private final CreditConsumptionClient creditClient;
    private final ModelCostCalculator costCalculator;
    /**
     * P1.3 / Phase 1C flag mirror - when true, deny iterations on models without
     * contextWindow / maxOutputTokens metadata rather than silently fall back to
     * growth-only projection (which would let unknown models bypass the worstCase
     * ceiling). Default false during the migration window so legacy snapshots
     * can't self-DoS the chat path. Same semantic as the JS twin's
     * {@code requireCtxWindow} option in {@code mcp/bridge/lib/budgetGuards.js}.
     */
    private final boolean requireCtxWindow;

    /** Cached balance fetched once per check; refreshed every N iterations. */
    private BigDecimal cachedBalance;
    private int iterationsSinceFetch = Integer.MAX_VALUE;

    /** How often to re-query the auth-service balance during a long run. */
    private static final int BALANCE_REFRESH_EVERY_N_ITERATIONS = 5;

    /** When cost calculator has zero rates, refresh more aggressively to compensate for unreliable estimates. */
    private static final int BALANCE_REFRESH_EVERY_N_ITERATIONS_ZERO_COST = 1;

    /**
     * Multiplier on the most recent iteration's delta when projecting the next.
     * Captures growth: if the last iter consumed 30k tokens and the trend is upward,
     * we project the next at 60k. Combined with {@code worstCaseSingleIter} via
     * {@code max(...)} so a sudden burst can't slip past either branch.
     * Empirically chosen 2.0 - see {@code budget-guard-fixtures.json} test cases.
     */
    private static final BigDecimal LAST_DELTA_SAFETY_FACTOR = new BigDecimal("2.0");

    public TenantBudgetGuard(CreditConsumptionClient creditClient, ModelCostCalculator costCalculator) {
        this(creditClient, costCalculator, false);
    }

    public TenantBudgetGuard(CreditConsumptionClient creditClient, ModelCostCalculator costCalculator,
                              boolean requireCtxWindow) {
        this.creditClient = creditClient;
        this.costCalculator = costCalculator != null ? costCalculator : ModelCostCalculator.zero();
        this.requireCtxWindow = requireCtxWindow;
    }

    @Override
    public GuardResult check(IterationContext ctx) {
        // Tenant guard is a no-op for system runs without a tenant.
        if (ctx.tenantId() == null || ctx.tenantId().isBlank() || creditClient == null) {
            return GuardResult.allow();
        }

        // Refresh balance lazily. When cost estimation is unreliable (zero rates),
        // refresh every iteration to avoid stale balance checks.
        int refreshInterval = costCalculator.isZero()
            ? BALANCE_REFRESH_EVERY_N_ITERATIONS_ZERO_COST
            : BALANCE_REFRESH_EVERY_N_ITERATIONS;
        if (cachedBalance == null || iterationsSinceFetch >= refreshInterval) {
            cachedBalance = creditClient.fetchBalance(ctx.tenantId());
            iterationsSinceFetch = 0;
        } else {
            iterationsSinceFetch++;
        }

        if (cachedBalance == null || cachedBalance.signum() <= 0) {
            return GuardResult.deny(
                AgentStopReason.BUDGET_EXHAUSTED, "tenant",
                "tenant balance is " + (cachedBalance == null ? "unknown" : cachedBalance.toPlainString()));
        }

        // Project current run cost so far + next iteration estimate; deny if it would
        // exceed the cached balance. Conservative: this is a snapshot, not authoritative
        // - the post-execution consume in AgentObservabilityService still does the final
        // reconciliation and may reject for races.
        BigDecimal runCostSoFar = costCalculator.computeCost(
            ctx.promptTokensSoFar(), ctx.completionTokensSoFar());

        // V162: short-circuit when consumed already meets or exceeds balance - symmetric
        // with the JS bridge guard's `if (consumed >= this.balance) return deny('exhausted')`.
        // Without this, a budget-exceeded run would still fall through to the projection
        // branch, masking the real failure mode in the deny message.
        if (runCostSoFar.compareTo(cachedBalance) >= 0) {
            return GuardResult.deny(AgentStopReason.BUDGET_EXHAUSTED, "tenant",
                "tenant balance exhausted (" + runCostSoFar.toPlainString() + " / " + cachedBalance.toPlainString() + ")");
        }

        // V162 fail-closed (P1.3): when requireCtxWindow flag is on AND the model has no
        // contextWindow/maxOutputTokens metadata, deny rather than silently fall through
        // to growth-only projection (which would let unknown models bypass the worstCase
        // ceiling). Mirror of JS TenantBudgetGuard's flag-on path.
        if (requireCtxWindow
                && (costCalculator.contextWindow() == null || costCalculator.maxOutputTokens() == null)) {
            return GuardResult.deny(AgentStopReason.BUDGET_EXHAUSTED, "tenant",
                "missing_ctx_window for " + ctx.provider() + "/" + ctx.model()
                + " - fail-closed under BUDGET_GUARD_REQUIRE_CTX_WINDOW");
        }

        // V162: projection = max(growth-based, last-delta-based, worstCaseSingleIter).
        // - growth-based: avg tokens × rates. Misses sudden bursts.
        // - last-delta-based: lastIteration tokens × safety_factor × rates. Captures
        //   monotonic growth ramp.
        // - worstCaseSingleIter: contextWindow × maxOutputTokens × rates. Captures
        //   step-function bursts (cruise → 21x burst). Invariant to growth pattern.
        // Take the max so any branch alone can trip the guard early.
        long avgPrompt = ctx.avgPromptTokensPerIteration();
        long avgCompletion = ctx.avgCompletionTokensPerIteration();
        long lastDeltaPrompt = ctx.lastIterationPromptTokens();
        long lastDeltaCompletion = ctx.lastIterationCompletionTokens();
        BigDecimal growthProj = costCalculator.computeCost(avgPrompt, avgCompletion);
        BigDecimal lastDeltaProj = costCalculator.computeCost(lastDeltaPrompt, lastDeltaCompletion)
            .multiply(LAST_DELTA_SAFETY_FACTOR);
        BigDecimal nextProjected = growthProj.max(lastDeltaProj);
        BigDecimal worstCase = costCalculator.worstCaseSingleIter();
        if (worstCase != null) {
            nextProjected = nextProjected.max(worstCase);
        }
        BigDecimal totalProjected = runCostSoFar.add(nextProjected);

        if (totalProjected.compareTo(cachedBalance) >= 0) {
            String detail = String.format(
                "tenant balance %s would be exceeded (run=%s + next=%s [growth=%s, lastDelta=%s, worstCase=%s] = %s)",
                cachedBalance.toPlainString(),
                runCostSoFar.toPlainString(),
                nextProjected.toPlainString(),
                growthProj.toPlainString(),
                lastDeltaProj.toPlainString(),
                worstCase != null ? worstCase.toPlainString() : "unknown",
                totalProjected.toPlainString());
            return GuardResult.deny(AgentStopReason.BUDGET_EXHAUSTED, "tenant", detail);
        }
        return GuardResult.allow();
    }
}
