package com.apimarketplace.conversation.streaming;

/**
 * Unified stream state enum.
 * Single source of truth for stream lifecycle states.
 */
public enum StreamState {
    CREATED,
    STREAMING,
    COMPLETED,
    STOPPED_BY_USER,
    AWAITING_APPROVAL,  // Paused waiting for user to approve/deny service access
    ERROR,
    INTERRUPTED;        // Producer died (pod drain/shutdown, heartbeat lost) - partial content was rescued

    public boolean isActive() {
        return this == CREATED || this == STREAMING;
    }

    /**
     * Checks if this stream can be reconnected to receive more content.
     * Only STREAMING state is reconnectable - CREATED means no content has been sent yet.
     * INTERRUPTED is NOT reconnectable: the producer is gone, no more content will arrive.
     */
    public boolean isReconnectable() {
        return this == STREAMING;
    }

    public boolean isTerminal() {
        return this == COMPLETED || this == STOPPED_BY_USER || this == AWAITING_APPROVAL
                || this == ERROR || this == INTERRUPTED;
    }

    /**
     * Checks if this state represents a pause waiting for user action.
     */
    public boolean isAwaitingUserAction() {
        return this == AWAITING_APPROVAL;
    }
}
