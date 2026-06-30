package com.apimarketplace.catalog.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for JsonSkeletonGenerator.
 *
 * JsonSkeletonGenerator creates a structural skeleton of JSON documents,
 * representing the schema with type annotations (_t) and merged array schemas.
 */
@DisplayName("JsonSkeletonGenerator")
class JsonSkeletonGeneratorTest {

    private JsonSkeletonGenerator generator;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        generator = new JsonSkeletonGenerator();
        mapper = new ObjectMapper();
    }

    // ========================================================================
    // Primitive Types
    // ========================================================================

    @Nested
    @DisplayName("Primitive Types")
    class PrimitiveTypesTests {

        @Test
        @DisplayName("should return 'string' for text node")
        void shouldReturnStringForTextNode() {
            // Arrange
            JsonNode input = mapper.valueToTree("hello world");

            // Act
            JsonNode result = generator.generateSkeleton(input);

            // Assert
            assertTrue(result.isTextual());
            assertEquals("string", result.asText());
        }

        @Test
        @DisplayName("should return 'number' for integer node")
        void shouldReturnNumberForIntegerNode() {
            // Arrange
            JsonNode input = mapper.valueToTree(42);

            // Act
            JsonNode result = generator.generateSkeleton(input);

            // Assert
            assertTrue(result.isTextual());
            assertEquals("number", result.asText());
        }

        @Test
        @DisplayName("should return 'number' for decimal node")
        void shouldReturnNumberForDecimalNode() {
            // Arrange
            JsonNode input = mapper.valueToTree(3.14159);

            // Act
            JsonNode result = generator.generateSkeleton(input);

            // Assert
            assertTrue(result.isTextual());
            assertEquals("number", result.asText());
        }

        @Test
        @DisplayName("should return 'boolean' for boolean node")
        void shouldReturnBooleanForBooleanNode() {
            // Arrange
            JsonNode input = mapper.valueToTree(true);

            // Act
            JsonNode result = generator.generateSkeleton(input);

            // Assert
            assertTrue(result.isTextual());
            assertEquals("boolean", result.asText());
        }

        @Test
        @DisplayName("should return 'null' for null input")
        void shouldReturnNullForNullInput() {
            // Act
            JsonNode result = generator.generateSkeleton(null);

            // Assert
            assertTrue(result.isTextual());
            assertEquals("null", result.asText());
        }

        @Test
        @DisplayName("should return 'null' for JSON null node")
        void shouldReturnNullForJsonNullNode() {
            // Arrange
            JsonNode input = mapper.nullNode();

            // Act
            JsonNode result = generator.generateSkeleton(input);

            // Assert
            assertTrue(result.isTextual());
            assertEquals("null", result.asText());
        }
    }

    // ========================================================================
    // Object Types
    // ========================================================================

    @Nested
    @DisplayName("Object Types")
    class ObjectTypesTests {

        @Test
        @DisplayName("should generate skeleton for simple object")
        void shouldGenerateSkeletonForSimpleObject() throws Exception {
            // Arrange
            String json = "{\"name\": \"John\", \"age\": 30}";
            JsonNode input = mapper.readTree(json);

            // Act
            JsonNode result = generator.generateSkeleton(input);

            // Assert
            assertTrue(result.isObject());
            assertEquals("obj", result.get("_t").asText());
            assertTrue(result.has("props"));

            JsonNode props = result.get("props");
            assertEquals("string", props.get("name").asText());
            assertEquals("number", props.get("age").asText());
        }

        @Test
        @DisplayName("should generate skeleton for nested object")
        void shouldGenerateSkeletonForNestedObject() throws Exception {
            // Arrange
            String json = "{\"user\": {\"name\": \"John\", \"address\": {\"city\": \"Paris\"}}}";
            JsonNode input = mapper.readTree(json);

            // Act
            JsonNode result = generator.generateSkeleton(input);

            // Assert
            assertEquals("obj", result.get("_t").asText());

            JsonNode userSkeleton = result.get("props").get("user");
            assertEquals("obj", userSkeleton.get("_t").asText());
            assertEquals("string", userSkeleton.get("props").get("name").asText());

            JsonNode addressSkeleton = userSkeleton.get("props").get("address");
            assertEquals("obj", addressSkeleton.get("_t").asText());
            assertEquals("string", addressSkeleton.get("props").get("city").asText());
        }

        @Test
        @DisplayName("should generate skeleton for empty object")
        void shouldGenerateSkeletonForEmptyObject() throws Exception {
            // Arrange
            String json = "{}";
            JsonNode input = mapper.readTree(json);

            // Act
            JsonNode result = generator.generateSkeleton(input);

            // Assert
            assertEquals("obj", result.get("_t").asText());
            assertTrue(result.has("props"));
            assertEquals(0, result.get("props").size());
        }
    }

    // ========================================================================
    // Array Types
    // ========================================================================

    @Nested
    @DisplayName("Array Types")
    class ArrayTypesTests {

        @Test
        @DisplayName("should generate skeleton for simple array of strings")
        void shouldGenerateSkeletonForSimpleArrayOfStrings() throws Exception {
            // Arrange
            String json = "[\"a\", \"b\", \"c\"]";
            JsonNode input = mapper.readTree(json);

            // Act
            JsonNode result = generator.generateSkeleton(input);

            // Assert
            assertEquals("arr", result.get("_t").asText());
            assertEquals("string", result.get("items").asText());
        }

        @Test
        @DisplayName("should generate skeleton for array of numbers")
        void shouldGenerateSkeletonForArrayOfNumbers() throws Exception {
            // Arrange
            String json = "[1, 2, 3, 4, 5]";
            JsonNode input = mapper.readTree(json);

            // Act
            JsonNode result = generator.generateSkeleton(input);

            // Assert
            assertEquals("arr", result.get("_t").asText());
            assertEquals("number", result.get("items").asText());
        }

        @Test
        @DisplayName("should generate skeleton for array of objects")
        void shouldGenerateSkeletonForArrayOfObjects() throws Exception {
            // Arrange
            String json = "[{\"id\": 1, \"name\": \"A\"}, {\"id\": 2, \"name\": \"B\"}]";
            JsonNode input = mapper.readTree(json);

            // Act
            JsonNode result = generator.generateSkeleton(input);

            // Assert
            assertEquals("arr", result.get("_t").asText());

            JsonNode items = result.get("items");
            assertEquals("obj", items.get("_t").asText());
            assertEquals("number", items.get("props").get("id").asText());
            assertEquals("string", items.get("props").get("name").asText());
        }

        @Test
        @DisplayName("should return 'empty' for empty array")
        void shouldReturnEmptyForEmptyArray() throws Exception {
            // Arrange
            String json = "[]";
            JsonNode input = mapper.readTree(json);

            // Act
            JsonNode result = generator.generateSkeleton(input);

            // Assert
            assertEquals("arr", result.get("_t").asText());
            assertEquals("empty", result.get("items").asText());
        }

        @Test
        @DisplayName("should merge array items with different properties")
        void shouldMergeArrayItemsWithDifferentProperties() throws Exception {
            // Arrange - array where objects have different keys
            String json = "[{\"a\": 1}, {\"b\": 2}, {\"a\": 3, \"c\": 4}]";
            JsonNode input = mapper.readTree(json);

            // Act
            JsonNode result = generator.generateSkeleton(input);

            // Assert
            assertEquals("arr", result.get("_t").asText());

            JsonNode items = result.get("items");
            assertEquals("obj", items.get("_t").asText());

            JsonNode props = items.get("props");
            // Should have all properties merged
            assertTrue(props.has("a"));
            assertTrue(props.has("b"));
            assertTrue(props.has("c"));
        }
    }

    // ========================================================================
    // Mixed Types
    // ========================================================================

    @Nested
    @DisplayName("Mixed Types")
    class MixedTypesTests {

        @Test
        @DisplayName("should return 'mixed' for array with different types")
        void shouldReturnMixedForArrayWithDifferentTypes() throws Exception {
            // Arrange - array with object and primitive
            String json = "[{\"a\": 1}, \"string\"]";
            JsonNode input = mapper.readTree(json);

            // Act
            JsonNode result = generator.generateSkeleton(input);

            // Assert
            assertEquals("arr", result.get("_t").asText());
            assertEquals("mixed", result.get("items").asText());
        }
    }

    // ========================================================================
    // Depth Protection
    // ========================================================================

    @Nested
    @DisplayName("Depth Protection")
    class DepthProtectionTests {

        @Test
        @DisplayName("should handle deeply nested structure up to limit")
        void shouldHandleDeeplyNestedStructureUpToLimit() {
            // Arrange - create a deeply nested structure
            ObjectNode root = mapper.createObjectNode();
            ObjectNode current = root;
            for (int i = 0; i < 60; i++) {
                ObjectNode nested = mapper.createObjectNode();
                current.set("nested", nested);
                current = nested;
            }
            current.put("value", "deep");

            // Act
            JsonNode result = generator.generateSkeleton(root);

            // Assert
            // Should complete without stack overflow
            assertNotNull(result);
            assertEquals("obj", result.get("_t").asText());

            // Navigate to find max_depth_reached
            JsonNode node = result;
            int depth = 0;
            while (node.has("props") && node.get("props").has("nested") && depth < 100) {
                node = node.get("props").get("nested");
                depth++;
            }
            // At some point it should have hit the limit
            assertTrue(depth <= 50, "Should respect MAX_DEPTH limit");
        }
    }

    // ========================================================================
    // Complex Structures
    // ========================================================================

    @Nested
    @DisplayName("Complex Structures")
    class ComplexStructuresTests {

        @Test
        @DisplayName("should handle object with mixed value types")
        void shouldHandleObjectWithMixedValueTypes() throws Exception {
            // Arrange
            String json = "{\"str\": \"text\", \"num\": 42, \"bool\": true, \"null\": null, \"arr\": [1,2], \"obj\": {\"x\": 1}}";
            JsonNode input = mapper.readTree(json);

            // Act
            JsonNode result = generator.generateSkeleton(input);

            // Assert
            assertEquals("obj", result.get("_t").asText());
            JsonNode props = result.get("props");

            assertEquals("string", props.get("str").asText());
            assertEquals("number", props.get("num").asText());
            assertEquals("boolean", props.get("bool").asText());
            assertEquals("null", props.get("null").asText());
            assertEquals("arr", props.get("arr").get("_t").asText());
            assertEquals("obj", props.get("obj").get("_t").asText());
        }

        @Test
        @DisplayName("should handle nested arrays")
        void shouldHandleNestedArrays() throws Exception {
            // Arrange
            String json = "[[1, 2], [3, 4]]";
            JsonNode input = mapper.readTree(json);

            // Act
            JsonNode result = generator.generateSkeleton(input);

            // Assert
            assertEquals("arr", result.get("_t").asText());

            JsonNode items = result.get("items");
            assertEquals("arr", items.get("_t").asText());
            assertEquals("number", items.get("items").asText());
        }

        @Test
        @DisplayName("should handle array of arrays of objects")
        void shouldHandleArrayOfArraysOfObjects() throws Exception {
            // Arrange
            String json = "[[{\"id\": 1}], [{\"id\": 2}]]";
            JsonNode input = mapper.readTree(json);

            // Act
            JsonNode result = generator.generateSkeleton(input);

            // Assert
            assertEquals("arr", result.get("_t").asText());

            JsonNode innerArray = result.get("items");
            assertEquals("arr", innerArray.get("_t").asText());

            JsonNode innerObject = innerArray.get("items");
            assertEquals("obj", innerObject.get("_t").asText());
            assertEquals("number", innerObject.get("props").get("id").asText());
        }
    }

    @Nested
    @DisplayName("isTriviallyEmptySkeleton")
    class IsTriviallyEmptySkeletonTests {

        @org.junit.jupiter.api.Test
        @DisplayName("empty object {_t:obj, props:{}} is trivial - Apify regression case")
        void emptyObjectIsTrivial() throws Exception {
            // Regression: in prod the Apify /run-sync-get-dataset-items skeleton was
            // saved as {"_t":"obj","props":{}} after the first run returned {} for an
            // actor with no output. Without this guard, the empty skeleton stuck
            // forever and every later run with a different actor was skipped.
            JsonNode skel = mapper.readTree("{\"_t\":\"obj\",\"props\":{}}");
            assertThat(generator.isTriviallyEmptySkeleton(skel)).isTrue();
        }

        @org.junit.jupiter.api.Test
        @DisplayName("non-empty object is not trivial")
        void nonEmptyObjectIsNotTrivial() throws Exception {
            JsonNode skel = mapper.readTree("{\"_t\":\"obj\",\"props\":{\"id\":\"string\"}}");
            assertThat(generator.isTriviallyEmptySkeleton(skel)).isFalse();
        }

        @org.junit.jupiter.api.Test
        @DisplayName("empty array {_t:arr, items:'empty'} is trivial")
        void emptyArrayIsTrivial() throws Exception {
            JsonNode skel = mapper.readTree("{\"_t\":\"arr\",\"items\":\"empty\"}");
            assertThat(generator.isTriviallyEmptySkeleton(skel)).isTrue();
        }

        @org.junit.jupiter.api.Test
        @DisplayName("array of empty objects is trivial (recursive)")
        void arrayOfEmptyObjectsIsTrivial() throws Exception {
            JsonNode skel = mapper.readTree("{\"_t\":\"arr\",\"items\":{\"_t\":\"obj\",\"props\":{}}}");
            assertThat(generator.isTriviallyEmptySkeleton(skel)).isTrue();
        }

        @org.junit.jupiter.api.Test
        @DisplayName("array with structured items is not trivial")
        void arrayWithRealItemsIsNotTrivial() throws Exception {
            JsonNode skel = mapper.readTree("{\"_t\":\"arr\",\"items\":{\"_t\":\"obj\",\"props\":{\"id\":\"string\"}}}");
            assertThat(generator.isTriviallyEmptySkeleton(skel)).isFalse();
        }

        @org.junit.jupiter.api.Test
        @DisplayName("bare primitive type token is trivial (no nested shape to learn)")
        void barePrimitiveIsTrivial() {
            assertThat(generator.isTriviallyEmptySkeleton(new com.fasterxml.jackson.databind.node.TextNode("string"))).isTrue();
        }

        @org.junit.jupiter.api.Test
        @DisplayName("null skeleton is trivial")
        void nullIsTrivial() {
            assertThat(generator.isTriviallyEmptySkeleton(null)).isTrue();
        }
    }
}
