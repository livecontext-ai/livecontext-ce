package com.apimarketplace.publication.service;

import com.apimarketplace.publication.domain.PublicationReviewEntity;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity;
import com.apimarketplace.publication.repository.PublicationReviewRepository;
import com.apimarketplace.publication.repository.WorkflowPublicationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PublicationReviewService")
class PublicationReviewServiceTest {

    @Mock
    private PublicationReviewRepository reviewRepository;

    @Mock
    private WorkflowPublicationRepository publicationRepository;

    private PublicationReviewService service;

    private static final UUID PUB_ID = UUID.randomUUID();
    private static final String PUBLISHER_ID = "publisher|001";
    private static final String REVIEWER_ID = "reviewer|002";
    private static final String OTHER_REVIEWER = "reviewer|003";

    private WorkflowPublicationEntity publication;

    @BeforeEach
    void setUp() {
        service = new PublicationReviewService(reviewRepository, publicationRepository);

        publication = new WorkflowPublicationEntity();
        publication.setId(PUB_ID);
        publication.setPublisherId(PUBLISHER_ID);
    }

    // ========================================================================
    // submitReview
    // ========================================================================

    @Nested
    @DisplayName("submitReview")
    class SubmitReview {

        @Test
        @DisplayName("creates new review when none exists")
        void createsNewReview() {
            when(publicationRepository.findById(PUB_ID)).thenReturn(Optional.of(publication));
            when(reviewRepository.findTopLevelByPublicationIdAndReviewerId(PUB_ID, REVIEWER_ID))
                    .thenReturn(Optional.empty());
            when(reviewRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(reviewRepository.countTopLevelByPublicationId(PUB_ID)).thenReturn(1);
            when(reviewRepository.computeAverageRating(PUB_ID)).thenReturn(4.0);

            PublicationReviewEntity result = service.submitReview(
                    PUB_ID, REVIEWER_ID, "John", null, Short.valueOf((short) 4), "Great!");

            assertThat(result.getPublicationId()).isEqualTo(PUB_ID);
            assertThat(result.getReviewerId()).isEqualTo(REVIEWER_ID);
            assertThat(result.getRating()).isEqualTo((short) 4);
            assertThat(result.getComment()).isEqualTo("Great!");
            assertThat(result.getReviewerName()).isEqualTo("John");
            assertThat(result.getParentId()).isNull();

            verify(reviewRepository).save(any(PublicationReviewEntity.class));
            verify(publicationRepository).updateReviewStats(PUB_ID, 4.0, 1);
        }

        @Test
        @DisplayName("updates existing review (upsert)")
        void updatesExistingReview() {
            PublicationReviewEntity existing = new PublicationReviewEntity();
            existing.setId(UUID.randomUUID());
            existing.setPublicationId(PUB_ID);
            existing.setReviewerId(REVIEWER_ID);
            existing.setRating((short) 3);
            existing.setComment("OK");

            when(publicationRepository.findById(PUB_ID)).thenReturn(Optional.of(publication));
            when(reviewRepository.findTopLevelByPublicationIdAndReviewerId(PUB_ID, REVIEWER_ID))
                    .thenReturn(Optional.of(existing));
            when(reviewRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(reviewRepository.countTopLevelByPublicationId(PUB_ID)).thenReturn(1);
            when(reviewRepository.computeAverageRating(PUB_ID)).thenReturn(5.0);

            PublicationReviewEntity result = service.submitReview(
                    PUB_ID, REVIEWER_ID, "John Updated", null, Short.valueOf((short) 5), "Amazing!");

            assertThat(result.getId()).isEqualTo(existing.getId());
            assertThat(result.getRating()).isEqualTo((short) 5);
            assertThat(result.getComment()).isEqualTo("Amazing!");
            assertThat(result.getReviewerName()).isEqualTo("John Updated");

            verify(publicationRepository).updateReviewStats(PUB_ID, 5.0, 1);
        }

        @Test
        @DisplayName("rejects publisher reviewing own publication")
        void rejectsPublisherSelfReview() {
            when(publicationRepository.findById(PUB_ID)).thenReturn(Optional.of(publication));

            assertThatThrownBy(() ->
                    service.submitReview(PUB_ID, PUBLISHER_ID, "Publisher", null, (short) 5, "Great!"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Cannot review your own publication");

            verify(reviewRepository, never()).save(any());
        }

        @Test
        @DisplayName("rejects invalid rating below 1")
        void rejectsRatingBelowOne() {
            when(publicationRepository.findById(PUB_ID)).thenReturn(Optional.of(publication));

            assertThatThrownBy(() ->
                    service.submitReview(PUB_ID, REVIEWER_ID, "John", null, Short.valueOf((short) 0), null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Rating must be between 1 and 5");
        }

        @Test
        @DisplayName("rejects invalid rating above 5")
        void rejectsRatingAboveFive() {
            when(publicationRepository.findById(PUB_ID)).thenReturn(Optional.of(publication));

            assertThatThrownBy(() ->
                    service.submitReview(PUB_ID, REVIEWER_ID, "John", null, Short.valueOf((short) 6), null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Rating must be between 1 and 5");
        }

        @Test
        @DisplayName("rejects review for non-existent publication")
        void rejectsNonExistentPublication() {
            when(publicationRepository.findById(PUB_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                    service.submitReview(PUB_ID, REVIEWER_ID, "John", null, Short.valueOf((short) 4), null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Publication not found");
        }

        @Test
        @DisplayName("allows rating-only review (no comment)")
        void allowsRatingOnlyReview() {
            when(publicationRepository.findById(PUB_ID)).thenReturn(Optional.of(publication));
            when(reviewRepository.findTopLevelByPublicationIdAndReviewerId(PUB_ID, REVIEWER_ID))
                    .thenReturn(Optional.empty());
            when(reviewRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(reviewRepository.countTopLevelByPublicationId(PUB_ID)).thenReturn(1);
            when(reviewRepository.computeAverageRating(PUB_ID)).thenReturn(3.0);

            PublicationReviewEntity result = service.submitReview(
                    PUB_ID, REVIEWER_ID, "John", null, Short.valueOf((short) 3), null);

            assertThat(result.getRating()).isEqualTo((short) 3);
            assertThat(result.getComment()).isNull();
        }

        @Test
        @DisplayName("allows comment-only review (no rating)")
        void allowsCommentOnlyReview() {
            when(publicationRepository.findById(PUB_ID)).thenReturn(Optional.of(publication));
            when(reviewRepository.findTopLevelByPublicationIdAndReviewerId(PUB_ID, REVIEWER_ID))
                    .thenReturn(Optional.empty());
            when(reviewRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(reviewRepository.countTopLevelByPublicationId(PUB_ID)).thenReturn(0);

            PublicationReviewEntity result = service.submitReview(
                    PUB_ID, REVIEWER_ID, "John", null, null, "Just a comment");

            assertThat(result.getRating()).isNull();
            assertThat(result.getComment()).isEqualTo("Just a comment");
        }

        @Test
        @DisplayName("truncates comment exceeding max length")
        void truncatesLongComment() {
            String longComment = "x".repeat(3000);

            when(publicationRepository.findById(PUB_ID)).thenReturn(Optional.of(publication));
            when(reviewRepository.findTopLevelByPublicationIdAndReviewerId(PUB_ID, REVIEWER_ID))
                    .thenReturn(Optional.empty());
            when(reviewRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(reviewRepository.countTopLevelByPublicationId(PUB_ID)).thenReturn(1);
            when(reviewRepository.computeAverageRating(PUB_ID)).thenReturn(4.0);

            PublicationReviewEntity result = service.submitReview(
                    PUB_ID, REVIEWER_ID, "John", null, Short.valueOf((short) 4), longComment);

            assertThat(result.getComment()).hasSize(2000);
        }
    }

    // ========================================================================
    // deleteReview
    // ========================================================================

    @Nested
    @DisplayName("deleteReview")
    class DeleteReview {

        @Test
        @DisplayName("deletes existing review and recomputes stats")
        void deletesExistingReview() {
            PublicationReviewEntity review = new PublicationReviewEntity();
            review.setId(UUID.randomUUID());
            review.setPublicationId(PUB_ID);
            review.setReviewerId(REVIEWER_ID);

            when(reviewRepository.findTopLevelByPublicationIdAndReviewerId(PUB_ID, REVIEWER_ID))
                    .thenReturn(Optional.of(review));
            when(reviewRepository.countTopLevelByPublicationId(PUB_ID)).thenReturn(0);

            service.deleteReview(PUB_ID, REVIEWER_ID);

            verify(reviewRepository).delete(review);
            verify(publicationRepository).updateReviewStats(PUB_ID, 0.0, 0);
        }

        @Test
        @DisplayName("throws when no review found to delete")
        void throwsWhenNoReviewToDelete() {
            when(reviewRepository.findTopLevelByPublicationIdAndReviewerId(PUB_ID, REVIEWER_ID))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.deleteReview(PUB_ID, REVIEWER_ID))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("No review found to delete");

            verify(reviewRepository, never()).delete(any());
        }
    }

    // ========================================================================
    // clearComment - delete ONLY the comment, keep the rating
    // ========================================================================

    @Nested
    @DisplayName("clearComment")
    class ClearComment {

        @Test
        @DisplayName("clears comment but keeps rating when the review is rated (no row delete, no stats recompute)")
        void keepsRatingWhenRated() {
            PublicationReviewEntity review = new PublicationReviewEntity();
            review.setId(UUID.randomUUID());
            review.setPublicationId(PUB_ID);
            review.setReviewerId(REVIEWER_ID);
            review.setRating((short) 4);
            review.setComment("nice app");

            when(reviewRepository.findTopLevelByPublicationIdAndReviewerId(PUB_ID, REVIEWER_ID))
                    .thenReturn(Optional.of(review));
            when(reviewRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            PublicationReviewEntity result = service.clearComment(PUB_ID, REVIEWER_ID);

            assertThat(result).isNotNull();
            assertThat(result.getRating()).isEqualTo((short) 4);
            assertThat(result.getComment()).isNull();
            verify(reviewRepository).save(review);
            verify(reviewRepository, never()).delete(any());
            // The comment does not feed the rating stats, so no recompute happens.
            verify(publicationRepository, never()).updateReviewStats(any(), anyDouble(), anyInt());
        }

        @Test
        @DisplayName("removes the whole row and recomputes stats when the review was comment-only")
        void removesRowWhenCommentOnly() {
            PublicationReviewEntity review = new PublicationReviewEntity();
            review.setId(UUID.randomUUID());
            review.setPublicationId(PUB_ID);
            review.setReviewerId(REVIEWER_ID);
            review.setRating(null);
            review.setComment("just a comment");

            when(reviewRepository.findTopLevelByPublicationIdAndReviewerId(PUB_ID, REVIEWER_ID))
                    .thenReturn(Optional.of(review));
            when(reviewRepository.countTopLevelByPublicationId(PUB_ID)).thenReturn(0);

            PublicationReviewEntity result = service.clearComment(PUB_ID, REVIEWER_ID);

            assertThat(result).isNull();
            verify(reviewRepository).delete(review);
            verify(reviewRepository, never()).save(any());
            verify(publicationRepository).updateReviewStats(PUB_ID, 0.0, 0);
        }

        @Test
        @DisplayName("throws when the user has no review")
        void throwsWhenNoReview() {
            when(reviewRepository.findTopLevelByPublicationIdAndReviewerId(PUB_ID, REVIEWER_ID))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.clearComment(PUB_ID, REVIEWER_ID))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("No review found to update");

            verify(reviewRepository, never()).save(any());
            verify(reviewRepository, never()).delete(any());
        }
    }

    // ========================================================================
    // clearRating - delete ONLY the rating, keep the comment
    // ========================================================================

    @Nested
    @DisplayName("clearRating")
    class ClearRating {

        @Test
        @DisplayName("clears rating but keeps comment when the review has a comment (recomputes stats)")
        void keepsCommentWhenCommented() {
            PublicationReviewEntity review = new PublicationReviewEntity();
            review.setId(UUID.randomUUID());
            review.setPublicationId(PUB_ID);
            review.setReviewerId(REVIEWER_ID);
            review.setRating((short) 5);
            review.setComment("loved it");

            when(reviewRepository.findTopLevelByPublicationIdAndReviewerId(PUB_ID, REVIEWER_ID))
                    .thenReturn(Optional.of(review));
            when(reviewRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(reviewRepository.countTopLevelByPublicationId(PUB_ID)).thenReturn(0);

            PublicationReviewEntity result = service.clearRating(PUB_ID, REVIEWER_ID);

            assertThat(result).isNotNull();
            assertThat(result.getRating()).isNull();
            assertThat(result.getComment()).isEqualTo("loved it");
            verify(reviewRepository).save(review);
            verify(reviewRepository, never()).delete(any());
            // Dropping the rating changes the vote count, so stats are recomputed.
            verify(publicationRepository).updateReviewStats(PUB_ID, 0.0, 0);
        }

        @Test
        @DisplayName("removes the whole row and recomputes stats when the review was rating-only")
        void removesRowWhenRatingOnly() {
            PublicationReviewEntity review = new PublicationReviewEntity();
            review.setId(UUID.randomUUID());
            review.setPublicationId(PUB_ID);
            review.setReviewerId(REVIEWER_ID);
            review.setRating((short) 3);
            review.setComment(null);

            when(reviewRepository.findTopLevelByPublicationIdAndReviewerId(PUB_ID, REVIEWER_ID))
                    .thenReturn(Optional.of(review));
            when(reviewRepository.countTopLevelByPublicationId(PUB_ID)).thenReturn(0);

            PublicationReviewEntity result = service.clearRating(PUB_ID, REVIEWER_ID);

            assertThat(result).isNull();
            verify(reviewRepository).delete(review);
            verify(reviewRepository, never()).save(any());
            verify(publicationRepository).updateReviewStats(PUB_ID, 0.0, 0);
        }

        @Test
        @DisplayName("treats a blank comment as empty and removes the rating-only row")
        void blankCommentCountsAsEmpty() {
            PublicationReviewEntity review = new PublicationReviewEntity();
            review.setId(UUID.randomUUID());
            review.setPublicationId(PUB_ID);
            review.setReviewerId(REVIEWER_ID);
            review.setRating((short) 3);
            review.setComment("   ");

            when(reviewRepository.findTopLevelByPublicationIdAndReviewerId(PUB_ID, REVIEWER_ID))
                    .thenReturn(Optional.of(review));
            when(reviewRepository.countTopLevelByPublicationId(PUB_ID)).thenReturn(0);

            PublicationReviewEntity result = service.clearRating(PUB_ID, REVIEWER_ID);

            assertThat(result).isNull();
            verify(reviewRepository).delete(review);
            verify(reviewRepository, never()).save(any());
        }

        @Test
        @DisplayName("throws when the user has no review")
        void throwsWhenNoReview() {
            when(reviewRepository.findTopLevelByPublicationIdAndReviewerId(PUB_ID, REVIEWER_ID))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.clearRating(PUB_ID, REVIEWER_ID))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("No review found to update");

            verify(reviewRepository, never()).save(any());
            verify(reviewRepository, never()).delete(any());
        }
    }

    // ========================================================================
    // deleteAllReviews
    // ========================================================================

    @Nested
    @DisplayName("deleteAllReviews")
    class DeleteAllReviews {

        @Test
        @DisplayName("bulk deletes all reviews for a publication")
        void bulkDeletesReviews() {
            service.deleteAllReviews(PUB_ID);

            verify(reviewRepository).deleteByPublicationId(PUB_ID);
        }
    }

    // ========================================================================
    // getReviews
    // ========================================================================

    @Nested
    @DisplayName("getReviews")
    class GetReviews {

        @Test
        @DisplayName("returns paginated top-level reviews only")
        void returnsPaginatedTopLevelReviews() {
            PublicationReviewEntity r1 = new PublicationReviewEntity();
            r1.setId(UUID.randomUUID());
            r1.setRating((short) 5);

            Page<PublicationReviewEntity> page = new PageImpl<>(List.of(r1), PageRequest.of(0, 10), 1);
            when(reviewRepository.findTopLevelByPublicationId(eq(PUB_ID), any(PageRequest.class)))
                    .thenReturn(page);

            Page<PublicationReviewEntity> result = service.getReviews(PUB_ID, 0, 10);

            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getTotalElements()).isEqualTo(1);
        }

        @Test
        @DisplayName("onlyWithComment=true routes to the comment-filtered query (excludes rating-only votes)")
        void onlyWithCommentRoutesToFilteredQuery() {
            // Contract: the Comments tab feeds `onlyWithComment=true` so rating-only
            // votes never show up as blank entries in the list. The Info tab keeps
            // calling getReviews(.., false) and reads averageRating/reviewCount from
            // the publication entity for the moyenne.
            PublicationReviewEntity withComment = new PublicationReviewEntity();
            withComment.setId(UUID.randomUUID());
            withComment.setComment("Great app");
            Page<PublicationReviewEntity> page = new PageImpl<>(List.of(withComment), PageRequest.of(0, 10), 1);
            when(reviewRepository.findTopLevelWithCommentByPublicationId(eq(PUB_ID), any(PageRequest.class)))
                    .thenReturn(page);

            Page<PublicationReviewEntity> result = service.getReviews(PUB_ID, 0, 10, true);

            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getContent().get(0).getComment()).isEqualTo("Great app");
            verify(reviewRepository).findTopLevelWithCommentByPublicationId(eq(PUB_ID), any(PageRequest.class));
            verify(reviewRepository, never()).findTopLevelByPublicationId(any(), any(PageRequest.class));
        }

        @Test
        @DisplayName("onlyWithComment=false (default) routes to the unfiltered query (preserves backward compat)")
        void onlyWithCommentFalseRoutesToUnfiltered() {
            Page<PublicationReviewEntity> page = new PageImpl<>(List.of(), PageRequest.of(0, 10), 0);
            when(reviewRepository.findTopLevelByPublicationId(eq(PUB_ID), any(PageRequest.class)))
                    .thenReturn(page);

            service.getReviews(PUB_ID, 0, 10, false);

            verify(reviewRepository).findTopLevelByPublicationId(eq(PUB_ID), any(PageRequest.class));
            verify(reviewRepository, never()).findTopLevelWithCommentByPublicationId(any(), any(PageRequest.class));
        }
    }

    // ========================================================================
    // countComments
    // ========================================================================

    @Nested
    @DisplayName("countComments")
    class CountComments {

        @Test
        @DisplayName("delegates to repository countTopLevelWithCommentByPublicationId")
        void delegatesToCommentCountQuery() {
            // Drives the Comments tab badge on the publication panel. Distinct from
            // WorkflowPublicationEntity.reviewCount (votes count, persisted by
            // recomputeStats and used by the Info tab average).
            when(reviewRepository.countTopLevelWithCommentByPublicationId(PUB_ID)).thenReturn(7);

            int result = service.countComments(PUB_ID);

            assertThat(result).isEqualTo(7);
            verify(reviewRepository).countTopLevelWithCommentByPublicationId(PUB_ID);
        }
    }

    // ========================================================================
    // getMyReview
    // ========================================================================

    @Nested
    @DisplayName("getMyReview")
    class GetMyReview {

        @Test
        @DisplayName("returns review when exists")
        void returnsReviewWhenExists() {
            PublicationReviewEntity review = new PublicationReviewEntity();
            review.setId(UUID.randomUUID());
            review.setRating((short) 4);

            when(reviewRepository.findTopLevelByPublicationIdAndReviewerId(PUB_ID, REVIEWER_ID))
                    .thenReturn(Optional.of(review));

            Optional<PublicationReviewEntity> result = service.getMyReview(PUB_ID, REVIEWER_ID);

            assertThat(result).isPresent();
            assertThat(result.get().getRating()).isEqualTo((short) 4);
        }

        @Test
        @DisplayName("returns empty when no review exists")
        void returnsEmptyWhenNoReview() {
            when(reviewRepository.findTopLevelByPublicationIdAndReviewerId(PUB_ID, REVIEWER_ID))
                    .thenReturn(Optional.empty());

            Optional<PublicationReviewEntity> result = service.getMyReview(PUB_ID, REVIEWER_ID);

            assertThat(result).isEmpty();
        }
    }

    // ========================================================================
    // stats recomputation
    // ========================================================================

    @Nested
    @DisplayName("stats recomputation")
    class StatsRecomputation {

        @Test
        @DisplayName("computes correct average for multiple reviews")
        void computesCorrectAverage() {
            when(publicationRepository.findById(PUB_ID)).thenReturn(Optional.of(publication));
            when(reviewRepository.findTopLevelByPublicationIdAndReviewerId(eq(PUB_ID), eq(REVIEWER_ID)))
                    .thenReturn(Optional.empty());
            when(reviewRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(reviewRepository.countTopLevelByPublicationId(PUB_ID)).thenReturn(3);
            when(reviewRepository.computeAverageRating(PUB_ID)).thenReturn(3.67);

            service.submitReview(PUB_ID, REVIEWER_ID, "John", null, Short.valueOf((short) 4), null);

            verify(publicationRepository).updateReviewStats(PUB_ID, 3.67, 3);
        }

        @Test
        @DisplayName("resets average to zero when last review deleted")
        void resetsAverageToZero() {
            PublicationReviewEntity review = new PublicationReviewEntity();
            review.setId(UUID.randomUUID());
            review.setPublicationId(PUB_ID);
            review.setReviewerId(REVIEWER_ID);

            when(reviewRepository.findTopLevelByPublicationIdAndReviewerId(PUB_ID, REVIEWER_ID))
                    .thenReturn(Optional.of(review));
            when(reviewRepository.countTopLevelByPublicationId(PUB_ID)).thenReturn(0);

            service.deleteReview(PUB_ID, REVIEWER_ID);

            verify(reviewRepository, never()).computeAverageRating(any());
            verify(publicationRepository).updateReviewStats(PUB_ID, 0.0, 0);
        }

        @Test
        @DisplayName("stats ignore replies - only count top-level reviews")
        void statsIgnoreReplies() {
            when(publicationRepository.findById(PUB_ID)).thenReturn(Optional.of(publication));
            when(reviewRepository.findTopLevelByPublicationIdAndReviewerId(eq(PUB_ID), eq(REVIEWER_ID)))
                    .thenReturn(Optional.empty());
            when(reviewRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(reviewRepository.countTopLevelByPublicationId(PUB_ID)).thenReturn(2);
            when(reviewRepository.computeAverageRating(PUB_ID)).thenReturn(4.5);

            service.submitReview(PUB_ID, REVIEWER_ID, "John", null, Short.valueOf((short) 5), null);

            // Verify it uses countTopLevelByPublicationId, not countByPublicationId
            verify(reviewRepository).countTopLevelByPublicationId(PUB_ID);
            verify(publicationRepository).updateReviewStats(PUB_ID, 4.5, 2);
        }
    }

    // ========================================================================
    // Replies
    // ========================================================================

    @Nested
    @DisplayName("submitReply")
    class SubmitReplyTests {

        @Test
        @DisplayName("creates reply with parentId set and rating null")
        void submitReplySuccess() {
            UUID reviewId = UUID.randomUUID();
            PublicationReviewEntity parentReview = new PublicationReviewEntity();
            parentReview.setId(reviewId);
            parentReview.setPublicationId(PUB_ID);
            parentReview.setReviewerId(REVIEWER_ID);
            parentReview.setRating((short) 5);
            // parentId is null -> top-level

            when(reviewRepository.findById(reviewId)).thenReturn(Optional.of(parentReview));
            when(reviewRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            PublicationReviewEntity result = service.submitReply(
                    reviewId, OTHER_REVIEWER, "Jane", null, "Thanks!");

            assertThat(result.getParentId()).isEqualTo(reviewId);
            assertThat(result.getRating()).isNull();
            assertThat(result.getComment()).isEqualTo("Thanks!");
            assertThat(result.getPublicationId()).isEqualTo(PUB_ID);
            assertThat(result.getReviewerId()).isEqualTo(OTHER_REVIEWER);

            verify(reviewRepository).save(any(PublicationReviewEntity.class));
        }

        @Test
        @DisplayName("rejects reply to a reply (no nesting)")
        void submitReplyToReplyFails() {
            UUID parentId = UUID.randomUUID();
            UUID replyId = UUID.randomUUID();
            PublicationReviewEntity existingReply = new PublicationReviewEntity();
            existingReply.setId(replyId);
            existingReply.setPublicationId(PUB_ID);
            existingReply.setParentId(parentId); // This is already a reply

            when(reviewRepository.findById(replyId)).thenReturn(Optional.of(existingReply));

            assertThatThrownBy(() ->
                    service.submitReply(replyId, OTHER_REVIEWER, "Jane", null, "Nested!"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Cannot reply to a reply");

            verify(reviewRepository, never()).save(any());
        }

        @Test
        @DisplayName("rejects reply with empty comment")
        void submitReplyEmptyCommentFails() {
            UUID reviewId = UUID.randomUUID();
            PublicationReviewEntity parentReview = new PublicationReviewEntity();
            parentReview.setId(reviewId);
            parentReview.setPublicationId(PUB_ID);

            when(reviewRepository.findById(reviewId)).thenReturn(Optional.of(parentReview));

            assertThatThrownBy(() ->
                    service.submitReply(reviewId, OTHER_REVIEWER, "Jane", null, ""))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Reply comment cannot be empty");

            verify(reviewRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("updateReply")
    class UpdateReplyTests {

        @Test
        @DisplayName("updates reply comment successfully")
        void updateReplySuccess() {
            UUID replyId = UUID.randomUUID();
            PublicationReviewEntity reply = new PublicationReviewEntity();
            reply.setId(replyId);
            reply.setParentId(UUID.randomUUID()); // is a reply
            reply.setReviewerId(REVIEWER_ID);
            reply.setComment("Old text");

            when(reviewRepository.findByIdAndReviewerId(replyId, REVIEWER_ID))
                    .thenReturn(Optional.of(reply));
            when(reviewRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            PublicationReviewEntity result = service.updateReply(replyId, REVIEWER_ID, "New text");

            assertThat(result.getComment()).isEqualTo("New text");
            verify(reviewRepository).save(any());
        }

        @Test
        @DisplayName("rejects update from wrong user")
        void updateReplyWrongUserFails() {
            UUID replyId = UUID.randomUUID();
            when(reviewRepository.findByIdAndReviewerId(replyId, OTHER_REVIEWER))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                    service.updateReply(replyId, OTHER_REVIEWER, "Hacked!"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Reply not found or not owned by user");

            verify(reviewRepository, never()).save(any());
        }

        @Test
        @DisplayName("rejects update on top-level review (not a reply)")
        void updateReplyOnTopLevelReviewFails() {
            UUID reviewId = UUID.randomUUID();
            PublicationReviewEntity topLevelReview = new PublicationReviewEntity();
            topLevelReview.setId(reviewId);
            topLevelReview.setParentId(null); // top-level, not a reply
            topLevelReview.setReviewerId(REVIEWER_ID);
            topLevelReview.setComment("Original review");

            when(reviewRepository.findByIdAndReviewerId(reviewId, REVIEWER_ID))
                    .thenReturn(Optional.of(topLevelReview));

            assertThatThrownBy(() ->
                    service.updateReply(reviewId, REVIEWER_ID, "Updated text"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("This is a top-level review, not a reply");

            verify(reviewRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("deleteReply")
    class DeleteReplyTests {

        @Test
        @DisplayName("deletes own reply successfully")
        void deleteReplySuccess() {
            UUID replyId = UUID.randomUUID();
            PublicationReviewEntity reply = new PublicationReviewEntity();
            reply.setId(replyId);
            reply.setParentId(UUID.randomUUID()); // is a reply
            reply.setReviewerId(REVIEWER_ID);

            when(reviewRepository.findByIdAndReviewerId(replyId, REVIEWER_ID))
                    .thenReturn(Optional.of(reply));

            service.deleteReply(replyId, REVIEWER_ID);

            verify(reviewRepository).delete(reply);
        }

        @Test
        @DisplayName("rejects delete from wrong user")
        void deleteReplyWrongUserFails() {
            UUID replyId = UUID.randomUUID();
            when(reviewRepository.findByIdAndReviewerId(replyId, OTHER_REVIEWER))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                    service.deleteReply(replyId, OTHER_REVIEWER))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Reply not found or not owned by user");

            verify(reviewRepository, never()).delete(any());
        }

        @Test
        @DisplayName("rejects delete on top-level review (not a reply)")
        void deleteReplyOnTopLevelReviewFails() {
            UUID reviewId = UUID.randomUUID();
            PublicationReviewEntity topLevelReview = new PublicationReviewEntity();
            topLevelReview.setId(reviewId);
            topLevelReview.setParentId(null); // top-level, not a reply
            topLevelReview.setReviewerId(REVIEWER_ID);

            when(reviewRepository.findByIdAndReviewerId(reviewId, REVIEWER_ID))
                    .thenReturn(Optional.of(topLevelReview));

            assertThatThrownBy(() ->
                    service.deleteReply(reviewId, REVIEWER_ID))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("This is a top-level review, not a reply");

            verify(reviewRepository, never()).delete(any());
        }
    }

    @Nested
    @DisplayName("getReplyCountsBatch")
    class GetReplyCountsBatchTests {

        @Test
        @DisplayName("returns correct batch counts")
        void batchCountsCorrect() {
            UUID id1 = UUID.randomUUID();
            UUID id2 = UUID.randomUUID();
            UUID id3 = UUID.randomUUID();

            when(reviewRepository.countRepliesByParentIds(List.of(id1, id2, id3)))
                    .thenReturn(List.of(
                            new Object[]{id1, 3L},
                            new Object[]{id3, 1L}
                    ));

            Map<UUID, Long> result = service.getReplyCountsBatch(List.of(id1, id2, id3));

            assertThat(result).hasSize(2);
            assertThat(result.get(id1)).isEqualTo(3L);
            assertThat(result.get(id2)).isNull(); // no replies
            assertThat(result.get(id3)).isEqualTo(1L);
        }

        @Test
        @DisplayName("returns empty map for empty input")
        void emptyInputReturnsEmptyMap() {
            Map<UUID, Long> result = service.getReplyCountsBatch(List.of());
            assertThat(result).isEmpty();
            verify(reviewRepository, never()).countRepliesByParentIds(any());
        }
    }

    @Nested
    @DisplayName("getReplies")
    class GetRepliesTests {

        @Test
        @DisplayName("returns replies sorted by createdAt ASC")
        void returnsRepliesSorted() {
            UUID parentId = UUID.randomUUID();
            PublicationReviewEntity r1 = new PublicationReviewEntity();
            r1.setId(UUID.randomUUID());
            r1.setComment("First");
            PublicationReviewEntity r2 = new PublicationReviewEntity();
            r2.setId(UUID.randomUUID());
            r2.setComment("Second");

            when(reviewRepository.findByParentIdOrderByCreatedAtAsc(parentId))
                    .thenReturn(List.of(r1, r2));

            List<PublicationReviewEntity> result = service.getReplies(parentId);

            assertThat(result).hasSize(2);
            assertThat(result.get(0).getComment()).isEqualTo("First");
            assertThat(result.get(1).getComment()).isEqualTo("Second");
        }
    }
}
