package com.apimarketplace.orchestrator.services.resume.state;

import com.apimarketplace.orchestrator.domain.execution.StateSnapshot;
import com.apimarketplace.orchestrator.domain.execution.StatusCounts;
import com.apimarketplace.orchestrator.domain.workflow.Edge;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.services.resume.WorkflowRunState;
import com.apimarketplace.orchestrator.services.state.StateSnapshotService;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("EdgeStateBuilder")
class EdgeStateBuilderTest {

    @Mock
    private StateReconstructorHelper helper;

    @Mock
    private StateSnapshotService stateSnapshotService;

    @Mock
    private WorkflowPlan plan;

    private EdgeStateBuilder builder;

    private static final String RUN_ID = "run-abc-123";

    @BeforeEach
    void setUp() {
        builder = new EdgeStateBuilder(helper, stateSnapshotService);
    }

    @Nested
    @DisplayName("buildEdgeStates() - without snapshot")
    class BuildEdgeStatesWithoutSnapshot {

        @Test
        @DisplayName("Builds edge states from plan edges with zero counts when no snapshot data")
        void buildsEdgeStatesFromPlanEdges() {
            Edge edge = new Edge("trigger:start", "mcp:step1");
            when(plan.getEdges()).thenReturn(List.of(edge));
            when(helper.determineEdgeStatus(any(), any(), any(), any(), any()))
                .thenReturn(RunStatus.COMPLETED);

            List<WorkflowRunState.EdgeState> result = builder.buildEdgeStates(
                plan, Set.of("mcp:step1"), Set.of(), Set.of(), new HashMap<>()
            );

            assertEquals(1, result.size());
            WorkflowRunState.EdgeState es = result.get(0);
            assertEquals(RunStatus.COMPLETED, es.status());
            assertEquals(0, es.completedCount(), "no snapshot data → zero counts");
            assertEquals(0, es.skippedCount());
            assertEquals(0, es.totalCount());
        }

        @Test
        @DisplayName("Skips edges with null from or to")
        void skipsEdgesWithNullFromOrTo() {
            Edge edge1 = new Edge(null, "mcp:step1");
            Edge edge2 = new Edge("trigger:start", null);
            when(plan.getEdges()).thenReturn(List.of(edge1, edge2));

            List<WorkflowRunState.EdgeState> result = builder.buildEdgeStates(
                plan, Set.of(), Set.of(), Set.of(), new HashMap<>()
            );

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("Preserves port-qualified edges as distinct entries")
        void preservesPortQualifiedEdges() {
            Edge edge1 = new Edge("core:check:if", "mcp:step1");
            Edge edge2 = new Edge("core:check:else", "mcp:step1");
            when(plan.getEdges()).thenReturn(List.of(edge1, edge2));
            when(helper.determineEdgeStatus(any(), any(), any(), any(), any()))
                .thenReturn(RunStatus.PENDING);

            List<WorkflowRunState.EdgeState> result = builder.buildEdgeStates(
                plan, Set.of(), Set.of(), Set.of(), new HashMap<>()
            );

            assertEquals(2, result.size());
            assertEquals("core:check:if", result.get(0).from());
            assertEquals("core:check:else", result.get(1).from());
        }

        @Test
        @DisplayName("Deduplicates truly duplicate edges without ports")
        void deduplicatesDuplicateEdges() {
            Edge edge1 = new Edge("trigger:start", "mcp:step1");
            Edge edge2 = new Edge("trigger:start", "mcp:step1");
            when(plan.getEdges()).thenReturn(List.of(edge1, edge2));
            when(helper.determineEdgeStatus(any(), any(), any(), any(), any()))
                .thenReturn(RunStatus.PENDING);

            List<WorkflowRunState.EdgeState> result = builder.buildEdgeStates(
                plan, Set.of(), Set.of(), Set.of(), new HashMap<>()
            );

            assertEquals(1, result.size());
        }
    }

    @Nested
    @DisplayName("buildEdgeStates() - multi-trigger fan-in")
    class MultiTriggerFanIn {

        @Test
        @DisplayName("Unfired trigger's edge stays at zero when sibling trigger fires through shared merge")
        void unfiredTriggerEdgeStaysAtZero() {
            // Plan: two triggers fan into a shared merge.
            //   trigger:manuala  ─┐
            //                     ├──► core:sharedmerge
            //   trigger:scheduler ┘
            Edge manualEdge = new Edge("trigger:manuala", "core:sharedmerge");
            Edge schedEdge = new Edge("trigger:scheduler", "core:sharedmerge");
            when(plan.getEdges()).thenReturn(List.of(manualEdge, schedEdge));

            // Snapshot has only the scheduler→merge edge recorded (5 fires).
            // The manual→merge edge is absent because manuala never fired.
            StateSnapshot snapshot = mock(StateSnapshot.class);
            StateSnapshot.EdgeCounts schedCounts = new StateSnapshot.EdgeCounts(0, 5, 0);
            when(snapshot.getEdges()).thenReturn(Map.of("trigger:scheduler->core:sharedmerge", schedCounts));
            when(snapshot.getEdgeCounts("trigger:scheduler", "core:sharedmerge")).thenReturn(schedCounts);
            when(snapshot.getEdgeCounts("trigger:manuala", "core:sharedmerge")).thenReturn(null);

            when(stateSnapshotService.getSnapshot(RUN_ID)).thenReturn(snapshot);

            // Helper returns PENDING for the unfired trigger's edge.
            when(helper.determineEdgeStatus(eq("trigger:manuala"), eq("core:sharedmerge"), any(), any(), any()))
                .thenReturn(RunStatus.PENDING);

            // Target step has accumulated counts from the 5 scheduler fires.
            // CRITICAL: these MUST NOT be attributed to the manuala edge.
            StatusCounts mergeCounts = new StatusCounts();
            for (int i = 0; i < 5; i++) {
                mergeCounts.incrementTotal();
                mergeCounts.incrementCompleted();
            }
            Map<String, StatusCounts> stepStatusCounts = Map.of("core:sharedmerge", mergeCounts);

            List<WorkflowRunState.EdgeState> result = builder.buildEdgeStates(
                RUN_ID, plan, Set.of("core:sharedmerge"), Set.of(), Set.of(), stepStatusCounts
            );

            WorkflowRunState.EdgeState manualEs = result.stream()
                .filter(e -> "trigger:manuala".equals(e.from()))
                .findFirst().orElseThrow();
            assertEquals(0, manualEs.completedCount(),
                "Unfired trigger edge must NOT inherit target merge node's completed count");
            assertEquals(0, manualEs.totalCount());
            assertEquals(RunStatus.PENDING, manualEs.status());

            WorkflowRunState.EdgeState schedEs = result.stream()
                .filter(e -> "trigger:scheduler".equals(e.from()))
                .findFirst().orElseThrow();
            assertEquals(5, schedEs.completedCount());
            assertEquals(5, schedEs.totalCount());
            assertEquals(RunStatus.COMPLETED, schedEs.status());
        }
    }

    @Nested
    @DisplayName("buildEdgeStates() - with snapshot data")
    class BuildEdgeStatesWithSnapshot {

        @Test
        @DisplayName("Uses StateSnapshot edge counts when present")
        void usesSnapshotCountsWhenPresent() {
            Edge edge = new Edge("trigger:start", "mcp:step1");
            when(plan.getEdges()).thenReturn(List.of(edge));

            StateSnapshot snapshot = mock(StateSnapshot.class);
            StateSnapshot.EdgeCounts counts = new StateSnapshot.EdgeCounts(0, 3, 0);
            when(snapshot.getEdges()).thenReturn(Map.of("trigger:start->mcp:step1", counts));
            when(snapshot.getEdgeCounts("trigger:start", "mcp:step1")).thenReturn(counts);
            when(stateSnapshotService.getSnapshot(RUN_ID)).thenReturn(snapshot);

            List<WorkflowRunState.EdgeState> result = builder.buildEdgeStates(
                RUN_ID, plan, Set.of(), Set.of(), Set.of(), new HashMap<>()
            );

            assertEquals(1, result.size());
            WorkflowRunState.EdgeState es = result.get(0);
            assertEquals(3, es.completedCount());
            assertEquals(3, es.totalCount());
            assertEquals(RunStatus.COMPLETED, es.status());
        }

        @Test
        @DisplayName("createEdgeStateFromSnapshot logs at DEBUG, not INFO, on the hot path")
        void createEdgeStateLogsAtDebugNotInfo() {
            // Regression guard for OOM diagnosis 2026-05-06: createEdgeStateFromSnapshot used
            // to log "[createEdgeStateFromSnapshot] Edge ... snapshotEdges=..." at INFO on
            // every call (1292×/20min observed in prod), each materialising
            // snapshot.getEdges().keySet() - one of the top Jackson allocators in the heap
            // histogram. Demoting to DEBUG (with isDebugEnabled() guard around the
            // keySet() call) cuts both the log volume and the eager allocation.
            Logger edgeLogger = (Logger) LoggerFactory.getLogger(EdgeStateBuilder.class);
            Level previous = edgeLogger.getLevel();
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.start();
            edgeLogger.addAppender(appender);
            edgeLogger.setLevel(Level.INFO); // INFO threshold: DEBUG must NOT pass through
            try {
                Edge edge = new Edge("trigger:start", "mcp:step1");
                when(plan.getEdges()).thenReturn(List.of(edge));

                StateSnapshot snapshot = mock(StateSnapshot.class);
                StateSnapshot.EdgeCounts counts = new StateSnapshot.EdgeCounts(0, 3, 0);
                when(snapshot.getEdges()).thenReturn(Map.of("trigger:start->mcp:step1", counts));
                when(snapshot.getEdgeCounts("trigger:start", "mcp:step1")).thenReturn(counts);
                when(stateSnapshotService.getSnapshot(RUN_ID)).thenReturn(snapshot);

                builder.buildEdgeStates(
                    RUN_ID, plan, Set.of(), Set.of(), Set.of(), new HashMap<>()
                );

                boolean spam = appender.list.stream().anyMatch(e ->
                    e.getLevel() == Level.INFO
                    && e.getFormattedMessage().contains("[createEdgeStateFromSnapshot]"));
                assertFalse(spam,
                    "createEdgeStateFromSnapshot must not log at INFO (hot path, OOM 2026-05-06)");
            } finally {
                edgeLogger.detachAppender(appender);
                edgeLogger.setLevel(previous);
            }
        }
    }
}
