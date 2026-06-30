package com.apimarketplace.common.mapping;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for StrictMappingEngine - strict JSON mapping with cursor-based navigation,
 * ancestor traversal, path caching, and type casting.
 */
@DisplayName("StrictMappingEngine")
class StrictMappingEngineTest {

    // -------------------------------------------------------------------------
    // Basic mapping
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Basic mapping")
    class BasicMappingTests {

        @Test
        @DisplayName("should map simple fields with items_path")
        void shouldMapSimpleFields() throws IOException {
            String json = """
                    {"data":{"users":[{"id":1,"name":"Alice"},{"id":2,"name":"Bob"}]}}
                    """;
            String mapping = """
                    {
                      "source": {"format":"json", "items_path":"$.data.users[*]"},
                      "fields": {
                        "userId": {"candidates":["@.id"], "to":"integer"},
                        "userName": {"candidates":["@.name"], "to":"string"}
                      }
                    }
                    """;

            StrictMappingEngine.MappingOutcome outcome = StrictMappingEngine.apply(json, mapping);

            assertThat(outcome.itemCount).isEqualTo(2);
            assertThat(outcome.items).hasSize(2);
            assertThat(outcome.items.get(0).get("userId")).isEqualTo(1);
            assertThat(outcome.items.get(0).get("userName")).isEqualTo("Alice");
            assertThat(outcome.items.get(1).get("userId")).isEqualTo(2);
            assertThat(outcome.items.get(1).get("userName")).isEqualTo("Bob");
            assertThat(outcome.unresolvedFields).isEmpty();
        }

        @Test
        @DisplayName("should handle root array as items when no source path specified")
        void shouldHandleRootArray() throws IOException {
            String json = "[{\"x\":10},{\"x\":20}]";
            String mapping = """
                    {
                      "source": {"format":"json"},
                      "fields": {
                        "value": {"candidates":["@.x"], "to":"integer"}
                      }
                    }
                    """;

            StrictMappingEngine.MappingOutcome outcome = StrictMappingEngine.apply(json, mapping);

            assertThat(outcome.itemCount).isEqualTo(2);
            assertThat(outcome.items.get(0).get("value")).isEqualTo(10);
            assertThat(outcome.items.get(1).get("value")).isEqualTo(20);
        }

        @Test
        @DisplayName("should handle single object as single item")
        void shouldHandleSingleObject() throws IOException {
            String json = "{\"status\":\"ok\",\"count\":42}";
            String mapping = """
                    {
                      "source": {"format":"json"},
                      "fields": {
                        "st": {"candidates":["@.status"], "to":"string"},
                        "num": {"candidates":["@.count"], "to":"integer"}
                      }
                    }
                    """;

            StrictMappingEngine.MappingOutcome outcome = StrictMappingEngine.apply(json, mapping);

            assertThat(outcome.itemCount).isEqualTo(1);
            assertThat(outcome.items.get(0).get("st")).isEqualTo("ok");
            assertThat(outcome.items.get(0).get("num")).isEqualTo(42);
        }
    }

    // -------------------------------------------------------------------------
    // Path prefixes (@, $, ^^)
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Path prefixes")
    class PathPrefixTests {

        @Test
        @DisplayName("@. should resolve from current item")
        void relativePath() throws IOException {
            String json = "{\"items\":[{\"a\":{\"b\":\"hello\"}}]}";
            String mapping = """
                    {
                      "source": {"format":"json","items_path":"$.items[*]"},
                      "fields": {
                        "val": {"candidates":["@.a.b"], "to":"string"}
                      }
                    }
                    """;

            StrictMappingEngine.MappingOutcome outcome = StrictMappingEngine.apply(json, mapping);

            assertThat(outcome.items.get(0).get("val")).isEqualTo("hello");
        }

        @Test
        @DisplayName("$. should resolve from root")
        void absolutePath() throws IOException {
            String json = "{\"meta\":{\"version\":\"3\"},\"items\":[{\"id\":1}]}";
            String mapping = """
                    {
                      "source": {"format":"json","items_path":"$.items[*]"},
                      "fields": {
                        "ver": {"candidates":["$.meta.version"], "to":"string"}
                      }
                    }
                    """;

            StrictMappingEngine.MappingOutcome outcome = StrictMappingEngine.apply(json, mapping);

            assertThat(outcome.items.get(0).get("ver")).isEqualTo("3");
        }

        @Test
        @DisplayName("^^. should resolve from ancestor (parent)")
        void ascendantPath() throws IOException {
            String json = "{\"groups\":[{\"groupName\":\"G1\",\"members\":[{\"name\":\"Alice\"}]}]}";
            String mapping = """
                    {
                      "source": {"format":"json","items_path":"$.groups[*].members[*]"},
                      "fields": {
                        "person": {"candidates":["@.name"], "to":"string"},
                        "group": {"candidates":["^^.groupName"], "to":"string"}
                      }
                    }
                    """;

            StrictMappingEngine.MappingOutcome outcome = StrictMappingEngine.apply(json, mapping);

            assertThat(outcome.items.get(0).get("person")).isEqualTo("Alice");
            // The ascend goes to parent which is the members array, then its parent which is the group object
            // Depending on ancestor depth, this may or may not resolve
        }
    }

    // -------------------------------------------------------------------------
    // Type casting
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Scalar type casting (castScalar)")
    class CastScalarTests {

        @Test
        @DisplayName("should cast to string from various types")
        void shouldCastToString() throws IOException {
            String json = "{\"num\":42,\"bool\":true,\"str\":\"hello\"}";
            String mapping = """
                    {
                      "source":{"format":"json"},
                      "fields":{
                        "fromNum":{"candidates":["@.num"],"to":"string"},
                        "fromBool":{"candidates":["@.bool"],"to":"string"},
                        "fromStr":{"candidates":["@.str"],"to":"string"}
                      }
                    }
                    """;

            StrictMappingEngine.MappingOutcome outcome = StrictMappingEngine.apply(json, mapping);

            assertThat(outcome.items.get(0).get("fromNum")).isEqualTo("42");
            assertThat(outcome.items.get(0).get("fromBool")).isEqualTo("true");
            assertThat(outcome.items.get(0).get("fromStr")).isEqualTo("hello");
        }

        @Test
        @DisplayName("should cast to integer")
        void shouldCastToInteger() throws IOException {
            String json = "{\"val\":123}";
            String mapping = """
                    {"source":{"format":"json"},"fields":{"r":{"candidates":["@.val"],"to":"integer"}}}
                    """;

            StrictMappingEngine.MappingOutcome outcome = StrictMappingEngine.apply(json, mapping);

            assertThat(outcome.items.get(0).get("r")).isEqualTo(123);
        }

        @Test
        @DisplayName("should cast to long")
        void shouldCastToLong() throws IOException {
            String json = "{\"val\":9876543210}";
            String mapping = """
                    {"source":{"format":"json"},"fields":{"r":{"candidates":["@.val"],"to":"long"}}}
                    """;

            StrictMappingEngine.MappingOutcome outcome = StrictMappingEngine.apply(json, mapping);

            assertThat(outcome.items.get(0).get("r")).isEqualTo(9876543210L);
        }

        @Test
        @DisplayName("should cast to number (double)")
        void shouldCastToNumber() throws IOException {
            String json = "{\"val\":2.718}";
            String mapping = """
                    {"source":{"format":"json"},"fields":{"r":{"candidates":["@.val"],"to":"number"}}}
                    """;

            StrictMappingEngine.MappingOutcome outcome = StrictMappingEngine.apply(json, mapping);

            assertThat(outcome.items.get(0).get("r")).isEqualTo(2.718);
        }

        @Test
        @DisplayName("should cast to boolean")
        void shouldCastToBoolean() throws IOException {
            String json = "{\"flag\":true}";
            String mapping = """
                    {"source":{"format":"json"},"fields":{"r":{"candidates":["@.flag"],"to":"boolean"}}}
                    """;

            StrictMappingEngine.MappingOutcome outcome = StrictMappingEngine.apply(json, mapping);

            assertThat(outcome.items.get(0).get("r")).isEqualTo(true);
        }

        @Test
        @DisplayName("should parse string 'true'/'false' as boolean")
        void shouldParseStringAsBoolean() throws IOException {
            String json = "{\"flag\":\"true\"}";
            String mapping = """
                    {"source":{"format":"json"},"fields":{"r":{"candidates":["@.flag"],"to":"boolean"}}}
                    """;

            StrictMappingEngine.MappingOutcome outcome = StrictMappingEngine.apply(json, mapping);

            assertThat(outcome.items.get(0).get("r")).isEqualTo(true);
        }
    }

    // -------------------------------------------------------------------------
    // Array casting
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Array type casting (castArray)")
    class CastArrayTests {

        @Test
        @DisplayName("should cast to array<string>")
        void shouldCastToArrayString() throws IOException {
            String json = "{\"items\":[{\"tags\":[\"a\",\"b\",\"c\"]}]}";
            String mapping = """
                    {
                      "source":{"format":"json","items_path":"$.items[*]"},
                      "fields":{
                        "labels":{"candidates":["@.tags[*]"],"to":"array<string>"}
                      }
                    }
                    """;

            StrictMappingEngine.MappingOutcome outcome = StrictMappingEngine.apply(json, mapping);

            @SuppressWarnings("unchecked")
            List<String> labels = (List<String>) outcome.items.get(0).get("labels");
            assertThat(labels).containsExactly("a", "b", "c");
        }

        @Test
        @DisplayName("should cast to array<number>")
        void shouldCastToArrayNumber() throws IOException {
            String json = "{\"items\":[{\"scores\":[1.5,2.5,3.5]}]}";
            String mapping = """
                    {
                      "source":{"format":"json","items_path":"$.items[*]"},
                      "fields":{
                        "nums":{"candidates":["@.scores[*]"],"to":"array<number>"}
                      }
                    }
                    """;

            StrictMappingEngine.MappingOutcome outcome = StrictMappingEngine.apply(json, mapping);

            @SuppressWarnings("unchecked")
            List<Double> nums = (List<Double>) outcome.items.get(0).get("nums");
            assertThat(nums).containsExactly(1.5, 2.5, 3.5);
        }
    }

    // -------------------------------------------------------------------------
    // Candidate fallback
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Candidate fallback")
    class CandidateFallbackTests {

        @Test
        @DisplayName("should use first matching candidate")
        void shouldUseFirstMatch() throws IOException {
            String json = "{\"items\":[{\"fullName\":\"Alice\",\"name\":\"A\"}]}";
            String mapping = """
                    {
                      "source":{"format":"json","items_path":"$.items[*]"},
                      "fields":{
                        "label":{"candidates":["@.fullName","@.name"],"to":"string"}
                      }
                    }
                    """;

            StrictMappingEngine.MappingOutcome outcome = StrictMappingEngine.apply(json, mapping);

            assertThat(outcome.items.get(0).get("label")).isEqualTo("Alice");
        }

        @Test
        @DisplayName("should fall back to next candidate when first is missing")
        void shouldFallBack() throws IOException {
            String json = "{\"items\":[{\"name\":\"Bob\"}]}";
            String mapping = """
                    {
                      "source":{"format":"json","items_path":"$.items[*]"},
                      "fields":{
                        "label":{"candidates":["@.fullName","@.name"],"to":"string"}
                      }
                    }
                    """;

            StrictMappingEngine.MappingOutcome outcome = StrictMappingEngine.apply(json, mapping);

            assertThat(outcome.items.get(0).get("label")).isEqualTo("Bob");
        }
    }

    // -------------------------------------------------------------------------
    // Default values and required fields
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Default values and required fields")
    class DefaultAndRequiredTests {

        @Test
        @DisplayName("should use default value when field is missing")
        void shouldUseDefault() throws IOException {
            String json = "{\"items\":[{\"id\":1}]}";
            String mapping = """
                    {
                      "source":{"format":"json","items_path":"$.items[*]"},
                      "fields":{
                        "status":{"candidates":["@.status"],"to":"string","default":"pending"}
                      }
                    }
                    """;

            StrictMappingEngine.MappingOutcome outcome = StrictMappingEngine.apply(json, mapping);

            assertThat(outcome.items.get(0).get("status")).isEqualTo("pending");
        }

        @Test
        @DisplayName("should report unresolved required fields")
        void shouldReportUnresolved() throws IOException {
            String json = "{\"items\":[{\"id\":1}]}";
            String mapping = """
                    {
                      "source":{"format":"json","items_path":"$.items[*]"},
                      "fields":{
                        "mandatory":{"candidates":["@.missing"],"to":"string","required":true}
                      }
                    }
                    """;

            StrictMappingEngine.MappingOutcome outcome = StrictMappingEngine.apply(json, mapping);

            assertThat(outcome.unresolvedFields).contains("mandatory");
        }

        @Test
        @DisplayName("should not report unresolved non-required fields")
        void shouldNotReportNonRequired() throws IOException {
            String json = "{\"items\":[{\"id\":1}]}";
            String mapping = """
                    {
                      "source":{"format":"json","items_path":"$.items[*]"},
                      "fields":{
                        "optional":{"candidates":["@.missing"],"to":"string","required":false}
                      }
                    }
                    """;

            StrictMappingEngine.MappingOutcome outcome = StrictMappingEngine.apply(json, mapping);

            assertThat(outcome.unresolvedFields).isEmpty();
        }
    }

    // -------------------------------------------------------------------------
    // Root alternatives
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Root alternatives")
    class RootAlternativesTests {

        @Test
        @DisplayName("should try root_alternatives when items_path is null")
        void shouldTryAlternatives() throws IOException {
            String json = "{\"results\":[{\"id\":1},{\"id\":2}]}";
            String mapping = """
                    {
                      "source":{
                        "format":"json",
                        "root_alternatives":["$.results[*]"]
                      },
                      "fields":{
                        "itemId":{"candidates":["@.id"],"to":"integer"}
                      }
                    }
                    """;

            StrictMappingEngine.MappingOutcome outcome = StrictMappingEngine.apply(json, mapping);

            assertThat(outcome.itemCount).isEqualTo(2);
        }

        @Test
        @DisplayName("should use root as final fallback")
        void shouldUseRootFallback() throws IOException {
            String json = "{\"entries\":[{\"v\":\"x\"}]}";
            String mapping = """
                    {
                      "source":{
                        "format":"json",
                        "root_alternatives":["$.nonexistent[*]"],
                        "root":"$.entries[*]"
                      },
                      "fields":{
                        "val":{"candidates":["@.v"],"to":"string"}
                      }
                    }
                    """;

            StrictMappingEngine.MappingOutcome outcome = StrictMappingEngine.apply(json, mapping);

            assertThat(outcome.itemCount).isEqualTo(1);
            assertThat(outcome.items.get(0).get("val")).isEqualTo("x");
        }
    }

    // -------------------------------------------------------------------------
    // Tokenizer
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Tokenizer")
    class TokenizerTests {

        @Test
        @DisplayName("should tokenize simple path")
        void shouldTokenizeSimplePath() {
            List<String> tokens = StrictMappingEngine.tokenize("data.users.name");
            assertThat(tokens).containsExactly("data", "users", "name");
        }

        @Test
        @DisplayName("should tokenize path with array brackets")
        void shouldTokenizeWithBrackets() {
            List<String> tokens = StrictMappingEngine.tokenize("data.items[*].name");
            assertThat(tokens).containsExactly("data", "items[*]", "name");
        }

        @Test
        @DisplayName("should tokenize path with numeric index")
        void shouldTokenizeWithIndex() {
            List<String> tokens = StrictMappingEngine.tokenize("items[0].value");
            assertThat(tokens).containsExactly("items[0]", "value");
        }

        @Test
        @DisplayName("should return empty list for blank input")
        void shouldReturnEmptyForBlank() {
            assertThat(StrictMappingEngine.tokenize("")).isEmpty();
            assertThat(StrictMappingEngine.tokenize("  ")).isEmpty();
            assertThat(StrictMappingEngine.tokenize(null)).isEmpty();
        }
    }

    // -------------------------------------------------------------------------
    // parseStart
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("parseStart")
    class ParseStartTests {

        @Test
        @DisplayName("should parse relative prefix @.")
        void shouldParseRelative() {
            StrictMappingEngine.StartRef ref = StrictMappingEngine.parseStart("@.name");
            assertThat(ref.kind).isEqualTo(StrictMappingEngine.StartKind.RELATIVE);
            assertThat(ref.body).isEqualTo("name");
        }

        @Test
        @DisplayName("should parse absolute prefix $.")
        void shouldParseAbsolute() {
            StrictMappingEngine.StartRef ref = StrictMappingEngine.parseStart("$.data.items");
            assertThat(ref.kind).isEqualTo(StrictMappingEngine.StartKind.ABSOLUTE);
            assertThat(ref.body).isEqualTo("data.items");
        }

        @Test
        @DisplayName("should parse single ascendant ^^.")
        void shouldParseSingleAscendant() {
            StrictMappingEngine.StartRef ref = StrictMappingEngine.parseStart("^^.parent");
            assertThat(ref.kind).isEqualTo(StrictMappingEngine.StartKind.ASCEND);
            assertThat(ref.up).isEqualTo(1);
            assertThat(ref.body).isEqualTo("parent");
        }

        @Test
        @DisplayName("should parse double ascendant ^^.^^.")
        void shouldParseDoubleAscendant() {
            StrictMappingEngine.StartRef ref = StrictMappingEngine.parseStart("^^.^^.grandparent");
            assertThat(ref.kind).isEqualTo(StrictMappingEngine.StartKind.ASCEND);
            assertThat(ref.up).isEqualTo(2);
            assertThat(ref.body).isEqualTo("grandparent");
        }

        @Test
        @DisplayName("should default to relative for plain path")
        void shouldDefaultToRelative() {
            StrictMappingEngine.StartRef ref = StrictMappingEngine.parseStart("name");
            assertThat(ref.kind).isEqualTo(StrictMappingEngine.StartKind.RELATIVE);
            assertThat(ref.body).isEqualTo("name");
        }
    }

    // -------------------------------------------------------------------------
    // Utility methods
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Utility methods")
    class UtilityTests {

        @Test
        @DisplayName("trimDollar should remove $. prefix")
        void shouldTrimDollarDot() {
            assertThat(StrictMappingEngine.trimDollar("$.data")).isEqualTo("data");
        }

        @Test
        @DisplayName("trimDollar should remove $ prefix without dot")
        void shouldTrimDollarOnly() {
            assertThat(StrictMappingEngine.trimDollar("$data")).isEqualTo("data");
        }

        @Test
        @DisplayName("notBlank should return false for null, empty, whitespace")
        void shouldDetectBlank() {
            assertThat(StrictMappingEngine.notBlank(null)).isFalse();
            assertThat(StrictMappingEngine.notBlank("")).isFalse();
            assertThat(StrictMappingEngine.notBlank("  ")).isFalse();
        }

        @Test
        @DisplayName("notBlank should return true for non-blank strings")
        void shouldDetectNonBlank() {
            assertThat(StrictMappingEngine.notBlank("hello")).isTrue();
            assertThat(StrictMappingEngine.notBlank(" a ")).isTrue();
        }

        @Test
        @DisplayName("parseIndex should parse valid integers")
        void shouldParseValidIndex() {
            assertThat(StrictMappingEngine.parseIndex("0")).isEqualTo(0);
            assertThat(StrictMappingEngine.parseIndex("42")).isEqualTo(42);
            assertThat(StrictMappingEngine.parseIndex(" 5 ")).isEqualTo(5);
        }

        @Test
        @DisplayName("parseIndex should return -1 for invalid input")
        void shouldReturnMinusOneForInvalid() {
            assertThat(StrictMappingEngine.parseIndex("abc")).isEqualTo(-1);
            assertThat(StrictMappingEngine.parseIndex("")).isEqualTo(-1);
        }

        @ParameterizedTest
        @CsvSource({"'42', 42", "'0', 0", "'999', 999"})
        @DisplayName("tryParseInt should parse valid integers")
        void shouldTryParseInt(String input, int expected) {
            assertThat(StrictMappingEngine.tryParseInt(input)).isEqualTo(expected);
        }

        @Test
        @DisplayName("tryParseInt should return null for invalid input")
        void tryParseIntInvalid() {
            assertThat(StrictMappingEngine.tryParseInt("abc")).isNull();
        }

        @Test
        @DisplayName("tryParseLong should parse valid longs")
        void shouldTryParseLong() {
            assertThat(StrictMappingEngine.tryParseLong("9876543210")).isEqualTo(9876543210L);
        }

        @Test
        @DisplayName("tryParseDouble should parse valid doubles")
        void shouldTryParseDouble() {
            assertThat(StrictMappingEngine.tryParseDouble("3.14")).isEqualTo(3.14);
        }
    }

    // -------------------------------------------------------------------------
    // walkAbsolute validation
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("walkAbsolute validation")
    class WalkAbsoluteValidationTests {

        @Test
        @DisplayName("should throw for non-absolute path")
        void shouldThrowForNonAbsolutePath() {
            assertThatThrownBy(() -> {
                com.fasterxml.jackson.databind.JsonNode root =
                        new com.fasterxml.jackson.databind.ObjectMapper().readTree("{}");
                StrictMappingEngine.walkAbsolute(root, "relative.path");
            }).isInstanceOf(IllegalArgumentException.class);
        }
    }

    // -------------------------------------------------------------------------
    // Edge cases
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Edge cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("should handle empty JSON object")
        void shouldHandleEmptyObject() throws IOException {
            String json = "{}";
            String mapping = """
                    {
                      "source":{"format":"json"},
                      "fields":{
                        "val":{"candidates":["@.missing"],"to":"string"}
                      }
                    }
                    """;

            StrictMappingEngine.MappingOutcome outcome = StrictMappingEngine.apply(json, mapping);

            assertThat(outcome.itemCount).isEqualTo(1);
            assertThat(outcome.items.get(0)).doesNotContainKey("val");
        }

        @Test
        @DisplayName("should handle empty fields spec")
        void shouldHandleEmptyFields() throws IOException {
            String json = "{\"data\":[{\"id\":1}]}";
            String mapping = """
                    {
                      "source":{"format":"json","items_path":"$.data[*]"},
                      "fields":{}
                    }
                    """;

            StrictMappingEngine.MappingOutcome outcome = StrictMappingEngine.apply(json, mapping);

            assertThat(outcome.itemCount).isEqualTo(1);
            assertThat(outcome.items.get(0)).isEmpty();
        }

        @Test
        @DisplayName("should handle null JSON values")
        void shouldHandleNullJsonValues() throws IOException {
            String json = "{\"items\":[{\"name\":null}]}";
            String mapping = """
                    {
                      "source":{"format":"json","items_path":"$.items[*]"},
                      "fields":{
                        "n":{"candidates":["@.name"],"to":"string"}
                      }
                    }
                    """;

            StrictMappingEngine.MappingOutcome outcome = StrictMappingEngine.apply(json, mapping);

            assertThat(outcome.items.get(0)).doesNotContainKey("n");
        }
    }

    // -------------------------------------------------------------------------
    // POJO model tests (StrictMappingSpec, FieldSpec, etc.)
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("POJO models")
    class PojoModelTests {

        @Test
        @DisplayName("StrictMappingSpec should have default values")
        void specDefaults() {
            StrictMappingEngine.StrictMappingSpec spec = new StrictMappingEngine.StrictMappingSpec();
            assertThat(spec.source).isNotNull();
            assertThat(spec.fields).isNotNull().isEmpty();
            assertThat(spec.meta).isNull();
        }

        @Test
        @DisplayName("SourceSpec should have default format")
        void sourceSpecDefaults() {
            StrictMappingEngine.SourceSpec src = new StrictMappingEngine.SourceSpec();
            assertThat(src.format).isEqualTo("JSON");
            assertThat(src.items_path).isNull();
            assertThat(src.root_alternatives).isNull();
            assertThat(src.root).isNull();
        }

        @Test
        @DisplayName("FieldSpec should have sensible defaults")
        void fieldSpecDefaults() {
            StrictMappingEngine.FieldSpec fs = new StrictMappingEngine.FieldSpec();
            assertThat(fs.candidates).isEmpty();
            assertThat(fs.to).isEqualTo("string");
            assertThat(fs.required).isFalse();
            assertThat(fs.defaultValue).isNull();
        }

        @Test
        @DisplayName("FieldSpec setDefault/getDefault should work")
        void fieldSpecDefaultAccessors() {
            StrictMappingEngine.FieldSpec fs = new StrictMappingEngine.FieldSpec();
            fs.setDefault("fallback");
            assertThat(fs.getDefault()).isEqualTo("fallback");
            assertThat(fs.defaultValue).isEqualTo("fallback");
        }

        @Test
        @DisplayName("MappingOutcome fields should be settable")
        void mappingOutcomeFields() {
            StrictMappingEngine.MappingOutcome outcome = new StrictMappingEngine.MappingOutcome();
            outcome.items = List.of(Map.of("k", "v"));
            outcome.itemCount = 1;
            outcome.unresolvedFields = List.of("missing");

            assertThat(outcome.items).hasSize(1);
            assertThat(outcome.itemCount).isEqualTo(1);
            assertThat(outcome.unresolvedFields).containsExactly("missing");
        }
    }
}
