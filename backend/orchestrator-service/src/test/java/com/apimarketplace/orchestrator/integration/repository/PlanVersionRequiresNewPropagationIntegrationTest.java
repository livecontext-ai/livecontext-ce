package com.apimarketplace.orchestrator.integration.repository;

import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.apimarketplace.orchestrator.domain.WorkflowPlanVersionEntity;
import com.apimarketplace.orchestrator.repository.WorkflowPlanVersionRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.services.WorkflowPlanVersionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

/**
 * Integration test pinning the {@code REQUIRES_NEW} propagation contract of
 * {@link WorkflowPlanVersionService#createVersionInNewTransaction} against a REAL
 * Spring transaction manager - the property mock-based unit tests cannot prove.
 *
 * <p>The contract (run/version-parity invariant, availability-over-strictness degrade):
 * callers like {@code WorkflowResumeService.stampPlanVersion} invoke the versioning
 * write from inside their own {@code @Transactional} method and CATCH failures to
 * degrade gracefully (WARN + keep legacy version stamp). With default {@code REQUIRED}
 * propagation, the inner failure crossing the {@code WorkflowPlanVersionService} proxy
 * would mark the SHARED transaction rollback-only - the caller's catch becomes useless
 * and the outer commit explodes with {@code UnexpectedRollbackException}, failing the
 * whole trigger-fire request. {@code REQUIRES_NEW} isolates the failure: the inner
 * transaction rolls back alone, the outer work commits.
 */
@SpringBootTest(classes = PlanVersionRequiresNewPropagationIntegrationTest.TestApp.class)
@ActiveProfiles("integration-test")
@DirtiesContext
@DisplayName("WorkflowPlanVersionService.createVersionInNewTransaction - REQUIRES_NEW propagation contract")
class PlanVersionRequiresNewPropagationIntegrationTest {

    /**
     * Minimal Spring Boot app: JPA + the version service wired as a plain
     * {@code @Bean} (annotation-driven transactions proxy @Bean instances too).
     * {@code StorageBreakdownService} is mocked to inject a controllable failure
     * INSIDE {@code createVersion} (thrown after the version row save, so the
     * inner transaction has pending work to roll back).
     */
    @org.springframework.context.annotation.Configuration
    @org.springframework.boot.autoconfigure.EnableAutoConfiguration
    @EntityScan(basePackageClasses = WorkflowPlanVersionEntity.class)
    @EnableJpaRepositories(basePackageClasses = WorkflowPlanVersionRepository.class)
    static class TestApp {
        @Bean
        WorkflowPlanVersionService planVersionService(WorkflowPlanVersionRepository versionRepository,
                                                      WorkflowRepository workflowRepository,
                                                      StorageBreakdownService breakdownService,
                                                      ObjectMapper objectMapper) {
            return new WorkflowPlanVersionService(versionRepository, workflowRepository,
                    breakdownService, objectMapper);
        }

        @Bean
        OuterTransactionalCaller outerTransactionalCaller(WorkflowPlanVersionService versionService) {
            return new OuterTransactionalCaller(versionService);
        }
    }

    /**
     * Stand-in for {@code WorkflowResumeService.updateRunPlan}: an outer
     * {@code @Transactional} method that calls the versioning write through the
     * Spring proxy, catches failures (the stampPlanVersion degrade), then performs
     * outer work that MUST survive the inner failure.
     */
    static class OuterTransactionalCaller {
        private final WorkflowPlanVersionService versionService;

        OuterTransactionalCaller(WorkflowPlanVersionService versionService) {
            this.versionService = versionService;
        }

        /** Inner fails and is caught; outer write (a version for another workflow) must commit. */
        @Transactional
        public void degradeThenCommitOuterWork(UUID failingWorkflowId, UUID outerWorkflowId,
                                               Map<String, Object> plan, String failingUser, String okUser) {
            try {
                versionService.createVersionInNewTransaction(failingWorkflowId, plan, failingUser, "In-run edit");
            } catch (Exception e) {
                // stampPlanVersion-style degrade: swallow + keep going.
            }
            // Outer work joins THIS transaction (REQUIRED) - must commit normally.
            versionService.createVersion(outerWorkflowId, plan, okUser, null);
        }

        /** Inner succeeds, then the OUTER transaction rolls back - the inner row must survive. */
        @Transactional
        public void innerCommitThenOuterRollback(UUID workflowId, Map<String, Object> plan, String user) {
            versionService.createVersionInNewTransaction(workflowId, plan, user, null);
            throw new RuntimeException("outer transaction rolls back");
        }

        /**
         * Same degrade contract through the execution-time wrapper
         * ({@code resolveContentVersionForExecutionInNewTransaction}) used by
         * stampPlanVersion / resolveVersionForPlan on the non-replay lane.
         */
        @Transactional
        public void degradeThenCommitOuterWorkViaResolve(UUID failingWorkflowId, UUID outerWorkflowId,
                                                         Map<String, Object> plan, String failingUser, String okUser) {
            try {
                versionService.resolveContentVersionForExecutionInNewTransaction(failingWorkflowId, plan, failingUser);
            } catch (Exception e) {
                // stampPlanVersion-style degrade: swallow + keep going.
            }
            versionService.createVersion(outerWorkflowId, plan, okUser, null);
        }
    }

    @Autowired private OuterTransactionalCaller outerCaller;
    @Autowired private WorkflowPlanVersionRepository versionRepository;
    @MockBean private StorageBreakdownService breakdownService;

    @Test
    @DisplayName("Inner versioning failure is caught by the outer tx - outer work commits, no UnexpectedRollbackException")
    void innerFailureDoesNotPoisonOuterTransaction() {
        UUID failingWorkflow = UUID.randomUUID();
        UUID outerWorkflow = UUID.randomUUID();
        // trackSave runs inside createVersion AFTER the version-row save → the inner
        // tx has pending work when it throws. Selective: only the failing user's call.
        doThrow(new RuntimeException("storage rollup down"))
                .when(breakdownService).trackSave(eq("user-fail"), anyString(), anyLong(), any());

        // With REQUIRED propagation this call would throw UnexpectedRollbackException
        // at commit despite the catch - the exact production failure this guards.
        outerCaller.degradeThenCommitOuterWork(failingWorkflow, outerWorkflow,
                planMap(), "user-fail", "user-ok");

        // Inner transaction rolled back alone: no version row for the failing workflow.
        List<WorkflowPlanVersionEntity> failingRows =
                versionRepository.findByWorkflowIdOrderByVersionDesc(failingWorkflow);
        assertThat(failingRows).isEmpty();

        // Outer work committed: the other workflow has its version row.
        List<WorkflowPlanVersionEntity> outerRows =
                versionRepository.findByWorkflowIdOrderByVersionDesc(outerWorkflow);
        assertThat(outerRows).hasSize(1);
        assertThat(outerRows.get(0).getCreatedBy()).isEqualTo("user-ok");
    }

    @Test
    @DisplayName("Execution-time resolve wrapper has the same REQUIRES_NEW isolation - inner failure never poisons the trigger-fire transaction")
    void resolveWrapperFailureDoesNotPoisonOuterTransaction() {
        UUID failingWorkflow = UUID.randomUUID();
        UUID outerWorkflow = UUID.randomUUID();
        // Empty history → the resolve falls into the createVersion seed lane, where
        // trackSave (thrown AFTER the row save) leaves pending inner work to unwind.
        doThrow(new RuntimeException("storage rollup down"))
                .when(breakdownService).trackSave(eq("user-fail"), anyString(), anyLong(), any());

        outerCaller.degradeThenCommitOuterWorkViaResolve(failingWorkflow, outerWorkflow,
                planMap(), "user-fail", "user-ok");

        assertThat(versionRepository.findByWorkflowIdOrderByVersionDesc(failingWorkflow)).isEmpty();
        List<WorkflowPlanVersionEntity> outerRows =
                versionRepository.findByWorkflowIdOrderByVersionDesc(outerWorkflow);
        assertThat(outerRows).hasSize(1);
        assertThat(outerRows.get(0).getCreatedBy()).isEqualTo("user-ok");
    }

    @Test
    @DisplayName("Inner version row survives an outer rollback - REQUIRES_NEW commits independently")
    void innerCommitSurvivesOuterRollback() {
        UUID workflowId = UUID.randomUUID();

        assertThatThrownBy(() ->
                outerCaller.innerCommitThenOuterRollback(workflowId, planMap(), "user-1"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("outer transaction rolls back");

        // With REQUIRED the row would have been unwound with the outer rollback.
        // REQUIRES_NEW committed it independently (the documented append-only trade-off).
        List<WorkflowPlanVersionEntity> rows =
                versionRepository.findByWorkflowIdOrderByVersionDesc(workflowId);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getVersion()).isEqualTo(1);
    }

    private Map<String, Object> planMap() {
        Map<String, Object> plan = new HashMap<>();
        plan.put("triggers", List.of(Map.of("type", "webhook", "label", "start")));
        plan.put("mcps", List.of());
        plan.put("edges", List.of());
        return plan;
    }
}
