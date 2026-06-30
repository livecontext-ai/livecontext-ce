package com.apimarketplace.orchestrator.integration.repository;

import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowEntity.WorkflowStatus;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.ExecutionMode;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
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
 * Integration tests for {@link WorkflowRunRepository}.
 * Tests run CRUD, status management, and query methods against H2 in-memory database.
 */
@DataJpaIntegrationTest
class WorkflowRunRepositoryIntegrationTest {

    @Autowired
    private WorkflowRunRepository runRepository;

    @Autowired
    private TestEntityManager entityManager;

    private static final String TENANT_A = "tenant-a";
    private static final String TENANT_B = "tenant-b";
    private static final String USER_A = "user-a";

    private WorkflowEntity workflowA;
    private WorkflowEntity workflowB;

    @BeforeEach
    void setUp() {
        workflowA = createAndPersistWorkflow(TENANT_A, "Workflow A");
        workflowB = createAndPersistWorkflow(TENANT_B, "Workflow B");
    }

    private WorkflowEntity createAndPersistWorkflow(String tenantId, String name) {
        WorkflowEntity wf = new WorkflowEntity(tenantId, name, USER_A);
        wf.setId(UUID.randomUUID());
        wf.setStatus(WorkflowStatus.ACTIVE);
        wf.setIsActive(true);
        // V263 OrgScopedEntity NOT NULL - stamp before persist (no request scope
        // bound in @DataJpaTest, so listener can't auto-fill). Reuse tenantId
        // as the org id for test isolation parity.
        wf.setOrganizationId(tenantId);
        entityManager.persist(wf);
        entityManager.flush();
        return wf;
    }

    private WorkflowRunEntity createRun(WorkflowEntity workflow, String runIdPublic, RunStatus status) {
        WorkflowRunEntity run = new WorkflowRunEntity(
                workflow, workflow.getTenantId(), runIdPublic,
                Map.of("source", "test"), null, USER_A);
        run.setStatus(status);
        // V263 OrgScopedEntity NOT NULL - stamp before persist. WorkflowRunEntity
        // is OrgScopedEntity too; reuse the parent workflow's org id for parity.
        run.setOrganizationId(workflow.getOrganizationId());
        return run;
    }

    private WorkflowRunEntity persistRun(WorkflowEntity workflow, String runIdPublic, RunStatus status) {
        WorkflowRunEntity run = createRun(workflow, runIdPublic, status);
        entityManager.persist(run);
        entityManager.flush();
        return run;
    }

    @Nested
    @DisplayName("Basic CRUD operations")
    class CrudOperations {

        @Test
        @DisplayName("should save and retrieve run by ID")
        void shouldSaveAndRetrieveById() {
            WorkflowRunEntity run = persistRun(workflowA, "run-001", RunStatus.PENDING);
            entityManager.clear();

            Optional<WorkflowRunEntity> found = runRepository.findById(run.getId());

            assertThat(found).isPresent();
            assertThat(found.get().getRunIdPublic()).isEqualTo("run-001");
            assertThat(found.get().getTenantId()).isEqualTo(TENANT_A);
            assertThat(found.get().getStatus()).isEqualTo(RunStatus.PENDING);
        }

        @Test
        @DisplayName("should update run status")
        void shouldUpdateRunStatus() {
            WorkflowRunEntity run = persistRun(workflowA, "run-002", RunStatus.PENDING);
            entityManager.clear();

            WorkflowRunEntity toUpdate = runRepository.findById(run.getId()).orElseThrow();
            toUpdate.setStatus(RunStatus.RUNNING);
            toUpdate.setUpdatedAt(Instant.now());
            runRepository.save(toUpdate);
            entityManager.flush();
            entityManager.clear();

            WorkflowRunEntity updated = runRepository.findById(run.getId()).orElseThrow();
            assertThat(updated.getStatus()).isEqualTo(RunStatus.RUNNING);
        }

        @Test
        @DisplayName("should persist trigger payload as JSON")
        void shouldPersistTriggerPayload() {
            WorkflowRunEntity run = new WorkflowRunEntity(
                    workflowA, TENANT_A, "run-json",
                    Map.of("key1", "value1", "key2", 42), null, USER_A);
            // V263 OrgScopedEntity NOT NULL - stamp before persist.
            run.setOrganizationId(workflowA.getOrganizationId());
            entityManager.persist(run);
            entityManager.flush();
            entityManager.clear();

            WorkflowRunEntity found = runRepository.findById(run.getId()).orElseThrow();
            assertThat(found.getTriggerPayload()).containsEntry("key1", "value1");
        }

        @Test
        @DisplayName("should persist execution mode")
        void shouldPersistExecutionMode() {
            WorkflowRunEntity run = createRun(workflowA, "run-step-by-step", RunStatus.PENDING);
            run.setExecutionMode(ExecutionMode.STEP_BY_STEP);
            entityManager.persist(run);
            entityManager.flush();
            entityManager.clear();

            WorkflowRunEntity found = runRepository.findById(run.getId()).orElseThrow();
            assertThat(found.getExecutionMode()).isEqualTo(ExecutionMode.STEP_BY_STEP);
            assertThat(found.isStepByStepMode()).isTrue();
        }
    }

    @Nested
    @DisplayName("findByRunIdPublic queries")
    class RunIdPublicQueries {

        @Test
        @DisplayName("should find run by public ID")
        void shouldFindByRunIdPublic() {
            persistRun(workflowA, "unique-public-id", RunStatus.RUNNING);
            entityManager.clear();

            Optional<WorkflowRunEntity> found = runRepository.findByRunIdPublic("unique-public-id");

            assertThat(found).isPresent();
            assertThat(found.get().getStatus()).isEqualTo(RunStatus.RUNNING);
        }

        @Test
        @DisplayName("should return empty for non-existent public ID")
        void shouldReturnEmptyForNonExistentPublicId() {
            Optional<WorkflowRunEntity> result = runRepository.findByRunIdPublic("nonexistent");
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should check existence by public ID")
        void shouldCheckExistsByRunIdPublic() {
            persistRun(workflowA, "exists-check", RunStatus.PENDING);
            entityManager.clear();

            assertThat(runRepository.existsByRunIdPublic("exists-check")).isTrue();
            assertThat(runRepository.existsByRunIdPublic("does-not-exist")).isFalse();
        }
    }

    @Nested
    @DisplayName("State snapshot queries")
    class StateSnapshotQueries {

        @Test
        @DisplayName("should find state snapshot by public run ID")
        void shouldFindStateSnapshotByRunIdPublic() {
            WorkflowRunEntity run = createRun(workflowA, "run-snapshot", RunStatus.RUNNING);
            run.setStateSnapshot("{\"nodes\":{\"trigger:start\":{\"status\":\"COMPLETED\"}}}");
            entityManager.persist(run);
            entityManager.flush();
            entityManager.clear();

            Optional<String> snapshot = runRepository.findStateSnapshotByRunIdPublic("run-snapshot");

            assertThat(snapshot).isPresent();
            assertThat(snapshot.get()).contains("trigger:start");
        }

        @Test
        @DisplayName("should return empty snapshot for non-existent run")
        void shouldReturnEmptySnapshotForNonExistentRun() {
            Optional<String> snapshot = runRepository.findStateSnapshotByRunIdPublic("nonexistent");
            assertThat(snapshot).isEmpty();
        }
    }

    @Nested
    @DisplayName("Workflow-based queries")
    class WorkflowBasedQueries {

        @Test
        @DisplayName("should find runs by workflow ID ordered by started at desc")
        void shouldFindByWorkflowIdOrderedDesc() {
            WorkflowRunEntity run1 = createRun(workflowA, "run-old", RunStatus.COMPLETED);
            run1.setStartedAt(Instant.now().minus(2, ChronoUnit.HOURS));
            entityManager.persist(run1);

            WorkflowRunEntity run2 = createRun(workflowA, "run-new", RunStatus.RUNNING);
            run2.setStartedAt(Instant.now());
            entityManager.persist(run2);
            entityManager.flush();
            entityManager.clear();

            List<WorkflowRunEntity> result = runRepository.findByWorkflowIdOrderByStartedAtDesc(workflowA.getId());

            assertThat(result).hasSize(2);
            assertThat(result.get(0).getRunIdPublic()).isEqualTo("run-new");
            assertThat(result.get(1).getRunIdPublic()).isEqualTo("run-old");
        }

        @Test
        @DisplayName("should find runs by workflow ID with pagination")
        void shouldFindByWorkflowIdWithPagination() {
            for (int i = 0; i < 5; i++) {
                WorkflowRunEntity run = createRun(workflowA, "run-page-" + i, RunStatus.COMPLETED);
                run.setStartedAt(Instant.now().minus(i, ChronoUnit.HOURS));
                entityManager.persist(run);
            }
            entityManager.flush();
            entityManager.clear();

            Page<WorkflowRunEntity> page1 = runRepository.findByWorkflowIdOrderByStartedAtDescPageable(
                    workflowA.getId(), PageRequest.of(0, 3));
            Page<WorkflowRunEntity> page2 = runRepository.findByWorkflowIdOrderByStartedAtDescPageable(
                    workflowA.getId(), PageRequest.of(1, 3));

            assertThat(page1.getContent()).hasSize(3);
            assertThat(page1.getTotalElements()).isEqualTo(5);
            assertThat(page2.getContent()).hasSize(2);
        }

        @Test
        @DisplayName("should count runs by workflow ID")
        void shouldCountByWorkflowId() {
            persistRun(workflowA, "run-count-1", RunStatus.COMPLETED);
            persistRun(workflowA, "run-count-2", RunStatus.FAILED);
            persistRun(workflowB, "run-count-3", RunStatus.RUNNING);
            entityManager.clear();

            assertThat(runRepository.countByWorkflowId(workflowA.getId())).isEqualTo(2);
            assertThat(runRepository.countByWorkflowId(workflowB.getId())).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Status-based queries")
    class StatusQueries {

        @Test
        @DisplayName("should find runs by status")
        void shouldFindByStatus() {
            persistRun(workflowA, "run-running-1", RunStatus.RUNNING);
            persistRun(workflowA, "run-running-2", RunStatus.RUNNING);
            persistRun(workflowA, "run-completed", RunStatus.COMPLETED);
            entityManager.clear();

            List<WorkflowRunEntity> running = runRepository.findByStatus(RunStatus.RUNNING);
            List<WorkflowRunEntity> completed = runRepository.findByStatus(RunStatus.COMPLETED);

            assertThat(running).hasSize(2);
            assertThat(completed).hasSize(1);
        }

        @Test
        @DisplayName("should find runs by workflow ID and status")
        void shouldFindByWorkflowIdAndStatus() {
            persistRun(workflowA, "run-wait-1", RunStatus.WAITING_TRIGGER);
            persistRun(workflowA, "run-wait-2", RunStatus.WAITING_TRIGGER);
            persistRun(workflowA, "run-running", RunStatus.RUNNING);
            entityManager.clear();

            List<WorkflowRunEntity> waiting = runRepository.findByWorkflowIdAndStatus(
                    workflowA.getId(), RunStatus.WAITING_TRIGGER);

            assertThat(waiting).hasSize(2);
        }

        @Test
        @DisplayName("should count runs by workflow ID and status")
        void shouldCountByWorkflowIdAndStatus() {
            persistRun(workflowA, "run-a1", RunStatus.RUNNING);
            persistRun(workflowA, "run-a2", RunStatus.RUNNING);
            persistRun(workflowA, "run-a3", RunStatus.COMPLETED);
            entityManager.clear();

            assertThat(runRepository.countByWorkflowIdAndStatus(workflowA.getId(), RunStatus.RUNNING)).isEqualTo(2);
            assertThat(runRepository.countByWorkflowIdAndStatus(workflowA.getId(), RunStatus.COMPLETED)).isEqualTo(1);
            assertThat(runRepository.countByWorkflowIdAndStatus(workflowA.getId(), RunStatus.FAILED)).isZero();
        }

        @Test
        @DisplayName("should find most recent waiting trigger run")
        void shouldFindMostRecentWaitingTrigger() {
            WorkflowRunEntity old = createRun(workflowA, "run-old-wait", RunStatus.WAITING_TRIGGER);
            old.setStartedAt(Instant.now().minus(1, ChronoUnit.HOURS));
            entityManager.persist(old);

            WorkflowRunEntity recent = createRun(workflowA, "run-new-wait", RunStatus.WAITING_TRIGGER);
            recent.setStartedAt(Instant.now());
            entityManager.persist(recent);
            entityManager.flush();
            entityManager.clear();

            Optional<WorkflowRunEntity> result = runRepository
                    .findFirstByWorkflowIdAndStatusOrderByStartedAtDesc(
                            workflowA.getId(), RunStatus.WAITING_TRIGGER);

            assertThat(result).isPresent();
            assertThat(result.get().getRunIdPublic()).isEqualTo("run-new-wait");
        }
    }

    // BATCH-B (2026-05-20): {@code TenantQueries} block removed. It exercised
    // {@code findByTenantId} / {@code findByTenantIdOrderByStartedAtDesc} which
    // were deleted as orphans (no src/main caller). Org-scoped run listing
    // belongs to the {@code OrganizationQueries} block (covered elsewhere in
    // the suite).

    @Nested
    @DisplayName("Time-based queries")
    class TimeBasedQueries {

        @Test
        @DisplayName("should find runs by workflow and started after")
        void shouldFindByWorkflowAndStartedAfter() {
            Instant cutoff = Instant.now().minus(1, ChronoUnit.HOURS);

            WorkflowRunEntity old = createRun(workflowA, "run-before", RunStatus.COMPLETED);
            old.setStartedAt(cutoff.minus(1, ChronoUnit.HOURS));
            entityManager.persist(old);

            WorkflowRunEntity recent = createRun(workflowA, "run-after", RunStatus.COMPLETED);
            recent.setStartedAt(cutoff.plus(30, ChronoUnit.MINUTES));
            entityManager.persist(recent);
            entityManager.flush();
            entityManager.clear();

            List<WorkflowRunEntity> result = runRepository.findByWorkflowIdAndStartedAtAfter(
                    workflowA.getId(), cutoff);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getRunIdPublic()).isEqualTo("run-after");
        }
    }

    @Nested
    @DisplayName("findApplicationRunsBatch - application-dedicated runs per workflow")
    class ApplicationRunsBatch {

        private WorkflowRunEntity appRun(WorkflowEntity wf, String runIdPublic, Instant startedAt) {
            WorkflowRunEntity run = createRun(wf, runIdPublic, RunStatus.COMPLETED);
            run.setSource("application");
            run.setPublicationId("pub-" + wf.getId()); // not in the predicate; set for realism
            run.setStartedAt(startedAt);
            return run;
        }

        @Test
        @DisplayName("returns ONLY source='application' runs (builder/null + showcase excluded)")
        void filtersBySourceApplication() {
            entityManager.persist(appRun(workflowA, "app-run", Instant.now()));
            WorkflowRunEntity builder = createRun(workflowA, "builder-run", RunStatus.COMPLETED); // source null
            builder.setStartedAt(Instant.now());
            entityManager.persist(builder);
            WorkflowRunEntity showcase = createRun(workflowA, "showcase_x", RunStatus.COMPLETED);
            showcase.setSource("showcase"); // a clone preview, must NOT count as the application run
            showcase.setStartedAt(Instant.now());
            entityManager.persist(showcase);
            entityManager.flush();
            entityManager.clear();

            List<WorkflowRunEntity> result = runRepository.findApplicationRunsBatch(List.of(workflowA.getId()));

            assertThat(result).extracting(WorkflowRunEntity::getRunIdPublic).containsExactly("app-run");
        }

        @Test
        @DisplayName("returns the most-recent application run per workflow, batched across workflows")
        void onePerWorkflowMostRecent() {
            entityManager.persist(appRun(workflowA, "a-old", Instant.now().minus(2, ChronoUnit.HOURS)));
            entityManager.persist(appRun(workflowA, "a-new", Instant.now()));
            entityManager.persist(appRun(workflowB, "b-1", Instant.now()));
            entityManager.flush();
            entityManager.clear();

            List<WorkflowRunEntity> result = runRepository.findApplicationRunsBatch(
                List.of(workflowA.getId(), workflowB.getId()));

            assertThat(result).extracting(WorkflowRunEntity::getRunIdPublic)
                .containsExactlyInAnyOrder("a-new", "b-1");
        }

        @Test
        @DisplayName("tie-break on created_at when started_at matches (deterministic, newest-created wins)")
        void tieBreakOnCreatedAt() {
            Instant sharedStarted = Instant.parse("2026-04-30T17:09:04.600588Z");
            WorkflowRunEntity r1 = appRun(workflowA, "run-1", sharedStarted);
            r1.setCreatedAt(Instant.parse("2026-04-30T17:00:00.000Z"));
            entityManager.persist(r1);
            WorkflowRunEntity r2 = appRun(workflowA, "run-2", sharedStarted);
            r2.setCreatedAt(Instant.parse("2026-04-30T18:00:00.000Z")); // created later -> wins the tie
            entityManager.persist(r2);
            entityManager.flush();
            entityManager.clear();

            List<WorkflowRunEntity> result = runRepository.findApplicationRunsBatch(List.of(workflowA.getId()));

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getRunIdPublic()).isEqualTo("run-2");
        }

        @Test
        @DisplayName("a workflow with no application run is absent from the batch")
        void noApplicationRunAbsent() {
            WorkflowRunEntity builderOnly = createRun(workflowA, "builder-only", RunStatus.COMPLETED);
            builderOnly.setStartedAt(Instant.now());
            entityManager.persist(builderOnly);
            entityManager.flush();
            entityManager.clear();

            List<WorkflowRunEntity> result = runRepository.findApplicationRunsBatch(
                List.of(workflowA.getId(), workflowB.getId()));

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("Production-run lookups - deterministic ordering")
    class ProductionRunsBatch {

        // ── findProductionRunsBatch (native, board) ──────────────────────────

        @Test
        @DisplayName("findProductionRunsBatch: tie-break on created_at, not updated_at, when started_at matches")
        void batchTieBreakOnCreatedAtNotUpdatedAt() {
            // Two runs at pinned v15 share started_at. run-1 was re-executed manually so
            // updated_at is freshest, but run-2 was CREATED later. The board must surface
            // run-2 because "latest run of the version" means most-recently-created.
            workflowA.setPinnedVersion(15);
            entityManager.merge(workflowA);

            Instant sharedStarted = Instant.parse("2026-04-30T17:09:04.600588Z");

            WorkflowRunEntity run1 = createRun(workflowA, "run-1", RunStatus.WAITING_TRIGGER);
            run1.setPlanVersion(15);
            run1.setStartedAt(sharedStarted);
            run1.setCreatedAt(Instant.parse("2026-04-30T17:00:00.000Z"));
            run1.setUpdatedAt(Instant.parse("2026-05-01T12:00:00.000Z")); // bumped by manual re-run
            entityManager.persist(run1);

            WorkflowRunEntity run2 = createRun(workflowA, "run-2", RunStatus.WAITING_TRIGGER);
            run2.setPlanVersion(15);
            run2.setStartedAt(sharedStarted);
            run2.setCreatedAt(Instant.parse("2026-04-30T18:00:00.000Z"));
            run2.setUpdatedAt(Instant.parse("2026-04-30T18:00:00.000Z"));
            entityManager.persist(run2);

            entityManager.flush();
            entityManager.clear();

            List<WorkflowRunEntity> result = runRepository.findProductionRunsBatch(List.of(workflowA.getId()));

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getRunIdPublic()).isEqualTo("run-2");
        }

        @Test
        @DisplayName("findProductionRunsBatch: returns one row per workflow when batched")
        void batchReturnsOnePerWorkflow() {
            workflowA.setPinnedVersion(15);
            workflowB.setPinnedVersion(7);
            entityManager.merge(workflowA);
            entityManager.merge(workflowB);

            WorkflowRunEntity a1 = createRun(workflowA, "a-1", RunStatus.WAITING_TRIGGER);
            a1.setPlanVersion(15);
            a1.setStartedAt(Instant.now().minus(2, ChronoUnit.HOURS));
            entityManager.persist(a1);
            WorkflowRunEntity a2 = createRun(workflowA, "a-2", RunStatus.WAITING_TRIGGER);
            a2.setPlanVersion(15);
            a2.setStartedAt(Instant.now());
            entityManager.persist(a2);
            WorkflowRunEntity b1 = createRun(workflowB, "b-1", RunStatus.WAITING_TRIGGER);
            b1.setPlanVersion(7);
            b1.setStartedAt(Instant.now());
            entityManager.persist(b1);
            entityManager.flush();
            entityManager.clear();

            List<WorkflowRunEntity> result = runRepository.findProductionRunsBatch(
                List.of(workflowA.getId(), workflowB.getId()));

            assertThat(result).extracting(WorkflowRunEntity::getRunIdPublic)
                .containsExactlyInAnyOrder("a-2", "b-1");
        }

        @Test
        @DisplayName("findProductionRunsBatch: workflow without pinned_version is excluded")
        void batchExcludesUnpinnedWorkflows() {
            // workflowA is NOT pinned; even though a run exists at planVersion=15, the
            // batch query must skip it because the JOIN requires pinned_version IS NOT NULL.
            WorkflowRunEntity run = createRun(workflowA, "stray", RunStatus.WAITING_TRIGGER);
            run.setPlanVersion(15);
            run.setStartedAt(Instant.now());
            entityManager.persist(run);
            entityManager.flush();
            entityManager.clear();

            List<WorkflowRunEntity> result = runRepository.findProductionRunsBatch(List.of(workflowA.getId()));

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("findProductionRunsBatch: runs at non-pinned plan_version are excluded")
        void batchExcludesNonPinnedVersions() {
            workflowA.setPinnedVersion(15);
            entityManager.merge(workflowA);

            WorkflowRunEntity oldVersion = createRun(workflowA, "v14-run", RunStatus.WAITING_TRIGGER);
            oldVersion.setPlanVersion(14);
            oldVersion.setStartedAt(Instant.now());
            entityManager.persist(oldVersion);
            WorkflowRunEntity pinnedVersion = createRun(workflowA, "v15-run", RunStatus.WAITING_TRIGGER);
            pinnedVersion.setPlanVersion(15);
            pinnedVersion.setStartedAt(Instant.now().minus(1, ChronoUnit.HOURS));
            entityManager.persist(pinnedVersion);
            entityManager.flush();
            entityManager.clear();

            List<WorkflowRunEntity> result = runRepository.findProductionRunsBatch(List.of(workflowA.getId()));

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getRunIdPublic()).isEqualTo("v15-run");
        }

        // ── findFirstProductionRunByWorkflowIdAndPlanVersionAndStatusIn (JPQL, trigger LATEST_TRUSTED) ──

        @Test
        @DisplayName("findFirstProductionRunByWorkflowIdAndPlanVersionAndStatusIn: tie-break on created_at picks newest run")
        void trustedTriggerTieBreakOnCreatedAt() {
            // Same scenario as the batch case, but for the trigger path used by webhook /
            // form / chat / workflow-chain via ProductionRunResolver.LATEST_TRUSTED.
            Instant sharedStarted = Instant.parse("2026-04-30T17:09:04.600588Z");

            WorkflowRunEntity older = createRun(workflowA, "older", RunStatus.WAITING_TRIGGER);
            older.setPlanVersion(15);
            older.setStartedAt(sharedStarted);
            older.setCreatedAt(Instant.parse("2026-04-30T17:00:00.000Z"));
            entityManager.persist(older);

            WorkflowRunEntity newer = createRun(workflowA, "newer", RunStatus.WAITING_TRIGGER);
            newer.setPlanVersion(15);
            newer.setStartedAt(sharedStarted);
            newer.setCreatedAt(Instant.parse("2026-04-30T18:00:00.000Z"));
            entityManager.persist(newer);

            entityManager.flush();
            entityManager.clear();

            Optional<WorkflowRunEntity> result = runRepository
                .findFirstProductionRunByWorkflowIdAndPlanVersionAndStatusIn(
                    workflowA.getId(), 15, java.util.List.of(
                        RunStatus.COMPLETED, RunStatus.WAITING_TRIGGER, RunStatus.RUNNING, RunStatus.PAUSED));

            assertThat(result).isPresent();
            assertThat(result.get().getRunIdPublic()).isEqualTo("newer");
        }

        // ── findFirstProductionRunByWorkflowIdAndPlanVersionAndStatus (JPQL, schedule LATEST_WAITING_TRIGGER) ──

        @Test
        @DisplayName("findFirstByWorkflowIdOrderByStartedAtDesc: returns latest run with multiple rows present, no NonUniqueResultException")
        void findFirstByWorkflowIdHandlesMultipleRows() {
            // Regression: when @Query overrides the derived findFirst keyword, Spring Data
            // does NOT auto-apply LIMIT 1, and the JPQL form throws NonUniqueResultException
            // on multi-row results. Native SQL with explicit LIMIT 1 is the fix.
            WorkflowRunEntity older = createRun(workflowA, "older", RunStatus.COMPLETED);
            older.setStartedAt(Instant.now().minus(2, ChronoUnit.HOURS));
            entityManager.persist(older);
            WorkflowRunEntity newer = createRun(workflowA, "newer", RunStatus.RUNNING);
            newer.setStartedAt(Instant.now());
            entityManager.persist(newer);
            entityManager.flush();
            entityManager.clear();

            Optional<WorkflowRunEntity> result = runRepository.findFirstByWorkflowIdOrderByStartedAtDesc(workflowA.getId());

            assertThat(result).isPresent();
            assertThat(result.get().getRunIdPublic()).isEqualTo("newer");
        }

        @Test
        @DisplayName("findFirstProductionRunByWorkflowIdAndPlanVersionAndStatus: tie-break on created_at picks newest run")
        void waitingTriggerTieBreakOnCreatedAt() {
            // Same scenario for the schedule path (LATEST_WAITING_TRIGGER policy).
            Instant sharedStarted = Instant.parse("2026-04-30T17:09:04.600588Z");

            WorkflowRunEntity older = createRun(workflowA, "older", RunStatus.WAITING_TRIGGER);
            older.setPlanVersion(15);
            older.setStartedAt(sharedStarted);
            older.setCreatedAt(Instant.parse("2026-04-30T17:00:00.000Z"));
            entityManager.persist(older);

            WorkflowRunEntity newer = createRun(workflowA, "newer", RunStatus.WAITING_TRIGGER);
            newer.setPlanVersion(15);
            newer.setStartedAt(sharedStarted);
            newer.setCreatedAt(Instant.parse("2026-04-30T18:00:00.000Z"));
            entityManager.persist(newer);

            entityManager.flush();
            entityManager.clear();

            Optional<WorkflowRunEntity> result = runRepository
                .findFirstProductionRunByWorkflowIdAndPlanVersionAndStatus(
                    workflowA.getId(), 15, RunStatus.WAITING_TRIGGER);

            assertThat(result).isPresent();
            assertThat(result.get().getRunIdPublic()).isEqualTo("newer");
        }
    }

    @Nested
    @DisplayName("Plan version queries")
    class PlanVersionQueries {

        @Test
        @DisplayName("should count runs by plan version")
        void shouldCountRunsByPlanVersion() {
            WorkflowRunEntity v1run1 = createRun(workflowA, "run-v1-1", RunStatus.COMPLETED);
            v1run1.setPlanVersion(1);
            entityManager.persist(v1run1);

            WorkflowRunEntity v1run2 = createRun(workflowA, "run-v1-2", RunStatus.COMPLETED);
            v1run2.setPlanVersion(1);
            entityManager.persist(v1run2);

            WorkflowRunEntity v2run1 = createRun(workflowA, "run-v2-1", RunStatus.COMPLETED);
            v2run1.setPlanVersion(2);
            entityManager.persist(v2run1);
            entityManager.flush();
            entityManager.clear();

            List<Object[]> counts = runRepository.countRunsByPlanVersion(workflowA.getId());

            assertThat(counts).hasSize(2);
            // Results contain [planVersion, count] pairs
        }
    }

    @Nested
    @DisplayName("Duration and completion tracking")
    class CompletionTracking {

        @Test
        @DisplayName("should persist duration and end time on completion")
        void shouldPersistDurationOnCompletion() {
            WorkflowRunEntity run = createRun(workflowA, "run-duration", RunStatus.RUNNING);
            run.setStartedAt(Instant.now().minus(5, ChronoUnit.SECONDS));
            entityManager.persist(run);
            entityManager.flush();
            entityManager.clear();

            WorkflowRunEntity toComplete = runRepository.findById(run.getId()).orElseThrow();
            toComplete.setStatus(RunStatus.COMPLETED);
            toComplete.setEndedAt(Instant.now());
            toComplete.setDurationMs(5000L);
            toComplete.setTotalNodes(3);
            runRepository.save(toComplete);
            entityManager.flush();
            entityManager.clear();

            WorkflowRunEntity completed = runRepository.findById(run.getId()).orElseThrow();
            assertThat(completed.getStatus()).isEqualTo(RunStatus.COMPLETED);
            assertThat(completed.getEndedAt()).isNotNull();
            assertThat(completed.getDurationMs()).isEqualTo(5000L);
            assertThat(completed.getTotalNodes()).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("Mode-scoped live-run lookup (editor run-reuse window)")
    class ModeScopedLiveRunLookup {

        private static final List<RunStatus> LIVE = List.of(
                RunStatus.WAITING_TRIGGER, RunStatus.RUNNING, RunStatus.PAUSED);

        private WorkflowRunEntity persistModeRun(String runIdPublic, int planVersion,
                                                 ExecutionMode mode, RunStatus status,
                                                 Instant startedAt) {
            WorkflowRunEntity run = createRun(workflowA, runIdPublic, status);
            run.setPlanVersion(planVersion);
            run.setExecutionMode(mode);
            run.setStartedAt(startedAt);
            entityManager.persist(run);
            entityManager.flush();
            return run;
        }

        @Test
        @DisplayName("returns the live run of the requested mode even when a newer run exists in the other mode")
        void modeScopedLookupIgnoresNewerOtherModeRun() {
            // Regression (2026-06-11): a latest-run-only lookup saw the newer SBS run,
            // judged it a mode mismatch and minted a third run at the same version.
            Instant now = Instant.now();
            persistModeRun("run-auto-old", 3, ExecutionMode.AUTOMATIC, RunStatus.WAITING_TRIGGER,
                    now.minus(10, ChronoUnit.MINUTES));
            persistModeRun("run-sbs-new", 3, ExecutionMode.STEP_BY_STEP, RunStatus.PAUSED,
                    now.minus(1, ChronoUnit.MINUTES));

            Optional<WorkflowRunEntity> found = runRepository
                    .findFirstByWorkflowIdAndPlanVersionAndExecutionModeAndStatusInOrderByStartedAtDesc(
                            workflowA.getId(), 3, ExecutionMode.AUTOMATIC, LIVE);

            assertThat(found).isPresent();
            assertThat(found.get().getRunIdPublic()).isEqualTo("run-auto-old");
        }

        @Test
        @DisplayName("matches RUNNING and PAUSED runs (mid-epoch fires reuse the live run) but never terminal ones")
        void liveWindowMatchesActiveButNotTerminal() {
            Instant now = Instant.now();
            persistModeRun("run-completed", 7, ExecutionMode.AUTOMATIC, RunStatus.COMPLETED,
                    now.minus(2, ChronoUnit.MINUTES));
            persistModeRun("run-running", 7, ExecutionMode.AUTOMATIC, RunStatus.RUNNING,
                    now.minus(5, ChronoUnit.MINUTES));

            Optional<WorkflowRunEntity> found = runRepository
                    .findFirstByWorkflowIdAndPlanVersionAndExecutionModeAndStatusInOrderByStartedAtDesc(
                            workflowA.getId(), 7, ExecutionMode.AUTOMATIC, LIVE);

            assertThat(found).isPresent();
            assertThat(found.get().getRunIdPublic()).isEqualTo("run-running");
        }

        @Test
        @DisplayName("returns empty when the only live run at the version is in the other mode")
        void emptyWhenOnlyOtherModeLive() {
            persistModeRun("run-sbs-only", 9, ExecutionMode.STEP_BY_STEP, RunStatus.WAITING_TRIGGER,
                    Instant.now());

            Optional<WorkflowRunEntity> found = runRepository
                    .findFirstByWorkflowIdAndPlanVersionAndExecutionModeAndStatusInOrderByStartedAtDesc(
                            workflowA.getId(), 9, ExecutionMode.AUTOMATIC, LIVE);

            assertThat(found).isEmpty();
        }
    }
}
