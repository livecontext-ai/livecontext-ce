package com.apimarketplace.catalog.repository;

import com.apimarketplace.catalog.domain.ToolCategoryEntity;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for tool categories
 */
@Repository
public interface ToolCategoryRepository extends CrudRepository<ToolCategoryEntity, UUID> {
    
    /**
     * Find all active tool categories ordered by sort order
     */
    List<ToolCategoryEntity> findByIsActiveTrueOrderBySortOrderAsc();
    
    /**
     * Find tool category by name
     */
    Optional<ToolCategoryEntity> findByName(String name);
    
    /**
     * Find tool category by slug
     */
    Optional<ToolCategoryEntity> findBySlug(String slug);
    
    /**
     * Check if tool category exists by name
     */
    boolean existsByName(String name);
    
    /**
     * Check if tool category exists by slug
     */
    boolean existsBySlug(String slug);
    
    /**
     * Find tool categories by name containing (case insensitive)
     */
    List<ToolCategoryEntity> findByNameContainingIgnoreCaseAndIsActiveTrue(String name);
    
    /**
     * Find tool categories by exact name (case insensitive)
     */
    List<ToolCategoryEntity> findByNameIgnoreCase(String name);

    /**
     * Find tool categories by IDs ordered by sort order
     */
    List<ToolCategoryEntity> findByIdInOrderBySortOrderAsc(List<UUID> ids);

}
