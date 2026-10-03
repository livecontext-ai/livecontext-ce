package com.apimarketplace.orchestrator.schedule;

import com.apimarketplace.auth.client.access.OrgAccessGuard;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.services.notification.delivery.NotificationDeliveryService;
import com.apimarketplace.trigger.client.TriggerClient;
import com.apimarketplace.trigger.client.dto.ScheduledExecutionDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression: the owner paused a failing schedule, and a day later still received "Still
 * failing ... Next reminder in 24 hours". Pausing or deleting a schedule left the workflow's
 * failure incident open, and the daily reminder never asks whether the workflow still runs.
 *
 * <p>Every schedule stop on both controllers is enumerated here, with its counterpart that
 * must NOT close anything (a resume, a refusal, a schedule that was not found).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Stopping a schedule closes its workflow's failure incident")
class ScheduleStopClosesIncidentTest {

    private static final UUID WORKFLOW_ID = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID SCHEDULE_ID = UUID.fromString("00000000-0000-4000-8000-000000000002");
    private static final String TRIGGER_ID = "trigger:cron";
    private static final String TENANT = "tenant-A";
    private static final String ORG = "org-1";

    @Mock TriggerClient triggerClient;
    @Mock ScheduleExecutorService scheduleExecutorService;
    @Mock WorkflowRepository workflowRepository;
    @Mock OrgAccessGuard orgAccessGuard;
    @Mock NotificationDeliveryService delivery;

    private static ScheduledExecutionDto schedule(boolean enabled) {
        ScheduledExecutionDto dto = new ScheduledExecutionDto();
        dto.setId(SCHEDULE_ID);
        dto.setWorkflowId(WORKFLOW_ID);
        dto.setTriggerId(TRIGGER_ID);
        dto.setEnabled(enabled);
        return dto;
    }

    @Nested
    @DisplayName("/api/schedules (the Triggers list and the agenda)")
    class Overview {

        private ScheduleOverviewController controller;

        @BeforeEach
        void setUp() {
            controller = new ScheduleOverviewController(triggerClient, workflowRepository, true);
            controller.setNotificationDelivery(delivery);
        }

        @Test
        @DisplayName("pausing closes the incident")
        void pauseCloses() {
            when(triggerClient.toggleSchedule(SCHEDULE_ID, false, ORG, TENANT)).thenReturn(schedule(false));

            controller.toggle(TENANT, ORG, "MEMBER", SCHEDULE_ID, Map.of("enabled", false));

            verify(delivery).onWorkflowStopped(WORKFLOW_ID);
        }

        @Test
        @DisplayName("resuming closes nothing")
        void resumeClosesNothing() {
            when(triggerClient.toggleSchedule(SCHEDULE_ID, true, ORG, TENANT)).thenReturn(schedule(true));

            controller.toggle(TENANT, ORG, "MEMBER", SCHEDULE_ID, Map.of("enabled", true));

            verify(delivery, never()).onWorkflowStopped(any());
        }

        @Test
        @DisplayName("a VIEWER is refused before anything closes, even when the pause itself would have succeeded")
        void refusedViewerClosesNothing() {
            when(triggerClient.toggleSchedule(SCHEDULE_ID, false, ORG, TENANT)).thenReturn(schedule(false));

            assertThat(controller.toggle(TENANT, ORG, "VIEWER", SCHEDULE_ID, Map.of("enabled", false))
                    .getStatusCode().value()).isEqualTo(403);

            verify(delivery, never()).onWorkflowStopped(any());
        }

        @Test
        @DisplayName("an unknown schedule closes nothing")
        void unknownScheduleClosesNothing() {
            when(triggerClient.toggleSchedule(SCHEDULE_ID, false, ORG, TENANT)).thenReturn(null);

            assertThat(controller.toggle(TENANT, ORG, "MEMBER", SCHEDULE_ID, Map.of("enabled", false))
                    .getStatusCode().value()).isEqualTo(404);

            verify(delivery, never()).onWorkflowStopped(any());
        }

        @Test
        @DisplayName("deleting a schedule whose read failed still succeeds, and closes nothing on a guess")
        void deleteWithUnreadableSchedule() {
            when(triggerClient.getSchedule(SCHEDULE_ID)).thenReturn(null);
            when(triggerClient.archiveScheduleById(SCHEDULE_ID, "USER_DELETED", ORG, TENANT)).thenReturn(true);

            assertThat(controller.delete(TENANT, ORG, "MEMBER", SCHEDULE_ID).getStatusCode().value()).isEqualTo(200);

            verify(delivery, never()).onWorkflowStopped(any());
        }

        @Test
        @DisplayName("deleting closes the incident; a delete that archived nothing does not")
        void deleteCloses() {
            when(triggerClient.getSchedule(SCHEDULE_ID)).thenReturn(schedule(true));
            when(triggerClient.archiveScheduleById(SCHEDULE_ID, "USER_DELETED", ORG, TENANT)).thenReturn(false);
            assertThat(controller.delete(TENANT, ORG, "MEMBER", SCHEDULE_ID).getStatusCode().value()).isEqualTo(404);
            verify(delivery, never()).onWorkflowStopped(any());

            when(triggerClient.archiveScheduleById(SCHEDULE_ID, "USER_DELETED", ORG, TENANT)).thenReturn(true);
            assertThat(controller.delete(TENANT, ORG, "MEMBER", SCHEDULE_ID).getStatusCode().value()).isEqualTo(200);
            verify(delivery).onWorkflowStopped(WORKFLOW_ID);
        }

        @Test
        @DisplayName("without the delivery bean, pause and delete behave exactly as before")
        void worksWithoutDelivery() {
            ScheduleOverviewController bare = new ScheduleOverviewController(triggerClient, workflowRepository, true);
            when(triggerClient.toggleSchedule(SCHEDULE_ID, false, ORG, TENANT)).thenReturn(schedule(false));
            when(triggerClient.archiveScheduleById(SCHEDULE_ID, "USER_DELETED", ORG, TENANT)).thenReturn(true);

            assertThat(bare.toggle(TENANT, ORG, "MEMBER", SCHEDULE_ID, Map.of("enabled", false))
                    .getStatusCode().value()).isEqualTo(200);
            assertThat(bare.delete(TENANT, ORG, "MEMBER", SCHEDULE_ID).getStatusCode().value()).isEqualTo(200);
            verify(triggerClient, never()).getSchedule(any());
        }
    }

    @Nested
    @DisplayName("/api/v2/workflows/{id}/schedule (the builder)")
    class PerWorkflow {

        private ScheduleController controller;

        @BeforeEach
        void setUp() {
            controller = new ScheduleController(triggerClient, scheduleExecutorService, workflowRepository,
                    orgAccessGuard, true);
            controller.setNotificationDelivery(delivery);
            WorkflowEntity workflow = new WorkflowEntity();
            workflow.setId(WORKFLOW_ID);
            workflow.setTenantId(TENANT);
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow));
            when(triggerClient.getScheduleByWorkflowAndTrigger(WORKFLOW_ID, TRIGGER_ID, null))
                    .thenReturn(schedule(true));
        }

        @Test
        @DisplayName("pausing closes the incident; resuming does not")
        void pauseClosesResumeDoesNot() {
            when(triggerClient.toggleSchedule(SCHEDULE_ID, true, null, TENANT)).thenReturn(schedule(true));
            controller.toggleSchedule(WORKFLOW_ID.toString(), TRIGGER_ID,
                    new ScheduleController.ToggleRequest(true), TENANT, null, null);
            verify(delivery, never()).onWorkflowStopped(any());

            when(triggerClient.toggleSchedule(SCHEDULE_ID, false, null, TENANT)).thenReturn(schedule(false));
            controller.toggleSchedule(WORKFLOW_ID.toString(), TRIGGER_ID,
                    new ScheduleController.ToggleRequest(false), TENANT, null, null);
            verify(delivery).onWorkflowStopped(WORKFLOW_ID);
        }

        @Test
        @DisplayName("deleting one schedule closes the incident")
        void deleteOneCloses() {
            when(triggerClient.archiveScheduleById(SCHEDULE_ID, "USER_DELETED", null, TENANT)).thenReturn(true);

            controller.deleteSchedule(WORKFLOW_ID.toString(), TRIGGER_ID, TENANT, null, null);

            verify(delivery).onWorkflowStopped(WORKFLOW_ID);
        }

        @Test
        @DisplayName("deleting every schedule closes the incident; when there was none to delete it does not")
        void deleteAllCloses() {
            when(triggerClient.archiveSchedulesByWorkflow(WORKFLOW_ID, "USER_DELETED")).thenReturn(0);
            controller.deleteAllSchedules(WORKFLOW_ID.toString(), TENANT, null, null);
            verify(delivery, never()).onWorkflowStopped(any());

            when(triggerClient.archiveSchedulesByWorkflow(WORKFLOW_ID, "USER_DELETED")).thenReturn(2);
            controller.deleteAllSchedules(WORKFLOW_ID.toString(), TENANT, null, null);
            verify(delivery).onWorkflowStopped(WORKFLOW_ID);
        }

        @Test
        @DisplayName("a caller outside the workflow's scope is refused before anything closes, even when the stop itself would have succeeded")
        void outOfScopeClosesNothing() {
            when(triggerClient.toggleSchedule(SCHEDULE_ID, false, null, "someone-else")).thenReturn(schedule(false));
            when(triggerClient.archiveSchedulesByWorkflow(WORKFLOW_ID, "USER_DELETED")).thenReturn(2);
            when(triggerClient.archiveScheduleById(SCHEDULE_ID, "USER_DELETED", null, "someone-else")).thenReturn(true);

            assertThat(controller.toggleSchedule(WORKFLOW_ID.toString(), TRIGGER_ID,
                    new ScheduleController.ToggleRequest(false), "someone-else", null, null)
                    .getStatusCode().is2xxSuccessful()).isFalse();
            assertThat(controller.deleteAllSchedules(WORKFLOW_ID.toString(), "someone-else", null, null)
                    .getStatusCode().is2xxSuccessful()).isFalse();
            assertThat(controller.deleteSchedule(WORKFLOW_ID.toString(), TRIGGER_ID, "someone-else", null, null)
                    .getStatusCode().is2xxSuccessful()).isFalse();

            verify(delivery, never()).onWorkflowStopped(any());
        }

        @Test
        @DisplayName("a pause the trigger service did not confirm, and a delete that archived nothing, close nothing")
        void unconfirmedStopClosesNothing() {
            when(triggerClient.toggleSchedule(SCHEDULE_ID, false, null, TENANT)).thenReturn(null);
            when(triggerClient.archiveScheduleById(SCHEDULE_ID, "USER_DELETED", null, TENANT)).thenReturn(false);

            controller.toggleSchedule(WORKFLOW_ID.toString(), TRIGGER_ID,
                    new ScheduleController.ToggleRequest(false), TENANT, null, null);
            controller.deleteSchedule(WORKFLOW_ID.toString(), TRIGGER_ID, TENANT, null, null);

            verify(delivery, never()).onWorkflowStopped(any());
        }
    }
}
