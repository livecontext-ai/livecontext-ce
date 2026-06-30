package com.apimarketplace.agent.service.budget;

import java.math.BigDecimal;

/**
 * Snapshot of an agent's credit budget after lazy reset has been applied.
 *
 * <p>Returned by {@link BudgetResolver#resolveAndPersist} and consumed by
 * {@link AgentBudgetGuard}.</p>
 *
 * @param totalBudget        the agent's {@code creditBudget} (may be {@code null} when disabled)
 * @param consumedAfterReset the value of {@code creditsConsumed} after the lazy reset has been
 *                           applied - i.e. {@code 0} if the period rolled over, otherwise the
 *                           historical accumulator
 * @param creditsReserved    the amount currently reserved by in-flight sub-agent executions
 * @param wasReset           {@code true} if this resolution triggered a reset (period rolled over)
 */
public record BudgetState(
    BigDecimal totalBudget,
    BigDecimal consumedAfterReset,
    BigDecimal creditsReserved,
    boolean wasReset
) {
    /** Disabled state - no budget configured. */
    public static BudgetState disabled() {
        return new BudgetState(null, BigDecimal.ZERO, BigDecimal.ZERO, false);
    }

    public boolean isEnabled() {
        return totalBudget != null && totalBudget.signum() > 0;
    }
}
