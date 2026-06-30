package com.apimarketplace.agent.logging;

import com.apimarketplace.agent.domain.ToolCall;
import com.apimarketplace.agent.domain.ToolResult;

import java.util.Map;

/**
 * Interface for agent execution logging.
 * Implementations can log to console, file, database, etc.
 */
public interface AgentLogger {

    /**
     * Log start of an agent execution.
     *
     * @param runId      Unique execution identifier
     * @param userPrompt The user's message
     * @param provider   The LLM provider (openai, anthropic, etc.)
     * @param model      The model being used
     */
    void logExecutionStart(String runId, String userPrompt, String provider, String model);

    /**
     * Log end of an agent execution.
     *
     * @param runId      Unique execution identifier
     * @param success    Whether execution succeeded
     * @param iterations Number of LLM iterations
     * @param toolCalls  Total number of tool calls
     * @param durationMs Execution duration in milliseconds
     * @param reason     End reason (COMPLETED, MAX_ITERATIONS, ERROR, etc.)
     */
    void logExecutionEnd(String runId, boolean success, int iterations,
                         int toolCalls, long durationMs, String reason);

    /**
     * Log a tool call before execution.
     *
     * @param runId    Unique execution identifier
     * @param toolCall The tool call to execute
     */
    void logToolCallStart(String runId, ToolCall toolCall);

    /**
     * Log a tool call result after execution.
     *
     * @param runId      Unique execution identifier
     * @param toolCall   The tool call that was executed
     * @param result     The tool result
     * @param durationMs Tool execution duration
     */
    void logToolCallEnd(String runId, ToolCall toolCall, ToolResult result, long durationMs);

    /**
     * Log an LLM iteration.
     *
     * @param runId        Unique execution identifier
     * @param iteration    Current iteration number
     * @param toolCallsCount Number of tool calls requested by LLM
     */
    void logIteration(String runId, int iteration, int toolCallsCount);

    /**
     * Log an error during execution.
     *
     * @param runId   Unique execution identifier
     * @param message Error message
     * @param error   The exception (may be null)
     */
    void logError(String runId, String message, Throwable error);

    /**
     * No-op logger that does nothing.
     */
    AgentLogger NOOP = new AgentLogger() {
        @Override public void logExecutionStart(String runId, String userPrompt, String provider, String model) {}
        @Override public void logExecutionEnd(String runId, boolean success, int iterations, int toolCalls, long durationMs, String reason) {}
        @Override public void logToolCallStart(String runId, ToolCall toolCall) {}
        @Override public void logToolCallEnd(String runId, ToolCall toolCall, ToolResult result, long durationMs) {}
        @Override public void logIteration(String runId, int iteration, int toolCallsCount) {}
        @Override public void logError(String runId, String message, Throwable error) {}
    };
}
