package com.apimarketplace.orchestrator.integration.repository;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.credential.client.CredentialClient;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowEntity.WorkflowStatus;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.WorkflowManagementService;
import com.apimarketplace.orchestrator.services.WorkflowPinService;
import com.apimarketplace.orchestrator.services.WorkflowPlanVersionService;
import com.apimarketplace.orchestrator.tools.application.ApplicationShowcaseResolver;
import com.apimarketplace.orchestrator.tools.workflow.WorkflowCrudModule;
import com.apimarketplace.orchestrator.tools.workflow.builder.AgentWorkflowFireService;
import com.apimarketplace.publication.client.PublicationClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * End-to-end (DB-backed) integration test for the {@code workflow(action='publish')} application
 * auto-promotion. Unlike the Mockito unit tests, this drives {@link WorkflowCrudModule#execute}
 * against the REAL repositories (H2): the workflow plan is loaded + parsed from a persisted
 * {@link WorkflowEntity}, and the showcase run is resolved via the real
 * {@code findByWorkflowIdOrderByStartedAtDescPageable} query over a persisted
 * {@link WorkflowRunEntity}. Only the outbound {@link PublicationClient} (an HTTP hop to
 * publication-service) is mocked so the request it would send can be inspected.
 *
 * <p>Pins the prod-reported bug: an interface-bearing workflow published without an explicit
 * interface_id used to ship a showcase-less WORKFLOW listing (no showcaseInterfaceId ->
 * {@code isApplication()=false} -> blank app preview). It must now auto-promote to an application.
 */
@DataJpaIntegrationTest
class WorkflowPublishApplicationAutoPromotionIntegrationTest {

    @Autowired
    private WorkflowRepository workflowRepository;

    @Autowired
    private WorkflowRunRepository runRepository;

    @Autowired
    private TestEntityManager entityManager;

    private static final String TENANT = "tenant-app-promo";
    private static final String USER = "user-app-promo";

    private WorkflowCrudModule module;
    private PublicationClient publicationClient;

    @BeforeEach
    void setUp() {
        publicationClient = mock(PublicationClient.class);
        // Real repositories + real resolver; the unused publish-path collaborators are mocked.
        ApplicationShowcaseResolver resolver = new ApplicationShowcaseResolver(runRepository);
        module = new WorkflowCrudModule(
                mock(WorkflowManagementService.class), runRepository,
                mock(AgentWorkflowFireService.class), mock(WorkflowPlanVersionService.class),
                mock(WorkflowPinService.class), publicationClient,
                mock(CredentialClient.class), workflowRepository, resolver,
                mock(com.apimarketplace.orchestrator.tools.utility.AgentCancellationProbe.class));
    }

    private WorkflowEntity persistWorkflow(String name, Map<String, Object> plan) {
        WorkflowEntity wf = new WorkflowEntity(TENANT, name, USER);
        wf.setId(UUID.randomUUID());
        wf.setStatus(WorkflowStatus.ACTIVE);
        wf.setIsActive(true);
        wf.setOrganizationId(TENANT); // V263 OrgScopedEntity NOT NULL
        wf.setPlan(plan);
        entityManager.persist(wf);
        entityManager.flush();
        return wf;
    }

    private void persistShowcaseableRun(WorkflowEntity workflow, String runIdPublic) {
        WorkflowRunEntity run = new WorkflowRunEntity(
                workflow, workflow.getTenantId(), runIdPublic, Map.of(), null, USER);
        run.setStatus(RunStatus.COMPLETED); // automatic (default), not a showcase clone -> showcaseable
        run.setOrganizationId(workflow.getOrganizationId());
        entityManager.persist(run);
        entityManager.flush();
    }

    private static Map<String, Object> interfacePlan(String interfaceId, String label, boolean entry) {
        return Map.of("interfaces", List.of(Map.of(
                "id", interfaceId, "label", label, "isEntryInterface", entry)));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> capturePublishRequest(Map<String, Object> params) {
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        when(publicationClient.publishWorkflow(captor.capture(), eq(TENANT), any()))
                .thenReturn(Map.<String, Object>of("id", "pub-it", "status", "ACTIVE"));
        Optional<ToolExecutionResult> result = module.execute("publish", params, TENANT, null);
        assertThat(result).isPresent();
        assertThat(result.get().success()).isTrue();
        entityManager.clear();
        return captor.getValue();
    }

    @Test
    @DisplayName("Interface-bearing workflow published without interface_id auto-promotes to an application (real plan parse + real showcase-run query)")
    void publishAutoPromotesInterfaceBearingWorkflow() {
        String entryInterfaceId = UUID.randomUUID().toString();
        WorkflowEntity wf = persistWorkflow("AI Phone Caller", Map.of("interfaces", List.of(
                Map.of("id", UUID.randomUUID().toString(), "label", "Call Placed", "isEntryInterface", false),
                Map.of("id", entryInterfaceId, "label", "Transcript", "isEntryInterface", true))));
        persistShowcaseableRun(wf, "run_app_promo_1");

        Map<String, Object> request = capturePublishRequest(
                Map.of("workflow_id", wf.getId().toString(), "title", "AI Phone Caller"));

        assertThat(request.get("showcaseInterfaceId")).isEqualTo(entryInterfaceId);
        assertThat(request.get("displayMode")).isEqualTo("APPLICATION");
        assertThat(request.get("showcaseRunId")).isEqualTo("run_app_promo_1");
    }

    @Test
    @DisplayName("Single-interface workflow with NO flagged entry falls back to that interface")
    void publishFallsBackToFirstInterface() {
        String onlyInterfaceId = UUID.randomUUID().toString();
        WorkflowEntity wf = persistWorkflow("Dashboard App",
                interfacePlan(onlyInterfaceId, "Dashboard", false));
        persistShowcaseableRun(wf, "run_app_promo_2");

        Map<String, Object> request = capturePublishRequest(
                Map.of("workflow_id", wf.getId().toString(), "title", "Dashboard App"));

        assertThat(request.get("showcaseInterfaceId")).isEqualTo(onlyInterfaceId);
        assertThat(request.get("displayMode")).isEqualTo("APPLICATION");
        assertThat(request.get("showcaseRunId")).isEqualTo("run_app_promo_2");
    }

    @Test
    @DisplayName("Workflow with NO interface stays a plain WORKFLOW publication - no showcase, no display_mode, no run query")
    void publishWithoutInterfaceStaysPlainWorkflow() {
        WorkflowEntity wf = persistWorkflow("Nightly ETL", Map.of("interfaces", List.of()));
        persistShowcaseableRun(wf, "run_app_promo_3"); // a run exists, but must NOT be attached

        Map<String, Object> request = capturePublishRequest(
                Map.of("workflow_id", wf.getId().toString(), "title", "Nightly ETL"));

        assertThat(request).doesNotContainKey("showcaseInterfaceId");
        assertThat(request).doesNotContainKey("displayMode");
        assertThat(request).doesNotContainKey("showcaseRunId");
    }
}
