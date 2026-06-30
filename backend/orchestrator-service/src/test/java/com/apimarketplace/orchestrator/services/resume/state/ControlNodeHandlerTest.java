package com.apimarketplace.orchestrator.services.resume.state;

import com.apimarketplace.orchestrator.domain.workflow.Edge;
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
@DisplayName("ControlNodeHandler")
class ControlNodeHandlerTest {

    @Mock
    private StateReconstructorHelper helper;

    @Mock
    private WorkflowPlan plan;

    private ControlNodeHandler handler;

    @BeforeEach
    void setUp() {
        handler = new ControlNodeHandler(helper);
    }

    @Nested
    @DisplayName("removeSkippedStepsWithCompletedPredecessors()")
    class RemoveSkippedStepsWithCompletedPredecessorsTests {

        @Test
        @DisplayName("Should remove skipped step with completed predecessor")
        void shouldRemoveSkippedWithCompletedPredecessor() {
            when(plan.getEdges()).thenReturn(List.of(new Edge("mcp:pred1", "mcp:merge")));

            Set<String> completedStepIds = new HashSet<>(Set.of("mcp:pred1"));
            Set<String> skippedStepIds = new HashSet<>(Set.of("mcp:merge"));

            handler.removeSkippedStepsWithCompletedPredecessors(plan, completedStepIds, skippedStepIds);

            assertFalse(skippedStepIds.contains("mcp:merge"));
        }

        @Test
        @DisplayName("Should handle null plan")
        void shouldHandleNullPlan() {
            Set<String> skippedStepIds = new HashSet<>(Set.of("mcp:step1"));

            handler.removeSkippedStepsWithCompletedPredecessors(null, Set.of(), skippedStepIds);

            assertTrue(skippedStepIds.contains("mcp:step1"));
        }

        @Test
        @DisplayName("Should do nothing for empty skipped set")
        void shouldDoNothingForEmptySkippedSet() {
            Set<String> skippedStepIds = new HashSet<>();

            handler.removeSkippedStepsWithCompletedPredecessors(plan, Set.of("mcp:step1"), skippedStepIds);

            assertTrue(skippedStepIds.isEmpty());
        }
    }

    @Nested
    @DisplayName("findPredecessorsFromEdges()")
    class FindPredecessorsFromEdgesTests {

        @Test
        @DisplayName("Should find predecessors from edges")
        void shouldFindPredecessors() {
            when(plan.getEdges()).thenReturn(List.of(
                new Edge("trigger:start", "mcp:step1"),
                new Edge("mcp:step1", "mcp:step2")
            ));

            List<String> predecessors = handler.findPredecessorsFromEdges(plan, "mcp:step2");

            assertEquals(1, predecessors.size());
            assertTrue(predecessors.contains("mcp:step1"));
        }

        @Test
        @DisplayName("Should return empty when no edges")
        void shouldReturnEmptyWhenNoEdges() {
            when(plan.getEdges()).thenReturn(null);

            List<String> predecessors = handler.findPredecessorsFromEdges(plan, "mcp:step1");

            assertTrue(predecessors.isEmpty());
        }

        @Test
        @DisplayName("Should not include duplicates")
        void shouldNotIncludeDuplicates() {
            when(plan.getEdges()).thenReturn(List.of(
                new Edge("core:check:if", "mcp:step1"),
                new Edge("core:check:else", "mcp:step1")
            ));

            List<String> predecessors = handler.findPredecessorsFromEdges(plan, "mcp:step1");

            assertEquals(1, predecessors.size());
            assertTrue(predecessors.contains("core:check"));
        }
    }
}
