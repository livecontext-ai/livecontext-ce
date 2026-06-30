package com.apimarketplace.catalog.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for the brand-normalized tsquery helper introduced for V159.
 *
 * <p>The helper underpins the OR-branch added to every lexical search method
 * so that user queries containing canonical brand names spelled as one word
 * ("openai", "googlecloud", "dalle") match rows whose provider is stored
 * with a separator ("Open Ai", "Google Cloud", "DALL-E"). The full-stack
 * effect is verified end-to-end against PostgreSQL by integration tests; this
 * suite locks down the helper's pure-string behavior so a regression in
 * tokenization is caught at unit-test speed.</p>
 */
@DisplayName("LexicalSearchIndexRepository.buildCompactTsQuery")
class LexicalSearchIndexRepositoryTest {

    @Nested
    @DisplayName("normalization")
    class Normalization {

        @Test
        @DisplayName("Joins query words with OR, lower-cased, separators stripped")
        void joinsWordsWithOrAndStripsSeparators() {
            assertEquals(
                "openai | create | image | generate",
                LexicalSearchIndexRepository.buildCompactTsQuery("openai create image generate")
            );
        }

        @Test
        @DisplayName("Strips dashes and underscores inside individual words")
        void stripsInternalSeparators() {
            // "dall-e" → "dalle", "create_image" → "createimage"
            assertEquals(
                "dalle | createimage | generation",
                LexicalSearchIndexRepository.buildCompactTsQuery("dall-e create_image generation")
            );
        }

        @Test
        @DisplayName("Lower-cases mixed-case input")
        void lowercases() {
            assertEquals(
                "openai | createimage",
                LexicalSearchIndexRepository.buildCompactTsQuery("OpenAI Create_Image")
            );
        }

        @Test
        @DisplayName("Drops tokens shorter than 2 characters to avoid matching every row")
        void dropsTinyTokens() {
            assertEquals(
                "openai | image",
                LexicalSearchIndexRepository.buildCompactTsQuery("openai a image")
            );
        }
    }

    @Nested
    @DisplayName("safety")
    class Safety {

        @Test
        @DisplayName("Returns sentinel that matches no row when input is null")
        void returnsSentinelOnNull() {
            assertEquals("__nomatch__", LexicalSearchIndexRepository.buildCompactTsQuery(null));
        }

        @Test
        @DisplayName("Returns sentinel that matches no row when input is blank")
        void returnsSentinelOnBlank() {
            assertEquals("__nomatch__", LexicalSearchIndexRepository.buildCompactTsQuery("   "));
        }

        @Test
        @DisplayName("Returns sentinel when every token is too short or non-alphanumeric")
        void returnsSentinelWhenAllTokensFiltered() {
            assertEquals("__nomatch__", LexicalSearchIndexRepository.buildCompactTsQuery("a ! ?"));
        }

        @Test
        @DisplayName("Strips punctuation that to_tsquery('simple', ?) would otherwise reject")
        void stripsPunctuationInsideTokens() {
            // "GPT-4!" → "gpt4" - keeps to_tsquery('simple', ?) call valid even on
            // queries containing characters that would normally fail to parse.
            assertEquals(
                "gpt4 | image",
                LexicalSearchIndexRepository.buildCompactTsQuery("GPT-4! image")
            );
        }
    }
}
