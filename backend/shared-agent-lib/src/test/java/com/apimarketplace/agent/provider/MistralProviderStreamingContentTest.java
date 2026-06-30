package com.apimarketplace.agent.provider;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests guarding MistralProvider against a JSON-null content delta.
 *
 * <p>Same bug class as the DeepSeek "nullnullnull..." prefix: an OpenAI-compatible
 * stream can carry {@code content: null} deltas, and {@code NullNode.asText()}
 * returns the literal {@code "null"} string. The fix guards the null content node
 * before calling {@code asText()}.
 */
@DisplayName("MistralProvider - streaming content (null-content guard)")
class MistralProviderStreamingContentTest {

    private final MistralProvider provider = new MistralProvider();

    @Test
    @DisplayName("content: null delta yields no chunk (not the literal \"null\")")
    void nullContentDeltaEmitsNothing() {
        String sseLine = "data: {\"choices\":[{\"delta\":{\"content\":null}}]}";

        String chunk = provider.processStreamingLine(sseLine);

        assertThat(chunk).isNull();
    }

    @Test
    @DisplayName("normal content chunk is returned verbatim")
    void normalContentChunkReturned() {
        String sseLine = "data: {\"choices\":[{\"delta\":{\"content\":\"Bonjour\"}}]}";

        String chunk = provider.processStreamingLine(sseLine);

        assertThat(chunk).isEqualTo("Bonjour");
    }

    @Test
    @DisplayName("empty content chunk yields no chunk")
    void emptyContentChunkEmitsNothing() {
        String sseLine = "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"\"}}]}";

        String chunk = provider.processStreamingLine(sseLine);

        assertThat(chunk).isNull();
    }
}
