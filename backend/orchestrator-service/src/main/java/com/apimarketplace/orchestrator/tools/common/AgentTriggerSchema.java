package com.apimarketplace.orchestrator.tools.common;

import com.apimarketplace.orchestrator.domain.workflow.Trigger;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.trigger.TriggerType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Agent-facing description of a workflow's trigger payload.
 *
 * <p>The LLM agent has no way to introspect a form-trigger's field list before
 * calling {@code application(action='execute')}; without this hint it has to
 * guess field names (the prod 2026-05-15 "FlyFinder" miss used
 * {@code origin/destination/departure_date} instead of the real SerpAPI Google
 * Flights names {@code departure_id/arrival_id/outbound_date}). This helper
 * extracts the minimal schema the agent needs to build a correct
 * {@code data_inputs} payload on the first try.
 *
 * <p>Only fireable triggers (manual / chat / form / webhook / schedule /
 * datasource) are considered - workflow / error triggers are system-only.
 */
public final class AgentTriggerSchema {

    /** Trigger types an agent can fire via {@code application.execute} or {@code workflow.execute}. */
    private static final Set<String> FIREABLE_TYPES = Set.of(
            "manual", "chat", "form", "webhook", "schedule", "datasource"
    );

    private AgentTriggerSchema() {}

    /**
     * Pick the trigger the agent would fire by default - the single fireable
     * one, or {@code null} when there are zero or multiple (the agent must
     * then pass an explicit {@code trigger_id}).
     */
    public static String defaultTriggerId(WorkflowPlan plan) {
        List<Trigger> fireable = fireableTriggers(plan);
        return fireable.size() == 1 ? fireable.get(0).getNormalizedKey() : null;
    }

    /**
     * The distinct fireable trigger types in this plan ({@code ["webhook", "schedule"]}, etc.).
     * Empty list = no agent-fireable trigger (workflow has only system triggers, or no triggers).
     */
    public static List<String> fireableTriggerTypes(WorkflowPlan plan) {
        return fireableTriggers(plan).stream()
                .map(Trigger::type)
                .filter(t -> t != null && !t.isBlank())
                .distinct()
                .toList();
    }

    /**
     * Per-trigger summary for multi-trigger apps - used by agent payloads when
     * {@link #defaultTriggerId(WorkflowPlan)} returns {@code null} (no single
     * default, agent must pick). Each entry is
     * {@code {trigger_id, type, label}}. Empty when zero fireable triggers.
     */
    public static List<Map<String, Object>> fireableTriggers(WorkflowPlan plan, boolean asSummary) {
        if (!asSummary) {
            // Convenience guard: never expose the raw Trigger record to callers
            throw new UnsupportedOperationException("Use defaultTriggerId or fireableTriggerTypes instead");
        }
        return fireableTriggers(plan).stream()
                .map(t -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("trigger_id", t.getNormalizedKey());
                    m.put("type", t.type());
                    if (t.label() != null && !t.label().isBlank()) m.put("label", t.label());
                    return m;
                })
                .toList();
    }

    /**
     * Agent-facing schema for a {@code data_inputs} payload on the default fireable trigger.
     * Returns {@code null} when no schema is meaningful (multiple fireable triggers, or
     * trigger type accepts arbitrary payload).
     *
     * <p>Shape for a form trigger:
     * <pre>{@code
     * {
     *   "trigger_type": "form",
     *   "fields": [
     *     {"name": "departure_id", "required": true,  "type": "text"},
     *     {"name": "children",      "required": false, "type": "number"}
     *   ]
     * }
     * }</pre>
     *
     * <p>Shape for a chat trigger: {@code {"trigger_type": "chat", "fields":
     * [{"name": "message", "required": true, "type": "text"}]}}.
     *
     * <p>Webhook / schedule / manual / datasource accept arbitrary or empty
     * payloads, so the response is {@code {"trigger_type": "<type>"}} with no
     * {@code fields} key - signal to the agent that {@code data_inputs} is
     * free-form (webhook) or unneeded (schedule / manual).
     */
    public static Map<String, Object> dataInputsSchema(WorkflowPlan plan) {
        List<Trigger> fireable = fireableTriggers(plan);
        if (fireable.size() != 1) return null;
        return dataInputsSchema(fireable.get(0));
    }

    /** Same as {@link #dataInputsSchema(WorkflowPlan)} but for a specific trigger. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> dataInputsSchema(Trigger trigger) {
        if (trigger == null || trigger.type() == null) return null;
        TriggerType type;
        try {
            type = TriggerType.fromString(trigger.type());
        } catch (IllegalArgumentException e) {
            return null;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("trigger_type", type.getValue());

        switch (type) {
            case FORM -> {
                Object fieldsRaw = trigger.params() != null ? trigger.params().get("fields") : null;
                if (!(fieldsRaw instanceof List<?> rawList)) return out;
                List<Map<String, Object>> fields = new ArrayList<>();
                for (Object raw : rawList) {
                    if (!(raw instanceof Map<?, ?> m)) continue;
                    Object name = m.get("name");
                    if (!(name instanceof String n) || n.isBlank()) continue;
                    Map<String, Object> field = new LinkedHashMap<>();
                    field.put("name", n);
                    field.put("required", Boolean.TRUE.equals(m.get("required"))
                            || "true".equalsIgnoreCase(String.valueOf(m.get("required"))));
                    Object ftype = m.get("type");
                    if (ftype instanceof String s && !s.isBlank()) {
                        field.put("type", s);
                        // Format hint: the agent has no way to know which date/time
                        // shape the form trigger expects, so embed the contract
                        // inline instead of forcing it to guess (audit gap from
                        // 2026-05-15 trace: agent passed "2027-03-10" which happened
                        // to work, but a future date field with type=datetime would
                        // silently fail without this hint).
                        switch (s.toLowerCase(Locale.ROOT)) {
                            case "date"     -> field.put("format", "YYYY-MM-DD (ISO 8601 date)");
                            case "datetime" -> field.put("format", "YYYY-MM-DDTHH:MM:SSZ (ISO 8601 datetime, UTC)");
                            case "time"     -> field.put("format", "HH:MM or HH:MM:SS");
                            default         -> { /* no format hint for text/number/select/etc */ }
                        }
                    }
                    // Select-type fields carry an `options` array - exposing them
                    // lets the agent pick a valid enum value on the first try.
                    Object options = m.get("options");
                    if (options instanceof List<?> optList && !optList.isEmpty()) {
                        field.put("options", optList);
                    }
                    fields.add(field);
                }
                if (!fields.isEmpty()) out.put("fields", fields);
            }
            case CHAT -> out.put("fields", List.of(Map.of(
                    "name", "message", "required", true, "type", "text"
            )));
            case WEBHOOK, MANUAL, SCHEDULE, DATASOURCE -> {
                // No fields key - agent knows payload is free-form / unused
            }
            default -> { /* unknown - leave only trigger_type */ }
        }
        return out;
    }

    private static List<Trigger> fireableTriggers(WorkflowPlan plan) {
        if (plan == null || plan.getTriggers() == null) return List.of();
        return plan.getTriggers().stream()
                .filter(t -> t.type() != null && FIREABLE_TYPES.contains(t.type().toLowerCase()))
                .toList();
    }
}
