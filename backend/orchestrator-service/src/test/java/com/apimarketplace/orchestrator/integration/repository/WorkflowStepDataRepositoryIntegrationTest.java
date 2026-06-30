package com.apimarketplace.orchestrator.integration.repository;

import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowEntity.WorkflowStatus;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.WorkflowStepDataEntity;
import com.apimarketplace.orchestrator.domain.execution.NodeType;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.persistence.WorkflowStepDataRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link WorkflowStepDataRepository}.
 * Tests step data persistence, custom queries, and edge cases against H2.
 */
@DataJpaIntegrationTest
class WorkflowStepDataRepositoryIntegrationTest {

    @Autowired
    private WorkflowStepDataRepository stepDataRepository;

    @Autowired
    private TestEntityManager entityManager;

    private static final String TENANT_A = "tenant-a";
    private static final String TENANT_B = "tenant-b";
    private static final String RUN_ID = "run-001";
    private static final String RUN_ID_2 = "run-002";

    private WorkflowEntity workflow;
    private WorkflowRunEntity workflowRun;
    private UUID workflowRunId;

    @BeforeEach
    void setUp() {
        workflow = new WorkflowEntity(TENANT_A, "Test Workflow", "user-a");
        workflow.setId(UUID.randomUUID());
        workflow.setStatus(WorkflowStatus.ACTIVE);
        workflow.setIsActive(true);
        // V263 OrgScopedEntity NOT NULL - stamp before persist. No request scope
        // in @DataJpaTest. Reuse TENANT_A as the org id for parity.
        workflow.setOrganizationId(TENANT_A);
        entityManager.persist(workflow);

        workflowRun = new WorkflowRunEntity(workflow, TENANT_A, RUN_ID, null, null, "user-a");
        workflowRun.setStatus(RunStatus.RUNNING);
        // V263 - WorkflowRunEntity is OrgScopedEntity too.
        workflowRun.setOrganizationId(TENANT_A);
        entityManager.persist(workflowRun);
        entityManager.flush();
        workflowRunId = workflowRun.getId();
    }

    private WorkflowStepDataEntity createStepData(String runId, String stepAlias, String toolId, String status) {
        WorkflowStepDataEntity step = new WorkflowStepDataEntity();
        step.setWorkflowRunId(workflowRunId);
        step.setRunId(runId);
        step.setStepAlias(stepAlias);
        step.setToolId(toolId);
        step.setStatus(status);
        step.setTenantId(TENANT_A);
        // V263 OrgScopedEntity NOT NULL - stamp before any persist. WorkflowStepDataEntity
        // is OrgScopedEntity; reuse the run's tenant/org id for parity.
        step.setOrganizationId(TENANT_A);
        step.setStartTime(Instant.now());
        step.setEpoch(0);
        step.setSpawn(0);
        step.setIteration(0);
        step.setItemIndex(0);
        return step;
    }

    private WorkflowStepDataEntity persistStep(String runId, String stepAlias, String toolId, String status) {
        WorkflowStepDataEntity step = createStepData(runId, stepAlias, toolId, status);
        entityManager.persist(step);
        entityManager.flush();
        return step;
    }

    @Nested
    @DisplayName("Basic CRUD operations")
    class CrudOperations {

        @Test
        @DisplayName("should save and retrieve step data by ID")
        void shouldSaveAndRetrieveById() {
            WorkflowStepDataEntity step = persistStep(RUN_ID, "api_call", "tool-123", "COMPLETED");
            entityManager.clear();

            Optional<WorkflowStepDataEntity> found = stepDataRepository.findById(step.getId());

            assertThat(found).isPresent();
            assertThat(found.get().getStepAlias()).isEqualTo("api_call");
            assertThat(found.get().getToolId()).isEqualTo("tool-123");
            assertThat(found.get().getStatus()).isEqualTo("COMPLETED");
            assertThat(found.get().getTenantId()).isEqualTo(TENANT_A);
        }

        @Test
        @DisplayName("should persist all fields including new unified columns")
        void shouldPersistAllFields() {
            WorkflowStepDataEntity step = createStepData(RUN_ID, "decision_node", "decision-tool", "COMPLETED");
            step.setNodeType(NodeType.DECISION);
            step.setConditionExpression("#{data.amount > 100}");
            step.setConditionResult(true);
            step.setSelectedBranch("if");
            step.setNormalizedKey("core:check_amount");
            step.setItemId("item-1");
            step.setItemNumber(1);
            step.setHttpStatus(200);
            step.setErrorMessage(null);
            step.setEndTime(Instant.now());
            entityManager.persist(step);
            entityManager.flush();
            entityManager.clear();

            WorkflowStepDataEntity found = stepDataRepository.findById(step.getId()).orElseThrow();
            assertThat(found.getNodeType()).isEqualTo(NodeType.DECISION);
            assertThat(found.getConditionExpression()).isEqualTo("#{data.amount > 100}");
            assertThat(found.getConditionResult()).isTrue();
            assertThat(found.getSelectedBranch()).isEqualTo("if");
            assertThat(found.getNormalizedKey()).isEqualTo("core:check_amount");
            assertThat(found.getItemId()).isEqualTo("item-1");
            assertThat(found.getItemNumber()).isEqualTo(1);
        }

        @Test
        @DisplayName("should persist loop-related fields")
        void shouldPersistLoopFields() {
            WorkflowStepDataEntity step = createStepData(RUN_ID, "loop_step", "loop-tool", "COMPLETED");
            step.setNodeType(NodeType.LOOP_CONTROLLER);
            step.setLoopId("core:my_loop");
            step.setLoopIteration(3);
            step.setLoopExitReason("condition_false");
            entityManager.persist(step);
            entityManager.flush();
            entityManager.clear();

            WorkflowStepDataEntity found = stepDataRepository.findById(step.getId()).orElseThrow();
            assertThat(found.getLoopId()).isEqualTo("core:my_loop");
            assertThat(found.getLoopIteration()).isEqualTo(3);
            assertThat(found.getLoopExitReason()).isEqualTo("condition_false");
        }

        @Test
        @DisplayName("should persist merge-related fields")
        void shouldPersistMergeFields() {
            WorkflowStepDataEntity step = createStepData(RUN_ID, "merge_step", "merge-tool", "COMPLETED");
            step.setNodeType(NodeType.MERGE);
            step.setMergeStrategy("WAIT_ALL");
            step.setMergeReceivedBranches(List.of("branch_0", "branch_1"));
            step.setMergeSkippedBranches(List.of("branch_2"));
            entityManager.persist(step);
            entityManager.flush();
            entityManager.clear();

            WorkflowStepDataEntity found = stepDataRepository.findById(step.getId()).orElseThrow();
            assertThat(found.getMergeStrategy()).isEqualTo("WAIT_ALL");
            assertThat(found.getMergeReceivedBranches()).containsExactly("branch_0", "branch_1");
            assertThat(found.getMergeSkippedBranches()).containsExactly("branch_2");
        }

        @Test
        @DisplayName("should persist skip-related fields")
        void shouldPersistSkipFields() {
            WorkflowStepDataEntity step = createStepData(RUN_ID, "skipped_step", "tool-x", "SKIPPED");
            step.setSkipReason("Decision else branch - condition was false");
            step.setSkipSourceNode("core:check_amount");
            entityManager.persist(step);
            entityManager.flush();
            entityManager.clear();

            WorkflowStepDataEntity found = stepDataRepository.findById(step.getId()).orElseThrow();
            assertThat(found.getSkipReason()).isEqualTo("Decision else branch - condition was false");
            assertThat(found.getSkipSourceNode()).isEqualTo("core:check_amount");
        }
    }

    @Nested
    @DisplayName("findByRunId queries")
    class RunIdQueries {

        @Test
        @DisplayName("should find all steps for a run")
        void shouldFindByRunId() {
            persistStep(RUN_ID, "step1", "tool-1", "COMPLETED");
            persistStep(RUN_ID, "step2", "tool-2", "RUNNING");
            persistStep(RUN_ID_2, "step3", "tool-3", "PENDING");
            entityManager.clear();

            List<WorkflowStepDataEntity> result = stepDataRepository.findByRunId(RUN_ID);

            assertThat(result).hasSize(2);
            assertThat(result).allMatch(s -> s.getRunId().equals(RUN_ID));
        }

        @Test
        @DisplayName("should count steps by run ID")
        void shouldCountByRunId() {
            persistStep(RUN_ID, "step1", "tool-1", "COMPLETED");
            persistStep(RUN_ID, "step2", "tool-2", "RUNNING");
            persistStep(RUN_ID_2, "step3", "tool-3", "PENDING");
            entityManager.clear();

            assertThat(stepDataRepository.countByRunId(RUN_ID)).isEqualTo(2);
            assertThat(stepDataRepository.countByRunId(RUN_ID_2)).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Epoch and re-run queries")
    class EpochQueries {

        @Test
        @DisplayName("should find steps by run ID and epoch")
        void shouldFindByRunIdAndEpoch() {
            WorkflowStepDataEntity epoch0 = createStepData(RUN_ID, "step_e0", "tool-1", "COMPLETED");
            epoch0.setEpoch(0);
            entityManager.persist(epoch0);

            WorkflowStepDataEntity epoch1 = createStepData(RUN_ID, "step_e1", "tool-2", "COMPLETED");
            epoch1.setEpoch(1);
            entityManager.persist(epoch1);
            entityManager.flush();
            entityManager.clear();

            List<WorkflowStepDataEntity> result = stepDataRepository.findByRunIdAndEpoch(RUN_ID, 0);
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getStepAlias()).isEqualTo("step_e0");
        }

        @Test
        @DisplayName("should find steps by normalized key ordered by epoch desc")
        void shouldFindByNormalizedKeyOrderedByEpochDesc() {
            for (int epoch = 0; epoch < 3; epoch++) {
                WorkflowStepDataEntity step = createStepData(RUN_ID, "api_call", "tool-1",
                        epoch < 2 ? "FAILED" : "COMPLETED");
                step.setEpoch(epoch);
                step.setNormalizedKey("mcp:api_call");
                entityManager.persist(step);
            }
            entityManager.flush();
            entityManager.clear();

            List<WorkflowStepDataEntity> result = stepDataRepository
                    .findByRunIdAndNormalizedKeyOrderByEpochDesc(RUN_ID, "mcp:api_call");

            assertThat(result).hasSize(3);
            assertThat(result.get(0).getEpoch()).isEqualTo(2); // Newest first
            assertThat(result.get(2).getEpoch()).isEqualTo(0); // Oldest last
        }

        @Test
        @DisplayName("deduplicates spawn reruns when counting interface render pages")
        void countDistinctItemsByRunIdAndNormalizedKeyDeduplicatesSpawnReruns() {
            for (int spawn = 0; spawn < 2; spawn++) {
                WorkflowStepDataEntity rerun = createStepData(RUN_ID, "application_entry", "interface-tool", "COMPLETED");
                rerun.setNodeType(NodeType.INTERFACE);
                rerun.setNormalizedKey("interface:application_entry");
                rerun.setEpoch(7);
                rerun.setItemIndex(3);
                rerun.setSpawn(spawn);
                entityManager.persist(rerun);
            }

            WorkflowStepDataEntity otherItem = createStepData(RUN_ID, "application_entry", "interface-tool", "COMPLETED");
            otherItem.setNodeType(NodeType.INTERFACE);
            otherItem.setNormalizedKey("interface:application_entry");
            otherItem.setEpoch(7);
            otherItem.setItemIndex(4);
            otherItem.setSpawn(0);
            entityManager.persist(otherItem);
            entityManager.flush();
            entityManager.clear();

            assertThat(stepDataRepository.countDistinctItemsByRunIdAndNormalizedKey(RUN_ID, "interface:application_entry"))
                    .isEqualTo(2);
            assertThat(stepDataRepository.countDistinctItemsExcludingTriggers(RUN_ID)).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("Workflow run ID queries")
    class WorkflowRunIdQueries {

        @Test
        @DisplayName("should find steps by workflow run ID ordered by ID asc")
        void shouldFindByWorkflowRunIdOrderedAsc() {
            persistStep(RUN_ID, "step1", "tool-1", "COMPLETED");
            persistStep(RUN_ID, "step2", "tool-2", "RUNNING");
            entityManager.clear();

            List<WorkflowStepDataEntity> result = stepDataRepository.findByWorkflowRunIdOrderByIdAsc(workflowRunId);

            assertThat(result).hasSize(2);
            assertThat(result.get(0).getId()).isLessThan(result.get(1).getId());
        }

    }

    @Nested
    @DisplayName("Delete operations")
    class DeleteOperations {

        @Test
        @DisplayName("should delete steps by run ID")
        void shouldDeleteByRunId() {
            persistStep(RUN_ID, "step1", "tool-1", "COMPLETED");
            persistStep(RUN_ID, "step2", "tool-2", "COMPLETED");
            persistStep(RUN_ID_2, "step3", "tool-3", "COMPLETED");
            entityManager.clear();

            stepDataRepository.deleteByRunId(RUN_ID);
            entityManager.flush();
            entityManager.clear();

            assertThat(stepDataRepository.findByRunId(RUN_ID)).isEmpty();
            assertThat(stepDataRepository.findByRunId(RUN_ID_2)).hasSize(1);
        }

    }

    @Nested
    @DisplayName("Top/latest queries")
    class TopQueries {

        @Test
        @DisplayName("should find latest step by run ID")
        void shouldFindTopByRunIdOrderByIdDesc() {
            persistStep(RUN_ID, "step1", "tool-1", "COMPLETED");
            persistStep(RUN_ID, "step2", "tool-2", "RUNNING");
            persistStep(RUN_ID, "step3", "tool-3", "PENDING");
            entityManager.clear();

            Optional<WorkflowStepDataEntity> latest = stepDataRepository.findTopByRunIdOrderByIdDesc(RUN_ID);

            assertThat(latest).isPresent();
            assertThat(latest.get().getStepAlias()).isEqualTo("step3");
        }
    }

    @Nested
    @DisplayName("Empty results edge cases")
    class EmptyResults {

        @Test
        @DisplayName("should return empty for non-existent run ID")
        void shouldReturnEmptyForNonExistentRunId() {
            assertThat(stepDataRepository.findByRunId("nonexistent")).isEmpty();
            assertThat(stepDataRepository.countByRunId("nonexistent")).isZero();
        }

    }
}
