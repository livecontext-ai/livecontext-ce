package com.apimarketplace.orchestrator.trigger;

import com.apimarketplace.orchestrator.domain.workflow.Trigger;

/**
 * Enum representing types of reusable triggers.
 *
 * Reusable triggers are triggers that can be fired multiple times on the same run.
 * Each execution increments the epoch counter and accumulates statistics.
 *
 * @see ReusableTriggerService
 * @see TriggerEpochManager
 */
public enum TriggerType {

    /**
     * Webhook trigger - fired via HTTP POST to /webhook/{token}
     */
    WEBHOOK("webhook"),

    /**
     * Manual trigger - fired via UI button click
     */
    MANUAL("manual"),

    /**
     * Chat trigger - fired via chat message
     */
    CHAT("chat"),

    /**
     * Datasource trigger - fired by row CRUD events in an internal datasource.
     * One event (created/updated/deleted) = one run. Server-side dispatch via
     * trigger-service's subscription registry - external, pin-gated like webhook.
     */
    DATASOURCE("datasource"),

    /**
     * Schedule trigger - fired automatically based on cron expression
     */
    SCHEDULE("schedule"),

    /**
     * Form trigger - fired via custom form submission
     */
    FORM("form"),

    /**
     * Workflow trigger - fired when another workflow completes.
     * The trigger's ID field contains the parent workflow ID that triggers this one.
     */
    WORKFLOW("workflow"),

    /**
     * Error trigger - fired when another workflow fails (FAILED or PARTIAL_SUCCESS).
     * The trigger's ID field contains the parent workflow ID whose failure triggers this one.
     * Anti-loop protection: error handler workflows that fail do NOT trigger other error handlers.
     */
    ERROR("error");

    private final String value;

    TriggerType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    /**
     * Check if this trigger type supports accumulation (multiple executions on same run).
     * Currently all reusable trigger types support accumulation.
     */
    public boolean supportsAccumulation() {
        return true;
    }

    /**
     * Parse a trigger type from its string value.
     *
     * @param type The string value (e.g., "webhook", "manual", "chat")
     * @return The corresponding TriggerType
     * @throws IllegalArgumentException if the type is unknown
     */
    public static TriggerType fromString(String type) {
        if (type == null) {
            throw new IllegalArgumentException("Trigger type cannot be null");
        }
        for (TriggerType t : values()) {
            if (t.value.equalsIgnoreCase(type)) {
                return t;
            }
        }
        throw new IllegalArgumentException("Unknown trigger type: " + type);
    }

    /**
     * Alias for fromString - parse a trigger type from its string value.
     *
     * @param value The string value (e.g., "webhook", "manual", "chat")
     * @return The corresponding TriggerType
     * @throws IllegalArgumentException if the value is unknown
     */
    public static TriggerType fromValue(String value) {
        return fromString(value);
    }

    /**
     * Check if a trigger type is "external" - dispatched server-side by workflowId lookup,
     * not by a frontend-provided runId.
     *
     * External triggers: webhook, schedule, workflow, error, datasource
     * Internal triggers: manual, chat, form (dispatched by frontend with explicit runId)
     *
     * When a new run is created for a workflow with external triggers,
     * stale runs (WAITING_TRIGGER, PAUSED) must be cancelled to prevent orphans.
     */
    public static boolean isExternalTrigger(String type) {
        if (type == null) return false;
        String lower = type.toLowerCase();
        return "webhook".equals(lower) || "schedule".equals(lower) || "workflow".equals(lower)
                || "error".equals(lower) || "datasource".equals(lower);
    }

    /**
     * Check if this trigger type instance is external.
     *
     * @return true if this trigger type is external (webhook, schedule, or workflow)
     * @see #isExternalTrigger(String)
     */
    public boolean isExternal() {
        return isExternalTrigger(this.value);
    }

    /**
     * Check if a string value represents a reusable trigger type.
     *
     * @param type The string value to check
     * @return true if the type is a known reusable trigger type
     */
    public static boolean isReusableTriggerType(String type) {
        if (type == null) {
            return false;
        }
        for (TriggerType t : values()) {
            if (t.value.equalsIgnoreCase(type)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check if a trigger is reusable (webhook, manual, chat, datasource, or schedule).
     * Reusable triggers can be fired multiple times on the same run.
     *
     * @param trigger The trigger to check
     * @return true if the trigger type supports re-execution
     */
    public static boolean isReusable(Trigger trigger) {
        if (trigger == null || trigger.type() == null) {
            return false;
        }
        return isReusableTriggerType(trigger.type());
    }

    /**
     * Get the TriggerType enum for a trigger, or null if unknown.
     *
     * @param trigger The trigger
     * @return The TriggerType, or null if the type is unknown
     */
    public static TriggerType fromTrigger(Trigger trigger) {
        if (trigger == null || trigger.type() == null) {
            return null;
        }
        try {
            return fromString(trigger.type());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
