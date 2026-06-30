package com.apimarketplace.orchestrator.services.completion;

import java.util.Map;

/**
 * Result of a step completion operation.
 *
 * @param persisted    Whether the step was actually persisted (false if duplicate)
 * @param statusCounts The current status counts for this node after completion (from DB)
 * @param eventData    The streaming event data that was emitted
 */
public record StepCompletionResult(
    boolean persisted,
    Map<String, Object> statusCounts,
    Map<String, Object> eventData
) {
    /**
     * Create a result for a persisted step.
     */
    public static StepCompletionResult persisted(
            Map<String, Object> statusCounts,
            Map<String, Object> eventData) {
        return new StepCompletionResult(true, statusCounts, eventData);
    }

    /**
     * Create a result for a duplicate (already persisted) step.
     */
    public static StepCompletionResult duplicate(
            Map<String, Object> statusCounts,
            Map<String, Object> eventData) {
        return new StepCompletionResult(false, statusCounts, eventData);
    }

    /**
     * Check if this was a duplicate entry.
     */
    public boolean isDuplicate() {
        return !persisted;
    }
}
