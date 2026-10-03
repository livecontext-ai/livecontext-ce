package com.apimarketplace.orchestrator.tools.workflow;

import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.common.classification.DataSensitivity;
import com.apimarketplace.common.classification.RestrictedDataPolicy;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.WorkflowManagementService;
import com.apimarketplace.orchestrator.services.WorkflowPinService;
import com.apimarketplace.orchestrator.services.WorkflowPlanVersionService;
import com.apimarketplace.orchestrator.tools.application.ApplicationShowcaseResolver;
import com.apimarketplace.orchestrator.tools.workflow.builder.AgentWorkflowFireService;
import com.apimarketplace.publication.client.PublicationClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * CASA LC-066: {@code workflow(action='publish')} copies the plan, its interfaces and the rows of
 * its tables into a marketplace snapshot that no restricted tag follows. From an execution that
 * read Gmail / Drive it is refused, like {@code skill(publish)} and {@code agent(publish)};
 * unpublishing is not.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("workflow(action='publish') from a restricted execution (LC-066)")
class WorkflowCrudModulePublishRestrictedTest {

    private static final String TENANT_ID = "tenant-1";

    @Mock WorkflowManagementService workflowService;
    @Mock WorkflowRunRepository workflowRunRepository;
    @Mock AgentWorkflowFireService agentWorkflowFireService;
    @Mock WorkflowPlanVersionService planVersionService;
    @Mock WorkflowPinService pinService;
    @Mock PublicationClient publicationClient;
    @Mock com.apimarketplace.credential.client.CredentialClient credentialClient;
    @Mock com.apimarketplace.orchestrator.repository.WorkflowRepository workflowRepository;
    @Mock com.apimarketplace.orchestrator.tools.utility.AgentCancellationProbe cancellationProbe;

    private WorkflowCrudModule module;

    @BeforeEach
    void setUp() {
        module = new WorkflowCrudModule(workflowService, workflowRunRepository,
                agentWorkflowFireService, planVersionService, pinService, publicationClient,
                credentialClient, workflowRepository, new ApplicationShowcaseResolver(workflowRunRepository),
                cancellationProbe,
                mock(com.apimarketplace.orchestrator.tools.common.RunStopToolHandler.class),
                mock(com.apimarketplace.orchestrator.services.resume.StepRerunService.class),
                mock(com.apimarketplace.orchestrator.services.resume.AutoRestartExecutionService.class));
        // No plan on disk: publish is a plain workflow publish, enough to reach the client call.
        when(workflowRepository.findById(any(UUID.class))).thenReturn(Optional.empty());
    }

    private static ToolExecutionContext ctx(Map<String, Object> credentials) {
        return new ToolExecutionContext(TENANT_ID, credentials, Map.of(), Set.of(), null, null, null, null);
    }

    private static Map<String, Object> restricted() {
        return Map.of(DataSensitivity.CREDENTIAL_KEY, DataSensitivity.RESTRICTED.name());
    }

    private ToolExecutionResult run(String action, Map<String, Object> credentials) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("workflow_id", UUID.randomUUID().toString());
        params.put("title", "My App");
        return module.execute(action, params, TENANT_ID, ctx(credentials)).orElseThrow();
    }

    @Test
    @DisplayName("regression: a restricted execution's publish is refused before publication-service")
    void restrictedPublishIsRefused() {
        ToolExecutionResult result = run("publish", restricted());

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo(ToolErrorCode.EXECUTION_FAILED);
        assertThat(result.error())
                .startsWith(RestrictedDataPolicy.REFUSAL_CODE)
                .contains("no workflow can be published");
        verifyNoInteractions(publicationClient);
    }

    @Test
    @DisplayName("an ordinary execution still publishes (no over-refusal)")
    void ordinaryPublishReachesPublicationService() {
        when(publicationClient.publishWorkflow(any(), eq(TENANT_ID), any()))
                .thenReturn(Map.of("id", UUID.randomUUID().toString()));

        run("publish", Map.of());

        verify(publicationClient).publishWorkflow(any(), eq(TENANT_ID), any());
    }

    @Test
    @DisplayName("unpublish still works from a restricted execution")
    void restrictedUnpublishStillWorks() {
        when(publicationClient.isWorkflowPublished(any(UUID.class), eq(TENANT_ID))).thenReturn(true);

        ToolExecutionResult result = run("unpublish", restricted());

        assertThat(result.success()).isTrue();
        verify(publicationClient).unpublishByWorkflowId(any(UUID.class), eq(TENANT_ID));
    }
}
