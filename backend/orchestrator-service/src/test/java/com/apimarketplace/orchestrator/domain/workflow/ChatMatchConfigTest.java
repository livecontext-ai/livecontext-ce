package com.apimarketplace.orchestrator.domain.workflow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ChatMatchConfig")
class ChatMatchConfigTest {

    @Nested
    @DisplayName("Factory methods")
    class FactoryMethodTests {

        @Test
        @DisplayName("any() should match any message")
        void anyShouldMatchAnyMessage() {
            ChatMatchConfig config = ChatMatchConfig.any();
            assertEquals(ChatMatchConfig.MatchType.ANY, config.type());
            assertTrue(config.matches("hello"));
            assertTrue(config.matches(""));
        }

        @Test
        @DisplayName("startsWith() should create correct config")
        void startsWithShouldCreateCorrectConfig() {
            ChatMatchConfig config = ChatMatchConfig.startsWith("/help", false);
            assertEquals(ChatMatchConfig.MatchType.STARTS_WITH, config.type());
            assertEquals("/help", config.value());
            assertFalse(config.caseSensitive());
        }

        @Test
        @DisplayName("endsWith() should create correct config")
        void endsWithShouldCreateCorrectConfig() {
            ChatMatchConfig config = ChatMatchConfig.endsWith("!", true);
            assertEquals(ChatMatchConfig.MatchType.ENDS_WITH, config.type());
        }

        @Test
        @DisplayName("contains() should create correct config")
        void containsShouldCreateCorrectConfig() {
            ChatMatchConfig config = ChatMatchConfig.contains("help", false);
            assertEquals(ChatMatchConfig.MatchType.CONTAINS, config.type());
        }

        @Test
        @DisplayName("equals() should create correct config")
        void equalsShouldCreateCorrectConfig() {
            ChatMatchConfig config = ChatMatchConfig.equals("hello", true);
            assertEquals(ChatMatchConfig.MatchType.EQUALS, config.type());
        }

        @Test
        @DisplayName("regex() should create correct config")
        void regexShouldCreateCorrectConfig() {
            ChatMatchConfig config = ChatMatchConfig.regex("^hello.*");
            assertEquals(ChatMatchConfig.MatchType.REGEX, config.type());
        }
    }

    @Nested
    @DisplayName("Validation")
    class ValidationTests {

        @Test
        @DisplayName("Should reject null value for non-ANY type")
        void shouldRejectNullValueForNonAnyType() {
            assertThrows(IllegalArgumentException.class,
                () -> new ChatMatchConfig(ChatMatchConfig.MatchType.STARTS_WITH, null, false, true, true));
        }

        @Test
        @DisplayName("Should reject empty value for non-ANY type")
        void shouldRejectEmptyValueForNonAnyType() {
            assertThrows(IllegalArgumentException.class,
                () -> new ChatMatchConfig(ChatMatchConfig.MatchType.CONTAINS, "", false, true, true));
        }

        @Test
        @DisplayName("Should reject invalid regex pattern")
        void shouldRejectInvalidRegex() {
            assertThrows(IllegalArgumentException.class,
                () -> new ChatMatchConfig(ChatMatchConfig.MatchType.REGEX, "[invalid", false, true, true));
        }

        @Test
        @DisplayName("Should default null type to ANY")
        void shouldDefaultNullTypeToAny() {
            ChatMatchConfig config = new ChatMatchConfig(null, null, false, true, true);
            assertEquals(ChatMatchConfig.MatchType.ANY, config.type());
        }
    }

    @Nested
    @DisplayName("matches()")
    class MatchesTests {

        @Test
        @DisplayName("Should return false for null message")
        void shouldReturnFalseForNullMessage() {
            ChatMatchConfig config = ChatMatchConfig.any();
            assertFalse(config.matches(null));
        }

        @Test
        @DisplayName("ANY should match everything")
        void anyShouldMatchEverything() {
            ChatMatchConfig config = ChatMatchConfig.any();
            assertTrue(config.matches("hello"));
            assertTrue(config.matches(""));
            assertTrue(config.matches("anything at all"));
        }

        @Test
        @DisplayName("STARTS_WITH case-insensitive should match")
        void startsWithCaseInsensitiveShouldMatch() {
            ChatMatchConfig config = ChatMatchConfig.startsWith("/help", false);
            assertTrue(config.matches("/help me"));
            assertTrue(config.matches("/HELP me"));
            assertFalse(config.matches("please /help"));
        }

        @Test
        @DisplayName("STARTS_WITH case-sensitive should match")
        void startsWithCaseSensitiveShouldMatch() {
            ChatMatchConfig config = ChatMatchConfig.startsWith("/help", true);
            assertTrue(config.matches("/help me"));
            assertFalse(config.matches("/HELP me"));
        }

        @Test
        @DisplayName("ENDS_WITH should match")
        void endsWithShouldMatch() {
            ChatMatchConfig config = ChatMatchConfig.endsWith("!", false);
            assertTrue(config.matches("Hello!"));
            assertFalse(config.matches("Hello"));
        }

        @Test
        @DisplayName("CONTAINS should match")
        void containsShouldMatch() {
            ChatMatchConfig config = ChatMatchConfig.contains("help", false);
            assertTrue(config.matches("I need help please"));
            assertTrue(config.matches("HELP"));
            assertFalse(config.matches("goodbye"));
        }

        @Test
        @DisplayName("EQUALS case-insensitive should match")
        void equalsCaseInsensitiveShouldMatch() {
            ChatMatchConfig config = ChatMatchConfig.equals("hello", false);
            assertTrue(config.matches("hello"));
            assertTrue(config.matches("HELLO"));
            assertFalse(config.matches("hello world"));
        }

        @Test
        @DisplayName("EQUALS case-sensitive should match")
        void equalsCaseSensitiveShouldMatch() {
            ChatMatchConfig config = ChatMatchConfig.equals("hello", true);
            assertTrue(config.matches("hello"));
            assertFalse(config.matches("Hello"));
        }

        @Test
        @DisplayName("REGEX should match patterns")
        void regexShouldMatchPatterns() {
            ChatMatchConfig config = ChatMatchConfig.regex("\\d{3}-\\d{4}");
            assertTrue(config.matches("Call 555-1234"));
            assertFalse(config.matches("No numbers here"));
        }

        @Test
        @DisplayName("REGEX case-insensitive should match")
        void regexCaseInsensitiveShouldMatch() {
            ChatMatchConfig config = new ChatMatchConfig(
                ChatMatchConfig.MatchType.REGEX, "hello", false, true, true);
            assertTrue(config.matches("HELLO WORLD"));
        }
    }

    @Nested
    @DisplayName("extractMessage()")
    class ExtractMessageTests {

        @Test
        @DisplayName("Should return null for null message")
        void shouldReturnNullForNullMessage() {
            ChatMatchConfig config = ChatMatchConfig.startsWith("/cmd", false);
            assertNull(config.extractMessage(null));
        }

        @Test
        @DisplayName("Should return message for non-matching message")
        void shouldReturnMessageForNonMatch() {
            ChatMatchConfig config = ChatMatchConfig.startsWith("/cmd", false);
            assertEquals("hello", config.extractMessage("hello"));
        }

        @Test
        @DisplayName("STARTS_WITH should trim prefix")
        void startsWithShouldTrimPrefix() {
            ChatMatchConfig config = ChatMatchConfig.startsWith("/help ", false);
            assertEquals("me please", config.extractMessage("/help me please"));
        }

        @Test
        @DisplayName("ENDS_WITH should trim suffix")
        void endsWithShouldTrimSuffix() {
            ChatMatchConfig config = ChatMatchConfig.endsWith("!", false);
            assertEquals("Hello", config.extractMessage("Hello!"));
        }

        @Test
        @DisplayName("CONTAINS should return original message")
        void containsShouldReturnOriginal() {
            ChatMatchConfig config = ChatMatchConfig.contains("world", false);
            assertEquals("hello world!", config.extractMessage("hello world!"));
        }

        @Test
        @DisplayName("EQUALS should return original message")
        void equalsShouldReturnOriginal() {
            ChatMatchConfig config = ChatMatchConfig.equals("hello", false);
            assertEquals("hello", config.extractMessage("hello"));
        }
    }

    @Nested
    @DisplayName("fromMap()")
    class FromMapTests {

        @Test
        @DisplayName("Should return ANY for null map")
        void shouldReturnAnyForNullMap() {
            ChatMatchConfig config = ChatMatchConfig.fromMap(null);
            assertEquals(ChatMatchConfig.MatchType.ANY, config.type());
        }

        @Test
        @DisplayName("Should return ANY for empty map")
        void shouldReturnAnyForEmptyMap() {
            ChatMatchConfig config = ChatMatchConfig.fromMap(Map.of());
            assertEquals(ChatMatchConfig.MatchType.ANY, config.type());
        }

        @Test
        @DisplayName("Should parse starts_with type")
        void shouldParseStartsWithType() {
            Map<String, Object> map = new HashMap<>();
            map.put("type", "starts_with");
            map.put("value", "/cmd");
            map.put("caseSensitive", true);

            ChatMatchConfig config = ChatMatchConfig.fromMap(map);
            assertEquals(ChatMatchConfig.MatchType.STARTS_WITH, config.type());
            assertEquals("/cmd", config.value());
            assertTrue(config.caseSensitive());
        }

        @Test
        @DisplayName("Should parse startswith type (camelCase)")
        void shouldParseStartswithCamelCase() {
            Map<String, Object> map = new HashMap<>();
            map.put("type", "startswith");
            map.put("value", "/cmd");

            ChatMatchConfig config = ChatMatchConfig.fromMap(map);
            assertEquals(ChatMatchConfig.MatchType.STARTS_WITH, config.type());
        }

        @Test
        @DisplayName("Should default trimPrefix and trimSuffix to true")
        void shouldDefaultTrimToTrue() {
            Map<String, Object> map = new HashMap<>();
            map.put("type", "any");

            ChatMatchConfig config = ChatMatchConfig.fromMap(map);
            assertTrue(config.trimPrefix());
            assertTrue(config.trimSuffix());
        }
    }

    @Nested
    @DisplayName("toMap()")
    class ToMapTests {

        @Test
        @DisplayName("Should convert to map correctly")
        void shouldConvertToMapCorrectly() {
            ChatMatchConfig config = ChatMatchConfig.startsWith("/help", true);
            Map<String, Object> map = config.toMap();

            assertEquals("starts_with", map.get("type"));
            assertEquals("/help", map.get("value"));
            assertEquals(true, map.get("caseSensitive"));
            assertEquals(true, map.get("trimPrefix"));
        }
    }
}
