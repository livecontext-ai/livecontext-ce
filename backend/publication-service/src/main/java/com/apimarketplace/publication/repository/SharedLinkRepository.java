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

    /**
     * Both token columns are encrypted with a random IV (see TokenAtRest), so every lookup goes
     * through the sibling hash column. There is no {@code findByToken} / {@code findByResourceToken}
     * on purpose: a JPQL equality on the encrypted column can never match.
     */
    Optional<SharedLinkEntity> findByTokenHash(String tokenHash);

    Optional<SharedLinkEntity> findByResourceTokenHashAndIsActiveTrue(String resourceTokenHash);

    Optional<SharedLinkEntity> findByResourceIdAndIsActiveTrue(UUID resourceId);

    /**
     * Deactivates by token hash alone, with NO tenant or organization predicate.
     *
     * <p>Intentional, and the reasoning lives at its only caller,
     * {@code SharedLinkService#unregister}: the endpoint behind it is unroutable from the edge
     * and every caller is already authorised on the owning resource. Read that note before
     * adding a scope clause here, because the callers treat a failure as non-blocking and a
     * predicate that misses would silently leave a link shared.
     */
    @Modifying
    @Query("UPDATE SharedLinkEntity s SET s.isActive = false, s.updatedAt = CURRENT_TIMESTAMP WHERE s.resourceTokenHash = :resourceTokenHash")
    int deactivateByResourceTokenHash(@Param("resourceTokenHash") String resourceTokenHash);

    @Modifying
    @Query("UPDATE SharedLinkEntity s SET s.accessCount = s.accessCount + 1, s.lastAccessed = CURRENT_TIMESTAMP WHERE s.id = :id")
    void incrementAccessCountById(@Param("id") UUID id);

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
         + "WHERE s.organizationId = :orgId AND s.resourceTokenHash = :resourceTokenHash "
         + "AND s.isActive = true")
    Optional<SharedLinkEntity> findByOrganizationIdStrictAndResourceTokenHashAndIsActiveTrue(
            @Param("orgId") String orgId, @Param("resourceTokenHash") String resourceTokenHash);

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
     * @deprecated Use {@link #findByOrganizationIdStrictAndResourceTokenHashAndIsActiveTrue(String, String)}.
     */
    @Deprecated
    Optional<SharedLinkEntity> findByTenantIdAndResourceTokenHashAndIsActiveTrue(String tenantId, String resourceTokenHash);

    /**
     * @deprecated Use {@link #findByOrganizationIdStrictAndResourceIdAndIsActiveTrue(String, UUID)}.
     */
    @Deprecated
    Optional<SharedLinkEntity> findByTenantIdAndResourceIdAndIsActiveTrue(String tenantId, UUID resourceId);

    /**
     * READ-ONLY plaintext match for a row written before 2026-09-17 (token in clear, no hash).
     * Native on purpose: a JPQL comparison would convert the parameter through the encrypting
     * converter. Rewrites nothing; the delayed startup backfill does. Gated by the service on
     * {@code PlaintextTokenBackfill.mayHaveLegacyRows}.
     */
    @Query(value = "SELECT * FROM publication.shared_links WHERE token = :plain AND token_hash IS NULL", nativeQuery = true)
    Optional<SharedLinkEntity> findLegacyPlaintext(@Param("plain") String plain);

    /**
     * READ-ONLY plaintext match for a row written before 2026-09-17 (token in clear, no hash).
     * Native on purpose: a JPQL comparison would convert the parameter through the encrypting
     * converter. Rewrites nothing; the delayed startup backfill does. Gated by the service on
     * {@code PlaintextTokenBackfill.mayHaveLegacyRows}.
     */
    @Query(value = "SELECT * FROM publication.shared_links WHERE resource_token = :plain AND resource_token_hash IS NULL", nativeQuery = true)
    Optional<SharedLinkEntity> findLegacyPlaintextResourceToken(@Param("plain") String plain);
}
