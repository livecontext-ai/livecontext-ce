package com.apimarketplace.orchestrator.schedule;

import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.trigger.client.TriggerClient;
import com.apimarketplace.trigger.client.dto.ScheduleCreateRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * A read-only member may not write, on the OTHER door into the same rows.
 *
 * <p>The agenda's own writes were gated first, and that was not enough: this controller
 * exposes the same operations on the same schedules under
 * {@code /api/v2/workflows/{id}/schedule/**}. A VIEWER refused at
 * {@code /api/agenda/schedules/{id}/run-now} could still POST {@code execute-now} here and
 * start the identical credit-spending run, or DELETE the schedule outright. Its only guard
 * was {@code guardWorkflowScope}, which answers "may this caller SEE this workflow" and has
 * no role check at all.
 *
 * <p>This is the third time in this feature that a rule landed on one call site and not its
 * siblings, so every write is enumerated here rather than sampled, and each case asserts the
 * refusal AND that nothing was called - a 403 that still wrote would be the same bug with a
 * better status code.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ScheduleController - VIEWER gate on every write")
class ScheduleControllerViewerGateTest {

    @Mock TriggerClient triggerClient;
    @Mock ScheduleExecutorService scheduleExecutorService;
    @Mock WorkflowRepository workflowRepository;
    @Mock com.apimarketplace.auth.client.access.OrgAccessGuard orgAccessGuard;

    private static final UUID WORKFLOW_ID = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final String TRIGGER_ID = "trigger:cron";
    private static final String TENANT = "tenant-A";
    private static final String ORG = "org-1";

    private ScheduleController controller;

    @BeforeEach
    void setUp() {
        controller = new ScheduleController(triggerClient, scheduleExecutorService, workflowRepository, orgAccessGuard, true);
        // The deny-list half of the gate allows by default here; this class pins the ROLE half.
        when(orgAccessGuard.canWrite(anyString(), anyString(), anyString(), anyString(), any())).thenReturn(true);
        WorkflowEntity workflow = new WorkflowEntity();
        workflow.setId(WORKFLOW_ID);
        workflow.setTenantId(TENANT);
        workflow.setOrganizationId(ORG);
        workflow.setPinnedVersion(3);
        when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow));
    }

    @SuppressWarnings("unchecked")
    private static void assertRefused(ResponseEntity<?> response) {
        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat((Map<String, Object>) response.getBody())
                .containsEntry("reason", "VIEWER_ROLE");
    }

    @Test
    @DisplayName("toggle is refused")
    void toggleRefused() {
        assertRefused(controller.toggleSchedule(WORKFLOW_ID.toString(), TRIGGER_ID,
                new ScheduleController.ToggleRequest(true), TENANT, ORG, "VIEWER"));

        verify(triggerClient, never()).toggleSchedule(any(), org.mockito.ArgumentMatchers.anyBoolean(),
                anyString(), anyString());
    }

    @Test
    @DisplayName("execute-now is refused - it spends credits")
    void executeNowRefused() {
        // The one that matters most: the agenda's run-early is gated, and this starts the
        // same run by a different URL.
        assertRefused(controller.executeNow(WORKFLOW_ID.toString(), TRIGGER_ID, TENANT, ORG, "VIEWER"));

        verifyNoInteractions(scheduleExecutorService);
    }

    @Test
    @DisplayName("create/update is refused")
    void createOrUpdateRefused() {
        assertRefused(controller.createOrUpdateSchedule(WORKFLOW_ID.toString(), TRIGGER_ID,
                new ScheduleCreateRequest("0 9 * * *", "UTC", null, true, null),
                TENANT, ORG, "VIEWER", "FREE"));

        verify(triggerClient, never()).createOrUpdateSchedule(any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("delete-all is refused - it archives, and archiving is permanent")
    void deleteAllRefused() {
        assertRefused(controller.deleteAllSchedules(WORKFLOW_ID.toString(), TENANT, ORG, "VIEWER"));

        verify(triggerClient, never()).archiveSchedulesByWorkflow(any(), anyString());
    }

    @Test
    @DisplayName("delete one is refused")
    void deleteOneRefused() {
        assertRefused(controller.deleteSchedule(WORKFLOW_ID.toString(), TRIGGER_ID, TENANT, ORG, "VIEWER"));

        verify(triggerClient, never()).archiveScheduleById(any(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("a MEMBER is not refused - the gate must not lock out everyone")
    void memberPassesTheGate() {
        // The over-correction this guards against: refusing on any role, or on a missing
        // one, turns a security fix into an outage for ordinary users.
        assertThat(controller.deleteAllSchedules(WORKFLOW_ID.toString(), TENANT, ORG, "MEMBER")
                .getStatusCode().value()).isNotEqualTo(403);
        assertThat(controller.deleteAllSchedules(WORKFLOW_ID.toString(), TENANT, ORG, null)
                .getStatusCode().value()).isNotEqualTo(403);
    }

    @Test
    @DisplayName("a personal workspace has no roles, so nothing is refused there")
    void personalWorkspaceIsUnaffected() {
        assertThat(controller.deleteAllSchedules(WORKFLOW_ID.toString(), TENANT, null, "VIEWER")
                .getStatusCode().value()).isNotEqualTo(403);
    }
}
