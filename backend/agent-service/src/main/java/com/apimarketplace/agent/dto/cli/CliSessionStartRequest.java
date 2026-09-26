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
    List<String> approvedToolActions,

    /**
     * The bridge's inactivity watchdog window for THIS run, in seconds (0 = disabled).
     *
     * <p>The bridge kills a run that produces no CLI output for this long, and a tool call
     * that parks on a user approval card is silent for its whole wait. Without the number
     * here, the approval gate cannot tell how much silence it may spend, so it would size a
     * park against its own default and let the watchdog kill the run mid-execution - right
     * after the user clicked approve, with the action already paid for. It is per-agent
     * (config, 10-7200 s), which is why it has to travel rather than be assumed.
     */
    Integer inactivityTimeoutSeconds,

    /**
     * How long a tool call may be HELD on the CLI this session belongs to, in seconds, as
     * worked out by the bridge from the per-call timeout it configured for that CLI. The
     * approval gate parks a call (an authorization card, an ask_user question) no longer
     * than this. Null when the bridge did not say: the gate then uses its shortest-CLI floor.
     * Read within 1..600 s ({@code ParkRequests.MIN_CLI_MAX_PARK_MS} / {@code MAX_CLI_MAX_PARK_MS},
     * values outside are pulled to the nearest bound; 0 and negative mean "not said"). The
     * gate's budget and half the inactivity window still bind above it.
     */
    Integer maxToolHoldSeconds,
    /**
     * The task this run executes, when it does ({@code __taskId__}). Null for a chat. It is what
     * makes the run not promptable: a task has nobody in front of the chat, so a question goes to
     * the connected chat channel instead of waiting on a screen nobody has open.
     */
    String taskId,
    /** True when the dispatcher said nobody is in front of this run: schedule, webhook, task. */
    Boolean unattendedRun,
    /** True when the bound agent asks permission for sensitive actions wherever it runs. */
    Boolean requireToolAuthorization,
    /**
     * How deep this run is in a chain of agents ({@code __agent_depth__}): 0 or null for a run a
     * person started, 1 and more for a sub-agent. A sub-agent's conversation is read by nobody and
     * its parent is waiting on it, so it must never park a card for a person.
     */
    Integer agentDepth,
    /**
     * The workflow run this session's agent node belongs to ({@code __workflowRunId__}). A reply
     * arriving later cannot re-enter a completed node, so such a run never asks through a channel.
     */
    String workflowRunId
) {
    public CliSessionStartRequest(List<String> enabledModules, String sessionId, String model,
                                  String conversationId, String conversationServiceUrl, String streamId,
                                  Boolean isNewConversation, String agentId, String executionId,
                                  List<String> approvedToolActions, Integer inactivityTimeoutSeconds,
                                  Integer maxToolHoldSeconds, String taskId, Boolean unattendedRun,
                                  Boolean requireToolAuthorization, Integer agentDepth) {
        this(enabledModules, sessionId, model, conversationId, conversationServiceUrl, streamId,
                isNewConversation, agentId, executionId, approvedToolActions, inactivityTimeoutSeconds,
                maxToolHoldSeconds, taskId, unattendedRun, requireToolAuthorization, agentDepth, null);
    }

    public CliSessionStartRequest(List<String> enabledModules, String sessionId, String model,
                                  String conversationId, String conversationServiceUrl, String streamId,
                                  Boolean isNewConversation, String agentId, String executionId,
                                  List<String> approvedToolActions, Integer inactivityTimeoutSeconds,
                                  Integer maxToolHoldSeconds, String taskId, Boolean unattendedRun,
                                  Boolean requireToolAuthorization) {
        this(enabledModules, sessionId, model, conversationId, conversationServiceUrl, streamId,
                isNewConversation, agentId, executionId, approvedToolActions, inactivityTimeoutSeconds,
                maxToolHoldSeconds, taskId, unattendedRun, requireToolAuthorization, null, null);
    }

    public CliSessionStartRequest(List<String> enabledModules, String sessionId, String model,
                                  String conversationId, String conversationServiceUrl, String streamId,
                                  Boolean isNewConversation, String agentId, String executionId,
                                  List<String> approvedToolActions, Integer inactivityTimeoutSeconds,
                                  Integer maxToolHoldSeconds) {
        this(enabledModules, sessionId, model, conversationId, conversationServiceUrl, streamId,
                isNewConversation, agentId, executionId, approvedToolActions, inactivityTimeoutSeconds,
                maxToolHoldSeconds, null, null, null);
    }


    public CliSessionStartRequest(List<String> enabledModules, String sessionId, String model,
                                  String conversationId, String conversationServiceUrl, String streamId,
                                  Boolean isNewConversation, String agentId, String executionId,
                                  List<String> approvedToolActions, Integer inactivityTimeoutSeconds) {
        this(enabledModules, sessionId, model, conversationId, conversationServiceUrl, streamId,
                isNewConversation, agentId, executionId, approvedToolActions, inactivityTimeoutSeconds, null,
                null, null, null);
    }

    /**
     * The shape before the inactivity window was threaded through. Kept so the many existing
     * construction sites (and an older bridge that does not send the field yet) stay valid:
     * a missing window means "not told", which the approval gate reads as "no watchdog
     * ceiling to respect" - the behaviour that shipped before.
     */
    public CliSessionStartRequest(List<String> enabledModules, String sessionId, String model,
                                  String conversationId, String conversationServiceUrl, String streamId,
                                  Boolean isNewConversation, String agentId, String executionId,
                                  List<String> approvedToolActions) {
        this(enabledModules, sessionId, model, conversationId, conversationServiceUrl, streamId,
                isNewConversation, agentId, executionId, approvedToolActions, null, null);
    }
}
