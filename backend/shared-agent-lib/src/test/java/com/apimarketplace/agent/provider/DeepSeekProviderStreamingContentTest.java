package com.apimarketplace.agent.provider;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for the "nullnullnull..." prefix on DeepSeek reasoning models
 * (deepseek-reasoner and the V4 flash/pro variants).
 *
 * <p>During the thinking phase those models stream {@code reasoning_content} with
 * {@code content: null}. The pre-fix code did {@code delta.get("content").asText()}
 * unconditionally; Jackson's {@code NullNode.asText()} returns the literal string
 * {@code "null"}, so one {@code "null"} was emitted per reasoning chunk and the
 * visible answer was prefixed with a long run of {@code null}. The fix guards the
 * JSON-null content node before calling {@code asText()}.
 */
@DisplayName("DeepSeekProvider - streaming content (reasoning null-content guard)")
class DeepSeekProviderStreamingContentTest {

    private final DeepSeekProvider provider = new DeepSeekProvider();

    @Test
    @DisplayName("reasoning chunk with content: null yields no chunk (not the literal \"null\")")
    void reasoningChunkWithNullContentEmitsNothing() {
        String sseLine = "data: {\"choices\":[{\"delta\":{" +
                "\"reasoning_content\":\"Let me think about this\",\"content\":null}}]}";

        String chunk = provider.processStreamingLine(sseLine);

        assertThat(chunk).isNull();
    }

    @Test
    @DisplayName("normal content chunk is returned verbatim")
    void normalContentChunkReturned() {
        String sseLine = "data: {\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}";

        String chunk = provider.processStreamingLine(sseLine);

        assertThat(chunk).isEqualTo("Hello");
    }

    @Test
    @DisplayName("empty content chunk yields no chunk")
    void emptyContentChunkEmitsNothing() {
        String sseLine = "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"\"}}]}";

        String chunk = provider.processStreamingLine(sseLine);

        assertThat(chunk).isNull();
    }

    @Test
    @DisplayName("a full reasoning-then-answer stream concatenates to the answer with no \"null\" prefix")
    void fullStreamHasNoNullPrefix() {
        String[] lines = {
                // role bootstrap chunk
                "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":null}}]}",
                // reasoning phase: content is null on every chunk
                "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"step 1\",\"content\":null}}]}",
                "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"step 2\",\"content\":null}}]}",
                // answer phase
                "data: {\"choices\":[{\"delta\":{\"content\":\"Every\"}}]}",
                "data: {\"choices\":[{\"delta\":{\"content\":\"thing's fine\"}}]}",
                "data: [DONE]"
        };

        StringBuilder full = new StringBuilder();
        for (String line : lines) {
            String chunk = provider.processStreamingLine(line);
            if (chunk != null && !chunk.isEmpty()) {
                full.append(chunk);
            }
        }

        assertThat(full.toString()).isEqualTo("Everything's fine");
        assertThat(full.toString()).doesNotContain("null");
    }
}
