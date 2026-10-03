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
    @DisplayName("regression (refusal text): a row refusal gives the reason once, no 'Heaviest' list, and the real table id")
    void tooLargeTableNamesItsIdAndTheFixes() {
        String reason = "Table 'Orders' has 6000 rows (max 5000 rows per published table).";
        when(publicationClient.publishWorkflow(any(), eq(TENANT_ID), any()))
                .thenThrow(new PublicationValidationException(
                        "PUBLICATION_SNAPSHOT_TOO_LARGE",
                        reason + " Delete rows from that table, or stop using it in this workflow, then publish again.",
                        Map.of("reason", reason, "maxTableRows", 5000, "breakdown", List.of(Map.of(
                                "type", "datasource", "id", "9", "name", "Orders", "items", 6000))),
                        null));

        ToolExecutionResult result = publish();

        assertThat(result.errorCode()).isEqualTo(
                com.apimarketplace.agent.tools.ToolErrorCode.INVALID_PARAMETER_VALUE);
        assertThat(result.error()).isEqualTo("Publish refused: " + reason + " Fix: delete rows with "
                + "table(action='delete_rows', table_id=9, where={...}), or remove the node that uses it with "
                + "workflow(action='remove', node='<label>'), then publish again.");
    }

    @Test
    @DisplayName("a size refusal lists the heaviest tables")
    void sizeRefusalListsHeaviest() {
        String reason = "Publication snapshot is 16.0 MB (max 15.0 MB).";
        when(publicationClient.publishWorkflow(any(), eq(TENANT_ID), any()))
                .thenThrow(new PublicationValidationException(
                        "PUBLICATION_SNAPSHOT_TOO_LARGE", reason + " Reduce the content of the heaviest resources listed.",
                        Map.of("reason", reason, "sizeBytes", 16_777_216L, "breakdown", List.of(Map.of(
                                "type", "datasource", "id", "9", "name", "Orders", "items", 4000))),
                        null));

        assertThat(publish().error()).startsWith("Publish refused: " + reason
                + " Heaviest: datasource \"Orders\" (4000 rows). Fix: delete rows from the heaviest table");
    }

    @Test
    @DisplayName("a transient table copy failure is a retryable EXTERNAL_SERVICE_ERROR, not a plan error")
    void tableCopyFailureIsRetryable() {
        when(publicationClient.publishWorkflow(any(), eq(TENANT_ID), any()))
                .thenThrow(new PublicationValidationException(
                        "TABLE_COPY_FAILED", "The rows of table 'Orders' (id 9) could not be read just now, so "
                                + "nothing was published. This is temporary: publish again in a moment.",
                        Map.of("retryable", true), null));

        ToolExecutionResult result = publish();

        assertThat(result.errorCode()).isEqualTo(com.apimarketplace.agent.tools.ToolErrorCode.EXTERNAL_SERVICE_ERROR);
        assertThat(result.error()).startsWith("Publish failed: The rows of table 'Orders' (id 9)");
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
