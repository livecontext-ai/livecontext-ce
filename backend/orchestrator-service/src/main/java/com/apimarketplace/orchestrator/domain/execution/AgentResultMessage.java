package com.apimarketplace.orchestrator.domain.execution;

import java.time.Instant;
import java.util.Map;

/**
 * Message format for async agent execution results.
 * Published by agent-service when an offloaded agent task completes.
 * Consumed by {@code AgentAsyncCompletionService}, which delivers the result back into
 * the same sync persistence pipeline used by inline agent execution.
 *
 * @param correlationId unique ID linking this result to the original queue request and PendingAgent entry
 * @param runId         workflow run ID (for batching by run)
 * @param nodeId        the agent node that initiated the request
 * @param result        JSON result payload (agent output, classify result, or guardrail result)
 * @param success       whether the agent execution succeeded
 * @param errorMessage  error message if execution failed (null on success)
 * @param agentType     agent type: "agent", "classify", or "guardrail"
 * @param completedAt   timestamp of completion on the agent-service side
 */
public record AgentResultMessage(
    String correlationId,
    String runId,
    String nodeId,
    Map<String, Object> result,
    boolean success,
    String errorMessage,
    String agentType,
    Instant completedAt
) {

    /**
     * Convert this result to resolution data for signal storage.
     */
    public Map<String, Object> toResolutionData() {
        var data = new java.util.HashMap<String, Object>();
        data.put("correlationId", correlationId);
        data.put("agentType", agentType);
        data.put("success", success);
        if (result != null) {
            data.put("result", result);
        }
        if (errorMessage != null) {
            data.put("errorMessage", errorMessage);
        }
        if (completedAt != null) {
            data.put("completedAt", completedAt.toString());
        }
        return data;
    }

    /**
     * Check if this result represents an error.
     */
    public boolean isError() {
        return !success;
    }
}
