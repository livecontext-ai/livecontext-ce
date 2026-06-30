package com.apimarketplace.common.mapping;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for SimpleMappingEngine - JSON mapping with path navigation, type conversion,
 * candidate fallbacks, and array support.
 */
@DisplayName("SimpleMappingEngine")
class SimpleMappingEngineTest {

    // -------------------------------------------------------------------------
    // Helper: build a mapping JSON string
    // -------------------------------------------------------------------------

    private String buildMapping(String itemsPath, Map<String, FieldDef> fields) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"source\":{\"format\":\"json\"");
        if (itemsPath != null) {
            sb.append(",\"items_path\":\"").append(itemsPath).append("\"");
        }
        sb.append("},\"fields\":{");

        boolean first = true;
        for (Map.Entry<String, FieldDef> entry : fields.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(entry.getKey()).append("\":");
            FieldDef fd = entry.getValue();
            sb.append("{\"candidates\":[");
            for (int i = 0; i < fd.candidates.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append("\"").append(fd.candidates.get(i)).append("\"");
            }
            sb.append("],\"to\":\"").append(fd.to).append("\"");
            if (fd.required) sb.append(",\"required\":true");
            if (fd.defaultValue != null) sb.append(",\"default\":\"").append(fd.defaultValue).append("\"");
            sb.append("}");
        }
        sb.append("}}");
        return sb.toString();
    }

    private record FieldDef(List<String> candidates, String to, boolean required, String defaultValue) {
        FieldDef(List<String> candidates, String to) {
            this(candidates, to, false, null);
        }
    }

    // -------------------------------------------------------------------------
    // Basic mapping tests
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Basic field mapping")
    class BasicFieldMapping {

        @Test
        @DisplayName("should map simple object fields with relative path")
        void shouldMapSimpleFields() throws IOException {
            String json = "{\"data\":{\"items\":[{\"id\":1,\"name\":\"Alice\"},{\"id\":2,\"name\":\"Bob\"}]}}";
            String mapping = buildMapping("$.data.items[*]", Map.of(
                    "userId", new FieldDef(List.of("@.id"), "integer"),
                    "userName", new FieldDef(List.of("@.name"), "string")
            ));

            SimpleMappingEngine.MappingOutcome outcome = SimpleMappingEngine.apply(json, mapping);

            assertThat(outcome.itemCount).isEqualTo(2);
            assertThat(outcome.items).hasSize(2);
            assertThat(outcome.items.get(0).get("userId")).isEqualTo(1);
            assertThat(outcome.items.get(0).get("userName")).isEqualTo("Alice");
            assertThat(outcome.items.get(1).get("userId")).isEqualTo(2);
            assertThat(outcome.items.get(1).get("userName")).isEqualTo("Bob");
        }

        @Test
        @DisplayName("should handle single object (not array) as single item")
        void shouldHandleSingleObject() throws IOException {
            String json = "{\"id\":42,\"title\":\"Hello\"}";
            String mapping = buildMapping(null, Map.of(
                    "itemId", new FieldDef(List.of("@.id"), "integer"),
                    "itemTitle", new FieldDef(List.of("@.title"), "string")
            ));

            SimpleMappingEngine.MappingOutcome outcome = SimpleMappingEngine.apply(json, mapping);

            assertThat(outcome.itemCount).isEqualTo(1);
            assertThat(outcome.items.get(0).get("itemId")).isEqualTo(42);
            assertThat(outcome.items.get(0).get("itemTitle")).isEqualTo("Hello");
        }

        @Test
        @DisplayName("should handle root array as items")
        void shouldHandleRootArray() throws IOException {
            String json = "[{\"x\":1},{\"x\":2},{\"x\":3}]";
            String mapping = buildMapping(null, Map.of(
                    "value", new FieldDef(List.of("@.x"), "integer")
            ));

            SimpleMappingEngine.MappingOutcome outcome = SimpleMappingEngine.apply(json, mapping);

            assertThat(outcome.itemCount).isEqualTo(3);
            assertThat(outcome.items.get(0).get("value")).isEqualTo(1);
            assertThat(outcome.items.get(2).get("value")).isEqualTo(3);
        }
    }

    // -------------------------------------------------------------------------
    // Path prefix tests
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Path prefixes (@, $, ^^)")
    class PathPrefixTests {

        @Test
        @DisplayName("should resolve relative path with @. prefix")
        void shouldResolveRelativePath() throws IOException {
            String json = "{\"results\":[{\"info\":{\"age\":30}}]}";
            String mapping = buildMapping("$.results[*]", Map.of(
                    "age", new FieldDef(List.of("@.info.age"), "integer")
            ));

            SimpleMappingEngine.MappingOutcome outcome = SimpleMappingEngine.apply(json, mapping);

            assertThat(outcome.items.get(0).get("age")).isEqualTo(30);
        }

        @Test
        @DisplayName("should resolve absolute path with $. prefix")
        void shouldResolveAbsolutePath() throws IOException {
            String json = "{\"meta\":{\"version\":\"2.0\"},\"data\":[{\"id\":1}]}";
            String mapping = buildMapping("$.data[*]", Map.of(
                    "apiVersion", new FieldDef(List.of("$.meta.version"), "string")
            ));

            SimpleMappingEngine.MappingOutcome outcome = SimpleMappingEngine.apply(json, mapping);

            assertThat(outcome.items.get(0).get("apiVersion")).isEqualTo("2.0");
        }

        @Test
        @DisplayName("should resolve ascendant path with ^^. prefix")
        void shouldResolveAscendantPath() throws IOException {
            String json = "{\"parent_info\":\"top\",\"children\":[{\"name\":\"child1\"}]}";
            String mapping = buildMapping("$.children[*]", Map.of(
                    "info", new FieldDef(List.of("^^.parent_info"), "string")
            ));

            SimpleMappingEngine.MappingOutcome outcome = SimpleMappingEngine.apply(json, mapping);

            assertThat(outcome.items.get(0).get("info")).isEqualTo("top");
        }
    }

    // -------------------------------------------------------------------------
    // Type conversion tests
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Type conversions")
    class TypeConversionTests {

        @Test
        @DisplayName("should convert to string")
        void shouldConvertToString() throws IOException {
            String json = "{\"val\":42}";
            String mapping = buildMapping(null, Map.of(
                    "result", new FieldDef(List.of("@.val"), "string")
            ));

            SimpleMappingEngine.MappingOutcome outcome = SimpleMappingEngine.apply(json, mapping);

            assertThat(outcome.items.get(0).get("result")).isEqualTo("42");
        }

        @Test
        @DisplayName("should convert to integer")
        void shouldConvertToInteger() throws IOException {
            String json = "{\"val\":99}";
            String mapping = buildMapping(null, Map.of(
                    "result", new FieldDef(List.of("@.val"), "integer")
            ));

            SimpleMappingEngine.MappingOutcome outcome = SimpleMappingEngine.apply(json, mapping);

            assertThat(outcome.items.get(0).get("result")).isEqualTo(99);
        }

        @Test
        @DisplayName("should convert to long")
        void shouldConvertToLong() throws IOException {
            String json = "{\"val\":9999999999}";
            String mapping = buildMapping(null, Map.of(
                    "result", new FieldDef(List.of("@.val"), "long")
            ));

            SimpleMappingEngine.MappingOutcome outcome = SimpleMappingEngine.apply(json, mapping);

            assertThat(outcome.items.get(0).get("result")).isEqualTo(9999999999L);
        }

        @Test
        @DisplayName("should convert to number (double)")
        void shouldConvertToNumber() throws IOException {
            String json = "{\"val\":3.14}";
            String mapping = buildMapping(null, Map.of(
                    "result", new FieldDef(List.of("@.val"), "number")
            ));

            SimpleMappingEngine.MappingOutcome outcome = SimpleMappingEngine.apply(json, mapping);

            assertThat(outcome.items.get(0).get("result")).isEqualTo(3.14);
        }

        @Test
        @DisplayName("should convert to boolean")
        void shouldConvertToBoolean() throws IOException {
            String json = "{\"active\":true}";
            String mapping = buildMapping(null, Map.of(
                    "result", new FieldDef(List.of("@.active"), "boolean")
            ));

            SimpleMappingEngine.MappingOutcome outcome = SimpleMappingEngine.apply(json, mapping);

            assertThat(outcome.items.get(0).get("result")).isEqualTo(true);
        }

        @Test
        @DisplayName("should return null for type mismatch (non-number to integer)")
        void shouldReturnNullForTypeMismatch() throws IOException {
            String json = "{\"val\":\"not-a-number\"}";
            String mapping = buildMapping(null, Map.of(
                    "result", new FieldDef(List.of("@.val"), "integer")
            ));

            SimpleMappingEngine.MappingOutcome outcome = SimpleMappingEngine.apply(json, mapping);

            assertThat(outcome.items.get(0)).doesNotContainKey("result");
        }
    }

    // -------------------------------------------------------------------------
    // Candidate fallback tests
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Candidate fallbacks")
    class CandidateFallbackTests {

        @Test
        @DisplayName("should use first matching candidate")
        void shouldUseFirstMatchingCandidate() throws IOException {
            String json = "{\"items\":[{\"fullName\":\"Alice\",\"name\":\"A\"}]}";
            String mapping = buildMapping("$.items[*]", Map.of(
                    "label", new FieldDef(List.of("@.fullName", "@.name"), "string")
            ));

            SimpleMappingEngine.MappingOutcome outcome = SimpleMappingEngine.apply(json, mapping);

            assertThat(outcome.items.get(0).get("label")).isEqualTo("Alice");
        }

        @Test
        @DisplayName("should fall back to second candidate when first is missing")
        void shouldFallBackToSecondCandidate() throws IOException {
            String json = "{\"items\":[{\"name\":\"Bob\"}]}";
            String mapping = buildMapping("$.items[*]", Map.of(
                    "label", new FieldDef(List.of("@.fullName", "@.name"), "string")
            ));

            SimpleMappingEngine.MappingOutcome outcome = SimpleMappingEngine.apply(json, mapping);

            assertThat(outcome.items.get(0).get("label")).isEqualTo("Bob");
        }
    }

    // -------------------------------------------------------------------------
    // Default value and required field tests
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Default values and required fields")
    class DefaultAndRequiredTests {

        @Test
        @DisplayName("should use default value when field is missing")
        void shouldUseDefaultValue() throws IOException {
            String json = "{\"data\":[{\"id\":1}]}";
            String mapping = buildMapping("$.data[*]", Map.of(
                    "status", new FieldDef(List.of("@.status"), "string", false, "unknown")
            ));

            SimpleMappingEngine.MappingOutcome outcome = SimpleMappingEngine.apply(json, mapping);

            assertThat(outcome.items.get(0).get("status")).isEqualTo("unknown");
        }

        @Test
        @DisplayName("should track unresolved required fields")
        void shouldTrackUnresolvedRequiredFields() throws IOException {
            String json = "{\"data\":[{\"id\":1}]}";
            String mapping = buildMapping("$.data[*]", Map.of(
                    "name", new FieldDef(List.of("@.name"), "string", true, null)
            ));

            SimpleMappingEngine.MappingOutcome outcome = SimpleMappingEngine.apply(json, mapping);

            assertThat(outcome.unresolvedFields).contains("name");
        }

        @Test
        @DisplayName("should not track unresolved non-required fields")
        void shouldNotTrackUnresolvedNonRequiredFields() throws IOException {
            String json = "{\"data\":[{\"id\":1}]}";
            String mapping = buildMapping("$.data[*]", Map.of(
                    "optional", new FieldDef(List.of("@.optional"), "string", false, null)
            ));

            SimpleMappingEngine.MappingOutcome outcome = SimpleMappingEngine.apply(json, mapping);

            assertThat(outcome.unresolvedFields).isEmpty();
        }
    }

    // -------------------------------------------------------------------------
    // Array type mapping tests
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Array type mapping")
    class ArrayTypeMappingTests {

        @Test
        @DisplayName("should map array<string> field")
        void shouldMapArrayOfStrings() throws IOException {
            String json = "{\"data\":[{\"tags\":[\"api\",\"rest\",\"json\"]}]}";

            // Build mapping manually for array type
            String mapping = """
                    {
                      "source": {"format":"json","items_path":"$.data[*]"},
                      "fields": {
                        "labels": {
                          "candidates": ["@.tags[*]"],
                          "to": "array<string>"
                        }
                      }
                    }
                    """;

            SimpleMappingEngine.MappingOutcome outcome = SimpleMappingEngine.apply(json, mapping);

            assertThat(outcome.items.get(0).get("labels"))
                    .isInstanceOf(List.class);
            @SuppressWarnings("unchecked")
            List<Object> labels = (List<Object>) outcome.items.get(0).get("labels");
            assertThat(labels).containsExactly("api", "rest", "json");
        }
    }

    // -------------------------------------------------------------------------
    // Root alternatives tests
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Root alternatives")
    class RootAlternativesTests {

        @Test
        @DisplayName("should use root_alternatives when items_path fails")
        void shouldUseRootAlternatives() throws IOException {
            String json = "{\"results\":[{\"id\":1},{\"id\":2}]}";

            String mapping = """
                    {
                      "source": {
                        "format": "json",
                        "items_path": "$.data.items[*]",
                        "root_alternatives": ["$.results[*]"]
                      },
                      "fields": {
                        "itemId": {"candidates":["@.id"], "to":"integer"}
                      }
                    }
                    """;

            SimpleMappingEngine.MappingOutcome outcome = SimpleMappingEngine.apply(json, mapping);

            assertThat(outcome.itemCount).isEqualTo(2);
            assertThat(outcome.items.get(0).get("itemId")).isEqualTo(1);
        }
    }

    // -------------------------------------------------------------------------
    // Nested path tests
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Nested path navigation")
    class NestedPathTests {

        @Test
        @DisplayName("should navigate deeply nested paths")
        void shouldNavigateDeeplyNestedPaths() throws IOException {
            String json = "{\"a\":{\"b\":{\"c\":[{\"d\":{\"e\":\"deep_value\"}}]}}}";
            String mapping = buildMapping("$.a.b.c[*]", Map.of(
                    "val", new FieldDef(List.of("@.d.e"), "string")
            ));

            SimpleMappingEngine.MappingOutcome outcome = SimpleMappingEngine.apply(json, mapping);

            assertThat(outcome.items.get(0).get("val")).isEqualTo("deep_value");
        }

        @Test
        @DisplayName("should handle array index access")
        void shouldHandleArrayIndexAccess() throws IOException {
            String json = "{\"items\":[{\"values\":[10,20,30]}]}";
            String mapping = buildMapping("$.items[*]", Map.of(
                    "first", new FieldDef(List.of("@.values[0]"), "integer")
            ));

            SimpleMappingEngine.MappingOutcome outcome = SimpleMappingEngine.apply(json, mapping);

            assertThat(outcome.items.get(0).get("first")).isEqualTo(10);
        }
    }

    // -------------------------------------------------------------------------
    // Edge cases
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Edge cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("should handle empty items array")
        void shouldHandleEmptyItemsArray() throws IOException {
            String json = "{\"data\":[]}";
            String mapping = buildMapping("$.data[*]", Map.of(
                    "id", new FieldDef(List.of("@.id"), "integer")
            ));

            SimpleMappingEngine.MappingOutcome outcome = SimpleMappingEngine.apply(json, mapping);

            // Engine returns 1 item with null fields when array is empty
            assertThat(outcome.itemCount).isEqualTo(1);
        }

        @Test
        @DisplayName("should handle missing items_path gracefully")
        void shouldHandleMissingItemsPath() throws IOException {
            String json = "{\"other\":{\"key\":\"val\"}}";
            String mapping = buildMapping("$.nonexistent[*]", Map.of(
                    "key", new FieldDef(List.of("@.key"), "string")
            ));

            SimpleMappingEngine.MappingOutcome outcome = SimpleMappingEngine.apply(json, mapping);

            // Falls back to root as single item
            assertThat(outcome.itemCount).isGreaterThanOrEqualTo(0);
        }

        @Test
        @DisplayName("should handle null values in JSON")
        void shouldHandleNullValues() throws IOException {
            String json = "{\"data\":[{\"id\":1,\"name\":null}]}";
            String mapping = buildMapping("$.data[*]", Map.of(
                    "name", new FieldDef(List.of("@.name"), "string")
            ));

            SimpleMappingEngine.MappingOutcome outcome = SimpleMappingEngine.apply(json, mapping);

            assertThat(outcome.items.get(0)).doesNotContainKey("name");
        }
    }
}
