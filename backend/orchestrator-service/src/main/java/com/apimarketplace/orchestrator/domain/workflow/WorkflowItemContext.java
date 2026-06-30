package com.apimarketplace.orchestrator.domain.workflow;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Contexte d'exécution isolé pour un item.
 */
public final class WorkflowItemContext {

    private final WorkflowExecution baseExecution;
    private final TriggerItemContext triggerItem;
    private final Map<String, StepStatus> stepStatuses = new ConcurrentHashMap<>();
    private final Map<String, Object> stepOutputs = new ConcurrentHashMap<>();

    public WorkflowItemContext(WorkflowExecution baseExecution, TriggerItemContext triggerItem) {
        this.baseExecution = baseExecution;
        this.triggerItem = triggerItem;
    }

    public WorkflowExecution getBaseExecution() {
        return baseExecution;
    }

    public TriggerItemContext getTriggerItem() {
        return triggerItem;
    }

    public Map<String, StepStatus> getStepStatuses() {
        return stepStatuses;
    }

    public Map<String, Object> getStepOutputs() {
        return stepOutputs;
    }

    public enum StepStatus {
        PENDING,
        RUNNING,
        COMPLETED,
        FAILED,
        SKIPPED
    }
}
