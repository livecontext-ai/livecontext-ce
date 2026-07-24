package com.apimarketplace.orchestrator.services.streaming.events;

import java.math.BigDecimal;

/**
 * Emitted whenever a run's accumulated cost changes (an agent execution
 * settled its credits). Carries the fresh figures so the run-mode RunInfo panel
 * can live-update "Cost of this run" without a REST poll.
 *
 * <ul>
 *   <li>{@code totalCostCredits} - total across ALL epochs of the run.</li>
 *   <li>{@code epochCostCredits} - cost of {@code epoch} alone.</li>
 *   <li>{@code budgetCredits} - the workflow budget, or {@code null} when none
 *       is set; lets the frontend paint the over-budget warning without a
 *       separate fetch.</li>
 * </ul>
 *
 * <p>All figures are in credits (1 credit = $0.001). The frontend renders them
 * as dollars in CE and as raw credits in cloud.
 */
public record RunCostEvent(
    String runId,
    int epoch,
    BigDecimal epochCostCredits,
    BigDecimal totalCostCredits,
    BigDecimal budgetCredits,
    long timestamp
) implements WorkflowEvent {

    public RunCostEvent {
        if (runId == null) {
            throw new IllegalArgumentException("runId is required");
        }
    }
}
