package com.apimarketplace.publication.controller;

import com.apimarketplace.auth.client.access.OrgAccessGuard;
import com.apimarketplace.publication.config.OrchestratorInternalClient;
import com.apimarketplace.publication.repository.WorkflowPublicationRepository;
import com.apimarketplace.publication.service.AgentPublicationService;
import com.apimarketplace.publication.service.ApplicationTemplateResetService;
import com.apimarketplace.publication.service.LandingInterfaceSnapshotter;
import com.apimarketplace.publication.service.OnboardingCategoryMapper;
import com.apimarketplace.publication.service.PublicationListQueryService;
import com.apimarketplace.publication.service.PublicationReviewService;
import com.apimarketplace.publication.service.PublicationValidationException;
import com.apimarketplace.publication.service.ResourcePublicationService;
import com.apimarketplace.publication.service.ShowcaseFileNamespaceRepairService;
import com.apimarketplace.publication.service.ShowcaseFileRefRewriter;
import com.apimarketplace.publication.service.ShowcaseSnapshotBackfillService;
import com.apimarketplace.publication.service.ShowcaseSnapshotReader;
import com.apimarketplace.publication.service.WorkflowPublicationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The custom-API publish refusal must reach every caller as a STRUCTURED 422, not an
 * opaque 500: the publish modal names the offending APIs from the body, and the MCP
 * publish tool classifies it as a fixable plan instead of a platform failure.
 *
 * <p>The workflow publish, workflow update and internal publish cases each fail on the
 * pre-change controllers (the refusal fell through to the generic {@code catch (Exception)}
 * branch → 500) and pass after it (422 + body). The {@code publish-agent} case is a
 * PARITY guard: that catch already existed, and this pins that the new error code flows
 * through it with its detail intact.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Custom-API publish refusal is a structured 422")
class PublicationCustomApiRefusal422ContractTest {

    @Mock private WorkflowPublicationService publicationService;
    @Mock private AgentPublicationService agentPublicationService;
    @Mock private PublicationListQueryService listQueryService;
    @Mock private PublicationReviewService reviewService;
    @Mock private ResourcePublicationService resourcePublicationService;
    @Mock private OrchestratorInternalClient orchestratorClient;
    @Mock private LandingInterfaceSnapshotter landingInterfaceSnapshotter;
    @Mock private ShowcaseSnapshotReader showcaseSnapshotReader;
    @Mock private ShowcaseFileRefRewriter fileRefRewriter;
    @Mock private OrgAccessGuard orgAccessGuard;
    @Mock private WorkflowPublicationRepository publicationRepository;
    @Mock private ShowcaseSnapshotBackfillService backfillService;

    private WorkflowPublicationController controller;
    private InternalPublicationController internalController;

    private static final String TENANT = "103";
    private static final String MESSAGE =
            "Custom APIs cannot be shared. This publication uses My Private API, which exists "
            + "only in your own account, so anyone installing it would get nodes that cannot run. "
            + "Replace those nodes with catalog integrations, or keep the publication private.";
    private static final List<Map<String, Object>> CUSTOM_APIS = List.of(Map.of(
            "apiSlug", "my-private-api",
            "apiName", "My Private API",
            "toolIdentifiers", List.of("my-private-api/do-thing")));

    private static PublicationValidationException refusal() {
        return new PublicationValidationException(
                PublicationValidationException.CUSTOM_API_NOT_PUBLISHABLE,
                MESSAGE,
                Map.of("customApis", CUSTOM_APIS));
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        controller = new WorkflowPublicationController(
                publicationService, agentPublicationService, listQueryService,
                reviewService, resourcePublicationService, orchestratorClient,
                landingInterfaceSnapshotter, showcaseSnapshotReader, fileRefRewriter,
                new OnboardingCategoryMapper(), orgAccessGuard,
                mock(ApplicationTemplateResetService.class));
        internalController = new InternalPublicationController(
                publicationRepository, publicationService, agentPublicationService,
                resourcePublicationService, orchestratorClient, backfillService,
                mock(ShowcaseFileNamespaceRepairService.class),
                mock(ObjectProvider.class));
    }

    private void stubPublishRefusal() {
        when(publicationService.publishWorkflow(
                any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), anyBoolean(), any(), any()))
                .thenThrow(refusal());
    }

    @Test
    @DisplayName("POST /publications → 422 carrying the error code, message and offending APIs")
    void publishWorkflowReturns422WithDetails() {
        stubPublishRefusal();
        WorkflowPublicationController.PublishWorkflowRequest request =
                new WorkflowPublicationController.PublishWorkflowRequest();
        request.workflowId = UUID.randomUUID().toString();

        ResponseEntity<?> response = controller.publishWorkflow(TENANT, null, null, request);

        assertThat(response.getStatusCode().value()).isEqualTo(422);
        Map<String, Object> body = asBody(response);
        assertThat(body).containsEntry("error", PublicationValidationException.CUSTOM_API_NOT_PUBLISHABLE);
        assertThat(body).containsEntry("message", MESSAGE);
        assertThat(body).containsEntry("customApis", CUSTOM_APIS);
    }

    @Test
    @DisplayName("PUT /publications/{id} (re-share) → 422 with the same structured body")
    void updatePublicationReturns422WithDetails() {
        when(publicationService.updatePublicationInfo(
                any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), anyBoolean(), anyBoolean(), any(), any()))
                .thenThrow(refusal());
        WorkflowPublicationController.UpdatePublicationRequest request =
                new WorkflowPublicationController.UpdatePublicationRequest();
        request.title = "Title";

        ResponseEntity<?> response =
                controller.updatePublication(TENANT, null, null, UUID.randomUUID().toString(), request);

        assertThat(response.getStatusCode().value()).isEqualTo(422);
        Map<String, Object> body = asBody(response);
        assertThat(body).containsEntry("error", PublicationValidationException.CUSTOM_API_NOT_PUBLISHABLE);
        assertThat(body).containsEntry("customApis", CUSTOM_APIS);
    }

    @Test
    @DisplayName("internal POST /publish (agent MCP path) → 422, not a platform 500")
    void internalPublishWorkflowReturns422() {
        stubPublishRefusal();

        ResponseEntity<?> response = internalController.publishWorkflow(
                Map.of("workflowId", UUID.randomUUID().toString()), TENANT, null);

        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(asBody(response))
                .containsEntry("error", PublicationValidationException.CUSTOM_API_NOT_PUBLISHABLE);
    }

    @Test
    @DisplayName("POST /publish-agent → 422 with the offending APIs (agent surface parity)")
    void publishAgentReturns422() {
        when(agentPublicationService.publishAgent(any(), any(), any())).thenThrow(refusal());

        ResponseEntity<?> response = controller.publishAgent(
                TENANT, null, null, Map.of("agentConfigId", UUID.randomUUID().toString()));

        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(asBody(response)).containsEntry("customApis", CUSTOM_APIS);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asBody(ResponseEntity<?> response) {
        return (Map<String, Object>) response.getBody();
    }
}
