package com.apimarketplace.common.plan;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * Strips standalone trigger row UUIDs from cloned workflow plans.
 *
 * <p>Prior bug shape (F4 PUB-HIJACK): at publish time, an application clone
 * inherited {@code params.scheduleId} from the source plan verbatim. The first
 * sync of the clone called {@code StandaloneScheduleService.updateWorkflowReference}
 * which silently rewrote {@code workflow_id} on the standalone row, transferring
 * the source's row to the clone. Source workflow went silently inert.
 *
 * <p>This helper removes the 4 standalone-trigger back-reference keys from a
 * cloned plan's {@code triggers[].params} so each clone creates its own fresh
 * standalone row on first sync. Combined with the Postgres
 * {@code raise_immutable_workflow_id} trigger (V206) and the deletion of
 * {@code updateWorkflowReference} from the 4 standalone services, this closes
 * the rebind class of bugs.
 *
 * <p>See {@code the project docs} for the full design.
 */
public final class PlanStripUtils {

    private PlanStripUtils() {}

    /**
     * Mutate the given plan in place, removing standalone-trigger back-reference
     * UUIDs from {@code triggers[].params}.
     *
     * <p><b>Mutates the input.</b> Callers cloning a plan MUST pass a DEEP copy
     * (use {@link #deepCopyAndStrip}). Shallow {@code new HashMap<>(sourcePlan)}
     * shares the {@code triggers} list reference and would corrupt the source plan.
     *
     * <p>Fail-soft on malformed input (null plan, missing/non-list triggers,
     * non-map trigger entry, missing/non-map params): no-op, no throw.
     */
    @SuppressWarnings("unchecked")
    public static void stripStandaloneTriggerRefs(Map<String, Object> plan) {
        if (plan == null) return;
        Object triggersRaw = plan.get("triggers");
        if (!(triggersRaw instanceof List<?> triggers)) return;
        for (Object t : triggers) {
            if (!(t instanceof Map<?, ?> triggerMap)) continue;
            Object paramsRaw = triggerMap.get("params");
            if (!(paramsRaw instanceof Map<?, ?> params)) continue;
            Map<String, Object> p = (Map<String, Object>) params;
            p.remove("scheduleId");
            p.remove("webhookId");
            p.remove("chatEndpointId");
            p.remove("formEndpointId");
        }
    }

    /**
     * Deep-copy the source plan via {@link ObjectMapper#convertValue} round-trip
     * (proven safe pattern at {@code SnapshotCloneService.java:81}) and strip
     * standalone refs from the copy. Source plan is unmodified.
     *
     * <p>Preferred entry point for clone sites - guarantees the strip does not
     * leak into the source plan.
     *
     * @return a deep copy of the source plan with standalone refs stripped,
     *         or {@code null} if source is {@code null}
     */
    public static Map<String, Object> deepCopyAndStrip(Map<String, Object> sourcePlan,
                                                       ObjectMapper objectMapper) {
        if (sourcePlan == null) return null;
        Map<String, Object> copy = objectMapper.convertValue(sourcePlan,
            new TypeReference<Map<String, Object>>() {});
        stripStandaloneTriggerRefs(copy);
        return copy;
    }
}
