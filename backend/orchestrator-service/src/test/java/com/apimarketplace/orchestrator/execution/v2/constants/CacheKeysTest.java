package com.apimarketplace.orchestrator.execution.v2.constants;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for CacheKeys utility class.
 */
@DisplayName("CacheKeys")
class CacheKeysTest {

    @Nested
    @DisplayName("contextCacheKey")
    class ContextCacheKey {

        @Test
        @DisplayName("should build composite key with colon separator")
        void shouldBuildCompositeKey() {
            assertEquals("run-123:item-0", CacheKeys.contextCacheKey("run-123", "item-0"));
        }

        @Test
        @DisplayName("should handle empty strings")
        void shouldHandleEmptyStrings() {
            assertEquals(":", CacheKeys.contextCacheKey("", ""));
        }
    }

    @Nested
    @DisplayName("treeCacheKey")
    class TreeCacheKey {

        @Test
        @DisplayName("should return runId directly")
        void shouldReturnRunId() {
            assertEquals("run-abc", CacheKeys.treeCacheKey("run-abc"));
        }
    }

    @Nested
    @DisplayName("executionCacheKey")
    class ExecutionCacheKey {

        @Test
        @DisplayName("should return runId directly")
        void shouldReturnRunId() {
            assertEquals("run-xyz", CacheKeys.executionCacheKey("run-xyz"));
        }
    }

    @Nested
    @DisplayName("triggerItemsCacheKey")
    class TriggerItemsCacheKey {

        @Test
        @DisplayName("should return runId directly")
        void shouldReturnRunId() {
            assertEquals("run-1", CacheKeys.triggerItemsCacheKey("run-1"));
        }
    }

    @Nested
    @DisplayName("childItemId")
    class ChildItemId {

        @Test
        @DisplayName("should build parent.index format")
        void shouldBuildChildItemId() {
            assertEquals("item-0.2", CacheKeys.childItemId("item-0", 2));
        }

        @Test
        @DisplayName("should handle zero index")
        void shouldHandleZeroIndex() {
            assertEquals("item-0.0", CacheKeys.childItemId("item-0", 0));
        }
    }

    @Nested
    @DisplayName("isChildItem")
    class IsChildItem {

        @Test
        @DisplayName("should return true for child item with dot")
        void shouldReturnTrueForChildItem() {
            assertTrue(CacheKeys.isChildItem("item-0.2"));
        }

        @Test
        @DisplayName("should return false for parent item without dot")
        void shouldReturnFalseForParentItem() {
            assertFalse(CacheKeys.isChildItem("item-0"));
        }

        @Test
        @DisplayName("should return false for null")
        void shouldReturnFalseForNull() {
            assertFalse(CacheKeys.isChildItem(null));
        }
    }

    @Nested
    @DisplayName("extractParentItemId")
    class ExtractParentItemId {

        @Test
        @DisplayName("should extract parent from child item ID")
        void shouldExtractParent() {
            assertEquals("item-0", CacheKeys.extractParentItemId("item-0.2"));
        }

        @Test
        @DisplayName("should handle nested dots (uses last dot)")
        void shouldHandleNestedDots() {
            assertEquals("item-0.1", CacheKeys.extractParentItemId("item-0.1.3"));
        }

        @Test
        @DisplayName("should return null for non-child item")
        void shouldReturnNullForNonChild() {
            assertNull(CacheKeys.extractParentItemId("item-0"));
        }

        @Test
        @DisplayName("should return null for null input")
        void shouldReturnNullForNull() {
            assertNull(CacheKeys.extractParentItemId(null));
        }
    }

    @Nested
    @DisplayName("extractChildIndex")
    class ExtractChildIndex {

        @Test
        @DisplayName("should extract child index")
        void shouldExtractChildIndex() {
            assertEquals(2, CacheKeys.extractChildIndex("item-0.2"));
        }

        @Test
        @DisplayName("should return -1 for non-child item")
        void shouldReturnMinusOneForNonChild() {
            assertEquals(-1, CacheKeys.extractChildIndex("item-0"));
        }

        @Test
        @DisplayName("should return -1 for null")
        void shouldReturnMinusOneForNull() {
            assertEquals(-1, CacheKeys.extractChildIndex(null));
        }

        @Test
        @DisplayName("should return -1 for invalid number after dot")
        void shouldReturnMinusOneForInvalidNumber() {
            assertEquals(-1, CacheKeys.extractChildIndex("item-0.abc"));
        }
    }

    @Nested
    @DisplayName("validateKey")
    class ValidateKey {

        @Test
        @DisplayName("should not throw for valid key")
        void shouldNotThrowForValidKey() {
            assertDoesNotThrow(() -> CacheKeys.validateKey("valid-key", "testKey"));
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"  ", "\t"})
        @DisplayName("should throw for null, empty, or blank key")
        void shouldThrowForInvalidKey(String key) {
            assertThrows(IllegalArgumentException.class, () -> CacheKeys.validateKey(key, "testKey"));
        }

        @Test
        @DisplayName("should include key name in error message")
        void shouldIncludeKeyNameInMessage() {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> CacheKeys.validateKey(null, "myKey"));
            assertTrue(ex.getMessage().contains("myKey"));
        }
    }

    @Nested
    @DisplayName("validateRunId")
    class ValidateRunId {

        @Test
        @DisplayName("should not throw for valid runId")
        void shouldNotThrowForValidRunId() {
            assertDoesNotThrow(() -> CacheKeys.validateRunId("run-1"));
        }

        @Test
        @DisplayName("should throw for null runId")
        void shouldThrowForNullRunId() {
            assertThrows(IllegalArgumentException.class, () -> CacheKeys.validateRunId(null));
        }
    }

    @Nested
    @DisplayName("validateItemId")
    class ValidateItemId {

        @Test
        @DisplayName("should not throw for valid itemId")
        void shouldNotThrowForValidItemId() {
            assertDoesNotThrow(() -> CacheKeys.validateItemId("item-0"));
        }

        @Test
        @DisplayName("should throw for blank itemId")
        void shouldThrowForBlankItemId() {
            assertThrows(IllegalArgumentException.class, () -> CacheKeys.validateItemId("  "));
        }
    }

    @Nested
    @DisplayName("stateKeys")
    class StateKeys {

        @Test
        @DisplayName("pausedWorkflowStateKey should return runId")
        void pausedWorkflowStateKey() {
            assertEquals("run-1", CacheKeys.pausedWorkflowStateKey("run-1"));
        }

        @Test
        @DisplayName("evaluatedCoresKey should return runId")
        void evaluatedCoresKey() {
            assertEquals("run-1", CacheKeys.evaluatedCoresKey("run-1"));
        }

        @Test
        @DisplayName("stateManagerKey should return runId")
        void stateManagerKey() {
            assertEquals("run-1", CacheKeys.stateManagerKey("run-1"));
        }
    }
}
