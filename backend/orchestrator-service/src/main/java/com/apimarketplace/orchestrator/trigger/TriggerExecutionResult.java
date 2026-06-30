package com.apimarketplace.orchestrator.trigger;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.Set;

/**
 * Result of executing a reusable trigger (webhook, manual, or chat).
 *
 * This record captures:
 * - Success/failure status
 * - The ready steps that can be executed next (for step-by-step mode)
 * - The current epoch after execution
 * - Any error message if execution failed
 *
 * @param runId The workflow run ID
 * @param triggerId The trigger node ID (e.g., "trigger:my_webhook")
 * @param triggerType The type of trigger executed
 * @param success Whether the trigger executed successfully
 * @param message Human-readable status message
 * @param readySteps Set of step IDs ready for execution (for step-by-step mode)
 * @param epoch Current epoch number after execution
 */
public record TriggerExecutionResult(
    String runId,
    String triggerId,
    TriggerType triggerType,
    boolean success,
    String message,
    Set<String> readySteps,
    int epoch
) {

    /**
     * Create a success result.
     *
     * @param runId The run ID
     * @param triggerId The trigger ID
     * @param type The trigger type
     * @param readySteps Steps ready for execution
     * @param epoch Current epoch
     * @return A successful execution result
     */
    public static TriggerExecutionResult success(String runId, String triggerId,
            TriggerType type, Set<String> readySteps, int epoch) {
        return new TriggerExecutionResult(
            runId,
            triggerId,
            type,
            true,
            "Trigger executed successfully",
            readySteps != null ? readySteps : Set.of(),
            epoch
        );
    }

    /**
     * Create a success result with a custom message.
     *
     * @param runId The run ID
     * @param triggerId The trigger ID
     * @param type The trigger type
     * @param message Custom success message
     * @param readySteps Steps ready for execution
     * @param epoch Current epoch
     * @return A successful execution result
     */
    public static TriggerExecutionResult success(String runId, String triggerId,
            TriggerType type, String message, Set<String> readySteps, int epoch) {
        return new TriggerExecutionResult(
            runId,
            triggerId,
            type,
            true,
            message,
            readySteps != null ? readySteps : Set.of(),
            epoch
        );
    }

    /**
     * Create an "accepted" result for fire-and-forget dispatch - the trigger has
     * been handed off to the worker pool but the epoch has not started yet, so
     * {@code epoch} is reported as -1 and {@code readySteps} is empty. The HTTP
     * controller returns this to the frontend; SSE streams the actual epoch
     * number and node progression.
     */
    public static TriggerExecutionResult accepted(String runId, String triggerId, TriggerType type) {
        return new TriggerExecutionResult(
            runId,
            triggerId,
            type,
            true,
            "Trigger accepted, executing asynchronously",
            Set.of(),
            -1
        );
    }

    /**
     * Create a failure result.
     *
     * @param runId The run ID
     * @param triggerId The trigger ID
     * @param type The trigger type
     * @param error Error message describing what went wrong
     * @return A failed execution result
     */
    public static TriggerExecutionResult failure(String runId, String triggerId,
            TriggerType type, String error) {
        return failure(runId, triggerId, type, error, 0);
    }

    /**
     * Create a failure result for an epoch that was already opened.
     *
     * @param runId The run ID
     * @param triggerId The trigger ID
     * @param type The trigger type
     * @param error Error message describing what went wrong
     * @param epoch Current epoch
     * @return A failed execution result
     */
    public static TriggerExecutionResult failure(String runId, String triggerId,
            TriggerType type, String error, int epoch) {
        return new TriggerExecutionResult(
            runId,
            triggerId,
            type,
            false,
            error,
            Set.of(),
            epoch
        );
    }

    /**
     * Check if this result indicates the workflow is paused and waiting for user action.
     *
     * @return true if in step-by-step mode and has ready steps
     */
    @JsonIgnore
    public boolean isPausedWaitingForUser() {
        return success && readySteps != null && !readySteps.isEmpty();
    }

    /**
     * Check if the workflow cycle completed and is ready for the next trigger.
     *
     * @return true if successful with no ready steps (auto mode completed)
     */
    @JsonIgnore
    public boolean isCycleCompleted() {
        return success && (readySteps == null || readySteps.isEmpty());
    }
}
