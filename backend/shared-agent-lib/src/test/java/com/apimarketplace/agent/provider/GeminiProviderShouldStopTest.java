package com.apimarketplace.agent.provider;

import com.apimarketplace.agent.domain.CompletionResponse;
import com.apimarketplace.agent.domain.ToolCall;
import com.apimarketplace.agent.streaming.StreamingCallback;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression for the Gemini side of the same shouldStop bug observed on
 * DeepSeek streaming. Pre-fix, {@code GeminiProvider.processGeminiStreamingResponse}
 * had its own per-line read loop that NEVER called {@code callback.shouldStop()},
 * so a STOP arriving mid-stream was silently ignored and the user kept paying
 * tokens until the model finished naturally.
 *
 * <p>Post-fix: the loop checks shouldStop() at the top of each iteration and
 * breaks out - same pattern as {@link AbstractLLMProvider#processStreamingResponse}.
 */
@DisplayName("GeminiProvider - processGeminiStreamingResponse honors callback.shouldStop()")
class GeminiProviderShouldStopTest {

    private final GeminiProvider provider = new GeminiProvider();

    private static String geminiSseLine(String text) {
        // Minimal valid Gemini SSE chunk shape so processStreamingLine returns content
        return "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"" + text + "\"}]}}]}";
    }

    @Test
    @DisplayName("Stop flipped after first chunk - second chunk is NOT delivered to callback")
    void stopBetweenChunksBreaksLoop() throws Exception {
        // Three SSE chunks: first delivered, then stop flips, second/third skipped.
        String sse = String.join("\n",
            geminiSseLine("hello"),
            geminiSseLine(" world"),
            geminiSseLine(" should-not-arrive"));

        BufferedReader reader = new BufferedReader(new StringReader(sse));

        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicInteger chunksSeen = new AtomicInteger(0);

        StreamingCallback cb = new StreamingCallback() {
            @Override public void onChunk(String c) {
                chunksSeen.incrementAndGet();
                // After the first delivered chunk, ask to stop
                stop.set(true);
            }
            @Override public void onToolCall(ToolCall t) {}
            @Override public void onComplete(CompletionResponse r) {}
            @Override public void onError(String e) {}
            @Override public boolean shouldStop() { return stop.get(); }
        };

        provider.processGeminiStreamingResponse(reader, cb, "{}", 200, new HashMap<>());

        assertThat(chunksSeen.get())
            .as("only the 1st chunk should have been delivered before the stop flip was honored")
            .isEqualTo(1);
    }

    @Test
    @DisplayName("Stop already true at entry - zero chunks delivered, no NPE, returns cleanly")
    void preFlightStopDeliversNothing() throws Exception {
        BufferedReader reader = new BufferedReader(new StringReader(
            geminiSseLine("never-shown") + "\n" + geminiSseLine("nor-this")));

        AtomicInteger chunksSeen = new AtomicInteger(0);
        StreamingCallback cb = new StreamingCallback() {
            @Override public void onChunk(String c) { chunksSeen.incrementAndGet(); }
            @Override public void onToolCall(ToolCall t) {}
            @Override public void onComplete(CompletionResponse r) {}
            @Override public void onError(String e) {}
            @Override public boolean shouldStop() { return true; }
        };

        provider.processGeminiStreamingResponse(reader, cb, "{}", 200, new HashMap<>());

        assertThat(chunksSeen.get()).isZero();
    }

    @Test
    @DisplayName("Default shouldStop()=false - stream runs through normally (no regression for non-cancelled callers)")
    void normalStreamRunsThrough() throws Exception {
        String sse = String.join("\n",
            geminiSseLine("hello"),
            geminiSseLine(" world"));
        BufferedReader reader = new BufferedReader(new StringReader(sse));

        AtomicInteger chunksSeen = new AtomicInteger(0);
        StreamingCallback cb = new StreamingCallback() {
            @Override public void onChunk(String c) { chunksSeen.incrementAndGet(); }
            @Override public void onToolCall(ToolCall t) {}
            @Override public void onComplete(CompletionResponse r) {}
            @Override public void onError(String e) {}
        };

        provider.processGeminiStreamingResponse(reader, cb, "{}", 200, new HashMap<>());

        assertThat(chunksSeen.get())
            .as("uncancelled callers must see all chunks - no regression")
            .isEqualTo(2);
    }
}
