package com.apimarketplace.auth.repository;

import com.apimarketplace.auth.domain.PasswordResetToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {

    /** Redemption lookup. The caller hashes the raw token first. */
    Optional<PasswordResetToken> findByTokenHash(String tokenHash);

    /**
     * Rate-limit input: how many tokens this user has been ISSUED since
     * {@code since}. Counts spent ones too, so redeeming a link does not buy a
     * fresh allowance. It does not count requests that never produced a token
     * (rate-limited, no local password, disabled), which is the intent: those
     * cost a SELECT and send no mail, so they are not what the cap is for.
     */
    @Query("SELECT COUNT(t) FROM PasswordResetToken t WHERE t.userId = :userId AND t.createdAt > :since")
    long countByUserSince(@Param("userId") Long userId, @Param("since") LocalDateTime since);

    /**
     * Claims one token, and reports whether THIS caller is the one that got it.
     *
     * <p>The {@code used_at IS NULL} predicate is what makes redemption
     * single-use under concurrency: a second transaction blocks on the row lock,
     * then re-evaluates the predicate against the committed row and updates
     * nothing, so it gets 0 back instead of also succeeding. Setting the field on
     * the entity and saving it cannot do this, because both callers would have
     * read {@code null} before either wrote.
     *
     * @return 1 if this call claimed the token, 0 if it was already spent
     */
    @Modifying
    @Query("UPDATE PasswordResetToken t SET t.usedAt = :now WHERE t.id = :id AND t.usedAt IS NULL")
    int markUsed(@Param("id") Long id, @Param("now") LocalDateTime now);

    /**
     * Burns every live token of a user.
     *
     * <p>Called both when a new one is issued (one live token per user, so an
     * old e-mail cannot still work) and right after a successful reset (a second
     * pending token must not survive the password it was meant to change).
     */
    @Modifying
    @Query("UPDATE PasswordResetToken t SET t.usedAt = :now "
            + "WHERE t.userId = :userId AND t.usedAt IS NULL")
    int invalidateLiveTokens(@Param("userId") Long userId, @Param("now") LocalDateTime now);

    /** Daily housekeeping: rows whose usefulness (redemption and audit window) has passed. */
    @Modifying
    @Query("DELETE FROM PasswordResetToken t WHERE t.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") LocalDateTime cutoff);
}
