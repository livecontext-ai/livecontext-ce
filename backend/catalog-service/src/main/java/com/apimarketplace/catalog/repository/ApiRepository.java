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
     * True when this credential key belongs to anybody other than {@code ownerId}: the ONE
     * ownership predicate behind registration (refuse a taken key), deletion (never remove a row
     * that is not yours) and execution (never resolve a stored secret filed under someone else's
     * key). CASA readiness, LC-002 / LC-057.
     *
     * <p>Ownership is decided by {@code created_by}, never by {@code source}: {@code source} is
     * whatever the submission said it was, while {@code created_by} is set from the gateway
     * identity. The key is foreign when:
     * <ol>
     *   <li>an API row carries it (as {@code platform_credential_name} or {@code icon_slug}) and
     *       was created by somebody else - a shipped integration, a bundle row, another tenant; or</li>
     *   <li>a {@code catalog.credentials} template of that name exists that the owner does not
     *       own. A template is the owner's only when ALL of these hold:
     *       <ul>
     *         <li>it was written by the custom-API path: {@code credential_type} is NULL and its
     *             metadata carries none of the {@code provider}/{@code category}/{@code source}
     *             markers that every migration-seeded (smtp, imap, ssh, sftp, database, llm_*),
     *             imported and bundle-applied template carries - so a NATIVE template, which has
     *             no API row at all, is always foreign;</li>
     *         <li>its {@code customApiOwner} stamp, when present, is the owner (rows written before
     *             the stamp existed have none);</li>
     *         <li>no tool of an API created by somebody else links it.</li>
     *       </ul>
     *       The previous version exempted a template whenever ONE of the owner's custom APIs
     *       carried the key, which was circular: an authType:none custom API named {@code imap}
     *       made the native imap template "its own", so deleting that API deleted the template
     *       for the whole installation and executing it kept the platform fallback.</li>
     * </ol>
     */
    @Query("""
            SELECT EXISTS (
                SELECT 1 FROM catalog.apis a
                WHERE (a.platform_credential_name = :key OR a.icon_slug = :key)
                  AND a.created_by IS DISTINCT FROM :ownerId
            ) OR EXISTS (
                SELECT 1 FROM catalog.credentials c
                WHERE c.credential_name = :key
                  AND NOT (
                        c.credential_type IS NULL
                    AND c.metadata ->> 'provider' IS NULL
                    AND c.metadata ->> 'category' IS NULL
                    AND c.metadata ->> 'source' IS NULL
                    AND COALESCE(c.metadata ->> 'customApiOwner', :ownerId) = :ownerId
                    AND NOT EXISTS (
                        SELECT 1 FROM catalog.tool_credentials tc
                        JOIN catalog.api_tools t ON t.id = tc.api_tool_id
                        JOIN catalog.apis o ON o.id = t.api_id
                        WHERE tc.credential_name = c.credential_name
                          AND o.created_by IS DISTINCT FROM :ownerId
                    )
                  )
            )
            """)
    boolean existsSharedIntegrationWithCredentialKey(@Param("key") String key,
                                                     @Param("ownerId") String ownerId);

    /**
     * The identity-free variant, for a caller with no owner to exempt: ANY API row or template
     * carrying the key is a collision. Fail closed by construction.
     */
    @Query("""
            SELECT EXISTS (
                SELECT 1 FROM catalog.apis a
                WHERE a.platform_credential_name = :key OR a.icon_slug = :key
            ) OR EXISTS (
                SELECT 1 FROM catalog.credentials c WHERE c.credential_name = :key
            )
            """)
    boolean existsShippedIntegrationWithCredentialKey(@Param("key") String key);

    /**
     * Ids of the API rows carrying this credential key that {@code ownerId} did not create. Used to
     * find the shipped integration whose hosts a host-bound credential may be sent to.
     */
    @Query("""
            SELECT a.id FROM catalog.apis a
            WHERE (a.platform_credential_name = :key OR a.icon_slug = :key)
              AND a.created_by IS DISTINCT FROM :ownerId
            """)
    List<UUID> findIdsHoldingCredentialKeyNotCreatedBy(@Param("key") String key,
                                                      @Param("ownerId") String ownerId);
    
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
