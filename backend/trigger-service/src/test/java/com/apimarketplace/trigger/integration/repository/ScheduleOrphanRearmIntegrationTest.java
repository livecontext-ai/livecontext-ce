package com.apimarketplace.trigger.integration.repository;

import com.apimarketplace.trigger.controller.InternalTriggerController;
import com.apimarketplace.trigger.domain.ScheduledExecutionEntity;
import com.apimarketplace.trigger.domain.TriggerState;
import com.apimarketplace.trigger.repository.ScheduledExecutionRepository;
import com.apimarketplace.trigger.repository.StandaloneChatEndpointRepository;
import com.apimarketplace.trigger.repository.StandaloneFormEndpointRepository;
import com.apimarketplace.trigger.repository.StandaloneWebhookRepository;
import com.apimarketplace.trigger.repository.WebhookTokenRepository;
import com.apimarketplace.trigger.service.ScheduleCronParser;
import com.apimarketplace.trigger.service.TriggerLifecycleManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end persistence test for the orphaned-schedule resurrection bug, against the REAL
 * repository, the REAL {@link TriggerLifecycleManager} state machine, and the REAL
 * {@link InternalTriggerController} endpoints - the cross-component half that the isolated unit
 * tests (which mock the lifecycle manager / repository) cannot prove.
 *
 * <p><b>Bug:</b> when a schedule trigger is removed from a workflow plan, the orchestrator's
 * {@code ScheduleSyncService.cleanupOrphanSchedules} suspends the row with reason
 * {@code PLAN_TRIGGER_REMOVED}, but the bulk re-arm {@code enableSchedulesByWorkflow} (run later in
 * the SAME sync to recover post-repin / post-reactivate rows) armed EVERY non-ARCHIVED row, so the
 * orphan bounced straight back to ACTIVE and kept being claimed by the due-query every cron tick.
 *
 * <p>This drives the real flow: two ACTIVE due schedules → orphan-suspend one → run the bulk re-arm
 * → assert the orphan STAYS suspended and is no longer due while the sibling stays due → re-add the
 * trigger via {@code createOrUpdateScheduleInternal} → assert it returns to ACTIVE and becomes due
 * again. It FAILS on the pre-fix behaviour (orphan resurrected → still due) and PASSES on the fix.
 *
 * <p>The due-query is asserted via {@code findDueExecutions} - the JPQL twin of the daemon's
 * {@code findDueForUpdate} (same predicate, including {@code state='ACTIVE'}), H2-compatible.
 */
@DataJpaIntegrationTest
@DisplayName("Schedule orphan re-arm - removed trigger stays suspended across the bulk sweep")
class ScheduleOrphanRearmIntegrationTest {

    @Autowired private ScheduledExecutionRepository scheduleRepository;
    @Autowired private StandaloneWebhookRepository webhookRepository;
    @Autowired private StandaloneChatEndpointRepository chatRepository;
    @Autowired private StandaloneFormEndpointRepository formRepository;
    @Autowired private WebhookTokenRepository tokenRepository;
    @Autowired private TestEntityManager entityManager;

    private TriggerLifecycleManager lifecycle;
    private InternalTriggerController controller;

    private static final String TENANT = "tenant-orphan-rearm";
    private static final String ORG = "org-orphan-rearm";
    private static final String CRON = "* * * * *"; // every minute → due within 60s

    @BeforeEach
    void setUp() {
        lifecycle = new TriggerLifecycleManager(
                scheduleRepository, webhookRepository, chatRepository, formRepository, tokenRepository);
        // Only scheduleRepository, cronParser and the lifecycle manager are exercised by the
        // schedule endpoints under test; the standalone services are unused here.
        controller = new InternalTriggerController(
                null, null, null, null, null,
                webhookRepository, chatRepository, formRepository,
                scheduleRepository, new ScheduleCronParser(), lifecycle);
    }

    @Test
    @DisplayName("removed trigger stays SUSPENDED across the bulk re-arm (no resurrection); sibling stays due; re-add re-activates")
    void orphanedScheduleStaysSuspendedAcrossBulkRearm_andReAddReactivates() {
        UUID workflowId = UUID.randomUUID();
        ScheduledExecutionEntity removed = persistActiveDueSchedule(workflowId, "trigger:reponses_poll");
        ScheduledExecutionEntity sibling = persistActiveDueSchedule(workflowId, "trigger:selection_2x");
        flushClear();

        // Sanity: both ACTIVE schedules are claimable by the daemon's due-query.
        assertThat(dueIds()).contains(removed.getId(), sibling.getId());

        // ── Trigger removed from the plan: orchestrator suspends the orphan. ──
        lifecycle.suspendSchedule(removed.getId(),
                TriggerLifecycleManager.Reason.PLAN_TRIGGER_REMOVED, TriggerLifecycleManager.Source.SYNC);
        // ── Same sync runs the bulk re-arm (previously resurrected the orphan). ──
        controller.enableSchedulesByWorkflow(workflowId, ORG, null);
        flushClear();

        // The orphan MUST stay suspended and drop out of the due-set; the sibling stays due.
        ScheduledExecutionEntity afterSweep = scheduleRepository.findById(removed.getId()).orElseThrow();
        assertThat(afterSweep.getState()).isEqualTo(TriggerState.SUSPENDED_NO_RUN);
        assertThat(afterSweep.getLastDisabledReason())
                .isEqualTo(TriggerLifecycleManager.Reason.PLAN_TRIGGER_REMOVED);
        assertThat(dueIds())
                .doesNotContain(removed.getId())
                .contains(sibling.getId());

        // ── Re-add the trigger to the plan: the sync upserts it enabled. ──
        controller.createOrUpdateScheduleInternal(reAddBody(workflowId, "trigger:reponses_poll"), ORG);
        flushClear();

        ScheduledExecutionEntity afterReAdd = scheduleRepository.findById(removed.getId()).orElseThrow();
        assertThat(afterReAdd.getState()).isEqualTo(TriggerState.ACTIVE);
        assertThat(afterReAdd.getLastDisabledReason()).isNull();
        // createOrUpdate stamps a fresh (future) next execution; force it due to prove dispatchability.
        backdateNextExecution(removed.getId());
        assertThat(dueIds()).contains(removed.getId());
    }

    // ---- helpers -----------------------------------------------------------

    private ScheduledExecutionEntity persistActiveDueSchedule(UUID workflowId, String triggerId) {
        ScheduledExecutionEntity e = new ScheduledExecutionEntity(
                workflowId, triggerId, TENANT, CRON, "UTC", Instant.now().minus(1, ChronoUnit.MINUTES));
        e.setOrganizationId(ORG);
        e.setEnabled(true); // state defaults to ACTIVE
        return entityManager.persistAndFlush(e);
    }

    private void backdateNextExecution(UUID scheduleId) {
        ScheduledExecutionEntity e = scheduleRepository.findById(scheduleId).orElseThrow();
        e.setNextExecutionAt(Instant.now().minus(1, ChronoUnit.MINUTES));
        entityManager.merge(e);
        flushClear();
    }

    private List<UUID> dueIds() {
        return scheduleRepository.findDueExecutions(Instant.now()).stream()
                .map(ScheduledExecutionEntity::getId).toList();
    }

    private void flushClear() {
        entityManager.flush();
        entityManager.clear();
    }

    private Map<String, Object> reAddBody(UUID workflowId, String triggerId) {
        Map<String, Object> body = new HashMap<>();
        body.put("workflowId", workflowId.toString());
        body.put("triggerId", triggerId);
        body.put("tenantId", TENANT);
        body.put("organizationId", ORG);
        body.put("cron", CRON);
        body.put("timezone", "UTC");
        body.put("enabled", true);
        return body;
    }
}
