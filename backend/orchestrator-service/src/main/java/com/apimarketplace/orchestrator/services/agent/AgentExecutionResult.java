package com.apimarketplace.orchestrator.services.agent;

import com.apimarketplace.agent.domain.AgentStopReason;
import com.apimarketplace.agent.domain.CompletionResponse;
import com.apimarketplace.agent.domain.Message;
import com.apimarketplace.agent.domain.ToolResult;
import com.apimarketplace.agent.domain.UsageInfo;
import lombok.Builder;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Result of agent execution, including the final response and execution trace.
 */
@Getter
@Builder
public class AgentExecutionResult {
    
    /**
     * The final response from the agent
     */
    private final CompletionResponse finalResponse;
    
    /**
     * All tool results from the execution
     */
    @Builder.Default
    private final List<ToolResult> toolResults = new ArrayList<>();
    
    /**
     * Number of iterations (LLM calls) made
     */
    private final int iterations;
    
    /**
     * Total token usage across all iterations
     */
    private final UsageInfo totalUsage;
    
    /**
     * Whether execution was successful
     */
    private final boolean success;
    
    /**
     * Error message if execution failed
     */
    private final String error;
    
    /**
     * Total execution time in milliseconds
     */
    private final long durationMs;
    
    /**
     * The provider used for execution
     */
    private final String provider;
    
    /**
     * The model used for execution
     */
    private final String model;

    /**
     * Full conversation history trace (all messages including tool results)
     */
    @Builder.Default
    private final List<Message> conversationHistory = new ArrayList<>();

    /**
     * Reason why the agent stopped execution
     */
    private final AgentStopReason stopReason;

    /**
     * Metrics for observability
     */
    @Builder.Default
    private final Map<String, Object> metrics = Map.of();

    /**
     * Per-iteration token usage breakdown
     */
    @Builder.Default
    private final List<UsageInfo> usagePerIteration = new ArrayList<>();

    /**
     * Per-iteration durations in milliseconds
     */
    @Builder.Default
    private final List<Long> iterationDurations = new ArrayList<>();

    /**
     * Per-iteration finish reasons from LLM
     */
    @Builder.Default
    private final List<String> finishReasonsPerIteration = new ArrayList<>();

    /**
     * Create a successful result (legacy - without conversation history)
     */
    public static AgentExecutionResult success(CompletionResponse response,
                                                List<ToolResult> toolResults,
                                                int iterations,
                                                UsageInfo usage,
                                                long durationMs,
                                                String provider,
                                                String model) {
        return success(response, toolResults, iterations, usage, durationMs, provider, model,
                List.of(), AgentStopReason.COMPLETED, Map.of());
    }

    /**
     * Create a successful result with full conversation trace
     */
    public static AgentExecutionResult success(CompletionResponse response,
                                                List<ToolResult> toolResults,
                                                int iterations,
                                                UsageInfo usage,
                                                long durationMs,
                                                String provider,
                                                String model,
                                                List<Message> conversationHistory,
                                                AgentStopReason stopReason,
                                                Map<String, Object> metrics) {
        return AgentExecutionResult.builder()
            .finalResponse(response)
            .toolResults(toolResults)
            .iterations(iterations)
            .totalUsage(usage)
            .success(true)
            .durationMs(durationMs)
            .provider(provider)
            .model(model)
            .conversationHistory(conversationHistory)
            .stopReason(stopReason)
            .metrics(metrics)
            .build();
    }
    
    /**
     * Create a failed result
     */
    public static AgentExecutionResult failure(String error, long durationMs, String provider) {
        return failure(error, durationMs, provider, AgentStopReason.ERROR);
    }

    /**
     * Create a failed result with specific stop reason
     */
    public static AgentExecutionResult failure(String error, long durationMs, String provider, AgentStopReason stopReason) {
        return AgentExecutionResult.builder()
            .success(false)
            .error(error)
            .durationMs(durationMs)
            .provider(provider)
            .stopReason(stopReason)
            .build();
    }
    
    /**
     * Get the final content from the response
     */
    public String getContent() {
        return finalResponse != null ? finalResponse.content() : null;
    }
}
