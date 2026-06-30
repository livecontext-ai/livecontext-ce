package com.apimarketplace.catalog.mapping.validator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for MappingValidator class.
 *
 * MappingValidator validates mapping specifications against sample JSON data.
 */
@DisplayName("MappingValidator")
class MappingValidatorTest {

    private MappingValidator validator;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        JsonPathEvaluator pathEvaluator = new JsonPathEvaluator(objectMapper);
        validator = new MappingValidator(pathEvaluator, objectMapper);
    }

    // ========================================================================
    // validateMapping tests - valid mappings
    // ========================================================================

    @Nested
    @DisplayName("validateMapping() - valid mappings")
    class ValidMappingTests {

        @Test
        @DisplayName("should validate simple mapping")
        void shouldValidateSimpleMapping() throws Exception {
            String sampleJson = "{\"name\": \"John\", \"age\": 30}";
            String mappingSpec = """
                {
                    "source": {"format": "json", "root": "$"},
                    "fields": {
                        "userName": {
                            "candidates": ["name", "username"],
                            "to": "string"
                        }
                    }
                }
                """;

            String result = validator.validateMapping(sampleJson, mappingSpec, 4);

            assertNotNull(result);
            JsonNode validated = objectMapper.readTree(result);
            assertNotNull(validated.get("source"));
            assertNotNull(validated.get("fields"));
        }

        @Test
        @DisplayName("should preserve valid candidates")
        void shouldPreserveValidCandidates() throws Exception {
            String sampleJson = "{\"id\": 123, \"name\": \"Test\"}";
            String mappingSpec = """
                {
                    "source": {"format": "json", "root": "$"},
                    "fields": {
                        "identifier": {
                            "candidates": ["id", "uuid", "pk"],
                            "to": "integer"
                        }
                    }
                }
                """;

            String result = validator.validateMapping(sampleJson, mappingSpec, 4);

            JsonNode validated = objectMapper.readTree(result);
            JsonNode candidates = validated.get("fields").get("identifier").get("candidates");
            assertTrue(candidates.isArray());
            assertEquals(1, candidates.size());
            assertEquals("id", candidates.get(0).asText());
        }

        @Test
        @DisplayName("should respect maxFallbacks limit")
        void shouldRespectMaxFallbacksLimit() throws Exception {
            String sampleJson = "{\"a\": \"1\", \"b\": \"2\", \"c\": \"3\", \"d\": \"4\", \"e\": \"5\"}";
            String mappingSpec = """
                {
                    "source": {"format": "json", "root": "$"},
                    "fields": {
                        "value": {
                            "candidates": ["a", "b", "c", "d", "e"],
                            "to": "string"
                        }
                    }
                }
                """;

            String result = validator.validateMapping(sampleJson, mappingSpec, 3);

            JsonNode validated = objectMapper.readTree(result);
            JsonNode candidates = validated.get("fields").get("value").get("candidates");
            assertEquals(3, candidates.size());
        }
    }

    // ========================================================================
    // validateMapping tests - invalid mappings
    // ========================================================================

    @Nested
    @DisplayName("validateMapping() - invalid mappings")
    class InvalidMappingTests {

        @Test
        @DisplayName("should throw for missing source")
        void shouldThrowForMissingSource() {
            String sampleJson = "{\"name\": \"John\"}";
            String mappingSpec = """
                {
                    "fields": {
                        "userName": {"candidates": ["name"], "to": "string"}
                    }
                }
                """;

            assertThrows(RuntimeException.class, () ->
                validator.validateMapping(sampleJson, mappingSpec, 4)
            );
        }

        @Test
        @DisplayName("should throw for missing fields")
        void shouldThrowForMissingFields() {
            String sampleJson = "{\"name\": \"John\"}";
            String mappingSpec = """
                {
                    "source": {"format": "json", "root": "$"}
                }
                """;

            assertThrows(RuntimeException.class, () ->
                validator.validateMapping(sampleJson, mappingSpec, 4)
            );
        }

        @Test
        @DisplayName("should remove fields with no valid candidates")
        void shouldRemoveFieldsWithNoValidCandidates() throws Exception {
            String sampleJson = "{\"name\": \"John\"}";
            String mappingSpec = """
                {
                    "source": {"format": "json", "root": "$"},
                    "fields": {
                        "email": {
                            "candidates": ["email", "mail", "e-mail"],
                            "to": "string"
                        }
                    }
                }
                """;

            String result = validator.validateMapping(sampleJson, mappingSpec, 4);

            JsonNode validated = objectMapper.readTree(result);
            assertNull(validated.get("fields").get("email"));
        }
    }

    // ========================================================================
    // validateMapping tests - type compatibility
    // ========================================================================

    @Nested
    @DisplayName("validateMapping() - type compatibility")
    class TypeCompatibilityTests {

        @Test
        @DisplayName("should validate integer type")
        void shouldValidateIntegerType() throws Exception {
            String sampleJson = "{\"count\": 42}";
            String mappingSpec = """
                {
                    "source": {"format": "json", "root": "$"},
                    "fields": {
                        "total": {
                            "candidates": ["count"],
                            "to": "integer"
                        }
                    }
                }
                """;

            String result = validator.validateMapping(sampleJson, mappingSpec, 4);

            JsonNode validated = objectMapper.readTree(result);
            assertNotNull(validated.get("fields").get("total"));
        }

        @Test
        @DisplayName("should validate boolean type")
        void shouldValidateBooleanType() throws Exception {
            String sampleJson = "{\"active\": true}";
            String mappingSpec = """
                {
                    "source": {"format": "json", "root": "$"},
                    "fields": {
                        "enabled": {
                            "candidates": ["active"],
                            "to": "boolean"
                        }
                    }
                }
                """;

            String result = validator.validateMapping(sampleJson, mappingSpec, 4);

            JsonNode validated = objectMapper.readTree(result);
            assertNotNull(validated.get("fields").get("enabled"));
        }

        @Test
        @DisplayName("should validate string coercion from number")
        void shouldValidateStringCoercionFromNumber() throws Exception {
            String sampleJson = "{\"id\": 123}";
            String mappingSpec = """
                {
                    "source": {"format": "json", "root": "$"},
                    "fields": {
                        "identifier": {
                            "candidates": ["id"],
                            "to": "string"
                        }
                    }
                }
                """;

            String result = validator.validateMapping(sampleJson, mappingSpec, 4);

            JsonNode validated = objectMapper.readTree(result);
            assertNotNull(validated.get("fields").get("identifier"));
        }
    }

    // ========================================================================
    // validateMapping tests - items path
    // ========================================================================

    @Nested
    @DisplayName("validateMapping() - items path")
    class ItemsPathTests {

        @Test
        @DisplayName("should validate mapping with items path")
        void shouldValidateMappingWithItemsPath() throws Exception {
            String sampleJson = "{\"data\": [{\"id\": 1, \"name\": \"A\"}, {\"id\": 2, \"name\": \"B\"}]}";
            String mappingSpec = """
                {
                    "source": {"format": "json", "root": "$", "items_path": "$.data"},
                    "fields": {
                        "itemId": {
                            "candidates": ["@.id"],
                            "to": "integer"
                        }
                    }
                }
                """;

            String result = validator.validateMapping(sampleJson, mappingSpec, 4);

            JsonNode validated = objectMapper.readTree(result);
            assertNotNull(validated.get("fields").get("itemId"));
        }
    }

    // ========================================================================
    // validateMapping tests - required fields
    // ========================================================================

    @Nested
    @DisplayName("validateMapping() - required fields")
    class RequiredFieldTests {

        @Test
        @DisplayName("should preserve required flag")
        void shouldPreserveRequiredFlag() throws Exception {
            String sampleJson = "{\"name\": \"John\"}";
            String mappingSpec = """
                {
                    "source": {"format": "json", "root": "$"},
                    "fields": {
                        "userName": {
                            "candidates": ["name"],
                            "to": "string",
                            "required": true
                        }
                    }
                }
                """;

            String result = validator.validateMapping(sampleJson, mappingSpec, 4);

            JsonNode validated = objectMapper.readTree(result);
            assertTrue(validated.get("fields").get("userName").get("required").asBoolean());
        }
    }

    // ========================================================================
    // validateMapping tests - error handling
    // ========================================================================

    @Nested
    @DisplayName("validateMapping() - error handling")
    class ErrorHandlingTests {

        @Test
        @DisplayName("should throw for invalid JSON mapping spec")
        void shouldThrowForInvalidJsonMappingSpec() {
            String sampleJson = "{\"name\": \"John\"}";
            String mappingSpec = "not valid json";

            assertThrows(RuntimeException.class, () ->
                validator.validateMapping(sampleJson, mappingSpec, 4)
            );
        }

        @Test
        @DisplayName("should handle empty candidates array")
        void shouldHandleEmptyCandidatesArray() throws Exception {
            String sampleJson = "{\"name\": \"John\"}";
            String mappingSpec = """
                {
                    "source": {"format": "json", "root": "$"},
                    "fields": {
                        "empty": {
                            "candidates": [],
                            "to": "string"
                        }
                    }
                }
                """;

            String result = validator.validateMapping(sampleJson, mappingSpec, 4);

            JsonNode validated = objectMapper.readTree(result);
            assertNull(validated.get("fields").get("empty"));
        }
    }
}
