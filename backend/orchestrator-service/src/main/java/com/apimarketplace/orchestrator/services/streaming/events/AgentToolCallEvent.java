package com.apimarketplace.orchestrator.services.streaming.events;

import java.util.Map;

/**
 * Event emitted when an agent calls a tool during execution.
 * Enables real-time tracking of agent tool usage via streaming.
 */
public record AgentToolCallEvent(
    String runId,
    String nodeId,
    String toolName,
    String toolCallId,
    AgentToolCallPhase phase,
    Map<String, Object> payload,
    int itemIndex,
    Integer iteration,
    long timestamp
) implements WorkflowEvent {

    public AgentToolCallEvent {
        if (runId == null) {
            throw new IllegalArgumentException("runId is required");
        }
        if (nodeId == null) {
            throw new IllegalArgumentException("nodeId is required");
        }
        if (toolName == null) {
            throw new IllegalArgumentException("toolName is required");
        }
        if (phase == null) {
            throw new IllegalArgumentException("phase is required");
        }
    }
}
