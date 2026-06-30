package com.apimarketplace.orchestrator.services.streaming;

import com.apimarketplace.orchestrator.domain.workflow.ExecutionMetricsCollector;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.services.streaming.bus.WorkflowEventPublisher;
import com.apimarketplace.orchestrator.services.streaming.events.StepLifecycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Service for publishing loop aggregate events.
 * Handles loop iteration events and aggregate status computation.
 */
@Service
public class LoopEventPublisher {

    private static final Logger logger = LoggerFactory.getLogger(LoopEventPublisher.class);

    private final WorkflowEventPublisher eventPublisher;

    @Autowired
    public LoopEventPublisher(WorkflowEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    /**
     * Publishes an aggregate event for a loop iteration.
     *
     * @param execution       The workflow execution
     * @param iterationStepId The full iteration step ID (e.g., "core:my_loop#iter-3")
     */
    public void publishLoopAggregateEvent(WorkflowExecution execution, String iterationStepId) {
        if (execution == null || iterationStepId == null) {
            return;
        }

        String baseLoopId = extractLoopBaseId(iterationStepId);
        if (baseLoopId == null) {
            return;
        }

        ExecutionMetricsCollector.ItemMetrics metrics = execution.getStepItemMetrics(baseLoopId);
        Map<String, Object> statusCounts = metrics != null ? metrics.toMap() : computeLoopFallbackCounts(execution, baseLoopId);
        RunStatus aggregateStatus = determineLoopAggregateStatus(metrics);

        Map<String, Object> eventData = buildLoopEventData(execution, baseLoopId, aggregateStatus, statusCounts);

        // Loop iteration metrics removed (while-loop cleanup)
        eventData.put("loopCompletedItems", 0);

        eventPublisher.emitStep(execution.getRunId(), baseLoopId, eventData, mapToLifecycle(aggregateStatus));

        logger.debug("Published loop aggregate event: loopId={}, status={}, processed={}/{}",
            baseLoopId, aggregateStatus.getValue(),
            statusCounts.get("processed"), statusCounts.get("total"));
    }

    /**
     * Determines the aggregate status for a loop based on its metrics.
     */
    public RunStatus determineLoopAggregateStatus(ExecutionMetricsCollector.ItemMetrics metrics) {
        if (metrics == null) {
            return RunStatus.RUNNING;
        }
        if (metrics.failure() > 0) {
            return RunStatus.FAILED;
        }
        if (metrics.skipped() > 0 && metrics.success() == 0 && metrics.processed() >= Math.max(metrics.total(), 1)) {
            return RunStatus.COMPLETED;
        }
        if (metrics.processed() >= Math.max(metrics.total(), 1) && metrics.success() >= metrics.total()) {
            return RunStatus.COMPLETED;
        }
        if (metrics.processed() >= Math.max(metrics.total(), 1) && metrics.skipped() + metrics.success() >= metrics.total()) {
            return RunStatus.COMPLETED;
        }
        return RunStatus.RUNNING;
    }

    /**
     * Checks if a step ID represents a loop iteration step.
     */
    public boolean isLoopIterationStep(String stepId) {
        return stepId != null && stepId.startsWith("core:") && stepId.contains("#iter-");
    }

    /**
     * Extracts the base loop ID from an iteration step ID.
     *
     * @param iterationStepId The iteration step ID (e.g., "core:my_loop#iter-3")
     * @return The base loop ID (e.g., "core:my_loop") or the original if no iteration suffix
     */
    public String extractLoopBaseId(String iterationStepId) {
        if (iterationStepId == null) {
            return null;
        }
        int index = iterationStepId.indexOf("#iter-");
        if (index <= 0) {
            return iterationStepId;
        }
        return iterationStepId.substring(0, index);
    }

    /**
     * Normalizes a step ID by removing item scope suffix.
     *
     * @param stepId The step ID (potentially with "#item-X" suffix)
     * @return The normalized step ID without item scope
     */
    public String normalizeLoopItemScopedStepId(String stepId) {
        if (stepId == null) {
            return null;
        }
        int index = stepId.indexOf("#item-");
        return index > 0 ? stepId.substring(0, index) : stepId;
    }

    /**
     * Checks if a loop iteration event should be suppressed (used for internal iteration tracking).
     */
    public boolean shouldSuppressLoopIterationEvent(String normalizedStepId) {
        return normalizedStepId != null && normalizedStepId.startsWith("core:") && normalizedStepId.contains("#iter-");
    }

    private Map<String, Object> buildLoopEventData(
            WorkflowExecution execution,
            String loopId,
            RunStatus status,
            Map<String, Object> statusCounts) {

        Map<String, Object> eventData = new LinkedHashMap<>();
        eventData.put("type", "step_executed");
        eventData.put("runId", execution.getRunId());
        eventData.put("stepId", loopId);
        eventData.put("stepAlias", loopId);
        eventData.put("normalizedStepId", loopId);
        eventData.put("loopId", loopId);
        eventData.put("status", status.getValue());
        eventData.put("uiStatus", StreamingEventUtils.mapToUIStatus(status.getValue()));
        eventData.put("backendStatus", status.getValue());
        eventData.put("message", buildLoopAggregateMessage(loopId, statusCounts));
        eventData.put("timestamp", System.currentTimeMillis());
        eventData.put("statusCounts", statusCounts);

        return eventData;
    }

    private String buildLoopAggregateMessage(String loopId, Map<String, Object> statusCounts) {
        int processed = toInt(statusCounts.get("processed"));
        int total = toInt(statusCounts.get("total"));
        return String.format("Loop %s processed %d/%d iterations", loopId, processed, Math.max(total, processed));
    }

    private Map<String, Object> computeLoopFallbackCounts(WorkflowExecution execution, String loopNodeId) {
        Map<String, Object> counts = defaultStatusCounts();
        if (execution == null || loopNodeId == null) {
            return counts;
        }

        // Loop iteration metrics removed (while-loop cleanup) - return default counts
        counts.put("total", 0);
        counts.put("running", 0);

        return counts;
    }

    private Map<String, Object> defaultStatusCounts() {
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("running", 0);
        counts.put("processed", 0);
        counts.put("total", 0);
        counts.put("completed", 0);
        counts.put("failed", 0);
        counts.put("skipped", 0);
        return counts;
    }

    private StepLifecycle mapToLifecycle(RunStatus status) {
        if (status == null) {
            return StepLifecycle.RUNNING;
        }
        return switch (status) {
            case COMPLETED -> StepLifecycle.SUCCESS;
            case FAILED -> StepLifecycle.FAILURE;
            case CANCELLED -> StepLifecycle.CANCELLED;
            default -> StepLifecycle.RUNNING;
        };
    }

    private int toInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String str && !str.isBlank()) {
            try {
                return Integer.parseInt(str.trim());
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }
}
