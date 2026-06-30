package com.apimarketplace.orchestrator.domain.workflow;

/**
 * Mode d'execution du workflow.
 * Determines how the workflow progresses through steps.
 */
public enum ExecutionMode {
    /**
     * Automatic mode: all ready steps execute automatically.
     * This is the default behavior.
     */
    AUTOMATIC("automatic"),

    /**
     * Step-by-step mode: workflow pauses after each node execution.
     * User must manually trigger each step, including control nodes (decisions).
     */
    STEP_BY_STEP("step_by_step");

    private final String value;

    ExecutionMode(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public boolean isStepByStep() {
        return this == STEP_BY_STEP;
    }

    public boolean isAutomatic() {
        return this == AUTOMATIC;
    }

    public static ExecutionMode fromString(String value) {
        if (value == null) return AUTOMATIC;

        for (ExecutionMode mode : values()) {
            if (mode.value.equals(value) || mode.name().equalsIgnoreCase(value)) {
                return mode;
            }
        }
        return AUTOMATIC;
    }
}
