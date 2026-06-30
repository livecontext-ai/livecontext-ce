package com.apimarketplace.orchestrator.schedule;

import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.trigger.client.TriggerClient;
import com.apimarketplace.trigger.client.dto.ScheduledExecutionDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ScheduleOverviewController")
class ScheduleOverviewControllerTest {

    @Mock private TriggerClient triggerClient;
    @Mock private WorkflowRepository workflowRepository;

    private ScheduleOverviewController controller;

    private static final String TENANT_ID = "user-789";
    private static final String ORG_ID = "8dc5a88d-1a38-4838-b517-fd0b88df7a88";
    private static final UUID WORKFLOW_ID_1 = UUID.randomUUID();
    private static final UUID WORKFLOW_ID_2 = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        controller = new ScheduleOverviewController(triggerClient, workflowRepository, true);
    }

    private ScheduledExecutionDto createScheduleDto(UUID workflowId, String triggerId, boolean enabled) {
        ScheduledExecutionDto dto = new ScheduledExecutionDto();
        dto.setId(UUID.randomUUID());
        dto.setWorkflowId(workflowId);
        dto.setTriggerId(triggerId);
        dto.setTenantId(TENANT_ID);
        dto.setCronExpression("0 9 * * *");
        dto.setTimezone("UTC");
        dto.setEnabled(enabled);
        dto.setExecutionCount(5);
        dto.setNextExecutionAt(Instant.now().plusSeconds(3600));
        dto.setLastExecutionAt(Instant.now().minusSeconds(3600));
        dto.setCreatedAt(Instant.now());
        return dto;
    }

    // ==================== getAll ====================

    @Nested
    @DisplayName("getAll()")
    class GetAllTests {

        @Test
        @DisplayName("Returns empty list when no schedules")
        void emptySchedules() {
            when(triggerClient.getSchedulesByTenant(TENANT_ID)).thenReturn(Collections.emptyList());

            ResponseEntity<List<ScheduleOverviewController.ScheduleOverviewResponse>> response =
                    controller.getAll(TENANT_ID, null);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).isEmpty();
        }

        @Test
        @DisplayName("Returns schedules with workflow names resolved")
        void schedulesWithWorkflowNames() {
            ScheduledExecutionDto s1 = createScheduleDto(WORKFLOW_ID_1, "trigger:cron_1", true);
            ScheduledExecutionDto s2 = createScheduleDto(WORKFLOW_ID_2, "trigger:cron_2", false);

            when(triggerClient.getSchedulesByTenant(TENANT_ID)).thenReturn(List.of(s1, s2));

            WorkflowEntity wf1 = new WorkflowEntity();
            wf1.setName("Invoice Processor");
            WorkflowEntity wf2 = new WorkflowEntity();
            wf2.setName("Daily Report");

            when(workflowRepository.findById(WORKFLOW_ID_1)).thenReturn(Optional.of(wf1));
            when(workflowRepository.findById(WORKFLOW_ID_2)).thenReturn(Optional.of(wf2));

            ResponseEntity<List<ScheduleOverviewController.ScheduleOverviewResponse>> response =
                    controller.getAll(TENANT_ID, null);

            assertThat(response.getBody()).hasSize(2);
            assertThat(response.getBody().get(0).workflowName()).isEqualTo("Invoice Processor");
            assertThat(response.getBody().get(1).workflowName()).isEqualTo("Daily Report");
        }

        @Test
        @DisplayName("Handles deleted workflow gracefully (shows 'Unknown')")
        void deletedWorkflowShowsUnknown() {
            ScheduledExecutionDto s = createScheduleDto(WORKFLOW_ID_1, "trigger:orphan", true);

            when(triggerClient.getSchedulesByTenant(TENANT_ID)).thenReturn(List.of(s));
            when(workflowRepository.findById(WORKFLOW_ID_1)).thenReturn(Optional.empty());

            ResponseEntity<List<ScheduleOverviewController.ScheduleOverviewResponse>> response =
                    controller.getAll(TENANT_ID, null);

            assertThat(response.getBody()).hasSize(1);
            assertThat(response.getBody().get(0).workflowName()).isEqualTo("Unknown");
        }

        @Test
        @DisplayName("Multiple schedules from same workflow share one lookup")
        void sameWorkflowMultipleSchedules() {
            ScheduledExecutionDto s1 = createScheduleDto(WORKFLOW_ID_1, "trigger:morning", true);
            ScheduledExecutionDto s2 = createScheduleDto(WORKFLOW_ID_1, "trigger:evening", true);

            when(triggerClient.getSchedulesByTenant(TENANT_ID)).thenReturn(List.of(s1, s2));

            WorkflowEntity wf = new WorkflowEntity();
            wf.setName("Shared Workflow");
            when(workflowRepository.findById(WORKFLOW_ID_1)).thenReturn(Optional.of(wf));

            ResponseEntity<List<ScheduleOverviewController.ScheduleOverviewResponse>> response =
                    controller.getAll(TENANT_ID, null);

            assertThat(response.getBody()).hasSize(2);
            // Both should have same workflow name
            assertThat(response.getBody().get(0).workflowName()).isEqualTo("Shared Workflow");
            assertThat(response.getBody().get(1).workflowName()).isEqualTo("Shared Workflow");
            // Only 1 lookup should happen
            verify(workflowRepository, times(1)).findById(WORKFLOW_ID_1);
        }

        @Test
        @DisplayName("Hides archived inactive schedules while keeping user-disabled schedules")
        void hidesArchivedInactiveSchedulesButKeepsUserDisabledSchedules() {
            ScheduledExecutionDto active = createScheduleDto(null, "trigger:active", true);
            ScheduledExecutionDto userDisabled = createScheduleDto(null, "trigger:user_disabled", false);
            userDisabled.setIsActive(true);
            ScheduledExecutionDto archived = createScheduleDto(null, "trigger:archived", false);
            archived.setIsActive(false);

            when(triggerClient.getSchedulesByTenant(TENANT_ID)).thenReturn(List.of(active, userDisabled, archived));

            ResponseEntity<List<ScheduleOverviewController.ScheduleOverviewResponse>> response =
                    controller.getAll(TENANT_ID, null);

            assertThat(response.getBody())
                    .extracting(ScheduleOverviewController.ScheduleOverviewResponse::id)
                    .containsExactly(active.getId().toString(), userDisabled.getId().toString());
        }

        @Test
        @DisplayName("Active organization scope lists organization schedules instead of personal schedules")
        void activeOrganizationScopeListsOrganizationSchedules() {
            ScheduledExecutionDto orgSchedule = createScheduleDto(null, "trigger:org_schedule", true);
            orgSchedule.setName("Org Standalone Schedule");
            orgSchedule.setOrganizationId(ORG_ID);

            when(triggerClient.getSchedulesByOrganization(ORG_ID)).thenReturn(List.of(orgSchedule));

            ResponseEntity<List<ScheduleOverviewController.ScheduleOverviewResponse>> response =
                    controller.getAll(TENANT_ID, ORG_ID);

            assertThat(response.getBody())
                    .extracting(ScheduleOverviewController.ScheduleOverviewResponse::name)
                    .containsExactly("Org Standalone Schedule");
            verify(triggerClient).getSchedulesByOrganization(ORG_ID);
            verify(triggerClient, never()).getSchedulesByTenant(TENANT_ID);
        }
    }

    // ==================== getConfig ====================

    @Nested
    @DisplayName("getConfig()")
    class GetConfigTests {

        @Test
        @DisplayName("FREE plan: max=3")
        void freePlanConfig() {
            when(triggerClient.countSchedulesByTenant(TENANT_ID, null)).thenReturn(2L);

            ResponseEntity<Map<String, Object>> response = controller.getConfig(TENANT_ID, null, "FREE");

            assertThat(response.getBody()).containsEntry("currentCount", 2L);
            assertThat(response.getBody()).containsEntry("maxPerUser", 3);
        }

        @Test
        @DisplayName("PRO plan: max=50")
        void proPlanConfig() {
            when(triggerClient.countSchedulesByTenant(TENANT_ID, null)).thenReturn(10L);

            ResponseEntity<Map<String, Object>> response = controller.getConfig(TENANT_ID, null, "PRO");

            assertThat(response.getBody()).containsEntry("currentCount", 10L);
            assertThat(response.getBody()).containsEntry("maxPerUser", 50);
        }

        @Test
        @DisplayName("Null plan: uses default (10)")
        void nullPlanConfig() {
            when(triggerClient.countSchedulesByTenant(TENANT_ID, null)).thenReturn(0L);

            ResponseEntity<Map<String, Object>> response = controller.getConfig(TENANT_ID, null, null);

            assertThat(response.getBody()).containsEntry("currentCount", 0L);
            assertThat(response.getBody()).containsEntry("maxPerUser", 10);
        }

        @Test
        @DisplayName("Enterprise shorthand: max=100")
        void enterpriseConfig() {
            when(triggerClient.countSchedulesByTenant(TENANT_ID, null)).thenReturn(50L);

            ResponseEntity<Map<String, Object>> response = controller.getConfig(TENANT_ID, null, "ENTERPRISE");

            assertThat(response.getBody()).containsEntry("currentCount", 50L);
            assertThat(response.getBody()).containsEntry("maxPerUser", 100);
        }

        @Test
        @DisplayName("Zero schedules returns zero count")
        void zeroSchedules() {
            when(triggerClient.countSchedulesByTenant(TENANT_ID, null)).thenReturn(0L);

            ResponseEntity<Map<String, Object>> response = controller.getConfig(TENANT_ID, null, "STARTER");

            assertThat(response.getBody()).containsEntry("currentCount", 0L);
        }

        @Test
        @DisplayName("Active organization scope counts organization schedules")
        void activeOrganizationScopeCountsOrganizationSchedules() {
            when(triggerClient.countSchedulesByTenant(TENANT_ID, ORG_ID)).thenReturn(3L);

            ResponseEntity<Map<String, Object>> response = controller.getConfig(TENANT_ID, ORG_ID, "CE");

            assertThat(response.getBody()).containsEntry("currentCount", 3L);
            verify(triggerClient).countSchedulesByTenant(TENANT_ID, ORG_ID);
        }
    }

    // ==================== toggle ====================

    @Nested
    @DisplayName("toggle()")
    class ToggleTests {

        @Test
        @DisplayName("Toggle enable success")
        void toggleEnable() {
            UUID scheduleId = UUID.randomUUID();
            ScheduledExecutionDto result = createScheduleDto(WORKFLOW_ID_1, "trigger:t1", true);
            when(triggerClient.toggleSchedule(scheduleId, true, ORG_ID, TENANT_ID)).thenReturn(result);

            ResponseEntity<?> response = controller.toggle(TENANT_ID, ORG_ID, scheduleId, Map.of("enabled", true));

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            verify(triggerClient).toggleSchedule(scheduleId, true, ORG_ID, TENANT_ID);
        }

        @Test
        @DisplayName("Toggle disable success")
        void toggleDisable() {
            UUID scheduleId = UUID.randomUUID();
            ScheduledExecutionDto result = createScheduleDto(WORKFLOW_ID_1, "trigger:t1", false);
            when(triggerClient.toggleSchedule(scheduleId, false, ORG_ID, TENANT_ID)).thenReturn(result);

            ResponseEntity<?> response = controller.toggle(TENANT_ID, ORG_ID, scheduleId, Map.of("enabled", false));

            assertThat(response.getStatusCode().value()).isEqualTo(200);
        }

        @Test
        @DisplayName("Toggle nonexistent schedule returns 404")
        void toggleNotFound() {
            UUID scheduleId = UUID.randomUUID();
            when(triggerClient.toggleSchedule(scheduleId, true, ORG_ID, TENANT_ID)).thenReturn(null);

            ResponseEntity<?> response = controller.toggle(TENANT_ID, ORG_ID, scheduleId, Map.of("enabled", true));

            assertThat(response.getStatusCode().value()).isEqualTo(404);
        }
    }

    // ==================== delete ====================

    @Nested
    @DisplayName("delete()")
    class DeleteTests {

        @Test
        @DisplayName("Delete schedule archives via TriggerClient (v5: routes through archive, not hard-delete)")
        void deleteSchedule() {
            UUID scheduleId = UUID.randomUUID();
            when(triggerClient.archiveScheduleById(scheduleId, "USER_DELETED", ORG_ID, TENANT_ID)).thenReturn(true);

            ResponseEntity<?> response = controller.delete(TENANT_ID, ORG_ID, scheduleId);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            // v5: hard-delete was replaced with archive so the audit log + execution_count
            // survive (USER_DELETED reason). The 180-day reaper sweeps later.
            verify(triggerClient).archiveScheduleById(scheduleId, "USER_DELETED", ORG_ID, TENANT_ID);
        }

        @Test
        @DisplayName("Cross-organization schedule delete returns 404")
        void deleteScheduleFromDifferentOrganization() {
            UUID scheduleId = UUID.randomUUID();
            when(triggerClient.archiveScheduleById(scheduleId, "USER_DELETED", ORG_ID, TENANT_ID)).thenReturn(false);

            ResponseEntity<?> response = controller.delete(TENANT_ID, ORG_ID, scheduleId);

            assertThat(response.getStatusCode().value()).isEqualTo(404);
        }
    }

    // ==================== ScheduleOverviewResponse record ====================

    @Nested
    @DisplayName("ScheduleOverviewResponse.fromDto()")
    class ResponseMappingTests {

        @Test
        @DisplayName("Maps all fields from DTO correctly")
        void mapsAllFields() {
            ScheduledExecutionDto dto = createScheduleDto(WORKFLOW_ID_1, "trigger:daily", true);
            dto.setMaxExecutions(100);

            ScheduleOverviewController.ScheduleOverviewResponse response =
                    ScheduleOverviewController.ScheduleOverviewResponse.fromDto(dto, "My Workflow");

            assertThat(response.id()).isEqualTo(dto.getId().toString());
            assertThat(response.workflowId()).isEqualTo(WORKFLOW_ID_1.toString());
            assertThat(response.workflowName()).isEqualTo("My Workflow");
            assertThat(response.triggerId()).isEqualTo("trigger:daily");
            assertThat(response.cronExpression()).isEqualTo("0 9 * * *");
            assertThat(response.timezone()).isEqualTo("UTC");
            assertThat(response.enabled()).isTrue();
            assertThat(response.maxExecutions()).isEqualTo(100);
            assertThat(response.executionCount()).isEqualTo(5);
            assertThat(response.nextExecutionAt()).isNotNull();
            assertThat(response.lastExecutionAt()).isNotNull();
            assertThat(response.createdAt()).isNotNull();
        }

        @Test
        @DisplayName("Handles null timestamps")
        void handlesNullTimestamps() {
            ScheduledExecutionDto dto = new ScheduledExecutionDto();
            dto.setId(UUID.randomUUID());
            dto.setWorkflowId(WORKFLOW_ID_1);
            dto.setTriggerId("trigger:t");
            dto.setCronExpression("* * * * *");
            dto.setTimezone("UTC");

            ScheduleOverviewController.ScheduleOverviewResponse response =
                    ScheduleOverviewController.ScheduleOverviewResponse.fromDto(dto, "WF");

            assertThat(response.nextExecutionAt()).isNull();
            assertThat(response.lastExecutionAt()).isNull();
            assertThat(response.createdAt()).isNull();
        }
    }
}
