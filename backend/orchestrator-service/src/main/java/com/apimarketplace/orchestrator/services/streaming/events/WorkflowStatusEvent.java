package com.apimarketplace.orchestrator.services.streaming.events;

import java.util.Map;

/**
 * Capture la dernière photographie du statut global du workflow.
 */
public record WorkflowStatusEvent(
    String runId,
    String status,
    String message,
    Map<String, Object> payload,
    long timestamp,
    boolean terminal
) implements WorkflowEvent {

    public WorkflowStatusEvent {
        if (runId == null) {
            throw new IllegalArgumentException("runId is required");
        }
        if (status == null) {
            throw new IllegalArgumentException("status is required");
        }
    }
}
