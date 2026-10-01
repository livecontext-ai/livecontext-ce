package com.apimarketplace.auth.repository;

import com.apimarketplace.auth.domain.RewardCode;
import com.apimarketplace.auth.domain.RewardProgram;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.List;

public interface RewardCodeRepository extends JpaRepository<RewardCode, Long> {

    /** Codes are matched case-insensitively (unique on UPPER(code), see V366). */
    Optional<RewardCode> findByCodeIgnoreCase(String code);

    long countByCodeStartingWithIgnoreCase(String prefix);

    /** Held until the issuing transaction commits, so equal names cannot choose the same code. */
    @Query(value = "SELECT 1 FROM pg_advisory_xact_lock(hashtext(:key))", nativeQuery = true)
    Integer lockPersonalOfferCodeName(@Param("key") String key);

    /** The owner's single code for a program (unique on (owner_user_id, program)). */
    Optional<RewardCode> findByOwnerUserIdAndProgram(Long ownerUserId, RewardProgram program);

    Optional<RewardCode> findByRecipientUserIdAndCampaignKey(Long recipientUserId, String campaignKey);

    List<RewardCode> findByRecipientUserIdAndProgram(Long recipientUserId, RewardProgram program);

    /** V549 admin report: partner codes and creator (credit-granting promo) codes, newest first. */
    @Query("""
           SELECT c FROM RewardCode c
           WHERE c.program = com.apimarketplace.auth.domain.RewardProgram.PARTNER
              OR (c.program = com.apimarketplace.auth.domain.RewardProgram.PROMO
                  AND c.benefitKind = com.apimarketplace.auth.domain.BenefitKind.CREDIT_GRANT)
           ORDER BY c.id DESC
           """)
    java.util.List<RewardCode> findPartnerProgramCodes();

    /**
     * The subset of {@code userIds} that own a live PARTNER code (active, inside its validity
     * window) on an enabled account: the official-partner badge. Same shape as the verified
     * badge lookups, one indexed query per rendered list.
     */
    @Query("""
           SELECT DISTINCT c.ownerUserId FROM RewardCode c, User u
           WHERE u.id = c.ownerUserId
             AND u.enabled = true
             AND c.program = com.apimarketplace.auth.domain.RewardProgram.PARTNER
             AND c.active = true
             AND c.validFrom <= :now
             AND (c.validUntil IS NULL OR c.validUntil >= :now)
             AND c.ownerUserId IN :userIds
           """)
    java.util.List<Long> findLivePartnerOwnerIdsIn(@Param("userIds") java.util.Collection<Long> userIds,
                                                   @Param("now") java.time.Instant now);

    /**
     * Atomically reserve one redemption slot. Race-safe guard that the code is
     * active and inside its validity window, re-checked against DB {@code now()}
     * inside the caller's transaction. A GLOBAL cap blocks past {@code cap_limit}
     * (returns 0); NONE and PER_OWNER_SOFT never block here (a soft cap is handled
     * by the caller marking the overflow TRACK_ONLY, it is not a hard limit).
     * Returns rows affected (1 = reserved, 0 = not redeemable). Paired with the
     * {@code uq_reward_redemption_user_code} constraint so a concurrent
     * double-redeem still rolls the whole transaction back (reservation included).
     */
    @Modifying(clearAutomatically = true)
    @Query(value = """
        UPDATE auth.reward_code
           SET current_redemptions = current_redemptions + 1
         WHERE id = :id
           AND active = TRUE
           AND now() >= valid_from
           AND (valid_until IS NULL OR now() <= valid_until)
           AND (cap_scope <> 'GLOBAL' OR cap_limit IS NULL OR current_redemptions < cap_limit)
        """, nativeQuery = true)
    int tryReserveRedemption(@Param("id") Long id);
}
