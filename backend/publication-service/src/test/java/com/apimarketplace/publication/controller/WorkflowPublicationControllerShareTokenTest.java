package com.apimarketplace.publication.controller;

import com.apimarketplace.publication.service.ApplicationTemplateResetService;

import com.apimarketplace.auth.client.access.OrgAccessGuard;
import com.apimarketplace.publication.config.OrchestratorInternalClient;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.DisplayMode;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.OwnerType;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.PublicationStatus;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.PublicationVisibility;
import com.apimarketplace.publication.service.AgentPublicationService;
import com.apimarketplace.publication.service.LandingInterfaceSnapshotter;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowPublicationController share-token publication reads")
class WorkflowPublicationControllerShareTokenTest {

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
    private static final String OWNER_ID = "103";
    private static final String ORG_ID = "25686b7f-dbdc-491e-ba74-e177486a88bc";

    @BeforeEach
    void setUp() {
        controller = new WorkflowPublicationController(
                publicationService, agentPublicationService, listQueryService,
                reviewService, resourcePublicationService, orchestratorClient,
                landingInterfaceSnapshotter, showcaseSnapshotReader, fileRefRewriter,
                new com.apimarketplace.publication.service.OnboardingCategoryMapper(),
                orgAccessGuard,
                org.mockito.Mockito.mock(ApplicationTemplateResetService.class));
    }

    @Test
    @DisplayName("Matching APPLICATION ShareToken can read a private ORG-owned publication without org header")
    void matchingApplicationShareTokenCanReadPrivateOrgPublication() {
        WorkflowPublicationEntity pub = privateOrgApplication();
        when(publicationService.getPublicationById(PUBLICATION_ID)).thenReturn(Optional.of(pub));
        when(publicationService.isCallerInOwnerScope(pub, OWNER_ID, null)).thenReturn(false);

        ResponseEntity<?> response = controller.getPublicationById(
                PUBLICATION_ID.toString(),
                OWNER_ID,
                null,
                "true",
                "APPLICATION",
                PUBLICATION_ID.toString());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).containsEntry("id", PUBLICATION_ID.toString());
        assertThat(body).containsEntry("visibility", "PRIVATE");
        assertThat(body).containsEntry("planSnapshot", Map.of("interfaces", "raw"));
        assertThat(body).doesNotContainKey("publisherEmail");
    }

    @Test
    @DisplayName("A share-link visitor never receives publisherEmail, although the token authenticates them AS the owner")
    void shareVisitorNeverReceivesPublisherEmail() {
        WorkflowPublicationEntity pub = privateOrgApplication();
        pub.setPublisherName("Owner Display Name");
        when(publicationService.getPublicationById(PUBLICATION_ID)).thenReturn(Optional.of(pub));
        // The edge injects the link owner's identity, so the owner-scope check passes.
        when(publicationService.isCallerInOwnerScope(pub, OWNER_ID, ORG_ID)).thenReturn(true);

        ResponseEntity<?> response = controller.getPublicationById(
                PUBLICATION_ID.toString(),
                OWNER_ID,
                ORG_ID,
                "true",
                "APPLICATION",
                PUBLICATION_ID.toString());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).containsEntry("ownedByMe", true);
        // The public display name stays (the viewer header shows it); only the address goes.
        assertThat(body).containsEntry("publisherName", "Owner Display Name");
        assertThat(body).doesNotContainKey("publisherEmail");
    }

    @Test
    @DisplayName("The owner's own session still receives publisherEmail: the scrub is specific to share visitors")
    void ownerSessionStillReceivesPublisherEmail() {
        WorkflowPublicationEntity pub = privateOrgApplication();
        when(publicationService.getPublicationById(PUBLICATION_ID)).thenReturn(Optional.of(pub));
        when(publicationService.isCallerInOwnerScope(pub, OWNER_ID, ORG_ID)).thenReturn(true);

        ResponseEntity<?> response = controller.getPublicationById(
                PUBLICATION_ID.toString(), OWNER_ID, ORG_ID, null, null, null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).containsEntry("publisherEmail", "owner@example.test");
    }

    @Test
    @DisplayName("Mismatched ShareToken resource cannot read a private ORG-owned publication")
    void mismatchedShareTokenCannotReadPrivateOrgPublication() {
        WorkflowPublicationEntity pub = privateOrgApplication();
        when(publicationService.getPublicationById(PUBLICATION_ID)).thenReturn(Optional.of(pub));

        ResponseEntity<?> response = controller.getPublicationById(
                PUBLICATION_ID.toString(),
                OWNER_ID,
                null,
                "true",
                "APPLICATION",
                UUID.randomUUID().toString());

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("Mismatched ShareToken resource stays denied even when the token owner owns the sibling publication")
    void mismatchedShareTokenCannotReadSiblingPublicationOwnedBySameUser() {
        WorkflowPublicationEntity pub = privateOrgApplication();
        when(publicationService.getPublicationById(PUBLICATION_ID)).thenReturn(Optional.of(pub));

        ResponseEntity<?> response = controller.getPublicationById(
                PUBLICATION_ID.toString(),
                OWNER_ID,
                ORG_ID,
                "true",
                "APPLICATION",
                UUID.randomUUID().toString());

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("Matching APPLICATION ShareToken resolves application workflow in publication owner org scope")
    void matchingApplicationShareTokenResolvesApplicationWorkflowInOwnerOrgScope() {
        WorkflowPublicationEntity pub = privateOrgApplication();
        UUID applicationWorkflowId = UUID.randomUUID();
        when(publicationService.getPublicationById(PUBLICATION_ID)).thenReturn(Optional.of(pub));
        when(publicationService.findApplicationWorkflow(PUBLICATION_ID, OWNER_ID, ORG_ID))
                .thenReturn(Map.of("id", applicationWorkflowId.toString()));

        ResponseEntity<?> response = controller.getApplicationWorkflow(
                OWNER_ID,
                null,
                "true",
                "APPLICATION",
                PUBLICATION_ID.toString(),
                PUBLICATION_ID.toString());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).containsEntry("workflowId", applicationWorkflowId.toString());
        verify(publicationService).findApplicationWorkflow(PUBLICATION_ID, OWNER_ID, ORG_ID);
    }

    @Test
    @DisplayName("Mismatched APPLICATION ShareToken cannot resolve a sibling application workflow")
    void mismatchedApplicationShareTokenCannotResolveSiblingApplicationWorkflow() {
        ResponseEntity<?> response = controller.getApplicationWorkflow(
                OWNER_ID,
                ORG_ID,
                "true",
                "APPLICATION",
                UUID.randomUUID().toString(),
                PUBLICATION_ID.toString());

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        verify(publicationService, never()).findApplicationWorkflow(PUBLICATION_ID, OWNER_ID, ORG_ID);
    }

    // ===== CASA LC-037 round-3: getMyReview (X-User-ID resolves to the OWNER under a share
    // token, so this must not leak the owner's review on an unrelated publication) =====

    @Test
    @DisplayName("pre-fix regression: a share token cannot read the owner's review on a DIFFERENT "
            + "publication than the one actually shared - closes an owner-impersonation review leak")
    void mismatchedShareTokenCannotReadMyReviewOnSiblingPublication() {
        UUID otherPublicationId = UUID.randomUUID();

        ResponseEntity<?> response = controller.getMyReview(OWNER_ID, PUBLICATION_ID.toString(), "true", "APPLICATION", otherPublicationId.toString());

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        verify(reviewService, never()).getMyReview(PUBLICATION_ID, OWNER_ID);
    }

    @Test
    @DisplayName("Matching APPLICATION ShareToken can read the owner's review on the shared publication itself")
    void matchingShareTokenCanReadMyReviewOnSharedPublication() {
        when(reviewService.getMyReview(PUBLICATION_ID, OWNER_ID)).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.getMyReview(OWNER_ID, PUBLICATION_ID.toString(), "true", "APPLICATION", PUBLICATION_ID.toString());

        // No review exists yet, so the endpoint's own 404 fires AFTER the binding check passed -
        // what matters here is that the lookup was reached at all (proven by the verify below),
        // unlike the mismatched-id case above which never calls the service.
        assertThat(response.getStatusCode().value()).isEqualTo(404);
        verify(reviewService).getMyReview(PUBLICATION_ID, OWNER_ID);
    }

    @Test
    @DisplayName("A non-share (authenticated) caller reading their own review is unaffected by the gate")
    void nonShareCallerReadsOwnReviewUnaffected() {
        when(reviewService.getMyReview(PUBLICATION_ID, OWNER_ID)).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.getMyReview(OWNER_ID, PUBLICATION_ID.toString(), null, null, null);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        verify(reviewService).getMyReview(PUBLICATION_ID, OWNER_ID);
    }

    private WorkflowPublicationEntity privateOrgApplication() {
        WorkflowPublicationEntity pub = new WorkflowPublicationEntity();
        pub.setId(PUBLICATION_ID);
        pub.setWorkflowId(UUID.randomUUID());
        pub.setTitle("Private shared app");
        pub.setPublisherId(OWNER_ID);
        pub.setPublisherEmail("owner@example.test");
        pub.setOwnerType(OwnerType.ORG);
        pub.setOwnerId(ORG_ID);
        pub.setStatus(PublicationStatus.ACTIVE);
        pub.setVisibility(PublicationVisibility.PRIVATE);
        pub.setDisplayMode(DisplayMode.APPLICATION);
        pub.setShowcaseInterfaceId(UUID.randomUUID());
        pub.setShowcaseRunId("run_share");
        pub.setPlanSnapshot(Map.of("interfaces", "raw"));
        return pub;
    }
}
