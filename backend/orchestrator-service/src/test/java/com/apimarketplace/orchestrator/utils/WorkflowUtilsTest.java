package com.apimarketplace.orchestrator.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("WorkflowUtils")
class WorkflowUtilsTest {

    @Nested
    @DisplayName("normalizeStepId()")
    class NormalizeStepIdTests {
        @Test
        @DisplayName("Should return null for null input")
        void shouldReturnNullForNullInput() {
            assertNull(WorkflowUtils.normalizeStepId(null));
        }

        @Test
        @DisplayName("Should return null for empty input")
        void shouldReturnNullForEmptyInput() {
            assertNull(WorkflowUtils.normalizeStepId(""));
            assertNull(WorkflowUtils.normalizeStepId("   "));
        }

        @ParameterizedTest
        @CsvSource({
            "mcp:MyStep, mcp:mystep",
            "mcp:UPPER, mcp:upper",
            "MCP:Mixed, mcp:mcp_mixed"
        })
        @DisplayName("Should normalize mcp prefix to lowercase")
        void shouldNormalizeMcpPrefix(String input, String expected) {
            assertEquals(expected, WorkflowUtils.normalizeStepId(input));
        }

        @Test
        @DisplayName("Should normalize trigger prefix identifier to lowercase")
        void shouldNormalizeTriggerPrefixIdentifier() {
            // normalizeStepId delegates to LabelNormalizer which normalizes labels to lowercase
            assertEquals("trigger:mywebhook", WorkflowUtils.normalizeStepId("trigger:MyWebhook"));
        }

        @ParameterizedTest
        @CsvSource({
            "core:Decision, core:decision",
            "table:Users, table:users",
            "agent:Claude, agent:claude",
            "note:MyNote, note:mynote",
            "interface:Form, interface:form"
        })
        @DisplayName("Should normalize other prefixes to lowercase")
        void shouldNormalizeOtherPrefixes(String input, String expected) {
            assertEquals(expected, WorkflowUtils.normalizeStepId(input));
        }

        @Test
        @DisplayName("Should add mcp prefix if none present")
        void shouldAddMcpPrefixIfNonePresent() {
            assertEquals("mcp:mystep", WorkflowUtils.normalizeStepId("MyStep"));
        }

        @Test
        @DisplayName("Should handle prefix-only input")
        void shouldHandlePrefixOnlyInput() {
            // "mcp:" -> isNormalizedKey=true, extractLabelFromKey returns "mcp:" (fallthrough),
            // then computeNodeId normalizes "mcp:" to "mcp", returning "mcp:mcp"
            assertEquals("mcp:mcp", WorkflowUtils.normalizeStepId("mcp:"));
            // "mcp:   " -> trim() produces "mcp:" -> extractLabelFromKey returns "mcp:" (fallthrough)
            // -> computeNodeId normalizes "mcp:" to "mcp", returning "mcp:mcp"
            assertEquals("mcp:mcp", WorkflowUtils.normalizeStepId("mcp:   "));
        }
    }

    @Nested
    @DisplayName("normalizeStepIdSafe()")
    class NormalizeStepIdSafeTests {
        @Test
        @DisplayName("Should return null for null input")
        void shouldReturnNullForNullInput() {
            assertNull(WorkflowUtils.normalizeStepIdSafe(null));
        }

        @Test
        @DisplayName("Should return normalized value even for prefix-only input")
        void shouldReturnNormalizedValueForPrefixOnly() {
            // normalizeStepId("mcp:") returns "mcp:mcp" (not null), so normalizeStepIdSafe returns it
            assertEquals("mcp:mcp", WorkflowUtils.normalizeStepIdSafe("mcp:"));
        }

        @Test
        @DisplayName("Should return normalized value")
        void shouldReturnNormalizedValue() {
            assertEquals("mcp:mystep", WorkflowUtils.normalizeStepIdSafe("MyStep"));
        }
    }

    @Nested
    @DisplayName("ID type checks - delegated to LabelNormalizer")
    class IdTypeCheckTests {
        @ParameterizedTest
        @ValueSource(strings = {"trigger:webhook", "trigger:datasource", "trigger:schedule"})
        @DisplayName("Should identify trigger IDs")
        void shouldIdentifyTriggerIds(String id) {
            assertTrue(LabelNormalizer.isTriggerKey(id));
            assertFalse(LabelNormalizer.isMcpKey(id));
        }

        @ParameterizedTest
        @ValueSource(strings = {"mcp:step1", "mcp:api_call", "mcp:transform"})
        @DisplayName("Should identify step IDs")
        void shouldIdentifyStepIds(String id) {
            assertTrue(LabelNormalizer.isMcpKey(id));
            assertFalse(LabelNormalizer.isTriggerKey(id));
        }

        @Test
        @DisplayName("Should identify table IDs")
        void shouldIdentifyTableIds() {
            assertTrue(LabelNormalizer.isTableKey("table:users"));
            assertFalse(LabelNormalizer.isTableKey("mcp:step"));
        }

        @Test
        @DisplayName("Should identify core IDs")
        void shouldIdentifyCoreIds() {
            assertTrue(LabelNormalizer.isCoreKey("core:decision"));
            assertTrue(LabelNormalizer.isCoreKey("core:loop"));
            assertFalse(LabelNormalizer.isCoreKey("mcp:step"));
        }

        @Test
        @DisplayName("Should identify agent IDs")
        void shouldIdentifyAgentIds() {
            assertTrue(LabelNormalizer.isAgentKey("agent:claude"));
            assertFalse(LabelNormalizer.isAgentKey("mcp:step"));
        }

        @Test
        @DisplayName("Should identify note IDs")
        void shouldIdentifyNoteIds() {
            assertTrue(LabelNormalizer.isNoteKey("note:comment"));
            assertFalse(LabelNormalizer.isNoteKey("mcp:step"));
        }

        @Test
        @DisplayName("Should identify interface IDs")
        void shouldIdentifyInterfaceIds() {
            assertTrue(LabelNormalizer.isInterfaceKey("interface:form"));
            assertFalse(LabelNormalizer.isInterfaceKey("mcp:step"));
        }

        @ParameterizedTest
        @NullAndEmptySource
        @DisplayName("Should return false for null/empty")
        void shouldReturnFalseForNullEmpty(String id) {
            assertFalse(LabelNormalizer.isTriggerKey(id));
            assertFalse(LabelNormalizer.isMcpKey(id));
            assertFalse(LabelNormalizer.isCoreKey(id));
        }
    }

    @Nested
    @DisplayName("extractLabelFromKey() - delegated to LabelNormalizer")
    class ExtractAliasTests {
        @Test
        @DisplayName("Should extract alias from prefixed ID")
        void shouldExtractAliasFromPrefixedId() {
            assertEquals("step1", LabelNormalizer.extractLabelFromKey("mcp:step1"));
            assertEquals("webhook", LabelNormalizer.extractLabelFromKey("trigger:webhook"));
            assertEquals("decision", LabelNormalizer.extractLabelFromKey("core:decision"));
        }

        @Test
        @DisplayName("Should return original if no prefix")
        void shouldReturnOriginalIfNoPrefix() {
            assertEquals("step1", LabelNormalizer.extractLabelFromKey("step1"));
        }

        @Test
        @DisplayName("Should return null for null input")
        void shouldReturnNullForNullInput() {
            assertNull(LabelNormalizer.extractLabelFromKey(null));
        }
    }

    @Nested
    @DisplayName("generateRunId()")
    class GenerateRunIdTests {
        @Test
        @DisplayName("Should generate unique run IDs")
        void shouldGenerateUniqueRunIds() {
            String id1 = WorkflowUtils.generateRunId();
            String id2 = WorkflowUtils.generateRunId();

            assertNotEquals(id1, id2);
            assertTrue(id1.startsWith("run_"));
            assertTrue(id2.startsWith("run_"));
        }
    }
}
