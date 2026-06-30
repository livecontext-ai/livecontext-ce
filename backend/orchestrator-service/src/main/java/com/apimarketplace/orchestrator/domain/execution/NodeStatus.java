package com.apimarketplace.orchestrator.domain.execution;

/**
 * Unified status enum for all workflow nodes.
 * <p>
 * This enum represents the current state of any node in a workflow execution.
 * The states follow a defined lifecycle:
 * </p>
 * <pre>
 *     PENDING -> READY -> RUNNING -> COMPLETED
 *                    \-> RUNNING -> FAILED
 *     PENDING -> SKIPPED (when predecessor branch is not taken)
 *     RUNNING -> AWAITING_SIGNAL -> COMPLETED | FAILED
 *     PENDING -> WAITING_TRIGGER -> RUNNING
 *     RUNNING -> COLLECTING -> COMPLETED | FAILED
 * </pre>
 * <p>
 * Terminal states (COMPLETED, SKIPPED, FAILED) cannot transition to other states.
 * </p>
 */
public enum NodeStatus {

    /**
     * Node is not yet reachable.
     * The node's predecessors have not completed yet.
     */
    PENDING,

    /**
     * Node is ready to be executed.
     * All prerequisites are met, waiting for user action (in step-by-step mode)
     * or automatic execution (in auto mode).
     */
    READY,

    /**
     * Node is currently executing.
     * Execution has started but not yet completed.
     */
    RUNNING,

    /**
     * Node finished successfully.
     * Execution completed without errors.
     */
    COMPLETED,

    /**
     * Node was skipped.
     * This happens when:
     * <ul>
     *   <li>A decision node selected a different branch</li>
     *   <li>A predecessor was skipped (skip propagation)</li>
     *   <li>A loop completed with zero iterations</li>
     * </ul>
     */
    SKIPPED,

    /**
     * Node execution failed.
     * An error occurred during execution.
     */
    FAILED,

    /**
     * Node is waiting for an external signal (timer, approval, webhook callback).
     * Transition: RUNNING -> AWAITING_SIGNAL -> COMPLETED | FAILED
     */
    AWAITING_SIGNAL,

    /**
     * Node is waiting for a trigger event to start execution.
     * Transition: PENDING -> WAITING_TRIGGER -> RUNNING
     */
    WAITING_TRIGGER,

    /**
     * Node is collecting items (aggregate/split node).
     * This is a transient state - not persisted.
     * Transition: RUNNING -> COLLECTING -> COMPLETED | FAILED
     */
    COLLECTING;

    /**
     * Returns the lowercase wire value for streaming and API payloads.
     * Uses snake_case for multi-word values.
     *
     * @return lowercase string representation (e.g., "completed", "awaiting_signal")
     */
    public String toWireValue() {
        return name().toLowerCase();
    }

    /**
     * Returns true if this is a terminal status (no further transitions possible).
     * Terminal statuses are: COMPLETED, SKIPPED, FAILED.
     *
     * @return true if terminal, false otherwise
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == SKIPPED || this == FAILED;
    }

    /**
     * Returns true if this status counts as "resolved" for merge node purposes.
     * A merge node can proceed when all its predecessors are resolved.
     * <p>
     * Resolved statuses are: COMPLETED, SKIPPED, FAILED.
     * </p>
     *
     * @return true if resolved, false otherwise
     */
    public boolean isResolved() {
        return isTerminal();
    }

    /**
     * Returns true if this is an active status (currently doing something).
     * Active statuses are: RUNNING, AWAITING_SIGNAL, COLLECTING.
     *
     * @return true if active, false otherwise
     */
    public boolean isActive() {
        return this == RUNNING || this == AWAITING_SIGNAL || this == COLLECTING;
    }

    /**
     * Returns true if this status indicates the node has been visited.
     * Visited means the node has transitioned past PENDING.
     *
     * @return true if visited, false otherwise
     */
    public boolean isVisited() {
        return this != PENDING;
    }

    /**
     * Returns true if this status allows execution to proceed.
     * Proceeding statuses are: COMPLETED, SKIPPED.
     * <p>
     * FAILED is not a proceeding status because it typically stops the workflow
     * (unless configured otherwise).
     * </p>
     *
     * @return true if proceeding, false otherwise
     */
    public boolean allowsProgress() {
        return this == COMPLETED || this == SKIPPED;
    }

    /**
     * Returns true if this status represents a successful completion.
     *
     * @return true if successful, false otherwise
     */
    public boolean isSuccessful() {
        return this == COMPLETED;
    }

    /**
     * Returns true if this status represents an error state.
     *
     * @return true if error, false otherwise
     */
    public boolean isError() {
        return this == FAILED;
    }

    /**
     * Returns true if this status should trigger skip propagation.
     */
    public boolean shouldPropagateSkip() {
        return this == FAILED || this == SKIPPED;
    }

    /**
     * Returns true if a transition to the target status is valid from this status.
     * <p>
     * Valid transitions:
     * <ul>
     *   <li>PENDING -> READY, SKIPPED, WAITING_TRIGGER</li>
     *   <li>READY -> RUNNING, SKIPPED, COMPLETED (COMPLETED for auto-evaluating nodes like Decision/Fork)</li>
     *   <li>RUNNING -> COMPLETED, FAILED, AWAITING_SIGNAL, COLLECTING</li>
     *   <li>AWAITING_SIGNAL -> COMPLETED, FAILED</li>
     *   <li>WAITING_TRIGGER -> RUNNING</li>
     *   <li>COLLECTING -> COMPLETED, FAILED</li>
     *   <li>Terminal states cannot transition</li>
     * </ul>
     *
     * @param target The target status
     * @return true if transition is valid, false otherwise
     */
    public boolean canTransitionTo(NodeStatus target) {
        if (target == null) {
            return false;
        }

        // Terminal states cannot transition
        if (this.isTerminal()) {
            return false;
        }

        return switch (this) {
            case PENDING -> target == READY || target == SKIPPED || target == WAITING_TRIGGER;
            case READY -> target == RUNNING || target == SKIPPED || target == COMPLETED;
            case RUNNING -> target == COMPLETED || target == FAILED || target == AWAITING_SIGNAL || target == COLLECTING;
            case AWAITING_SIGNAL -> target == COMPLETED || target == FAILED;
            case WAITING_TRIGGER -> target == RUNNING;
            case COLLECTING -> target == COMPLETED || target == FAILED;
            default -> false;
        };
    }

    /**
     * Converts a string to a NodeStatus.
     * The comparison is case-insensitive.
     *
     * @param value The string value
     * @return The corresponding NodeStatus
     * @throws IllegalArgumentException if the value is not a valid status
     */
    public static NodeStatus fromString(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Status value cannot be null or blank");
        }
        String normalized = value.toUpperCase().trim();
        return switch (normalized) {
            case "SUCCESS" -> COMPLETED;
            case "FAILURE", "ERROR" -> FAILED;
            default -> valueOf(normalized);
        };
    }

    /**
     * Tries to convert a string to a NodeStatus, returning a default on failure.
     *
     * @param value        The string value
     * @param defaultValue The default value if parsing fails
     * @return The corresponding NodeStatus, or defaultValue if parsing fails
     */
    public static NodeStatus fromStringOrDefault(String value, NodeStatus defaultValue) {
        try {
            return fromString(value);
        } catch (IllegalArgumentException e) {
            return defaultValue;
        }
    }
}
