package com.apimarketplace.orchestrator.services.resume;

import com.apimarketplace.orchestrator.domain.workflow.ExecutionMode;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("WorkflowRunState")
class WorkflowRunStateTest {

    private static final Instant NOW = Instant.now();

    @Nested
    @DisplayName("Full constructor")
    class FullConstructorTests {

        @Test
        @DisplayName("Should create with all fields")
        void shouldCreateWithAllFields() {
            WorkflowRunState state = new WorkflowRunState(
                "run-1", "wf-1", RunStatus.RUNNING, ExecutionMode.STEP_BY_STEP,
                NOW, null, Map.of("key", "val"),
                List.of(), List.of(),
                Set.of("mcp:step1"),
                Set.of("trigger:start"),
                Set.of(),
                Set.of(),
                Set.of(),
                Map.of(),
                List.of()
            );

            assertEquals("run-1", state.runId());
            assertEquals("wf-1", state.workflowId());
            assertEquals(RunStatus.RUNNING, state.status());
            assertEquals(ExecutionMode.STEP_BY_STEP, state.executionMode());
            assertEquals(NOW, state.startedAt());
            assertNull(state.pausedAt());
            assertNotNull(state.plan());
            assertTrue(state.readySteps().contains("mcp:step1"));
            assertTrue(state.completedStepIds().contains("trigger:start"));
            assertTrue(state.interfaces().isEmpty());
        }
    }

    @Nested
    @DisplayName("Backwards-compatible constructor (without interfaces)")
    class BackwardsCompatibleConstructorTests {

        @Test
        @DisplayName("Should create with default empty runningStepIds and interfaces")
        void shouldCreateWithDefaults() {
            WorkflowRunState state = new WorkflowRunState(
                "run-1", "wf-1", RunStatus.PAUSED, ExecutionMode.AUTOMATIC,
                NOW, NOW, Map.of(),
                List.of(), List.of(),
                Set.of(),
                Set.of("mcp:step1"),
                Set.of(),
                Set.of(),
                Map.of()
            );

            assertEquals(RunStatus.PAUSED, state.status());
            assertTrue(state.runningStepIds().isEmpty());
            assertTrue(state.interfaces().isEmpty());
        }
    }

    @Nested
    @DisplayName("canExecuteStep()")
    class CanExecuteStepTests {

        @Test
        @DisplayName("Should return true for step in readySteps")
        void shouldReturnTrueForReadyStep() {
            WorkflowRunState state = createStateWithReadySteps(Set.of("mcp:step1", "mcp:step2"));

            assertTrue(state.canExecuteStep("mcp:step1"));
            assertTrue(state.canExecuteStep("mcp:step2"));
        }

        @Test
        @DisplayName("Should return false for step not in readySteps")
        void shouldReturnFalseForNonReadyStep() {
            WorkflowRunState state = createStateWithReadySteps(Set.of("mcp:step1"));

            assertFalse(state.canExecuteStep("mcp:step2"));
        }

        @Test
        @DisplayName("Should return false when readySteps is null")
        void shouldReturnFalseWhenNull() {
            WorkflowRunState state = new WorkflowRunState(
                "run-1", "wf-1", RunStatus.RUNNING, ExecutionMode.AUTOMATIC,
                NOW, null, Map.of(),
                List.of(), List.of(),
                null,
                Set.of(), Set.of(), Set.of(), Set.of(),
                Map.of(), List.of()
            );

            assertFalse(state.canExecuteStep("mcp:step1"));
        }
    }

    @Nested
    @DisplayName("getStepState()")
    class GetStepStateTests {

        @Test
        @DisplayName("Should find step by stepId")
        void shouldFindByStepId() {
            WorkflowRunState.StepState step = createStepState("mcp:step1", "My Step", RunStatus.COMPLETED);
            WorkflowRunState state = createStateWithSteps(List.of(step));

            WorkflowRunState.StepState found = state.getStepState("mcp:step1");

            assertNotNull(found);
            assertEquals("mcp:step1", found.stepId());
        }

        @Test
        @DisplayName("Should find step by stepAlias")
        void shouldFindByStepAlias() {
            WorkflowRunState.StepState step = createStepState("mcp:step1", "My Step", RunStatus.COMPLETED);
            WorkflowRunState state = createStateWithSteps(List.of(step));

            WorkflowRunState.StepState found = state.getStepState("My Step");

            assertNotNull(found);
            assertEquals("My Step", found.stepAlias());
        }

        @Test
        @DisplayName("Should return null for non-existing step")
        void shouldReturnNullForMissing() {
            WorkflowRunState state = createStateWithSteps(List.of());

            assertNull(state.getStepState("mcp:missing"));
        }

        @Test
        @DisplayName("Should return null when steps list is null")
        void shouldReturnNullWhenStepsNull() {
            WorkflowRunState state = new WorkflowRunState(
                "run-1", "wf-1", RunStatus.RUNNING, ExecutionMode.AUTOMATIC,
                NOW, null, Map.of(),
                null, List.of(),
                Set.of(), Set.of(), Set.of(), Set.of(), Set.of(),
                Map.of(), List.of()
            );

            assertNull(state.getStepState("mcp:step1"));
        }
    }

    @Nested
    @DisplayName("canResume()")
    class CanResumeTests {

        @Test
        @DisplayName("Should return true when PAUSED")
        void shouldReturnTrueWhenPaused() {
            WorkflowRunState state = createStateWithStatus(RunStatus.PAUSED);
            assertTrue(state.canResume());
        }

        @Test
        @DisplayName("Should return false when RUNNING")
        void shouldReturnFalseWhenRunning() {
            WorkflowRunState state = createStateWithStatus(RunStatus.RUNNING);
            assertFalse(state.canResume());
        }

        @Test
        @DisplayName("Should return false when COMPLETED")
        void shouldReturnFalseWhenCompleted() {
            WorkflowRunState state = createStateWithStatus(RunStatus.COMPLETED);
            assertFalse(state.canResume());
        }
    }

    @Nested
    @DisplayName("isRunning()")
    class IsRunningTests {

        @Test
        @DisplayName("Should return true when RUNNING")
        void shouldReturnTrueWhenRunning() {
            WorkflowRunState state = createStateWithStatus(RunStatus.RUNNING);
            assertTrue(state.isRunning());
        }

        @Test
        @DisplayName("Should return false when PAUSED")
        void shouldReturnFalseWhenPaused() {
            WorkflowRunState state = createStateWithStatus(RunStatus.PAUSED);
            assertFalse(state.isRunning());
        }
    }

    @Nested
    @DisplayName("isTerminal()")
    class IsTerminalTests {

        @Test
        @DisplayName("Should return true for COMPLETED")
        void shouldReturnTrueForCompleted() {
            WorkflowRunState state = createStateWithStatus(RunStatus.COMPLETED);
            assertTrue(state.isTerminal());
        }

        @Test
        @DisplayName("Should return true for FAILED")
        void shouldReturnTrueForFailed() {
            WorkflowRunState state = createStateWithStatus(RunStatus.FAILED);
            assertTrue(state.isTerminal());
        }

        @Test
        @DisplayName("Should return false for RUNNING")
        void shouldReturnFalseForRunning() {
            WorkflowRunState state = createStateWithStatus(RunStatus.RUNNING);
            assertFalse(state.isTerminal());
        }

        @Test
        @DisplayName("Should return false for null status")
        void shouldReturnFalseForNullStatus() {
            WorkflowRunState state = createStateWithStatus(null);
            assertFalse(state.isTerminal());
        }
    }

    @Nested
    @DisplayName("StepState record")
    class StepStateTests {

        @Test
        @DisplayName("Should create with all fields")
        void shouldCreateWithAllFields() {
            WorkflowRunState.StepState step = new WorkflowRunState.StepState(
                "mcp:step1", "My Step", "tool-123",
                RunStatus.COMPLETED,
                Map.of("input", "val"), Map.of("output", "result"),
                0, 1, 200, null,
                NOW, NOW.plusMillis(500),
                500L, Set.of("trigger:start"), true,
                Map.of("success", 1)
            );

            assertEquals("mcp:step1", step.stepId());
            assertEquals("My Step", step.stepAlias());
            assertEquals("tool-123", step.toolId());
            assertEquals(RunStatus.COMPLETED, step.status());
            assertEquals(200, step.httpStatus());
            assertEquals(500L, step.executionTimeMs());
            assertTrue(step.canExecute());
            assertNotNull(step.statusCounts());
        }

        @Test
        @DisplayName("Should create with backwards-compatible constructor (without statusCounts)")
        void shouldCreateWithoutStatusCounts() {
            WorkflowRunState.StepState step = new WorkflowRunState.StepState(
                "mcp:step1", "My Step", "tool-123",
                RunStatus.COMPLETED,
                null, null, null, null, null, null,
                NOW, NOW.plusMillis(100),
                100L, Set.of(), false
            );

            assertNull(step.statusCounts());
        }
    }

    @Nested
    @DisplayName("EdgeState record")
    class EdgeStateTests {

        @Test
        @DisplayName("Should create with all fields")
        void shouldCreateWithAllFields() {
            WorkflowRunState.EdgeState edge = new WorkflowRunState.EdgeState(
                "trigger:start", "mcp:step1",
                RunStatus.COMPLETED,
                5, 2, 10
            );

            assertEquals("trigger:start", edge.from());
            assertEquals("mcp:step1", edge.to());
            assertEquals(RunStatus.COMPLETED, edge.status());
            assertEquals(5, edge.completedCount());
            assertEquals(2, edge.skippedCount());
            assertEquals(10, edge.totalCount());
        }

        @Test
        @DisplayName("Should create with backwards-compatible constructor (without skippedCount)")
        void shouldCreateWithoutSkippedCount() {
            WorkflowRunState.EdgeState edge = new WorkflowRunState.EdgeState(
                "trigger:start", "mcp:step1",
                RunStatus.COMPLETED,
                3, 5
            );

            assertEquals(0, edge.skippedCount());
            assertEquals(3, edge.completedCount());
            assertEquals(5, edge.totalCount());
        }
    }

    // ---- Helpers ----

    private WorkflowRunState createStateWithReadySteps(Set<String> readySteps) {
        return new WorkflowRunState(
            "run-1", "wf-1", RunStatus.RUNNING, ExecutionMode.AUTOMATIC,
            NOW, null, Map.of(),
            List.of(), List.of(),
            readySteps, Set.of(), Set.of(), Set.of(), Set.of(),
            Map.of(), List.of()
        );
    }

    private WorkflowRunState createStateWithSteps(List<WorkflowRunState.StepState> steps) {
        return new WorkflowRunState(
            "run-1", "wf-1", RunStatus.RUNNING, ExecutionMode.AUTOMATIC,
            NOW, null, Map.of(),
            steps, List.of(),
            Set.of(), Set.of(), Set.of(), Set.of(), Set.of(),
            Map.of(), List.of()
        );
    }

    private WorkflowRunState createStateWithStatus(RunStatus status) {
        return new WorkflowRunState(
            "run-1", "wf-1", status, ExecutionMode.AUTOMATIC,
            NOW, null, Map.of(),
            List.of(), List.of(),
            Set.of(), Set.of(), Set.of(), Set.of(), Set.of(),
            Map.of(), List.of()
        );
    }

    private WorkflowRunState.StepState createStepState(String stepId, String alias, RunStatus status) {
        return new WorkflowRunState.StepState(
            stepId, alias, "tool-1", status,
            null, null, null, null, null, null,
            NOW, NOW, 0L, Set.of(), false
        );
    }
}
