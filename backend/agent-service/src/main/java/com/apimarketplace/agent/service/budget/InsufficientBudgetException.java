package com.apimarketplace.agent.service.budget;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Thrown by {@link BudgetReservationService#tryReserveChain} when at least one ancestor in the
 * caller chain does not have enough free budget to cover the requested reservation.
 *
 * <p>The transaction wrapping {@code tryReserveChain} rolls back when this is thrown, so any
 * successful row updates applied to earlier ancestors are undone automatically - no manual
 * compensation needed.
 *
 * <p>The offending ancestor's id is carried so the caller
 * ({@code SubAgentExecutionHandler}) can surface a precise error message with scope
 * {@code "parent_reservation"} on {@code AgentStopReason.BUDGET_EXHAUSTED}.
 */
public class InsufficientBudgetException extends RuntimeException {

    private final UUID agentId;
    private final BigDecimal requested;

    public InsufficientBudgetException(UUID agentId, BigDecimal requested) {
        super("Insufficient free budget on ancestor " + agentId + " for reservation " + requested);
        this.agentId = agentId;
        this.requested = requested;
    }

    public UUID getAgentId() {
        return agentId;
    }

    public BigDecimal getRequested() {
        return requested;
    }
}
