package com.apimarketplace.agent.provider;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Defensive regression tests for the JSON-null text-part guard in GeminiProvider.
 *
 * <p>Same bug class as the DeepSeek "nullnullnull..." prefix: a part with
 * {@code "text": null} resolves to a Jackson {@code NullNode}, whose {@code asText()}
 * returns the literal {@code "null"} string. The guard skips it.
 */
@DisplayName("GeminiProvider - streaming content (null-text guard)")
class GeminiProviderStreamingContentTest {

    private final GeminiProvider provider = new GeminiProvider();

    @Test
    @DisplayName("part with text: null yields no chunk (not the literal \"null\")")
    void nullTextPartEmitsNothing() {
        String sseLine = "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":null}]}}]}";

        String chunk = provider.processStreamingLine(sseLine);

        assertThat(chunk).isNull();
    }

    @Test
    @DisplayName("normal text part is returned verbatim")
    void normalTextPartReturned() {
        String sseLine = "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Bonjour\"}]}}]}";

        String chunk = provider.processStreamingLine(sseLine);

        assertThat(chunk).isEqualTo("Bonjour");
    }

    @Test
    @DisplayName("thinking part (thought=true) with text: null yields no thinking (not \"null\")")
    void thoughtPartWithNullTextEmitsNoThinking() {
        String sseLine = "data: {\"candidates\":[{\"content\":{\"parts\":[{\"thought\":true,\"text\":null}]}}]}";

        String thinking = provider.parseGeminiThinking(sseLine);

        assertThat(thinking).isNull();
    }

    @Test
    @DisplayName("thinking part via 'thinking' field with null value yields no thinking")
    void thinkingFieldNullEmitsNoThinking() {
        String sseLine = "data: {\"candidates\":[{\"content\":{\"parts\":[{\"thinking\":null}]}}]}";

        String thinking = provider.parseGeminiThinking(sseLine);

        assertThat(thinking).isNull();
    }

    @Test
    @DisplayName("normal thinking part (thought=true) is returned")
    void thoughtPartWithTextReturnsThinking() {
        String sseLine = "data: {\"candidates\":[{\"content\":{\"parts\":[{\"thought\":true,\"text\":\"reasoning\"}]}}]}";

        String thinking = provider.parseGeminiThinking(sseLine);

        assertThat(thinking).isEqualTo("reasoning");
    }
}
