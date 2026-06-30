package com.apimarketplace.agent.streaming;

import com.apimarketplace.agent.domain.CompletionResponse;
import com.apimarketplace.agent.domain.ToolCall;
import com.apimarketplace.agent.domain.ToolResult;

/**
 * Callback interface for streaming LLM responses.
 * Implementations receive real-time updates as the LLM generates content.
 */
public interface StreamingCallback {

    /**
     * Called when a new content chunk is received.
     *
     * @param content The content chunk
     */
    void onChunk(String content);

    /**
     * Called when thinking/reasoning content is received (for thinking models like Gemini 2.5+, o1, etc.).
     * This is the model's internal reasoning process before producing the final response.
     *
     * @param thinking The thinking/reasoning content
     */
    default void onThinking(String thinking) {
        // Default: do nothing - implementations can override to display thinking
    }

    /**
     * Called when the LLM requests a tool call.
     * In streaming mode, tool calls may be built incrementally.
     *
     * @param toolCall The tool call request
     */
    void onToolCall(ToolCall toolCall);

    /**
     * Called when a tool execution completes.
     * This allows intercepting tool results for special handling
     * (e.g., visualization markers, progress updates).
     *
     * @param result The tool execution result
     */
    default void onToolResult(ToolResult result) {
        // Default: do nothing - implementations can override
    }

    /**
     * Called when the streaming is complete.
     *
     * @param response The final complete response
     */
    void onComplete(CompletionResponse response);

    /**
     * Called when an error occurs during streaming.
     *
     * @param error The error message
     */
    void onError(String error);

    /**
     * Called when an error occurs during streaming with exception.
     *
     * @param error The error message
     * @param exception The exception that caused the error
     */
    default void onError(String error, Throwable exception) {
        onError(error + ": " + exception.getMessage());
    }

    /**
     * Called by the agent loop to check if execution should stop.
     * Implementations can return true to signal a hard stop (e.g., user cancelled).
     * The agent loop will exit immediately without completing the current iteration.
     *
     * @return true if the agent loop should stop immediately, false to continue
     */
    default boolean shouldStop() {
        return false;
    }

    /**
     * Optional hard cap on completion tokens for this streaming turn.
     *
     * <p>When non-negative, the LLM provider tracks tokens streamed so far (via a
     * character-based approximation on the accumulated content) and sets an internal
     * stop when the budget is exceeded, without consulting {@link #shouldStop}.
     * This is the last line of defense against a single-turn overdraft: the pre-flight
     * gate may have passed on an optimistic estimate, and without this cap a runaway
     * generation keeps burning tokens until the provider itself stops.
     *
     * <p>The cap is best-effort: the check uses ~4 chars/token, which under-estimates
     * for some tokenizers, so a conservative caller should pass a tight upper bound
     * (e.g. the agent's configured {@code max_tokens}). Callers that don't care return
     * the default {@code -1} and the cap is inert.
     *
     * <p>Intentionally local - no HTTP call in the hot streaming path.
     *
     * @return max completion tokens for this turn, or {@code -1} for no cap
     */
    default long getCompletionTokenBudget() {
        return -1;
    }

    /**
     * Inactivity watchdog window in milliseconds for this run, or {@code -1} when no watchdog
     * applies.
     *
     * <p>When positive, the blocking streaming provider tightens the socket read timeout to a
     * sub-window cadence and polls: a read timeout is treated as "no provider output for the poll
     * window", {@link #shouldStop()} is re-checked (the watchdog trips once total silence exceeds
     * this window), and otherwise the read is retried. This lets a fully-silent (zero-byte) stream
     * be broken at the inactivity window rather than only at the much larger socket read timeout.
     * Overridden by {@code InactivityWatchdogCallback}; the default means "no inactivity polling".</p>
     *
     * @return the inactivity window in ms, or {@code -1} for none
     */
    default long getInactivityTimeoutMs() {
        return -1;
    }

    /**
     * Liveness ping: "the agent is still doing work even though it is not streaming output right
     * now." The agent loop calls this as each tool in a multi-tool batch finishes, so that time
     * spent executing tools does NOT count as inactivity (a sequence of legitimately long tool
     * calls must not be mistaken for a hung agent). The inactivity watchdog overrides this to reset
     * its idle clock; every other implementation is a no-op.
     */
    default void onKeepAlive() {
        // Default: do nothing.
    }
}
