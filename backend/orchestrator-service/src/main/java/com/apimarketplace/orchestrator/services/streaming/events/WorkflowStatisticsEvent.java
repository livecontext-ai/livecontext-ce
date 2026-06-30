package com.apimarketplace.orchestrator.services.streaming.events;

import java.util.Map;

/**
 * Diffusé pour chaque mise à jour des statistiques globales du workflow.
 */
public record WorkflowStatisticsEvent(
    String runId,
    Map<String, Object> payload,
    long timestamp
) implements WorkflowEvent {

    public WorkflowStatisticsEvent {
        if (runId == null) {
            throw new IllegalArgumentException("runId is required");
        }
    }
}
