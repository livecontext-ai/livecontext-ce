package com.apimarketplace.agent.streaming;

import com.apimarketplace.agent.domain.CompletionResponse;
import com.apimarketplace.agent.domain.ToolCall;
import com.apimarketplace.agent.domain.ToolResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link InactivityWatchdogCallback}.
 *
 * <p>Verifies the core promise: a working agent (any activity within the window) is NEVER tripped,
 * while a silent agent trips once the window is exceeded - and a real user/system cancel always wins
 * (and is not relabeled as inactivity).</p>
 */
@DisplayName("InactivityWatchdogCallback")
class InactivityWatchdogCallbackTest {

    /** Mutable test clock. */
    static final class Clock implements LongSupplier {
        long now;
        Clock(long start) { this.now = start; }
        void advance(long ms) { this.now += ms; }
        @Override public long getAsLong() { return now; }
    }

    /** Recording delegate whose shouldStop() can be toggled to simulate a user/system cancel. */
    static final class RecordingCallback implements StreamingCallback {
        final List<String> chunks = new ArrayList<>();
        final List<String> thinking = new ArrayList<>();
        final List<ToolCall> toolCalls = new ArrayList<>();
        final List<ToolResult> toolResults = new ArrayList<>();
        CompletionResponse completed;
        String error;
        boolean stop;
        int keepAlives;
        long completionTokenBudget = 4242L;

        @Override public void onChunk(String content) { chunks.add(content); }
        @Override public void onThinking(String t) { thinking.add(t); }
        @Override public void onToolCall(ToolCall tc) { toolCalls.add(tc); }
        @Override public void onToolResult(ToolResult r) { toolResults.add(r); }
        @Override public void onComplete(CompletionResponse r) { completed = r; }
        @Override public void onError(String e) { error = e; }
        @Override public void onKeepAlive() { keepAlives++; }
        @Override public boolean shouldStop() { return stop; }
        @Override public long getCompletionTokenBudget() { return completionTokenBudget; }
    }

    private static final long WINDOW = 5 * 60 * 1000L; // 5 min

    @Test
    @DisplayName("a working agent (activity just under the window) is never tripped")
    void workingAgentNeverTrips() {
        Clock clock = new Clock(1_000);
        RecordingCallback delegate = new RecordingCallback();
        InactivityWatchdogCallback wd = new InactivityWatchdogCallback(delegate, WINDOW, clock);

        // Emit a token every (WINDOW - 1) ms, ten times, for a total runtime far exceeding WINDOW.
        for (int i = 0; i < 10; i++) {
            clock.advance(WINDOW - 1);
            assertThat(wd.shouldStop()).as("must not trip while activity keeps arriving").isFalse();
            wd.onChunk("token-" + i);
        }
        assertThat(wd.isIdleTripped()).isFalse();
        assertThat(delegate.chunks).hasSize(10);
    }

    @Test
    @DisplayName("a silent agent trips once the window is exceeded, and latches idleTripped")
    void silentAgentTripsAfterWindow() {
        Clock clock = new Clock(0);
        RecordingCallback delegate = new RecordingCallback();
        InactivityWatchdogCallback wd = new InactivityWatchdogCallback(delegate, WINDOW, clock);

        clock.advance(WINDOW);                 // exactly at the window: not yet (strictly greater)
        assertThat(wd.shouldStop()).isFalse();
        assertThat(wd.isIdleTripped()).isFalse();

        clock.advance(1);                      // one ms past the window
        assertThat(wd.shouldStop()).isTrue();
        assertThat(wd.isIdleTripped()).isTrue();

        // Latched: stays tripped on subsequent reads even though no time advances.
        assertThat(wd.isIdleTripped()).isTrue();
    }

    @Test
    @DisplayName("every activity type resets the idle clock (chunk, thinking, tool call, tool result)")
    void everyActivityTypeResetsTheClock() {
        Clock clock = new Clock(0);
        RecordingCallback delegate = new RecordingCallback();
        InactivityWatchdogCallback wd = new InactivityWatchdogCallback(delegate, WINDOW, clock);

        clock.advance(WINDOW - 10); wd.onChunk("c");
        clock.advance(WINDOW - 10); wd.onThinking("th");
        clock.advance(WINDOW - 10); wd.onToolCall(new ToolCall("id", "tool", java.util.Map.of(), 0));
        clock.advance(WINDOW - 10); wd.onToolResult(ToolResult.builder().content("ok").success(true).build());

        // Each event kept the run alive; still within the window after the last reset.
        clock.advance(WINDOW - 1);
        assertThat(wd.shouldStop()).isFalse();
        assertThat(wd.isIdleTripped()).isFalse();
    }

    @Test
    @DisplayName("onKeepAlive resets the idle clock so a long multi-tool batch is never killed")
    void keepAliveResetsTheClock() {
        Clock clock = new Clock(0);
        RecordingCallback delegate = new RecordingCallback();
        InactivityWatchdogCallback wd = new InactivityWatchdogCallback(delegate, WINDOW, clock);

        // Simulate a batch of 5 sequential tools, each running just under the window. The agent
        // streams nothing during tool execution, but each completed tool pings onKeepAlive.
        for (int i = 0; i < 5; i++) {
            clock.advance(WINDOW - 1);
            assertThat(wd.shouldStop()).as("must not trip while tools keep finishing").isFalse();
            wd.onKeepAlive();
        }
        assertThat(wd.isIdleTripped()).isFalse();
        assertThat(delegate.keepAlives).as("onKeepAlive is forwarded to the delegate").isEqualTo(5);
    }

    @Test
    @DisplayName("a real user/system cancel wins and is NOT relabeled as inactivity")
    void userCancelWinsAndIsNotInactivity() {
        Clock clock = new Clock(0);
        RecordingCallback delegate = new RecordingCallback();
        InactivityWatchdogCallback wd = new InactivityWatchdogCallback(delegate, WINDOW, clock);

        delegate.stop = true;                  // user cancel, while still inside the idle window
        assertThat(wd.shouldStop()).isTrue();
        assertThat(wd.isIdleTripped())
            .as("a user cancel must not be misclassified as an inactivity stop")
            .isFalse();
    }

    @Test
    @DisplayName("a disabled watchdog (window <= 0) never trips, however long the silence")
    void disabledWatchdogNeverTrips() {
        Clock clock = new Clock(0);
        RecordingCallback delegate = new RecordingCallback();
        InactivityWatchdogCallback wd = new InactivityWatchdogCallback(delegate, 0, clock);

        assertThat(wd.isEnabled()).isFalse();
        clock.advance(Long.MAX_VALUE / 2);
        assertThat(wd.shouldStop()).isFalse();
        assertThat(wd.isIdleTripped()).isFalse();
    }

    @Test
    @DisplayName("activity and lifecycle calls are forwarded to the delegate; budget is delegated")
    void forwardsToDelegate() {
        Clock clock = new Clock(0);
        RecordingCallback delegate = new RecordingCallback();
        InactivityWatchdogCallback wd = new InactivityWatchdogCallback(delegate, WINDOW, clock);

        wd.onChunk("hello");
        wd.onThinking("hmm");
        wd.onToolCall(new ToolCall("1", "search", java.util.Map.of(), 0));
        wd.onToolResult(ToolResult.builder().content("r").success(true).build());
        CompletionResponse resp = CompletionResponse.text("done");
        wd.onComplete(resp);
        wd.onError("boom");

        assertThat(delegate.chunks).containsExactly("hello");
        assertThat(delegate.thinking).containsExactly("hmm");
        assertThat(delegate.toolCalls).hasSize(1);
        assertThat(delegate.toolResults).hasSize(1);
        assertThat(delegate.completed).isSameAs(resp);
        assertThat(delegate.error).isEqualTo("boom");
        assertThat(wd.getCompletionTokenBudget()).isEqualTo(4242L);
        assertThat(wd.getInactivityTimeoutMs()).isEqualTo(WINDOW);
        assertThat(wd.isEnabled()).isTrue();
    }
}
