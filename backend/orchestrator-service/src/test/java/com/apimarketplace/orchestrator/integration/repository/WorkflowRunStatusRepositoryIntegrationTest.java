package com.apimarketplace.orchestrator.integration.repository;

import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowEntity.WorkflowStatus;
import com.apimarketplace.orchestrator.domain.WorkflowRunStatusEntity;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.repository.WorkflowRunStatusRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link WorkflowRunStatusRepository}.
 * Tests run status persistence and lookup by run ID.
 */
@DataJpaIntegrationTest
class WorkflowRunStatusRepositoryIntegrationTest {

    @Autowired
    private WorkflowRunStatusRepository runStatusRepository;

    @Autowired
    private TestEntityManager entityManager;

    private static final String TENANT_A = "tenant-a";

    private WorkflowEntity workflow;

    @BeforeEach
    void setUp() {
        workflow = new WorkflowEntity(TENANT_A, "Test Workflow", "user-a");
        workflow.setId(UUID.randomUUID());
        workflow.setStatus(WorkflowStatus.ACTIVE);
        workflow.setIsActive(true);
        // V263 OrgScopedEntity NOT NULL - stamp before persist. No request scope
        // in @DataJpaTest, so listener cannot auto-fill. Reuse TENANT_A as org id.
        workflow.setOrganizationId(TENANT_A);
        entityManager.persist(workflow);
        entityManager.flush();
    }

    private WorkflowRunStatusEntity createRunStatus(UUID runId, RunStatus status, Map<String, Object> payload) {
        WorkflowRunStatusEntity entity = new WorkflowRunStatusEntity(runId, workflow, TENANT_A, status, payload);
        // V263 OrgScopedEntity NOT NULL - stamp before any persist. Parity with
        // parent workflow's org id (TENANT_A in the @BeforeEach setup).
        entity.setOrganizationId(TENANT_A);
        return entity;
    }

    @Nested
    @DisplayName("Basic CRUD operations")
    class CrudOperations {

        @Test
        @DisplayName("should save and retrieve run status by run ID")
        void shouldSaveAndRetrieveByRunId() {
            UUID runId = UUID.randomUUID();
            Map<String, Object> payload = Map.of(
                    "totalNodes", 5,
                    "completedNodes", 3,
                    "status", "RUNNING"
            );

            WorkflowRunStatusEntity entity = createRunStatus(runId, RunStatus.RUNNING, payload);
            entityManager.persist(entity);
            entityManager.flush();
            entityManager.clear();

            Optional<WorkflowRunStatusEntity> found = runStatusRepository.findByRunId(runId);

            assertThat(found).isPresent();
            assertThat(found.get().getRunId()).isEqualTo(runId);
            assertThat(found.get().getStatus()).isEqualTo(RunStatus.RUNNING);
            assertThat(found.get().getTenantId()).isEqualTo(TENANT_A);
            assertThat(found.get().getPayload()).containsEntry("totalNodes", 5);
            assertThat(found.get().getCreatedAt()).isNotNull();
            assertThat(found.get().getUpdatedAt()).isNotNull();
        }

        @Test
        @DisplayName("should update status and payload")
        void shouldUpdateStatusAndPayload() {
            UUID runId = UUID.randomUUID();
            WorkflowRunStatusEntity entity = createRunStatus(runId, RunStatus.RUNNING,
                    Map.of("completedNodes", 0));
            entityManager.persist(entity);
            entityManager.flush();
            entityManager.clear();

            WorkflowRunStatusEntity toUpdate = runStatusRepository.findByRunId(runId).orElseThrow();
            toUpdate.setStatus(RunStatus.COMPLETED);
            toUpdate.setPayload(Map.of("completedNodes", 5, "totalNodes", 5));
            runStatusRepository.save(toUpdate);
            entityManager.flush();
            entityManager.clear();

            WorkflowRunStatusEntity updated = runStatusRepository.findByRunId(runId).orElseThrow();
            assertThat(updated.getStatus()).isEqualTo(RunStatus.COMPLETED);
            assertThat(updated.getPayload()).containsEntry("completedNodes", 5);
        }

        @Test
        @DisplayName("should return empty for non-existent run ID")
        void shouldReturnEmptyForNonExistentRunId() {
            Optional<WorkflowRunStatusEntity> result = runStatusRepository.findByRunId(UUID.randomUUID());
            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("Payload persistence")
    class PayloadPersistence {

        @Test
        @DisplayName("should persist complex JSON payload")
        void shouldPersistComplexPayload() {
            UUID runId = UUID.randomUUID();
            Map<String, Object> complexPayload = Map.of(
                    "nodes", Map.of(
                            "trigger:start", Map.of("status", "COMPLETED"),
                            "mcp:api_call", Map.of("status", "RUNNING")
                    ),
                    "edges", Map.of(
                            "trigger:start->mcp:api_call", "COMPLETED"
                    ),
                    "totalNodes", 3,
                    "completedNodes", 1
            );

            WorkflowRunStatusEntity entity = createRunStatus(runId, RunStatus.RUNNING, complexPayload);
            entityManager.persist(entity);
            entityManager.flush();
            entityManager.clear();

            WorkflowRunStatusEntity found = runStatusRepository.findByRunId(runId).orElseThrow();
            assertThat(found.getPayload()).containsKey("nodes");
            assertThat(found.getPayload()).containsKey("edges");
        }
    }

    @Nested
    @DisplayName("Multiple run statuses")
    class MultipleRunStatuses {

        @Test
        @DisplayName("should handle multiple distinct run statuses")
        void shouldHandleMultipleDistinctStatuses() {
            UUID runId1 = UUID.randomUUID();
            UUID runId2 = UUID.randomUUID();

            WorkflowRunStatusEntity status1 = createRunStatus(runId1, RunStatus.COMPLETED,
                    Map.of("result", "success"));
            entityManager.persist(status1);

            WorkflowRunStatusEntity status2 = createRunStatus(runId2, RunStatus.FAILED,
                    Map.of("error", "timeout"));
            entityManager.persist(status2);
            entityManager.flush();
            entityManager.clear();

            Optional<WorkflowRunStatusEntity> found1 = runStatusRepository.findByRunId(runId1);
            Optional<WorkflowRunStatusEntity> found2 = runStatusRepository.findByRunId(runId2);

            assertThat(found1).isPresent();
            assertThat(found1.get().getStatus()).isEqualTo(RunStatus.COMPLETED);
            assertThat(found2).isPresent();
            assertThat(found2.get().getStatus()).isEqualTo(RunStatus.FAILED);
        }
    }
}
