package com.apimarketplace.publication.repository;

import com.apimarketplace.publication.domain.ExperienceParticipationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Repository for experience participation tracking.
 */
@Repository
public interface ExperienceParticipationRepository extends JpaRepository<ExperienceParticipationEntity, Long> {

    /**
     * Check if a user has already participated in an experience today.
     */
    boolean existsByPublicationIdAndUserIdAndParticipatedAt(UUID publicationId, String userId, LocalDate date);

    /**
     * Count total participations for an experience on a given day (for stats).
     */
    long countByPublicationIdAndParticipatedAt(UUID publicationId, LocalDate date);

    /**
     * Count total participations for an experience (all time).
     */
    long countByPublicationId(UUID publicationId);
}
