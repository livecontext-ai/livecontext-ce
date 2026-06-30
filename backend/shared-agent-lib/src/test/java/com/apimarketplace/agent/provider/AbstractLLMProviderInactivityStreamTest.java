package com.apimarketplace.agent.provider;

import com.apimarketplace.agent.domain.CompletionRequest;
import com.apimarketplace.agent.domain.CompletionResponse;
import com.apimarketplace.agent.domain.ToolCall;
import com.apimarketplace.agent.streaming.StreamingCallback;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Behavioral coverage for the inactivity socket-timeout branch added to
 * {@link AbstractLLMProvider#processStreamingResponse}: when the streaming socket read times out
 * (no provider bytes for the inactivity window), the read loop must re-check {@code shouldStop()}
 * instead of erroring out - so a fully-silent stream is broken at the window rather than at the much
 * larger socket read timeout. With NO watchdog window the timeout must propagate exactly as before.
 */
@DisplayName("AbstractLLMProvider - inactivity socket-timeout handling in the streaming read loop")
class AbstractLLMProviderInactivityStreamTest {

    static class TestProvider extends AbstractLLMProvider {
        @Override protected String getApiKey() { return "k"; }
        @Override protected String getApiUrl() { return "https://api.test.com/v1/completions"; }
        @Override protected Map<String, Object> buildRequestBody(CompletionRequest r) { return Map.of(); }
        @Override protected CompletionResponse parseResponse(Map<String, Object> r) { return CompletionResponse.text("ok"); }
        @Override protected HttpHeaders buildHeaders() { return new HttpHeaders(); }
        @Override protected String processStreamingLine(String line) { return null; }
        @Override public String getProviderName() { return "test"; }
        @Override public String getDefaultModel() { return "test-model"; }
        @Override public List<String> getSupportedModels() { return List.of("test-model"); }

        CompletionResponse run(BufferedReader reader, StreamingCallback cb) throws Exception {
            return processStreamingResponse(reader, cb);
        }
    }

    /** A reader that throws SocketTimeoutException on its first N reads, then signals EOF. */
    static class TimeoutThenEofReader extends Reader {
        private final int throwTimes;
        private int reads = 0;
        TimeoutThenEofReader(int throwTimes) { this.throwTimes = throwTimes; }
        @Override public int read(char[] cbuf, int off, int len) throws IOException {
            if (reads++ < throwTimes) {
                throw new SocketTimeoutException("Read timed out");
            }
            return -1; // EOF
        }
        @Override public void close() { }
    }

    static class InactivityCallback implements StreamingCallback {
        private final long window;
        private final boolean stop;
        InactivityCallback(long window, boolean stop) { this.window = window; this.stop = stop; }
        @Override public void onChunk(String c) { }
        @Override public void onToolCall(ToolCall t) { }
        @Override public void onComplete(CompletionResponse r) { }
        @Override public void onError(String e) { }
        @Override public boolean shouldStop() { return stop; }
        @Override public long getInactivityTimeoutMs() { return window; }
    }

    @Test
    @DisplayName("window>0 + watchdog tripped: a socket timeout breaks the stream cleanly (no exception)")
    void timeoutWithTrippedWatchdogBreaks() {
        TestProvider provider = new TestProvider();
        BufferedReader reader = new BufferedReader(new TimeoutThenEofReader(1));

        assertThatCode(() -> provider.run(reader, new InactivityCallback(5_000L, true)))
            .as("a socket timeout while the watchdog says stop must end the stream, not throw")
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("window>0 + not yet tripped: a socket timeout is retried (keep waiting), then EOF ends cleanly")
    void timeoutWithoutTripContinues() {
        TestProvider provider = new TestProvider();
        BufferedReader reader = new BufferedReader(new TimeoutThenEofReader(1));

        // shouldStop=false -> the catch must `continue` (re-read); the next read hits EOF and the
        // loop terminates without surfacing the SocketTimeoutException as an error.
        assertThatCode(() -> provider.run(reader, new InactivityCallback(5_000L, false)))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("no watchdog window: a socket timeout propagates exactly as before")
    void timeoutWithoutWindowPropagates() {
        TestProvider provider = new TestProvider();
        BufferedReader reader = new BufferedReader(new TimeoutThenEofReader(1));

        assertThatThrownBy(() -> provider.run(reader, new InactivityCallback(-1L, false)))
            .as("without an inactivity window the socket timeout must NOT be swallowed")
            .isInstanceOf(SocketTimeoutException.class);
    }
}
