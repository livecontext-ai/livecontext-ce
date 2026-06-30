package com.apimarketplace.orchestrator.domain.workflow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("MergeNode")
class MergeNodeTest {

    @Nested
    @DisplayName("isValid()")
    class IsValidTests {

        @Test
        @DisplayName("Should be valid with stepId and multiple sources")
        void shouldBeValidWithMultipleSources() {
            MergeNode node = new MergeNode("mcp:final", List.of("mcp:step1", "mcp:step2"), "QUEUE_1_TO_1");
            assertTrue(node.isValid());
        }

        @Test
        @DisplayName("Should be invalid with null stepId")
        void shouldBeInvalidWithNullStepId() {
            MergeNode node = new MergeNode(null, List.of("mcp:step1", "mcp:step2"), "QUEUE_1_TO_1");
            assertFalse(node.isValid());
        }

        @Test
        @DisplayName("Should be invalid with null sources")
        void shouldBeInvalidWithNullSources() {
            MergeNode node = new MergeNode("mcp:final", null, "QUEUE_1_TO_1");
            assertFalse(node.isValid());
        }

        @Test
        @DisplayName("Should be invalid with single source")
        void shouldBeInvalidWithSingleSource() {
            MergeNode node = new MergeNode("mcp:final", List.of("mcp:step1"), "QUEUE_1_TO_1");
            assertFalse(node.isValid());
        }

        @Test
        @DisplayName("Should be invalid with empty sources")
        void shouldBeInvalidWithEmptySources() {
            MergeNode node = new MergeNode("mcp:final", List.of(), "QUEUE_1_TO_1");
            assertFalse(node.isValid());
        }
    }

    @Nested
    @DisplayName("getSourceCount()")
    class GetSourceCountTests {

        @Test
        @DisplayName("Should return correct source count")
        void shouldReturnCorrectCount() {
            MergeNode node = new MergeNode("mcp:final", List.of("a", "b", "c"), "QUEUE_1_TO_1");
            assertEquals(3, node.getSourceCount());
        }

        @Test
        @DisplayName("Should return 0 for null sources")
        void shouldReturnZeroForNullSources() {
            MergeNode node = new MergeNode("mcp:final", null, "QUEUE_1_TO_1");
            assertEquals(0, node.getSourceCount());
        }
    }
}
