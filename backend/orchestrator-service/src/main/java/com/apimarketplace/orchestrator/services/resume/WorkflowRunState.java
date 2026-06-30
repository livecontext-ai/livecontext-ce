package com.apimarketplace.orchestrator.services.resume;

import com.apimarketplace.orchestrator.domain.workflow.ExecutionMode;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Represents the complete state of a workflow run, reconstructed from database.
 * Used for pause/resume functionality and step-by-step execution mode.
 */
public record WorkflowRunState(
    String runId,
    String workflowId,
    RunStatus status,
    ExecutionMode executionMode,
    Instant startedAt,
    Instant pausedAt,
    Map<String, Object> plan,
    List<StepState> steps,
    List<EdgeState> edges,
    Set<String> readySteps,
    Set<String> completedStepIds,
    Set<String> failedStepIds,
    Set<String> skippedStepIds,
    Set<String> runningStepIds,
    Map<String, Object> loops,
    List<Map<String, Object>> interfaces
) {

    /**
     * Constructor for backwards compatibility (without interfaces, without runningStepIds).
     */
    public WorkflowRunState(
        String runId,
        String workflowId,
        RunStatus status,
        ExecutionMode executionMode,
        Instant startedAt,
        Instant pausedAt,
        Map<String, Object> plan,
        List<StepState> steps,
        List<EdgeState> edges,
        Set<String> readySteps,
        Set<String> completedStepIds,
        Set<String> failedStepIds,
        Set<String> skippedStepIds,
        Map<String, Object> loops
    ) {
        this(runId, workflowId, status, executionMode, startedAt, pausedAt, plan, steps, edges,
             readySteps, completedStepIds, failedStepIds, skippedStepIds, Set.of(), loops, List.of());
    }

    /**
     * Represents the state of a single step.
     */
    public record StepState(
        String stepId,
        String stepAlias,
        String toolId,
        RunStatus status,
        Map<String, Object> inputData,
        Map<String, Object> output,
        Integer itemIndex,
        Integer iteration,
        Integer httpStatus,
        String errorMessage,
        Instant startTime,
        Instant endTime,
        long executionTimeMs,
        Set<String> dependencies,
        boolean canExecute,
        Map<String, Integer> statusCounts,
        Long totalExecutionTimeMs
    ) {
        /**
         * Constructor for backwards compatibility (without totalExecutionTimeMs).
         */
        public StepState(
            String stepId,
            String stepAlias,
            String toolId,
            RunStatus status,
            Map<String, Object> inputData,
            Map<String, Object> output,
            Integer itemIndex,
            Integer iteration,
            Integer httpStatus,
            String errorMessage,
            Instant startTime,
            Instant endTime,
            long executionTimeMs,
            Set<String> dependencies,
            boolean canExecute,
            Map<String, Integer> statusCounts
        ) {
            this(stepId, stepAlias, toolId, status, inputData, output, itemIndex, iteration,
                 httpStatus, errorMessage, startTime, endTime, executionTimeMs, dependencies, canExecute, statusCounts, null);
        }

        /**
         * Constructor for backwards compatibility (without statusCounts).
         */
        public StepState(
            String stepId,
            String stepAlias,
            String toolId,
            RunStatus status,
            Map<String, Object> inputData,
            Map<String, Object> output,
            Integer itemIndex,
            Integer iteration,
            Integer httpStatus,
            String errorMessage,
            Instant startTime,
            Instant endTime,
            long executionTimeMs,
            Set<String> dependencies,
            boolean canExecute
        ) {
            this(stepId, stepAlias, toolId, status, inputData, output, itemIndex, iteration,
                 httpStatus, errorMessage, startTime, endTime, executionTimeMs, dependencies, canExecute, null, null);
        }
    }

    /**
     * Represents the state of an edge between steps.
     * In batch mode, completedCount and skippedCount can both be > 0.
     */
    public record EdgeState(
        String from,
        String to,
        RunStatus status,
        int completedCount,
        int skippedCount,
        int totalCount
    ) {
        /**
         * Constructor for backwards compatibility (without skippedCount).
         */
        public EdgeState(String from, String to, RunStatus status, int completedCount, int totalCount) {
            this(from, to, status, completedCount, 0, totalCount);
        }
    }

    /**
     * Check if a specific step can be executed (dependencies met).
     */
    public boolean canExecuteStep(String stepId) {
        return readySteps != null && readySteps.contains(stepId);
    }

    /**
     * Get the state of a specific step.
     */
    public StepState getStepState(String stepId) {
        if (steps == null) return null;
        return steps.stream()
            .filter(s -> stepId.equals(s.stepId()) || stepId.equals(s.stepAlias()))
            .findFirst()
            .orElse(null);
    }

    /**
     * Check if workflow can be resumed.
     */
    public boolean canResume() {
        return status == RunStatus.PAUSED;
    }

    /**
     * Check if workflow is currently running.
     */
    public boolean isRunning() {
        return status == RunStatus.RUNNING;
    }

    /**
     * Check if workflow has completed (success or failure).
     */
    public boolean isTerminal() {
        return status != null && status.isTerminal();
    }
}
