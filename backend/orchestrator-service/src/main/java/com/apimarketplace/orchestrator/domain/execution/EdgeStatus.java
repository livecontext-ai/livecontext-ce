package com.apimarketplace.orchestrator.domain.execution;

/**
 * Execution status of a workflow edge.
 * Note: Edges can NEVER be FAILED - only SKIPPED or COMPLETED.
 */
public enum EdgeStatus {

    /**
     * Edge has not been traversed yet.
     * Waiting for source node to complete.
     */
    PENDING("pending"),

    /**
     * Edge is currently being traversed.
     * Source node completed, target node is executing.
     */
    RUNNING("running"),

    /**
     * Edge was successfully traversed.
     * For conditional edges: this branch was selected.
     */
    COMPLETED("completed"),

    /**
     * Edge was skipped.
     * Either: branch not selected (condition false) or skip propagation from source.
     * Note: There is NO FAILED status for edges.
     */
    SKIPPED("skipped");

    private final String value;

    EdgeStatus(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    /**
     * Returns true if this status represents a terminal state.
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == SKIPPED;
    }

    /**
     * Parse from string value (case-insensitive).
     */
    public static EdgeStatus fromValue(String value) {
        if (value == null || value.isBlank()) {
            return PENDING;
        }
        String normalized = value.toLowerCase().trim();
        return switch (normalized) {
            case "pending" -> PENDING;
            case "running", "traversing" -> RUNNING;
            case "completed", "success" -> COMPLETED;
            case "skipped", "failed" -> SKIPPED; // failed edges become skipped
            default -> PENDING;
        };
    }

    /**
     * Convert NodeStatus to EdgeStatus.
     * Used when propagating status from node to outgoing edges.
     */
    public static EdgeStatus fromNodeStatus(NodeStatus nodeStatus) {
        if (nodeStatus == null) {
            return PENDING;
        }
        return switch (nodeStatus) {
            case PENDING, READY, WAITING_TRIGGER -> PENDING;
            case RUNNING, AWAITING_SIGNAL, COLLECTING -> RUNNING;
            case COMPLETED -> COMPLETED;
            case FAILED, SKIPPED -> SKIPPED; // Node failure/skip -> edge skipped
        };
    }
}
