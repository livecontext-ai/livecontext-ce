package com.apimarketplace.catalog.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ToolBatchRequest record.
 *
 * ToolBatchRequest is used for batch tool fetching,
 * allowing multiple tools to be fetched in a single request.
 */
@DisplayName("ToolBatchRequest")
class ToolBatchRequestTest {

    // ========================================================================
    // Record construction tests
    // ========================================================================

    @Nested
    @DisplayName("Record construction")
    class RecordConstructionTests {

        @Test
        @DisplayName("should create record with tool slugs")
        void shouldCreateRecordWithToolSlugs() {
            List<String> slugs = Arrays.asList("tool-1", "tool-2", "tool-3");

            ToolBatchRequest request = new ToolBatchRequest(slugs);

            assertEquals(3, request.toolSlugs().size());
            assertTrue(request.toolSlugs().contains("tool-1"));
            assertTrue(request.toolSlugs().contains("tool-2"));
            assertTrue(request.toolSlugs().contains("tool-3"));
        }

        @Test
        @DisplayName("should allow null tool slugs")
        void shouldAllowNullToolSlugs() {
            ToolBatchRequest request = new ToolBatchRequest(null);

            assertNull(request.toolSlugs());
        }

        @Test
        @DisplayName("should allow empty tool slugs list")
        void shouldAllowEmptyToolSlugsList() {
            ToolBatchRequest request = new ToolBatchRequest(Collections.emptyList());

            assertNotNull(request.toolSlugs());
            assertTrue(request.toolSlugs().isEmpty());
        }

        @Test
        @DisplayName("should preserve order of tool slugs")
        void shouldPreserveOrderOfToolSlugs() {
            List<String> slugs = Arrays.asList("alpha", "beta", "gamma", "delta");

            ToolBatchRequest request = new ToolBatchRequest(slugs);

            assertEquals("alpha", request.toolSlugs().get(0));
            assertEquals("beta", request.toolSlugs().get(1));
            assertEquals("gamma", request.toolSlugs().get(2));
            assertEquals("delta", request.toolSlugs().get(3));
        }
    }

    // ========================================================================
    // Equality tests
    // ========================================================================

    @Nested
    @DisplayName("Equality")
    class EqualityTests {

        @Test
        @DisplayName("should be equal for same slugs")
        void shouldBeEqualForSameSlugs() {
            ToolBatchRequest request1 = new ToolBatchRequest(List.of("a", "b", "c"));
            ToolBatchRequest request2 = new ToolBatchRequest(List.of("a", "b", "c"));

            assertEquals(request1, request2);
            assertEquals(request1.hashCode(), request2.hashCode());
        }

        @Test
        @DisplayName("should not be equal for different slugs")
        void shouldNotBeEqualForDifferentSlugs() {
            ToolBatchRequest request1 = new ToolBatchRequest(List.of("a", "b"));
            ToolBatchRequest request2 = new ToolBatchRequest(List.of("x", "y"));

            assertNotEquals(request1, request2);
        }

        @Test
        @DisplayName("should not be equal when one is null")
        void shouldNotBeEqualWhenOneIsNull() {
            ToolBatchRequest request1 = new ToolBatchRequest(List.of("a"));
            ToolBatchRequest request2 = new ToolBatchRequest(null);

            assertNotEquals(request1, request2);
        }
    }

    // ========================================================================
    // toString tests
    // ========================================================================

    @Nested
    @DisplayName("toString()")
    class ToStringTests {

        @Test
        @DisplayName("should include tool slugs in string representation")
        void shouldIncludeToolSlugsInString() {
            ToolBatchRequest request = new ToolBatchRequest(List.of("tool-1", "tool-2"));

            String str = request.toString();

            assertTrue(str.contains("tool-1"));
            assertTrue(str.contains("tool-2"));
        }
    }
}
