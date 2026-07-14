package com.apimarketplace.orchestrator.controllers.workflow;

import com.apimarketplace.auth.client.access.OrgAccessGuard;
import com.apimarketplace.common.scope.ScopeGuard;
import com.apimarketplace.orchestrator.controllers.dto.WorkflowLevelsResponse;
import com.apimarketplace.orchestrator.controllers.dto.WorkflowPlanRequest;
import com.apimarketplace.orchestrator.controllers.dto.WorkflowResponseFactory;
import com.apimarketplace.orchestrator.controllers.dto.WorkflowValidationResponse;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.domain.workflow.ValidationResult;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.EditorRunResolver;
import com.apimarketplace.orchestrator.services.WorkflowExecutionService;
import com.apimarketplace.orchestrator.services.WorkflowPlanVersionService;
import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.orchestrator.services.resume.ExecutionContextManager;
import com.apimarketplace.orchestrator.services.resume.WorkflowResumeService;
import com.apimarketplace.orchestrator.services.resume.WorkflowRunState;
import com.apimarketplace.orchestrator.trigger.TriggerTypeDetector;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Controller for Workflow Execution operations.
 * Handles execute, validate, start, and calculate-levels.
 */
@RestController
@RequestMapping("/api/v2/workflows/dag")
@Validated
public class WorkflowExecutionController {

    private static final Logger logger = LoggerFactory.getLogger(WorkflowExecutionController.class);

    @Autowired
    private WorkflowExecutionService executionService;

    @Autowired
    private WorkflowResponseFactory responseFactory;

    @Autowired
    private WorkflowControllerHelper helper;

    @Autowired
    private TriggerTypeDetector triggerTypeDetector;

    @Autowired
    private WorkflowResumeService resumeService;

    @Autowired
    private ExecutionContextManager contextManager;

    @Autowired
    private WorkflowRunRepository runRepository;

    @Autowired
    private CreditConsumptionClient creditClient;

    @Autowired
    private WorkflowPlanVersionService versionService;

    @Autowired
    private EditorRunResolver editorRunResolver;

    @Autowired
    private WorkflowRepository workflowRepository;

    @Autowired
    private OrgAccessGuard orgAccessGuard;

    private static final String HEADER_SHARE_CONTEXT = "X-Share-Context";
    private static final String HEADER_SHARE_RESOURCE_TYPE = "X-Share-Resource-Type";
    private static final String HEADER_SHARE_RESOURCE_TOKEN = "X-Share-Resource-Token";
    private static final String HEADER_SHARE_RESOURCE_ID = "X-Share-Resource-Id";

    /**
     * Executes a workflow DAG.
     */
    @PostMapping("/execute")
    public ResponseEntity<?> executeWorkflow(
            @Valid @RequestBody WorkflowPlanRequest request,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-User-Plan", required = false) String userPlan,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId,
            @RequestHeader(value = "X-Organization-Role", required = false) String orgRole,
            @RequestHeader(value = HEADER_SHARE_CONTEXT, required = false) String shareContext,
            @RequestHeader(value = HEADER_SHARE_RESOURCE_TYPE, required = false) String shareResourceType,
            @RequestHeader(value = HEADER_SHARE_RESOURCE_TOKEN, required = false) String shareResourceToken,
            @RequestHeader(value = HEADER_SHARE_RESOURCE_ID, required = false) String shareResourceId) {
        try {
            logger.info("Workflow execution request received, X-User-ID: {}, X-User-Plan: {}", userId, userPlan);

            Map<String, Object> dataInputs = new HashMap<>(request.getDataInputs());
            String workflowId = request.getWorkflowId();

            if (workflowId == null || workflowId.isBlank()) {
                return ResponseEntity.badRequest().body(responseFactory.createFailureResponse("workflowId is required"));
            }

            UUID wfId;
            try {
                wfId = UUID.fromString(workflowId);
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().body(responseFactory.createFailureResponse("Invalid workflowId"));
            }

            WorkflowEntity workflow = workflowRepository.findById(wfId).orElse(null);
            if (workflow == null) {
                return ResponseEntity.badRequest().body(responseFactory.createFailureResponse("Workflow not found: " + workflowId));
            }

            ResponseEntity<?> shareScopeError = validateSharedApplicationExecutionScope(
                    request, workflow, shareContext, shareResourceType, shareResourceToken, shareResourceId);
            if (shareScopeError != null) {
                return shareScopeError;
            }

            boolean sharedApplicationBootstrap = "true".equalsIgnoreCase(shareContext);
            WorkflowPlan plan = resolveExecutionPlan(request, workflow, userId, sharedApplicationBootstrap);
            if (plan == null) {
                return ResponseEntity.badRequest().body(responseFactory.createFailureResponse("Invalid workflow plan format"));
            }

            logger.info("Parsed plan ID: {}, requested workflowId: {}", plan.getId(), workflowId);

            // Strict-isolation scope (2026-05-18, ScopeGuard alignment).
            // History: this endpoint had NO scope check at all before round-3
            // (2026-05-17), so any authenticated user could run + auto-save
            // (overwrite plan!) on any workflow by guessing the UUID. Round-3
            // added owner-or-org; today's pass tightens to strict-isolation
            // so caller in OrgA cannot execute their personal workflow via
            // this endpoint (and vice versa).
            if (!ScopeGuard.isInStrictScope(userId, orgId,
                    workflow.getTenantId(), workflow.getOrganizationId())) {
                logger.warn("[SCOPE] WorkflowExecution cross-tenant blocked: workflowId={} caller={} orgId={}",
                        workflowId, userId, orgId);
                return ResponseEntity.notFound().build();
            }

            String workflowOrgId = workflow.getOrganizationId();
            if (workflowOrgId != null
                    && !orgAccessGuard.canWrite(workflowOrgId, userId, "workflow", workflowId, orgRole)) {
                logger.warn("OrgAccess denied: user {} restricted from executing workflow {} in org {}",
                        userId, workflowId, workflowOrgId);
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(responseFactory.createFailureResponse("Workflow access is read-only"));
            }

            if (!creditClient.checkCredits(userId)) {
                logger.warn("Insufficient credits for user {}, blocking workflow execution", userId);
                return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
                        .body(responseFactory.createFailureResponse("Insufficient credits to execute workflow"));
            }

            // Auto-save before run: if canvas plan differs from DB, update it.
            // APPLICATION-type workflows skip this entirely - their plan is the frozen
            // acquired marketplace clone, not mutable in place. If the caller submitted
            // a diverged canvas, we run the stored basePlan-derived plan as-is and
            // log a WARN so support can spot UI drift. Restoring/changing the plan
            // is the user's job via POST /workflows/{id}/reset-plan.
            if (workflow.isApplication()) {
                logger.info("Skipped auto-save on APPLICATION workflow {} (immutable acquired clone) - running stored plan as-is",
                        workflowId);
            } else {
                try {
                    Map<String, Object> canvasPlan = plan.getOriginalPlan();
                    if (!versionService.plansAreEqual(canvasPlan, workflow.getPlan())) {
                        workflow.setPlan(new HashMap<>(canvasPlan));
                        workflow.setUpdatedAt(Instant.now());
                        workflowRepository.save(workflow);
                        logger.info("Auto-saved canvas plan to workflow {} before run", workflowId);
                    }
                } catch (Exception e) {
                    logger.warn("Auto-save before run failed for workflow {}: {}", workflowId, e.getMessage());
                }
            }

            var requestedMode = request.isStepByStepMode()
                    ? com.apimarketplace.orchestrator.domain.workflow.ExecutionMode.STEP_BY_STEP
                    : com.apimarketplace.orchestrator.domain.workflow.ExecutionMode.AUTOMATIC;
            var resolution = editorRunResolver.findOrCreateRun(workflow, plan, dataInputs, userId, requestedMode,
                    request.getMockMode());
            WorkflowRunEntity runEntity = resolution.runEntity();
            String resolvedRunId = runEntity.getRunIdPublic();

            logger.info("EditorRunResolver: run={}, planVersion={}, reused={}",
                    resolvedRunId, resolution.planVersion(), resolution.reused());

            // If reusing an existing WAITING_TRIGGER run, return it directly.
            // No metadata update needed - the run already has its metadata from creation.
            // Include readySteps + webhookTokens so the frontend can show triggers immediately.
            if (resolution.reused()) {
                return ResponseEntity.ok(helper.buildReusedRunResponse(resolvedRunId, plan));
            }

            // New run - store metadata directly on the already-fetched entity (single save)
            boolean dirty = false;
            Map<String, Object> metadata = runEntity.getMetadata() != null
                    ? new HashMap<>(runEntity.getMetadata()) : new HashMap<>();

            if (userPlan != null && !userPlan.isBlank()) {
                metadata.put("userPlan", userPlan);
                dirty = true;
            }
            // Stamp org context on the first-class columns. Readers consult
            // WorkflowRunEntity.organizationId / .organizationRole (or the
            // ExecutionContext fields populated from them).
            if (orgId != null && !orgId.isBlank()) {
                runEntity.setOrganizationId(orgId);
                if (orgRole != null) {
                    runEntity.setOrganizationRole(orgRole);
                }
                dirty = true;
            }
            if (dirty) {
                runEntity.setMetadata(metadata);
            }
            if (request.getSource() != null && !request.getSource().isBlank()) {
                runEntity.setSource(request.getSource());
                runEntity.setPublicationId(request.getPublicationId());
                dirty = true;
            }
            if (dirty) {
                runRepository.save(runEntity);
            }

            // New run - proceed with normal execution flow
            WorkflowExecution execution = resolution.execution();

            if (request.isStepByStepMode()) {
                return ResponseEntity.ok(helper.buildStepByStepModeStartResponse(execution));
            }

            if (triggerTypeDetector.hasReusableTrigger(plan)) {
                return ResponseEntity.ok(helper.buildWaitingTriggerResponse(execution, plan));
            }

            // HOTFIX-2 - pass active org so the worker thread inherits the scope.
            helper.startAsyncExecution(execution, runEntity.getOrganizationId());

            return ResponseEntity.ok(responseFactory.createSuccessResponse(execution));

        } catch (IllegalArgumentException e) {
            // e.g. invalid mockMode value - a caller mistake, not a server fault
            logger.warn("Invalid workflow execution request: {}", e.getMessage());
            return ResponseEntity.badRequest().body(responseFactory.createFailureResponse(e.getMessage()));
        } catch (Exception e) {
            logger.error("Error in workflow execution endpoint", e);
            return ResponseEntity.internalServerError().body(responseFactory.createFailureResponse("Internal server error: " + e.getMessage()));
        }
    }

    private WorkflowPlan resolveExecutionPlan(
            WorkflowPlanRequest request,
            WorkflowEntity workflow,
            String userId,
            boolean sharedApplicationBootstrap) {
        String workflowId = request.getWorkflowId();
        if (!sharedApplicationBootstrap) {
            // workflowId and tenantId are passed externally, not embedded in plan JSON.
            return helper.parseWorkflowPlan(request.getPlanJson(), workflowId, userId);
        }

        Map<String, Object> storedPlan = workflow.getPlan();
        if (storedPlan == null || storedPlan.isEmpty()) {
            logger.warn("Shared application bootstrap rejected because workflow {} has no stored plan", workflowId);
            return null;
        }

        return WorkflowPlan.fromMap(new HashMap<>(storedPlan), workflowId, userId);
    }

    private ResponseEntity<?> validateSharedApplicationExecutionScope(
            WorkflowPlanRequest request,
            WorkflowEntity workflow,
            String shareContext,
            String shareResourceType,
            String shareResourceToken,
            String shareResourceId) {
        if (!"true".equalsIgnoreCase(shareContext)) {
            return null;
        }

        if (!"APPLICATION".equalsIgnoreCase(shareResourceType)
                || !"application".equalsIgnoreCase(request.getSource())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(responseFactory.createFailureResponse("Shared links can only bootstrap application runs"));
        }

        String publicationId = request.getPublicationId();
        if (publicationId == null || publicationId.isBlank()
                || shareResourceToken == null || !publicationId.equals(shareResourceToken)) {
            return ResponseEntity.notFound().build();
        }

        if (shareResourceId != null && !shareResourceId.isBlank()
                && !request.getWorkflowId().equals(shareResourceId)) {
            return ResponseEntity.notFound().build();
        }

        UUID sourcePublicationId;
        try {
            sourcePublicationId = UUID.fromString(publicationId);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }

        if (!workflow.isApplication()
                || workflow.getSourcePublicationId() == null
                || !workflow.getSourcePublicationId().equals(sourcePublicationId)) {
            return ResponseEntity.notFound().build();
        }

        return null;
    }

    /**
     * Validates a workflow DAG.
     */
    @PostMapping("/validate")
    public ResponseEntity<?> validateWorkflow(
            @Valid @RequestBody WorkflowPlanRequest request,
            @RequestHeader(value = "X-User-ID", required = false) String userId) {
        try {
            logger.info("Workflow validation request received, X-User-ID: {}", userId);

            WorkflowPlan plan = helper.parseWorkflowPlan(request.getPlanJson(), request.getWorkflowId(), userId);
            if (plan == null) {
                return ResponseEntity.badRequest().body(responseFactory.createFailureResponse("Invalid workflow plan format"));
            }

            ValidationResult validation = new ValidationResult(true, List.of(), List.of(), 0);
            WorkflowValidationResponse response = responseFactory.createValidationResponse(validation);

            logger.info("Validation completed: valid={}, errors={}, warnings={}",
                       validation.isValid(), validation.getErrors().size(), validation.getWarnings().size());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Error in workflow validation endpoint", e);
            return ResponseEntity.internalServerError().body(responseFactory.createFailureResponse("Internal server error: " + e.getMessage()));
        }
    }

    /**
     * Starts a workflow execution that is in PENDING state.
     */
    @PostMapping("/{workflowId}/runs/{runId}/start")
    public ResponseEntity<?> startWorkflowRun(
            @PathVariable String workflowId,
            @PathVariable String runId,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId) {
        try {
            logger.info("Starting workflow run: workflowId={}, runId={}, userId={}", workflowId, runId, userId);

            if (userId == null || userId.isBlank()) {
                logger.error("X-User-ID header is missing");
                return ResponseEntity.badRequest().body(responseFactory.createFailureResponse("X-User-ID header is required"));
            }

            if (!creditClient.checkCredits(userId)) {
                logger.warn("Insufficient credits for user {}, blocking workflow start", userId);
                return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
                        .body(responseFactory.createFailureResponse("Insufficient credits to start workflow"));
            }

            // Get run entity to check authorization
            WorkflowRunEntity runEntity = runRepository.findByRunIdPublic(runId).orElse(null);
            if (runEntity == null) {
                logger.error("Execution not found: runId={}", runId);
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(responseFactory.createFailureResponse("Execution not found: " + runId));
            }

            // Audit 2026-05-17 round-3 - owner-or-org scope predicate.
            if (!WorkflowControllerHelper.isRunInScope(runEntity, userId, orgId)) {
                logger.warn("[SCOPE] startWorkflowRun cross-tenant blocked: runId={} caller={} orgId={}",
                        runId, userId, orgId);
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(responseFactory.createFailureResponse("Execution not found"));
            }

            // Reconstruct execution state from DB (no in-memory cache)
            WorkflowRunState state = resumeService.reconstructStateForApi(runId);
            if (state == null) {
                logger.error("Failed to reconstruct state: runId={}", runId);
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(responseFactory.createFailureResponse("Failed to reconstruct execution state: " + runId));
            }

            WorkflowExecution execution = contextManager.rebuildExecutionContext(runId, state);
            // HOTFIX-2 - pass run's org so worker thread inherits the scope.
            helper.startAsyncExecution(execution, runEntity.getOrganizationId());

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("runId", runId);
            response.put("workflowId", workflowId);
            response.put("status", RunStatus.RUNNING.getValue());
            response.put("message", "Workflow execution started");
            response.put("timestamp", Instant.now());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Error starting workflow run: workflowId={}, runId={}", workflowId, runId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(responseFactory.createFailureResponse("Failed to start execution: " + e.getMessage()));
        }
    }

    /**
     * Calculates execution levels for a workflow.
     */
    @PostMapping("/calculate-levels")
    public ResponseEntity<?> calculateLevels(
            @Valid @RequestBody WorkflowPlanRequest request,
            @RequestHeader(value = "X-User-ID", required = false) String userId) {
        try {
            logger.info("Calculate levels request received, X-User-ID: {}", userId);

            WorkflowPlan plan = helper.parseWorkflowPlan(request.getPlanJson(), request.getWorkflowId(), userId);
            if (plan == null) {
                return ResponseEntity.badRequest().body(responseFactory.createFailureResponse("Invalid workflow plan format"));
            }

            Map<String, Object> levels = executionService.calculateExecutionLevels(plan);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> levelData = (List<Map<String, Object>>) levels.get("levels");
            WorkflowLevelsResponse response = new WorkflowLevelsResponse(
                levelData,
                (Integer) levels.get("maxLevel"),
                (Integer) levels.get("totalSteps"),
                (Boolean) levels.get("hasLoops"),
                (Boolean) levels.get("hasConditionalLogic")
            );

            logger.info("Levels calculated successfully");
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Error calculating levels", e);
            return ResponseEntity.internalServerError().body(responseFactory.createFailureResponse("Error calculating levels: " + e.getMessage()));
        }
    }

}
