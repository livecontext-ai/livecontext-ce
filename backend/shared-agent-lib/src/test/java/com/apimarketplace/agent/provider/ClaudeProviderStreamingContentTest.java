package com.apimarketplace.agent.provider;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Defensive regression tests for the JSON-null text-delta guard in ClaudeProvider.
 *
 * <p>Same bug class as the DeepSeek "nullnullnull..." prefix: {@code NullNode.asText()}
 * returns the literal {@code "null"} string. Claude does not emit null text deltas in
 * practice, but the guard keeps the whole provider family immune.
 */
@DisplayName("ClaudeProvider - streaming content (null-text guard)")
class ClaudeProviderStreamingContentTest {

    private final ClaudeProvider provider = new ClaudeProvider();

    @Test
    @DisplayName("text_delta with text: null yields no chunk (not the literal \"null\")")
    void nullTextDeltaEmitsNothing() {
        String sseLine = "data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":null}}";

        String chunk = provider.processStreamingLine(sseLine);

        assertThat(chunk).isNull();
    }

    @Test
    @DisplayName("normal text_delta is returned verbatim")
    void normalTextDeltaReturned() {
        String sseLine = "data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"Hi\"}}";

        String chunk = provider.processStreamingLine(sseLine);

        assertThat(chunk).isEqualTo("Hi");
    }
}
