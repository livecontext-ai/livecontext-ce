package com.apimarketplace.orchestrator.trigger;

import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.domain.workflow.Trigger;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Service for dispatching workflow failure events to error handler workflows.
 *
 * When a workflow fails (FAILED or PARTIAL_SUCCESS), this service finds all workflows
 * that have an "error" trigger referencing the failed workflow, and triggers them
 * with an error payload containing failure details.
 *
 * Architecture:
 * - Triggered from V2WorkflowFinalizer after workflow failure
 * - Finds downstream error handler workflows via WorkflowTriggerLookupService
 * - Uses accumulation pattern: reuses existing WAITING_TRIGGER run
 * - Delegates execution to ReusableTriggerService
 * - Anti-loop protection: error handler workflows that fail do NOT trigger other error handlers
 *
 * @see ReusableTriggerService
 * @see TriggerType#ERROR
 * @see WorkflowTriggerDispatchService
 */
@Service
public class ErrorTriggerDispatchService {

    private static final Logger logger = LoggerFactory.getLogger(ErrorTriggerDispatchService.class);

    /**
     * Maximum concurrent RUNNING runs allowed per workflow.
     * If this limit is reached, new triggers are skipped with a warning.
     */
    private static final int MAX_CONCURRENT_RUNS = 5;

    private final WorkflowTriggerLookupService triggerLookupService;
    private final WorkflowRunRepository runRepository;
    private final ReusableTriggerService triggerService;

    public ErrorTriggerDispatchService(
            WorkflowTriggerLookupService triggerLookupService,
            WorkflowRunRepository runRepository,
            ReusableTriggerService triggerService) {
        this.triggerLookupService = triggerLookupService;
        this.runRepository = runRepository;
        this.triggerService = triggerService;
    }

    /**
     * Dispatch workflow failure to all downstream error handler workflows.
     *
     * This method is called asynchronously when a workflow fails.
     * It finds all workflows with an "error" trigger referencing the failed workflow
     * and triggers them with the error payload.
     *
     * Anti-loop protection: if the failed workflow itself has an "error" trigger type,
     * it is an error handler and we do NOT dispatch its failure to prevent infinite loops.
     *
     * @param failedExecution The failed workflow execution
     */
    @Async
    public void dispatchWorkflowFailure(WorkflowExecution failedExecution) {
        dispatchInternal(failedExecution, /* requireTerminalStatus= */ true);
    }

    /**
     * Dispatch workflow failure for a reusable-trigger epoch that had one or more failed steps.
     *
     * Reusable-trigger workflows (manual/webhook/chat/schedule/form/error) never transition to
     * FAILED/PARTIAL_SUCCESS - they reset to WAITING_TRIGGER between epochs. The standard
     * {@link #dispatchWorkflowFailure} is gated on terminal status and would be a no-op for
     * them, leaving error triggers dead at runtime (#ET1).
     *
     * This variant skips the terminal-status gate: the caller (ReusableTriggerService) is
     * responsible for invoking it only when the epoch actually had failures.
     */
    @Async
    public void dispatchEpochFailure(WorkflowExecution failedExecution) {
        dispatchInternal(failedExecution, /* requireTerminalStatus= */ false);
    }

    private void dispatchInternal(WorkflowExecution failedExecution, boolean requireTerminalStatus) {
        if (failedExecution == null) {
            logger.warn("Cannot dispatch null execution");
            return;
        }

        String parentRunId = failedExecution.getRunId();
        RunStatus parentStatus = failedExecution.getStatus();

        if (requireTerminalStatus
                && parentStatus != RunStatus.FAILED
                && parentStatus != RunStatus.PARTIAL_SUCCESS) {
            logger.debug("Skipping error trigger dispatch - status is {}, not FAILED or PARTIAL_SUCCESS", parentStatus);
            return;
        }

        // Get the workflow ID from the run entity
        UUID workflowRunId = failedExecution.getWorkflowRunId();
        if (workflowRunId == null) {
            logger.warn("Cannot dispatch workflow failure - no workflowRunId for runId={}", parentRunId);
            return;
        }

        Optional<WorkflowRunEntity> runEntityOpt = runRepository.findById(workflowRunId);
        if (runEntityOpt.isEmpty()) {
            logger.warn("Cannot find WorkflowRunEntity for workflowRunId={}", workflowRunId);
            return;
        }

        WorkflowRunEntity runEntity = runEntityOpt.get();
        WorkflowEntity parentWorkflow = runEntity.getWorkflow();
        String parentWorkflowId = parentWorkflow.getId().toString();

        // Anti-loop protection: if this workflow is itself an error handler, don't dispatch
        if (isErrorHandlerWorkflow(runEntity)) {
            logger.info("Skipping error trigger dispatch - workflow {} is itself an error handler (anti-loop protection)",
                parentWorkflowId);
            return;
        }

        logger.info("Dispatching workflow failure: workflowId={}, runId={}, status={}",
            parentWorkflowId, parentRunId, parentStatus);

        try {
            // Find all downstream workflows that have an error trigger referencing this workflow
            List<WorkflowEntity> downstreamWorkflows = triggerLookupService
                .findByErrorTrigger(parentWorkflowId);

            if (downstreamWorkflows.isEmpty()) {
                logger.debug("No error handler workflows found for parent workflow {}", parentWorkflowId);
                return;
            }

            logger.info("Found {} error handler workflow(s) to trigger for failed workflow {}",
                downstreamWorkflows.size(), parentWorkflowId);

            // Build the error payload
            Map<String, Object> payload = buildErrorPayload(failedExecution, parentWorkflowId, parentRunId);

            // Trigger each downstream workflow
            // Audit 2026-05-17 round-5 - cross-workspace guard. A FAILED
            // workflow in org A must not leak its failure payload (step
            // outputs, error message, stack) into an error-handler workflow
            // in org B. Same shape as WorkflowTriggerDispatchService R4 fix.
            String parentOrg = parentWorkflow.getOrganizationId();
            String parentTenant = parentWorkflow.getTenantId();
            for (WorkflowEntity downstream : downstreamWorkflows) {
                String dsOrg = downstream.getOrganizationId();
                String dsTenant = downstream.getTenantId();
                boolean workspaceMatch = com.apimarketplace.common.scope.ScopeGuard
                        .crossResourceMatches(parentOrg, dsOrg)
                        && (parentOrg != null
                                || (parentTenant != null && parentTenant.equals(dsTenant)));
                if (!workspaceMatch) {
                    logger.warn("[SCOPE] Cross-workspace error-trigger blocked: parent={} (org={}, tenant={}), downstream={} (org={}, tenant={})",
                            parentWorkflowId, parentOrg, parentTenant, downstream.getId(), dsOrg, dsTenant);
                    continue;
                }
                try {
                    triggerDownstreamWorkflow(downstream, payload, parentRunId, parentWorkflowId);
                } catch (Exception e) {
                    logger.error("Failed to trigger error handler workflow {}: {}",
                        downstream.getId(), e.getMessage(), e);
                    // Continue with other workflows
                }
            }

        } catch (Exception e) {
            logger.error("Error dispatching workflow failure for {}: {}",
                parentWorkflowId, e.getMessage(), e);
        }
    }

    /**
     * Trigger a single downstream error handler workflow with the given payload.
     *
     * Uses accumulation pattern (like webhook/schedule): reuses the latest WAITING_TRIGGER run.
     * Never creates a new run - user must start one from the UI.
     *
     * @param workflow The downstream error handler workflow to trigger
     * @param payload The error payload
     * @param parentRunId The parent run ID for traceability
     * @param parentWorkflowId The parent workflow ID
     */
    private void triggerDownstreamWorkflow(WorkflowEntity workflow, Map<String, Object> payload,
                                            String parentRunId, String parentWorkflowId) {
        UUID workflowId = workflow.getId();

        logger.info("Triggering error handler workflow: id={}, name={}", workflowId, workflow.getName());

        // Check concurrent runs limit to prevent explosion
        long runningCount = runRepository.countByWorkflowIdAndStatus(workflowId, RunStatus.RUNNING);
        if (runningCount >= MAX_CONCURRENT_RUNS) {
            logger.warn("Workflow {} already has {} running instances (limit: {}), skipping error trigger from parent run {}",
                workflowId, runningCount, MAX_CONCURRENT_RUNS, parentRunId);
            return;
        }

        // Version-aware run lookup
        Optional<WorkflowRunEntity> waitingRunOpt;
        if (workflow.getPinnedVersion() != null) {
            waitingRunOpt = runRepository.findFirstByWorkflowIdAndPlanVersionOrderByStartedAtDesc(
                    workflowId, workflow.getPinnedVersion());
        } else {
            waitingRunOpt = runRepository.findFirstByWorkflowIdOrderByStartedAtDesc(workflowId);
        }

        if (waitingRunOpt.isEmpty()) {
            logger.warn("[ErrorTrigger] No active run for workflow {}, skipping dispatch from parent {}",
                workflowId, parentRunId);
            return;
        }

        WorkflowRunEntity runEntity = waitingRunOpt.get();

        // Reject terminal runs
        RunStatus runEntityStatus = runEntity.getStatus();
        if (runEntityStatus.isTerminal()) {
            logger.warn("[ErrorTrigger] Latest run {} for workflow {} is terminal ({}), skipping dispatch",
                    runEntity.getRunIdPublic(), workflowId, runEntityStatus);
            return;
        }
        logger.info("[ErrorTrigger] Reusing existing run {} for workflow {}",
            runEntity.getRunIdPublic(), workflowId);

        // Find the error trigger in the plan that references this specific parent
        String triggerId = findErrorTriggerId(runEntity, parentWorkflowId);
        if (triggerId == null) {
            logger.error("No error trigger found in plan for workflow {} referencing parent {}",
                workflowId, parentWorkflowId);
            return;
        }

        // Add parent run ID to payload for traceability
        Map<String, Object> enrichedPayload = new HashMap<>(payload);
        enrichedPayload.put("parentRunId", parentRunId);
        // Defense-in-depth: error payload includes user-controlled data;
        // strip the internal plan-control marker.
        enrichedPayload = ReusableTriggerService.sanitizePlanMarker(enrichedPayload);

        // Execute the trigger via ReusableTriggerService
        try {
            TriggerExecutionResult result = triggerService.executeTrigger(
                runEntity, triggerId, TriggerType.ERROR, enrichedPayload);

            if (result.success()) {
                logger.info("Successfully triggered error handler workflow {}, run {}",
                    workflowId, runEntity.getRunIdPublic());
            } else {
                logger.warn("Error trigger execution returned non-success for workflow {}: {}",
                    workflowId, result.message());
            }
        } catch (Exception e) {
            logger.error("Failed to execute error trigger for workflow {}: {}",
                workflowId, e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Build the error payload from the failed execution.
     *
     * @param execution The failed workflow execution
     * @param parentWorkflowId The parent workflow ID
     * @param parentRunId The parent run ID
     * @return Payload map containing error details and metadata
     */
    private Map<String, Object> buildErrorPayload(WorkflowExecution execution,
                                                    String parentWorkflowId, String parentRunId) {
        Map<String, Object> payload = new HashMap<>();

        // Core error fields
        payload.put("parentWorkflowId", parentWorkflowId);
        payload.put("parentRunId", parentRunId);
        payload.put("status", execution.getStatus().name());
        payload.put("errorMessage", execution.getErrorMessage() != null
            ? execution.getErrorMessage() : "Workflow execution failed");
        payload.put("triggeredAt", Instant.now().toString());

        // Statistics
        if (execution.getStatistics() != null) {
            payload.put("failedSteps", execution.getStatistics().failedSteps());
            payload.put("completedSteps", execution.getStatistics().completedSteps());
            payload.put("totalSteps", execution.getStatistics().totalSteps());
            payload.put("skippedSteps", execution.getStatistics().skippedSteps());
        }

        return payload;
    }

    /**
     * Check if a workflow is itself an error handler (has at least one "error" trigger).
     * This is used for anti-loop protection: error handler workflows that fail
     * should NOT trigger other error handlers.
     *
     * @param runEntity The workflow run entity
     * @return true if the workflow has an "error" trigger type
     */
    boolean isErrorHandlerWorkflow(WorkflowRunEntity runEntity) {
        Map<String, Object> planMap = runEntity.getPlan();
        if (planMap == null) {
            return false;
        }

        try {
            WorkflowPlan plan = WorkflowPlan.fromMap(planMap);
            for (Trigger trigger : plan.getTriggers()) {
                if ("error".equals(trigger.type())) {
                    return true;
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to parse workflow plan for run {}: {}", runEntity.getRunIdPublic(), e.getMessage());
        }

        return false;
    }

    /**
     * Find the error trigger ID from the workflow plan that references a specific parent workflow.
     *
     * A workflow can have multiple error triggers, each referencing a different parent workflow.
     * This method finds the trigger whose id matches the parentWorkflowId.
     *
     * @param run The workflow run entity
     * @param parentWorkflowId The ID of the parent workflow that failed
     * @return The error trigger's normalized key, or null if not found
     */
    private String findErrorTriggerId(WorkflowRunEntity run, String parentWorkflowId) {
        if (parentWorkflowId == null || parentWorkflowId.isBlank()) {
            logger.error("parentWorkflowId is required for error trigger dispatch");
            return null;
        }

        Map<String, Object> planMap = run.getPlan();
        if (planMap == null) {
            return null;
        }

        try {
            WorkflowPlan plan = WorkflowPlan.fromMap(planMap);

            for (Trigger trigger : plan.getTriggers()) {
                if ("error".equals(trigger.type())) {
                    // The trigger's id field contains the parent workflow ID
                    if (parentWorkflowId.equalsIgnoreCase(trigger.id())) {
                        logger.debug("Found matching error trigger for parent {}: {}",
                            parentWorkflowId, trigger.getNormalizedKey());
                        return trigger.getNormalizedKey();
                    }
                }
            }

            logger.warn("No error trigger found referencing parent workflow {}", parentWorkflowId);
        } catch (Exception e) {
            logger.warn("Failed to parse workflow plan for run {}: {}", run.getRunIdPublic(), e.getMessage());
        }

        return null;
    }
}
