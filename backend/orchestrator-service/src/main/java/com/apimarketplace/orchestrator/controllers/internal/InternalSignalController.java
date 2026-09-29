package com.apimarketplace.orchestrator.controllers.internal;

import com.apimarketplace.orchestrator.controllers.workflow.WorkflowControllerHelper;
import com.apimarketplace.orchestrator.domain.execution.SignalResolution;
import com.apimarketplace.orchestrator.execution.v2.services.UnifiedSignalService;
import com.apimarketplace.orchestrator.repository.SignalWaitRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Internal endpoint for the Gateway to forward signal resolution actions
 * received via WebSocket.
 * Protected by X-Gateway-Secret header.
 */
@RestController
@RequestMapping("/api/internal/signals")
public class InternalSignalController {

    private static final Logger log = LoggerFactory.getLogger(InternalSignalController.class);

    private final UnifiedSignalService signalService;
    private final SignalWaitRepository signalWaitRepository;
    private final WorkflowRunRepository runRepository;
    private final com.apimarketplace.auth.client.access.OrgAccessGuard orgAccessGuard;

    public InternalSignalController(UnifiedSignalService signalService,
                                    SignalWaitRepository signalWaitRepository,
                                    WorkflowRunRepository runRepository,
                                    com.apimarketplace.auth.client.access.OrgAccessGuard orgAccessGuard) {
        this.signalService = signalService;
        this.signalWaitRepository = signalWaitRepository;
        this.runRepository = runRepository;
        this.orgAccessGuard = orgAccessGuard;
    }

    /** Backward-compatible direct-call overload used by controller unit tests: no role. */
    public ResponseEntity<Map<String, Object>> resolveSignal(Long signalId, String userId, String organizationId,
                                                             Map<String, Object> resolutionData) {
        return resolveSignal(signalId, userId, organizationId, null, resolutionData);
    }

    /**
     * Resolve a signal (approval, webhook wait, etc.) forwarded from WebSocket action.
     */
    @SuppressWarnings("unchecked")
    @PostMapping("/{signalId}/resolve")
    public ResponseEntity<Map<String, Object>> resolveSignal(
            @PathVariable Long signalId,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
            @RequestHeader(value = "X-Organization-Role", required = false) String organizationRole,
            @RequestBody Map<String, Object> resolutionData) {

        log.info("Internal signal resolution: signalId={}, userId={}", signalId, userId);

        // Run-scope guard - mirror of the public twin (WorkflowSignalController
        // guards every resolve with isRunInScope). The signalId is client-supplied
        // over WebSocket: without this check a forged signal.resolve could approve
        // a foreign run's pending approval. The WS layer forwards the session's
        // active workspace as X-Organization-ID (same resolution chain as the
        // HTTP AuthenticationFilter).
        if (userId == null || userId.isBlank()) {
            return ResponseEntity.status(401)
                    .body(Map.of("status", "error", "message", "Missing X-User-ID"));
        }
        var epochInfo = signalWaitRepository.findEpochInfoById(signalId);
        if (epochInfo.isEmpty()) {
            return ResponseEntity.status(404)
                    .body(Map.of("status", "error", "message", "Signal not found"));
        }
        var run = runRepository.findByRunIdPublic(epochInfo.get().runId());
        if (run.isEmpty() || !WorkflowControllerHelper.isRunInScope(run.get(), userId, organizationId)) {
            log.warn("[InternalSignal] Run scope blocked: signalId={}, runId={}, caller={}, org={}",
                    signalId, epochInfo.get().runId(), userId, organizationId);
            return ResponseEntity.status(404)
                    .body(Map.of("status", "error", "message", "Signal not found"));
        }
        // Same write gate as the HTTP twin: resolving drives the run with the owner's
        // credentials (VIEWER role, then the member deny-list on the run's workflow).
        // The WS layer forwards the role it validated at handshake / from the DB.
        String denial = com.apimarketplace.orchestrator.controllers.workflow.RunWriteGate.denial(
                orgAccessGuard, run.get(), userId, organizationId, organizationRole, "resolve signals on");
        if (denial != null) {
            return ResponseEntity.status(com.apimarketplace.orchestrator.controllers.workflow.RunWriteGate.statusFor(denial)).body(Map.of("status", "error", "message", denial));
        }

        try {
            String resolutionStr = (String) resolutionData.getOrDefault("resolution", "APPROVED");
            SignalResolution resolution = SignalResolution.valueOf(resolutionStr.toUpperCase());

            Map<String, Object> payload = resolutionData.containsKey("payload")
                    ? (Map<String, Object>) resolutionData.get("payload")
                    : Map.of();

            String resolvedBy = userId;

            boolean resolved = signalService.resolveSignal(signalId, resolution, payload, resolvedBy);

            if (resolved) {
                return ResponseEntity.ok(Map.of("status", "resolved", "signalId", signalId));
            } else {
                return ResponseEntity.ok(Map.of("status", "already_resolved", "signalId", signalId));
            }
        } catch (IllegalArgumentException e) {
            log.warn("Invalid resolution value for signal {}: {}", signalId, e.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("status", "error", "message", "Invalid resolution value"));
        } catch (Exception e) {
            log.error("Failed to resolve signal {}: {}", signalId, e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("status", "error", "message", e.getMessage()));
        }
    }
}
