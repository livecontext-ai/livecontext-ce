package com.apimarketplace.trigger.controller;

import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.trigger.client.dto.ScheduleCreateRequest;
import com.apimarketplace.trigger.domain.ScheduledExecutionEntity;
import com.apimarketplace.trigger.repository.ScheduledExecutionRepository;
import com.apimarketplace.trigger.service.PlanLimitHelper;
import com.apimarketplace.trigger.service.ScheduleCronParser;
import com.apimarketplace.trigger.service.TriggerLifecycleManager;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

/**
 * Regression tests for the just-shipped ScheduleController.createOrUpdateSchedule
 * org-stamp fix.
 *
 * <p>Bug shape: prior to the fix, the public REST endpoint
 * {@code POST /api/v2/workflows/{workflowId}/schedule/{triggerId}} created
 * {@link ScheduledExecutionEntity} rows without {@code organization_id},
 * because the entity constructor signature does not accept an org id. Schedules
 * created by an org-workspace member therefore landed NULL-org and were
 * invisible to {@code ActiveAutomationsService.getSchedulesByOrganization}
 * (the data source for the bell-icon Triggers tab in org workspaces).
 *
 * <p>The fix stamps {@code organization_id} from the resolved
 * {@code X-Organization-ID} header on every save where the row's value is
 * currently null. This covers both:
 * (1) new row creation (org id stamped at first save),
 * (2) defensive backfill on update of legacy NULL-org rows (so a workflow
 *     re-saved from an org workspace upgrades silently).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ScheduleController.createOrUpdateSchedule - organization_id stamping")
class ScheduleControllerOrgStampTest {

    @Mock private ScheduledExecutionRepository scheduleRepository;
    @Mock private ScheduleCronParser cronParser;
    @Mock private TenantResolver tenantResolver;
    @Mock private PlanLimitHelper planLimitHelper;
    @Mock private TriggerLifecycleManager triggerLifecycleManager;
    @Mock private HttpServletRequest request;

    private ScheduleController controller;

    private static final UUID WORKFLOW_ID = UUID.randomUUID();
    private static final String TRIGGER_ID = "trigger:daily_9am";
    private static final String TENANT_ID = "tenant-alice";

    @BeforeEach
    void setUp() {
        controller = new ScheduleController(scheduleRepository, cronParser, tenantResolver,
                planLimitHelper, triggerLifecycleManager);
        when(tenantResolver.resolve(request)).thenReturn(TENANT_ID);
        when(cronParser.isValid("0 9 * * *")).thenReturn(true);
        when(cronParser.getNextExecution("0 9 * * *", "UTC"))
                .thenReturn(Instant.parse("2026-05-18T09:00:00Z"));
        when(cronParser.getDescription("0 9 * * *")).thenReturn("At 09:00 every day");
        when(planLimitHelper.getMaxEndpoints(any())).thenReturn(100);
        when(scheduleRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private ScheduleCreateRequest createRequest() {
        return new ScheduleCreateRequest("0 9 * * *", "UTC", null, true, null);
    }

    @Test
    @DisplayName("createOrUpdateSchedule_newRow_stampsOrganizationIdFromHeader - new schedule row inherits the X-Organization-ID header (bell Triggers tab visibility)")
    void createOrUpdateSchedule_newRow_stampsOrganizationIdFromHeader() {
        // Arrange - no existing row, caller sits in ORG-1.
        when(tenantResolver.resolveOrgId(request)).thenReturn("ORG-1");
        when(scheduleRepository.existsByWorkflowIdAndTriggerId(WORKFLOW_ID, TRIGGER_ID)).thenReturn(false);
        when(scheduleRepository.countActiveByOrganizationIdStrict("ORG-1")).thenReturn(0L);
        when(scheduleRepository.findAllByWorkflowIdAndTriggerId(WORKFLOW_ID, TRIGGER_ID))
                .thenReturn(List.of());

        // Act
        ResponseEntity<Map<String, Object>> response =
                controller.createOrUpdateSchedule(WORKFLOW_ID, TRIGGER_ID, createRequest(), request);

        // Assert - the persisted row carries organization_id = ORG-1.
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        ArgumentCaptor<ScheduledExecutionEntity> captor =
                ArgumentCaptor.forClass(ScheduledExecutionEntity.class);
        verify(scheduleRepository).save(captor.capture());
        assertThat(captor.getValue().getOrganizationId())
                .as("new schedule must inherit X-Organization-ID so org-scoped Triggers tab surfaces it")
                .isEqualTo("ORG-1");
    }

    @Test
    @DisplayName("createOrUpdateSchedule_existingNullOrgRow_backfillsFromHeader - defensive backfill upgrades legacy NULL-org rows on update")
    void createOrUpdateSchedule_existingNullOrgRow_backfillsFromHeader() {
        // Arrange - pre-existing row with organization_id=null (legacy pre-V218 row),
        // caller now operates in ORG-2 and re-saves the schedule.
        when(tenantResolver.resolveOrgId(request)).thenReturn("ORG-2");
        when(scheduleRepository.existsByWorkflowIdAndTriggerId(WORKFLOW_ID, TRIGGER_ID)).thenReturn(true);
        ScheduledExecutionEntity legacy = new ScheduledExecutionEntity(
                WORKFLOW_ID, TRIGGER_ID, TENANT_ID, "0 0 * * *", "UTC", Instant.now());
        // Constructor leaves organization_id null - confirms the pre-fix shape.
        assertThat(legacy.getOrganizationId()).isNull();
        when(scheduleRepository.findAllByWorkflowIdAndTriggerId(WORKFLOW_ID, TRIGGER_ID))
                .thenReturn(new java.util.ArrayList<>(List.of(legacy)));

        // Act
        ResponseEntity<Map<String, Object>> response =
                controller.createOrUpdateSchedule(WORKFLOW_ID, TRIGGER_ID, createRequest(), request);

        // Assert - the legacy row's organization_id is now ORG-2 (defensive backfill).
        // Without this branch, V218-era NULL-org rows would stay invisible to org list
        // endpoints forever, even after the workflow was migrated to an org workspace.
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        ArgumentCaptor<ScheduledExecutionEntity> captor =
                ArgumentCaptor.forClass(ScheduledExecutionEntity.class);
        verify(scheduleRepository).save(captor.capture());
        assertThat(captor.getValue().getOrganizationId())
                .as("legacy NULL-org row must be backfilled to caller's active org on update")
                .isEqualTo("ORG-2");
    }
}
