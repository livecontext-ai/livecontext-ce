package com.apimarketplace.orchestrator.services.streaming.events;

/**
 * Evénement unitaire décrivant une transition de counters pour un edge.
 * Les compteurs agrégés sont recalculés par le RunStateStore.
 */
public record EdgeStatusEvent(
    String runId,
    String edgeId,
    String from,
    String to,
    EdgeLifecycle lifecycle,
    Integer itemIndex,
    Integer iteration,
    long timestamp
) implements WorkflowEvent {

    public EdgeStatusEvent {
        if (runId == null) {
            throw new IllegalArgumentException("runId is required");
        }
        if (edgeId == null) {
            throw new IllegalArgumentException("edgeId is required");
        }
    }

    /**
     * Backward-compatible constructor without iteration.
     */
    public EdgeStatusEvent(
        String runId,
        String edgeId,
        String from,
        String to,
        EdgeLifecycle lifecycle,
        Integer itemIndex,
        long timestamp
    ) {
        this(runId, edgeId, from, to, lifecycle, itemIndex, null, timestamp);
    }
}
