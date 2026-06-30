package com.apimarketplace.orchestrator.services.resume.skip;

import com.apimarketplace.orchestrator.domain.workflow.Edge;
import com.apimarketplace.orchestrator.domain.workflow.ExecutionGraph;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReachabilityAnalyzer")
class ReachabilityAnalyzerTest {

    @Mock
    private SkipGraphAnalyzer graphAnalyzer;

    @Mock
    private ParentChildChecker parentChildChecker;

    @Mock
    private WorkflowPlan plan;

    @Mock
    private ExecutionGraph graph;

    private ReachabilityAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        analyzer = new ReachabilityAnalyzer(graphAnalyzer, parentChildChecker);
    }

    @Nested
    @DisplayName("isDependencyUnreachable()")
    class IsDependencyUnreachableTests {

        @Test
        @DisplayName("Should return true when node is already skipped")
        void shouldReturnTrueWhenSkipped() {
            Set<String> skippedStepIds = new HashSet<>(Set.of("mcp:step1"));
            Set<String> completedStepIds = new HashSet<>();

            boolean result = analyzer.isDependencyUnreachable(plan, graph, "mcp:step1", skippedStepIds, completedStepIds);

            assertTrue(result);
        }

        @Test
        @DisplayName("Should return true when parent is skipped")
        void shouldReturnTrueWhenParentSkipped() {
            Set<String> skippedStepIds = new HashSet<>(Set.of("core:loop"));
            Set<String> completedStepIds = new HashSet<>();
            when(parentChildChecker.isDependencyParentSkipped("core:loop::condition_checker", skippedStepIds))
                .thenReturn(true);

            boolean result = analyzer.isDependencyUnreachable(plan, graph, "core:loop::condition_checker", skippedStepIds, completedStepIds);

            assertTrue(result);
        }

        @Test
        @DisplayName("Should return false when node is completed")
        void shouldReturnFalseWhenCompleted() {
            Set<String> skippedStepIds = new HashSet<>();
            Set<String> completedStepIds = new HashSet<>(Set.of("mcp:step1"));
            when(parentChildChecker.isDependencyParentSkipped("mcp:step1", skippedStepIds)).thenReturn(false);

            boolean result = analyzer.isDependencyUnreachable(plan, graph, "mcp:step1", skippedStepIds, completedStepIds);

            assertFalse(result);
        }

        @Test
        @DisplayName("Should return false when no predecessors (entry point)")
        void shouldReturnFalseWhenEntryPoint() {
            Set<String> skippedStepIds = new HashSet<>();
            Set<String> completedStepIds = new HashSet<>();
            when(parentChildChecker.isDependencyParentSkipped("trigger:start", skippedStepIds)).thenReturn(false);
            when(graph.getDependencies("trigger:start")).thenReturn(null);
            when(graphAnalyzer.findPredecessorsFromEdges(plan, "trigger:start")).thenReturn(List.of());

            boolean result = analyzer.isDependencyUnreachable(plan, graph, "trigger:start", skippedStepIds, completedStepIds);

            assertFalse(result);
        }

        @Test
        @DisplayName("Should return true when all predecessors are skipped")
        void shouldReturnTrueWhenAllPredecessorsSkipped() {
            Set<String> skippedStepIds = new HashSet<>(Set.of("mcp:pred1", "mcp:pred2"));
            Set<String> completedStepIds = new HashSet<>();
            when(parentChildChecker.isDependencyParentSkipped("mcp:target", skippedStepIds)).thenReturn(false);
            when(graph.getDependencies("mcp:target")).thenReturn(null);
            when(graphAnalyzer.findPredecessorsFromEdges(plan, "mcp:target"))
                .thenReturn(List.of("mcp:pred1", "mcp:pred2"));

            boolean result = analyzer.isDependencyUnreachable(plan, graph, "mcp:target", skippedStepIds, completedStepIds);

            assertTrue(result);
        }

        @Test
        @DisplayName("Should return false when at least one predecessor is completed")
        void shouldReturnFalseWhenOnePredecessorCompleted() {
            Set<String> skippedStepIds = new HashSet<>(Set.of("mcp:pred1"));
            Set<String> completedStepIds = new HashSet<>(Set.of("mcp:pred2"));
            when(parentChildChecker.isDependencyParentSkipped("mcp:target", skippedStepIds)).thenReturn(false);
            when(graph.getDependencies("mcp:target")).thenReturn(null);
            when(graphAnalyzer.findPredecessorsFromEdges(plan, "mcp:target"))
                .thenReturn(List.of("mcp:pred1", "mcp:pred2"));

            boolean result = analyzer.isDependencyUnreachable(plan, graph, "mcp:target", skippedStepIds, completedStepIds);

            assertFalse(result);
        }
    }

    @Nested
    @DisplayName("isDependencyUnreachableShallow()")
    class IsDependencyUnreachableShallowTests {

        @Test
        @DisplayName("Should return true when node is skipped")
        void shouldReturnTrueWhenSkipped() {
            Set<String> skippedStepIds = new HashSet<>(Set.of("mcp:step1"));
            Set<String> completedStepIds = new HashSet<>();

            boolean result = analyzer.isDependencyUnreachableShallow(plan, graph, "mcp:step1", skippedStepIds, completedStepIds);

            assertTrue(result);
        }

        @Test
        @DisplayName("Should return false when node is completed")
        void shouldReturnFalseWhenCompleted() {
            Set<String> skippedStepIds = new HashSet<>();
            Set<String> completedStepIds = new HashSet<>(Set.of("mcp:step1"));
            when(parentChildChecker.isDependencyParentSkipped("mcp:step1", skippedStepIds)).thenReturn(false);

            boolean result = analyzer.isDependencyUnreachableShallow(plan, graph, "mcp:step1", skippedStepIds, completedStepIds);

            assertFalse(result);
        }

        @Test
        @DisplayName("Should return false for entry points (no predecessors)")
        void shouldReturnFalseForEntryPoints() {
            Set<String> skippedStepIds = new HashSet<>();
            Set<String> completedStepIds = new HashSet<>();
            when(parentChildChecker.isDependencyParentSkipped("trigger:start", skippedStepIds)).thenReturn(false);
            when(graphAnalyzer.findPredecessorsFromEdges(plan, "trigger:start")).thenReturn(List.of());

            boolean result = analyzer.isDependencyUnreachableShallow(plan, graph, "trigger:start", skippedStepIds, completedStepIds);

            assertFalse(result);
        }

        @Test
        @DisplayName("Should return true when all direct predecessors are skipped")
        void shouldReturnTrueWhenAllDirectPredecessorsSkipped() {
            Set<String> skippedStepIds = new HashSet<>(Set.of("mcp:pred1"));
            Set<String> completedStepIds = new HashSet<>();
            when(parentChildChecker.isDependencyParentSkipped("mcp:target", skippedStepIds)).thenReturn(false);
            when(graphAnalyzer.findPredecessorsFromEdges(plan, "mcp:target")).thenReturn(List.of("mcp:pred1"));

            boolean result = analyzer.isDependencyUnreachableShallow(plan, graph, "mcp:target", skippedStepIds, completedStepIds);

            assertTrue(result);
        }
    }

    @Nested
    @DisplayName("areAllPredecessorsUnreachable()")
    class AreAllPredecessorsUnreachableTests {

        @Test
        @DisplayName("Should return false when predecessor is completed")
        void shouldReturnFalseWhenPredecessorCompleted() {
            Set<String> skippedStepIds = new HashSet<>();
            Set<String> completedStepIds = new HashSet<>(Set.of("mcp:pred1"));
            Set<String> visited = new HashSet<>();

            boolean result = analyzer.areAllPredecessorsUnreachable(
                plan, graph, "mcp:target", List.of("mcp:pred1"), skippedStepIds, completedStepIds, visited
            );

            assertFalse(result);
        }

        @Test
        @DisplayName("Should return true when all predecessors are skipped")
        void shouldReturnTrueWhenAllSkipped() {
            Set<String> skippedStepIds = new HashSet<>(Set.of("mcp:pred1", "mcp:pred2"));
            Set<String> completedStepIds = new HashSet<>();
            Set<String> visited = new HashSet<>();

            boolean result = analyzer.areAllPredecessorsUnreachable(
                plan, graph, "mcp:target", List.of("mcp:pred1", "mcp:pred2"), skippedStepIds, completedStepIds, visited
            );

            assertTrue(result);
        }

        @Test
        @DisplayName("Should return true for cycles (breaks infinite loop)")
        void shouldReturnTrueForCycles() {
            Set<String> visited = new HashSet<>();
            visited.add("mcp:target"); // Already visited

            boolean result = analyzer.areAllPredecessorsUnreachable(
                plan, graph, "mcp:target", List.of("mcp:pred1"), new HashSet<>(), new HashSet<>(), visited
            );

            assertTrue(result);
        }

        @Test
        @DisplayName("Should return false when predecessor is entry point and not skipped")
        void shouldReturnFalseWhenPredecessorIsEntryPoint() {
            Set<String> skippedStepIds = new HashSet<>();
            Set<String> completedStepIds = new HashSet<>();
            Set<String> visited = new HashSet<>();
            when(graphAnalyzer.findPredecessorsFromEdges(plan, "mcp:pred1")).thenReturn(List.of());

            boolean result = analyzer.areAllPredecessorsUnreachable(
                plan, graph, "mcp:target", List.of("mcp:pred1"), skippedStepIds, completedStepIds, visited
            );

            assertFalse(result);
        }
    }
}
