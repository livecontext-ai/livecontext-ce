package com.apimarketplace.orchestrator.services.merge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ItemMergeEntry")
class ItemMergeEntryTest {

    @Nested
    @DisplayName("Factory methods")
    class FactoryMethodTests {

        @Test
        @DisplayName("success() should create successful entry")
        void successShouldCreateSuccessEntry() {
            Map<String, Object> data = Map.of("key", "value");

            ItemMergeEntry entry = ItemMergeEntry.success("0", 0, "mcp:process", data);

            assertEquals("0", entry.itemId());
            assertEquals(0, entry.itemIndex());
            assertEquals("mcp:process", entry.sourceNodeId());
            assertEquals(data, entry.data());
            assertEquals(ItemMergeEntry.Status.SUCCESS, entry.status());
            assertNull(entry.errorMessage());
            assertNotNull(entry.completedAt());
        }

        @Test
        @DisplayName("failed() should create failed entry with error message")
        void failedShouldCreateFailedEntry() {
            ItemMergeEntry entry = ItemMergeEntry.failed("0.1", 1, "mcp:step", "Connection refused");

            assertEquals("0.1", entry.itemId());
            assertEquals(1, entry.itemIndex());
            assertEquals("mcp:step", entry.sourceNodeId());
            assertEquals(Map.of(), entry.data());
            assertEquals(ItemMergeEntry.Status.FAILED, entry.status());
            assertEquals("Connection refused", entry.errorMessage());
        }

        @Test
        @DisplayName("skipped() should create skipped entry with reason")
        void skippedShouldCreateSkippedEntry() {
            ItemMergeEntry entry = ItemMergeEntry.skipped("0.2", 2, "mcp:step", "Condition not met");

            assertEquals("0.2", entry.itemId());
            assertEquals(2, entry.itemIndex());
            assertEquals(Map.of(), entry.data());
            assertEquals(ItemMergeEntry.Status.SKIPPED, entry.status());
            assertEquals("Condition not met", entry.errorMessage());
        }
    }

    @Nested
    @DisplayName("Status checks")
    class StatusCheckTests {

        @Test
        @DisplayName("isSuccess() should return true for SUCCESS status")
        void isSuccessShouldReturnTrue() {
            ItemMergeEntry entry = ItemMergeEntry.success("0", 0, "node", Map.of());
            assertTrue(entry.isSuccess());
            assertFalse(entry.isFailed());
            assertFalse(entry.isSkipped());
        }

        @Test
        @DisplayName("isFailed() should return true for FAILED status")
        void isFailedShouldReturnTrue() {
            ItemMergeEntry entry = ItemMergeEntry.failed("0", 0, "node", "error");
            assertTrue(entry.isFailed());
            assertFalse(entry.isSuccess());
            assertFalse(entry.isSkipped());
        }

        @Test
        @DisplayName("isSkipped() should return true for SKIPPED status")
        void isSkippedShouldReturnTrue() {
            ItemMergeEntry entry = ItemMergeEntry.skipped("0", 0, "node", "reason");
            assertTrue(entry.isSkipped());
            assertFalse(entry.isSuccess());
            assertFalse(entry.isFailed());
        }

        @Test
        @DisplayName("isResolved() should return true for any non-null status")
        void isResolvedShouldReturnTrue() {
            assertTrue(ItemMergeEntry.success("0", 0, "node", Map.of()).isResolved());
            assertTrue(ItemMergeEntry.failed("0", 0, "node", "err").isResolved());
            assertTrue(ItemMergeEntry.skipped("0", 0, "node", "reason").isResolved());
        }

        @Test
        @DisplayName("isResolved() should return false for null status")
        void isResolvedShouldReturnFalseForNull() {
            ItemMergeEntry entry = new ItemMergeEntry(
                "0", 0, "node", Map.of(), null, null, Instant.now()
            );
            assertFalse(entry.isResolved());
        }
    }

    @Nested
    @DisplayName("Scope and split detection")
    class ScopeTests {

        @Test
        @DisplayName("getScope() should return parent scope for split child")
        void getScopeShouldReturnParentForSplitChild() {
            ItemMergeEntry entry = ItemMergeEntry.success("0.1", 1, "mcp:step", Map.of());
            assertEquals("0", entry.getScope());
        }

        @Test
        @DisplayName("getScope() should return self for root item")
        void getScopeShouldReturnSelfForRoot() {
            ItemMergeEntry entry = ItemMergeEntry.success("0", 0, "mcp:step", Map.of());
            assertEquals("0", entry.getScope());
        }

        @Test
        @DisplayName("getScope() should return parent for nested split child")
        void getScopeShouldReturnParentForNestedChild() {
            ItemMergeEntry entry = ItemMergeEntry.success("0.1.2", 2, "mcp:step", Map.of());
            assertEquals("0.1", entry.getScope());
        }

        @Test
        @DisplayName("isSplitChild() should return true for dotted itemId")
        void isSplitChildShouldReturnTrueForDotted() {
            ItemMergeEntry entry = ItemMergeEntry.success("0.1", 1, "mcp:step", Map.of());
            assertTrue(entry.isSplitChild());
        }

        @Test
        @DisplayName("isSplitChild() should return false for root itemId")
        void isSplitChildShouldReturnFalseForRoot() {
            ItemMergeEntry entry = ItemMergeEntry.success("0", 0, "mcp:step", Map.of());
            assertFalse(entry.isSplitChild());
        }
    }

    @Nested
    @DisplayName("Status enum")
    class StatusEnumTests {

        @Test
        @DisplayName("Should have three statuses")
        void shouldHaveThreeStatuses() {
            assertEquals(3, ItemMergeEntry.Status.values().length);
        }

        @Test
        @DisplayName("Should contain SUCCESS, FAILED, SKIPPED")
        void shouldContainExpectedValues() {
            assertNotNull(ItemMergeEntry.Status.valueOf("SUCCESS"));
            assertNotNull(ItemMergeEntry.Status.valueOf("FAILED"));
            assertNotNull(ItemMergeEntry.Status.valueOf("SKIPPED"));
        }
    }
}
