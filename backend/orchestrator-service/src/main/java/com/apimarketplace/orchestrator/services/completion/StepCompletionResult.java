package com.apimarketplace.orchestrator.services.completion;

import java.util.Map;

/**
 * Result of a step completion operation.
 *
 * @param persisted          Whether the step was actually persisted (false if duplicate)
 * @param statusCounts       The current status counts for this node after completion (from DB)
 * @param eventData          The streaming event data that was emitted
 * @param payloadLost        True when the step's output payload could not be stored and the
 *                           orchestrator rewrote a SUCCESS result to FAILED (tier 2 of the
 *                           payload-loss contract). Callers that drive traversal from their
 *                           own copy of the node result MUST honor this flag: treat the node
 *                           as FAILED (skip-cascade descendants) instead of traversing its
 *                           success path.
 * @param payloadLostMessage The failure message naming the loss cause (null unless payloadLost)
 */
public record StepCompletionResult(
    boolean persisted,
    Map<String, Object> statusCounts,
    Map<String, Object> eventData,
    boolean payloadLost,
    String payloadLostMessage
) {
    /**
     * Create a result for a persisted step.
     */
    public static StepCompletionResult persisted(
            Map<String, Object> statusCounts,
            Map<String, Object> eventData) {
        return new StepCompletionResult(true, statusCounts, eventData, false, null);
    }

    /**
     * Create a result for a persisted step whose output payload was LOST -
     * the row landed as FAILED and the in-memory result was rewritten.
     */
    public static StepCompletionResult persistedPayloadLost(
            Map<String, Object> statusCounts,
            Map<String, Object> eventData,
            String payloadLostMessage) {
        return new StepCompletionResult(true, statusCounts, eventData, true, payloadLostMessage);
    }

    /**
     * Create a result for a duplicate (already persisted) step.
     */
    public static StepCompletionResult duplicate(
            Map<String, Object> statusCounts,
            Map<String, Object> eventData) {
        return new StepCompletionResult(false, statusCounts, eventData, false, null);
    }

    /**
     * Check if this was a duplicate entry.
     */
    public boolean isDuplicate() {
        return !persisted;
    }
}
