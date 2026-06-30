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
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for the {@code /publication-visibilities-by-workflow-ids} internal endpoint, which lets
 * the orchestrator workflow + applications boards mark each own card with a public / private
 * indicator and offer a visibility filter. The endpoint folds the repository's
 * {@code [[workflowId, visibility], ...]} rows into a {@code Map<workflowId, visibility>}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InternalPublicationController - publication-visibilities-by-workflow-ids")
class InternalPublicationControllerVisibilitiesTest {

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

    @Test
    @DisplayName("folds repository rows into a (workflowId → visibility) map")
    void foldsRows() {
        UUID publicWf = UUID.randomUUID();
        UUID privateWf = UUID.randomUUID();
        when(publicationRepository.findPublicationVisibilitiesByWorkflowIds(anyCollection()))
                .thenReturn(List.<Object[]>of(
                        new Object[]{publicWf, WorkflowPublicationEntity.PublicationVisibility.PUBLIC},
                        new Object[]{privateWf, WorkflowPublicationEntity.PublicationVisibility.PRIVATE}));

        ResponseEntity<Map<String, String>> response =
                controller.findPublicationVisibilities(
                        Map.of("workflowIds", List.of(publicWf.toString(), privateWf.toString())));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        Map<String, String> body = response.getBody();
        assertThat(body)
                .containsEntry(publicWf.toString(), "PUBLIC")
                .containsEntry(privateWf.toString(), "PRIVATE");
    }

    @Test
    @DisplayName("missing/empty workflowIds short-circuits to an empty map without touching the repository")
    void emptyInputShortCircuits() {
        ResponseEntity<Map<String, String>> response =
                controller.findPublicationVisibilities(Map.of());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEmpty();
        verify(publicationRepository, never()).findPublicationVisibilitiesByWorkflowIds(anyCollection());
    }
}
