package com.apimarketplace.orchestrator.services.streaming;

import com.apimarketplace.orchestrator.domain.workflow.Edge;
import com.apimarketplace.orchestrator.domain.workflow.ExecutionGraph;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.services.state.StateSnapshotService;
import com.apimarketplace.orchestrator.services.streaming.bus.WorkflowEventPublisher;
import com.apimarketplace.orchestrator.services.streaming.events.EdgeLifecycle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for EdgeStatusService.
 *
 * This service publishes edge events to the WorkflowEventBus,
 * handling edge registration and lifecycle transitions.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EdgeStatusService")
class EdgeStatusServiceTest {

    @Mock
    private WorkflowEventPublisher eventPublisher;

    @Mock
    private StateSnapshotService stateSnapshotService;

    @Mock
    private WorkflowExecution execution;

    @Mock
    private WorkflowPlan plan;

    @Mock
    private ExecutionGraph graph;

    private EdgeStatusService service;

    @BeforeEach
    void setUp() {
        service = new EdgeStatusService(eventPublisher, stateSnapshotService);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // markIncomingEdgesRunning() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("markIncomingEdgesRunning()")
    class MarkIncomingEdgesRunningTests {

        @Test
        @DisplayName("Should emit RUNNING events for all incoming edges")
        void shouldEmitRunningEventsForAllIncomingEdges() {
            when(execution.getRunId()).thenReturn("run-1");
            when(execution.getPlan()).thenReturn(plan);
            when(plan.getExecutionGraph()).thenReturn(graph);
            when(graph.getDependencies("mcp:step2")).thenReturn(Set.of("trigger:start", "mcp:step1"));

            service.markIncomingEdgesRunning(execution, "mcp:step2");

            verify(eventPublisher, times(2)).emitEdge(
                eq("run-1"),
                anyString(),
                anyString(),
                eq("mcp:step2"),
                eq(EdgeLifecycle.RUNNING),
                isNull(),
                isNull()
            );
        }

        @Test
        @DisplayName("Should do nothing when execution is null")
        void shouldDoNothingWhenExecutionIsNull() {
            service.markIncomingEdgesRunning(null, "mcp:step2");

            verifyNoInteractions(eventPublisher);
        }

        @Test
        @DisplayName("Should do nothing when stepId is null")
        void shouldDoNothingWhenStepIdIsNull() {
            service.markIncomingEdgesRunning(execution, null);

            verifyNoInteractions(eventPublisher);
        }

        @Test
        @DisplayName("Should include itemIndex when provided")
        void shouldIncludeItemIndexWhenProvided() {
            when(execution.getRunId()).thenReturn("run-1");
            when(execution.getPlan()).thenReturn(plan);
            when(plan.getExecutionGraph()).thenReturn(graph);
            when(graph.getDependencies("mcp:step2")).thenReturn(Set.of("mcp:step1"));

            service.markIncomingEdgesRunning(execution, "mcp:step2", 5);

            verify(eventPublisher).emitEdge(
                eq("run-1"),
                anyString(),
                eq("mcp:step1"),
                eq("mcp:step2"),
                eq(EdgeLifecycle.RUNNING),
                eq(5),
                isNull()
            );
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // markIncomingEdgesCompleted() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("markIncomingEdgesCompleted()")
    class MarkIncomingEdgesCompletedTests {

        @Test
        @DisplayName("Should emit COMPLETED events for all incoming edges")
        void shouldEmitCompletedEventsForAllIncomingEdges() {
            when(execution.getRunId()).thenReturn("run-1");
            when(execution.getPlan()).thenReturn(plan);
            when(plan.getExecutionGraph()).thenReturn(graph);
            when(graph.getDependencies("mcp:step2")).thenReturn(Set.of("mcp:step1"));

            service.markIncomingEdgesCompleted(execution, "mcp:step2");

            verify(eventPublisher).emitEdge(
                eq("run-1"),
                anyString(),
                eq("mcp:step1"),
                eq("mcp:step2"),
                eq(EdgeLifecycle.COMPLETED),
                isNull(),
                isNull()
            );
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // markIncomingEdgesSkipped() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("markIncomingEdgesSkipped()")
    class MarkIncomingEdgesSkippedTests {

        @Test
        @DisplayName("Should emit SKIPPED events for all incoming edges")
        void shouldEmitSkippedEventsForAllIncomingEdges() {
            when(execution.getRunId()).thenReturn("run-1");
            when(execution.getPlan()).thenReturn(plan);
            when(plan.getExecutionGraph()).thenReturn(graph);
            when(graph.getDependencies("mcp:step2")).thenReturn(Set.of("mcp:step1"));

            service.markIncomingEdgesSkipped(execution, "mcp:step2");

            verify(eventPublisher).emitEdge(
                eq("run-1"),
                anyString(),
                eq("mcp:step1"),
                eq("mcp:step2"),
                eq(EdgeLifecycle.SKIPPED),
                isNull(),
                isNull()
            );
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // markEdgeRunning() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("markEdgeRunning()")
    class MarkEdgeRunningTests {

        @Test
        @DisplayName("Should emit RUNNING event for specific edge")
        void shouldEmitRunningEventForSpecificEdge() {
            when(execution.getRunId()).thenReturn("run-1");

            service.markEdgeRunning(execution, "trigger:start", "mcp:step1");

            verify(eventPublisher).emitEdge(
                eq("run-1"),
                eq("trigger:start->mcp:step1"),
                eq("trigger:start"),
                eq("mcp:step1"),
                eq(EdgeLifecycle.RUNNING),
                isNull(),
                isNull()
            );
        }

        @Test
        @DisplayName("Should include itemIndex and iteration when provided")
        void shouldIncludeItemIndexAndIterationWhenProvided() {
            when(execution.getRunId()).thenReturn("run-1");

            service.markEdgeRunning(execution, "core:loop", "mcp:step1", 5, 3);

            verify(eventPublisher).emitEdge(
                eq("run-1"),
                anyString(),
                eq("core:loop"),
                eq("mcp:step1"),
                eq(EdgeLifecycle.RUNNING),
                eq(5),
                eq(3)
            );
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // markEdgeCompleted() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("markEdgeCompleted()")
    class MarkEdgeCompletedTests {

        @Test
        @DisplayName("Should emit COMPLETED event for specific edge")
        void shouldEmitCompletedEventForSpecificEdge() {
            when(execution.getRunId()).thenReturn("run-1");

            service.markEdgeCompleted(execution, "trigger:start", "mcp:step1");

            verify(eventPublisher).emitEdge(
                eq("run-1"),
                anyString(),
                eq("trigger:start"),
                eq("mcp:step1"),
                eq(EdgeLifecycle.COMPLETED),
                isNull(),
                isNull()
            );
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // markEdgeSkipped() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("markEdgeSkipped()")
    class MarkEdgeSkippedTests {

        @Test
        @DisplayName("Should emit SKIPPED event for specific edge")
        void shouldEmitSkippedEventForSpecificEdge() {
            when(execution.getRunId()).thenReturn("run-1");

            // "core:decision:else" preserves port via normalizePreservingPort
            service.markEdgeSkipped(execution, "core:decision:else", "mcp:step2");

            verify(eventPublisher).emitEdge(
                eq("run-1"),
                anyString(),
                eq("core:decision:else"),
                eq("mcp:step2"),
                eq(EdgeLifecycle.SKIPPED),
                isNull(),
                isNull()
            );
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // registerWorkflowEdges() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("registerWorkflowEdges()")
    class RegisterWorkflowEdgesTests {

        @Test
        @DisplayName("Should register all edges from plan")
        void shouldRegisterAllEdgesFromPlan() {
            when(execution.getRunId()).thenReturn("run-1");
            when(execution.getPlan()).thenReturn(plan);

            Edge edge1 = mock(Edge.class);
            when(edge1.from()).thenReturn("trigger:start");
            when(edge1.to()).thenReturn("mcp:step1");

            Edge edge2 = mock(Edge.class);
            when(edge2.from()).thenReturn("mcp:step1");
            when(edge2.to()).thenReturn("mcp:step2");

            when(plan.getEdges()).thenReturn(List.of(edge1, edge2));

            service.registerWorkflowEdges(execution);

            verify(eventPublisher, times(2)).emitEdge(
                eq("run-1"),
                anyString(),
                anyString(),
                anyString(),
                eq(EdgeLifecycle.REGISTERED),
                isNull(),
                isNull()
            );
        }

        @Test
        @DisplayName("Should do nothing when execution is null")
        void shouldDoNothingWhenExecutionIsNull() {
            service.registerWorkflowEdges(null);

            verifyNoInteractions(eventPublisher);
        }

        @Test
        @DisplayName("Should do nothing when plan is null")
        void shouldDoNothingWhenPlanIsNull() {
            when(execution.getPlan()).thenReturn(null);

            service.registerWorkflowEdges(execution);

            verifyNoInteractions(eventPublisher);
        }

        @Test
        @DisplayName("Should skip edges with null from or to")
        void shouldSkipEdgesWithNullFromOrTo() {
            when(execution.getRunId()).thenReturn("run-1");
            when(execution.getPlan()).thenReturn(plan);

            Edge validEdge = mock(Edge.class);
            when(validEdge.from()).thenReturn("trigger:start");
            when(validEdge.to()).thenReturn("mcp:step1");

            Edge nullFromEdge = mock(Edge.class);
            when(nullFromEdge.from()).thenReturn(null);
            lenient().when(nullFromEdge.to()).thenReturn("mcp:step2");

            Edge nullToEdge = mock(Edge.class);
            when(nullToEdge.from()).thenReturn("mcp:step1");
            when(nullToEdge.to()).thenReturn(null);

            when(plan.getEdges()).thenReturn(List.of(validEdge, nullFromEdge, nullToEdge));

            service.registerWorkflowEdges(execution);

            // Only the valid edge should be registered
            verify(eventPublisher, times(1)).emitEdge(
                anyString(), anyString(), anyString(), anyString(),
                eq(EdgeLifecycle.REGISTERED), isNull(), isNull()
            );
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // markDescendantEdgesSkipped() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("markDescendantEdgesSkipped()")
    class MarkDescendantEdgesSkippedTests {

        @Test
        @DisplayName("Should mark all descendant edges as skipped")
        void shouldMarkAllDescendantEdgesAsSkipped() {
            when(execution.getRunId()).thenReturn("run-1");
            when(execution.getPlan()).thenReturn(plan);
            when(plan.getExecutionGraph()).thenReturn(graph);

            // Graph: root -> step1 -> step2
            when(graph.getDependents("mcp:root")).thenReturn(Set.of("mcp:step1"));
            when(graph.getDependents("mcp:step1")).thenReturn(Set.of("mcp:step2"));
            when(graph.getDependents("mcp:step2")).thenReturn(Set.of());

            service.markDescendantEdgesSkipped(execution, "mcp:root", null);

            verify(eventPublisher, times(2)).emitEdge(
                eq("run-1"),
                anyString(),
                anyString(),
                anyString(),
                eq(EdgeLifecycle.SKIPPED),
                isNull(),
                isNull()
            );
        }

        @Test
        @DisplayName("Should respect descendant filter when provided")
        void shouldRespectDescendantFilterWhenProvided() {
            when(execution.getRunId()).thenReturn("run-1");
            when(execution.getPlan()).thenReturn(plan);
            when(plan.getExecutionGraph()).thenReturn(graph);

            when(graph.getDependents("mcp:root")).thenReturn(Set.of("mcp:step1", "mcp:step2"));
            when(graph.getDependents("mcp:step1")).thenReturn(Set.of());
            when(graph.getDependents("mcp:step2")).thenReturn(Set.of());

            // Only include step1 in filter
            service.markDescendantEdgesSkipped(execution, "mcp:root", Set.of("mcp:step1"));

            // Only edge to step1 should be marked as skipped
            ArgumentCaptor<String> toCaptor = ArgumentCaptor.forClass(String.class);
            verify(eventPublisher, times(1)).emitEdge(
                eq("run-1"),
                anyString(),
                anyString(),
                toCaptor.capture(),
                eq(EdgeLifecycle.SKIPPED),
                isNull(),
                isNull()
            );

            assertEquals("mcp:step1", toCaptor.getValue());
        }

        @Test
        @DisplayName("Should do nothing when graph is null")
        void shouldDoNothingWhenGraphIsNull() {
            when(execution.getPlan()).thenReturn(plan);
            when(plan.getExecutionGraph()).thenReturn(null);

            service.markDescendantEdgesSkipped(execution, "mcp:root", null);

            verifyNoInteractions(eventPublisher);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Step ID normalization tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Step ID Normalization")
    class StepIdNormalizationTests {

        @Test
        @DisplayName("Should strip #item- suffix from step IDs")
        void shouldStripItemSuffixFromStepIds() {
            when(execution.getRunId()).thenReturn("run-1");

            service.markEdgeRunning(execution, "mcp:step1#item-5", "mcp:step2#item-5");

            verify(eventPublisher).emitEdge(
                eq("run-1"),
                eq("mcp:step1->mcp:step2"),
                eq("mcp:step1"),
                eq("mcp:step2"),
                eq(EdgeLifecycle.RUNNING),
                isNull(),
                isNull()
            );
        }

        @Test
        @DisplayName("Should strip #iter- suffix from step IDs")
        void shouldStripIterSuffixFromStepIds() {
            when(execution.getRunId()).thenReturn("run-1");

            service.markEdgeRunning(execution, "core:loop#iter-3", "mcp:step1");

            verify(eventPublisher).emitEdge(
                eq("run-1"),
                eq("core:loop->mcp:step1"),
                eq("core:loop"),
                eq("mcp:step1"),
                eq(EdgeLifecycle.RUNNING),
                isNull(),
                isNull()
            );
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Edge batch accumulation tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Edge batch accumulation")
    class EdgeBatchAccumulationTests {

        @Test
        @DisplayName("Should accumulate multiple COMPLETED on same edge instead of deduplicating")
        void shouldAccumulateMultipleCompletedOnSameEdge() {
            when(execution.getRunId()).thenReturn("run-1");

            service.beginEdgeBatch();

            // Emit 3 COMPLETED events for the same edge (simulates split with 3 items)
            service.markEdgeCompleted(execution, "core:split", "mcp:step1", 0);
            service.markEdgeCompleted(execution, "core:split", "mcp:step1", 1);
            service.markEdgeCompleted(execution, "core:split", "mcp:step1", 2);

            var result = service.flushEdgeBatch("run-1");

            // Should flush with count=3, not deduplicated to 1
            ArgumentCaptor<java.util.Map<String, java.util.Map.Entry<String, Integer>>> captor =
                ArgumentCaptor.forClass(java.util.Map.class);
            verify(stateSnapshotService).recordEdgeStatusesBatch(eq("run-1"), captor.capture());

            var batch = captor.getValue();
            assertEquals(1, batch.size(), "Should have 1 edge key");
            var entry = batch.get("core:split->mcp:step1");
            assertNotNull(entry, "Should have edge core:split->mcp:step1");
            assertEquals("COMPLETED", entry.getKey());
            assertEquals(3, entry.getValue(), "Should accumulate 3 completed");

            // Return value should also reflect count=3
            var returnEntry = result.get("core:split->mcp:step1");
            assertNotNull(returnEntry);
            assertEquals("COMPLETED", returnEntry.getKey());
            assertEquals(3, returnEntry.getValue());
        }

        @Test
        @DisplayName("Should accumulate mixed COMPLETED and SKIPPED on same edge")
        void shouldAccumulateMixedStatusesOnSameEdge() {
            when(execution.getRunId()).thenReturn("run-1");

            service.beginEdgeBatch();

            // 3 completed + 2 skipped on same edge
            service.markEdgeCompleted(execution, "core:approval:approved", "core:wait", 0);
            service.markEdgeCompleted(execution, "core:approval:approved", "core:wait", 1);
            service.markEdgeCompleted(execution, "core:approval:approved", "core:wait", 2);
            service.markEdgeSkipped(execution, "core:approval:approved", "core:wait", 3);
            service.markEdgeSkipped(execution, "core:approval:approved", "core:wait", 4);

            var returnMap = service.flushEdgeBatch("run-1");

            // Return map should contain BOTH entries (completed + skipped with ::SKIPPED suffix)
            assertEquals(2, returnMap.size(), "Return map should have 2 entries for dual-status edge");
            assertTrue(returnMap.containsKey("core:approval:approved->core:wait"), "Should have completed entry");
            assertTrue(returnMap.containsKey("core:approval:approved->core:wait::SKIPPED"), "Should have skipped entry with suffix");
            assertEquals("COMPLETED", returnMap.get("core:approval:approved->core:wait").getKey());
            assertEquals(3, returnMap.get("core:approval:approved->core:wait").getValue());
            assertEquals("SKIPPED", returnMap.get("core:approval:approved->core:wait::SKIPPED").getKey());
            assertEquals(2, returnMap.get("core:approval:approved->core:wait::SKIPPED").getValue());

            // Should produce TWO recordEdgeStatusesBatch calls: one for completed, one for skipped
            ArgumentCaptor<java.util.Map<String, java.util.Map.Entry<String, Integer>>> captor =
                ArgumentCaptor.forClass(java.util.Map.class);
            verify(stateSnapshotService, times(2)).recordEdgeStatusesBatch(eq("run-1"), captor.capture());

            var calls = captor.getAllValues();
            // Find the completed and skipped calls
            java.util.Map.Entry<String, Integer> completedEntry = null;
            java.util.Map.Entry<String, Integer> skippedEntry = null;
            for (var call : calls) {
                var e = call.get("core:approval:approved->core:wait");
                if (e != null) {
                    if ("COMPLETED".equals(e.getKey())) completedEntry = e;
                    else if ("SKIPPED".equals(e.getKey())) skippedEntry = e;
                }
            }

            assertNotNull(completedEntry, "Should have completed batch");
            assertEquals(3, completedEntry.getValue(), "Should have 3 completed");
            assertNotNull(skippedEntry, "Should have skipped batch");
            assertEquals(2, skippedEntry.getValue(), "Should have 2 skipped");
        }

        @Test
        @DisplayName("Should return empty map when no batch is active")
        void shouldReturnEmptyMapWhenNoBatch() {
            var result = service.flushEdgeBatch("run-1");
            assertTrue(result.isEmpty());
            verifyNoInteractions(stateSnapshotService);
        }

        @Test
        @DisplayName("Should write directly to DB when no batch is active")
        void shouldWriteDirectlyWhenNoBatch() {
            when(execution.getRunId()).thenReturn("run-1");

            // No beginEdgeBatch() - immediate mode
            service.markEdgeCompleted(execution, "mcp:step1", "mcp:step2");

            // Should call recordEdgeStatus directly (not batch)
            verify(stateSnapshotService).recordEdgeStatus("run-1", "mcp:step1", "mcp:step2", "COMPLETED");
        }
    }
}
