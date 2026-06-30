package com.apimarketplace.orchestrator.services.streaming;

import com.apimarketplace.orchestrator.domain.workflow.ExecutionStatistics;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.metrics.WorkflowMetrics;
import com.apimarketplace.orchestrator.services.streaming.bus.WorkflowEventPublisher;
import com.apimarketplace.orchestrator.services.streaming.context.RunContextRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Builds and emits workflow-level events (status, configuration, statistics, error).
 * Handles terminal status detection and finalization tracking.
 *
 * <p>Delegates finalization tracking to {@link RunContextRegistry}.
 */
@Component
public class WorkflowEventEmitter {

    private static final Logger logger = LoggerFactory.getLogger(WorkflowEventEmitter.class);

    private final WorkflowEventPublisher eventPublisher;
    private final RunContextRegistry contextRegistry;
    private final WorkflowMetrics workflowMetrics;

    public WorkflowEventEmitter(WorkflowEventPublisher eventPublisher,
                                @Lazy RunContextRegistry contextRegistry,
                                WorkflowMetrics workflowMetrics) {
        this.eventPublisher = eventPublisher;
        this.contextRegistry = contextRegistry;
        this.workflowMetrics = workflowMetrics;
    }

    /**
     * Builds workflow status event data.
     *
     * @param execution The workflow execution
     * @param status The status string
     * @param message The status message
     * @return Event data map
     */
    public Map<String, Object> buildStatusEventData(WorkflowExecution execution, String status, String message) {
        ExecutionStatistics stats = execution.getStatistics();

        Map<String, Object> eventData = new HashMap<>();
        eventData.put("type", "workflowStatus");
        eventData.put("runId", execution.getRunId());
        eventData.put("workflowId", execution.getPlan().getId());
        eventData.put("status", status);
        eventData.put("message", message);
        eventData.put("timestamp", System.currentTimeMillis());
        eventData.put("overallProgressPct", stats.progressPercentage());
        eventData.put("stepsCompleted", stats.completedSteps());
        eventData.put("stepsFailed", stats.failedSteps());
        eventData.put("stepsSkipped", stats.skippedSteps());
        eventData.put("totalSteps", stats.totalSteps());
        eventData.put("currentLevel", stats.currentLevel());
        eventData.put("maxLevel", stats.maxLevel());
        // Derive isRunning from the status parameter, not the execution object.
        // The execution object may have stale status during reusable trigger cycles
        // (e.g., still RUNNING when we're emitting WAITING_TRIGGER after cycle completion).
        eventData.put("isRunning", "RUNNING".equalsIgnoreCase(status));
        eventData.put("executionTime", stats.totalExecutionTime());

        // Include accumulated node platform cost for real-time cost visibility
        long nodeCost = workflowMetrics.getRunNodeCredits(execution.getRunId());
        eventData.put("nodeCreditsConsumed", nodeCost);

        return eventData;
    }

    /**
     * Emits workflow status event and handles finalization.
     *
     * @param execution The workflow execution
     * @param status The status string
     * @param message The status message
     * @param eventData The event data to emit
     */
    public void emitStatusEvent(WorkflowExecution execution, String status, String message, Map<String, Object> eventData) {
        logger.info("Sending workflow status event: {} -> {} (isRunning: {})",
                   execution.getRunId(), status, execution.isRunning());

        // Mark as finalized if terminal status
        String statusUpper = status.toUpperCase();
        boolean terminal = isTerminalStatus(statusUpper);
        if (terminal) {
            contextRegistry.markFinalized(execution.getRunId());
        }

        eventPublisher.emitWorkflowStatus(execution.getRunId(), status, message, eventData, terminal);
    }

    /**
     * Builds workflow configuration event data.
     *
     * @param execution The workflow execution
     * @return Event data map
     */
    public Map<String, Object> buildConfigurationEventData(WorkflowExecution execution) {
        Map<String, Object> eventData = new HashMap<>();
        eventData.put("type", "workflowConfiguration");
        eventData.put("runId", execution.getRunId());
        eventData.put("workflowId", execution.getPlan().getId());
        eventData.put("totalSteps", execution.getPlan().getMcps().size());
        eventData.put("totalTriggers", execution.getPlan().getTriggers().size());
        eventData.put("totalEdges", execution.getPlan().getEdges().size());
        eventData.put("maxLevel", execution.getPlan().getExecutionGraph().getMaxLevel());
        eventData.put("hasConditionalLogic", execution.getPlan().hasConditionalLogic());
        eventData.put("hasWhileLoops", false);
        eventData.put("mergeNodesCount", execution.getPlan().getMergeNodes().size());
        eventData.put("timestamp", System.currentTimeMillis());

        return eventData;
    }

    /**
     * Builds workflow statistics event data.
     *
     * @param execution The workflow execution
     * @return Event data map
     */
    public Map<String, Object> buildStatisticsEventData(WorkflowExecution execution) {
        ExecutionStatistics stats = execution.getStatistics();

        Map<String, Object> eventData = new HashMap<>();
        eventData.put("type", "workflow_statistics");
        eventData.put("runId", execution.getRunId());
        eventData.put("workflowId", execution.getPlan().getId());
        eventData.put("totalSteps", stats.totalSteps());
        eventData.put("completedSteps", stats.completedSteps());
        eventData.put("failedSteps", stats.failedSteps());
        eventData.put("skippedSteps", stats.skippedSteps());
        eventData.put("progressPercentage", stats.progressPercentage());
        eventData.put("totalExecutionTime", stats.totalExecutionTime());
        eventData.put("maxLevel", stats.maxLevel());
        eventData.put("executionType", "DAG");
        eventData.put("timestamp", System.currentTimeMillis());

        // Include accumulated node platform cost (1 credit per completed/failed node)
        // Agent LLM costs are tracked separately in agent-service and available via /credits/analytics/run/{runId}
        long nodeCost = workflowMetrics.getRunNodeCredits(execution.getRunId());
        eventData.put("nodeCreditsConsumed", nodeCost);

        return eventData;
    }

    /**
     * Emits workflow statistics event.
     *
     * @param execution The workflow execution
     * @param eventData The event data to emit
     */
    public void emitStatisticsEvent(WorkflowExecution execution, Map<String, Object> eventData) {
        eventPublisher.emitWorkflowStatistics(execution.getRunId(), eventData);
    }

    /**
     * Builds error event data.
     *
     * @param execution The workflow execution
     * @param error The exception that occurred
     * @return Event data map
     */
    public Map<String, Object> buildErrorEventData(WorkflowExecution execution, Exception error) {
        Map<String, Object> eventData = new HashMap<>();
        eventData.put("type", "workflowError");
        eventData.put("runId", execution.getRunId());
        eventData.put("workflowId", execution.getPlan().getId());
        eventData.put("error", error.getMessage());
        eventData.put("errorType", error.getClass().getSimpleName());
        eventData.put("timestamp", System.currentTimeMillis());

        return eventData;
    }

    /**
     * Builds connection event data.
     *
     * @param runId The run ID
     * @param message The connection message
     * @return Event data map
     */
    public Map<String, Object> buildConnectionEventData(String runId, String message) {
        Map<String, Object> eventData = new HashMap<>();
        eventData.put("message", message);
        eventData.put("timestamp", System.currentTimeMillis());
        eventData.put("status", "CONNECTED");
        eventData.put("runId", runId);

        return eventData;
    }

    /**
     * Builds final status event data for completed runs.
     *
     * @param runId The run ID
     * @param status The final status
     * @return Event data map
     */
    public Map<String, Object> buildFinalStatusEventData(String runId, String status) {
        Map<String, Object> statusEvent = new HashMap<>();
        statusEvent.put("type", "workflowStatus");
        statusEvent.put("runId", runId);
        statusEvent.put("status", status);
        statusEvent.put("message", "Workflow execution completed");
        statusEvent.put("timestamp", System.currentTimeMillis());
        statusEvent.put("isRunning", false);

        return statusEvent;
    }

    /**
     * Checks if a status is terminal (workflow completed/failed/cancelled).
     */
    public boolean isTerminalStatus(String statusUpper) {
        if (statusUpper == null) {
            return false;
        }
        return switch (statusUpper) {
            case "COMPLETED", "FAILED", "CANCELLED", "PARTIAL_SUCCESS" -> true;
            default -> false;
        };
    }

    /**
     * Checks if an execution has already been finalized.
     */
    public boolean isFinalized(String runId) {
        return contextRegistry.isFinalized(runId);
    }

    /**
     * Marks an execution as finalized.
     */
    public void markFinalized(String runId) {
        contextRegistry.markFinalized(runId);
    }

}
