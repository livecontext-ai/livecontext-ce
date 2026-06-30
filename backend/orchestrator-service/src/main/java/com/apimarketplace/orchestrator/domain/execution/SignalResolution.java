package com.apimarketplace.orchestrator.domain.execution;

/**
 * Resolution outcome of a signal wait.
 */
public enum SignalResolution {
    /** Timer expired normally (WAIT_TIMER) */
    COMPLETED,

    /** User approved (USER_APPROVAL) */
    APPROVED,

    /** User rejected (USER_APPROVAL) */
    REJECTED,

    /** Signal timed out without resolution (USER_APPROVAL, WEBHOOK_WAIT) */
    TIMEOUT,

    /** Signal was cancelled (e.g., DAG reset, workflow cancellation) */
    CANCELLED,

    /** Interface action fired (does NOT complete the interface) */
    ACTION_FIRED,

    /** Interface __continue action: user explicitly continues the workflow past the interface */
    CONTINUE,

    /** Agent execution completed successfully (async queue mode) */
    AGENT_COMPLETED,

    /** Agent execution failed (async queue mode) */
    AGENT_FAILED
}
