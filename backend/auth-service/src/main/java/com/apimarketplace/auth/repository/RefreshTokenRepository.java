package com.apimarketplace.auth.repository;

import com.apimarketplace.auth.domain.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Repository for refresh token management in embedded auth mode (CE).
 */
@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * Revoke all active refresh tokens for a user (e.g., on password change or logout-all).
     */
    @Modifying
    @Query("UPDATE RefreshToken rt SET rt.revoked = true, rt.revokedAt = :now " +
            "WHERE rt.user.id = :userId AND rt.revoked = false")
    int revokeAllByUserId(@Param("userId") Long userId, @Param("now") LocalDateTime now);

    /**
     * Count active (non-revoked, non-expired) tokens for a user.
     */
    @Query("SELECT COUNT(rt) FROM RefreshToken rt " +
            "WHERE rt.user.id = :userId AND rt.revoked = false AND rt.expiresAt > :now")
    long countActiveByUserId(@Param("userId") Long userId, @Param("now") LocalDateTime now);

    /**
     * Cleanup expired or revoked tokens older than the given cutoff.
     */
    @Modifying
    @Query("DELETE FROM RefreshToken rt WHERE rt.expiresAt < :cutoff OR (rt.revoked = true AND rt.revokedAt < :cutoff)")
    int deleteExpiredOrRevoked(@Param("cutoff") LocalDateTime cutoff);
}
