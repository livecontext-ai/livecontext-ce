package com.apimarketplace.orchestrator.services.streaming.events;

import java.util.Map;

public record MergeEvent(
    String runId,
    String mergeId,
    MergeEventType type,
    Map<String, Object> payload,
    long timestamp
) implements WorkflowEvent {

    public MergeEvent {
        if (runId == null) {
            throw new IllegalArgumentException("runId is required");
        }
        if (mergeId == null) {
            throw new IllegalArgumentException("mergeId is required");
        }
        if (type == null) {
            throw new IllegalArgumentException("type is required");
        }
    }
}
