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

    List<ApiKey> findByUserIdAndRevokedAtIsNullOrderByCreatedAtDesc(Long userId);

    long countByUserIdAndRevokedAtIsNull(Long userId);

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
