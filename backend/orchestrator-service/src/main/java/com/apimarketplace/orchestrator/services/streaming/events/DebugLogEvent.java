package com.apimarketplace.orchestrator.services.streaming.events;

/**
 * Message texte libre utile pour diagnostiquer un run.
 */
public record DebugLogEvent(
    String runId,
    String level,
    String message,
    long timestamp
) implements WorkflowEvent {

    public DebugLogEvent {
        if (runId == null) {
            throw new IllegalArgumentException("runId is required");
        }
        if (message == null) {
            throw new IllegalArgumentException("message is required");
        }
    }
}
