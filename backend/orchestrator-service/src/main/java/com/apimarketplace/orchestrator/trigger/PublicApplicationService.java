package com.apimarketplace.orchestrator.trigger;

import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.interfaces.client.InterfaceClient;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.execution.SignalResolution;
import com.apimarketplace.orchestrator.domain.execution.SignalType;
import com.apimarketplace.orchestrator.domain.execution.SignalWaitEntity;
import com.apimarketplace.orchestrator.domain.workflow.InterfaceDef;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.services.UnifiedSignalService;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.InterfaceRenderService;
import com.apimarketplace.orchestrator.services.InterfaceRenderService.InterfaceRenderResult;
import com.apimarketplace.orchestrator.services.epoch.WorkflowEpochService;
import com.apimarketplace.orchestrator.services.interfaces.InterfaceActionService;
import com.apimarketplace.publication.client.PublicationClient;
import com.apimarketplace.storage.client.StorageClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for public application access (no auth required, token-based).
 * Resolves shared link tokens to application runs and handles interface rendering + actions.
 */
@Service
public class PublicApplicationService {

    private static final Logger logger = LoggerFactory.getLogger(PublicApplicationService.class);

    private final PublicationClient publicationClient;
    private final WorkflowRepository workflowRepository;
    private final WorkflowRunRepository runRepository;
    private final InterfaceRenderService interfaceRenderService;
    private final InterfaceActionService interfaceActionService;
    private final UnifiedSignalService signalService;
    private final ReusableTriggerService triggerService;
    private final CreditConsumptionClient creditClient;
    private final StorageClient storageClient;
    private final WorkflowEpochService epochService;
    private final InterfaceClient interfaceClient;

    /**
     * Bounded in-memory cache: sl_token → AppContext.
     * Avoids re-resolving on every call. Max 1000 entries to prevent DoS.
     */
    private final ConcurrentHashMap<String, AppContext> cache = new ConcurrentHashMap<>();

    record AppContext(
            String publicationId,
            UUID workflowId,
            String tenantId,
            String appName,
            long resolvedAt
    ) {}

    private static final long CACHE_TTL_MS = 60 * 1000; // 1 minute (reduced for faster deactivation propagation)
    private static final int MAX_CACHE_SIZE = 1000;

    public PublicApplicationService(PublicationClient publicationClient,
                                    WorkflowRepository workflowRepository,
                                    WorkflowRunRepository runRepository,
                                    InterfaceRenderService interfaceRenderService,
                                    InterfaceActionService interfaceActionService,
                                    UnifiedSignalService signalService,
                                    ReusableTriggerService triggerService,
                                    CreditConsumptionClient creditClient,
                                    StorageClient storageClient,
                                    WorkflowEpochService epochService,
                                    InterfaceClient interfaceClient) {
        this.publicationClient = publicationClient;
        this.workflowRepository = workflowRepository;
        this.runRepository = runRepository;
        this.interfaceRenderService = interfaceRenderService;
        this.interfaceActionService = interfaceActionService;
        this.signalService = signalService;
        this.triggerService = triggerService;
        this.creditClient = creditClient;
        this.storageClient = storageClient;
        this.epochService = epochService;
        this.interfaceClient = interfaceClient;
    }

    /**
     * Best-effort async bump of {@code interfaces.updated_at} for the bell's
     * Activity tab - mirror of {@code InterfaceActionController.asyncTouchInterface}.
     * Fire-and-forget on ForkJoinPool with explicit try/catch in the lambda so
     * the WARN log actually fires. NEVER blocks the public-app action response
     * (failure = cosmetic timestamp gap, not user-visible error).
     */
    private void asyncTouchInterface(Map<String, Object> signalConfig, String orgIdForWorker) {
        if (signalConfig == null) return;
        Object rawId = signalConfig.get("interfaceId");
        if (!(rawId instanceof String idStr) || idStr.isBlank()) return;
        final UUID interfaceId;
        try {
            interfaceId = UUID.fromString(idStr);
        } catch (IllegalArgumentException e) {
            return;
        }
        // HOTFIX-2 (2026-05-20) - rebind orgId on the ForkJoinPool worker thread.
        // The common pool strips the request ThreadLocal, so any OrgScopedEntity
        // persist downstream of touchUpdatedAt would fail V261 NOT NULL.
        CompletableFuture.runAsync(() -> {
            com.apimarketplace.common.web.TenantResolver.runWithOrgScope(orgIdForWorker, () -> {
                try {
                    interfaceClient.touchUpdatedAt(interfaceId);
                } catch (Exception e) {
                    logger.warn("[PublicApp] Activity-tab touch failed for interface {} (non-critical): {}",
                            interfaceId, e.getMessage());
                }
            });
        });
    }

    // ========== Config ==========

    /**
     * Get application config: name, interfaces list, run status, active interfaces.
     */
    public Map<String, Object> getConfig(String slToken) {
        AppContext ctx = resolveToken(slToken);
        WorkflowEntity workflow = loadWorkflow(ctx.workflowId());

        WorkflowPlan plan = WorkflowPlan.fromMap(workflow.getPlan(), workflow.getTenantId());
        List<InterfaceDef> interfaces = plan.getInterfaces();

        // Find active run
        WorkflowRunEntity run = findActiveRun(workflow, ctx.publicationId());

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("name", ctx.appName() != null ? ctx.appName() : workflow.getName());
        config.put("description", workflow.getDescription());
        config.put("workflowId", workflow.getId().toString());

        // Interfaces list
        List<Map<String, Object>> interfaceList = interfaces.stream()
                .map(iface -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", iface.id());
                    m.put("label", iface.label());
                    m.put("nodeId", iface.getNormalizedKey());
                    m.put("isEntry", Boolean.TRUE.equals(iface.isEntryInterface()));
                    return m;
                })
                .toList();
        config.put("interfaces", interfaceList);

        if (run == null) {
            config.put("runId", null);
            config.put("runStatus", "NO_RUN");
            config.put("activeInterfaces", List.of());
            return config;
        }

        config.put("runId", run.getRunIdPublic());
        config.put("runStatus", run.getStatus().name());

        // Active INTERFACE_SIGNALs
        config.put("activeInterfaces", getActiveInterfaceSignals(run.getRunIdPublic()));

        return config;
    }

    // ========== Render ==========

    /**
     * Render an interface from the application's run.
     */
    public InterfaceRenderResult renderInterface(String slToken, UUID interfaceId, int page, int size, Integer epoch) {
        return renderInterface(slToken, interfaceId, page, size, epoch, java.util.Map.of());
    }

    /**
     * Render an interface with SQL-level variable pagination.
     */
    public InterfaceRenderResult renderInterface(String slToken, UUID interfaceId, int page, int size,
                                                  Integer epoch, java.util.Map<String, Integer> variablePages) {
        AppContext ctx = resolveToken(slToken);
        WorkflowEntity workflow = loadWorkflow(ctx.workflowId());
        WorkflowRunEntity run = findActiveRun(workflow, ctx.publicationId());

        if (run == null) {
            throw new IllegalStateException("No active run for this application");
        }

        return interfaceRenderService.render(interfaceId, run.getRunIdPublic(), ctx.tenantId(), page, size, epoch, variablePages);
    }

    // ========== Run Info (epoch timestamps) ==========

    /**
     * Get run-info: epoch timestamps for the epoch slider (run-scoped, same as WebSocket batch updates).
     */
    public Map<String, Object> getRunInfo(String slToken, UUID interfaceId) {
        AppContext ctx = resolveToken(slToken);
        WorkflowEntity workflow = loadWorkflow(ctx.workflowId());
        WorkflowRunEntity run = findActiveRun(workflow, ctx.publicationId());

        if (run == null) {
            return Map.of("epochTimestamps", List.of());
        }

        String runId = run.getRunIdPublic();

        // Use the same epoch source as the authenticated app (WorkflowEpochService)
        var epochs = epochService.listEpochTimestamps(runId);
        List<Map<String, Object>> epochList = epochs.stream().map(row -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("epoch", row.epoch());
            m.put("startedAt", row.startedAt() != null ? row.startedAt().toString() : null);
            m.put("endedAt", row.endedAt() != null ? row.endedAt().toString() : null);
            return m;
        }).toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("epochTimestamps", epochList);
        result.put("runId", runId);
        return result;
    }

    // ========== Action ==========

    /**
     * Fire an interface action on the application's run.
     */
    public Map<String, Object> fireAction(String slToken, String nodeId, String actionKey, Map<String, Object> data) {
        AppContext ctx = resolveToken(slToken);
        WorkflowEntity workflow = loadWorkflow(ctx.workflowId());
        WorkflowRunEntity run = findActiveRun(workflow, ctx.publicationId());

        if (run == null) {
            throw new IllegalStateException("No active run for this application");
        }

        String runId = run.getRunIdPublic();

        // Credit check
        if (!creditClient.checkCredits(ctx.tenantId())) {
            throw new IllegalStateException("Insufficient credits");
        }

        // Find active INTERFACE_SIGNAL for this nodeId
        List<SignalWaitEntity> activeSignals = signalService.getActiveSignals(runId);
        SignalWaitEntity targetSignal = activeSignals.stream()
                .filter(s -> nodeId.equals(s.getNodeId()))
                .filter(s -> s.getSignalType() == SignalType.INTERFACE_SIGNAL)
                .max(Comparator.comparingInt(SignalWaitEntity::getEpoch))
                .orElse(null);

        if (targetSignal == null) {
            throw new IllegalArgumentException("No active interface signal for the specified node");
        }

        // Resolve actionKey via actionMapping
        Map<String, Object> signalConfig = targetSignal.getSignalConfig();
        @SuppressWarnings("unchecked")
        Map<String, String> actionMapping = signalConfig != null && signalConfig.get("actionMapping") instanceof Map
                ? (Map<String, String>) signalConfig.get("actionMapping") : Map.of();

        String targetKey = actionMapping.get(actionKey);
        if (targetKey == null && "__continue".equals(actionKey)) {
            targetKey = "__continue";
        }
        if (targetKey == null) {
            throw new IllegalArgumentException("No target mapped for the specified action");
        }

        int epoch = targetSignal.getEpoch();

        // Persist action data
        try {
            interfaceActionService.persistActionData(runId, nodeId, actionKey, data, ctx.tenantId(), epoch);
        } catch (Exception e) {
            logger.warn("[PublicApp] Failed to persist action data (non-fatal): {}", e.getMessage());
        }

        // Bump interfaces.updated_at so the bell's Activity tab surfaces this
        // interface on public-app action fire. Fire-and-forget on ForkJoinPool -
        // failure is cosmetic only, does NOT block the public-app response.
        // HOTFIX-2 - thread workflow's org scope to the worker thread.
        asyncTouchInterface(signalConfig, workflow.getOrganizationId());

        // __continue: resolve the signal
        if ("__continue".equals(targetKey)) {
            logger.info("[PublicApp] __continue: resolving signal runId={}, nodeId={}", runId, nodeId);
            boolean resolved = signalService.resolveSignal(
                    targetSignal.getId(),
                    SignalResolution.CONTINUE,
                    Map.of("action_name", actionKey, "data", data),
                    "public_app");

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", resolved ? "continued" : "already_resolved");
            result.put("nodeId", nodeId);
            result.put("actionKey", actionKey);
            result.put("targetKey", targetKey);
            return result;
        }

        // Regular action: fire mapped trigger
        logger.info("[PublicApp] Firing action: runId={}, nodeId={}, actionKey={}, targetKey={}", runId, nodeId, actionKey, targetKey);

        // Extract triggerId from targetKey (e.g., "trigger:search:submit" → "trigger:search")
        String triggerId = extractTriggerId(targetKey);
        if (triggerId != null) {
            try {
                Map<String, Object> payload = new HashMap<>(data);
                payload.put("_source", "public_app");
                payload.put("_actionKey", actionKey);
                // Public-app data comes from an untrusted external client.
                // Strip the internal plan-control marker so it cannot be forged.
                payload = ReusableTriggerService.sanitizePlanMarker(payload);

                TriggerExecutionResult triggerResult = triggerService.executeTrigger(
                        run, triggerId, TriggerType.FORM, payload);

                if (!triggerResult.success()) {
                    logger.warn("[PublicApp] Trigger execution failed: {}", triggerResult.message());
                }
            } catch (Exception e) {
                logger.error("[PublicApp] Failed to execute trigger {}: {}", triggerId, e.getMessage());
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "fired");
        result.put("nodeId", nodeId);
        result.put("actionKey", actionKey);
        result.put("targetKey", targetKey);
        return result;
    }

    // ========== Trigger (same as authenticated /v2/workflows/runs/{runId}/trigger/{type}/{id}) ==========

    /**
     * Execute a trigger on the application's run (same flow as the authenticated trigger endpoint).
     * The frontend parses "trigger:label:submit" → triggerId="trigger:label", triggerType="form".
     */
    public Map<String, Object> executeTrigger(String slToken, String triggerId, String triggerType, Map<String, Object> data) {
        AppContext ctx = resolveToken(slToken);
        WorkflowEntity workflow = loadWorkflow(ctx.workflowId());
        WorkflowRunEntity run = findActiveRun(workflow, ctx.publicationId());

        if (run == null) {
            throw new IllegalStateException("No active run for this application");
        }

        // Credit check
        if (!creditClient.checkCredits(ctx.tenantId())) {
            throw new IllegalStateException("Insufficient credits");
        }

        TriggerType type = "chat".equalsIgnoreCase(triggerType) ? TriggerType.CHAT :
                           "form".equalsIgnoreCase(triggerType) ? TriggerType.FORM : TriggerType.MANUAL;

        Map<String, Object> payload = new HashMap<>(data);
        payload.put("_source", "public_app");
        // Public-app data is untrusted external - sanitize the internal marker.
        payload = ReusableTriggerService.sanitizePlanMarker(payload);

        logger.info("[PublicApp] Executing trigger: runId={}, triggerId={}, type={}", run.getRunIdPublic(), triggerId, type);

        TriggerExecutionResult result = triggerService.executeTrigger(run, triggerId, type, payload);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", result.success() ? "triggered" : "failed");
        response.put("triggerId", triggerId);
        response.put("message", result.message());
        return response;
    }

    // ========== Status ==========

    /**
     * Get run status and active interface signals.
     */
    public Map<String, Object> getStatus(String slToken) {
        AppContext ctx = resolveToken(slToken);
        WorkflowEntity workflow = loadWorkflow(ctx.workflowId());
        WorkflowRunEntity run = findActiveRun(workflow, ctx.publicationId());

        Map<String, Object> status = new LinkedHashMap<>();
        if (run == null) {
            status.put("runStatus", "NO_RUN");
            status.put("activeInterfaces", List.of());
            return status;
        }

        status.put("runId", run.getRunIdPublic());
        status.put("runStatus", run.getStatus().name());
        status.put("activeInterfaces", getActiveInterfaceSignals(run.getRunIdPublic()));
        return status;
    }

    // ========== File Proxy ==========

    /**
     * Download a file from storage using the application owner's tenant context.
     * Validates that the file key belongs to the application's tenant.
     */
    public byte[] downloadFile(String slToken, String key) {
        AppContext ctx = resolveToken(slToken);
        // Validate key belongs to this tenant (key format: tenantId/...)
        if (!key.startsWith(ctx.tenantId() + "/")) {
            throw new IllegalArgumentException("Access denied");
        }
        byte[] content = storageClient.download(ctx.tenantId(), key);
        if (content == null) {
            throw new IllegalArgumentException("File not found");
        }
        return content;
    }

    // ========== Helpers ==========

    private AppContext resolveToken(String slToken) {
        AppContext cached = cache.get(slToken);
        if (cached != null && (System.currentTimeMillis() - cached.resolvedAt()) < CACHE_TTL_MS) {
            return cached;
        }

        // Remove expired entry
        if (cached != null) {
            cache.remove(slToken);
        }

        Map<String, Object> linkData = publicationClient.resolveSharedLinkByToken(slToken);

        // Unified error message for all token validation failures (prevents enumeration)
        if (linkData == null) {
            throw new IllegalArgumentException("Invalid or expired share token");
        }
        boolean isActive = Boolean.TRUE.equals(linkData.get("isActive"));
        if (!isActive) {
            throw new IllegalArgumentException("Invalid or expired share token");
        }
        String resourceType = (String) linkData.get("resourceType");
        if (!"APPLICATION".equals(resourceType)) {
            throw new IllegalArgumentException("Invalid or expired share token");
        }

        String tenantId = (String) linkData.get("tenantId");
        String title = (String) linkData.get("title");

        // Get workflowId from metadata or publication
        UUID workflowId = resolveWorkflowId(linkData);

        AppContext ctx = new AppContext(
                (String) linkData.get("resourceToken"),
                workflowId,
                tenantId,
                title,
                System.currentTimeMillis()
        );

        // Bounded cache: evict oldest entries if at capacity
        if (cache.size() >= MAX_CACHE_SIZE) {
            cache.entrySet().stream()
                    .min(Comparator.comparingLong(e -> e.getValue().resolvedAt()))
                    .ifPresent(oldest -> cache.remove(oldest.getKey()));
        }

        cache.put(slToken, ctx);
        return ctx;
    }

    @SuppressWarnings("unchecked")
    private UUID resolveWorkflowId(Map<String, Object> linkData) {
        // Try metadata.workflowId first
        Object metadata = linkData.get("metadata");
        if (metadata instanceof Map) {
            Object wfId = ((Map<String, Object>) metadata).get("workflowId");
            if (wfId != null) {
                return UUID.fromString(wfId.toString());
            }
        }

        // Fallback: look up publication for workflowId
        String publicationId = (String) linkData.get("resourceToken");
        if (publicationId != null) {
            Map<String, Object> publication = publicationClient.getPublicationById(UUID.fromString(publicationId));
            if (publication != null && publication.get("workflowId") != null) {
                return UUID.fromString(publication.get("workflowId").toString());
            }
        }

        // Last resort: resourceId might be the workflowId
        String resourceId = (String) linkData.get("resourceId");
        if (resourceId != null && !resourceId.isBlank()) {
            return UUID.fromString(resourceId);
        }

        throw new IllegalStateException("Application resource not found");
    }

    private WorkflowEntity loadWorkflow(UUID workflowId) {
        return workflowRepository.findById(workflowId)
                .orElseThrow(() -> new IllegalArgumentException("Application resource not found"));
    }

    /**
     * Find the active application run for a workflow + publication.
     * Prefers the application-dedicated run (source='application'), falls back to latest run.
     */
    private WorkflowRunEntity findActiveRun(WorkflowEntity workflow, String publicationId) {
        // Look for the application-dedicated run (source='application' + publicationId).
        // This is the SAME query used by /app/applications to find/create runs.
        // No fallback to showcase or other runs - shared apps must display the application run.
        if (publicationId != null) {
            Optional<WorkflowRunEntity> appRun = runRepository
                    .findFirstByWorkflowIdAndSourceAndPublicationIdOrderByStartedAtDesc(
                            workflow.getId(), "application", publicationId);
            if (appRun.isPresent()) {
                WorkflowRunEntity run = appRun.get();
                logger.info("[PublicApp] Found application run: runId={}, status={}, publicationId={}",
                        run.getRunIdPublic(), run.getStatus(), publicationId);
                if (!run.getStatus().isTerminal() || run.getStatus() == RunStatus.COMPLETED) {
                    return run;
                }
            }
        }

        logger.warn("[PublicApp] No application run found for workflowId={}, publicationId={} - "
                + "user must open the application in /app/applications first to create a run",
                workflow.getId(), publicationId);
        return null;
    }

    private List<Map<String, Object>> getActiveInterfaceSignals(String runId) {
        List<SignalWaitEntity> activeSignals = signalService.getActiveSignals(runId);
        return activeSignals.stream()
                .filter(s -> s.getSignalType() == SignalType.INTERFACE_SIGNAL)
                .map(signal -> {
                    Map<String, Object> signalMap = new LinkedHashMap<>();
                    signalMap.put("nodeId", signal.getNodeId());
                    signalMap.put("epoch", signal.getEpoch());
                    if (signal.getSignalConfig() != null) {
                        signalMap.put("interfaceId", signal.getSignalConfig().get("interfaceId"));
                        signalMap.put("actionMapping", signal.getSignalConfig().get("actionMapping"));
                    }
                    return signalMap;
                })
                .toList();
    }

    /**
     * Extract triggerId from a targetKey like "trigger:search:submit" → "trigger:search".
     */
    private static String extractTriggerId(String targetKey) {
        if (targetKey == null || !targetKey.startsWith("trigger:")) return null;
        String[] parts = targetKey.split(":");
        if (parts.length >= 3) {
            return parts[0] + ":" + parts[1];
        }
        return targetKey;
    }
}
