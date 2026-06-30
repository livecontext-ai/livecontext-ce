package com.apimarketplace.agent.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Focused tests for the three-state integer patch semantic used by agent guard overrides
 * (see {@link RequestParameterExtractor#extractIntegerMap}). The semantic is load-bearing
 * because the caller uses {@code containsKey} to distinguish "leave untouched", "clear",
 * and "set".
 */
@DisplayName("RequestParameterExtractor.extractIntegerMap")
class RequestParameterExtractorTest {

    private final RequestParameterExtractor extractor = new RequestParameterExtractor();
    private static final List<String> KEYS = List.of("a", "b", "c");

    @Nested
    @DisplayName("three-state semantic (absent / null / value)")
    class ThreeStateSemantic {

        @Test
        @DisplayName("returns null when no key is present in the request")
        void returnsNullWhenNoKeysPresent() {
            Map<String, Object> req = Map.of("unrelated", 42);
            assertThat(extractor.extractIntegerMap(req, KEYS)).isNull();
        }

        @Test
        @DisplayName("returns null when the request map is empty")
        void returnsNullForEmptyRequest() {
            assertThat(extractor.extractIntegerMap(new HashMap<>(), KEYS)).isNull();
        }

        @Test
        @DisplayName("preserves explicit null (key present with null value)")
        void preservesExplicitNull() {
            Map<String, Object> req = new HashMap<>();
            req.put("a", null);

            Map<String, Integer> out = extractor.extractIntegerMap(req, KEYS);

            assertThat(out).containsKey("a");
            assertThat(out.get("a")).isNull();
            assertThat(out).doesNotContainKey("b");
            assertThat(out).doesNotContainKey("c");
        }

        @Test
        @DisplayName("includes only keys that appear in the request")
        void returnsOnlyPresentKeys() {
            Map<String, Object> req = new HashMap<>();
            req.put("a", 7);
            req.put("c", 9);

            Map<String, Integer> out = extractor.extractIntegerMap(req, KEYS);

            assertThat(out).containsEntry("a", 7).containsEntry("c", 9);
            assertThat(out).doesNotContainKey("b");
        }

        @Test
        @DisplayName("accepts numeric strings (parity with getInteger)")
        void acceptsNumericStrings() {
            Map<String, Object> req = new HashMap<>();
            req.put("a", "42");

            Map<String, Integer> out = extractor.extractIntegerMap(req, KEYS);

            assertThat(out).containsEntry("a", 42);
        }
    }

    @Nested
    @DisplayName("rejects non-coercible values - no silent clear")
    class RejectsInvalidValues {

        @Test
        @DisplayName("throws when value is a non-numeric string")
        void throwsForNonNumericString() {
            Map<String, Object> req = Map.of("a", "not-a-number");
            assertThatThrownBy(() -> extractor.extractIntegerMap(req, KEYS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("a must be an integer or null")
                .hasMessageContaining("not-a-number");
        }

        @Test
        @DisplayName("throws when value is a Boolean")
        void throwsForBoolean() {
            Map<String, Object> req = Map.of("a", true);
            assertThatThrownBy(() -> extractor.extractIntegerMap(req, KEYS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("a must be an integer or null");
        }

        @Test
        @DisplayName("throws when value is a Map")
        void throwsForMap() {
            Map<String, Object> req = Map.of("a", Map.of("nested", 1));
            assertThatThrownBy(() -> extractor.extractIntegerMap(req, KEYS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("a must be an integer or null");
        }

        @Test
        @DisplayName("throws when value is a List")
        void throwsForList() {
            Map<String, Object> req = Map.of("a", List.of(1, 2, 3));
            assertThatThrownBy(() -> extractor.extractIntegerMap(req, KEYS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("a must be an integer or null");
        }
    }

    @Nested
    @DisplayName("getText - content fields never silently stringify a number")
    class GetTextContentGuard {

        @Test
        @DisplayName("returns the string for genuine text")
        void returnsText() {
            Map<String, Object> req = Map.of("instructions", "# Real markdown content");
            assertThat(extractor.getText(req, "instructions")).isEqualTo("# Real markdown content");
        }

        @Test
        @DisplayName("returns null when the key is absent")
        void returnsNullWhenAbsent() {
            assertThat(extractor.getText(new HashMap<>(), "instructions")).isNull();
        }

        @Test
        @DisplayName("THROWS on a numeric value (the 106735-in-instructions bug) instead of storing the number")
        void throwsForNumber() {
            Map<String, Object> req = Map.of("instructions", 106735);
            assertThatThrownBy(() -> extractor.getText(req, "instructions"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("instructions must be text")
                .hasMessageContaining("106735");
        }

        @Test
        @DisplayName("THROWS on a Boolean value too")
        void throwsForBoolean() {
            Map<String, Object> req = Map.of("systemPrompt", true);
            assertThatThrownBy(() -> extractor.getText(req, "systemPrompt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("systemPrompt must be text");
        }

        @Test
        @DisplayName("contrast: the legacy getString silently stringifies a number (why getText exists)")
        void getStringStillStringifiesForBackCompat() {
            Map<String, Object> req = Map.of("instructions", 106735);
            assertThat(extractor.getString(req, "instructions")).isEqualTo("106735");
        }
    }
}
