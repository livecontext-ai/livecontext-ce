package com.apimarketplace.common.storage.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("JsonSkeletonGenerator Tests")
class JsonSkeletonGeneratorTest {

    private ObjectMapper objectMapper;
    private JsonSkeletonGenerator generator;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        generator = new JsonSkeletonGenerator(objectMapper);
    }

    @Nested
    @DisplayName("generateSkeleton - null handling")
    class NullHandlingTests {

        @Test
        @DisplayName("should return 'null' text node for null input")
        void returnNullTextNodeForNullInput() {
            JsonNode result = generator.generateSkeleton(null);

            assertThat(result.isTextual()).isTrue();
            assertThat(result.asText()).isEqualTo("null");
        }
    }

    @Nested
    @DisplayName("generateSkeleton - primitive types")
    class PrimitiveTypesTests {

        @Test
        @DisplayName("should return 'string' for string value")
        void returnStringForStringValue() {
            JsonNode input = objectMapper.valueToTree("hello");

            JsonNode result = generator.generateSkeleton(input);

            assertThat(result.isTextual()).isTrue();
            assertThat(result.asText()).isEqualTo("string");
        }

        @Test
        @DisplayName("should return 'number' for integer value")
        void returnNumberForIntegerValue() {
            JsonNode input = objectMapper.valueToTree(42);

            JsonNode result = generator.generateSkeleton(input);

            assertThat(result.isTextual()).isTrue();
            assertThat(result.asText()).isEqualTo("number");
        }

        @Test
        @DisplayName("should return 'number' for double value")
        void returnNumberForDoubleValue() {
            JsonNode input = objectMapper.valueToTree(3.14);

            JsonNode result = generator.generateSkeleton(input);

            assertThat(result.isTextual()).isTrue();
            assertThat(result.asText()).isEqualTo("number");
        }

        @Test
        @DisplayName("should return 'boolean' for boolean value")
        void returnBooleanForBooleanValue() {
            JsonNode input = objectMapper.valueToTree(true);

            JsonNode result = generator.generateSkeleton(input);

            assertThat(result.isTextual()).isTrue();
            assertThat(result.asText()).isEqualTo("boolean");
        }
    }

    @Nested
    @DisplayName("generateSkeleton - objects")
    class ObjectTests {

        @Test
        @DisplayName("should create skeleton with _t=obj for object")
        void createSkeletonWithObjType() {
            ObjectNode input = objectMapper.createObjectNode();
            input.put("name", "John");

            JsonNode result = generator.generateSkeleton(input);

            assertThat(result.get("_t").asText()).isEqualTo("obj");
        }

        @Test
        @DisplayName("should include props field for object")
        void includePropsFieldForObject() {
            ObjectNode input = objectMapper.createObjectNode();
            input.put("name", "John");
            input.put("age", 30);

            JsonNode result = generator.generateSkeleton(input);

            assertThat(result.has("props")).isTrue();
            assertThat(result.get("props").has("name")).isTrue();
            assertThat(result.get("props").has("age")).isTrue();
        }

        @Test
        @DisplayName("should recursively process nested objects")
        void recursivelyProcessNestedObjects() {
            ObjectNode input = objectMapper.createObjectNode();
            ObjectNode nested = input.putObject("address");
            nested.put("city", "Paris");

            JsonNode result = generator.generateSkeleton(input);

            JsonNode addressSkeleton = result.get("props").get("address");
            assertThat(addressSkeleton.get("_t").asText()).isEqualTo("obj");
            assertThat(addressSkeleton.get("props").has("city")).isTrue();
        }

        @Test
        @DisplayName("should handle empty object")
        void handleEmptyObject() {
            ObjectNode input = objectMapper.createObjectNode();

            JsonNode result = generator.generateSkeleton(input);

            assertThat(result.get("_t").asText()).isEqualTo("obj");
            assertThat(result.get("props").isEmpty()).isTrue();
        }
    }

    @Nested
    @DisplayName("generateSkeleton - arrays")
    class ArrayTests {

        @Test
        @DisplayName("should create skeleton with _t=arr for array")
        void createSkeletonWithArrType() {
            ArrayNode input = objectMapper.createArrayNode();
            input.add("item1");

            JsonNode result = generator.generateSkeleton(input);

            assertThat(result.get("_t").asText()).isEqualTo("arr");
        }

        @Test
        @DisplayName("should include items field for array")
        void includeItemsFieldForArray() {
            ArrayNode input = objectMapper.createArrayNode();
            input.add("item1");
            input.add("item2");

            JsonNode result = generator.generateSkeleton(input);

            assertThat(result.has("items")).isTrue();
        }

        @Test
        @DisplayName("should return 'empty' for empty array items")
        void returnEmptyForEmptyArray() {
            ArrayNode input = objectMapper.createArrayNode();

            JsonNode result = generator.generateSkeleton(input);

            assertThat(result.get("items").asText()).isEqualTo("empty");
        }

        @Test
        @DisplayName("should handle array of objects")
        void handleArrayOfObjects() {
            ArrayNode input = objectMapper.createArrayNode();
            ObjectNode item = objectMapper.createObjectNode();
            item.put("id", 1);
            item.put("name", "Item");
            input.add(item);

            JsonNode result = generator.generateSkeleton(input);

            JsonNode itemsSkeleton = result.get("items");
            assertThat(itemsSkeleton.get("_t").asText()).isEqualTo("obj");
            assertThat(itemsSkeleton.get("props").has("id")).isTrue();
            assertThat(itemsSkeleton.get("props").has("name")).isTrue();
        }

        @Test
        @DisplayName("should merge schemas from multiple array items")
        void mergeSchemasFromMultipleArrayItems() {
            ArrayNode input = objectMapper.createArrayNode();

            ObjectNode item1 = objectMapper.createObjectNode();
            item1.put("id", 1);
            input.add(item1);

            ObjectNode item2 = objectMapper.createObjectNode();
            item2.put("id", 2);
            item2.put("extra", "value");
            input.add(item2);

            JsonNode result = generator.generateSkeleton(input);

            JsonNode props = result.get("items").get("props");
            assertThat(props.has("id")).isTrue();
            assertThat(props.has("extra")).isTrue();
        }
    }

    @Nested
    @DisplayName("generateSkeleton - depth protection")
    class DepthProtectionTests {

        @Test
        @DisplayName("should protect against deeply nested structures")
        void protectAgainstDeeplyNestedStructures() {
            // Create deeply nested structure
            ObjectNode current = objectMapper.createObjectNode();
            ObjectNode root = current;

            for (int i = 0; i < 150; i++) {
                ObjectNode nested = objectMapper.createObjectNode();
                current.set("nested", nested);
                current = nested;
            }
            current.put("deep", "value");

            JsonNode result = generator.generateSkeleton(root);

            // Should complete without stack overflow
            assertThat(result).isNotNull();
            assertThat(result.get("_t").asText()).isEqualTo("obj");
        }
    }

    @Nested
    @DisplayName("generateSkeleton - complex structures")
    class ComplexStructuresTests {

        @Test
        @DisplayName("should handle mixed array types as 'mixed'")
        void handleMixedArrayTypes() {
            ArrayNode input = objectMapper.createArrayNode();
            input.add("string");

            ObjectNode obj = objectMapper.createObjectNode();
            obj.put("key", "value");
            input.add(obj);

            JsonNode result = generator.generateSkeleton(input);

            // First item is string, second is object - should produce mixed
            assertThat(result.get("items").asText()).isEqualTo("mixed");
        }

        @Test
        @DisplayName("should handle nested arrays")
        void handleNestedArrays() {
            ArrayNode input = objectMapper.createArrayNode();
            ArrayNode nested = objectMapper.createArrayNode();
            nested.add(1);
            nested.add(2);
            input.add(nested);

            JsonNode result = generator.generateSkeleton(input);

            assertThat(result.get("_t").asText()).isEqualTo("arr");
            assertThat(result.get("items").get("_t").asText()).isEqualTo("arr");
        }

        @Test
        @DisplayName("should handle real-world JSON structure")
        void handleRealWorldJsonStructure() {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("success", true);
            root.put("message", "OK");

            ObjectNode data = root.putObject("data");
            data.put("total", 100);

            ArrayNode items = data.putArray("items");
            for (int i = 0; i < 3; i++) {
                ObjectNode item = objectMapper.createObjectNode();
                item.put("id", i);
                item.put("name", "Item " + i);
                item.put("active", true);
                items.add(item);
            }

            JsonNode result = generator.generateSkeleton(root);

            // Verify structure
            assertThat(result.get("_t").asText()).isEqualTo("obj");

            JsonNode props = result.get("props");
            assertThat(props.get("success").asText()).isEqualTo("boolean");
            assertThat(props.get("message").asText()).isEqualTo("string");

            JsonNode dataSkeleton = props.get("data");
            assertThat(dataSkeleton.get("_t").asText()).isEqualTo("obj");

            JsonNode itemsSkeleton = dataSkeleton.get("props").get("items");
            assertThat(itemsSkeleton.get("_t").asText()).isEqualTo("arr");

            JsonNode itemProps = itemsSkeleton.get("items").get("props");
            assertThat(itemProps.get("id").asText()).isEqualTo("number");
            assertThat(itemProps.get("name").asText()).isEqualTo("string");
            assertThat(itemProps.get("active").asText()).isEqualTo("boolean");
        }
    }
}
