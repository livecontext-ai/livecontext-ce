package com.apimarketplace.orchestrator.execution.v2.engine;

import com.apimarketplace.orchestrator.domain.execution.NodeStatus;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Aggregated result of workflow execution for all items.
 */
public record WorkflowResult(
    String runId,
    NodeStatus overallStatus,
    int totalItems,
    int successItems,
    int failedItems,
    List<ItemResult> itemResults,
    Optional<String> errorMessage
) {

    /**
     * Aggregates results from all items.
     */
    public static WorkflowResult aggregate(String runId, List<ItemResult> itemResults) {
        int total = itemResults.size();
        int success = (int) itemResults.stream().filter(ItemResult::isSuccess).count();
        int failed = (int) itemResults.stream().filter(ItemResult::isFailure).count();

        NodeStatus overallStatus;
        if (failed == 0) {
            overallStatus = NodeStatus.COMPLETED;
        } else if (success == 0) {
            overallStatus = NodeStatus.FAILED;
        } else {
            overallStatus = NodeStatus.COMPLETED;  // Partial success
        }

        return new WorkflowResult(
            runId,
            overallStatus,
            total,
            success,
            failed,
            itemResults,
            Optional.empty()
        );
    }

    public static WorkflowResult failed(String runId, String error) {
        return new WorkflowResult(
            runId,
            NodeStatus.FAILED,
            0,
            0,
            0,
            List.of(),
            Optional.of(error)
        );
    }

    public boolean isSuccess() {
        return overallStatus == NodeStatus.COMPLETED;
    }

    /**
     * Gets outputs for a specific step across all items.
     */
    public Map<String, Object> getStepOutputs(String stepId) {
        return itemResults.stream()
            .filter(ItemResult::isSuccess)
            .filter(r -> r.outputs().containsKey(stepId))
            .collect(Collectors.toMap(
                ItemResult::itemId,
                r -> r.outputs().get(stepId)
            ));
    }
}
