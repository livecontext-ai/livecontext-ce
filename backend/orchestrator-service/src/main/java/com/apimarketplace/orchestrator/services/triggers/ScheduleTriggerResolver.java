package com.apimarketplace.orchestrator.services.triggers;

import com.apimarketplace.orchestrator.domain.workflow.Trigger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves schedule (cron) triggers when {@link com.apimarketplace.orchestrator.services.TriggerResolverService}
 * is asked to load trigger items - typically by
 * {@link com.apimarketplace.orchestrator.execution.v2.services.V2TriggerLoadingService#loadTriggerItemsIfNeeded}
 * during a UI page load on a scheduled run.
 *
 * <p><b>Why this handler exists.</b> Schedule triggers are FIRED by
 * {@link com.apimarketplace.orchestrator.schedule.ScheduleExecutorService}
 * directly (cron daemon → ReusableTriggerService) - that path stamps the
 * payload itself and never calls {@code TriggerResolverService.resolveTrigger}.
 * Pre-fix, however, the V2 trigger-loading path called {@code resolveTrigger}
 * for ANY trigger type, including schedule. With no handler registered, the
 * resolver threw {@code IllegalArgumentException("Unsupported trigger type:
 * schedule")} - an ERROR + stack trace logged on every UI page-load of a
 * scheduled run, confirmed in prod 2026-05-06 12:22 incident logs (29 hits in
 * the 8 min before OOM, polluting triage).
 *
 * <p>This handler returns a payload that signals "no per-item fan-out":
 * {@code count: 0, data: []}, plus the metadata describing the FIRE itself
 * (triggered_at, triggered_by="schedule", trigger params). The
 * {@link com.apimarketplace.orchestrator.execution.v2.services.V2TriggerLoadingService#extractTriggerItems}
 * extractor honours an explicit {@code count: 0} as a deterministic opt-out
 * from the wrap-the-whole-result fallback - without that contract, the
 * fallback would produce a single phantom item downstream and a {@code core:split}
 * adjacent to the schedule trigger would fan out once.
 *
 * <p>The fields {@code triggered_at}, {@code triggered_by} are snake_case to
 * match the V11 {@code seed_node_type_documentation.sql} seed and the
 * frontend schema (CLAUDE.md "Node Output Schema - 3-Way Alignment").
 */
@Slf4j
@Component
public class ScheduleTriggerResolver implements TriggerTypeHandler {

    @Override
    public boolean canHandle(String triggerType) {
        return "schedule".equalsIgnoreCase(triggerType);
    }

    @Override
    public Map<String, Object> resolve(Trigger trigger, String tenantId, Map<String, Object> resolvedInputs) {
        log.debug("Resolving schedule trigger: {} for tenant: {}", trigger.id(), tenantId);

        String triggeredAt = Instant.now().toString();

        // No-fan-out marker: data=[] and count=0. Schedule triggers do NOT have
        // per-item data - the metadata (triggered_at, triggered_by, params)
        // describes the FIRE itself, not iterable items. Returning a single-
        // element data array would make a downstream `core:split` fan out once
        // with a phantom item where the pre-fix path (which threw and was
        // caught) produced zero. Audit 2026-05-06 P1 #6 (Audit 2 #3).
        //
        // The fire path (ScheduleExecutorService → ReusableTriggerService) builds
        // its own payload and does NOT call resolveTrigger - this resolver only
        // runs when V2TriggerLoadingService.loadTriggerItemsIfNeeded is invoked
        // (UI page-load on a scheduled run, SBS preview). Both consumers handle
        // empty `data` correctly: the UI displays "no items" / "0 fan-out".
        Map<String, Object> payload = new HashMap<>();
        payload.put("triggerId", trigger.id());
        payload.put("type", "schedule");
        payload.put("status", "success");
        payload.put("source", "schedule");
        payload.put("triggered_at", triggeredAt);
        payload.put("triggered_by", "schedule");
        if (trigger.params() != null && !trigger.params().isEmpty()) {
            payload.putAll(trigger.params());
        }
        payload.put("data", List.of());
        payload.put("count", 0);

        return payload;
    }
}
