package com.apimarketplace.trigger.controller;

import com.apimarketplace.trigger.client.dto.ScheduledExecutionDto;
import com.apimarketplace.trigger.domain.ScheduledExecutionEntity;
import com.apimarketplace.trigger.domain.TriggerState;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression tests for the "orphan schedule keeps firing after its trigger is removed
 * from the plan" bug.
 *
 * <p>Root cause: {@code ScheduleSyncService.cleanupOrphanSchedules} suspends a removed
 * trigger's row with {@code reason=PLAN_TRIGGER_REMOVED} (state {@code SUSPENDED_NO_RUN}),
 * but the bulk re-arm {@link InternalTriggerController#enableSchedulesByWorkflow} that runs
 * later in the SAME sync called {@code armSchedule} on every non-ARCHIVED row, immediately
 * re-activating the orphan. Net effect: the schedule ping-ponged SUSPEND→ACTIVE and never
 * stayed suspended, so {@code findDueForUpdate} kept picking it and it fired a new epoch
 * every tick.
 *
 * <p>Fix 1: the bulk re-arm skips rows whose {@code lastDisabledReason} is
 * {@code PLAN_TRIGGER_REMOVED} (and which are not already ACTIVE), so it no longer revives
 * orphans - while still re-arming the {@code SUSPENDED_UNPINNED} (post re-pin) and
 * {@code USER_DISABLED} (post reactivate) rows that the sweep exists for.
 *
 * <p>Fix 2: because the sweep no longer revives PLAN_TRIGGER_REMOVED rows, the legitimate
 * "re-add the trigger to the plan" path re-activates explicitly in
 * {@code createOrUpdateScheduleInternal} (config upsert alone does not transition state).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InternalTriggerController - schedule re-arm orphan guard (PLAN_TRIGGER_REMOVED)")
class InternalTriggerControllerScheduleRearmGuardTest {

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
    private static final String ORG = "org-1";
    private static final String TENANT = "tenant-1";

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
    @DisplayName("enableSchedulesByWorkflow_skipsPlanTriggerRemovedOrphan_butRearmsUnpinnedAndUserDisabledAndActive")
    void enableSchedulesByWorkflow_skipsOnlyPlanTriggerRemovedOrphan() {
        // Arrange - one row per lifecycle state the sweep can encounter.
        ScheduledExecutionEntity orphan = row("orphan",
                TriggerState.SUSPENDED_NO_RUN, TriggerLifecycleManager.Reason.PLAN_TRIGGER_REMOVED);
        ScheduledExecutionEntity unpinned = row("unpinned",
                TriggerState.SUSPENDED_UNPINNED, TriggerLifecycleManager.Reason.WORKFLOW_UNPINNED);
        ScheduledExecutionEntity userDisabled = row("user-disabled",
                TriggerState.SUSPENDED_NO_RUN, TriggerLifecycleManager.Reason.USER_DISABLED);
        ScheduledExecutionEntity active = row("active", TriggerState.ACTIVE, null);

        when(scheduleRepository.findByWorkflowId(workflowId))
                .thenReturn(List.of(orphan, unpinned, userDisabled, active));
        when(triggerLifecycleManager.armSchedule(any(), any())).thenReturn(true);

        // Act - no org filter (null query + null header).
        ResponseEntity<Integer> response = controller.enableSchedulesByWorkflow(workflowId, null, null);

        // Assert - orphan is NOT re-armed; the three legitimate rows are.
        verify(triggerLifecycleManager, never()).armSchedule(eq(orphan.getId()), any());
        verify(triggerLifecycleManager).armSchedule(eq(unpinned.getId()), eq(TriggerLifecycleManager.Source.SYNC));
        verify(triggerLifecycleManager).armSchedule(eq(userDisabled.getId()), eq(TriggerLifecycleManager.Source.SYNC));
        verify(triggerLifecycleManager).armSchedule(eq(active.getId()), eq(TriggerLifecycleManager.Source.SYNC));
        // Dispatchable count reflects only the armed rows (3), not the skipped orphan.
        assertThat(response.getBody()).isEqualTo(3);
    }

    @Test
    @DisplayName("createOrUpdateScheduleInternal_reActivatesPlanTriggerRemovedRow_onReAdd")
    void createOrUpdate_reActivatesPlanTriggerRemovedRowOnReAdd() {
        // Arrange - the trigger was previously removed (row suspended PLAN_TRIGGER_REMOVED),
        // now it is re-added to the plan: a sync upsert arrives with enabled=true.
        ScheduledExecutionEntity existing = row("re-added",
                TriggerState.SUSPENDED_NO_RUN, TriggerLifecycleManager.Reason.PLAN_TRIGGER_REMOVED);
        when(cronParser.isValid("0 * * * *")).thenReturn(true);
        when(cronParser.getNextExecution("0 * * * *", "UTC"))
                .thenReturn(Instant.parse("2026-01-01T00:00:00Z"));
        when(scheduleRepository.findAllByWorkflowIdAndTriggerId(workflowId, "trigger:reponses_poll"))
                .thenReturn(new ArrayList<>(List.of(existing)));
        when(scheduleRepository.findById(existing.getId())).thenReturn(Optional.of(existing));

        // Act
        controller.createOrUpdateScheduleInternal(body(true), ORG);

        // Assert - the re-add path arms the row explicitly (the bulk sweep no longer would).
        verify(triggerLifecycleManager).armSchedule(eq(existing.getId()), eq(TriggerLifecycleManager.Source.SYNC));
    }

    @Test
    @DisplayName("createOrUpdateScheduleInternal_doesNotReActivateUserDisabledRow (re-add re-activation is scoped to PLAN_TRIGGER_REMOVED)")
    void createOrUpdate_doesNotReActivateUserDisabledRow() {
        ScheduledExecutionEntity existing = row("user-disabled",
                TriggerState.SUSPENDED_NO_RUN, TriggerLifecycleManager.Reason.USER_DISABLED);
        when(cronParser.isValid("0 * * * *")).thenReturn(true);
        when(cronParser.getNextExecution("0 * * * *", "UTC"))
                .thenReturn(Instant.parse("2026-01-01T00:00:00Z"));
        when(scheduleRepository.findAllByWorkflowIdAndTriggerId(workflowId, "trigger:reponses_poll"))
                .thenReturn(new ArrayList<>(List.of(existing)));

        controller.createOrUpdateScheduleInternal(body(true), ORG);

        verify(triggerLifecycleManager, never()).armSchedule(any(), any());
    }

    @Test
    @DisplayName("updateScheduleWorkflowReference_reActivatesPlanTriggerRemovedStandaloneRow_onReAdopt")
    void adoptStandalone_reActivatesPlanTriggerRemovedRow() {
        // A standalone-backed schedule that was orphaned (PLAN_TRIGGER_REMOVED) is re-added:
        // the sync re-adopts it onto the workflow. The adopt only sets workflow_id/trigger_id,
        // so the controller must re-activate explicitly - otherwise the PLAN_TRIGGER_REMOVED skip
        // in the bulk re-arm would leave the re-added standalone schedule suspended forever.
        ScheduledExecutionEntity existing = row("standalone-readd",
                TriggerState.SUSPENDED_NO_RUN, TriggerLifecycleManager.Reason.PLAN_TRIGGER_REMOVED);
        ScheduledExecutionDto dto = new ScheduledExecutionDto();
        dto.setId(existing.getId());
        when(standaloneScheduleService.updateWorkflowReference(any(), any(), eq(existing.getId()), any(), any(), any()))
                .thenReturn(dto);
        when(scheduleRepository.findById(existing.getId())).thenReturn(Optional.of(existing));

        Map<String, String> body = new HashMap<>();
        body.put("workflowId", workflowId.toString());
        body.put("triggerId", "trigger:reponses_poll");

        controller.updateScheduleWorkflowReference(TENANT, ORG, existing.getId(), body);

        verify(triggerLifecycleManager).armSchedule(eq(existing.getId()), eq(TriggerLifecycleManager.Source.SYNC));
    }

    @Test
    @DisplayName("updateScheduleWorkflowReference_doesNotReActivateUserDisabledStandaloneRow (re-activation scoped to PLAN_TRIGGER_REMOVED)")
    void adoptStandalone_doesNotReActivateUserDisabledRow() {
        ScheduledExecutionEntity existing = row("standalone-userdisabled",
                TriggerState.SUSPENDED_NO_RUN, TriggerLifecycleManager.Reason.USER_DISABLED);
        ScheduledExecutionDto dto = new ScheduledExecutionDto();
        dto.setId(existing.getId());
        when(standaloneScheduleService.updateWorkflowReference(any(), any(), eq(existing.getId()), any(), any(), any()))
                .thenReturn(dto);
        when(scheduleRepository.findById(existing.getId())).thenReturn(Optional.of(existing));

        Map<String, String> body = new HashMap<>();
        body.put("workflowId", workflowId.toString());
        body.put("triggerId", "trigger:reponses_poll");

        controller.updateScheduleWorkflowReference(TENANT, ORG, existing.getId(), body);

        verify(triggerLifecycleManager, never()).armSchedule(any(), any());
    }

    @Test
    @DisplayName("createOrUpdateScheduleInternal_doesNotReActivateWhenDisabled (enabled=false leaves a removed trigger suspended)")
    void createOrUpdate_doesNotReActivateWhenDisabled() {
        ScheduledExecutionEntity existing = row("disabled-readd",
                TriggerState.SUSPENDED_NO_RUN, TriggerLifecycleManager.Reason.PLAN_TRIGGER_REMOVED);
        when(cronParser.isValid("0 * * * *")).thenReturn(true);
        when(cronParser.getNextExecution("0 * * * *", "UTC"))
                .thenReturn(Instant.parse("2026-01-01T00:00:00Z"));
        when(scheduleRepository.findAllByWorkflowIdAndTriggerId(workflowId, "trigger:reponses_poll"))
                .thenReturn(new ArrayList<>(List.of(existing)));

        controller.createOrUpdateScheduleInternal(body(false), ORG);

        verify(triggerLifecycleManager, never()).armSchedule(any(), any());
    }

    // ---- helpers -----------------------------------------------------------

    private ScheduledExecutionEntity row(String tag, TriggerState state, String reason) {
        ScheduledExecutionEntity e = new ScheduledExecutionEntity(
                workflowId, "trigger:" + tag, TENANT, "0 * * * *", "UTC",
                Instant.parse("2026-01-01T00:00:00Z"));
        e.setId(UUID.randomUUID());
        e.setOrganizationId(ORG);
        e.setState(state);
        e.setLastDisabledReason(reason);
        return e;
    }

    private Map<String, Object> body(boolean enabled) {
        Map<String, Object> body = new HashMap<>();
        body.put("workflowId", workflowId.toString());
        body.put("triggerId", "trigger:reponses_poll");
        body.put("tenantId", TENANT);
        body.put("organizationId", ORG);
        body.put("cron", "0 * * * *");
        body.put("timezone", "UTC");
        body.put("enabled", enabled);
        return body;
    }
}
