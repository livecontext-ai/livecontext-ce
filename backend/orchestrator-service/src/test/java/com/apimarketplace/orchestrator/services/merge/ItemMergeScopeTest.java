package com.apimarketplace.orchestrator.services.merge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ItemMergeScope")
class ItemMergeScopeTest {

    @Nested
    @DisplayName("getParentScope()")
    class GetParentScopeTests {

        @ParameterizedTest(name = "getParentScope(\"{0}\") should return \"{1}\"")
        @CsvSource({
            "0.1, 0",
            "0.1.2, 0.1",
            "0, 0",
            "1, 1",
            "5.3.2.1, 5.3.2"
        })
        @DisplayName("Should extract correct parent scope")
        void shouldExtractParentScope(String itemId, String expected) {
            assertEquals(expected, ItemMergeScope.getParentScope(itemId));
        }

        @ParameterizedTest
        @NullAndEmptySource
        @DisplayName("Should return empty string for null/empty")
        void shouldReturnEmptyForNullOrEmpty(String itemId) {
            assertEquals("", ItemMergeScope.getParentScope(itemId));
        }
    }

    @Nested
    @DisplayName("getRootScope()")
    class GetRootScopeTests {

        @ParameterizedTest(name = "getRootScope(\"{0}\") should return \"{1}\"")
        @CsvSource({
            "0.1.2, 0",
            "0.1, 0",
            "0, 0",
            "3, 3",
            "5.3.2.1, 5"
        })
        @DisplayName("Should extract correct root scope")
        void shouldExtractRootScope(String itemId, String expected) {
            assertEquals(expected, ItemMergeScope.getRootScope(itemId));
        }

        @ParameterizedTest
        @NullAndEmptySource
        @DisplayName("Should return empty string for null/empty")
        void shouldReturnEmptyForNullOrEmpty(String itemId) {
            assertEquals("", ItemMergeScope.getRootScope(itemId));
        }
    }

    @Nested
    @DisplayName("isChildOf()")
    class IsChildOfTests {

        @ParameterizedTest(name = "isChildOf(\"{0}\", \"{1}\") should return {2}")
        @CsvSource({
            "0.1, 0, true",
            "0.1.2, 0.1, true",
            "0.1.2, 0, true",
            "1.1, 0, false",
            "0, 0, false"
        })
        @DisplayName("Should correctly determine child relationship")
        void shouldDetermineChildRelationship(String itemId, String scope, boolean expected) {
            assertEquals(expected, ItemMergeScope.isChildOf(itemId, scope));
        }

        @Test
        @DisplayName("Should return false for null itemId")
        void shouldReturnFalseForNullItemId() {
            assertFalse(ItemMergeScope.isChildOf(null, "0"));
        }

        @Test
        @DisplayName("Should return false for null scope")
        void shouldReturnFalseForNullScope() {
            assertFalse(ItemMergeScope.isChildOf("0.1", null));
        }
    }

    @Nested
    @DisplayName("isDirectChildOf()")
    class IsDirectChildOfTests {

        @ParameterizedTest(name = "isDirectChildOf(\"{0}\", \"{1}\") should return {2}")
        @CsvSource({
            "0.1, 0, true",
            "0.1.2, 0, false",
            "0.1.2, 0.1, true",
            "0, 0, false",
            "1.1, 0, false"
        })
        @DisplayName("Should correctly determine direct child relationship")
        void shouldDetermineDirectChildRelationship(String itemId, String scope, boolean expected) {
            assertEquals(expected, ItemMergeScope.isDirectChildOf(itemId, scope));
        }
    }

    @Nested
    @DisplayName("getChildIndex()")
    class GetChildIndexTests {

        @ParameterizedTest(name = "getChildIndex(\"{0}\", \"{1}\") should return {2}")
        @CsvSource({
            "0.1, 0, 1",
            "0.3, 0, 3",
            "0.1.2, 0.1, 2"
        })
        @DisplayName("Should extract correct child index")
        void shouldExtractChildIndex(String itemId, String scope, int expected) {
            assertEquals(expected, ItemMergeScope.getChildIndex(itemId, scope));
        }

        @Test
        @DisplayName("Should return -1 for non-direct child")
        void shouldReturnMinusOneForNonDirectChild() {
            assertEquals(-1, ItemMergeScope.getChildIndex("0.1.2", "0"));
        }

        @Test
        @DisplayName("Should return -1 for same-level items")
        void shouldReturnMinusOneForSameLevel() {
            assertEquals(-1, ItemMergeScope.getChildIndex("0", "0"));
        }
    }

    @Nested
    @DisplayName("getDepth()")
    class GetDepthTests {

        @ParameterizedTest(name = "getDepth(\"{0}\") should return {1}")
        @CsvSource({
            "0, 0",
            "0.1, 1",
            "0.1.2, 2",
            "0.1.2.3, 3"
        })
        @DisplayName("Should return correct depth level")
        void shouldReturnCorrectDepth(String itemId, int expected) {
            assertEquals(expected, ItemMergeScope.getDepth(itemId));
        }

        @ParameterizedTest
        @NullAndEmptySource
        @DisplayName("Should return 0 for null/empty")
        void shouldReturnZeroForNullOrEmpty(String itemId) {
            assertEquals(0, ItemMergeScope.getDepth(itemId));
        }
    }

    @Nested
    @DisplayName("isSplitChild()")
    class IsSplitChildTests {

        @ParameterizedTest(name = "isSplitChild(\"{0}\") should return {1}")
        @CsvSource({
            "0.1, true",
            "0.1.2, true",
            "0, false",
            "1, false"
        })
        @DisplayName("Should correctly identify split children")
        void shouldIdentifySplitChildren(String itemId, boolean expected) {
            assertEquals(expected, ItemMergeScope.isSplitChild(itemId));
        }

        @Test
        @DisplayName("Should return false for null")
        void shouldReturnFalseForNull() {
            assertFalse(ItemMergeScope.isSplitChild(null));
        }
    }

    @Nested
    @DisplayName("createChildId()")
    class CreateChildIdTests {

        @ParameterizedTest(name = "createChildId(\"{0}\", {1}) should return \"{2}\"")
        @CsvSource({
            "0, 1, 0.1",
            "0, 3, 0.3",
            "0.1, 2, 0.1.2"
        })
        @DisplayName("Should create correct child IDs")
        void shouldCreateCorrectChildIds(String parentScope, int index, String expected) {
            assertEquals(expected, ItemMergeScope.createChildId(parentScope, index));
        }
    }
}
