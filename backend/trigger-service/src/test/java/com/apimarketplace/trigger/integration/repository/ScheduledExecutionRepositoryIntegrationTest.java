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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link ScheduledExecutionRepository}.
 * Tests schedule CRUD, due execution queries, and business methods.
 */
@DataJpaIntegrationTest
class ScheduledExecutionRepositoryIntegrationTest {

    @Autowired
    private ScheduledExecutionRepository scheduledExecutionRepository;

    @Autowired
    private TestEntityManager entityManager;

    private ScheduledExecutionEntity persistSchedule(UUID workflowId, String triggerId, String tenantId,
                                                      String cron, boolean enabled, Instant nextExecution) {
        ScheduledExecutionEntity entity = new ScheduledExecutionEntity(
                workflowId, triggerId, tenantId, cron, "UTC", nextExecution);
        entity.setEnabled(enabled);
        // V263 OrgScopedEntity: stamp org-id before persist (NOT NULL after V261)
        entity.setOrganizationId(tenantId);
        entityManager.persist(entity);
        entityManager.flush();
        return entity;
    }

    private ScheduledExecutionEntity persistDefaultSchedule(UUID workflowId, String triggerId) {
        return persistSchedule(workflowId, triggerId, "tenant-1", "0 */5 * * * *", true,
                Instant.now().minus(1, ChronoUnit.MINUTES));
    }

    @Nested
    @DisplayName("Basic CRUD")
    class CrudOperations {

        @Test
        @DisplayName("should save and retrieve schedule")
        void shouldSaveAndRetrieve() {
            UUID workflowId = UUID.randomUUID();
            Instant next = Instant.now().plus(1, ChronoUnit.HOURS);
            ScheduledExecutionEntity entity = new ScheduledExecutionEntity(
                    workflowId, "trigger:schedule", "tenant-1", "0 0 * * * *", "UTC", next);
            // V263 OrgScopedEntity: stamp org-id before persist (NOT NULL after V261)
            entity.setOrganizationId("tenant-1");
            ScheduledExecutionEntity saved = scheduledExecutionRepository.save(entity);

            assertThat(saved.getId()).isNotNull();
            assertThat(saved.getWorkflowId()).isEqualTo(workflowId);
            assertThat(saved.getTriggerId()).isEqualTo("trigger:schedule");
            assertThat(saved.getCronExpression()).isEqualTo("0 0 * * * *");
        }
    }

    @Nested
    @DisplayName("Due Execution Queries")
    class DueExecutionQueries {

        @Test
        @DisplayName("should find due executions")
        void shouldFindDueExecutions() {
            UUID w1 = UUID.randomUUID();
            UUID w2 = UUID.randomUUID();
            // Due (past)
            persistSchedule(w1, "trigger:s1", "t1", "0 * * * * *", true,
                    Instant.now().minus(5, ChronoUnit.MINUTES));
            // Not due (future)
            persistSchedule(w2, "trigger:s2", "t1", "0 * * * * *", true,
                    Instant.now().plus(1, ChronoUnit.HOURS));

            List<ScheduledExecutionEntity> due = scheduledExecutionRepository.findDueExecutions(Instant.now());

            assertThat(due).hasSize(1);
            assertThat(due.get(0).getWorkflowId()).isEqualTo(w1);
        }

        @Test
        @DisplayName("should exclude disabled schedules from due")
        void shouldExcludeDisabledFromDue() {
            UUID workflowId = UUID.randomUUID();
            persistSchedule(workflowId, "trigger:disabled", "t1", "0 * * * * *", false,
                    Instant.now().minus(5, ChronoUnit.MINUTES));

            List<ScheduledExecutionEntity> due = scheduledExecutionRepository.findDueExecutions(Instant.now());

            assertThat(due).isEmpty();
        }

        @Test
        @DisplayName("should exclude schedules that reached max executions")
        void shouldExcludeMaxExecutionsReached() {
            UUID workflowId = UUID.randomUUID();
            ScheduledExecutionEntity entity = persistSchedule(workflowId, "trigger:maxed", "t1",
                    "0 * * * * *", true, Instant.now().minus(5, ChronoUnit.MINUTES));
            entity.setMaxExecutions(3);
            entity.setExecutionCount(3);
            entityManager.merge(entity);
            entityManager.flush();

            List<ScheduledExecutionEntity> due = scheduledExecutionRepository.findDueExecutions(Instant.now());

            assertThat(due).isEmpty();
        }

        @Test
        @DisplayName("should order due executions by next execution time")
        void shouldOrderByNextExecution() {
            UUID w1 = UUID.randomUUID();
            UUID w2 = UUID.randomUUID();
            persistSchedule(w1, "trigger:later", "t1", "0 * * * * *", true,
                    Instant.now().minus(1, ChronoUnit.MINUTES));
            persistSchedule(w2, "trigger:earlier", "t1", "0 * * * * *", true,
                    Instant.now().minus(10, ChronoUnit.MINUTES));

            List<ScheduledExecutionEntity> due = scheduledExecutionRepository.findDueExecutions(Instant.now());

            assertThat(due).hasSize(2);
            assertThat(due.get(0).getWorkflowId()).isEqualTo(w2); // earlier first
        }
    }

    @Nested
    @DisplayName("Workflow/Trigger Queries")
    class WorkflowTriggerQueries {

        @Test
        @DisplayName("should find by workflow ID and trigger ID")
        void shouldFindByWorkflowIdAndTriggerId() {
            UUID workflowId = UUID.randomUUID();
            persistDefaultSchedule(workflowId, "trigger:s1");
            persistDefaultSchedule(workflowId, "trigger:s2");

            Optional<ScheduledExecutionEntity> found = scheduledExecutionRepository
                    .findByWorkflowIdAndTriggerId(workflowId, "trigger:s1");

            assertThat(found).isPresent();
            assertThat(found.get().getTriggerId()).isEqualTo("trigger:s1");
        }

        @Test
        @DisplayName("should find all by workflow ID")
        void shouldFindAllByWorkflowId() {
            UUID workflowId = UUID.randomUUID();
            persistDefaultSchedule(workflowId, "trigger:s1");
            persistDefaultSchedule(workflowId, "trigger:s2");
            persistDefaultSchedule(UUID.randomUUID(), "trigger:other");

            List<ScheduledExecutionEntity> found = scheduledExecutionRepository.findByWorkflowId(workflowId);

            assertThat(found).hasSize(2);
        }

        @Test
        @DisplayName("should check existence by workflow ID and trigger ID")
        void shouldCheckExistence() {
            UUID workflowId = UUID.randomUUID();
            persistDefaultSchedule(workflowId, "trigger:exists");

            assertThat(scheduledExecutionRepository.existsByWorkflowIdAndTriggerId(workflowId, "trigger:exists")).isTrue();
            assertThat(scheduledExecutionRepository.existsByWorkflowIdAndTriggerId(workflowId, "trigger:nope")).isFalse();
        }

        @Test
        @DisplayName("should check existence by workflow ID")
        void shouldCheckExistenceByWorkflowId() {
            UUID workflowId = UUID.randomUUID();
            persistDefaultSchedule(workflowId, "trigger:s1");

            assertThat(scheduledExecutionRepository.existsByWorkflowId(workflowId)).isTrue();
            assertThat(scheduledExecutionRepository.existsByWorkflowId(UUID.randomUUID())).isFalse();
        }
    }

    @Nested
    @DisplayName("Delete Operations")
    class DeleteOperations {

        @Test
        @DisplayName("should delete by workflow ID")
        void shouldDeleteByWorkflowId() {
            UUID workflowId = UUID.randomUUID();
            persistDefaultSchedule(workflowId, "trigger:s1");
            persistDefaultSchedule(workflowId, "trigger:s2");

            scheduledExecutionRepository.deleteByWorkflowId(workflowId);
            entityManager.flush();

            assertThat(scheduledExecutionRepository.findByWorkflowId(workflowId)).isEmpty();
        }

        @Test
        @DisplayName("should delete schedules not in trigger ID list")
        void shouldDeleteByWorkflowIdAndTriggerIdNotIn() {
            UUID workflowId = UUID.randomUUID();
            persistDefaultSchedule(workflowId, "trigger:keep");
            persistDefaultSchedule(workflowId, "trigger:remove");

            scheduledExecutionRepository.deleteByWorkflowIdAndTriggerIdNotIn(workflowId, List.of("trigger:keep"));
            entityManager.flush();

            List<ScheduledExecutionEntity> remaining = scheduledExecutionRepository.findByWorkflowId(workflowId);
            assertThat(remaining).hasSize(1);
            assertThat(remaining.get(0).getTriggerId()).isEqualTo("trigger:keep");
        }

        @Test
        @DisplayName("should delete expired schedules")
        void shouldDeleteExpired() {
            UUID workflowId = UUID.randomUUID();
            ScheduledExecutionEntity expired = persistDefaultSchedule(workflowId, "trigger:expired");
            expired.setExpiresAt(Instant.now().minus(1, ChronoUnit.DAYS));
            entityManager.merge(expired);
            entityManager.flush();

            int deleted = scheduledExecutionRepository.deleteExpired(Instant.now());

            assertThat(deleted).isEqualTo(1);
            assertThat(scheduledExecutionRepository.findByWorkflowId(workflowId)).isEmpty();
        }

        @Test
        @DisplayName("should not delete non-expired schedules")
        void shouldNotDeleteNonExpired() {
            UUID workflowId = UUID.randomUUID();
            ScheduledExecutionEntity notExpired = persistDefaultSchedule(workflowId, "trigger:valid");
            notExpired.setExpiresAt(Instant.now().plus(1, ChronoUnit.DAYS));
            entityManager.merge(notExpired);
            entityManager.flush();

            int deleted = scheduledExecutionRepository.deleteExpired(Instant.now());

            assertThat(deleted).isEqualTo(0);
            assertThat(scheduledExecutionRepository.findByWorkflowId(workflowId)).hasSize(1);
        }
    }

    @Nested
    @DisplayName("Business Methods")
    class BusinessMethods {

        @Test
        @DisplayName("should record execution and increment count")
        void shouldRecordExecution() {
            UUID workflowId = UUID.randomUUID();
            ScheduledExecutionEntity entity = persistDefaultSchedule(workflowId, "trigger:record");
            assertThat(entity.getExecutionCount()).isEqualTo(0);

            Instant nextExec = Instant.now().plus(5, ChronoUnit.MINUTES);
            entity.recordExecution(nextExec);

            assertThat(entity.getExecutionCount()).isEqualTo(1);
            assertThat(entity.getLastExecutionAt()).isNotNull();
            assertThat(entity.getNextExecutionAt()).isEqualTo(nextExec);
        }

        @Test
        @DisplayName("should detect max executions reached")
        void shouldDetectMaxExecutionsReached() {
            ScheduledExecutionEntity entity = new ScheduledExecutionEntity(
                    UUID.randomUUID(), "trigger:max", "t1", "0 * * * * *", "UTC", Instant.now());
            entity.setMaxExecutions(2);
            entity.setExecutionCount(2);

            assertThat(entity.hasReachedMaxExecutions()).isTrue();
        }

        @Test
        @DisplayName("should not reach max when no limit set")
        void shouldNotReachMaxWhenNoLimit() {
            ScheduledExecutionEntity entity = new ScheduledExecutionEntity(
                    UUID.randomUUID(), "trigger:unlimited", "t1", "0 * * * * *", "UTC", Instant.now());
            entity.setExecutionCount(1000);

            assertThat(entity.hasReachedMaxExecutions()).isFalse();
        }

        @Test
        @DisplayName("should check isDue correctly")
        void shouldCheckIsDue() {
            ScheduledExecutionEntity dueEntity = new ScheduledExecutionEntity(
                    UUID.randomUUID(), "trigger:due", "t1", "0 * * * * *", "UTC",
                    Instant.now().minus(1, ChronoUnit.MINUTES));
            dueEntity.setEnabled(true);

            assertThat(dueEntity.isDue()).isTrue();
        }

        @Test
        @DisplayName("should not be due when disabled")
        void shouldNotBeDueWhenDisabled() {
            ScheduledExecutionEntity entity = new ScheduledExecutionEntity(
                    UUID.randomUUID(), "trigger:disabled", "t1", "0 * * * * *", "UTC",
                    Instant.now().minus(1, ChronoUnit.MINUTES));
            entity.setEnabled(false);

            assertThat(entity.isDue()).isFalse();
        }
    }

    @Nested
    @DisplayName("Monitoring Queries")
    class MonitoringQueries {

        @Test
        @DisplayName("should count active schedules")
        void shouldCountActiveSchedules() {
            persistSchedule(UUID.randomUUID(), "trigger:active1", "t1", "0 * * * * *", true, Instant.now());
            persistSchedule(UUID.randomUUID(), "trigger:active2", "t1", "0 * * * * *", true, Instant.now());
            persistSchedule(UUID.randomUUID(), "trigger:inactive", "t1", "0 * * * * *", false, Instant.now());

            long count = scheduledExecutionRepository.countActiveSchedules();

            assertThat(count).isEqualTo(2);
        }
    }
}
