package com.apimarketplace.agent.streaming;

import com.apimarketplace.agent.domain.CompletionResponse;
import com.apimarketplace.agent.domain.ToolCall;
import com.apimarketplace.agent.domain.ToolResult;

import java.util.function.LongSupplier;

/**
 * {@link StreamingCallback} decorator that enforces an INACTIVITY watchdog.
 *
 * <p>If the wrapped agent produces NO activity (a content token, a thinking token, a tool call, or
 * a tool result) for longer than the configured window, {@link #shouldStop()} flips to {@code true}
 * so the agent loop can terminate the run with
 * {@link com.apimarketplace.agent.domain.AgentStopReason#INACTIVITY_TIMEOUT}.</p>
 *
 * <p>This is deliberately distinct from the total {@code executionTimeout}: an agent that keeps
 * streaming resets the idle clock on every event and is <strong>never</strong> stopped by this
 * watchdog, regardless of total runtime. Only silence (a stalled/hung provider or downstream call)
 * trips it - which is exactly the "dead agent" case a total-time cap cannot catch.</p>
 *
 * <p>The watchdog only flags intent (via {@code shouldStop()} returning true). On the streaming
 * path that flag is polled per line at the provider read loop, and at every agent-loop boundary, so
 * an agent that emits some bytes but no real progress is broken at the next check. A fully-silent
 * socket (no bytes at all) is broken by the provider's socket read timeout, which the loop aligns
 * to this same window - see {@link #getInactivityTimeoutMs()}.</p>
 *
 * <p>Thread-safety: {@code lastActivityMs} and {@code idleTripped} are volatile; activity is stamped
 * from the provider's streaming thread while {@code shouldStop()}/{@code isIdleTripped()} may be read
 * from the agent-loop thread.</p>
 */
public class InactivityWatchdogCallback implements StreamingCallback {

    private final StreamingCallback delegate;
    private final long inactivityTimeoutMs;
    private final LongSupplier nowMs;

    private volatile long lastActivityMs;
    private volatile boolean idleTripped;

    public InactivityWatchdogCallback(StreamingCallback delegate, long inactivityTimeoutMs) {
        this(delegate, inactivityTimeoutMs, System::currentTimeMillis);
    }

    /**
     * Advanced/testing constructor: inject a controllable clock (a fake clock in tests, or a
     * monotonic time source). Production code uses the 2-arg constructor.
     */
    public InactivityWatchdogCallback(StreamingCallback delegate, long inactivityTimeoutMs, LongSupplier nowMs) {
        this.delegate = delegate;
        this.inactivityTimeoutMs = inactivityTimeoutMs;
        this.nowMs = nowMs;
        this.lastActivityMs = nowMs.getAsLong();
    }

    /** True when this watchdog has an active (positive) inactivity window. */
    public boolean isEnabled() {
        return inactivityTimeoutMs > 0;
    }

    /** The configured inactivity window in ms ({@code <= 0} when disabled). */
    public long getInactivityTimeoutMs() {
        return inactivityTimeoutMs;
    }

    /**
     * True once the idle window has been exceeded (latched). The agent loop reads this AFTER the
     * loop breaks to reclassify a {@code STOPPED_BY_USER} break into {@code INACTIVITY_TIMEOUT}.
     */
    public boolean isIdleTripped() {
        return idleTripped;
    }

    private void markActivity() {
        lastActivityMs = nowMs.getAsLong();
    }

    @Override
    public void onChunk(String content) {
        markActivity();
        delegate.onChunk(content);
    }

    @Override
    public void onThinking(String thinking) {
        markActivity();
        delegate.onThinking(thinking);
    }

    @Override
    public void onToolCall(ToolCall toolCall) {
        markActivity();
        delegate.onToolCall(toolCall);
    }

    @Override
    public void onToolResult(ToolResult result) {
        markActivity();
        delegate.onToolResult(result);
    }

    @Override
    public void onComplete(CompletionResponse response) {
        delegate.onComplete(response);
    }

    @Override
    public void onError(String error) {
        delegate.onError(error);
    }

    @Override
    public void onError(String error, Throwable exception) {
        delegate.onError(error, exception);
    }

    @Override
    public void onKeepAlive() {
        // A tool finished executing - the agent is alive and working, so reset the idle clock even
        // though no token/tool event streamed. Prevents a long multi-tool batch from false-tripping.
        markActivity();
        delegate.onKeepAlive();
    }

    @Override
    public boolean shouldStop() {
        // A real user/system cancel wins and short-circuits, so it is never relabeled as inactivity.
        if (delegate.shouldStop()) {
            return true;
        }
        if (isEnabled() && (nowMs.getAsLong() - lastActivityMs) > inactivityTimeoutMs) {
            idleTripped = true;
            return true;
        }
        return false;
    }

    @Override
    public long getCompletionTokenBudget() {
        return delegate.getCompletionTokenBudget();
    }
}
