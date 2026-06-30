package com.apimarketplace.agent.client.queue;

import java.util.Map;

/**
 * Message published to the agent execution queue when async mode is enabled.
 * Contains all information needed for agent-service to execute the request
 * and correlate the result back to the waiting caller.
 *
 * <p>Producers: orchestrator's workflow agent path AND conversation-service's
 * chat path (since PR2 - chat-on-queue unification). Both push to the same
 * Redis lists ({@code agent:queue:agent}, {@code agent:queue:classify},
 * {@code agent:queue:guardrail}) consumed by {@code AgentQueueWorkerService}
 * in agent-service.</p>
 *
 * @param correlationId unique ID for result correlation
 * @param runId         workflow run ID (null for chat-originated tasks)
 * @param nodeId        the agent node ID (null for chat-originated tasks)
 * @param tenantId      tenant/user ID
 * @param agentType     agent type: "agent", "classify", or "guardrail"
 * @param provider      LLM provider name
 * @param model         LLM model name
 * @param requestPayload serialized agent request (AgentExecutionRequestDto, ClassifyRequestDto, or GuardrailRequestDto)
 * @param userRoles    optional comma-separated caller roles from X-User-Roles for queued bridge policy checks
 * @param schemaVersion message schema version for backward compatibility
 */
public record AgentExecutionRequestMessage(
    String correlationId,
    String runId,
    String nodeId,
    String tenantId,
    String agentType,
    String provider,
    String model,
    Map<String, Object> requestPayload,
    String userRoles,
    int schemaVersion
) {

    /** Current schema version */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public static AgentExecutionRequestMessage create(
            String correlationId,
            String runId,
            String nodeId,
            String tenantId,
            String agentType,
            String provider,
            String model,
            Map<String, Object> requestPayload) {
        return create(
            correlationId, runId, nodeId, tenantId,
            agentType, provider, model, requestPayload, null
        );
    }

    public static AgentExecutionRequestMessage create(
            String correlationId,
            String runId,
            String nodeId,
            String tenantId,
            String agentType,
            String provider,
            String model,
            Map<String, Object> requestPayload,
            String userRoles) {
        return new AgentExecutionRequestMessage(
            correlationId, runId, nodeId, tenantId,
            agentType, provider, model, requestPayload, userRoles,
            CURRENT_SCHEMA_VERSION
        );
    }
}
