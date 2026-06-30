package com.apimarketplace.orchestrator.services.streaming;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("LabelExtractor")
class LabelExtractorTest {

    private LabelExtractor labelExtractor;

    @BeforeEach
    void setUp() {
        labelExtractor = new LabelExtractor();
    }

    @Nested
    @DisplayName("extractRawLabelFromAlias()")
    class ExtractRawLabelFromAliasTests {

        @ParameterizedTest
        @CsvSource({
            "mcp:test, test",
            "mcp:my_step, my_step",
            "trigger:webhook, webhook",
            "trigger:chat, chat",
            "core:decision, decision",
            "agent:my_agent, my_agent"
        })
        @DisplayName("Should strip known prefixes")
        void shouldStripKnownPrefixes(String input, String expected) {
            assertEquals(expected, labelExtractor.extractRawLabelFromAlias(input));
        }

        @Test
        @DisplayName("Should return value without prefix unchanged")
        void shouldReturnUnchangedWithoutPrefix() {
            assertEquals("raw_label", labelExtractor.extractRawLabelFromAlias("raw_label"));
        }

        @ParameterizedTest
        @NullSource
        @DisplayName("Should return null for null input")
        void shouldReturnNullForNull(String input) {
            assertNull(labelExtractor.extractRawLabelFromAlias(input));
        }
    }

    @Nested
    @DisplayName("extractLabelForDbQuery()")
    class ExtractLabelForDbQueryTests {

        @Test
        @DisplayName("Should return alias when execution is null")
        void shouldReturnAliasWhenExecutionIsNull() {
            String result = labelExtractor.extractLabelForDbQuery(null, "step1", "mcp:test_step");
            assertEquals("test_step", result);
        }

        @Test
        @DisplayName("Should extract label from mcp alias")
        void shouldExtractLabelFromMcpAlias() {
            String result = labelExtractor.extractLabelForDbQuery(null, null, "mcp:my_step");
            assertEquals("my_step", result);
        }

        @Test
        @DisplayName("Should extract label from trigger alias")
        void shouldExtractLabelFromTriggerAlias() {
            String result = labelExtractor.extractLabelForDbQuery(null, null, "trigger:webhook");
            assertEquals("webhook", result);
        }

        @Test
        @DisplayName("Should return aliasToEmit when no other source available")
        void shouldReturnAliasWhenNoOtherSource() {
            String result = labelExtractor.extractLabelForDbQuery(null, null, "raw_alias");
            assertEquals("raw_alias", result);
        }

        @Test
        @DisplayName("Should return aliasToEmit for null alias")
        void shouldReturnNullForNullAlias() {
            assertNull(labelExtractor.extractLabelForDbQuery(null, null, null));
        }
    }
}
