package com.apimarketplace.catalog.mapping.adapter;

import com.apimarketplace.catalog.mapping.dsl.SourceSpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for JsonAdapter class.
 *
 * JsonAdapter handles JSON format data for mapping operations, supporting
 * JSONPath-like expressions, array iteration, and value extraction.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("JsonAdapter")
class JsonAdapterTest {

    private JsonAdapter adapter;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        adapter = new JsonAdapter();
        objectMapper = new ObjectMapper();
        ReflectionTestUtils.setField(adapter, "objectMapper", objectMapper);
    }

    // ========================================================================
    // isCollection tests
    // ========================================================================

    @Nested
    @DisplayName("isCollection()")
    class IsCollectionTests {

        @Test
        @DisplayName("should return true for JSON array at root")
        void shouldReturnTrueForJsonArrayAtRoot() {
            byte[] input = "[{\"id\": 1}, {\"id\": 2}]".getBytes(StandardCharsets.UTF_8);
            SourceSpec spec = new SourceSpec();

            boolean result = adapter.isCollection(spec, input);

            assertTrue(result);
        }

        @Test
        @DisplayName("should return false for JSON object at root")
        void shouldReturnFalseForJsonObjectAtRoot() {
            byte[] input = "{\"key\": \"value\"}".getBytes(StandardCharsets.UTF_8);
            SourceSpec spec = new SourceSpec();

            boolean result = adapter.isCollection(spec, input);

            assertFalse(result);
        }

        @Test
        @DisplayName("should return true when root path points to array")
        void shouldReturnTrueWhenRootPathPointsToArray() {
            byte[] input = "{\"data\": [{\"id\": 1}, {\"id\": 2}]}".getBytes(StandardCharsets.UTF_8);
            SourceSpec spec = new SourceSpec();
            spec.setRoot("data");

            boolean result = adapter.isCollection(spec, input);

            assertTrue(result);
        }

        @Test
        @DisplayName("should return false when root path points to object")
        void shouldReturnFalseWhenRootPathPointsToObject() {
            byte[] input = "{\"data\": {\"id\": 1}}".getBytes(StandardCharsets.UTF_8);
            SourceSpec spec = new SourceSpec();
            spec.setRoot("data");

            boolean result = adapter.isCollection(spec, input);

            assertFalse(result);
        }

        @Test
        @DisplayName("should return false for invalid JSON")
        void shouldReturnFalseForInvalidJson() {
            byte[] input = "not valid json".getBytes(StandardCharsets.UTF_8);
            SourceSpec spec = new SourceSpec();

            boolean result = adapter.isCollection(spec, input);

            assertFalse(result);
        }
    }

    // ========================================================================
    // iterateItems tests
    // ========================================================================

    @Nested
    @DisplayName("iterateItems()")
    class IterateItemsTests {

        @Test
        @DisplayName("should iterate over JSON array items")
        void shouldIterateOverJsonArrayItems() {
            byte[] input = "[{\"id\": 1}, {\"id\": 2}, {\"id\": 3}]".getBytes(StandardCharsets.UTF_8);
            SourceSpec spec = new SourceSpec();

            Iterable<?> result = adapter.iterateItems(spec, input);

            int count = 0;
            for (Object item : result) {
                count++;
                assertNotNull(item);
            }
            assertEquals(3, count);
        }

        @Test
        @DisplayName("should iterate using items_path with wildcard")
        void shouldIterateUsingItemsPath() {
            // Use wildcard [*] to iterate over array elements
            byte[] input = "{\"response\": {\"items\": [{\"id\": 1}, {\"id\": 2}]}}".getBytes(StandardCharsets.UTF_8);
            SourceSpec spec = new SourceSpec();
            spec.setItemsPath("response.items[*]");

            Iterable<?> result = adapter.iterateItems(spec, input);

            int count = 0;
            for (Object item : result) {
                count++;
            }
            assertEquals(2, count);
        }

        @Test
        @DisplayName("should resolve root path to array node")
        void shouldIterateUsingRootPath() {
            // Root path resolves to the array itself (1 result containing the array)
            byte[] input = "{\"data\": [{\"name\": \"A\"}, {\"name\": \"B\"}]}".getBytes(StandardCharsets.UTF_8);
            SourceSpec spec = new SourceSpec();
            spec.setRoot("data");

            Iterable<?> result = adapter.iterateItems(spec, input);

            int count = 0;
            for (Object item : result) {
                count++;
                // Verify it's an array with 2 elements
                assertTrue(item instanceof JsonNode);
                assertTrue(((JsonNode) item).isArray());
                assertEquals(2, ((JsonNode) item).size());
            }
            assertEquals(1, count);
        }

        @Test
        @DisplayName("should return single item for non-array root")
        void shouldReturnSingleItemForNonArrayRoot() {
            byte[] input = "{\"id\": 1, \"name\": \"test\"}".getBytes(StandardCharsets.UTF_8);
            SourceSpec spec = new SourceSpec();

            Iterable<?> result = adapter.iterateItems(spec, input);

            int count = 0;
            for (Object item : result) {
                count++;
            }
            assertEquals(1, count);
        }

        @Test
        @DisplayName("should return empty list for invalid JSON")
        void shouldReturnEmptyListForInvalidJson() {
            byte[] input = "invalid".getBytes(StandardCharsets.UTF_8);
            SourceSpec spec = new SourceSpec();

            Iterable<?> result = adapter.iterateItems(spec, input);

            assertFalse(result.iterator().hasNext());
        }

        @Test
        @DisplayName("should use root alternatives when primary root path fails")
        void shouldUseRootAlternativesWhenPrimaryPathFails() {
            // When root path doesn't exist and no itemsPath, alternatives are checked
            byte[] input = "{\"results\": [{\"id\": 1}]}".getBytes(StandardCharsets.UTF_8);
            SourceSpec spec = new SourceSpec();
            // Set root to null/empty to trigger alternative check (non-empty root takes precedence)
            spec.setRoot(null);
            spec.setRootAlternatives(List.of("results[*]", "items[*]"));

            Iterable<?> result = adapter.iterateItems(spec, input);

            int count = 0;
            for (Object item : result) {
                count++;
            }
            assertEquals(1, count);
        }
    }

    // ========================================================================
    // evalScalar tests
    // ========================================================================

    @Nested
    @DisplayName("evalScalar()")
    class EvalScalarTests {

        @Test
        @DisplayName("should extract string value")
        void shouldExtractStringValue() throws Exception {
            JsonNode node = objectMapper.readTree("{\"name\": \"test\"}");

            Object result = adapter.evalScalar(node, "name");

            assertEquals("test", result);
        }

        @Test
        @DisplayName("should extract integer value")
        void shouldExtractIntegerValue() throws Exception {
            JsonNode node = objectMapper.readTree("{\"count\": 42}");

            Object result = adapter.evalScalar(node, "count");

            assertEquals(42, result);
        }

        @Test
        @DisplayName("should extract boolean value")
        void shouldExtractBooleanValue() throws Exception {
            JsonNode node = objectMapper.readTree("{\"active\": true}");

            Object result = adapter.evalScalar(node, "active");

            assertEquals(true, result);
        }

        @Test
        @DisplayName("should extract nested value using dot notation")
        void shouldExtractNestedValueUsingDotNotation() throws Exception {
            JsonNode node = objectMapper.readTree("{\"user\": {\"profile\": {\"name\": \"John\"}}}");

            Object result = adapter.evalScalar(node, "user.profile.name");

            assertEquals("John", result);
        }

        @Test
        @DisplayName("should extract array element by index")
        void shouldExtractArrayElementByIndex() throws Exception {
            JsonNode node = objectMapper.readTree("{\"items\": [\"a\", \"b\", \"c\"]}");

            Object result = adapter.evalScalar(node, "items[1]");

            assertEquals("b", result);
        }

        @Test
        @DisplayName("should return null for missing path")
        void shouldReturnNullForMissingPath() throws Exception {
            JsonNode node = objectMapper.readTree("{\"name\": \"test\"}");

            Object result = adapter.evalScalar(node, "missing");

            assertNull(result);
        }

        @Test
        @DisplayName("should return null for non-JsonNode context")
        void shouldReturnNullForNonJsonNodeContext() {
            Object result = adapter.evalScalar("not a node", "path");

            assertNull(result);
        }

        @Test
        @DisplayName("should return null for null context")
        void shouldReturnNullForNullContext() {
            Object result = adapter.evalScalar(null, "path");

            assertNull(result);
        }

        @Test
        @DisplayName("should handle relative path with @. prefix")
        void shouldHandleRelativePathWithAtPrefix() throws Exception {
            JsonNode node = objectMapper.readTree("{\"name\": \"test\"}");

            Object result = adapter.evalScalar(node, "@.name");

            assertEquals("test", result);
        }
    }

    // ========================================================================
    // evalNodes tests
    // ========================================================================

    @Nested
    @DisplayName("evalNodes()")
    class EvalNodesTests {

        @Test
        @DisplayName("should return array nodes")
        void shouldReturnArrayNodes() throws Exception {
            JsonNode node = objectMapper.readTree("{\"items\": [{\"id\": 1}, {\"id\": 2}]}");

            Iterable<?> result = adapter.evalNodes(node, "items");

            int count = 0;
            for (Object item : result) {
                count++;
            }
            assertEquals(2, count);
        }

        @Test
        @DisplayName("should wrap single node in list")
        void shouldWrapSingleNodeInList() throws Exception {
            JsonNode node = objectMapper.readTree("{\"data\": {\"id\": 1}}");

            Iterable<?> result = adapter.evalNodes(node, "data");

            int count = 0;
            for (Object item : result) {
                count++;
            }
            assertEquals(1, count);
        }

        @Test
        @DisplayName("should return empty list for non-JsonNode context")
        void shouldReturnEmptyListForNonJsonNodeContext() {
            Iterable<?> result = adapter.evalNodes("not a node", "path");

            assertFalse(result.iterator().hasNext());
        }
    }

    // ========================================================================
    // flatten tests
    // ========================================================================

    @Nested
    @DisplayName("flatten()")
    class FlattenTests {

        @Test
        @DisplayName("should flatten simple object")
        void shouldFlattenSimpleObject() {
            byte[] input = "{\"name\": \"test\", \"count\": 5}".getBytes(StandardCharsets.UTF_8);

            Map<String, Object> result = adapter.flatten(input);

            assertEquals("test", result.get("name"));
            assertEquals(5, result.get("count"));
        }

        @Test
        @DisplayName("should flatten nested object")
        void shouldFlattenNestedObject() {
            byte[] input = "{\"user\": {\"name\": \"John\", \"age\": 30}}".getBytes(StandardCharsets.UTF_8);

            Map<String, Object> result = adapter.flatten(input);

            assertEquals("John", result.get("user.name"));
            assertEquals(30, result.get("user.age"));
        }

        @Test
        @DisplayName("should flatten array with indices")
        void shouldFlattenArrayWithIndices() {
            byte[] input = "{\"items\": [\"a\", \"b\", \"c\"]}".getBytes(StandardCharsets.UTF_8);

            Map<String, Object> result = adapter.flatten(input);

            assertEquals("a", result.get("items[0]"));
            assertEquals("b", result.get("items[1]"));
            assertEquals("c", result.get("items[2]"));
        }

        @Test
        @DisplayName("should flatten boolean values")
        void shouldFlattenBooleanValues() {
            byte[] input = "{\"active\": true, \"verified\": false}".getBytes(StandardCharsets.UTF_8);

            Map<String, Object> result = adapter.flatten(input);

            assertEquals(true, result.get("active"));
            assertEquals(false, result.get("verified"));
        }

        @Test
        @DisplayName("should flatten null values")
        void shouldFlattenNullValues() {
            byte[] input = "{\"value\": null}".getBytes(StandardCharsets.UTF_8);

            Map<String, Object> result = adapter.flatten(input);

            assertTrue(result.containsKey("value"));
            assertNull(result.get("value"));
        }

        @Test
        @DisplayName("should return empty map for invalid JSON")
        void shouldReturnEmptyMapForInvalidJson() {
            byte[] input = "invalid json".getBytes(StandardCharsets.UTF_8);

            Map<String, Object> result = adapter.flatten(input);

            assertTrue(result.isEmpty());
        }
    }

    // ========================================================================
    // getRoot tests
    // ========================================================================

    @Nested
    @DisplayName("getRoot()")
    class GetRootTests {

        @Test
        @DisplayName("should return JsonNode for valid JSON")
        void shouldReturnJsonNodeForValidJson() {
            byte[] input = "{\"key\": \"value\"}".getBytes(StandardCharsets.UTF_8);

            Object result = adapter.getRoot(input);

            assertNotNull(result);
            assertTrue(result instanceof JsonNode);
        }

        @Test
        @DisplayName("should return null for invalid JSON")
        void shouldReturnNullForInvalidJson() {
            byte[] input = "not valid json".getBytes(StandardCharsets.UTF_8);

            Object result = adapter.getRoot(input);

            assertNull(result);
        }

        @Test
        @DisplayName("should return JsonNode for JSON array")
        void shouldReturnJsonNodeForJsonArray() {
            byte[] input = "[1, 2, 3]".getBytes(StandardCharsets.UTF_8);

            Object result = adapter.getRoot(input);

            assertNotNull(result);
            assertTrue(result instanceof JsonNode);
            assertTrue(((JsonNode) result).isArray());
        }
    }

    // ========================================================================
    // Complex path tests
    // ========================================================================

    @Nested
    @DisplayName("Complex paths")
    class ComplexPathTests {

        @Test
        @DisplayName("should handle wildcard array access")
        void shouldHandleWildcardArrayAccess() {
            byte[] input = "{\"users\": [{\"name\": \"A\"}, {\"name\": \"B\"}]}".getBytes(StandardCharsets.UTF_8);
            SourceSpec spec = new SourceSpec();
            spec.setItemsPath("users[*]");

            Iterable<?> result = adapter.iterateItems(spec, input);

            int count = 0;
            for (Object item : result) {
                count++;
            }
            assertEquals(2, count);
        }

        @Test
        @DisplayName("should handle deeply nested paths")
        void shouldHandleDeeplyNestedPaths() throws Exception {
            JsonNode node = objectMapper.readTree("{\"a\": {\"b\": {\"c\": {\"d\": \"deep\"}}}}");

            Object result = adapter.evalScalar(node, "a.b.c.d");

            assertEquals("deep", result);
        }

        @Test
        @DisplayName("should handle mixed array and object paths")
        void shouldHandleMixedArrayAndObjectPaths() throws Exception {
            JsonNode node = objectMapper.readTree("{\"data\": [{\"users\": [{\"name\": \"John\"}]}]}");

            Object result = adapter.evalScalar(node, "data[0].users[0].name");

            assertEquals("John", result);
        }
    }

    // ========================================================================
    // Edge cases tests
    // ========================================================================

    @Nested
    @DisplayName("Edge cases")
    class EdgeCasesTests {

        @Test
        @DisplayName("should handle empty JSON object")
        void shouldHandleEmptyJsonObject() {
            byte[] input = "{}".getBytes(StandardCharsets.UTF_8);

            Map<String, Object> result = adapter.flatten(input);

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should handle empty JSON array")
        void shouldHandleEmptyJsonArray() {
            byte[] input = "[]".getBytes(StandardCharsets.UTF_8);

            Map<String, Object> result = adapter.flatten(input);

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should handle special characters in values")
        void shouldHandleSpecialCharactersInValues() {
            byte[] input = "{\"text\": \"Hello\\nWorld\\t!\"}".getBytes(StandardCharsets.UTF_8);

            Map<String, Object> result = adapter.flatten(input);

            assertEquals("Hello\nWorld\t!", result.get("text"));
        }

        @Test
        @DisplayName("should handle numeric keys")
        void shouldHandleNumericKeys() {
            byte[] input = "{\"123\": \"value\"}".getBytes(StandardCharsets.UTF_8);

            Map<String, Object> result = adapter.flatten(input);

            assertEquals("value", result.get("123"));
        }

        @Test
        @DisplayName("should handle long integers")
        void shouldHandleLongIntegers() {
            byte[] input = "{\"bigNum\": 9223372036854775807}".getBytes(StandardCharsets.UTF_8);

            Map<String, Object> result = adapter.flatten(input);

            assertEquals(9223372036854775807L, result.get("bigNum"));
        }

        @Test
        @DisplayName("should handle floating point numbers")
        void shouldHandleFloatingPointNumbers() {
            byte[] input = "{\"decimal\": 3.14159}".getBytes(StandardCharsets.UTF_8);

            Map<String, Object> result = adapter.flatten(input);

            assertEquals(3.14159, (Double) result.get("decimal"), 0.00001);
        }
    }
}
