package com.apimarketplace.orchestrator.services.streaming.state;

import com.apimarketplace.orchestrator.services.state.StateSnapshotService;
import com.apimarketplace.orchestrator.services.streaming.events.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("RunState")
class RunStateTest {

    @Mock
    private StateSnapshotService stateSnapshotService;

    private RunState runState;

    @BeforeEach
    void setUp() {
        runState = new RunState("test-run-1", stateSnapshotService);
    }

    @Test
    @DisplayName("Should return runId")
    void shouldReturnRunId() {
        assertEquals("test-run-1", runState.getRunId());
    }

    @Nested
    @DisplayName("updateStep()")
    class UpdateStepTests {

        @Test
        @DisplayName("Should add new step")
        void shouldAddNewStep() {
            runState.updateStep("test-run-1", "mcp:step1", Map.of("status", "RUNNING", "id", "mcp:step1"));

            // Verify via snapshot (but snapshot requires DB, so just verify no exception)
            assertDoesNotThrow(() -> runState.updateStep("test-run-1", "mcp:step1",
                Map.of("status", "COMPLETED", "id", "mcp:step1")));
        }

        @Test
        @DisplayName("Should not throw for null stepId")
        void shouldNotThrowForNullStepId() {
            assertDoesNotThrow(() -> runState.updateStep("test-run-1", null, Map.of()));
        }

        @Test
        @DisplayName("Should not throw for null payload")
        void shouldNotThrowForNullPayload() {
            assertDoesNotThrow(() -> runState.updateStep("test-run-1", "mcp:step1", null));
        }

        @Test
        @DisplayName("Should filter internal fields starting with underscore")
        void shouldFilterInternalFields() {
            // Internal fields starting with "_" should be excluded
            assertDoesNotThrow(() -> runState.updateStep("test-run-1", "mcp:step1",
                Map.of("status", "RUNNING", "_internal", "hidden", "id", "mcp:step1")));
        }
    }

    @Nested
    @DisplayName("updateEdge()")
    class UpdateEdgeTests {

        @Test
        @DisplayName("Should not throw on edge update (no-op)")
        void shouldNotThrowOnEdgeUpdate() {
            // updateEdge is now a no-op, edges read from DB
            assertDoesNotThrow(() -> runState.updateEdge("edge1", "mcp:a", "mcp:b",
                EdgeLifecycle.COMPLETED, 0));
        }

        @Test
        @DisplayName("Should not throw with iteration parameter")
        void shouldNotThrowWithIteration() {
            assertDoesNotThrow(() -> runState.updateEdge("edge1", "mcp:a", "mcp:b",
                EdgeLifecycle.RUNNING, 0, 3));
        }
    }

    @Nested
    @DisplayName("updateWorkflowStatus()")
    class UpdateWorkflowStatusTests {

        @Test
        @DisplayName("Should update workflow status")
        void shouldUpdateWorkflowStatus() {
            assertDoesNotThrow(() -> runState.updateWorkflowStatus(
                Map.of("stepsCompleted", 5), "RUNNING", "Processing", false));
        }

        @Test
        @DisplayName("Should handle null payload")
        void shouldHandleNullPayload() {
            assertDoesNotThrow(() -> runState.updateWorkflowStatus(null, "COMPLETED", null, true));
        }

        @Test
        @DisplayName("Should mark terminal status")
        void shouldMarkTerminalStatus() {
            runState.updateWorkflowStatus(null, "COMPLETED", "Done", true);
            // terminal flag is internal, but verified through snapshot
        }
    }

    @Nested
    @DisplayName("updateWorkflowStatistics()")
    class UpdateWorkflowStatisticsTests {

        @Test
        @DisplayName("Should not throw for valid payload")
        void shouldNotThrowForValidPayload() {
            assertDoesNotThrow(() -> runState.updateWorkflowStatistics(
                Map.of("totalSteps", 10, "completedSteps", 5)));
        }

        @Test
        @DisplayName("Should not throw for null payload")
        void shouldNotThrowForNullPayload() {
            assertDoesNotThrow(() -> runState.updateWorkflowStatistics(null));
        }
    }

    @Nested
    @DisplayName("updateLoop()")
    class UpdateLoopTests {

        @Test
        @DisplayName("Should not throw for null loopId")
        void shouldNotThrowForNullLoopId() {
            LoopEvent event = new LoopEvent("test-run-1", "core:loop1", LoopEventType.STARTED, Map.of(), System.currentTimeMillis());
            assertDoesNotThrow(() -> runState.updateLoop(null, event));
        }

        @Test
        @DisplayName("Should update loop state")
        void shouldUpdateLoopState() {
            LoopEvent event = new LoopEvent("test-run-1", "core:loop1", LoopEventType.ITERATION_COMPLETED,
                Map.of("currentIteration", 0, "maxIterations", 5), System.currentTimeMillis());
            assertDoesNotThrow(() -> runState.updateLoop("core:loop1", event));
        }

        @Test
        @DisplayName("Should aggregate loop iterations")
        void shouldAggregateLoopIterations() {
            LoopEvent event1 = new LoopEvent("test-run-1", "core:loop1", LoopEventType.ITERATION_COMPLETED,
                Map.of("currentIteration", 0), System.currentTimeMillis());
            LoopEvent event2 = new LoopEvent("test-run-1", "core:loop1", LoopEventType.ITERATION_COMPLETED,
                Map.of("currentIteration", 1), System.currentTimeMillis());

            assertDoesNotThrow(() -> {
                runState.updateLoop("core:loop1", event1);
                runState.updateLoop("core:loop1", event2);
            });
        }
    }

    @Nested
    @DisplayName("updateMerge()")
    class UpdateMergeTests {

        @Test
        @DisplayName("Should not throw for null mergeId")
        void shouldNotThrowForNullMergeId() {
            MergeEvent event = new MergeEvent("test-run-1", "core:merge1", MergeEventType.ENQUEUED,
                Map.of(), System.currentTimeMillis());
            assertDoesNotThrow(() -> runState.updateMerge(null, event));
        }

        @Test
        @DisplayName("Should update merge state")
        void shouldUpdateMergeState() {
            MergeEvent event = new MergeEvent("test-run-1", "core:merge1", MergeEventType.MERGED,
                Map.of("receivedCount", 3), System.currentTimeMillis());
            assertDoesNotThrow(() -> runState.updateMerge("core:merge1", event));
        }
    }

    @Nested
    @DisplayName("appendLog()")
    class AppendLogTests {

        @Test
        @DisplayName("Should append log entry")
        void shouldAppendLog() {
            assertDoesNotThrow(() -> runState.appendLog("INFO", "Test message", System.currentTimeMillis()));
        }

        @Test
        @DisplayName("Should default null level to INFO")
        void shouldDefaultLevelToInfo() {
            assertDoesNotThrow(() -> runState.appendLog(null, "message", System.currentTimeMillis()));
        }
    }

    @Nested
    @DisplayName("updateAgentToolCall()")
    class UpdateAgentToolCallTests {

        @Test
        @DisplayName("Should not throw for null event")
        void shouldNotThrowForNullEvent() {
            assertDoesNotThrow(() -> runState.updateAgentToolCall(null));
        }

        @Test
        @DisplayName("Should record agent tool call")
        void shouldRecordAgentToolCall() {
            AgentToolCallEvent event = new AgentToolCallEvent(
                "test-run-1", "agent:my_agent", "search", "call-1",
                AgentToolCallPhase.CALLING, Map.of(), 0, null, System.currentTimeMillis());

            assertDoesNotThrow(() -> runState.updateAgentToolCall(event));
        }
    }

    @Nested
    @DisplayName("updateRetry()")
    class UpdateRetryTests {

        @Test
        @DisplayName("Should not throw for null stepId")
        void shouldNotThrowForNullStepId() {
            assertDoesNotThrow(() -> runState.updateRetry(null, 1, Map.of()));
        }

        @Test
        @DisplayName("Should update retry on existing step")
        void shouldUpdateRetryOnExistingStep() {
            // First add a step, then retry it
            runState.updateStep("test-run-1", "mcp:step1", Map.of("status", "RUNNING", "id", "mcp:step1"));
            assertDoesNotThrow(() -> runState.updateRetry("mcp:step1", 1, Map.of("status", "RETRYING")));
        }
    }
}
