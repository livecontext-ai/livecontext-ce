package com.apimarketplace.trigger.integration.repository;

import com.apimarketplace.trigger.domain.ScheduledExecutionEntity;
import com.apimarketplace.trigger.repository.ScheduledExecutionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end persistence test for the "public access" page <b>schedules counter</b>
 * (orchestrator {@code GET /api/schedules/config} → {@code currentCount}, rendered as
 * {@code N/9999}). That counter is backed by
 * {@link ScheduledExecutionRepository#countActiveByOrganizationIdStrict(String)} -
 * {@code COUNT(*) WHERE organization_id = :orgId AND (workflow_id IS NOT NULL OR agent_entity_id IS NOT NULL)}.
 *
 * <p><b>Bug class under guard (the schedule orphan bug).</b> A schedule created standalone
 * in the builder lands with {@code workflow_id = NULL} and an empty {@code trigger_id}. The
 * counter's {@code workflow_id IS NOT NULL} predicate deliberately excludes such orphans, so
 * an unadopted standalone schedule shows {@code 0/9999} - it is also skipped by the fire daemon
 * ({@link ScheduledExecutionRepository#findDueExecutions(Instant)} carries the same predicate)
 * and reaped after 24h. The F4 PUB-HIJACK fix temporarily REMOVED schedule adoption, leaving
 * every builder schedule a permanent orphan: never fired, never counted, never displayed-as-active.
 *
 * <p>The just-shipped fix restores adoption at pin time:
 * {@code StandaloneScheduleService.updateWorkflowReference} sets BOTH {@code workflow_id} and
 * {@code trigger_id} (guarded NULL→value), driven from
 * {@code ScheduleSyncService.syncSingleSchedule}. This test reproduces that exact state
 * transition at the persistence layer (the single source of truth the counter reads) and asserts:
 *
 * <ol>
 *   <li>a freshly-created standalone schedule (workflow_id NULL) is NOT counted - {@code 0};</li>
 *   <li>after adoption (workflow_id + trigger_id set), the SAME row IS counted - {@code 1};</li>
 *   <li>the adopted row is now also returned by the fire daemon's due-query.</li>
 * </ol>
 *
 * <p><b>Scope - this is a COUNTER-CONTRACT test, not the adoption regression guard.</b> It pins
 * the public-access counter's behaviour: an orphan (workflow_id NULL) shows {@code 0}, an adopted
 * row (workflow_id set) shows {@code 1}, and the count is org-scoped - a faithful proxy for the
 * live {@code /api/schedules/config} counter, which delegates straight to this query. It drives
 * the persistence layer directly (it does NOT call {@code StandaloneScheduleService.updateWorkflowReference}),
 * so it would NOT fail if the production adoption code were removed - that regression is guarded
 * by {@code StandaloneScheduleServiceTest.adoptsUnownedSchedule}. The value here is proving the
 * counter keys on {@code workflow_id} (the orphan gate) and is org-isolated.
 *
 * <p>Mirrors {@link ScheduledExecutionRepositoryIntegrationTest}'s harness exactly:
 * {@code @DataJpaIntegrationTest} (H2 in PostgreSQL mode, create-drop, no external DB),
 * {@link TestEntityManager}, and the {@code persistSchedule} helper shape.
 */
@DataJpaIntegrationTest
@DisplayName("Schedule adoption → public-access schedules counter (countActiveByOrganizationIdStrict)")
class ScheduleAdoptionCountIntegrationTest {

    private static final String ORG_ID = "8dc5a88d-1a38-4838-b517-fd0b88df7a88";
    private static final String TENANT_ID = "user-789";

    @Autowired
    private ScheduledExecutionRepository scheduledExecutionRepository;

    @Autowired
    private TestEntityManager entityManager;

    /**
     * Persist a standalone (builder-created) schedule exactly as
     * {@code StandaloneScheduleService.create} does it: stamped with tenant + org,
     * but with {@code workflow_id = NULL} and {@code trigger_id} unset (the row is not
     * yet adopted onto any workflow).
     */
    private ScheduledExecutionEntity persistStandaloneSchedule(String orgId) {
        ScheduledExecutionEntity entity = new ScheduledExecutionEntity(
                null,                       // workflowId NULL - never adopted yet
                null,                       // triggerId unset
                TENANT_ID,
                "0 9 * * * *",
                "UTC",
                Instant.now().minus(1, ChronoUnit.MINUTES));
        entity.setOrganizationId(orgId);
        entity.setSourceNodeId("node-" + UUID.randomUUID());
        entity.setEnabled(true);
        entityManager.persist(entity);
        entityManager.flush();
        return entity;
    }

    /**
     * Reproduce {@code StandaloneScheduleService.updateWorkflowReference}: the adoption
     * mutation that sets BOTH workflow_id and trigger_id (guarded NULL→value upstream),
     * then re-read the row from the DB so the count query sees the committed state.
     */
    private void adoptOntoWorkflow(ScheduledExecutionEntity entity, UUID workflowId, String triggerId) {
        entity.setWorkflowId(workflowId);
        entity.setTriggerId(triggerId);
        entity.setUpdatedAt(Instant.now());
        entityManager.merge(entity);
        entityManager.flush();
        entityManager.clear();
    }

    @Nested
    @DisplayName("countActiveByOrganizationIdStrict - the /api/schedules/config currentCount")
    class CounterBehaviour {

        @Test
        @DisplayName("Standalone schedule (workflow_id NULL) is NOT counted - public access shows 0/9999")
        void standaloneScheduleIsNotCounted() {
            persistStandaloneSchedule(ORG_ID);

            long count = scheduledExecutionRepository.countActiveByOrganizationIdStrict(ORG_ID);

            // The orphan is intentionally excluded by the workflow_id IS NOT NULL predicate.
            assertThat(count).isZero();
        }

        @Test
        @DisplayName("Adopted+active schedule IS counted - counter goes 0 → 1 (regression guard for the orphan bug)")
        void adoptedScheduleIsCounted() {
            ScheduledExecutionEntity schedule = persistStandaloneSchedule(ORG_ID);

            // Pre-adoption: orphan, not counted.
            assertThat(scheduledExecutionRepository.countActiveByOrganizationIdStrict(ORG_ID)).isZero();

            // Pin-time adoption: workflow_id + trigger_id set (the just-shipped fix).
            UUID workflowId = UUID.randomUUID();
            adoptOntoWorkflow(schedule, workflowId, "trigger:daily_9am");

            // Post-adoption: the SAME row now satisfies workflow_id IS NOT NULL → counted.
            // (Counter contract: the gate is workflow_id; the adoption-call regression itself is
            //  guarded by StandaloneScheduleServiceTest.adoptsUnownedSchedule, not here.)
            long count = scheduledExecutionRepository.countActiveByOrganizationIdStrict(ORG_ID);
            assertThat(count).isEqualTo(1L);

            // And the adopted row carries the workflow reference the counter keys on.
            ScheduledExecutionEntity reloaded = scheduledExecutionRepository.findById(schedule.getId()).orElseThrow();
            assertThat(reloaded.getWorkflowId()).isEqualTo(workflowId);
            assertThat(reloaded.getTriggerId()).isEqualTo("trigger:daily_9am");
        }

        @Test
        @DisplayName("Adoption count is org-scoped - a sibling org's adopted schedule does not leak into this org's counter")
        void counterIsOrgScoped() {
            String otherOrg = "11111111-2222-3333-4444-555555555555";

            ScheduledExecutionEntity mine = persistStandaloneSchedule(ORG_ID);
            adoptOntoWorkflow(mine, UUID.randomUUID(), "trigger:mine");

            ScheduledExecutionEntity theirs = persistStandaloneSchedule(otherOrg);
            adoptOntoWorkflow(theirs, UUID.randomUUID(), "trigger:theirs");

            assertThat(scheduledExecutionRepository.countActiveByOrganizationIdStrict(ORG_ID)).isEqualTo(1L);
            assertThat(scheduledExecutionRepository.countActiveByOrganizationIdStrict(otherOrg)).isEqualTo(1L);
        }
    }

    @Nested
    @DisplayName("findDueExecutions - the same workflow_id gate that decides whether a schedule fires")
    class FireDaemonBehaviour {

        @Test
        @DisplayName("Standalone (unadopted) schedule never fires; adopted+due schedule does")
        void onlyAdoptedScheduleIsDispatchable() {
            ScheduledExecutionEntity schedule = persistStandaloneSchedule(ORG_ID);

            // Pre-adoption: skipped by the daemon (workflow_id IS NULL).
            assertThat(scheduledExecutionRepository.findDueExecutions(Instant.now()))
                    .extracting(ScheduledExecutionEntity::getId)
                    .doesNotContain(schedule.getId());

            adoptOntoWorkflow(schedule, UUID.randomUUID(), "trigger:daily_9am");

            // Post-adoption: due + dispatchable.
            assertThat(scheduledExecutionRepository.findDueExecutions(Instant.now()))
                    .extracting(ScheduledExecutionEntity::getId)
                    .contains(schedule.getId());
        }
    }
}
