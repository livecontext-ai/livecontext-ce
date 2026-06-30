package com.apimarketplace.orchestrator.services;

import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.ExecutionMode;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("EditorRunResolver")
class EditorRunResolverTest {

    @Mock private WorkflowPlanVersionService versionService;
    @Mock private WorkflowExecutionService executionService;
    @Mock private WorkflowRunRepository runRepository;

    private EditorRunResolver resolver;

    private static final UUID WORKFLOW_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String RUN_ID = "run-abc-123";
    private static final String TENANT_ID = "user@test.com";
    // Mirror of EditorRunResolver.REUSABLE_STATUSES - the live-run reuse window.
    private static final List<RunStatus> REUSABLE_STATUSES =
            List.of(RunStatus.WAITING_TRIGGER, RunStatus.RUNNING, RunStatus.PAUSED);

    @BeforeEach
    void setUp() {
        resolver = new EditorRunResolver(versionService, executionService, runRepository);
    }

    @Nested
    @DisplayName("findOrCreateRun")
    class FindOrCreateRunTests {

        @Mock private WorkflowEntity workflow;
        @Mock private WorkflowPlan plan;

        @BeforeEach
        void initWorkflow() {
            lenient().when(workflow.getId()).thenReturn(WORKFLOW_ID);
            lenient().when(plan.getOriginalPlan()).thenReturn(Map.of("name", "test"));
        }

        @Test
        @DisplayName("Reuses existing WAITING_TRIGGER run at same version and same mode")
        void reusesWaitingTriggerRun() {
            when(versionService.resolveContentVersionForExecution(eq(WORKFLOW_ID), any(), eq(TENANT_ID))).thenReturn(5);

            WorkflowRunEntity existingRun = mock(WorkflowRunEntity.class);
            when(existingRun.getRunIdPublic()).thenReturn("run-existing");
            when(runRepository.findFirstByWorkflowIdAndPlanVersionAndExecutionModeAndStatusInOrderByStartedAtDesc(
                    WORKFLOW_ID, 5, ExecutionMode.AUTOMATIC, REUSABLE_STATUSES))
                    .thenReturn(Optional.of(existingRun));

            EditorRunResolver.Resolution result = resolver.findOrCreateRun(
                    workflow, plan, Map.of(), TENANT_ID, ExecutionMode.AUTOMATIC);

            assertThat(result.reused()).isTrue();
            assertThat(result.runEntity()).isSameAs(existingRun);
            assertThat(result.execution()).isNull();
            assertThat(result.planVersion()).isEqualTo(5);
            verify(executionService, never()).createExecution(any(), any(), anyInt());
        }

        @Test
        @DisplayName("Structurally drifted canvas at the SAME version skips reuse - fresh run, no new version minted")
        void structuralDriftSkipsReuseButKeepsVersion() {
            // With stable execution-time versions (2026-06-11), a structurally edited
            // canvas resolves to the SAME version number as the old WAITING_TRIGGER
            // run. Reusing that run would corrupt its StateSnapshot (node-id-indexed
            // counters), and the fire-path topology guard would silently freeze the
            // old plan. A fresh run (clean snapshot) at the same version is required.
            Map<String, Object> oldStructure = Map.of(
                    "mcps", List.of(Map.of("label", "A", "service", "http", "action", "get", "params", Map.of())),
                    "edges", List.of(Map.of("from", "trigger:start", "to", "mcp:a")));
            Map<String, Object> newStructure = Map.of(
                    "mcps", List.of(
                            Map.of("label", "A", "service", "http", "action", "get", "params", Map.of()),
                            Map.of("label", "B", "service", "http", "action", "get", "params", Map.of())),
                    "edges", List.of(
                            Map.of("from", "trigger:start", "to", "mcp:a"),
                            Map.of("from", "mcp:a", "to", "mcp:b")));
            when(plan.getOriginalPlan()).thenReturn(newStructure);
            when(versionService.resolveContentVersionForExecution(eq(WORKFLOW_ID), any(), eq(TENANT_ID))).thenReturn(5);

            WorkflowRunEntity existingRun = mock(WorkflowRunEntity.class);
            when(existingRun.getRunIdPublic()).thenReturn("run-existing");
            when(existingRun.getPlan()).thenReturn(new java.util.HashMap<>(oldStructure));
            when(runRepository.findFirstByWorkflowIdAndPlanVersionAndExecutionModeAndStatusInOrderByStartedAtDesc(
                    WORKFLOW_ID, 5, ExecutionMode.AUTOMATIC, REUSABLE_STATUSES))
                    .thenReturn(Optional.of(existingRun));

            WorkflowExecution execution = mock(WorkflowExecution.class);
            when(execution.getRunId()).thenReturn(RUN_ID);
            when(executionService.createExecution(eq(plan), any(), eq(5))).thenReturn(execution);
            WorkflowRunEntity newRun = mock(WorkflowRunEntity.class);
            when(newRun.getMetadata()).thenReturn(null);
            when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(newRun));

            EditorRunResolver.Resolution result = resolver.findOrCreateRun(
                    workflow, plan, Map.of(), TENANT_ID, ExecutionMode.AUTOMATIC);

            assertThat(result.reused()).isFalse();
            assertThat(result.runEntity()).isSameAs(newRun);
            assertThat(result.planVersion()).isEqualTo(5);
        }

        @Test
        @DisplayName("Param-only drifted canvas (topology-compatible) still reuses the WAITING_TRIGGER run at the same version")
        void paramOnlyDriftStillReusesRun() {
            // The structural guard must only block node/edge changes. A parameter
            // edit produces a plan that differs from the run's frozen plan but is
            // topology-compatible - the run is reused (the fire path propagates the
            // new params into run.plan).
            Map<String, Object> frozenParams = Map.of(
                    "mcps", List.of(Map.of("label", "A", "service", "http", "action", "get", "params", Map.of("url", "old"))),
                    "edges", List.of(Map.of("from", "trigger:start", "to", "mcp:a")));
            Map<String, Object> editedParams = Map.of(
                    "mcps", List.of(Map.of("label", "A", "service", "http", "action", "get", "params", Map.of("url", "new"))),
                    "edges", List.of(Map.of("from", "trigger:start", "to", "mcp:a")));
            when(plan.getOriginalPlan()).thenReturn(editedParams);
            when(versionService.resolveContentVersionForExecution(eq(WORKFLOW_ID), any(), eq(TENANT_ID))).thenReturn(5);

            WorkflowRunEntity existingRun = mock(WorkflowRunEntity.class);
            when(existingRun.getRunIdPublic()).thenReturn("run-existing");
            when(existingRun.getPlan()).thenReturn(new HashMap<>(frozenParams));
            when(runRepository.findFirstByWorkflowIdAndPlanVersionAndExecutionModeAndStatusInOrderByStartedAtDesc(
                    WORKFLOW_ID, 5, ExecutionMode.AUTOMATIC, REUSABLE_STATUSES))
                    .thenReturn(Optional.of(existingRun));

            EditorRunResolver.Resolution result = resolver.findOrCreateRun(
                    workflow, plan, Map.of(), TENANT_ID, ExecutionMode.AUTOMATIC);

            assertThat(result.reused()).isTrue();
            assertThat(result.runEntity()).isSameAs(existingRun);
            assertThat(result.planVersion()).isEqualTo(5);
            verify(executionService, never()).createExecution(any(), any(), anyInt());
        }

        @Test
        @DisplayName("Creates new run when no WAITING_TRIGGER run exists")
        void createsNewRunWhenNoneExists() {
            when(versionService.resolveContentVersionForExecution(eq(WORKFLOW_ID), any(), eq(TENANT_ID))).thenReturn(3);
            when(runRepository.findFirstByWorkflowIdAndPlanVersionAndExecutionModeAndStatusInOrderByStartedAtDesc(
                    WORKFLOW_ID, 3, ExecutionMode.AUTOMATIC, REUSABLE_STATUSES))
                    .thenReturn(Optional.empty());

            WorkflowExecution execution = mock(WorkflowExecution.class);
            when(execution.getRunId()).thenReturn(RUN_ID);
            when(executionService.createExecution(eq(plan), any(), eq(3))).thenReturn(execution);

            WorkflowRunEntity newRun = mock(WorkflowRunEntity.class);
            when(newRun.getMetadata()).thenReturn(null);
            when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(newRun));

            EditorRunResolver.Resolution result = resolver.findOrCreateRun(
                    workflow, plan, Map.of(), TENANT_ID, ExecutionMode.AUTOMATIC);

            assertThat(result.reused()).isFalse();
            assertThat(result.runEntity()).isSameAs(newRun);
            assertThat(result.execution()).isSameAs(execution);
            assertThat(result.planVersion()).isEqualTo(3);
        }

        @Test
        @DisplayName("New run is marked as __editorRun__ in metadata")
        void marksNewRunAsEditorRun() {
            when(versionService.resolveContentVersionForExecution(eq(WORKFLOW_ID), any(), eq(TENANT_ID))).thenReturn(1);
            when(runRepository.findFirstByWorkflowIdAndPlanVersionAndExecutionModeAndStatusInOrderByStartedAtDesc(
                    eq(WORKFLOW_ID), eq(1), eq(ExecutionMode.AUTOMATIC), any())).thenReturn(Optional.empty());

            WorkflowExecution execution = mock(WorkflowExecution.class);
            when(execution.getRunId()).thenReturn(RUN_ID);
            when(executionService.createExecution(any(), any(), eq(1))).thenReturn(execution);

            WorkflowRunEntity newRun = mock(WorkflowRunEntity.class);
            when(newRun.getMetadata()).thenReturn(null);
            when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(newRun));

            resolver.findOrCreateRun(workflow, plan, Map.of(), TENANT_ID, ExecutionMode.AUTOMATIC);

            verify(newRun).setMetadata(argThat(meta ->
                    Boolean.TRUE.equals(meta.get("__editorRun__"))));
            verify(runRepository).save(newRun);
        }

        @Test
        @DisplayName("Preserves existing metadata when marking __editorRun__")
        void preservesExistingMetadata() {
            when(versionService.resolveContentVersionForExecution(eq(WORKFLOW_ID), any(), eq(TENANT_ID))).thenReturn(1);
            when(runRepository.findFirstByWorkflowIdAndPlanVersionAndExecutionModeAndStatusInOrderByStartedAtDesc(
                    eq(WORKFLOW_ID), eq(1), eq(ExecutionMode.AUTOMATIC), any())).thenReturn(Optional.empty());

            WorkflowExecution execution = mock(WorkflowExecution.class);
            when(execution.getRunId()).thenReturn(RUN_ID);
            when(executionService.createExecution(any(), any(), eq(1))).thenReturn(execution);

            WorkflowRunEntity newRun = mock(WorkflowRunEntity.class);
            Map<String, Object> existingMeta = new HashMap<>(Map.of("userPlan", "pro"));
            when(newRun.getMetadata()).thenReturn(existingMeta);
            when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(newRun));

            resolver.findOrCreateRun(workflow, plan, Map.of(), TENANT_ID, ExecutionMode.AUTOMATIC);

            verify(newRun).setMetadata(argThat(meta ->
                    Boolean.TRUE.equals(meta.get("__editorRun__"))
                            && "pro".equals(meta.get("userPlan"))));
        }

        @Test
        @DisplayName("Passes dataInputs to createExecution for new runs")
        void passesDataInputs() {
            Map<String, Object> dataInputs = Map.of("key", "value");
            when(versionService.resolveContentVersionForExecution(eq(WORKFLOW_ID), any(), eq(TENANT_ID))).thenReturn(1);
            when(runRepository.findFirstByWorkflowIdAndPlanVersionAndExecutionModeAndStatusInOrderByStartedAtDesc(
                    eq(WORKFLOW_ID), eq(1), eq(ExecutionMode.AUTOMATIC), any())).thenReturn(Optional.empty());

            WorkflowExecution execution = mock(WorkflowExecution.class);
            when(execution.getRunId()).thenReturn(RUN_ID);
            when(executionService.createExecution(eq(plan), eq(dataInputs), eq(1))).thenReturn(execution);

            WorkflowRunEntity newRun = mock(WorkflowRunEntity.class);
            when(newRun.getMetadata()).thenReturn(null);
            when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(newRun));

            resolver.findOrCreateRun(workflow, plan, dataInputs, TENANT_ID, ExecutionMode.AUTOMATIC);

            verify(executionService).createExecution(eq(plan), eq(dataInputs), eq(1));
        }

        @Test
        @DisplayName("Throws if newly created run is not found in DB")
        void throwsIfRunNotFound() {
            when(versionService.resolveContentVersionForExecution(eq(WORKFLOW_ID), any(), eq(TENANT_ID))).thenReturn(1);
            when(runRepository.findFirstByWorkflowIdAndPlanVersionAndExecutionModeAndStatusInOrderByStartedAtDesc(
                    eq(WORKFLOW_ID), eq(1), eq(ExecutionMode.AUTOMATIC), any())).thenReturn(Optional.empty());

            WorkflowExecution execution = mock(WorkflowExecution.class);
            when(execution.getRunId()).thenReturn("run-ghost");
            when(executionService.createExecution(any(), any(), eq(1))).thenReturn(execution);
            when(runRepository.findByRunIdPublic("run-ghost")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> resolver.findOrCreateRun(
                    workflow, plan, Map.of(), TENANT_ID, ExecutionMode.AUTOMATIC))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Run not found after creation");
        }

        @Test
        @DisplayName("Reused run does not get __editorRun__ metadata update")
        void reusedRunNotMarked() {
            when(versionService.resolveContentVersionForExecution(eq(WORKFLOW_ID), any(), eq(TENANT_ID))).thenReturn(5);

            WorkflowRunEntity existingRun = mock(WorkflowRunEntity.class);
            when(existingRun.getRunIdPublic()).thenReturn("run-existing");
            when(runRepository.findFirstByWorkflowIdAndPlanVersionAndExecutionModeAndStatusInOrderByStartedAtDesc(
                    WORKFLOW_ID, 5, ExecutionMode.AUTOMATIC, REUSABLE_STATUSES))
                    .thenReturn(Optional.of(existingRun));

            resolver.findOrCreateRun(workflow, plan, Map.of(), TENANT_ID, ExecutionMode.AUTOMATIC);

            verify(existingRun, never()).setMetadata(any());
            verify(runRepository, never()).save(any());
        }
    }

    /**
     * Regression - duplicate same-version run when a fire arrives mid-epoch (2026-06-11).
     *
     * <p>Prod: an agent fired trigger A, then fired trigger B ~10s later while epoch 1
     * was still blocked on a WAIT_TIMER (run status PAUSED/RUNNING, not WAITING_TRIGGER).
     * The resolver only reused WAITING_TRIGGER runs, so the second fire minted a
     * duplicate run at the same plan version instead of opening a new epoch on the
     * live run. The reuse window now covers all live statuses
     * (WAITING_TRIGGER / RUNNING / PAUSED); the fire path supports active-run fires
     * (per-trigger DAGs), as the production webhook lane already proved.
     */
    @Nested
    @DisplayName("Live-run reuse window (regression: duplicate same-version run on mid-epoch fire)")
    class LiveRunReuseWindowTests {

        @Mock private WorkflowEntity workflow;
        @Mock private WorkflowPlan plan;

        @BeforeEach
        void initWorkflow() {
            lenient().when(workflow.getId()).thenReturn(WORKFLOW_ID);
            lenient().when(plan.getOriginalPlan()).thenReturn(Map.of("name", "test"));
        }

        private WorkflowRunEntity liveRun(RunStatus status) {
            WorkflowRunEntity run = mock(WorkflowRunEntity.class);
            lenient().when(run.getRunIdPublic()).thenReturn("run-live");
            lenient().when(run.getStatus()).thenReturn(status);
            return run;
        }

        @Test
        @DisplayName("Fire while previous epoch still RUNNING reuses the live run (no duplicate same-version run)")
        void firesOnRunningRunReuseIt() {
            when(versionService.resolveContentVersionForExecution(eq(WORKFLOW_ID), any(), eq(TENANT_ID))).thenReturn(3);
            WorkflowRunEntity running = liveRun(RunStatus.RUNNING);
            when(runRepository.findFirstByWorkflowIdAndPlanVersionAndExecutionModeAndStatusInOrderByStartedAtDesc(
                    WORKFLOW_ID, 3, ExecutionMode.AUTOMATIC, REUSABLE_STATUSES))
                    .thenReturn(Optional.of(running));

            EditorRunResolver.Resolution result = resolver.findOrCreateRun(
                    workflow, plan, Map.of(), TENANT_ID, ExecutionMode.AUTOMATIC);

            assertThat(result.reused()).isTrue();
            assertThat(result.runEntity()).isSameAs(running);
            assertThat(result.planVersion()).isEqualTo(3);
            verify(executionService, never()).createExecution(any(), any(), anyInt());
        }

        @Test
        @DisplayName("Fire while previous epoch is PAUSED on a signal (wait/approval/interface) reuses the live run")
        void firesOnPausedRunReuseIt() {
            when(versionService.resolveContentVersionForExecution(eq(WORKFLOW_ID), any(), eq(TENANT_ID))).thenReturn(3);
            WorkflowRunEntity paused = liveRun(RunStatus.PAUSED);
            when(runRepository.findFirstByWorkflowIdAndPlanVersionAndExecutionModeAndStatusInOrderByStartedAtDesc(
                    WORKFLOW_ID, 3, ExecutionMode.AUTOMATIC, REUSABLE_STATUSES))
                    .thenReturn(Optional.of(paused));

            EditorRunResolver.Resolution result = resolver.findOrCreateRun(
                    workflow, plan, Map.of(), TENANT_ID, ExecutionMode.AUTOMATIC);

            assertThat(result.reused()).isTrue();
            assertThat(result.runEntity()).isSameAs(paused);
            verify(executionService, never()).createExecution(any(), any(), anyInt());
        }

        @Test
        @DisplayName("Reuse query asks for exactly WAITING_TRIGGER + RUNNING + PAUSED - terminal runs are never fire targets")
        void reuseWindowExcludesTerminalStatuses() {
            when(versionService.resolveContentVersionForExecution(eq(WORKFLOW_ID), any(), eq(TENANT_ID))).thenReturn(3);
            when(runRepository.findFirstByWorkflowIdAndPlanVersionAndExecutionModeAndStatusInOrderByStartedAtDesc(
                    eq(WORKFLOW_ID), eq(3), eq(ExecutionMode.AUTOMATIC), any())).thenReturn(Optional.empty());
            WorkflowExecution execution = mock(WorkflowExecution.class);
            when(execution.getRunId()).thenReturn(RUN_ID);
            when(executionService.createExecution(any(), any(), eq(3))).thenReturn(execution);
            WorkflowRunEntity newRun = mock(WorkflowRunEntity.class);
            when(newRun.getMetadata()).thenReturn(null);
            when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(newRun));

            resolver.findOrCreateRun(workflow, plan, Map.of(), TENANT_ID, ExecutionMode.AUTOMATIC);

            verify(runRepository).findFirstByWorkflowIdAndPlanVersionAndExecutionModeAndStatusInOrderByStartedAtDesc(
                    eq(WORKFLOW_ID), eq(3), eq(ExecutionMode.AUTOMATIC), argThat(statuses ->
                            statuses.size() == 3
                                    && statuses.contains(RunStatus.WAITING_TRIGGER)
                                    && statuses.contains(RunStatus.RUNNING)
                                    && statuses.contains(RunStatus.PAUSED)));
        }

        @Test
        @DisplayName("SBS request while an AUTO run is live creates a new run - the lookup is mode-scoped, SBS is the one legitimate same-version duplicate")
        void modeMismatchOnActiveRunStillCreatesNewRun() {
            when(versionService.resolveContentVersionForExecution(eq(WORKFLOW_ID), any(), eq(TENANT_ID))).thenReturn(3);
            // A live AUTOMATIC run exists at this version, but the SBS request must not
            // see it: the lookup filters by the REQUESTED mode and finds nothing.
            WorkflowRunEntity runningAuto = liveRun(RunStatus.RUNNING);
            lenient().when(runRepository.findFirstByWorkflowIdAndPlanVersionAndExecutionModeAndStatusInOrderByStartedAtDesc(
                    WORKFLOW_ID, 3, ExecutionMode.AUTOMATIC, REUSABLE_STATUSES))
                    .thenReturn(Optional.of(runningAuto));
            when(runRepository.findFirstByWorkflowIdAndPlanVersionAndExecutionModeAndStatusInOrderByStartedAtDesc(
                    WORKFLOW_ID, 3, ExecutionMode.STEP_BY_STEP, REUSABLE_STATUSES))
                    .thenReturn(Optional.empty());
            WorkflowExecution execution = mock(WorkflowExecution.class);
            when(execution.getRunId()).thenReturn(RUN_ID);
            when(executionService.createExecution(any(), any(), eq(3))).thenReturn(execution);
            WorkflowRunEntity newRun = mock(WorkflowRunEntity.class);
            when(newRun.getMetadata()).thenReturn(null);
            when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(newRun));

            EditorRunResolver.Resolution result = resolver.findOrCreateRun(
                    workflow, plan, Map.of(), TENANT_ID, ExecutionMode.STEP_BY_STEP);

            assertThat(result.reused()).isFalse();
            assertThat(result.runEntity()).isSameAs(newRun);
            assertThat(result.planVersion()).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("ExecutionMode mismatch handling")
    class ExecutionModeMismatchTests {

        @Mock private WorkflowEntity workflow;
        @Mock private WorkflowPlan plan;

        @BeforeEach
        void initWorkflow() {
            lenient().when(workflow.getId()).thenReturn(WORKFLOW_ID);
            lenient().when(plan.getOriginalPlan()).thenReturn(Map.of("name", "test"));
        }

        @Test
        @DisplayName("Creates new run when only an AUTOMATIC live run exists but STEP_BY_STEP requested (mode-scoped lookup)")
        void autoToStepByStepCreatesNewRun() {
            when(versionService.resolveContentVersionForExecution(eq(WORKFLOW_ID), any(), eq(TENANT_ID))).thenReturn(5);

            // A live AUTOMATIC run exists at this version, but the SBS-scoped lookup finds nothing.
            WorkflowRunEntity existingRun = mock(WorkflowRunEntity.class);
            lenient().when(existingRun.getRunIdPublic()).thenReturn("run-auto");
            lenient().when(runRepository.findFirstByWorkflowIdAndPlanVersionAndExecutionModeAndStatusInOrderByStartedAtDesc(
                    WORKFLOW_ID, 5, ExecutionMode.AUTOMATIC, REUSABLE_STATUSES))
                    .thenReturn(Optional.of(existingRun));
            when(runRepository.findFirstByWorkflowIdAndPlanVersionAndExecutionModeAndStatusInOrderByStartedAtDesc(
                    WORKFLOW_ID, 5, ExecutionMode.STEP_BY_STEP, REUSABLE_STATUSES))
                    .thenReturn(Optional.empty());

            // New run creation
            WorkflowExecution execution = mock(WorkflowExecution.class);
            when(execution.getRunId()).thenReturn(RUN_ID);
            when(executionService.createExecution(eq(plan), any(), eq(5))).thenReturn(execution);

            WorkflowRunEntity newRun = mock(WorkflowRunEntity.class);
            when(newRun.getMetadata()).thenReturn(null);
            when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(newRun));

            // Request STEP_BY_STEP
            EditorRunResolver.Resolution result = resolver.findOrCreateRun(
                    workflow, plan, Map.of(), TENANT_ID, ExecutionMode.STEP_BY_STEP);

            assertThat(result.reused()).isFalse();
            assertThat(result.runEntity()).isSameAs(newRun);
            assertThat(result.execution()).isSameAs(execution);
            verify(executionService).createExecution(eq(plan), any(), eq(5));
        }

        @Test
        @DisplayName("Creates new run when only a STEP_BY_STEP live run exists but AUTOMATIC requested (mode-scoped lookup)")
        void stepByStepToAutoCreatesNewRun() {
            when(versionService.resolveContentVersionForExecution(eq(WORKFLOW_ID), any(), eq(TENANT_ID))).thenReturn(5);

            // A live STEP_BY_STEP run exists at this version, but the AUTOMATIC-scoped lookup finds nothing.
            WorkflowRunEntity existingRun = mock(WorkflowRunEntity.class);
            lenient().when(existingRun.getRunIdPublic()).thenReturn("run-sbs");
            lenient().when(runRepository.findFirstByWorkflowIdAndPlanVersionAndExecutionModeAndStatusInOrderByStartedAtDesc(
                    WORKFLOW_ID, 5, ExecutionMode.STEP_BY_STEP, REUSABLE_STATUSES))
                    .thenReturn(Optional.of(existingRun));
            when(runRepository.findFirstByWorkflowIdAndPlanVersionAndExecutionModeAndStatusInOrderByStartedAtDesc(
                    WORKFLOW_ID, 5, ExecutionMode.AUTOMATIC, REUSABLE_STATUSES))
                    .thenReturn(Optional.empty());

            // New run creation
            WorkflowExecution execution = mock(WorkflowExecution.class);
            when(execution.getRunId()).thenReturn(RUN_ID);
            when(executionService.createExecution(eq(plan), any(), eq(5))).thenReturn(execution);

            WorkflowRunEntity newRun = mock(WorkflowRunEntity.class);
            when(newRun.getMetadata()).thenReturn(null);
            when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(newRun));

            // Request AUTOMATIC
            EditorRunResolver.Resolution result = resolver.findOrCreateRun(
                    workflow, plan, Map.of(), TENANT_ID, ExecutionMode.AUTOMATIC);

            assertThat(result.reused()).isFalse();
            assertThat(result.runEntity()).isSameAs(newRun);
            assertThat(result.execution()).isSameAs(execution);
            verify(executionService).createExecution(eq(plan), any(), eq(5));
        }

        @Test
        @DisplayName("Reuses run when mode matches STEP_BY_STEP")
        void reusesWhenBothStepByStep() {
            when(versionService.resolveContentVersionForExecution(eq(WORKFLOW_ID), any(), eq(TENANT_ID))).thenReturn(5);

            WorkflowRunEntity existingRun = mock(WorkflowRunEntity.class);
            when(existingRun.getRunIdPublic()).thenReturn("run-sbs");
            when(runRepository.findFirstByWorkflowIdAndPlanVersionAndExecutionModeAndStatusInOrderByStartedAtDesc(
                    WORKFLOW_ID, 5, ExecutionMode.STEP_BY_STEP, REUSABLE_STATUSES))
                    .thenReturn(Optional.of(existingRun));

            EditorRunResolver.Resolution result = resolver.findOrCreateRun(
                    workflow, plan, Map.of(), TENANT_ID, ExecutionMode.STEP_BY_STEP);

            assertThat(result.reused()).isTrue();
            assertThat(result.runEntity()).isSameAs(existingRun);
            assertThat(result.execution()).isNull();
            verify(executionService, never()).createExecution(any(), any(), anyInt());
        }

        @Test
        @DisplayName("Automatic re-execute reuses its live AUTOMATIC run even when a newer STEP_BY_STEP run exists (regression: auto/SBS alternation minted a run per flip)")
        void autoReexecuteFindsItsRunBehindNewerSbsRun() {
            // e2e-exposed flaw: a latest-run-only lookup saw the newer SBS run, judged
            // it a mode mismatch and minted a THIRD run, even though the original
            // automatic run was still live at the same version. The mode-scoped query
            // returns the automatic run regardless of newer runs in other modes.
            when(versionService.resolveContentVersionForExecution(eq(WORKFLOW_ID), any(), eq(TENANT_ID))).thenReturn(5);

            WorkflowRunEntity autoRun = mock(WorkflowRunEntity.class);
            when(autoRun.getRunIdPublic()).thenReturn("run-auto");
            when(runRepository.findFirstByWorkflowIdAndPlanVersionAndExecutionModeAndStatusInOrderByStartedAtDesc(
                    WORKFLOW_ID, 5, ExecutionMode.AUTOMATIC, REUSABLE_STATUSES))
                    .thenReturn(Optional.of(autoRun));

            EditorRunResolver.Resolution result = resolver.findOrCreateRun(
                    workflow, plan, Map.of(), TENANT_ID, ExecutionMode.AUTOMATIC);

            assertThat(result.reused()).isTrue();
            assertThat(result.runEntity()).isSameAs(autoRun);
            verify(executionService, never()).createExecution(any(), any(), anyInt());
        }
    }
}
