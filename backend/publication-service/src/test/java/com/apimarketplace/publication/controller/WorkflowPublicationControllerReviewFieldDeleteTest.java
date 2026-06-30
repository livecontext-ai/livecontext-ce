package com.apimarketplace.publication.controller;

import com.apimarketplace.auth.client.access.OrgAccessGuard;
import com.apimarketplace.publication.config.OrchestratorInternalClient;
import com.apimarketplace.publication.domain.PublicationReviewEntity;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Covers the two field-scoped review delete endpoints that keep rating and
 * comment independent: DELETE /reviews/comment and DELETE /reviews/rating. The
 * contract under test is the controller's response mapping - a surviving review
 * is returned via toReviewResponse, a fully removed row yields {success,removed},
 * and an IllegalArgumentException (no review) surfaces as 400.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowPublicationController field-scoped review deletion")
class WorkflowPublicationControllerReviewFieldDeleteTest {

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

    private static final UUID PUB_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String USER_ID = "303";

    @BeforeEach
    void setUp() {
        controller = new WorkflowPublicationController(
                publicationService, agentPublicationService, listQueryService,
                reviewService, resourcePublicationService, orchestratorClient,
                landingInterfaceSnapshotter, showcaseSnapshotReader, fileRefRewriter,
                new com.apimarketplace.publication.service.OnboardingCategoryMapper(),
                orgAccessGuard);
    }

    private PublicationReviewEntity ratedRowWithoutComment() {
        PublicationReviewEntity review = new PublicationReviewEntity();
        review.setId(UUID.randomUUID());
        review.setPublicationId(PUB_ID);
        review.setReviewerId(USER_ID);
        review.setRating((short) 4);
        review.setComment(null);
        return review;
    }

    private PublicationReviewEntity commentedRowWithoutRating() {
        PublicationReviewEntity review = new PublicationReviewEntity();
        review.setId(UUID.randomUUID());
        review.setPublicationId(PUB_ID);
        review.setReviewerId(USER_ID);
        review.setRating(null);
        review.setComment("still here");
        return review;
    }

    // ------------------------------------------------------------------------
    // DELETE /reviews/comment
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("deleteReviewComment returns the surviving review (rating kept, comment null) when the row survives")
    void deleteCommentReturnsSurvivingReview() {
        when(reviewService.clearComment(PUB_ID, USER_ID)).thenReturn(ratedRowWithoutComment());

        ResponseEntity<?> response = controller.deleteReviewComment(USER_ID, PUB_ID.toString());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).containsEntry("rating", (short) 4);
        assertThat(body).containsEntry("comment", null);
        assertThat(body).doesNotContainKey("removed");
    }

    @Test
    @DisplayName("deleteReviewComment returns {success,removed} when the comment-only row was removed")
    void deleteCommentReturnsRemovedWhenRowGone() {
        when(reviewService.clearComment(PUB_ID, USER_ID)).thenReturn(null);

        ResponseEntity<?> response = controller.deleteReviewComment(USER_ID, PUB_ID.toString());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).containsEntry("success", true).containsEntry("removed", true);
    }

    @Test
    @DisplayName("deleteReviewComment maps a missing review to 400 BAD REQUEST")
    void deleteCommentMissingReviewIs400() {
        when(reviewService.clearComment(PUB_ID, USER_ID))
                .thenThrow(new IllegalArgumentException("No review found to update"));

        ResponseEntity<?> response = controller.deleteReviewComment(USER_ID, PUB_ID.toString());

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).containsEntry("error", "No review found to update");
    }

    // ------------------------------------------------------------------------
    // DELETE /reviews/rating
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("deleteReviewRating returns the surviving review (comment kept, rating null) when the row survives")
    void deleteRatingReturnsSurvivingReview() {
        when(reviewService.clearRating(PUB_ID, USER_ID)).thenReturn(commentedRowWithoutRating());

        ResponseEntity<?> response = controller.deleteReviewRating(USER_ID, PUB_ID.toString());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).containsEntry("comment", "still here");
        assertThat(body).containsEntry("rating", null);
        assertThat(body).doesNotContainKey("removed");
    }

    @Test
    @DisplayName("deleteReviewRating returns {success,removed} when the rating-only row was removed")
    void deleteRatingReturnsRemovedWhenRowGone() {
        when(reviewService.clearRating(PUB_ID, USER_ID)).thenReturn(null);

        ResponseEntity<?> response = controller.deleteReviewRating(USER_ID, PUB_ID.toString());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).containsEntry("success", true).containsEntry("removed", true);
    }

    @Test
    @DisplayName("deleteReviewRating maps a missing review to 400 BAD REQUEST")
    void deleteRatingMissingReviewIs400() {
        when(reviewService.clearRating(PUB_ID, USER_ID))
                .thenThrow(new IllegalArgumentException("No review found to update"));

        ResponseEntity<?> response = controller.deleteReviewRating(USER_ID, PUB_ID.toString());

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).containsEntry("error", "No review found to update");
    }
}
