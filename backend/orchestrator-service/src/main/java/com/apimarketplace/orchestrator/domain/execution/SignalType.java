package com.apimarketplace.orchestrator.domain.execution;

/**
 * Type of signal a node can wait on.
 */
public enum SignalType {
    /** Timer-based wait (e.g., WaitNode with duration > 3s) */
    WAIT_TIMER,

    /** User approval required before proceeding */
    USER_APPROVAL,

    /** Waiting for external webhook callback */
    WEBHOOK_WAIT,

    /** Interface node awaiting user action */
    INTERFACE_SIGNAL,

    /** Agent execution offloaded to async queue (scaling mode) */
    AGENT_EXECUTION,

    /**
     * Browser-agent session paused - the user has taken control of the
     * Chromium tab via CDP-over-WS. Always blocking: the workflow holds
     * until the user resumes (mirrors INTERFACE_SIGNAL with __continue).
     */
    BROWSER_USER_TAKEOVER
}
