package com.apimarketplace.orchestrator.tools.workflow;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.WorkflowManagementService;
import com.apimarketplace.orchestrator.services.WorkflowPinService;
import com.apimarketplace.orchestrator.services.WorkflowPlanVersionService;
import com.apimarketplace.orchestrator.tools.application.ApplicationShowcaseResolver;
import com.apimarketplace.orchestrator.tools.workflow.builder.AgentWorkflowFireService;
import com.apimarketplace.publication.client.PublicationClient;
import com.apimarketplace.publication.client.PublicationValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.LinkedHashMap;
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
 * The MCP {@code workflow(action='publish')} tool must report a structured publish
 * refusal as a FIXABLE input problem: the plan references a custom API, which the agent
 * can remove. Before the typed mapping it landed in the generic RuntimeException branch
 * and came back as {@code EXECUTION_FAILED} "Failed to publish workflow: 422 ...", which
 * reads as a platform outage and invites a blind retry.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("workflow(action='publish') - custom-API refusal is an input error, not a platform failure")
class WorkflowCrudModuleCustomApiRefusalTest {

    private static final String TENANT_ID = "tenant-1";
    private static final String MESSAGE =
            "Custom APIs cannot be shared. This publication uses My Private API, which exists "
            + "only in your own account, so anyone installing it would get nodes that cannot run.";

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
                credentialClient, workflowRepository,
                new ApplicationShowcaseResolver(workflowRunRepository),
                cancellationProbe,
                mock(com.apimarketplace.orchestrator.tools.common.RunStopToolHandler.class),
                mock(com.apimarketplace.orchestrator.services.resume.StepRerunService.class),
                mock(com.apimarketplace.orchestrator.services.resume.AutoRestartExecutionService.class));
        // No plan on disk: publish falls back to a plain workflow publish (no showcase
        // auto-promotion), which is all this test needs to reach the client call.
        when(workflowRepository.findById(any(UUID.class))).thenReturn(Optional.empty());
    }

    private ToolExecutionResult publish() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("workflow_id", UUID.randomUUID().toString());
        params.put("title", "My App");
        Optional<ToolExecutionResult> result = module.execute("publish", params, TENANT_ID, null);
        assertThat(result).isPresent();
        return result.get();
    }

    @Test
    @DisplayName("the refusal names the custom APIs, the nodes carrying them, and both fixes")
    void customApiRefusalIsAnInputError() {
        when(publicationClient.publishWorkflow(any(), eq(TENANT_ID), any()))
                .thenThrow(new PublicationValidationException(
                        "CUSTOM_API_NOT_PUBLISHABLE", MESSAGE,
                        Map.of("customApis", List.of(Map.of(
                                "apiSlug", "my-private-api", "apiName", "My Private API",
                                "toolIdentifiers", List.of("my-private-api/do-thing")))),
                        null));

        ToolExecutionResult result = publish();

        assertThat(result.success()).isFalse();
        assertThat(result.error()).startsWith("Publish refused: ");
        assertThat(result.error())
                .contains("My Private API")
                .contains("my-private-api/do-thing")
                // The fix has to name actions that can actually do the job: `modify` edits a
                // node's params and cannot re-point its catalog tool, so the guidance sends the
                // agent to remove + add_node (or to a private publish) instead.
                .contains("workflow(action='remove'")
                .contains("add_node")
                .contains("visibility='PRIVATE'");
        assertThat(result.errorCode()).isEqualTo(
                com.apimarketplace.agent.tools.ToolErrorCode.INVALID_PARAMETER_VALUE);
    }

    @Test
    @DisplayName("a nameless API entry falls back to its slug rather than printing null")
    void namelessApiFallsBackToSlug() {
        when(publicationClient.publishWorkflow(any(), eq(TENANT_ID), any()))
                .thenThrow(new PublicationValidationException(
                        "CUSTOM_API_NOT_PUBLISHABLE", MESSAGE,
                        Map.of("customApis", List.of(Map.of("apiSlug", "my-private-api"))),
                        null));

        ToolExecutionResult result = publish();

        assertThat(result.error()).contains("my-private-api");
        assertThat(result.error()).doesNotContain("null");
    }

    @Test
    @DisplayName("another structured refusal code relays its sentence without the custom-API guidance")
    void unknownRefusalCodeKeepsTheSentenceOnly() {
        when(publicationClient.publishWorkflow(any(), eq(TENANT_ID), any()))
                .thenThrow(new PublicationValidationException(
                        "SOME_FUTURE_CODE", "Something else is wrong.", Map.of(), null));

        ToolExecutionResult result = publish();

        assertThat(result.error()).isEqualTo("Publish refused: Something else is wrong.");
        assertThat(result.errorCode()).isEqualTo(
                com.apimarketplace.agent.tools.ToolErrorCode.INVALID_PARAMETER_VALUE);
    }

    @Test
    @DisplayName("an unrelated runtime failure still reports EXECUTION_FAILED (the mapping is not a catch-all)")
    void otherFailuresStayExecutionFailures() {
        when(publicationClient.publishWorkflow(any(), eq(TENANT_ID), any()))
                .thenThrow(new RuntimeException("publication-service unreachable"));

        ToolExecutionResult result = publish();

        assertThat(result.success()).isFalse();
        assertThat(result.error()).startsWith("Failed to publish workflow: ");
        assertThat(result.errorCode()).isEqualTo(
                com.apimarketplace.agent.tools.ToolErrorCode.EXECUTION_FAILED);
    }
}
