package com.apimarketplace.orchestrator.controllers.internal;

import com.apimarketplace.auth.client.access.OrgAccessGuard;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.execution.v2.scheduler.V2StepByStepScheduler;
import com.apimarketplace.orchestrator.execution.v2.services.UnifiedSignalService;
import com.apimarketplace.orchestrator.execution.v2.services.V2StepByStepService;
import com.apimarketplace.orchestrator.repository.SignalWaitRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.resume.WorkflowResumeService;
import com.apimarketplace.orchestrator.services.streaming.SnapshotService;
import com.apimarketplace.orchestrator.services.state.StateSnapshotService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Regression for the WebSocket bypass: {@code signal.resolve} and {@code sbs.execute} reach
 * these internal endpoints with the session's org but (before the fix) no role, so the
 * VIEWER refusal of the HTTP twins did not apply and a VIEWER could approve a gate or step
 * a run over the socket. They now read the forwarded {@code X-Organization-Role} and go
 * through the same RunWriteGate (VIEWER role, then the member deny-list).
 */
@DisplayName("Internal WS endpoints (signal.resolve, sbs.execute) apply the run write gate")
class InternalWsRunWriteGateTest {

    private static final String USER = "user-1";
    private static final String ORG = "org-1";
    private static final UUID WORKFLOW_ID = UUID.fromString("9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d");

    private UnifiedSignalService signalService;
    private V2StepByStepService v2StepByStepService;
    private OrgAccessGuard guard;
    private InternalSignalController signalController;
    private InternalSbsController sbsController;

    @BeforeEach
    void setUp() {
        signalService = mock(UnifiedSignalService.class);
        v2StepByStepService = mock(V2StepByStepService.class);
        guard = mock(OrgAccessGuard.class);
        SignalWaitRepository signalWaitRepository = mock(SignalWaitRepository.class);
        WorkflowRunRepository runRepository = mock(WorkflowRunRepository.class);

        WorkflowEntity wf = new WorkflowEntity();
        wf.setId(WORKFLOW_ID);
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setWorkflow(wf);
        run.setRunIdPublic("run-1");
        run.setTenantId("owner-1");
        run.setOrganizationId(ORG);
        when(runRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(run));
        when(signalWaitRepository.findEpochInfoById(7L)).thenReturn(Optional.of(
                new SignalWaitRepository.EpochInfo("run-1", "trigger:start", 1, Instant.EPOCH)));

        signalController = new InternalSignalController(signalService, signalWaitRepository, runRepository, guard);
        sbsController = new InternalSbsController(v2StepByStepService, mock(V2StepByStepScheduler.class),
                mock(WorkflowResumeService.class), mock(StateSnapshotService.class), mock(SnapshotService.class),
                mock(TaskExecutor.class), runRepository, guard);
    }

    @Test
    @DisplayName("signal.resolve forwarded with a VIEWER role is 403 and resolves nothing")
    void viewerSignalResolveRefused() {
        ResponseEntity<Map<String, Object>> r = signalController.resolveSignal(7L, USER, ORG, "VIEWER",
                Map.of("resolution", "APPROVED"));

        assertThat(r.getStatusCode().value()).isEqualTo(403);
        verifyNoInteractions(signalService);
    }

    @Test
    @DisplayName("sbs.execute forwarded with a VIEWER role is 403 and executes nothing")
    void viewerSbsExecuteRefused() {
        ResponseEntity<Map<String, Object>> r = sbsController.executeNode("run-1", "mcp:a", USER, ORG, "VIEWER", Map.of());

        assertThat(r.getStatusCode().value()).isEqualTo(403);
        assertThat(r.getBody()).containsEntry("accepted", false);
        verifyNoInteractions(v2StepByStepService);
    }

    @Test
    @DisplayName("a MEMBER restricted from the run's workflow is 403 on both")
    void restrictedMemberRefused() {
        when(guard.canWrite(ORG, USER, "workflow", WORKFLOW_ID.toString(), "MEMBER")).thenReturn(false);

        assertThat(signalController.resolveSignal(7L, USER, ORG, "MEMBER", Map.of()).getStatusCode().value())
                .isEqualTo(403);
        assertThat(sbsController.executeNode("run-1", "mcp:a", USER, ORG, "MEMBER", Map.of()).getStatusCode().value())
                .isEqualTo(403);
        verifyNoInteractions(signalService, v2StepByStepService);
    }

    @Test
    @DisplayName("an unrestricted MEMBER resolve goes past the gate to the signal service")
    void memberResolveProceeds() {
        when(guard.canWrite(ORG, USER, "workflow", WORKFLOW_ID.toString(), "MEMBER")).thenReturn(true);

        ResponseEntity<Map<String, Object>> r = signalController.resolveSignal(7L, USER, ORG, "MEMBER",
                Map.of("resolution", "APPROVED"));

        assertThat(r.getStatusCode().value()).isNotEqualTo(403);
        org.mockito.Mockito.verify(signalService).resolveSignal(
                org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyMap(), org.mockito.ArgumentMatchers.any());
    }
}
