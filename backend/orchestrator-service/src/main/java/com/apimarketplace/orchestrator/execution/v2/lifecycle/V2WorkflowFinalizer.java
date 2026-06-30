package com.apimarketplace.orchestrator.execution.v2.lifecycle;

import com.apimarketplace.orchestrator.domain.workflow.ExecutionStatistics;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.execution.v2.engine.WorkflowResult;
import com.apimarketplace.orchestrator.execution.v2.services.UnifiedSignalService;
import com.apimarketplace.orchestrator.execution.v2.services.V2ExecutionEventService;
import com.apimarketplace.orchestrator.services.WorkflowPersistenceService;
import com.apimarketplace.orchestrator.services.execution.WorkflowRunFinalizer;
import com.apimarketplace.orchestrator.trigger.ErrorTriggerDispatchService;
import com.apimarketplace.orchestrator.trigger.WorkflowTriggerDispatchService;
import com.apimarketplace.common.analytics.PostHogAnalyticsClient;
import com.apimarketplace.common.web.TenantResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * V2 Workflow Finalizer - handles workflow completion/failure lifecycle.
 *
 * Responsibilities:
 * 1. Update WorkflowExecution status based on result
 * 2. Emit final streaming events (workflow complete/failed)
 * 3. Persist final state to database
 * 4. Flush batch scheduler (via legacy WorkflowRunFinalizer)
 * 5. Cleanup resources
 *
 * Integrates with legacy services to maintain compatibility.
 */
@Service
public class V2WorkflowFinalizer {

    private static final Logger logger = LoggerFactory.getLogger(V2WorkflowFinalizer.class);

    private final V2ExecutionEventService eventService;
    private final WorkflowPersistenceService persistenceService;
    private final WorkflowRunFinalizer legacyFinalizer;
    private final WorkflowTriggerDispatchService workflowTriggerDispatchService;

    @Autowired(required = false)
    private ErrorTriggerDispatchService errorTriggerDispatchService;

    @Autowired(required = false)
    private UnifiedSignalService unifiedSignalService;

    /**
     * Optional product-analytics emitter (PostHog). Field-injected (required=false)
     * so the existing constructor and its test call-sites are untouched and the
     * finalizer still wires when analytics is disabled (the bean is then a no-op).
     */
    @Autowired(required = false)
    private PostHogAnalyticsClient postHogAnalyticsClient;

    public V2WorkflowFinalizer(
            V2ExecutionEventService eventService,
            WorkflowPersistenceService persistenceService,
            WorkflowRunFinalizer legacyFinalizer,
            WorkflowTriggerDispatchService workflowTriggerDispatchService) {
        this.eventService = eventService;
        this.persistenceService = persistenceService;
        this.legacyFinalizer = legacyFinalizer;
        this.workflowTriggerDispatchService = workflowTriggerDispatchService;
    }

    /**
     * Finalize a completed workflow execution.
     *
     * @param execution The workflow execution
     * @param result The aggregated result from all items
     */
    public void finalizeWorkflow(WorkflowExecution execution, WorkflowResult result) {
        if (execution == null) {
            logger.warn("Cannot finalize null execution");
            return;
        }

        String runId = execution.getRunId();

        // Check for blocking signals before finalizing.
        // Non-blocking signals (auto-advance interfaces without __continue) don't prevent completion.
        if (unifiedSignalService != null && unifiedSignalService.hasBlockingSignals(runId)) {
            logger.info("⏳ Blocking signals still active, deferring finalization: runId={}", runId);
            return;
        }

        logger.info("🏁 Finalizing workflow: runId={}, workflowRunId={}, success={}, totalItems={}, successItems={}, failedItems={}, status={}, executionTimeMs={}",
            runId, execution.getWorkflowRunId(), result.isSuccess(),
            result.totalItems(), result.successItems(), result.failedItems(),
            execution.getStatus(), execution.getTotalExecutionTime());

        try {
            // 1. Update execution status based on result
            updateRunStatus(execution, result);

            // 2. Flush batch scheduler and persist snapshot (legacy integration)
            legacyFinalizer.flushAndPersist(execution);

            // 3. Emit workflow completion event
            String message = buildCompletionMessage(execution, result);
            eventService.emitWorkflowComplete(execution, result.isSuccess(), message);

            // 4. Persist final workflow state
            persistenceService.recordWorkflowCompletion(execution);

            // 5. Cleanup execution resources
            // Note: merge state and Redis cache cleanup now handled by RunContextRegistry.close()
            eventService.cleanupExecution(runId);

            // 6. Dispatch to downstream workflows (async) - trigger workflows that depend on this one
            if (workflowTriggerDispatchService != null) {
                workflowTriggerDispatchService.dispatchWorkflowCompletion(execution);
            }

            // 7. Dispatch to error handler workflows (async) if workflow failed
            if (errorTriggerDispatchService != null
                    && (execution.getStatus() == RunStatus.FAILED || execution.getStatus() == RunStatus.PARTIAL_SUCCESS)) {
                errorTriggerDispatchService.dispatchWorkflowFailure(execution);
            }

            ExecutionStatistics finalStats = execution.getStatistics();
            logger.info("✅ Workflow finalized: runId={}, workflowRunId={}, status={}, successItems={}/{}, completedSteps={}, failedSteps={}, skippedSteps={}, totalExecutionTimeMs={}, successRate={}%",
                runId, execution.getWorkflowRunId(), execution.getStatus(),
                result.successItems(), result.totalItems(),
                finalStats.completedSteps(), finalStats.failedSteps(), finalStats.skippedSteps(),
                execution.getTotalExecutionTime(),
                String.format("%.1f", finalStats.getSuccessRate() * 100));

            emitWorkflowRunCompletedAnalytics(execution, result, finalStats);

        } catch (Exception e) {
            logger.error("❌ Error finalizing workflow: runId={}, error={}",
                runId, e.getMessage(), e);
            // Try to emit error event
            try {
                eventService.emitWorkflowComplete(execution, false,
                    "Finalization error: " + e.getMessage());
            } catch (Exception ignored) {
                // Best effort
            }
        }
    }

    /**
     * Fire a summarized {@code workflow_run_completed} product-analytics event
     * (PostHog). Fire-and-forget: no-op unless configured, never throws, never
     * blocks. The tenant (distinct_id) comes from the plan - the reliable source
     * the persistence layer also uses - NOT a (possibly stale) thread-local;
     * org is best-effort. PII-free: only status / counts / ids.
     */
    private void emitWorkflowRunCompletedAnalytics(WorkflowExecution execution, WorkflowResult result, ExecutionStatistics stats) {
        if (postHogAnalyticsClient == null || !postHogAnalyticsClient.isActive()) return;
        try {
            String tenantId = execution.getPlan() != null ? execution.getPlan().getTenantId() : null;
            if (tenantId == null || tenantId.isBlank()) return;
            Map<String, Object> props = buildWorkflowRunCompletedProps(
                    execution.getStatus() != null ? execution.getStatus().name() : null,
                    execution.getTotalExecutionTime(),
                    stats != null ? stats.completedSteps() : 0,
                    stats != null ? stats.failedSteps() : 0,
                    stats != null ? stats.skippedSteps() : 0,
                    stats != null ? stats.getSuccessRate() : 0.0,
                    result != null ? result.totalItems() : 0,
                    result != null ? result.successItems() : 0,
                    result != null ? result.failedItems() : 0,
                    execution.getRunId(),
                    execution.getWorkflowRunId() != null ? execution.getWorkflowRunId().toString() : null,
                    TenantResolver.currentRequestOrganizationId());
            postHogAnalyticsClient.capture(tenantId, "workflow_run_completed", props);
        } catch (Exception e) {
            logger.debug("[posthog] failed building workflow_run_completed event (dropped): {}", e.toString());
        }
    }

    /** PII-free property bag for {@code workflow_run_completed}. Package-private + static for unit testing. */
    static Map<String, Object> buildWorkflowRunCompletedProps(String runStatus, long durationMs,
            int completedSteps, int failedSteps, int skippedSteps, double successRate,
            int totalItems, int successItems, int failedItems,
            String runId, String workflowRunId, String orgId) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("run_status", runStatus);
        props.put("duration_ms", durationMs);
        props.put("completed_steps", completedSteps);
        props.put("failed_steps", failedSteps);
        props.put("skipped_steps", skippedSteps);
        props.put("success_rate", successRate);
        props.put("total_items", totalItems);
        props.put("success_items", successItems);
        props.put("failed_items", failedItems);
        props.put("run_id", runId);
        if (workflowRunId != null) props.put("workflow_run_id", workflowRunId);
        if (orgId != null) props.put("organization_id", orgId);
        return props;
    }

    /**
     * Finalize a failed workflow execution.
     *
     * @param execution The workflow execution
     * @param error The error that caused the failure
     */
    public void finalizeWithError(WorkflowExecution execution, Throwable error) {
        if (execution == null) {
            logger.warn("Cannot finalize null execution");
            return;
        }

        String runId = execution.getRunId();
        logger.error("❌ Finalizing failed workflow: runId={}, workflowRunId={}, status={}, errorClass={}, error={}, completedSteps={}, failedSteps={}, skippedSteps={}",
            runId, execution.getWorkflowRunId(), execution.getStatus(),
            error.getClass().getSimpleName(), error.getMessage(),
            execution.getStatistics().completedSteps(),
            execution.getStatistics().failedSteps(),
            execution.getStatistics().skippedSteps());

        try {
            // 1. Mark execution as failed
            execution.setStatus(RunStatus.FAILED);
            if (error instanceof Exception) {
                execution.setError("Workflow execution failed: " + error.getMessage(), (Exception) error);
            }

            // 2. Flush batch scheduler
            legacyFinalizer.flushAndPersist(execution);

            // 3. Emit workflow error event
            eventService.emitWorkflowComplete(execution, false, error.getMessage());

            // 4. Persist final state
            persistenceService.recordWorkflowCompletion(execution);

            // 5. Cleanup execution resources
            // Note: merge state and Redis cache cleanup now handled by RunContextRegistry.close()
            eventService.cleanupExecution(runId);

            // 6. Dispatch to error handler workflows (async)
            if (errorTriggerDispatchService != null) {
                errorTriggerDispatchService.dispatchWorkflowFailure(execution);
            }

            // 7. Emit terminal analytics for the exception-driven failure path too,
            // so crashed runs aren't invisible (the success finalizer only covers
            // COMPLETED + in-band FAILED). No WorkflowResult here → item counts
            // default to 0; run_status reflects the FAILED state.
            emitWorkflowRunCompletedAnalytics(execution, null, execution.getStatistics());

        } catch (Exception e) {
            logger.error("❌ Error during error finalization: runId={}", runId, e);
        }
    }

    /**
     * Update execution status based on workflow result.
     */
    private void updateRunStatus(WorkflowExecution execution, WorkflowResult result) {
        ExecutionStatistics stats = execution.getStatistics();

        if (result.isSuccess()) {
            // Check if any steps failed
            if (stats.failedSteps() > 0) {
                execution.setStatus(RunStatus.FAILED);
            } else {
                execution.complete();
            }
        } else {
            execution.setStatus(RunStatus.FAILED);
        }
    }

    /**
     * Build a human-readable completion message.
     */
    private String buildCompletionMessage(WorkflowExecution execution, WorkflowResult result) {
        ExecutionStatistics stats = execution.getStatistics();

        if (result.isSuccess() && stats.failedSteps() == 0) {
            return String.format("Workflow completed successfully. %d items processed, %d steps completed.",
                result.totalItems(), stats.completedSteps());
        } else if (result.isSuccess() && stats.failedSteps() > 0) {
            return String.format("Workflow completed with failures. %d items, %d steps completed, %d steps failed.",
                result.totalItems(), stats.completedSteps(), stats.failedSteps());
        } else {
            return String.format("Workflow failed. %d/%d items succeeded.",
                result.successItems(), result.totalItems());
        }
    }
}
