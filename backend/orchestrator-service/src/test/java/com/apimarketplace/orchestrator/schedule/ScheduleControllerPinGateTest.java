package com.apimarketplace.orchestrator.schedule;

import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.trigger.client.TriggerClient;
import com.apimarketplace.trigger.client.dto.ScheduleCreateRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression guard for the schedule pin gate.
 *
 * <p>Bug 2026-05-14: prod had 5 ACTIVE+enabled rows in {@code scheduled_executions}
 * on workflows where {@code pinned_version IS NULL} - 4 of them APPLICATION clones
 * never toggled live by the user. One of the latent creation paths is the orphan
 * REST endpoint {@code POST /api/v2/workflows/{workflowId}/schedule/{triggerId}}:
 * before this gate, any direct call (curl, admin script, future frontend code) could
 * create an ACTIVE row that auto-fired forever, bypassing the
 * {@link com.apimarketplace.orchestrator.services.persistence.PinAwareTriggerSyncService}
 * pin-aware sync.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ScheduleController - pin gate on createOrUpdateSchedule")
class ScheduleControllerPinGateTest {

    @Mock TriggerClient triggerClient;
    @Mock ScheduleExecutorService scheduleExecutorService;
    @Mock WorkflowRepository workflowRepository;

    private static final UUID WORKFLOW_ID = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final String TRIGGER_ID = "trigger:cron";
    private static final String TENANT = "tenant-A";

    private ScheduleController controller() {
        return new ScheduleController(triggerClient, scheduleExecutorService, workflowRepository, true);
    }

    @Test
    @DisplayName("Unpinned workflow → 400 WORKFLOW_NOT_PINNED + no triggerClient call")
    @SuppressWarnings("unchecked")
    void unpinnedWorkflowReturns400AndDoesNotCallTriggerService() {
        WorkflowEntity unpinned = new WorkflowEntity();
        unpinned.setId(WORKFLOW_ID);
        // Strict-isolation alignment (2026-05-18): the schedule endpoint
        // funnels through ScopeGuard.isInStrictScope before the pin check,
        // so the workflow must carry a tenantId matching the caller for the
        // pin gate (not scope) to be the assertion under test.
        unpinned.setTenantId(TENANT);
        unpinned.setPinnedVersion(null);
        when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(unpinned));

        ResponseEntity<?> response = controller().createOrUpdateSchedule(
                WORKFLOW_ID.toString(), TRIGGER_ID,
                new ScheduleCreateRequest("0 9 * * *", "UTC", null, true, null),
                TENANT, null, "FREE");

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("kind")).isEqualTo("WORKFLOW_NOT_PINNED");
        verify(triggerClient, never()).createOrUpdateSchedule(any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("Missing workflow → 404 + no triggerClient call")
    void missingWorkflowReturns404() {
        when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller().createOrUpdateSchedule(
                WORKFLOW_ID.toString(), TRIGGER_ID,
                new ScheduleCreateRequest("0 9 * * *", "UTC", null, true, null),
                TENANT, null, "FREE");

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        verify(triggerClient, never()).createOrUpdateSchedule(any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("Pinned workflow → proxies to trigger-service")
    void pinnedWorkflowProxies() {
        WorkflowEntity pinned = new WorkflowEntity();
        pinned.setId(WORKFLOW_ID);
        // Strict-isolation alignment (2026-05-18).
        pinned.setTenantId(TENANT);
        pinned.setPinnedVersion(3);
        when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(pinned));
        when(triggerClient.getScheduleByWorkflowAndTrigger(eq(WORKFLOW_ID), eq(TRIGGER_ID), isNull())).thenReturn(null);
        when(triggerClient.countSchedulesByTenant(TENANT, null)).thenReturn(0L);
        when(triggerClient.createOrUpdateSchedule(
                eq(WORKFLOW_ID), eq(TRIGGER_ID), eq(TENANT), isNull(), any(), isNull(), isNull()))
                .thenReturn(new com.apimarketplace.trigger.client.dto.ScheduledExecutionDto());

        ResponseEntity<?> response = controller().createOrUpdateSchedule(
                WORKFLOW_ID.toString(), TRIGGER_ID,
                new ScheduleCreateRequest("0 9 * * *", "UTC", null, true, null),
                TENANT, null, "PRO");

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        verify(triggerClient).createOrUpdateSchedule(
                eq(WORKFLOW_ID), eq(TRIGGER_ID), eq(TENANT), isNull(), any(), isNull(), isNull());
    }
}
