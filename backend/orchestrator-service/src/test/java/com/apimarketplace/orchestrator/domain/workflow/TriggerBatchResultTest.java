package com.apimarketplace.orchestrator.domain.workflow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TriggerBatchResult")
class TriggerBatchResultTest {

    private Trigger createTrigger() {
        return new Trigger("trigger-1", "My Trigger", "single", "datasource");
    }

    @Nested
    @DisplayName("Constructor validation")
    class ConstructorValidationTests {

        @Test
        @DisplayName("Should create valid batch result")
        void shouldCreateValidBatchResult() {
            Trigger trigger = createTrigger();
            List<Map<String, Object>> items = List.of(Map.of("id", 1), Map.of("id", 2));

            TriggerBatchResult result = new TriggerBatchResult(
                trigger, "tenant-1", items, 0, 10, 50, true, 10, 50
            );

            assertSame(trigger, result.trigger());
            assertEquals("tenant-1", result.tenantId());
            assertEquals(2, result.items().size());
            assertEquals(0, result.offset());
            assertEquals(10, result.limit());
            assertEquals(50, result.totalCount());
            assertTrue(result.hasMore());
            assertEquals(10, result.nextOffset());
            assertEquals(50, result.realTotalCount());
        }

        @Test
        @DisplayName("Should make items immutable")
        void shouldMakeItemsImmutable() {
            List<Map<String, Object>> items = List.of(Map.of("id", 1));
            TriggerBatchResult result = new TriggerBatchResult(
                createTrigger(), "tenant-1", items, 0, 10, 1, false, 0, 1
            );

            assertThrows(UnsupportedOperationException.class, () -> result.items().add(Map.of("id", 2)));
        }

        @Test
        @DisplayName("Should handle null items as empty list")
        void shouldHandleNullItems() {
            TriggerBatchResult result = new TriggerBatchResult(
                createTrigger(), "tenant-1", null, 0, 10, 0, false, 0, 0
            );

            assertNotNull(result.items());
            assertTrue(result.items().isEmpty());
        }

        @Test
        @DisplayName("Should throw on negative offset")
        void shouldThrowOnNegativeOffset() {
            assertThrows(IllegalArgumentException.class, () ->
                new TriggerBatchResult(createTrigger(), "tenant-1", List.of(), -1, 10, 0, false, 0, 0));
        }

        @Test
        @DisplayName("Should throw on zero limit")
        void shouldThrowOnZeroLimit() {
            assertThrows(IllegalArgumentException.class, () ->
                new TriggerBatchResult(createTrigger(), "tenant-1", List.of(), 0, 0, 0, false, 0, 0));
        }

        @Test
        @DisplayName("Should throw on negative limit")
        void shouldThrowOnNegativeLimit() {
            assertThrows(IllegalArgumentException.class, () ->
                new TriggerBatchResult(createTrigger(), "tenant-1", List.of(), 0, -1, 0, false, 0, 0));
        }

        @Test
        @DisplayName("Should throw on negative totalCount")
        void shouldThrowOnNegativeTotalCount() {
            assertThrows(IllegalArgumentException.class, () ->
                new TriggerBatchResult(createTrigger(), "tenant-1", List.of(), 0, 10, -1, false, 0, 0));
        }

        @Test
        @DisplayName("Should throw on negative nextOffset")
        void shouldThrowOnNegativeNextOffset() {
            assertThrows(IllegalArgumentException.class, () ->
                new TriggerBatchResult(createTrigger(), "tenant-1", List.of(), 0, 10, 0, false, -1, 0));
        }

        @Test
        @DisplayName("Should throw on negative realTotalCount")
        void shouldThrowOnNegativeRealTotalCount() {
            assertThrows(IllegalArgumentException.class, () ->
                new TriggerBatchResult(createTrigger(), "tenant-1", List.of(), 0, 10, 0, false, 0, -1));
        }
    }

    @Nested
    @DisplayName("isEmpty()")
    class IsEmptyTests {

        @Test
        @DisplayName("Should return true when no items")
        void shouldReturnTrueWhenEmpty() {
            TriggerBatchResult result = new TriggerBatchResult(
                createTrigger(), "tenant-1", List.of(), 0, 10, 0, false, 0, 0
            );
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("Should return false when items exist")
        void shouldReturnFalseWhenItems() {
            TriggerBatchResult result = new TriggerBatchResult(
                createTrigger(), "tenant-1", List.of(Map.of("id", 1)), 0, 10, 1, false, 0, 1
            );
            assertFalse(result.isEmpty());
        }
    }

    @Nested
    @DisplayName("toLegacyPayload()")
    class ToLegacyPayloadTests {

        @Test
        @DisplayName("Should include all required fields")
        void shouldIncludeAllFields() {
            Trigger trigger = createTrigger();
            List<Map<String, Object>> items = List.of(Map.of("id", 1));

            TriggerBatchResult result = new TriggerBatchResult(
                trigger, "tenant-1", items, 0, 10, 50, true, 10, 50
            );

            Map<String, Object> payload = result.toLegacyPayload();

            assertEquals("trigger-1", payload.get("triggerId"));
            assertEquals("tenant-1", payload.get("tenantId"));
            assertEquals(items, payload.get("data"));
            assertEquals(1, payload.get("count"));
            assertEquals(50, payload.get("totalCount"));
            assertEquals(50, payload.get("realTotalCount"));
            assertEquals(0, payload.get("offset"));
            assertEquals(10, payload.get("limit"));
            assertEquals(true, payload.get("hasMore"));
            assertEquals(10, payload.get("nextOffset"));
            assertEquals("success", payload.get("status"));
            assertEquals("datasource", payload.get("source"));
        }

        @Test
        @DisplayName("Should set nextOffset to null when hasMore is false")
        void shouldNullifyNextOffsetWhenNoMore() {
            TriggerBatchResult result = new TriggerBatchResult(
                createTrigger(), "tenant-1", List.of(), 0, 10, 0, false, 0, 0
            );

            Map<String, Object> payload = result.toLegacyPayload();
            assertNull(payload.get("nextOffset"));
        }

        @Test
        @DisplayName("Should handle null trigger")
        void shouldHandleNullTrigger() {
            TriggerBatchResult result = new TriggerBatchResult(
                null, "tenant-1", List.of(), 0, 10, 0, false, 0, 0
            );

            Map<String, Object> payload = result.toLegacyPayload();
            assertNull(payload.get("triggerId"));
        }
    }

    @Nested
    @DisplayName("withEmptyItems()")
    class WithEmptyItemsTests {

        @Test
        @DisplayName("Should return result with empty items and hasMore false")
        void shouldReturnEmptyItems() {
            TriggerBatchResult original = new TriggerBatchResult(
                createTrigger(), "tenant-1", List.of(Map.of("id", 1)), 0, 10, 50, true, 10, 50
            );

            TriggerBatchResult empty = original.withEmptyItems();

            assertTrue(empty.isEmpty());
            assertFalse(empty.hasMore());
            assertEquals(original.trigger(), empty.trigger());
            assertEquals(original.tenantId(), empty.tenantId());
            assertEquals(original.offset(), empty.offset());
            assertEquals(original.limit(), empty.limit());
            assertEquals(original.totalCount(), empty.totalCount());
            assertEquals(original.realTotalCount(), empty.realTotalCount());
            // nextOffset should be the original offset (not advancing)
            assertEquals(original.offset(), empty.nextOffset());
        }
    }
}
