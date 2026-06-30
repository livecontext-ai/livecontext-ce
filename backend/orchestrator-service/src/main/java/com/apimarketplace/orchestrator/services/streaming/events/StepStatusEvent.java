package com.apimarketplace.orchestrator.services.streaming.events;

import java.util.Map;

/**
 * Evénement émis à chaque changement d'état d'un step ou d'une itération
 * (inclus les placeholders initiaux et les agrégations de loop).
 */
public record StepStatusEvent(
    String runId,
    String normalizedStepId,
    Map<String, Object> payload,
    StepLifecycle lifecycle,
    long timestamp
) implements WorkflowEvent {

    public StepStatusEvent {
        if (runId == null) {
            throw new IllegalArgumentException("runId is required");
        }
        if (normalizedStepId == null) {
            throw new IllegalArgumentException("normalizedStepId is required");
        }
    }
}
