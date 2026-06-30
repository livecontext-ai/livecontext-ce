package com.apimarketplace.auth.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * A configurable reward-code program template (table {@code auth.reward_code}).
 *
 * <p>One row is exactly one {@link RewardProgram}. The benefit/reward policy
 * columns express PROMO, REFERRAL, and PARTNER through configuration; DB CHECK
 * constraints (see V366) keep illegal combinations unrepresentable. PROMO codes
 * are global marketing codes (no owner); REFERRAL/PARTNER codes are owned and
 * grant the owner a reward when a redeemer converts to a paid subscription.
 */
@Entity
@Table(name = "reward_code")
public class RewardCode {

    /** Benefit-type discriminator carried onto a free-node redemption row. */
    public static final String BENEFIT_WORKFLOW_NODE_FREE = "WORKFLOW_NODE_FREE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RewardProgram program;

    /** NULL for PROMO; the earning account for REFERRAL (self) and PARTNER. */
    @Column(name = "owner_user_id")
    private Long ownerUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "benefit_kind", nullable = false, length = 24)
    private BenefitKind benefitKind;

    @Column(name = "benefit_amount", nullable = false)
    private int benefitAmount = 0;

    @Column(name = "benefit_duration_days", nullable = false)
    private int benefitDurationDays = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "benefit_trigger", nullable = false, length = 16)
    private BenefitTrigger benefitTrigger;

    @Enumerated(EnumType.STRING)
    @Column(name = "owner_reward_kind", nullable = false, length = 16)
    private OwnerRewardKind ownerRewardKind = OwnerRewardKind.NONE;

    @Column(name = "owner_reward_amount", nullable = false)
    private int ownerRewardAmount = 0;

    @Column(name = "hold_days", nullable = false)
    private int holdDays = 0;

    @Column(name = "clawback_enabled", nullable = false)
    private boolean clawbackEnabled = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "cap_scope", nullable = false, length = 16)
    private CapScope capScope = CapScope.NONE;

    /** NULL = uncapped. */
    @Column(name = "cap_limit")
    private Integer capLimit;

    @Column(name = "current_redemptions", nullable = false)
    private int currentRedemptions = 0;

    // Partner payout seam (inert in v1).
    @Column(name = "payout_kind", length = 24)
    private String payoutKind;

    @Column(name = "payout_bps")
    private Integer payoutBps;

    @Column(name = "payout_currency", length = 3)
    private String payoutCurrency;

    @Column(name = "payout_external_account_id", length = 255)
    private String payoutExternalAccountId;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "valid_from", nullable = false)
    private Instant validFrom;

    /** NULL = no expiry (the referral default). */
    @Column(name = "valid_until")
    private Instant validUntil;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    /** True iff the code is active and {@code now} is inside its validity window. */
    public boolean isRedeemableAt(Instant now) {
        return active
                && validFrom != null && !now.isBefore(validFrom)
                && (validUntil == null || !now.isAfter(validUntil));
    }

    /** True iff a GLOBAL cap exists and is reached (a soft or absent cap never exhausts). */
    public boolean isExhausted() {
        return capScope == CapScope.GLOBAL && capLimit != null && currentRedemptions >= capLimit;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public RewardProgram getProgram() { return program; }
    public void setProgram(RewardProgram program) { this.program = program; }
    public Long getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(Long ownerUserId) { this.ownerUserId = ownerUserId; }
    public BenefitKind getBenefitKind() { return benefitKind; }
    public void setBenefitKind(BenefitKind benefitKind) { this.benefitKind = benefitKind; }
    public int getBenefitAmount() { return benefitAmount; }
    public void setBenefitAmount(int benefitAmount) { this.benefitAmount = benefitAmount; }
    public int getBenefitDurationDays() { return benefitDurationDays; }
    public void setBenefitDurationDays(int benefitDurationDays) { this.benefitDurationDays = benefitDurationDays; }
    public BenefitTrigger getBenefitTrigger() { return benefitTrigger; }
    public void setBenefitTrigger(BenefitTrigger benefitTrigger) { this.benefitTrigger = benefitTrigger; }
    public OwnerRewardKind getOwnerRewardKind() { return ownerRewardKind; }
    public void setOwnerRewardKind(OwnerRewardKind ownerRewardKind) { this.ownerRewardKind = ownerRewardKind; }
    public int getOwnerRewardAmount() { return ownerRewardAmount; }
    public void setOwnerRewardAmount(int ownerRewardAmount) { this.ownerRewardAmount = ownerRewardAmount; }
    public int getHoldDays() { return holdDays; }
    public void setHoldDays(int holdDays) { this.holdDays = holdDays; }
    public boolean isClawbackEnabled() { return clawbackEnabled; }
    public void setClawbackEnabled(boolean clawbackEnabled) { this.clawbackEnabled = clawbackEnabled; }
    public CapScope getCapScope() { return capScope; }
    public void setCapScope(CapScope capScope) { this.capScope = capScope; }
    public Integer getCapLimit() { return capLimit; }
    public void setCapLimit(Integer capLimit) { this.capLimit = capLimit; }
    public int getCurrentRedemptions() { return currentRedemptions; }
    public void setCurrentRedemptions(int currentRedemptions) { this.currentRedemptions = currentRedemptions; }
    public String getPayoutKind() { return payoutKind; }
    public void setPayoutKind(String payoutKind) { this.payoutKind = payoutKind; }
    public Integer getPayoutBps() { return payoutBps; }
    public void setPayoutBps(Integer payoutBps) { this.payoutBps = payoutBps; }
    public String getPayoutCurrency() { return payoutCurrency; }
    public void setPayoutCurrency(String payoutCurrency) { this.payoutCurrency = payoutCurrency; }
    public String getPayoutExternalAccountId() { return payoutExternalAccountId; }
    public void setPayoutExternalAccountId(String payoutExternalAccountId) { this.payoutExternalAccountId = payoutExternalAccountId; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public Instant getValidFrom() { return validFrom; }
    public void setValidFrom(Instant validFrom) { this.validFrom = validFrom; }
    public Instant getValidUntil() { return validUntil; }
    public void setValidUntil(Instant validUntil) { this.validUntil = validUntil; }
    public Instant getCreatedAt() { return createdAt; }
}
