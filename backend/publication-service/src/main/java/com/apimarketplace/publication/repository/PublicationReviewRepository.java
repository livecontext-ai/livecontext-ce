package com.apimarketplace.publication.repository;

import com.apimarketplace.publication.domain.PublicationReviewEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PublicationReviewRepository extends JpaRepository<PublicationReviewEntity, UUID> {

    @Query("SELECT r FROM PublicationReviewEntity r WHERE r.publicationId = :pubId AND r.parentId IS NULL ORDER BY r.createdAt DESC")
    Page<PublicationReviewEntity> findTopLevelByPublicationId(
            @Param("pubId") UUID publicationId, Pageable pageable);

    /**
     * Top-level rows that carry a non-empty comment. Rating-only votes are excluded -
     * they belong on the Info tab (average + vote count), not in the Comments list.
     */
    @Query("SELECT r FROM PublicationReviewEntity r WHERE r.publicationId = :pubId AND r.parentId IS NULL AND r.comment IS NOT NULL AND TRIM(r.comment) <> '' ORDER BY r.createdAt DESC")
    Page<PublicationReviewEntity> findTopLevelWithCommentByPublicationId(
            @Param("pubId") UUID publicationId, Pageable pageable);

    @Query("SELECT r FROM PublicationReviewEntity r WHERE r.publicationId = :pubId AND r.reviewerId = :reviewerId AND r.parentId IS NULL")
    Optional<PublicationReviewEntity> findTopLevelByPublicationIdAndReviewerId(
            @Param("pubId") UUID publicationId, @Param("reviewerId") String reviewerId);

    @Query("SELECT COUNT(r) FROM PublicationReviewEntity r WHERE r.publicationId = :pubId AND r.parentId IS NULL AND r.rating IS NOT NULL")
    int countTopLevelByPublicationId(@Param("pubId") UUID publicationId);

    /**
     * Count of top-level rows that carry a non-empty comment. Used for the Comments
     * tab badge - distinct from {@link #countTopLevelByPublicationId} (which counts
     * votes for the moyenne).
     */
    @Query("SELECT COUNT(r) FROM PublicationReviewEntity r WHERE r.publicationId = :pubId AND r.parentId IS NULL AND r.comment IS NOT NULL AND TRIM(r.comment) <> ''")
    int countTopLevelWithCommentByPublicationId(@Param("pubId") UUID publicationId);

    @Query("SELECT COALESCE(AVG(r.rating * 1.0), 0.0) FROM PublicationReviewEntity r WHERE r.publicationId = :pubId AND r.parentId IS NULL AND r.rating IS NOT NULL")
    double computeAverageRating(@Param("pubId") UUID publicationId);

    List<PublicationReviewEntity> findByParentIdOrderByCreatedAtAsc(UUID parentId);

    @Query("SELECT r.parentId, COUNT(r) FROM PublicationReviewEntity r WHERE r.parentId IN :parentIds GROUP BY r.parentId")
    List<Object[]> countRepliesByParentIds(@Param("parentIds") List<UUID> parentIds);

    Optional<PublicationReviewEntity> findByIdAndReviewerId(UUID id, String reviewerId);

    @Modifying
    @Query("DELETE FROM PublicationReviewEntity r WHERE r.publicationId = :publicationId")
    void deleteByPublicationId(@Param("publicationId") UUID publicationId);

    Page<PublicationReviewEntity> findByPublicationIdOrderByCreatedAtDesc(UUID publicationId, Pageable pageable);

    Optional<PublicationReviewEntity> findByPublicationIdAndReviewerId(UUID publicationId, String reviewerId);

    int countByPublicationId(UUID publicationId);
}
