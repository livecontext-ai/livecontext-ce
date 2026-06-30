package com.apimarketplace.orchestrator.services.persistence;

import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowPlanVersionEntity;
import com.apimarketplace.orchestrator.domain.workflow.Trigger;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.services.WorkflowPlanVersionService;
import com.apimarketplace.trigger.client.TriggerClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Pin-aware trigger sync for ALL trigger types (schedule, webhook, chat, form).
 *
 * <p><b>Core invariant:</b> Only the pinned (production) version's triggers are active.
 * Draft/unpinned version saves NEVER modify production trigger resources.
 *
 * <p>Called from:
 * <ul>
 *   <li>{@code WorkflowVersionController.pinVersion()} - after pin/unpin</li>
 *   <li>{@code WorkflowManagementService.saveWorkflow()} - after each save</li>
 * </ul>
 *
 * <p>Delegates schedule sync to {@link ScheduleSyncService} and handles
 * webhook/chat/form sync directly via {@link TriggerClient}.
 */
@Service
public class PinAwareTriggerSyncService {

    private static final Logger logger = LoggerFactory.getLogger(PinAwareTriggerSyncService.class);

    private final TriggerClient triggerClient;
    private final WorkflowPlanVersionService versionService;
    private final ScheduleSyncService scheduleSyncService;
    private final DatasourceSubscriptionSyncService datasourceSubscriptionSyncService;

    @Autowired
    public PinAwareTriggerSyncService(
            @Autowired(required = false) TriggerClient triggerClient,
            @Autowired(required = false) WorkflowPlanVersionService versionService,
            @Autowired(required = false) ScheduleSyncService scheduleSyncService,
            @Autowired(required = false) DatasourceSubscriptionSyncService datasourceSubscriptionSyncService) {
        this.triggerClient = triggerClient;
        this.versionService = versionService;
        this.scheduleSyncService = scheduleSyncService;
        this.datasourceSubscriptionSyncService = datasourceSubscriptionSyncService;
    }

    /**
     * Syncs ALL trigger types from the pinned production version.
     *
     * <p>If a pinned version exists, loads its plan and syncs:
     * schedules, webhooks, chat endpoints, and form endpoints.
     * If no version is pinned, cleans up all external trigger resources.
     *
     * @param workflow the workflow entity
     */
    public void syncAllTriggersFromPinnedVersion(WorkflowEntity workflow) {
        if (triggerClient == null) {
            logger.warn("[TriggerSync] TriggerClient not available, skipping all trigger sync");
            return;
        }

        Integer pinnedVersion = workflow.getPinnedVersion();

        if (pinnedVersion == null) {
            // Unpinned - disable/cleanup all production triggers
            logger.info("[TriggerSync] No pinned version for workflow {}, disabling all triggers",
                    workflow.getId());
            disableAllTriggers(workflow.getId());
            return;
        }

        // Load the pinned version's plan
        WorkflowPlan pinnedPlan = loadPinnedPlan(workflow.getId(), pinnedVersion);
        if (pinnedPlan == null) {
            // Symmetric to ScheduleSyncService.syncFromPlan plan-null guard:
            // a transient DB error or a missing plan version row must NOT cascade
            // into a wipe of every trigger resource. Skip and let the next sync retry.
            // (Hardening 2026-04-29: previously this called cleanupAllTriggers which
            // deletes schedules + clears chat/form back-links; transient failures
            // would have suspended a working production workflow.)
            logger.error("[TriggerSync] Could not load pinned plan v{} for workflow {} - skipping sync to preserve state (next save/pin will retry)",
                    pinnedVersion, workflow.getId());
            return;
        }

        // Sync each trigger type from the pinned plan
        syncSchedules(workflow);
        syncWebhooks(workflow, pinnedPlan);
        syncChatFormEndpoints(workflow, pinnedPlan);
        syncDatasourceSubscriptions(workflow);

        logger.info("[TriggerSync] All triggers synced for workflow {} from pinned v{}",
                workflow.getId(), pinnedVersion);
    }

    /**
     * Syncs all triggers using a specific plan.
     *
     * <p>Contract: a {@code scheduled_executions} row is in state {@code ACTIVE}
     * <em>iff</em> the workflow has a pinned production version (live toggle ON).
     * Drafts of unpinned workflows MUST NOT create or re-arm schedule rows -
     * without this guard, a clone/install or any plain draft save creates rows
     * that auto-fire forever on workflows the user never made "live".
     *
     * <p>Webhook / chat / form endpoints are URL-bound resources that fire only
     * on external HTTP request; users may need to configure them before pinning.
     * Their sync from draft is preserved.
     *
     * @param workflow the workflow entity
     * @param plan     the plan to sync from (draft plan); ignored for schedule
     *                 sync - schedules always follow pin state.
     */
    public void syncAllTriggersFromPlan(WorkflowEntity workflow, WorkflowPlan plan) {
        if (triggerClient == null) {
            logger.warn("[TriggerSync] TriggerClient not available, skipping trigger sync");
            return;
        }

        // If pinned, always use pinned version (draft plan is ignored)
        if (workflow.getPinnedVersion() != null) {
            syncAllTriggersFromPinnedVersion(workflow);
            return;
        }

        // Unpinned save: schedule rows must NOT be ACTIVE (contract above).
        // syncFromPinnedVersion with pinnedVersion=null falls through to
        // disableAllSchedules - suspends any existing ACTIVE row, no-op on already
        // suspended ones. Webhook / chat / form sync from draft as before.
        if (scheduleSyncService != null) {
            scheduleSyncService.syncFromPinnedVersion(workflow);
        }
        syncWebhooks(workflow, plan);
        syncChatFormEndpoints(workflow, plan);
        // Unpinned workflows have no production subscriptions - pin is required
        // for the datasource event stream to reach the run.
        if (datasourceSubscriptionSyncService != null) {
            datasourceSubscriptionSyncService.deleteAllSubscriptions(workflow.getId());
        }
    }

    // ─────────────────────── Datasource subscriptions ───────────────────────

    private void syncDatasourceSubscriptions(WorkflowEntity workflow) {
        if (datasourceSubscriptionSyncService != null) {
            datasourceSubscriptionSyncService.syncFromPinnedVersion(workflow);
        }
    }

    // ─────────────────────── Schedule ───────────────────────

    private void syncSchedules(WorkflowEntity workflow) {
        if (scheduleSyncService != null) {
            scheduleSyncService.syncFromPinnedVersion(workflow);
        }
    }

    // ─────────────────────── Webhook ───────────────────────

    /**
     * Syncs webhook tokens from a plan. Extracts current webhook trigger IDs
     * and cleans up orphan tokens via trigger-service.
     *
     * <p>Also pushes per-trigger webhook config (httpMethod, authType, authConfig)
     * for triggers backed by a standalone webhook (params.webhookId set). Without
     * this push, edits in the builder UI never reach standalone_webhooks and the
     * row stays on its creation-time config - including auth type, which is a
     * SECURITY issue (e.g. user changes auth=basic but row keeps auth=none).
     */
    void syncWebhooks(WorkflowEntity workflow, WorkflowPlan plan) {
        List<String> currentTriggerIds = new ArrayList<>();
        java.util.List<String> webhookSecurityWarnings = new java.util.ArrayList<>();
        if (plan != null && plan.getTriggers() != null) {
            for (Trigger trigger : plan.getTriggers()) {
                if ("webhook".equalsIgnoreCase(trigger.type())) {
                    currentTriggerIds.add(trigger.getNormalizedKey());
                    String warning = pushStandaloneWebhookConfig(workflow, trigger);
                    if (warning != null) webhookSecurityWarnings.add(warning);
                }
            }
        }

        try {
            triggerClient.cleanupOrphanTokens(workflow.getId(), currentTriggerIds);
        } catch (Exception e) {
            logger.warn("[TriggerSync] Failed to cleanup orphan webhook tokens for workflow {}: {}",
                    workflow.getId(), e.getMessage());
        }

        // SECURITY: if any webhook config push hit a transient error (5xx/transport),
        // surface it via TriggerSyncWarningException so the controller propagates the
        // warning to the API response. The user must know that auth/method changes
        // may not have taken effect on a publicly-accessible endpoint.
        if (!webhookSecurityWarnings.isEmpty()) {
            throw new TriggerSyncWarningException(
                    "Webhook config push failed (auth/method change may not have taken effect): "
                            + String.join("; ", webhookSecurityWarnings));
        }
    }

    /**
     * Pushes the webhook config to standalone_webhooks. Returns:
     * <ul>
     *   <li>{@code null} on success or non-security failure (legacy attached, malformed id, 404 - phantom row)</li>
     *   <li>a non-null warning string on transient/server errors that may have left
     *       a public endpoint on a stale auth_type - SECURITY risk to surface upstream.</li>
     * </ul>
     */
    private String pushStandaloneWebhookConfig(WorkflowEntity workflow, Trigger trigger) {
        Map<String, Object> params = trigger.params();
        if (params == null) return null;
        Object webhookIdObj = params.get("webhookId");
        if (webhookIdObj == null) return null;  // Attached webhook (legacy) - token-only, no config row to update
        UUID webhookId;
        try {
            webhookId = UUID.fromString(webhookIdObj.toString());
        } catch (IllegalArgumentException e) {
            logger.warn("[TriggerSync] Invalid webhookId in plan for workflow {} trigger {}: {}",
                    workflow.getId(), trigger.getNormalizedKey(), webhookIdObj);
            return null;
        }

        String httpMethod = stringOr(params.get("httpMethod"), "POST");
        String authType = stringOr(params.get("authType"), "none");
        Map<String, String> authConfig = extractAuthConfig(authType, params);

        com.apimarketplace.trigger.client.dto.StandaloneWebhookRequest request =
                new com.apimarketplace.trigger.client.dto.StandaloneWebhookRequest(
                        trigger.label() != null ? trigger.label() : trigger.getNormalizedKey(),
                        null,
                        httpMethod,
                        authType,
                        authConfig,
                        workflow.getId().toString(),
                        workflow.getName(),
                        null);
        try {
            triggerClient.updateStandaloneWebhookStrict(workflow.getTenantId(), webhookId, request);
            logger.info("[TriggerSync] Pushed webhook config to {} (workflow {} trigger {})",
                    webhookId, workflow.getId(), trigger.getNormalizedKey());
            return null;
        } catch (com.apimarketplace.trigger.client.TriggerClientException e) {
            if (e.isNotFound()) {
                // Phantom row - log warn but do not block the workflow save. The user will
                // recreate the webhook from settings UI when they notice it's gone.
                logger.warn("[TriggerSync] Standalone webhook {} not found for workflow {}: row may have been deleted out-of-band",
                        webhookId, workflow.getId());
                return null;
            }
            // Transient (5xx / transport) - SECURITY: auth/method change did NOT take effect.
            // Public webhook may still be accessible on the previous auth_type.
            logger.error("[TriggerSync] SECURITY: webhook {} config push failed transiently for workflow {} ({}): {} - public endpoint may stay on stale auth",
                    webhookId, workflow.getId(), e.getKind(), e.getMessage());
            return "webhook " + webhookId + " (" + e.getKind() + ")";
        } catch (Exception e) {
            logger.warn("[TriggerSync] Failed to push webhook config {} for workflow {}: {}",
                    webhookId, workflow.getId(), e.getMessage());
            return null;
        }
    }

    private static Map<String, String> extractAuthConfig(String authType, Map<String, Object> params) {
        if (authType == null || "none".equalsIgnoreCase(authType)) return null;
        java.util.HashMap<String, String> cfg = new java.util.HashMap<>();
        switch (authType.toLowerCase()) {
            case "basic" -> {
                cfg.put("username", stringOr(params.get("basicUsername"), ""));
                cfg.put("password", stringOr(params.get("basicPassword"), ""));
            }
            case "header" -> {
                cfg.put("headerName", stringOr(params.get("authHeaderName"), "X-API-Key"));
                cfg.put("headerValue", stringOr(params.get("authHeaderValue"), ""));
            }
            case "jwt" -> {
                cfg.put("secretKey", stringOr(params.get("jwtSecretKey"), ""));
                cfg.put("algorithm", stringOr(params.get("jwtAlgorithm"), "HS256"));
            }
            default -> { return null; }
        }
        return cfg;
    }

    private static String stringOr(Object o, String fallback) {
        return o == null ? fallback : o.toString();
    }

    // ─────────────────────── Chat / Form ───────────────────────

    /**
     * Syncs chat and form endpoint trigger IDs from a plan.
     * If a trigger type was removed from the plan, sets its triggerId to null.
     */
    void syncChatFormEndpoints(WorkflowEntity workflow, WorkflowPlan plan) {
        String chatTriggerId = null;
        String formTriggerId = null;
        UUID chatEndpointId = null;
        UUID formEndpointId = null;
        Trigger chatTrigger = null;
        Trigger formTrigger = null;

        if (plan != null && plan.getTriggers() != null) {
            for (Trigger t : plan.getTriggers()) {
                if ("chat".equalsIgnoreCase(t.type()) && chatTriggerId == null) {
                    chatTriggerId = t.getNormalizedKey();
                    chatEndpointId = extractEndpointId(t.params(), "chatEndpointId");
                    chatTrigger = t;
                }
                if ("form".equalsIgnoreCase(t.type()) && formTriggerId == null) {
                    formTriggerId = t.getNormalizedKey();
                    formEndpointId = extractEndpointId(t.params(), "formEndpointId");
                    formTrigger = t;
                }
            }
        }

        // Acquired/cloned applications have their standalone endpoint ids STRIPPED at clone
        // (PlanStripUtils, anti-hijack), so a form trigger arrives with NO formEndpointId. Per
        // PlanStripUtils' own contract each clone must "create its own fresh row on first sync",
        // but this sync only ever ADOPTED an endpoint by id - it never CREATED one - so a
        // downloaded app's form never got a row, never appeared in Public Access, and could not
        // fire. Create a fresh, org-scoped endpoint here (mirrors
        // TriggerCreator.autoCreateStandaloneFormEndpoint); the back-link below then stamps its
        // workflow_id. Idempotent across re-pins: ensureFormEndpointForWorkflow reuses the row
        // already linked to this workflow instead of creating a duplicate.
        if (formTrigger != null && formEndpointId == null) {
            formEndpointId = ensureFormEndpointForWorkflow(workflow, formTriggerId, formTrigger);
        }
        // Same stripped-clone gap for the CHAT public endpoint (chatEndpointId is stripped too).
        if (chatTrigger != null && chatEndpointId == null) {
            chatEndpointId = ensureChatEndpointForWorkflow(workflow, chatTriggerId, chatTrigger);
        }

        // Link newly-created endpoints to the workflow. Endpoints are created in the builder
        // before the workflow has an ID, so `workflow_id` stays NULL until this back-link
        // call. Without it, syncTriggerId/dispatch lookups by workflow_id find nothing.
        //
        // INVARIANT: if the back-link fails (cross-tenant 404 or transient HTTP), do NOT
        // run the subsequent syncTriggerId - otherwise we'd overwrite the triggerId on
        // an unrelated set of endpoints (incoherent state). Symmetric to the schedule
        // phantom-keepId fix (2026-04-29 21:00).
        String tenantId = workflow.getTenantId();
        String workflowName = workflow.getName();
        boolean chatBackLinkOk = chatEndpointId == null;  // null = nothing to link, "ok" by default
        boolean formBackLinkOk = formEndpointId == null;
        if (chatEndpointId != null) {
            try {
                Object result = triggerClient.updateChatEndpointWorkflowReference(
                        tenantId, chatEndpointId, workflow.getId(), workflowName);
                chatBackLinkOk = (result != null);
                if (!chatBackLinkOk) {
                    logger.warn("[TriggerSync] Chat endpoint back-link returned null for workflow {} endpoint {} - skipping syncTriggerId to avoid overwriting unrelated endpoints",
                            workflow.getId(), chatEndpointId);
                }
            } catch (Exception e) {
                logger.warn("[TriggerSync] Failed to link chat endpoint {} to workflow {}: {}",
                        chatEndpointId, workflow.getId(), e.getMessage());
            }
        }
        if (formEndpointId != null) {
            try {
                Object result = triggerClient.updateFormEndpointWorkflowReference(
                        tenantId, formEndpointId, workflow.getId(), workflowName);
                formBackLinkOk = (result != null);
                if (!formBackLinkOk) {
                    logger.warn("[TriggerSync] Form endpoint back-link returned null for workflow {} endpoint {} - skipping syncTriggerId + config push",
                            workflow.getId(), formEndpointId);
                }
            } catch (Exception e) {
                logger.warn("[TriggerSync] Failed to link form endpoint {} to workflow {}: {}",
                        formEndpointId, workflow.getId(), e.getMessage());
            }
            // Push the form config (fields, authType, successMessage) so a UI edit
            // reaches the standalone_form_endpoints row instead of staying in the plan.
            // Symmetric to the schedule cron-stale fix.
            if (formBackLinkOk && formTrigger != null) {
                pushFormEndpointConfig(workflow, tenantId, formEndpointId, formTriggerId, formTrigger);
            }
        }

        if (chatBackLinkOk) {
            try {
                triggerClient.syncChatEndpointTriggerId(workflow.getId(), chatTriggerId);
            } catch (Exception e) {
                logger.warn("[TriggerSync] Failed to sync chat endpoint triggerId for workflow {}: {}",
                        workflow.getId(), e.getMessage());
            }
        }

        if (formBackLinkOk) {
            try {
                triggerClient.syncFormEndpointTriggerId(workflow.getId(), formTriggerId);
            } catch (Exception e) {
                logger.warn("[TriggerSync] Failed to sync form endpoint triggerId for workflow {}: {}",
                        workflow.getId(), e.getMessage());
            }
        }
    }

    /**
     * Ensure a standalone form endpoint exists for this workflow's form trigger and return its id.
     * Used for acquired/cloned applications whose {@code formEndpointId} was stripped at clone time
     * ({@code PlanStripUtils}). Idempotent: reuses the endpoint already linked to this workflow (so a
     * re-pin never duplicates), otherwise creates a fresh one in the workflow's ORGANIZATION (workspace
     * parity). Mirrors {@code TriggerCreator.autoCreateStandaloneFormEndpoint}; the caller's back-link
     * then stamps {@code workflow_id} so the form shows in Public Access and can fire.
     */
    @SuppressWarnings("unchecked")
    private UUID ensureFormEndpointForWorkflow(WorkflowEntity workflow, String formTriggerId, Trigger formTrigger) {
        UUID existing = findFormEndpointIdForWorkflow(workflow.getTenantId(), workflow.getId());
        if (existing != null) {
            return existing;
        }
        Map<String, Object> params = formTrigger.params() != null ? formTrigger.params() : Map.of();
        Object fieldsObj = params.get("fields");
        List<Map<String, Object>> fields = (fieldsObj instanceof List<?>) ? (List<Map<String, Object>>) fieldsObj : null;
        String label = formTrigger.label() != null ? formTrigger.label() : formTriggerId;
        // workflowId/workflowName left null here (mirrors the builder): the back-link in the caller
        // stamps workflow_id immediately after, which is also what makes the next sync idempotent.
        com.apimarketplace.trigger.client.dto.StandaloneFormEndpointRequest request =
                new com.apimarketplace.trigger.client.dto.StandaloneFormEndpointRequest(
                        label,
                        stringOrNull(params.get("formDescription")),
                        null,
                        null,
                        fields,
                        stringOrNull(params.get("successMessage")),
                        null,
                        formTriggerId);
        try {
            com.apimarketplace.trigger.client.dto.StandaloneFormEndpointDto created =
                    triggerClient.createFormEndpoint(workflow.getTenantId(), null, request, workflow.getOrganizationId());
            if (created != null && created.getId() != null) {
                logger.info("[TriggerSync] Auto-created form endpoint {} for workflow {} (org {}) from a stripped clone",
                        created.getId(), workflow.getId(), workflow.getOrganizationId());
                return created.getId();
            }
        } catch (Exception e) {
            logger.warn("[TriggerSync] Failed to auto-create form endpoint for workflow {} trigger {}: {}",
                    workflow.getId(), formTriggerId, e.getMessage());
        }
        // createFormEndpoint returned null (e.g. 409 already-exists from a concurrent sync) - adopt
        // the workflow-linked row if one now exists.
        return findFormEndpointIdForWorkflow(workflow.getTenantId(), workflow.getId());
    }

    /**
     * The id of the form endpoint already linked to this workflow, or null. Matches by
     * {@code workflow_id} ONLY (never by triggerId) so a same-named form trigger in a sibling
     * workflow is never wrongly adopted (the very hijack class {@code PlanStripUtils} guards).
     */
    private UUID findFormEndpointIdForWorkflow(String tenantId, UUID workflowId) {
        try {
            List<com.apimarketplace.trigger.client.dto.StandaloneFormEndpointDto> all =
                    triggerClient.getFormEndpoints(tenantId);
            if (all != null) {
                for (com.apimarketplace.trigger.client.dto.StandaloneFormEndpointDto e : all) {
                    if (e != null && workflowId.equals(e.getWorkflowId())) {
                        return e.getId();
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("[TriggerSync] Failed to list form endpoints for workflow {}: {}", workflowId, e.getMessage());
        }
        return null;
    }

    /**
     * Ensure a standalone CHAT endpoint exists for this workflow's chat trigger and return its id.
     * Mirrors {@link #ensureFormEndpointForWorkflow}: the chat public endpoint id ({@code chatEndpointId})
     * is stripped at clone the same way, so an acquired app's chat never reached Public Access. Idempotent
     * via workflow_id; created in the workflow's ORGANIZATION (workspace parity).
     */
    private UUID ensureChatEndpointForWorkflow(WorkflowEntity workflow, String chatTriggerId, Trigger chatTrigger) {
        UUID existing = findChatEndpointIdForWorkflow(workflow.getTenantId(), workflow.getId());
        if (existing != null) {
            return existing;
        }
        Map<String, Object> params = chatTrigger.params() != null ? chatTrigger.params() : Map.of();
        String label = chatTrigger.label() != null ? chatTrigger.label() : chatTriggerId;
        Object memObj = params.get("memoryEnabled");
        Boolean memoryEnabled = (memObj instanceof Boolean b) ? b : Boolean.TRUE;
        com.apimarketplace.trigger.client.dto.StandaloneChatEndpointRequest request =
                new com.apimarketplace.trigger.client.dto.StandaloneChatEndpointRequest(
                        label,
                        stringOrNull(params.get("description")),
                        null,
                        null,
                        stringOrNull(params.get("welcomeMessage")),
                        stringOrNull(params.get("model")),
                        stringOrNull(params.get("provider")),
                        memoryEnabled,
                        null,
                        chatTriggerId);
        try {
            com.apimarketplace.trigger.client.dto.StandaloneChatEndpointDto created =
                    triggerClient.createChatEndpoint(workflow.getTenantId(), null, request, workflow.getOrganizationId());
            if (created != null && created.getId() != null) {
                logger.info("[TriggerSync] Auto-created chat endpoint {} for workflow {} (org {}) from a stripped clone",
                        created.getId(), workflow.getId(), workflow.getOrganizationId());
                return created.getId();
            }
        } catch (Exception e) {
            logger.warn("[TriggerSync] Failed to auto-create chat endpoint for workflow {} trigger {}: {}",
                    workflow.getId(), chatTriggerId, e.getMessage());
        }
        return findChatEndpointIdForWorkflow(workflow.getTenantId(), workflow.getId());
    }

    /** The id of the chat endpoint already linked to this workflow, or null. Matches by workflow_id only. */
    private UUID findChatEndpointIdForWorkflow(String tenantId, UUID workflowId) {
        try {
            List<com.apimarketplace.trigger.client.dto.StandaloneChatEndpointDto> all =
                    triggerClient.getChatEndpoints(tenantId);
            if (all != null) {
                for (com.apimarketplace.trigger.client.dto.StandaloneChatEndpointDto e : all) {
                    if (e != null && workflowId.equals(e.getWorkflowId())) {
                        return e.getId();
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("[TriggerSync] Failed to list chat endpoints for workflow {}: {}", workflowId, e.getMessage());
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private void pushFormEndpointConfig(WorkflowEntity workflow, String tenantId,
                                         UUID formEndpointId, String formTriggerId, Trigger trigger) {
        Map<String, Object> params = trigger.params();
        if (params == null) params = Map.of();

        String name = trigger.label() != null ? trigger.label() : formTriggerId;
        String description = stringOrNull(params.get("formDescription"));
        Object fieldsObj = params.get("fields");
        List<Map<String, Object>> formConfig = (fieldsObj instanceof List<?>) ? (List<Map<String, Object>>) fieldsObj : List.of();
        String successMessage = stringOrNull(params.get("submitButtonText"));

        com.apimarketplace.trigger.client.dto.StandaloneFormEndpointRequest request =
                new com.apimarketplace.trigger.client.dto.StandaloneFormEndpointRequest(
                        name, description, workflow.getId().toString(), workflow.getName(),
                        formConfig, successMessage, null, formTriggerId);
        try {
            com.apimarketplace.trigger.client.dto.StandaloneFormEndpointDto result =
                    triggerClient.updateFormEndpoint(tenantId, formEndpointId, request);
            if (result == null) {
                logger.warn("[TriggerSync] Form config push returned null for workflow {} endpoint {} - form fields/successMessage may not have taken effect",
                        workflow.getId(), formEndpointId);
                return;
            }
            logger.info("[TriggerSync] Pushed form config to {} (workflow {} trigger {})",
                    formEndpointId, workflow.getId(), formTriggerId);
        } catch (Exception e) {
            logger.warn("[TriggerSync] Failed to push form config {} for workflow {}: {}",
                    formEndpointId, workflow.getId(), e.getMessage());
        }
    }

    private static String stringOrNull(Object o) {
        return o == null ? null : o.toString();
    }

    private static UUID extractEndpointId(Map<String, Object> params, String key) {
        if (params == null) return null;
        Object v = params.get(key);
        if (v == null) return null;
        try {
            return UUID.fromString(v.toString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ─────────────────────── Helpers ───────────────────────

    /**
     * Loads the pinned version's plan.
     */
    WorkflowPlan loadPinnedPlan(UUID workflowId, int pinnedVersion) {
        if (versionService == null) {
            logger.warn("[TriggerSync] WorkflowPlanVersionService not available");
            return null;
        }
        try {
            Optional<WorkflowPlanVersionEntity> versionOpt =
                    versionService.getVersion(workflowId, pinnedVersion);
            if (versionOpt.isEmpty()) {
                logger.warn("[TriggerSync] Pinned version {} not found for workflow {}",
                        pinnedVersion, workflowId);
                return null;
            }
            Map<String, Object> planMap = versionOpt.get().getPlan();
            if (planMap == null) {
                return null;
            }
            return WorkflowPlan.fromMap(planMap, null);
        } catch (Exception e) {
            logger.error("[TriggerSync] Failed to load pinned plan v{} for workflow {}: {}",
                    pinnedVersion, workflowId, e.getMessage());
            return null;
        }
    }

    /**
     * Disables all production triggers (used when unpinned).
     * Schedules are disabled (not deleted) - user may re-pin.
     * Webhook tokens, chat/form endpoint references are cleared.
     */
    private void disableAllTriggers(UUID workflowId) {
        // Disable schedules via ScheduleSyncService (which disables, not deletes)
        if (scheduleSyncService != null) {
            try {
                // Create a temporary workflow entity to pass to syncFromPinnedVersion
                // which handles the null pinnedVersion → disable case
                WorkflowEntity tempWorkflow = new WorkflowEntity();
                tempWorkflow.setId(workflowId);
                tempWorkflow.setPinnedVersion(null);
                scheduleSyncService.syncFromPinnedVersion(tempWorkflow);
            } catch (Exception e) {
                logger.warn("[TriggerSync] Failed to disable schedules for workflow {}: {}", workflowId, e.getMessage());
            }
        }
        // Clear webhook orphan tokens
        try {
            triggerClient.cleanupOrphanTokens(workflowId, List.of());
        } catch (Exception e) {
            logger.warn("[TriggerSync] Failed to cleanup webhook tokens for workflow {}: {}", workflowId, e.getMessage());
        }
        // Clear chat/form endpoint references
        try {
            triggerClient.syncChatEndpointTriggerId(workflowId, null);
        } catch (Exception e) {
            logger.warn("[TriggerSync] Failed to clear chat triggerId for workflow {}: {}", workflowId, e.getMessage());
        }
        try {
            triggerClient.syncFormEndpointTriggerId(workflowId, null);
        } catch (Exception e) {
            logger.warn("[TriggerSync] Failed to clear form triggerId for workflow {}: {}", workflowId, e.getMessage());
        }
        // Datasource subscriptions are deleted (not just disabled) on unpin -
        // without a pinned version there is no production run for row events
        // to target. A future re-pin recreates them from the pinned plan.
        if (datasourceSubscriptionSyncService != null) {
            datasourceSubscriptionSyncService.deleteAllSubscriptions(workflowId);
        }
    }

    // NOTE: cleanupAllTriggers method DELETED 2026-05-13. It was private, had ZERO callers
    // post-2026-04-29 hardening (kept as dead code with a comment at line 88 referencing it).
    // The method's hard-DELETE pattern conflicted with the v5 SUSPEND-on-cleanup design.
    // Audit C tech debt item #2. Removed to align with single-cleanup-path policy.
}
