package com.apimarketplace.agent.streaming;

import com.apimarketplace.agent.domain.CompletionResponse;
import com.apimarketplace.agent.domain.ToolCall;
import com.apimarketplace.agent.domain.ToolResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link TeeStreamingCallback} fans every event out to both delegates so a
 * workflow agent node with a user-facing conversation can feed the
 * conversation panel AND the workflow run view at once (the two formats used
 * to be mutually exclusive in AgentRemoteExecutionService).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TeeStreamingCallback")
class TeeStreamingCallbackTest {

    @Mock private StreamingCallback primary;
    @Mock private StreamingCallback secondary;

    private TeeStreamingCallback tee() {
        return new TeeStreamingCallback(primary, secondary);
    }

    @Test
    @DisplayName("every streaming event reaches both delegates")
    void fansOutAllEvents() {
        TeeStreamingCallback tee = tee();
        ToolCall call = mock(ToolCall.class);
        ToolResult result = mock(ToolResult.class);
        CompletionResponse response = mock(CompletionResponse.class);

        tee.onChunk("chunk");
        tee.onThinking("thinking");
        tee.onToolCall(call);
        tee.onToolResult(result);
        tee.onComplete(response);
        tee.onError("boom");
        tee.onKeepAlive();

        for (StreamingCallback delegate : new StreamingCallback[]{primary, secondary}) {
            verify(delegate).onChunk("chunk");
            verify(delegate).onThinking("thinking");
            verify(delegate).onToolCall(call);
            verify(delegate).onToolResult(result);
            verify(delegate).onComplete(response);
            verify(delegate).onError("boom");
            verify(delegate).onKeepAlive();
        }
    }

    @Test
    @DisplayName("onError with exception reaches both delegates")
    void fansOutErrorWithException() {
        TeeStreamingCallback tee = tee();
        RuntimeException cause = new RuntimeException("cause");

        tee.onError("boom", cause);

        verify(primary).onError("boom", cause);
        verify(secondary).onError("boom", cause);
    }

    @Test
    @DisplayName("shouldStop is an OR: either side's cancel signal stops the loop")
    void shouldStopIsOr() {
        when(primary.shouldStop()).thenReturn(false);
        when(secondary.shouldStop()).thenReturn(true);
        assertThat(tee().shouldStop()).isTrue();

        when(primary.shouldStop()).thenReturn(true);
        assertThat(tee().shouldStop()).isTrue();

        when(primary.shouldStop()).thenReturn(false);
        when(secondary.shouldStop()).thenReturn(false);
        assertThat(tee().shouldStop()).isFalse();
    }

    @Test
    @DisplayName("scalar hints take the primary's value and fall back to the secondary's")
    void scalarHintsPreferPrimary() {
        when(primary.getCompletionTokenBudget()).thenReturn(100L);
        assertThat(tee().getCompletionTokenBudget()).isEqualTo(100L);

        when(primary.getCompletionTokenBudget()).thenReturn(-1L);
        when(secondary.getCompletionTokenBudget()).thenReturn(50L);
        assertThat(tee().getCompletionTokenBudget()).isEqualTo(50L);

        when(primary.getInactivityTimeoutMs()).thenReturn(-1L);
        when(secondary.getInactivityTimeoutMs()).thenReturn(30000L);
        assertThat(tee().getInactivityTimeoutMs()).isEqualTo(30000L);

        when(primary.getInactivityTimeoutMs()).thenReturn(15000L);
        assertThat(tee().getInactivityTimeoutMs()).isEqualTo(15000L);
    }

    @Test
    @DisplayName("delegation order is primary first (conversation events keep their relative order)")
    void primaryFirst() {
        StringBuilder order = new StringBuilder();
        StreamingCallback a = new RecordingCallback(order, "A");
        StreamingCallback b = new RecordingCallback(order, "B");

        new TeeStreamingCallback(a, b).onChunk("x");

        assertThat(order.toString()).isEqualTo("AB");
    }

    private static final class RecordingCallback implements StreamingCallback {
        private final StringBuilder order;
        private final String tag;

        private RecordingCallback(StringBuilder order, String tag) {
            this.order = order;
            this.tag = tag;
        }

        @Override public void onChunk(String content) { order.append(tag); }
        @Override public void onToolCall(ToolCall toolCall) { }
        @Override public void onComplete(CompletionResponse response) { }
        @Override public void onError(String error) { }
    }
}
