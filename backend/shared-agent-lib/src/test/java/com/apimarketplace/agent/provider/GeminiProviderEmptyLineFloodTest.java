package com.apimarketplace.agent.provider;

import com.apimarketplace.agent.domain.CompletionResponse;
import com.apimarketplace.agent.domain.ToolCall;
import com.apimarketplace.agent.streaming.StreamingCallback;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Backpressure against Gemini's empty-SSE-line flood (prod bug 2026-06-10).
 *
 * <p>Gemini sometimes streams a flood of blank/empty SSE lines (its "EMPTY RESPONSE /
 * thinking-only" misbehavior). Pre-fix, {@code processGeminiStreamingResponse} iterated
 * once per blank line and re-polled {@code callback.shouldStop()} each time, so a stopped
 * (or just misbehaving) stream spun thousands of times.
 *
 * <p>Post-fix: a run of {@code MAX_CONSECUTIVE_EMPTY_LINES} (100) consecutive blank lines
 * aborts the loop. A non-empty line resets the counter, so realistic SSE - which only ever
 * has a handful of blank event separators between data lines - is unaffected.
 */
@DisplayName("GeminiProvider - processGeminiStreamingResponse aborts on an empty-line flood")
class GeminiProviderEmptyLineFloodTest {

    private final GeminiProvider provider = new GeminiProvider();

    private static String geminiSseLine(String text) {
        // Minimal valid Gemini SSE chunk shape so processStreamingLine returns content
        return "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"" + text + "\"}]}}]}";
    }

    @Test
    @DisplayName("200 consecutive blank lines - loop aborts at the threshold, NOT after all 200")
    void floodOfBlankLinesAbortsAtThreshold() throws Exception {
        // 200 blank lines and nothing else. Pre-fix this would read/poll all 200; post-fix
        // it must break at the 100-line threshold.
        String sse = "\n".repeat(200);
        BufferedReader reader = new BufferedReader(new StringReader(sse));

        // shouldStop() doubles as a per-iteration probe: it is invoked once at the top of
        // every loop iteration. If the flood guard works, it is polled ~threshold times,
        // never close to 200.
        AtomicInteger shouldStopPolls = new AtomicInteger(0);
        AtomicInteger chunksSeen = new AtomicInteger(0);
        AtomicReference<CompletionResponse> completed = new AtomicReference<>();

        StreamingCallback cb = new StreamingCallback() {
            @Override public void onChunk(String c) { chunksSeen.incrementAndGet(); }
            @Override public void onToolCall(ToolCall t) {}
            @Override public void onComplete(CompletionResponse r) { completed.set(r); }
            @Override public void onError(String e) {}
            @Override public boolean shouldStop() {
                shouldStopPolls.incrementAndGet();
                return false;
            }
        };

        provider.processGeminiStreamingResponse(reader, cb, "{}", 200, new HashMap<>());

        // The loop aborts roughly at the 100-line threshold - well short of all 200 blanks.
        assertThat(shouldStopPolls.get())
            .as("flood guard must break near the 100-line threshold, not iterate all 200 blank lines")
            .isLessThan(150);
        // No content was ever delivered, and the method finalizes/returns cleanly (no hang).
        assertThat(chunksSeen.get()).isZero();
        assertThat(completed.get())
            .as("onComplete still fires after a flood-abort (falls into the non-explicit-end path)")
            .isNotNull();
        assertThat(completed.get().content())
            .as("an all-blank flood yields empty content")
            .isEmpty();
    }

    @Test
    @DisplayName("Realistic SSE with a few blank separators - parsed normally, no premature abort")
    void normalStreamWithBlankSeparatorsIsUnaffected() throws Exception {
        // Interleave data lines with a couple of blank separators each (well under the
        // 100-line threshold). consecutiveEmpty resets on every data line, so the flood
        // guard never trips.
        String sse = String.join("\n",
            geminiSseLine("hello"),
            "", "",
            geminiSseLine(" brave"),
            "", "", "",
            geminiSseLine(" world"),
            "", "");
        BufferedReader reader = new BufferedReader(new StringReader(sse));

        AtomicInteger chunksSeen = new AtomicInteger(0);
        AtomicReference<CompletionResponse> completed = new AtomicReference<>();

        StreamingCallback cb = new StreamingCallback() {
            @Override public void onChunk(String c) { chunksSeen.incrementAndGet(); }
            @Override public void onToolCall(ToolCall t) {}
            @Override public void onComplete(CompletionResponse r) { completed.set(r); }
            @Override public void onError(String e) {}
        };

        provider.processGeminiStreamingResponse(reader, cb, "{}", 200, new HashMap<>());

        // All three content lines parsed - no premature abort from the blank separators.
        assertThat(chunksSeen.get())
            .as("all content chunks must be delivered despite interleaved blank separators")
            .isEqualTo(3);
        assertThat(completed.get()).isNotNull();
        assertThat(completed.get().content()).isEqualTo("hello brave world");
    }
}
