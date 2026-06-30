package com.apimarketplace.publication.service;

import com.apimarketplace.publication.domain.PublicationReviewEntity;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity;
import com.apimarketplace.publication.repository.PublicationReviewRepository;
import com.apimarketplace.publication.repository.WorkflowPublicationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Service for publication reviews and replies.
 * Handles CRUD + denormalized stats recomputation on the publication entity.
 * Replies are stored in the same table with a non-null parentId.
 */
@Service
@Transactional
public class PublicationReviewService {

    private static final Logger logger = LoggerFactory.getLogger(PublicationReviewService.class);

    private static final int MAX_COMMENT_LENGTH = 2000;

    private final PublicationReviewRepository reviewRepository;
    private final WorkflowPublicationRepository publicationRepository;

    public PublicationReviewService(PublicationReviewRepository reviewRepository,
                                    WorkflowPublicationRepository publicationRepository) {
        this.reviewRepository = reviewRepository;
        this.publicationRepository = publicationRepository;
    }

    // ========================================================================
    // Top-level reviews
    // ========================================================================

    /**
     * Get paginated top-level reviews for a publication, newest first.
     */
    @Transactional(readOnly = true)
    public Page<PublicationReviewEntity> getReviews(UUID publicationId, int page, int size) {
        return getReviews(publicationId, page, size, false);
    }

    /**
     * Get paginated top-level reviews for a publication, newest first.
     *
     * @param onlyWithComment when {@code true}, excludes rating-only entries
     *                        (votes without text). Used by the Comments tab so it
     *                        only shows entries that actually carry a comment.
     *                        The Info tab continues to read {@code averageRating}
     *                        and {@code reviewCount} which are computed over all
     *                        ratings, regardless of comment presence.
     */
    @Transactional(readOnly = true)
    public Page<PublicationReviewEntity> getReviews(UUID publicationId, int page, int size, boolean onlyWithComment) {
        PageRequest pageable = PageRequest.of(page, size);
        return onlyWithComment
                ? reviewRepository.findTopLevelWithCommentByPublicationId(publicationId, pageable)
                : reviewRepository.findTopLevelByPublicationId(publicationId, pageable);
    }

    /**
     * Count of top-level rows that carry a non-empty comment. Powers the Comments
     * tab badge on the publication panel. Distinct from {@code WorkflowPublicationEntity.reviewCount}
     * (which counts votes - i.e. rows with a non-null rating).
     */
    @Transactional(readOnly = true)
    public int countComments(UUID publicationId) {
        return reviewRepository.countTopLevelWithCommentByPublicationId(publicationId);
    }

    /**
     * Get the current user's top-level review for a publication.
     */
    @Transactional(readOnly = true)
    public Optional<PublicationReviewEntity> getMyReview(UUID publicationId, String reviewerId) {
        return reviewRepository.findTopLevelByPublicationIdAndReviewerId(publicationId, reviewerId);
    }

    /**
     * Submit or update a review (upsert by publication_id + reviewer_id for top-level).
     * Rating and comment are independent - either or both can be provided.
     * Publisher cannot review their own publication.
     */
    public PublicationReviewEntity submitReview(UUID publicationId, String reviewerId,
                                                String reviewerName, String reviewerAvatarUrl,
                                                Short rating, String comment) {
        WorkflowPublicationEntity publication = publicationRepository.findById(publicationId)
                .orElseThrow(() -> new IllegalArgumentException("Publication not found: " + publicationId));

        if (reviewerId.equals(publication.getPublisherId())) {
            throw new IllegalArgumentException("Cannot review your own publication");
        }

        if (rating != null && (rating < 1 || rating > 5)) {
            throw new IllegalArgumentException("Rating must be between 1 and 5");
        }

        String sanitizedComment = truncateComment(comment);

        // Upsert: find existing top-level or create new
        PublicationReviewEntity review = reviewRepository
                .findTopLevelByPublicationIdAndReviewerId(publicationId, reviewerId)
                .orElseGet(() -> {
                    PublicationReviewEntity newReview = new PublicationReviewEntity();
                    newReview.setPublicationId(publicationId);
                    newReview.setReviewerId(reviewerId);
                    return newReview;
                });

        review.setReviewerName(reviewerName);
        review.setReviewerAvatarUrl(reviewerAvatarUrl);
        // Only update rating if provided (allows comment-only updates)
        if (rating != null) {
            review.setRating(rating);
        }
        // Only update comment if provided (allows rating-only updates)
        if (sanitizedComment != null) {
            review.setComment(sanitizedComment);
        }

        PublicationReviewEntity saved = reviewRepository.save(review);
        recomputeStats(publicationId);

        logger.info("Review submitted for publication {} by {}", publicationId, reviewerId);
        return saved;
    }

    /**
     * Delete the current user's top-level review (cascades replies via DB FK).
     */
    public void deleteReview(UUID publicationId, String reviewerId) {
        PublicationReviewEntity review = reviewRepository
                .findTopLevelByPublicationIdAndReviewerId(publicationId, reviewerId)
                .orElseThrow(() -> new IllegalArgumentException("No review found to delete"));

        reviewRepository.delete(review);
        recomputeStats(publicationId);

        logger.info("Review deleted for publication {} by {}", publicationId, reviewerId);
    }

    /**
     * Remove ONLY the comment from the current user's top-level review, keeping
     * the rating untouched. The rating and the comment are independent concerns
     * (the Info tab owns the rating, the Comments tab owns the comment), so a
     * user deleting their comment must not also lose their star rating.
     *
     * <p>If the review carried no rating (a comment-only entry), there is nothing
     * left to keep once the comment is gone, so the row is deleted entirely.
     *
     * @return the surviving review with its comment cleared, or {@code null} when
     *         the row was comment-only and therefore removed.
     */
    public PublicationReviewEntity clearComment(UUID publicationId, String reviewerId) {
        PublicationReviewEntity review = reviewRepository
                .findTopLevelByPublicationIdAndReviewerId(publicationId, reviewerId)
                .orElseThrow(() -> new IllegalArgumentException("No review found to update"));

        if (review.getRating() != null) {
            // Keep the rating: only the comment goes. No stats recompute needed -
            // the comment does not feed averageRating / reviewCount.
            review.setComment(null);
            PublicationReviewEntity saved = reviewRepository.save(review);
            logger.info("Comment cleared (rating kept) for publication {} by {}", publicationId, reviewerId);
            return saved;
        }

        // Comment-only row: nothing left to keep, drop it.
        reviewRepository.delete(review);
        recomputeStats(publicationId);
        logger.info("Comment-only review removed for publication {} by {}", publicationId, reviewerId);
        return null;
    }

    /**
     * Remove ONLY the rating from the current user's top-level review, keeping the
     * comment untouched. Symmetric to {@link #clearComment(UUID, String)} - lets a
     * user retract their star rating from the Info tab without deleting a comment
     * they posted in the Comments tab.
     *
     * <p>If the review carried no comment (a rating-only vote), there is nothing
     * left to keep, so the row is deleted entirely.
     *
     * @return the surviving review with its rating cleared, or {@code null} when
     *         the row was rating-only and therefore removed.
     */
    public PublicationReviewEntity clearRating(UUID publicationId, String reviewerId) {
        PublicationReviewEntity review = reviewRepository
                .findTopLevelByPublicationIdAndReviewerId(publicationId, reviewerId)
                .orElseThrow(() -> new IllegalArgumentException("No review found to update"));

        boolean hasComment = review.getComment() != null && !review.getComment().trim().isEmpty();
        if (hasComment) {
            // Keep the comment: only the rating goes. Stats recompute IS needed -
            // dropping the rating changes the vote count and average.
            review.setRating(null);
            PublicationReviewEntity saved = reviewRepository.save(review);
            recomputeStats(publicationId);
            logger.info("Rating cleared (comment kept) for publication {} by {}", publicationId, reviewerId);
            return saved;
        }

        // Rating-only row: nothing left to keep, drop it.
        reviewRepository.delete(review);
        recomputeStats(publicationId);
        logger.info("Rating-only review removed for publication {} by {}", publicationId, reviewerId);
        return null;
    }

    /**
     * Delete all reviews for a publication (called from unpublish cleanup).
     */
    public void deleteAllReviews(UUID publicationId) {
        reviewRepository.deleteByPublicationId(publicationId);
        logger.info("All reviews deleted for publication {}", publicationId);
    }

    // ========================================================================
    // Replies
    // ========================================================================

    /**
     * Get replies for a given top-level review, sorted by createdAt ASC.
     */
    @Transactional(readOnly = true)
    public List<PublicationReviewEntity> getReplies(UUID parentReviewId) {
        return reviewRepository.findByParentIdOrderByCreatedAtAsc(parentReviewId);
    }

    /**
     * Batch-load reply counts for a list of top-level review IDs.
     * Returns a map of reviewId -> replyCount.
     */
    @Transactional(readOnly = true)
    public Map<UUID, Long> getReplyCountsBatch(List<UUID> parentIds) {
        if (parentIds == null || parentIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<UUID, Long> result = new HashMap<>();
        List<Object[]> rows = reviewRepository.countRepliesByParentIds(parentIds);
        for (Object[] row : rows) {
            result.put((UUID) row[0], (Long) row[1]);
        }
        return result;
    }

    /**
     * Submit a reply to a top-level review.
     * Replies cannot be nested (no reply-to-reply).
     */
    public PublicationReviewEntity submitReply(UUID parentReviewId, String reviewerId,
                                               String reviewerName, String reviewerAvatarUrl,
                                               String comment) {
        PublicationReviewEntity parentReview = reviewRepository.findById(parentReviewId)
                .orElseThrow(() -> new IllegalArgumentException("Parent review not found: " + parentReviewId));

        if (parentReview.isReply()) {
            throw new IllegalArgumentException("Cannot reply to a reply");
        }

        if (comment == null || comment.trim().isEmpty()) {
            throw new IllegalArgumentException("Reply comment cannot be empty");
        }

        String sanitizedComment = truncateComment(comment.trim());

        PublicationReviewEntity reply = new PublicationReviewEntity();
        reply.setPublicationId(parentReview.getPublicationId());
        reply.setParentId(parentReviewId);
        reply.setReviewerId(reviewerId);
        reply.setReviewerName(reviewerName);
        reply.setReviewerAvatarUrl(reviewerAvatarUrl);
        reply.setRating(null); // replies have no rating
        reply.setComment(sanitizedComment);

        PublicationReviewEntity saved = reviewRepository.save(reply);
        logger.info("Reply submitted to review {} by {}", parentReviewId, reviewerId);
        return saved;
    }

    /**
     * Update own reply's comment text.
     */
    public PublicationReviewEntity updateReply(UUID replyId, String reviewerId, String comment) {
        PublicationReviewEntity reply = reviewRepository.findByIdAndReviewerId(replyId, reviewerId)
                .orElseThrow(() -> new IllegalArgumentException("Reply not found or not owned by user"));

        if (!reply.isReply()) {
            throw new IllegalArgumentException("This is a top-level review, not a reply");
        }

        if (comment == null || comment.trim().isEmpty()) {
            throw new IllegalArgumentException("Reply comment cannot be empty");
        }

        reply.setComment(truncateComment(comment.trim()));
        PublicationReviewEntity saved = reviewRepository.save(reply);
        logger.info("Reply {} updated by {}", replyId, reviewerId);
        return saved;
    }

    /**
     * Delete own reply.
     */
    public void deleteReply(UUID replyId, String reviewerId) {
        PublicationReviewEntity reply = reviewRepository.findByIdAndReviewerId(replyId, reviewerId)
                .orElseThrow(() -> new IllegalArgumentException("Reply not found or not owned by user"));

        if (!reply.isReply()) {
            throw new IllegalArgumentException("This is a top-level review, not a reply");
        }

        reviewRepository.delete(reply);
        logger.info("Reply {} deleted by {}", replyId, reviewerId);
    }

    // ========================================================================
    // Stats
    // ========================================================================

    /**
     * Recompute average_rating and review_count using only top-level reviews.
     */
    private void recomputeStats(UUID publicationId) {
        int count = reviewRepository.countTopLevelByPublicationId(publicationId);
        double average = count > 0 ? reviewRepository.computeAverageRating(publicationId) : 0.0;
        publicationRepository.updateReviewStats(publicationId, average, count);
    }

    private String truncateComment(String comment) {
        if (comment != null && comment.length() > MAX_COMMENT_LENGTH) {
            return comment.substring(0, MAX_COMMENT_LENGTH);
        }
        return comment;
    }
}
