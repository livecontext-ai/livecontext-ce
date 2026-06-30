package com.apimarketplace.orchestrator.repository;

import com.apimarketplace.orchestrator.domain.WorkflowCategoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for workflow categories.
 */
@Repository
public interface WorkflowCategoryRepository extends JpaRepository<WorkflowCategoryEntity, UUID> {

    /**
     * Find category by slug
     */
    Optional<WorkflowCategoryEntity> findBySlug(String slug);

    /**
     * Find all active categories ordered by display order
     */
    List<WorkflowCategoryEntity> findByIsActiveTrueOrderByDisplayOrderAsc();

    /**
     * Find all categories ordered by display order
     */
    List<WorkflowCategoryEntity> findAllByOrderByDisplayOrderAsc();

    /**
     * Check if slug exists
     */
    boolean existsBySlug(String slug);
}
