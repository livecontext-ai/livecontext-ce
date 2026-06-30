package com.apimarketplace.agent.tokenizer;

import com.apimarketplace.agent.domain.CompletionRequest;
import com.apimarketplace.agent.domain.Message;
import com.apimarketplace.agent.domain.ToolDefinition;
import com.apimarketplace.agent.domain.ToolParameter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stage 1a.4 - pin the behavior of the cl100k_base estimator. These tests lock
 * in the observable contract (counts are > 0 for non-empty, monotonic as content
 * grows, bounded for pathological inputs) without asserting exact token numbers,
 * which can shift by ±1 across Jtokkit versions.
 */
@DisplayName("JtokkitTokenEstimator - cl100k_base (Stage 1a.4)")
class JtokkitTokenEstimatorTest {

    private JtokkitTokenEstimator estimator;

    @BeforeEach
    void setUp() {
        estimator = new JtokkitTokenEstimator();
        estimator.init();
    }

    @Test
    @DisplayName("name() returns 'jtokkit' for telemetry dispatch")
    void nameIsJtokkit() {
        assertThat(estimator.name()).isEqualTo("jtokkit");
    }

    @Test
    @DisplayName("countTokens on null or empty returns 0 - no NPE, no 1-token-for-BOS")
    void nullAndEmptyCountZero() {
        assertThat(estimator.countTokens(null)).isZero();
        assertThat(estimator.countTokens("")).isZero();
    }

    @Test
    @DisplayName("short ASCII prose tokenizes to ~1 token per 4 chars (English baseline)")
    void asciiProseIsBroadlyChars4() {
        // "Hello, world. This is a simple test." = 36 chars
        // cl100k: 10 tokens (roughly chars/3.6). Allow ±3 for version drift.
        int tokens = estimator.countTokens("Hello, world. This is a simple test.");
        assertThat(tokens).isBetween(7, 13);
    }

    @Test
    @DisplayName("emoji-heavy input spends more tokens than ASCII of same char length")
    void emojiCostsMoreThanAscii() {
        String ascii = "abcdefghijklmnop"; // 16 chars
        String emoji = "🔥🎉✨🚀🎯🔒🎨🎭"; // 8 emoji ≈ 16 code units
        int asciiTokens = estimator.countTokens(ascii);
        int emojiTokens = estimator.countTokens(emoji);
        // Each emoji is its own multi-byte token in cl100k, whereas ASCII words
        // often merge. Emoji MUST be ≥ ascii to reflect real cost.
        assertThat(emojiTokens).isGreaterThanOrEqualTo(asciiTokens);
    }

    @Test
    @DisplayName("estimate: promptTokens + maxTokens, with default completion reserve=500")
    void defaultCompletionReserveIsFiveHundred() {
        CompletionRequest empty = CompletionRequest.builder()
            .model("gpt-4").build();
        assertThat(estimator.estimate(empty)).isEqualTo(500);

        CompletionRequest withMax = CompletionRequest.builder()
            .model("gpt-4").maxTokens(1000).build();
        assertThat(estimator.estimate(withMax)).isEqualTo(1000);
    }

    @Test
    @DisplayName("estimate sums system + user + history + tools + completion reserve")
    void allInputSectionsAreCounted() {
        CompletionRequest req = CompletionRequest.builder()
            .model("gpt-4")
            .systemPrompt("You are a helpful assistant.")
            .userPrompt("Write a haiku about autumn leaves.")
            .conversationHistory(List.of(
                Message.user("Previous turn user message."),
                Message.assistant("Previous turn assistant reply.")
            ))
            .tools(List.of(ToolDefinition.builder()
                .name("search_web")
                .description("Search the web for a query.")
                .parameters(List.of(ToolParameter.builder()
                    .name("query").type("string").description("search string").build()))
                .build()))
            .maxTokens(200)
            .build();

        int total = estimator.estimate(req);
        // Every segment contributes; completion reserve is 200. Total must be
        // strictly greater than 200 (some prompt tokens were counted) and less
        // than 1000 (sanity upper bound for this small payload).
        assertThat(total).isGreaterThan(200).isLessThan(1000);
    }

    @Test
    @DisplayName("growing prompt monotonically grows estimate - regression guard")
    void monotonicInPromptSize() {
        CompletionRequest small = CompletionRequest.builder()
            .model("gpt-4").userPrompt("hi").maxTokens(100).build();
        CompletionRequest medium = CompletionRequest.builder()
            .model("gpt-4").userPrompt("hi ".repeat(50)).maxTokens(100).build();
        CompletionRequest large = CompletionRequest.builder()
            .model("gpt-4").userPrompt("hi ".repeat(500)).maxTokens(100).build();

        assertThat(estimator.estimate(small)).isLessThan(estimator.estimate(medium));
        assertThat(estimator.estimate(medium)).isLessThan(estimator.estimate(large));
    }

    @Test
    @DisplayName("countToolTokens grows with parameter count")
    void toolTokensScaleWithParams() {
        ToolDefinition zeroParam = ToolDefinition.builder()
            .name("t").description("d").parameters(List.of()).build();
        ToolDefinition fiveParam = ToolDefinition.builder()
            .name("t").description("d").parameters(List.of(
                ToolParameter.builder().name("a").type("string").description("x").build(),
                ToolParameter.builder().name("b").type("string").description("x").build(),
                ToolParameter.builder().name("c").type("string").description("x").build(),
                ToolParameter.builder().name("d").type("string").description("x").build(),
                ToolParameter.builder().name("e").type("string").description("x").build()
            )).build();

        assertThat(estimator.countToolTokens(fiveParam))
            .isGreaterThan(estimator.countToolTokens(zeroParam));
    }

    @Test
    @DisplayName("tool with null name/description/parameters is safe (no NPE)")
    void sparseToolDoesNotNpe() {
        ToolDefinition sparse = ToolDefinition.builder().name(null).description(null).parameters(null).build();
        int tokens = estimator.countToolTokens(sparse);
        // Only the envelope is counted.
        assertThat(tokens).isGreaterThan(0).isLessThan(50);
    }

    @Test
    @DisplayName("null content in conversation history is skipped, not NPE'd")
    void nullHistoryContentIsSafe() {
        CompletionRequest req = CompletionRequest.builder()
            .model("gpt-4")
            .conversationHistory(List.of(
                Message.builder().role(Message.Role.USER).content(null).build(),
                Message.user("present")
            ))
            .maxTokens(100)
            .build();

        assertThat(estimator.estimate(req)).isGreaterThanOrEqualTo(100);
    }

    @Test
    @DisplayName("JSON tool-schema text costs FEWER chars-per-token than prose - calibration sanity")
    void jsonHasLowerCharsPerToken() {
        String prose = "The quick brown fox jumps over the lazy dog.";
        String json = "{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}}}";
        int proseTokens = estimator.countTokens(prose);
        int jsonTokens = estimator.countTokens(json);
        double proseRatio = (double) prose.length() / proseTokens;
        double jsonRatio = (double) json.length() / jsonTokens;
        // JSON has denser tokenization (more tokens per char) → lower chars/token.
        // This is why the legacy chars/4 heuristic severely under-counts tool schemas.
        assertThat(jsonRatio).isLessThan(proseRatio);
    }
}
