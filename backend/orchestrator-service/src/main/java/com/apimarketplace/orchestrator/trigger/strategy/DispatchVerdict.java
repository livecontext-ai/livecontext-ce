package com.apimarketplace.orchestrator.trigger.strategy;

/**
 * Round-7 PR4: unified outcome enum returned by every {@link TriggerExecutionStrategy}.
 *
 * <p>Every dispatcher (schedule, webhook, form, chat, workflow-chain) produces one of
 * these verdicts. Callers map verdicts to surface (HTTP code, log line, skip-tick)
 * per the table in {@code the project docs}.
 *
 * <p>Critical contract (round-7 §3.5): NONE of the verdicts mutate trigger state.
 * Only {@code TriggerLifecycleManager}, {@code WorkflowPinService}, the admin API,
 * the reaper, and TTL/max-execution checks may transition state. Dispatchers report
 * verdicts; lifecycle is owned elsewhere.
 */
public enum DispatchVerdict {
    /** Run found and dispatch executed. */
    FIRE,
    /** Workflow has no pinned version. */
    REFUSE_NOT_PINNED,
    /** Pinned-version run is in a terminal status (CANCELLED/TIMEOUT/FAILED). */
    REFUSE_RUN_TERMINAL,
    /** Pinned-version has no eligible run for this trigger's selection policy. */
    REFUSE_RUN_MISSING,
    /** Trigger config row is missing or the workflow plan no longer references it. */
    REFUSE_NO_TRIGGER,
    /** Tenant is out of credits / quota. */
    REFUSE_NO_CREDITS,
    /** Workflow itself was not found (deleted between dispatch and resolve). */
    REFUSE_WORKFLOW_MISSING
}
