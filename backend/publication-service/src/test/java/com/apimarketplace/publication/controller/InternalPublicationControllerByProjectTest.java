package com.apimarketplace.publication.controller;

import com.apimarketplace.publication.config.OrchestratorInternalClient;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity;
import com.apimarketplace.publication.repository.WorkflowPublicationRepository;
import com.apimarketplace.publication.service.AgentPublicationService;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit test for the {@code /by-project/{projectId}} internal endpoint that feeds a project's
 * Applications tab. The summary map MUST carry {@code publisherId} (and {@code planVersion}) so the
 * frontend {@code PublisherAvatar} resolves the real avatar via {@code /api/proxy/users/{id}/avatar}
 * instead of falling back to name-initials ("LI"). This pins the regression.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InternalPublicationController - by-project summary map")
class InternalPublicationControllerByProjectTest {

    @Mock private WorkflowPublicationRepository publicationRepository;
    @Mock private WorkflowPublicationService publicationService;
    @Mock private AgentPublicationService agentPublicationService;
    @Mock private ResourcePublicationService resourcePublicationService;
    @Mock private OrchestratorInternalClient orchestratorClient;
    @Mock private ShowcaseSnapshotBackfillService backfillService;

    private InternalPublicationController controller;

    @BeforeEach
    void setUp() {
        controller = new InternalPublicationController(
                publicationRepository, publicationService, agentPublicationService,
                resourcePublicationService, orchestratorClient, backfillService,
                org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class));
    }

    private WorkflowPublicationEntity pub(UUID id, String publisherId, Integer planVersion) {
        // 4-arg legacy ctor sets publisherId; no categoryId so the orchestratorClient
        // category lookup branch is skipped (keeps this a pure controller unit test).
        WorkflowPublicationEntity p = new WorkflowPublicationEntity(
                UUID.randomUUID(), "My App", Map.of(), publisherId);
        p.setId(id);
        p.setPlanVersion(planVersion);
        // A PRIVATE + PENDING_REVIEW app exercises the parity fields: without them the
        // project Applications card shows a misleading public/approved state.
        p.setVisibility(WorkflowPublicationEntity.PublicationVisibility.PRIVATE);
        p.setStatus(WorkflowPublicationEntity.PublicationStatus.PENDING_REVIEW);
        return p;
    }

    @Test
    @DisplayName("by-project map carries publisherId (real-avatar fix), planVersion, visibility & status (card parity)")
    void summaryMapCarriesPublisherIdAndPlanVersion() {
        UUID projectId = UUID.randomUUID();
        UUID pubId = UUID.randomUUID();
        // No userId / no org header → falls through to the plain by-project lookup.
        when(publicationRepository.findByProjectId(projectId))
                .thenReturn(List.of(pub(pubId, "user-42", 7)));

        ResponseEntity<List<Map<String, Object>>> response =
                controller.findByProjectId(projectId, null, null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).hasSize(1);
        Map<String, Object> summary = response.getBody().get(0);
        assertThat(summary)
                .containsEntry("id", pubId.toString())
                .containsEntry("publisherId", "user-42")
                .containsEntry("planVersion", 7)
                .containsEntry("title", "My App")
                // Parity fields - the frontend VisibilityBadge needs `visibility` (else it
                // defaults to the private Lock icon for every app), and the status badge
                // needs `status`.
                .containsEntry("visibility", "PRIVATE")
                .containsEntry("status", "PENDING_REVIEW");
    }
}
