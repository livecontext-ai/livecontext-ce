package com.apimarketplace.orchestrator.services.resume.state;

import com.apimarketplace.orchestrator.domain.WorkflowStepDataEntity;
import com.apimarketplace.orchestrator.domain.execution.StateSnapshot;
import com.apimarketplace.orchestrator.domain.execution.StatusCounts;
import com.apimarketplace.orchestrator.domain.workflow.*;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.services.resume.WorkflowRunState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for StepStateBuilder.
 * Tests step state building operations for state reconstruction.
 */
@ExtendWith(MockitoExtension.class)
class StepStateBuilderTest {

    @Mock
    private StateReconstructorHelper helper;

    @Mock
    private StatusCountsBuilder statusCountsBuilder;

    private StepStateBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new StepStateBuilder(helper, statusCountsBuilder);
    }

    // ========================================================================
    // buildStepStates() Tests - Step Nodes
    // ========================================================================

    @Nested
    @DisplayName("buildStepStates() - Step Nodes")
    class BuildStepStatesStepNodesTests {

        @Test
        @DisplayName("Should build state for step with no entities (pending)")
        void shouldBuildStateForStepWithNoEntities() {
            // Given
            Step step = createStep("step-1", "API Call");
            WorkflowPlan plan = createPlanWithSteps(List.of(step));
            ExecutionGraph graph = createMockExecutionGraph();

            Map<String, List<WorkflowStepDataEntity>> stepsByAlias = new HashMap<>();
            Set<String> completedStepIds = new HashSet<>();
            Set<String> failedStepIds = new HashSet<>();
            Set<String> skippedStepIds = new HashSet<>();
            Set<String> readySteps = new HashSet<>();
            Map<String, StatusCounts> stepStatusCounts = new HashMap<>();

            when(helper.determineStepStatus(anyString(), any(), any(), any(), any()))
                    .thenReturn(RunStatus.PENDING);
            when(statusCountsBuilder.getStatusCountsMap(anyString(), anyString(), any()))
                    .thenReturn(null);

            // When
            List<WorkflowRunState.StepState> states = builder.buildStepStates(
                    plan, graph, stepsByAlias, completedStepIds, failedStepIds,
                    skippedStepIds, readySteps, stepStatusCounts
            );

            // Then
            assertEquals(1, states.size());
            WorkflowRunState.StepState state = states.get(0);
            assertEquals("mcp:api_call", state.stepId());
            assertEquals("api_call", state.stepAlias());
            assertEquals(RunStatus.PENDING, state.status());
            assertNull(state.inputData());
            assertNull(state.output());
            assertFalse(state.canExecute());
        }

        @Test
        @DisplayName("Should build state for step with completed entity")
        void shouldBuildStateForStepWithCompletedEntity() {
            // Given
            Step step = createStep("step-1", "API Call");
            WorkflowPlan plan = createPlanWithSteps(List.of(step));
            ExecutionGraph graph = createMockExecutionGraph();

            WorkflowStepDataEntity entity = createStepEntity("api_call", "completed");
            entity.setInputData(Map.of("url", "https://api.example.com"));
            entity.setHttpStatus(200);
            entity.setStartTime(Instant.now().minusSeconds(10));
            entity.setEndTime(Instant.now());

            Map<String, List<WorkflowStepDataEntity>> stepsByAlias = Map.of(
                    "api_call", List.of(entity)
            );
            Set<String> completedStepIds = new HashSet<>(Set.of("mcp:api_call"));
            Set<String> failedStepIds = new HashSet<>();
            Set<String> skippedStepIds = new HashSet<>();
            Set<String> readySteps = new HashSet<>();
            Map<String, StatusCounts> stepStatusCounts = new HashMap<>();

            when(helper.determineStepStatus(anyString(), any(), any(), any(), any()))
                    .thenReturn(RunStatus.COMPLETED);
            when(helper.loadStepOutput(entity))
                    .thenReturn(Map.of("output", Map.of("data", "result")));
            when(helper.calculateExecutionTime(entity)).thenReturn(10000L);
            when(statusCountsBuilder.getStatusCountsMap(anyString(), anyString(), any()))
                    .thenReturn(Map.of("completed", 1, "total", 1));

            // When
            List<WorkflowRunState.StepState> states = builder.buildStepStates(
                    plan, graph, stepsByAlias, completedStepIds, failedStepIds,
                    skippedStepIds, readySteps, stepStatusCounts
            );

            // Then
            assertEquals(1, states.size());
            WorkflowRunState.StepState state = states.get(0);
            assertEquals(RunStatus.COMPLETED, state.status());
            assertNotNull(state.inputData());
            assertNotNull(state.output());
            assertEquals(200, state.httpStatus());
            assertEquals(10000L, state.executionTimeMs());
        }

        @Test
        @DisplayName("Should mark step as canExecute when in readySteps")
        void shouldMarkStepAsCanExecuteWhenInReadySteps() {
            // Given
            Step step = createStep("step-1", "API Call");
            WorkflowPlan plan = createPlanWithSteps(List.of(step));
            ExecutionGraph graph = createMockExecutionGraph();

            Map<String, List<WorkflowStepDataEntity>> stepsByAlias = new HashMap<>();
            Set<String> completedStepIds = new HashSet<>();
            Set<String> failedStepIds = new HashSet<>();
            Set<String> skippedStepIds = new HashSet<>();
            Set<String> readySteps = new HashSet<>(Set.of("mcp:api_call"));
            Map<String, StatusCounts> stepStatusCounts = new HashMap<>();

            when(helper.determineStepStatus(anyString(), any(), any(), any(), any()))
                    .thenReturn(RunStatus.PENDING);

            // When
            List<WorkflowRunState.StepState> states = builder.buildStepStates(
                    plan, graph, stepsByAlias, completedStepIds, failedStepIds,
                    skippedStepIds, readySteps, stepStatusCounts
            );

            // Then
            assertEquals(1, states.size());
            assertTrue(states.get(0).canExecute());
        }

        @Test
        @DisplayName("Should use last entity when multiple entities exist")
        void shouldUseLastEntityWhenMultipleExist() {
            // Given
            Step step = createStep("step-1", "API Call");
            WorkflowPlan plan = createPlanWithSteps(List.of(step));
            ExecutionGraph graph = createMockExecutionGraph();

            WorkflowStepDataEntity entity1 = createStepEntity("api_call", "running");
            entity1.setIteration(0);
            WorkflowStepDataEntity entity2 = createStepEntity("api_call", "completed");
            entity2.setIteration(1);

            Map<String, List<WorkflowStepDataEntity>> stepsByAlias = Map.of(
                    "api_call", List.of(entity1, entity2)
            );
            Set<String> completedStepIds = new HashSet<>(Set.of("mcp:api_call"));
            Set<String> failedStepIds = new HashSet<>();
            Set<String> skippedStepIds = new HashSet<>();
            Set<String> readySteps = new HashSet<>();
            Map<String, StatusCounts> stepStatusCounts = new HashMap<>();

            when(helper.determineStepStatus(anyString(), any(), any(), any(), any()))
                    .thenReturn(RunStatus.COMPLETED);
            when(helper.loadStepOutput(entity2)).thenReturn(Map.of());
            when(helper.calculateExecutionTime(entity2)).thenReturn(0L);

            // When
            List<WorkflowRunState.StepState> states = builder.buildStepStates(
                    plan, graph, stepsByAlias, completedStepIds, failedStepIds,
                    skippedStepIds, readySteps, stepStatusCounts
            );

            // Then
            assertEquals(1, states.size());
            assertEquals(RunStatus.COMPLETED, states.get(0).status());
        }
    }

    // ========================================================================
    // buildStepStates() Tests - Trigger Nodes
    // ========================================================================

    @Nested
    @DisplayName("buildStepStates() - Trigger Nodes")
    class BuildStepStatesTriggerNodesTests {

        @Test
        @DisplayName("Should build state for trigger with no entities")
        void shouldBuildStateForTriggerWithNoEntities() {
            // Given
            Trigger trigger = createTrigger("trigger-1", "Start", "webhook");
            WorkflowPlan plan = createPlanWithTriggers(List.of(trigger));
            ExecutionGraph graph = createMockExecutionGraph();

            Map<String, List<WorkflowStepDataEntity>> stepsByAlias = new HashMap<>();
            Set<String> completedStepIds = new HashSet<>();
            Set<String> failedStepIds = new HashSet<>();
            Set<String> skippedStepIds = new HashSet<>();
            Set<String> readySteps = new HashSet<>();
            Map<String, StatusCounts> stepStatusCounts = new HashMap<>();

            when(helper.determineStepStatus(anyString(), any(), any(), any(), any()))
                    .thenReturn(RunStatus.PENDING);

            // When
            List<WorkflowRunState.StepState> states = builder.buildStepStates(
                    plan, graph, stepsByAlias, completedStepIds, failedStepIds,
                    skippedStepIds, readySteps, stepStatusCounts
            );

            // Then
            assertEquals(1, states.size());
            WorkflowRunState.StepState state = states.get(0);
            assertEquals("trigger:start", state.stepId());
            assertEquals("start", state.stepAlias());
            assertTrue(state.dependencies().isEmpty()); // Triggers have no dependencies
        }

        @Test
        @DisplayName("Should build state for trigger with completed entity")
        void shouldBuildStateForTriggerWithCompletedEntity() {
            // Given
            Trigger trigger = createTrigger("trigger-1", "Start", "webhook");
            WorkflowPlan plan = createPlanWithTriggers(List.of(trigger));
            ExecutionGraph graph = createMockExecutionGraph();

            WorkflowStepDataEntity entity = createStepEntity("start", "completed");
            entity.setInputData(Map.of("payload", Map.of("key", "value")));

            Map<String, List<WorkflowStepDataEntity>> stepsByAlias = Map.of(
                    "start", List.of(entity)
            );
            Set<String> completedStepIds = new HashSet<>(Set.of("trigger:start"));
            Set<String> failedStepIds = new HashSet<>();
            Set<String> skippedStepIds = new HashSet<>();
            Set<String> readySteps = new HashSet<>();
            Map<String, StatusCounts> stepStatusCounts = new HashMap<>();

            when(helper.determineStepStatus(anyString(), any(), any(), any(), any()))
                    .thenReturn(RunStatus.COMPLETED);
            when(helper.loadStepOutput(entity)).thenReturn(Map.of("output", Map.of()));
            when(helper.calculateExecutionTime(entity)).thenReturn(100L);

            // When
            List<WorkflowRunState.StepState> states = builder.buildStepStates(
                    plan, graph, stepsByAlias, completedStepIds, failedStepIds,
                    skippedStepIds, readySteps, stepStatusCounts
            );

            // Then
            assertEquals(1, states.size());
            assertEquals(RunStatus.COMPLETED, states.get(0).status());
            assertNotNull(states.get(0).inputData());
        }
    }

    // ========================================================================
    // buildStepStates() Tests - Agent Nodes
    // ========================================================================

    @Nested
    @DisplayName("buildStepStates() - Agent Nodes")
    class BuildStepStatesAgentNodesTests {

        @Test
        @DisplayName("Should build state for agent with no entities")
        void shouldBuildStateForAgentWithNoEntities() {
            // Given
            Agent agent = createAgent("agent-1", "Assistant");
            WorkflowPlan plan = createPlanWithAgents(List.of(agent));
            ExecutionGraph graph = createMockExecutionGraph();

            Map<String, List<WorkflowStepDataEntity>> stepsByAlias = new HashMap<>();
            Set<String> completedStepIds = new HashSet<>();
            Set<String> failedStepIds = new HashSet<>();
            Set<String> skippedStepIds = new HashSet<>();
            Set<String> readySteps = new HashSet<>();
            Map<String, StatusCounts> stepStatusCounts = new HashMap<>();

            when(helper.determineStepStatus(anyString(), any(), any(), any(), any()))
                    .thenReturn(RunStatus.PENDING);

            // When
            List<WorkflowRunState.StepState> states = builder.buildStepStates(
                    plan, graph, stepsByAlias, completedStepIds, failedStepIds,
                    skippedStepIds, readySteps, stepStatusCounts
            );

            // Then
            assertEquals(1, states.size());
            assertEquals("agent:assistant", states.get(0).stepId());
        }

        @Test
        @DisplayName("Should build state for agent with completed entity")
        void shouldBuildStateForAgentWithCompletedEntity() {
            // Given
            Agent agent = createAgent("agent-1", "Assistant");
            WorkflowPlan plan = createPlanWithAgents(List.of(agent));
            ExecutionGraph graph = createMockExecutionGraph();

            WorkflowStepDataEntity entity = createStepEntity("assistant", "completed");

            Map<String, List<WorkflowStepDataEntity>> stepsByAlias = Map.of(
                    "assistant", List.of(entity)
            );
            Set<String> completedStepIds = new HashSet<>(Set.of("agent:assistant"));
            Set<String> failedStepIds = new HashSet<>();
            Set<String> skippedStepIds = new HashSet<>();
            Set<String> readySteps = new HashSet<>();
            Map<String, StatusCounts> stepStatusCounts = new HashMap<>();

            when(helper.determineStepStatus(anyString(), any(), any(), any(), any()))
                    .thenReturn(RunStatus.COMPLETED);
            when(helper.loadStepOutput(entity)).thenReturn(Map.of());
            when(helper.calculateExecutionTime(entity)).thenReturn(0L);

            // When
            List<WorkflowRunState.StepState> states = builder.buildStepStates(
                    plan, graph, stepsByAlias, completedStepIds, failedStepIds,
                    skippedStepIds, readySteps, stepStatusCounts
            );

            // Then
            assertEquals(1, states.size());
            assertEquals(RunStatus.COMPLETED, states.get(0).status());
        }
    }

    // ========================================================================
    // buildStepStates() Tests - Core Nodes
    // ========================================================================

    @Nested
    @DisplayName("buildStepStates() - Core Nodes")
    class BuildStepStatesCoreNodesTests {

        @Test
        @DisplayName("Should build state for loop core node")
        void shouldBuildStateForLoopCoreNode() {
            // Given
            Core loopCore = createCore("loop", "While Loop", "loop-1");
            WorkflowPlan plan = createPlanWithCores(List.of(loopCore));
            ExecutionGraph graph = createMockExecutionGraph();

            Map<String, List<WorkflowStepDataEntity>> stepsByAlias = new HashMap<>();
            Set<String> completedStepIds = new HashSet<>();
            Set<String> failedStepIds = new HashSet<>();
            Set<String> skippedStepIds = new HashSet<>();
            Set<String> readySteps = new HashSet<>();
            Map<String, StatusCounts> stepStatusCounts = new HashMap<>();

            when(helper.determineStepStatus(anyString(), any(), any(), any(), any()))
                    .thenReturn(RunStatus.PENDING);

            // When
            List<WorkflowRunState.StepState> states = builder.buildStepStates(
                    plan, graph, stepsByAlias, completedStepIds, failedStepIds,
                    skippedStepIds, readySteps, stepStatusCounts
            );

            // Then
            assertEquals(1, states.size());
            assertEquals("core:while_loop", states.get(0).stepId());
        }

        @Test
        @DisplayName("Should build state for decision core node")
        void shouldBuildStateForDecisionCoreNode() {
            // Given
            Core decisionCore = createCore("decision", "Check Status", "decision-1");
            WorkflowPlan plan = createPlanWithCores(List.of(decisionCore));
            ExecutionGraph graph = createMockExecutionGraph();

            Map<String, List<WorkflowStepDataEntity>> stepsByAlias = new HashMap<>();
            Set<String> completedStepIds = new HashSet<>();
            Set<String> failedStepIds = new HashSet<>();
            Set<String> skippedStepIds = new HashSet<>();
            Set<String> readySteps = new HashSet<>();
            Map<String, StatusCounts> stepStatusCounts = new HashMap<>();

            when(helper.determineStepStatus(anyString(), any(), any(), any(), any()))
                    .thenReturn(RunStatus.PENDING);

            // When
            List<WorkflowRunState.StepState> states = builder.buildStepStates(
                    plan, graph, stepsByAlias, completedStepIds, failedStepIds,
                    skippedStepIds, readySteps, stepStatusCounts
            );

            // Then
            assertEquals(1, states.size());
            assertEquals("core:check_status", states.get(0).stepId());
        }

        @Test
        @DisplayName("Should build state for merge core nodes")
        void shouldBuildStateForMergeCoreNodes() {
            // Given
            Core mergeCore = createCore("merge", "Wait All", "merge-1");
            WorkflowPlan plan = createPlanWithCores(List.of(mergeCore));
            ExecutionGraph graph = createMockExecutionGraph();

            Map<String, List<WorkflowStepDataEntity>> stepsByAlias = new HashMap<>();
            Set<String> completedStepIds = new HashSet<>();
            Set<String> failedStepIds = new HashSet<>();
            Set<String> skippedStepIds = new HashSet<>();
            Set<String> readySteps = new HashSet<>();
            Map<String, StatusCounts> stepStatusCounts = new HashMap<>();

            when(helper.determineStepStatus(anyString(), any(), any(), any(), any()))
                    .thenReturn(RunStatus.PENDING);

            // When
            List<WorkflowRunState.StepState> states = builder.buildStepStates(
                    plan, graph, stepsByAlias, completedStepIds, failedStepIds,
                    skippedStepIds, readySteps, stepStatusCounts
            );

            // Then - Merge nodes are now included in step states
            assertEquals(1, states.size());
            assertEquals("core:wait_all", states.get(0).stepId());
        }

        @Test
        @DisplayName("Should build state for download_file core node")
        void shouldBuildStateForDownloadFileCoreNode() {
            // Given
            Core downloadCore = createCore("download_file", "Fetch PDF", "download-1");
            WorkflowPlan plan = createPlanWithCores(List.of(downloadCore));
            ExecutionGraph graph = createMockExecutionGraph();

            Map<String, List<WorkflowStepDataEntity>> stepsByAlias = new HashMap<>();
            Set<String> completedStepIds = new HashSet<>();
            Set<String> failedStepIds = new HashSet<>();
            Set<String> skippedStepIds = new HashSet<>();
            Set<String> readySteps = new HashSet<>();
            Map<String, StatusCounts> stepStatusCounts = new HashMap<>();

            when(helper.determineStepStatus(anyString(), any(), any(), any(), any()))
                    .thenReturn(RunStatus.PENDING);

            // When
            List<WorkflowRunState.StepState> states = builder.buildStepStates(
                    plan, graph, stepsByAlias, completedStepIds, failedStepIds,
                    skippedStepIds, readySteps, stepStatusCounts
            );

            // Then
            assertEquals(1, states.size());
            assertEquals("core:fetch_pdf", states.get(0).stepId());
            assertEquals("fetch_pdf", states.get(0).stepAlias());
        }

        @Test
        @DisplayName("Should build state for http_request core node")
        void shouldBuildStateForHttpRequestCoreNode() {
            // Given
            Core httpCore = createCore("http_request", "Call API", "http-1");
            WorkflowPlan plan = createPlanWithCores(List.of(httpCore));
            ExecutionGraph graph = createMockExecutionGraph();

            Map<String, List<WorkflowStepDataEntity>> stepsByAlias = new HashMap<>();
            Set<String> completedStepIds = new HashSet<>();
            Set<String> failedStepIds = new HashSet<>();
            Set<String> skippedStepIds = new HashSet<>();
            Set<String> readySteps = new HashSet<>();
            Map<String, StatusCounts> stepStatusCounts = new HashMap<>();

            when(helper.determineStepStatus(anyString(), any(), any(), any(), any()))
                    .thenReturn(RunStatus.PENDING);

            // When
            List<WorkflowRunState.StepState> states = builder.buildStepStates(
                    plan, graph, stepsByAlias, completedStepIds, failedStepIds,
                    skippedStepIds, readySteps, stepStatusCounts
            );

            // Then
            assertEquals(1, states.size());
            assertEquals("core:call_api", states.get(0).stepId());
        }

        @Test
        @DisplayName("Should build state for split core node")
        void shouldBuildStateForSplitCoreNode() {
            // Given
            Core splitCore = createCore("split", "Each Item", "split-1");
            WorkflowPlan plan = createPlanWithCores(List.of(splitCore));
            ExecutionGraph graph = createMockExecutionGraph();

            Map<String, List<WorkflowStepDataEntity>> stepsByAlias = new HashMap<>();
            Set<String> completedStepIds = new HashSet<>();
            Set<String> failedStepIds = new HashSet<>();
            Set<String> skippedStepIds = new HashSet<>();
            Set<String> readySteps = new HashSet<>();
            Map<String, StatusCounts> stepStatusCounts = new HashMap<>();

            when(helper.determineStepStatus(anyString(), any(), any(), any(), any()))
                    .thenReturn(RunStatus.PENDING);

            // When
            List<WorkflowRunState.StepState> states = builder.buildStepStates(
                    plan, graph, stepsByAlias, completedStepIds, failedStepIds,
                    skippedStepIds, readySteps, stepStatusCounts
            );

            // Then
            assertEquals(1, states.size());
            assertEquals("core:each_item", states.get(0).stepId());
        }
    }

    // ========================================================================
    // buildStepStates() Tests - Deduplication
    // ========================================================================

    @Nested
    @DisplayName("buildStepStates() - Deduplication")
    class BuildStepStatesDeduplicationTests {

        @Test
        @DisplayName("Should not duplicate state for same alias processed twice")
        void shouldNotDuplicateStateForSameAlias() {
            // Given - Two steps with same normalized alias (edge case)
            Step step1 = createStep("step-1", "API Call");
            Step step2 = createStep("step-2", "Api call"); // Different case, same normalized
            WorkflowPlan plan = createPlanWithSteps(List.of(step1, step2));
            ExecutionGraph graph = createMockExecutionGraph();

            Map<String, List<WorkflowStepDataEntity>> stepsByAlias = new HashMap<>();
            Set<String> completedStepIds = new HashSet<>();
            Set<String> failedStepIds = new HashSet<>();
            Set<String> skippedStepIds = new HashSet<>();
            Set<String> readySteps = new HashSet<>();
            Map<String, StatusCounts> stepStatusCounts = new HashMap<>();

            when(helper.determineStepStatus(anyString(), any(), any(), any(), any()))
                    .thenReturn(RunStatus.PENDING);

            // When
            List<WorkflowRunState.StepState> states = builder.buildStepStates(
                    plan, graph, stepsByAlias, completedStepIds, failedStepIds,
                    skippedStepIds, readySteps, stepStatusCounts
            );

            // Then - Should only have one state (deduplicated by alias)
            assertEquals(1, states.size());
        }
    }

    // ========================================================================
    // buildStepStates() Tests - Status Counts
    // ========================================================================

    @Nested
    @DisplayName("buildStepStates() - Status Counts")
    class BuildStepStatesStatusCountsTests {

        @Test
        @DisplayName("Should include status counts in step state")
        void shouldIncludeStatusCountsInStepState() {
            // Given
            Step step = createStep("step-1", "API Call");
            WorkflowPlan plan = createPlanWithSteps(List.of(step));
            ExecutionGraph graph = createMockExecutionGraph();

            Map<String, List<WorkflowStepDataEntity>> stepsByAlias = new HashMap<>();
            Set<String> completedStepIds = new HashSet<>();
            Set<String> failedStepIds = new HashSet<>();
            Set<String> skippedStepIds = new HashSet<>();
            Set<String> readySteps = new HashSet<>();
            Map<String, StatusCounts> stepStatusCounts = new HashMap<>();

            Map<String, Integer> countsMap = Map.of(
                    "running", 0,
                    "completed", 5,
                    "failed", 1,
                    "skipped", 0,
                    "total", 6
            );
            when(helper.determineStepStatus(anyString(), any(), any(), any(), any()))
                    .thenReturn(RunStatus.PENDING);
            when(statusCountsBuilder.getStatusCountsMap(anyString(), anyString(), any()))
                    .thenReturn(countsMap);

            // When
            List<WorkflowRunState.StepState> states = builder.buildStepStates(
                    plan, graph, stepsByAlias, completedStepIds, failedStepIds,
                    skippedStepIds, readySteps, stepStatusCounts
            );

            // Then
            assertEquals(1, states.size());
            assertNotNull(states.get(0).statusCounts());
            assertEquals(5, states.get(0).statusCounts().get("completed"));
            assertEquals(1, states.get(0).statusCounts().get("failed"));
            assertEquals(6, states.get(0).statusCounts().get("total"));
        }
    }

    // ========================================================================
    // buildStepStates() Tests - Rerun (StateSnapshot overrides stale entity)
    // ========================================================================

    @Nested
    @DisplayName("buildStepStates() - Rerun Scenarios")
    class BuildStepStatesRerunTests {

        @Test
        @DisplayName("Should use StateSnapshot PENDING status over stale entity COMPLETED after rerun")
        void shouldUseSnapshotStatusOverStaleEntityStatus() {
            // Given: entity has stale COMPLETED from previous execution,
            // but StateSnapshot says PENDING (node was reset for rerun)
            Step step = createStep("step-1", "API Call");
            WorkflowPlan plan = createPlanWithSteps(List.of(step));
            ExecutionGraph graph = createMockExecutionGraph();

            WorkflowStepDataEntity entity = createStepEntity("api_call", "completed"); // stale

            Map<String, List<WorkflowStepDataEntity>> stepsByAlias = Map.of(
                    "api_call", List.of(entity)
            );
            Set<String> completedStepIds = new HashSet<>(); // NOT in completed
            Set<String> failedStepIds = new HashSet<>();
            Set<String> skippedStepIds = new HashSet<>();
            Set<String> readySteps = new HashSet<>();
            Map<String, StatusCounts> stepStatusCounts = new HashMap<>();

            // StateSnapshot says PENDING (the authoritative source)
            when(helper.determineStepStatus(anyString(), any(), any(), any(), any()))
                    .thenReturn(RunStatus.PENDING);
            when(helper.loadStepOutput(entity)).thenReturn(Map.of());
            when(helper.calculateExecutionTime(entity)).thenReturn(0L);

            // When
            List<WorkflowRunState.StepState> states = builder.buildStepStates(
                    plan, graph, stepsByAlias, completedStepIds, failedStepIds,
                    skippedStepIds, readySteps, stepStatusCounts
            );

            // Then: status should be PENDING (from StateSnapshot), not COMPLETED (from stale entity)
            assertEquals(1, states.size());
            assertEquals(RunStatus.PENDING, states.get(0).status());
        }

        @Test
        @DisplayName("regression: current-epoch SKIPPED wins over historical completed counts (rerun branch switch)")
        void skippedInCurrentEpochWinsOverHistoricalCompletedCounts() {
            // Bug: after a rerun switched a decision branch, the deactivated branch node was
            // SKIPPED in the current epoch state, but NodeCounts (never reset) still carried
            // completed=1 from the pre-rerun pass - deriveStatusFromCounts resurrected
            // COMPLETED, so the deactivated branch displayed as completed.
            Step step = createStep("step-1", "If Path");
            WorkflowPlan plan = createPlanWithSteps(List.of(step));
            ExecutionGraph graph = createMockExecutionGraph();

            WorkflowStepDataEntity entity = createStepEntity("if_path", "completed"); // pre-rerun row
            Map<String, List<WorkflowStepDataEntity>> stepsByAlias = Map.of(
                    "if_path", List.of(entity)
            );
            Set<String> completedStepIds = new HashSet<>();
            Set<String> failedStepIds = new HashSet<>();
            Set<String> skippedStepIds = new HashSet<>(Set.of("mcp:if_path")); // current epoch: SKIPPED
            Set<String> readySteps = new HashSet<>();
            Map<String, StatusCounts> stepStatusCounts = new HashMap<>();

            // StateSnapshot (authoritative current-epoch state) says SKIPPED
            when(helper.determineStepStatus(anyString(), any(), any(), any(), any()))
                    .thenReturn(RunStatus.SKIPPED);
            when(helper.loadStepOutput(entity)).thenReturn(Map.of());
            when(helper.calculateExecutionTime(entity)).thenReturn(0L);
            // Historical NodeCounts: completed=1 (pre-rerun) + skipped=1 (post-rerun)
            when(statusCountsBuilder.getStatusCountsMap(anyString(), anyString(), any()))
                    .thenReturn(Map.of("completed", 1, "skipped", 1, "total", 2));

            List<WorkflowRunState.StepState> states = builder.buildStepStates(
                    plan, graph, stepsByAlias, completedStepIds, failedStepIds,
                    skippedStepIds, readySteps, stepStatusCounts
            );

            assertEquals(1, states.size());
            assertEquals(RunStatus.SKIPPED, states.get(0).status(),
                    "current-epoch SKIPPED must not be overridden by accumulated completed counts");
        }

        @Test
        @DisplayName("regression: current-epoch COMPLETED wins over stale failed counts (rerun fixed a FAILED node)")
        void completedInCurrentEpochWinsOverStaleFailedCounts() {
            // Bug: a node FAILED, the user reran it with a fixed config and it COMPLETED.
            // NodeCounts (never reset) still carried failed=1 from the superseded spawn -
            // deriveStatusFromCounts demoted the fresh COMPLETED to PARTIAL_SUCCESS forever.
            Step step = createStep("step-1", "Fmt Date");
            WorkflowPlan plan = createPlanWithSteps(List.of(step));
            ExecutionGraph graph = createMockExecutionGraph();

            WorkflowStepDataEntity entity = createStepEntity("fmt_date", "completed");
            Map<String, List<WorkflowStepDataEntity>> stepsByAlias = Map.of(
                    "fmt_date", List.of(entity)
            );
            Set<String> completedStepIds = new HashSet<>(Set.of("mcp:fmt_date")); // current epoch: COMPLETED
            Set<String> failedStepIds = new HashSet<>();
            Set<String> skippedStepIds = new HashSet<>();
            Set<String> readySteps = new HashSet<>();
            Map<String, StatusCounts> stepStatusCounts = new HashMap<>();

            when(helper.determineStepStatus(anyString(), any(), any(), any(), any()))
                    .thenReturn(RunStatus.COMPLETED);
            when(helper.loadStepOutput(entity)).thenReturn(Map.of());
            when(helper.calculateExecutionTime(entity)).thenReturn(0L);
            // Accumulated NodeCounts: failed=1 (pre-rerun spawn) + completed=1 (post-fix spawn)
            when(statusCountsBuilder.getStatusCountsMap(anyString(), anyString(), any()))
                    .thenReturn(Map.of("completed", 1, "failed", 1, "total", 2));

            List<WorkflowRunState.StepState> states = builder.buildStepStates(
                    plan, graph, stepsByAlias, completedStepIds, failedStepIds,
                    skippedStepIds, readySteps, stepStatusCounts
            );

            assertEquals(1, states.size());
            assertEquals(RunStatus.COMPLETED, states.get(0).status(),
                    "current-epoch COMPLETED must not be demoted by a stale failed count from a superseded spawn");
        }

        @Test
        @DisplayName("split partial failure still shows PARTIAL_SUCCESS via the per-epoch partialFailed marker")
        void partialFailedMarkerStillYieldsPartialSuccess() {
            // The fix above must NOT hide genuine current-pass partial failures: those are
            // carried by EpochState.partialFailedNodeIds (split continue-anyway), not by
            // accumulated counts.
            Step step = createStep("step-1", "Per Item");
            WorkflowPlan plan = createPlanWithSteps(List.of(step));
            ExecutionGraph graph = createMockExecutionGraph();

            WorkflowStepDataEntity entity = createStepEntity("per_item", "completed");
            Map<String, List<WorkflowStepDataEntity>> stepsByAlias = Map.of(
                    "per_item", List.of(entity)
            );
            Set<String> completedStepIds = new HashSet<>(Set.of("mcp:per_item"));
            Set<String> failedStepIds = new HashSet<>();
            Set<String> skippedStepIds = new HashSet<>();
            Set<String> readySteps = new HashSet<>();
            Map<String, StatusCounts> stepStatusCounts = new HashMap<>();

            when(helper.determineStepStatus(anyString(), any(), any(), any(), any()))
                    .thenReturn(RunStatus.COMPLETED);
            when(helper.loadStepOutput(entity)).thenReturn(Map.of());
            when(helper.calculateExecutionTime(entity)).thenReturn(0L);
            when(statusCountsBuilder.getStatusCountsMap(anyString(), anyString(), any()))
                    .thenReturn(Map.of("completed", 2, "failed", 1, "total", 3));

            List<WorkflowRunState.StepState> states = builder.buildStepStates(
                    plan, graph, stepsByAlias, completedStepIds, failedStepIds,
                    skippedStepIds, readySteps, stepStatusCounts,
                    null, Map.of(), Map.of(), null,
                    StateReconstructor.OutputLoadMode.FULL,
                    Set.of("mcp:per_item") // current pass: completed WITH per-item failures
            );

            assertEquals(1, states.size());
            assertEquals(RunStatus.PARTIAL_SUCCESS, states.get(0).status(),
                    "a node marked partial-failed in the current epoch must surface PARTIAL_SUCCESS");
        }

        @Test
        @DisplayName("COMPLETED node with a live running count (parallel epoch) still shows RUNNING")
        void completedWithParallelRunningCountStillShowsRunning() {
            // The current-state-wins guard keeps the RUNNING override: a parallel epoch may
            // still be executing the node and live activity beats the other epoch's terminal state.
            Step step = createStep("step-1", "API Call");
            WorkflowPlan plan = createPlanWithSteps(List.of(step));
            ExecutionGraph graph = createMockExecutionGraph();

            WorkflowStepDataEntity entity = createStepEntity("api_call", "completed");
            Map<String, List<WorkflowStepDataEntity>> stepsByAlias = Map.of(
                    "api_call", List.of(entity)
            );
            Set<String> completedStepIds = new HashSet<>(Set.of("mcp:api_call"));
            Set<String> failedStepIds = new HashSet<>();
            Set<String> skippedStepIds = new HashSet<>();
            Set<String> readySteps = new HashSet<>();
            Map<String, StatusCounts> stepStatusCounts = new HashMap<>();

            when(helper.determineStepStatus(anyString(), any(), any(), any(), any()))
                    .thenReturn(RunStatus.COMPLETED);
            when(helper.loadStepOutput(entity)).thenReturn(Map.of());
            when(helper.calculateExecutionTime(entity)).thenReturn(0L);
            when(statusCountsBuilder.getStatusCountsMap(anyString(), anyString(), any()))
                    .thenReturn(Map.of("running", 1, "completed", 1, "total", 2));

            List<WorkflowRunState.StepState> states = builder.buildStepStates(
                    plan, graph, stepsByAlias, completedStepIds, failedStepIds,
                    skippedStepIds, readySteps, stepStatusCounts
            );

            assertEquals(1, states.size());
            assertEquals(RunStatus.RUNNING, states.get(0).status(),
                    "live running activity in a parallel epoch must override the terminal COMPLETED");
        }
    }

    // ========================================================================
    // buildStepStates() Tests - Running Nodes (SBS mode)
    // ========================================================================

    @Nested
    @DisplayName("buildStepStates() - Running Nodes Override")
    class BuildStepStatesRunningNodesTests {

        @Test
        @DisplayName("Should override PENDING to RUNNING for nodes in runningNodeIds")
        void shouldOverridePendingToRunningForRunningNodes() {
            // Given: agent node is executing in SBS mode, StateSnapshot has it in runningNodeIds
            Agent agent = createAgent("agent-1", "Nova");
            WorkflowPlan plan = createPlanWithAgents(List.of(agent));
            ExecutionGraph graph = createMockExecutionGraph();

            Map<String, List<WorkflowStepDataEntity>> stepsByAlias = new HashMap<>();
            Set<String> completedStepIds = new HashSet<>();
            Set<String> failedStepIds = new HashSet<>();
            Set<String> skippedStepIds = new HashSet<>();
            Set<String> readySteps = new HashSet<>();
            Map<String, StatusCounts> stepStatusCounts = new HashMap<>();
            Set<String> runningNodeIds = new HashSet<>(Set.of("agent:nova"));

            when(helper.determineStepStatus(anyString(), any(), any(), any(), any()))
                    .thenReturn(RunStatus.PENDING);

            // When
            List<WorkflowRunState.StepState> states = builder.buildStepStates(
                    plan, graph, stepsByAlias, completedStepIds, failedStepIds,
                    skippedStepIds, readySteps, stepStatusCounts,
                    null, Map.of(), Map.of(), runningNodeIds
            );

            // Then: status should be RUNNING, not PENDING
            assertEquals(1, states.size());
            assertEquals(RunStatus.RUNNING, states.get(0).status());
        }

        @Test
        @DisplayName("Should NOT override AWAITING_SIGNAL for nodes also in runningNodeIds")
        void shouldNotOverrideAwaitingSignalForRunningNodes() {
            // Given: a node is awaiting signal AND in runningNodeIds set
            Step step = createStep("step-1", "Wait Approval");
            WorkflowPlan plan = createPlanWithSteps(List.of(step));
            ExecutionGraph graph = createMockExecutionGraph();

            Map<String, List<WorkflowStepDataEntity>> stepsByAlias = new HashMap<>();
            Set<String> completedStepIds = new HashSet<>();
            Set<String> failedStepIds = new HashSet<>();
            Set<String> skippedStepIds = new HashSet<>();
            Set<String> readySteps = new HashSet<>();
            Map<String, StatusCounts> stepStatusCounts = new HashMap<>();
            Set<String> awaitingSignalNodeIds = new HashSet<>(Set.of("mcp:wait_approval"));
            Set<String> runningNodeIds = new HashSet<>(Set.of("mcp:wait_approval"));

            when(helper.determineStepStatus(anyString(), any(), any(), any(), any()))
                    .thenReturn(RunStatus.PENDING);

            // When
            List<WorkflowRunState.StepState> states = builder.buildStepStates(
                    plan, graph, stepsByAlias, completedStepIds, failedStepIds,
                    skippedStepIds, readySteps, stepStatusCounts,
                    awaitingSignalNodeIds, Map.of(), Map.of(), runningNodeIds
            );

            // Then: awaiting signal takes priority over running
            assertEquals(1, states.size());
            assertEquals(RunStatus.AWAITING_SIGNAL, states.get(0).status());
        }
    }

    // ========================================================================
    // buildStepStates() Tests - Error Handling
    // ========================================================================

    @Nested
    @DisplayName("buildStepStates() - Error Handling")
    class BuildStepStatesErrorHandlingTests {

        @Test
        @DisplayName("Should include error message for failed step")
        void shouldIncludeErrorMessageForFailedStep() {
            // Given
            Step step = createStep("step-1", "API Call");
            WorkflowPlan plan = createPlanWithSteps(List.of(step));
            ExecutionGraph graph = createMockExecutionGraph();

            WorkflowStepDataEntity entity = createStepEntity("api_call", "failed");
            entity.setErrorMessage("Connection timeout");
            entity.setHttpStatus(500);

            Map<String, List<WorkflowStepDataEntity>> stepsByAlias = Map.of(
                    "api_call", List.of(entity)
            );
            Set<String> completedStepIds = new HashSet<>();
            Set<String> failedStepIds = new HashSet<>(Set.of("mcp:api_call"));
            Set<String> skippedStepIds = new HashSet<>();
            Set<String> readySteps = new HashSet<>();
            Map<String, StatusCounts> stepStatusCounts = new HashMap<>();

            when(helper.determineStepStatus(anyString(), any(), any(), any(), any()))
                    .thenReturn(RunStatus.FAILED);
            when(helper.loadStepOutput(entity)).thenReturn(Map.of());
            when(helper.calculateExecutionTime(entity)).thenReturn(0L);

            // When
            List<WorkflowRunState.StepState> states = builder.buildStepStates(
                    plan, graph, stepsByAlias, completedStepIds, failedStepIds,
                    skippedStepIds, readySteps, stepStatusCounts
            );

            // Then
            assertEquals(1, states.size());
            assertEquals(RunStatus.FAILED, states.get(0).status());
            assertEquals("Connection timeout", states.get(0).errorMessage());
            assertEquals(500, states.get(0).httpStatus());
        }
    }

    // ========================================================================
    // buildStepStates() Tests - SBS Epoch-Aware Status
    // ========================================================================

    @Nested
    @DisplayName("buildStepStates() - SBS Epoch-Aware PENDING")
    class BuildStepStatesSbsEpochTests {

        @Test
        @DisplayName("Should return PENDING when StateSnapshot says PENDING, even with historical NodeCounts")
        void shouldReturnPendingEvenWithHistoricalCounts() {
            // Given: step has historical completed=3 from previous epochs in NodeCounts,
            // but StateSnapshot says PENDING (not in completed/failed/skipped/ready flat views)
            // because a new epoch started and the node hasn't been executed yet.
            Step step = createStep("step-1", "API Call");
            WorkflowPlan plan = createPlanWithSteps(List.of(step));
            ExecutionGraph graph = createMockExecutionGraph();

            WorkflowStepDataEntity entity = createStepEntity("api_call", "completed"); // stale from old epoch

            Map<String, List<WorkflowStepDataEntity>> stepsByAlias = Map.of(
                    "api_call", List.of(entity)
            );
            Set<String> completedStepIds = new HashSet<>(); // NOT in completed (new epoch)
            Set<String> failedStepIds = new HashSet<>();
            Set<String> skippedStepIds = new HashSet<>();
            Set<String> readySteps = new HashSet<>(); // NOT in ready either (downstream node)
            Map<String, StatusCounts> stepStatusCounts = new HashMap<>();

            // StateSnapshot says PENDING (authoritative)
            when(helper.determineStepStatus(anyString(), any(), any(), any(), any()))
                    .thenReturn(RunStatus.PENDING);
            when(helper.loadStepOutput(entity)).thenReturn(Map.of());
            when(helper.calculateExecutionTime(entity)).thenReturn(0L);

            // When
            List<WorkflowRunState.StepState> states = builder.buildStepStates(
                    plan, graph, stepsByAlias, completedStepIds, failedStepIds,
                    skippedStepIds, readySteps, stepStatusCounts
            );

            // Then: status should be PENDING (from StateSnapshot), not COMPLETED
            assertEquals(1, states.size());
            assertEquals(RunStatus.PENDING, states.get(0).status(),
                    "Node should be PENDING after new epoch - historical counts don't override");
        }

        @Test
        @DisplayName("Should return PENDING for ready node (direct child of trigger) after new epoch")
        void shouldReturnPendingForReadyNodeAfterNewEpoch() {
            // Given: step1 is a direct child of the trigger. After new trigger fire,
            // it's in readySteps. Status should be PENDING (with canExecute=true).
            Step step = createStep("step-1", "API Call");
            WorkflowPlan plan = createPlanWithSteps(List.of(step));
            ExecutionGraph graph = createMockExecutionGraph();

            Map<String, List<WorkflowStepDataEntity>> stepsByAlias = new HashMap<>();
            Set<String> completedStepIds = new HashSet<>();
            Set<String> failedStepIds = new HashSet<>();
            Set<String> skippedStepIds = new HashSet<>();
            Set<String> readySteps = new HashSet<>(Set.of("mcp:api_call")); // ready!
            Map<String, StatusCounts> stepStatusCounts = new HashMap<>();

            when(helper.determineStepStatus(anyString(), any(), any(), any(), any()))
                    .thenReturn(RunStatus.PENDING);

            // When
            List<WorkflowRunState.StepState> states = builder.buildStepStates(
                    plan, graph, stepsByAlias, completedStepIds, failedStepIds,
                    skippedStepIds, readySteps, stepStatusCounts
            );

            // Then: PENDING + canExecute
            assertEquals(1, states.size());
            assertEquals(RunStatus.PENDING, states.get(0).status());
            assertTrue(states.get(0).canExecute(),
                    "Ready node should have canExecute=true");
        }
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    private Step createStep(String id, String label) {
        return new Step(
                id,
                "mcp",     // type
                label,     // label
                null,      // parentLoopId
                Map.of(),  // input
                null,      // dataSourceId
                null,      // crud
                null       // graphNodeId
        );
    }

    private Trigger createTrigger(String id, String label, String type) {
        return new Trigger(id, label, "single", type);
    }

    private Agent createAgent(String id, String label) {
        return new Agent(
                id,        // id
                "agent",   // type
                label,     // label
                null,      // agentConfigId
                null,      // withMemory
                null,      // provider
                null,      // model
                null,      // systemPrompt
                null,      // prompt
                null,      // temperature
                null,      // maxTokens
                null,      // maxIterations
                null,      // maxTools
                null,      // tools
                null,      // parentLoopId
                null,      // input
                null,      // classifyCategories
                null,      // classifyInput
                null,      // guardrailRules
                null       // guardrailInput
        , null);
    }

    private Core createCore(String type, String label, String id) {
        return new Core(
                id,        // id
                type,      // type
                null,      // position
                label,     // label
                null,      // decisionConditions
                null,      // switchExpression
                null,      // switchCases
                null,      // loopCondition
                null,      // maxIterations
                null,      // strategy
                null,      // list
                null,      // maxItems
                null,      // splitStrategy
                null,      // forkOutputs
                null,      // transformConfig
                null,      // waitConfig
                null,      // downloadConfig
                null,      // responseConfig
                null,      // aggregateConfig
                null,      // optionChoices
                null,      // httpRequestConfig
                null,      // approvalConfig
                null,      // dataInputConfig
                null,      // filterConfig
                null,      // sortConfig
                null,      // limitConfig
                null,      // removeDuplicatesConfig
                null,      // summarizeConfig
                null,      // dateTimeConfig
                null,      // cryptoJwtConfig
                null,      // xmlConfig
                null,      // compressionConfig
                null,      // rssConfig
                null,      // convertToFileConfig
                null,      // extractFromFileConfig
                null,      // compareDatasetsConfig
                null,      // subWorkflowConfig
                null,      // respondToWebhookConfig
                null,      // sendEmailConfig
                null,      // emailInboxConfig
                null,      // codeConfig
                null,      // setConfig
                null,      // htmlExtractConfig
                null,      // taskConfig
                null,      // stopOnErrorConfig
                null,      // sshConfig
                null,      // sftpConfig
                null,      // databaseConfig
                null,      // params
                null       // graphNodeId
        );
    }

    private WorkflowPlan createPlanWithSteps(List<Step> steps) {
        return new WorkflowPlan(
                "plan-1",
                "tenant-1",
                List.of(),  // triggers
                steps,      // mcps
                null,       // agents
                List.of(),  // edges
                null, null, null, null, Map.of()
        );
    }

    private WorkflowPlan createPlanWithTriggers(List<Trigger> triggers) {
        return new WorkflowPlan(
                "plan-1",
                "tenant-1",
                triggers,
                List.of(),
                null,       // agents
                List.of(),  // edges
                null, null, null, null, Map.of()
        );
    }

    private WorkflowPlan createPlanWithAgents(List<Agent> agents) {
        return new WorkflowPlan(
                "plan-1",
                "tenant-1",
                List.of(),
                List.of(),
                agents,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                Map.of()
        );
    }

    private WorkflowPlan createPlanWithCores(List<Core> cores) {
        return new WorkflowPlan(
                "plan-1",
                "tenant-1",
                List.of(),
                List.of(),
                null,       // agents
                List.of(),  // edges
                cores,
                null, null, null, Map.of()
        );
    }

    private ExecutionGraph createMockExecutionGraph() {
        return new ExecutionGraph();
    }

    private WorkflowStepDataEntity createStepEntity(String alias, String status) {
        WorkflowStepDataEntity entity = new WorkflowStepDataEntity();
        entity.setStepAlias(alias);
        entity.setStatus(status);
        entity.setToolId("tool-1");
        return entity;
    }
}
