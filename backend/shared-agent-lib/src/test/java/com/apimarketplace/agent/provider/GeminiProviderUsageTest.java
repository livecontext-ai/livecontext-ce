package com.apimarketplace.agent.provider;

import com.apimarketplace.agent.domain.CompletionResponse;
import com.apimarketplace.agent.domain.ToolCall;
import com.apimarketplace.agent.domain.UsageInfo;
import com.apimarketplace.agent.streaming.StreamingCallback;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stage 0.3 - validate Gemini usage parsing emits the two new fields
 * ({@code thoughtsTokenCount}, {@code cachedContentTokenCount}) from both the
 * non-streaming and streaming paths. Telemetry depends on these being present.
 */
@DisplayName("GeminiProvider - usage parsing")
class GeminiProviderUsageTest {

    private final GeminiProvider provider = new GeminiProvider();

    @Test
    @DisplayName("streaming path emits thoughtsTokenCount and cachedContentTokenCount when present")
    void streamingEmitsGeminiFields() {
        String sseLine = "data: {\"usageMetadata\":{" +
            "\"promptTokenCount\":105771," +
            "\"candidatesTokenCount\":511," +
            "\"totalTokenCount\":106282," +
            "\"thoughtsTokenCount\":185," +
            "\"cachedContentTokenCount\":4096}}";

        UsageInfo usage = provider.extractStreamingUsage(sseLine);

        assertThat(usage).isNotNull();
        assertThat(usage.promptTokens()).isEqualTo(105771);
        assertThat(usage.completionTokens()).isEqualTo(511);
        assertThat(usage.totalTokens()).isEqualTo(106282);
        assertThat(usage.thoughtsTokenCount()).isEqualTo(185);
        assertThat(usage.cachedContentTokenCount()).isEqualTo(4096);
    }

    @Test
    @DisplayName("streaming path mirrors thoughts/cachedContent into the generic reasoning/cached billing counters")
    void streamingMirrorsGeminiCountersIntoGenericBillingFields() {
        // Regression for the 2026-06-11 cache-aware billing fix: the observability DTOs
        // only carry the GENERIC counters (reasoningTokens / cachedTokens) to billing,
        // where the google family bills thoughts as additive output and cached content
        // at the cached discount. Without the mirror, Gemini thinking stayed unbilled.
        String sseLine = "data: {\"usageMetadata\":{" +
            "\"promptTokenCount\":105771," +
            "\"candidatesTokenCount\":511," +
            "\"totalTokenCount\":106282," +
            "\"thoughtsTokenCount\":185," +
            "\"cachedContentTokenCount\":4096}}";

        UsageInfo usage = provider.extractStreamingUsage(sseLine);

        assertThat(usage).isNotNull();
        assertThat(usage.reasoningTokens()).isEqualTo(185);
        assertThat(usage.cachedTokens()).isEqualTo(4096);
    }

    @Test
    @DisplayName("streaming path leaves the generic mirrors null when the Gemini fields are absent")
    void streamingMirrorsNullWhenAbsent() {
        String sseLine = "data: {\"usageMetadata\":{" +
            "\"promptTokenCount\":100," +
            "\"candidatesTokenCount\":50," +
            "\"totalTokenCount\":150}}";

        UsageInfo usage = provider.extractStreamingUsage(sseLine);

        assertThat(usage).isNotNull();
        assertThat(usage.reasoningTokens()).isNull();
        assertThat(usage.cachedTokens()).isNull();
    }

    @Test
    @DisplayName("streaming path leaves the two Gemini fields null when absent - no false zero")
    void streamingNullWhenAbsent() {
        String sseLine = "data: {\"usageMetadata\":{" +
            "\"promptTokenCount\":100," +
            "\"candidatesTokenCount\":50," +
            "\"totalTokenCount\":150}}";

        UsageInfo usage = provider.extractStreamingUsage(sseLine);

        assertThat(usage).isNotNull();
        assertThat(usage.promptTokens()).isEqualTo(100);
        assertThat(usage.thoughtsTokenCount()).isNull();
        assertThat(usage.cachedContentTokenCount()).isNull();
    }

    @Test
    @DisplayName("streaming path returns null for non-usage SSE lines (no spurious UsageInfo)")
    void streamingReturnsNullForNonUsageLine() {
        assertThat(provider.extractStreamingUsage("data: {\"candidates\":[]}")).isNull();
        assertThat(provider.extractStreamingUsage("")).isNull();
        assertThat(provider.extractStreamingUsage("data: [DONE]")).isNull();
    }

    @Test
    @DisplayName("cumulative usage across chunks is replaced, not summed - final equals last chunk's total")
    void streamingUsageDoesNotAccumulateAcrossChunks() throws Exception {
        // Gemini emits cumulative counts on each chunk that carries usageMetadata.
        // The stream below reports 100, 150, 200 prompt tokens as running totals -
        // summing would yield 450 and triple-count the real token spend.
        String sse = String.join("\n",
            "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Hel\"}]}}]," +
                "\"usageMetadata\":{\"promptTokenCount\":100,\"candidatesTokenCount\":5,\"totalTokenCount\":105}}",
            "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"lo\"}]}}]," +
                "\"usageMetadata\":{\"promptTokenCount\":100,\"candidatesTokenCount\":10,\"totalTokenCount\":110}}",
            "data: {\"candidates\":[{\"finishReason\":\"STOP\"}]," +
                "\"usageMetadata\":{\"promptTokenCount\":100,\"candidatesTokenCount\":15,\"totalTokenCount\":115}}",
            ""
        );

        AtomicReference<CompletionResponse> captured = new AtomicReference<>();
        StreamingCallback callback = new StreamingCallback() {
            @Override public void onChunk(String content) {}
            @Override public void onToolCall(ToolCall toolCall) {}
            @Override public void onComplete(CompletionResponse response) { captured.set(response); }
            @Override public void onError(String error) {}
        };

        provider.processGeminiStreamingResponse(
            new BufferedReader(new StringReader(sse)), callback, "{}", 200, java.util.Map.of());

        CompletionResponse response = captured.get();
        assertThat(response).isNotNull();
        UsageInfo usage = response.usage();
        assertThat(usage).as("usage must reflect last chunk's cumulative totals").isNotNull();
        assertThat(usage.promptTokens()).isEqualTo(100);
        assertThat(usage.completionTokens()).isEqualTo(15);
        assertThat(usage.totalTokens()).isEqualTo(115);
    }

    @Test
    @DisplayName("usage only on final chunk - captured, not lost (null guard holds)")
    void streamingUsageOnlyOnFinalChunk() throws Exception {
        // Real Gemini streams often drop usageMetadata on mid-stream chunks and emit it
        // only on the finishReason line. The null-guard must not overwrite a captured
        // value with null on the earlier chunks.
        String sse = String.join("\n",
            "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Hel\"}]}}]}",
            "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"lo\"}]}}]}",
            "data: {\"candidates\":[{\"finishReason\":\"STOP\"}]," +
                "\"usageMetadata\":{\"promptTokenCount\":42,\"candidatesTokenCount\":7,\"totalTokenCount\":49}}",
            ""
        );

        AtomicReference<CompletionResponse> captured = new AtomicReference<>();
        StreamingCallback callback = collectingCallback(captured);

        provider.processGeminiStreamingResponse(
            new BufferedReader(new StringReader(sse)), callback, "{}", 200, java.util.Map.of());

        UsageInfo usage = captured.get().usage();
        assertThat(usage).as("usage must be captured from final chunk").isNotNull();
        assertThat(usage.promptTokens()).isEqualTo(42);
        assertThat(usage.completionTokens()).isEqualTo(7);
        assertThat(usage.totalTokens()).isEqualTo(49);
    }

    @Test
    @DisplayName("usage only on first chunk - later null chunks do not clobber it")
    void streamingUsageOnlyOnFirstChunk() throws Exception {
        // Symmetric case: usage arrives early, mid-stream chunks carry no usage.
        // The replace-when-non-null guard must keep the initial capture intact.
        String sse = String.join("\n",
            "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Hi\"}]}}]," +
                "\"usageMetadata\":{\"promptTokenCount\":17,\"candidatesTokenCount\":2,\"totalTokenCount\":19}}",
            "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"!\"}]}}]}",
            "data: {\"candidates\":[{\"finishReason\":\"STOP\"}]}",
            ""
        );

        AtomicReference<CompletionResponse> captured = new AtomicReference<>();
        StreamingCallback callback = collectingCallback(captured);

        provider.processGeminiStreamingResponse(
            new BufferedReader(new StringReader(sse)), callback, "{}", 200, java.util.Map.of());

        UsageInfo usage = captured.get().usage();
        assertThat(usage).as("usage captured from first chunk must survive later null lines").isNotNull();
        assertThat(usage.promptTokens()).isEqualTo(17);
        assertThat(usage.completionTokens()).isEqualTo(2);
        assertThat(usage.totalTokens()).isEqualTo(19);
    }

    private static StreamingCallback collectingCallback(AtomicReference<CompletionResponse> captured) {
        return new StreamingCallback() {
            @Override public void onChunk(String content) {}
            @Override public void onToolCall(ToolCall toolCall) {}
            @Override public void onComplete(CompletionResponse response) { captured.set(response); }
            @Override public void onError(String error) {}
        };
    }
}
