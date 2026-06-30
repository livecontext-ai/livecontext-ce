package com.apimarketplace.orchestrator.domain.workflow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Edge record.
 *
 * Edge represents a connection between nodes in the workflow graph.
 * Format: from -> to with optional params data.
 */
@DisplayName("Edge")
class EdgeTest {

    @Nested
    @DisplayName("Constructor")
    class ConstructorTests {

        @Test
        @DisplayName("Should create edge with all fields")
        void shouldCreateEdgeWithAllFields() {
            Map<String, Object> params = Map.of("data", "{{trigger:start.payload}}");
            Edge edge = new Edge("trigger:start", "mcp:process", params);

            assertEquals("trigger:start", edge.from());
            assertEquals("mcp:process", edge.to());
            assertEquals(params, edge.params());
        }

        @Test
        @DisplayName("Should create edge with two-arg constructor")
        void shouldCreateEdgeWithTwoArgConstructor() {
            Edge edge = new Edge("trigger:start", "mcp:process");

            assertEquals("trigger:start", edge.from());
            assertEquals("mcp:process", edge.to());
            assertNull(edge.params());
        }

        @Test
        @DisplayName("Should allow null params")
        void shouldAllowNullParams() {
            Edge edge = new Edge("trigger:start", "mcp:process", null);

            assertNull(edge.params());
        }

        @Test
        @DisplayName("Should allow empty params map")
        void shouldAllowEmptyParamsMap() {
            Edge edge = new Edge("trigger:start", "mcp:process", Map.of());

            assertNotNull(edge.params());
            assertTrue(edge.params().isEmpty());
        }
    }

    @Nested
    @DisplayName("Edge with ports")
    class EdgeWithPortsTests {

        @Test
        @DisplayName("Should create edge with decision 'if' port")
        void shouldCreateEdgeWithIfPort() {
            Edge edge = new Edge("core:check:if", "mcp:success");

            assertEquals("core:check:if", edge.from());
            assertEquals("mcp:success", edge.to());
        }

        @Test
        @DisplayName("Should create edge with decision 'else' port")
        void shouldCreateEdgeWithElsePort() {
            Edge edge = new Edge("core:check:else", "mcp:failure");

            assertEquals("core:check:else", edge.from());
        }

        @Test
        @DisplayName("Should create edge with loop 'body' port")
        void shouldCreateEdgeWithLoopBodyPort() {
            Edge edge = new Edge("core:loop:body", "mcp:iterate_step");

            assertEquals("core:loop:body", edge.from());
        }

        @Test
        @DisplayName("Should create edge with loop 'exit' port")
        void shouldCreateEdgeWithLoopExitPort() {
            Edge edge = new Edge("core:loop:exit", "mcp:after_loop");

            assertEquals("core:loop:exit", edge.from());
        }

        @Test
        @DisplayName("Should create edge with fork branch port")
        void shouldCreateEdgeWithForkBranchPort() {
            Edge edge = new Edge("core:parallel:branch_0", "mcp:task_a");

            assertEquals("core:parallel:branch_0", edge.from());
        }
    }

    @Nested
    @DisplayName("getEdgeId()")
    class GetEdgeIdTests {

        @Test
        @DisplayName("Should return from->to format")
        void shouldReturnFromToFormat() {
            Edge edge = new Edge("trigger:start", "mcp:process");

            assertEquals("trigger:start->mcp:process", edge.getEdgeId());
        }

        @Test
        @DisplayName("Should include port in edge id")
        void shouldIncludePortInEdgeId() {
            Edge edge = new Edge("core:check:if", "mcp:success");

            assertEquals("core:check:if->mcp:success", edge.getEdgeId());
        }
    }

    @Nested
    @DisplayName("hasParams()")
    class HasParamsTests {

        @Test
        @DisplayName("Should return true when params is not empty")
        void shouldReturnTrueWhenParamsNotEmpty() {
            Edge edge = new Edge("trigger:start", "mcp:process", Map.of("key", "value"));

            assertTrue(edge.hasParams());
        }

        @Test
        @DisplayName("Should return false when params is null")
        void shouldReturnFalseWhenParamsNull() {
            Edge edge = new Edge("trigger:start", "mcp:process", null);

            assertFalse(edge.hasParams());
        }

        @Test
        @DisplayName("Should return false when params is empty")
        void shouldReturnFalseWhenParamsEmpty() {
            Edge edge = new Edge("trigger:start", "mcp:process", Map.of());

            assertFalse(edge.hasParams());
        }
    }

    @Nested
    @DisplayName("Record equality")
    class RecordEqualityTests {

        @Test
        @DisplayName("Should be equal for same values")
        void shouldBeEqualForSameValues() {
            Edge edge1 = new Edge("trigger:start", "mcp:process", Map.of("key", "value"));
            Edge edge2 = new Edge("trigger:start", "mcp:process", Map.of("key", "value"));

            assertEquals(edge1, edge2);
            assertEquals(edge1.hashCode(), edge2.hashCode());
        }

        @Test
        @DisplayName("Should not be equal for different from")
        void shouldNotBeEqualForDifferentFrom() {
            Edge edge1 = new Edge("trigger:start", "mcp:process");
            Edge edge2 = new Edge("trigger:other", "mcp:process");

            assertNotEquals(edge1, edge2);
        }

        @Test
        @DisplayName("Should not be equal for different to")
        void shouldNotBeEqualForDifferentTo() {
            Edge edge1 = new Edge("trigger:start", "mcp:process");
            Edge edge2 = new Edge("trigger:start", "mcp:other");

            assertNotEquals(edge1, edge2);
        }
    }
}
