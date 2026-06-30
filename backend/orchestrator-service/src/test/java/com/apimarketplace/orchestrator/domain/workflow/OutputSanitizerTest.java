package com.apimarketplace.orchestrator.domain.workflow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("OutputSanitizer")
class OutputSanitizerTest {

    @Nested
    @DisplayName("sanitize()")
    class SanitizeTests {

        @Test
        @DisplayName("Should return empty map for null input")
        void shouldReturnEmptyForNull() {
            Map<String, Object> result = OutputSanitizer.sanitize(null);
            assertNotNull(result);
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("Should return empty map for empty input")
        void shouldReturnEmptyForEmptyMap() {
            Map<String, Object> result = OutputSanitizer.sanitize(Map.of());
            assertNotNull(result);
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("Should pass through primitive values")
        void shouldPassThroughPrimitives() {
            Map<String, Object> source = new HashMap<>();
            source.put("str", "hello");
            source.put("num", 42);
            source.put("bool", true);
            source.put("dbl", 3.14);

            Map<String, Object> result = OutputSanitizer.sanitize(source);

            assertEquals("hello", result.get("str"));
            assertEquals(42, result.get("num"));
            assertEquals(true, result.get("bool"));
            assertEquals(3.14, result.get("dbl"));
        }

        @Test
        @DisplayName("Should handle null values in map")
        void shouldHandleNullValues() {
            Map<String, Object> source = new HashMap<>();
            source.put("key", null);

            Map<String, Object> result = OutputSanitizer.sanitize(source);
            assertNull(result.get("key"));
        }

        @Test
        @DisplayName("Should skip null keys")
        void shouldSkipNullKeys() {
            Map<String, Object> source = new HashMap<>();
            source.put(null, "value");
            source.put("valid", "data");

            Map<String, Object> result = OutputSanitizer.sanitize(source);
            assertEquals(1, result.size());
            assertEquals("data", result.get("valid"));
        }

        @Test
        @DisplayName("Should convert enum values to string")
        void shouldConvertEnumToString() {
            Map<String, Object> source = new HashMap<>();
            source.put("status", RunStatus.COMPLETED);

            Map<String, Object> result = OutputSanitizer.sanitize(source);
            assertEquals("COMPLETED", result.get("status"));
        }

        @Test
        @DisplayName("Should sanitize nested maps")
        void shouldSanitizeNestedMaps() {
            Map<String, Object> nested = new HashMap<>();
            nested.put("innerKey", "innerValue");
            Map<String, Object> source = new HashMap<>();
            source.put("nested", nested);

            Map<String, Object> result = OutputSanitizer.sanitize(source);
            @SuppressWarnings("unchecked")
            Map<String, Object> resultNested = (Map<String, Object>) result.get("nested");
            assertNotNull(resultNested);
            assertEquals("innerValue", resultNested.get("innerKey"));
        }

        @Test
        @DisplayName("Should sanitize collections")
        void shouldSanitizeCollections() {
            Map<String, Object> source = new HashMap<>();
            source.put("list", List.of("a", "b", "c"));

            Map<String, Object> result = OutputSanitizer.sanitize(source);
            @SuppressWarnings("unchecked")
            List<Object> resultList = (List<Object>) result.get("list");
            assertNotNull(resultList);
            assertEquals(3, resultList.size());
            assertEquals("a", resultList.get(0));
        }

        @Test
        @DisplayName("Should detect circular references in maps")
        void shouldDetectCircularReferencesInMaps() {
            Map<String, Object> source = new HashMap<>();
            Map<String, Object> child = new HashMap<>();
            child.put("parent", source);
            source.put("child", child);

            Map<String, Object> result = OutputSanitizer.sanitize(source);
            @SuppressWarnings("unchecked")
            Map<String, Object> resultChild = (Map<String, Object>) result.get("child");
            // source is not tracked in visited by the top-level sanitize() call,
            // so child.parent -> source is processed as a new map, and within that
            // source.child -> child is detected as circular. So parent becomes {child=<circular>}.
            @SuppressWarnings("unchecked")
            Map<String, Object> parentRef = (Map<String, Object>) resultChild.get("parent");
            assertNotNull(parentRef);
            assertEquals("<circular>", parentRef.get("child"));
        }

        @Test
        @DisplayName("Should detect circular references in collections")
        void shouldDetectCircularReferencesInCollections() {
            List<Object> list = new ArrayList<>();
            Map<String, Object> source = new HashMap<>();
            list.add("normal");
            list.add(list); // self-reference
            source.put("list", list);

            Map<String, Object> result = OutputSanitizer.sanitize(source);
            @SuppressWarnings("unchecked")
            List<Object> resultList = (List<Object>) result.get("list");
            assertEquals("normal", resultList.get(0));
            // Second element is the circular reference
            assertEquals(List.of("<circular>"), resultList.get(1));
        }

        @Test
        @DisplayName("Should convert arrays to lists")
        void shouldConvertArraysToLists() {
            Map<String, Object> source = new HashMap<>();
            source.put("arr", new int[]{1, 2, 3});

            Map<String, Object> result = OutputSanitizer.sanitize(source);
            @SuppressWarnings("unchecked")
            List<Object> resultList = (List<Object>) result.get("arr");
            assertNotNull(resultList);
            assertEquals(3, resultList.size());
            assertEquals(1, resultList.get(0));
        }

        @Test
        @DisplayName("Should convert unknown types via toString()")
        void shouldConvertUnknownTypesViaToString() {
            Map<String, Object> source = new HashMap<>();
            source.put("custom", new StringBuilder("test"));

            Map<String, Object> result = OutputSanitizer.sanitize(source);
            assertEquals("test", result.get("custom"));
        }

        @Test
        @DisplayName("Should handle StepExecutionResult")
        void shouldHandleStepExecutionResult() {
            StepExecutionResult ser = StepExecutionResult.success("step1", Map.of("key", "val"), 100L);
            Map<String, Object> source = new HashMap<>();
            source.put("result", ser);

            Map<String, Object> result = OutputSanitizer.sanitize(source);
            @SuppressWarnings("unchecked")
            Map<String, Object> resultSer = (Map<String, Object>) result.get("result");

            assertNotNull(resultSer);
            assertEquals("step1", resultSer.get("stepId"));
            assertEquals("COMPLETED", resultSer.get("status"));
            assertEquals("Success", resultSer.get("message"));
            assertEquals(100L, resultSer.get("executionTime"));
        }

        @Test
        @DisplayName("Should handle StepExecutionResult with error")
        void shouldHandleStepExecutionResultWithError() {
            StepExecutionResult ser = StepExecutionResult.failure("step2", "oops",
                new RuntimeException("boom"), 50L);
            Map<String, Object> source = new HashMap<>();
            source.put("result", ser);

            Map<String, Object> result = OutputSanitizer.sanitize(source);
            @SuppressWarnings("unchecked")
            Map<String, Object> resultSer = (Map<String, Object>) result.get("result");

            assertEquals("FAILED", resultSer.get("status"));
            assertEquals("RuntimeException", resultSer.get("error"));
        }

        @Test
        @DisplayName("Should convert non-string map keys to strings")
        void shouldConvertNonStringMapKeys() {
            Map<Object, Object> nested = new HashMap<>();
            nested.put(42, "numericKey");
            Map<String, Object> source = new HashMap<>();
            source.put("map", nested);

            Map<String, Object> result = OutputSanitizer.sanitize(source);
            @SuppressWarnings("unchecked")
            Map<String, Object> resultMap = (Map<String, Object>) result.get("map");
            assertEquals("numericKey", resultMap.get("42"));
        }
    }
}
