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
     * True when this credential key is held by a shipped integration, or by a credential row the
     * given owner does not own.
     *
     * <p>{@code catalog.credentials.credential_name} is UNIQUE with no tenant column, so a custom
     * API registering under an existing key overwrites that template for the whole installation,
     * and deleting the custom API removes it. Registration refuses that collision, and deletion
     * leaves such a row alone; this is the lookup both use.
     *
     * <p>It reads BOTH tables, and each is there for a case the other cannot see.
     * {@code catalog.credentials} is where the rows at risk live, and it is the only table that
     * knows about a NATIVE template, which has no API row at all: {@code imap} and {@code smtp}
     * are both on production under {@code variant='primary'}, exactly what this registration path
     * writes, so an API named "IMAP" would have upserted straight over the template the Email
     * Inbox node depends on. Variant is deliberately NOT part of the predicate: the hazard is the
     * name.
     *
     * <p>It asks TWO questions, because either one alone leaves the key open.
     *
     * <p>The first is whether a SHIPPED api row holds the key. That clause exists because the
     * credential row is exactly what a collision destroys: production carried a Ghost integration
     * whose 41 tools had lost every {@code tool_credentials} link and whose {@code catalog
     * .credentials} row was gone, so a guard that only read that table saw nothing left to
     * protect and waved the next registration through. Matching {@code icon_slug} as well as
     * {@code platform_credential_name} covers a shipped row whose credential name was never set.
     *
     * <p>The second is whether a credential row exists that the CALLER does not own. Scoping the
     * exemption to the caller is the point: keying it on "any custom API" meant the first
     * squatter disabled the guard permanently, for that key, for everybody after them. Your own
     * rows still exempt you, so re-registering your own API, and the delete-then-recreate an
     * update performs, both pass.
     */
    @Query("""
            SELECT EXISTS (
                SELECT 1 FROM catalog.apis shipped
                WHERE shipped.source IS DISTINCT FROM 'custom'
                  AND (shipped.platform_credential_name = :key OR shipped.icon_slug = :key)
            ) OR EXISTS (
                SELECT 1 FROM catalog.credentials c
                WHERE c.credential_name = :key
                  AND NOT EXISTS (
                      SELECT 1 FROM catalog.apis mine
                      WHERE mine.source = 'custom'
                        AND mine.created_by = :ownerId
                        AND mine.platform_credential_name = c.credential_name
                  )
            )
            """)
    boolean existsSharedIntegrationWithCredentialKey(@Param("key") String key,
                                                     @Param("ownerId") String ownerId);

    /**
     * The identity-free half of the check above: does a SHIPPED catalogue integration hold this
     * credential key?
     *
     * <p>Used where there is no caller to scope the ownership exemption to. Asking the full
     * predicate with a null owner would make the ownership half match nothing and therefore fire
     * for every key that has any credential row, which silently turns a fail-OPEN site into a
     * fail-CLOSED one.
     */
    @Query("""
            SELECT EXISTS (
                SELECT 1 FROM catalog.apis shipped
                WHERE shipped.source IS DISTINCT FROM 'custom'
                  AND (shipped.platform_credential_name = :key OR shipped.icon_slug = :key)
            )
            """)
    boolean existsShippedIntegrationWithCredentialKey(@Param("key") String key);
    
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
     * Find API by its platform credential integration name. One icon = one API:
     * {@code platform_credential_name} is unique per API (see
     * scripts/api-migrations/SCHEMA.md), so a single row is expected.
     */
    Optional<ApiEntity> findByPlatformCredentialName(String platformCredentialName);

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
