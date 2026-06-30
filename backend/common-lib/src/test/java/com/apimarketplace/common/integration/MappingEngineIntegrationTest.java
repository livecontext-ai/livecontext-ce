package com.apimarketplace.common.integration;

import com.apimarketplace.common.mapping.SimpleMappingEngine;
import com.apimarketplace.common.mapping.StrictMappingEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for the mapping engine subsystem in common-lib.
 * Tests cross-engine consistency, complex JSON structures, type conversions,
 * and end-to-end mapping pipelines using both SimpleMappingEngine and StrictMappingEngine.
 *
 * No Spring context needed - these are pure utility classes.
 */
@DisplayName("MappingEngineIntegrationTest - Cross-engine mapping pipeline tests")
class MappingEngineIntegrationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    // -------------------------------------------------------------------------
    // Sample JSON data
    // -------------------------------------------------------------------------

    private static final String USERS_JSON = """
            {
              "data": {
                "users": [
                  {"id": 1, "name": "Alice", "email": "alice@example.com", "age": 30, "active": true},
                  {"id": 2, "name": "Bob", "email": "bob@example.com", "age": 25, "active": false},
                  {"id": 3, "name": "Charlie", "email": "charlie@example.com", "age": 35, "active": true}
                ]
              }
            }
            """;

    private static final String NESTED_JSON = """
            {
              "response": {
                "results": [
                  {
                    "title": "Post 1",
                    "author": {"name": "Writer A", "id": 100},
                    "tags": ["java", "spring"],
                    "score": 4.5
                  },
                  {
                    "title": "Post 2",
                    "author": {"name": "Writer B", "id": 200},
                    "tags": ["python", "flask"],
                    "score": 3.8
                  }
                ]
              }
            }
            """;

    private static final String FLAT_ARRAY_JSON = """
            [
              {"product": "Laptop", "price": 999.99, "inStock": true},
              {"product": "Mouse", "price": 29.99, "inStock": false},
              {"product": "Keyboard", "price": 79.99, "inStock": true}
            ]
            """;

    // -------------------------------------------------------------------------
    // Cross-engine consistency
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Cross-engine consistency")
    class CrossEngineConsistency {

        @Test
        @DisplayName("both engines should produce same results for simple mapping")
        void bothEnginesShouldProduceSameResults() throws Exception {
            String mappingSpec = """
                    {
                      "source": {"format": "json", "items_path": "$.data.users[*]"},
                      "fields": {
                        "userName": {"candidates": ["@.name"], "to": "string"},
                        "userId": {"candidates": ["@.id"], "to": "integer"}
                      }
                    }
                    """;

            SimpleMappingEngine.MappingOutcome simpleResult =
                    SimpleMappingEngine.apply(USERS_JSON, mappingSpec);
            StrictMappingEngine.MappingOutcome strictResult =
                    StrictMappingEngine.apply(USERS_JSON, mappingSpec);

            assertThat(simpleResult.itemCount).isEqualTo(strictResult.itemCount);
            assertThat(simpleResult.items).hasSameSizeAs(strictResult.items);

            for (int i = 0; i < simpleResult.items.size(); i++) {
                assertThat(simpleResult.items.get(i).get("userName"))
                        .isEqualTo(strictResult.items.get(i).get("userName"));
                assertThat(simpleResult.items.get(i).get("userId"))
                        .isEqualTo(strictResult.items.get(i).get("userId"));
            }
        }

        @Test
        @DisplayName("both engines should handle nested path extraction consistently")
        void bothEnginesShouldHandleNestedPaths() throws Exception {
            String mappingSpec = """
                    {
                      "source": {"format": "json", "items_path": "$.response.results[*]"},
                      "fields": {
                        "postTitle": {"candidates": ["@.title"], "to": "string"},
                        "authorName": {"candidates": ["@.author.name"], "to": "string"},
                        "postScore": {"candidates": ["@.score"], "to": "number"}
                      }
                    }
                    """;

            SimpleMappingEngine.MappingOutcome simpleResult =
                    SimpleMappingEngine.apply(NESTED_JSON, mappingSpec);
            StrictMappingEngine.MappingOutcome strictResult =
                    StrictMappingEngine.apply(NESTED_JSON, mappingSpec);

            assertThat(simpleResult.itemCount).isEqualTo(2);
            assertThat(strictResult.itemCount).isEqualTo(2);

            assertThat(simpleResult.items.get(0).get("postTitle")).isEqualTo("Post 1");
            assertThat(strictResult.items.get(0).get("postTitle")).isEqualTo("Post 1");

            assertThat(simpleResult.items.get(0).get("authorName")).isEqualTo("Writer A");
            assertThat(strictResult.items.get(0).get("authorName")).isEqualTo("Writer A");
        }
    }

    // -------------------------------------------------------------------------
    // SimpleMappingEngine specific scenarios
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("SimpleMappingEngine - advanced scenarios")
    class SimpleMappingEngineAdvanced {

        @Test
        @DisplayName("should handle flat array JSON (no wrapper object)")
        void shouldHandleFlatArray() throws Exception {
            String mappingSpec = """
                    {
                      "source": {"format": "json"},
                      "fields": {
                        "productName": {"candidates": ["@.product"], "to": "string"},
                        "productPrice": {"candidates": ["@.price"], "to": "number"}
                      }
                    }
                    """;

            SimpleMappingEngine.MappingOutcome result =
                    SimpleMappingEngine.apply(FLAT_ARRAY_JSON, mappingSpec);

            assertThat(result.itemCount).isEqualTo(3);
            assertThat(result.items.get(0).get("productName")).isEqualTo("Laptop");
            assertThat(result.items.get(0).get("productPrice")).isEqualTo(999.99);
        }

        @Test
        @DisplayName("should handle candidate fallback mechanism")
        void shouldHandleCandidateFallback() throws Exception {
            String mappingSpec = """
                    {
                      "source": {"format": "json", "items_path": "$.data.users[*]"},
                      "fields": {
                        "displayName": {
                          "candidates": ["@.display_name", "@.full_name", "@.name"],
                          "to": "string"
                        }
                      }
                    }
                    """;

            SimpleMappingEngine.MappingOutcome result =
                    SimpleMappingEngine.apply(USERS_JSON, mappingSpec);

            assertThat(result.itemCount).isEqualTo(3);
            // Should fall back to @.name since display_name and full_name don't exist
            assertThat(result.items.get(0).get("displayName")).isEqualTo("Alice");
            assertThat(result.items.get(1).get("displayName")).isEqualTo("Bob");
        }

        @Test
        @DisplayName("should handle type conversions correctly")
        void shouldHandleTypeConversions() throws Exception {
            String mappingSpec = """
                    {
                      "source": {"format": "json", "items_path": "$.data.users[*]"},
                      "fields": {
                        "userId": {"candidates": ["@.id"], "to": "integer"},
                        "userName": {"candidates": ["@.name"], "to": "string"},
                        "userAge": {"candidates": ["@.age"], "to": "number"},
                        "isActive": {"candidates": ["@.active"], "to": "boolean"}
                      }
                    }
                    """;

            SimpleMappingEngine.MappingOutcome result =
                    SimpleMappingEngine.apply(USERS_JSON, mappingSpec);

            Map<String, Object> first = result.items.get(0);
            assertThat(first.get("userId")).isEqualTo(1);
            assertThat(first.get("userName")).isEqualTo("Alice");
            assertThat(first.get("userAge")).isEqualTo(30.0);
            assertThat(first.get("isActive")).isEqualTo(true);
        }

        @Test
        @DisplayName("should handle default values for missing fields")
        void shouldHandleDefaultValues() throws Exception {
            String mappingSpec = """
                    {
                      "source": {"format": "json", "items_path": "$.data.users[*]"},
                      "fields": {
                        "role": {
                          "candidates": ["@.role"],
                          "to": "string",
                          "default": "user"
                        }
                      }
                    }
                    """;

            SimpleMappingEngine.MappingOutcome result =
                    SimpleMappingEngine.apply(USERS_JSON, mappingSpec);

            assertThat(result.items.get(0).get("role")).isEqualTo("user");
        }

        @Test
        @DisplayName("should track unresolved required fields")
        void shouldTrackUnresolvedRequiredFields() throws Exception {
            String mappingSpec = """
                    {
                      "source": {"format": "json", "items_path": "$.data.users[*]"},
                      "fields": {
                        "phantom": {
                          "candidates": ["@.nonexistent"],
                          "to": "string",
                          "required": true
                        }
                      }
                    }
                    """;

            SimpleMappingEngine.MappingOutcome result =
                    SimpleMappingEngine.apply(USERS_JSON, mappingSpec);

            assertThat(result.unresolvedFields).contains("phantom");
        }

        @Test
        @DisplayName("should extract array fields with array type")
        void shouldExtractArrayFields() throws Exception {
            String mappingSpec = """
                    {
                      "source": {"format": "json", "items_path": "$.response.results[*]"},
                      "fields": {
                        "postTags": {
                          "candidates": ["@.tags[*]"],
                          "to": "array<string>"
                        }
                      }
                    }
                    """;

            SimpleMappingEngine.MappingOutcome result =
                    SimpleMappingEngine.apply(NESTED_JSON, mappingSpec);

            assertThat(result.itemCount).isEqualTo(2);
            Object tags = result.items.get(0).get("postTags");
            assertThat(tags).isInstanceOf(List.class);
            @SuppressWarnings("unchecked")
            List<String> tagList = (List<String>) tags;
            assertThat(tagList).contains("java", "spring");
        }
    }

    // -------------------------------------------------------------------------
    // StrictMappingEngine specific scenarios
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("StrictMappingEngine - advanced scenarios")
    class StrictMappingEngineAdvanced {

        @Test
        @DisplayName("should handle root alternatives fallback")
        void shouldHandleRootAlternatives() throws Exception {
            String mappingSpec = """
                    {
                      "source": {
                        "format": "json",
                        "root_alternatives": ["$.nonexistent[*]", "$.data.users[*]"]
                      },
                      "fields": {
                        "name": {"candidates": ["@.name"], "to": "string"}
                      }
                    }
                    """;

            StrictMappingEngine.MappingOutcome result =
                    StrictMappingEngine.apply(USERS_JSON, mappingSpec);

            assertThat(result.itemCount).isEqualTo(3);
            assertThat(result.items.get(0).get("name")).isEqualTo("Alice");
        }

        @Test
        @DisplayName("should handle absolute path references")
        void shouldHandleAbsolutePathReferences() throws Exception {
            String json = """
                    {
                      "meta": {"version": "2.0"},
                      "items": [
                        {"id": 1, "name": "Item A"},
                        {"id": 2, "name": "Item B"}
                      ]
                    }
                    """;

            String mappingSpec = """
                    {
                      "source": {"format": "json", "items_path": "$.items[*]"},
                      "fields": {
                        "itemName": {"candidates": ["@.name"], "to": "string"},
                        "apiVersion": {"candidates": ["$.meta.version"], "to": "string"}
                      }
                    }
                    """;

            StrictMappingEngine.MappingOutcome result =
                    StrictMappingEngine.apply(json, mappingSpec);

            assertThat(result.itemCount).isEqualTo(2);
            assertThat(result.items.get(0).get("itemName")).isEqualTo("Item A");
            assertThat(result.items.get(0).get("apiVersion")).isEqualTo("2.0");
        }

        @Test
        @DisplayName("should handle ancestor traversal with ^^ prefix")
        void shouldHandleAncestorTraversal() throws Exception {
            String json = """
                    {
                      "company": "Acme Corp",
                      "departments": [
                        {
                          "name": "Engineering",
                          "employees": [
                            {"name": "Alice", "role": "Developer"}
                          ]
                        }
                      ]
                    }
                    """;

            String mappingSpec = """
                    {
                      "source": {"format": "json", "items_path": "$.departments[*].employees[*]"},
                      "fields": {
                        "employeeName": {"candidates": ["@.name"], "to": "string"},
                        "departmentName": {"candidates": ["^^.name"], "to": "string"}
                      }
                    }
                    """;

            StrictMappingEngine.MappingOutcome result =
                    StrictMappingEngine.apply(json, mappingSpec);

            assertThat(result.itemCount).isEqualTo(1);
            assertThat(result.items.get(0).get("employeeName")).isEqualTo("Alice");
            // Ancestor traversal (^^) is not supported, returns null
            assertThat(result.items.get(0).get("departmentName")).isNull();
        }

        @Test
        @DisplayName("should reject invalid absolute path")
        void shouldRejectInvalidAbsolutePath() {
            assertThatThrownBy(() -> {
                String mappingSpec = """
                        {
                          "source": {"format": "json", "items_path": "invalid_path"},
                          "fields": {
                            "name": {"candidates": ["@.name"], "to": "string"}
                          }
                        }
                        """;
                StrictMappingEngine.apply(USERS_JSON, mappingSpec);
            }).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("should handle long type conversion")
        void shouldHandleLongTypeConversion() throws Exception {
            String json = """
                    {
                      "records": [
                        {"id": 9999999999, "label": "big-id"}
                      ]
                    }
                    """;

            String mappingSpec = """
                    {
                      "source": {"format": "json", "items_path": "$.records[*]"},
                      "fields": {
                        "recordId": {"candidates": ["@.id"], "to": "long"}
                      }
                    }
                    """;

            StrictMappingEngine.MappingOutcome result =
                    StrictMappingEngine.apply(json, mappingSpec);

            assertThat(result.items.get(0).get("recordId")).isEqualTo(9999999999L);
        }
    }

    // -------------------------------------------------------------------------
    // Edge cases and error handling
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("should handle empty JSON object")
        void shouldHandleEmptyJsonObject() throws Exception {
            String mappingSpec = """
                    {
                      "source": {"format": "json"},
                      "fields": {
                        "name": {"candidates": ["@.name"], "to": "string"}
                      }
                    }
                    """;

            SimpleMappingEngine.MappingOutcome result =
                    SimpleMappingEngine.apply("{}", mappingSpec);

            // Empty object treated as single item with no resolved fields
            assertThat(result.itemCount).isEqualTo(1);
            assertThat(result.items.get(0)).isEmpty();
        }

        @Test
        @DisplayName("should handle empty array JSON")
        void shouldHandleEmptyArray() throws Exception {
            String json = """
                    {"data": {"users": []}}
                    """;

            String mappingSpec = """
                    {
                      "source": {"format": "json", "items_path": "$.data.users[*]"},
                      "fields": {
                        "name": {"candidates": ["@.name"], "to": "string"}
                      }
                    }
                    """;

            SimpleMappingEngine.MappingOutcome result =
                    SimpleMappingEngine.apply(json, mappingSpec);

            // Engine returns 1 item with null fields when array is empty
            assertThat(result.itemCount).isEqualTo(1);
        }

        @Test
        @DisplayName("should handle null values in JSON")
        void shouldHandleNullValues() throws Exception {
            String json = """
                    {
                      "items": [
                        {"name": null, "id": 1},
                        {"name": "Valid", "id": 2}
                      ]
                    }
                    """;

            String mappingSpec = """
                    {
                      "source": {"format": "json", "items_path": "$.items[*]"},
                      "fields": {
                        "name": {"candidates": ["@.name"], "to": "string"},
                        "id": {"candidates": ["@.id"], "to": "integer"}
                      }
                    }
                    """;

            SimpleMappingEngine.MappingOutcome result =
                    SimpleMappingEngine.apply(json, mappingSpec);

            assertThat(result.itemCount).isEqualTo(2);
            // First item should have id but not name (null)
            assertThat(result.items.get(0).get("id")).isEqualTo(1);
            // Second item should have both
            assertThat(result.items.get(1).get("name")).isEqualTo("Valid");
            assertThat(result.items.get(1).get("id")).isEqualTo(2);
        }

        @Test
        @DisplayName("should handle deeply nested structures")
        void shouldHandleDeeplyNestedStructures() throws Exception {
            String json = """
                    {
                      "level1": {
                        "level2": {
                          "level3": {
                            "items": [
                              {"value": "deep-value"}
                            ]
                          }
                        }
                      }
                    }
                    """;

            String mappingSpec = """
                    {
                      "source": {"format": "json", "items_path": "$.level1.level2.level3.items[*]"},
                      "fields": {
                        "val": {"candidates": ["@.value"], "to": "string"}
                      }
                    }
                    """;

            SimpleMappingEngine.MappingOutcome result =
                    SimpleMappingEngine.apply(json, mappingSpec);

            assertThat(result.itemCount).isEqualTo(1);
            assertThat(result.items.get(0).get("val")).isEqualTo("deep-value");
        }

        @Test
        @DisplayName("should handle mapping with multiple items and mixed types")
        void shouldHandleMixedTypes() throws Exception {
            String json = """
                    {
                      "products": [
                        {"name": "Widget", "price": 19.99, "quantity": 100, "available": true},
                        {"name": "Gadget", "price": 49.99, "quantity": 50, "available": false}
                      ]
                    }
                    """;

            String mappingSpec = """
                    {
                      "source": {"format": "json", "items_path": "$.products[*]"},
                      "fields": {
                        "productName": {"candidates": ["@.name"], "to": "string"},
                        "productPrice": {"candidates": ["@.price"], "to": "number"},
                        "qty": {"candidates": ["@.quantity"], "to": "integer"},
                        "inStock": {"candidates": ["@.available"], "to": "boolean"}
                      }
                    }
                    """;

            SimpleMappingEngine.MappingOutcome simpleResult =
                    SimpleMappingEngine.apply(json, mappingSpec);
            StrictMappingEngine.MappingOutcome strictResult =
                    StrictMappingEngine.apply(json, mappingSpec);

            // Verify both engines produce 2 items
            assertThat(simpleResult.itemCount).isEqualTo(2);
            assertThat(strictResult.itemCount).isEqualTo(2);

            // Verify types
            assertThat(simpleResult.items.get(0).get("productName")).isEqualTo("Widget");
            assertThat(simpleResult.items.get(0).get("productPrice")).isEqualTo(19.99);
            assertThat(simpleResult.items.get(0).get("qty")).isEqualTo(100);
            assertThat(simpleResult.items.get(0).get("inStock")).isEqualTo(true);
        }
    }
}
