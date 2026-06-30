package com.apimarketplace.agent.loop;

import com.apimarketplace.agent.domain.CompletionResponse;
import com.apimarketplace.agent.domain.ToolCall;
import com.apimarketplace.agent.domain.ToolResult;
import com.apimarketplace.agent.streaming.StreamingCallback;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression guard for the bug observed on 2026-04-29 where a DeepSeek streaming
 * conversation kept iterating 16+ times after the user clicked STOP.
 *
 * <p>Root cause: {@code AgentLoopExecutor.StreamingCollector} implemented
 * {@link StreamingCallback} but did NOT override the {@code default boolean
 * shouldStop()} (which returns {@code false}). When the LLM provider polled
 * {@code callback.shouldStop()} per streamed line, it always saw the wrapper's
 * default {@code false}, so the Redis cancel key set by the conversation STOP
 * was silently ignored and the agent kept consuming tokens until natural
 * completion (or never).
 *
 * <p>This test pins the delegation: the collector MUST forward
 * {@code shouldStop()} (and {@code getCompletionTokenBudget()}) to its delegate,
 * and MUST short-circuit {@code onChunk} when the delegate signals stop so
 * post-stop chunks neither inflate buffers nor leak through to the UI.
 */
@DisplayName("StreamingCollector - delegates shouldStop / completion-token budget to wrapped callback")
class StreamingCollectorShouldStopDelegationTest {

    private LoopExecutionState newState() {
        return new LoopExecutionState("run-test", 10, 0);
    }

    @Test
    @DisplayName("shouldStop() returns delegate's value (regression: pre-fix returned default false)")
    void shouldStopDelegatesToDelegate() {
        AtomicBoolean delegateStop = new AtomicBoolean(false);
        StreamingCallback delegate = new StreamingCallback() {
            @Override public void onChunk(String c) {}
            @Override public void onToolCall(ToolCall t) {}
            @Override public void onComplete(CompletionResponse r) {}
            @Override public void onError(String e) {}
            @Override public boolean shouldStop() { return delegateStop.get(); }
        };

        AgentLoopExecutor.StreamingCollector collector =
            new AgentLoopExecutor.StreamingCollector(delegate, newState());

        assertThat(collector.shouldStop()).isFalse();
        delegateStop.set(true);
        assertThat(collector.shouldStop())
            .as("collector must reflect delegate.shouldStop()=true so the LLM provider sees the cancel signal")
            .isTrue();
    }

    @Test
    @DisplayName("shouldStop() fails open when delegate throws - never crashes the LLM stream")
    void shouldStopFailsOpenWhenDelegateThrows() {
        StreamingCallback throwingDelegate = new StreamingCallback() {
            @Override public void onChunk(String c) {}
            @Override public void onToolCall(ToolCall t) {}
            @Override public void onComplete(CompletionResponse r) {}
            @Override public void onError(String e) {}
            @Override public boolean shouldStop() {
                throw new RuntimeException("redis-down");
            }
        };

        AgentLoopExecutor.StreamingCollector collector =
            new AgentLoopExecutor.StreamingCollector(throwingDelegate, newState());

        assertThat(collector.shouldStop())
            .as("delegate exceptions must NOT propagate into the provider read loop")
            .isFalse();
    }

    @Test
    @DisplayName("onChunk short-circuits when delegate signals stop - prevents post-stop buffer growth")
    void onChunkSkippedAfterStop() {
        AtomicBoolean stopped = new AtomicBoolean(false);
        AtomicInteger chunksSeenByDelegate = new AtomicInteger(0);
        StreamingCallback delegate = new StreamingCallback() {
            @Override public void onChunk(String c) { chunksSeenByDelegate.incrementAndGet(); }
            @Override public void onToolCall(ToolCall t) {}
            @Override public void onComplete(CompletionResponse r) {}
            @Override public void onError(String e) {}
            @Override public boolean shouldStop() { return stopped.get(); }
        };

        LoopExecutionState state = newState();
        AgentLoopExecutor.StreamingCollector collector =
            new AgentLoopExecutor.StreamingCollector(delegate, state);

        collector.onChunk("hello");
        assertThat(chunksSeenByDelegate.get()).isEqualTo(1);
        assertThat(state.getFullContent().toString()).isEqualTo("hello");

        stopped.set(true);
        collector.onChunk(" world");
        collector.onChunk(" more");
        assertThat(chunksSeenByDelegate.get())
            .as("post-stop chunks must not reach the delegate")
            .isEqualTo(1);
        assertThat(state.getFullContent().toString())
            .as("post-stop chunks must not grow the state buffer")
            .isEqualTo("hello");
    }

    @Test
    @DisplayName("getCompletionTokenBudget() delegates - caller-set cap is honored, not silently inerted")
    void completionTokenBudgetDelegates() {
        StreamingCallback delegate = new StreamingCallback() {
            @Override public void onChunk(String c) {}
            @Override public void onToolCall(ToolCall t) {}
            @Override public void onComplete(CompletionResponse r) {}
            @Override public void onError(String e) {}
            @Override public long getCompletionTokenBudget() { return 1234L; }
        };

        AgentLoopExecutor.StreamingCollector collector =
            new AgentLoopExecutor.StreamingCollector(delegate, newState());

        assertThat(collector.getCompletionTokenBudget()).isEqualTo(1234L);
    }

    @Test
    @DisplayName("getInactivityTimeoutMs() delegates - the inactivity window reaches the provider (regression: was inert)")
    void inactivityTimeoutDelegates() {
        // Pre-fix the collector did NOT override this, so the provider saw the interface default
        // (-1) and never tightened the streaming socket timeout -> a fully-silent stream was caught
        // at the 1h read timeout instead of at the watchdog window.
        StreamingCallback delegate = new StreamingCallback() {
            @Override public void onChunk(String c) {}
            @Override public void onToolCall(ToolCall t) {}
            @Override public void onComplete(CompletionResponse r) {}
            @Override public void onError(String e) {}
            @Override public long getInactivityTimeoutMs() { return 300_000L; }
        };

        AgentLoopExecutor.StreamingCollector collector =
            new AgentLoopExecutor.StreamingCollector(delegate, newState());

        assertThat(collector.getInactivityTimeoutMs())
            .as("collector MUST forward the inactivity window so AbstractLLMProvider tightens the socket read timeout")
            .isEqualTo(300_000L);
    }

    @Test
    @DisplayName("onKeepAlive() delegates - per-tool liveness pings reach the watchdog so long tool batches are not false-killed")
    void keepAliveDelegates() {
        AtomicInteger keepAlives = new AtomicInteger(0);
        StreamingCallback delegate = new StreamingCallback() {
            @Override public void onChunk(String c) {}
            @Override public void onToolCall(ToolCall t) {}
            @Override public void onComplete(CompletionResponse r) {}
            @Override public void onError(String e) {}
            @Override public void onKeepAlive() { keepAlives.incrementAndGet(); }
        };

        AgentLoopExecutor.StreamingCollector collector =
            new AgentLoopExecutor.StreamingCollector(delegate, newState());

        collector.onKeepAlive();
        collector.onKeepAlive();
        assertThat(keepAlives.get())
            .as("collector MUST forward onKeepAlive so the inactivity watchdog resets between tools")
            .isEqualTo(2);
    }

    @Test
    @DisplayName("onToolResult delegates - agent loop tool results reach the wrapped callback")
    void onToolResultDelegates() {
        AtomicInteger seen = new AtomicInteger(0);
        StreamingCallback delegate = new StreamingCallback() {
            @Override public void onChunk(String c) {}
            @Override public void onToolCall(ToolCall t) {}
            @Override public void onToolResult(ToolResult r) { seen.incrementAndGet(); }
            @Override public void onComplete(CompletionResponse r) {}
            @Override public void onError(String e) {}
        };
        AgentLoopExecutor.StreamingCollector collector =
            new AgentLoopExecutor.StreamingCollector(delegate, newState());

        collector.onToolResult(ToolResult.builder().success(true).content("ok").build());
        assertThat(seen.get()).isEqualTo(1);
    }
}
