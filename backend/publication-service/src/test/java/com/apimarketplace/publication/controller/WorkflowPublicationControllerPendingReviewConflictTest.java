package com.apimarketplace.publication.controller;

import com.apimarketplace.auth.client.access.OrgAccessGuard;
import com.apimarketplace.publication.config.OrchestratorInternalClient;
import com.apimarketplace.publication.service.AgentPublicationService;
import com.apimarketplace.publication.service.LandingInterfaceSnapshotter;
import com.apimarketplace.publication.service.OnboardingCategoryMapper;
import com.apimarketplace.publication.service.PublicationListQueryService;
import com.apimarketplace.publication.service.PublicationPendingReviewException;
import com.apimarketplace.publication.service.PublicationReviewService;
import com.apimarketplace.publication.service.ResourcePublicationService;
import com.apimarketplace.publication.service.ShowcaseFileRefRewriter;
import com.apimarketplace.publication.service.ShowcaseSnapshotReader;
import com.apimarketplace.publication.service.WorkflowPublicationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * Regression guard for the bug class where the publish/unpublish handlers for
 * workflows and agents let the PENDING_REVIEW guard's {@link IllegalStateException}
 * fall through to the generic {@code catch (Exception)} branch and returned HTTP
 * 500. A "publication is pending review" rejection is a client-state conflict,
 * not a server fault - every such handler must return 409 CONFLICT (matching the
 * already-correct {@code publish-resource}/{@code unpublish-resource}/delete
 * handlers), so the frontend surfaces the message and fails fast instead of
 * retrying a 5xx three times.
 *
 * <p>Each test fails on the pre-fix controller (status 500) and passes post-fix
 * (status 409). The {@code deletePublication} path is covered by
 * {@link WorkflowPublicationControllerDeleteTest}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowPublicationController - PENDING_REVIEW guard returns 409 CONFLICT")
class WorkflowPublicationControllerPendingReviewConflictTest {

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

    private WorkflowPublicationController controller;

    private static final String TENANT = "103";
    private static final String PUBLISH_PENDING =
            "Cannot re-publish while publication is pending review. Please wait for admin approval.";
    private static final String UNPUBLISH_PENDING =
            "Cannot unpublish while publication is pending review. Please wait for admin approval.";

    @BeforeEach
    void setUp() {
        controller = new WorkflowPublicationController(
                publicationService, agentPublicationService, listQueryService,
                reviewService, resourcePublicationService, orchestratorClient,
                landingInterfaceSnapshotter, showcaseSnapshotReader, fileRefRewriter,
                new OnboardingCategoryMapper(),
                orgAccessGuard);
    }

    @Test
    @DisplayName("publishWorkflow re-publish while pending review → 409 (was 500)")
    void publishWorkflowPendingReviewReturnsConflict() {
        when(publicationService.publishWorkflow(
                any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), anyBoolean(), any()))
                .thenThrow(new PublicationPendingReviewException(PUBLISH_PENDING));

        WorkflowPublicationController.PublishWorkflowRequest request =
                new WorkflowPublicationController.PublishWorkflowRequest();
        request.workflowId = UUID.randomUUID().toString();

        ResponseEntity<?> response = controller.publishWorkflow(TENANT, null, null, request);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(asErrorBody(response)).containsEntry("error", PUBLISH_PENDING);
    }

    @Test
    @DisplayName("unpublishWorkflow while pending review → 409 (was 500)")
    void unpublishWorkflowPendingReviewReturnsConflict() {
        UUID workflowId = UUID.randomUUID();
        when(publicationService.unpublishWorkflow(any(), any(), any()))
                .thenThrow(new PublicationPendingReviewException(UNPUBLISH_PENDING));

        ResponseEntity<?> response =
                controller.unpublishWorkflow(TENANT, null, null, workflowId.toString());

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(asErrorBody(response)).containsEntry("error", UNPUBLISH_PENDING);
    }

    @Test
    @DisplayName("publishAgent re-publish while pending review → 409 (was 500)")
    void publishAgentPendingReviewReturnsConflict() {
        when(agentPublicationService.publishAgent(any(), any(), any()))
                .thenThrow(new PublicationPendingReviewException(PUBLISH_PENDING));

        ResponseEntity<?> response =
                controller.publishAgent(TENANT, null, null, Map.of("agentConfigId", UUID.randomUUID().toString()));

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(asErrorBody(response)).containsEntry("error", PUBLISH_PENDING);
    }

    @Test
    @DisplayName("unpublishAgent while pending review → 409 (was 500)")
    void unpublishAgentPendingReviewReturnsConflict() {
        UUID agentConfigId = UUID.randomUUID();
        doThrow(new PublicationPendingReviewException(UNPUBLISH_PENDING))
                .when(agentPublicationService).unpublishAgent(any(), any(), any());

        ResponseEntity<?> response =
                controller.unpublishAgent(TENANT, null, null, agentConfigId.toString());

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(asErrorBody(response)).containsEntry("error", UNPUBLISH_PENDING);
    }

    @Test
    @DisplayName("publishResource while pending review → 409")
    void publishResourcePendingReviewReturnsConflict() {
        when(resourcePublicationService.publishResource(any(), any(), any()))
                .thenThrow(new PublicationPendingReviewException(PUBLISH_PENDING));

        ResponseEntity<?> response =
                controller.publishResource(TENANT, null, null, Map.of("type", "SKILL", "resourceId", "r1"));

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(asErrorBody(response)).containsEntry("error", PUBLISH_PENDING);
    }

    @Test
    @DisplayName("publishResource with a non-pending IllegalStateException stays 500, not 409")
    void publishResourceTransientIllegalStateStays500() {
        when(resourcePublicationService.publishResource(any(), any(), any()))
                .thenThrow(new IllegalStateException("resource snapshot build failed"));

        ResponseEntity<?> response =
                controller.publishResource(TENANT, null, null, Map.of("type", "SKILL", "resourceId", "r1"));

        assertThat(response.getStatusCode().value()).isEqualTo(500);
    }

    @Test
    @DisplayName("unpublishResource while pending review → 409")
    void unpublishResourcePendingReviewReturnsConflict() {
        doThrow(new PublicationPendingReviewException(UNPUBLISH_PENDING))
                .when(resourcePublicationService).unpublishResource(any(), any(), any(), any());

        ResponseEntity<?> response = controller.unpublishResource(TENANT, null, null, "SKILL", "r1");

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(asErrorBody(response)).containsEntry("error", UNPUBLISH_PENDING);
    }

    @Test
    @DisplayName("unpublishResource with a non-pending IllegalStateException stays 500, not 409")
    void unpublishResourceTransientIllegalStateStays500() {
        doThrow(new IllegalStateException("unexpected resource fault"))
                .when(resourcePublicationService).unpublishResource(any(), any(), any(), any());

        ResponseEntity<?> response = controller.unpublishResource(TENANT, null, null, "SKILL", "r1");

        assertThat(response.getStatusCode().value()).isEqualTo(500);
    }

    @Test
    @DisplayName("publishWorkflow with a non-pending IllegalStateException (transient snapshot fault) stays 500, not 409")
    void publishWorkflowTransientIllegalStateStays500() {
        // A genuine server-side failure (e.g. showcase snapshot capture) throws a
        // plain IllegalStateException, NOT the pending-review subtype. It must stay
        // 5xx so the frontend retries - the 409 mapping is for conflicts only.
        when(publicationService.publishWorkflow(
                any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), anyBoolean(), any()))
                .thenThrow(new IllegalStateException("Showcase snapshot capture failed"));

        WorkflowPublicationController.PublishWorkflowRequest request =
                new WorkflowPublicationController.PublishWorkflowRequest();
        request.workflowId = UUID.randomUUID().toString();

        ResponseEntity<?> response = controller.publishWorkflow(TENANT, null, null, request);

        assertThat(response.getStatusCode().value()).isEqualTo(500);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asErrorBody(ResponseEntity<?> response) {
        return (Map<String, Object>) response.getBody();
    }
}
