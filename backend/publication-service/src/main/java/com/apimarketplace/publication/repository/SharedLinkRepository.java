package com.apimarketplace.publication.repository;

import com.apimarketplace.publication.domain.SharedLinkEntity;
import com.apimarketplace.publication.domain.SharedLinkEntity.ResourceType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Shared link repository.
 *
 * <p>Post-V261, every persisted shared link has a non-null {@code organization_id}
 * (the gateway always injects {@code X-Organization-ID}; personal-workspace users
 * get their personal org UUID). USER_SCOPED resources are keyed by
 * {@code organization_id} per product decision - callers MUST pass a non-null
 * organizationId and route through the {@code *ByOrganizationIdStrict} finders.
 *
 * <p>The public lookups by {@code token} / {@code resourceToken} stay
 * unscoped because the token IS the auth (used by anonymous public-link
 * resolvers).
 */
@Repository
public interface SharedLinkRepository extends JpaRepository<SharedLinkEntity, UUID> {

    // ──────────────────────────────────────────────────────────────────────
    // Token-keyed public lookups (no org scope - token IS the auth)
    // ──────────────────────────────────────────────────────────────────────

    Optional<SharedLinkEntity> findByToken(String token);

    Optional<SharedLinkEntity> findByResourceTokenAndIsActiveTrue(String resourceToken);

    Optional<SharedLinkEntity> findByResourceIdAndIsActiveTrue(UUID resourceId);

    @Modifying
    @Query("UPDATE SharedLinkEntity s SET s.isActive = false, s.updatedAt = CURRENT_TIMESTAMP WHERE s.resourceToken = :resourceToken")
    void deactivateByResourceToken(@Param("resourceToken") String resourceToken);

    @Modifying
    @Query("UPDATE SharedLinkEntity s SET s.accessCount = s.accessCount + 1, s.lastAccessed = CURRENT_TIMESTAMP WHERE s.token = :token")
    void incrementAccessCount(@Param("token") String token);

    // ──────────────────────────────────────────────────────────────────────
    // Org-strict finders (USER_SCOPED isolation - canonical CRUD path)
    // ──────────────────────────────────────────────────────────────────────

    @Query("SELECT s FROM SharedLinkEntity s "
         + "WHERE s.id = :id AND s.organizationId = :orgId")
    Optional<SharedLinkEntity> findByIdAndOrganizationIdStrict(
            @Param("id") UUID id, @Param("orgId") String orgId);

    @Query("SELECT s FROM SharedLinkEntity s "
         + "WHERE s.organizationId = :orgId ORDER BY s.createdAt DESC")
    List<SharedLinkEntity> findByOrganizationIdStrictOrderByCreatedAtDesc(
            @Param("orgId") String orgId);

    @Query("SELECT s FROM SharedLinkEntity s "
         + "WHERE s.organizationId = :orgId AND s.resourceType = :resourceType "
         + "ORDER BY s.createdAt DESC")
    List<SharedLinkEntity> findByOrganizationIdStrictAndResourceTypeOrderByCreatedAtDesc(
            @Param("orgId") String orgId, @Param("resourceType") ResourceType resourceType);

    @Query("SELECT COUNT(s) FROM SharedLinkEntity s "
         + "WHERE s.organizationId = :orgId")
    long countByOrganizationIdStrict(@Param("orgId") String orgId);

    @Query("SELECT COUNT(s) FROM SharedLinkEntity s "
         + "WHERE s.organizationId = :orgId AND s.resourceType = :resourceType")
    long countByOrganizationIdStrictAndResourceType(
            @Param("orgId") String orgId, @Param("resourceType") ResourceType resourceType);

    @Query("SELECT s FROM SharedLinkEntity s "
         + "WHERE s.organizationId = :orgId AND s.resourceToken = :resourceToken "
         + "AND s.isActive = true")
    Optional<SharedLinkEntity> findByOrganizationIdStrictAndResourceTokenAndIsActiveTrue(
            @Param("orgId") String orgId, @Param("resourceToken") String resourceToken);

    @Query("SELECT s FROM SharedLinkEntity s "
         + "WHERE s.organizationId = :orgId AND s.resourceId = :resourceId "
         + "AND s.isActive = true")
    Optional<SharedLinkEntity> findByOrganizationIdStrictAndResourceIdAndIsActiveTrue(
            @Param("orgId") String orgId, @Param("resourceId") UUID resourceId);

    // ──────────────────────────────────────────────────────────────────────
    // Legacy tenant-only finders - kept @Deprecated for the cross-tenant
    // idempotency check inside register() (one source path that still keys
    // on the publisher's tenantId rather than orgId, since the token is
    // global). All other call sites route through the org-strict variants.
    // ──────────────────────────────────────────────────────────────────────

    /**
     * @deprecated Use {@link #findByIdAndOrganizationIdStrict(UUID, String)}.
     */
    @Deprecated
    Optional<SharedLinkEntity> findByIdAndTenantId(UUID id, String tenantId);

    /**
     * @deprecated Use {@link #findByOrganizationIdStrictOrderByCreatedAtDesc(String)}.
     */
    @Deprecated
    List<SharedLinkEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId);

    /**
     * @deprecated Use {@link #findByOrganizationIdStrictAndResourceTypeOrderByCreatedAtDesc(String, ResourceType)}.
     */
    @Deprecated
    List<SharedLinkEntity> findByTenantIdAndResourceTypeOrderByCreatedAtDesc(String tenantId, ResourceType resourceType);

    /**
     * @deprecated Use {@link #countByOrganizationIdStrict(String)}.
     */
    @Deprecated
    long countByTenantId(String tenantId);

    /**
     * @deprecated Use {@link #countByOrganizationIdStrictAndResourceType(String, ResourceType)}.
     */
    @Deprecated
    long countByTenantIdAndResourceType(String tenantId, ResourceType resourceType);

    /**
     * @deprecated Use {@link #findByOrganizationIdStrictAndResourceTokenAndIsActiveTrue(String, String)}.
     */
    @Deprecated
    Optional<SharedLinkEntity> findByTenantIdAndResourceTokenAndIsActiveTrue(String tenantId, String resourceToken);

    /**
     * @deprecated Use {@link #findByOrganizationIdStrictAndResourceIdAndIsActiveTrue(String, UUID)}.
     */
    @Deprecated
    Optional<SharedLinkEntity> findByTenantIdAndResourceIdAndIsActiveTrue(String tenantId, UUID resourceId);
}
