package com.apimarketplace.trigger.controller;

import com.apimarketplace.trigger.domain.ScheduledExecutionEntity;
import com.apimarketplace.trigger.client.dto.ScheduledExecutionDto;
import com.apimarketplace.trigger.repository.ScheduledExecutionRepository;
import com.apimarketplace.trigger.repository.StandaloneChatEndpointRepository;
import com.apimarketplace.trigger.repository.StandaloneFormEndpointRepository;
import com.apimarketplace.trigger.repository.StandaloneWebhookRepository;
import com.apimarketplace.trigger.service.ScheduleCronParser;
import com.apimarketplace.trigger.service.StandaloneChatEndpointService;
import com.apimarketplace.trigger.service.StandaloneFormEndpointService;
import com.apimarketplace.trigger.service.StandaloneScheduleService;
import com.apimarketplace.trigger.service.StandaloneWebhookService;
import com.apimarketplace.trigger.service.TriggerLifecycleManager;
import com.apimarketplace.trigger.service.WebhookTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression test for the just-shipped InternalTriggerController.createOrUpdateScheduleInternal
 * org-stamp fix.
 *
 * <p>Bug shape: prior to the fix, callers of the internal endpoint
 * {@code POST /api/internal/trigger/schedules/create} could not override the
 * {@code X-Organization-ID} header by passing {@code organizationId} in the
 * request body. The orchestrator workflow-save path threads the active org
 * through the body field (since the X-Organization-ID header forwarder in
 * PR16 is silent for async daemon callers), so without body-precedence the
 * row landed NULL-org - the same root cause class as the public endpoint.
 *
 * <p>The fix: body field wins over header (explicit caller intent). When
 * the body provides a non-blank {@code organizationId}, the entity is
 * stamped from it; the header is only the fallback.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InternalTriggerController.createOrUpdateScheduleInternal - body organizationId precedence")
class InternalTriggerControllerScheduleOrgStampTest {

    @Mock private WebhookTokenService tokenService;
    @Mock private StandaloneWebhookService standaloneWebhookService;
    @Mock private StandaloneScheduleService standaloneScheduleService;
    @Mock private StandaloneChatEndpointService chatEndpointService;
    @Mock private StandaloneFormEndpointService formEndpointService;
    @Mock private StandaloneWebhookRepository webhookRepository;
    @Mock private StandaloneChatEndpointRepository chatEndpointRepository;
    @Mock private StandaloneFormEndpointRepository formEndpointRepository;
    @Mock private ScheduledExecutionRepository scheduleRepository;
    @Mock private ScheduleCronParser cronParser;
    @Mock private TriggerLifecycleManager triggerLifecycleManager;

    private InternalTriggerController controller;
    private final UUID workflowId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        controller = new InternalTriggerController(
                tokenService, standaloneWebhookService, standaloneScheduleService,
                chatEndpointService, formEndpointService, webhookRepository,
                chatEndpointRepository, formEndpointRepository,
                scheduleRepository, cronParser, triggerLifecycleManager);
        lenient().when(scheduleRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("schedulesCreate_bodyOrganizationId_winsOverHeader - explicit body field overrides X-Organization-ID header on new row")
    void schedulesCreate_bodyOrganizationId_winsOverHeader() {
        // Arrange - no existing row, valid cron, both body field and header carry an org id.
        when(cronParser.isValid("0 9 * * *")).thenReturn(true);
        when(cronParser.getNextExecution("0 9 * * *", "UTC"))
                .thenReturn(Instant.parse("2026-05-18T09:00:00Z"));
        when(scheduleRepository.findAllByWorkflowIdAndTriggerId(workflowId, "trigger:morning"))
                .thenReturn(List.of());

        Map<String, Object> body = new HashMap<>();
        body.put("workflowId", workflowId.toString());
        body.put("triggerId", "trigger:morning");
        body.put("tenantId", "tenant-alice");
        body.put("organizationId", "BODY-ORG");
        body.put("cron", "0 9 * * *");
        body.put("timezone", "UTC");
        body.put("enabled", true);

        // Act - header carries a DIFFERENT org id; the body must win.
        ResponseEntity<?> response = controller.createOrUpdateScheduleInternal(body, "HEADER-ORG");

        // Assert - the persisted row carries the body's org id, not the header's.
        // This pins the "explicit caller intent" contract documented at L645-647.
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        ArgumentCaptor<ScheduledExecutionEntity> captor =
                ArgumentCaptor.forClass(ScheduledExecutionEntity.class);
        verify(scheduleRepository).save(captor.capture());
        assertThat(captor.getValue().getOrganizationId())
                .as("body-supplied organizationId must take precedence over X-Organization-ID header")
                .isEqualTo("BODY-ORG");
    }

    @Test
    @DisplayName("schedulesCreate_existingRow_recomputesNextExecutionAt when cron changes")
    void schedulesCreate_existingRow_recomputesNextExecutionAtWhenCronChanges() {
        ScheduledExecutionEntity existing = new ScheduledExecutionEntity(
                workflowId,
                "trigger:morning",
                "tenant-alice",
                "0 9 * * *",
                "UTC",
                Instant.parse("2026-05-22T09:00:00Z"));
        existing.setId(UUID.randomUUID());
        existing.setOrganizationId("ORG-1");
        when(cronParser.isValid("0 12 * * *")).thenReturn(true);
        when(cronParser.getNextExecution("0 12 * * *", "Europe/Paris"))
                .thenReturn(Instant.parse("2026-05-23T10:00:00Z"));
        when(scheduleRepository.findAllByWorkflowIdAndTriggerId(workflowId, "trigger:morning"))
                .thenReturn(new ArrayList<>(List.of(existing)));

        Map<String, Object> body = new HashMap<>();
        body.put("workflowId", workflowId.toString());
        body.put("triggerId", "trigger:morning");
        body.put("tenantId", "tenant-alice");
        body.put("organizationId", "ORG-1");
        body.put("cron", "0 12 * * *");
        body.put("timezone", "Europe/Paris");
        body.put("enabled", true);

        ResponseEntity<?> response = controller.createOrUpdateScheduleInternal(body, "ORG-1");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        ArgumentCaptor<ScheduledExecutionEntity> captor =
                ArgumentCaptor.forClass(ScheduledExecutionEntity.class);
        verify(scheduleRepository).save(captor.capture());
        assertThat(captor.getValue().getCronExpression()).isEqualTo("0 12 * * *");
        assertThat(captor.getValue().getTimezone()).isEqualTo("Europe/Paris");
        assertThat(captor.getValue().getNextExecutionAt())
                .isEqualTo(Instant.parse("2026-05-23T10:00:00Z"));
    }

    @Test
    @DisplayName("recordScheduleExecution returns the updated next execution timestamp")
    void recordScheduleExecutionReturnsUpdatedNextExecutionTimestamp() {
        UUID scheduleId = UUID.randomUUID();
        ScheduledExecutionEntity schedule = new ScheduledExecutionEntity(
                workflowId,
                "trigger:morning",
                "tenant-alice",
                "0 9 * * *",
                "UTC",
                Instant.parse("2026-05-22T09:00:00Z"));
        schedule.setId(scheduleId);
        schedule.setOrganizationId("ORG-1");
        schedule.setExecutionCount(5);
        Instant next = Instant.parse("2026-05-23T09:00:00Z");
        when(scheduleRepository.findById(scheduleId)).thenReturn(Optional.of(schedule));
        when(cronParser.isValid("0 9 * * *")).thenReturn(true);
        when(cronParser.getNextExecution("0 9 * * *", "UTC")).thenReturn(next);

        ResponseEntity<ScheduledExecutionDto> response =
                controller.recordScheduleExecution(scheduleId, Map.of());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getNextExecutionAt()).isEqualTo(next);
        assertThat(response.getBody().getExecutionCount()).isEqualTo(6);
        verify(scheduleRepository).save(schedule);
    }

    @Test
    @DisplayName("recordScheduleExecution returns an inactive DTO when a legacy invalid cron is archived")
    void recordScheduleExecutionReturnsInactiveDtoWhenInvalidCronIsArchived() {
        UUID scheduleId = UUID.randomUUID();
        ScheduledExecutionEntity schedule = new ScheduledExecutionEntity(
                workflowId,
                "trigger:morning",
                "tenant-alice",
                "*/120 * * * *",
                "UTC",
                Instant.parse("2026-05-22T09:00:00Z"));
        schedule.setId(scheduleId);
        schedule.setOrganizationId("ORG-1");
        when(scheduleRepository.findById(scheduleId)).thenReturn(Optional.of(schedule));
        when(cronParser.isValid("*/120 * * * *")).thenReturn(false);
        doAnswer(inv -> {
            schedule.setEnabled(false);
            schedule.setIsActive(false);
            return null;
        }).when(triggerLifecycleManager).archiveSchedule(
                scheduleId,
                TriggerLifecycleManager.Reason.INVALID_CRON_LEGACY,
                TriggerLifecycleManager.Source.REAPER);

        ResponseEntity<ScheduledExecutionDto> response =
                controller.recordScheduleExecution(scheduleId, Map.of());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isEnabled()).isFalse();
        assertThat(response.getBody().getIsActive()).isFalse();
        verify(triggerLifecycleManager).archiveSchedule(
                scheduleId,
                TriggerLifecycleManager.Reason.INVALID_CRON_LEGACY,
                TriggerLifecycleManager.Source.REAPER);
    }

    @Test
    @DisplayName("toggleSchedule rejects ids outside the forwarded organization scope")
    void toggleScheduleRejectsCrossOrganizationId() {
        UUID scheduleId = UUID.randomUUID();
        when(scheduleRepository.findByIdAndOrganizationIdStrict(scheduleId, "ORG-B"))
                .thenReturn(Optional.empty());

        ResponseEntity<ScheduledExecutionDto> response =
                controller.toggleSchedule(scheduleId, "ORG-B", Map.of("enabled", false));

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        verify(triggerLifecycleManager, never()).suspendSchedule(any(), any(), any());
        verify(triggerLifecycleManager, never()).armSchedule(any(), any());
    }

    @Test
    @DisplayName("toggleSchedule scopes the state mutation to the forwarded organization")
    void toggleScheduleScopesMutationToOrganization() {
        UUID scheduleId = UUID.randomUUID();
        ScheduledExecutionEntity schedule = new ScheduledExecutionEntity(
                workflowId,
                "trigger:morning",
                "tenant-alice",
                "0 9 * * *",
                "UTC",
                Instant.parse("2026-05-22T09:00:00Z"));
        schedule.setId(scheduleId);
        schedule.setOrganizationId("ORG-A");
        when(scheduleRepository.findByIdAndOrganizationIdStrict(scheduleId, "ORG-A"))
                .thenReturn(Optional.of(schedule));
        when(scheduleRepository.findById(scheduleId)).thenReturn(Optional.of(schedule));

        ResponseEntity<ScheduledExecutionDto> response =
                controller.toggleSchedule(scheduleId, "ORG-A", Map.of("enabled", false));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(triggerLifecycleManager).suspendSchedule(
                scheduleId,
                TriggerLifecycleManager.Reason.USER_DISABLED,
                TriggerLifecycleManager.Source.ADMIN);
    }

    @Test
    @DisplayName("archiveScheduleById rejects ids outside the forwarded organization scope")
    void archiveScheduleByIdRejectsCrossOrganizationId() {
        UUID scheduleId = UUID.randomUUID();
        when(scheduleRepository.findByIdAndOrganizationIdStrict(scheduleId, "ORG-B"))
                .thenReturn(Optional.empty());

        ResponseEntity<Void> response =
                controller.archiveScheduleById(scheduleId, "ORG-B", TriggerLifecycleManager.Reason.USER_DELETED);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        verify(triggerLifecycleManager, never()).archiveSchedule(any(), any(), any());
    }

    @Test
    @DisplayName("archiveScheduleById scopes the archive transition to the forwarded organization")
    void archiveScheduleByIdScopesMutationToOrganization() {
        UUID scheduleId = UUID.randomUUID();
        ScheduledExecutionEntity schedule = new ScheduledExecutionEntity(
                workflowId,
                "trigger:morning",
                "tenant-alice",
                "0 9 * * *",
                "UTC",
                Instant.parse("2026-05-22T09:00:00Z"));
        schedule.setId(scheduleId);
        schedule.setOrganizationId("ORG-A");
        when(scheduleRepository.findByIdAndOrganizationIdStrict(scheduleId, "ORG-A"))
                .thenReturn(Optional.of(schedule));

        ResponseEntity<Void> response =
                controller.archiveScheduleById(scheduleId, "ORG-A", TriggerLifecycleManager.Reason.USER_DELETED);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(triggerLifecycleManager).archiveSchedule(
                scheduleId,
                TriggerLifecycleManager.Reason.USER_DELETED,
                TriggerLifecycleManager.Source.ADMIN);
    }

    @Test
    @DisplayName("restoreScheduleDispatch restores previous markers only when advanced count still matches")
    void restoreScheduleDispatchRestoresPreviousMarkersWhenCountMatches() {
        UUID scheduleId = UUID.randomUUID();
        ScheduledExecutionEntity schedule = new ScheduledExecutionEntity(
                workflowId,
                "trigger:morning",
                "tenant-alice",
                "0 9 * * *",
                "UTC",
                Instant.parse("2026-05-23T09:00:00Z"));
        schedule.setId(scheduleId);
        schedule.setOrganizationId("ORG-1");
        schedule.setLastExecutionAt(Instant.parse("2026-05-22T09:00:01Z"));
        schedule.setExecutionCount(6);
        when(scheduleRepository.findById(scheduleId)).thenReturn(Optional.of(schedule));

        ResponseEntity<ScheduledExecutionDto> response = controller.restoreScheduleDispatch(scheduleId, Map.of(
                "previousNextExecutionAt", "2026-05-22T09:00:00Z",
                "previousLastExecutionAt", "2026-05-21T09:00:00Z",
                "advancedNextExecutionAt", "2026-05-23T09:00:00Z",
                "advancedLastExecutionAt", "2026-05-22T09:00:01Z",
                "previousExecutionCount", 5,
                "advancedExecutionCount", 6));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getNextExecutionAt()).isEqualTo(Instant.parse("2026-05-22T09:00:00Z"));
        assertThat(response.getBody().getLastExecutionAt()).isEqualTo(Instant.parse("2026-05-21T09:00:00Z"));
        assertThat(response.getBody().getExecutionCount()).isEqualTo(5);
        verify(scheduleRepository).save(schedule);
    }

    @Test
    @DisplayName("restoreScheduleDispatch refuses stale restore when another dispatch already advanced")
    void restoreScheduleDispatchRefusesStaleRestoreWhenCountChanged() {
        UUID scheduleId = UUID.randomUUID();
        ScheduledExecutionEntity schedule = new ScheduledExecutionEntity(
                workflowId,
                "trigger:morning",
                "tenant-alice",
                "0 9 * * *",
                "UTC",
                Instant.parse("2026-05-24T09:00:00Z"));
        schedule.setId(scheduleId);
        schedule.setOrganizationId("ORG-1");
        schedule.setExecutionCount(7);
        when(scheduleRepository.findById(scheduleId)).thenReturn(Optional.of(schedule));

        ResponseEntity<ScheduledExecutionDto> response = controller.restoreScheduleDispatch(scheduleId, Map.of(
                "previousNextExecutionAt", "2026-05-22T09:00:00Z",
                "previousExecutionCount", 5,
                "advancedExecutionCount", 6));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getNextExecutionAt()).isEqualTo(Instant.parse("2026-05-24T09:00:00Z"));
        assertThat(response.getBody().getExecutionCount()).isEqualTo(7);
        verify(scheduleRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    @DisplayName("restoreScheduleDispatch refuses stale restore when advanced markers changed")
    void restoreScheduleDispatchRefusesStaleRestoreWhenAdvancedMarkersChanged() {
        UUID scheduleId = UUID.randomUUID();
        ScheduledExecutionEntity schedule = new ScheduledExecutionEntity(
                workflowId,
                "trigger:morning",
                "tenant-alice",
                "0 9 * * *",
                "UTC",
                Instant.parse("2026-05-24T09:00:00Z"));
        schedule.setId(scheduleId);
        schedule.setOrganizationId("ORG-1");
        schedule.setLastExecutionAt(Instant.parse("2026-05-23T09:00:00Z"));
        schedule.setExecutionCount(6);
        when(scheduleRepository.findById(scheduleId)).thenReturn(Optional.of(schedule));

        ResponseEntity<ScheduledExecutionDto> response = controller.restoreScheduleDispatch(scheduleId, Map.of(
                "previousNextExecutionAt", "2026-05-22T09:00:00Z",
                "previousExecutionCount", 5,
                "advancedExecutionCount", 6,
                "advancedNextExecutionAt", "2026-05-23T09:00:00Z"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getNextExecutionAt()).isEqualTo(Instant.parse("2026-05-24T09:00:00Z"));
        assertThat(response.getBody().getExecutionCount()).isEqualTo(6);
        verify(scheduleRepository, org.mockito.Mockito.never()).save(any());
    }
}
