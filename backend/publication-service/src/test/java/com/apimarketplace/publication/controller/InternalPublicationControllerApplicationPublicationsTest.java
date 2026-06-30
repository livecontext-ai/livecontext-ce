package com.apimarketplace.publication.controller;

import com.apimarketplace.publication.config.OrchestratorInternalClient;
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
 * Unit test for the {@code /application-publications-by-workflow-ids} internal endpoint, which lets
 * the orchestrator applications board surface a publisher's OWN published-as-application workflows
 * (WORKFLOW publications with a showcase interface) next to the acquired ones. The endpoint folds
 * the repository's {@code [[workflowId, publicationId, status, showcaseInterfaceId, showcaseRunId], ...]}
 * rows into a {@code Map<workflowId, {publicationId, status, showcaseInterfaceId?, showcaseRunId?}>}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InternalPublicationController - application-publications-by-workflow-ids")
class InternalPublicationControllerApplicationPublicationsTest {

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
    @DisplayName("folds repository rows into a (workflowId → {publicationId, status, showcase ids}) map")
    void foldsRows() {
        UUID wf = UUID.randomUUID();
        UUID pub = UUID.randomUUID();
        UUID iface = UUID.randomUUID();
        when(publicationRepository.findApplicationPublicationsByWorkflowIds(anyCollection()))
                .thenReturn(List.<Object[]>of(new Object[]{wf, pub,
                        com.apimarketplace.publication.domain.WorkflowPublicationEntity.PublicationStatus.ACTIVE,
                        iface, "run_show_9"}));

        ResponseEntity<Map<String, Map<String, String>>> response =
                controller.findApplicationPublicationsByWorkflowIds(
                        Map.of("workflowIds", List.of(wf.toString())));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        Map<String, Map<String, String>> body = response.getBody();
        assertThat(body).containsKey(wf.toString());
        assertThat(body.get(wf.toString()))
                .containsEntry("publicationId", pub.toString())
                .containsEntry("status", "ACTIVE")
                .containsEntry("showcaseInterfaceId", iface.toString())
                .containsEntry("showcaseRunId", "run_show_9");
    }

    @Test
    @DisplayName("a null showcaseRunId is omitted from the map (no captured showcase run) - the board then keeps the public showcase path")
    void nullShowcaseRunOmitted() {
        UUID wf = UUID.randomUUID();
        UUID pub = UUID.randomUUID();
        UUID iface = UUID.randomUUID();
        when(publicationRepository.findApplicationPublicationsByWorkflowIds(anyCollection()))
                .thenReturn(List.<Object[]>of(new Object[]{wf, pub,
                        com.apimarketplace.publication.domain.WorkflowPublicationEntity.PublicationStatus.ACTIVE,
                        iface, null}));

        ResponseEntity<Map<String, Map<String, String>>> response =
                controller.findApplicationPublicationsByWorkflowIds(
                        Map.of("workflowIds", List.of(wf.toString())));

        Map<String, String> ref = response.getBody().get(wf.toString());
        assertThat(ref).containsEntry("showcaseInterfaceId", iface.toString());
        assertThat(ref).doesNotContainKey("showcaseRunId");
    }

    @Test
    @DisplayName("missing/empty workflowIds short-circuits to an empty map without touching the repository")
    void emptyInputShortCircuits() {
        ResponseEntity<Map<String, Map<String, String>>> response =
                controller.findApplicationPublicationsByWorkflowIds(Map.of());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEmpty();
        verify(publicationRepository, never()).findApplicationPublicationsByWorkflowIds(anyCollection());
    }
}
