package com.apimarketplace.orchestrator.services.resume.skip;

import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SkippedStepCleanup")
class SkippedStepCleanupTest {

    @Mock
    private SkipGraphAnalyzer graphAnalyzer;

    @Mock
    private WorkflowPlan plan;

    private SkippedStepCleanup cleanup;

    @BeforeEach
    void setUp() {
        cleanup = new SkippedStepCleanup(graphAnalyzer);
    }

    @Nested
    @DisplayName("removeSkippedStepsWithCompletedPredecessors()")
    class RemoveSkippedStepsTests {

        @Test
        @DisplayName("Should remove skipped step with completed predecessor")
        void shouldRemoveSkippedWithCompletedPredecessor() {
            Set<String> completedStepIds = new HashSet<>(Set.of("mcp:pred1"));
            Set<String> skippedStepIds = new HashSet<>(Set.of("mcp:merge_node"));

            when(graphAnalyzer.findPredecessorsFromEdges(plan, "mcp:merge_node"))
                .thenReturn(List.of("mcp:pred1", "mcp:pred2"));

            cleanup.removeSkippedStepsWithCompletedPredecessors(plan, completedStepIds, skippedStepIds);

            assertFalse(skippedStepIds.contains("mcp:merge_node"));
        }

        @Test
        @DisplayName("Should not remove skipped step without completed predecessors")
        void shouldNotRemoveWithoutCompletedPredecessors() {
            Set<String> completedStepIds = new HashSet<>();
            Set<String> skippedStepIds = new HashSet<>(Set.of("mcp:merge_node"));

            when(graphAnalyzer.findPredecessorsFromEdges(plan, "mcp:merge_node"))
                .thenReturn(List.of("mcp:pred1"));

            cleanup.removeSkippedStepsWithCompletedPredecessors(plan, completedStepIds, skippedStepIds);

            assertTrue(skippedStepIds.contains("mcp:merge_node"));
        }

        @Test
        @DisplayName("Should handle empty skipped set")
        void shouldHandleEmptySkippedSet() {
            Set<String> completedStepIds = new HashSet<>(Set.of("mcp:pred1"));
            Set<String> skippedStepIds = new HashSet<>();

            cleanup.removeSkippedStepsWithCompletedPredecessors(plan, completedStepIds, skippedStepIds);

            assertTrue(skippedStepIds.isEmpty());
        }

        @Test
        @DisplayName("Should handle null plan")
        void shouldHandleNullPlan() {
            Set<String> completedStepIds = new HashSet<>(Set.of("mcp:pred1"));
            Set<String> skippedStepIds = new HashSet<>(Set.of("mcp:step1"));

            cleanup.removeSkippedStepsWithCompletedPredecessors(null, completedStepIds, skippedStepIds);

            assertTrue(skippedStepIds.contains("mcp:step1"));
        }

        @Test
        @DisplayName("Should not remove skipped step with no predecessors")
        void shouldNotRemoveWithNoPredecessors() {
            Set<String> completedStepIds = new HashSet<>(Set.of("mcp:pred1"));
            Set<String> skippedStepIds = new HashSet<>(Set.of("mcp:orphan"));

            when(graphAnalyzer.findPredecessorsFromEdges(plan, "mcp:orphan"))
                .thenReturn(List.of());

            cleanup.removeSkippedStepsWithCompletedPredecessors(plan, completedStepIds, skippedStepIds);

            assertTrue(skippedStepIds.contains("mcp:orphan"));
        }

        @Test
        @DisplayName("Should handle parent node completion for virtual nodes")
        void shouldHandleParentNodeCompletionForVirtualNodes() {
            Set<String> completedStepIds = new HashSet<>(Set.of("core:while"));
            Set<String> skippedStepIds = new HashSet<>(Set.of("mcp:after_loop"));

            when(graphAnalyzer.findPredecessorsFromEdges(plan, "mcp:after_loop"))
                .thenReturn(List.of("core:while::condition_checker"));

            cleanup.removeSkippedStepsWithCompletedPredecessors(plan, completedStepIds, skippedStepIds);

            assertFalse(skippedStepIds.contains("mcp:after_loop"));
        }

        @Test
        @DisplayName("Should remove multiple skipped steps with completed predecessors")
        void shouldRemoveMultipleSkippedSteps() {
            Set<String> completedStepIds = new HashSet<>(Set.of("mcp:pred1"));
            Set<String> skippedStepIds = new HashSet<>(Set.of("mcp:step_a", "mcp:step_b"));

            when(graphAnalyzer.findPredecessorsFromEdges(plan, "mcp:step_a"))
                .thenReturn(List.of("mcp:pred1"));
            when(graphAnalyzer.findPredecessorsFromEdges(plan, "mcp:step_b"))
                .thenReturn(List.of("mcp:pred1"));

            cleanup.removeSkippedStepsWithCompletedPredecessors(plan, completedStepIds, skippedStepIds);

            assertTrue(skippedStepIds.isEmpty());
        }
    }
}
