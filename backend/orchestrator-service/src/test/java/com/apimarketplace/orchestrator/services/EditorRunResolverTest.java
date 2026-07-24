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
import org.mockito.ArgumentCaptor;
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

        @Test
        @DisplayName("Regression 2026-07-20: an editor/agent fire never adopts the live production run - it gets its own run, production metadata untouched")
        void editorFireNeverAdoptsTheProductionRun() {
            // This test was previously the INVERSE - it characterized the hazard as
            // current behaviour with an explicit instruction to invert it once closed.
            // This is that inversion.
            //
            // Reuse is keyed on (workflowId, planVersion, mode, live status) with no
            // notion of run ownership, so on a pinned workflow an editor or AGENT fire
            // whose canvas equals the pinned content matches the live PRODUCTION run
            // exactly. Adopting it applied the reuse block to production state: the
            // mock override in particular would make the next SCHEDULED fire mock its
            // tools. The resolver now recognises the production run via the
            // production_run_id FK and refuses to adopt it.
            when(versionService.resolveContentVersionForExecution(eq(WORKFLOW_ID), any(), eq(TENANT_ID))).thenReturn(17);

            // A live PRODUCTION run at the pinned version, pointed at by the FK.
            UUID productionRunId = UUID.randomUUID();
            WorkflowRunEntity productionRun = mock(WorkflowRunEntity.class);
            lenient().when(productionRun.getRunIdPublic()).thenReturn("run-production");
            when(productionRun.getId()).thenReturn(productionRunId);
            when(workflow.getProductionRunId()).thenReturn(productionRunId);
            when(runRepository.findFirstByWorkflowIdAndPlanVersionAndExecutionModeAndStatusInOrderByStartedAtDesc(
                    WORKFLOW_ID, 17, ExecutionMode.AUTOMATIC, REUSABLE_STATUSES))
                    .thenReturn(Optional.of(productionRun));

            WorkflowRunEntity freshRun = mock(WorkflowRunEntity.class);
            lenient().when(freshRun.getMetadata()).thenReturn(null);
            WorkflowExecution execution = mock(WorkflowExecution.class);
            when(execution.getRunId()).thenReturn("run-editor-fresh");
            when(executionService.createExecution(any(), any(), anyInt())).thenReturn(execution);
            when(runRepository.findByRunIdPublic("run-editor-fresh")).thenReturn(Optional.of(freshRun));

            // The editor/agent fires WITH a mock override - the mutation that mattered.
            EditorRunResolver.Resolution result = resolver.findOrCreateRun(
                    workflow, plan, Map.of(), TENANT_ID, ExecutionMode.AUTOMATIC, "all_mcp");

            // A SEPARATE run is created instead of adopting production.
            assertThat(result.reused()).isFalse();
            assertThat(result.runEntity()).isSameAs(freshRun);
            verify(executionService).createExecution(any(), any(), anyInt());

            // THE FIX: production's metadata is never written. Pre-fix the mock
            // override landed here and production mocked its tools on the next fire.
            verify(productionRun, never()).setMetadata(any());

            // The editor's own run carries the flags, including the mock override.
            ArgumentCaptor<Map<String, Object>> metaCaptor = ArgumentCaptor.forClass(Map.class);
            verify(freshRun).setMetadata(metaCaptor.capture());
            assertThat(metaCaptor.getValue())
                    .containsEntry("__editorRun__", true)
                    .containsEntry(
                            com.apimarketplace.orchestrator.execution.v2.engine.MockRunGate.MOCK_MODE_METADATA_KEY,
                            "all_mcp");
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
    @DisplayName("Version replay - production isolation")
    class ReplayProductionIsolationTests {

        @Mock private WorkflowEntity workflow;
        @Mock private WorkflowPlan versionedPlan;

        @BeforeEach
        void initWorkflow() {
            lenient().when(workflow.getId()).thenReturn(WORKFLOW_ID);
            lenient().when(versionedPlan.getOriginalPlan()).thenReturn(Map.of("name", "test"));
        }

        @Test
        @DisplayName("Regression 2026-07-20: a version replay never adopts the live production run")
        void replayNeverAdoptsTheProductionRun() {
            // The replay lane is the worse half of the hazard: adopting production
            // would re-freeze its plan to the replayed version AND stamp
            // __versionReplay__, which makes ReusableTriggerService stop propagating
            // workflow.plan to it - freezing production on an old version indefinitely.
            UUID productionRunId = UUID.randomUUID();
            WorkflowRunEntity productionRun = mock(WorkflowRunEntity.class);
            lenient().when(productionRun.getRunIdPublic()).thenReturn("run-production");
            when(productionRun.getId()).thenReturn(productionRunId);
            when(workflow.getProductionRunId()).thenReturn(productionRunId);
            when(runRepository.findFirstByWorkflowIdAndPlanVersionAndExecutionModeAndStatusInOrderByStartedAtDesc(
                    WORKFLOW_ID, 17, ExecutionMode.AUTOMATIC, REUSABLE_STATUSES))
                    .thenReturn(Optional.of(productionRun));

            WorkflowRunEntity freshRun = mock(WorkflowRunEntity.class);
            lenient().when(freshRun.getMetadata()).thenReturn(null);
            WorkflowExecution execution = mock(WorkflowExecution.class);
            when(execution.getRunId()).thenReturn("run-replay-fresh");
            when(executionService.createExecution(any(), any(), anyInt())).thenReturn(execution);
            when(runRepository.findByRunIdPublic("run-replay-fresh")).thenReturn(Optional.of(freshRun));

            EditorRunResolver.Resolution result = resolver.findOrCreateRunForVersion(
                    workflow, versionedPlan, 17, Map.of(), TENANT_ID, ExecutionMode.AUTOMATIC);

            assertThat(result.reused()).isFalse();
            assertThat(result.runEntity()).isSameAs(freshRun);
            // Production keeps its plan and its metadata: neither re-frozen nor
            // stamped __versionReplay__.
            verify(productionRun, never()).setPlan(any());
            verify(productionRun, never()).setMetadata(any());
        }
    }

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

    @Nested
    @DisplayName("Mock-mode reconciliation (__mockMode__ run metadata)")
    class MockModeReconciliationTests {

        @Mock private WorkflowEntity workflow;
        @Mock private WorkflowPlan plan;

        @BeforeEach
        void initWorkflow() {
            lenient().when(workflow.getId()).thenReturn(WORKFLOW_ID);
            lenient().when(plan.getOriginalPlan()).thenReturn(Map.of("name", "test"));
        }

        private WorkflowRunEntity stubNewRunCreation() {
            WorkflowExecution execution = mock(WorkflowExecution.class);
            when(execution.getRunId()).thenReturn(RUN_ID);
            when(executionService.createExecution(eq(plan), any(), anyInt())).thenReturn(execution);
            WorkflowRunEntity newRun = mock(WorkflowRunEntity.class);
            when(newRun.getMetadata()).thenReturn(null);
            when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(newRun));
            return newRun;
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> capturedMetadata(WorkflowRunEntity run) {
            org.mockito.ArgumentCaptor<Map<String, Object>> captor =
                    org.mockito.ArgumentCaptor.forClass(Map.class);
            verify(run, atLeastOnce()).setMetadata(captor.capture());
            return captor.getValue();
        }

        @Test
        @DisplayName("mockMode='off' is stamped as __mockMode__ on a NEW run (alongside __editorRun__)")
        void offModeStampedOnCreate() {
            when(versionService.resolveContentVersionForExecution(eq(WORKFLOW_ID), any(), eq(TENANT_ID))).thenReturn(5);
            when(runRepository.findFirstByWorkflowIdAndPlanVersionAndExecutionModeAndStatusInOrderByStartedAtDesc(
                    eq(WORKFLOW_ID), eq(5), eq(ExecutionMode.AUTOMATIC), eq(REUSABLE_STATUSES)))
                    .thenReturn(Optional.empty());
            WorkflowRunEntity newRun = stubNewRunCreation();

            resolver.findOrCreateRun(workflow, plan, Map.of(), TENANT_ID, ExecutionMode.AUTOMATIC, "off");

            Map<String, Object> metadata = capturedMetadata(newRun);
            assertThat(metadata).containsEntry("__editorRun__", true)
                    .containsEntry("__mockMode__", "off");
        }

        @Test
        @DisplayName("default fire (no mockMode) leaves __mockMode__ ABSENT on a new run")
        void defaultFireHasNoMockModeKey() {
            when(versionService.resolveContentVersionForExecution(eq(WORKFLOW_ID), any(), eq(TENANT_ID))).thenReturn(5);
            when(runRepository.findFirstByWorkflowIdAndPlanVersionAndExecutionModeAndStatusInOrderByStartedAtDesc(
                    eq(WORKFLOW_ID), eq(5), eq(ExecutionMode.AUTOMATIC), eq(REUSABLE_STATUSES)))
                    .thenReturn(Optional.empty());
            WorkflowRunEntity newRun = stubNewRunCreation();

            resolver.findOrCreateRun(workflow, plan, Map.of(), TENANT_ID, ExecutionMode.AUTOMATIC);

            Map<String, Object> metadata = capturedMetadata(newRun);
            assertThat(metadata).containsEntry("__editorRun__", true)
                    .doesNotContainKey("__mockMode__");
        }

        @Test
        @DisplayName("RECONCILE on reuse: the fire request decides - a refire without mockMode REMOVES a stale __mockMode__")
        void refireWithoutModeRemovesStaleFlag() {
            when(versionService.resolveContentVersionForExecution(eq(WORKFLOW_ID), any(), eq(TENANT_ID))).thenReturn(5);
            WorkflowRunEntity existing = mock(WorkflowRunEntity.class);
            when(existing.getRunIdPublic()).thenReturn("run-existing");
            when(existing.getMetadata()).thenReturn(Map.of("__editorRun__", true, "__mockMode__", "all_mcp"));
            when(runRepository.findFirstByWorkflowIdAndPlanVersionAndExecutionModeAndStatusInOrderByStartedAtDesc(
                    WORKFLOW_ID, 5, ExecutionMode.AUTOMATIC, REUSABLE_STATUSES))
                    .thenReturn(Optional.of(existing));

            EditorRunResolver.Resolution result = resolver.findOrCreateRun(
                    workflow, plan, Map.of(), TENANT_ID, ExecutionMode.AUTOMATIC);

            assertThat(result.reused()).isTrue();
            Map<String, Object> metadata = capturedMetadata(existing);
            assertThat(metadata).doesNotContainKey("__mockMode__");
            verify(runRepository).save(existing);
        }

        @Test
        @DisplayName("RECONCILE on reuse: refire with mockMode='all_mcp' SETS the flag on the reused run")
        void refireWithModeSetsFlag() {
            when(versionService.resolveContentVersionForExecution(eq(WORKFLOW_ID), any(), eq(TENANT_ID))).thenReturn(5);
            WorkflowRunEntity existing = mock(WorkflowRunEntity.class);
            when(existing.getRunIdPublic()).thenReturn("run-existing");
            when(existing.getMetadata()).thenReturn(Map.of("__editorRun__", true));
            when(runRepository.findFirstByWorkflowIdAndPlanVersionAndExecutionModeAndStatusInOrderByStartedAtDesc(
                    WORKFLOW_ID, 5, ExecutionMode.AUTOMATIC, REUSABLE_STATUSES))
                    .thenReturn(Optional.of(existing));

            resolver.findOrCreateRun(workflow, plan, Map.of(), TENANT_ID, ExecutionMode.AUTOMATIC, "all_mcp");

            Map<String, Object> metadata = capturedMetadata(existing);
            assertThat(metadata).containsEntry("__mockMode__", "all_mcp");
        }

        @Test
        @DisplayName("reuse with an UNCHANGED mock state saves nothing (no gratuitous writes)")
        void unchangedStateSavesNothing() {
            when(versionService.resolveContentVersionForExecution(eq(WORKFLOW_ID), any(), eq(TENANT_ID))).thenReturn(5);
            WorkflowRunEntity existing = mock(WorkflowRunEntity.class);
            when(existing.getRunIdPublic()).thenReturn("run-existing");
            when(existing.getMetadata()).thenReturn(Map.of("__editorRun__", true, "__mockMode__", "off"));
            when(runRepository.findFirstByWorkflowIdAndPlanVersionAndExecutionModeAndStatusInOrderByStartedAtDesc(
                    WORKFLOW_ID, 5, ExecutionMode.AUTOMATIC, REUSABLE_STATUSES))
                    .thenReturn(Optional.of(existing));

            resolver.findOrCreateRun(workflow, plan, Map.of(), TENANT_ID, ExecutionMode.AUTOMATIC, "off");

            verify(existing, never()).setMetadata(any());
            verify(runRepository, never()).save(existing);
        }

        @Test
        @DisplayName("invalid mockMode fails fast with a clear message listing the valid values")
        void invalidModeRejected() {
            assertThatThrownBy(() -> resolver.findOrCreateRun(
                    workflow, plan, Map.of(), TENANT_ID, ExecutionMode.AUTOMATIC, "yolo"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("yolo")
                    .hasMessageContaining("all_mcp");
        }

        @Test
        @DisplayName("'default' and blank normalize to no-override (key absent)")
        void defaultAndBlankNormalize() {
            when(versionService.resolveContentVersionForExecution(eq(WORKFLOW_ID), any(), eq(TENANT_ID))).thenReturn(5);
            when(runRepository.findFirstByWorkflowIdAndPlanVersionAndExecutionModeAndStatusInOrderByStartedAtDesc(
                    eq(WORKFLOW_ID), eq(5), eq(ExecutionMode.AUTOMATIC), eq(REUSABLE_STATUSES)))
                    .thenReturn(Optional.empty());
            WorkflowRunEntity newRun = stubNewRunCreation();

            resolver.findOrCreateRun(workflow, plan, Map.of(), TENANT_ID, ExecutionMode.AUTOMATIC, "Default");

            assertThat(capturedMetadata(newRun)).doesNotContainKey("__mockMode__");
        }
    }
}
