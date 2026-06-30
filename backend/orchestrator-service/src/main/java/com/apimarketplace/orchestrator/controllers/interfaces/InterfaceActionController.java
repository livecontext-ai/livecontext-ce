package com.apimarketplace.orchestrator.controllers.interfaces;

import com.apimarketplace.interfaces.client.InterfaceClient;
import com.apimarketplace.orchestrator.controllers.workflow.WorkflowControllerHelper;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.execution.SignalResolution;
import com.apimarketplace.orchestrator.domain.execution.SignalType;
import com.apimarketplace.orchestrator.domain.execution.SignalWaitEntity;
import com.apimarketplace.orchestrator.execution.v2.services.UnifiedSignalService;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.interfaces.InterfaceActionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * REST controller for interface action fire operations.
 *
 * Fires interface actions (button clicks, form submits) without resolving
 * the interface signal. The interface stays active in AWAITING_SIGNAL state.
 *
 * <p>Action fires can trigger downstream branches by executing target triggers
 * or steps mapped via the actionMapping configuration.</p>
 */
@RestController
@RequestMapping("/api/v2/workflows/dag/runs/{runId}/interface-actions")
public class InterfaceActionController {

    private static final Logger logger = LoggerFactory.getLogger(InterfaceActionController.class);

    private final UnifiedSignalService signalService;
    private final InterfaceActionService interfaceActionService;
    private final WorkflowRunRepository runRepository;
    private final InterfaceClient interfaceClient;

    public InterfaceActionController(UnifiedSignalService signalService,
                                     InterfaceActionService interfaceActionService,
                                     WorkflowRunRepository runRepository,
                                     InterfaceClient interfaceClient) {
        this.signalService = signalService;
        this.interfaceActionService = interfaceActionService;
        this.runRepository = runRepository;
        this.interfaceClient = interfaceClient;
    }

    /**
     * Best-effort async bump of {@code interfaces.updated_at} so the bell's
     * Activity tab surfaces this interface after a user action fire. Uses
     * {@code CompletableFuture.runAsync} on the default ForkJoinPool (fire-and-
     * forget - does NOT use {@code recentActivityExecutor} which has
     * {@code CallerRunsPolicy} and would block the Tomcat request thread under
     * saturation). The explicit try/catch inside the lambda is required for the
     * WARN log to fire - uncaught exceptions in async lambdas go to the default
     * uncaught-exception handler, not to our logger.
     */
    private void asyncTouchInterface(Map<String, Object> signalConfig, String orgIdForWorker) {
        if (signalConfig == null) return;
        Object rawId = signalConfig.get("interfaceId");
        if (!(rawId instanceof String idStr) || idStr.isBlank()) return;
        final UUID interfaceId;
        try {
            interfaceId = UUID.fromString(idStr);
        } catch (IllegalArgumentException e) {
            return; // malformed signalConfig - skip silently
        }
        // HOTFIX-2 (2026-05-20) - re-bind orgId on the ForkJoinPool worker thread.
        // Default common pool strips the request ThreadLocal; without re-binding,
        // any OrgScopedEntity persist downstream of touchUpdatedAt would fail
        // V261 NOT NULL and cascade-abort the transaction.
        CompletableFuture.runAsync(() -> {
            com.apimarketplace.common.web.TenantResolver.runWithOrgScope(orgIdForWorker, () -> {
                try {
                    interfaceClient.touchUpdatedAt(interfaceId);
                } catch (Exception e) {
                    logger.warn("[InterfaceAction] Activity-tab touch failed for interface {} (non-critical): {}",
                            interfaceId, e.getMessage());
                }
            });
        });
    }

    /**
     * Audit 2026-05-17 round-4 - fire-action and signal-list scoped by caller.
     * Returns 401 if X-User-ID missing, 404 if run not visible to (tenant, org).
     */
    private boolean isCallerInRunScope(String runId, String userId, String orgId) {
        if (userId == null || userId.isBlank()) return false;
        Optional<WorkflowRunEntity> runOpt = runRepository.findByRunIdPublic(runId);
        if (runOpt.isEmpty()) return false;
        return WorkflowControllerHelper.isRunInScope(runOpt.get(), userId, orgId);
    }

    /**
     * Fire an interface action.
     *
     * The action is matched to a target via the interface's actionMapping.
     * The interface signal is NOT resolved - the interface stays active.
     *
     * Request body:
     * {
     *   "actionKey": "submit",
     *   "data": { "field1": "value1", ... }
     * }
     *
     * @param runId  The workflow run ID
     * @param nodeId The interface node ID (e.g., "interface:my_form")
     * @param body   The action payload
     * @return Action fire result
     */
    @PostMapping("/{nodeId}/fire")
    public ResponseEntity<Map<String, Object>> fireAction(
            @PathVariable String runId,
            @PathVariable String nodeId,
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId) {

        logger.info("[InterfaceAction] Fire action: runId={}, nodeId={}, userId={}", runId, nodeId, userId);

        if (userId == null || userId.isBlank()) {
            return ResponseEntity.status(401).body(Map.of("error", "Missing X-User-ID"));
        }
        if (!isCallerInRunScope(runId, userId, orgId)) {
            logger.warn("[SCOPE] InterfaceAction.fireAction cross-tenant blocked: runId={} caller={} orgId={}",
                    runId, userId, orgId);
            return ResponseEntity.notFound().build();
        }

        String actionKey = body.get("actionKey") instanceof String ? (String) body.get("actionKey") : null;
        if (actionKey == null || actionKey.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "Missing 'actionKey' field"));
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> data = body.get("data") instanceof Map ? (Map<String, Object>) body.get("data") : Map.of();

        // Optional itemId for split context: identifies which per-item signal to target.
        // Without it, we pick the latest-epoch signal (fine for non-split, ambiguous for split).
        String requestedItemId = body.get("itemId") instanceof String s ? s : null;
        // itemIndex for persistActionData (storage key). Defaults to 0 for non-split.
        int itemIndex = 0;
        if (body.get("itemIndex") instanceof Number n) {
            itemIndex = n.intValue();
        } else if (requestedItemId != null) {
            try { itemIndex = Integer.parseInt(requestedItemId); } catch (NumberFormatException ignored) {}
        }

        // Find the active INTERFACE_SIGNAL for this run/nodeId
        List<SignalWaitEntity> activeSignals = signalService.getActiveSignals(runId);
        SignalWaitEntity targetSignal;
        if (requestedItemId != null) {
            // Split context: find the exact signal for this item
            targetSignal = activeSignals.stream()
                .filter(s -> nodeId.equals(s.getNodeId()))
                .filter(s -> s.getSignalType() == SignalType.INTERFACE_SIGNAL)
                .filter(s -> requestedItemId.equals(s.getItemId()))
                .max(java.util.Comparator.comparingInt(SignalWaitEntity::getEpoch))
                .orElse(null);
        } else {
            // Non-split: pick the latest-epoch signal
            targetSignal = activeSignals.stream()
                .filter(s -> nodeId.equals(s.getNodeId()))
                .filter(s -> s.getSignalType() == SignalType.INTERFACE_SIGNAL)
                .max(java.util.Comparator.comparingInt(SignalWaitEntity::getEpoch))
                .orElse(null);
        }

        if (targetSignal == null) {
            return ResponseEntity.notFound().build();
        }

        // Look up the actionMapping from the signal config
        Map<String, Object> signalConfig = targetSignal.getSignalConfig();
        @SuppressWarnings("unchecked")
        Map<String, String> actionMapping = signalConfig != null && signalConfig.get("actionMapping") instanceof Map
            ? (Map<String, String>) signalConfig.get("actionMapping") : Map.of();

        String targetKey = actionMapping.get(actionKey);

        // Support __continue as a direct action key (default continue button in toolbar)
        if (targetKey == null && "__continue".equals(actionKey)) {
            targetKey = "__continue";
        }

        if (targetKey == null) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "No target mapped for actionKey: " + actionKey,
                "availableActions", actionMapping.keySet()));
        }

        // Persist action data on the interface node for downstream SpEL access
        if (userId != null && !userId.isBlank()) {
            try {
                int epoch = targetSignal.getEpoch();
                interfaceActionService.persistActionData(runId, nodeId, actionKey, data, userId, epoch, null, itemIndex);
            } catch (Exception e) {
                logger.warn("[InterfaceAction] Failed to persist action data (non-fatal): {}", e.getMessage());
            }
        }

        // Bump interfaces.updated_at so the bell's Activity tab surfaces this
        // interface on user action fire. Fire-and-forget on ForkJoinPool -
        // failure does NOT block the user's action response.
        // HOTFIX-2 - capture the request's active org BEFORE the runAsync so the
        // worker thread inherits the binding (the HTTP thread still has it).
        asyncTouchInterface(signalConfig,
                com.apimarketplace.common.web.TenantResolver.currentRequestOrganizationId());

        // __continue: resolve the signal so the workflow continues past the interface node
        if ("__continue".equals(targetKey)) {
            logger.info("[InterfaceAction] __continue action: resolving signal for runId={}, nodeId={}", runId, nodeId);
            boolean resolved = signalService.resolveSignal(
                    targetSignal.getId(),
                    SignalResolution.CONTINUE,
                    Map.of("action_name", actionKey, "data", data),
                    userId != null ? userId : "system");

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", resolved ? "continued" : "already_resolved");
            result.put("nodeId", nodeId);
            result.put("actionKey", actionKey);
            result.put("targetKey", targetKey);
            result.put("data", data);
            return ResponseEntity.ok(result);
        }

        // Regular action: DO NOT resolve the signal - the interface stays active.
        // The target execution is handled separately (e.g., TriggerService).
        logger.info("[InterfaceAction] Action fired: runId={}, nodeId={}, actionKey={}, targetKey={}",
            runId, nodeId, actionKey, targetKey);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "fired");
        result.put("nodeId", nodeId);
        result.put("actionKey", actionKey);
        result.put("targetKey", targetKey);
        result.put("data", data);

        return ResponseEntity.ok(result);
    }

    /**
     * List pending interface signals for a workflow run.
     */
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> listInterfaceSignals(
            @PathVariable String runId,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId) {
        logger.debug("[InterfaceAction] List interface signals: runId={}", runId);

        if (userId == null || userId.isBlank()) return ResponseEntity.status(401).build();
        if (!isCallerInRunScope(runId, userId, orgId)) {
            logger.warn("[SCOPE] InterfaceAction.listInterfaceSignals cross-tenant blocked: runId={} caller={} orgId={}",
                    runId, userId, orgId);
            return ResponseEntity.notFound().build();
        }

        List<SignalWaitEntity> activeSignals = signalService.getActiveSignals(runId);
        List<Map<String, Object>> result = activeSignals.stream()
            .filter(s -> s.getSignalType() == SignalType.INTERFACE_SIGNAL)
            .map(signal -> {
                Map<String, Object> signalMap = new LinkedHashMap<>();
                signalMap.put("id", signal.getId());
                signalMap.put("nodeId", signal.getNodeId());
                signalMap.put("status", signal.getStatus().name());
                if (signal.getSignalConfig() != null) {
                    signalMap.put("interfaceId", signal.getSignalConfig().get("interfaceId"));
                    signalMap.put("actionMapping", signal.getSignalConfig().get("actionMapping"));
                }
                return signalMap;
            })
            .toList();

        return ResponseEntity.ok(result);
    }
}
