package com.apimarketplace.orchestrator.controllers.workflow;

import com.apimarketplace.common.scope.ScopeGuard;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.services.WorkflowExecutionService;
import com.apimarketplace.orchestrator.services.WorkflowIconExtractor;
import com.apimarketplace.orchestrator.services.resume.WorkflowResumeService;
import com.apimarketplace.orchestrator.services.resume.WorkflowRunState;
import com.apimarketplace.orchestrator.trigger.TriggerType;
import com.apimarketplace.orchestrator.trigger.TriggerTypeDetector;
import com.apimarketplace.orchestrator.utils.LabelNormalizer;
import com.apimarketplace.trigger.client.TriggerClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Helper component for workflow controllers.
 * Contains shared utility methods used across multiple workflow controllers.
 */
@Component
public class WorkflowControllerHelper {

    private static final Logger logger = LoggerFactory.getLogger(WorkflowControllerHelper.class);

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private WorkflowExecutionService executionService;

    @Autowired
    private WorkflowResumeService resumeService;

    @Autowired
    private TriggerTypeDetector triggerTypeDetector;

    @Autowired
    private TriggerClient triggerClient;

    /**
     * Strict-isolation scope predicate for a single {@link WorkflowRunEntity},
     * aligned with {@link ScopeGuard#isInStrictScope}.
     *
     * <p>Active workspace ({@code callerOrgId} set) → matches only rows
     * tagged with that org id. Post-V261 (2026-05-19) the gateway always
     * injects X-Organization-ID (personal workspaces resolve to the user's
     * default personal org), so the legacy personal-strict
     * ({@code callerOrgId} null + {@code organization_id IS NULL}) branch
     * of {@link ScopeGuard#isInStrictScope} is unreachable for normal
     * traffic.
     *
     * <p>Audit 2026-05-18 - replaced the prior owner-OR-org predicate that
     * let a caller currently in OrgA workspace touch their personal runs
     * (and vice versa) because {@code userId == tenantId} alone granted
     * access. The mirroring SQL list-finder
     * {@code findRunSummariesByWorkflowIdInScope} is being aligned in the
     * same pass; out-of-scope rows map to 404 (controller layer).
     *
     * <p>{@link ScopeGuard} is the single source of truth - see
     * {@code common-lib/.../scope/ScopeGuard.java}.
     */
    public static boolean isRunInScope(WorkflowRunEntity run, String callerUserId, String callerOrgId) {
        if (run == null) {
            return false;
        }
        return ScopeGuard.isInStrictScope(
                callerUserId, callerOrgId,
                run.getTenantId(), run.getOrganizationId())
            && shareContextPermitsRun(run);
    }

    /**
     * Share-context resource binding for run reads.
     *
     * <p>The gateway resolves a share token to the OWNER's identity, so the
     * strict-scope check alone authorizes a share-link holder against the
     * owner's entire workspace - a holder of one application share link could
     * pass a different {@code runId} of the same owner and read it. This binds
     * a share-context run read to the SHARED application's publication: the run
     * must carry {@code publicationId == X-Share-Resource-Token}.
     *
     * <p>Only APPLICATION share links legitimately read runs (via the bootstrap
     * flow that stamps {@code run.publicationId = request.publicationId}, which
     * itself must equal the share resource token - see
     * {@code WorkflowExecutionController.validateSharedApplicationExecutionScope}).
     * Non-share requests (no {@code X-Share-Context}) and non-APPLICATION share
     * types are left unchanged. Internal (non-servlet) calls are unconstrained.
     */
    public static boolean shareContextPermitsRun(WorkflowRunEntity run) {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) {
            return true; // non-servlet (internal) call - no share constraint
        }
        HttpServletRequest req = attrs.getRequest();
        if (!"true".equalsIgnoreCase(req.getHeader("X-Share-Context"))) {
            return true; // authenticated, non-share request - unchanged
        }
        if (!"APPLICATION".equalsIgnoreCase(req.getHeader("X-Share-Resource-Type"))) {
            return true; // only application shares read runs; other share types unchanged
        }
        String resourceToken = req.getHeader("X-Share-Resource-Token");
        return run != null
                && run.getPublicationId() != null
                && resourceToken != null
                && run.getPublicationId().equals(resourceToken);
    }

    /**
     * Parses a workflow plan from JSON.
     * workflowId and tenantId are provided externally (from URL param / X-User-ID header)
     * and are NOT part of the plan JSON - they live in dedicated DB columns.
     */
    public WorkflowPlan parseWorkflowPlan(String planJson, String workflowId, String tenantId) {
        try {
            logger.debug("Parsing workflow plan JSON, workflowId: {}, tenantId: {}", workflowId, tenantId);
            @SuppressWarnings("unchecked")
            Map<String, Object> planData = objectMapper.readValue(planJson, Map.class);

            return WorkflowPlan.fromMap(planData, workflowId, tenantId);

        } catch (Exception e) {
            logger.error("Error parsing workflow plan: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Converts a plan object to a Map.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> convertToPlanMap(Object planObj) throws Exception {
        if (planObj instanceof Map) {
            return (Map<String, Object>) planObj;
        }
        String planJson = objectMapper.writeValueAsString(planObj);
        return objectMapper.readValue(planJson, Map.class);
    }

    /**
     * Starts async workflow execution.
     *
     * <p>HOTFIX-2 (2026-05-20) - {@code orgIdForWorker} must be the caller's active
     * org. The async lambda runs on the ForkJoinPool common pool, which strips the
     * request {@code TenantResolver} ThreadLocal; without re-binding via
     * {@link com.apimarketplace.common.web.TenantResolver#runWithOrgScope}, any
     * downstream OrgScopedEntity persist (storage.storage, workflow_step_data)
     * trips V261 NOT NULL and cascade-fails the transaction.
     */
    public void startAsyncExecution(WorkflowExecution execution, String orgIdForWorker) {
        CompletableFuture.runAsync(() -> {
            com.apimarketplace.common.web.TenantResolver.runWithOrgScope(orgIdForWorker, () -> {
                try {
                    logger.info("Starting async workflow execution: {}", execution.getRunId());
                    executionService.execute(execution);
                    logger.info("Workflow execution completed: {}", execution.getRunId());
                } catch (Exception e) {
                    logger.error("Workflow execution failed: {}", execution.getRunId(), e);
                    executionService.handleExecutionError(execution, e);
                }
            });
        }).exceptionally(throwable -> {
            logger.error("Critical error in async execution: {}", execution.getRunId(), throwable);
            executionService.handleExecutionError(execution, (Exception) throwable);
            return null;
        });
    }

    /**
     * Builds step-by-step mode start response.
     */
    public Map<String, Object> buildStepByStepModeStartResponse(WorkflowExecution execution) {
        logger.info("Starting workflow in step-by-step mode: {}", execution.getRunId());
        WorkflowRunState runState = resumeService.startInStepByStepMode(execution.getRunId());

        // Use actual DB status: startInStepByStepMode() may set WAITING_TRIGGER
        // for reusable trigger workflows instead of PAUSED
        Set<String> readySteps = runState.readySteps();
        boolean hasTriggerReady = readySteps != null && readySteps.stream()
                .anyMatch(s -> s.startsWith("trigger:"));
        RunStatus actualStatus = hasTriggerReady ? RunStatus.WAITING_TRIGGER : RunStatus.PAUSED;

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("runId", execution.getRunId());
        response.put("workflowId", execution.getPlan().getId());
        response.put("executionMode", "step_by_step");
        response.put("status", actualStatus.getValue());
        response.put("message", "Workflow started in step-by-step mode");
        response.put("readySteps", readySteps);
        response.put("startTime", execution.getStartTime());
        response.put("timestamp", Instant.now());
        return response;
    }

    /**
     * Builds response for workflows with reusable triggers that need to wait.
     */
    public Map<String, Object> buildWaitingTriggerResponse(WorkflowExecution execution, WorkflowPlan plan) {
        logger.info("Workflow has reusable trigger, returning WAITING_TRIGGER status: {}", execution.getRunId());

        List<String> readySteps = new ArrayList<>();
        if (plan.getTriggers() != null) {
            for (var trigger : plan.getTriggers()) {
                if (TriggerType.isReusableTriggerType(trigger.type())) {
                    String label = trigger.label() != null ? trigger.label() : trigger.id();
                    String triggerId = LabelNormalizer.triggerKey(label);
                    readySteps.add(triggerId);
                    logger.info("Added trigger to readySteps: {}", triggerId);
                }
            }
        }

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("runId", execution.getRunId());
        response.put("workflowId", execution.getPlan().getId());
        response.put("executionMode", "automatic");
        response.put("status", RunStatus.WAITING_TRIGGER.getValue());
        response.put("message", "Workflow started, waiting for trigger");
        response.put("startTime", execution.getStartTime());
        response.put("timestamp", Instant.now());
        response.put("readySteps", readySteps);

        if (triggerTypeDetector.hasWebhookTrigger(plan)) {
            try {
                UUID wfId = UUID.fromString(execution.getPlan().getId());
                Map<String, String> tokens = triggerClient.getTokensForWorkflow(wfId);
                if (!tokens.isEmpty()) {
                    response.put("webhookTokens", tokens);
                }
            } catch (Exception e) {
                logger.warn("Could not retrieve webhook tokens for workflow: {}", e.getMessage());
            }
        }

        return response;
    }

    /**
     * Builds response for a reused live run (WAITING_TRIGGER between fires, or
     * RUNNING/PAUSED while a previous epoch is still in flight - see
     * {@code EditorRunResolver.REUSABLE_STATUSES}).
     * Same shape as {@link #buildWaitingTriggerResponse} but without a WorkflowExecution.
     *
     * <p>The status field is intentionally hardcoded to {@code waiting_trigger} regardless
     * of the run's actual DB status: it routes the frontend into the show-trigger-buttons
     * branch so the user can fire (each fire opens a new epoch on this same run). The live
     * status streams to the UI separately once attached to the run.
     */
    public Map<String, Object> buildReusedRunResponse(String runId, WorkflowPlan plan) {
        logger.info("Reusing existing live run: {}", runId);

        List<String> readySteps = new ArrayList<>();
        if (plan.getTriggers() != null) {
            for (var trigger : plan.getTriggers()) {
                if (TriggerType.isReusableTriggerType(trigger.type())) {
                    String label = trigger.label() != null ? trigger.label() : trigger.id();
                    readySteps.add(LabelNormalizer.triggerKey(label));
                }
            }
        }

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("runId", runId);
        response.put("status", RunStatus.WAITING_TRIGGER.getValue());
        response.put("reused", true);
        response.put("readySteps", readySteps);

        if (triggerTypeDetector.hasWebhookTrigger(plan)) {
            try {
                UUID wfId = UUID.fromString(plan.getId());
                Map<String, String> tokens = triggerClient.getTokensForWorkflow(wfId);
                if (!tokens.isEmpty()) {
                    response.put("webhookTokens", tokens);
                }
            } catch (Exception e) {
                logger.warn("Could not retrieve webhook tokens for reused run: {}", e.getMessage());
            }
        }

        return response;
    }

    /**
     * Builds a workflow response map.
     */
    public Map<String, Object> buildWorkflowResponse(WorkflowEntity workflow) {
        Map<String, Object> response = new HashMap<>();
        response.put("id", workflow.getId().toString());
        response.put("name", workflow.getName());
        response.put("description", workflow.getDescription());
        response.put("status", workflow.getStatus().toString());
        response.put("isActive", workflow.getIsActive());
        response.put("createdAt", workflow.getCreatedAt());
        response.put("updatedAt", workflow.getUpdatedAt());
        response.put("lastExecutedAt", workflow.getLastExecutedAt());
        response.put("plan", workflow.getPlan());
        // Lazy-compute nodeIcons for workflows saved before the feature was deployed
        var nodeIcons = workflow.getNodeIcons();
        if (nodeIcons == null && workflow.getPlan() != null) {
            nodeIcons = WorkflowIconExtractor.extractNodeIcons(workflow.getPlan());
        }
        response.put("nodeIcons", nodeIcons);
        Map<String, String> tokens = triggerClient.getTokensForWorkflow(workflow.getId());
        if (!tokens.isEmpty()) {
            response.put("webhookTokens", tokens);
        }
        // readOnly removed - applications use snapshot model now
        if (workflow.getSourcePublicationId() != null) {
            response.put("sourcePublicationId", workflow.getSourcePublicationId().toString());
        }
        if (workflow.getAcquiredAt() != null) {
            response.put("acquiredAt", workflow.getAcquiredAt());
        }
        return response;
    }

    /**
     * Builds instance data for list response.
     */
    public Map<String, Object> buildInstanceData(WorkflowRunEntity instance) {
        Map<String, Object> data = new HashMap<>();
        data.put("id", instance.getId().toString());
        data.put("runId", instance.getRunIdPublic());
        data.put("workflowId", instance.getWorkflow().getId().toString());
        data.put("status", instance.getStatus().toString());
        data.put("startedAt", instance.getStartedAt());
        data.put("endedAt", instance.getEndedAt());
        data.put("durationMs", instance.getDurationMs());
        return data;
    }

    /**
     * Builds instance detail response.
     */
    public Map<String, Object> buildInstanceDetailResponse(WorkflowRunEntity instance) {
        Map<String, Object> response = new HashMap<>();
        response.put("id", instance.getId().toString());
        response.put("runId", instance.getRunIdPublic());
        response.put("workflowId", instance.getWorkflow().getId().toString());
        response.put("status", instance.getStatus().toString());
        response.put("startedAt", instance.getStartedAt());
        response.put("endedAt", instance.getEndedAt());
        response.put("durationMs", instance.getDurationMs());
        response.put("triggerPayload", instance.getTriggerPayload());
        response.put("metadata", instance.getMetadata());
        response.put("plan", instance.getPlan());
        return response;
    }
}
