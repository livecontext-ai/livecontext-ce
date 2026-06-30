package com.apimarketplace.orchestrator.services.streaming;

import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.services.streaming.bus.WorkflowEventPublisher;
import com.apimarketplace.orchestrator.services.streaming.events.StepLifecycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Registers initial snapshots for workflow execution.
 * Creates placeholder events for triggers and steps at workflow start.
 */
@Component
public class InitialSnapshotRegistrar {

    private static final Logger logger = LoggerFactory.getLogger(InitialSnapshotRegistrar.class);

    private final WorkflowEventPublisher eventPublisher;
    private final EdgeStatusService edgeStatusService;

    public InitialSnapshotRegistrar(WorkflowEventPublisher eventPublisher, EdgeStatusService edgeStatusService) {
        this.eventPublisher = eventPublisher;
        this.edgeStatusService = edgeStatusService;
    }

    /**
     * Registers initial snapshots for all triggers and steps in the workflow.
     *
     * @param execution The workflow execution
     */
    public void registerInitialSnapshots(WorkflowExecution execution) {
        if (execution == null || execution.getPlan() == null) {
            return;
        }

        long timestamp = System.currentTimeMillis();

        // Register placeholder snapshots for triggers
        execution.getPlan().getTriggers().forEach(trigger -> {
            String stepId = trigger.getNormalizedKey();
            registerPlaceholderSnapshot(execution, stepId, timestamp, Map.of("triggerId", stepId));
        });

        // Register placeholder snapshots for steps
        execution.getPlan().getMcps().forEach(step -> {
            String stepId = step.getNormalizedKey();
            registerPlaceholderSnapshot(execution, stepId, timestamp, Collections.emptyMap());
        });

        // Register workflow edges
        edgeStatusService.registerWorkflowEdges(execution);
    }

    /**
     * Registers a placeholder snapshot for a single step.
     *
     * @param execution The workflow execution
     * @param stepId The step ID
     * @param timestamp The timestamp
     * @param extraFields Additional fields to include
     */
    public void registerPlaceholderSnapshot(WorkflowExecution execution,
                                             String stepId,
                                             long timestamp,
                                             Map<String, Object> extraFields) {
        if (execution == null || stepId == null) {
            return;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("runId", execution.getRunId());
        payload.put("stepAlias", stepId);
        payload.put("normalizedStepId", stepId);
        payload.put("status", "pending");
        payload.put("message", "Pending");
        payload.put("timestamp", timestamp);
        payload.put("statusCounts", defaultStatusCounts());

        if (extraFields != null) {
            extraFields.forEach((key, value) -> {
                if (value != null) {
                    payload.put(key, value);
                }
            });
        }

        eventPublisher.emitStep(execution.getRunId(), stepId, payload, StepLifecycle.PENDING);
    }

    /**
     * Creates default status counts map.
     *
     * @return Map with all status counts initialized to 0
     */
    public Map<String, Object> defaultStatusCounts() {
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("running", 0);
        counts.put("processed", 0);
        counts.put("total", 0);
        counts.put("completed", 0);
        counts.put("failed", 0);
        counts.put("skipped", 0);
        return counts;
    }
}
