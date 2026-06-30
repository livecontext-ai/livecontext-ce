package com.apimarketplace.catalog.repository;

import com.apimarketplace.catalog.domain.ApiEntity;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for API entities
 */
@Repository
public interface ApiRepository extends CrudRepository<ApiEntity, UUID> {
    
    /**
     * Find API by name
     */
    Optional<ApiEntity> findByApiName(String apiName);
    
    /**
     * Find APIs by category
     */
    List<ApiEntity> findByCategoryId(UUID categoryId);
    
    /**
     * Find APIs by subcategory
     */
    List<ApiEntity> findBySubcategoryId(UUID subcategoryId);
    
    /**
     * Find active APIs
     */
    List<ApiEntity> findByIsActiveTrue();
    
    /**
     * Find APIs by creator
     */
    List<ApiEntity> findByCreatedBy(String createdBy);

    /**
     * Find APIs by creator and API name
     */
    List<ApiEntity> findByCreatedByAndApiName(String createdBy, String apiName);

    /**
     * Find API by slug
     */
    Optional<ApiEntity> findByApiSlug(String apiSlug);

    /**
     * Find APIs by creator and API slug
     */
    List<ApiEntity> findByCreatedByAndApiSlug(String createdBy, String apiSlug);

    /**
     * Check if API name exists (excluding current API)
     */
    @Query("SELECT COUNT(*) > 0 FROM apis WHERE api_name = :apiName AND id != :excludeId")
    boolean existsByApiNameAndIdNot(@Param("apiName") String apiName, @Param("excludeId") UUID excludeId);

    /**
     * Check if API slug exists (excluding current API)
     */
    @Query("SELECT COUNT(*) > 0 FROM apis WHERE api_slug = :apiSlug AND id != :excludeId")
    boolean existsByApiSlugAndIdNot(@Param("apiSlug") String apiSlug, @Param("excludeId") UUID excludeId);
    
    /**
     * Find APIs by name (case-insensitive contains)
     */
    @Query("SELECT * FROM apis WHERE LOWER(api_name) LIKE LOWER(CONCAT('%', :name, '%'))")
    List<ApiEntity> findByApiNameContainingIgnoreCase(@Param("name") String name);

    /**
     * Find custom APIs created by a specific tenant.
     */
    @Query("""
        SELECT *
          FROM apis
         WHERE source = 'custom'
           AND is_active = true
           AND (
                (:organizationId IS NOT NULL AND :organizationId <> '' AND organization_id = :organizationId)
                OR ((:organizationId IS NULL OR :organizationId = '') AND created_by = :tenantId AND organization_id IS NULL)
           )
         ORDER BY created_at DESC
        """)
    List<ApiEntity> findCustomApisInScope(
            @Param("tenantId") String tenantId,
            @Param("organizationId") String organizationId);

    /**
     * Find APIs with their tools count
     */
    @Query("""
        SELECT a.*, COUNT(at.id) as tools_count
        FROM apis a
        LEFT JOIN api_tools at ON a.id = at.api_id
        WHERE a.is_active = true
        GROUP BY a.id
        ORDER BY a.created_at DESC
        """)
    List<ApiWithToolsCount> findApisWithToolsCount();

    /**
     * Record for API with tools count
     */
    record ApiWithToolsCount(
        UUID id, String apiName, String description, String baseUrl,
        UUID categoryId, UUID subcategoryId, Boolean isActive, String createdBy,
        Long toolsCount
    ) {}

}
