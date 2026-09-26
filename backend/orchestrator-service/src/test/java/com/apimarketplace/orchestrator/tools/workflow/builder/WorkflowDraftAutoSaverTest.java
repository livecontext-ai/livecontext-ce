package com.apimarketplace.orchestrator.tools.workflow.builder;

import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.services.WorkflowManagementService;
import com.apimarketplace.orchestrator.services.WorkflowPlanVersionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

/**
 * The auto-save is what moves the session's baseline: the plan it just wrote is the
 * one the next action compares the stored plan against. Without it every later canvas
 * save would be compared with the plan as first LOADED, and a session's own earlier
 * writes would read as someone else's.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowDraftAutoSaver - records what it wrote as the session baseline")
class WorkflowDraftAutoSaverTest {

    @Mock private WorkflowBuilderSessionManager sessionManager;
    @Mock private WorkflowManagementService workflowService;
    @Mock private WorkflowPlanVersionService versionService;
    @Mock private WorkflowBuilderLoader loader;
    @Mock private WorkflowBuilderSessionStore sessionStore;

    @InjectMocks private WorkflowDraftAutoSaver autoSaver;

    private final UUID workflowId = UUID.randomUUID();
    private WorkflowBuilderSession session;

    @BeforeEach
    void setUp() {
        session = WorkflowBuilderSession.create("t", "conv-1", "Wf", null);
        session.setLoadedWorkflowId(workflowId.toString());
    }

    @Test
    @DisplayName("After an auto-save the stored plan it wrote becomes the baseline, and the session is persisted")
    void autoSaveRecordsTheWrittenPlanAsBaseline() {
        Map<String, Object> written = Map.of("name", "Wf", "tenant_id", "t");
        WorkflowEntity saved = new WorkflowEntity();
        saved.setId(workflowId);
        saved.setPlan(written);
        when(sessionManager.getSession(any(), eq("t"), eq("conv-1")))
                .thenReturn(new WorkflowBuilderSessionManager.SessionResult(session, null));
        when(sessionManager.getSessionStore()).thenReturn(sessionStore);
        when(workflowService.saveDraft(anyMap(), eq("t"), eq(workflowId), any())).thenReturn(saved);

        autoSaver.autoSaveDraft(Map.of(), "t", "conv-1");

        var order = inOrder(workflowService, loader, sessionStore);
        order.verify(workflowService).saveDraft(anyMap(), eq("t"), eq(workflowId), any());
        order.verify(loader).recordStoredPlan(session, written);
        order.verify(sessionStore).save(session);
    }

    @Test
    @DisplayName("Creating the draft records its plan as the baseline of the new session")
    void createDraftRecordsBaseline() {
        WorkflowBuilderSession fresh = WorkflowBuilderSession.create("t", "conv-1", "Wf", null);
        Map<String, Object> written = Map.of("name", "Wf");
        WorkflowEntity saved = new WorkflowEntity();
        saved.setId(workflowId);
        saved.setPlan(written);
        when(workflowService.saveDraft(anyMap(), eq("t"), eq(null), any())).thenReturn(saved);

        autoSaver.createDraft(fresh, "t");

        org.mockito.Mockito.verify(loader).recordStoredPlan(fresh, written);
    }
}
