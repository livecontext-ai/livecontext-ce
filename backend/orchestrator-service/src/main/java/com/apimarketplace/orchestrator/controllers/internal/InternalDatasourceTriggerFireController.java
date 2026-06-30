package com.apimarketplace.orchestrator.controllers.internal;

import com.apimarketplace.orchestrator.trigger.DatasourceTriggerDispatchService;
import com.apimarketplace.orchestrator.trigger.DatasourceTriggerDispatchService.DispatchResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Internal endpoint invoked by trigger-service when it has fanned out a row
 * event to a matching subscription. Each call fires the DATASOURCE trigger for
 * one (workflowId, triggerId).
 *
 * This controller does the orchestrator-side work that the trigger-service does
 * NOT do:
 *   - pin enforcement (via ProductionRunResolver)
 *   - credit check
 *   - resuming on the run (ReusableTriggerService.executeTrigger)
 *
 * Not gateway-exposed - only reachable from trigger-service on the internal
 * network (same pattern as /api/internal/trigger/* on trigger-service).
 */
@RestController
@RequestMapping("/api/internal/triggers/datasource")
public class InternalDatasourceTriggerFireController {

    private final DatasourceTriggerDispatchService dispatchService;

    public InternalDatasourceTriggerFireController(DatasourceTriggerDispatchService dispatchService) {
        this.dispatchService = dispatchService;
    }

    @PostMapping("/fire")
    public ResponseEntity<Map<String, Object>> fire(@RequestBody Map<String, Object> body) {
        UUID workflowId = parseUuid(body.get("workflowId"));
        String triggerId = asString(body.get("triggerId"));
        String eventType = asString(body.get("eventType"));
        Long dataSourceId = asLong(body.get("dataSourceId"));
        Long rowId = asLong(body.get("rowId"));
        @SuppressWarnings("unchecked")
        Map<String, Object> row = (Map<String, Object>) body.get("row");
        @SuppressWarnings("unchecked")
        Map<String, Object> previousRow = (Map<String, Object>) body.get("previousRow");
        Instant triggeredAt = parseInstant(body.get("triggeredAt"));
        // 2026-05-18 - workspace scope of the originating datasource event.
        // Null = legacy caller or personal scope. The dispatch service uses it
        // to refuse cross-workspace fan-out when present.
        String organizationId = asString(body.get("organizationId"));

        DispatchResult result = dispatchService.dispatch(workflowId, triggerId, eventType,
                dataSourceId, rowId, row, previousRow, triggeredAt, organizationId);

        Map<String, Object> response = new HashMap<>();
        response.put("success", result.success());
        response.put("status", result.status());
        if (result.message() != null) response.put("message", result.message());
        if (result.runId() != null) response.put("runId", result.runId());

        // Always 200 - dispatch outcomes (not_pinned, no_run, etc.) are domain
        // signals, not HTTP-level errors. trigger-service logs them but keeps
        // fanning out to other subscriptions.
        return ResponseEntity.ok(response);
    }

    private static String asString(Object o) {
        return o == null ? null : o.toString();
    }

    private static UUID parseUuid(Object o) {
        if (o == null) return null;
        try { return UUID.fromString(o.toString()); }
        catch (IllegalArgumentException e) { return null; }
    }

    private static Long asLong(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.longValue();
        try { return Long.parseLong(o.toString()); }
        catch (NumberFormatException e) { return null; }
    }

    private static Instant parseInstant(Object o) {
        if (o == null) return null;
        try { return Instant.parse(o.toString()); }
        catch (Exception e) { return null; }
    }
}
