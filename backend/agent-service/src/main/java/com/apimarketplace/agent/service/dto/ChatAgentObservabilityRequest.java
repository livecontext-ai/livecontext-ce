package com.apimarketplace.agent.service.dto;

import java.util.List;
import java.util.Map;

/**
 * DTO for chat agent observability recording.
 * Sent by conversation-service after agent streaming completes.
 * Supports both legacy (counters only) and full-detail recording.
 */
public record ChatAgentObservabilityRequest(
    // Agent identity
    String agentEntityId,

    // LLM config
    String provider,
    String model,
    Double temperature,
    Integer maxTokens,
    Integer maxIterations,

    // Outcome
    boolean success,
    String stopReason,
    /** Budget guard scope when stopReason=BUDGET_EXHAUSTED ('tenant' | 'agent'). */
    String budgetScope,
    String errorMessage,

    // Counters
    long durationMs,
    int iterationCount,
    int totalToolCalls,
    int successfulToolCalls,
    int failedToolCalls,
    int messageCount,

    // Token usage
    int totalPromptTokens,
    int totalCompletionTokens,
    int totalTokens,

    // Extended token usage
    Integer totalCacheCreationTokens,
    Integer totalCacheReadTokens,
    Integer totalCachedTokens,
    Integer totalReasoningTokens,

    // Tool patterns
    String toolSequence,
    List<String> distinctTools,
    boolean loopDetected,
    String loopType,
    String loopToolName,

    // System prompt & user prompt (used for controlled SYSTEM+USER prepending in observability)
    String systemPrompt,
    String userPrompt,

    // Chat context
    String conversationId,

    // Source type: "CHAT" (default), "WEBHOOK", "TASK", "TASK_REVIEW"
    String source,

    // Task linkage - set when this execution was triggered by an agent task
    String taskId,

    // Stable correlation ID minted at dispatch (= the agent_executions.id we will INSERT).
    // Carried through MCP credentials so AgentTaskService.claimTask can write the
    // claim log row keyed by this id BEFORE the agent_executions row exists. The
    // observability writer then reads the claim log to populate the denormalised
    // agent_executions.task_id when the workflow caller didn't supply it. Distinct
    // from the workflow-level runId (which maps to workflow_run.id, set by the
    // orchestrator path only - null on the chat / schedule / webhook path).
    String executionId,

    // Detailed data (nullable for backward compat)
    List<ToolResultDto> toolResults,
    List<MessageDto> conversationHistory,
    List<UsageInfoDto> usagePerIteration,
    List<Long> iterationDurations,
    List<String> finishReasonsPerIteration,
    List<Integer> toolCallsPerIteration
) {

    public record ToolResultDto(
        String toolCallId,
        String toolName,
        Object arguments,  // Can be Map<String,Object> or pre-serialized JSON String
        boolean success,
        String content,
        String error,
        Long durationMs,
        Map<String, Object> metadata
    ) {}

    public record MessageDto(
        String role,
        String content,
        String toolCallId,
        String toolName,
        List<ToolCallDto> toolCalls
    ) {}

    public record ToolCallDto(
        String id,
        String toolName,
        Map<String, Object> arguments
    ) {}

    public record UsageInfoDto(
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens,
        Integer cacheCreationInputTokens,
        Integer cacheReadInputTokens,
        Integer cachedTokens,
        Integer reasoningTokens
    ) {}
}
