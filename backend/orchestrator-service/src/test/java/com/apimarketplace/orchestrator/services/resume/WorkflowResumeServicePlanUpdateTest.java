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
    @Mock private com.apimarketplace.orchestrator.repository.WorkflowRepository workflowRepository;

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
    @DisplayName("Regression 2026-07-20: the PRODUCTION run of a pinned workflow is NOT editable in run mode (guard was keyed on __editorRun__, which production carries)")
    void updateRunPlanRefusesOnTheProductionRunOfAPinnedWorkflow() {
        // Pinning PROMOTES the editor run the user tested with and never strips
        // __editorRun__, so the production run carries that flag. The old guard
        // exempted editor runs, i.e. exactly this run: edits landed on production.
        // The guard now keys on the production_run_id FK.
        Map<String, Object> frozen = planWith(List.of(mcp("Fetch", Map.of("url", "pinned"))),
                List.of(edge("trigger:start", "mcp:fetch")));
        Map<String, Object> edited = planWith(List.of(mcp("Fetch", Map.of("url", "hacked"))),
                List.of(edge("trigger:start", "mcp:fetch")));

        WorkflowEntity workflow = workflowEntity(frozen);
        workflow.setPinnedVersion(17);
        WorkflowRunEntity run = runEntity(frozen);
        // The production run: FK points at it AND it carries __editorRun__ (promoted).
        UUID productionRunId = UUID.randomUUID();
        org.springframework.test.util.ReflectionTestUtils.setField(run, "id", productionRunId);
        workflow.setProductionRunId(productionRunId);
        run.setMetadata(new HashMap<>(Map.of("__editorRun__", true)));
        org.springframework.test.util.ReflectionTestUtils.setField(run, "workflow", workflow);
        when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));

        WorkflowPlan result = service.updateRunPlan(RUN_ID, edited);

        assertThat(result).as("refused edits return null so callers keep the frozen plan").isNull();
        verify(runRepository, never()).save(any());
        Object step = ((List<?>) run.getPlan().get("mcps")).get(0);
        assertThat((Map<String, Object>) ((Map<?, ?>) step).get("params")).containsEntry("url", "pinned");
    }

    @Test
    @DisplayName("Pinned workflow - a NON-production editor run may still iterate (the exemption is preserved, only production is protected)")
    void updateRunPlanStillAllowsNonProductionEditorRunsOnPinnedWorkflow() {
        // Guard against over-correcting: iterating on a pinned workflow from a
        // separate editor run must keep working.
        Map<String, Object> frozen = planWith(List.of(mcp("Fetch", Map.of("url", "v1"))),
                List.of(edge("trigger:start", "mcp:fetch")));
        Map<String, Object> edited = planWith(List.of(mcp("Fetch", Map.of("url", "v2"))),
                List.of(edge("trigger:start", "mcp:fetch")));

        WorkflowEntity workflow = workflowEntity(frozen);
        workflow.setPinnedVersion(17);
        // Production is a DIFFERENT run.
        workflow.setProductionRunId(UUID.randomUUID());
        WorkflowRunEntity run = runEntity(frozen);
        org.springframework.test.util.ReflectionTestUtils.setField(run, "id", UUID.randomUUID());
        run.setMetadata(new HashMap<>(Map.of("__editorRun__", true)));
        org.springframework.test.util.ReflectionTestUtils.setField(run, "workflow", workflow);
        when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));

        WorkflowPlan result = service.updateRunPlan(RUN_ID, edited);

        assertThat(result).isNotNull();
        Object step = ((List<?>) run.getPlan().get("mcps")).get(0);
        assertThat((Map<String, Object>) ((Map<?, ?>) step).get("params")).containsEntry("url", "v2");
    }

    @Test
    @DisplayName("Regression 2026-07-20: refreshPlanFromWorkflowDefinition never swaps the pinned production run onto the draft")
    void refreshPlanKeepsTheFrozenPinnedPlanOnTheProductionRun() {
        // Reachable via "rerun from step" on the production run: the sync wrote
        // workflow.getPlan() (the DRAFT) into the production run while planVersion
        // still matched the pin, so the chokepoint let it execute.
        Map<String, Object> pinnedFrozen = planWith(List.of(mcp("Fetch", Map.of("url", "pinned"))),
                List.of(edge("trigger:start", "mcp:fetch")));
        Map<String, Object> draft = planWith(List.of(mcp("Fetch", Map.of("url", "draft"))),
                List.of(edge("trigger:start", "mcp:fetch")));

        WorkflowEntity workflow = workflowEntity(draft);
        workflow.setPinnedVersion(17);
        WorkflowRunEntity run = runEntity(pinnedFrozen);
        UUID productionRunId = UUID.randomUUID();
        org.springframework.test.util.ReflectionTestUtils.setField(run, "id", productionRunId);
        workflow.setProductionRunId(productionRunId);
        org.springframework.test.util.ReflectionTestUtils.setField(run, "workflow", workflow);
        when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));

        service.refreshPlanFromWorkflowDefinition(RUN_ID);

        verify(runRepository, never()).save(any());
        Object step = ((List<?>) run.getPlan().get("mcps")).get(0);
        assertThat((Map<String, Object>) ((Map<?, ?>) step).get("params"))
                .as("production stays on the pinned plan, not the draft")
                .containsEntry("url", "pinned");
    }

    @Test
    @DisplayName("Regression 2026-07-21: production run with a NULL cached plan reloads the PINNED version's content, never the draft")
    void refreshPlanReloadsPinnedContentWhenFrozenPlanIsNull() {
        // Legacy/corrupt state: the production run lost its cached plan. The reload
        // branch must fetch the PINNED version row - a mutation swapping it for
        // workflow.getPlan() (the draft) previously passed the whole suite.
        // Structural marker: pinned content has ONE mcp, the draft has TWO.
        Map<String, Object> pinnedContent = planWith(List.of(mcp("Fetch", Map.of("url", "pinned"))),
                List.of(edge("trigger:start", "mcp:fetch")));
        Map<String, Object> draft = planWith(
                List.of(mcp("Fetch", Map.of("url", "draft")), mcp("Extra", Map.of())),
                List.of(edge("trigger:start", "mcp:fetch"), edge("mcp:fetch", "mcp:extra")));

        WorkflowEntity workflow = workflowEntity(draft);
        workflow.setPinnedVersion(17);
        WorkflowRunEntity run = runEntity(draft);
        run.setPlan(null);
        UUID productionRunId = UUID.randomUUID();
        org.springframework.test.util.ReflectionTestUtils.setField(run, "id", productionRunId);
        workflow.setProductionRunId(productionRunId);
        org.springframework.test.util.ReflectionTestUtils.setField(run, "workflow", workflow);
        when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));
        org.springframework.test.util.ReflectionTestUtils.setField(service, "planVersionService", planVersionService);
        com.apimarketplace.orchestrator.domain.WorkflowPlanVersionEntity pinnedRow =
                mock(com.apimarketplace.orchestrator.domain.WorkflowPlanVersionEntity.class);
        when(pinnedRow.getPlan()).thenReturn(pinnedContent);
        when(planVersionService.getVersion(workflow.getId(), 17)).thenReturn(Optional.of(pinnedRow));

        WorkflowPlan result = service.refreshPlanFromWorkflowDefinition(RUN_ID);

        assertThat(result).isNotNull();
        assertThat(result.getMcps())
                .as("executes the reloaded pinned content (1 mcp), not the draft (2 mcps)")
                .hasSize(1);
        verify(planVersionService).getVersion(workflow.getId(), 17);
        verify(runRepository, never()).save(any());
    }

    @Test
    @DisplayName("Last resort 2026-07-21: NULL cached plan AND pinned version row missing -> draft executes (loud pin violation)")
    void refreshPlanFallsBackToDraftWhenPinnedRowIsAlsoMissing() {
        Map<String, Object> draft = planWith(
                List.of(mcp("Fetch", Map.of("url", "draft")), mcp("Extra", Map.of())),
                List.of(edge("trigger:start", "mcp:fetch"), edge("mcp:fetch", "mcp:extra")));

        WorkflowEntity workflow = workflowEntity(draft);
        workflow.setPinnedVersion(17);
        WorkflowRunEntity run = runEntity(draft);
        run.setPlan(null);
        UUID productionRunId = UUID.randomUUID();
        org.springframework.test.util.ReflectionTestUtils.setField(run, "id", productionRunId);
        workflow.setProductionRunId(productionRunId);
        org.springframework.test.util.ReflectionTestUtils.setField(run, "workflow", workflow);
        when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));
        org.springframework.test.util.ReflectionTestUtils.setField(service, "planVersionService", planVersionService);
        when(planVersionService.getVersion(workflow.getId(), 17)).thenReturn(Optional.empty());

        WorkflowPlan result = service.refreshPlanFromWorkflowDefinition(RUN_ID);

        // Both frozen plan and version row are gone: executing the draft is the only
        // remaining option (ERROR-logged as a pin violation) - not a crash. The fix's
        // delta vs pre-fix code: the draft is only EXECUTED, never written back onto
        // the production run (pre-fix, the sync branch saved it and re-stamped the
        // version - a durable pin violation instead of a one-shot one).
        assertThat(result).isNotNull();
        assertThat(result.getMcps()).hasSize(2);
        verify(runRepository, never()).save(any());
    }

    @Test
    @DisplayName("Pinned workflow - stampPlanVersion writes back whatever version the resolver answers (contract test, pin logic lives in the resolver)")
    void refreshPlanStampsPinnedVersionOnPinnedWorkflow() {
        // Contract test, NOT a regression test: planVersionService is a mock here, so
        // this passes both pre- and post-fix. It exists to pin the write-back
        // behaviour that makes the resolver-side fix effective at this caller -
        // stampPlanVersion is pin-blind by design and must not second-guess the
        // number it is handed. The behavioural regression coverage lives in
        // WorkflowPlanVersionServiceTest, against the real resolver.
        Map<String, Object> frozen = planWith(List.of(mcp("Fetch", Map.of("url", "pinned"))),
                List.of(edge("trigger:start", "mcp:fetch")));
        Map<String, Object> workflowLive = planWith(List.of(mcp("Fetch", Map.of("url", "pinned"))),
                List.of(edge("trigger:start", "mcp:fetch")));

        WorkflowEntity workflow = workflowEntity(workflowLive);
        workflow.setPinnedVersion(17);
        WorkflowRunEntity run = runEntity(frozen);
        run.setPlanVersion(17);
        org.springframework.test.util.ReflectionTestUtils.setField(run, "workflow", workflow);
        when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));
        org.springframework.test.util.ReflectionTestUtils.setField(service, "planVersionService", planVersionService);
        // Post-fix resolver answer for a run executing the pinned content, with a
        // newer draft v18 present in the history.
        when(planVersionService.resolveContentVersionForExecutionInNewTransaction(workflow.getId(), workflowLive, TENANT_ID))
                .thenReturn(17);

        service.refreshPlanFromWorkflowDefinition(RUN_ID);

        assertThat(run.getPlanVersion()).isEqualTo(17);
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

    // ====================================================================
    // In-run MOCK mirror: editor-run mock edits must reach workflow.plan
    // (their durable home - run.plan is refreshed FROM workflow.plan on
    // every fire, so a run-only mock would be wiped by the next refresh).
    // ====================================================================

    @Test
    @DisplayName("updateRunPlan - editor run: a mock in the payload is mirrored into workflow.plan (params stay run-scoped)")
    void updateRunPlanMirrorsMockIntoWorkflowPlan() {
        Map<String, Object> frozen = planWith(List.of(mcp("Fetch", Map.of("url", "v1"))),
                List.of(edge("trigger:start", "mcp:fetch")));
        Map<String, Object> mockedMcp = mcp("Fetch", Map.of("url", "run-scoped-edit"));
        mockedMcp.put("mock", Map.of("output", Map.of("score", 87)));
        Map<String, Object> updated = planWith(List.of(mockedMcp),
                List.of(edge("trigger:start", "mcp:fetch")));

        WorkflowEntity workflow = workflowEntity(frozen);
        WorkflowRunEntity run = editorRunEntity(frozen, workflow);
        when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));
        org.springframework.test.util.ReflectionTestUtils.setField(service, "workflowRepository", workflowRepository);

        WorkflowPlan result = service.updateRunPlan(RUN_ID, updated);

        assertThat(result).isNotNull();
        verify(workflowRepository).save(workflow);
        Map<?, ?> wfMcp = (Map<?, ?>) ((List<?>) workflow.getPlan().get("mcps")).get(0);
        assertThat(wfMcp.get("mock")).isEqualTo(Map.of("output", Map.of("score", 87)));
        // ONLY the mock is mirrored: the workflow keeps its own params.
        assertThat(((Map<?, ?>) wfMcp.get("params")).get("url")).isEqualTo("v1");
    }

    @Test
    @DisplayName("updateRunPlan - editor run: payload without any mock leaves workflow.plan untouched")
    void updateRunPlanWithoutMockDoesNotTouchWorkflowPlan() {
        Map<String, Object> frozen = planWith(List.of(mcp("Fetch", Map.of("url", "v1"))),
                List.of(edge("trigger:start", "mcp:fetch")));
        Map<String, Object> updated = planWith(List.of(mcp("Fetch", Map.of("url", "v2"))),
                List.of(edge("trigger:start", "mcp:fetch")));

        WorkflowEntity workflow = workflowEntity(frozen);
        WorkflowRunEntity run = editorRunEntity(frozen, workflow);
        when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));
        org.springframework.test.util.ReflectionTestUtils.setField(service, "workflowRepository", workflowRepository);

        service.updateRunPlan(RUN_ID, updated);

        verify(workflowRepository, never()).save(any());
    }

    @Test
    @DisplayName("updateRunPlan - NON-editor run never mirrors mocks to workflow.plan")
    void updateRunPlanNonEditorRunDoesNotMirror() {
        Map<String, Object> frozen = planWith(List.of(mcp("Fetch", Map.of())),
                List.of(edge("trigger:start", "mcp:fetch")));
        Map<String, Object> mockedMcp = mcp("Fetch", Map.of());
        mockedMcp.put("mock", Map.of("output", Map.of("ok", true)));
        Map<String, Object> updated = planWith(List.of(mockedMcp),
                List.of(edge("trigger:start", "mcp:fetch")));

        WorkflowRunEntity run = runEntity(frozen); // no __editorRun__ metadata
        when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));
        org.springframework.test.util.ReflectionTestUtils.setField(service, "workflowRepository", workflowRepository);

        service.updateRunPlan(RUN_ID, updated);

        verify(workflowRepository, never()).save(any());
    }

    @Test
    @DisplayName("updateRunPlan - APPLICATION workflow: mirror skipped (immutable acquired clone)")
    void updateRunPlanApplicationWorkflowDoesNotMirror() {
        Map<String, Object> frozen = planWith(List.of(mcp("Fetch", Map.of())),
                List.of(edge("trigger:start", "mcp:fetch")));
        Map<String, Object> mockedMcp = mcp("Fetch", Map.of());
        mockedMcp.put("mock", Map.of("output", Map.of("ok", true)));
        Map<String, Object> updated = planWith(List.of(mockedMcp),
                List.of(edge("trigger:start", "mcp:fetch")));

        WorkflowEntity workflow = workflowEntity(frozen);
        workflow.setWorkflowType(WorkflowEntity.WorkflowType.APPLICATION);
        WorkflowRunEntity run = editorRunEntity(frozen, workflow);
        when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));
        org.springframework.test.util.ReflectionTestUtils.setField(service, "workflowRepository", workflowRepository);

        service.updateRunPlan(RUN_ID, updated);

        verify(workflowRepository, never()).save(any());
    }

    @Test
    @DisplayName("updateRunPlan - mirror failure is non-fatal: the run plan write still succeeds")
    void updateRunPlanMirrorFailureIsNonFatal() {
        Map<String, Object> frozen = planWith(List.of(mcp("Fetch", Map.of())),
                List.of(edge("trigger:start", "mcp:fetch")));
        Map<String, Object> mockedMcp = mcp("Fetch", Map.of());
        mockedMcp.put("mock", Map.of("output", Map.of("ok", true)));
        Map<String, Object> updated = planWith(List.of(mockedMcp),
                List.of(edge("trigger:start", "mcp:fetch")));

        WorkflowEntity workflow = workflowEntity(frozen);
        WorkflowRunEntity run = editorRunEntity(frozen, workflow);
        when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));
        org.springframework.test.util.ReflectionTestUtils.setField(service, "workflowRepository", workflowRepository);
        when(workflowRepository.save(any())).thenThrow(new RuntimeException("db down"));

        WorkflowPlan result = service.updateRunPlan(RUN_ID, updated);

        assertThat(result).isNotNull();
        verify(runRepository).save(run);
    }

    // Helpers

    private WorkflowRunEntity editorRunEntity(Map<String, Object> plan, WorkflowEntity workflow) {
        WorkflowRunEntity run = runEntity(plan);
        org.springframework.test.util.ReflectionTestUtils.setField(run, "workflow", workflow);
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("__editorRun__", Boolean.TRUE);
        run.setMetadata(metadata);
        return run;
    }

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
