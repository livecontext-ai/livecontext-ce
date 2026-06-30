package com.apimarketplace.orchestrator.services;

import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.ExecutionMode;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Verifies the version-replay semantics of {@link EditorRunResolver}:
 *
 * <ul>
 *   <li>{@code findOrCreateRunForVersion} REUSE must guarantee the run truly carries
 *       the stored version-N content and the {@code __versionReplay__} flag - the
 *       reused run may have been created by plain {@code findOrCreateRun} (no flag)
 *       or carry a plan that drifted via legacy in-run edits. Without re-freezing,
 *       the passive-fire refresh in ReusableTriggerService would swap in the latest
 *       canvas plan and the "replay" would execute the wrong version.</li>
 *   <li>{@code findOrCreateRun} (current-canvas) REUSE must clear a leftover
 *       {@code __versionReplay__} flag, otherwise the run would skip workflow.plan
 *       propagation forever.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EditorRunResolver - version replay reuse semantics")
class EditorRunResolverVersionReplayTest {

    @Mock private WorkflowPlanVersionService versionService;
    @Mock private WorkflowExecutionService executionService;
    @Mock private WorkflowRunRepository runRepository;

    private EditorRunResolver resolver;

    private static final UUID WORKFLOW_ID = UUID.randomUUID();
    private static final String TENANT_ID = "tenant-1";
    // Mirror of EditorRunResolver.REUSABLE_STATUSES - the live-run reuse window.
    private static final List<RunStatus> REUSABLE_STATUSES =
            List.of(RunStatus.WAITING_TRIGGER, RunStatus.RUNNING, RunStatus.PAUSED);
    private static final String RUN_ID = "run-replay-reuse-1";

    @BeforeEach
    void setUp() {
        resolver = new EditorRunResolver(versionService, executionService, runRepository);
    }

    @Test
    @DisplayName("Replay reuse of an unflagged run re-freezes the plan to the stored version content and stamps __versionReplay__")
    void replayReuseStampsFlagAndRefreezesPlan() {
        // Bug: findOrCreateRunForVersion reused the WAITING_TRIGGER run found at
        // version N as-is. A run created by plain findOrCreateRun carries no
        // __versionReplay__ flag (and may carry a drifted plan), so the subsequent
        // passive fire refreshed it to the latest canvas plan - execute(version=N)
        // silently executed the wrong version.
        Map<String, Object> storedV5Content = planMap("v5-url");
        Map<String, Object> driftedRunPlan = planMap("drifted-url");
        WorkflowPlan versionedPlan = WorkflowPlan.fromMap(storedV5Content, WORKFLOW_ID.toString(), TENANT_ID);

        WorkflowRunEntity existing = new WorkflowRunEntity();
        existing.setRunIdPublic(RUN_ID);
        existing.setStatus(RunStatus.WAITING_TRIGGER);
        existing.setExecutionMode(ExecutionMode.AUTOMATIC);
        existing.setTenantId(TENANT_ID);
        existing.setPlanVersion(5);
        existing.setPlan(new HashMap<>(driftedRunPlan));
        existing.setMetadata(new HashMap<>(Map.of("__editorRun__", Boolean.TRUE)));

        when(runRepository.findFirstByWorkflowIdAndPlanVersionAndExecutionModeAndStatusInOrderByStartedAtDesc(
                WORKFLOW_ID, 5, ExecutionMode.AUTOMATIC, REUSABLE_STATUSES)).thenReturn(Optional.of(existing));

        EditorRunResolver.Resolution resolution = resolver.findOrCreateRunForVersion(
                workflowEntity(), versionedPlan, 5, Map.of(), TENANT_ID, ExecutionMode.AUTOMATIC);

        assertThat(resolution.reused()).isTrue();
        assertThat(resolution.runEntity().getMetadata()).containsEntry("__versionReplay__", 5);
        Map<?, ?> params = (Map<?, ?>) ((Map<?, ?>) ((List<?>) resolution.runEntity().getPlan().get("mcps")).get(0)).get("params");
        assertThat(params.get("url")).isEqualTo("v5-url");
        verify(runRepository).save(existing);
        verify(executionService, never()).createExecution(any(), any(), any());
    }

    @Test
    @DisplayName("Replay reuse is refused when the run's plan structurally drifted from the stored version content - fresh run instead")
    void replayReuseFallsBackToNewRunWhenTopologyIncompatible() {
        // StateSnapshot indexes per-node counters by node id: re-freezing a
        // structurally different plan onto a populated run would corrupt it.
        // Legacy rows (pre content-true stamping) can carry such drift.
        Map<String, Object> storedV5Content = planMap("v5-url");
        Map<String, Object> structurallyDriftedRunPlan = planMapWithExtraNode();
        WorkflowPlan versionedPlan = WorkflowPlan.fromMap(storedV5Content, WORKFLOW_ID.toString(), TENANT_ID);

        WorkflowRunEntity existing = new WorkflowRunEntity();
        existing.setRunIdPublic(RUN_ID);
        existing.setStatus(RunStatus.WAITING_TRIGGER);
        existing.setExecutionMode(ExecutionMode.AUTOMATIC);
        existing.setTenantId(TENANT_ID);
        existing.setPlanVersion(5);
        existing.setPlan(new HashMap<>(structurallyDriftedRunPlan));
        existing.setMetadata(new HashMap<>(Map.of("__editorRun__", Boolean.TRUE)));

        when(runRepository.findFirstByWorkflowIdAndPlanVersionAndExecutionModeAndStatusInOrderByStartedAtDesc(
                WORKFLOW_ID, 5, ExecutionMode.AUTOMATIC, REUSABLE_STATUSES)).thenReturn(Optional.of(existing));

        com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution execution =
                mock(com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution.class);
        when(execution.getRunId()).thenReturn("run-fresh-replay");
        when(executionService.createExecution(versionedPlan, Map.of(), 5)).thenReturn(execution);
        WorkflowRunEntity fresh = new WorkflowRunEntity();
        fresh.setRunIdPublic("run-fresh-replay");
        fresh.setTenantId(TENANT_ID);
        when(runRepository.findByRunIdPublic("run-fresh-replay")).thenReturn(Optional.of(fresh));

        EditorRunResolver.Resolution resolution = resolver.findOrCreateRunForVersion(
                workflowEntity(), versionedPlan, 5, Map.of(), TENANT_ID, ExecutionMode.AUTOMATIC);

        assertThat(resolution.reused()).isFalse();
        assertThat(resolution.runEntity().getRunIdPublic()).isEqualTo("run-fresh-replay");
        assertThat(resolution.runEntity().getMetadata()).containsEntry("__versionReplay__", 5);
        // The drifted run was left untouched.
        List<?> mcps = (List<?>) existing.getPlan().get("mcps");
        assertThat(mcps).hasSize(2);
        verify(runRepository, never()).save(existing);
    }

    @Test
    @DisplayName("Current-canvas reuse clears a leftover __versionReplay__ flag so workflow.plan propagation resumes")
    void currentModeReuseClearsReplayFlag() {
        Map<String, Object> canvasPlan = planMap("current-url");
        WorkflowPlan plan = WorkflowPlan.fromMap(canvasPlan, WORKFLOW_ID.toString(), TENANT_ID);

        WorkflowRunEntity existing = new WorkflowRunEntity();
        existing.setRunIdPublic(RUN_ID);
        existing.setStatus(RunStatus.WAITING_TRIGGER);
        existing.setExecutionMode(ExecutionMode.AUTOMATIC);
        existing.setTenantId(TENANT_ID);
        existing.setPlanVersion(5);
        existing.setPlan(new HashMap<>(canvasPlan));
        Map<String, Object> flagged = new HashMap<>();
        flagged.put("__editorRun__", Boolean.TRUE);
        flagged.put("__versionReplay__", 5);
        existing.setMetadata(flagged);

        when(versionService.resolveContentVersionForExecution(WORKFLOW_ID, canvasPlan, TENANT_ID)).thenReturn(5);
        when(runRepository.findFirstByWorkflowIdAndPlanVersionAndExecutionModeAndStatusInOrderByStartedAtDesc(
                WORKFLOW_ID, 5, ExecutionMode.AUTOMATIC, REUSABLE_STATUSES)).thenReturn(Optional.of(existing));

        EditorRunResolver.Resolution resolution = resolver.findOrCreateRun(
                workflowEntity(), plan, Map.of(), TENANT_ID, ExecutionMode.AUTOMATIC);

        assertThat(resolution.reused()).isTrue();
        assertThat(resolution.runEntity().getMetadata()).doesNotContainKey("__versionReplay__");
        assertThat(resolution.runEntity().getMetadata()).containsEntry("__editorRun__", Boolean.TRUE);
        verify(runRepository).save(existing);
    }

    @Test
    @DisplayName("Current-canvas reuse of an unflagged run does not touch metadata (no gratuitous save)")
    void currentModeReuseWithoutFlagDoesNotResave() {
        Map<String, Object> canvasPlan = planMap("current-url");
        WorkflowPlan plan = WorkflowPlan.fromMap(canvasPlan, WORKFLOW_ID.toString(), TENANT_ID);

        WorkflowRunEntity existing = new WorkflowRunEntity();
        existing.setRunIdPublic(RUN_ID);
        existing.setStatus(RunStatus.WAITING_TRIGGER);
        existing.setExecutionMode(ExecutionMode.AUTOMATIC);
        existing.setTenantId(TENANT_ID);
        existing.setPlanVersion(5);
        existing.setPlan(new HashMap<>(canvasPlan));
        existing.setMetadata(new HashMap<>(Map.of("__editorRun__", Boolean.TRUE)));

        when(versionService.resolveContentVersionForExecution(WORKFLOW_ID, canvasPlan, TENANT_ID)).thenReturn(5);
        when(runRepository.findFirstByWorkflowIdAndPlanVersionAndExecutionModeAndStatusInOrderByStartedAtDesc(
                WORKFLOW_ID, 5, ExecutionMode.AUTOMATIC, REUSABLE_STATUSES)).thenReturn(Optional.of(existing));

        EditorRunResolver.Resolution resolution = resolver.findOrCreateRun(
                workflowEntity(), plan, Map.of(), TENANT_ID, ExecutionMode.AUTOMATIC);

        assertThat(resolution.reused()).isTrue();
        verify(runRepository, never()).save(any());
    }

    @Test
    @DisplayName("Replay reuse also targets a RUNNING run mid-epoch (regression: duplicate same-version run on mid-epoch fire)")
    void replayReusesRunningRunMidEpoch() {
        // Same live-window regression as findOrCreateRun: a replay fired while the
        // previous epoch was still in flight (RUNNING/PAUSED) minted a duplicate
        // run at version N instead of accumulating an epoch on the live run.
        Map<String, Object> storedV5Content = planMap("v5-url");
        WorkflowPlan versionedPlan = WorkflowPlan.fromMap(storedV5Content, WORKFLOW_ID.toString(), TENANT_ID);

        WorkflowRunEntity existing = new WorkflowRunEntity();
        existing.setRunIdPublic(RUN_ID);
        existing.setStatus(RunStatus.RUNNING);
        existing.setExecutionMode(ExecutionMode.AUTOMATIC);
        existing.setTenantId(TENANT_ID);
        existing.setPlanVersion(5);
        existing.setPlan(new HashMap<>(storedV5Content));
        existing.setMetadata(new HashMap<>(Map.of("__editorRun__", Boolean.TRUE)));

        when(runRepository.findFirstByWorkflowIdAndPlanVersionAndExecutionModeAndStatusInOrderByStartedAtDesc(
                WORKFLOW_ID, 5, ExecutionMode.AUTOMATIC, REUSABLE_STATUSES)).thenReturn(Optional.of(existing));

        EditorRunResolver.Resolution resolution = resolver.findOrCreateRunForVersion(
                workflowEntity(), versionedPlan, 5, Map.of(), TENANT_ID, ExecutionMode.AUTOMATIC);

        assertThat(resolution.reused()).isTrue();
        assertThat(resolution.runEntity()).isSameAs(existing);
        assertThat(resolution.runEntity().getMetadata()).containsEntry("__versionReplay__", 5);
        verify(executionService, never()).createExecution(any(), any(), any());
    }

    @Test
    @DisplayName("Replay reuse also targets a PAUSED run blocked on a signal (regression: duplicate same-version run on mid-epoch fire)")
    void replayReusesPausedRunBlockedOnSignal() {
        Map<String, Object> storedV5Content = planMap("v5-url");
        WorkflowPlan versionedPlan = WorkflowPlan.fromMap(storedV5Content, WORKFLOW_ID.toString(), TENANT_ID);

        WorkflowRunEntity existing = new WorkflowRunEntity();
        existing.setRunIdPublic(RUN_ID);
        existing.setStatus(RunStatus.PAUSED);
        existing.setExecutionMode(ExecutionMode.AUTOMATIC);
        existing.setTenantId(TENANT_ID);
        existing.setPlanVersion(5);
        existing.setPlan(new HashMap<>(storedV5Content));
        existing.setMetadata(new HashMap<>(Map.of("__editorRun__", Boolean.TRUE)));

        when(runRepository.findFirstByWorkflowIdAndPlanVersionAndExecutionModeAndStatusInOrderByStartedAtDesc(
                WORKFLOW_ID, 5, ExecutionMode.AUTOMATIC, REUSABLE_STATUSES)).thenReturn(Optional.of(existing));

        EditorRunResolver.Resolution resolution = resolver.findOrCreateRunForVersion(
                workflowEntity(), versionedPlan, 5, Map.of(), TENANT_ID, ExecutionMode.AUTOMATIC);

        assertThat(resolution.reused()).isTrue();
        assertThat(resolution.runEntity()).isSameAs(existing);
        assertThat(resolution.runEntity().getMetadata()).containsEntry("__versionReplay__", 5);
        verify(executionService, never()).createExecution(any(), any(), any());
    }

    // Helpers

    private WorkflowEntity workflowEntity() {
        WorkflowEntity workflow = new WorkflowEntity();
        workflow.setId(WORKFLOW_ID);
        workflow.setTenantId(TENANT_ID);
        workflow.setName("Test Workflow");
        return workflow;
    }

    private Map<String, Object> planMapWithExtraNode() {
        Map<String, Object> base = planMap("v5-url");
        Map<String, Object> extra = new HashMap<>();
        extra.put("label", "Step B");
        extra.put("service", "http");
        extra.put("action", "get");
        extra.put("params", new HashMap<>());
        base.put("mcps", List.of(((List<?>) base.get("mcps")).get(0), extra));
        base.put("edges", List.of(
                Map.of("from", "trigger:my_webhook", "to", "mcp:step_a"),
                Map.of("from", "mcp:step_a", "to", "mcp:step_b")));
        return base;
    }

    private Map<String, Object> planMap(String url) {
        Map<String, Object> step = new HashMap<>();
        step.put("label", "Step A");
        step.put("service", "http");
        step.put("action", "get");
        step.put("params", new HashMap<>(Map.of("url", url)));

        Map<String, Object> plan = new HashMap<>();
        plan.put("id", WORKFLOW_ID.toString());
        plan.put("triggers", List.of(Map.of("type", "webhook", "label", "my_webhook")));
        plan.put("mcps", List.of(step));
        plan.put("agents", List.of());
        plan.put("cores", List.of());
        plan.put("tables", List.of());
        plan.put("interfaces", List.of());
        plan.put("edges", List.of(Map.of("from", "trigger:my_webhook", "to", "mcp:step_a")));
        return plan;
    }
}
