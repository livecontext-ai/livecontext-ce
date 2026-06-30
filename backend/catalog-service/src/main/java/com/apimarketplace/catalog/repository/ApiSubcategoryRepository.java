package com.apimarketplace.catalog.repository;

import com.apimarketplace.catalog.domain.ApiSubcategoryEntity;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for API subcategory entities
 */
@Repository
public interface ApiSubcategoryRepository extends CrudRepository<ApiSubcategoryEntity, UUID> {
    
    /**
     * Find subcategories by category ID
     */
    List<ApiSubcategoryEntity> findByCategoryId(UUID categoryId);
    
    /**
     * Find subcategory by name and category ID
     */
    Optional<ApiSubcategoryEntity> findByNameAndCategoryId(String name, UUID categoryId);
    
    /**
     * Find subcategory by slug and category ID
     */
    Optional<ApiSubcategoryEntity> findBySlugAndCategoryId(String slug, UUID categoryId);
    
    /**
     * Find subcategory by slug regardless of category
     */
    Optional<ApiSubcategoryEntity> findBySlug(String slug);

    /**
     * Find subcategories by name ignoring case
     */
    List<ApiSubcategoryEntity> findByNameIgnoreCase(String name);
    
    /**
     * Find all subcategories ordered by name
     */
    List<ApiSubcategoryEntity> findAllByOrderByNameAsc();
    
    /**
     * Check if subcategory name exists in category (excluding current subcategory)
     */
    boolean existsByNameAndCategoryIdAndIdNot(String name, UUID categoryId, UUID excludeId);
    
    /**
     * Check if subcategory slug exists in category (excluding current subcategory)
     */
    boolean existsBySlugAndCategoryIdAndIdNot(String slug, UUID categoryId, UUID excludeId);
}
