package com.apimarketplace.publication.controller;

import com.apimarketplace.auth.client.access.OrgAccessGuard;
import com.apimarketplace.publication.config.OrchestratorInternalClient;
import com.apimarketplace.publication.domain.PublicationReviewEntity;
import com.apimarketplace.publication.service.AgentPublicationService;
import com.apimarketplace.publication.service.ApplicationTemplateResetService;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GET /{publicationId}/reviews/mine is on the share-link allow-list (the viewer shows the
 * visitor's own review of the shared app). A share holder is authenticated AS THE OWNER, so
 * without a binding the link read the owner's review of ANY publication by id.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowPublicationController - /reviews/mine bound to the shared publication")
class WorkflowPublicationControllerShareReviewMineTest {

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

    private static final String OWNER = "owner-1";
    private static final UUID SHARED_PUB = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_PUB = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @BeforeEach
    void setUp() {
        controller = new WorkflowPublicationController(
                publicationService, agentPublicationService, listQueryService,
                reviewService, resourcePublicationService, orchestratorClient,
                landingInterfaceSnapshotter, showcaseSnapshotReader, fileRefRewriter,
                new OnboardingCategoryMapper(), orgAccessGuard,
                mock(ApplicationTemplateResetService.class));
    }

    private static PublicationReviewEntity review(UUID pub) {
        PublicationReviewEntity r = new PublicationReviewEntity();
        r.setId(UUID.randomUUID());
        r.setPublicationId(pub);
        r.setReviewerId(OWNER);
        r.setRating((short) 4);
        r.setComment("owner's private opinion");
        return r;
    }

    @Test
    @DisplayName("share link probing ANOTHER publication id gets 404 and no review is read")
    void shareForeignPublicationIs404() {
        ResponseEntity<?> r = controller.getMyReview(OWNER, OTHER_PUB.toString(),
                "true", "APPLICATION", SHARED_PUB.toString());

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(reviewService, never()).getMyReview(any(), any());
    }

    @Test
    @DisplayName("non-APPLICATION share context is refused as well")
    void nonApplicationShareIs404() {
        ResponseEntity<?> r = controller.getMyReview(OWNER, SHARED_PUB.toString(),
                "true", "CONVERSATION", SHARED_PUB.toString());

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(reviewService, never()).getMyReview(any(), any());
    }

    @Test
    @DisplayName("share link on the shared publication itself still answers")
    void shareOwnPublicationAnswers() {
        when(reviewService.getMyReview(SHARED_PUB, OWNER)).thenReturn(Optional.of(review(SHARED_PUB)));

        ResponseEntity<?> r = controller.getMyReview(OWNER, SHARED_PUB.toString(),
                "true", "APPLICATION", SHARED_PUB.toString());

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("no share context: a signed-in user reads their review of any publication (unchanged)")
    void signedInUnchanged() {
        when(reviewService.getMyReview(OTHER_PUB, OWNER)).thenReturn(Optional.of(review(OTHER_PUB)));

        ResponseEntity<?> r = controller.getMyReview(OWNER, OTHER_PUB.toString(), null, null, null);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
