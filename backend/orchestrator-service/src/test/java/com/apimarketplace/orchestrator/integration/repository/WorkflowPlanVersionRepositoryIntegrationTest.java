package com.apimarketplace.orchestrator.integration.repository;

import com.apimarketplace.orchestrator.domain.WorkflowPlanVersionEntity;
import com.apimarketplace.orchestrator.repository.WorkflowPlanVersionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link WorkflowPlanVersionRepository}.
 * Tests plan version history CRUD, max version lookup, and version ordering.
 */
@DataJpaIntegrationTest
class WorkflowPlanVersionRepositoryIntegrationTest {

    @Autowired
    private WorkflowPlanVersionRepository versionRepository;

    @Autowired
    private TestEntityManager entityManager;

    private WorkflowPlanVersionEntity createVersion(UUID workflowId, int version, String createdBy) {
        Map<String, Object> plan = Map.of(
                "triggers", List.of(Map.of("type", "manual")),
                "steps", List.of(),
                "version_label", "v" + version
        );
        return new WorkflowPlanVersionEntity(workflowId, version, plan, createdBy);
    }

    private WorkflowPlanVersionEntity persistVersion(UUID workflowId, int version, String createdBy) {
        WorkflowPlanVersionEntity entity = createVersion(workflowId, version, createdBy);
        entityManager.persist(entity);
        entityManager.flush();
        return entity;
    }

    @Nested
    @DisplayName("Basic CRUD operations")
    class CrudOperations {

        @Test
        @DisplayName("should save and retrieve plan version")
        void shouldSaveAndRetrieve() {
            UUID workflowId = UUID.randomUUID();
            WorkflowPlanVersionEntity saved = persistVersion(workflowId, 1, "user-a");
            entityManager.clear();

            Optional<WorkflowPlanVersionEntity> found = versionRepository.findById(saved.getId());

            assertThat(found).isPresent();
            assertThat(found.get().getWorkflowId()).isEqualTo(workflowId);
            assertThat(found.get().getVersion()).isEqualTo(1);
            assertThat(found.get().getCreatedBy()).isEqualTo("user-a");
            assertThat(found.get().getPlan()).containsKey("triggers");
            assertThat(found.get().getCreatedAt()).isNotNull();
        }

        @Test
        @DisplayName("should persist label field")
        void shouldPersistLabel() {
            UUID workflowId = UUID.randomUUID();
            WorkflowPlanVersionEntity entity = createVersion(workflowId, 1, "user-a");
            entity.setLabel("Initial release");
            entityManager.persist(entity);
            entityManager.flush();
            entityManager.clear();

            WorkflowPlanVersionEntity found = versionRepository.findById(entity.getId()).orElseThrow();
            assertThat(found.getLabel()).isEqualTo("Initial release");
        }
    }

    @Nested
    @DisplayName("Version number queries")
    class VersionNumberQueries {

        @Test
        @DisplayName("should get max version for a workflow")
        void shouldGetMaxVersion() {
            UUID workflowId = UUID.randomUUID();
            persistVersion(workflowId, 1, "user-a");
            persistVersion(workflowId, 2, "user-a");
            persistVersion(workflowId, 3, "user-a");
            entityManager.clear();

            Optional<Integer> maxVersion = versionRepository.getMaxVersion(workflowId);

            assertThat(maxVersion).isPresent();
            assertThat(maxVersion.get()).isEqualTo(3);
        }

        @Test
        @DisplayName("should return empty max version for workflow with no versions")
        void shouldReturnEmptyMaxVersionForNonExistent() {
            Optional<Integer> maxVersion = versionRepository.getMaxVersion(UUID.randomUUID());
            assertThat(maxVersion).isEmpty();
        }

        @Test
        @DisplayName("should not mix versions across workflows")
        void shouldNotMixVersionsAcrossWorkflows() {
            UUID wf1 = UUID.randomUUID();
            UUID wf2 = UUID.randomUUID();

            persistVersion(wf1, 1, "user-a");
            persistVersion(wf1, 2, "user-a");
            persistVersion(wf2, 1, "user-b");
            persistVersion(wf2, 2, "user-b");
            persistVersion(wf2, 3, "user-b");
            entityManager.clear();

            assertThat(versionRepository.getMaxVersion(wf1).orElse(0)).isEqualTo(2);
            assertThat(versionRepository.getMaxVersion(wf2).orElse(0)).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("Version listing queries")
    class VersionListingQueries {

        @Test
        @DisplayName("should find versions ordered by version desc")
        void shouldFindVersionsOrderedDesc() {
            UUID workflowId = UUID.randomUUID();
            persistVersion(workflowId, 1, "user-a");
            persistVersion(workflowId, 2, "user-a");
            persistVersion(workflowId, 3, "user-a");
            entityManager.clear();

            List<WorkflowPlanVersionEntity> result = versionRepository
                    .findByWorkflowIdOrderByVersionDesc(workflowId);

            assertThat(result).hasSize(3);
            assertThat(result.get(0).getVersion()).isEqualTo(3);
            assertThat(result.get(1).getVersion()).isEqualTo(2);
            assertThat(result.get(2).getVersion()).isEqualTo(1);
        }

        @Test
        @DisplayName("should find specific version by workflow and version number")
        void shouldFindByWorkflowIdAndVersion() {
            UUID workflowId = UUID.randomUUID();
            persistVersion(workflowId, 1, "user-a");
            persistVersion(workflowId, 2, "user-a");
            entityManager.clear();

            Optional<WorkflowPlanVersionEntity> found = versionRepository
                    .findByWorkflowIdAndVersion(workflowId, 2);

            assertThat(found).isPresent();
            assertThat(found.get().getVersion()).isEqualTo(2);
        }

        @Test
        @DisplayName("should return empty for non-existent version")
        void shouldReturnEmptyForNonExistentVersion() {
            UUID workflowId = UUID.randomUUID();
            persistVersion(workflowId, 1, "user-a");
            entityManager.clear();

            Optional<WorkflowPlanVersionEntity> result = versionRepository
                    .findByWorkflowIdAndVersion(workflowId, 99);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("Count queries")
    class CountQueries {

        @Test
        @DisplayName("should count versions for a workflow")
        void shouldCountByWorkflowId() {
            UUID wf1 = UUID.randomUUID();
            UUID wf2 = UUID.randomUUID();

            persistVersion(wf1, 1, "user-a");
            persistVersion(wf1, 2, "user-a");
            persistVersion(wf2, 1, "user-b");
            entityManager.clear();

            assertThat(versionRepository.countByWorkflowId(wf1)).isEqualTo(2);
            assertThat(versionRepository.countByWorkflowId(wf2)).isEqualTo(1);
            assertThat(versionRepository.countByWorkflowId(UUID.randomUUID())).isZero();
        }
    }

    @Nested
    @DisplayName("Plan data persistence")
    class PlanDataPersistence {

        @Test
        @DisplayName("should persist and retrieve complex plan JSON")
        void shouldPersistComplexPlan() {
            UUID workflowId = UUID.randomUUID();
            Map<String, Object> complexPlan = Map.of(
                    "triggers", List.of(Map.of("type", "webhook", "id", "trigger:wh")),
                    "steps", List.of(Map.of("id", "mcp:api_call", "tool", "rest-api")),
                    "edges", List.of(Map.of("from", "trigger:wh", "to", "mcp:api_call")),
                    "cores", List.of()
            );

            WorkflowPlanVersionEntity entity = new WorkflowPlanVersionEntity(
                    workflowId, 1, complexPlan, "user-a");
            entityManager.persist(entity);
            entityManager.flush();
            entityManager.clear();

            WorkflowPlanVersionEntity found = versionRepository.findById(entity.getId()).orElseThrow();
            assertThat(found.getPlan()).containsKey("triggers");
            assertThat(found.getPlan()).containsKey("steps");
            assertThat(found.getPlan()).containsKey("edges");
            assertThat(found.getPlan()).containsKey("cores");
        }

        @Test
        @DisplayName("should handle different plans for different versions")
        void shouldHandleDifferentPlansPerVersion() {
            UUID workflowId = UUID.randomUUID();

            WorkflowPlanVersionEntity v1 = new WorkflowPlanVersionEntity(
                    workflowId, 1, Map.of("steps", List.of("step-a")), "user-a");
            entityManager.persist(v1);

            WorkflowPlanVersionEntity v2 = new WorkflowPlanVersionEntity(
                    workflowId, 2, Map.of("steps", List.of("step-a", "step-b")), "user-a");
            entityManager.persist(v2);
            entityManager.flush();
            entityManager.clear();

            WorkflowPlanVersionEntity foundV1 = versionRepository
                    .findByWorkflowIdAndVersion(workflowId, 1).orElseThrow();
            WorkflowPlanVersionEntity foundV2 = versionRepository
                    .findByWorkflowIdAndVersion(workflowId, 2).orElseThrow();

            assertThat(foundV1.getPlan().get("steps")).isNotEqualTo(foundV2.getPlan().get("steps"));
        }
    }

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("should handle workflow with no versions")
        void shouldHandleWorkflowWithNoVersions() {
            UUID workflowId = UUID.randomUUID();

            assertThat(versionRepository.findByWorkflowIdOrderByVersionDesc(workflowId)).isEmpty();
            assertThat(versionRepository.getMaxVersion(workflowId)).isEmpty();
            assertThat(versionRepository.countByWorkflowId(workflowId)).isZero();
        }
    }
}
