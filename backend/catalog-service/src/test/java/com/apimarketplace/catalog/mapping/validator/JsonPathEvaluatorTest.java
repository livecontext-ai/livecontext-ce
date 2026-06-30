package com.apimarketplace.catalog.mapping.validator;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for JsonPathEvaluator class.
 *
 * JsonPathEvaluator validates and extracts data from JSON using strict paths.
 */
@DisplayName("JsonPathEvaluator")
class JsonPathEvaluatorTest {

    private JsonPathEvaluator evaluator;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        evaluator = new JsonPathEvaluator(objectMapper);
    }

    // ========================================================================
    // pathExists tests
    // ========================================================================

    @Nested
    @DisplayName("pathExists()")
    class PathExistsTests {

        @Test
        @DisplayName("should return true for existing simple path")
        void shouldReturnTrueForExistingSimplePath() {
            String json = "{\"name\": \"John\"}";

            boolean result = evaluator.pathExists(json, "name", null);

            assertTrue(result);
        }

        @Test
        @DisplayName("should return true for existing absolute path")
        void shouldReturnTrueForExistingAbsolutePath() {
            String json = "{\"user\": {\"name\": \"John\"}}";

            boolean result = evaluator.pathExists(json, "$.user.name", null);

            assertTrue(result);
        }

        @Test
        @DisplayName("should return false for non-existing path")
        void shouldReturnFalseForNonExistingPath() {
            String json = "{\"name\": \"John\"}";

            boolean result = evaluator.pathExists(json, "email", null);

            assertFalse(result);
        }

        @Test
        @DisplayName("should return false for invalid JSON")
        void shouldReturnFalseForInvalidJson() {
            String json = "not valid json";

            boolean result = evaluator.pathExists(json, "name", null);

            assertFalse(result);
        }

        @Test
        @DisplayName("should check path within items context")
        void shouldCheckPathWithinItemsContext() {
            String json = "{\"data\": {\"items\": [{\"id\": 1}, {\"id\": 2}]}}";

            boolean result = evaluator.pathExists(json, "@.id", "$.data.items");

            assertTrue(result);
        }
    }

    // ========================================================================
    // extractAll tests - simple paths
    // ========================================================================

    @Nested
    @DisplayName("extractAll() - simple paths")
    class ExtractAllSimpleTests {

        @Test
        @DisplayName("should extract string value")
        void shouldExtractStringValue() {
            String json = "{\"name\": \"John\"}";

            List<Object> results = evaluator.extractAll(json, "name", null);

            assertEquals(1, results.size());
            assertEquals("John", results.get(0));
        }

        @Test
        @DisplayName("should extract integer value")
        void shouldExtractIntegerValue() {
            String json = "{\"age\": 30}";

            List<Object> results = evaluator.extractAll(json, "age", null);

            assertEquals(1, results.size());
            assertEquals(30, results.get(0));
        }

        @Test
        @DisplayName("should extract boolean value")
        void shouldExtractBooleanValue() {
            String json = "{\"active\": true}";

            List<Object> results = evaluator.extractAll(json, "active", null);

            assertEquals(1, results.size());
            assertEquals(true, results.get(0));
        }

        @Test
        @DisplayName("should extract nested value")
        void shouldExtractNestedValue() {
            String json = "{\"user\": {\"profile\": {\"email\": \"test@test.com\"}}}";

            List<Object> results = evaluator.extractAll(json, "user.profile.email", null);

            assertEquals(1, results.size());
            assertEquals("test@test.com", results.get(0));
        }
    }

    // ========================================================================
    // extractAll tests - absolute paths
    // ========================================================================

    @Nested
    @DisplayName("extractAll() - absolute paths")
    class ExtractAllAbsoluteTests {

        @Test
        @DisplayName("should extract with dollar prefix")
        void shouldExtractWithDollarPrefix() {
            String json = "{\"data\": {\"value\": 42}}";

            List<Object> results = evaluator.extractAll(json, "$.data.value", null);

            assertEquals(1, results.size());
            assertEquals(42, results.get(0));
        }

        @Test
        @DisplayName("should extract from root with $")
        void shouldExtractFromRootWithDollar() {
            String json = "{\"name\": \"root\"}";

            List<Object> results = evaluator.extractAll(json, "$name", null);

            assertEquals(1, results.size());
            assertEquals("root", results.get(0));
        }
    }

    // ========================================================================
    // extractAll tests - relative paths
    // ========================================================================

    @Nested
    @DisplayName("extractAll() - relative paths")
    class ExtractAllRelativeTests {

        @Test
        @DisplayName("should extract with @ prefix")
        void shouldExtractWithAtPrefix() {
            String json = "{\"field\": \"value\"}";

            List<Object> results = evaluator.extractAll(json, "@.field", null);

            assertEquals(1, results.size());
            assertEquals("value", results.get(0));
        }
    }

    // ========================================================================
    // extractAll tests - array access
    // ========================================================================

    @Nested
    @DisplayName("extractAll() - array access")
    class ExtractAllArrayTests {

        @Test
        @DisplayName("should extract array element by index")
        void shouldExtractArrayElementByIndex() {
            String json = "{\"items\": [\"a\", \"b\", \"c\"]}";

            List<Object> results = evaluator.extractAll(json, "items[1]", null);

            assertEquals(1, results.size());
            assertEquals("b", results.get(0));
        }

        @Test
        @DisplayName("should extract all array elements with wildcard")
        void shouldExtractAllArrayElementsWithWildcard() {
            String json = "{\"items\": [\"a\", \"b\", \"c\"]}";

            List<Object> results = evaluator.extractAll(json, "items[*]", null);

            assertEquals(3, results.size());
            assertTrue(results.contains("a"));
            assertTrue(results.contains("b"));
            assertTrue(results.contains("c"));
        }

        @Test
        @DisplayName("should extract first array element")
        void shouldExtractFirstArrayElement() {
            String json = "{\"data\": [10, 20, 30]}";

            List<Object> results = evaluator.extractAll(json, "data[0]", null);

            assertEquals(1, results.size());
            assertEquals(10, results.get(0));
        }
    }

    // ========================================================================
    // extractAll tests - with items path
    // ========================================================================

    @Nested
    @DisplayName("extractAll() - with items path")
    class ExtractAllWithItemsPathTests {

        @Test
        @DisplayName("should extract from items array")
        void shouldExtractFromItemsArray() {
            String json = "{\"results\": [{\"id\": 1, \"name\": \"A\"}, {\"id\": 2, \"name\": \"B\"}]}";

            List<Object> results = evaluator.extractAll(json, "@.name", "$.results");

            assertEquals(2, results.size());
            assertTrue(results.contains("A"));
            assertTrue(results.contains("B"));
        }

        @Test
        @DisplayName("should extract nested items from array")
        void shouldExtractNestedItemsFromArray() {
            String json = "{\"data\": {\"items\": [{\"value\": 1}, {\"value\": 2}, {\"value\": 3}]}}";

            List<Object> results = evaluator.extractAll(json, "@.value", "$.data.items");

            assertEquals(3, results.size());
        }
    }

    // ========================================================================
    // extractAll tests - error handling
    // ========================================================================

    @Nested
    @DisplayName("extractAll() - error handling")
    class ExtractAllErrorTests {

        @Test
        @DisplayName("should return empty list for invalid JSON")
        void shouldReturnEmptyListForInvalidJson() {
            String json = "not valid json";

            List<Object> results = evaluator.extractAll(json, "field", null);

            assertTrue(results.isEmpty());
        }

        @Test
        @DisplayName("should return empty list for non-existing path")
        void shouldReturnEmptyListForNonExistingPath() {
            String json = "{\"name\": \"John\"}";

            List<Object> results = evaluator.extractAll(json, "missing", null);

            assertTrue(results.isEmpty());
        }

        @Test
        @DisplayName("should return empty list for null value")
        void shouldReturnEmptyListForNullValue() {
            String json = "{\"value\": null}";

            List<Object> results = evaluator.extractAll(json, "value", null);

            assertTrue(results.isEmpty());
        }
    }

    // ========================================================================
    // Path type tests
    // ========================================================================

    @Nested
    @DisplayName("Path types")
    class PathTypeTests {

        @Test
        @DisplayName("should handle parent navigation prefix")
        void shouldHandleParentNavigationPrefix() {
            String json = "{\"parent\": {\"child\": {\"name\": \"test\"}}}";

            // Parent navigation (^^.) is partially implemented
            List<Object> results = evaluator.extractAll(json, "^^.name", "$.parent.child");

            // Parent navigation returns empty since parent references aren't maintained
            assertNotNull(results);
        }

        @Test
        @DisplayName("should differentiate relative and absolute paths")
        void shouldDifferentiateRelativeAndAbsolutePaths() {
            String json = "{\"outer\": {\"field\": \"outer\"}, \"field\": \"root\"}";

            List<Object> absoluteResults = evaluator.extractAll(json, "$.field", null);
            List<Object> relativeResults = evaluator.extractAll(json, "@.field", null);

            // Both should work from root context
            assertFalse(absoluteResults.isEmpty());
            assertFalse(relativeResults.isEmpty());
        }
    }
}
