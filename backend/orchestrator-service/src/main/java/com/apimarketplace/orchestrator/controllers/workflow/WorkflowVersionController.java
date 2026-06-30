package com.apimarketplace.orchestrator.controllers.workflow;

import com.apimarketplace.auth.client.access.OrgAccessDeniedException;
import com.apimarketplace.auth.client.access.OrgAccessGuard;
import com.apimarketplace.common.plan.PlanStripUtils;
import com.apimarketplace.common.scope.ScopeGuard;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowPlanVersionEntity;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.WorkflowManagementService;
import com.apimarketplace.orchestrator.services.WorkflowPinService;
import com.apimarketplace.orchestrator.services.WorkflowPlanVersionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Controller for workflow plan version history.
 * Provides endpoints to list, view, restore, and rename versions.
 */
@RestController
@RequestMapping("/api/v2/workflows/dag")
public class WorkflowVersionController {

    private static final Logger logger = LoggerFactory.getLogger(WorkflowVersionController.class);

    private final WorkflowPlanVersionService versionService;
    private final WorkflowRepository workflowRepository;
    private final WorkflowRunRepository workflowRunRepository;
    private final WorkflowPinService pinService;
    private final ObjectMapper objectMapper;
    private final OrgAccessGuard orgAccessGuard;
    private final WorkflowManagementService workflowManagementService;

    public WorkflowVersionController(WorkflowPlanVersionService versionService,
                                      WorkflowRepository workflowRepository,
                                      WorkflowRunRepository workflowRunRepository,
                                      WorkflowPinService pinService,
                                      ObjectMapper objectMapper,
                                      OrgAccessGuard orgAccessGuard,
                                      WorkflowManagementService workflowManagementService) {
        this.versionService = versionService;
        this.workflowRepository = workflowRepository;
        this.workflowRunRepository = workflowRunRepository;
        this.pinService = pinService;
        this.objectMapper = objectMapper;
        this.orgAccessGuard = orgAccessGuard;
        this.workflowManagementService = workflowManagementService;
    }

    /**
     * List all versions for a workflow (metadata only, no plan body).
     */
    @GetMapping("/{workflowId}/versions")
    public ResponseEntity<?> listVersions(
            @PathVariable("workflowId") String workflowId,
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId) {
        try {
            UUID id = UUID.fromString(workflowId);

            if (!verifyOwnership(id, tenantId, orgId)) {
                return ResponseEntity.notFound().build();
            }

            List<WorkflowPlanVersionEntity> versions = versionService.listVersions(id);
            int currentVersion = versionService.getCurrentVersion(id);

            Map<Integer, Long> runCountMap = workflowRunRepository.countRunsByPlanVersion(id).stream()
                    .collect(Collectors.toMap(
                            r -> (Integer) r[0],
                            r -> (Long) r[1]
                    ));

            List<Map<String, Object>> versionList = versions.stream()
                    .map(v -> {
                        Map<String, Object> map = new LinkedHashMap<>();
                        map.put("version", v.getVersion());
                        map.put("label", v.getLabel());
                        map.put("nodeCount", countNodes(v.getPlan()));
                        map.put("runCount", runCountMap.getOrDefault(v.getVersion(), 0L));
                        map.put("createdAt", v.getCreatedAt());
                        map.put("createdBy", v.getCreatedBy());
                        return map;
                    })
                    .toList();

            // Include pinned version info for frontend badge/indicator
            Optional<WorkflowEntity> wf = workflowRepository.findById(id);
            Integer pinnedVersion = wf.map(WorkflowEntity::getPinnedVersion).orElse(null);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("versions", versionList);
            response.put("currentVersion", currentVersion);
            response.put("pinnedVersion", pinnedVersion);
            response.put("totalVersions", versionList.size());

            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid workflow ID format"));
        } catch (Exception e) {
            logger.error("Error listing versions for workflow: {}", workflowId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to list versions: " + e.getMessage()));
        }
    }

    /**
     * Get a specific version with its full plan.
     */
    @GetMapping("/{workflowId}/versions/{version}")
    public ResponseEntity<?> getVersion(
            @PathVariable("workflowId") String workflowId,
            @PathVariable("version") int version,
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId) {
        try {
            UUID id = UUID.fromString(workflowId);

            if (!verifyOwnership(id, tenantId, orgId)) {
                return ResponseEntity.notFound().build();
            }

            Optional<WorkflowPlanVersionEntity> versionOpt = versionService.getVersion(id, version);
            if (versionOpt.isEmpty()) {
                return ResponseEntity.notFound().build();
            }

            WorkflowPlanVersionEntity v = versionOpt.get();
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("version", v.getVersion());
            response.put("label", v.getLabel());
            response.put("plan", v.getPlan());
            response.put("createdAt", v.getCreatedAt());
            response.put("createdBy", v.getCreatedBy());

            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid workflow ID format"));
        } catch (Exception e) {
            logger.error("Error getting version {} for workflow: {}", version, workflowId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to get version: " + e.getMessage()));
        }
    }

    /**
     * Restore a version: copies the versioned plan back to workflows.plan
     * and creates a new version from the current plan before restoring.
     */
    @PostMapping("/{workflowId}/versions/{version}/restore")
    public ResponseEntity<?> restoreVersion(
            @PathVariable("workflowId") String workflowId,
            @PathVariable("version") int version,
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId,
            @RequestHeader(value = "X-Organization-Role", required = false) String orgRole) {
        try {
            UUID id = UUID.fromString(workflowId);

            Optional<WorkflowEntity> workflowOpt = workflowRepository.findById(id);
            if (workflowOpt.isEmpty()) {
                return ResponseEntity.notFound().build();
            }

            WorkflowEntity workflow = workflowOpt.get();
            // Strict-isolation scope (2026-05-18, ScopeGuard alignment).
            if (!ScopeGuard.isInStrictScope(tenantId, orgId,
                    workflow.getTenantId(), workflow.getOrganizationId())) {
                return ResponseEntity.notFound().build();
            }

            // Org per-resource write-gate. restoreVersion rewrites workflows.plan
            // (setPlan + save below) - the EXACT mutation the saveWorkflow gate
            // blocks. A member restricted to READ on this workflow must not be able
            // to side-step that gate through the version-restore route. OWNER/ADMIN
            // bypass + null-orgRole-still-enforces are inherited from canWrite.
            String entityOrgId = workflow.getOrganizationId();
            if (entityOrgId != null
                    && !orgAccessGuard.canWrite(entityOrgId, tenantId, "workflow", id.toString(), orgRole)) {
                logger.warn("OrgAccess denied: user {} restricted from restoring version {} of workflow {} in org {}",
                        tenantId, version, workflowId, entityOrgId);
                throw new OrgAccessDeniedException("workflow", id.toString());
            }

            // APPLICATION-type workflows are acquired marketplace clones. The OWNER may
            // now customize their installed clone in place via PUT /plan
            // (WorkflowCrudController.updateWorkflowPlan), and every such edit accrues a
            // plan version - so the OWNER must also be able to roll a single step back by
            // restoring one of those versions. basePlan stays the immutable floor
            // (POST /workflows/{id}/reset-plan still reverts to the acquired original).
            // We still enforce the application-scoped org deny-list: the generic "workflow"
            // gate above never catches an application-level restriction. Mirrors the gate
            // updateWorkflowPlan / reset-plan run (single source of truth in
            // WorkflowManagementService.assertApplicationInstanceWritable).
            if (workflow.isApplication()) {
                workflowManagementService.assertApplicationInstanceWritable(workflow, tenantId, orgRole);
            }

            Optional<WorkflowPlanVersionEntity> versionOpt = versionService.getVersion(id, version);
            if (versionOpt.isEmpty()) {
                return ResponseEntity.notFound().build();
            }

            // Restore the old plan (no archiving - current plan is already versioned from the last save).
            // F4 PUB-HIJACK defense-in-depth: deep-copy + strip standalone trigger row UUIDs.
            // Historical versions saved before V206 may contain `params.scheduleId/webhookId/...`
            // that would conflict with the now-immutable workflow_id DB trigger if a sync tried
            // to rebind. Stripping at restore ensures sync creates fresh standalone rows.
            WorkflowPlanVersionEntity versionEntity = versionOpt.get();
            workflow.setPlan(PlanStripUtils.deepCopyAndStrip(versionEntity.getPlan(), objectMapper));
            workflow.setUpdatedAt(java.time.Instant.now());
            workflowRepository.save(workflow);

            int newCurrentVersion = versionService.getCurrentVersion(id);

            logger.info("Restored workflow {} to version {}, new current version: {}",
                    workflowId, version, newCurrentVersion);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("message", "Version " + version + " restored successfully");
            response.put("restoredFromVersion", version);
            response.put("currentVersion", newCurrentVersion);
            response.put("plan", workflow.getPlan());

            return ResponseEntity.ok(response);

        } catch (OrgAccessDeniedException e) {
            // Org per-resource write-gate denied. Re-throw so the global advice maps
            // it to 403 (@ResponseStatus FORBIDDEN) instead of catch(Exception) → 500.
            logger.warn("OrgAccess denied on restoreVersion {}: {}", workflowId, e.getMessage());
            throw e;
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid parameters: " + e.getMessage()));
        } catch (Exception e) {
            logger.error("Error restoring version {} for workflow: {}", version, workflowId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to restore version: " + e.getMessage()));
        }
    }

    /**
     * Rename a version (set/update its label).
     */
    @PatchMapping("/{workflowId}/versions/{version}")
    public ResponseEntity<?> renameVersion(
            @PathVariable("workflowId") String workflowId,
            @PathVariable("version") int version,
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId,
            @RequestHeader(value = "X-Organization-Role", required = false) String orgRole,
            @RequestBody Map<String, Object> request) {
        try {
            UUID id = UUID.fromString(workflowId);

            Optional<WorkflowEntity> workflowOpt = workflowRepository.findById(id);
            if (workflowOpt.isEmpty()) {
                return ResponseEntity.notFound().build();
            }
            WorkflowEntity workflow = workflowOpt.get();
            // Strict-isolation scope (2026-05-18, ScopeGuard alignment).
            if (!ScopeGuard.isInStrictScope(tenantId, orgId,
                    workflow.getTenantId(), workflow.getOrganizationId())) {
                return ResponseEntity.notFound().build();
            }

            // Org per-resource write-gate. renameVersion mutates version-label
            // metadata; a READ-restricted member must not modify it. OWNER/ADMIN
            // bypass + null-orgRole-still-enforces are inherited from canWrite.
            String entityOrgId = workflow.getOrganizationId();
            if (entityOrgId != null
                    && !orgAccessGuard.canWrite(entityOrgId, tenantId, "workflow", id.toString(), orgRole)) {
                logger.warn("OrgAccess denied: user {} restricted from renaming version {} of workflow {} in org {}",
                        tenantId, version, workflowId, entityOrgId);
                throw new OrgAccessDeniedException("workflow", id.toString());
            }

            String label = request.get("label") != null ? request.get("label").toString() : null;

            WorkflowPlanVersionEntity updated = versionService.renameVersion(id, version, label);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("version", updated.getVersion());
            response.put("label", updated.getLabel());

            return ResponseEntity.ok(response);

        } catch (OrgAccessDeniedException e) {
            logger.warn("OrgAccess denied on renameVersion {}: {}", workflowId, e.getMessage());
            throw e;
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Error renaming version {} for workflow: {}", version, workflowId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to rename version: " + e.getMessage()));
        }
    }

    /**
     * Pin a specific version as the production version.
     * When pinned, triggers use this version's plan instead of the latest.
     * Send { "version": null } to unpin (revert to latest behavior).
     */
    @PatchMapping("/{workflowId}/pin")
    public ResponseEntity<?> pinVersion(
            @PathVariable("workflowId") String workflowId,
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId,
            @RequestHeader(value = "X-Organization-Role", required = false) String orgRole,
            @RequestBody Map<String, Object> request) {
        try {
            UUID id = UUID.fromString(workflowId);
            Object versionObj = request.get("version");
            Integer version = versionObj != null ? ((Number) versionObj).intValue() : null;

            // Org per-resource write-gate. pinVersion flips the production version
            // pointer - a mutation a READ-restricted member must not perform. Gate
            // BEFORE pinService.pin (which itself only does a scope check, no
            // canWrite). pinService keeps its own constructor/signature unchanged;
            // we fetch the entity through the repository the controller already
            // holds to read organizationId. OWNER/ADMIN bypass + null-orgRole-
            // still-enforces are inherited from canWrite. A not-found / out-of-scope
            // workflow is left for pinService.pin to resolve (NotFound/Forbidden →
            // 404) so the existence-leak behaviour is unchanged.
            Optional<WorkflowEntity> workflowOpt = workflowRepository.findById(id);
            if (workflowOpt.isPresent()) {
                String entityOrgId = workflowOpt.get().getOrganizationId();
                if (entityOrgId != null
                        && !orgAccessGuard.canWrite(entityOrgId, tenantId, "workflow", id.toString(), orgRole)) {
                    logger.warn("OrgAccess denied: user {} restricted from pinning workflow {} in org {}",
                            tenantId, workflowId, entityOrgId);
                    throw new OrgAccessDeniedException("workflow", id.toString());
                }
            }

            WorkflowPinService.PinResult result = pinService.pin(id, tenantId, orgId, version);
            return switch (result) {
                case WorkflowPinService.PinResult.NotFound ignored -> ResponseEntity.notFound().build();
                case WorkflowPinService.PinResult.Forbidden ignored -> ResponseEntity.notFound().build();
                case WorkflowPinService.PinResult.VersionNotFound vnf ->
                        ResponseEntity.badRequest().body(Map.of("error", "Version " + vnf.version() + " not found"));
                case WorkflowPinService.PinResult.NoSuccessfulRun nsr ->
                        ResponseEntity.badRequest().body(Map.of("error",
                                "No successful run exists for version " + nsr.version()
                                        + ". Start a run with this version first."));
                case WorkflowPinService.PinResult.Success s -> {
                    Map<String, Object> response = new LinkedHashMap<>();
                    response.put("success", true);
                    response.put("pinnedVersion", s.pinnedVersion());
                    // Public runId of the production run that backs this pin - null on unpin
                    // or when no trusted run exists yet. Frontend uses it to redirect to
                    // /run/{id} so the user can watch live ticks (the builder edit URL is
                    // not subscribed to the WS channel - see WorkflowModeContext).
                    response.put("productionRunIdPublic", s.productionRunIdPublic());
                    response.put("message", s.pinnedVersion() != null
                            ? "Version " + s.pinnedVersion() + " pinned as production"
                            : "Unpinned - triggers will use latest version");
                    yield ResponseEntity.ok(response);
                }
            };

        } catch (OrgAccessDeniedException e) {
            logger.warn("OrgAccess denied on pinVersion {}: {}", workflowId, e.getMessage());
            throw e;
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid parameters: " + e.getMessage()));
        } catch (Exception e) {
            logger.error("Error pinning version for workflow: {}", workflowId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to pin version: " + e.getMessage()));
        }
    }

    private int countNodes(Map<String, Object> plan) {
        if (plan == null) return 0;
        int count = 0;
        for (String key : List.of("triggers", "mcps", "cores", "agents", "tables", "interfaces")) {
            Object list = plan.get(key);
            if (list instanceof List<?> items) {
                count += items.size();
            }
        }
        return count;
    }

    private boolean verifyOwnership(UUID workflowId, String tenantId) {
        return verifyOwnership(workflowId, tenantId, null);
    }

    /**
     * Strict-isolation scope verification (2026-05-18, ScopeGuard alignment).
     * Used by the version-drawer (list/restore) + pin actions. Returns false
     * when the workflow does not exist OR is not in the caller's active
     * workspace.
     */
    private boolean verifyOwnership(UUID workflowId, String tenantId, String orgId) {
        Optional<WorkflowEntity> workflowOpt = workflowRepository.findById(workflowId);
        if (workflowOpt.isEmpty()) {
            return false;
        }
        WorkflowEntity wf = workflowOpt.get();
        return ScopeGuard.isInStrictScope(tenantId, orgId,
                wf.getTenantId(), wf.getOrganizationId());
    }
}
