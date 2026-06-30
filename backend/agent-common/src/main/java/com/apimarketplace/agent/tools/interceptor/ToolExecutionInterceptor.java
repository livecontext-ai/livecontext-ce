package com.apimarketplace.agent.tools.interceptor;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;

import java.util.Map;

/**
 * Interceptor interface for cross-cutting concerns during tool execution.
 * Implementations can handle logging, metrics, rate limiting, auditing, etc.
 */
public interface ToolExecutionInterceptor {

    /**
     * Called before tool execution.
     *
     * @param toolName   The name of the tool being executed
     * @param parameters The parameters passed to the tool
     * @param context    The execution context
     */
    default void beforeExecution(String toolName, Map<String, Object> parameters, ToolExecutionContext context) {
        // Default: no-op
    }

    /**
     * Called after successful tool execution.
     *
     * @param toolName   The name of the tool that was executed
     * @param result     The execution result
     * @param durationMs Execution duration in milliseconds
     */
    default void afterExecution(String toolName, ToolExecutionResult result, long durationMs) {
        // Default: no-op
    }

    /**
     * Called when tool execution throws an exception.
     *
     * @param toolName   The name of the tool that failed
     * @param exception  The exception that was thrown
     * @param durationMs Execution duration in milliseconds
     */
    default void onError(String toolName, Exception exception, long durationMs) {
        // Default: no-op
    }

    /**
     * Get the order of this interceptor.
     * Lower values are executed first.
     *
     * @return Order value (default 100)
     */
    default int getOrder() {
        return 100;
    }
}
