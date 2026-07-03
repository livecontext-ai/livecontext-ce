package com.apimarketplace.agent.streaming;

import com.apimarketplace.agent.domain.CompletionResponse;
import com.apimarketplace.agent.domain.ToolCall;
import com.apimarketplace.agent.domain.ToolResult;

import java.util.Objects;

/**
 * Fans every streaming event out to two delegates.
 *
 * <p>Motivation: the conversation-format callback (live transcript on
 * {@code ws:conversation:{conversationId}}) and the workflow-format callback
 * (tool envelopes on {@code ws:workflow:run:{runId}}) used to be mutually
 * exclusive - an agent NODE with a user-facing conversation had to pick one,
 * so either the conversation panel or the workflow run view went silent.
 * The tee removes the exclusivity: a workflow agent node with a conversation
 * streams to BOTH surfaces.
 *
 * <p>Delegation is sequential (primary first). Exceptions propagate exactly
 * like the single-callback path did - the tee adds no swallowing.
 * {@link #shouldStop()} is an OR so either side's cancel signal stops the
 * loop. The scalar hints (token budget, inactivity window) take the primary's
 * value and fall back to the secondary's when the primary declines.
 */
public class TeeStreamingCallback implements StreamingCallback {

    private final StreamingCallback primary;
    private final StreamingCallback secondary;

    public TeeStreamingCallback(StreamingCallback primary, StreamingCallback secondary) {
        this.primary = Objects.requireNonNull(primary, "primary");
        this.secondary = Objects.requireNonNull(secondary, "secondary");
    }

    @Override
    public void onChunk(String content) {
        primary.onChunk(content);
        secondary.onChunk(content);
    }

    @Override
    public void onThinking(String thinking) {
        primary.onThinking(thinking);
        secondary.onThinking(thinking);
    }

    @Override
    public void onToolCall(ToolCall toolCall) {
        primary.onToolCall(toolCall);
        secondary.onToolCall(toolCall);
    }

    @Override
    public void onToolResult(ToolResult result) {
        primary.onToolResult(result);
        secondary.onToolResult(result);
    }

    @Override
    public void onComplete(CompletionResponse response) {
        primary.onComplete(response);
        secondary.onComplete(response);
    }

    @Override
    public void onError(String error) {
        primary.onError(error);
        secondary.onError(error);
    }

    @Override
    public void onError(String error, Throwable exception) {
        primary.onError(error, exception);
        secondary.onError(error, exception);
    }

    @Override
    public boolean shouldStop() {
        return primary.shouldStop() || secondary.shouldStop();
    }

    @Override
    public long getCompletionTokenBudget() {
        long p = primary.getCompletionTokenBudget();
        return p >= 0 ? p : secondary.getCompletionTokenBudget();
    }

    @Override
    public long getInactivityTimeoutMs() {
        long p = primary.getInactivityTimeoutMs();
        return p >= 0 ? p : secondary.getInactivityTimeoutMs();
    }

    @Override
    public void onKeepAlive() {
        primary.onKeepAlive();
        secondary.onKeepAlive();
    }
}
