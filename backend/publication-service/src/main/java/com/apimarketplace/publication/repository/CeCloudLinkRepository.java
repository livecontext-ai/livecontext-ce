package com.apimarketplace.publication.repository;

import com.apimarketplace.publication.domain.CeCloudLinkEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface CeCloudLinkRepository extends JpaRepository<CeCloudLinkEntity, UUID> {

    Optional<CeCloudLinkEntity> findByTenantId(Long tenantId);

    void deleteByTenantId(Long tenantId);

    boolean existsByLlmSource(String llmSource);

    /**
     * THE active cloud link of this CE install, install-globally - the most
     * recently linked registered link (registeredAt != null), regardless of
     * llmSource. Used by the model-catalog bundle sync, which runs once per
     * install (not per tenant): being cloud-linked is what entitles the install
     * to catalog updates, independent of the per-tenant CLOUD/BYOK inference
     * choice. Empty when this install has no registered link.
     */
    Optional<CeCloudLinkEntity> findFirstByRegisteredAtNotNullOrderByLinkedAtDesc();

    /**
     * The token columns of a link, read as values straight from the database. A scalar
     * projection is never served from a persistence context, so a caller that holds an
     * older entity instance of the row (open-session-in-view, a scheduler's findAll) still
     * reads the refresh token the last refresh stored.
     */
    @Query("SELECT new com.apimarketplace.publication.repository.CloudLinkTokens("
            + "l.encryptedRefreshToken, l.cachedAccessToken, l.tokenExpiresAt) "
            + "FROM CeCloudLinkEntity l WHERE l.tenantId = :tenantId")
    Optional<CloudLinkTokens> findTokensByTenantId(@Param("tenantId") Long tenantId);

    /**
     * The ONLY writer of the token columns after the link's insert (they are
     * {@code updatable = false} on the entity, so no save() can put a spent refresh token back).
     */
    @Modifying
    @Transactional
    @Query("UPDATE CeCloudLinkEntity l SET l.encryptedRefreshToken = :encryptedRefreshToken, "
            + "l.cachedAccessToken = :cachedAccessToken, l.tokenExpiresAt = :tokenExpiresAt, "
            + "l.lastUsedAt = :lastUsedAt WHERE l.tenantId = :tenantId")
    int updateTokens(@Param("tenantId") Long tenantId,
                     @Param("encryptedRefreshToken") String encryptedRefreshToken,
                     @Param("cachedAccessToken") String cachedAccessToken,
                     @Param("tokenExpiresAt") Instant tokenExpiresAt,
                     @Param("lastUsedAt") Instant lastUsedAt);

    /**
     * Drops the cached access token (the cloud revoked the link). The refresh token is kept: a
     * reconnect replaces the whole row anyway.
     */
    @Modifying
    @Transactional
    @Query("UPDATE CeCloudLinkEntity l SET l.cachedAccessToken = NULL, l.tokenExpiresAt = NULL "
            + "WHERE l.tenantId = :tenantId")
    int clearCachedAccessToken(@Param("tenantId") Long tenantId);

    /** Stamps lastUsedAt without loading (and later saving) an entity instance. */
    @Modifying
    @Transactional
    @Query("UPDATE CeCloudLinkEntity l SET l.lastUsedAt = :lastUsedAt WHERE l.tenantId = :tenantId")
    int touchLastUsedAt(@Param("tenantId") Long tenantId, @Param("lastUsedAt") Instant lastUsedAt);
}
