package com.apimarketplace.orchestrator.execution.v2.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("OutputUnwrapper Tests")
class OutputUnwrapperTest {

    // =========================================================================
    // unwrapOutput(Map)
    // =========================================================================

    @Nested
    @DisplayName("unwrapOutput(Map)")
    class UnwrapOutputMapTests {

        @Test
        @DisplayName("Should return null for null input")
        void shouldReturnNullForNullInput() {
            assertNull(OutputUnwrapper.unwrapOutput(null));
        }

        @Test
        @DisplayName("Should unwrap single output key with Map value")
        void shouldUnwrapSingleOutputKey() {
            Map<String, Object> inner = Map.of("data", "value", "count", 5);
            Map<String, Object> wrapped = Map.of("output", inner);

            Map<String, Object> result = OutputUnwrapper.unwrapOutput(wrapped);

            assertEquals(inner, result);
            assertEquals("value", result.get("data"));
            assertEquals(5, result.get("count"));
        }

        @Test
        @DisplayName("Should return original map if output key has non-Map value")
        void shouldReturnOriginalIfOutputValueNotMap() {
            Map<String, Object> wrapped = Map.of("output", "not_a_map");

            Map<String, Object> result = OutputUnwrapper.unwrapOutput(wrapped);

            assertEquals(wrapped, result);
        }

        @Test
        @DisplayName("Should return original map if more than one key")
        void shouldReturnOriginalIfMultipleKeys() {
            Map<String, Object> map = new HashMap<>();
            map.put("output", Map.of("inner", "data"));
            map.put("extra", "value");

            Map<String, Object> result = OutputUnwrapper.unwrapOutput(map);

            assertSame(map, result);
        }

        @Test
        @DisplayName("Should return original map if no output key")
        void shouldReturnOriginalIfNoOutputKey() {
            Map<String, Object> map = Map.of("data", "value", "count", 5);

            Map<String, Object> result = OutputUnwrapper.unwrapOutput(map);

            assertSame(map, result);
        }

        @Test
        @DisplayName("Should return empty map as-is")
        void shouldReturnEmptyMapAsIs() {
            Map<String, Object> map = Map.of();

            Map<String, Object> result = OutputUnwrapper.unwrapOutput(map);

            assertSame(map, result);
        }
    }

    // =========================================================================
    // unwrapOutputWithBranchDetection(Map)
    // =========================================================================

    @Nested
    @DisplayName("unwrapOutputWithBranchDetection(Map)")
    class UnwrapOutputWithBranchDetectionTests {

        @Test
        @DisplayName("Should return null for null input")
        void shouldReturnNullForNullInput() {
            assertNull(OutputUnwrapper.unwrapOutputWithBranchDetection(null));
        }

        @Test
        @DisplayName("Should unwrap single output key - same as basic unwrap")
        void shouldUnwrapSingleOutputKey() {
            Map<String, Object> inner = Map.of("data", "value");
            Map<String, Object> wrapped = Map.of("output", inner);

            Map<String, Object> result = OutputUnwrapper.unwrapOutputWithBranchDetection(wrapped);

            assertEquals(inner, result);
        }

        @Test
        @DisplayName("Should unwrap when output key has inner with selected_branch_index")
        void shouldUnwrapWhenInnerHasSelectedBranchIndex() {
            Map<String, Object> inner = Map.of("selected_branch_index", 0, "node_type", "decision");
            Map<String, Object> wrapped = new HashMap<>();
            wrapped.put("output", inner);
            wrapped.put("other_key", "other_value");

            Map<String, Object> result = OutputUnwrapper.unwrapOutputWithBranchDetection(wrapped);

            assertEquals(inner, result);
            assertEquals(0, result.get("selected_branch_index"));
        }

        @Test
        @DisplayName("Should unwrap when output key has inner with node_type")
        void shouldUnwrapWhenInnerHasNodeType() {
            Map<String, Object> inner = Map.of("node_type", "DECISION", "selected_branch", "if");
            Map<String, Object> wrapped = new HashMap<>();
            wrapped.put("output", inner);
            wrapped.put("extra", "data");

            Map<String, Object> result = OutputUnwrapper.unwrapOutputWithBranchDetection(wrapped);

            assertEquals(inner, result);
        }

        @Test
        @DisplayName("Should NOT unwrap when selected_branch_index is already at root level")
        void shouldNotUnwrapWhenBranchIndexAtRoot() {
            Map<String, Object> inner = Map.of("inner_data", "value");
            Map<String, Object> map = new HashMap<>();
            map.put("output", inner);
            map.put("selected_branch_index", 1);

            Map<String, Object> result = OutputUnwrapper.unwrapOutputWithBranchDetection(map);

            assertSame(map, result);
        }

        @Test
        @DisplayName("Should unwrap when output key has inner with selected_port (User Approval)")
        void shouldUnwrapWhenInnerHasSelectedPort() {
            Map<String, Object> inner = new HashMap<>();
            inner.put("selected_port", "approved");
            inner.put("resolution", "APPROVED");
            inner.put("resolved_by", "user@test.com");
            inner.put("signal_type", "USER_APPROVAL");
            Map<String, Object> wrapped = new HashMap<>();
            wrapped.put("output", inner);
            wrapped.put("statusValue", "COMPLETED");

            Map<String, Object> result = OutputUnwrapper.unwrapOutputWithBranchDetection(wrapped);

            assertEquals(inner, result);
            assertEquals("approved", result.get("selected_port"));
            assertEquals("APPROVED", result.get("resolution"));
        }

        @Test
        @DisplayName("Should return original when output inner has no decision data")
        void shouldReturnOriginalWhenInnerHasNoDecisionData() {
            Map<String, Object> inner = Map.of("plain", "data");
            Map<String, Object> map = new HashMap<>();
            map.put("output", inner);
            map.put("extra", "value");

            Map<String, Object> result = OutputUnwrapper.unwrapOutputWithBranchDetection(map);

            assertSame(map, result);
        }

        @Test
        @DisplayName("Should return original map if no output key")
        void shouldReturnOriginalIfNoOutputKey() {
            Map<String, Object> map = Map.of("data", "value", "count", 5);

            Map<String, Object> result = OutputUnwrapper.unwrapOutputWithBranchDetection(map);

            assertSame(map, result);
        }

        @Test
        @DisplayName("Should handle output key with non-Map value in extended check")
        void shouldHandleNonMapValueInExtendedCheck() {
            Map<String, Object> map = new HashMap<>();
            map.put("output", "string_value");
            map.put("extra", "data");

            Map<String, Object> result = OutputUnwrapper.unwrapOutputWithBranchDetection(map);

            assertSame(map, result);
        }
    }

    // =========================================================================
    // unwrapOutputObject(Object)
    // =========================================================================

    @Nested
    @DisplayName("unwrapOutputObject(Object)")
    class UnwrapOutputObjectTests {

        @Test
        @DisplayName("Should return null for null input")
        void shouldReturnNullForNullInput() {
            assertNull(OutputUnwrapper.unwrapOutputObject(null));
        }

        @Test
        @DisplayName("Should unwrap Map with single output key")
        void shouldUnwrapMapWithSingleOutputKey() {
            Map<String, Object> inner = Map.of("data", "value");
            Map<String, Object> wrapped = Map.of("output", inner);

            Object result = OutputUnwrapper.unwrapOutputObject(wrapped);

            assertEquals(inner, result);
        }

        @Test
        @DisplayName("Should return original Map if output value is null")
        void shouldReturnOriginalIfOutputValueNull() {
            Map<String, Object> map = new HashMap<>();
            map.put("output", null);

            Object result = OutputUnwrapper.unwrapOutputObject(map);

            assertSame(map, result);
        }

        @Test
        @DisplayName("Should return original Map if multiple keys")
        void shouldReturnOriginalMapIfMultipleKeys() {
            Map<String, Object> map = new HashMap<>();
            map.put("output", Map.of("inner", "data"));
            map.put("extra", "value");

            Object result = OutputUnwrapper.unwrapOutputObject(map);

            assertSame(map, result);
        }

        @Test
        @DisplayName("Should return non-Map input as-is")
        void shouldReturnNonMapInputAsIs() {
            String str = "plain_string";
            assertEquals(str, OutputUnwrapper.unwrapOutputObject(str));

            Integer num = 42;
            assertEquals(num, OutputUnwrapper.unwrapOutputObject(num));

            List<String> list = List.of("a", "b");
            assertSame(list, OutputUnwrapper.unwrapOutputObject(list));
        }

        @Test
        @DisplayName("Should unwrap Map with output key containing a List")
        void shouldUnwrapMapWithOutputKeyContainingList() {
            List<String> innerList = List.of("item1", "item2");
            Map<String, Object> wrapped = Map.of("output", innerList);

            Object result = OutputUnwrapper.unwrapOutputObject(wrapped);

            assertEquals(innerList, result);
        }

        @Test
        @DisplayName("Should unwrap Map with output key containing a String")
        void shouldUnwrapMapWithOutputKeyContainingString() {
            Map<String, Object> wrapped = Map.of("output", "plain_value");

            Object result = OutputUnwrapper.unwrapOutputObject(wrapped);

            assertEquals("plain_value", result);
        }

        @Test
        @DisplayName("Should return original Map if no output key")
        void shouldReturnOriginalIfNoOutputKey() {
            Map<String, Object> map = Map.of("data", "value");

            Object result = OutputUnwrapper.unwrapOutputObject(map);

            assertSame(map, result);
        }
    }

    // =========================================================================
    // tryUnwrapToList(Object) - added 2026-05-14 alongside the prod
    // Instagram-Profile-Scraper fix. Pre-fix, SplitNode silently wrapped a
    // {items:[...], status:..., runId:...} Map as a 1-element list containing
    // the whole Map, and downstream current_item.X accesses returned undefined.
    // This helper now extracts the array under a recognized key OR returns
    // Optional.empty(), letting callers fail loud (Split) or fall back as
    // single-item (Queue1To1Strategy.merge).
    // =========================================================================

    @Nested
    @DisplayName("tryUnwrapToList(Object) - iterable extraction with known-array-key probe")
    class TryUnwrapToListTests {

        // ── Pass-through: already iterable ──────────────────────────────────

        @Test
        @DisplayName("List input is returned as-is")
        void listInput_returnsSameContents() {
            List<Object> input = List.of("a", "b", "c");
            Optional<List<Object>> result = OutputUnwrapper.tryUnwrapToList(input);
            assertTrue(result.isPresent());
            assertEquals(input, result.get());
        }

        @Test
        @DisplayName("Empty List is returned as empty (NOT empty Optional)")
        void emptyList_isPresentAndEmpty() {
            Optional<List<Object>> result = OutputUnwrapper.tryUnwrapToList(List.of());
            assertTrue(result.isPresent(), "an empty array is a valid result, distinct from 'no array found'");
            assertTrue(result.get().isEmpty());
        }

        @Test
        @DisplayName("Collection input is materialized into a List")
        void collectionInput_isMaterialized() {
            Collection<Object> input = new java.util.LinkedHashSet<>(List.of(1, 2, 3));
            Optional<List<Object>> result = OutputUnwrapper.tryUnwrapToList(input);
            assertTrue(result.isPresent());
            assertEquals(List.of(1, 2, 3), result.get());
        }

        @Test
        @DisplayName("Object[] input is converted into a List")
        void objectArrayInput_isConverted() {
            Object[] arr = new Object[] {"x", "y"};
            Optional<List<Object>> result = OutputUnwrapper.tryUnwrapToList(arr);
            assertTrue(result.isPresent());
            assertEquals(List.of("x", "y"), result.get());
        }

        @Test
        @DisplayName("int[] primitive array input is reflectively boxed into a List")
        void primitiveArrayInput_isBoxedViaReflection() {
            int[] arr = new int[] {10, 20};
            Optional<List<Object>> result = OutputUnwrapper.tryUnwrapToList(arr);
            assertTrue(result.isPresent());
            assertEquals(2, result.get().size());
            assertEquals(10, result.get().get(0));
            assertEquals(20, result.get().get(1));
        }

        // ── Map unwrap by known key ─────────────────────────────────────────

        @Test
        @DisplayName("Map with `items` key unwraps to that array - the Apify/MCP canonical shape")
        void mapWithItemsKey_unwrapsToInnerArray() {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("items", List.of(Map.of("displayUrl", "u1"), Map.of("displayUrl", "u2")));
            input.put("status", "SUCCEEDED");
            input.put("runId", "abc");
            Optional<List<Object>> result = OutputUnwrapper.tryUnwrapToList(input);
            assertTrue(result.isPresent());
            assertEquals(2, result.get().size());
            assertEquals("u1", ((Map<?, ?>) result.get().get(0)).get("displayUrl"));
        }

        @Test
        @DisplayName("Map with `records` key unwraps - Airtable shape")
        void mapWithRecordsKey_unwraps() {
            Map<String, Object> input = Map.of("records", List.of("r1", "r2"));
            Optional<List<Object>> result = OutputUnwrapper.tryUnwrapToList(input);
            assertTrue(result.isPresent());
            assertEquals(List.of("r1", "r2"), result.get());
        }

        @Test
        @DisplayName("Map with `results` key unwraps - generic search-result shape")
        void mapWithResultsKey_unwraps() {
            Map<String, Object> input = Map.of("results", List.of(1, 2, 3));
            Optional<List<Object>> result = OutputUnwrapper.tryUnwrapToList(input);
            assertTrue(result.isPresent());
            assertEquals(List.of(1, 2, 3), result.get());
        }

        @Test
        @DisplayName("Map with `data` key unwraps - OpenAI-style envelope")
        void mapWithDataKey_unwraps() {
            Map<String, Object> input = Map.of("data", List.of("d1", "d2"));
            Optional<List<Object>> result = OutputUnwrapper.tryUnwrapToList(input);
            assertTrue(result.isPresent());
            assertEquals(List.of("d1", "d2"), result.get());
        }

        @Test
        @DisplayName("Map with BOTH `items` and `data` - `items` wins (priority order: items before data)")
        void mapWithItemsAndData_priorityYieldsItems() {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("data", List.of("from-data"));
            input.put("items", List.of("from-items-A", "from-items-B"));
            Optional<List<Object>> result = OutputUnwrapper.tryUnwrapToList(input);
            assertTrue(result.isPresent());
            assertEquals(List.of("from-items-A", "from-items-B"), result.get(),
                "items is more specific than data; iteration order in ARRAY_BEARING_KEYS pins this");
        }

        @Test
        @DisplayName("Map with `items` = empty array → empty list (NOT empty Optional, NOT single-Map fallback)")
        void mapWithEmptyItemsArray_returnsEmptyList() {
            Map<String, Object> input = Map.of("items", List.of());
            Optional<List<Object>> result = OutputUnwrapper.tryUnwrapToList(input);
            assertTrue(result.isPresent(), "an empty array under a recognized key is still a valid result");
            assertTrue(result.get().isEmpty());
        }

        // ── Negative: empty Optional ─────────────────────────────────────────

        @Test
        @DisplayName("Map with NO recognized key → empty Optional (caller fails loud)")
        void mapWithNoRecognizedKey_returnsEmpty() {
            Map<String, Object> input = Map.of("status", "ok", "runId", "abc");
            Optional<List<Object>> result = OutputUnwrapper.tryUnwrapToList(input);
            assertTrue(result.isEmpty(),
                "Map without an array-bearing key must surface as empty - caller picks fail-loud vs fallback");
        }

        @Test
        @DisplayName("Map with `items` value that is itself a Map (not array) → empty Optional, do NOT recurse")
        void mapWithItemsKeyHoldingMap_returnsEmpty() {
            Map<String, Object> input = Map.of("items", Map.of("nested", "value"));
            Optional<List<Object>> result = OutputUnwrapper.tryUnwrapToList(input);
            assertTrue(result.isEmpty(),
                "we don't guess deeper paths - the agent must point at the correct array explicitly");
        }

        @Test
        @DisplayName("Map with `items` recognized but holding a String → empty Optional, do NOT try lower-priority keys")
        void mapWithFirstKeyMatchingButNonArray_stopsProbing() {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("items", "not-an-array");
            input.put("data", List.of("would-have-matched"));
            Optional<List<Object>> result = OutputUnwrapper.tryUnwrapToList(input);
            assertTrue(result.isEmpty(),
                "first recognized key wins; if its value is non-array we surface empty so the agent can fix the reference");
        }

        @Test
        @DisplayName("null input → empty Optional")
        void nullInput_returnsEmpty() {
            assertTrue(OutputUnwrapper.tryUnwrapToList(null).isEmpty());
        }

        @Test
        @DisplayName("Primitive String → empty Optional (NOT single-item wrap)")
        void primitiveString_returnsEmpty() {
            assertTrue(OutputUnwrapper.tryUnwrapToList("a single string").isEmpty(),
                "splitting a scalar is always user intent error - single-item wrap was the prod silent-failure shape");
        }

        @Test
        @DisplayName("Primitive Integer → empty Optional")
        void primitiveInteger_returnsEmpty() {
            assertTrue(OutputUnwrapper.tryUnwrapToList(42).isEmpty());
        }

        @Test
        @DisplayName("Map with `items` value as Object[] → reflection branch boxes the array correctly")
        void mapWithItemsKeyHoldingObjectArray_reflectivelyUnboxes() {
            // Pins the otherwise-dead-coverage reflection branch inside the Map probe loop:
            // when a recognized key holds a Java array (not a List/Collection), we reflectively
            // copy its elements into a fresh list. Without this test a future regression that
            // dropped the array branch would only surface in production via Apify SDK builds
            // that return Object[] dataset payloads.
            Map<String, Object> input = Map.of("items", new Object[] {"a", "b", "c"});
            Optional<List<Object>> result = OutputUnwrapper.tryUnwrapToList(input);
            assertTrue(result.isPresent());
            assertEquals(List.of("a", "b", "c"), result.get());
        }

        @Test
        @DisplayName("Map with non-`items` recognized key only - picks first PRIORITY match, not first INSERTION-ORDER match")
        void mapWithNonItemsRecognizedKey_picksByPriorityNotInsertionOrder() {
            // Even though `data` is inserted first, the helper iterates ARRAY_BEARING_KEYS in
            // priority order - `records` (position 1) wins over `data` (position 9). Without
            // this test a regression that walked the input Map's iteration order instead of
            // ARRAY_BEARING_KEYS would pass `mapWithItemsAndData_priorityYieldsItems` (which
            // only pins positions 0 vs 9) but break mid-priority recognition.
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("data", List.of("from-data"));        // priority 9
            input.put("records", List.of("from-records"));  // priority 1
            Optional<List<Object>> result = OutputUnwrapper.tryUnwrapToList(input);
            assertTrue(result.isPresent());
            assertEquals(List.of("from-records"), result.get(),
                "ARRAY_BEARING_KEYS priority must drive selection, not the Map's own key iteration order");
        }
    }

    // =========================================================================
    // describeNonListShape(Object, String) - shared loud-fail diagnostic used by
    // BOTH SplitNode.buildShapeDiagnostic AND SplitNodeExecutor so the two split
    // entry points (AUTOMATIC + STEP_BY_STEP) cannot drift on the message.
    // =========================================================================

    @Nested
    @DisplayName("describeNonListShape(Object, String) - shared fail-loud diagnostic")
    class DescribeNonListShapeTests {

        @Test
        @DisplayName("Map branch lists observed keys, the array-bearing keys, and echoes the expression")
        void mapShape_listsObservedAndArrayBearingKeys() {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("status", "FAILED");
            input.put("runId", "abc");
            String msg = OutputUnwrapper.describeNonListShape(input, "{{run.output}}");
            assertTrue(msg.contains("{{run.output}}"), "echoes the offending expression");
            assertTrue(msg.contains("resolved to a Map with keys"), "names the observed shape");
            assertTrue(msg.contains("status") && msg.contains("runId"), "lists the observed Map keys");
            assertTrue(msg.contains("items") && msg.contains("records"),
                "lists the known array-bearing keys so the agent can self-correct without log access");
        }

        @Test
        @DisplayName("non-Map branch names the actual runtime type and suggests an array reference")
        void scalarShape_namesTypeAndSuggestsFix() {
            String msg = OutputUnwrapper.describeNonListShape("a scalar", "{{single}}");
            assertTrue(msg.contains("resolved to String"), "names the concrete runtime type");
            assertTrue(msg.contains("{{single}}"));
            assertTrue(msg.contains("output.items"), "points at the canonical array-reference fix");
        }

        @Test
        @DisplayName("Map key list is capped at 20 keys to keep the diagnostic readable")
        void mapShape_capsObservedKeysAt20() {
            Map<String, Object> input = new LinkedHashMap<>();
            for (int i = 0; i < 30; i++) {
                input.put("k" + i, i);
            }
            String msg = OutputUnwrapper.describeNonListShape(input, "{{wide}}");
            // LinkedHashMap preserves insertion order; stream.limit(20) keeps k0..k19, drops k20..k29.
            assertTrue(msg.contains("k0"), "first keys are listed");
            assertFalse(msg.contains("k29"), "keys beyond the 20-key cap are omitted");
        }
    }
}
