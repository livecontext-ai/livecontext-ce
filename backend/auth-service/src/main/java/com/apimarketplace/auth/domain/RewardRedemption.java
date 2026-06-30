package com.apimarketplace.auth.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * The per-redeemer lifecycle row for a {@link RewardCode} (table
 * {@code auth.reward_redemption}).
 *
 * <p>For PROMO this is a terminal {@code GRANTED} free-node counter (the legacy
 * promo benefit). For conversion-gated programs (REFERRAL, PARTNER) it walks
 * {@code PENDING -> QUALIFIED -> RELEASED} (or {@code CLAWED_BACK} on a refund or
 * dispute). {@code TRACK_ONLY} is the soft-cap overflow that waits for manual
 * approval. The snapshotted reward amounts are what a clawback negates.
 *
 * <p>{@code UNIQUE(redeemer_user_id, reward_code_id)} enforces one redemption per
 * code per user and guards a concurrent double-redeem.
 */
@Entity
@Table(name = "reward_redemption")
public class RewardRedemption {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "reward_code_id", nullable = false)
    private Long rewardCodeId;

    @Column(name = "redeemer_user_id", nullable = false)
    private Long redeemerUserId;

    /** Snapshot of the code owner (NULL for PROMO). */
    @Column(name = "owner_user_id")
    private Long ownerUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RewardProgram program;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RewardStatus status;

    // FREE_NODE_COUNTER fields (PROMO only).
    @Column(name = "benefit_type", length = 64)
    private String benefitType;

    @Column(name = "benefit_until")
    private Instant benefitUntil;

    @Column(name = "free_credits_used", nullable = false)
    private int freeCreditsUsed = 0;

    @Column(name = "free_credits_cap", nullable = false)
    private int freeCreditsCap = 0;

    // Conversion / hold / clawback fields.
    @Column(name = "provider_subscription_id", length = 255)
    private String providerSubscriptionId;

    @Column(name = "qualified_at")
    private Instant qualifiedAt;

    @Column(name = "release_due_at")
    private Instant releaseDueAt;

    @Column(name = "released_at")
    private Instant releasedAt;

    @Column(name = "clawed_back_at")
    private Instant clawedBackAt;

    /** Snapshotted at qualification; a clawback negates THIS, never a re-read literal. */
    @Column(name = "redeemer_reward_amount")
    private Integer redeemerRewardAmount;

    @Column(name = "owner_reward_amount")
    private Integer ownerRewardAmount;

    @Column(name = "reward_source_id", length = 512)
    private String rewardSourceId;

    @Column(name = "owner_reward_source_id", length = 512)
    private String ownerRewardSourceId;

    @Column(name = "redeemed_at", nullable = false)
    private Instant redeemedAt;

    @Column(nullable = false)
    private boolean active = true;

    /** True iff the reward is qualified and still inside its anti-refund hold window. */
    public boolean isInHold(Instant now) {
        return status == RewardStatus.QUALIFIED
                && releaseDueAt != null && now.isBefore(releaseDueAt);
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getRewardCodeId() { return rewardCodeId; }
    public void setRewardCodeId(Long rewardCodeId) { this.rewardCodeId = rewardCodeId; }
    public Long getRedeemerUserId() { return redeemerUserId; }
    public void setRedeemerUserId(Long redeemerUserId) { this.redeemerUserId = redeemerUserId; }
    public Long getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(Long ownerUserId) { this.ownerUserId = ownerUserId; }
    public RewardProgram getProgram() { return program; }
    public void setProgram(RewardProgram program) { this.program = program; }
    public RewardStatus getStatus() { return status; }
    public void setStatus(RewardStatus status) { this.status = status; }
    public String getBenefitType() { return benefitType; }
    public void setBenefitType(String benefitType) { this.benefitType = benefitType; }
    public Instant getBenefitUntil() { return benefitUntil; }
    public void setBenefitUntil(Instant benefitUntil) { this.benefitUntil = benefitUntil; }
    public int getFreeCreditsUsed() { return freeCreditsUsed; }
    public void setFreeCreditsUsed(int freeCreditsUsed) { this.freeCreditsUsed = freeCreditsUsed; }
    public int getFreeCreditsCap() { return freeCreditsCap; }
    public void setFreeCreditsCap(int freeCreditsCap) { this.freeCreditsCap = freeCreditsCap; }
    public String getProviderSubscriptionId() { return providerSubscriptionId; }
    public void setProviderSubscriptionId(String providerSubscriptionId) { this.providerSubscriptionId = providerSubscriptionId; }
    public Instant getQualifiedAt() { return qualifiedAt; }
    public void setQualifiedAt(Instant qualifiedAt) { this.qualifiedAt = qualifiedAt; }
    public Instant getReleaseDueAt() { return releaseDueAt; }
    public void setReleaseDueAt(Instant releaseDueAt) { this.releaseDueAt = releaseDueAt; }
    public Instant getReleasedAt() { return releasedAt; }
    public void setReleasedAt(Instant releasedAt) { this.releasedAt = releasedAt; }
    public Instant getClawedBackAt() { return clawedBackAt; }
    public void setClawedBackAt(Instant clawedBackAt) { this.clawedBackAt = clawedBackAt; }
    public Integer getRedeemerRewardAmount() { return redeemerRewardAmount; }
    public void setRedeemerRewardAmount(Integer redeemerRewardAmount) { this.redeemerRewardAmount = redeemerRewardAmount; }
    public Integer getOwnerRewardAmount() { return ownerRewardAmount; }
    public void setOwnerRewardAmount(Integer ownerRewardAmount) { this.ownerRewardAmount = ownerRewardAmount; }
    public String getRewardSourceId() { return rewardSourceId; }
    public void setRewardSourceId(String rewardSourceId) { this.rewardSourceId = rewardSourceId; }
    public String getOwnerRewardSourceId() { return ownerRewardSourceId; }
    public void setOwnerRewardSourceId(String ownerRewardSourceId) { this.ownerRewardSourceId = ownerRewardSourceId; }
    public Instant getRedeemedAt() { return redeemedAt; }
    public void setRedeemedAt(Instant redeemedAt) { this.redeemedAt = redeemedAt; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}
