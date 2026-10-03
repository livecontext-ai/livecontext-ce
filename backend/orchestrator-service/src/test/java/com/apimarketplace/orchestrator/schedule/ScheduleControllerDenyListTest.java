package com.apimarketplace.orchestrator.schedule;

import com.apimarketplace.auth.client.access.OrgAccessGuard;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.trigger.client.TriggerClient;
import com.apimarketplace.trigger.client.dto.ScheduleCreateRequest;
import com.apimarketplace.trigger.client.dto.ScheduledExecutionDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * LC-012 (security audit 2026-08-13): the schedule surface only refused the VIEWER role, so a
 * MEMBER an owner had deny-listed from a specific workflow could still run it through
 * {@code execute-now}, install a cron row that fires it forever, or read its schedules. Each
 * write case asserts the 403 AND that nothing downstream was called.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ScheduleController - per-workflow deny-list gate (LC-012)")
class ScheduleControllerDenyListTest {

    private static final UUID WORKFLOW_ID = UUID.fromString("00000000-0000-4000-8000-0000000000bb");
    private static final String TRIGGER_ID = "trigger:cron";
    private static final String CALLER = "member-1";
    private static final String ORG = "org-1";

    @Mock TriggerClient triggerClient;
    @Mock ScheduleExecutorService scheduleExecutorService;
    @Mock WorkflowRepository workflowRepository;
    @Mock OrgAccessGuard orgAccessGuard;

    private ScheduleController controller;

    @BeforeEach
    void setUp() {
        controller = new ScheduleController(triggerClient, scheduleExecutorService, workflowRepository,
                orgAccessGuard, true);
        WorkflowEntity workflow = new WorkflowEntity();
        workflow.setId(WORKFLOW_ID);
        workflow.setTenantId("owner-1");
        workflow.setOrganizationId(ORG);
        workflow.setPinnedVersion(3);
        when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow));
    }

    private void denyListed() {
        when(orgAccessGuard.canWrite(eq(ORG), eq(CALLER), eq("workflow"), eq(WORKFLOW_ID.toString()), any()))
                .thenReturn(false);
        when(orgAccessGuard.canAccess(eq(ORG), eq(CALLER), eq("workflow"), eq(WORKFLOW_ID.toString()), any()))
                .thenReturn(false);
    }

    @SuppressWarnings("unchecked")
    private static void assertDenied(ResponseEntity<?> response) {
        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat((Map<String, Object>) response.getBody()).containsEntry("reason", "ORG_ACCESS_DENIED");
    }

    @Test
    @DisplayName("deny-listed MEMBER cannot execute-now")
    void denyListedMemberCannotExecuteNow() {
        denyListed();
        assertDenied(controller.executeNow(WORKFLOW_ID.toString(), TRIGGER_ID, CALLER, ORG, "MEMBER"));
        verifyNoInteractions(scheduleExecutorService);
    }

    @Test
    @DisplayName("deny-listed MEMBER cannot install a cron row")
    void denyListedMemberCannotCreate() {
        denyListed();
        assertDenied(controller.createOrUpdateSchedule(WORKFLOW_ID.toString(), TRIGGER_ID,
                new ScheduleCreateRequest("0 9 * * *", "UTC", null, true, null),
                CALLER, ORG, "MEMBER", "PRO"));
        verify(triggerClient, never()).createOrUpdateSchedule(any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("deny-listed MEMBER cannot toggle or delete")
    void denyListedMemberCannotToggleOrDelete() {
        denyListed();
        assertDenied(controller.toggleSchedule(WORKFLOW_ID.toString(), TRIGGER_ID,
                new ScheduleController.ToggleRequest(false), CALLER, ORG, "MEMBER"));
        assertDenied(controller.deleteAllSchedules(WORKFLOW_ID.toString(), CALLER, ORG, "MEMBER"));
        assertDenied(controller.deleteSchedule(WORKFLOW_ID.toString(), TRIGGER_ID, CALLER, ORG, "MEMBER"));
        verify(triggerClient, never()).toggleSchedule(any(), anyBoolean(), anyString(), anyString());
        verify(triggerClient, never()).archiveSchedulesByWorkflow(any(), anyString());
    }

    @Test
    @DisplayName("deny-listed MEMBER cannot read the schedule status either")
    void denyListedMemberCannotReadStatus() {
        denyListed();
        assertDenied(controller.getScheduleStatus(WORKFLOW_ID.toString(), CALLER, ORG, "MEMBER"));
        assertDenied(controller.getScheduleStatusForTrigger(WORKFLOW_ID.toString(), TRIGGER_ID, CALLER, ORG, "MEMBER"));
        verify(triggerClient, never()).getSchedulesByWorkflow(any(), any());
    }

    @Test
    @DisplayName("the gate fails closed when the guard bean is absent")
    void failsClosedWithoutGuard() {
        ScheduleController noGuard = new ScheduleController(triggerClient, scheduleExecutorService,
                workflowRepository, null, true);
        assertDenied(noGuard.executeNow(WORKFLOW_ID.toString(), TRIGGER_ID, CALLER, ORG, "MEMBER"));
        verifyNoInteractions(scheduleExecutorService);
    }

    @Test
    @DisplayName("an allowed MEMBER still reads and fires (no over-blocking)")
    void allowedMemberPasses() {
        when(orgAccessGuard.canWrite(anyString(), anyString(), anyString(), anyString(), any())).thenReturn(true);
        when(orgAccessGuard.canAccess(anyString(), anyString(), anyString(), anyString(), any())).thenReturn(true);
        when(triggerClient.getSchedulesByWorkflow(any(), any())).thenReturn(List.of());
        ScheduledExecutionDto schedule = new ScheduledExecutionDto();
        when(triggerClient.getScheduleByWorkflowAndTrigger(any(), anyString(), any())).thenReturn(schedule);

        assertThat(controller.getScheduleStatus(WORKFLOW_ID.toString(), CALLER, ORG, "MEMBER")
                .getStatusCode().value()).isEqualTo(200);
        assertThat(controller.deleteAllSchedules(WORKFLOW_ID.toString(), CALLER, ORG, "MEMBER")
                .getStatusCode().value()).isNotEqualTo(403);
    }
}
