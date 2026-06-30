package com.apimarketplace.catalog.integration;

import com.apimarketplace.catalog.mapping.validator.JsonPathEvaluator;
import com.apimarketplace.catalog.mapping.validator.MappingValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for the mapping subsystem: MappingValidator + JsonPathEvaluator.
 * Validates that mapping specifications work correctly against sample JSON data,
 * including candidate path validation, type compatibility checks, and field cleanup.
 *
 * No Spring context needed - instantiates components directly.
 */
@DisplayName("MappingValidatorIntegrationTest - Mapping validation pipeline")
class MappingValidatorIntegrationTest {

    private MappingValidator validator;
    private JsonPathEvaluator pathEvaluator;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        pathEvaluator = new JsonPathEvaluator(objectMapper);
        validator = new MappingValidator(pathEvaluator, objectMapper);
    }

    // -------------------------------------------------------------------------
    // Sample data
    // -------------------------------------------------------------------------

    private static final String SAMPLE_JSON = """
            {
              "data": {
                "users": [
                  {"id": 1, "name": "Alice", "email": "alice@example.com", "age": 30, "active": true},
                  {"id": 2, "name": "Bob", "email": "bob@example.com", "age": 25, "active": false}
                ],
                "meta": {
                  "total": 2,
                  "page": 1
                }
              }
            }
            """;

    private static final String NESTED_ARRAY_JSON = """
            {
              "results": [
                {"title": "Post 1", "tags": ["java", "spring"], "score": 4.5},
                {"title": "Post 2", "tags": ["python", "flask"], "score": 3.8}
              ]
            }
            """;

    // -------------------------------------------------------------------------
    // JsonPathEvaluator tests
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("JsonPathEvaluator")
    class JsonPathEvaluatorTests {

        @Test
        @DisplayName("should detect existing paths")
        void shouldDetectExistingPaths() {
            assertThat(pathEvaluator.pathExists(SAMPLE_JSON, "$.data.users[0].name", null)).isTrue();
            assertThat(pathEvaluator.pathExists(SAMPLE_JSON, "$.data.meta.total", null)).isTrue();
        }

        @Test
        @DisplayName("should detect non-existing paths")
        void shouldDetectNonExistingPaths() {
            assertThat(pathEvaluator.pathExists(SAMPLE_JSON, "$.data.nonexistent", null)).isFalse();
            assertThat(pathEvaluator.pathExists(SAMPLE_JSON, "$.missing.path", null)).isFalse();
        }

        @Test
        @DisplayName("should extract values with items path context")
        void shouldExtractValuesWithItemsPath() {
            List<Object> results = pathEvaluator.extractAll(SAMPLE_JSON, "@.name", "$.data.users[*]");
            assertThat(results).isNotEmpty();
            assertThat(results).contains("Alice");
        }

        @Test
        @DisplayName("should extract integer values")
        void shouldExtractIntegerValues() {
            List<Object> results = pathEvaluator.extractAll(SAMPLE_JSON, "@.id", "$.data.users[*]");
            assertThat(results).isNotEmpty();
            assertThat(results).contains(1);
        }

        @Test
        @DisplayName("should extract boolean values")
        void shouldExtractBooleanValues() {
            List<Object> results = pathEvaluator.extractAll(SAMPLE_JSON, "@.active", "$.data.users[*]");
            assertThat(results).isNotEmpty();
            assertThat(results).contains(true);
        }

        @Test
        @DisplayName("should handle array wildcard extraction")
        void shouldHandleArrayWildcard() {
            List<Object> results = pathEvaluator.extractAll(SAMPLE_JSON, "$.data.users[*].name", null);
            // Array wildcard should extract item node(s)
            assertThat(results).isNotEmpty();
        }

        @Test
        @DisplayName("should return empty list for invalid JSON")
        void shouldReturnEmptyForInvalidJson() {
            List<Object> results = pathEvaluator.extractAll("not-json", "$.field", null);
            assertThat(results).isEmpty();
        }

        @Test
        @DisplayName("should handle null and empty paths gracefully")
        void shouldHandleNullPaths() {
            assertThat(pathEvaluator.pathExists(SAMPLE_JSON, null, null)).isFalse();
            assertThat(pathEvaluator.pathExists(SAMPLE_JSON, "", null)).isFalse();
        }
    }

    // -------------------------------------------------------------------------
    // MappingValidator tests
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("MappingValidator - validateMapping")
    class MappingValidationTests {

        @Test
        @DisplayName("should validate and retain valid candidates")
        void shouldRetainValidCandidates() throws Exception {
            String mappingSpec = """
                    {
                      "source": {"format": "json", "items_path": "$.data.users[*]"},
                      "fields": {
                        "userName": {
                          "candidates": ["@.name", "@.fullName"],
                          "to": "string",
                          "required": true
                        }
                      }
                    }
                    """;

            String result = validator.validateMapping(SAMPLE_JSON, mappingSpec, 3);

            JsonNode resultNode = objectMapper.readTree(result);
            JsonNode userNameField = resultNode.get("fields").get("userName");
            assertThat(userNameField).isNotNull();
            assertThat(userNameField.get("candidates").size()).isGreaterThanOrEqualTo(1);
            // "@.name" should be kept as it resolves to valid data
            assertThat(userNameField.get("candidates").get(0).asText()).isEqualTo("@.name");
        }

        @Test
        @DisplayName("should remove invalid candidates")
        void shouldRemoveInvalidCandidates() throws Exception {
            String mappingSpec = """
                    {
                      "source": {"format": "json", "items_path": "$.data.users[*]"},
                      "fields": {
                        "userName": {
                          "candidates": ["@.nonexistent_field", "@.name"],
                          "to": "string",
                          "required": false
                        }
                      }
                    }
                    """;

            String result = validator.validateMapping(SAMPLE_JSON, mappingSpec, 3);

            JsonNode resultNode = objectMapper.readTree(result);
            JsonNode userNameField = resultNode.get("fields").get("userName");
            assertThat(userNameField).isNotNull();

            // Only "@.name" should remain (nonexistent is removed)
            boolean containsName = false;
            for (JsonNode candidate : userNameField.get("candidates")) {
                if ("@.name".equals(candidate.asText())) {
                    containsName = true;
                }
            }
            assertThat(containsName).isTrue();
        }

        @Test
        @DisplayName("should enforce maxFallbacks limit")
        void shouldEnforceMaxFallbacks() throws Exception {
            String mappingSpec = """
                    {
                      "source": {"format": "json", "items_path": "$.data.users[*]"},
                      "fields": {
                        "identity": {
                          "candidates": ["@.id", "@.name", "@.email", "@.age"],
                          "to": "string",
                          "required": false
                        }
                      }
                    }
                    """;

            String result = validator.validateMapping(SAMPLE_JSON, mappingSpec, 2);

            JsonNode resultNode = objectMapper.readTree(result);
            JsonNode identityField = resultNode.get("fields").get("identity");
            assertThat(identityField).isNotNull();
            // Should have at most 2 candidates (maxFallbacks = 2)
            assertThat(identityField.get("candidates").size()).isLessThanOrEqualTo(2);
        }

        @Test
        @DisplayName("should remove fields with no valid candidates")
        void shouldRemoveFieldsWithNoValidCandidates() throws Exception {
            String mappingSpec = """
                    {
                      "source": {"format": "json", "items_path": "$.data.users[*]"},
                      "fields": {
                        "phantom": {
                          "candidates": ["@.ghost", "@.phantom", "@.invisible"],
                          "to": "string",
                          "required": false
                        }
                      }
                    }
                    """;

            String result = validator.validateMapping(SAMPLE_JSON, mappingSpec, 3);

            JsonNode resultNode = objectMapper.readTree(result);
            assertThat(resultNode.get("fields").has("phantom")).isFalse();
        }

        @Test
        @DisplayName("should validate type compatibility for integer fields")
        void shouldValidateTypeCompatibilityForInteger() throws Exception {
            String mappingSpec = """
                    {
                      "source": {"format": "json", "items_path": "$.data.users[*]"},
                      "fields": {
                        "userId": {
                          "candidates": ["@.id"],
                          "to": "integer",
                          "required": true
                        }
                      }
                    }
                    """;

            String result = validator.validateMapping(SAMPLE_JSON, mappingSpec, 3);

            JsonNode resultNode = objectMapper.readTree(result);
            assertThat(resultNode.get("fields").has("userId")).isTrue();
            assertThat(resultNode.get("fields").get("userId").get("to").asText()).isEqualTo("integer");
        }

        @Test
        @DisplayName("should validate type compatibility for boolean fields")
        void shouldValidateTypeCompatibilityForBoolean() throws Exception {
            String mappingSpec = """
                    {
                      "source": {"format": "json", "items_path": "$.data.users[*]"},
                      "fields": {
                        "isActive": {
                          "candidates": ["@.active"],
                          "to": "boolean",
                          "required": false
                        }
                      }
                    }
                    """;

            String result = validator.validateMapping(SAMPLE_JSON, mappingSpec, 3);

            JsonNode resultNode = objectMapper.readTree(result);
            assertThat(resultNode.get("fields").has("isActive")).isTrue();
        }

        @Test
        @DisplayName("should throw for missing source in mapping spec")
        void shouldThrowForMissingSource() {
            String mappingSpec = """
                    {
                      "fields": {
                        "name": {"candidates": ["@.name"], "to": "string"}
                      }
                    }
                    """;

            assertThatThrownBy(() -> validator.validateMapping(SAMPLE_JSON, mappingSpec, 3))
                    .isInstanceOf(RuntimeException.class);
        }

        @Test
        @DisplayName("should throw for missing fields in mapping spec")
        void shouldThrowForMissingFields() {
            String mappingSpec = """
                    {
                      "source": {"format": "json", "items_path": "$.data.users[*]"}
                    }
                    """;

            assertThatThrownBy(() -> validator.validateMapping(SAMPLE_JSON, mappingSpec, 3))
                    .isInstanceOf(RuntimeException.class);
        }

        @Test
        @DisplayName("should preserve source configuration in validated output")
        void shouldPreserveSourceConfig() throws Exception {
            String mappingSpec = """
                    {
                      "source": {"format": "json", "items_path": "$.data.users[*]"},
                      "fields": {
                        "name": {"candidates": ["@.name"], "to": "string"}
                      }
                    }
                    """;

            String result = validator.validateMapping(SAMPLE_JSON, mappingSpec, 3);

            JsonNode resultNode = objectMapper.readTree(result);
            assertThat(resultNode.get("source").get("items_path").asText())
                    .isEqualTo("$.data.users[*]");
        }
    }

    // -------------------------------------------------------------------------
    // End-to-end: validation then application via SimpleMappingEngine
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("End-to-end mapping validation and execution")
    class EndToEndTests {

        @Test
        @DisplayName("should validate and then apply mapping successfully")
        void shouldValidateAndApply() throws Exception {
            String mappingSpec = """
                    {
                      "source": {"format": "json", "items_path": "$.data.users[*]"},
                      "fields": {
                        "name": {"candidates": ["@.name"], "to": "string", "required": true},
                        "userId": {"candidates": ["@.id"], "to": "integer", "required": true}
                      }
                    }
                    """;

            // Step 1: Validate
            String validatedSpec = validator.validateMapping(SAMPLE_JSON, mappingSpec, 3);

            // Step 2: Apply using SimpleMappingEngine
            com.apimarketplace.common.mapping.SimpleMappingEngine.MappingOutcome outcome =
                    com.apimarketplace.common.mapping.SimpleMappingEngine.apply(SAMPLE_JSON, validatedSpec);

            assertThat(outcome.itemCount).isEqualTo(2);
            assertThat(outcome.items.get(0).get("name")).isEqualTo("Alice");
            assertThat(outcome.items.get(0).get("userId")).isEqualTo(1);
            assertThat(outcome.items.get(1).get("name")).isEqualTo("Bob");
            assertThat(outcome.items.get(1).get("userId")).isEqualTo(2);
        }

        @Test
        @DisplayName("should handle nested array JSON through validation and execution")
        void shouldHandleNestedArrays() throws Exception {
            String mappingSpec = """
                    {
                      "source": {"format": "json", "items_path": "$.results[*]"},
                      "fields": {
                        "postTitle": {"candidates": ["@.title"], "to": "string", "required": true},
                        "postScore": {"candidates": ["@.score"], "to": "number", "required": false}
                      }
                    }
                    """;

            String validatedSpec = validator.validateMapping(NESTED_ARRAY_JSON, mappingSpec, 3);

            com.apimarketplace.common.mapping.SimpleMappingEngine.MappingOutcome outcome =
                    com.apimarketplace.common.mapping.SimpleMappingEngine.apply(NESTED_ARRAY_JSON, validatedSpec);

            assertThat(outcome.itemCount).isEqualTo(2);
            assertThat(outcome.items.get(0).get("postTitle")).isEqualTo("Post 1");
            assertThat(outcome.items.get(0).get("postScore")).isEqualTo(4.5);
        }
    }
}
