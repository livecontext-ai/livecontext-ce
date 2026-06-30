package com.apimarketplace.publication.controller;

import com.apimarketplace.publication.config.OrchestratorInternalClient;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.PublicationType;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for the {@code /resource-publication-statuses} internal endpoint, the batch sibling of
 * {@code is-resource-published/{type}/{id}}: it lets an owning service (datasource / interface / skill)
 * enrich a whole list page's publication badge in ONE call. The endpoint parses {@code type}, delegates
 * to {@link ResourcePublicationService#getResourcePublicationStatuses}, and fails open (empty map) on a
 * missing/empty {@code resourceIds} or an unknown {@code type} so a caller's list never breaks.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InternalPublicationController - resource-publication-statuses")
class InternalPublicationControllerResourceStatusesTest {

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
    @DisplayName("parses the type and returns the service's batched (resourceId → {status,...}) map")
    void delegatesToService() {
        when(resourcePublicationService.getResourcePublicationStatuses(eq(PublicationType.TABLE), anyCollection()))
                .thenReturn(Map.of(
                        "10", Map.of("status", "ACTIVE"),
                        "11", Map.of("status", "REJECTED", "rejectionReason", "off-topic")));

        ResponseEntity<Map<String, Map<String, String>>> response =
                controller.findResourcePublicationStatuses(
                        Map.of("type", "TABLE", "resourceIds", List.of("10", "11")));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        Map<String, Map<String, String>> body = response.getBody();
        assertThat(body.get("10")).containsEntry("status", "ACTIVE");
        assertThat(body.get("11")).containsEntry("status", "REJECTED").containsEntry("rejectionReason", "off-topic");
    }

    @Test
    @DisplayName("a lower-case type is accepted (case-insensitive parse)")
    void parsesTypeCaseInsensitively() {
        when(resourcePublicationService.getResourcePublicationStatuses(eq(PublicationType.INTERFACE), anyCollection()))
                .thenReturn(Map.of("a", Map.of("status", "ACTIVE")));

        ResponseEntity<Map<String, Map<String, String>>> response =
                controller.findResourcePublicationStatuses(
                        Map.of("type", "interface", "resourceIds", List.of("a")));

        assertThat(response.getBody()).containsKey("a");
    }

    @Test
    @DisplayName("an unknown type fails open to an empty map without touching the service")
    void unknownTypeFailsOpen() {
        ResponseEntity<Map<String, Map<String, String>>> response =
                controller.findResourcePublicationStatuses(
                        Map.of("type", "BOGUS", "resourceIds", List.of("10")));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEmpty();
        verify(resourcePublicationService, never()).getResourcePublicationStatuses(any(), anyCollection());
    }

    @Test
    @DisplayName("missing/empty resourceIds short-circuits to an empty map without touching the service")
    void emptyIdsShortCircuits() {
        ResponseEntity<Map<String, Map<String, String>>> empty =
                controller.findResourcePublicationStatuses(Map.of("type", "TABLE", "resourceIds", List.of()));
        assertThat(empty.getBody()).isEmpty();

        ResponseEntity<Map<String, Map<String, String>>> missing =
                controller.findResourcePublicationStatuses(Map.of("type", "TABLE"));
        assertThat(missing.getBody()).isEmpty();

        verify(resourcePublicationService, never()).getResourcePublicationStatuses(any(), anyCollection());
    }
}
