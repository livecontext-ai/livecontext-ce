package com.apimarketplace.publication.controller;

import com.apimarketplace.auth.client.access.OrgAccessGuard;
import com.apimarketplace.publication.config.OrchestratorInternalClient;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.DisplayMode;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.OwnerType;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.PublicationStatus;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.PublicationType;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.PublicationVisibility;
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

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for the AUTHENTICATED showcase-render endpoint
 * {@link WorkflowPublicationController#renderShowcaseAuthenticated}.
 *
 * <p>Bug: the installed-application card preview ({@code ApplicationCard} ->
 * {@code ShowcasePreview}) rendered an acquired app via the anonymous
 * {@code /by-id/.../showcase-render}, which 403s when the publisher unpublishes
 * (INACTIVE) or privatises (PRIVATE) the source publication, so the card fell
 * back to the node-icon cover tile (a "workflow view" with no interface). The
 * authenticated twin lets an OWNER or a receipt-holding ACQUIRER read the frozen
 * showcase even when it is no longer public, while non-acquirers still get 403.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowPublicationController.renderShowcaseAuthenticated - acquirer showcase bypass")
class WorkflowPublicationControllerShowcaseRenderAuthTest {

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

    private static final UUID PUBLICATION_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID WORKFLOW_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID SHOWCASE_INTERFACE_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final String PUBLISHER_ID = "publisher-001";
    private static final String ACQUIRER_ID = "acquirer-002";
    private static final String ACQUIRER_ORG = "org-acquirer";

    @BeforeEach
    void setUp() {
        controller = new WorkflowPublicationController(
                publicationService, agentPublicationService, listQueryService,
                reviewService, resourcePublicationService, orchestratorClient,
                landingInterfaceSnapshotter, showcaseSnapshotReader, fileRefRewriter,
                new OnboardingCategoryMapper(), orgAccessGuard);
    }

    private WorkflowPublicationEntity publication(PublicationStatus status, PublicationVisibility visibility) {
        WorkflowPublicationEntity pub = new WorkflowPublicationEntity();
        pub.setId(PUBLICATION_ID);
        pub.setWorkflowId(WORKFLOW_ID);
        pub.setPublicationType(PublicationType.WORKFLOW);
        pub.setOwnerType(OwnerType.USER);
        pub.setOwnerId(PUBLISHER_ID);
        pub.setPublisherId(PUBLISHER_ID);
        pub.setTitle("Installed App");
        pub.setStatus(status);
        pub.setVisibility(visibility);
        pub.setDisplayMode(DisplayMode.WORKFLOW);
        pub.setCreditsPerUse(0);
        pub.setShowcaseInterfaceId(SHOWCASE_INTERFACE_ID);
        pub.setShowcaseRunId("showcase_run_1");
        return pub;
    }

    /** Stub the snapshot reader so the post-gate render returns a non-empty body. */
    private void stubSnapshotRenders(WorkflowPublicationEntity pub) {
        when(showcaseSnapshotReader.hasSnapshot(pub)).thenReturn(true);
        when(showcaseSnapshotReader.readInterfaceRender(
                eq(pub), eq(SHOWCASE_INTERFACE_ID.toString()), anyInt(), anyInt(), isNull()))
                .thenReturn(Optional.of(Map.of("htmlTemplate", "<div>preview</div>")));
    }

    private ResponseEntity<?> renderAsAcquirer() {
        return controller.renderShowcaseAuthenticated(
                PUBLICATION_ID.toString(), ACQUIRER_ID, ACQUIRER_ORG, null, 0, 1, null);
    }

    @Test
    @DisplayName("acquirer renders the frozen showcase of a now-PRIVATE app they installed - 200")
    void acquirerRendersPrivateShowcase() {
        WorkflowPublicationEntity pub = publication(PublicationStatus.ACTIVE, PublicationVisibility.PRIVATE);
        when(publicationService.getPublicationById(PUBLICATION_ID)).thenReturn(Optional.of(pub));
        when(publicationService.isCallerInOwnerScope(pub, ACQUIRER_ID, ACQUIRER_ORG)).thenReturn(false);
        when(publicationService.callerHoldsReceipt(PUBLICATION_ID, ACQUIRER_ID, ACQUIRER_ORG)).thenReturn(true);
        stubSnapshotRenders(pub);

        ResponseEntity<?> response = renderAsAcquirer();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).containsEntry("htmlTemplate", "<div>preview</div>");
    }

    @Test
    @DisplayName("acquirer renders the showcase of an INACTIVE (unpublished) app they installed - 200")
    void acquirerRendersInactiveShowcase() {
        WorkflowPublicationEntity pub = publication(PublicationStatus.INACTIVE, PublicationVisibility.PUBLIC);
        when(publicationService.getPublicationById(PUBLICATION_ID)).thenReturn(Optional.of(pub));
        when(publicationService.isCallerInOwnerScope(pub, ACQUIRER_ID, ACQUIRER_ORG)).thenReturn(false);
        when(publicationService.callerHoldsReceipt(PUBLICATION_ID, ACQUIRER_ID, ACQUIRER_ORG)).thenReturn(true);
        stubSnapshotRenders(pub);

        ResponseEntity<?> response = renderAsAcquirer();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    @DisplayName("non-acquirer (no receipt) is FORBIDDEN on a non-public showcase - 403, snapshot never read")
    void nonAcquirerForbiddenOnPrivate() {
        WorkflowPublicationEntity pub = publication(PublicationStatus.ACTIVE, PublicationVisibility.PRIVATE);
        when(publicationService.getPublicationById(PUBLICATION_ID)).thenReturn(Optional.of(pub));
        when(publicationService.isCallerInOwnerScope(pub, ACQUIRER_ID, ACQUIRER_ORG)).thenReturn(false);
        when(publicationService.callerHoldsReceipt(PUBLICATION_ID, ACQUIRER_ID, ACQUIRER_ORG)).thenReturn(false);

        ResponseEntity<?> response = renderAsAcquirer();

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        verify(showcaseSnapshotReader, never()).readInterfaceRender(any(), anyString(), anyInt(), anyInt(), any());
    }

    @Test
    @DisplayName("owner renders their own PRIVATE showcase without any receipt lookup - 200")
    void ownerRendersPrivateShowcaseWithoutReceiptLookup() {
        WorkflowPublicationEntity pub = publication(PublicationStatus.ACTIVE, PublicationVisibility.PRIVATE);
        when(publicationService.getPublicationById(PUBLICATION_ID)).thenReturn(Optional.of(pub));
        when(publicationService.isCallerInOwnerScope(pub, PUBLISHER_ID, null)).thenReturn(true);
        stubSnapshotRenders(pub);

        ResponseEntity<?> response = controller.renderShowcaseAuthenticated(
                PUBLICATION_ID.toString(), PUBLISHER_ID, null, null, 0, 1, null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(publicationService, never()).callerHoldsReceipt(any(), anyString(), any());
    }

    @Test
    @DisplayName("public ACTIVE showcase renders without any receipt lookup (hot path untouched) - 200")
    void publicShowcaseNoReceiptLookup() {
        WorkflowPublicationEntity pub = publication(PublicationStatus.ACTIVE, PublicationVisibility.PUBLIC);
        when(publicationService.getPublicationById(PUBLICATION_ID)).thenReturn(Optional.of(pub));
        when(publicationService.isCallerInOwnerScope(eq(pub), any(), any())).thenReturn(false);
        stubSnapshotRenders(pub);

        ResponseEntity<?> response = renderAsAcquirer();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        // visibleToPublic short-circuits the acquirer check: receipts never queried for a public app.
        verify(publicationService, never()).callerHoldsReceipt(any(), anyString(), any());
    }

    @Test
    @DisplayName("acquirer of a publication with no captured showcase interface gets 404 (after passing the gate)")
    void acquirerMissingShowcaseInterfaceReturns404() {
        WorkflowPublicationEntity pub = publication(PublicationStatus.ACTIVE, PublicationVisibility.PRIVATE);
        pub.setShowcaseInterfaceId(null);
        when(publicationService.getPublicationById(PUBLICATION_ID)).thenReturn(Optional.of(pub));
        when(publicationService.isCallerInOwnerScope(pub, ACQUIRER_ID, ACQUIRER_ORG)).thenReturn(false);
        when(publicationService.callerHoldsReceipt(PUBLICATION_ID, ACQUIRER_ID, ACQUIRER_ORG)).thenReturn(true);

        ResponseEntity<?> response = renderAsAcquirer();

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        verify(showcaseSnapshotReader, never()).hasSnapshot(any());
    }

    @Test
    @DisplayName("unknown publication id -> 404 (no gate, no receipt lookup)")
    void unknownPublicationReturns404() {
        when(publicationService.getPublicationById(PUBLICATION_ID)).thenReturn(Optional.empty());

        ResponseEntity<?> response = renderAsAcquirer();

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        verify(publicationService, never()).callerHoldsReceipt(any(), anyString(), any());
    }

    @Test
    @DisplayName("a malformed explicit interfaceId is rejected 400 (shared helper validation)")
    void malformedInterfaceIdReturns400() {
        WorkflowPublicationEntity pub = publication(PublicationStatus.ACTIVE, PublicationVisibility.PRIVATE);
        when(publicationService.getPublicationById(PUBLICATION_ID)).thenReturn(Optional.of(pub));
        when(publicationService.isCallerInOwnerScope(pub, ACQUIRER_ID, ACQUIRER_ORG)).thenReturn(false);
        when(publicationService.callerHoldsReceipt(PUBLICATION_ID, ACQUIRER_ID, ACQUIRER_ORG)).thenReturn(true);

        ResponseEntity<?> response = controller.renderShowcaseAuthenticated(
                PUBLICATION_ID.toString(), ACQUIRER_ID, ACQUIRER_ORG, "not-a-uuid", 0, 1, null);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        verify(showcaseSnapshotReader, never()).hasSnapshot(any());
    }

    @Test
    @DisplayName("no captured snapshot -> 503 (admin backfill, shared helper)")
    void missingSnapshotReturns503() {
        WorkflowPublicationEntity pub = publication(PublicationStatus.ACTIVE, PublicationVisibility.PRIVATE);
        when(publicationService.getPublicationById(PUBLICATION_ID)).thenReturn(Optional.of(pub));
        when(publicationService.isCallerInOwnerScope(pub, ACQUIRER_ID, ACQUIRER_ORG)).thenReturn(false);
        when(publicationService.callerHoldsReceipt(PUBLICATION_ID, ACQUIRER_ID, ACQUIRER_ORG)).thenReturn(true);
        when(showcaseSnapshotReader.hasSnapshot(pub)).thenReturn(false);

        ResponseEntity<?> response = renderAsAcquirer();

        assertThat(response.getStatusCode().value()).isEqualTo(503);
    }

    @Test
    @DisplayName("snapshot present but the interface render is empty -> 404 (shared helper)")
    void emptyRenderReturns404() {
        WorkflowPublicationEntity pub = publication(PublicationStatus.ACTIVE, PublicationVisibility.PRIVATE);
        when(publicationService.getPublicationById(PUBLICATION_ID)).thenReturn(Optional.of(pub));
        when(publicationService.isCallerInOwnerScope(pub, ACQUIRER_ID, ACQUIRER_ORG)).thenReturn(false);
        when(publicationService.callerHoldsReceipt(PUBLICATION_ID, ACQUIRER_ID, ACQUIRER_ORG)).thenReturn(true);
        when(showcaseSnapshotReader.hasSnapshot(pub)).thenReturn(true);
        when(showcaseSnapshotReader.readInterfaceRender(
                eq(pub), eq(SHOWCASE_INTERFACE_ID.toString()), anyInt(), anyInt(), isNull()))
                .thenReturn(Optional.empty());

        ResponseEntity<?> response = renderAsAcquirer();

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("snapshot render with a blank html template -> 404 (shared helper)")
    void blankHtmlReturns404() {
        WorkflowPublicationEntity pub = publication(PublicationStatus.ACTIVE, PublicationVisibility.PRIVATE);
        when(publicationService.getPublicationById(PUBLICATION_ID)).thenReturn(Optional.of(pub));
        when(publicationService.isCallerInOwnerScope(pub, ACQUIRER_ID, ACQUIRER_ORG)).thenReturn(false);
        when(publicationService.callerHoldsReceipt(PUBLICATION_ID, ACQUIRER_ID, ACQUIRER_ORG)).thenReturn(true);
        when(showcaseSnapshotReader.hasSnapshot(pub)).thenReturn(true);
        when(showcaseSnapshotReader.readInterfaceRender(
                eq(pub), eq(SHOWCASE_INTERFACE_ID.toString()), anyInt(), anyInt(), isNull()))
                .thenReturn(Optional.of(Map.of("htmlTemplate", "")));

        ResponseEntity<?> response = renderAsAcquirer();

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }
}
