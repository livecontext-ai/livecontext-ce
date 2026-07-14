package com.apimarketplace.orchestrator.controllers.workflow;

import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.execution.SignalResolution;
import com.apimarketplace.orchestrator.domain.execution.SignalType;
import com.apimarketplace.orchestrator.execution.v2.split.SplitContextManager;
import com.apimarketplace.orchestrator.domain.execution.SignalWaitEntity;
import com.apimarketplace.orchestrator.execution.v2.services.SignalResumeService;
import com.apimarketplace.orchestrator.execution.v2.services.UnifiedSignalService;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * REST controller for workflow signal operations.
 *
 * Endpoints:
 * - POST .../signals/{nodeId}/resolve: Approve/reject a signal (user action)
 * - GET .../signals: List pending signals for a run
 * - DELETE .../signals/{nodeId}: Cancel a signal
 * - POST .../signals/callback: Webhook callback (external systems)
 */
@RestController
@RequestMapping("/api/v2/workflows/dag/runs/{runId}/signals")
public class WorkflowSignalController {

    private static final Logger logger = LoggerFactory.getLogger(WorkflowSignalController.class);

    private final UnifiedSignalService signalService;
    private final SignalResumeService signalResumeService;
    private final WorkflowRunRepository runRepository;

    public WorkflowSignalController(UnifiedSignalService signalService,
                                     SignalResumeService signalResumeService,
                                     WorkflowRunRepository runRepository) {
        this.signalService = signalService;
        this.signalResumeService = signalResumeService;
        this.runRepository = runRepository;
    }

    /**
     * Audit 2026-05-16 round-3 - every signal endpoint MUST scope by caller.
     * Returns:
     *   401 if X-User-ID is missing
     *   404 if run is not visible to (tenantId, orgId)
     *   null on success - caller proceeds.
     */
    private ResponseEntity<Map<String, Object>> guardRunScope(
            String runId, String userId, String orgId) {
        if (userId == null || userId.isBlank()) {
            return ResponseEntity.status(401).body(Map.of("error", "Missing X-User-ID"));
        }
        Optional<WorkflowRunEntity> runOpt = runRepository.findByRunIdPublic(runId);
        if (runOpt.isEmpty()) return ResponseEntity.notFound().build();
        if (!WorkflowControllerHelper.isRunInScope(runOpt.get(), userId, orgId)) {
            logger.warn("[SCOPE] Signal endpoint cross-tenant blocked: runId={} caller={} orgId={}", runId, userId, orgId);
            return ResponseEntity.notFound().build();
        }
        return null;
    }

    private ResponseEntity<List<Map<String, Object>>> guardRunScopeList(
            String runId, String userId, String orgId) {
        if (userId == null || userId.isBlank()) return ResponseEntity.status(401).build();
        Optional<WorkflowRunEntity> runOpt = runRepository.findByRunIdPublic(runId);
        if (runOpt.isEmpty()) return ResponseEntity.notFound().build();
        if (!WorkflowControllerHelper.isRunInScope(runOpt.get(), userId, orgId)) {
            logger.warn("[SCOPE] Signal list cross-tenant blocked: runId={} caller={} orgId={}", runId, userId, orgId);
            return ResponseEntity.notFound().build();
        }
        return null;
    }

    /**
     * Resolve a signal (approve, reject, or complete).
     *
     * Request body:
     * {
     *   "resolution": "APPROVED" | "REJECTED" | "TIMEOUT" | "CANCELLED",
     *   "comment": "optional comment",
     *   "data": { ... optional resolution data ... }
     * }
     */
    @PostMapping("/{nodeId}/resolve")
    public ResponseEntity<Map<String, Object>> resolveSignal(
            @PathVariable String runId,
            @PathVariable String nodeId,
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId) {

        logger.info("[SignalController] Resolve signal: runId={}, nodeId={}, userId={}", runId, nodeId, userId);

        ResponseEntity<Map<String, Object>> scopeBlock = guardRunScope(runId, userId, orgId);
        if (scopeBlock != null) return scopeBlock;

        String resolutionStr = (String) body.get("resolution");
        if (resolutionStr == null || resolutionStr.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "Missing 'resolution' field",
                "valid_values", List.of("APPROVED", "REJECTED", "TIMEOUT", "CANCELLED")));
        }

        SignalResolution resolution;
        try {
            resolution = SignalResolution.valueOf(resolutionStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "Invalid resolution: " + resolutionStr,
                "valid_values", List.of("APPROVED", "REJECTED", "TIMEOUT", "CANCELLED")));
        }

        // Build resolution data from body
        Map<String, Object> resolutionData = new LinkedHashMap<>();
        if (body.get("comment") != null) {
            resolutionData.put("comment", body.get("comment"));
        }
        if (body.get("data") != null && body.get("data") instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> extraData = (Map<String, Object>) body.get("data");
            resolutionData.putAll(extraData);
        }

        // Find the active signal for this run/nodeId, scoped by epoch and/or itemId when provided.
        // Without epoch filtering, findFirst() returns the earliest signal (wrong epoch).
        // Without itemId filtering, split context signals are ambiguous (all share the same nodeId).
        Integer requestedEpoch = null;
        Object epochObj = body.get("epoch");
        if (epochObj instanceof Number) {
            requestedEpoch = ((Number) epochObj).intValue();
        }
        String requestedItemId = body.get("itemId") instanceof String s ? s : null;

        List<SignalWaitEntity> activeSignals = signalService.getActiveSignals(runId);
        SignalWaitEntity targetSignal;
        if (requestedItemId != null) {
            // itemId specified: find the exact signal for this item (split context)
            final Integer ep = requestedEpoch;
            targetSignal = activeSignals.stream()
                .filter(s -> nodeId.equals(s.getNodeId()) && requestedItemId.equals(s.getItemId()))
                .filter(s -> ep == null || s.getEpoch() == ep)
                .findFirst()
                .orElse(null);
        } else if (requestedEpoch != null) {
            // Explicit epoch: find the signal for exactly that epoch
            final int epoch = requestedEpoch;
            targetSignal = activeSignals.stream()
                .filter(s -> nodeId.equals(s.getNodeId()) && s.getEpoch() == epoch)
                .findFirst()
                .orElse(null);
        } else {
            // No epoch/itemId specified: pick the LATEST epoch's signal for this nodeId
            // (prevents resolving an earlier epoch's signal by accident)
            targetSignal = activeSignals.stream()
                .filter(s -> nodeId.equals(s.getNodeId()))
                .max(Comparator.comparingInt(SignalWaitEntity::getEpoch))
                .orElse(null);
        }

        if (targetSignal == null) {
            return ResponseEntity.notFound().build();
        }

        String resolvedBy = userId != null ? userId : "api";
        boolean resolved = signalService.resolveSignal(
            targetSignal.getId(), resolution, resolutionData, resolvedBy);

        if (!resolved) {
            return ResponseEntity.status(409).body(Map.of(
                "error", "Signal could not be resolved (already claimed or resolved)"));
        }

        // Synchronous resume on the HTTP thread (the async @TransactionalEventListener is a
        // safety net that may not fire in all contexts). The agent resolve_approval action
        // performs the same resolve+resume via RunSignalResolutionService.resolveAndResume.
        targetSignal.setResolution(resolution);
        targetSignal.setResolutionData(resolutionData);
        targetSignal.setResolvedAt(Instant.now());
        targetSignal.setResolvedBy(resolvedBy);
        try {
            signalResumeService.resumeAfterSignal(targetSignal);
        } catch (Exception e) {
            logger.warn("[SignalController] Sync resume after signal failed (async listener is safety net): {}",
                e.getMessage());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "resolved");
        result.put("signalId", targetSignal.getId());
        result.put("nodeId", nodeId);
        result.put("resolution", resolution.name());
        result.put("resolvedBy", resolvedBy);

        return ResponseEntity.ok(result);
    }

    /**
     * Resolve ALL pending USER_APPROVAL signals for a node.
     * When epoch is provided, only resolves signals for that specific epoch
     * (used in split context where each item has its own signal within one epoch).
     * When epoch is omitted, resolves across all epochs (all-epoch bulk view).
     *
     * Request body:
     * {
     *   "resolution": "APPROVED" | "REJECTED",
     *   "comment": "optional comment",
     *   "epoch": optional integer
     * }
     */
    @PostMapping("/{nodeId}/resolve-all")
    public ResponseEntity<Map<String, Object>> resolveAllSignals(
            @PathVariable String runId,
            @PathVariable String nodeId,
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId) {

        logger.info("[SignalController] Resolve ALL signals: runId={}, nodeId={}, userId={}", runId, nodeId, userId);

        ResponseEntity<Map<String, Object>> scopeBlock = guardRunScope(runId, userId, orgId);
        if (scopeBlock != null) return scopeBlock;

        String resolutionStr = (String) body.get("resolution");
        if (resolutionStr == null || resolutionStr.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "Missing 'resolution' field",
                "valid_values", List.of("APPROVED", "REJECTED", "TIMEOUT", "CANCELLED")));
        }

        SignalResolution resolution;
        try {
            resolution = SignalResolution.valueOf(resolutionStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "Invalid resolution: " + resolutionStr,
                "valid_values", List.of("APPROVED", "REJECTED", "TIMEOUT", "CANCELLED")));
        }

        Map<String, Object> resolutionData = new LinkedHashMap<>();
        if (body.get("comment") != null) {
            resolutionData.put("comment", body.get("comment"));
        }

        Object epochObj = body.get("epoch");
        Integer requestedEpoch = epochObj instanceof Number n ? n.intValue() : null;

        List<SignalWaitEntity> activeSignals = signalService.getActiveSignals(runId);
        List<SignalWaitEntity> targetSignals = activeSignals.stream()
            .filter(s -> nodeId.equals(s.getNodeId()) && s.getSignalType() == SignalType.USER_APPROVAL)
            .filter(s -> requestedEpoch == null || s.getEpoch() == requestedEpoch)
            .sorted(Comparator.comparingInt(SignalWaitEntity::getEpoch))
            .toList();

        if (targetSignals.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        String resolvedBy = userId != null ? userId : "api";
        int resolvedCount = 0;

        for (SignalWaitEntity signal : targetSignals) {
            boolean resolved = signalService.resolveSignal(
                signal.getId(), resolution, resolutionData, resolvedBy);

            if (resolved) {
                resolvedCount++;
                signal.setResolution(resolution);
                signal.setResolutionData(resolutionData);
                signal.setResolvedAt(Instant.now());
                signal.setResolvedBy(resolvedBy);
                try {
                    signalResumeService.resumeAfterSignal(signal);
                } catch (Exception e) {
                    logger.warn("[SignalController] Sync resume after bulk signal failed for epoch={}: {}",
                        signal.getEpoch(), e.getMessage());
                }
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "resolved");
        result.put("count", resolvedCount);
        result.put("nodeId", nodeId);
        result.put("resolution", resolution.name());
        result.put("resolvedBy", resolvedBy);

        return ResponseEntity.ok(result);
    }

    /**
     * List pending signals for a workflow run.
     * Includes itemId and epoch for each signal so the frontend can distinguish
     * per-item signals in split context.
     */
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> listSignals(
            @PathVariable String runId,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId) {
        logger.debug("[SignalController] List signals: runId={}", runId);

        ResponseEntity<List<Map<String, Object>>> scopeBlock = guardRunScopeList(runId, userId, orgId);
        if (scopeBlock != null) return scopeBlock;

        List<SignalWaitEntity> activeSignals = signalService.getActiveSignals(runId);
        List<Map<String, Object>> signalList = new ArrayList<>();

        for (SignalWaitEntity signal : activeSignals) {
            signalList.add(buildSignalMap(signal));
        }

        return ResponseEntity.ok(signalList);
    }

    /**
     * List pending signals for a specific node.
     * Returns per-item detail (itemId, epoch, config) so the frontend can render
     * individual approval buttons in split context (e.g., 5 items each needing approval).
     */
    @GetMapping("/{nodeId}/pending")
    public ResponseEntity<List<Map<String, Object>>> listPendingForNode(
            @PathVariable String runId,
            @PathVariable String nodeId,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId) {

        logger.debug("[SignalController] List pending signals: runId={}, nodeId={}", runId, nodeId);

        ResponseEntity<List<Map<String, Object>>> scopeBlock = guardRunScopeList(runId, userId, orgId);
        if (scopeBlock != null) return scopeBlock;

        List<SignalWaitEntity> signals = signalService.getActiveSignalsForNode(runId, nodeId);
        List<Map<String, Object>> signalList = new ArrayList<>();

        for (SignalWaitEntity signal : signals) {
            signalList.add(buildSignalMap(signal));
        }

        return ResponseEntity.ok(signalList);
    }

    /**
     * Build a signal map with all fields needed by the frontend, including itemId and epoch.
     */
    private Map<String, Object> buildSignalMap(SignalWaitEntity signal) {
        Map<String, Object> signalMap = new LinkedHashMap<>();
        signalMap.put("id", signal.getId());
        signalMap.put("nodeId", signal.getNodeId());
        signalMap.put("signalType", signal.getSignalType().name());
        signalMap.put("status", signal.getStatus().name());
        signalMap.put("epoch", signal.getEpoch());
        signalMap.put("itemId", signal.getItemId());
        if (signal.getExpiresAt() != null) {
            signalMap.put("expiresAt", signal.getExpiresAt().toString());
        }
        if (signal.getSignalConfig() != null) {
            signalMap.put("config", signal.getSignalConfig());
        }
        // Split context: expose the per-item data persisted at registration so the
        // approver can see WHAT they are approving (e.g. which item of the split).
        // DISPLAY-only projection: the persisted blob also carries restoration keys
        // (splitNodeId/items/...) for cross-pod resume - those must NOT reach the UI.
        if (signal.getSplitItemData() != null && !signal.getSplitItemData().isEmpty()) {
            signalMap.put("itemContext",
                SplitContextManager.toDisplayItemContext(signal.getSplitItemData()));
        }
        // Configured approval context: the node's contextTemplate resolved at yield, shown to the
        // approver. Plain display text (no restoration keys to strip).
        if (signal.getApprovalContext() != null && !signal.getApprovalContext().isBlank()) {
            signalMap.put("approvalContext", signal.getApprovalContext());
        }
        return signalMap;
    }

    /**
     * Cancel a signal for a specific node.
     */
    @DeleteMapping("/{nodeId}")
    public ResponseEntity<Map<String, Object>> cancelSignal(
            @PathVariable String runId,
            @PathVariable String nodeId,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId) {

        logger.info("[SignalController] Cancel signal: runId={}, nodeId={}, userId={}", runId, nodeId, userId);

        ResponseEntity<Map<String, Object>> scopeBlock = guardRunScope(runId, userId, orgId);
        if (scopeBlock != null) return scopeBlock;

        // Cancel ONLY the named node's signal(s), not every pending signal in the run.
        // cancelByRun would have cancelled sibling approvals and timers too; epoch=-1 targets
        // this node across all epochs.
        int cancelled = signalService.cancelForNodes(runId, java.util.Set.of(nodeId), -1);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "cancelled");
        result.put("cancelledCount", cancelled);
        result.put("nodeId", nodeId);

        return ResponseEntity.ok(result);
    }
}
