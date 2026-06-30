package com.apimarketplace.catalog.repository;

import com.apimarketplace.catalog.domain.ApiCategoryEntity;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for API category entities
 */
@Repository
public interface ApiCategoryRepository extends CrudRepository<ApiCategoryEntity, UUID> {
    
    /**
     * Find category by name
     */
    Optional<ApiCategoryEntity> findByName(String name);
    
    /**
     * Find category by slug
     */
    Optional<ApiCategoryEntity> findBySlug(String slug);
    
    /**
     * Find all categories ordered by name
     */
    List<ApiCategoryEntity> findAllByOrderByNameAsc();
    
    /**
     * Check if category name exists (excluding current category)
     */
    boolean existsByNameAndIdNot(String name, UUID excludeId);
    
    /**
     * Check if category slug exists (excluding current category)
     */
    boolean existsBySlugAndIdNot(String slug, UUID excludeId);
}
