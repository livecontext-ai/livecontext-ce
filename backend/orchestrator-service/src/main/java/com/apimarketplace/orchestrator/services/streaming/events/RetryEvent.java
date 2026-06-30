package com.apimarketplace.orchestrator.services.streaming.events;

import java.util.Map;

public record RetryEvent(
    String runId,
    String stepId,
    long itemId,
    int retryIndex,
    String cause,
    Map<String, Object> payload,
    long timestamp
) implements WorkflowEvent {

    public RetryEvent {
        if (runId == null) {
            throw new IllegalArgumentException("runId is required");
        }
        if (stepId == null) {
            throw new IllegalArgumentException("stepId is required");
        }
        if (retryIndex < 0) {
            throw new IllegalArgumentException("retryIndex must be >= 0");
        }
    }
}
