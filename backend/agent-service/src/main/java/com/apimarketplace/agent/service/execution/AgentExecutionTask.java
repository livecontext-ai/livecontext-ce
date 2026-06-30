package com.apimarketplace.agent.service.execution;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * DTO representing an agent execution task dequeued from the distributed queue.
 * Used by {@link AgentQueueWorkerService} to route tasks to the appropriate
 * execution service (agent loop, classify, or guardrail).
 *
 * <p>The {@code agentType} field determines which execution path is used:
 * <ul>
 *   <li>{@code "agent"} - full agent loop (multi-iteration, tools, streaming)</li>
 *   <li>{@code "classify"} - single-shot classification</li>
 *   <li>{@code "guardrail"} - single-shot guardrail validation</li>
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentExecutionTask(
    /** Unique correlation ID for result publishing and deduplication */
    String correlationId,

    /** Execution type: "agent", "classify", or "guardrail" */
    String agentType,

    /** Serialized JSON request payload (AgentExecutionRequestDto, ClassifyRequestDto, or GuardrailRequestDto) */
    String requestPayload,

    /** Priority level (0 = highest, 70 = lowest). Used for queue ordering. */
    int priority,

    /** Optional metadata for tracing and observability (runId, nodeId, tenantId, etc.) */
    Map<String, String> metadata
) {

    /** Agent type constant for full agent loop execution */
    public static final String TYPE_AGENT = "agent";

    /** Agent type constant for classification execution */
    public static final String TYPE_CLASSIFY = "classify";

    /** Agent type constant for guardrail validation execution */
    public static final String TYPE_GUARDRAIL = "guardrail";
}
