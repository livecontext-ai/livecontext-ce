package com.apimarketplace.trigger.domain;

/**
 * Lifecycle state of a trigger config row (schedule, webhook, chat, form,
 * webhook_token).
 *
 * <p>Replaces the legacy boolean {@code enabled}/{@code is_active} columns -
 * those columns remain in PR2 for backward compatibility (the application keeps them
 * in sync via {@code TriggerLifecycleManager}). They are scheduled for removal in
 * PR5 once all dispatch and admin paths read {@code state} exclusively.
 *
 * <p>State transitions:
 * <pre>
 *   ACTIVE              ←→ SUSPENDED_NO_RUN     (workflow pinned but no run available)
 *   ACTIVE              ←→ SUSPENDED_UNPINNED   (workflow currently has no pin)
 *   ACTIVE/SUSPENDED_*  →  ARCHIVED             (admin / TTL expired / max_executions
 *                                                 reached / user-disabled / workflow deleted)
 * </pre>
 *
 * <p>Round-7 contract (see the project docs): the dispatch
 * layer NEVER mutates state. Only {@code TriggerLifecycleManager}, the admin API,
 * the reaper, the pin transaction, and TTL/max_executions can transition state.
 *
 * <p>Database storage: {@code VARCHAR(20)} with a Postgres CHECK constraint enforcing
 * the enum values (see {@code V137__unified_trigger_state_model.sql}).
 */
public enum TriggerState {
    /**
     * Trigger is healthy and dispatchable. The dispatch layer fires it whenever its
     * cron / webhook URL / form submission / chat message arrives.
     */
    ACTIVE,

    /**
     * Workflow is pinned but no production run is available at the pinned version
     * (race condition between pin and run creation, or user manually deleted the run).
     * Recovery: the next pin event or run creation will arm the trigger back to ACTIVE.
     */
    SUSPENDED_NO_RUN,

    /**
     * Workflow is currently unpinned. Production triggers cannot fire without a pin.
     * Recovery: pinning the workflow re-arms the trigger.
     */
    SUSPENDED_UNPINNED,

    /**
     * Trigger is permanently disabled. Reasons include: user-toggled off, TTL expired,
     * max_executions reached, workflow deleted, plan no longer references the trigger.
     * Manual recovery only (admin {@code /rearm} endpoint).
     */
    ARCHIVED;

    /**
     * Convenience predicate for the dispatch hot path: only ACTIVE triggers should
     * be considered for firing.
     */
    public boolean isDispatchable() {
        return this == ACTIVE;
    }
}
