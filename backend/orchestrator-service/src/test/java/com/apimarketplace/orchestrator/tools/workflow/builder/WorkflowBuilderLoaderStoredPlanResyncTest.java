package com.apimarketplace.orchestrator.tools.workflow.builder;

import com.apimarketplace.datasource.client.DataSourceClient;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.service.NodeLibraryService;
import com.apimarketplace.orchestrator.services.WorkflowManagementService;
import com.apimarketplace.orchestrator.services.WorkflowPlanVersionService;
import com.apimarketplace.trigger.client.TriggerClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * An agent builder session keeps its own copy of the workflow and every auto-save
 * writes that whole copy to {@code workflows.plan}. A save made on the builder canvas
 * between two agent actions (a node moved to y=500) was put back to the session's
 * older copy (y=306) by the agent's next action, reproduced live on 2026-09-23.
 * {@link WorkflowBuilderLoader#resyncWithStoredPlan} rebuilds the session from the
 * stored plan whenever that plan is no longer the one the session last read or wrote.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowBuilderLoader - rebuild the session from a plan saved on the canvas")
class WorkflowBuilderLoaderStoredPlanResyncTest {

    @Mock private WorkflowBuilderSessionStore sessionStore;
    @Mock private WorkflowManagementService workflowService;
    @Mock private WorkflowRepository workflowRepository;
    @Mock private WorkflowBuilderLogger buildLogger;
    @Mock private WorkflowBuilderValidator validator;
    @Mock private DataSourceClient dataSourceClient;
    @Mock private ToolSchemaFetcher toolSchemaFetcher;
    @Mock private NodeLibraryService nodeLibraryService;
    @Mock private TriggerClient triggerClient;
    @Mock private WorkflowPlanVersionService versionService;
    @Mock private AgentWorkflowFireService agentFireService;

    private static final UUID WORKFLOW_ID = UUID.randomUUID();

    private WorkflowBuilderLoader loader;

    @BeforeEach
    void setUp() {
        loader = new WorkflowBuilderLoader(
                sessionStore, workflowService, workflowRepository,
                buildLogger, validator, dataSourceClient,
                toolSchemaFetcher, nodeLibraryService,
                new ObjectMapper(), triggerClient, versionService,
                agentFireService);
    }

    /** A two-core plan whose "Finish" node sits at {@code finishY}. */
    private static Map<String, Object> plan(Number finishY) {
        Map<String, Object> shape = new LinkedHashMap<>();
        shape.put("id", "core:shape");
        shape.put("label", "Shape");
        shape.put("type", "transform");
        shape.put("position", new LinkedHashMap<>(Map.of("x", 290, "y", 0)));
        Map<String, Object> finish = new LinkedHashMap<>();
        finish.put("id", "core:finish");
        finish.put("label", "Finish");
        finish.put("type", "transform");
        finish.put("position", new LinkedHashMap<>(Map.of("x", 580, "y", finishY)));
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("name", "Position Save Test");
        plan.put("cores", new ArrayList<>(List.of(shape, finish)));
        plan.put("edges", new ArrayList<>(List.of(Map.of("from", "core:shape", "to", "core:finish"))));
        return plan;
    }

    private static WorkflowEntity storedWorkflow(Map<String, Object> plan) {
        WorkflowEntity workflow = new WorkflowEntity();
        workflow.setId(WORKFLOW_ID);
        workflow.setTenantId("t");
        workflow.setName("Position Save Test");
        workflow.setPlan(plan);
        return workflow;
    }

    /** A session that last wrote {@code baseline}, holding the same content plus one undo step. */
    private WorkflowBuilderSession sessionThatLastWrote(Map<String, Object> baseline) {
        WorkflowBuilderSession session = WorkflowBuilderSession.create("t", "conv-1", "Position Save Test", null);
        session.setLoadedWorkflowId(WORKFLOW_ID.toString());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cores = (List<Map<String, Object>>) plan(306).get("cores");
        session.getCores().addAll(cores);
        session.getActionHistory().add(WorkflowBuilderSession.SessionAction.builder()
                .actionType("modify").nodeId("core:shape").timestamp(Instant.now()).build());
        loader.recordStoredPlan(session, baseline);
        return session;
    }

    @SuppressWarnings("unchecked")
    private static Object finishY(WorkflowBuilderSession session) {
        return session.getCores().stream()
                .filter(c -> "Finish".equals(c.get("label")))
                .map(c -> ((Map<String, Object>) c.get("position")).get("y"))
                .findFirst().orElseThrow();
    }

    @Test
    @DisplayName("A move saved on the canvas after the session's last write is adopted, not overwritten")
    void adoptsNodePositionSavedOnCanvasAfterSessionLastWrote() {
        WorkflowBuilderSession session = sessionThatLastWrote(plan(306));
        when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(storedWorkflow(plan(500))));

        boolean rebuilt = loader.resyncWithStoredPlan(session);

        assertThat(rebuilt).isTrue();
        assertThat(finishY(session)).isEqualTo(500);
        verify(sessionStore).save(session);
    }

    @Test
    @DisplayName("After a rebuild the baseline is the stored plan, so the next action does not rebuild again")
    void rebuildMovesBaselineToStoredPlan() {
        WorkflowBuilderSession session = sessionThatLastWrote(plan(306));
        when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(storedWorkflow(plan(500))));
        loader.resyncWithStoredPlan(session);

        boolean rebuiltAgain = loader.resyncWithStoredPlan(session);

        assertThat(rebuiltAgain).isFalse();
    }

    @Test
    @DisplayName("A rebuild clears undo/redo: an undo would otherwise restore the content the canvas replaced")
    void rebuildClearsUndoHistory() {
        WorkflowBuilderSession session = sessionThatLastWrote(plan(306));
        session.getRedoStack().add(WorkflowBuilderSession.SessionAction.builder().actionType("remove").build());
        when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(storedWorkflow(plan(500))));

        loader.resyncWithStoredPlan(session);

        assertThat(session.getActionHistory()).isEmpty();
        assertThat(session.getRedoStack()).isEmpty();
    }

    @Test
    @DisplayName("A rebuild keeps the session identity: id, conversation and loaded workflow")
    void rebuildKeepsSessionIdentity() {
        WorkflowBuilderSession session = sessionThatLastWrote(plan(306));
        String sessionId = session.getSessionId();
        when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(storedWorkflow(plan(500))));

        loader.resyncWithStoredPlan(session);

        assertThat(session.getSessionId()).isEqualTo(sessionId);
        assertThat(session.getConversationId()).isEqualTo("conv-1");
        assertThat(session.getLoadedWorkflowId()).isEqualTo(WORKFLOW_ID.toString());
    }

    @Test
    @DisplayName("The stored plan the session last wrote is not a change, whatever Java number type each side holds")
    void storedPlanEqualToBaselineIsNotAChangeAcrossNumberTypes() {
        // The session built its copy with Long coordinates; a database read yields Integer.
        WorkflowBuilderSession session = sessionThatLastWrote(plan(306L));
        when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(storedWorkflow(plan(306))));

        boolean rebuilt = loader.resyncWithStoredPlan(session);

        assertThat(rebuilt).isFalse();
        assertThat(session.getActionHistory()).hasSize(1);
        verify(sessionStore, never()).save(any());
    }

    @Test
    @DisplayName("The baseline is a copy: editing the session in memory does not move it")
    void baselineIsDetachedFromTheRecordedMap() {
        Map<String, Object> written = plan(306);
        WorkflowBuilderSession session = sessionThatLastWrote(written);
        @SuppressWarnings("unchecked")
        Map<String, Object> finish = ((List<Map<String, Object>>) written.get("cores")).get(1);
        finish.put("position", Map.of("x", 580, "y", 999));
        when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(storedWorkflow(plan(306))));

        assertThat(loader.resyncWithStoredPlan(session)).isFalse();
    }

    @Test
    @DisplayName("A session with no baseline is left alone and the database is not read")
    void sessionWithoutBaselineIsLeftAlone() {
        WorkflowBuilderSession session = WorkflowBuilderSession.create("t", "conv-1", "New", null);
        session.setLoadedWorkflowId(WORKFLOW_ID.toString());

        assertThat(loader.resyncWithStoredPlan(session)).isFalse();
        verifyNoInteractions(workflowRepository);
    }

    @Test
    @DisplayName("Reloading the same workflow in the conversation adopts a canvas save instead of keeping the old copy")
    void reloadOfSameWorkflowAdoptsCanvasSave() {
        WorkflowBuilderSession session = sessionThatLastWrote(plan(306));
        when(sessionStore.getSessionForConversation("t", "conv-1")).thenReturn(Optional.of(session));
        when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(storedWorkflow(plan(500))));

        loader.executeLoad("t", null, "conv-1", Map.of("id", WORKFLOW_ID.toString()));

        assertThat(finishY(session)).isEqualTo(500);
    }

    @Test
    @DisplayName("A workflow that no longer exists leaves the session alone")
    void missingWorkflowLeavesSessionAlone() {
        WorkflowBuilderSession session = sessionThatLastWrote(plan(306));
        when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.empty());

        assertThat(loader.resyncWithStoredPlan(session)).isFalse();
        assertThat(finishY(session)).isEqualTo(306);
    }

    @Test
    @DisplayName("An empty stored plan is never adopted: it would wipe the session")
    void emptyStoredPlanIsNotAdopted() {
        WorkflowBuilderSession session = sessionThatLastWrote(plan(306));
        when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(storedWorkflow(new LinkedHashMap<>())));

        assertThat(loader.resyncWithStoredPlan(session)).isFalse();
        assertThat(session.getCores()).hasSize(2);
    }

    @Test
    @DisplayName("An explicit save records what it stored as the baseline, so the next action does not rebuild")
    void explicitSaveRecordsTheStoredPlanAsBaseline() {
        org.springframework.test.util.ReflectionTestUtils.setField(loader, "allowSaveWithoutValidation", false);
        WorkflowBuilderSession session = sessionThatLastWrote(plan(306));
        var validation = WorkflowBuilderValidator.ValidationResult.builder().build();
        when(validator.validate(any())).thenReturn(validation);
        when(validator.toAgentFormat(validation)).thenReturn(new LinkedHashMap<>(Map.of(
                "errors", List.of(), "warnings", List.of())));
        Map<String, Object> storedBySave = plan(420);
        WorkflowEntity saved = storedWorkflow(storedBySave);
        when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(saved));
        when(workflowService.saveWorkflow(any(), org.mockito.ArgumentMatchers.anyMap(), any(), any()))
                .thenReturn(new WorkflowManagementService.SaveResult(saved, false, null));

        loader.executeSave(session);

        assertThat(loader.resyncWithStoredPlan(session)).isFalse();
    }

    @Test
    @DisplayName("A session stored before baselines followed its writes is not rebuilt: its baseline is adopted once")
    void legacySessionAdoptsBaselineWithoutRebuilding() {
        // Its baseline is the plan as first loaded, which its own auto-saves have moved away
        // from; rebuilding would clear its undo and tell the agent of an outside save that
        // never happened.
        WorkflowBuilderSession session = sessionThatLastWrote(plan(306));
        session.setBaselineTracksWrites(false);
        when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(storedWorkflow(plan(500))));

        assertThat(loader.resyncWithStoredPlan(session)).isFalse();
        assertThat(finishY(session)).isEqualTo(306);
        assertThat(session.getActionHistory()).hasSize(1);
        assertThat(session.isBaselineTracksWrites()).isTrue();
        verify(sessionStore).save(session);
        // From now on it is compared like any session.
        assertThat(loader.resyncWithStoredPlan(session)).isFalse();
    }
}
