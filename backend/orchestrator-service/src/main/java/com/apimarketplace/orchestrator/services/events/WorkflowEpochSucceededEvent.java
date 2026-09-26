package com.apimarketplace.orchestrator.services.events;

import java.util.UUID;

/**
 * Published by {@code ReusableTriggerService.resetForNextCycle} when an epoch of a
 * reusable-trigger run closes with NO failure. The success twin of
 * {@link WorkflowEpochFailedEvent}: it is what lets a workflow that was failing be
 * reported as recovered, since reusable-trigger runs never reach a terminal status.
 */
public record WorkflowEpochSucceededEvent(
        UUID runId,
        UUID workflowId,
        int epoch,
        Integer planVersion
) {
}
