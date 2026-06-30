package com.apimarketplace.publication.controller;

import com.apimarketplace.publication.config.OrchestratorInternalClient;
import com.apimarketplace.publication.repository.WorkflowPublicationRepository;
import com.apimarketplace.publication.service.AgentPublicationService;
import com.apimarketplace.publication.service.PublicationPendingReviewException;
import com.apimarketplace.publication.service.ResourcePublicationService;
import com.apimarketplace.publication.service.ShowcaseSnapshotBackfillService;
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
 * Sibling of {@link WorkflowPublicationControllerPendingReviewConflictTest} for
 * the INTERNAL (server-to-server) controller. The agent's
 * {@code application(action='create')} re-publish path and CE↔cloud S2S publishes
 * reach these endpoints, and they hit the same PENDING_REVIEW guard. Before the
 * fix they returned HTTP 500 (publish-agent had no generic catch at all, so the
 * IllegalStateException escaped as a Spring default 500); they now return 409
 * CONFLICT, honoring the contract documented on PublisherProfileUnavailableException.
 *
 * <p>Each test fails on the pre-fix controller (500) and passes post-fix (409).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InternalPublicationController - PENDING_REVIEW guard returns 409 CONFLICT")
class InternalPublicationControllerPendingReviewConflictTest {

    @Mock private WorkflowPublicationRepository publicationRepository;
    @Mock private WorkflowPublicationService publicationService;
    @Mock private AgentPublicationService agentPublicationService;
    @Mock private ResourcePublicationService resourcePublicationService;
    @Mock private OrchestratorInternalClient orchestratorClient;
    @Mock private ShowcaseSnapshotBackfillService backfillService;

    private InternalPublicationController controller;

    private static final String TENANT = "103";
    private static final String PENDING = "Cannot re-publish while publication is pending review. Please wait for admin approval.";

    @BeforeEach
    void setUp() {
        controller = new InternalPublicationController(
                publicationRepository, publicationService, agentPublicationService,
                resourcePublicationService, orchestratorClient, backfillService,
                org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class));
    }

    @Test
    @DisplayName("publishWorkflow (/publish) re-publish while pending review → 409 (was 500)")
    void publishWorkflowPendingReviewReturnsConflict() {
        when(publicationService.publishWorkflow(
                any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), anyBoolean(), any()))
                .thenThrow(new PublicationPendingReviewException(PENDING));

        ResponseEntity<?> response = controller.publishWorkflow(
                Map.of("workflowId", UUID.randomUUID().toString()), TENANT, null);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(asErrorBody(response)).containsEntry("error", PENDING);
    }

    @Test
    @DisplayName("publishAgent (/publish-agent) re-publish while pending review → 409 (was an unmapped 500)")
    void publishAgentPendingReviewReturnsConflict() {
        when(agentPublicationService.publishAgent(any(), any(), any()))
                .thenThrow(new PublicationPendingReviewException(PENDING));

        ResponseEntity<?> response = controller.publishAgent(
                TENANT, null, Map.of("agentConfigId", UUID.randomUUID().toString()));

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(asErrorBody(response)).containsEntry("error", PENDING);
    }

    @Test
    @DisplayName("unpublishByAgentConfigId (/unpublish-agent) while pending review → 409 (was 500)")
    void unpublishAgentPendingReviewReturnsConflict() {
        UUID agentConfigId = UUID.randomUUID();
        doThrow(new PublicationPendingReviewException("Cannot unpublish while publication is pending review."))
                .when(agentPublicationService).unpublishAgent(agentConfigId, TENANT, null);

        ResponseEntity<?> response = controller.unpublishByAgentConfigId(TENANT, null, agentConfigId);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
    }

    @Test
    @DisplayName("publishWorkflow with a non-pending IllegalStateException (transient fault) stays 500, not 409")
    void publishWorkflowTransientIllegalStateStays500() {
        when(publicationService.publishWorkflow(
                any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), anyBoolean(), any()))
                .thenThrow(new IllegalStateException("Showcase snapshot capture failed"));

        ResponseEntity<?> response = controller.publishWorkflow(
                Map.of("workflowId", UUID.randomUUID().toString()), TENANT, null);

        assertThat(response.getStatusCode().value()).isEqualTo(500);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asErrorBody(ResponseEntity<?> response) {
        return (Map<String, Object>) response.getBody();
    }
}
