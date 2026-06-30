package com.apimarketplace.catalog.repository;

import com.apimarketplace.catalog.domain.ApiToolEntity;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for API tool entities
 */
@Repository
public interface ApiToolRepository extends CrudRepository<ApiToolEntity, UUID> {
    
    /**
     * Find tools by API ID
     */
    List<ApiToolEntity> findByApiId(UUID apiId);

    /**
     * Find active tools by API ID
     */
    List<ApiToolEntity> findByApiIdAndIsActiveTrue(UUID apiId);
    
    /**
     * Find tool by slug
     */
    Optional<ApiToolEntity> findByToolSlug(String toolSlug);

    /**
     * Find all active tools
     */
    List<ApiToolEntity> findByIsActiveTrue();

    /**
     * Find all active, non-deprecated tools - the user-facing list variant of
     * {@link #findByIsActiveTrue()}. Bundle-deprecated tools (V331
     * {@code deprecated_at}) are hidden from lists but remain resolvable by
     * UUID/slug for execution.
     */
    List<ApiToolEntity> findByIsActiveTrueAndDeprecatedAtIsNull();

    /**
     * Find all active tools visible to a tenant (public + tenant's private APIs).
     * Bundle-deprecated rows (tool or parent API) are excluded - this feeds the
     * user-facing catalog list.
     */
    @Query("SELECT at.* FROM catalog.api_tools at JOIN catalog.apis a ON at.api_id = a.id WHERE at.is_active = true AND a.is_active = true AND at.deprecated_at IS NULL AND a.deprecated_at IS NULL AND (a.visibility = 'public' OR a.created_by = :tenantId)")
    List<ApiToolEntity> findActiveVisibleToTenant(@Param("tenantId") String tenantId);

    /**
     * Find tool by API ID and tool slug
     */
    Optional<ApiToolEntity> findByApiIdAndToolSlug(UUID apiId, String toolSlug);
}
