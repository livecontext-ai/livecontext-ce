package com.apimarketplace.orchestrator.services.resume;

import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.*;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.WorkflowExecutionService;
import com.apimarketplace.orchestrator.services.WorkflowStreamingService;
import com.apimarketplace.orchestrator.services.resume.cache.WorkflowCacheManager;
import com.apimarketplace.orchestrator.execution.v2.services.V2StepByStepService;
import com.apimarketplace.orchestrator.execution.v2.engine.StepByStepExecutionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.Optional;

/**
 * Manages workflow execution context lifecycle.
 *
 * Extracted from WorkflowResumeService for Single Responsibility Principle.
 * Responsibilities:
 * - Rebuild execution context from saved state
 * - Execute ready steps
 * - Complete workflow when done
 *
 * @see WorkflowResumeService
 */
@Service
public class ExecutionContextManager {

    private static final Logger logger = LoggerFactory.getLogger(ExecutionContextManager.class);

    private final WorkflowRunRepository runRepository;
    private final WorkflowExecutionService executionService;
    private final WorkflowStreamingService streamingService;
    private final WorkflowCacheManager cacheManager;

    @Autowired(required = false)
    private V2StepByStepService v2StepByStepService;

    public ExecutionContextManager(
            WorkflowRunRepository runRepository,
            WorkflowExecutionService executionService,
            WorkflowStreamingService streamingService,
            WorkflowCacheManager cacheManager) {
        this.runRepository = runRepository;
        this.executionService = executionService;
        this.streamingService = streamingService;
        this.cacheManager = cacheManager;
    }

    /**
     * Rebuilds a WorkflowExecution context from saved state.
     *
     * CRITICAL: Uses state.completedStepIds()/failedStepIds()/skippedStepIds() instead of
     * iterating through state.steps() because:
     * - The step status sets are filtered for CURRENT EPOCH only
     * - state.steps() status is derived from aggregate statusCounts (ALL epochs)
     *
     * This ensures that when a new epoch starts, the execution context is built
     * with ONLY the current epoch's step results, not historical data.
     */
    public WorkflowExecution rebuildExecutionContext(String runId, WorkflowRunState state) {
        // Get tenantId from database
        Optional<WorkflowRunEntity> runEntityOpt = runRepository.findByRunIdPublic(runId);
        String tenantId = runEntityOpt.map(WorkflowRunEntity::getTenantId).orElse(null);

        WorkflowPlan plan = WorkflowPlan.fromMap(state.plan(), state.workflowId(), tenantId);
        WorkflowExecution execution = new WorkflowExecution(runId, plan, Map.of());
        runEntityOpt.ifPresent(r -> {
            execution.setWorkflowRunId(r.getId());
            if (r.getMetadata() != null) {
                if (r.getMetadata().get("__displayName__") instanceof String dn) {
                    execution.setDisplayName(dn);
                }
            }
        });

        // CRITICAL FIX: Use the epoch-filtered sets (completedStepIds, failedStepIds, skippedStepIds)
        // instead of iterating through state.steps() which has status derived from aggregate counts.
        // For a new epoch, these sets will be EMPTY, ensuring fresh execution context.
        int completedCount = state.completedStepIds() != null ? state.completedStepIds().size() : 0;
        int failedCount = state.failedStepIds() != null ? state.failedStepIds().size() : 0;
        int skippedCount = state.skippedStepIds() != null ? state.skippedStepIds().size() : 0;

        logger.info("[rebuildExecutionContext] runId={}, currentEpochSteps: completed={}, failed={}, skipped={}",
            runId, completedCount, failedCount, skippedCount);

        // Restore completed step results from current epoch only
        if (state.completedStepIds() != null) {
            for (String stepId : state.completedStepIds()) {
                WorkflowRunState.StepState stepState = state.getStepState(stepId);
                if (stepState != null) {
                    StepExecutionResult result = StepExecutionResult.success(
                        stepId,
                        stepState.output() != null ? stepState.output() : Map.of(),
                        stepState.executionTimeMs()
                    );
                    execution.setStepResult(stepId, result);
                    if (stepState.output() != null) {
                        execution.updateStepOutput(stepId, stepState.output());
                    }
                }
            }
        }

        // Restore failed step results from current epoch only
        if (state.failedStepIds() != null) {
            for (String stepId : state.failedStepIds()) {
                WorkflowRunState.StepState stepState = state.getStepState(stepId);
                if (stepState != null) {
                    StepExecutionResult result = StepExecutionResult.failure(
                        stepId,
                        stepState.errorMessage(),
                        null,
                        stepState.executionTimeMs()
                    );
                    execution.setStepResult(stepId, result);
                }
            }
        }

        // Restore skipped step results from current epoch only
        if (state.skippedStepIds() != null) {
            for (String stepId : state.skippedStepIds()) {
                WorkflowRunState.StepState stepState = state.getStepState(stepId);
                StepExecutionResult result = StepExecutionResult.skipped(
                    stepId,
                    stepState != null && stepState.errorMessage() != null ? stepState.errorMessage() : "Skipped"
                );
                execution.setStepResult(stepId, result);
            }
        }

        execution.setStatus(RunStatus.RUNNING);
        return execution;
    }

    /**
     * Creates an execution context from DB state.
     * Always rebuilds fresh from DB - no in-memory caching.
     */
    public WorkflowExecution getOrCreateExecutionContext(String runId, WorkflowRunState state) {
        return rebuildExecutionContext(runId, state);
    }

    /**
     * Executes all steps that are ready using the V2 execution engine.
     * Continues executing in a loop until no more ready nodes or workflow completes.
     */
    public void executeReadySteps(WorkflowExecution execution, Set<String> readySteps) {
        String runId = execution.getRunId();

        if (v2StepByStepService == null) {
            logger.error("V2StepByStepService not available, cannot execute ready steps");
            return;
        }

        try {
            // Ensure V2 service is initialized
            if (!v2StepByStepService.isInitialized(runId)) {
                logger.info("[executeReadySteps] V2 not initialized for runId={}, initializing...", runId);
                WorkflowPlan plan = execution.getPlan();
                v2StepByStepService.initializeStepByStep(execution, plan);
            }

            // Execute ready steps in a loop until complete or no more ready nodes
            Set<String> currentReadySteps = new HashSet<>(readySteps);
            int maxIterations = 100; // Safety limit to prevent infinite loops
            int iteration = 0;

            while (!currentReadySteps.isEmpty() && iteration < maxIterations) {
                iteration++;
                logger.info("[executeReadySteps] Iteration {}: executing {} ready steps: {}",
                    iteration, currentReadySteps.size(), currentReadySteps);

                // Execute each ready step
                Set<String> nextReadySteps = new HashSet<>();

                for (String stepId : currentReadySteps) {
                    try {
                        logger.info("[executeReadySteps] Executing step: {}", stepId);
                        StepByStepExecutionResult v2Result = v2StepByStepService.executeNode(runId, stepId, "0");

                        if (v2Result.workflowComplete()) {
                            logger.info("[executeReadySteps] Workflow complete after executing: {}", stepId);
                            return;
                        }

                        // Collect next ready nodes
                        if (v2Result.readyNodes() != null && !v2Result.readyNodes().isEmpty()) {
                            nextReadySteps.addAll(v2Result.readyNodes());
                        }

                        logger.info("[executeReadySteps] Step {} completed, success={}, nextReady={}",
                            stepId, v2Result.isSuccess(), v2Result.readyNodes());

                    } catch (Exception e) {
                        logger.error("[executeReadySteps] Failed to execute step {}: {}", stepId, e.getMessage(), e);
                        // Continue with other steps even if one fails
                    }
                }

                currentReadySteps = nextReadySteps;
            }

            if (iteration >= maxIterations) {
                logger.warn("[executeReadySteps] Reached max iterations limit ({}) for runId={}", maxIterations, runId);
            }

        } catch (Exception e) {
            logger.error("[executeReadySteps] Error executing ready steps for runId={}: {}", runId, e.getMessage(), e);
        }
    }

    /**
     * Completes the workflow after all steps are done.
     */
    public void completeWorkflow(WorkflowExecution execution, WorkflowRunState state) {
        RunStatus finalStatus = state.failedStepIds().isEmpty()
            ? RunStatus.COMPLETED
            : RunStatus.FAILED;

        execution.setStatus(finalStatus);

        runRepository.findByRunIdPublic(execution.getRunId()).ifPresent(entity -> {
            entity.setStatus(finalStatus);
            entity.setEndedAt(Instant.now());
            entity.setUpdatedAt(Instant.now());
            runRepository.save(entity);
        });

        streamingService.sendWorkflowStatusEvent(execution, finalStatus,
            finalStatus == RunStatus.COMPLETED ? "Workflow completed" : "Workflow failed");

        cacheManager.removePausedWorkflow(execution.getRunId());
    }

    /**
     * Checks if the workflow is complete and updates status accordingly.
     */
    public void checkAndCompleteWorkflow(String runId, WorkflowExecution execution, WorkflowRunState state) {
        // Check if all steps are done (no more ready steps and all processed)
        if (state.readySteps().isEmpty()) {
            Set<String> allStepIds = getAllStepIds(execution.getPlan());
            int totalProcessed = state.completedStepIds().size() +
                                state.failedStepIds().size() +
                                state.skippedStepIds().size();

            if (totalProcessed >= allStepIds.size()) {
                RunStatus finalStatus = state.failedStepIds().isEmpty()
                    ? RunStatus.COMPLETED
                    : RunStatus.FAILED;

                execution.setStatus(finalStatus);

                runRepository.findByRunIdPublic(runId).ifPresent(entity -> {
                    entity.setStatus(finalStatus);
                    entity.setEndedAt(Instant.now());
                    entity.setUpdatedAt(Instant.now());
                    runRepository.save(entity);
                });

                streamingService.sendWorkflowStatusEvent(execution, finalStatus,
                    finalStatus == RunStatus.COMPLETED ? "Workflow completed" : "Workflow failed");

                cacheManager.clearAllCaches(runId);

                logger.info("Workflow {} completed with status: {}", runId, finalStatus);
            }
        }
    }

    /**
     * Gets all step IDs from a plan.
     */
    public Set<String> getAllStepIds(WorkflowPlan plan) {
        Set<String> ids = new HashSet<>();
        for (Step step : plan.getMcps()) {
            ids.add(step.getNormalizedKey());
        }
        for (Trigger trigger : plan.getTriggers()) {
            ids.add(trigger.getNormalizedKey());
        }
        return ids;
    }

    /**
     * Gets missing dependencies for a step.
     */
    public Set<String> getMissingDependencies(String stepId, WorkflowRunState state) {
        WorkflowRunState.StepState stepState = state.getStepState(stepId);
        if (stepState == null || stepState.dependencies() == null) {
            return Set.of();
        }

        Set<String> missing = new HashSet<>();
        for (String dep : stepState.dependencies()) {
            if (!state.completedStepIds().contains(dep)) {
                missing.add(dep);
            }
        }
        return missing;
    }
}
