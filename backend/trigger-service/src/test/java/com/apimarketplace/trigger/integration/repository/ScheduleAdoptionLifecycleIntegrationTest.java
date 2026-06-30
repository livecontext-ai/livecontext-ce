package com.apimarketplace.trigger.integration.repository;

import com.apimarketplace.trigger.client.dto.ScheduledExecutionDto;
import com.apimarketplace.trigger.client.dto.StandaloneScheduleRequest;
import com.apimarketplace.trigger.domain.ScheduledExecutionEntity;
import com.apimarketplace.trigger.repository.ScheduledExecutionRepository;
import com.apimarketplace.trigger.service.PlanLimitHelper;
import com.apimarketplace.trigger.service.ScheduleCronParser;
import com.apimarketplace.trigger.service.StandaloneScheduleService;
import com.apimarketplace.trigger.service.WorkflowReferenceImmutableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end persistence test for the schedule-trigger ADOPTION lifecycle - the just-shipped
 * fix that links a builder-created standalone schedule onto its workflow at pin time.
 *
 * <p><b>Bug class under test (schedule-style orphan).</b> A {@code trigger:schedule} node added
 * in the builder first materialises as a STANDALONE schedule row via
 * {@link StandaloneScheduleService#create} - {@code workflow_id} is NULL and {@code trigger_id}
 * is unset. The fire daemon ({@link com.apimarketplace.orchestrator.schedule.ScheduleExecutorService})
 * claims due rows through {@code ScheduledExecutionRepository.findDueForUpdate}, whose predicate
 * (mirrored exactly by the JPQL twin {@code findDueExecutions} asserted here) requires
 * {@code workflow_id IS NOT NULL OR agent_entity_id IS NOT NULL}. So a never-adopted standalone
 * schedule is INVISIBLE to the daemon → it never fires, is never counted, and is reaped after 24h.
 *
 * <p>The F4 PUB-HIJACK fix had removed schedule's adoption entirely (chat/webhook/form kept theirs),
 * leaving the orphan. The current fix restores adoption via
 * {@link StandaloneScheduleService#updateWorkflowReference} (called on pin by the orchestrator's
 * {@code ScheduleSyncService.syncSingleSchedule} →
 * {@code TriggerClient.updateScheduleWorkflowReferenceStrict} → the
 * {@code PATCH /api/internal/trigger/schedules/standalone/{id}/workflow} endpoint), which sets BOTH
 * {@code workflow_id} AND {@code trigger_id}, guarded NULL→value only.
 *
 * <p>This test drives the whole chain at the persistence boundary against the real repository and a
 * real {@link StandaloneScheduleService}, exactly like {@link ScheduledExecutionRepositoryIntegrationTest}:
 * REGISTER (standalone, orphan) → prove NOT due → ADOPT (the fix) → FIRE (now due) → COUNT (execution
 * increments). It FAILS on the buggy behaviour (no adoption ⇒ the orphan never becomes due) and PASSES
 * on the fixed behaviour. The hijack-safety guard (re-adopt to a different workflow rejected) is also
 * pinned so the regression test cannot pass by simply rewriting {@code workflow_id} unconditionally.
 */
@DataJpaIntegrationTest
@DisplayName("Schedule adoption lifecycle - standalone register → pin-adopt → fire → count")
class ScheduleAdoptionLifecycleIntegrationTest {

    @Autowired
    private ScheduledExecutionRepository scheduleRepository;

    @Autowired
    private TestEntityManager entityManager;

    private StandaloneScheduleService scheduleService;

    private static final String TENANT_ID = "tenant-sched-e2e";
    private static final String ORG_ID = "org-sched-e2e";
    // "* * * * *" → every minute, so the next firing is always within 60s of now → due quickly.
    private static final String EVERY_MINUTE_CRON = "* * * * *";

    @BeforeEach
    void setUp() {
        // Mirror the production wiring of StandaloneScheduleService: a real cron parser and a real
        // plan-limit helper (limits enabled, FREE-tier default headroom is fine for a single row).
        this.scheduleService = new StandaloneScheduleService(
                scheduleRepository,
                new ScheduleCronParser(),
                new PlanLimitHelper(true));
    }

    /** Create a builder-style standalone schedule (workflow_id NULL, trigger_id unset). */
    private ScheduledExecutionDto createStandalone(String sourceNodeId) {
        StandaloneScheduleRequest request = new StandaloneScheduleRequest(
                "Daily Job", "builder schedule node", EVERY_MINUTE_CRON,
                "UTC", null, true, null, sourceNodeId);
        return scheduleService.create(TENANT_ID, ORG_ID, "FREE", request);
    }

    /** Force the row due NOW so the daemon's due-query would pick it up this tick. */
    private void backdateNextExecution(UUID scheduleId) {
        ScheduledExecutionEntity entity = scheduleRepository.findById(scheduleId).orElseThrow();
        entity.setNextExecutionAt(Instant.now().minus(1, ChronoUnit.MINUTES));
        entityManager.merge(entity);
        entityManager.flush();
        entityManager.clear();
    }

    @Nested
    @DisplayName("Register → Adopt → Fire → Count (the fix)")
    class FullAdoptionChain {

        @Test
        @DisplayName("Standalone schedule is created as an ORPHAN: workflow_id NULL, trigger_id unset, NOT due")
        void standaloneIsCreatedAsOrphanAndIsNotDue() {
            ScheduledExecutionDto created = createStandalone("node-orphan-1");

            ScheduledExecutionEntity row = scheduleRepository.findById(created.getId()).orElseThrow();
            assertThat(row.getWorkflowId()).as("builder schedule starts UNLINKED").isNull();
            assertThat(row.getTriggerId()).as("trigger_id unset until adoption").isNull();

            // Even forced due, the orphan must be invisible to the daemon's claim query
            // (findDueExecutions = the JPQL twin of findDueForUpdate; both require a non-null FK).
            backdateNextExecution(created.getId());
            List<ScheduledExecutionEntity> due = scheduleRepository.findDueExecutions(Instant.now());
            assertThat(due)
                    .as("an unadopted standalone schedule must NEVER be dispatchable")
                    .noneMatch(s -> s.getId().equals(created.getId()));
        }

        @Test
        @DisplayName("Pin ADOPTS the standalone schedule (workflow_id + trigger_id set) → becomes DUE → fires → counts")
        void pinAdoptsScheduleThenItFiresAndCounts() {
            UUID workflowId = UUID.randomUUID();
            String triggerId = "trigger:daily_job";

            // ── REGISTER ── builder creates the standalone (orphan) schedule.
            ScheduledExecutionDto created = createStandalone("node-adopt-1");
            assertThat(scheduleRepository.findById(created.getId()).orElseThrow().getWorkflowId()).isNull();

            // ── ADOPT (the fix) ── pin links workflow_id + trigger_id, guarded NULL→value.
            ScheduledExecutionDto adopted = scheduleService.updateWorkflowReference(
                    TENANT_ID, ORG_ID, created.getId(), workflowId, triggerId, "My Workflow");

            assertThat(adopted.getWorkflowId()).isEqualTo(workflowId);
            assertThat(adopted.getTriggerId()).isEqualTo(triggerId);
            assertThat(adopted.getWorkflowName()).isEqualTo("My Workflow");

            ScheduledExecutionEntity persisted = scheduleRepository.findById(created.getId()).orElseThrow();
            assertThat(persisted.getWorkflowId()).as("workflow_id persisted on the row").isEqualTo(workflowId);
            assertThat(persisted.getTriggerId()).as("trigger_id persisted on the row").isEqualTo(triggerId);

            // ── FIRE ── the daemon's due-query now returns the row (it WOULD dispatch it this tick).
            backdateNextExecution(created.getId());
            List<ScheduledExecutionEntity> due = scheduleRepository.findDueExecutions(Instant.now());
            assertThat(due)
                    .as("once adopted, the schedule is dispatchable by the daemon")
                    .anyMatch(s -> s.getId().equals(created.getId())
                            && workflowId.equals(s.getWorkflowId())
                            && triggerId.equals(s.getTriggerId()));

            // ── COUNT ── recording a fire increments execution_count (the row is counted, not N/9999).
            ScheduledExecutionEntity toRecord = scheduleRepository.findById(created.getId()).orElseThrow();
            int before = toRecord.getExecutionCount();
            toRecord.recordExecution(Instant.now().plus(1, ChronoUnit.MINUTES));
            scheduleRepository.save(toRecord);
            entityManager.flush();

            ScheduledExecutionEntity afterFire = scheduleRepository.findById(created.getId()).orElseThrow();
            assertThat(afterFire.getExecutionCount()).isEqualTo(before + 1);
            assertThat(afterFire.getLastExecutionAt()).isNotNull();
        }

        @Test
        @DisplayName("Adoption is idempotent - re-adopting the SAME workflow is a no-op, row stays dispatchable")
        void reAdoptingSameWorkflowIsIdempotent() {
            UUID workflowId = UUID.randomUUID();
            String triggerId = "trigger:daily_job";
            ScheduledExecutionDto created = createStandalone("node-idem-1");

            scheduleService.updateWorkflowReference(TENANT_ID, ORG_ID, created.getId(), workflowId, triggerId, "WF");
            // Pin again (e.g. a second save / re-sync) - same owner, must not throw.
            ScheduledExecutionDto again = scheduleService.updateWorkflowReference(
                    TENANT_ID, ORG_ID, created.getId(), workflowId, triggerId, "WF");

            assertThat(again.getWorkflowId()).isEqualTo(workflowId);
            assertThat(again.getTriggerId()).isEqualTo(triggerId);
        }
    }

    @Nested
    @DisplayName("Hijack-safety - adoption is guarded NULL→value only")
    class HijackSafety {

        @Test
        @DisplayName("Re-adopting an already-owned schedule onto a DIFFERENT workflow is rejected (F4 PUB-HIJACK stays closed)")
        void rebindingToAnotherWorkflowIsRejected() {
            UUID firstWorkflow = UUID.randomUUID();
            UUID foreignWorkflow = UUID.randomUUID();
            ScheduledExecutionDto created = createStandalone("node-hijack-1");

            // First adoption: NULL → value, allowed.
            scheduleService.updateWorkflowReference(
                    TENANT_ID, ORG_ID, created.getId(), firstWorkflow, "trigger:a", "First WF");

            // Attempt to rebind value → DIFFERENT value (the hijack shape) must be refused.
            assertThatThrownBy(() -> scheduleService.updateWorkflowReference(
                    TENANT_ID, ORG_ID, created.getId(), foreignWorkflow, "trigger:b", "Foreign WF"))
                    .isInstanceOf(WorkflowReferenceImmutableException.class)
                    .hasMessageContaining("workflowId is immutable");

            // The row must still belong to the FIRST workflow - no silent rewrite.
            ScheduledExecutionEntity row = scheduleRepository.findById(created.getId()).orElseThrow();
            assertThat(row.getWorkflowId()).isEqualTo(firstWorkflow);
            assertThat(row.getTriggerId()).isEqualTo("trigger:a");
        }

        @Test
        @DisplayName("Cross-org adoption cannot see the row (org-scoped finder) → IllegalArgumentException, not a claim")
        void crossOrgAdoptionCannotClaimTheRow() {
            ScheduledExecutionDto created = createStandalone("node-xorg-1");

            assertThatThrownBy(() -> scheduleService.updateWorkflowReference(
                    TENANT_ID, "org-someone-else", created.getId(),
                    UUID.randomUUID(), "trigger:x", "Their WF"))
                    .isInstanceOf(IllegalArgumentException.class);

            // Still an unclaimed orphan in its own org.
            ScheduledExecutionEntity row = scheduleRepository.findById(created.getId()).orElseThrow();
            assertThat(row.getWorkflowId()).isNull();
        }
    }
}
