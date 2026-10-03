package com.apimarketplace.orchestrator.trigger;

import com.apimarketplace.auth.client.access.OrgAccessGuard;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.resume.WorkflowResumeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression tests for LC-012 (security audit 2026-08-13).
 *
 * The platform has two authorization layers: workspace isolation ({@code ScopeGuard}) and an
 * intra-organization layer ({@code OrgAccessGuard}) implementing the read-only VIEWER role and
 * the per-member per-resource deny-list. Layer 2 was applied on the workflow object and on the
 * storage explorer, and NOWHERE on the run surface.
 *
 * <p>So a VIEWER, or a MEMBER explicitly denied the Gmail workflow, could
 * {@code POST /api/v2/workflows/runs/{runId}/trigger/manual} and cause a fresh fetch of the
 * owner's mailbox. Scope alone let them through, because they ARE in the workspace; what they
 * are not is allowed to run it. The frontend hid the launcher for VIEWER on the belief the
 * backend would refuse, which was true only for {@code /dag/execute}.
 *
 * <p>That least-privilege story is exactly what a Limited Use reviewer probes once
 * {@code gmail.readonly} is on the shared client.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("TriggerController intra-organization role gate")
class TriggerControllerOrgRoleTest {

    private static final String CALLER = "tenant-1";
    private static final String ORG = "org-1";
    private static final String RUN_ID = "run-public-1";

    @Mock private WorkflowRunRepository runRepository;
    @Mock private ReusableTriggerService triggerService;
    @Mock private WorkflowResumeService resumeService;
    @Mock private OrgAccessGuard orgAccessGuard;

    private TriggerController controller;
    private UUID workflowId;

    @BeforeEach
    void setUp() {
        controller = new TriggerController(runRepository, triggerService, resumeService, orgAccessGuard);

        workflowId = UUID.randomUUID();
        WorkflowRunEntity run = orgRun();
        when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));
    }

    /** A run inside an org workspace, owned by the caller's tenant so scope (layer 1) passes. */
    private WorkflowRunEntity orgRun() {
        WorkflowRunEntity run = new WorkflowRunEntity();
        ReflectionTestUtils.setField(run, "tenantId", CALLER);
        ReflectionTestUtils.setField(run, "organizationId", ORG);
        ReflectionTestUtils.setField(run, "runIdPublic", RUN_ID);
        ReflectionTestUtils.setField(run, "status", RunStatus.WAITING_TRIGGER);

        com.apimarketplace.orchestrator.domain.WorkflowEntity workflow =
                new com.apimarketplace.orchestrator.domain.WorkflowEntity();
        ReflectionTestUtils.setField(workflow, "id", workflowId);
        ReflectionTestUtils.setField(run, "workflow", workflow);
        return run;
    }

    @Test
    @DisplayName("a member denied the workflow cannot fire its trigger, even inside the workspace")
    void deniedMemberCannotFire() {
        // Layer 1 passes (same tenant, same org). Layer 2 is what must refuse.
        when(orgAccessGuard.canWrite(eq(ORG), eq(CALLER), eq("workflow"), anyString(), eq("MEMBER")))
                .thenReturn(false);

        ResponseEntity<TriggerController.TriggerResponse> response =
                controller.triggerManual(RUN_ID, Map.of(), null, CALLER, ORG, "MEMBER");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(triggerService, never()).executeTrigger(any(), anyString(), any(), any());
    }

    @Test
    @DisplayName("a VIEWER cannot fire a trigger: firing executes the workflow, which is a write")
    void viewerCannotFire() {
        // canWrite folds in the role-level VIEWER block, so the guard answers false without any
        // per-resource restriction being configured. Stubbed here the way the real guard behaves.
        when(orgAccessGuard.canWrite(eq(ORG), eq(CALLER), eq("workflow"), anyString(), eq("VIEWER")))
                .thenReturn(false);

        ResponseEntity<TriggerController.TriggerResponse> response =
                controller.triggerManual(RUN_ID, Map.of(), null, CALLER, ORG, "VIEWER");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(triggerService, never()).executeTrigger(any(), anyString(), any(), any());
    }

    @Test
    @DisplayName("the gate is keyed on the run's parent workflow, not on the run")
    void gateIsKeyedOnTheWorkflow() {
        // Denying a member the Gmail workflow must deny them every run of it, so the id passed
        // to the guard has to be the workflow's, not the run's.
        when(orgAccessGuard.canWrite(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(false);

        controller.triggerManual(RUN_ID, Map.of(), null, CALLER, ORG, "MEMBER");

        verify(orgAccessGuard).canWrite(ORG, CALLER, "workflow", workflowId.toString(), "MEMBER");
    }

    @Test
    @DisplayName("an allowed member still gets through, so the gate is not a blanket refusal")
    void allowedMemberPassesTheGate() {
        when(orgAccessGuard.canWrite(eq(ORG), eq(CALLER), eq("workflow"), anyString(), eq("MEMBER")))
                .thenReturn(true);

        ResponseEntity<TriggerController.TriggerResponse> response =
                controller.triggerManual(RUN_ID, Map.of(), null, CALLER, ORG, "MEMBER");

        // Past the gate the run proceeds into the normal trigger flow; what matters here is only
        // that it was NOT refused with 403.
        assertThat(response.getStatusCode()).isNotEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("a personal (non-org) run is unaffected: there is no role to enforce")
    void personalRunSkipsTheGate() {
        WorkflowRunEntity personal = orgRun();
        ReflectionTestUtils.setField(personal, "organizationId", null);
        when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(personal));

        ResponseEntity<TriggerController.TriggerResponse> response =
                controller.triggerManual(RUN_ID, Map.of(), null, CALLER, null, null);

        assertThat(response.getStatusCode()).isNotEqualTo(HttpStatus.FORBIDDEN);
        verify(orgAccessGuard, never()).canWrite(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("a deny-listed member cannot list the run's triggers either (audit round 2)")
    void deniedMemberCannotListTriggers() {
        when(orgAccessGuard.canAccess(eq(ORG), eq(CALLER), eq("workflow"), anyString(), eq("MEMBER")))
                .thenReturn(false);

        ResponseEntity<?> response = controller.getAvailableTriggers(RUN_ID, CALLER, ORG, "MEMBER");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(orgAccessGuard).canAccess(ORG, CALLER, "workflow", workflowId.toString(), "MEMBER");
    }

    @Test
    @DisplayName("a VIEWER may still list triggers: the read gate uses canAccess, not canWrite")
    void viewerMayListTriggers() {
        when(orgAccessGuard.canAccess(eq(ORG), eq(CALLER), eq("workflow"), anyString(), eq("VIEWER")))
                .thenReturn(true);

        ResponseEntity<?> response = controller.getAvailableTriggers(RUN_ID, CALLER, ORG, "VIEWER");

        assertThat(response.getStatusCode()).isNotEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("canReadWorkflowResource fails closed on an org resource with no workflow id")
    void canReadWorkflowResourceFailsClosedOnBlankWorkflow() {
        assertThat(com.apimarketplace.orchestrator.controllers.workflow.WorkflowControllerHelper
                .canReadWorkflowResource(ORG, CALLER, null, "MEMBER", orgAccessGuard)).isFalse();
        assertThat(com.apimarketplace.orchestrator.controllers.workflow.WorkflowControllerHelper
                .canReadWorkflowResource(ORG, CALLER, "  ", "MEMBER", orgAccessGuard)).isFalse();
        // A personal resource has no deny-list, so no workflow id is needed there.
        assertThat(com.apimarketplace.orchestrator.controllers.workflow.WorkflowControllerHelper
                .canReadWorkflowResource(null, CALLER, null, null, orgAccessGuard)).isTrue();
    }
}
