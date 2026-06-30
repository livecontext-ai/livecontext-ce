package com.apimarketplace.orchestrator.services.template;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for PathNavigator service.
 *
 * This service handles navigation through nested Maps using dot-separated paths.
 * It also supports automatic navigation through "output" sub-maps.
 */
@DisplayName("PathNavigator")
class PathNavigatorTest {

    private PathNavigator pathNavigator;

    @BeforeEach
    void setUp() {
        pathNavigator = new PathNavigator();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // navigatePath() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("navigatePath()")
    class NavigatePathTests {

        @Test
        @DisplayName("Should return root when path is null")
        void shouldReturnRootWhenPathIsNull() {
            Map<String, Object> root = Map.of("name", "John");
            assertSame(root, pathNavigator.navigatePath(root, null));
        }

        @Test
        @DisplayName("Should return root when path is empty")
        void shouldReturnRootWhenPathIsEmpty() {
            Map<String, Object> root = Map.of("name", "John");
            assertSame(root, pathNavigator.navigatePath(root, ""));
        }

        @Test
        @DisplayName("Should return null when root is null")
        void shouldReturnNullWhenRootIsNull() {
            assertNull(pathNavigator.navigatePath(null, "name"));
        }

        @Test
        @DisplayName("Should navigate simple path")
        void shouldNavigateSimplePath() {
            Map<String, Object> root = Map.of("name", "John", "age", 30);
            assertEquals("John", pathNavigator.navigatePath(root, "name"));
            assertEquals(30, pathNavigator.navigatePath(root, "age"));
        }

        @Test
        @DisplayName("Should navigate nested path")
        void shouldNavigateNestedPath() {
            Map<String, Object> address = Map.of("city", "Paris", "zip", "75001");
            Map<String, Object> root = Map.of("user", Map.of("name", "John", "address", address));

            assertEquals("John", pathNavigator.navigatePath(root, "user.name"));
            assertEquals("Paris", pathNavigator.navigatePath(root, "user.address.city"));
            assertEquals("75001", pathNavigator.navigatePath(root, "user.address.zip"));
        }

        @Test
        @DisplayName("Should return null for non-existent path")
        void shouldReturnNullForNonExistentPath() {
            Map<String, Object> root = Map.of("name", "John");
            assertNull(pathNavigator.navigatePath(root, "nonexistent"));
            assertNull(pathNavigator.navigatePath(root, "name.nested"));
        }

        @Test
        @DisplayName("Should return null when intermediate value is null")
        void shouldReturnNullWhenIntermediateValueIsNull() {
            Map<String, Object> root = new HashMap<>();
            root.put("user", null);
            assertNull(pathNavigator.navigatePath(root, "user.name"));
        }

        @Test
        @DisplayName("Should return null when navigating through non-map")
        void shouldReturnNullWhenNavigatingThroughNonMap() {
            Map<String, Object> root = Map.of("name", "John");
            assertNull(pathNavigator.navigatePath(root, "name.nested"));
        }

        @Test
        @DisplayName("Should navigate path with array access")
        void shouldNavigatePathWithArrayAccess() {
            Map<String, Object> root = Map.of(
                "edges", List.of(
                    Map.of("node", Map.of("text", "hello")),
                    Map.of("node", Map.of("text", "world"))
                )
            );
            assertEquals("hello", pathNavigator.navigatePath(root, "edges[0].node.text"));
            assertEquals("world", pathNavigator.navigatePath(root, "edges[1].node.text"));
        }

        @Test
        @DisplayName("Should return null for out-of-bounds array access")
        void shouldReturnNullForOutOfBoundsArrayAccess() {
            Map<String, Object> root = Map.of("items", List.of("a", "b"));
            assertNull(pathNavigator.navigatePath(root, "items[5]"));
        }

        @Test
        @DisplayName("Should handle array access on non-list value")
        void shouldHandleArrayAccessOnNonListValue() {
            Map<String, Object> root = Map.of("name", "John");
            assertNull(pathNavigator.navigatePath(root, "name[0]"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // navigateMapPath() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("navigateMapPath()")
    class NavigateMapPathTests {

        @Test
        @DisplayName("Should return map when path is null")
        void shouldReturnMapWhenPathIsNull() {
            Map<String, Object> map = Map.of("name", "John");
            assertSame(map, pathNavigator.navigateMapPath(map, null));
        }

        @Test
        @DisplayName("Should return map when path is empty")
        void shouldReturnMapWhenPathIsEmpty() {
            Map<String, Object> map = Map.of("name", "John");
            assertSame(map, pathNavigator.navigateMapPath(map, ""));
        }

        @Test
        @DisplayName("Should return null when map is null")
        void shouldReturnNullWhenMapIsNull() {
            assertNull(pathNavigator.navigateMapPath(null, "name"));
        }

        @Test
        @DisplayName("Should navigate simple path")
        void shouldNavigateSimplePath() {
            Map<String, Object> map = Map.of("name", "John", "age", 30);
            assertEquals("John", pathNavigator.navigateMapPath(map, "name"));
        }

        @Test
        @DisplayName("Should navigate nested path")
        void shouldNavigateNestedPath() {
            Map<String, Object> map = Map.of("user", Map.of("name", "John"));
            assertEquals("John", pathNavigator.navigateMapPath(map, "user.name"));
        }

        @Test
        @DisplayName("Should find value in output sub-map")
        void shouldFindValueInOutputSubMap() {
            Map<String, Object> output = Map.of("result", "success", "data", Map.of("id", 123));
            Map<String, Object> map = Map.of("output", output);

            assertEquals("success", pathNavigator.navigateMapPath(map, "result"));
            assertEquals(123, pathNavigator.navigateMapPath(map, "data.id"));
        }

        @Test
        @DisplayName("Should prefer direct key over output sub-map")
        void shouldPreferDirectKeyOverOutputSubMap() {
            Map<String, Object> output = Map.of("name", "from_output");
            Map<String, Object> map = Map.of("name", "direct", "output", output);

            assertEquals("direct", pathNavigator.navigateMapPath(map, "name"));
        }

        @Test
        @DisplayName("Should return null for non-existent path")
        void shouldReturnNullForNonExistentPath() {
            Map<String, Object> map = Map.of("name", "John");
            assertNull(pathNavigator.navigateMapPath(map, "nonexistent"));
        }

        @Test
        @DisplayName("Should return null when navigating through non-map")
        void shouldReturnNullWhenNavigatingThroughNonMap() {
            Map<String, Object> map = Map.of("name", "John");
            assertNull(pathNavigator.navigateMapPath(map, "name.nested"));
        }

        @Test
        @DisplayName("Should navigate map path with array access in middle")
        void shouldNavigateMapPathWithArrayAccessInMiddle() {
            Map<String, Object> map = Map.of(
                "output", Map.of(
                    "edge_media_to_caption", Map.of(
                        "edges", List.of(
                            Map.of("node", Map.of("text", "caption text"))
                        )
                    )
                )
            );
            assertEquals("caption text",
                pathNavigator.navigateMapPath(map, "output.edge_media_to_caption.edges[0].node.text"));
        }

        @Test
        @DisplayName("Should navigate map path with array access via output fallback")
        void shouldNavigateMapPathWithArrayAccessViaOutputFallback() {
            Map<String, Object> map = Map.of(
                "output", Map.of(
                    "items", List.of("first", "second", "third")
                )
            );
            assertEquals("second", pathNavigator.navigateMapPath(map, "items[1]"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // getVariableValueFromMap() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getVariableValueFromMap()")
    class GetVariableValueFromMapTests {

        @Test
        @DisplayName("Should get simple variable value")
        void shouldGetSimpleVariableValue() {
            Map<String, Object> variables = Map.of("name", "John", "age", 30);
            assertEquals("John", pathNavigator.getVariableValueFromMap("name", variables));
            assertEquals(30, pathNavigator.getVariableValueFromMap("age", variables));
        }

        @Test
        @DisplayName("Should get nested variable value")
        void shouldGetNestedVariableValue() {
            Map<String, Object> user = Map.of("name", "John", "address", Map.of("city", "Paris"));
            Map<String, Object> variables = Map.of("user", user);

            assertEquals("John", pathNavigator.getVariableValueFromMap("user.name", variables));
            assertEquals("Paris", pathNavigator.getVariableValueFromMap("user.address.city", variables));
        }

        @Test
        @DisplayName("Should fall back to lowercase key")
        void shouldFallBackToLowercaseKey() {
            Map<String, Object> variables = new HashMap<>();
            variables.put("username", "john");

            assertEquals("john", pathNavigator.getVariableValueFromMap("USERNAME", variables));
            assertEquals("john", pathNavigator.getVariableValueFromMap("UserName", variables));
        }

        @Test
        @DisplayName("Should prefer exact match over lowercase")
        void shouldPreferExactMatchOverLowercase() {
            Map<String, Object> variables = new HashMap<>();
            variables.put("name", "exact");
            variables.put("NAME", "uppercase");

            assertEquals("uppercase", pathNavigator.getVariableValueFromMap("NAME", variables));
            assertEquals("exact", pathNavigator.getVariableValueFromMap("name", variables));
        }

        @Test
        @DisplayName("Should return null for non-existent variable")
        void shouldReturnNullForNonExistentVariable() {
            Map<String, Object> variables = Map.of("name", "John");
            assertNull(pathNavigator.getVariableValueFromMap("nonexistent", variables));
        }

        @Test
        @DisplayName("Should return null when base is not a map for nested path")
        void shouldReturnNullWhenBaseIsNotMapForNestedPath() {
            Map<String, Object> variables = Map.of("name", "John");
            assertNull(pathNavigator.getVariableValueFromMap("name.nested", variables));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // getNestedValueFromMap() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getNestedValueFromMap()")
    class GetNestedValueFromMapTests {

        @Test
        @DisplayName("Should get simple nested value")
        void shouldGetSimpleNestedValue() {
            Map<String, Object> map = Map.of("name", "John", "age", 30);
            assertEquals("John", pathNavigator.getNestedValueFromMap(map, "name"));
        }

        @Test
        @DisplayName("Should get deeply nested value")
        void shouldGetDeeplyNestedValue() {
            Map<String, Object> level2 = Map.of("value", "deep");
            Map<String, Object> level1 = Map.of("level2", level2);
            Map<String, Object> map = Map.of("level1", level1);

            assertEquals("deep", pathNavigator.getNestedValueFromMap(map, "level1.level2.value"));
        }

        @Test
        @DisplayName("Should find value in output sub-map")
        void shouldFindValueInOutputSubMap() {
            Map<String, Object> output = Map.of("status", 200, "body", Map.of("id", 1));
            Map<String, Object> map = Map.of("output", output);

            assertEquals(200, pathNavigator.getNestedValueFromMap(map, "status"));
            assertEquals(1, pathNavigator.getNestedValueFromMap(map, "body.id"));
        }

        @Test
        @DisplayName("Should prefer direct key over output sub-map")
        void shouldPreferDirectKeyOverOutputSubMap() {
            Map<String, Object> output = Map.of("key", "from_output");
            Map<String, Object> map = Map.of("key", "direct", "output", output);

            assertEquals("direct", pathNavigator.getNestedValueFromMap(map, "key"));
        }

        @Test
        @DisplayName("Should return null for non-existent key")
        void shouldReturnNullForNonExistentKey() {
            Map<String, Object> map = Map.of("name", "John");
            assertNull(pathNavigator.getNestedValueFromMap(map, "nonexistent"));
        }

        @Test
        @DisplayName("Should return null when navigating through non-map")
        void shouldReturnNullWhenNavigatingThroughNonMap() {
            Map<String, Object> map = Map.of("name", "John");
            assertNull(pathNavigator.getNestedValueFromMap(map, "name.nested"));
        }
    }
}
