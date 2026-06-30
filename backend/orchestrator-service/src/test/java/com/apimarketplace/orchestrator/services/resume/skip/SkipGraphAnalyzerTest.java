package com.apimarketplace.orchestrator.services.resume.skip;

import com.apimarketplace.orchestrator.domain.workflow.Edge;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SkipGraphAnalyzer")
class SkipGraphAnalyzerTest {

    @Mock
    private WorkflowPlan mockPlan;

    private SkipGraphAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        analyzer = new SkipGraphAnalyzer();
    }

    @Nested
    @DisplayName("findSuccessorsFromEdges()")
    class FindSuccessorsTests {

        @Test
        @DisplayName("Should find direct successors from edges")
        void shouldFindDirectSuccessors() {
            Edge edge1 = new Edge("trigger:start", "mcp:step1");
            Edge edge2 = new Edge("trigger:start", "mcp:step2");
            when(mockPlan.getEdges()).thenReturn(List.of(edge1, edge2));

            List<String> successors = analyzer.findSuccessorsFromEdges(mockPlan, "trigger:start");

            assertEquals(2, successors.size());
            assertTrue(successors.contains("mcp:step1"));
            assertTrue(successors.contains("mcp:step2"));
        }

        @Test
        @DisplayName("Should return empty list when no edges")
        void shouldReturnEmptyWhenNoEdges() {
            when(mockPlan.getEdges()).thenReturn(null);

            List<String> successors = analyzer.findSuccessorsFromEdges(mockPlan, "trigger:start");

            assertTrue(successors.isEmpty());
        }

        @Test
        @DisplayName("Should return empty list when node has no outgoing edges")
        void shouldReturnEmptyWhenNoOutgoing() {
            Edge edge = new Edge("trigger:start", "mcp:step1");
            when(mockPlan.getEdges()).thenReturn(List.of(edge));

            List<String> successors = analyzer.findSuccessorsFromEdges(mockPlan, "mcp:step1");

            assertTrue(successors.isEmpty());
        }

        @Test
        @DisplayName("Should handle edges with ports (decision:check:if)")
        void shouldHandleEdgesWithPorts() {
            Edge edge = new Edge("core:check:if", "mcp:process");
            when(mockPlan.getEdges()).thenReturn(List.of(edge));

            List<String> successors = analyzer.findSuccessorsFromEdges(mockPlan, "core:check");

            assertEquals(1, successors.size());
            assertTrue(successors.contains("mcp:process"));
        }

        @Test
        @DisplayName("Should not include duplicate successors")
        void shouldNotIncludeDuplicates() {
            Edge edge1 = new Edge("core:check:if", "mcp:step1");
            Edge edge2 = new Edge("core:check:else", "mcp:step1");
            when(mockPlan.getEdges()).thenReturn(List.of(edge1, edge2));

            List<String> successors = analyzer.findSuccessorsFromEdges(mockPlan, "core:check");

            assertEquals(1, successors.size());
        }

        @Test
        @DisplayName("Should skip edges with null from or to")
        void shouldSkipNullEdges() {
            Edge edge1 = new Edge(null, "mcp:step1");
            Edge edge2 = new Edge("trigger:start", null);
            Edge edge3 = new Edge("trigger:start", "mcp:step1");
            when(mockPlan.getEdges()).thenReturn(List.of(edge1, edge2, edge3));

            List<String> successors = analyzer.findSuccessorsFromEdges(mockPlan, "trigger:start");

            assertEquals(1, successors.size());
        }
    }

    @Nested
    @DisplayName("findPredecessorsFromEdges()")
    class FindPredecessorsTests {

        @Test
        @DisplayName("Should find direct predecessors from edges")
        void shouldFindDirectPredecessors() {
            Edge edge1 = new Edge("mcp:step1", "mcp:final");
            Edge edge2 = new Edge("mcp:step2", "mcp:final");
            when(mockPlan.getEdges()).thenReturn(List.of(edge1, edge2));

            List<String> predecessors = analyzer.findPredecessorsFromEdges(mockPlan, "mcp:final");

            assertEquals(2, predecessors.size());
            assertTrue(predecessors.contains("mcp:step1"));
            assertTrue(predecessors.contains("mcp:step2"));
        }

        @Test
        @DisplayName("Should return empty list when no edges")
        void shouldReturnEmptyWhenNoEdges() {
            when(mockPlan.getEdges()).thenReturn(null);

            List<String> predecessors = analyzer.findPredecessorsFromEdges(mockPlan, "mcp:step1");

            assertTrue(predecessors.isEmpty());
        }

        @Test
        @DisplayName("Should handle edges with ports in 'to' field")
        void shouldHandlePortsInTo() {
            Edge edge = new Edge("trigger:start", "core:check");
            when(mockPlan.getEdges()).thenReturn(List.of(edge));

            List<String> predecessors = analyzer.findPredecessorsFromEdges(mockPlan, "core:check");

            assertEquals(1, predecessors.size());
            assertTrue(predecessors.contains("trigger:start"));
        }
    }
}
