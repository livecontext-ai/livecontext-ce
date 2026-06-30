package com.apimarketplace.orchestrator.services.resume;

import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.services.UnifiedSignalService;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.WorkflowExecutionService;
import com.apimarketplace.orchestrator.services.WorkflowPersistenceService;
import com.apimarketplace.orchestrator.services.WorkflowPlanVersionService;
import com.apimarketplace.orchestrator.services.WorkflowStreamingService;
import com.apimarketplace.orchestrator.services.cache.RunCacheRegistry;
import com.apimarketplace.orchestrator.services.resume.cache.WorkflowCacheManager;
import com.apimarketplace.orchestrator.services.resume.state.StateReconstructor;
import com.apimarketplace.orchestrator.services.state.StateSnapshotService;
import com.apimarketplace.orchestrator.services.streaming.state.RunStateStore;
import com.apimarketplace.orchestrator.trigger.TriggerEpochManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link WorkflowResumeService#updateRunPlan} and
 * {@link WorkflowResumeService#refreshPlanFromWorkflowDefinition} - both share
 * the topology guard that prevents corrupting {@code StateSnapshot} when a
 * live-edited plan changes node-ids or edges.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowResumeService - plan update/refresh topology guard")
class WorkflowResumeServicePlanUpdateTest {

    @Mock private WorkflowRunRepository runRepository;
    @Mock private WorkflowExecutionService executionService;
    @Mock private WorkflowPersistenceService persistenceService;
    @Mock private WorkflowStreamingService streamingService;
    @Mock private RunStateStore runStateStore;
    @Mock private WorkflowCacheManager cacheManager;
    @Mock private StateReconstructor stateReconstructor;
    @Mock private RunCacheRegistry cacheRegistry;
    @Mock private ExecutionContextManager contextManager;
    @Mock private StepByStepExecutor stepByStepExecutor;
    @Mock private TriggerEpochManager epochManager;
    @Mock private StateSnapshotService stateSnapshotService;
    @Mock private UnifiedSignalService unifiedSignalService;
    @Mock private WorkflowPlanVersionService planVersionService;

    private WorkflowResumeService service;

    private static final String RUN_ID = "run-plan-update-1";
    private static final String TENANT_ID = "tenant-1";

    @BeforeEach
    void setUp() throws Exception {
        service = new WorkflowResumeService(
                runRepository, executionService, persistenceService,
                streamingService, runStateStore,
                cacheManager, stateReconstructor, cacheRegistry,
                contextManager, stepByStepExecutor,
                epochManager, stateSnapshotService
        );
        Field signalField = WorkflowResumeService.class.getDeclaredField("unifiedSignalService");
        signalField.setAccessible(true);
        signalField.set(service, unifiedSignalService);
    }

    @Test
    @DisplayName("updateRunPlan - topology-compatible params change is persisted")
    void updateRunPlanPersistsCompatibleChange() {
        Map<String, Object> frozen = planWith(List.of(mcp("Fetch", Map.of("url", "v1"))),
                List.of(edge("trigger:start", "mcp:fetch")));
        Map<String, Object> updated = planWith(List.of(mcp("Fetch", Map.of("url", "v2"))),
                List.of(edge("trigger:start", "mcp:fetch")));

        WorkflowRunEntity run = runEntity(frozen);
        when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));

        WorkflowPlan result = service.updateRunPlan(RUN_ID, updated);

        // New plan persisted.
        verify(runRepository).save(run);
        Object stepA = ((List<?>) run.getPlan().get("mcps")).get(0);
        Map<?, ?> params = (Map<?, ?>) ((Map<?, ?>) stepA).get("params");
        assertThat(params.get("url")).isEqualTo("v2");
        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("updateRunPlan - topology-incompatible payload is rejected, frozen plan preserved")
    void updateRunPlanRejectsTopologyChange() {
        Map<String, Object> frozen = planWith(List.of(mcp("Fetch", Map.of())),
                List.of(edge("trigger:start", "mcp:fetch")));
        Map<String, Object> withAddedNode = planWith(
                List.of(mcp("Fetch", Map.of()), mcp("Malicious", Map.of())),
                List.of(edge("trigger:start", "mcp:fetch"), edge("mcp:fetch", "mcp:malicious")));

        WorkflowRunEntity run = runEntity(frozen);
        when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));

        WorkflowPlan result = service.updateRunPlan(RUN_ID, withAddedNode);

        // Frozen plan preserved - no save, single mcp node still.
        verify(runRepository, never()).save(any());
        List<?> mcps = (List<?>) run.getPlan().get("mcps");
        assertThat(mcps).hasSize(1);
        // Returns null on rejection so callers (e.g. TriggerController) can
        // distinguish "wrote the new plan" from "kept the frozen plan" and
        // avoid tagging PLAN_FROM_PAYLOAD_MARKER on a rejected payload -
        // otherwise executeTriggerInternal would suppress the workflow.plan
        // refresh and run on stale data.
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("refreshPlanFromWorkflowDefinition - topology-incompatible live plan is rejected")
    void refreshPlanRejectsTopologyChange() {
        Map<String, Object> frozen = planWith(List.of(mcp("Fetch", Map.of())),
                List.of(edge("trigger:start", "mcp:fetch")));
        Map<String, Object> workflowLive = planWith(
                List.of(mcp("Fetch", Map.of()), mcp("NewStep", Map.of())),
                List.of(edge("trigger:start", "mcp:fetch"), edge("mcp:fetch", "mcp:newstep")));

        WorkflowEntity workflow = workflowEntity(workflowLive);
        WorkflowRunEntity run = runEntity(frozen);
        org.springframework.test.util.ReflectionTestUtils.setField(run, "workflow", workflow);
        when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));

        WorkflowPlan result = service.refreshPlanFromWorkflowDefinition(RUN_ID);

        // Frozen plan preserved - no save since live plan was rejected.
        verify(runRepository, never()).save(any());
        List<?> mcps = (List<?>) run.getPlan().get("mcps");
        assertThat(mcps).hasSize(1);
        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("refreshPlanFromWorkflowDefinition - compatible live plan is applied")
    void refreshPlanAppliesCompatibleChange() {
        Map<String, Object> frozen = planWith(List.of(mcp("Fetch", Map.of("url", "old"))),
                List.of(edge("trigger:start", "mcp:fetch")));
        Map<String, Object> workflowLive = planWith(List.of(mcp("Fetch", Map.of("url", "new"))),
                List.of(edge("trigger:start", "mcp:fetch")));

        WorkflowEntity workflow = workflowEntity(workflowLive);
        WorkflowRunEntity run = runEntity(frozen);
        org.springframework.test.util.ReflectionTestUtils.setField(run, "workflow", workflow);
        when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));

        service.refreshPlanFromWorkflowDefinition(RUN_ID);

        verify(runRepository).save(run);
        Object stepA = ((List<?>) run.getPlan().get("mcps")).get(0);
        Map<?, ?> params = (Map<?, ?>) ((Map<?, ?>) stepA).get("params");
        assertThat(params.get("url")).isEqualTo("new");
    }

    @Test
    @DisplayName("updateRunPlan - pinned workflow + non-editor run: payload write is refused, run.plan untouched")
    void updateRunPlanRefusesOnPinnedNonEditorRun() {
        Map<String, Object> frozen = planWith(List.of(mcp("Fetch", Map.of("url", "v1"))),
                List.of(edge("trigger:start", "mcp:fetch")));
        Map<String, Object> attemptedEdit = planWith(List.of(mcp("Fetch", Map.of("url", "v2-malicious"))),
                List.of(edge("trigger:start", "mcp:fetch")));

        WorkflowEntity workflow = workflowEntity(frozen);
        workflow.setPinnedVersion(7);
        WorkflowRunEntity run = runEntity(frozen);
        org.springframework.test.util.ReflectionTestUtils.setField(run, "workflow", workflow);
        // Non-editor: metadata empty (default behavior).
        when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));

        WorkflowPlan result = service.updateRunPlan(RUN_ID, attemptedEdit);

        // Refused → null return → controller will NOT tag the marker.
        // Run.plan untouched (no save).
        assertThat(result).isNull();
        verify(runRepository, never()).save(any());
        Map<?, ?> params = (Map<?, ?>) ((Map<?, ?>) ((List<?>) run.getPlan().get("mcps")).get(0)).get("params");
        assertThat(params.get("url")).isEqualTo("v1");
    }

    @Test
    @DisplayName("updateRunPlan - pinned workflow + editor run: payload write is allowed")
    void updateRunPlanAllowsOnPinnedEditorRun() {
        Map<String, Object> frozen = planWith(List.of(mcp("Fetch", Map.of("url", "v1"))),
                List.of(edge("trigger:start", "mcp:fetch")));
        Map<String, Object> draftEdit = planWith(List.of(mcp("Fetch", Map.of("url", "v2"))),
                List.of(edge("trigger:start", "mcp:fetch")));

        WorkflowEntity workflow = workflowEntity(frozen);
        workflow.setPinnedVersion(7);
        WorkflowRunEntity run = runEntity(frozen);
        org.springframework.test.util.ReflectionTestUtils.setField(run, "workflow", workflow);
        Map<String, Object> editorMetadata = new HashMap<>();
        editorMetadata.put("__editorRun__", Boolean.TRUE);
        run.setMetadata(editorMetadata);
        when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));

        WorkflowPlan result = service.updateRunPlan(RUN_ID, draftEdit);

        // Editor runs may iterate against drafts even on pinned workflows.
        assertThat(result).isNotNull();
        verify(runRepository).save(run);
        Map<?, ?> params = (Map<?, ?>) ((Map<?, ?>) ((List<?>) run.getPlan().get("mcps")).get(0)).get("params");
        assertThat(params.get("url")).isEqualTo("v2");
    }

    // ====================================================================
    // run.planVersion ↔ workflow_plan_versions content-parity invariant
    // ====================================================================

    @Test
    @DisplayName("updateRunPlan - accepted payload is reconciled into the version history WITHOUT minting a new version (re-fire keeps the run's version stable)")
    void updateRunPlanResolvesVersionWithoutMinting() {
        // Bug (2026-06-11): every play/re-fire that carried a canvas plan minted an
        // "In-run edit" version - v21 → v22 on a simple epoch-2 replay. Runs must
        // resolve to the latest version (overwriting its content in place on drift),
        // never create a new number.
        Map<String, Object> frozen = planWith(List.of(mcp("Fetch", Map.of("url", "v1"))),
                List.of(edge("trigger:start", "mcp:fetch")));
        Map<String, Object> updated = planWith(List.of(mcp("Fetch", Map.of("url", "v2"))),
                List.of(edge("trigger:start", "mcp:fetch")));

        WorkflowRunEntity run = runEntity(frozen);
        run.setPlanVersion(5);
        UUID workflowId = run.getWorkflow().getId();
        when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));
        org.springframework.test.util.ReflectionTestUtils.setField(service, "planVersionService", planVersionService);
        when(planVersionService.resolveContentVersionForExecutionInNewTransaction(workflowId, updated, TENANT_ID)).thenReturn(5);

        WorkflowPlan result = service.updateRunPlan(RUN_ID, updated);

        assertThat(result).isNotNull();
        verify(planVersionService).resolveContentVersionForExecutionInNewTransaction(workflowId, updated, TENANT_ID);
        // Non-replay runs NEVER mint a version (pre-fix this was createVersion("In-run edit")).
        verify(planVersionService, never()).createVersionInNewTransaction(any(), any(), any(), any());
        assertThat(run.getPlanVersion()).isEqualTo(5);
        verify(runRepository).save(run);
    }

    @Test
    @DisplayName("updateRunPlan - rejected (topology-incompatible) payload creates no version and keeps the run's version")
    void updateRunPlanRejectedPayloadDoesNotCreateVersion() {
        Map<String, Object> frozen = planWith(List.of(mcp("Fetch", Map.of())),
                List.of(edge("trigger:start", "mcp:fetch")));
        Map<String, Object> withAddedNode = planWith(
                List.of(mcp("Fetch", Map.of()), mcp("Extra", Map.of())),
                List.of(edge("trigger:start", "mcp:fetch"), edge("mcp:fetch", "mcp:extra")));

        WorkflowRunEntity run = runEntity(frozen);
        run.setPlanVersion(5);
        when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));
        org.springframework.test.util.ReflectionTestUtils.setField(service, "planVersionService", planVersionService);

        WorkflowPlan result = service.updateRunPlan(RUN_ID, withAddedNode);

        assertThat(result).isNull();
        verify(planVersionService, never()).createVersionInNewTransaction(any(), any(), any(), any());
        verify(planVersionService, never()).resolveContentVersionForExecutionInNewTransaction(any(), any(), any());
        assertThat(run.getPlanVersion()).isEqualTo(5);
    }

    @Test
    @DisplayName("updateRunPlan - version service absent (narrow wiring): plan is written, planVersion keeps legacy behavior")
    void updateRunPlanWithoutVersionServiceKeepsLegacyBehavior() {
        Map<String, Object> frozen = planWith(List.of(mcp("Fetch", Map.of("url", "v1"))),
                List.of(edge("trigger:start", "mcp:fetch")));
        Map<String, Object> updated = planWith(List.of(mcp("Fetch", Map.of("url", "v2"))),
                List.of(edge("trigger:start", "mcp:fetch")));

        WorkflowRunEntity run = runEntity(frozen);
        run.setPlanVersion(5);
        when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));
        // planVersionService NOT injected.

        WorkflowPlan result = service.updateRunPlan(RUN_ID, updated);

        assertThat(result).isNotNull();
        verify(runRepository).save(run);
        assertThat(run.getPlanVersion()).isEqualTo(5);
    }

    @Test
    @DisplayName("updateRunPlan - version stamping failure still writes the plan and keeps the legacy version (availability over strictness)")
    void updateRunPlanVersionStampFailureStillWritesPlanWithLegacyVersion() {
        Map<String, Object> frozen = planWith(List.of(mcp("Fetch", Map.of("url", "v1"))),
                List.of(edge("trigger:start", "mcp:fetch")));
        Map<String, Object> updated = planWith(List.of(mcp("Fetch", Map.of("url", "v2"))),
                List.of(edge("trigger:start", "mcp:fetch")));

        WorkflowRunEntity run = runEntity(frozen);
        run.setPlanVersion(5);
        when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));
        org.springframework.test.util.ReflectionTestUtils.setField(service, "planVersionService", planVersionService);
        when(planVersionService.resolveContentVersionForExecutionInNewTransaction(any(), any(), any()))
                .thenThrow(new RuntimeException("version table unavailable"));

        WorkflowPlan result = service.updateRunPlan(RUN_ID, updated);

        // The fire must not be blocked by a versioning failure: the plan write goes
        // through (WARN logged), the run keeps its previous version stamp.
        assertThat(result).isNotNull();
        verify(runRepository).save(run);
        assertThat(run.getPlanVersion()).isEqualTo(5);
        Map<?, ?> params = (Map<?, ?>) ((Map<?, ?>) ((List<?>) run.getPlan().get("mcps")).get(0)).get("params");
        assertThat(params.get("url")).isEqualTo("v2");
    }

    @Test
    @DisplayName("updateRunPlan - accepted in-run edit on a replay run moves __versionReplay__ to the freshly stamped version")
    void updateRunPlanUpdatesReplayFlagToStampedVersion() {
        // A replay run that takes an in-run edit no longer replays its original
        // version; a stale flag value would make later passive fires claim to
        // replay N while frozen on M's content.
        Map<String, Object> frozen = planWith(List.of(mcp("Fetch", Map.of("url", "v3-url"))),
                List.of(edge("trigger:start", "mcp:fetch")));
        Map<String, Object> edited = planWith(List.of(mcp("Fetch", Map.of("url", "edited-url"))),
                List.of(edge("trigger:start", "mcp:fetch")));

        WorkflowRunEntity run = runEntity(frozen);
        run.setPlanVersion(3);
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("__editorRun__", Boolean.TRUE);
        metadata.put("__versionReplay__", 3);
        run.setMetadata(metadata);
        UUID workflowId = run.getWorkflow().getId();
        when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));
        org.springframework.test.util.ReflectionTestUtils.setField(service, "planVersionService", planVersionService);
        when(planVersionService.createVersionInNewTransaction(workflowId, edited, TENANT_ID, "In-run edit")).thenReturn(12);

        WorkflowPlan result = service.updateRunPlan(RUN_ID, edited);

        assertThat(result).isNotNull();
        assertThat(run.getPlanVersion()).isEqualTo(12);
        assertThat(run.getMetadata()).containsEntry("__versionReplay__", 12);
    }

    @Test
    @DisplayName("refreshPlanFromWorkflowDefinition - synced live plan re-stamps the run with its content-true version")
    void refreshPlanStampsVersionForSyncedContent() {
        // Bug: the SBS plan sync wrote run.plan = workflow.plan but left
        // run.planVersion at its creation-time value - version badge lied.
        Map<String, Object> frozen = planWith(List.of(mcp("Fetch", Map.of("url", "old"))),
                List.of(edge("trigger:start", "mcp:fetch")));
        Map<String, Object> workflowLive = planWith(List.of(mcp("Fetch", Map.of("url", "new"))),
                List.of(edge("trigger:start", "mcp:fetch")));

        WorkflowEntity workflow = workflowEntity(workflowLive);
        WorkflowRunEntity run = runEntity(frozen);
        run.setPlanVersion(4);
        org.springframework.test.util.ReflectionTestUtils.setField(run, "workflow", workflow);
        when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));
        org.springframework.test.util.ReflectionTestUtils.setField(service, "planVersionService", planVersionService);
        when(planVersionService.resolveContentVersionForExecutionInNewTransaction(workflow.getId(), workflowLive, TENANT_ID)).thenReturn(9);

        service.refreshPlanFromWorkflowDefinition(RUN_ID);

        verify(planVersionService).resolveContentVersionForExecutionInNewTransaction(workflow.getId(), workflowLive, TENANT_ID);
        verify(planVersionService, never()).createVersionInNewTransaction(any(), any(), any(), any());
        assertThat(run.getPlanVersion()).isEqualTo(9);
        verify(runRepository).save(run);
    }

    @Test
    @DisplayName("refreshPlanFromWorkflowDefinition - version-replay run keeps its frozen plan, never synced to the live definition")
    void refreshPlanKeepsFrozenPlanForVersionReplayRun() {
        // Bug companion to the ReusableTriggerService replay guard: a run created by
        // workflow(action='execute', version=N) must replay the frozen content even
        // when a passive refresh path (step-rerun, SBS) asks to sync from workflow.plan.
        Map<String, Object> frozenV3 = planWith(List.of(mcp("Fetch", Map.of("url", "v3-url"))),
                List.of(edge("trigger:start", "mcp:fetch")));
        Map<String, Object> workflowLive = planWith(List.of(mcp("Fetch", Map.of("url", "v9-url"))),
                List.of(edge("trigger:start", "mcp:fetch")));

        WorkflowEntity workflow = workflowEntity(workflowLive);
        WorkflowRunEntity run = runEntity(frozenV3);
        run.setPlanVersion(3);
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("__editorRun__", Boolean.TRUE);
        metadata.put("__versionReplay__", 3);
        run.setMetadata(metadata);
        org.springframework.test.util.ReflectionTestUtils.setField(run, "workflow", workflow);
        when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));

        WorkflowPlan result = service.refreshPlanFromWorkflowDefinition(RUN_ID);

        assertThat(result).isNotNull();
        verify(runRepository, never()).save(any());
        Map<?, ?> params = (Map<?, ?>) ((Map<?, ?>) ((List<?>) run.getPlan().get("mcps")).get(0)).get("params");
        assertThat(params.get("url")).isEqualTo("v3-url");
        assertThat(run.getPlanVersion()).isEqualTo(3);
    }

    // Helpers

    private WorkflowRunEntity runEntity(Map<String, Object> plan) {
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setRunIdPublic(RUN_ID);
        run.setTenantId(TENANT_ID);
        run.setPlan(new HashMap<>(plan));
        run.setWorkflow(workflowEntity(plan));
        return run;
    }

    private WorkflowEntity workflowEntity(Map<String, Object> plan) {
        WorkflowEntity workflow = new WorkflowEntity();
        workflow.setId(UUID.randomUUID());
        workflow.setTenantId(TENANT_ID);
        workflow.setName("Test Workflow");
        workflow.setPlan(new HashMap<>(plan));
        return workflow;
    }

    private Map<String, Object> planWith(List<Map<String, Object>> mcps, List<Map<String, Object>> edges) {
        Map<String, Object> plan = new HashMap<>();
        plan.put("triggers", List.of(Map.of("type", "webhook", "label", "start")));
        plan.put("mcps", mcps);
        plan.put("agents", List.of());
        plan.put("cores", List.of());
        plan.put("tables", List.of());
        plan.put("interfaces", List.of());
        plan.put("edges", edges);
        return plan;
    }

    private Map<String, Object> mcp(String label, Map<String, Object> params) {
        Map<String, Object> step = new HashMap<>();
        step.put("label", label);
        step.put("service", "http");
        step.put("action", "get");
        step.put("params", new HashMap<>(params));
        return step;
    }

    private Map<String, Object> edge(String from, String to) {
        return Map.of("from", from, "to", to);
    }
}
