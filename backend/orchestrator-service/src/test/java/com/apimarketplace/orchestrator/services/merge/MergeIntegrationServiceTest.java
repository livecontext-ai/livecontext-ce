package com.apimarketplace.orchestrator.services.merge;

import com.apimarketplace.orchestrator.domain.workflow.Core;
import com.apimarketplace.orchestrator.domain.workflow.Edge;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.domain.workflow.StepExecutionResult;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for MergeIntegrationService.
 *
 * This service integrates merge operations with the workflow execution flow.
 * It bridges the gap between step completions and the merge collector.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MergeIntegrationService")
class MergeIntegrationServiceTest {

    @Mock
    private ItemMergeCollector collector;

    @Mock
    private WorkflowPlan plan;

    private MergeIntegrationService service;

    @BeforeEach
    void setUp() {
        service = new MergeIntegrationService(collector);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // initializeForWorkflow() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("initializeForWorkflow()")
    class InitializeForWorkflowTests {

        @Test
        @DisplayName("Should initialize with empty plan")
        void shouldInitializeWithEmptyPlan() {
            when(plan.getCores()).thenReturn(List.of());
            when(plan.getEdges()).thenReturn(List.of());

            assertDoesNotThrow(() -> service.initializeForWorkflow("run-1", plan));
        }

        @Test
        @DisplayName("Should detect implicit merge points from edges")
        void shouldDetectImplicitMergePointsFromEdges() {
            when(plan.getCores()).thenReturn(List.of());

            // Two edges going to same target = implicit merge
            Edge edge1 = mock(Edge.class);
            when(edge1.from()).thenReturn("mcp:step1");
            when(edge1.to()).thenReturn("mcp:final");

            Edge edge2 = mock(Edge.class);
            when(edge2.from()).thenReturn("mcp:step2");
            when(edge2.to()).thenReturn("mcp:final");

            when(plan.getEdges()).thenReturn(List.of(edge1, edge2));

            service.initializeForWorkflow("run-1", plan);

            // Verify merge points registered
            Set<String> mergePoints = service.getMergePointsForNode("run-1", "mcp:step1");
            assertFalse(mergePoints.isEmpty());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // initializeMergePoint() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("initializeMergePoint()")
    class InitializeMergePointTests {

        @Test
        @DisplayName("Should return false when no sources found")
        void shouldReturnFalseWhenNoSourcesFound() {
            boolean result = service.initializeMergePoint("run-1", "unknown-merge", "0");

            assertFalse(result);
        }

        @Test
        @DisplayName("Should return true when already initialized")
        void shouldReturnTrueWhenAlreadyInitialized() {
            when(plan.getCores()).thenReturn(List.of());
            Edge edge1 = mock(Edge.class);
            when(edge1.from()).thenReturn("mcp:step1");
            when(edge1.to()).thenReturn("mcp:final");
            Edge edge2 = mock(Edge.class);
            when(edge2.from()).thenReturn("mcp:step2");
            when(edge2.to()).thenReturn("mcp:final");
            when(plan.getEdges()).thenReturn(List.of(edge1, edge2));

            service.initializeForWorkflow("run-1", plan);

            // Initialize twice
            boolean first = service.initializeMergePoint("run-1", "core:mcp:final", "0");
            boolean second = service.initializeMergePoint("run-1", "core:mcp:final", "0");

            assertTrue(first);
            assertTrue(second);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // registerSplitCount() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("registerSplitCount()")
    class RegisterSplitCountTests {

        @Test
        @DisplayName("Should do nothing when no merge points for node")
        void shouldDoNothingWhenNoMergePointsForNode() {
            service.registerSplitCount("run-1", "unknown-node", "0", 5);

            verifyNoInteractions(collector);
        }

        @Test
        @DisplayName("Should set expected count on collector")
        void shouldSetExpectedCountOnCollector() {
            // Setup: create edges that make mcp:split contribute to merge
            when(plan.getCores()).thenReturn(List.of());
            Edge edge1 = mock(Edge.class);
            when(edge1.from()).thenReturn("mcp:split");
            when(edge1.to()).thenReturn("mcp:final");
            Edge edge2 = mock(Edge.class);
            when(edge2.from()).thenReturn("mcp:other");
            when(edge2.to()).thenReturn("mcp:final");
            when(plan.getEdges()).thenReturn(List.of(edge1, edge2));

            service.initializeForWorkflow("run-1", plan);

            service.registerSplitCount("run-1", "mcp:split", "0", 5);

            verify(collector, atLeastOnce()).setExpectedCount(eq("run-1"), anyString(), eq("0"), eq("mcp:split"), eq(5));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // recordCompletion() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("recordCompletion()")
    class RecordCompletionTests {

        @Test
        @DisplayName("Should return null when node has no merge points")
        void shouldReturnNullWhenNodeHasNoMergePoints() {
            StepExecutionResult result = StepExecutionResult.success("step-1", Map.of(), 100L);

            MergeResult mergeResult = service.recordCompletion(
                    "run-1", "0", 0, "mcp:unknown", Map.of(), result
            );

            assertNull(mergeResult);
        }

        @Test
        @DisplayName("Should record success to collector")
        void shouldRecordSuccessToCollector() {
            // Setup merge points
            when(plan.getCores()).thenReturn(List.of());
            Edge edge1 = mock(Edge.class);
            when(edge1.from()).thenReturn("mcp:step1");
            when(edge1.to()).thenReturn("mcp:final");
            Edge edge2 = mock(Edge.class);
            when(edge2.from()).thenReturn("mcp:step2");
            when(edge2.to()).thenReturn("mcp:final");
            when(plan.getEdges()).thenReturn(List.of(edge1, edge2));

            service.initializeForWorkflow("run-1", plan);

            StepExecutionResult stepResult = StepExecutionResult.success("step-1", Map.of("data", "value"), 100L);
            when(collector.recordSuccess(anyString(), anyString(), anyString(), anyInt(), anyString(), any()))
                    .thenReturn(MergeResult.waiting("core:mcp:final", "0", null));

            MergeResult result = service.recordCompletion(
                    "run-1", "0", 0, "mcp:step1", Map.of("data", "value"), stepResult
            );

            verify(collector, atLeastOnce()).recordSuccess(eq("run-1"), anyString(), eq("0"), eq(0), eq("mcp:step1"), any());
        }

        @Test
        @DisplayName("Should record failure to collector")
        void shouldRecordFailureToCollector() {
            // Setup merge points
            when(plan.getCores()).thenReturn(List.of());
            Edge edge1 = mock(Edge.class);
            when(edge1.from()).thenReturn("mcp:step1");
            when(edge1.to()).thenReturn("mcp:final");
            Edge edge2 = mock(Edge.class);
            when(edge2.from()).thenReturn("mcp:step2");
            when(edge2.to()).thenReturn("mcp:final");
            when(plan.getEdges()).thenReturn(List.of(edge1, edge2));

            service.initializeForWorkflow("run-1", plan);

            StepExecutionResult stepResult = StepExecutionResult.failure("step-1", "Error", new RuntimeException("Error"), 100L);
            when(collector.recordFailure(anyString(), anyString(), anyString(), anyInt(), anyString(), anyString()))
                    .thenReturn(MergeResult.waiting("core:mcp:final", "0", null));

            MergeResult result = service.recordCompletion(
                    "run-1", "0", 0, "mcp:step1", Map.of(), stepResult
            );

            verify(collector, atLeastOnce()).recordFailure(eq("run-1"), anyString(), eq("0"), eq(0), eq("mcp:step1"), anyString());
        }

        @Test
        @DisplayName("Should record skipped to collector")
        void shouldRecordSkippedToCollector() {
            // Setup merge points
            when(plan.getCores()).thenReturn(List.of());
            Edge edge1 = mock(Edge.class);
            when(edge1.from()).thenReturn("mcp:step1");
            when(edge1.to()).thenReturn("mcp:final");
            Edge edge2 = mock(Edge.class);
            when(edge2.from()).thenReturn("mcp:step2");
            when(edge2.to()).thenReturn("mcp:final");
            when(plan.getEdges()).thenReturn(List.of(edge1, edge2));

            service.initializeForWorkflow("run-1", plan);

            StepExecutionResult stepResult = StepExecutionResult.skipped("step-1", "Branch not taken");
            when(collector.recordSkipped(anyString(), anyString(), anyString(), anyInt(), anyString(), anyString()))
                    .thenReturn(MergeResult.waiting("core:mcp:final", "0", null));

            MergeResult result = service.recordCompletion(
                    "run-1", "0", 0, "mcp:step1", Map.of(), stepResult
            );

            verify(collector, atLeastOnce()).recordSkipped(eq("run-1"), anyString(), eq("0"), eq(0), eq("mcp:step1"), anyString());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // getMergeState() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getMergeState()")
    class GetMergeStateTests {

        @Test
        @DisplayName("Should delegate to collector")
        void shouldDelegateToCollector() {
            ItemMergeState mockState = mock(ItemMergeState.class);
            when(collector.getMergeState("run-1", "merge-1", "0")).thenReturn(mockState);

            ItemMergeState result = service.getMergeState("run-1", "merge-1", "0");

            assertEquals(mockState, result);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // isMergeComplete() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("isMergeComplete()")
    class IsMergeCompleteTests {

        @Test
        @DisplayName("Should delegate to collector")
        void shouldDelegateToCollector() {
            when(collector.isComplete("run-1", "merge-1", "0")).thenReturn(true);

            boolean result = service.isMergeComplete("run-1", "merge-1", "0");

            assertTrue(result);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // cleanupRun() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("cleanupRun()")
    class CleanupRunTests {

        @Test
        @DisplayName("Should cleanup collector and local mappings")
        void shouldCleanupCollectorAndLocalMappings() {
            // Setup some state
            when(plan.getCores()).thenReturn(List.of());
            Edge edge1 = mock(Edge.class);
            when(edge1.from()).thenReturn("mcp:step1");
            when(edge1.to()).thenReturn("mcp:final");
            Edge edge2 = mock(Edge.class);
            when(edge2.from()).thenReturn("mcp:step2");
            when(edge2.to()).thenReturn("mcp:final");
            when(plan.getEdges()).thenReturn(List.of(edge1, edge2));

            service.initializeForWorkflow("run-1", plan);

            service.cleanupRun("run-1");

            verify(collector).cleanupRun("run-1");

            // Merge points should be cleared
            Set<String> mergePoints = service.getMergePointsForNode("run-1", "mcp:step1");
            assertTrue(mergePoints.isEmpty());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // getMergePointsForNode() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getMergePointsForNode()")
    class GetMergePointsForNodeTests {

        @Test
        @DisplayName("Should return empty set for unknown node")
        void shouldReturnEmptySetForUnknownNode() {
            Set<String> mergePoints = service.getMergePointsForNode("run-1", "unknown");

            assertTrue(mergePoints.isEmpty());
        }

        @Test
        @DisplayName("Should return merge points for registered node")
        void shouldReturnMergePointsForRegisteredNode() {
            when(plan.getCores()).thenReturn(List.of());
            Edge edge1 = mock(Edge.class);
            when(edge1.from()).thenReturn("mcp:step1");
            when(edge1.to()).thenReturn("mcp:final");
            Edge edge2 = mock(Edge.class);
            when(edge2.from()).thenReturn("mcp:step2");
            when(edge2.to()).thenReturn("mcp:final");
            when(plan.getEdges()).thenReturn(List.of(edge1, edge2));

            service.initializeForWorkflow("run-1", plan);

            Set<String> mergePoints = service.getMergePointsForNode("run-1", "mcp:step1");

            assertFalse(mergePoints.isEmpty());
        }
    }
}
