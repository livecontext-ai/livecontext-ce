package com.apimarketplace.orchestrator.services.streaming.events;

import java.math.BigDecimal;

/**
 * Emitted when a trigger fire is refused because the run's accumulated cost has
 * reached the workflow budget: the in-flight epoch (if any) finishes, but no new
 * epoch starts. The run-mode UI surfaces this as an explanatory toast.
 *
 * <p>Figures in credits (1 credit = $0.001); the frontend renders dollars in CE
 * and raw credits in cloud.
 */
public record RunBudgetBlockedEvent(
    String runId,
    BigDecimal spentCredits,
    BigDecimal budgetCredits,
    long timestamp
) implements WorkflowEvent {

    public RunBudgetBlockedEvent {
        if (runId == null) {
            throw new IllegalArgumentException("runId is required");
        }
    }
}
