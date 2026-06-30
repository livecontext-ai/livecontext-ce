package com.apimarketplace.publication.controller;

import com.apimarketplace.auth.client.access.OrgAccessGuard;
import com.apimarketplace.publication.config.OrchestratorInternalClient;
import com.apimarketplace.publication.dto.PublicationListItem;
import com.apimarketplace.publication.service.AgentPublicationService;
import com.apimarketplace.publication.service.LandingInterfaceSnapshotter;
import com.apimarketplace.publication.service.OnboardingCategoryMapper;
import com.apimarketplace.publication.service.PublicationListQueryService;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for the acquired-application LIST enrichment after the publisher removes the source
 * from the marketplace.
 *
 * <p>Bug: {@code buildAcquiredItems} (and {@code getPurchases}) enriched each acquired item via
 * {@link PublicationListQueryService#findByIds} which filters {@code status='ACTIVE'}. Once the
 * publisher unpublishes (INACTIVE) or deletes (soft delete = INACTIVE) the source, that lookup
 * returned nothing, so the builder fell into the {@code pub == null} branch and synthesized a
 * minimal {@code remote=true} stand-in WITHOUT the showcase fields. On the frontend
 * {@code ApplicationCard} that made {@code canPreview=false} (no {@code showcaseInterfaceId})
 * and {@code authenticated=false} (remote=true), dropping the installed-app card onto the
 * node-icon cover tile and bypassing the receipt-gated authenticated showcase-render that
 * already serves acquirers. The fix resolves the real row regardless of status via
 * {@link PublicationListQueryService#findByIdsIncludingInactive}; the synth branch is then
 * reserved for genuinely cloud-sourced acquisitions absent from the local catalog.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowPublicationController - acquired list keeps the real publication after removal")
class WorkflowPublicationControllerAcquiredListInactiveTest {

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

    private static final String ACQUIRER_ID = "acquirer-002";
    private static final UUID PUBLICATION_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CLONED_WORKFLOW_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID SHOWCASE_INTERFACE_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @BeforeEach
    void setUp() {
        controller = new WorkflowPublicationController(
                publicationService, agentPublicationService, listQueryService,
                reviewService, resourcePublicationService, orchestratorClient,
                landingInterfaceSnapshotter, showcaseSnapshotReader, fileRefRewriter,
                new OnboardingCategoryMapper(), orgAccessGuard);
    }

    private Map<String, Object> acquiredWorkflow() {
        return Map.of(
                "id", CLONED_WORKFLOW_ID.toString(),
                "sourcePublicationId", PUBLICATION_ID.toString(),
                "title", "Installed App",
                "acquiredAt", "2026-06-25T08:00:00Z");
    }

    /** A lightweight list item for an INACTIVE (unpublished/deleted) acquired publication. */
    private PublicationListItem inactiveListItem() {
        return new PublicationListItem(
                PUBLICATION_ID, "WORKFLOW", null, null, "Installed App", "A downloader app",
                SHOWCASE_INTERFACE_ID, "showcase_run_1", "APPLICATION", 0,
                "publisher-001", "Publisher One", null, null,
                "INACTIVE", "PRIVATE", "USER", "publisher-001",
                0, 0, 1, null, 0, 0, 1, 0, 0, 0.0, 0,
                null, null, null, null, null, null, null, null,
                null, null, null, null, null);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> firstItemPublication(ResponseEntity<?> response) {
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        List<Map<String, Object>> items = (List<Map<String, Object>>) body.get("items");
        assertThat(items).hasSize(1);
        return (Map<String, Object>) items.get(0).get("publication");
    }

    @Test
    @DisplayName("INACTIVE acquired app: enrichment uses findByIdsIncludingInactive and keeps the REAL publication (showcase fields, not the remote synth)")
    void inactiveAcquiredAppKeepsRealPublication() {
        when(publicationService.getAcquiredWorkflows(eq(ACQUIRER_ID), isNull()))
                .thenReturn(List.of(acquiredWorkflow()));
        when(listQueryService.findByIdsIncludingInactive(List.of(PUBLICATION_ID)))
                .thenReturn(List.of(inactiveListItem()));

        ResponseEntity<?> response = controller.getAcquiredApplicationsPaged(
                ACQUIRER_ID, null, null, null, 0, 25);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        Map<String, Object> pub = firstItemPublication(response);
        // The real INACTIVE row flows through: the card can preview (showcaseInterfaceId present)
        // and routes to the AUTHENTICATED showcase-render (no remote=true).
        assertThat(pub).containsEntry("showcaseInterfaceId", SHOWCASE_INTERFACE_ID.toString());
        assertThat(pub).containsEntry("showcaseRunId", "showcase_run_1");
        assertThat(pub).containsEntry("status", "INACTIVE");
        assertThat(pub).containsEntry("publisherName", "Publisher One");
        assertThat(pub.get("remote")).isNull();

        // Pin the call site: the ACTIVE-only lookup must NOT be used for acquired items.
        verify(listQueryService).findByIdsIncludingInactive(List.of(PUBLICATION_ID));
        verify(listQueryService, never()).findByIds(org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    @DisplayName("Cloud-sourced acquisition absent from the local catalog: still falls back to the remote synth stand-in")
    void remoteAcquisitionStillSynthesized() {
        when(publicationService.getAcquiredWorkflows(eq(ACQUIRER_ID), isNull()))
                .thenReturn(List.of(acquiredWorkflow()));
        // Source publication not present locally (a genuinely remote/cloud acquisition).
        when(listQueryService.findByIdsIncludingInactive(List.of(PUBLICATION_ID)))
                .thenReturn(List.of());

        ResponseEntity<?> response = controller.getAcquiredApplicationsPaged(
                ACQUIRER_ID, null, null, null, 0, 25);

        Map<String, Object> pub = firstItemPublication(response);
        assertThat(pub).containsEntry("remote", true);
        assertThat(pub).containsEntry("id", PUBLICATION_ID.toString());
        // The synth carries no showcase interface - the card uses the remote by-id proxy path.
        assertThat(pub.get("showcaseInterfaceId")).isNull();
    }

    @Test
    @DisplayName("GET /purchases: an INACTIVE purchased pub resolves the REAL light publication (replaces the service synth), via findByIdsIncludingInactive")
    void purchasesResolvesRealInactivePublication() {
        // The service hands the controller a minimal remote-synth placeholder; once the real
        // (INACTIVE) row is found locally, the controller must replace it with the real publication.
        Map<String, Object> serviceSynth = Map.of("id", PUBLICATION_ID.toString(), "remote", true);
        Map<String, Object> purchase = Map.of(
                "publicationId", PUBLICATION_ID.toString(),
                "publication", serviceSynth);
        when(publicationService.getPurchases(eq(ACQUIRER_ID), isNull())).thenReturn(List.of(purchase));
        when(listQueryService.findByIdsIncludingInactive(List.of(PUBLICATION_ID)))
                .thenReturn(List.of(inactiveListItem()));

        ResponseEntity<?> response = controller.getPurchases(ACQUIRER_ID, null);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> purchases = (List<Map<String, Object>>) body.get("purchases");
        assertThat(purchases).hasSize(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> pub = (Map<String, Object>) purchases.get(0).get("publication");
        // Replaced with the REAL row (not the remote synth): showcase fields present, status INACTIVE.
        assertThat(pub).containsEntry("showcaseInterfaceId", SHOWCASE_INTERFACE_ID.toString());
        assertThat(pub).containsEntry("status", "INACTIVE");
        assertThat(pub.get("remote")).isNull();

        verify(listQueryService).findByIdsIncludingInactive(List.of(PUBLICATION_ID));
        verify(listQueryService, never()).findByIds(org.mockito.ArgumentMatchers.anyList());
    }
}
