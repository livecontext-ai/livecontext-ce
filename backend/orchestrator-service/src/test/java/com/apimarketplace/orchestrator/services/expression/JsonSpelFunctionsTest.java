package com.apimarketplace.orchestrator.services.expression;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the json() / fromJson() / toJson() SpEL functions.
 *
 * <p>These functions let workflow authors and the LLM agent declare typed JSON intent
 * inline in templates, e.g. {@code "{{json('{\"a\":1}')}}"} resolves to a typed Map.
 * This is the user-facing API for solving the "stringified object param" bug
 * (Gemini generationConfig case): wrap the value in {@code json(...)} and the engine
 * delivers a real Map to the downstream tool.
 */
@DisplayName("JsonSpelFunctions")
class JsonSpelFunctionsTest {

    // ═══════════════════════════════════════════════════════════════════════════
    // json() - happy paths
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("json() parsing")
    class JsonParsingTests {

        @Test
        @DisplayName("Parses object string to Map")
        void parsesObjectStringToMap() {
            Object result = ExpressionFunctions.json("{\"a\":1,\"b\":\"hello\"}");
            assertInstanceOf(Map.class, result);
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) result;
            assertEquals(1, m.get("a"));
            assertEquals("hello", m.get("b"));
        }

        @Test
        @DisplayName("Parses array string to List")
        void parsesArrayStringToList() {
            Object result = ExpressionFunctions.json("[1,2,3]");
            assertInstanceOf(List.class, result);
            assertEquals(List.of(1, 2, 3), result);
        }

        @Test
        @DisplayName("Parses scalar JSON literal - quoted string")
        void parsesQuotedString() {
            assertEquals("hello", ExpressionFunctions.json("\"hello\""));
        }

        @Test
        @DisplayName("Parses scalar JSON literal - number, boolean, null")
        void parsesScalarLiterals() {
            assertEquals(42, ExpressionFunctions.json("42"));
            assertEquals(Boolean.TRUE, ExpressionFunctions.json("true"));
            assertNull(ExpressionFunctions.json("null"));
        }

        @Test
        @DisplayName("Parses nested object preserving structure")
        void parsesNestedObject() {
            Object result = ExpressionFunctions.json("{\"outer\":{\"inner\":[1,2]}}");
            assertInstanceOf(Map.class, result);
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) result;
            @SuppressWarnings("unchecked")
            Map<String, Object> outer = (Map<String, Object>) m.get("outer");
            assertEquals(List.of(1, 2), outer.get("inner"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // json() - idempotence
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("json() idempotence")
    class JsonIdempotenceTests {

        @Test
        @DisplayName("Idempotent on already-typed Map (returns same reference)")
        void idempotentOnMap() {
            Map<String, Object> input = Map.of("a", 1);
            Object result = ExpressionFunctions.json(input);
            assertSame(input, result, "Map should pass through without re-parsing");
        }

        @Test
        @DisplayName("Idempotent on already-typed List")
        void idempotentOnList() {
            List<Integer> input = List.of(1, 2, 3);
            Object result = ExpressionFunctions.json(input);
            assertSame(input, result);
        }

        @Test
        @DisplayName("Idempotent on Number")
        void idempotentOnNumber() {
            assertEquals(42, ExpressionFunctions.json(42));
            assertEquals(3.14, ExpressionFunctions.json(3.14));
        }

        @Test
        @DisplayName("Idempotent on Boolean")
        void idempotentOnBoolean() {
            assertEquals(Boolean.TRUE, ExpressionFunctions.json(Boolean.TRUE));
            assertEquals(Boolean.FALSE, ExpressionFunctions.json(Boolean.FALSE));
        }

        @Test
        @DisplayName("Idempotent on Java native arrays (int[], String[])")
        void idempotentOnNativeArrays() {
            int[] ints = {1, 2, 3};
            String[] strs = {"a", "b"};
            assertSame(ints, ExpressionFunctions.json(ints), "int[] should pass through, not String.valueOf");
            assertSame(strs, ExpressionFunctions.json(strs), "String[] should pass through");
        }

        @Test
        @DisplayName("json(toJson(map)) round-trips to equal Map")
        void roundTripIsIdentity() {
            Map<String, Object> original = new LinkedHashMap<>();
            original.put("a", 1);
            original.put("b", "hello");
            original.put("c", List.of(1, 2));

            String serialized = ExpressionFunctions.toJson(original);
            Object parsed = ExpressionFunctions.json(serialized);

            assertEquals(original, parsed);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // json() - null / blank handling
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("json() null/blank handling")
    class JsonNullBlankTests {

        @Test
        @DisplayName("Returns null on null input")
        void returnsNullOnNull() {
            assertNull(ExpressionFunctions.json(null));
        }

        @Test
        @DisplayName("Returns null on blank string (matches default/ifempty contract)")
        void returnsNullOnBlank() {
            assertNull(ExpressionFunctions.json(""));
            assertNull(ExpressionFunctions.json("   "));
            assertNull(ExpressionFunctions.json("\n\t"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // json() - error path
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("json() error path")
    class JsonErrorTests {

        @Test
        @DisplayName("Throws JsonParseException with value preview on invalid JSON")
        void throwsWithPreviewOnInvalid() {
            JsonParseException ex = assertThrows(JsonParseException.class,
                () -> ExpressionFunctions.json("{not json"));
            assertNotNull(ex.getValuePreview());
            assertTrue(ex.getValuePreview().contains("{not json"),
                "Preview should contain the offending value, was: " + ex.getValuePreview());
            assertTrue(ex.getMessage().contains("failed to parse"),
                "Message should describe the failure, was: " + ex.getMessage());
        }

        @Test
        @DisplayName("Truncates long value preview to 80 chars + ellipsis")
        void truncatesLongPreview() {
            String longInvalid = "{".repeat(200);
            JsonParseException ex = assertThrows(JsonParseException.class,
                () -> ExpressionFunctions.json(longInvalid));
            assertTrue(ex.getValuePreview().length() <= 81 + 1,
                "Preview should be capped near 80 chars + ellipsis, was " + ex.getValuePreview().length());
            assertTrue(ex.getValuePreview().endsWith("…"));
        }

        @Test
        @DisplayName("Coerces non-String non-typed input via String.valueOf then parses")
        void coercesArbitraryObjectViaToString() {
            // A POJO whose toString() yields valid JSON should parse.
            Object weird = new Object() {
                @Override public String toString() { return "[1,2,3]"; }
            };
            Object result = ExpressionFunctions.json(weird);
            assertEquals(List.of(1, 2, 3), result);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Hard caps
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("json() hard caps")
    class JsonHardCapsTests {

        @Test
        @DisplayName("Rejects nesting deeper than the configured cap (64)")
        void rejectsTooDeepNesting() {
            // 200 nested arrays exceeds the 64-deep cap.
            String deep = "[".repeat(200) + "1" + "]".repeat(200);
            assertThrows(JsonParseException.class, () -> ExpressionFunctions.json(deep));
        }

        @Test
        @DisplayName("Rejects single string token longer than 256KB cap")
        void rejectsSingleStringExceeding256KB() {
            // Build a JSON string literal of 300_000 characters - exceeds 256KB cap.
            // Format: ["AAAA...AAAA"] - single string token > 256KB inside an array.
            String huge = "[\"" + "A".repeat(300_000) + "\"]";
            assertThrows(JsonParseException.class, () -> ExpressionFunctions.json(huge),
                "Single-string >256KB should be rejected by StreamReadConstraints");
        }

        @Test
        @DisplayName("maxNumberLength cap rejects pathological number tokens")
        void rejectsHugeNumber() {
            // Jackson's maxNumberLength (1000) limits the textual length of a single
            // number token. Build a 5_000-digit number to exceed the cap.
            String hugeNumber = "1".repeat(5_000);
            assertThrows(JsonParseException.class, () -> ExpressionFunctions.json(hugeNumber),
                "Number token >1000 chars should be rejected by StreamReadConstraints");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // fromJson() alias
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("fromJson() alias")
    class FromJsonAliasTests {

        @Test
        @DisplayName("fromJson() yields the same result as json() for an object string")
        void aliasMirrorsJson() {
            assertEquals(ExpressionFunctions.json("{\"a\":1}"),
                         ExpressionFunctions.fromJson("{\"a\":1}"));
        }

        @Test
        @DisplayName("fromJson() is idempotent on Map")
        void aliasIdempotentOnMap() {
            Map<String, Object> input = Map.of("k", "v");
            assertSame(input, ExpressionFunctions.fromJson(input));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // toJson() - reverse direction
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("toJson() serialization")
    class ToJsonTests {

        @Test
        @DisplayName("Serializes Map to compact JSON string")
        void serializesMap() {
            assertEquals("{\"a\":1}", ExpressionFunctions.toJson(Map.of("a", 1)));
        }

        @Test
        @DisplayName("Serializes List to compact JSON array")
        void serializesList() {
            assertEquals("[1,\"x\"]", ExpressionFunctions.toJson(List.of(1, "x")));
        }

        @Test
        @DisplayName("Serializes empty containers correctly")
        void serializesEmptyContainers() {
            assertEquals("{}", ExpressionFunctions.toJson(Collections.emptyMap()));
            assertEquals("[]", ExpressionFunctions.toJson(Collections.emptyList()));
        }

        @Test
        @DisplayName("Returns literal 'null' for null (Jackson default)")
        void serializesNullAsLiteral() {
            assertEquals("null", ExpressionFunctions.toJson(null));
        }

        @Test
        @DisplayName("Serializes scalar values directly")
        void serializesScalars() {
            assertEquals("42", ExpressionFunctions.toJson(42));
            assertEquals("\"hello\"", ExpressionFunctions.toJson("hello"));
            assertEquals("true", ExpressionFunctions.toJson(true));
        }
    }
}
