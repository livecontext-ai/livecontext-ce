package com.apimarketplace.orchestrator.controllers.workflow;

import com.apimarketplace.common.scope.ScopeGuard;
import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.apimarketplace.orchestrator.controllers.dto.WorkflowPlanRequest;
import com.apimarketplace.orchestrator.controllers.dto.WorkflowResponseFactory;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.services.WorkflowIconExtractor;
import com.apimarketplace.orchestrator.services.WorkflowManagementService;
import com.apimarketplace.orchestrator.services.WorkflowPlanVersionService;
import com.apimarketplace.orchestrator.services.persistence.PinAwareTriggerSyncService;
import com.apimarketplace.orchestrator.trigger.TriggerTypeDetector;
import com.apimarketplace.trigger.client.TriggerClient;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Controller for Workflow CRUD operations.
 * Handles create, read, update, and delete operations for workflows.
 */
@RestController
@RequestMapping("/api/v2/workflows/dag")
@Validated
public class WorkflowCrudController {

    private static final Logger logger = LoggerFactory.getLogger(WorkflowCrudController.class);

    @Autowired
    private WorkflowRepository workflowRepository;

    @Autowired
    private WorkflowManagementService workflowManagementService;

    @Autowired
    private WorkflowResponseFactory responseFactory;

    @Autowired
    private WorkflowControllerHelper helper;

    @Autowired
    private TriggerClient triggerClient;

    @Autowired
    private TriggerTypeDetector triggerTypeDetector;

    @Autowired
    private WorkflowPlanVersionService versionService;

    @Autowired
    private StorageBreakdownService breakdownService;

    @Autowired(required = false)
    private PinAwareTriggerSyncService pinAwareTriggerSyncService;

    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @Autowired
    private com.apimarketplace.auth.client.access.OrgAccessGuard orgAccessGuard;

    /**
     * Saves a workflow DAG without executing it.
     * Creates a new workflow or updates an existing one.
     */
    @PostMapping
    public ResponseEntity<?> saveWorkflow(
            @Valid @RequestBody WorkflowPlanRequest request,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
            @RequestHeader(value = "X-Organization-Role", required = false) String orgRole) {
        try {
            logger.info("Workflow save request received, X-User-ID: {}", userId);

            if (userId == null || userId.isBlank()) {
                logger.error("X-User-ID header is missing");
                return ResponseEntity.badRequest().body(responseFactory.createFailureResponse("X-User-ID header is required"));
            }

            WorkflowPlan plan = workflowManagementService.parsePlanWithTenantId(request.getPlanJson(), userId);

            UUID workflowId = null;
            if (request.getWorkflowId() != null && !request.getWorkflowId().isBlank()) {
                try {
                    workflowId = UUID.fromString(request.getWorkflowId());
                } catch (IllegalArgumentException e) {
                    logger.warn("Invalid workflowId in request: {}", request.getWorkflowId());
                }
            }
            // Plan resource limit check is enforced inside WorkflowManagementService.saveWorkflow
            // so both REST and shared-agent-lib paths get the same protection.

            WorkflowManagementService.SaveResult result = workflowManagementService.saveWorkflow(
                plan,
                new HashMap<>(request.getDataInputs()),
                workflowId,
                organizationId,
                orgRole
            );

            Map<String, Object> response = workflowManagementService.buildSaveResponse(result);
            return ResponseEntity.ok(response);

        } catch (IllegalStateException e) {
            // WorkflowManagementService.saveWorkflow throws IllegalStateException for
            // "workflow not found" + "workflow does not belong to tenant" - both are
            // cross-scope. Return 404 to avoid leaking existence (consistent with
            // WorkflowRunController.* which already uses 404 on isRunInScope failure).
            logger.warn("Cannot save workflow: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(responseFactory.createFailureResponse(e.getMessage()));
        } catch (IllegalArgumentException e) {
            logger.error("Invalid workflow plan format", e);
            return ResponseEntity.badRequest().body(responseFactory.createFailureResponse("Invalid workflow plan format: " + e.getMessage()));
        } catch (com.apimarketplace.auth.client.access.OrgAccessDeniedException e) {
            // Org per-resource write-gate (member restricted to READ/DENY on this workflow).
            // Re-throw so the global handler maps it to 403 instead of catch(Exception) → 500.
            throw e;
        } catch (Exception e) {
            logger.error("Error saving workflow", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(responseFactory.createFailureResponse("Internal server error: " + e.getMessage()));
        }
    }

    /**
     * Gets a workflow by its ID.
     * Requires tenant authentication - only returns workflows owned by the requesting tenant.
     */
    @GetMapping("/{workflowId}")
    public ResponseEntity<?> getWorkflow(
            @PathVariable("workflowId") String workflowId,
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId,
            @RequestHeader(value = "X-Organization-Role", required = false) String orgRole) {
        try {
            UUID id = UUID.fromString(workflowId);
            Optional<WorkflowEntity> workflowOpt = workflowManagementService.getWorkflow(id);

            if (workflowOpt.isEmpty()) {
                return ResponseEntity.notFound().build();
            }

            WorkflowEntity workflow = workflowOpt.get();

            // Strict-isolation scope (2026-05-18, ScopeGuard alignment). The
            // active workspace (org or default personal org) matches only
            // rows tagged with that org id. Closes the workspace-mismatch
            // leak that R3-R7 fixed elsewhere but had not yet reached this
            // entry point. Post-V261 (2026-05-19) every workflow row carries
            // a non-null organization_id so the personal-strict IS NULL
            // branch of {@link ScopeGuard#isInStrictScope} is unreachable
            // for normal traffic.
            if (!ScopeGuard.isInStrictScope(tenantId, orgId,
                    workflow.getTenantId(), workflow.getOrganizationId())) {
                logger.warn("Tenant {} attempted to access workflow {} out of scope (owner={} workflow-org={} caller-org={})",
                    tenantId, workflowId, workflow.getTenantId(), workflow.getOrganizationId(), orgId);
                return ResponseEntity.notFound().build();
            }

            String workflowOrgId = workflow.getOrganizationId();
            if (workflowOrgId != null
                    && !orgAccessGuard.canAccess(workflowOrgId, tenantId, "workflow", id.toString(), orgRole)) {
                logger.warn("OrgAccess deny-list: user {} restricted from reading workflow {} in org {}",
                        tenantId, id, workflowOrgId);
                throw new com.apimarketplace.auth.client.access.OrgAccessDeniedException(
                        "workflow", id.toString());
            }

            Map<String, Object> response = helper.buildWorkflowResponse(workflow);
            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid workflow ID format"));
        } catch (com.apimarketplace.auth.client.access.OrgAccessDeniedException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Error getting workflow: {}", workflowId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Internal server error: " + e.getMessage()));
        }
    }

    /**
     * Updates the plan for an existing workflow.
     * Requires tenant authentication - only allows updates to workflows owned by the requesting tenant.
     */
    @PutMapping("/{workflowId}/plan")
    public ResponseEntity<?> updateWorkflowPlan(
            @PathVariable("workflowId") String workflowId,
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String headerOrgId,
            @RequestHeader(value = "X-Organization-Role", required = false) String orgRole,
            @RequestBody Map<String, Object> request) {
        try {
            logger.info("Updating plan for workflow: {}, tenantId: {}, orgId: {}, orgRole: {}",
                    workflowId, tenantId, headerOrgId, orgRole);

            UUID id;
            try {
                id = UUID.fromString(workflowId);
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().body(Map.of("error", "Invalid workflow ID format"));
            }

            Optional<WorkflowEntity> workflowOpt = workflowRepository.findById(id);
            if (workflowOpt.isEmpty()) {
                return ResponseEntity.notFound().build();
            }

            WorkflowEntity workflow = workflowOpt.get();

            // Strict-isolation scope (2026-05-18, ScopeGuard alignment). The
            // write visibility gate runs after (PR-2.f).
            if (!ScopeGuard.isInStrictScope(tenantId, headerOrgId,
                    workflow.getTenantId(), workflow.getOrganizationId())) {
                logger.warn("Tenant {} attempted to update workflow {} out of scope (owner={} org={} caller-org={})",
                    tenantId, workflowId, workflow.getTenantId(), workflow.getOrganizationId(), headerOrgId);
                return ResponseEntity.notFound().build();
            }

            // PR-2.e (V2 D4 fix): org-scoped deny-list enforcement on plan update.
            // updateWorkflowPlan can inject agent:/mcp:/trigger: nodes that execute
            // with OWNER privileges. A future PR-2.e.1 (PlanDiffer) may relax this
            // to "only sensitive diffs require write access" - for now the gate is on
            // every plan update if the workflow is org-scoped.
            String orgId = workflow.getOrganizationId();
            if (orgId != null && isViewerRole(orgRole)) {
                logger.warn("OrgAccess denied: VIEWER user {} attempted to update plan of workflow {} in org {}",
                        tenantId, workflowId, orgId);
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "VIEWER role cannot modify workflows"));
            }
            if (orgId != null
                    && !orgAccessGuard.canWrite(orgId, tenantId, "workflow", id.toString(), orgRole)) {
                logger.warn("OrgAccess denied: user {} restricted from updating plan of workflow {} in org {}",
                        tenantId, workflowId, orgId);
                throw new com.apimarketplace.auth.client.access.OrgAccessDeniedException(
                        "workflow", id.toString());
            }

            // APPLICATION-type workflows are run-only acquired clones - their plan is the
            // contract the marketplace acquirer received, not editable in place. Editing now
            // lives in the DECOUPLED editable WORKFLOW twin that acquiring also creates
            // (sourcePublicationId=null, visible in /app/workflows). If the acquirer wants the
            // original published plan back on the application itself, they use
            // POST /workflows/{id}/reset-plan (restores from basePlan). Letting the plan drift
            // here would break the publish/acquire isolation guarantee and silently diverge
            // from basePlan, with no way back to the acquired state once a single mutating call
            // lands.
            if (workflow.isApplication()) {
                logger.warn("Refused plan update on APPLICATION workflow {}: applications are run-only acquired clones",
                        workflowId);
                return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                        "error", "Cannot update the plan of an APPLICATION workflow. " +
                                 "Applications are run-only acquired clones - edit the decoupled workflow in " +
                                 "/app/workflows, or use POST /workflows/" + workflowId +
                                 "/reset-plan to restore from basePlan.",
                        "code", "APPLICATION_PLAN_IMMUTABLE"));
            }

            Object planObj = request.get("plan");
            if (planObj == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "Plan is required"));
            }

            Map<String, Object> planMap = helper.convertToPlanMap(planObj);

            // Save the new plan first
            Map<String, Object> oldPlan = workflow.getPlan();
            workflow.setPlan(planMap);
            workflow.setNodeIcons(WorkflowIconExtractor.extractNodeIcons(planMap));
            workflow.setUpdatedAt(Instant.now());

            // Update workflow name if provided in the plan
            Object planName = planMap.get("name");
            if (planName instanceof String && !((String) planName).isBlank()) {
                workflow.setName((String) planName);
                logger.info("Workflow name updated to: {}", planName);
            }

            // Update workflow description if provided in the plan
            Object planDescription = planMap.get("description");
            if (planDescription instanceof String) {
                workflow.setDescription((String) planDescription);
            }

            // Note: Webhook tokens are now synced in WorkflowManagementService.syncWebhookToken()
            // This endpoint only updates the plan, tokens are managed separately

            long oldPlanSize = estimatePlanSize(oldPlan);
            workflowRepository.save(workflow);
            long newPlanSize = estimatePlanSize(planMap);
            // Issue #149 - thread orgId so team-workspace CONFIGURATION usage tracks edits.
            breakdownService.trackSizeChange(tenantId, "CONFIGURATION", newPlanSize - oldPlanSize, workflow.getOrganizationId());
            logger.info("Workflow plan updated successfully: {}", workflowId);

            // Create version of the NEW plan (only if changed from latest version)
            int currentVersion = versionService.createVersion(id, planMap, tenantId);

            // Pin-aware trigger sync - propagate plan changes to schedules / webhooks /
            // chat / form / datasource. Without this, edits via the builder PUT path
            // never reach trigger-service, leaving stale cron / endpoint config
            // (e.g. workflow scheduled every 10 min while the plan says 1 h).
            String triggerSyncWarning = null;
            if (pinAwareTriggerSyncService != null) {
                try {
                    WorkflowPlan parsedPlan = WorkflowPlan.fromMap(planMap, id.toString(), tenantId);
                    pinAwareTriggerSyncService.syncAllTriggersFromPlan(workflow, parsedPlan);
                } catch (com.apimarketplace.orchestrator.services.persistence.TriggerSyncWarningException e) {
                    // Sync completed but with security-relevant warnings the user must see.
                    triggerSyncWarning = e.getMessage();
                    logger.warn("Trigger sync warning for workflow {}: {}", workflowId, e.getMessage());
                } catch (Exception e) {
                    triggerSyncWarning = e.getMessage();
                    logger.warn("Failed to sync triggers for workflow {} after plan update: {}",
                            workflowId, e.getMessage());
                }
            }

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Workflow plan updated successfully");
            response.put("workflowId", workflowId);
            response.put("updatedAt", workflow.getUpdatedAt());
            response.put("planVersion", currentVersion);
            if (triggerSyncWarning != null) {
                response.put("triggerSyncWarning", triggerSyncWarning);
            }
            Map<String, String> tokens = triggerClient.getTokensForWorkflow(workflow.getId());
            if (!tokens.isEmpty()) {
                response.put("webhookTokens", tokens);
            }

            return ResponseEntity.ok(response);

        } catch (com.apimarketplace.auth.client.access.OrgAccessDeniedException e) {
            // PR-2.e: let the global advice in auth-client map this to 403.
            logger.warn("OrgAccess denied on updateWorkflowPlan {}: {}", workflowId, e.getMessage());
            throw e;
        } catch (Exception e) {
            logger.error("Error updating workflow plan: {}", workflowId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to update workflow plan: " + e.getMessage()));
        }
    }

    /**
     * Updates the lifecycle status of a workflow (DRAFT <-> ACTIVE toggle).
     */
    @PatchMapping("/{workflowId}/status")
    public ResponseEntity<?> updateWorkflowStatus(
            @PathVariable("workflowId") String workflowId,
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader(value = "X-Organization-Role", required = false) String orgRole,
            @RequestBody Map<String, String> request) {
        try {
            String statusStr = request.get("status");
            if (statusStr == null || statusStr.isBlank()) {
                return ResponseEntity.badRequest()
                    .body(responseFactory.createFailureResponse("Missing 'status' field in request body"));
            }

            WorkflowEntity.WorkflowStatus newStatus;
            try {
                newStatus = WorkflowEntity.WorkflowStatus.valueOf(statusStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest()
                    .body(responseFactory.createFailureResponse("Invalid status: " + statusStr + ". Allowed values: DRAFT, ACTIVE"));
            }

            UUID id = UUID.fromString(workflowId);
            WorkflowEntity updated = workflowManagementService.updateWorkflowStatus(id, tenantId, newStatus, orgRole);

            return ResponseEntity.ok(Map.of(
                "success", true,
                "status", updated.getStatus().toString(),
                "workflowId", workflowId
            ));

        } catch (com.apimarketplace.auth.client.access.OrgAccessDeniedException e) {
            // PR-2.c: let the global advice in auth-client map this to 403.
            // Must come BEFORE the catch-all below which would remap to 500.
            logger.warn("OrgAccess denied on updateWorkflowStatus {}: {}", workflowId, e.getMessage());
            throw e;
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(responseFactory.createFailureResponse(e.getMessage()));
        } catch (IllegalStateException e) {
            // Cross-scope / not-found: standardize on 404 (same as WorkflowRunController.*).
            logger.warn("Cannot update workflow status: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(responseFactory.createFailureResponse(e.getMessage()));
        } catch (Exception e) {
            logger.error("Error updating workflow status: {}", workflowId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(responseFactory.createFailureResponse("Internal server error: " + e.getMessage()));
        }
    }

    /**
     * Clones a workflow by its ID, creating a copy with "(Copy)" suffix.
     */
    @PostMapping("/{workflowId}/clone")
    public ResponseEntity<?> cloneWorkflow(
            @PathVariable("workflowId") String workflowId,
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader(value = "X-Organization-Role", required = false) String orgRole) {
        try {
            UUID id = UUID.fromString(workflowId);
            WorkflowEntity cloned = workflowManagementService.cloneWorkflow(id, tenantId, orgRole);
            return ResponseEntity.ok(Map.of("id", cloned.getId(), "name", cloned.getName()));
        } catch (com.apimarketplace.auth.client.access.OrgAccessDeniedException e) {
            // PR-2.c: let the global advice in auth-client map this to 403.
            logger.warn("OrgAccess denied on cloneWorkflow {}: {}", workflowId, e.getMessage());
            throw e;
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Error cloning workflow: {}", workflowId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to clone workflow: " + e.getMessage()));
        }
    }

    /**
     * Reset an APPLICATION workflow's plan to its basePlan (immutable reference).
     */
    @PostMapping("/{workflowId}/reset-plan")
    public ResponseEntity<?> resetApplicationPlan(
            @PathVariable("workflowId") String workflowId,
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId) {
        try {
            UUID id = UUID.fromString(workflowId);
            WorkflowEntity updated = workflowManagementService.resetApplicationPlan(id, tenantId, orgId);
            return ResponseEntity.ok(Map.of(
                "success", true,
                "workflowId", workflowId,
                "message", "Application plan reset to base plan"
            ));
        } catch (com.apimarketplace.auth.client.access.OrgAccessDeniedException e) {
            // Org per-resource write-gate (member restricted to READ/DENY on this application).
            // Re-throw so the global handler maps it to 403 instead of catch(Exception) → 500.
            throw e;
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Error resetting application plan: {}", workflowId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to reset application plan: " + e.getMessage()));
        }
    }

    /**
     * Deletes a workflow by its ID.
     */
    @DeleteMapping("/{workflowId}")
    public ResponseEntity<?> deleteWorkflow(
            @PathVariable("workflowId") String workflowId,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Organization-Role", required = false) String orgRole) {
        try {
            logger.info("Deleting workflow: {}, X-User-ID: {}, X-Organization-Role: {}",
                    workflowId, userId, orgRole);

            UUID id;
            try {
                id = UUID.fromString(workflowId);
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().body(Map.of("error", "Invalid workflow ID format"));
            }

            boolean deleted = workflowManagementService.deleteWorkflow(id, userId, orgRole);

            if (deleted) {
                logger.info("Workflow deleted successfully: {}", workflowId);
                return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Workflow deleted successfully",
                    "workflowId", workflowId
                ));
            } else {
                logger.warn("Workflow not found or not authorized: {}", workflowId);
                return ResponseEntity.notFound().build();
            }

        } catch (com.apimarketplace.auth.client.access.OrgAccessDeniedException e) {
            // PR-2: org-scoped deny-list rejected the write. 403 via @ResponseStatus
            // on the exception ; explicit re-throw avoids the catch-all 500 below.
            logger.warn("OrgAccess denied on deleteWorkflow {}: {}", workflowId, e.getMessage());
            throw e;
        } catch (Exception e) {
            logger.error("Error deleting workflow: {}", workflowId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Internal server error: " + e.getMessage()));
        }
    }

    private long estimatePlanSize(Map<String, Object> plan) {
        if (plan == null || plan.isEmpty()) return 0;
        try {
            return objectMapper.writeValueAsBytes(plan).length;
        } catch (Exception e) {
            return 0;
        }
    }

    private static boolean isViewerRole(String orgRole) {
        return orgRole != null && "VIEWER".equalsIgnoreCase(orgRole.trim());
    }
}
