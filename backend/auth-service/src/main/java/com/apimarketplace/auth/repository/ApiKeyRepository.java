package com.apimarketplace.auth.repository;

import com.apimarketplace.auth.domain.ApiKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for named multi API keys (auth.api_keys, V398).
 * Revocation is soft: every read filters {@code revokedAt IS NULL}.
 */
@Repository
public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {

    Optional<ApiKey> findByKeyHashAndRevokedAtIsNull(String keyHash);

    /** Named-key lookup over every hash form the key can be stored under, in one query. */
    List<ApiKey> findByKeyHashInAndRevokedAtIsNull(java.util.Collection<String> keyHashes);

    /**
     * Moves a named key's stored hash to the current HMAC key after a successful lookup under an
     * older form (CASA LC-070). Compare-and-set on the old hash, own transaction.
     */
    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("UPDATE ApiKey k SET k.keyHash = :newHash WHERE k.id = :id AND k.keyHash = :oldHash")
    int rehash(@Param("id") UUID id, @Param("oldHash") String oldHash, @Param("newHash") String newHash);

    List<ApiKey> findByUserIdAndRevokedAtIsNullOrderByCreatedAtDesc(Long userId);

    long countByUserIdAndRevokedAtIsNull(Long userId);

    /**
     * Keys that still count against the per-user cap: not revoked and not expired (CASA LC-054).
     * An expired key cannot authenticate, so it must not block the user from creating its
     * replacement.
     */
    @Query("SELECT COUNT(k) FROM ApiKey k WHERE k.userId = :userId AND k.revokedAt IS NULL "
            + "AND (k.expiresAt IS NULL OR k.expiresAt > :now)")
    long countUsableByUserId(@Param("userId") Long userId, @Param("now") LocalDateTime now);

    /**
     * Best-effort last-used stamp, in its OWN transaction: the resolve path runs
     * {@code Propagation.NOT_SUPPORTED} (see ApiKeyService.resolveByPlaintextKey)
     * and must never fail or be slowed down because of this observability write.
     */
    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("UPDATE ApiKey k SET k.lastUsedAt = :now WHERE k.id = :id")
    int touchLastUsedAt(@Param("id") UUID id, @Param("now") LocalDateTime now);
}
