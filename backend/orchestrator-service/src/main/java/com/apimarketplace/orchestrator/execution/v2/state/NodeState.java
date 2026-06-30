package com.apimarketplace.orchestrator.execution.v2.state;

import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * State of a single node execution.
 * Immutable record.
 */
public record NodeState(
    String nodeId,
    NodeStatus status,
    Instant startTime,
    Instant endTime,
    Map<String, Object> output,
    Optional<String> errorMessage
) {

    public static NodeState from(NodeExecutionResult result) {
        return new NodeState(
            result.nodeId(),
            result.status(),
            Instant.now(),
            Instant.now(),
            result.output(),
            result.errorMessage()
        );
    }

    public static NodeState running(String nodeId) {
        return new NodeState(
            nodeId,
            NodeStatus.RUNNING,
            Instant.now(),
            null,
            Map.of(),
            Optional.empty()
        );
    }

    public static NodeState pending(String nodeId) {
        return new NodeState(
            nodeId,
            NodeStatus.PENDING,
            null,
            null,
            Map.of(),
            Optional.empty()
        );
    }
}
