package com.apimarketplace.orchestrator.services;

import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.Trigger;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.domain.workflow.ValidationResult;
import com.apimarketplace.auth.client.access.OrgAccessGuard;
import com.apimarketplace.common.scope.ScopeGuard;
import com.apimarketplace.common.storage.repository.StorageRepository;
import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.common.storage.service.StorageBreakdownService;

import com.apimarketplace.orchestrator.repository.PendingSignalRepository;
import com.apimarketplace.orchestrator.repository.SignalWaitRepository;
import com.apimarketplace.orchestrator.repository.WorkflowEpochRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.file.FileStorageService;
import com.apimarketplace.orchestrator.services.interfaces.WorkflowCrud;
import com.apimarketplace.orchestrator.services.persistence.PinAwareTriggerSyncService;
import com.apimarketplace.orchestrator.services.persistence.ScheduleSyncService;
import com.apimarketplace.orchestrator.webhook.WebhookIndexService;
import com.apimarketplace.publication.client.PublicationClient;
import com.apimarketplace.trigger.client.TriggerClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Service centralisé pour la gestion des workflows (génération, validation, sauvegarde)
 * Simplifie et centralise toute la logique métier
 *
 * @see WorkflowCrud
 */
@Service
public class WorkflowManagementService implements WorkflowCrud {

    private static final Logger logger = LoggerFactory.getLogger(WorkflowManagementService.class);

    @Autowired
    private WorkflowRepository workflowRepository;

    // WorkflowValidationService removed - legacy validation system deprecated

    @Autowired
    private WorkflowRunRepository workflowRunRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired(required = false)
    private WebhookIndexService webhookIndexService;

    @Autowired
    private TriggerClient triggerClient;

    @Autowired
    private PublicationClient publicationClient;

    @Autowired(required = false)
    private com.apimarketplace.conversation.client.ConversationClient conversationServiceClient;

    @Autowired
    private StorageBreakdownService breakdownService;

    @Autowired
    private StorageRepository storageRepository;

    @Autowired
    private FileStorageService fileStorageService;

    @Autowired
    private WorkflowEpochRepository workflowEpochRepository;

    @Autowired
    private SignalWaitRepository signalWaitRepository;

    @Autowired
    private PendingSignalRepository pendingSignalRepository;

    @Autowired
    private OrgAccessGuard orgAccessService;

    @Autowired
    private ScheduleSyncService scheduleSyncService;

    @Autowired
    private PinAwareTriggerSyncService pinAwareTriggerSyncService;

    @Autowired
    private com.apimarketplace.auth.client.entitlement.EntitlementGuard entitlementGuard;

    @PersistenceContext
    private EntityManager entityManager;

    // Chat/form endpoint repos removed - now delegated to trigger-service via TriggerClient

    /**
     * List workflows visible to a user, applying org-level deny-list filtering when applicable.
     * This is the single source of truth for workflow listing - used by both REST controllers and tool modules.
     * Excludes APPLICATION-type workflows (those are listed via {@link #listApplications}).
     */
    public List<WorkflowEntity> listWorkflows(String tenantId, String orgId, String orgRole) {
        // Phase 6 MIGRATION_ORG_ID_NOT_NULL (H-4, 2026-05-19) - post-V261 every
        // workflow row carries a non-null organization_id (personal workspaces
        // resolve to the user's default-personal org). Production traffic
        // always carries X-Organization-ID. The legacy tenant-only fallback
        // (which used findByTenantIdAndWorkflowTypeOrderByUpdatedAtDesc) leaked
        // cross-workspace rows for users belonging to multiple orgs and is now
        // gone. Defensive empty-list for any caller still passing null.
        if (orgId == null || orgId.isBlank()) {
            return java.util.Collections.emptyList();
        }
        List<WorkflowEntity> workflows = workflowRepository.findByOrganizationOrOwnerAndType(
                orgId, tenantId, WorkflowEntity.WorkflowType.WORKFLOW);
        return orgAccessService.filterAccessible(workflows, orgId, tenantId, "workflow", orgRole,
            w -> w.getId().toString());
    }

    /**
     * List APPLICATION-type workflows visible to a user.
     */
    public List<WorkflowEntity> listApplications(String tenantId, String orgId, String orgRole) {
        // Same H-4 contract as listWorkflows above.
        if (orgId == null || orgId.isBlank()) {
            return java.util.Collections.emptyList();
        }
        List<WorkflowEntity> workflows = workflowRepository.findByOrganizationOrOwnerAndType(
                orgId, tenantId, WorkflowEntity.WorkflowType.APPLICATION);
        return orgAccessService.filterAccessible(workflows, orgId, tenantId, "workflow", orgRole,
            w -> w.getId().toString());
    }

    /**
     * Reset an APPLICATION workflow's plan to its basePlan (immutable reference).
     * Only valid for APPLICATION-type workflows that have a basePlan.
     *
     * @param workflowId the workflow UUID
     * @param tenantId the requesting tenant (for ownership check)
     * @return the updated workflow entity
     */
    @org.springframework.transaction.annotation.Transactional
    public WorkflowEntity resetApplicationPlan(UUID workflowId, String tenantId) {
        return resetApplicationPlan(workflowId, tenantId,
                com.apimarketplace.common.web.TenantResolver.currentRequestOrganizationId());
    }

    /**
     * Phase 6c post-audit (2026-05-19) - org-aware overload. The legacy
     * {@code tenantId.equals(workflow.getTenantId())} predicate refused
     * legitimate org-teammate admins from resetting a workspace-shared
     * APPLICATION whose {@code tenant_id} is the original installer's,
     * not the teammate's. Mirrors the strict-scope shape already used by
     * the sibling reset endpoints (status / clone / delete / updatePlan).
     */
    @org.springframework.transaction.annotation.Transactional
    public WorkflowEntity resetApplicationPlan(UUID workflowId, String tenantId, String organizationId) {
        WorkflowEntity workflow = workflowRepository.findById(workflowId)
                .orElseThrow(() -> new IllegalArgumentException("Workflow not found: " + workflowId));

        if (!ScopeGuard.isInStrictScope(
                tenantId, organizationId,
                workflow.getTenantId(), workflow.getOrganizationId())) {
            throw new IllegalArgumentException("Workflow does not belong to caller's active workspace");
        }

        // Org per-resource write-gate: a member restricted to READ/DENY on this APPLICATION cannot
        // reset its plan. reset-plan is one of several gated plan-write routes for an acquired app -
        // alongside the owner-writable PUT /plan (updateWorkflowPlan) and restoreVersion, which run
        // the SAME application gate - so each must enforce the deny-list or a restricted member would
        // have an open plan-write vector. Mirrors the canWrite gate on the sibling status/clone/delete/
        // updatePlan/saveWorkflow endpoints. orgRole comes from the request context (TenantResolver),
        // like the organizationId this method already resolves. OWNER/ADMIN bypass; orgRole=null
        // still enforces member restrictions (never a bypass).
        String entityOrgId = workflow.getOrganizationId();
        if (entityOrgId != null && !orgAccessService.canWrite(entityOrgId, tenantId, "workflow",
                workflowId.toString(),
                com.apimarketplace.common.web.TenantResolver.currentRequestOrganizationRole())) {
            logger.warn("OrgAccess deny-list: user {} restricted from resetting workflow {} in org {}",
                    tenantId, workflowId, entityOrgId);
            throw new com.apimarketplace.auth.client.access.OrgAccessDeniedException(
                    "workflow", workflowId.toString());
        }
        // Acquired APPLICATION instances are ALSO deny-listed by their SOURCE PUBLICATION id under
        // the "application" type (the id /app/applications + publication-service use) - the
        // "workflow" gate above never catches an application restriction. See
        // enforceApplicationWriteGate.
        enforceApplicationWriteGate(workflow, tenantId,
                com.apimarketplace.common.web.TenantResolver.currentRequestOrganizationRole());

        if (workflow.getWorkflowType() != WorkflowEntity.WorkflowType.APPLICATION) {
            throw new IllegalArgumentException("Only APPLICATION workflows can be reset");
        }

        if (workflow.getBasePlan() == null || workflow.getBasePlan().isEmpty()) {
            throw new IllegalArgumentException("Workflow has no base plan to reset from");
        }

        // Deep-copy basePlan to plan. The previous `new HashMap<>(...)` was a
        // SHALLOW copy - the `triggers` list reference was shared with basePlan,
        // so a downstream strip would have mutated basePlan in-place. Use
        // PlanStripUtils.deepCopyAndStrip which round-trips via ObjectMapper
        // (true deep copy) and strips standalone trigger row UUIDs in one pass,
        // preventing the basePlan from leaking scheduleId/webhookId/etc. into
        // the new plan. F4 PUB-HIJACK defense-in-depth.
        Map<String, Object> resetPlan =
                com.apimarketplace.common.plan.PlanStripUtils.deepCopyAndStrip(
                        workflow.getBasePlan(), objectMapper);
        workflow.setPlan(resetPlan);
        workflow.setUpdatedAt(Instant.now());

        logger.info("Reset APPLICATION workflow {} plan from basePlan", workflowId);
        return workflowRepository.save(workflow);
    }

    /**
     * Enforce the org per-member deny-list for an acquired APPLICATION instance, keyed on its
     * SOURCE PUBLICATION id under the {@code "application"} resource type - the exact id
     * {@code /app/applications} (publication-service) and {@code ProjectService} restrict by, and the
     * id the member-access modal writes. The generic {@code "workflow"} gate on the app-instance write
     * paths (reset-plan / delete / status toggle) never catches an application restriction because an
     * acquired app's workflow id is never offered to an admin under {@code "workflow"} (the modal lists
     * only WORKFLOW-type rows there). So a member fully restricted (DENY) from an application could
     * still reset / delete / pause the installed instance without this gate.
     *
     * <p>No-op for plain workflows, in personal scope (no org), or for an app with no source
     * publication. OWNER/ADMIN bypass and {@code orgRole == null} still enforces member restrictions
     * (never a bypass) - both handled inside {@link OrgAccessGuard#canWrite}.
     */
    private void enforceApplicationWriteGate(WorkflowEntity workflow, String tenantId, String orgRole) {
        if (workflow == null || !workflow.isApplication()) {
            return;
        }
        String entityOrgId = workflow.getOrganizationId();
        UUID pubId = workflow.getSourcePublicationId();
        if (entityOrgId == null || pubId == null) {
            return;
        }
        if (!orgAccessService.canWrite(entityOrgId, tenantId, "application", pubId.toString(), orgRole)) {
            logger.warn("OrgAccess deny-list: user {} restricted from application {} (workflow {}) in org {}",
                    tenantId, pubId, workflow.getId(), entityOrgId);
            throw new com.apimarketplace.auth.client.access.OrgAccessDeniedException(
                    "application", pubId.toString());
        }
    }

    /**
     * Authorize an org-scoped write to an acquired APPLICATION instance (the
     * restore-a-prior-plan-version path in {@code WorkflowVersionController}).
     * Enforces only the application-scoped org deny-list - the generic {@code "workflow"}
     * gate the controller runs never catches an application-level restriction (see
     * {@link #enforceApplicationWriteGate}). No-op for plain (non-APPLICATION) workflows;
     * throws {@link com.apimarketplace.auth.client.access.OrgAccessDeniedException} when the
     * caller is restricted (READ/DENY) on this application.
     *
     * <p>Note: the human {@code PUT /api/v2/workflows/dag/{id}/plan} edit path no longer
     * routes through this gate - applications are run-only there (it returns
     * {@code 409 APPLICATION_PLAN_IMMUTABLE}); editing lives in the decoupled WORKFLOW twin.
     */
    public void assertApplicationInstanceWritable(WorkflowEntity workflow, String tenantId, String orgRole) {
        enforceApplicationWriteGate(workflow, tenantId, orgRole);
    }

    /**
     * Résultat de la sauvegarde d'un workflow
     */
    public static class SaveResult {
        private final WorkflowEntity workflow;
        private final boolean isNew;
        private final ValidationResult validation;
        
        public SaveResult(WorkflowEntity workflow, boolean isNew, ValidationResult validation) {
            this.workflow = workflow;
            this.isNew = isNew;
            this.validation = validation;
        }
        
        public WorkflowEntity getWorkflow() { return workflow; }
        public boolean isNew() { return isNew; }
        public ValidationResult getValidation() { return validation; }
    }
    
    /**
     * Parse un plan workflow depuis un JSON
     */
    public WorkflowPlan parsePlan(String planJson) {
        return parsePlanWithTenantId(planJson, null);
    }

    /**
     * Parse un plan workflow depuis un JSON avec injection du tenant_id depuis le header X-User-ID
     */
    public WorkflowPlan parsePlanWithTenantId(String planJson, String userId) {
        try {
            Map<String, Object> planData = objectMapper.readValue(planJson, Map.class);

            // Resolve tenantId: prefer X-User-ID header, fallback to plan's tenant_id
            String tenantId = (userId != null && !userId.isBlank()) ? userId
                    : (String) planData.get("tenant_id");

            return WorkflowPlan.fromMap(planData, userId);
        } catch (Exception e) {
            logger.error("Error parsing workflow plan", e);
            throw new IllegalArgumentException("Invalid workflow plan format: " + e.getMessage(), e);
        }
    }

    /**
     * Détermine ou génère l'ID du workflow
     */
    public UUID resolveWorkflowId(String planId, UUID providedId) {
        if (providedId != null) {
            return providedId;
        }
        
        if (planId != null && !planId.isBlank()) {
            try {
                return UUID.fromString(planId);
            } catch (IllegalArgumentException e) {
                logger.warn("Invalid workflow ID format in plan: {}, generating new UUID", planId);
            }
        }
        
        return UUID.randomUUID();
    }
    
    /**
     * Sauvegarde un workflow (création ou mise à jour)
     * 
     * @param plan Le plan workflow à sauvegarder
     * @param dataInputs Les données d'entrée
     * @param workflowId ID optionnel (si null, utilise plan.getId() ou génère un nouveau)
     * @return SaveResult avec le workflow sauvegardé et les métadonnées
     */
    public SaveResult saveWorkflow(WorkflowPlan plan, Map<String, Object> dataInputs, UUID workflowId) {
        return saveWorkflow(plan, dataInputs, workflowId, null);
    }

    /**
     * REST save path WITH the org per-resource write-gate. For an EXISTING org workflow a
     * member restricted to READ (or DENY) on it cannot persist changes - a NEW workflow has
     * no resource to restrict yet, so the gate applies only on update. OWNER/ADMIN bypass and
     * {@code orgRole == null} still enforces member restrictions (it is never a bypass). The
     * agent finish/save path keeps using the {@code orgRole}-less 4-arg overload below (gated
     * separately by ToolAccessControl), so its behaviour is unchanged. Symmetric with the
     * canWrite guards on delete/clone/status and the PUT /workflows/{id}/plan endpoint.
     */
    public SaveResult saveWorkflow(WorkflowPlan plan, Map<String, Object> dataInputs, UUID workflowId,
                                   String organizationId, String orgRole) {
        UUID finalWorkflowId = resolveWorkflowId(plan.getId(), workflowId);
        workflowRepository.findById(finalWorkflowId).ifPresent(existing -> {
            String entityOrgId = existing.getOrganizationId();
            if (entityOrgId != null
                    && !orgAccessService.canWrite(entityOrgId, plan.getTenantId(), "workflow",
                            finalWorkflowId.toString(), orgRole)) {
                logger.warn("OrgAccess deny-list: user {} restricted from writing workflow {} in org {}",
                        plan.getTenantId(), finalWorkflowId, entityOrgId);
                throw new com.apimarketplace.auth.client.access.OrgAccessDeniedException(
                        "workflow", finalWorkflowId.toString());
            }
        });
        return saveWorkflow(plan, dataInputs, workflowId, organizationId);
    }

    public SaveResult saveWorkflow(WorkflowPlan plan, Map<String, Object> dataInputs, UUID workflowId, String organizationId) {
        // 1. Résoudre l'ID du workflow
        UUID finalWorkflowId = resolveWorkflowId(plan.getId(), workflowId);

        // 2. Vérifier si le workflow existe déjà
        Optional<WorkflowEntity> existingOpt = workflowRepository.findById(finalWorkflowId);
        boolean isNew = existingOpt.isEmpty();

        // 2b. Plan resource limit - applies only to new workflows.
        // Same enforcement point for REST controller AND shared-agent-lib tools
        // (WorkflowBuilderProvider calls this method directly). LimitExceededException
        // is mapped to HTTP 409 by the global handler in auth-client and to a "DO NOT
        // RETRY" tool result by the per-tool catch in shared-agent-lib.
        if (isNew && plan.getTenantId() != null && entitlementGuard != null) {
            String tenantId = plan.getTenantId();
            // PR30b - scope-aware quota count so org and personal quotas are
            // independent. Pre-PR30b the count conflated org + personal,
            // so creating workflows in an org pushed the personal quota
            // toward its limit (and vice versa).
            // Post-V261 (2026-05-19): every user-scoped row carries a non-null
            // organization_id (personal workspaces use the user's default
            // personal org). The blank-orgId branch is gone - the strict-org
            // count covers personal workspaces too.
            final String orgIdForCount = organizationId;
            entitlementGuard.check(tenantId,
                    com.apimarketplace.auth.client.entitlement.ResourceType.WORKFLOW,
                    () -> workflowRepository.countByOrganizationIdStrict(orgIdForCount));
        }

        WorkflowEntity workflow;
        if (isNew) {
            workflow = createNewWorkflow(plan, finalWorkflowId);
            // Match saveDraft's defensive guard - reject blank strings the same way
            // so a malformed caller cannot land "" into the column.
            if (organizationId != null && !organizationId.isBlank()) {
                workflow.setOrganizationId(organizationId);
            }
            logger.info("📝 Creating new workflow: {}", finalWorkflowId);
        } else {
            // APPLICATION-type workflows are frozen acquired marketplace clones - the
            // plan is the contract the acquirer received. Block in-place mutation here,
            // the single chokepoint shared by POST /api/v2/workflows/dag (REST direct),
            // the agent finish/save path for existing workflows, and any future caller
            // of this service method. Reset-plan is the sanctioned restore route - it
            // bypasses this method and operates on basePlan directly. Symmetric with
            // the PUT /workflows/{id}/plan and InterfaceService.updateInterface guards.
            WorkflowEntity existing = existingOpt.get();
            if (existing.isApplication()) {
                logger.warn("Refused saveWorkflow on APPLICATION workflow {}: applications are immutable acquired clones",
                        finalWorkflowId);
                throw new com.apimarketplace.orchestrator.exception.ApplicationPlanImmutableException(finalWorkflowId);
            }
            workflow = updateExistingWorkflow(existing, plan);
            logger.info("📝 Updating existing workflow: {}", finalWorkflowId);
        }
        
        // 4. Mettre à jour le plan et les data inputs
        workflow.setPlan(plan.getOriginalPlan());
        workflow.setNodeIcons(WorkflowIconExtractor.extractNodeIcons(plan.getOriginalPlan()));
        workflow.setDataInputs(dataInputs != null ? new HashMap<>(dataInputs) : new HashMap<>());

        // 4.5 Sync webhook token: only for unpinned workflows (pre-pin backward compat).
        // When pinned, PinAwareTriggerSyncService handles webhook cleanup from the pinned plan.
        if (workflow.getPinnedVersion() == null) {
            syncWebhookToken(workflow, plan, finalWorkflowId);
        }

        // 5. Sauvegarder en base
        long oldSize = isNew ? 0 : estimateWorkflowSize(existingOpt.get());
        WorkflowEntity saved = workflowRepository.save(workflow);
        long newSize = estimateWorkflowSize(saved);

        // 5b. Track storage breakdown - Issue #149: thread organizationId so the
        // workspace storage card sees CONFIGURATION usage growing in TEAM scope too.
        if (isNew) {
            breakdownService.trackSave(saved.getTenantId(), "CONFIGURATION", newSize, saved.getOrganizationId());
        } else {
            breakdownService.trackSizeChange(saved.getTenantId(), "CONFIGURATION", newSize - oldSize, saved.getOrganizationId());
        }

        // 6. Sync webhook index
        if (webhookIndexService != null) {
            try {
                webhookIndexService.syncForWorkflow(saved);
            } catch (Exception e) {
                logger.warn("Failed to sync webhook index for workflow {}: {}", finalWorkflowId, e.getMessage());
            }
        }

        // 8. Pin-aware trigger sync (schedule, chat, form) - only syncs from pinned version if pinned
        syncAllTriggersForWorkflow(saved, plan);

        logger.info("✅ Workflow saved successfully: {} (new: {})", finalWorkflowId, isNew);

        // Legacy validation system removed - always returns valid
        ValidationResult validation = new ValidationResult(true, List.of(), List.of(), 0);
        return new SaveResult(saved, isNew, validation);
    }

    /**
     * Legacy 3-arg overload - routes to the org-aware form with {@code organizationId=null}.
     * Personal-scope drafts only. Callers in org workspace MUST use the 4-arg form so the
     * draft row carries {@code organization_id} matching the active workspace; otherwise
     * the draft lands invisible in org-scoped list endpoints (ActiveAutomationsService,
     * WorkflowListController, etc).
     */
    public WorkflowEntity saveDraft(Map<String, Object> planMap, String tenantId, UUID workflowId) {
        return saveDraft(planMap, tenantId, workflowId, null);
    }

    /**
     * Save a workflow as a draft without full validation.
     * Used for auto-draft functionality to preserve work-in-progress workflows.
     * Only requires basic validation (name must exist).
     *
     * @param planMap The raw plan map (may be incomplete)
     * @param tenantId The tenant ID
     * @param workflowId Optional workflow ID (for updating existing draft)
     * @param organizationId Active workspace org id (null in personal scope). Stamped on
     *                       new draft rows so list endpoints filtering on
     *                       {@code organization_id = :orgId} surface them. On update,
     *                       the existing row's organization_id is preserved as-is.
     * @return The saved workflow entity
     */
    public WorkflowEntity saveDraft(Map<String, Object> planMap, String tenantId, UUID workflowId,
                                     String organizationId) {
        UUID finalWorkflowId = workflowId != null ? workflowId : UUID.randomUUID();

        // Basic validation only - name must exist
        String name = (String) planMap.get("name");
        if (name == null || name.isBlank()) {
            name = "Draft " + finalWorkflowId.toString().substring(0, 8);
            planMap.put("name", name);
        }

        // Ensure tenant_id is set
        planMap.put("tenant_id", tenantId);

        Optional<WorkflowEntity> existingOpt = workflowRepository.findById(finalWorkflowId);
        boolean isNew = existingOpt.isEmpty();

        WorkflowEntity workflow;
        Instant now = Instant.now();

        if (isNew) {
            workflow = new WorkflowEntity();
            workflow.setId(finalWorkflowId);
            workflow.setTenantId(tenantId);
            if (organizationId != null && !organizationId.isBlank()) {
                workflow.setOrganizationId(organizationId);
            }
            workflow.setCreatedAt(now);
            logger.info("📝 Creating new draft workflow: {} (orgScope={})", finalWorkflowId,
                    (organizationId != null && !organizationId.isBlank()));
        } else {
            workflow = existingOpt.get();
            // Defense-in-depth: the WorkflowBuilderProvider PLAN_MUTATING_ACTIONS gate
            // already short-circuits agent mutations on APPLICATION workflows, so the
            // autosave path (WorkflowDraftAutoSaver → saveDraft) cannot hit this branch
            // through a successful modify on an APPLICATION. But read_rows/find_rows do
            // trigger autoSaveDraft (per MODIFYING_ACTIONS auto-save trigger set), so
            // without this guard a pure-read on an APPLICATION would still call
            // workflow.setPlan() below and bump updated_at on a frozen entity. Symmetric
            // with the saveWorkflow guard at L297-310.
            if (workflow.isApplication()) {
                logger.warn("Refused saveDraft on APPLICATION workflow {}: applications are immutable acquired clones",
                        finalWorkflowId);
                throw new com.apimarketplace.orchestrator.exception.ApplicationPlanImmutableException(finalWorkflowId);
            }
            logger.info("📝 Updating existing draft workflow: {}", finalWorkflowId);
        }

        workflow.setName(name);
        // For new workflows, set ACTIVE status immediately (DRAFT system removed).
        // For existing workflows, preserve the current status.
        if (isNew) {
            workflow.setStatus(WorkflowEntity.WorkflowStatus.ACTIVE);
            workflow.setIsActive(true);
        }
        workflow.setUpdatedAt(now);
        workflow.setPlan(planMap);
        workflow.setNodeIcons(WorkflowIconExtractor.extractNodeIcons(planMap));

        Object description = planMap.get("description");
        if (description instanceof String) {
            workflow.setDescription((String) description);
        }

        // Set metadata
        Map<String, Object> metadata = workflow.getMetadata();
        if (metadata == null) {
            metadata = new HashMap<>();
        }
        metadata.put("draftSavedAt", now.toString());
        metadata.put("isDraft", true);
        workflow.setMetadata(metadata);

        long oldSize = isNew ? 0 : estimateWorkflowSize(existingOpt.get());
        WorkflowEntity saved = workflowRepository.save(workflow);
        long newSize = estimateWorkflowSize(saved);

        // Track storage breakdown - Issue #149: read the post-save org so the
        // workspace storage card reflects the actual scope (new drafts inherit
        // the org we just stamped; updated drafts keep the existing one).
        String orgIdForDraft = saved.getOrganizationId();
        if (isNew) {
            breakdownService.trackSave(tenantId, "CONFIGURATION", newSize, orgIdForDraft);
        } else {
            breakdownService.trackSizeChange(tenantId, "CONFIGURATION", newSize - oldSize, orgIdForDraft);
        }

        logger.info("✅ Draft workflow saved: {} (new: {})", finalWorkflowId, isNew);

        return saved;
    }

    /**
     * Crée un nouveau workflow
     */
    private WorkflowEntity createNewWorkflow(WorkflowPlan plan, UUID workflowId) {
        WorkflowEntity workflow = new WorkflowEntity();
        workflow.setId(workflowId);
        workflow.setTenantId(plan.getTenantId());
        workflow.setStatus(WorkflowEntity.WorkflowStatus.ACTIVE);
        workflow.setIsActive(true);

        Instant now = Instant.now();
        workflow.setCreatedAt(now);
        workflow.setUpdatedAt(now);
        
        Map<String, Object> rawPlan = new HashMap<>(plan.getOriginalPlan());
        String planName = rawPlan.getOrDefault("name", "").toString();
        String defaultName = "Workflow " + workflowId.toString().substring(0, 8);
        workflow.setName(planName != null && !planName.isBlank() ? planName : defaultName);
        
        Object description = rawPlan.get("description");
        workflow.setDescription(description instanceof String ? (String) description : null);
        
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("savedAt", now.toString());
        metadata.put("source", "api");
        if (rawPlan.containsKey("original_id")) {
            metadata.put("original_plan_id", rawPlan.get("original_id"));
        }
        // Preserve metadata from the plan (e.g., isAutoDraft for auto-created workflows)
        if (rawPlan.containsKey("metadata") && rawPlan.get("metadata") instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> planMetadata = (Map<String, Object>) rawPlan.get("metadata");
            metadata.putAll(planMetadata);
        }
        workflow.setMetadata(metadata);

        return workflow;
    }
    
    /**
     * Met à jour un workflow existant
     */
    private WorkflowEntity updateExistingWorkflow(WorkflowEntity workflow, WorkflowPlan plan) {
        workflow.setUpdatedAt(Instant.now());

        Map<String, Object> rawPlan = new HashMap<>(plan.getOriginalPlan());
        String planName = rawPlan.getOrDefault("name", "").toString();
        if (planName != null && !planName.isBlank()) {
            workflow.setName(planName);
        }

        Object description = rawPlan.get("description");
        if (description instanceof String) {
            workflow.setDescription((String) description);
        }

        // Status is now controlled explicitly via the toggle endpoint.
        // Save preserves the current status (DRAFT or ACTIVE).

        // Update metadata
        Map<String, Object> metadata = workflow.getMetadata();
        if (metadata == null) {
            metadata = new HashMap<>();
        }
        metadata.put("lastSavedAt", Instant.now().toString());
        workflow.setMetadata(metadata);

        return workflow;
    }
    
    /**
     * Construit une réponse de sauvegarde standardisée
     */
    public Map<String, Object> buildSaveResponse(SaveResult result) {
        WorkflowEntity workflow = result.getWorkflow();
        ValidationResult validation = result.getValidation();

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", result.isNew() ? "Workflow created successfully" : "Workflow updated successfully");
        response.put("workflowId", workflow.getId().toString());
        response.put("name", workflow.getName());
        response.put("status", workflow.getStatus().toString());
        response.put("isNew", result.isNew());
        response.put("createdAt", workflow.getCreatedAt());
        response.put("updatedAt", workflow.getUpdatedAt());
        response.put("validation", Map.of(
            "isValid", validation.isValid(),
            "errorsCount", validation.getErrors().size(),
            "warningsCount", validation.getWarnings().size(),
            "complexityScore", validation.getComplexityScore()
        ));

        // Include webhook tokens (multi-DAG support)
        Map<String, String> webhookTokens = triggerClient.getTokensForWorkflow(workflow.getId());
        if (!webhookTokens.isEmpty()) {
            response.put("webhookTokens", webhookTokens);
        }

        return response;
    }
    
    /**
     * Updates the lifecycle status of a workflow (DRAFT <-> ACTIVE toggle).
     * Syncs the isActive flag accordingly.
     *
     * @param workflowId the workflow UUID
     * @param tenantId the requesting tenant (for ownership check)
     * @param newStatus the target status (DRAFT or ACTIVE)
     * @return the updated workflow entity
     * @throws IllegalArgumentException if the status is not DRAFT or ACTIVE
     * @throws IllegalStateException if the workflow is not found or not owned by the tenant
     */
    /**
     * Backward-compat overload - see 3-arg variant. orgRole=null → strict
     * MEMBER semantics (no admin bypass). HTTP callers MUST use the 3-arg
     * overload with the gateway-validated X-Organization-Role header.
     */
    public WorkflowEntity updateWorkflowStatus(UUID workflowId, String tenantId, WorkflowEntity.WorkflowStatus newStatus) {
        return updateWorkflowStatus(workflowId, tenantId, newStatus, null);
    }

    public WorkflowEntity updateWorkflowStatus(UUID workflowId, String tenantId,
                                                WorkflowEntity.WorkflowStatus newStatus, String orgRole) {
        if (newStatus != WorkflowEntity.WorkflowStatus.DRAFT && newStatus != WorkflowEntity.WorkflowStatus.ACTIVE) {
            throw new IllegalArgumentException("Only DRAFT and ACTIVE status transitions are allowed");
        }

        Optional<WorkflowEntity> workflowOpt = workflowRepository.findById(workflowId);
        if (workflowOpt.isEmpty()) {
            throw new IllegalStateException("Workflow not found: " + workflowId);
        }

        WorkflowEntity workflow = workflowOpt.get();
        String orgId = TenantResolver.currentRequestOrganizationId();
        if (!ScopeGuard.isInStrictScope(tenantId, orgId, workflow.getTenantId(), workflow.getOrganizationId())) {
            throw new IllegalStateException("Unauthorized: workflow does not belong to tenant");
        }

        // PR-2.c: org-scoped deny-list enforcement on status toggle.
        String entityOrgId = workflow.getOrganizationId();
        if (entityOrgId != null
                && !orgAccessService.canWrite(entityOrgId, tenantId, "workflow", workflowId.toString(), orgRole)) {
            logger.warn("OrgAccess denied: user {} restricted from workflow {} status toggle in org {}",
                    tenantId, workflowId, entityOrgId);
            throw new com.apimarketplace.auth.client.access.OrgAccessDeniedException(
                    "workflow", workflowId.toString());
        }
        // Acquired APPLICATION instances: ALSO gate on the "application" deny-list (source
        // publication id). See enforceApplicationWriteGate.
        enforceApplicationWriteGate(workflow, tenantId, orgRole);

        workflow.setStatus(newStatus);
        workflow.setIsActive(newStatus == WorkflowEntity.WorkflowStatus.ACTIVE);
        workflow.setUpdatedAt(Instant.now());
        logger.info("Workflow {} status toggled to {}", workflowId, newStatus);

        return workflowRepository.save(workflow);
    }

    // ===== GESTION DES INSTANCES (EXECUTIONS) =====

    /**
     * Récupère un workflow par son ID
     */
    public Optional<WorkflowEntity> getWorkflow(UUID workflowId) {
        return workflowRepository.findById(workflowId);
    }

    /**
     * Supprime un workflow par son ID
     * Vérifie que le workflow appartient au tenant spécifié
     */
    @Transactional
    /**
     * Backward-compat overload. Equivalent to {@link #deleteWorkflow(UUID, String, String)}
     * with {@code orgRole = null} - the org-scoped deny-list check runs with
     * non-admin semantics (member treated as MEMBER, restrictions enforced).
     * Used by internal callers (tool modules, deletion cascade) that do not
     * carry an explicit role context. HTTP callers must use the 3-arg overload
     * with the gateway-validated {@code X-Organization-Role} header.
     *
     * <p><b>Phase A trade-off (PR-1.h follow-up):</b> the {@code orgRole}
     * argument is currently trusted from the gateway-validated header. A user
     * who is a member of multiple orgs but interacting with a non-default org
     * could see the wrong role enforced. PR-1.h will swap to a DB-backed
     * lookup of {@code member(orgId, userId)} so the role is resolved
     * server-side per request. Until then, the default-role-in-default-org
     * behaviour is acceptable because the deny-list applies <em>per-resource
     * within a single org</em>, and the gateway already pins the (userId,
     * defaultOrgId) pair from the JWT.
     */
    public boolean deleteWorkflow(UUID workflowId, String tenantId) {
        return deleteWorkflow(workflowId, tenantId, null);
    }

    @Transactional
    public boolean deleteWorkflow(UUID workflowId, String tenantId, String orgRole) {
        Optional<WorkflowEntity> workflowOpt = workflowRepository.findById(workflowId);
        if (workflowOpt.isEmpty()) {
            logger.warn("Workflow not found for deletion: {}", workflowId);
            return false;
        }

        WorkflowEntity workflow = workflowOpt.get();

        // Vérifier que le workflow appartient au tenant
        String orgId = TenantResolver.currentRequestOrganizationId();
        if (!ScopeGuard.isInStrictScope(tenantId, orgId, workflow.getTenantId(), workflow.getOrganizationId())) {
            logger.warn("Workflow {} does not belong to tenant {}", workflowId, tenantId);
            return false;
        }

        // PR-2 (V2 fix): org-scoped deny-list enforcement on write.
        // Post-V261 (2026-05-19) every user-scoped workflow row carries a
        // non-null organization_id (personal workspaces resolve to the user's
        // default personal org), so this gate fires uniformly. OWNER/ADMIN
        // bypass via OrgAccessGuard contract.
        String entityOrgId = workflow.getOrganizationId();
        if (entityOrgId != null
                && !orgAccessService.canWrite(entityOrgId, tenantId, "workflow", workflowId.toString(), orgRole)) {
            logger.warn("OrgAccess denied: user {} restricted from workflow {} in org {}",
                    tenantId, workflowId, entityOrgId);
            throw new com.apimarketplace.auth.client.access.OrgAccessDeniedException(
                    "workflow", workflowId.toString());
        }
        // Acquired APPLICATION instances: ALSO gate on the "application" deny-list (source
        // publication id). See enforceApplicationWriteGate.
        enforceApplicationWriteGate(workflow, tenantId, orgRole);

        // Remove webhook tokens from index before deletion
        if (webhookIndexService != null) {
            try {
                webhookIndexService.removeAllForWorkflow(workflowId.toString());
            } catch (Exception e) {
                logger.warn("Failed to remove webhook index for workflow {}: {}", workflowId, e.getMessage());
            }
        }

        // v5: archive (don't hard-delete) every standalone trigger row owned by the workflow.
        // Routes through trigger-service's TriggerLifecycleManager.archive* so the deletion
        // event is recorded in trigger_state_audit_log with reason WORKFLOW_DELETED + source
        // DELETION, and execution_count / token / endpoint history survive for forensic /
        // replay purposes. Archived rows are not currently auto-reaped - they persist until
        // manual cleanup or a future ARCHIVED-row reaper. (The existing
        // StandaloneTriggerReaperService sweeps only workflow_id IS NULL orphans, not ARCHIVED.)
        //
        // Per-type independent try/catch: TriggerClient.archive*ByWorkflow already swallows
        // transport-level exceptions and returns 0, but an unchecked from URL construction
        // or a wired null would propagate. Isolating each call prevents one runtime fault
        // from silently skipping the other three trigger types (Audit-B SHOULD-FIX).
        int s = 0, w = 0, c = 0, f = 0;
        try { s = triggerClient.archiveSchedulesByWorkflow(workflowId, "WORKFLOW_DELETED"); }
        catch (Exception e) { logger.warn("Archive schedules failed for workflow {}: {}", workflowId, e.getMessage()); }
        try { w = triggerClient.archiveWebhooksByWorkflow(workflowId, "WORKFLOW_DELETED"); }
        catch (Exception e) { logger.warn("Archive webhooks failed for workflow {}: {}", workflowId, e.getMessage()); }
        try { c = triggerClient.archiveChatEndpointsByWorkflow(workflowId, "WORKFLOW_DELETED"); }
        catch (Exception e) { logger.warn("Archive chat endpoints failed for workflow {}: {}", workflowId, e.getMessage()); }
        try { f = triggerClient.archiveFormEndpointsByWorkflow(workflowId, "WORKFLOW_DELETED"); }
        catch (Exception e) { logger.warn("Archive form endpoints failed for workflow {}: {}", workflowId, e.getMessage()); }
        logger.info("Archived triggers for workflow {}: schedules={}, webhooks={}, chats={}, forms={}",
                workflowId, s, w, c, f);

        // Remove datasource trigger subscriptions (fan-out registry in trigger-service)
        try {
            triggerClient.deleteDatasourceSubscriptionsByWorkflow(workflowId);
        } catch (Exception e) {
            logger.warn("Failed to remove datasource subscriptions for workflow {}: {}", workflowId, e.getMessage());
        }

        // Delete associated conversations (soft delete). Forward the WORKFLOW's own org
        // (same class of bug as the agent cascade): the 2-arg overload defaults orgId=null,
        // which makes the conversation-service strict-scope gate skip every org-tagged
        // conversation → orphaned, still-active rows after the workflow is gone. This runs
        // outside a servlet context for some callers, so pass the org explicitly.
        if (conversationServiceClient != null) {
            try {
                conversationServiceClient.deleteConversationsByWorkflowId(
                        workflowId.toString(), tenantId, workflow.getOrganizationId());
            } catch (Exception e) {
                logger.warn("Failed to delete conversations for workflow {}: {}", workflowId, e.getMessage());
            }
        }

        // Deactivate publication if exists (keep planSnapshot for re-acquisition by existing acquirers)
        try {
            publicationClient.unpublishByWorkflowId(workflowId, tenantId);
        } catch (Exception e) {
            logger.warn("Failed to cleanup publication for workflow {}: {}", workflowId, e.getMessage());
        }

        // Clean up run-related data that has no FK cascade
        cleanupWorkflowRunData(workflowId, tenantId);

        // Track storage breakdown before deletion - Issue #149: thread orgId.
        long deletedSize = estimateWorkflowSize(workflow);
        breakdownService.trackDelete(tenantId, "CONFIGURATION", deletedSize, workflow.getOrganizationId());

        workflowRepository.deleteById(workflowId);
        logger.info("Workflow deleted successfully: {}", workflowId);
        return true;
    }

    /**
     * Clean up all run-related data for a workflow that has no FK cascade.
     * Must be called BEFORE workflowRepository.deleteById() since we need run IDs.
     */
    private void cleanupWorkflowRunData(UUID workflowId, String tenantId) {
        List<WorkflowRunEntity> runs = workflowRunRepository.findByWorkflowIdOrderByStartedAtDesc(workflowId);
        if (runs.isEmpty()) {
            logger.debug("No runs to clean up for workflow {}", workflowId);
            return;
        }

        List<String> runIds = runs.stream()
                .map(WorkflowRunEntity::getRunIdPublic)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toList());

        // Detach loaded runs so their @ManyToOne workflow proxies aren't cascade-checked
        // at commit flush against the DELETED workflow (would throw TransientObjectException).
        // FK has ON DELETE CASCADE at the DB level, so workflow_runs rows are removed by Postgres.
        runs.forEach(entityManager::detach);

        logger.info("Cleaning up {} runs for workflow {}", runIds.size(), workflowId);

        // EXECUTION_DATA tracked by daily reconciliation only (incremental tracking
        // causes negative drift because state_snapshot grows during execution).
        // TODO(storage-drift): see WorkflowRunPersistenceService:102 +
        // StateSnapshotService:2133 - same source-fix obligation.

        // 1. Soft-delete storage entries (DB rows in storage.storage)
        try {
            int softDeleted = storageRepository.softDeleteByWorkflowId(workflowId.toString());
            if (softDeleted > 0) {
                logger.info("Soft-deleted {} storage entries for workflow {}", softDeleted, workflowId);
            }
        } catch (Exception e) {
            logger.warn("Failed to soft-delete storage entries for workflow {}: {}", workflowId, e.getMessage());
        }

        // 2. Delete S3 files for each run
        for (String runId : runIds) {
            try {
                int deleted = fileStorageService.deleteRunFiles(tenantId, workflowId.toString(), runId);
                if (deleted > 0) {
                    logger.debug("Deleted {} S3 files for run {}", deleted, runId);
                }
            } catch (Exception e) {
                logger.warn("Failed to delete S3 files for run {}: {}", runId, e.getMessage());
            }
        }

        // 3. Delete workflow_epochs (no FK, JdbcTemplate-based)
        try {
            int deletedEpochs = workflowEpochRepository.deleteByRunIds(runIds);
            if (deletedEpochs > 0) {
                logger.info("Deleted {} epoch rows for workflow {}", deletedEpochs, workflowId);
            }
        } catch (Exception e) {
            logger.warn("Failed to delete epochs for workflow {}: {}", workflowId, e.getMessage());
        }

        // 4. Delete workflow_signal_waits (no FK)
        try {
            int deletedSignals = signalWaitRepository.deleteByRunIdIn(runIds);
            if (deletedSignals > 0) {
                logger.info("Deleted {} signal waits for workflow {}", deletedSignals, workflowId);
            }
        } catch (Exception e) {
            logger.warn("Failed to delete signal waits for workflow {}: {}", workflowId, e.getMessage());
        }

        // 5. Delete workflow_pending_signals (no FK)
        try {
            int deletedPending = pendingSignalRepository.deleteByRunIdIn(runIds);
            if (deletedPending > 0) {
                logger.info("Deleted {} pending signals for workflow {}", deletedPending, workflowId);
            }
        } catch (Exception e) {
            logger.warn("Failed to delete pending signals for workflow {}: {}", workflowId, e.getMessage());
        }
    }

    /**
     * Récupère une instance spécifique par son runId
     */
    public Optional<WorkflowRunEntity> getInstance(String runId) {
        return workflowRunRepository.findByRunIdPublic(runId);
    }
    
    /**
     * Liste les instances récentes d'un workflow (limité)
     */
    public List<WorkflowRunEntity> getRecentInstances(UUID workflowId, int limit) {
        return workflowRunRepository.findByWorkflowIdOrderByStartedAtDescPageable(
            workflowId, PageRequest.of(0, limit)
        ).getContent();
    }

    /**
     * Check if a workflow plan has at least one webhook trigger.
     * Form triggers are internal-only and do not use the public token mechanism.
     */
    private boolean hasWebhookTrigger(WorkflowPlan plan) {
        if (plan == null || plan.getTriggers() == null) {
            return false;
        }
        return plan.getTriggers().stream()
            .anyMatch(t -> "webhook".equals(t.type()));
    }

    /**
     * Check if a workflow plan has at least one schedule trigger
     */
    private boolean hasScheduleTrigger(WorkflowPlan plan) {
        if (plan == null || plan.getTriggers() == null) {
            return false;
        }
        return plan.getTriggers().stream()
            .anyMatch(t -> "schedule".equalsIgnoreCase(t.type()));
    }

    /**
     * Pin-aware trigger sync for ALL trigger types (schedule, webhook, chat, form).
     *
     * <p>If a pinned version exists, syncs from the pinned version's plan (production).
     * Draft saves never change the production triggers. If no version is pinned,
     * falls back to the provided plan (backward compat for pre-pin workflows).
     */
    private void syncAllTriggersForWorkflow(WorkflowEntity workflow, WorkflowPlan plan) {
        try {
            pinAwareTriggerSyncService.syncAllTriggersFromPlan(workflow, plan);
        } catch (Exception e) {
            logger.warn("Failed to sync triggers for workflow {}: {}", workflow.getId(), e.getMessage());
        }
    }

    /**
     * Clone a workflow by creating a copy with a new UUID.
     * Copies: name (with " (Copy)" suffix), description, plan, dataInputs, tags, metadata, nodeIcons.
     * Does NOT copy: workflowType, basePlan, webhookToken, schedule, sourcePublicationId, acquiredAt, lastExecutedAt.
     * Clones are always created as WORKFLOW type (not APPLICATION).
     */
    @org.springframework.transaction.annotation.Transactional
    public WorkflowEntity cloneWorkflow(UUID sourceId, String tenantId) {
        return cloneWorkflow(sourceId, tenantId, null);
    }

    @org.springframework.transaction.annotation.Transactional
    public WorkflowEntity cloneWorkflow(UUID sourceId, String tenantId, String orgRole) {
        WorkflowEntity source = workflowRepository.findById(sourceId)
            .orElseThrow(() -> new IllegalArgumentException("Workflow not found: " + sourceId));
        String orgId = TenantResolver.currentRequestOrganizationId();
        if (!ScopeGuard.isInStrictScope(tenantId, orgId, source.getTenantId(), source.getOrganizationId())) {
            throw new IllegalArgumentException("Workflow tenant mismatch");
        }
        if (source.isApplication()) {
            throw new IllegalArgumentException("Cannot clone APPLICATION workflows. Use reset-plan to restore the original plan.");
        }

        // PR-2.c: org-scoped deny-list enforcement on clone (a restricted member
        // shouldn't be able to fork a workflow they can't see).
        String entityOrgId = source.getOrganizationId();
        if (entityOrgId != null
                && !orgAccessService.canWrite(entityOrgId, tenantId, "workflow", sourceId.toString(), orgRole)) {
            logger.warn("OrgAccess denied: user {} restricted from cloning workflow {} in org {}",
                    tenantId, sourceId, entityOrgId);
            throw new com.apimarketplace.auth.client.access.OrgAccessDeniedException(
                    "workflow", sourceId.toString());
        }

        WorkflowEntity clone = new WorkflowEntity(tenantId, source.getName() + " (Copy)", tenantId);
        clone.setId(UUID.randomUUID());
        clone.setDescription(source.getDescription());
        // F4 PUB-HIJACK fix: deep copy + strip standalone trigger row UUIDs so
        // the duplicate workflow creates fresh standalone rows on first sync
        // instead of inheriting scheduleId/webhookId/etc. that point at the
        // source's rows (which would be hijacked or rejected by the DB
        // immutability trigger).
        clone.setPlan(com.apimarketplace.common.plan.PlanStripUtils.deepCopyAndStrip(
                source.getPlan(), objectMapper));
        clone.setDataInputs(source.getDataInputs() != null ? new HashMap<>(source.getDataInputs()) : null);
        clone.setTags(source.getTags() != null ? new ArrayList<>(source.getTags()) : null);
        clone.setMetadata(source.getMetadata() != null ? new HashMap<>(source.getMetadata()) : null);
        clone.setNodeIcons(source.getNodeIcons() != null ? new ArrayList<>(source.getNodeIcons()) : null);
        // DO NOT copy: webhookToken, schedule, sourcePublicationId, acquiredAt, lastExecutedAt
        WorkflowEntity savedClone = workflowRepository.save(clone);
        // Issue #149 - clones land in the cloner's personal scope (org_id remains
        // unset on the clone), so the org rollup is intentionally not touched.
        breakdownService.trackSave(tenantId, "CONFIGURATION", estimateWorkflowSize(savedClone), savedClone.getOrganizationId());
        return savedClone;
    }

    /**
     * Cleanup orphan webhook tokens for a workflow.
     * All new webhook triggers use standalone webhooks (webhookId in params).
     * This only cleans up legacy webhook_tokens entries from triggers that were removed.
     */
    private void syncWebhookToken(WorkflowEntity workflow, WorkflowPlan plan, UUID workflowId) {
        List<String> currentTriggerIds = new ArrayList<>();
        if (plan != null && plan.getTriggers() != null) {
            for (Trigger trigger : plan.getTriggers()) {
                if ("webhook".equals(trigger.type())) {
                    currentTriggerIds.add(trigger.getNormalizedKey());
                }
            }
        }

        // Cleanup orphan tokens via trigger-service
        try {
            triggerClient.cleanupOrphanTokens(workflowId, currentTriggerIds);
        } catch (Exception e) {
            logger.warn("Failed to cleanup orphan webhook tokens for workflow {}: {}", workflowId, e.getMessage());
        }
    }

    // syncChatFormTriggerIds removed - now handled by PinAwareTriggerSyncService

    /**
     * Estimate the storage size of a workflow entity (plan + dataInputs as JSONB).
     */
    private long estimateWorkflowSize(WorkflowEntity workflow) {
        long size = 0;
        try {
            if (workflow.getPlan() != null) {
                size += objectMapper.writeValueAsBytes(workflow.getPlan()).length;
            }
            if (workflow.getDataInputs() != null) {
                size += objectMapper.writeValueAsBytes(workflow.getDataInputs()).length;
            }
        } catch (Exception e) {
            logger.debug("Failed to estimate workflow size: {}", e.getMessage());
        }
        return size;
    }

    /**
     * Estimates the size of a workflow run's EXECUTION_DATA columns
     * (state_snapshot, plan, trigger_payload, metadata) in bytes.
     * Matches the columns measured by StorageReconciliationQueries.EXECUTION_DATA.
     */
    private long estimateRunExecutionDataSize(WorkflowRunEntity run) {
        long size = 0;
        try {
            if (run.getStateSnapshot() != null) {
                size += run.getStateSnapshot().getBytes(StandardCharsets.UTF_8).length;
            }
            if (run.getPlan() != null) {
                size += objectMapper.writeValueAsBytes(run.getPlan()).length;
            }
            if (run.getTriggerPayload() != null) {
                size += objectMapper.writeValueAsBytes(run.getTriggerPayload()).length;
            }
            if (run.getMetadata() != null) {
                size += objectMapper.writeValueAsBytes(run.getMetadata()).length;
            }
        } catch (Exception e) {
            logger.debug("Failed to estimate run execution data size for run {}: {}",
                    run.getRunIdPublic(), e.getMessage());
        }
        return size;
    }
}

