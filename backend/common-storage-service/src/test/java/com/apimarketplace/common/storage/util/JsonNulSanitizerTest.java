package com.apimarketplace.common.storage.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for the U+0000 strip funnel helper - the data-shaped root
 * cause of the "step lost its whole output blob but reported COMPLETED"
 * incident (PG rejects NUL in jsonb with SQLSTATE 22P05).
 */
@DisplayName("JsonNulSanitizer - U+0000 strip for jsonb funnels")
class JsonNulSanitizerTest {

    /** The NUL codepoint, built without ever writing the escape in source. */
    private static final String NUL = String.valueOf((char) 0);

    private final ObjectMapper mapper = new ObjectMapper();

    private String serialize(Object o) throws Exception {
        return mapper.writeValueAsString(o);
    }

    @Nested
    @DisplayName("containsNulEscape (the cheap probe)")
    class Probe {

        @Test
        @DisplayName("detects the escape sequence Jackson emits for a real U+0000 codepoint")
        void detectsRealNul() throws Exception {
            String json = serialize(Map.of("k", "a" + NUL + "b"));
            assertThat(JsonNulSanitizer.containsNulEscape(json)).isTrue();
        }

        @Test
        @DisplayName("clean payloads are hit-free")
        void cleanPayloadIsHitFree() throws Exception {
            String json = serialize(Map.of("k", "plain value", "n", 42));
            assertThat(JsonNulSanitizer.containsNulEscape(json)).isFalse();
        }

        @Test
        @DisplayName("null input is not a hit")
        void nullInput() {
            assertThat(JsonNulSanitizer.containsNulEscape(null)).isFalse();
        }

        @Test
        @DisplayName("a LITERAL backslash-u0000 in the data also trips the probe (deliberate false positive, resolved by the parse-based strip)")
        void literalBackslashTripsProbe() throws Exception {
            String json = serialize(Map.of("k", "\\" + "u0000"));
            assertThat(JsonNulSanitizer.containsNulEscape(json)).isTrue();
        }
    }

    @Nested
    @DisplayName("stripNulCodepoints")
    class Strip {

        @Test
        @DisplayName("removes U+0000 from string values, preserving surrounding text: a<NUL>b -> ab")
        void stripsNulPreservingSurroundingText() throws Exception {
            String json = serialize(Map.of("k", "a" + NUL + "b"));

            String cleaned = JsonNulSanitizer.stripNulCodepoints(json);

            Map<?, ?> back = mapper.readValue(cleaned, Map.class);
            assertThat(back.get("k")).isEqualTo("ab");
            assertThat(cleaned).doesNotContain(NUL);
            assertThat(JsonNulSanitizer.containsNulEscape(cleaned)).isFalse();
        }

        @Test
        @DisplayName("strips U+0000 from NESTED strings (objects inside arrays inside objects)")
        void stripsNestedStrings() throws Exception {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("outer", Map.of(
                    "list", List.of("x" + NUL + "y", Map.of("deep", NUL + "z"))));
            String cleaned = JsonNulSanitizer.stripNulCodepoints(serialize(payload));

            assertThat(cleaned).doesNotContain(NUL);
            Map<?, ?> back = mapper.readValue(cleaned, Map.class);
            Map<?, ?> outer = (Map<?, ?>) back.get("outer");
            List<?> list = (List<?>) outer.get("list");
            assertThat(list.get(0)).isEqualTo("xy");
            assertThat(((Map<?, ?>) list.get(1)).get("deep")).isEqualTo("z");
        }

        @Test
        @DisplayName("strips U+0000 from object FIELD NAMES too")
        void stripsFieldNames() throws Exception {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("bad" + NUL + "key", "value");
            String cleaned = JsonNulSanitizer.stripNulCodepoints(serialize(payload));

            Map<?, ?> back = mapper.readValue(cleaned, Map.class);
            assertThat(back.keySet().contains("badkey")).isTrue();
            assertThat(cleaned).doesNotContain(NUL);
        }

        @Test
        @DisplayName("a string containing the LITERAL text backslash-u0000 is NOT altered")
        void literalBackslashU0000IsPreserved() throws Exception {
            String literal = "\\" + "u0000"; // 6 chars: \ u 0 0 0 0 - legal data, NOT a NUL codepoint
            String json = serialize(Map.of("k", literal));

            String cleaned = JsonNulSanitizer.stripNulCodepoints(json);

            Map<?, ?> back = mapper.readValue(cleaned, Map.class);
            assertThat(back.get("k"))
                    .as("literal backslash-u0000 is legal data and must survive the strip byte-identically")
                    .isEqualTo(literal);
        }

        @Test
        @DisplayName("non-string values (numbers incl. big decimals, booleans, nulls) survive the round-trip unchanged")
        void nonStringValuesSurvive() throws Exception {
            String json = "{\"i\":42,\"d\":1.100,\"big\":123456789012345678901234567890,\"b\":true,\"n\":null,\"s\":\"a" + "\\" + "u0000" + "b\"}";
            // the s value above decodes to a<NUL>b: the escape is assembled at runtime,
            // never written in source (Java pre-lexes unicode escapes even in strings)

            String cleaned = JsonNulSanitizer.stripNulCodepoints(json);

            assertThat(cleaned).contains("\"i\":42");
            assertThat(cleaned).contains("\"d\":1.100");
            assertThat(cleaned).contains("\"big\":123456789012345678901234567890");
            assertThat(cleaned).contains("\"b\":true");
            assertThat(cleaned).contains("\"n\":null");
            assertThat(mapper.readTree(cleaned).get("s").asText()).isEqualTo("ab");
        }
    }
}
