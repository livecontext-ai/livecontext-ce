package com.apimarketplace.agent.dto.cli;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Request DTO to start a CLI tool execution session.
 * Claude Code is the agent - this just initializes the session and returns available tools.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CliSessionStartRequest(
    List<String> enabledModules,
    String sessionId,
    String model,
    String conversationId,
    String conversationServiceUrl,
    String streamId,
    Boolean isNewConversation,
    String agentId,
    /**
     * Stable correlation ID minted by the dispatcher (= agent_executions.id).
     * Threaded into MCP credentials as {@code __executionId__} so
     * {@code AgentTaskService.claimTask} writes the claim log row keyed by
     * this id, closing the race where the claim happens before the
     * {@code agent_executions} row exists. See {@link com.apimarketplace.agent.domain.AgentTaskClaimEntity}.
     */
    String executionId,

    /**
     * User-authorized sensitive tool-action rule keys ({@code "tool:action"}) for
     * this turn. Threaded from conversation-service (resolveAndConsumeForTurn) through
     * the bridge so {@code CliAgentService} injects {@code __approvedToolActions__}
     * into the session credentials - letting {@code ToolAuthorizationGuard} skip the
     * gate on a resume turn (bridge parity with the remote AgentLoopService path).
     */
    List<String> approvedToolActions
) {}
