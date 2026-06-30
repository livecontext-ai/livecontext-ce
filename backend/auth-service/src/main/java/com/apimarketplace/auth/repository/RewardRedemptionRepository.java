package com.apimarketplace.auth.repository;

import com.apimarketplace.auth.domain.RewardProgram;
import com.apimarketplace.auth.domain.RewardRedemption;
import com.apimarketplace.auth.domain.RewardStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface RewardRedemptionRepository extends JpaRepository<RewardRedemption, Long> {

    Optional<RewardRedemption> findByRedeemerUserIdAndRewardCodeId(Long redeemerUserId, Long rewardCodeId);

    /** One REFERRAL redemption per redeemer (partial unique index, see V366). */
    Optional<RewardRedemption> findByRedeemerUserIdAndProgram(Long redeemerUserId, RewardProgram program);

    /** A redeemer's redemptions in a given lifecycle state (drives the conversion hook). */
    List<RewardRedemption> findByRedeemerUserIdAndStatus(Long redeemerUserId, RewardStatus status);

    /** Active redemptions for a user (drives the "your active rewards" UI). */
    List<RewardRedemption> findByRedeemerUserIdAndActiveTrue(Long redeemerUserId);

    /** Redemptions of any of an owner's codes (drives the invite stats). */
    List<RewardRedemption> findByOwnerUserId(Long ownerUserId);

    /** Clawback resolves the converting redemption by its Stripe subscription. */
    Optional<RewardRedemption> findByProviderSubscriptionId(String providerSubscriptionId);

    /** Releaser scan: qualified rewards whose hold has elapsed. */
    List<RewardRedemption> findByStatusAndReleaseDueAtBefore(RewardStatus status, Instant cutoff);

    /** Sweeper scan: attributed redemptions still awaiting conversion. */
    List<RewardRedemption> findByProgramAndStatus(RewardProgram program, RewardStatus status);

    /**
     * Atomic per-node claim: increments {@code free_credits_used} on exactly ONE
     * active, non-expired, non-exhausted PROMO redemption of {@code benefitType}
     * for the user. {@code FOR UPDATE SKIP LOCKED} + {@code LIMIT 1} make concurrent
     * node executions serialize cleanly instead of double-spending the cap. Returns
     * rows affected (1 = claimed, the node is free; 0 = no usable benefit).
     */
    @Modifying(clearAutomatically = true)
    @Query(value = """
        UPDATE auth.reward_redemption
           SET free_credits_used = free_credits_used + 1
         WHERE id = (
             SELECT id FROM auth.reward_redemption
              WHERE redeemer_user_id = :userId
                AND program = 'PROMO'
                AND active = TRUE
                AND benefit_type = :benefitType
                AND benefit_until > now()
                AND free_credits_used < free_credits_cap
              ORDER BY benefit_until DESC
              LIMIT 1
              FOR UPDATE SKIP LOCKED
         )
        """, nativeQuery = true)
    int claimFreeWorkflowNode(@Param("userId") Long userId, @Param("benefitType") String benefitType);

    /**
     * Pessimistic row lock for the conversion lifecycle. The releaser and the
     * refund/dispute handler both lock the row and re-read its status inside the
     * lock, so exactly one terminal transition (RELEASED xor CLAWED_BACK) wins.
     */
    @Query(value = "SELECT * FROM auth.reward_redemption WHERE id = :id FOR UPDATE", nativeQuery = true)
    Optional<RewardRedemption> lockByIdForUpdate(@Param("id") Long id);
}
