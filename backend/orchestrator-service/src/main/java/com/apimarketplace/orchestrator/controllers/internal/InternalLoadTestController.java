package com.apimarketplace.orchestrator.controllers.internal;

import com.apimarketplace.orchestrator.controllers.dto.WorkflowPlanRequest;
import com.apimarketplace.orchestrator.controllers.workflow.WorkflowExecutionController;
import com.apimarketplace.orchestrator.trigger.TriggerController;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Auth-bypass shim endpoints used by the local k6 load harness
 * (see {@code scripts/load/}). Only exists to mirror the agent-service
 * pattern where {@code /api/internal/**} is exempt from the gateway filter,
 * so k6 can pound the queue without forging a gateway HMAC.
 *
 * <p>Delegates straight to {@link WorkflowExecutionController} and
 * {@link TriggerController} - no new business logic here. Do NOT call from
 * real clients.
 */
@RestController
@RequestMapping("/api/internal/orchestrator/loadtest")
public class InternalLoadTestController {

    private static final Logger log = LoggerFactory.getLogger(InternalLoadTestController.class);

    private final WorkflowExecutionController executionController;
    private final TriggerController triggerController;

    public InternalLoadTestController(
            WorkflowExecutionController executionController,
            TriggerController triggerController) {
        this.executionController = executionController;
        this.triggerController = triggerController;
    }

    @PostMapping("/dag/execute")
    public ResponseEntity<?> dagExecute(
            @Valid @RequestBody WorkflowPlanRequest request,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-User-Plan", required = false) String userPlan,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId,
            @RequestHeader(value = "X-Organization-Role", required = false) String orgRole) {
        log.info("[LoadTest] dag/execute userId={} plan={}", userId, userPlan);
        return executionController.executeWorkflow(request, userId, userPlan, orgId, orgRole,
                null, null, null, null);
    }

    @PostMapping("/runs/{runId}/trigger/manual")
    public ResponseEntity<TriggerController.TriggerResponse> triggerManual(
            @PathVariable("runId") String runId,
            @RequestBody(required = false) Map<String, Object> payload,
            @RequestHeader(value = "X-User-Plan", required = false) String userPlan,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId) {
        log.debug("[LoadTest] trigger/manual runId={} plan={}", runId, userPlan);
        return triggerController.triggerManual(runId, payload, userPlan, userId, orgId);
    }
}
