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

/**
 * Unit tests for LabelNormalizer utility class.
 *
 * This class is the single source of truth for label normalization
 * across the entire application. Tests verify all normalization rules
 * and key construction methods.
 */
@DisplayName("LabelNormalizer")
class LabelNormalizerTest {

    // ═══════════════════════════════════════════════════════════════════════════
    // normalizeLabel() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("normalizeLabel()")
    class NormalizeLabelTests {

        @Test
        @DisplayName("Should convert to lowercase")
        void shouldConvertToLowercase() {
            assertEquals("my_label", LabelNormalizer.normalizeLabel("MY_LABEL"));
            assertEquals("hello", LabelNormalizer.normalizeLabel("HELLO"));
            assertEquals("mixed_case", LabelNormalizer.normalizeLabel("MiXeD_CaSe"));
        }

        @Test
        @DisplayName("Should replace spaces with underscores")
        void shouldReplaceSpacesWithUnderscores() {
            assertEquals("my_label", LabelNormalizer.normalizeLabel("My Label"));
            assertEquals("hello_world", LabelNormalizer.normalizeLabel("Hello World"));
            assertEquals("multiple_words_here", LabelNormalizer.normalizeLabel("Multiple Words Here"));
        }

        @Test
        @DisplayName("Should transliterate accented characters")
        void shouldTransliterateAccents() {
            assertEquals("entree_ids", LabelNormalizer.normalizeLabel("Entrée IDs"));
            assertEquals("cafe", LabelNormalizer.normalizeLabel("Café"));
            assertEquals("nino", LabelNormalizer.normalizeLabel("Niño"));
            assertEquals("francais", LabelNormalizer.normalizeLabel("Français"));
            assertEquals("uber", LabelNormalizer.normalizeLabel("Über"));
            assertEquals("resume", LabelNormalizer.normalizeLabel("Résumé"));
        }

        @Test
        @DisplayName("Should replace non-alphanumeric with underscores")
        void shouldReplaceNonAlphanumeric() {
            assertEquals("test_123", LabelNormalizer.normalizeLabel("test@123"));
            assertEquals("hello_world", LabelNormalizer.normalizeLabel("hello-world"));
            assertEquals("api_call", LabelNormalizer.normalizeLabel("api.call"));
            assertEquals("if_else", LabelNormalizer.normalizeLabel("If / else"));
            assertEquals("a_b_c", LabelNormalizer.normalizeLabel("a#b$c"));
        }

        @Test
        @DisplayName("Should collapse multiple underscores")
        void shouldCollapseMultipleUnderscores() {
            assertEquals("a_b", LabelNormalizer.normalizeLabel("a___b"));
            assertEquals("hello_world", LabelNormalizer.normalizeLabel("hello___world"));
            assertEquals("test", LabelNormalizer.normalizeLabel("___test___"));
            assertEquals("a_b_c", LabelNormalizer.normalizeLabel("a__b__c"));
        }

        @Test
        @DisplayName("Should trim leading and trailing underscores")
        void shouldTrimUnderscores() {
            assertEquals("label", LabelNormalizer.normalizeLabel("_label_"));
            assertEquals("test", LabelNormalizer.normalizeLabel("___test___"));
            assertEquals("hello", LabelNormalizer.normalizeLabel("_hello"));
            assertEquals("world", LabelNormalizer.normalizeLabel("world_"));
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "\t", "\n", "  \t\n  "})
        @DisplayName("Should return null for null, empty, or blank strings")
        void shouldReturnNullForNullEmptyOrBlank(String input) {
            assertNull(LabelNormalizer.normalizeLabel(input));
        }

        @Test
        @DisplayName("Should return null for strings that normalize to empty")
        void shouldReturnNullForStringsNormalizingToEmpty() {
            assertNull(LabelNormalizer.normalizeLabel("___"));
            assertNull(LabelNormalizer.normalizeLabel("@#$%"));
            assertNull(LabelNormalizer.normalizeLabel("   @   "));
        }

        @Test
        @DisplayName("Should preserve numbers")
        void shouldPreserveNumbers() {
            assertEquals("step_123", LabelNormalizer.normalizeLabel("Step 123"));
            assertEquals("123abc", LabelNormalizer.normalizeLabel("123abc"));
            assertEquals("a1b2c3", LabelNormalizer.normalizeLabel("a1b2c3"));
        }

        @ParameterizedTest
        @CsvSource({
            "My Label, my_label",
            "API Call, api_call",
            "For Each Item, for_each_item",
            "Check-Value, check_value",
            "Step 123, step_123",
            "While Loop, while_loop",
            "If / else, if_else"
        })
        @DisplayName("Should normalize various labels correctly")
        void shouldNormalizeVariousLabels(String input, String expected) {
            assertEquals(expected, LabelNormalizer.normalizeLabel(input));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Key construction methods tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("triggerKey()")
    class TriggerKeyTests {

        @Test
        @DisplayName("Should prefix with trigger:")
        void shouldPrefixWithTrigger() {
            assertEquals("trigger:my_webhook", LabelNormalizer.triggerKey("My Webhook"));
            assertEquals("trigger:start", LabelNormalizer.triggerKey("Start"));
        }

        @Test
        @DisplayName("Should normalize before prefixing")
        void shouldNormalizeBeforePrefixing() {
            assertEquals("trigger:daily_report", LabelNormalizer.triggerKey("Daily Report"));
            assertEquals("trigger:api_trigger", LabelNormalizer.triggerKey("API-Trigger"));
        }

        @Test
        @DisplayName("Should return null for null or blank input")
        void shouldReturnNullForNullOrBlank() {
            assertNull(LabelNormalizer.triggerKey(null));
            assertNull(LabelNormalizer.triggerKey(""));
            assertNull(LabelNormalizer.triggerKey("   "));
        }

        @Test
        @DisplayName("Should use fallback when label is null")
        void shouldUseFallbackWhenLabelIsNull() {
            assertEquals("trigger:fallback_id", LabelNormalizer.triggerKey(null, "fallback_id"));
            assertEquals("trigger:backup", LabelNormalizer.triggerKey("", "Backup"));
        }

        @Test
        @DisplayName("Should prefer label over fallback")
        void shouldPreferLabelOverFallback() {
            assertEquals("trigger:primary", LabelNormalizer.triggerKey("Primary", "fallback"));
        }
    }

    @Nested
    @DisplayName("mcpKey()")
    class McpKeyTests {

        @Test
        @DisplayName("Should prefix with mcp:")
        void shouldPrefixWithMcp() {
            assertEquals("mcp:api_call", LabelNormalizer.mcpKey("API Call"));
            assertEquals("mcp:fetch_data", LabelNormalizer.mcpKey("Fetch Data"));
        }

        @Test
        @DisplayName("Should return null for null or blank input")
        void shouldReturnNullForNullOrBlank() {
            assertNull(LabelNormalizer.mcpKey(null));
            assertNull(LabelNormalizer.mcpKey(""));
        }

        @Test
        @DisplayName("Should use fallback when label is null")
        void shouldUseFallbackWhenLabelIsNull() {
            assertEquals("mcp:step_1", LabelNormalizer.mcpKey(null, "step_1"));
        }
    }

    @Nested
    @DisplayName("tableKey()")
    class TableKeyTests {

        @Test
        @DisplayName("Should prefix with table:")
        void shouldPrefixWithTable() {
            assertEquals("table:users_table", LabelNormalizer.tableKey("Users Table"));
            assertEquals("table:customers", LabelNormalizer.tableKey("Customers"));
        }

        @Test
        @DisplayName("Should return null for null or blank input")
        void shouldReturnNullForNullOrBlank() {
            assertNull(LabelNormalizer.tableKey(null));
        }

        @Test
        @DisplayName("Should use fallback when label is null")
        void shouldUseFallbackWhenLabelIsNull() {
            assertEquals("table:default_table", LabelNormalizer.tableKey(null, "default_table"));
        }
    }

    @Nested
    @DisplayName("agentKey()")
    class AgentKeyTests {

        @Test
        @DisplayName("Should prefix with agent:")
        void shouldPrefixWithAgent() {
            assertEquals("agent:data_analyzer", LabelNormalizer.agentKey("Data Analyzer"));
            assertEquals("agent:my_agent", LabelNormalizer.agentKey("My Agent"));
        }

        @Test
        @DisplayName("Should return null for null or blank input")
        void shouldReturnNullForNullOrBlank() {
            assertNull(LabelNormalizer.agentKey(null));
        }

        @Test
        @DisplayName("Should use fallback when label is null")
        void shouldUseFallbackWhenLabelIsNull() {
            assertEquals("agent:agent_1", LabelNormalizer.agentKey(null, "agent_1"));
        }
    }

    @Nested
    @DisplayName("coreKey()")
    class CoreKeyTests {

        @Test
        @DisplayName("Should prefix with core:")
        void shouldPrefixWithCore() {
            assertEquals("core:check_status", LabelNormalizer.coreKey("Check Status"));
            assertEquals("core:while_loop", LabelNormalizer.coreKey("While Loop"));
            assertEquals("core:for_each", LabelNormalizer.coreKey("For Each"));
        }

        @Test
        @DisplayName("Should return null for null or blank input")
        void shouldReturnNullForNullOrBlank() {
            assertNull(LabelNormalizer.coreKey(null));
        }

        @Test
        @DisplayName("Should use fallback when label is null")
        void shouldUseFallbackWhenLabelIsNull() {
            assertEquals("core:decision_1", LabelNormalizer.coreKey(null, "decision_1"));
        }
    }

    @Nested
    @DisplayName("noteKey()")
    class NoteKeyTests {

        @Test
        @DisplayName("Should prefix with note:")
        void shouldPrefixWithNote() {
            assertEquals("note:my_note", LabelNormalizer.noteKey("My Note"));
            assertEquals("note:documentation", LabelNormalizer.noteKey("Documentation"));
        }

        @Test
        @DisplayName("Should return null for null or blank input")
        void shouldReturnNullForNullOrBlank() {
            assertNull(LabelNormalizer.noteKey(null));
        }

        @Test
        @DisplayName("Should use fallback when label is null")
        void shouldUseFallbackWhenLabelIsNull() {
            assertEquals("note:note_1", LabelNormalizer.noteKey(null, "note_1"));
        }
    }

    @Nested
    @DisplayName("interfaceKey()")
    class InterfaceKeyTests {

        @Test
        @DisplayName("Should prefix with interface:")
        void shouldPrefixWithInterface() {
            assertEquals("interface:user_form", LabelNormalizer.interfaceKey("User Form"));
            assertEquals("interface:login_page", LabelNormalizer.interfaceKey("Login Page"));
        }

        @Test
        @DisplayName("Should return null for null or blank input")
        void shouldReturnNullForNullOrBlank() {
            assertNull(LabelNormalizer.interfaceKey(null));
        }

        @Test
        @DisplayName("Should use fallback when label is null")
        void shouldUseFallbackWhenLabelIsNull() {
            assertEquals("interface:form_1", LabelNormalizer.interfaceKey(null, "form_1"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Key validation methods tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("isNormalizedKey()")
    class IsNormalizedKeyTests {

        @Test
        @DisplayName("Should return true for valid prefixed keys")
        void shouldReturnTrueForValidPrefixedKeys() {
            assertTrue(LabelNormalizer.isNormalizedKey("trigger:webhook"));
            assertTrue(LabelNormalizer.isNormalizedKey("mcp:api_call"));
            assertTrue(LabelNormalizer.isNormalizedKey("table:users"));
            assertTrue(LabelNormalizer.isNormalizedKey("agent:analyzer"));
            assertTrue(LabelNormalizer.isNormalizedKey("core:decision"));
            assertTrue(LabelNormalizer.isNormalizedKey("note:my_note"));
            assertTrue(LabelNormalizer.isNormalizedKey("interface:form"));
        }

        @Test
        @DisplayName("Should return false for invalid keys")
        void shouldReturnFalseForInvalidKeys() {
            assertFalse(LabelNormalizer.isNormalizedKey("invalid:key"));
            assertFalse(LabelNormalizer.isNormalizedKey("no_prefix"));
            assertFalse(LabelNormalizer.isNormalizedKey(""));
            assertFalse(LabelNormalizer.isNormalizedKey(null));
            assertFalse(LabelNormalizer.isNormalizedKey("   "));
        }
    }

    @Nested
    @DisplayName("Type-specific key checks")
    class TypeSpecificKeyChecksTests {

        @Test
        @DisplayName("isTriggerKey() should detect trigger keys")
        void isTriggerKeyShouldDetectTriggerKeys() {
            assertTrue(LabelNormalizer.isTriggerKey("trigger:webhook"));
            assertFalse(LabelNormalizer.isTriggerKey("mcp:step"));
            assertFalse(LabelNormalizer.isTriggerKey(null));
        }

        @Test
        @DisplayName("isMcpKey() should detect mcp keys")
        void isMcpKeyShouldDetectMcpKeys() {
            assertTrue(LabelNormalizer.isMcpKey("mcp:api_call"));
            assertFalse(LabelNormalizer.isMcpKey("trigger:start"));
            assertFalse(LabelNormalizer.isMcpKey(null));
        }

        @Test
        @DisplayName("isTableKey() should detect table keys")
        void isTableKeyShouldDetectTableKeys() {
            assertTrue(LabelNormalizer.isTableKey("table:users"));
            assertFalse(LabelNormalizer.isTableKey("mcp:step"));
            assertFalse(LabelNormalizer.isTableKey(null));
        }

        @Test
        @DisplayName("isAgentKey() should detect agent keys")
        void isAgentKeyShouldDetectAgentKeys() {
            assertTrue(LabelNormalizer.isAgentKey("agent:analyzer"));
            assertFalse(LabelNormalizer.isAgentKey("mcp:step"));
            assertFalse(LabelNormalizer.isAgentKey(null));
        }

        @Test
        @DisplayName("isCoreKey() should detect core keys")
        void isCoreKeyShouldDetectCoreKeys() {
            assertTrue(LabelNormalizer.isCoreKey("core:decision"));
            assertFalse(LabelNormalizer.isCoreKey("mcp:step"));
            assertFalse(LabelNormalizer.isCoreKey(null));
        }

        @Test
        @DisplayName("isNoteKey() should detect note keys")
        void isNoteKeyShouldDetectNoteKeys() {
            assertTrue(LabelNormalizer.isNoteKey("note:my_note"));
            assertFalse(LabelNormalizer.isNoteKey("mcp:step"));
            assertFalse(LabelNormalizer.isNoteKey(null));
        }

        @Test
        @DisplayName("isInterfaceKey() should detect interface keys")
        void isInterfaceKeyShouldDetectInterfaceKeys() {
            assertTrue(LabelNormalizer.isInterfaceKey("interface:form"));
            assertFalse(LabelNormalizer.isInterfaceKey("mcp:step"));
            assertFalse(LabelNormalizer.isInterfaceKey(null));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Utility methods tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getNodeType()")
    class GetNodeTypeTests {

        @Test
        @DisplayName("Should extract node type from key")
        void shouldExtractNodeTypeFromKey() {
            assertEquals("mcp", LabelNormalizer.getNodeType("mcp:api_call"));
            assertEquals("trigger", LabelNormalizer.getNodeType("trigger:webhook"));
            assertEquals("core", LabelNormalizer.getNodeType("core:decision"));
            assertEquals("agent", LabelNormalizer.getNodeType("agent:analyzer"));
        }

        @Test
        @DisplayName("Should return null for invalid keys")
        void shouldReturnNullForInvalidKeys() {
            assertNull(LabelNormalizer.getNodeType(null));
            assertNull(LabelNormalizer.getNodeType(""));
            assertNull(LabelNormalizer.getNodeType("no_colon"));
            assertNull(LabelNormalizer.getNodeType("   "));
        }
    }

    @Nested
    @DisplayName("extractLabelFromKey()")
    class ExtractLabelFromKeyTests {

        @Test
        @DisplayName("Should extract label from key")
        void shouldExtractLabelFromKey() {
            assertEquals("api_call", LabelNormalizer.extractLabelFromKey("mcp:api_call"));
            assertEquals("webhook", LabelNormalizer.extractLabelFromKey("trigger:webhook"));
            assertEquals("check_status", LabelNormalizer.extractLabelFromKey("core:check_status"));
        }

        @Test
        @DisplayName("Should return null for null/blank, return key for no colon")
        void shouldReturnNullForInvalidKeys() {
            assertNull(LabelNormalizer.extractLabelFromKey(null));
            assertNull(LabelNormalizer.extractLabelFromKey(""));
            // No colon found - returns the key itself as the label
            assertEquals("no_colon", LabelNormalizer.extractLabelFromKey("no_colon"));
        }

        @Test
        @DisplayName("Should return key itself when only prefix with colon")
        void shouldHandleKeyWithOnlyPrefix() {
            // colonIndex >= 0 but colonIndex == key.length()-1, so falls through to return key
            assertEquals("mcp:", LabelNormalizer.extractLabelFromKey("mcp:"));
        }
    }

    @Nested
    @DisplayName("extractLabel()")
    class ExtractLabelTests {

        @Test
        @DisplayName("Should extract label without normalization")
        void shouldExtractLabelWithoutNormalization() {
            assertEquals("api_call", LabelNormalizer.extractLabel("mcp:api_call"));
            assertEquals("My Label", LabelNormalizer.extractLabel("trigger:My Label"));
        }

        @Test
        @DisplayName("Should return input if no colon")
        void shouldReturnInputIfNoColon() {
            assertEquals("no_prefix", LabelNormalizer.extractLabel("no_prefix"));
        }

        @Test
        @DisplayName("Should return null or blank input as-is")
        void shouldReturnNullOrBlankAsIs() {
            assertNull(LabelNormalizer.extractLabel(null));
            assertEquals("", LabelNormalizer.extractLabel(""));
        }
    }

    @Nested
    @DisplayName("extractAndNormalizeLabel()")
    class ExtractAndNormalizeLabelTests {

        @Test
        @DisplayName("Should extract and normalize with prefix")
        void shouldExtractAndNormalizeWithPrefix() {
            assertEquals("my_loop", LabelNormalizer.extractAndNormalizeLabel("core:My Loop", "core:"));
            assertEquals("webhook", LabelNormalizer.extractAndNormalizeLabel("trigger:Webhook", "trigger:"));
        }

        @Test
        @DisplayName("Should normalize if prefix not found")
        void shouldNormalizeIfPrefixNotFound() {
            assertEquals("my_label", LabelNormalizer.extractAndNormalizeLabel("My Label", "core:"));
        }

        @Test
        @DisplayName("Should return null for null or blank input")
        void shouldReturnNullForNullOrBlank() {
            assertNull(LabelNormalizer.extractAndNormalizeLabel(null, "core:"));
            assertNull(LabelNormalizer.extractAndNormalizeLabel("", "core:"));
        }
    }

    @Nested
    @DisplayName("extractCoreLabel()")
    class ExtractCoreLabelTests {

        @Test
        @DisplayName("Should extract and normalize core label")
        void shouldExtractAndNormalizeCoreLabel() {
            assertEquals("my_loop", LabelNormalizer.extractCoreLabel("core:my_loop"));
            assertEquals("check_status", LabelNormalizer.extractCoreLabel("core:Check Status"));
        }
    }

    @Nested
    @DisplayName("extractTriggerLabel()")
    class ExtractTriggerLabelTests {

        @Test
        @DisplayName("Should extract and normalize trigger label")
        void shouldExtractAndNormalizeTriggerLabel() {
            assertEquals("webhook", LabelNormalizer.extractTriggerLabel("trigger:webhook"));
            assertEquals("my_trigger", LabelNormalizer.extractTriggerLabel("trigger:My Trigger"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // normalizeVariableReferences() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("normalizeVariableReferences()")
    class NormalizeVariableReferencesTests {

        @Test
        @DisplayName("Should normalize label with spaces in MCP reference")
        void shouldNormalizeMcpWithSpaces() {
            assertEquals(
                "{{mcp:fetch_profile.output.data.user.biography}}",
                LabelNormalizer.normalizeVariableReferences("{{mcp:Fetch Profile.output.data.user.biography}}")
            );
        }

        @Test
        @DisplayName("Should normalize label with spaces in trigger reference")
        void shouldNormalizeTriggerWithSpaces() {
            assertEquals(
                "{{trigger:input_form.output.email}}",
                LabelNormalizer.normalizeVariableReferences("{{trigger:Input Form.output.email}}")
            );
        }

        @Test
        @DisplayName("Should normalize label with spaces in agent reference")
        void shouldNormalizeAgentWithSpaces() {
            assertEquals(
                "{{agent:my_agent.output.response}}",
                LabelNormalizer.normalizeVariableReferences("{{agent:My Agent.output.response}}")
            );
        }

        @Test
        @DisplayName("Should normalize multiple references in one string")
        void shouldNormalizeMultipleReferences() {
            String input = "Analyze {{mcp:Fetch Data.output.items}} and compare with {{trigger:My Form.output.threshold}}";
            String expected = "Analyze {{mcp:fetch_data.output.items}} and compare with {{trigger:my_form.output.threshold}}";
            assertEquals(expected, LabelNormalizer.normalizeVariableReferences(input));
        }

        @Test
        @DisplayName("Should not modify already-normalized references")
        void shouldNotModifyAlreadyNormalized() {
            String input = "{{mcp:fetch_profile.output.data}}";
            assertEquals(input, LabelNormalizer.normalizeVariableReferences(input));
        }

        @Test
        @DisplayName("Should handle reference without field path")
        void shouldHandleReferenceWithoutField() {
            assertEquals(
                "{{mcp:fetch_profile}}",
                LabelNormalizer.normalizeVariableReferences("{{mcp:Fetch Profile}}")
            );
        }

        @Test
        @DisplayName("Should handle null and strings without references")
        void shouldHandleNullAndPlainStrings() {
            assertNull(LabelNormalizer.normalizeVariableReferences(null));
            assertEquals("plain text", LabelNormalizer.normalizeVariableReferences("plain text"));
            assertEquals("", LabelNormalizer.normalizeVariableReferences(""));
        }

        @Test
        @DisplayName("Should normalize accented characters in references")
        void shouldNormalizeAccentedChars() {
            assertEquals(
                "{{mcp:meteo_paris.output.temperature}}",
                LabelNormalizer.normalizeVariableReferences("{{mcp:Météo Paris.output.temperature}}")
            );
        }

        @Test
        @DisplayName("Should normalize core reference")
        void shouldNormalizeCoreReference() {
            assertEquals(
                "{{core:my_loop.output.iteration}}",
                LabelNormalizer.normalizeVariableReferences("{{core:My Loop.output.iteration}}")
            );
        }

        @Test
        @DisplayName("Should normalize table and interface references")
        void shouldNormalizeTableAndInterfaceReferences() {
            assertEquals(
                "{{table:user_data.output.rows}}",
                LabelNormalizer.normalizeVariableReferences("{{table:User Data.output.rows}}")
            );
            assertEquals(
                "{{interface:display_results.output.selected}}",
                LabelNormalizer.normalizeVariableReferences("{{interface:Display Results.output.selected}}")
            );
        }

        @ParameterizedTest
        @CsvSource({
            "{{mcp:Fetch Profile.output.data}},          {{mcp:fetch_profile.output.data}}",
            "{{trigger:Input Form.output.email}},         {{trigger:input_form.output.email}}",
            "{{agent:Data Analyzer.output.result}},       {{agent:data_analyzer.output.result}}",
            "{{core:Check Status.output.passed}},         {{core:check_status.output.passed}}",
            "{{table:User Table.output.count}},           {{table:user_table.output.count}}",
            "{{interface:Profile View.output.action}},    {{interface:profile_view.output.action}}"
        })
        @DisplayName("Should normalize all prefix types via parameterized test")
        void shouldNormalizeAllPrefixTypes(String input, String expected) {
            assertEquals(expected.trim(), LabelNormalizer.normalizeVariableReferences(input.trim()));
        }

        @Test
        @DisplayName("Should preserve text around references")
        void shouldPreserveTextAroundReferences() {
            assertEquals(
                "Hello {{mcp:fetch_data.output.name}}, your score is {{agent:scorer.output.value}}!",
                LabelNormalizer.normalizeVariableReferences(
                    "Hello {{mcp:Fetch Data.output.name}}, your score is {{agent:Scorer.output.value}}!")
            );
        }

        @Test
        @DisplayName("Should handle reference with deeply nested field path")
        void shouldHandleDeeplyNestedFieldPath() {
            assertEquals(
                "{{mcp:fetch_profile.output.data.user.profile_pic_url}}",
                LabelNormalizer.normalizeVariableReferences("{{mcp:Fetch Profile.output.data.user.profile_pic_url}}")
            );
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // normalizeValueDeep() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("normalizeValueDeep()")
    class NormalizeValueDeepTests {

        @Test
        @DisplayName("Should normalize a String value")
        void shouldNormalizeString() {
            assertEquals(
                "{{mcp:fetch_data.output.items}}",
                LabelNormalizer.normalizeValueDeep("{{mcp:Fetch Data.output.items}}")
            );
        }

        @Test
        @DisplayName("Should return non-reference String unchanged")
        void shouldReturnPlainStringUnchanged() {
            assertEquals("plain text", LabelNormalizer.normalizeValueDeep("plain text"));
        }

        @Test
        @DisplayName("Should pass through Number, Boolean, null unchanged")
        void shouldPassThroughPrimitives() {
            assertEquals(42, LabelNormalizer.normalizeValueDeep(42));
            assertEquals(3.14, LabelNormalizer.normalizeValueDeep(3.14));
            assertEquals(true, LabelNormalizer.normalizeValueDeep(true));
            assertNull(LabelNormalizer.normalizeValueDeep(null));
        }

        @Test
        @DisplayName("Should normalize Strings inside a List")
        void shouldNormalizeList() {
            List<Object> input = List.of(
                "{{mcp:Fetch Data.output.a}}",
                "no ref",
                42
            );
            List<?> result = (List<?>) LabelNormalizer.normalizeValueDeep(input);

            assertEquals("{{mcp:fetch_data.output.a}}", result.get(0));
            assertEquals("no ref", result.get(1));
            assertEquals(42, result.get(2));
        }

        @Test
        @DisplayName("Should normalize Strings inside a nested Map")
        void shouldNormalizeNestedMap() {
            Map<String, Object> inner = new LinkedHashMap<>();
            inner.put("ref", "{{agent:My Agent.output.text}}");
            inner.put("count", 5);

            Map<String, Object> outer = new LinkedHashMap<>();
            outer.put("nested", inner);
            outer.put("top", "{{trigger:My Form.output.id}}");

            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) LabelNormalizer.normalizeValueDeep(outer);

            @SuppressWarnings("unchecked")
            Map<String, Object> resultInner = (Map<String, Object>) result.get("nested");
            assertEquals("{{agent:my_agent.output.text}}", resultInner.get("ref"));
            assertEquals(5, resultInner.get("count"));
            assertEquals("{{trigger:my_form.output.id}}", result.get("top"));
        }

        @Test
        @DisplayName("Should normalize List of Maps (like categories)")
        void shouldNormalizeListOfMaps() {
            List<Object> input = new ArrayList<>();
            Map<String, Object> item1 = new LinkedHashMap<>();
            item1.put("label", "billing");
            item1.put("description", "Check {{mcp:Payment Service.output.status}}");
            input.add(item1);

            List<?> result = (List<?>) LabelNormalizer.normalizeValueDeep(input);
            @SuppressWarnings("unchecked")
            Map<String, Object> resultItem = (Map<String, Object>) result.get(0);
            assertEquals("billing", resultItem.get("label"));
            assertEquals("Check {{mcp:payment_service.output.status}}", resultItem.get("description"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // normalizeVariableReferencesDeep() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("normalizeVariableReferencesDeep()")
    class NormalizeVariableReferencesDeepTests {

        @Test
        @DisplayName("Should return null for null input")
        void shouldReturnNullForNull() {
            assertNull(LabelNormalizer.normalizeVariableReferencesDeep(null));
        }

        @Test
        @DisplayName("Should handle empty map")
        void shouldHandleEmptyMap() {
            Map<String, Object> result = LabelNormalizer.normalizeVariableReferencesDeep(new LinkedHashMap<>());
            assertNotNull(result);
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("Should normalize flat map with mixed value types")
        void shouldNormalizeFlatMap() {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("prompt", "Analyze {{mcp:Fetch Data.output.content}}");
            input.put("model", "gpt-4");
            input.put("temperature", 0.7);
            input.put("active", true);
            input.put("optional", null);

            Map<String, Object> result = LabelNormalizer.normalizeVariableReferencesDeep(input);

            assertEquals("Analyze {{mcp:fetch_data.output.content}}", result.get("prompt"));
            assertEquals("gpt-4", result.get("model"));
            assertEquals(0.7, result.get("temperature"));
            assertEquals(true, result.get("active"));
            assertNull(result.get("optional"));
        }

        @Test
        @DisplayName("Should preserve key names (only normalize values)")
        void shouldPreserveKeys() {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("Fetch Profile", "{{mcp:Fetch Profile.output.data}}");

            Map<String, Object> result = LabelNormalizer.normalizeVariableReferencesDeep(input);

            assertTrue(result.containsKey("Fetch Profile"), "Key should not be normalized");
            assertEquals("{{mcp:fetch_profile.output.data}}", result.get("Fetch Profile"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Real-world node type scenarios
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Real-world node scenarios")
    class RealWorldNodeScenarios {

        @Test
        @DisplayName("MCP node: tool params with nested references")
        void mcpNodeToolParams() {
            // Simulates McpCreator storing tool parameters
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("to", "{{trigger:Contact Form.output.email}}");
            params.put("subject", "Welcome {{trigger:Contact Form.output.name}}!");
            params.put("body", Map.of(
                "greeting", "Hello {{trigger:Contact Form.output.name}}",
                "data", "{{mcp:Fetch User.output.profile}}"
            ));
            params.put("priority", 1);

            Map<String, Object> result = LabelNormalizer.normalizeVariableReferencesDeep(params);

            assertEquals("{{trigger:contact_form.output.email}}", result.get("to"));
            assertEquals("Welcome {{trigger:contact_form.output.name}}!", result.get("subject"));
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) result.get("body");
            assertEquals("Hello {{trigger:contact_form.output.name}}", body.get("greeting"));
            assertEquals("{{mcp:fetch_user.output.profile}}", body.get("data"));
            assertEquals(1, result.get("priority"));
        }

        @Test
        @DisplayName("Agent node: prompt and systemPrompt with references")
        void agentNodePrompts() {
            Map<String, Object> agentNode = new LinkedHashMap<>();
            agentNode.put("type", "agent");
            agentNode.put("label", "Data Analyzer");
            agentNode.put("prompt", "Analyze the following data: {{mcp:Fetch Data.output.content}}");
            agentNode.put("systemPrompt", "You are an expert. Context: {{trigger:Input Form.output.context}}");

            Map<String, Object> result = LabelNormalizer.normalizeVariableReferencesDeep(agentNode);

            assertEquals("agent", result.get("type")); // unchanged
            assertEquals("Data Analyzer", result.get("label")); // unchanged (not a ref)
            assertEquals("Analyze the following data: {{mcp:fetch_data.output.content}}", result.get("prompt"));
            assertEquals("You are an expert. Context: {{trigger:input_form.output.context}}", result.get("systemPrompt"));
        }

        @Test
        @DisplayName("Classify node: content and prompt with references")
        void classifyNodeContentAndPrompt() {
            Map<String, Object> classifyNode = new LinkedHashMap<>();
            classifyNode.put("content", "{{trigger:Ticket Form.output.description}}");
            classifyNode.put("prompt", "Classify by {{mcp:Config Loader.output.criteria}}");
            classifyNode.put("categories", List.of(
                Map.of("label", "billing", "description", "Payment issues"),
                Map.of("label", "technical", "description", "Bug reports")
            ));

            Map<String, Object> result = LabelNormalizer.normalizeVariableReferencesDeep(classifyNode);

            assertEquals("{{trigger:ticket_form.output.description}}", result.get("content"));
            assertEquals("Classify by {{mcp:config_loader.output.criteria}}", result.get("prompt"));

            // Categories have no refs, should be unchanged
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> cats = (List<Map<String, Object>>) result.get("categories");
            assertEquals("billing", cats.get(0).get("label"));
            assertEquals("Payment issues", cats.get(0).get("description"));
        }

        @Test
        @DisplayName("Guardrail node: content with references, rules unchanged")
        void guardrailNodeContent() {
            Map<String, Object> guardrailNode = new LinkedHashMap<>();
            guardrailNode.put("content", "{{trigger:Chat Input.output.message}}");
            guardrailNode.put("rules", Map.of(
                "pii", "Block emails and phones",
                "toxicity", "Block offensive content"
            ));
            guardrailNode.put("action", "block");

            Map<String, Object> result = LabelNormalizer.normalizeVariableReferencesDeep(guardrailNode);

            assertEquals("{{trigger:chat_input.output.message}}", result.get("content"));
            assertEquals("block", result.get("action"));
            @SuppressWarnings("unchecked")
            Map<String, Object> rules = (Map<String, Object>) result.get("rules");
            assertEquals("Block emails and phones", rules.get("pii")); // no refs, unchanged
        }

        @Test
        @DisplayName("Decision node: conditions with variable references")
        void decisionNodeConditions() {
            Map<String, Object> decisionNode = new LinkedHashMap<>();
            decisionNode.put("type", "decision");
            decisionNode.put("label", "Check Status");

            List<Map<String, Object>> conditions = new ArrayList<>();
            Map<String, Object> cond = new LinkedHashMap<>();
            cond.put("expression", "{{mcp:Fetch Order.output.status}} == 'paid'");
            cond.put("port", "if");
            conditions.add(cond);
            decisionNode.put("decisionConditions", conditions);

            Map<String, Object> result = LabelNormalizer.normalizeVariableReferencesDeep(decisionNode);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> resultConds = (List<Map<String, Object>>) result.get("decisionConditions");
            assertEquals("{{mcp:fetch_order.output.status}} == 'paid'", resultConds.get(0).get("expression"));
        }

        @Test
        @DisplayName("Switch node: switchExpression and case values with references")
        void switchNodeExpressionAndCases() {
            Map<String, Object> switchNode = new LinkedHashMap<>();
            switchNode.put("switchExpression", "{{agent:Classifier.output.selected_category}}");

            List<Map<String, Object>> cases = new ArrayList<>();
            cases.add(new LinkedHashMap<>(Map.of("value", "billing", "port", "case_0")));
            cases.add(new LinkedHashMap<>(Map.of("value", "{{mcp:Config.output.default_case}}", "port", "case_1")));
            switchNode.put("switchCases", cases);

            Map<String, Object> result = LabelNormalizer.normalizeVariableReferencesDeep(switchNode);

            assertEquals("{{agent:classifier.output.selected_category}}", result.get("switchExpression"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> resultCases = (List<Map<String, Object>>) result.get("switchCases");
            assertEquals("billing", resultCases.get(0).get("value")); // no ref
            assertEquals("{{mcp:config.output.default_case}}", resultCases.get(1).get("value"));
        }

        @Test
        @DisplayName("Loop node: condition and items with references")
        void loopNodeConditionAndItems() {
            Map<String, Object> loopNode = new LinkedHashMap<>();
            loopNode.put("loopCondition", "{{core:Counter.output.index}} < {{mcp:Fetch List.output.total}}");
            loopNode.put("list", "{{mcp:Fetch List.output.items}}");

            Map<String, Object> result = LabelNormalizer.normalizeVariableReferencesDeep(loopNode);

            assertEquals(
                "{{core:counter.output.index}} < {{mcp:fetch_list.output.total}}",
                result.get("loopCondition")
            );
            assertEquals("{{mcp:fetch_list.output.items}}", result.get("list"));
        }

        @Test
        @DisplayName("Interface node: variable_mapping with multiple references")
        void interfaceNodeVariableMapping() {
            Map<String, Object> interfaceNode = new LinkedHashMap<>();
            interfaceNode.put("label", "Display Profile");

            Map<String, Object> variableMapping = new LinkedHashMap<>();
            variableMapping.put("username", "{{mcp:Fetch Profile.output.data.user.username}}");
            variableMapping.put("bio", "{{mcp:Fetch Profile.output.data.user.biography}}");
            variableMapping.put("followers", "{{mcp:Fetch Profile.output.data.user.follower_count}}");
            variableMapping.put("static_text", "Welcome to the profile page");
            interfaceNode.put("variable_mapping", variableMapping);

            Map<String, Object> result = LabelNormalizer.normalizeVariableReferencesDeep(interfaceNode);

            @SuppressWarnings("unchecked")
            Map<String, Object> resultMapping = (Map<String, Object>) result.get("variable_mapping");
            assertEquals("{{mcp:fetch_profile.output.data.user.username}}", resultMapping.get("username"));
            assertEquals("{{mcp:fetch_profile.output.data.user.biography}}", resultMapping.get("bio"));
            assertEquals("{{mcp:fetch_profile.output.data.user.follower_count}}", resultMapping.get("followers"));
            assertEquals("Welcome to the profile page", resultMapping.get("static_text")); // no ref
        }

        @Test
        @DisplayName("Utility transform node: expression with references")
        void utilityTransformNode() {
            Map<String, Object> transformNode = new LinkedHashMap<>();
            transformNode.put("type", "transform");
            transformNode.put("expression", "{{mcp:Fetch Data.output.items}}.![#this.name]");
            transformNode.put("label", "Extract Names");

            Map<String, Object> result = LabelNormalizer.normalizeVariableReferencesDeep(transformNode);

            assertEquals("{{mcp:fetch_data.output.items}}.![#this.name]", result.get("expression"));
            assertEquals("transform", result.get("type"));
            assertEquals("Extract Names", result.get("label")); // not a ref
        }

        @Test
        @DisplayName("HTTP request node: url, headers, body with references")
        void httpRequestNode() {
            Map<String, Object> httpNode = new LinkedHashMap<>();
            httpNode.put("type", "http_request");
            httpNode.put("url", "https://api.example.com/users/{{trigger:Start Form.output.user_id}}");

            Map<String, Object> headers = new LinkedHashMap<>();
            headers.put("Authorization", "Bearer {{mcp:Auth Service.output.token}}");
            headers.put("Content-Type", "application/json");
            httpNode.put("headers", headers);

            httpNode.put("body", "{\"query\": \"{{trigger:Start Form.output.search_term}}\"}");

            Map<String, Object> result = LabelNormalizer.normalizeVariableReferencesDeep(httpNode);

            assertEquals(
                "https://api.example.com/users/{{trigger:start_form.output.user_id}}",
                result.get("url")
            );
            @SuppressWarnings("unchecked")
            Map<String, Object> resultHeaders = (Map<String, Object>) result.get("headers");
            assertEquals("Bearer {{mcp:auth_service.output.token}}", resultHeaders.get("Authorization"));
            assertEquals("application/json", resultHeaders.get("Content-Type")); // no ref
            assertEquals(
                "{\"query\": \"{{trigger:start_form.output.search_term}}\"}",
                result.get("body")
            );
        }

        @Test
        @DisplayName("Plan import: mixed node types in a full plan structure")
        void fullPlanImport() {
            // Simulates what set_plan receives
            Map<String, Object> trigger = new LinkedHashMap<>();
            trigger.put("label", "Contact Form");
            trigger.put("type", "form");

            Map<String, Object> step = new LinkedHashMap<>();
            step.put("label", "Send Email");
            step.put("params", Map.of(
                "to", "{{trigger:Contact Form.output.email}}",
                "body", "Hello {{trigger:Contact Form.output.name}}"
            ));

            Map<String, Object> agent = new LinkedHashMap<>();
            agent.put("label", "Summarizer");
            agent.put("prompt", "Summarize: {{mcp:Send Email.output.response}}");

            // Normalize each like WorkflowBuilderPlanExporter.importList() does
            Map<String, Object> normTrigger = LabelNormalizer.normalizeVariableReferencesDeep(new LinkedHashMap<>(trigger));
            Map<String, Object> normStep = LabelNormalizer.normalizeVariableReferencesDeep(new LinkedHashMap<>(step));
            Map<String, Object> normAgent = LabelNormalizer.normalizeVariableReferencesDeep(new LinkedHashMap<>(agent));

            // Trigger has no refs - should be unchanged
            assertEquals("Contact Form", normTrigger.get("label"));

            // Step params should be normalized
            @SuppressWarnings("unchecked")
            Map<String, Object> normParams = (Map<String, Object>) normStep.get("params");
            assertEquals("{{trigger:contact_form.output.email}}", normParams.get("to"));
            assertEquals("Hello {{trigger:contact_form.output.name}}", normParams.get("body"));

            // Agent prompt should be normalized
            assertEquals("Summarize: {{mcp:send_email.output.response}}", normAgent.get("prompt"));
        }
    }
}
