package com.apimarketplace.agent.service.budget;

import com.apimarketplace.agent.domain.AgentStopReason;
import com.apimarketplace.agent.loop.GuardResult;
import com.apimarketplace.agent.loop.IterationContext;
import com.apimarketplace.agent.loop.PreIterationGuard;

import java.math.BigDecimal;

/**
 * Per-agent budget guard.
 *
 * <p>Replaces the legacy {@code CreditBudgetGuard} which was a glorified iteration counter.
 * This implementation performs a real cost projection: it calculates how much the agent
 * has already spent on this run plus an estimate of the next iteration based on the
 * running average of completed iterations, and compares the total against the configured
 * agent {@code creditBudget} (after subtracting any historical {@code creditsConsumed}
 * already resolved by {@link BudgetResolver}).</p>
 *
 * <p>V162: projection is now {@code max(growthProj, lastDeltaProj × safety, worstCaseSingleIter)}
 * - see {@link TenantBudgetGuard} for the rationale. Closes the step-function burst
 * vector that drove the -11305 incident on chat conversations.</p>
 *
 * <p>Guard scope is reported as {@code "agent"} on denial.</p>
 */
public final class AgentBudgetGuard implements PreIterationGuard {

    /** See {@link TenantBudgetGuard#LAST_DELTA_SAFETY_FACTOR}. */
    private static final BigDecimal LAST_DELTA_SAFETY_FACTOR = new BigDecimal("2.0");

    /** Total budget allowed for this agent (after lazy reset). May be {@code null} if disabled. */
    private final BigDecimal totalBudget;

    /** Already consumed amount before this run started (post-reset). */
    private final BigDecimal consumedBeforeRun;

    /** Credits currently reserved by in-flight sub-agent executions. */
    private final BigDecimal creditsReserved;

    /** Provider/model pricing used to compute the cost of each iteration. */
    private final ModelCostCalculator costCalculator;

    /**
     * @param totalBudget        the agent's {@code creditBudget} after lazy reset; {@code null} disables the guard
     * @param consumedBeforeRun  the agent's {@code creditsConsumed} after lazy reset
     * @param creditsReserved    credits reserved by in-flight sub-agents
     * @param costCalculator     pricing for the (provider, model) of this run
     */
    public AgentBudgetGuard(BigDecimal totalBudget, BigDecimal consumedBeforeRun,
                            BigDecimal creditsReserved, ModelCostCalculator costCalculator) {
        this.totalBudget = totalBudget;
        this.consumedBeforeRun = consumedBeforeRun != null ? consumedBeforeRun : BigDecimal.ZERO;
        this.creditsReserved = creditsReserved != null ? creditsReserved : BigDecimal.ZERO;
        this.costCalculator = costCalculator != null ? costCalculator : ModelCostCalculator.zero();
    }

    /**
     * Backward-compatible constructor without creditsReserved.
     */
    public AgentBudgetGuard(BigDecimal totalBudget, BigDecimal consumedBeforeRun, ModelCostCalculator costCalculator) {
        this(totalBudget, consumedBeforeRun, BigDecimal.ZERO, costCalculator);
    }

    @Override
    public GuardResult check(IterationContext ctx) {
        // Disabled guard.
        if (totalBudget == null || totalBudget.signum() <= 0) {
            return GuardResult.allow();
        }

        BigDecimal runCostSoFar = costCalculator.computeCost(
            ctx.promptTokensSoFar(), ctx.completionTokensSoFar());

        // V162: projection = max(growth, lastDelta × safety, worstCaseSingleIter).
        // See TenantBudgetGuard for rationale. **Skip projection until iter 3 (≥ 2
        // completed)** to match the JS twin (`mcp/bridge/lib/budgetGuards.js`
        // AgentBudgetGuard line ~67, `iters >= 2`).
        //
        // Why ≥ 2 and not ≥ 1: with only 1 completed iter, lastDelta == runCost ==
        // growth, so lastDelta×2 = 2 × runCost. Any iter consuming > budget/3 would
        // self-deny iter 2 - even when the user's next call is legitimately small.
        // Wait for 2 samples before trusting the projection. The tenant guard owns
        // the upfront ceiling via worstCase on iter 1; the agent guard's role is
        // "stop runaway loops" once a real growth pattern is observable.
        BigDecimal nextProjected = BigDecimal.ZERO;
        BigDecimal growthProj = BigDecimal.ZERO;
        BigDecimal lastDeltaProj = BigDecimal.ZERO;
        BigDecimal worstCase = null;
        if (ctx.iterationsCompleted() >= 2) {
            long avgPrompt = ctx.avgPromptTokensPerIteration();
            long avgCompletion = ctx.avgCompletionTokensPerIteration();
            growthProj = costCalculator.computeCost(avgPrompt, avgCompletion);
            lastDeltaProj = costCalculator.computeCost(
                ctx.lastIterationPromptTokens(), ctx.lastIterationCompletionTokens()
            ).multiply(LAST_DELTA_SAFETY_FACTOR);
            nextProjected = growthProj.max(lastDeltaProj);
            worstCase = costCalculator.worstCaseSingleIter();
            if (worstCase != null) {
                nextProjected = nextProjected.max(worstCase);
            }
        }

        BigDecimal totalProjected = consumedBeforeRun.add(creditsReserved).add(runCostSoFar).add(nextProjected);

        if (totalProjected.compareTo(totalBudget) >= 0) {
            String detail = String.format(
                "agent budget %s exceeded (consumed=%s + reserved=%s + run=%s + next=%s [growth=%s, lastDelta=%s, worstCase=%s] = %s)",
                totalBudget.toPlainString(),
                consumedBeforeRun.toPlainString(),
                creditsReserved.toPlainString(),
                runCostSoFar.toPlainString(),
                nextProjected.toPlainString(),
                growthProj.toPlainString(),
                lastDeltaProj.toPlainString(),
                worstCase != null ? worstCase.toPlainString() : "unknown",
                totalProjected.toPlainString());
            return GuardResult.deny(AgentStopReason.BUDGET_EXHAUSTED, "agent", detail);
        }
        return GuardResult.allow();
    }
}
