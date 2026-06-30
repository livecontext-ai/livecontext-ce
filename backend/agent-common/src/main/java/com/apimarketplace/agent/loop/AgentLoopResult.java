package com.apimarketplace.agent.loop;

import com.apimarketplace.agent.domain.AgentStopReason;
import com.apimarketplace.agent.domain.CompletionResponse;
import com.apimarketplace.agent.domain.Message;
import com.apimarketplace.agent.domain.ToolResult;
import com.apimarketplace.agent.domain.UsageInfo;
import lombok.Builder;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Result of agent loop execution.
 */
@Builder(toBuilder = true)
public record AgentLoopResult(
    /**
     * Whether the execution was successful
     */
    boolean success,

    /**
     * The final response from the LLM
     */
    CompletionResponse response,

    /**
     * Final content (extracted from response)
     */
    String content,

    /**
     * All tool results from the execution
     */
    List<ToolResult> toolResults,

    /**
     * Number of iterations executed
     */
    int iterations,

    /**
     * Total token usage
     */
    UsageInfo usage,

    /**
     * Total execution time in milliseconds
     */
    long durationMs,

    /**
     * Error message if execution failed
     */
    String error,

    /**
     * The provider used
     */
    String provider,

    /**
     * The model used
     */
    String model,

    /**
     * Full conversation history trace (all messages including tool results)
     */
    List<Message> conversationHistory,

    /**
     * Reason why the agent stopped execution
     */
    AgentStopReason stopReason,

    /**
     * Metrics for observability
     */
    Map<String, Object> metrics,

    /**
     * Per-iteration token usage breakdown
     */
    List<UsageInfo> usagePerIteration,

    /**
     * Per-iteration durations in milliseconds
     */
    List<Long> iterationDurations,

    /**
     * Per-iteration finish reasons from LLM
     */
    List<String> finishReasonsPerIteration
) {
    /**
     * Create a successful result (legacy - without conversation history)
     */
    public static AgentLoopResult success(CompletionResponse response,
                                          List<ToolResult> toolResults,
                                          int iterations,
                                          UsageInfo usage,
                                          long durationMs,
                                          String provider,
                                          String model) {
        return success(response, toolResults, iterations, usage, durationMs, provider, model,
                List.of(), AgentStopReason.COMPLETED, Map.of(),
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
    }

    /**
     * Create a successful result with full conversation trace (legacy without per-iteration data)
     */
    public static AgentLoopResult success(CompletionResponse response,
                                          List<ToolResult> toolResults,
                                          int iterations,
                                          UsageInfo usage,
                                          long durationMs,
                                          String provider,
                                          String model,
                                          List<Message> conversationHistory,
                                          AgentStopReason stopReason,
                                          Map<String, Object> metrics) {
        return success(response, toolResults, iterations, usage, durationMs, provider, model,
                conversationHistory, stopReason, metrics,
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
    }

    /**
     * Create a successful result with full conversation trace and per-iteration data
     */
    public static AgentLoopResult success(CompletionResponse response,
                                          List<ToolResult> toolResults,
                                          int iterations,
                                          UsageInfo usage,
                                          long durationMs,
                                          String provider,
                                          String model,
                                          List<Message> conversationHistory,
                                          AgentStopReason stopReason,
                                          Map<String, Object> metrics,
                                          List<UsageInfo> usagePerIteration,
                                          List<Long> iterationDurations,
                                          List<String> finishReasonsPerIteration) {
        return AgentLoopResult.builder()
            .success(true)
            .response(response)
            .content(response != null ? response.content() : "")
            .toolResults(toolResults)
            .iterations(iterations)
            .usage(usage)
            .durationMs(durationMs)
            .provider(provider)
            .model(model)
            .conversationHistory(conversationHistory)
            .stopReason(stopReason)
            .metrics(metrics)
            .usagePerIteration(usagePerIteration)
            .iterationDurations(iterationDurations)
            .finishReasonsPerIteration(finishReasonsPerIteration)
            .build();
    }

    /**
     * Create a failed result
     */
    public static AgentLoopResult failure(String error, long durationMs, String provider) {
        return failure(error, durationMs, provider, AgentStopReason.ERROR);
    }

    /**
     * Create a failed result with specific stop reason
     */
    public static AgentLoopResult failure(String error, long durationMs, String provider, AgentStopReason stopReason) {
        return AgentLoopResult.builder()
            .success(false)
            .error(error)
            .toolResults(Collections.emptyList())
            .durationMs(durationMs)
            .provider(provider)
            .stopReason(stopReason)
            .conversationHistory(Collections.emptyList())
            .usagePerIteration(Collections.emptyList())
            .iterationDurations(Collections.emptyList())
            .finishReasonsPerIteration(Collections.emptyList())
            .build();
    }
}
