package com.apimarketplace.orchestrator.services.streaming.events;

import java.util.Map;

public record LoopEvent(
    String runId,
    String loopId,
    LoopEventType type,
    Map<String, Object> payload,
    long timestamp
) implements WorkflowEvent {

    public LoopEvent {
        if (runId == null) {
            throw new IllegalArgumentException("runId is required");
        }
        if (loopId == null) {
            throw new IllegalArgumentException("loopId is required");
        }
        if (type == null) {
            throw new IllegalArgumentException("type is required");
        }
    }
}
