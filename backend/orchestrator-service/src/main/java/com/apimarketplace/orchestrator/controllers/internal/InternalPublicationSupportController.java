package com.apimarketplace.orchestrator.controllers.internal;

import com.apimarketplace.common.scope.ScopeGuard;
import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.repository.WorkflowCategoryRepository;
import com.apimarketplace.orchestrator.repository.WorkflowEpochRepository;
import com.apimarketplace.orchestrator.repository.WorkflowPlanVersionRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.repository.SignalWaitRepository;
import com.apimarketplace.orchestrator.repository.PendingSignalRepository;
import com.apimarketplace.orchestrator.domain.execution.DagState;
import com.apimarketplace.orchestrator.domain.execution.StateSnapshot;
import com.apimarketplace.orchestrator.services.InterfaceRenderService;
import com.apimarketplace.orchestrator.services.InterfaceRenderService.InterfaceRenderResult;
import com.apimarketplace.orchestrator.services.InterfaceRenderService.ItemRenderData;
import com.apimarketplace.orchestrator.services.InterfaceRenderService.PaginationInfo;
import com.apimarketplace.orchestrator.services.RunCloneService;
import com.apimarketplace.orchestrator.services.StepAggregationService;
import com.apimarketplace.orchestrator.services.WorkflowPlanVersionService;
import com.apimarketplace.interfaces.client.InterfaceClient;
import com.apimarketplace.interfaces.client.dto.InterfaceSnapshotDto;
import com.apimarketplace.orchestrator.services.epoch.WorkflowEpochService;
import com.apimarketplace.orchestrator.services.file.FileStorageService;
import com.apimarketplace.orchestrator.services.publication.ShowcaseSnapshotBuilder;
import com.apimarketplace.orchestrator.services.resume.WorkflowResumeService;
import com.apimarketplace.orchestrator.services.resume.WorkflowRunState;
import com.apimarketplace.orchestrator.services.state.StateSnapshotService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Internal API endpoints for publication-service to access workflow and run data.
 * These endpoints are not gateway-authenticated (internal network only).
 */
@RestController
@RequestMapping("/api/internal/publication-support")
public class InternalPublicationSupportController {

    private static final Logger log = LoggerFactory.getLogger(InternalPublicationSupportController.class);

    private final WorkflowRepository workflowRepository;
    private final WorkflowRunRepository workflowRunRepository;
    private final WorkflowPlanVersionRepository planVersionRepository;
    private final WorkflowPlanVersionService planVersionService;
    private final WorkflowCategoryRepository categoryRepository;
    private final RunCloneService runCloneService;
    private final FileStorageService fileStorageService;
    private final WorkflowEpochRepository workflowEpochRepository;
    private final SignalWaitRepository signalWaitRepository;
    private final PendingSignalRepository pendingSignalRepository;
    private final InterfaceRenderService interfaceRenderService;
    private final WorkflowResumeService workflowResumeService;
    private final WorkflowEpochService workflowEpochService;
    private final StateSnapshotService stateSnapshotService;
    private final StepAggregationService stepAggregationService;
    private final InterfaceClient interfaceClient;
    private final ShowcaseSnapshotBuilder showcaseSnapshotBuilder;
    private final ObjectMapper objectMapper;
    private final com.apimarketplace.orchestrator.services.WorkflowManagementService workflowManagementService;
    private final com.apimarketplace.orchestrator.services.ApplicationLifecycleService applicationLifecycleService;

    /**
     * 2026-05-04 hot-fix (audit TR-1) - see WorkflowRunController for the full
     * rationale. Briefly: REST `seq` returned to FE must be the WsEventSequencer
     * counter, not StateSnapshot.seq, otherwise FE.lastKnownSeq compares
     * against incompatible counters and skip-applies tracking on cold-load.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.apimarketplace.orchestrator.services.streaming.WsEventSequencer wsEventSequencer;

    public InternalPublicationSupportController(
            WorkflowRepository workflowRepository,
            WorkflowRunRepository workflowRunRepository,
            WorkflowPlanVersionRepository planVersionRepository,
            WorkflowPlanVersionService planVersionService,
            WorkflowCategoryRepository categoryRepository,
            RunCloneService runCloneService,
            FileStorageService fileStorageService,
            WorkflowEpochRepository workflowEpochRepository,
            SignalWaitRepository signalWaitRepository,
            PendingSignalRepository pendingSignalRepository,
            InterfaceRenderService interfaceRenderService,
            WorkflowResumeService workflowResumeService,
            WorkflowEpochService workflowEpochService,
            StateSnapshotService stateSnapshotService,
            StepAggregationService stepAggregationService,
            InterfaceClient interfaceClient,
            ShowcaseSnapshotBuilder showcaseSnapshotBuilder,
            ObjectMapper objectMapper,
            com.apimarketplace.orchestrator.services.WorkflowManagementService workflowManagementService,
            com.apimarketplace.orchestrator.services.ApplicationLifecycleService applicationLifecycleService) {
        this.workflowRepository = workflowRepository;
        this.workflowRunRepository = workflowRunRepository;
        this.planVersionRepository = planVersionRepository;
        this.planVersionService = planVersionService;
        this.categoryRepository = categoryRepository;
        this.runCloneService = runCloneService;
        this.fileStorageService = fileStorageService;
        this.workflowEpochRepository = workflowEpochRepository;
        this.signalWaitRepository = signalWaitRepository;
        this.pendingSignalRepository = pendingSignalRepository;
        this.interfaceRenderService = interfaceRenderService;
        this.workflowResumeService = workflowResumeService;
        this.workflowEpochService = workflowEpochService;
        this.stateSnapshotService = stateSnapshotService;
        this.stepAggregationService = stepAggregationService;
        this.interfaceClient = interfaceClient;
        this.showcaseSnapshotBuilder = showcaseSnapshotBuilder;
        this.objectMapper = objectMapper;
        this.workflowManagementService = workflowManagementService;
        this.applicationLifecycleService = applicationLifecycleService;
    }

    /**
     * Capture a complete frozen view of a run for storage on the
     * publication entity. Returns the JSON shape documented on
     * {@link com.apimarketplace.orchestrator.services.publication.ShowcaseSnapshotBuilder}.
     *
     * <p>Used by publication-service at publish time so the marketplace
     * preview reads from JSONB instead of round-tripping back here.
     */
    @GetMapping("/runs/{runId}/full-snapshot")
    public ResponseEntity<?> getFullShowcaseSnapshot(
            @PathVariable("runId") String runIdPublic,
            @RequestParam("tenantId") String tenantId,
            @RequestParam(value = "organizationId", required = false) String organizationId,
            @RequestParam(value = "epochFilter", required = false) Integer epochFilter) {
        try {
            return showcaseSnapshotBuilder.capture(runIdPublic, tenantId, organizationId, epochFilter)
                    .<ResponseEntity<?>>map(ResponseEntity::ok)
                    .orElseGet(() -> ResponseEntity.notFound().build());
        } catch (Exception e) {
            log.error("[FullSnapshot] capture failed for run={} tenant={} org={} epoch={}: {}",
                    runIdPublic, tenantId, organizationId, epochFilter, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Capture failed"));
        }
    }

    /**
     * Render a (source) run's interface and return ONLY the resolved
     * {@code items[].data} - used by publication-service's pre-publish image
     * screening (Wave 2b) to surface images that live in the render data
     * (scraped CDN URLs, downloaded FileRefs) rather than the static template.
     *
     * <p>Unlike {@code /showcase/render}, this accepts a live source run (no
     * {@code showcase_} prefix) and enforces tenant/org scope via headers -
     * the caller is the authenticated publisher, not an anonymous visitor.
     * Best-effort: render failures return an empty item list (screening must
     * never block a publish), 404 only when the run is missing/out-of-scope.
     */
    @GetMapping("/runs/{runId}/interface-render")
    public ResponseEntity<?> renderInterfaceForScreening(
            @PathVariable("runId") String runIdPublic,
            @RequestParam("interfaceId") UUID interfaceId,
            @RequestParam(value = "epoch", required = false) Integer epoch,
            @RequestHeader(value = "X-User-ID", required = false) String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId) {
        Optional<WorkflowRunEntity> runOpt = workflowRunRepository.findByRunIdPublic(runIdPublic);
        if (runOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        WorkflowRunEntity run = runOpt.get();
        if (!ScopeGuard.isInStrictScope(tenantId, organizationId, run.getTenantId(), run.getOrganizationId())) {
            return ResponseEntity.notFound().build();
        }
        // Cap matches ShowcaseSnapshotBuilder.RENDER_CAP so screening sees the
        // same item window the published snapshot will freeze.
        final int SCREENING_RENDER_CAP = 200;
        List<Map<String, Object>> items = new ArrayList<>();
        try {
            InterfaceRenderResult result = interfaceRenderService.render(
                    interfaceId, runIdPublic, run.getTenantId(), 0, SCREENING_RENDER_CAP, epoch);
            for (ItemRenderData item : result.items()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("epoch", item.epoch());
                m.put("itemIndex", item.itemIndex());
                m.put("data", item.data());
                items.add(m);
            }
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.warn("[ScreeningRender] render failed for iface={} run={}: {} - returning empty (non-blocking)",
                    interfaceId, runIdPublic, e.getMessage());
        }
        return ResponseEntity.ok(Map.of("items", items));
    }

    // ========== Workflow Operations ==========

    /**
     * Get workflow data for publication validation.
     */
    @GetMapping("/workflows/{workflowId}/for-publication")
    public ResponseEntity<?> getWorkflowForPublication(
            @PathVariable UUID workflowId,
            @RequestHeader(value = "X-User-ID", required = false) String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId) {
        Optional<WorkflowEntity> workflowOpt = workflowRepository.findById(workflowId);
        if (workflowOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        WorkflowEntity workflow = workflowOpt.get();
        if (!ScopeGuard.isInStrictScope(tenantId, organizationId,
                workflow.getTenantId(), workflow.getOrganizationId())) {
            return ResponseEntity.notFound().build();
        }
        Map<String, Object> result = new HashMap<>();
        result.put("id", workflow.getId().toString());
        result.put("tenantId", workflow.getTenantId());
        result.put("organizationId", workflow.getOrganizationId());
        result.put("name", workflow.getName());
        result.put("description", workflow.getDescription());
        result.put("plan", workflow.getPlan());
        result.put("status", workflow.getStatus() != null ? workflow.getStatus().name() : null);
        result.put("workflowType", workflow.getWorkflowType() != null ? workflow.getWorkflowType().name() : null);
        result.put("isApplication", workflow.getWorkflowType() == WorkflowEntity.WorkflowType.APPLICATION);
        return ResponseEntity.ok(result);
    }

    /**
     * Get a specific plan version.
     */
    @GetMapping("/workflows/{workflowId}/plan-version/{version}")
    public ResponseEntity<?> getPlanVersion(
            @PathVariable UUID workflowId,
            @PathVariable int version) {
        var versionEntity = planVersionRepository.findByWorkflowIdAndVersion(workflowId, version);
        if (versionEntity.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Map<String, Object> result = new HashMap<>();
        result.put("plan", versionEntity.get().getPlan());
        result.put("version", versionEntity.get().getVersion());
        return ResponseEntity.ok(result);
    }

    /**
     * Get the latest plan version number.
     */
    @GetMapping("/workflows/{workflowId}/latest-plan-version")
    public ResponseEntity<?> getLatestPlanVersion(@PathVariable UUID workflowId) {
        Optional<Integer> maxVersion = planVersionRepository.getMaxVersion(workflowId);
        return ResponseEntity.ok(maxVersion.orElse(null));
    }

    /**
     * Create a workflow clone from an acquired publication.
     *
     * <p>The optional {@code workflowType} request field decides the row type:
     * {@code APPLICATION} (default) for the ROOT clone of an acquisition,
     * {@code WORKFLOW} for every other clone created alongside it (sub-workflow
     * children, agent-publication workflows). Only ONE APPLICATION row may
     * exist per (organization, source publication) - enforced by the V268
     * partial unique index {@code uq_workflow_org_source_pub_application} and
     * relied on by {@code findByOrganizationIdAndSourcePublicationIdAndWorkflowType}
     * (the /app/applications/{pubId} root lookup). Stamping children as
     * APPLICATION used to violate both (duplicate-key 500 at acquire).
     */
    @PostMapping("/workflows/create-application")
    @Transactional
    public ResponseEntity<?> createApplicationWorkflow(
            @RequestBody Map<String, Object> request,
            @RequestHeader(value = "X-User-ID", required = false) String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String headerOrganizationId) {
        try {
            String title = (String) request.get("title");
            String description = (String) request.get("description");
            @SuppressWarnings("unchecked")
            Map<String, Object> plan = (Map<String, Object>) request.get("plan");
            @SuppressWarnings("unchecked")
            Map<String, Object> basePlan = (Map<String, Object>) request.get("basePlan");
            String sourcePublicationIdStr = (String) request.get("sourcePublicationId");
            UUID sourcePublicationId = sourcePublicationIdStr != null ? UUID.fromString(sourcePublicationIdStr) : null;
            String bodyOrganizationId = (String) request.get("organizationId");
            String organizationId = hasText(bodyOrganizationId) ? bodyOrganizationId : headerOrganizationId;
            if (!hasText(organizationId)) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "organizationId is required to create an APPLICATION workflow"));
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> nodeIcons = (List<Map<String, Object>>) request.get("nodeIcons");

            WorkflowEntity app = new WorkflowEntity(tenantId, title, tenantId);
            String requestedId = (String) request.get("id");
            UUID workflowId;
            if (requestedId != null) {
                workflowId = UUID.fromString(requestedId);
                if (workflowRepository.existsById(workflowId)) {
                    return ResponseEntity.badRequest().body(Map.of("error", "Workflow ID already exists: " + requestedId));
                }
            } else {
                workflowId = UUID.randomUUID();
            }
            app.setId(workflowId);
            String requestedTypeRaw = (String) request.get("workflowType");
            WorkflowEntity.WorkflowType workflowType = WorkflowEntity.WorkflowType.APPLICATION;
            if (requestedTypeRaw != null && !requestedTypeRaw.isBlank()) {
                try {
                    workflowType = WorkflowEntity.WorkflowType.valueOf(requestedTypeRaw.trim());
                } catch (IllegalArgumentException e) {
                    return ResponseEntity.badRequest().body(Map.of(
                            "error", "Unknown workflowType: " + requestedTypeRaw));
                }
            }
            app.setWorkflowType(workflowType);
            // F4 PUB-HIJACK defense-in-depth: strip standalone trigger row UUIDs
            // even if publication-service forgot to strip (regression, direct
            // test caller, non-canonical publish path). The map is consumed from
            // the request body here - strip in place is safe (no source to corrupt).
            com.apimarketplace.common.plan.PlanStripUtils.stripStandaloneTriggerRefs(basePlan);
            com.apimarketplace.common.plan.PlanStripUtils.stripStandaloneTriggerRefs(plan);
            app.setBasePlan(basePlan);
            app.setPlan(plan);
            app.setSourcePublicationId(sourcePublicationId);
            // Optional provenance metadata (e.g. duplicatedFromApplicationId for a
            // decoupled editable-duplicate WORKFLOW). Carried in metadata, NOT in
            // source_publication_id, so the duplicate stays exempt from the V268
            // partial unique index + every APPLICATION lookup.
            @SuppressWarnings("unchecked")
            Map<String, Object> metadata = request.get("metadata") instanceof Map
                    ? (Map<String, Object>) request.get("metadata") : null;
            if (metadata != null && !metadata.isEmpty()) {
                app.setMetadata(metadata);
            }
            app.setDescription(description);
            app.setNodeIcons(nodeIcons);
            app.setStatus(WorkflowEntity.WorkflowStatus.ACTIVE);
            app.setIsActive(true);
            app.setOrganizationId(organizationId);

            WorkflowEntity saved = workflowRepository.save(app);
            if (plan != null) {
                planVersionService.createVersion(saved.getId(), saved.getPlan(), tenantId, "Application acquisition");
            }

            Map<String, Object> result = new HashMap<>();
            result.put("id", saved.getId().toString());
            result.put("tenantId", saved.getTenantId());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Failed to create application workflow: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Refresh the basePlan / plan / nodeIcons of an existing APPLICATION
     * workflow on re-publish. The publisher's APPLICATION is the
     * publisher-side test fixture for the publication and must reflect the
     * latest snapshot just like a freshly-acquired tenant would.
     *
     * <p>Returns 404 when the application does not exist (caller should fall
     * back to {@code create-application}).
     */
    @PostMapping("/workflows/{workflowId}/refresh-from-publication")
    @Transactional
    public ResponseEntity<?> refreshApplicationFromPublication(
            @PathVariable UUID workflowId,
            @RequestBody Map<String, Object> request) {
        Optional<WorkflowEntity> appOpt = workflowRepository.findById(workflowId);
        if (appOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        WorkflowEntity app = appOpt.get();
        if (app.getWorkflowType() != WorkflowEntity.WorkflowType.APPLICATION) {
            return ResponseEntity.badRequest().body(Map.of("error", "Not an APPLICATION workflow"));
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> plan = (Map<String, Object>) request.get("plan");
        @SuppressWarnings("unchecked")
        Map<String, Object> basePlan = (Map<String, Object>) request.get("basePlan");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nodeIcons = (List<Map<String, Object>>) request.get("nodeIcons");
        String title = (String) request.get("title");
        String description = (String) request.get("description");

        // F4 PUB-HIJACK defense-in-depth: same as create-application.
        if (plan != null) {
            com.apimarketplace.common.plan.PlanStripUtils.stripStandaloneTriggerRefs(plan);
            app.setPlan(plan);
        }
        if (basePlan != null) {
            com.apimarketplace.common.plan.PlanStripUtils.stripStandaloneTriggerRefs(basePlan);
            app.setBasePlan(basePlan);
        }
        if (nodeIcons != null) app.setNodeIcons(nodeIcons);
        if (title != null) app.setName(title);
        if (description != null) app.setDescription(description);

        WorkflowEntity saved = workflowRepository.save(app);
        Map<String, Object> result = new HashMap<>();
        result.put("id", saved.getId().toString());
        result.put("tenantId", saved.getTenantId());
        return ResponseEntity.ok(result);
    }

    /**
     * Enumerate every APPLICATION workflow (main + sub) created in a tenant
     * for a given publication. Used by the publication-service compensating
     * cleanup when {@code acquirePublication} fails mid-clone - unlike the
     * single-row {@link #findBySourcePublication}, this returns the full
     * tree of partial rows so they can all be deleted.
     */
    @GetMapping("/workflows/all-by-source-publication")
    public ResponseEntity<List<Map<String, Object>>> findAllBySourcePublication(
            @RequestParam UUID pubId,
            @RequestParam String tenantId,
            @RequestParam(value = "organizationId", required = true) String organizationId) {
        TenantResolver.requireOrgId(organizationId);
        List<WorkflowEntity> apps = workflowRepository
                .findAllByOrganizationIdAndSourcePublicationId(organizationId, pubId);
        List<Map<String, Object>> rows = apps.stream().map(app -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", app.getId().toString());
            m.put("tenantId", app.getTenantId());
            m.put("organizationId", app.getOrganizationId());
            m.put("title", app.getName());
            return m;
        }).collect(Collectors.toList());
        return ResponseEntity.ok(rows);
    }

    /**
     * Find existing application workflow by publication and tenant.
     */
    @GetMapping("/workflows/by-source-publication")
    public ResponseEntity<?> findBySourcePublication(
            @RequestParam UUID pubId,
            @RequestParam String tenantId,
            @RequestParam(value = "organizationId", required = true) String organizationId) {
        TenantResolver.requireOrgId(organizationId);
        Optional<WorkflowEntity> appOpt =
                applicationLifecycleService.resolveClone(organizationId, pubId);
        if (appOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        WorkflowEntity app = appOpt.get();
        Map<String, Object> result = new HashMap<>();
        result.put("id", app.getId().toString());
        result.put("tenantId", app.getTenantId());
        result.put("organizationId", app.getOrganizationId());
        result.put("title", app.getName());
        return ResponseEntity.ok(result);
    }

    /**
     * Check if tenant has a workflow from a specific publication.
     */
    @GetMapping("/workflows/exists-by-source")
    public ResponseEntity<Boolean> existsBySourcePublication(
            @RequestParam UUID pubId,
            @RequestParam String tenantId,
            @RequestParam(value = "organizationId", required = false) String organizationId) {
        // Post-V261 (2026-05-19): every user-scoped workflow row carries a
        // non-null organization_id (personal workspaces resolve to the user's
        // default personal org). The legacy personal-strict
        // existsByTenantIdAndSourcePublicationIdAndOrganizationIdIsNull was
        // removed - callers route through the org-strict exists when an
        // organizationId is present, otherwise we fall back to the broad
        // tenant-only check (best-effort for any legacy caller still passing
        // a blank organizationId during the migration window).
        //
        // Typed to APPLICATION: "already acquired" means the acquisition's
        // ROOT row exists. Sub-workflow children (WORKFLOW rows tagged with
        // the same publication) must not count - an orphan child left by a
        // failed acquire would otherwise block re-acquisition forever.
        boolean exists = hasText(organizationId)
                ? workflowRepository.existsByOrganizationIdAndSourcePublicationIdAndWorkflowType(
                        organizationId, pubId, WorkflowEntity.WorkflowType.APPLICATION)
                : workflowRepository.existsByTenantIdAndSourcePublicationIdAndWorkflowType(
                        tenantId, pubId, WorkflowEntity.WorkflowType.APPLICATION);
        return ResponseEntity.ok(exists);
    }

    /**
     * Delete an acquire-clone workflow row - compensation path for a failed
     * acquisition. Guarded: the row must be tagged with the given source
     * publication and belong to the given tenant; anything else is refused so
     * the endpoint cannot delete arbitrary workflows. Delegates to the
     * canonical cascade delete (runs, plan versions, schedules, files).
     */
    @DeleteMapping("/workflows/{workflowId}/acquired")
    public ResponseEntity<?> deleteAcquiredWorkflow(
            @PathVariable UUID workflowId,
            @RequestParam UUID pubId,
            @RequestParam String tenantId) {
        Optional<WorkflowEntity> workflowOpt = workflowRepository.findById(workflowId);
        if (workflowOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        WorkflowEntity workflow = workflowOpt.get();
        if (!pubId.equals(workflow.getSourcePublicationId()) || !tenantId.equals(workflow.getTenantId())) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "Workflow is not an acquire clone of this publication for this tenant"));
        }
        boolean deleted = workflowManagementService.deleteWorkflow(workflowId, tenantId);
        return deleted
                ? ResponseEntity.noContent().build()
                : ResponseEntity.internalServerError().body(Map.of("error", "Delete failed"));
    }

    /**
     * Delete a DECOUPLED editable-duplicate workflow row - compensation path for a
     * failed {@code duplicateToEditableWorkflow} clone. Guarded: the row must belong
     * to the tenant AND be a plain {@code WORKFLOW} with a NULL
     * {@code source_publication_id} (the shape a decoupled duplicate + its
     * sub-workflows have). This refuses to delete any acquired APPLICATION (those
     * carry a source tag and use {@link #deleteAcquiredWorkflow}, whose pubId guard
     * would NPE on a null source). Delegates to the canonical cascade delete.
     */
    @DeleteMapping("/workflows/{workflowId}/decoupled-duplicate")
    public ResponseEntity<?> deleteDecoupledDuplicateWorkflow(
            @PathVariable UUID workflowId,
            @RequestParam String tenantId) {
        Optional<WorkflowEntity> workflowOpt = workflowRepository.findById(workflowId);
        if (workflowOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        WorkflowEntity workflow = workflowOpt.get();
        boolean decoupled = workflow.getSourcePublicationId() == null
                && workflow.getWorkflowType() == WorkflowEntity.WorkflowType.WORKFLOW;
        if (!tenantId.equals(workflow.getTenantId()) || !decoupled) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "Workflow is not a decoupled editable duplicate for this tenant"));
        }
        boolean deleted = workflowManagementService.deleteWorkflow(workflowId, tenantId);
        return deleted
                ? ResponseEntity.noContent().build()
                : ResponseEntity.internalServerError().body(Map.of("error", "Delete failed"));
    }

    /**
     * Count workflows in an organization (all types) - the WORKFLOW-quota count the
     * {@code saveWorkflow} entitlement gate uses ({@code countByOrganizationIdStrict}).
     * The duplicate-to-editable acquire path calls this to bill WORKFLOW quota itself,
     * because the internal {@code create-application} endpoint runs no entitlement check.
     */
    @GetMapping("/workflows/count-by-org")
    public ResponseEntity<Long> countWorkflowsByOrg(@RequestParam String organizationId) {
        return ResponseEntity.ok(workflowRepository.countByOrganizationIdStrict(organizationId));
    }

    /**
     * Get acquired workflows for a tenant.
     *
     * <p>2026-05-21 fix - {@code organizationId} switched from required=true to
     * required=false with a tenant-only fallback. Before, the Applications page
     * 404'd whenever the upstream caller didn't pass an orgId (personal-scope
     * legacy code path, gateway misconfig, direct API access without
     * X-Active-Organization-ID). The fallback uses {@code findAcquiredByTenantId}
     * which scopes by the path-bound {@code tenantId} only.
     */
    @GetMapping("/workflows/acquired/{tenantId}")
    public ResponseEntity<?> getAcquiredWorkflows(
            @PathVariable String tenantId,
            @RequestParam(value = "organizationId", required = false) String organizationId) {
        List<WorkflowEntity> workflows = (organizationId != null && !organizationId.isBlank())
                ? workflowRepository.findAcquiredByOrganizationId(organizationId, WorkflowEntity.WorkflowType.APPLICATION)
                : workflowRepository.findAcquiredByTenantId(tenantId, WorkflowEntity.WorkflowType.APPLICATION);
        List<Map<String, Object>> result = workflows.stream().map(w -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", w.getId().toString());
            map.put("tenantId", w.getTenantId());
            map.put("organizationId", w.getOrganizationId());
            map.put("title", w.getName());
            map.put("sourcePublicationId", w.getSourcePublicationId() != null ? w.getSourcePublicationId().toString() : null);
            map.put("acquiredAt", w.getAcquiredAt() != null ? w.getAcquiredAt().toString() : null);
            // Entry interface id (lean - not the full plan): lets publication-service mark a
            // cloud-acquired purchase so its My-Purchases card can fall back to rendering the
            // acquirer's OWN local clone interface when the cloud source is unpublished/deleted.
            map.put("entryInterfaceId", entryInterfaceIdFromPlan(w.getPlan()));
            return map;
        }).collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    /**
     * The entry interface id of a workflow plan - the interface flagged {@code isEntryInterface=true},
     * else the first interface; null when the plan carries no interface. Kept lean (an id, not the
     * whole plan) for the acquired-workflows LIST payload.
     */
    @SuppressWarnings("unchecked")
    static String entryInterfaceIdFromPlan(Map<String, Object> plan) {
        if (plan == null) return null;
        Object ifaces = plan.get("interfaces");
        if (!(ifaces instanceof List<?> list) || list.isEmpty()) return null;
        Map<String, Object> entry = null;
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) continue;
            if (Boolean.TRUE.equals(m.get("isEntryInterface"))) { entry = (Map<String, Object>) m; break; }
            if (entry == null) entry = (Map<String, Object>) m; // fallback to the first interface
        }
        return entry != null && entry.get("id") != null ? entry.get("id").toString() : null;
    }

    /**
     * List all workflow IDs for a tenant (non-APPLICATION only).
     * Used by agent publication when mode=all and no explicit workflow list.
     * <p>BATCH-B (2026-05-20) - strict-org routing when the caller provides an
     * organizationId query param (publication-service has been wired to pass it
     * since the cloud-link rollout). Tenant-only fallback preserved for any
     * legacy in-flight caller still on the 2-arg signature; will be removed in
     * Phase 11 once {@link com.apimarketplace.orchestrator.repository.WorkflowRepository#findByTenantId(String)}
     * is dropped.
     */
    @GetMapping("/workflows/ids-by-tenant/{tenantId}")
    public ResponseEntity<List<String>> getWorkflowIdsByTenant(
            @PathVariable String tenantId,
            @RequestParam(value = "organizationId", required = false) String organizationId) {
        List<WorkflowEntity> workflows = hasText(organizationId)
                ? workflowRepository.findByOrganizationIdStrict(organizationId)
                : workflowRepository.findByTenantId(tenantId);
        List<String> ids = workflows.stream()
                .filter(w -> w.getWorkflowType() != WorkflowEntity.WorkflowType.APPLICATION)
                .map(w -> w.getId().toString())
                .toList();
        return ResponseEntity.ok(ids);
    }

    /**
     * Check which workflow IDs from a given set actually exist.
     * Used by publication-service cleanup to detect orphaned publications.
     */
    @GetMapping("/workflows/exists")
    public ResponseEntity<Set<UUID>> getExistingWorkflowIds(@RequestParam String ids) {
        if (ids == null || ids.isBlank()) {
            return ResponseEntity.ok(Set.of());
        }
        Set<UUID> requestedIds = Arrays.stream(ids.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(UUID::fromString)
                .collect(Collectors.toSet());

        Set<UUID> existingIds = workflowRepository.findAllById(requestedIds).stream()
                .map(WorkflowEntity::getId)
                .collect(Collectors.toSet());

        return ResponseEntity.ok(existingIds);
    }

    // ========== Run Operations ==========

    /**
     * Clone a showcase run.
     */
    @PostMapping("/runs/clone")
    @Transactional
    public ResponseEntity<?> cloneRun(@RequestBody Map<String, Object> request) {
        try {
            String sourceRunId = (String) request.get("sourceRunId");
            String prefix = (String) request.get("prefix");
            String publicationId = (String) request.get("publicationId");

            WorkflowRunEntity clonedRun = runCloneService.cloneRun(sourceRunId, prefix, publicationId);

            Map<String, Object> result = new HashMap<>();
            result.put("runIdPublic", clonedRun.getRunIdPublic());
            result.put("id", clonedRun.getId() != null ? clonedRun.getId().toString() : null);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Failed to clone run: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Delete a cloned run.
     */
    @DeleteMapping("/runs/clone/{runId}")
    @Transactional
    public ResponseEntity<?> deleteClonedRun(@PathVariable String runId) {
        try {
            runCloneService.deleteClonedRun(runId);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.warn("Failed to delete cloned run {}: {}", runId, e.getMessage());
            return ResponseEntity.ok().build(); // Fire-and-forget
        }
    }

    /**
     * Resolve a public run id to its interface_run_snapshots, keyed by
     * {@code interfaceId.toString()}. Used by publication-service at publish
     * time to enrich {@code planSnapshot.interfaces[i]._snapshot_variableMappings}
     * and {@code _snapshot_actionMappings} with the runtime mappings of the
     * source run. Without these the marketplace preview would have to fall
     * back to reading {@code interface_run_snapshots} from the publisher's
     * tenant - which is exactly the cross-tenant coupling we're removing.
     *
     * <p>Returns 404 when the run does not exist; an empty map when the run
     * exists but has no interface snapshots (workflow without interfaces).
     */
    @GetMapping("/runs/{runId}/interface-snapshots")
    public ResponseEntity<?> getInterfaceSnapshotsForRun(@PathVariable("runId") String runIdPublic,
                                                          @RequestParam("tenantId") String tenantId,
                                                          @RequestParam(value = "organizationId", required = false) String organizationId) {
        Optional<WorkflowRunEntity> runOpt = workflowRunRepository.findByRunIdPublic(runIdPublic);
        if (runOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        WorkflowRunEntity run = runOpt.get();
        if (!ScopeGuard.isInStrictScope(tenantId, organizationId, run.getTenantId(), run.getOrganizationId())) {
            return ResponseEntity.notFound().build();
        }

        Map<String, Object> result = new HashMap<>();
        try {
            List<InterfaceSnapshotDto> snapshots = interfaceClient.getSnapshotsForRun(run.getId(), tenantId, organizationId);
            for (InterfaceSnapshotDto snap : snapshots) {
                if (snap.getInterfaceId() == null) continue;
                Map<String, Object> entry = new HashMap<>();
                entry.put("variableMappings", snap.getVariableMappings());
                entry.put("actionMappings", snap.getActionMappings());
                result.put(snap.getInterfaceId().toString(), entry);
            }
        } catch (Exception e) {
            log.warn("[InterfaceSnapshots] Failed to fetch snapshots for run {} (tenant {}): {}",
                    runIdPublic, tenantId, e.getMessage());
            return ResponseEntity.ok(result);
        }
        return ResponseEntity.ok(result);
    }

    /**
     * Validate that a run exists and is suitable for use as the showcase
     * source of a publication. Verifies:
     * <ul>
     *   <li>tenant ownership of the run (defense-in-depth - capture later
     *       enforces the same, but failing fast here keeps the publish
     *       transaction cheap);</li>
     *   <li>the run is not step-by-step (cannot be faithfully replayed);</li>
     *   <li>the run terminated successfully (FAILED / CANCELLED / TIMEOUT
     *       runs would showcase a broken state to marketplace visitors).</li>
     * </ul>
     */
    @GetMapping({"/runs/{runId}/validate-for-publication", "/runs/{runId}/validate-showcase"})
    public ResponseEntity<?> validateRunForPublication(
            @PathVariable String runId,
            @RequestHeader(value = "X-User-ID", required = false) String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId) {
        Optional<WorkflowRunEntity> runOpt = workflowRunRepository.findByRunIdPublic(runId);
        if (runOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        WorkflowRunEntity run = runOpt.get();
        if (tenantId != null && !ScopeGuard.isInStrictScope(
                tenantId, organizationId, run.getTenantId(), run.getOrganizationId())) {
            return ResponseEntity.notFound().build();
        }
        boolean isStepByStep = run.getExecutionMode() != null && run.getExecutionMode().isStepByStep();
        String status = run.getStatus() != null ? run.getStatus().name() : null;
        // A run is publishable when it has produced at least one successful
        // cycle. For one-shot workflows this means status=COMPLETED or
        // PARTIAL_SUCCESS. For reusable-trigger workflows (webhook, manual,
        // chat, datasource, schedule) the natural post-fire state is
        // WAITING_TRIGGER - the cycle finished and is waiting for the next
        // fire. We accept it as long as at least one cycle ran (epoch > 0
        // OR steps exist). FAILED / CANCELLED / TIMEOUT remain excluded -
        // a broken cycle would showcase a broken state.
        boolean terminatedSuccessfully = "COMPLETED".equals(status)
                || "PARTIAL_SUCCESS".equals(status)
                || "WAITING_TRIGGER".equals(status);

        Map<String, Object> result = new HashMap<>();
        result.put("runIdPublic", run.getRunIdPublic());
        result.put("isStepByStep", isStepByStep);
        result.put("status", status);
        result.put("publishable", !isStepByStep && terminatedSuccessfully);
        return ResponseEntity.ok(result);
    }

    /**
     * Render a publication's frozen showcase clone (called by publication-service
     * on behalf of anonymous marketplace visitors).
     *
     * <p>Defense-in-depth: even though publication-service has already validated
     * visibility + status, this layer also enforces that {@code runId} is a
     * {@code showcase_*} clone AND that the run's {@code publication_id} matches
     * the claimed publication. That way an internal caller cannot pivot to a
     * live run or to another publisher's showcase.
     *
     * <p>Returns the same shape as {@code /api/interfaces/{id}/render} so the
     * frontend can reuse its existing renderer without conditional parsing.
     */
    @GetMapping("/showcase/render")
    public ResponseEntity<?> renderShowcase(
            @RequestParam("publicationId") String publicationId,
            @RequestParam("interfaceId") UUID interfaceId,
            @RequestParam("runId") String runId,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "1") int size,
            @RequestParam(value = "epoch", required = false) Integer epoch) {

        // Hard bound on size - anonymous endpoint, no incentive to paginate hard
        int effectiveSize = size <= 0 ? 1 : Math.min(size, 10);
        int effectivePage = Math.max(0, page);

        // Defense-in-depth: {@code showcase_*} prefix + publicationId match.
        ResponseEntity<?> guard = checkShowcaseOwnership(publicationId, runId);
        if (guard != null) return guard;

        // 3. Delegate to the existing render pipeline. ownerTenantId is resolved
        //    from the run itself inside InterfaceRenderService.render() so we can
        //    safely pass null here - the anonymous caller has no tenant.
        InterfaceRenderResult result;
        try {
            result = interfaceRenderService.render(
                    interfaceId, runId, null, effectivePage, effectiveSize, epoch);
        } catch (IllegalArgumentException e) {
            // Bad arguments from the caller (e.g. malformed interface UUID) - 400 not 500
            log.warn("[ShowcaseRender] Bad args for pub={} iface={} run={}: {}",
                    publicationId, interfaceId, runId, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("[ShowcaseRender] render failed for pub={} iface={} run={}: {}",
                    publicationId, interfaceId, runId, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Render failed"));
        }

        // Interface was never bound to the run OR was deleted - the snapshot
        // lookup returned nothing so htmlTemplate is empty. Surface as 404
        // rather than 200-with-empty-payload so the caller can fall back
        // cleanly (publication-service turns this into a 404 to the browser).
        if (result.htmlTemplate() == null || result.htmlTemplate().isEmpty()) {
            log.debug("[ShowcaseRender] Empty template for pub={} iface={} run={} - treating as not found",
                    publicationId, interfaceId, runId);
            return ResponseEntity.notFound().build();
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("htmlTemplate", result.htmlTemplate());
        body.put("cssTemplate", result.cssTemplate());
        body.put("jsTemplate", result.jsTemplate());
        body.put("actionMappings", result.actionMappings());
        List<Map<String, Object>> items = new ArrayList<>();
        for (ItemRenderData item : result.items()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("epoch", item.epoch());
            m.put("itemIndex", item.itemIndex());
            m.put("spawn", item.spawn());
            m.put("data", item.data());
            m.put("triggerData", item.triggerData());
            items.add(m);
        }
        body.put("items", items);
        PaginationInfo p = result.pagination();
        Map<String, Object> pagination = new LinkedHashMap<>();
        pagination.put("page", p.page());
        pagination.put("size", p.size());
        pagination.put("totalItems", p.totalItems());
        pagination.put("totalPages", p.totalPages());
        body.put("pagination", pagination);
        return ResponseEntity.ok(body);
    }

    /**
     * Return the aggregated-step list for a publication's showcase clone,
     * optionally filtered by epoch. Mirrors the auth'd
     * {@code GET /instances/{runId}/steps/aggregated?epoch=N} so the
     * RunInfo panel in the marketplace preview can display per-epoch step
     * details when the user clicks on an epoch pill.
     */
    @GetMapping("/showcase/aggregated-steps")
    public ResponseEntity<?> getShowcaseAggregatedSteps(
            @RequestParam("publicationId") String publicationId,
            @RequestParam("runId") String runId,
            @RequestParam(value = "epoch", required = false) Integer epoch) {

        ResponseEntity<?> guard = checkShowcaseOwnership(publicationId, runId);
        if (guard != null) return guard;

        try {
            Optional<List<StepAggregationService.AggregatedStep>> aggregatedOpt = epoch != null
                    ? stepAggregationService.getAggregatedSteps(runId, epoch)
                    : stepAggregationService.getAggregatedSteps(runId);

            if (aggregatedOpt.isEmpty()) {
                return ResponseEntity.notFound().build();
            }

            List<Map<String, Object>> response = stepAggregationService.toResponseList(aggregatedOpt.get());
            // Note: the auth'd controller enriches per-epoch responses with
            // awaiting-signal nodes from StateSnapshot. Showcase clones are
            // frozen (never executed), so there are no active awaiting_signal
            // states to inject - the SQL aggregation result is authoritative.
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("[ShowcaseAggregatedSteps] failed for pub={} run={} epoch={}: {}",
                    publicationId, runId, epoch, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Aggregated-steps lookup failed"));
        }
    }

    /**
     * Return the per-epoch aggregated node/edge status counts for a
     * publication's showcase clone. Mirrors the auth'd
     * {@code GET /runs/{runId}/epochs/{epoch}/state} shape so the frontend's
     * epoch-viewing hook can apply the counts identically.
     *
     * <p>Same defense-in-depth guards as the other showcase endpoints:
     * runId must be a {@code showcase_} clone, and its publicationId must
     * match the claimed one.
     */
    @GetMapping("/showcase/epoch-state")
    @Transactional(readOnly = true)
    public ResponseEntity<?> getShowcaseEpochState(
            @RequestParam("publicationId") String publicationId,
            @RequestParam("runId") String runId,
            @RequestParam("epoch") int epoch) {

        ResponseEntity<?> guard = checkShowcaseOwnership(publicationId, runId);
        if (guard != null) return guard;

        try {
            Map<String, Object> result = workflowEpochService.getEpochState(runId, epoch);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("[ShowcaseEpochState] failed for pub={} run={} epoch={}: {}",
                    publicationId, runId, epoch, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Epoch-state lookup failed"));
        }
    }

    /**
     * Return the active signal waits for a specific epoch of a showcase
     * clone. Mirrors {@code GET /runs/{runId}/epochs/{epoch}/signals}.
     */
    @GetMapping("/showcase/epoch-signals")
    @Transactional(readOnly = true)
    public ResponseEntity<?> getShowcaseEpochSignals(
            @RequestParam("publicationId") String publicationId,
            @RequestParam("runId") String runId,
            @RequestParam("epoch") int epoch) {

        ResponseEntity<?> guard = checkShowcaseOwnership(publicationId, runId);
        if (guard != null) return guard;

        try {
            List<com.apimarketplace.orchestrator.domain.execution.SignalWaitEntity> signals =
                    signalWaitRepository.findActiveByRunIdAndEpoch(runId, epoch);
            List<Map<String, Object>> response = signals.stream()
                    .map(s -> {
                        Map<String, Object> map = new LinkedHashMap<>();
                        map.put("nodeId", s.getNodeId());
                        map.put("signalType", s.getSignalType().name());
                        map.put("status", s.getStatus().name());
                        map.put("itemId", s.getItemId());
                        if (s.getCreatedAt() != null) map.put("createdAt", s.getCreatedAt().toString());
                        if (s.getExpiresAt() != null) map.put("expiresAt", s.getExpiresAt().toString());
                        return map;
                    })
                    .collect(Collectors.toList());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("[ShowcaseEpochSignals] failed for pub={} run={} epoch={}: {}",
                    publicationId, runId, epoch, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Epoch-signals lookup failed"));
        }
    }

    /**
     * Shared defense-in-depth guard used by showcase state endpoints.
     * Returns null when the (publicationId, runId) pair is valid, or a
     * pre-built error ResponseEntity otherwise.
     */
    private ResponseEntity<?> checkShowcaseOwnership(String publicationId, String runId) {
        if (runId == null || !runId.startsWith("showcase_")) {
            log.warn("[Showcase*] Rejected non-showcase runId={}", runId);
            return ResponseEntity.badRequest().body(Map.of("error", "Not a showcase run"));
        }
        Optional<WorkflowRunEntity> runOpt = workflowRunRepository.findByRunIdPublic(runId);
        if (runOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        if (!Objects.equals(publicationId, runOpt.get().getPublicationId())) {
            log.warn("[Showcase*] publicationId mismatch: claimed={} actual={} runId={}",
                    publicationId, runOpt.get().getPublicationId(), runId);
            // 404 (not 403) to avoid leaking that a run with this id exists under
            // a different publication - caller has no business knowing.
            return ResponseEntity.status(404).body(Map.of("error", "Run does not belong to publication"));
        }
        return null;
    }

    /**
     * Return the frozen run state of a publication's showcase clone.
     *
     * <p>Anonymous marketplace visitors need this to light up the toolbar's
     * epoch calendar, step-status counts, and completed/skipped node badges -
     * everything that the auth'd {@code /api/v2/workflows/dag/runs/{id}/state}
     * would give an owner. Same defense-in-depth as {@code /showcase/render}:
     * <ol>
     *   <li>{@code runId} MUST start with {@code showcase_} (frozen clone).</li>
     *   <li>The run's {@code publication_id} MUST match the caller-claimed
     *       {@code publicationId}.</li>
     * </ol>
     * Response shape mirrors {@code WorkflowRunController.getRunState} so the
     * frontend store can ingest it without branching.
     */
    @GetMapping("/showcase/run-state")
    @Transactional(readOnly = true)
    public ResponseEntity<?> getShowcaseRunState(
            @RequestParam("publicationId") String publicationId,
            @RequestParam("runId") String runId) {

        ResponseEntity<?> guard = checkShowcaseOwnership(publicationId, runId);
        if (guard != null) return guard;
        WorkflowRunEntity run = workflowRunRepository.findByRunIdPublic(runId).get();

        try {
            // Showcase clone API mirrors GET /state. Public showcase frontend doesn't read
            // globalData or non-rendered (mcp/trigger/core/table) output blobs - use the
            // lean reconstructor to avoid N storage round-trips per refresh.
            WorkflowRunState state = workflowResumeService.reconstructStateForApi(runId);
            StateSnapshot dbSnapshot = stateSnapshotService.getSnapshot(runId);
            // 2026-05-04 hot-fix (audit TR-1): align with WsEventSequencer counter
            // so FE.lastKnownSeq guard doesn't reject WS events that arrive before
            // this REST response. Math.max guard handles the case where the
            // showcase clone has never been live (no WS events) yet.
            long seq = (wsEventSequencer != null)
                    ? Math.max(wsEventSequencer.currentSeq(runId), dbSnapshot.getSeq())
                    : dbSnapshot.getSeq();

            int currentEpoch = 0;
            for (DagState dag : dbSnapshot.getDags().values()) {
                currentEpoch = Math.max(currentEpoch, dag.getCurrentEpoch());
            }
            var epochTimestamps = workflowEpochService.listEpochTimestamps(runId);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("runId", state.runId());
            response.put("workflowId", state.workflowId());
            response.put("status", state.status());
            response.put("executionMode", state.executionMode());
            response.put("startedAt", state.startedAt());
            response.put("pausedAt", state.pausedAt());
            response.put("plan", state.plan());
            response.put("steps", state.steps());
            response.put("edges", state.edges());
            response.put("readySteps", state.readySteps());
            response.put("completedStepIds", state.completedStepIds());
            response.put("failedStepIds", state.failedStepIds());
            response.put("skippedStepIds", state.skippedStepIds());
            response.put("runningStepIds", state.runningStepIds());
            response.put("loops", state.loops());
            response.put("interfaces", state.interfaces());
            response.put("seq", seq);
            response.put("currentEpoch", currentEpoch);
            if (!epochTimestamps.isEmpty()) {
                response.put("epochTimestamps", epochTimestamps);
            }
            response.put("totalDurationMs", dbSnapshot.getTotalDurationMs());
            response.put("planVersion", run.getPlanVersion());
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            log.warn("[ShowcaseRunState] Bad args for pub={} run={}: {}",
                    publicationId, runId, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("[ShowcaseRunState] failed for pub={} run={}: {}",
                    publicationId, runId, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Run state lookup failed"));
        }
    }

    /**
     * Copy an S3 file from one path to another (download + re-upload).
     * Used by publication-service for DataInput file snapshot/clone.
     */
    @PostMapping("/files/copy")
    public ResponseEntity<?> copyFile(@RequestBody Map<String, Object> request) {
        try {
            String sourcePath = (String) request.get("sourcePath");
            String sourceTenantId = (String) request.get("sourceTenantId");
            String tenantId = (String) request.get("tenantId");
            String workflowId = (String) request.get("workflowId");
            String runId = (String) request.get("runId");
            String stepAlias = (String) request.get("stepAlias");
            String fileName = (String) request.get("fileName");
            String mimeType = (String) request.get("mimeType");

            if (sourceTenantId == null || sourceTenantId.isBlank()) {
                sourceTenantId = inferTenantIdFromStoragePath(sourcePath);
            }

            var content = fileStorageService.download(sourceTenantId, sourcePath);
            if (content.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "File not found: " + sourcePath));
            }

            var fileRef = fileStorageService.upload(tenantId, workflowId, runId, stepAlias,
                    fileName, mimeType, content.get());

            // Return the NEW storage-row id too - the re-uploaded file is a brand-new storage row in
            // the target tenant. Callers (publish snapshot / acquire clone) MUST rewrite the FileRef's
            // `id` (not just its `path`) to this new id; the opaque by-id URL is built from `id`, so a
            // FileRef left with the SOURCE id renders cross-tenant (403/404) for the authenticated path.
            Map<String, Object> body = new java.util.HashMap<>();
            body.put("newPath", fileRef.path());
            if (fileRef.id() != null) {
                body.put("newId", fileRef.id());
            }
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            log.error("Failed to copy file: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    private static String inferTenantIdFromStoragePath(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        if (path.contains("://")) {
            return null;
        }
        int idx = path.indexOf('/');
        if (idx <= 0) {
            return null;
        }
        return path.substring(0, idx);
    }

    /**
     * Cleanup application runs (epochs, signals, S3 files, run entities).
     */
    @PostMapping("/runs/cleanup-application")
    @Transactional
    public ResponseEntity<?> cleanupApplicationRuns(@RequestBody Map<String, Object> request) {
        try {
            UUID workflowId = UUID.fromString((String) request.get("workflowId"));
            String publicationId = (String) request.get("publicationId");
            String tenantId = (String) request.get("tenantId");

            List<WorkflowRunEntity> runs = workflowRunRepository.findAllByWorkflowIdAndSourceAndPublicationId(
                    workflowId, "application", publicationId);

            if (runs.isEmpty()) {
                return ResponseEntity.ok(Map.of("deletedRuns", 0));
            }

            List<String> runIds = runs.stream()
                    .map(WorkflowRunEntity::getRunIdPublic)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

            // 1. Delete S3 files
            for (String runId : runIds) {
                try {
                    fileStorageService.deleteRunFiles(tenantId, workflowId.toString(), runId);
                } catch (Exception e) {
                    log.warn("Failed to delete S3 files for run {}: {}", runId, e.getMessage());
                }
            }

            // 2. Delete epochs
            try { workflowEpochRepository.deleteByRunIds(runIds); } catch (Exception e) {
                log.warn("Failed to delete epochs: {}", e.getMessage());
            }

            // 3. Delete signal waits
            try { signalWaitRepository.deleteByRunIdIn(runIds); } catch (Exception e) {
                log.warn("Failed to delete signal waits: {}", e.getMessage());
            }

            // 4. Delete pending signals
            try { pendingSignalRepository.deleteByRunIdIn(runIds); } catch (Exception e) {
                log.warn("Failed to delete pending signals: {}", e.getMessage());
            }

            // 5. Delete run entities (DB cascades handle step_data etc.)
            workflowRunRepository.deleteAllByWorkflowIdAndSourceAndPublicationId(workflowId, "application", publicationId);

            return ResponseEntity.ok(Map.of("deletedRuns", runIds.size()));
        } catch (Exception e) {
            log.error("Failed to cleanup application runs: {}", e.getMessage(), e);
            return ResponseEntity.ok(Map.of("deletedRuns", 0)); // Fire-and-forget
        }
    }

    // ========== Category Operations ==========

    @GetMapping("/categories/{categoryId}")
    public ResponseEntity<Map<String, Object>> getCategoryById(@PathVariable UUID categoryId) {
        return categoryRepository.findById(categoryId)
                .map(cat -> {
                    Map<String, Object> result = new HashMap<>();
                    result.put("id", cat.getId().toString());
                    result.put("slug", cat.getSlug());
                    result.put("name", cat.getName());
                    result.put("iconSlug", cat.getIconSlug());
                    result.put("color", cat.getColor());
                    return ResponseEntity.ok(result);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
