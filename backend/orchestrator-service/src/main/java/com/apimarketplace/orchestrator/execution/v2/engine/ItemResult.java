package com.apimarketplace.orchestrator.execution.v2.engine;

import com.apimarketplace.orchestrator.domain.execution.NodeStatus;

import java.util.Map;
import java.util.Optional;

/**
 * Result of executing a single item through the workflow.
 */
public record ItemResult(
    String itemId,
    NodeStatus status,
    Map<String, Object> outputs,
    Optional<String> errorMessage
) {

    public static ItemResult success(String itemId, ExecutionContext finalContext) {
        return new ItemResult(
            itemId,
            NodeStatus.COMPLETED,
            finalContext.getAllStepOutputs(),
            Optional.empty()
        );
    }

    public static ItemResult failure(String itemId, String error) {
        return new ItemResult(
            itemId,
            NodeStatus.FAILED,
            Map.of(),
            Optional.of(error)
        );
    }

    public boolean isSuccess() {
        return status == NodeStatus.COMPLETED;
    }

    public boolean isFailure() {
        return status == NodeStatus.FAILED;
    }
}
