package com.apimarketplace.orchestrator.integration.repository;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowEntity.WorkflowStatus;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.WorkflowPlanVersionService;
import com.apimarketplace.orchestrator.tools.application.ApplicationShowcaseResolver;
import com.apimarketplace.orchestrator.tools.workflow.WorkflowCrudModule;
import com.apimarketplace.orchestrator.tools.workflow.builder.AgentWorkflowFireService;
import org.hibernate.Hibernate;
import org.hibernate.LazyInitializationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.AdditionalAnswers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Regression for prod "Failed to get run: Could not initialize proxy [WorkflowEntity#...] - no
 * session" on workflow(action='get_run') / get_node_output (fixed by e2c5d9ee02: the report
 * read getPlan() off the run's lazy workflow when the run's plan version had been pruned).
 *
 * <p>The agent-tool path loads the run with {@code findByRunIdPublic} OUTSIDE any session
 * (open-in-view is off), so {@code run.getWorkflow()} is a detached, uninitialized Hibernate
 * proxy. This drives the REAL {@link WorkflowCrudModule} with a run whose workflow is exactly
 * such a proxy (the persistence context is cleared as the repository returns), so any code on
 * those paths that reads a field other than the id fails here the way it failed in prod. The
 * two premise tests pin what that rests on: the id read is safe, any other read throws.
 */
@DataJpaIntegrationTest
class WorkflowRunDetachedWorkflowProxyTest {

    @Autowired
    private WorkflowRunRepository runRepository;

    @Autowired
    private TestEntityManager entityManager;

    private UUID workflowId;
    private static final String RUN = "run-detached";
    private static final String TENANT = "t";

    @BeforeEach
    void persistRun() {
        WorkflowEntity wf = new WorkflowEntity(TENANT, "W", "u");
        wf.setId(UUID.randomUUID());
        wf.setStatus(WorkflowStatus.ACTIVE);
        wf.setIsActive(true);
        wf.setOrganizationId(TENANT);
        wf.setPlan(Map.of("triggers", java.util.List.of()));
        entityManager.persist(wf);
        WorkflowRunEntity run = new WorkflowRunEntity(wf, TENANT, RUN, Map.of(), null, "u");
        run.setStatus(RunStatus.COMPLETED);
        run.setOrganizationId(TENANT);
        run.setPlanVersion(4);
        entityManager.persist(run);
        entityManager.flush();
        entityManager.clear();
        workflowId = wf.getId();
    }

    /** The real repository, except that every run it returns is detached, as on the agent-tool path. */
    private WorkflowRunRepository detachingRepository() {
        WorkflowRunRepository repo = mock(WorkflowRunRepository.class, AdditionalAnswers.delegatesTo(runRepository));
        doAnswer(inv -> {
            Optional<WorkflowRunEntity> found = runRepository.findByRunIdPublic(inv.getArgument(0));
            entityManager.clear();
            return found;
        }).when(repo).findByRunIdPublic(anyString());
        return repo;
    }

    private WorkflowCrudModule module(WorkflowRunRepository repo, WorkflowPlanVersionService versions,
                                      AgentWorkflowFireService fire) {
        return new WorkflowCrudModule(
                mock(com.apimarketplace.orchestrator.services.WorkflowManagementService.class),
                repo, fire, versions,
                mock(com.apimarketplace.orchestrator.services.WorkflowPinService.class),
                mock(com.apimarketplace.publication.client.PublicationClient.class),
                mock(com.apimarketplace.credential.client.CredentialClient.class),
                mock(com.apimarketplace.orchestrator.repository.WorkflowRepository.class),
                new ApplicationShowcaseResolver(repo),
                mock(com.apimarketplace.orchestrator.tools.utility.AgentCancellationProbe.class),
                mock(com.apimarketplace.orchestrator.tools.common.RunStopToolHandler.class),
                mock(com.apimarketplace.orchestrator.services.resume.StepRerunService.class),
                mock(com.apimarketplace.orchestrator.services.resume.AutoRestartExecutionService.class));
    }

    @Test
    @DisplayName("regression: get_run and get_node_output on a detached run read only the workflow id and succeed")
    void runReads_onDetachedRun_readOnlyTheWorkflowId() {
        WorkflowPlanVersionService versions = mock(WorkflowPlanVersionService.class);
        when(versions.resolvePlanForRun(any(), any(), any())).thenReturn(new WorkflowPlanVersionService.RunPlan(null, null));
        AgentWorkflowFireService fire = mock(AgentWorkflowFireService.class);
        when(fire.buildRunMacroReport(any(), any(), any())).thenAnswer(inv -> {
            // The report receives the SAME detached run: prove its workflow is still an
            // uninitialized proxy, i.e. nothing upstream touched a field of it.
            WorkflowRunEntity run = inv.getArgument(0);
            assertThat(Hibernate.isInitialized(run.getWorkflow())).isFalse();
            return Map.of("run_id", RUN);
        });
        when(fire.latestEpochForNode(RUN, "mcp:step")).thenReturn(2);
        when(fire.buildNodeOutputReport(any(), any(), anyInt(), anyString(), any(),
                any(), any(), any(), any(), any(), any())).thenReturn(Map.of("node_id", "mcp:step"));
        WorkflowCrudModule module = module(detachingRepository(), versions, fire);

        // The caller's workspace, as the agent-tool request carries it.
        ToolExecutionResult[] results = new ToolExecutionResult[2];
        com.apimarketplace.common.web.TenantResolver.runWithOrgScope(TENANT, () -> {
            results[0] = module.execute("get_run", Map.of("run_id", RUN), TENANT, null).orElseThrow();
            results[1] = module.execute("get_node_output",
                    Map.of("run_id", RUN, "node_id", "mcp:step"), TENANT, null).orElseThrow();
        });
        ToolExecutionResult getRun = results[0];
        ToolExecutionResult nodeOutput = results[1];

        assertThat(getRun.success()).as(String.valueOf(getRun.error())).isTrue();
        assertThat(nodeOutput.success()).as(String.valueOf(nodeOutput.error())).isTrue();
        verify(versions, times(2)).resolvePlanForRun(eq(workflowId), eq(4), eq(TENANT));
    }

    @Test
    @DisplayName("premise: a detached run's workflow proxy answers getId() without a session and stays uninitialized")
    void detachedProxy_idReadIsSafe() {
        WorkflowEntity proxy = detachingRepository().findByRunIdPublic(RUN).orElseThrow().getWorkflow();

        assertThat(Hibernate.isInitialized(proxy)).isFalse();
        assertThat(proxy.getId()).isEqualTo(workflowId);
        assertThat(Hibernate.isInitialized(proxy)).isFalse();
    }

    @Test
    @DisplayName("premise: any other field of that proxy throws the prod error, which is why run reads must reload by id")
    void detachedProxy_fieldReadThrows() {
        WorkflowEntity proxy = detachingRepository().findByRunIdPublic(RUN).orElseThrow().getWorkflow();

        assertThatThrownBy(proxy::getPlan)
                .isInstanceOf(LazyInitializationException.class)
                .hasMessageContaining("no session");
    }
}
