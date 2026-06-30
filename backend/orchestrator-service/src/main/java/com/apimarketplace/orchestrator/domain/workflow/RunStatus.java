package com.apimarketplace.orchestrator.domain.workflow;

/**
 * Status of a workflow run (overall execution lifecycle).
 * <p>
 * This is distinct from {@link com.apimarketplace.orchestrator.domain.execution.NodeStatus}
 * which tracks individual node states. RunStatus tracks the overall workflow run state.
 * </p>
 */
public enum RunStatus {
    PENDING("pending"),
    RUNNING("running"),
    PAUSED("paused"),
    COMPLETED("completed"),
    FAILED("failed"),
    PARTIAL_SUCCESS("partial_success"),
    SKIPPED("skipped"),
    CANCELLED("cancelled"),
    TIMEOUT("timeout"),
    WAITING_TRIGGER("waiting_trigger"),
    AWAITING_SIGNAL("awaiting_signal");

    private final String value;

    RunStatus(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    /**
     * Returns the lowercase wire value for streaming and API payloads.
     */
    public String toWireValue() {
        return value;
    }

    public boolean isTerminal() {
        return this == COMPLETED || this == SKIPPED || this == FAILED || this == PARTIAL_SUCCESS || this == CANCELLED || this == TIMEOUT;
    }

    public boolean isPaused() {
        return this == PAUSED;
    }

    public boolean canResume() {
        return this == PAUSED;
    }

    public boolean isWaitingForTrigger() {
        return this == WAITING_TRIGGER;
    }

    public boolean isSuccess() {
        return this == COMPLETED;
    }

    public boolean isFailure() {
        return this == FAILED || this == CANCELLED || this == TIMEOUT;
    }

    /**
     * Parse from string value (case-insensitive, with legacy aliases).
     */
    public static RunStatus fromString(String value) {
        if (value == null || value.isBlank()) return PENDING;

        String normalized = value.toLowerCase().trim();
        return switch (normalized) {
            case "pending" -> PENDING;
            case "running" -> RUNNING;
            case "paused", "pausing" -> PAUSED;
            case "completed" -> COMPLETED;
            case "failed" -> FAILED;
            case "partial_success" -> PARTIAL_SUCCESS;
            case "cancelled" -> CANCELLED;
            case "timeout" -> TIMEOUT;
            case "waiting_trigger" -> WAITING_TRIGGER;
            case "awaiting_signal", "awaiting" -> AWAITING_SIGNAL;
            // Legacy aliases
            case "resuming" -> RUNNING;
            case "ready" -> PENDING;
            case "skipped" -> SKIPPED;
            default -> PENDING;
        };
    }
}
