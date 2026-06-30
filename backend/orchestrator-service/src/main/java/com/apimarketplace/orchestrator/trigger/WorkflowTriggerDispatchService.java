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
 * Service for dispatching workflow completion events to downstream workflows.
 *
 * When a workflow completes, this service finds all workflows that have a "workflow" trigger
 * referencing the completed workflow, and triggers them with the completion payload.
 *
 * Architecture:
 * - Triggered from V2WorkflowFinalizer after workflow completion
 * - Finds downstream workflows via WorkflowTriggerLookupService
 * - Uses accumulation pattern: reuses existing WAITING_TRIGGER run, cancels stale runs
 * - Only creates a new run if no WAITING_TRIGGER run exists
 * - Delegates execution to ReusableTriggerService
 *
 * @see ReusableTriggerService
 * @see TriggerType#WORKFLOW
 */
@Service
public class WorkflowTriggerDispatchService {

    private static final Logger logger = LoggerFactory.getLogger(WorkflowTriggerDispatchService.class);

    /**
     * Maximum concurrent RUNNING runs allowed per workflow.
     * If this limit is reached, new triggers are skipped with a warning.
     */
    private static final int MAX_CONCURRENT_RUNS = 5;

    private final WorkflowTriggerLookupService triggerLookupService;
    private final WorkflowRunRepository runRepository;
    private final ReusableTriggerService triggerService;
    private final ProductionRunResolver productionRunResolver;

    public WorkflowTriggerDispatchService(
            WorkflowTriggerLookupService triggerLookupService,
            WorkflowRunRepository runRepository,
            ReusableTriggerService triggerService,
            ProductionRunResolver productionRunResolver) {
        this.triggerLookupService = triggerLookupService;
        this.runRepository = runRepository;
        this.triggerService = triggerService;
        this.productionRunResolver = productionRunResolver;
    }

    /**
     * Dispatch workflow completion to all downstream workflows.
     *
     * This method is called asynchronously when a workflow completes.
     * It finds all workflows with a "workflow" trigger referencing the completed workflow
     * and triggers them with the completion payload.
     *
     * @param completedExecution The completed workflow execution
     */
    @Async
    public void dispatchWorkflowCompletion(WorkflowExecution completedExecution) {
        if (completedExecution == null) {
            logger.warn("Cannot dispatch null execution");
            return;
        }

        String parentRunId = completedExecution.getRunId();
        RunStatus parentStatus = completedExecution.getStatus();

        // Get the workflow ID from the run entity
        UUID workflowRunId = completedExecution.getWorkflowRunId();
        if (workflowRunId == null) {
            logger.warn("Cannot dispatch workflow completion - no workflowRunId for runId={}", parentRunId);
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

        logger.info("🔗 Dispatching workflow completion: workflowId={}, runId={}, status={}",
            parentWorkflowId, parentRunId, parentStatus);

        // Only trigger on COMPLETED status (not FAILED, PAUSED, etc.)
        if (parentStatus != RunStatus.COMPLETED) {
            logger.debug("Skipping workflow trigger dispatch - parent status is {}, not COMPLETED", parentStatus);
            return;
        }

        try {
            // Find all downstream workflows that have this workflow as a trigger
            List<WorkflowEntity> downstreamWorkflows = triggerLookupService
                .findByWorkflowTrigger(parentWorkflowId);

            if (downstreamWorkflows.isEmpty()) {
                logger.debug("No downstream workflows found for parent workflow {}", parentWorkflowId);
                return;
            }

            logger.info("Found {} downstream workflow(s) to trigger for parent workflow {}",
                downstreamWorkflows.size(), parentWorkflowId);

            // Build the trigger payload from parent's result
            Map<String, Object> payload = buildTriggerPayload(completedExecution, parentWorkflowId, parentRunId);

            // Trigger each downstream workflow
            // Audit 2026-05-17 round-4 - cross-workspace guard. Parent and
            // downstream must share the same workspace boundary:
            //   - both org-tagged with the same organization_id, OR
            //   - both NULL-org and same tenantId (personal scope).
            // A cross-workspace fan-out would silently fire a workflow in
            // another tenant's workspace from a public-trigger pulse.
            String parentOrg = parentWorkflow.getOrganizationId();
            String parentTenant = parentWorkflow.getTenantId();
            for (WorkflowEntity downstream : downstreamWorkflows) {
                String dsOrg = downstream.getOrganizationId();
                String dsTenant = downstream.getTenantId();
                // Workspace must match (both org-tagged with same org, OR both
                // personal); personal scope additionally requires same tenant.
                boolean workspaceMatch = com.apimarketplace.common.scope.ScopeGuard
                        .crossResourceMatches(parentOrg, dsOrg)
                        && (parentOrg != null
                                || (parentTenant != null && parentTenant.equals(dsTenant)));
                if (!workspaceMatch) {
                    logger.warn("[SCOPE] Cross-workspace workflow trigger blocked: parent={} (org={}, tenant={}), downstream={} (org={}, tenant={})",
                            parentWorkflowId, parentOrg, parentTenant, downstream.getId(), dsOrg, dsTenant);
                    continue;
                }
                try {
                    triggerDownstreamWorkflow(downstream, payload, parentRunId);
                } catch (Exception e) {
                    logger.error("Failed to trigger downstream workflow {}: {}",
                        downstream.getId(), e.getMessage(), e);
                    // Continue with other workflows
                }
            }

        } catch (Exception e) {
            logger.error("Error dispatching workflow completion for {}: {}",
                parentWorkflowId, e.getMessage(), e);
        }
    }

    /**
     * Trigger a single downstream workflow with the given payload.
     *
     * Uses accumulation pattern (like webhook/schedule): reuses the latest WAITING_TRIGGER run.
     * Never creates a new run - user must start one from the UI.
     *
     * @param workflow The downstream workflow to trigger
     * @param payload The trigger payload (parent's result)
     * @param parentRunId The parent run ID for traceability
     */
    private void triggerDownstreamWorkflow(WorkflowEntity workflow, Map<String, Object> payload, String parentRunId) {
        UUID workflowId = workflow.getId();

        logger.info("Triggering downstream workflow: id={}, name={}", workflowId, workflow.getName());

        // Check concurrent runs limit to prevent explosion
        long runningCount = runRepository.countByWorkflowIdAndStatus(workflowId, RunStatus.RUNNING);
        if (runningCount >= MAX_CONCURRENT_RUNS) {
            logger.warn("Workflow {} already has {} running instances (limit: {}), skipping trigger from parent run {}",
                workflowId, runningCount, MAX_CONCURRENT_RUNS, parentRunId);
            return;
        }

        // Centralized: chained workflow triggers fire ONLY on the pinned version.
        ProductionRunResolver.Resolution resolution =
            productionRunResolver.resolve(workflowId, com.apimarketplace.orchestrator.trigger.ProductionRunResolver.RunSelectionPolicy.LATEST_TRUSTED);
        if (!resolution.isFound()) {
            if (resolution.isNotPinned()) {
                logger.warn("[WorkflowTrigger] Downstream workflow {} has no pinned version. " +
                    "Pin a production version to enable chained workflow triggers (parent: {}).",
                    workflowId, parentRunId);
            } else {
                logger.warn("[WorkflowTrigger] No active run for workflow {} ({}), skipping dispatch from parent {}",
                    workflowId, resolution.outcome(), parentRunId);
            }
            return;
        }
        WorkflowRunEntity runEntity = resolution.run().get();

        // Reject every terminal status. Mirrors WebhookDispatchService,
        // DatasourceTriggerDispatchService and TriggerController: in normal flow
        // resetForNextCycle transitions a finishing cycle to WAITING_TRIGGER, so a
        // terminal status here means the cycle never reset (typically a JVM crash
        // mid-execution). Re-firing on a terminal run reopens a new epoch and re-
        // triggers the same crash - see prod 2026-05-07 12:40 UTC where
        // run_<id> accumulated 73 epochs before the user noticed.
        RunStatus runEntityStatus = runEntity.getStatus();
        if (runEntityStatus != null && runEntityStatus.isTerminal()) {
            logger.warn("[WorkflowTrigger] Latest run {} for workflow {} is {} - not triggerable",
                    runEntity.getRunIdPublic(), workflowId, runEntityStatus);
            return;
        }
        logger.info("[WorkflowTrigger] Reusing existing run {} for workflow {}",
            runEntity.getRunIdPublic(), workflowId);

        // Find the workflow trigger in the plan that references this specific parent
        String parentWorkflowId = (String) payload.get("parentWorkflowId");
        String triggerId = findWorkflowTriggerId(runEntity, parentWorkflowId);
        if (triggerId == null) {
            logger.error("No workflow trigger found in plan for workflow {} referencing parent {}",
                workflowId, parentWorkflowId);
            return;
        }

        // Add parent run ID to payload for traceability
        Map<String, Object> enrichedPayload = new HashMap<>(payload);
        enrichedPayload.put("parentRunId", parentRunId);
        // Parent's cycle result may include node outputs containing arbitrary
        // user-defined keys - strip the internal plan-control marker.
        enrichedPayload = ReusableTriggerService.sanitizePlanMarker(enrichedPayload);

        // Execute the trigger via ReusableTriggerService
        try {
            TriggerExecutionResult result = triggerService.executeTrigger(
                runEntity, triggerId, TriggerType.WORKFLOW, enrichedPayload);

            if (result.success()) {
                logger.info("Successfully triggered downstream workflow {}, run {}",
                    workflowId, runEntity.getRunIdPublic());
            } else {
                logger.warn("Trigger execution returned non-success for workflow {}: {}",
                    workflowId, result.message());
            }
        } catch (Exception e) {
            logger.error("Failed to execute trigger for workflow {}: {}",
                workflowId, e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Build the trigger payload from the completed execution.
     *
     * @param execution The completed workflow execution
     * @param parentWorkflowId The parent workflow ID
     * @param parentRunId The parent run ID
     * @return Payload map containing result, status, and metadata
     */
    private Map<String, Object> buildTriggerPayload(WorkflowExecution execution, String parentWorkflowId, String parentRunId) {
        Map<String, Object> payload = new HashMap<>();

        // Core fields - use step outputs as the result
        Map<String, Object> stepOutputs = execution.getStepOutputs();
        payload.put("result", stepOutputs != null ? stepOutputs : new HashMap<>());
        payload.put("status", execution.getStatus().name());
        payload.put("triggered_at", Instant.now().toString());

        // Parent workflow metadata
        payload.put("parentWorkflowId", parentWorkflowId);
        payload.put("parentRunId", parentRunId);

        // Statistics if available
        if (execution.getStatistics() != null) {
            Map<String, Object> stats = new HashMap<>();
            stats.put("completedSteps", execution.getStatistics().completedSteps());
            stats.put("failedSteps", execution.getStatistics().failedSteps());
            stats.put("totalSteps", execution.getStatistics().totalSteps());
            payload.put("statistics", stats);
        }

        return payload;
    }

    /**
     * Find the workflow trigger ID from the workflow plan that references a specific parent workflow.
     *
     * Multi-DAG Support:
     * A workflow can have multiple workflow triggers, each referencing a different parent workflow.
     * This method finds the trigger whose `id` matches the parentWorkflowId.
     *
     * @param run The workflow run entity
     * @param parentWorkflowId The ID of the parent workflow that triggered this one (required)
     * @return The workflow trigger's normalized key, or null if not found
     */
    /**
     * Dispatch a reusable-trigger epoch completion to downstream workflows.
     *
     * Unlike {@link #dispatchWorkflowCompletion}, this method is called from
     * {@link ReusableTriggerService} after a successful epoch cycle completes.
     * Reusable-trigger workflows never go through V2WorkflowFinalizer, so this
     * separate entry point bridges the gap.
     *
     * @param runId          The parent run ID (public)
     * @param workflowRunId  The parent WorkflowRunEntity UUID
     * @param stepOutputs    The accumulated step outputs from the epoch
     * @param hasFailures    Whether the epoch had any failed steps
     */
    @Async
    public void dispatchCycleCompletion(String runId, UUID workflowRunId,
                                         Map<String, Object> stepOutputs, boolean hasFailures) {
        if (hasFailures) {
            logger.debug("[WorkflowTrigger] Cycle had failures, skipping dispatch for runId={}", runId);
            return;
        }

        Optional<WorkflowRunEntity> runEntityOpt = runRepository.findById(workflowRunId);
        if (runEntityOpt.isEmpty()) {
            logger.warn("[WorkflowTrigger] Cannot find WorkflowRunEntity for workflowRunId={}", workflowRunId);
            return;
        }

        WorkflowRunEntity runEntity = runEntityOpt.get();
        WorkflowEntity parentWorkflow = runEntity.getWorkflow();
        String parentWorkflowId = parentWorkflow.getId().toString();

        logger.info("🔗 [WorkflowTrigger] Dispatching cycle completion: workflowId={}, runId={}",
            parentWorkflowId, runId);

        try {
            List<WorkflowEntity> downstreamWorkflows = triggerLookupService
                .findByWorkflowTrigger(parentWorkflowId);

            if (downstreamWorkflows.isEmpty()) {
                logger.debug("[WorkflowTrigger] No downstream workflows for parent workflow {}", parentWorkflowId);
                return;
            }

            logger.info("[WorkflowTrigger] Found {} downstream workflow(s) for parent workflow {}",
                downstreamWorkflows.size(), parentWorkflowId);

            // Build payload from step outputs
            Map<String, Object> payload = new HashMap<>();
            payload.put("result", stepOutputs != null ? stepOutputs : new HashMap<>());
            payload.put("status", "COMPLETED");
            payload.put("triggered_at", Instant.now().toString());
            payload.put("parentWorkflowId", parentWorkflowId);
            payload.put("parentRunId", runId);

            // Audit 2026-05-17 round-5 - same cross-workspace guard as
            // dispatchWorkflowCompletion (R4). Reusable-trigger epoch
            // completion was missed in round-4; closing it here.
            String parentOrg = parentWorkflow.getOrganizationId();
            String parentTenant = parentWorkflow.getTenantId();
            for (WorkflowEntity downstream : downstreamWorkflows) {
                String dsOrg = downstream.getOrganizationId();
                String dsTenant = downstream.getTenantId();
                // Workspace must match (both org-tagged with same org, OR both
                // personal); personal scope additionally requires same tenant.
                boolean workspaceMatch = com.apimarketplace.common.scope.ScopeGuard
                        .crossResourceMatches(parentOrg, dsOrg)
                        && (parentOrg != null
                                || (parentTenant != null && parentTenant.equals(dsTenant)));
                if (!workspaceMatch) {
                    logger.warn("[SCOPE] Cross-workspace workflow trigger blocked (cycle): parent={} (org={}, tenant={}), downstream={} (org={}, tenant={})",
                            parentWorkflowId, parentOrg, parentTenant, downstream.getId(), dsOrg, dsTenant);
                    continue;
                }
                try {
                    triggerDownstreamWorkflow(downstream, payload, runId);
                } catch (Exception e) {
                    logger.error("[WorkflowTrigger] Failed to trigger downstream workflow {}: {}",
                        downstream.getId(), e.getMessage(), e);
                }
            }
        } catch (Exception e) {
            logger.error("[WorkflowTrigger] Error dispatching cycle completion for {}: {}",
                parentWorkflowId, e.getMessage(), e);
        }
    }

    private String findWorkflowTriggerId(WorkflowRunEntity run, String parentWorkflowId) {
        if (parentWorkflowId == null || parentWorkflowId.isBlank()) {
            logger.error("parentWorkflowId is required for workflow trigger dispatch");
            return null;
        }

        Map<String, Object> planMap = run.getPlan();
        if (planMap == null) {
            return null;
        }

        try {
            WorkflowPlan plan = WorkflowPlan.fromMap(planMap);

            for (Trigger trigger : plan.getTriggers()) {
                if ("workflow".equals(trigger.type())) {
                    // The trigger's id field contains the parent workflow ID
                    if (parentWorkflowId.equalsIgnoreCase(trigger.id())) {
                        logger.debug("Found matching workflow trigger for parent {}: {}",
                            parentWorkflowId, trigger.getNormalizedKey());
                        return trigger.getNormalizedKey();
                    }
                }
            }

            logger.warn("No workflow trigger found referencing parent workflow {}", parentWorkflowId);
        } catch (Exception e) {
            logger.warn("Failed to parse workflow plan for run {}: {}", run.getRunIdPublic(), e.getMessage());
        }

        return null;
    }
}
