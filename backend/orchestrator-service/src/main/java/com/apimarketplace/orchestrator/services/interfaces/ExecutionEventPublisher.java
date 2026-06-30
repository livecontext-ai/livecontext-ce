package com.apimarketplace.orchestrator.services.interfaces;

import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;

import java.util.Map;
import java.util.Set;

/**
 * Interface for publishing workflow execution events.
 * Defines the contract for streaming execution state to clients.
 *
 * Implementations handle:
 * - Workflow status events (started, completed, failed)
 * - Step execution events
 * - Pause/resume events
 * - Ready steps notifications
 *
 * @see com.apimarketplace.orchestrator.services.WorkflowStreamingService
 */
public interface ExecutionEventPublisher {

    // ==================== Lifecycle ====================

    /**
     * Initialize streaming for an execution.
     *
     * @param execution The workflow execution
     */
    void initializeStreaming(WorkflowExecution execution);

    /**
     * Finalize streaming when execution completes.
     *
     * @param execution The workflow execution
     */
    void finalizeStreaming(WorkflowExecution execution);

    // ==================== Workflow Events ====================

    /**
     * Send workflow status event.
     *
     * @param execution The workflow execution
     * @param status Status string (e.g., "started", "completed", "failed")
     * @param message Optional message
     */
    void sendWorkflowStatusEvent(WorkflowExecution execution, String status, String message);

    /**
     * Send workflow status event with RunStatus enum.
     *
     * @param execution The workflow execution
     * @param status The execution status
     * @param message Optional message
     */
    void sendWorkflowStatusEvent(WorkflowExecution execution, RunStatus status, String message);

    /**
     * Send workflow configuration event (graph structure, metadata).
     *
     * @param execution The workflow execution
     */
    void sendWorkflowConfigurationEvent(WorkflowExecution execution);

    /**
     * Send error event.
     *
     * @param execution The workflow execution
     * @param error The exception that occurred
     */
    void sendErrorEvent(WorkflowExecution execution, Exception error);

    // ==================== Step Events ====================

    /**
     * Send batch update for multiple steps/edges.
     *
     * @param runId The run identifier
     * @param payload The batch payload
     */
    void sendBatchUpdate(String runId, Map<String, Object> payload);

    // ==================== Control Events ====================

    /**
     * Send ready steps event.
     *
     * @param execution The workflow execution
     * @param readySteps Set of ready step IDs
     */
    void sendReadyStepsEvent(WorkflowExecution execution, Set<String> readySteps);

    /**
     * Send pause event.
     *
     * @param execution The workflow execution
     * @param readySteps Set of ready step IDs at pause time
     */
    void sendPauseEvent(WorkflowExecution execution, Set<String> readySteps);

    /**
     * Send resume event.
     *
     * @param execution The workflow execution
     */
    void sendResumeEvent(WorkflowExecution execution);

    /**
     * Send rerun event.
     *
     * @param execution The workflow execution
     * @param stepId The step being rerun from
     * @param resetSteps Steps that were reset
     * @param newEpoch The new epoch number
     */
    void sendRerunEvent(WorkflowExecution execution, String stepId, Set<String> resetSteps, int newEpoch);

}
