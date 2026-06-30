package com.apimarketplace.catalog.repository;

import com.apimarketplace.catalog.domain.ToolNameEntity;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for tool names
 */
@Repository
public interface ToolNameRepository extends CrudRepository<ToolNameEntity, UUID> {
    
    /**
     * Find all active tool names ordered by name
     */
    List<ToolNameEntity> findByIsActiveTrueOrderByNameAsc();
    
    /**
     * Find tool names by tool category ID
     */
    List<ToolNameEntity> findByToolCategoryIdAndIsActiveTrueOrderByNameAsc(UUID toolCategoryId);
    
    /**
     * Find tool name by name and tool category ID
     */
    Optional<ToolNameEntity> findByNameAndToolCategoryId(String name, UUID toolCategoryId);
    
    /**
     * Find tool name by slug and tool category ID
     */
    Optional<ToolNameEntity> findBySlugAndToolCategoryId(String slug, UUID toolCategoryId);

    /**
     * Find all tool names by name (can return multiple results from different categories)
     */
    List<ToolNameEntity> findByNameAndIsActiveTrue(String name);
    
    /**
     * Find all tool names by slug (can return multiple results from different categories)
     */
    List<ToolNameEntity> findBySlugAndIsActiveTrue(String slug);
    
    /**
     * Check if tool name exists by name and tool category ID
     */
    boolean existsByNameAndToolCategoryId(String name, UUID toolCategoryId);
    
    /**
     * Check if tool name exists by slug and tool category ID
     */
    boolean existsBySlugAndToolCategoryId(String slug, UUID toolCategoryId);
    
    /**
     * Check if tool name exists by name (in any category)
     */
    boolean existsByName(String name);
    
    /**
     * Check if tool name exists by slug (in any category)
     */
    boolean existsBySlug(String slug);
    
    /**
     * Find tool names by name containing (case insensitive)
     */
    List<ToolNameEntity> findByNameContainingIgnoreCaseAndIsActiveTrue(String name);
    
    // REMOVED: findByMethodAndIsActiveTrueOrderByNameAsc - method field no longer exists in ToolNameEntity
    // REMOVED: findByToolCategoryIdAndMethodAndIsActiveTrueOrderByNameAsc - method field no longer exists in ToolNameEntity

    /**
     * Find tool names by tool category ID and name (case insensitive)
     */
    List<ToolNameEntity> findByToolCategoryIdAndNameIgnoreCase(UUID toolCategoryId, String name);

    /**
     * Find tool names for external use (external or both scopes)
     */
    List<ToolNameEntity> findByRunScopeInAndIsActiveTrueOrderByNameAsc(List<String> runScopes);

    /**
     * Find tool names by run scope and tool category
     */
    List<ToolNameEntity> findByToolCategoryIdAndRunScopeInAndIsActiveTrueOrderByNameAsc(UUID toolCategoryId, List<String> runScopes);

    /**
     * Find tool names that require user credentials
     */
    List<ToolNameEntity> findByRequiresUserCredentialsAndIsActiveTrueOrderByNameAsc(Boolean requiresCredentials);

    /**
     * Find tool names by subcategory ID
     */
    List<ToolNameEntity> findBySubcategoryIdAndIsActiveTrue(UUID subcategoryId);
    
    /**
     * Find tool names by multiple subcategory IDs
     */
    List<ToolNameEntity> findBySubcategoryIdInAndIsActiveTrue(List<UUID> subcategoryIds);
    
    /**
     * Find tool names by subcategory ID ordered by name
     */
    List<ToolNameEntity> findBySubcategoryIdAndIsActiveTrueOrderByNameAsc(UUID subcategoryId);

    /**
     * Find tool names by tool category ID and subcategory ID ordered by name
     */
    List<ToolNameEntity> findByToolCategoryIdAndSubcategoryIdAndIsActiveTrueOrderByNameAsc(UUID toolCategoryId, UUID subcategoryId);

    /**
     * Check if a slug already exists (for unique slug generation)
     */
    boolean existsBySlugStartingWith(String slugPrefix);

    /**
     * Find all slugs matching a prefix (for unique slug generation)
     */
    @Query("SELECT slug FROM tool_names WHERE slug LIKE CONCAT(:prefix, '%')")
    List<String> findSlugsByPrefix(@Param("prefix") String prefix);
}
