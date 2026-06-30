package com.apimarketplace.publication.screening;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ImageScreeningDecisionRepository
        extends JpaRepository<ImageScreeningDecisionEntity, Long> {

    /** Read-side: every decision logged against a publication, newest first. */
    List<ImageScreeningDecisionEntity> findByPublicationIdOrderByDecidedAtDesc(UUID publicationId);
}
