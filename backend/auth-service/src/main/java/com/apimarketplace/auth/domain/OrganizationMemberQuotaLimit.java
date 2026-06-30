package com.apimarketplace.auth.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * PR11 - per-member quota cap configurable by an organization's OWNER /
 * ADMIN. NULL on a dimension means "no cap on that dimension". Enforced
 * in {@code CreditService.consume*} against the EXECUTOR (not the payer),
 * so a Q1=b owner-redirected debit cannot bypass a member's cap.
 *
 * <p>One row per {@code (org, user)} pair. {@code reset_cadence} is fixed
 * to {@code MONTHLY_SUB_CYCLE} in v1 (aligned to the org owner's Stripe
 * billing cycle - resolves §9.2 of the org/membership redesign plan).
 * Future cadences (DAILY/WEEKLY) require widening the CHECK constraint in
 * the V199 migration; the column already accepts a generic VARCHAR(32).
 *
 * @see com.apimarketplace.auth.service.CreditService
 */
@Entity
@Table(name = "org_member_quota_limit")
@IdClass(OrganizationMemberQuotaLimit.PK.class)
public class OrganizationMemberQuotaLimit {

    public static final String CADENCE_MONTHLY_SUB_CYCLE = "MONTHLY_SUB_CYCLE";

    @Id
    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** Per-period credit cap. NULL = no cap on this dimension. */
    @Column(name = "period_credits", precision = 18, scale = 4)
    private BigDecimal periodCredits;

    /** Per-period storage cap (bytes). NULL = no cap. */
    @Column(name = "period_storage_bytes")
    private Long periodStorageBytes;

    /** Per-period token cap. NULL = no cap. */
    @Column(name = "period_llm_tokens")
    private Long periodLlmTokens;

    @Column(name = "reset_cadence", nullable = false, length = 32)
    private String resetCadence = CADENCE_MONTHLY_SUB_CYCLE;

    @Column(name = "created_by_user_id", nullable = false)
    private Long createdByUserId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public OrganizationMemberQuotaLimit() {}

    public OrganizationMemberQuotaLimit(UUID orgId, Long userId, Long createdByUserId) {
        this.orgId = orgId;
        this.userId = userId;
        this.createdByUserId = createdByUserId;
    }

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public UUID getOrgId() { return orgId; }
    public void setOrgId(UUID orgId) { this.orgId = orgId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public BigDecimal getPeriodCredits() { return periodCredits; }
    public void setPeriodCredits(BigDecimal periodCredits) { this.periodCredits = periodCredits; }
    public Long getPeriodStorageBytes() { return periodStorageBytes; }
    public void setPeriodStorageBytes(Long periodStorageBytes) { this.periodStorageBytes = periodStorageBytes; }
    public Long getPeriodLlmTokens() { return periodLlmTokens; }
    public void setPeriodLlmTokens(Long periodLlmTokens) { this.periodLlmTokens = periodLlmTokens; }
    public String getResetCadence() { return resetCadence; }
    public void setResetCadence(String resetCadence) { this.resetCadence = resetCadence; }
    public Long getCreatedByUserId() { return createdByUserId; }
    public void setCreatedByUserId(Long createdByUserId) { this.createdByUserId = createdByUserId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    /** True iff at least one cap dimension is set (null-row would be useless). */
    public boolean hasAnyCap() {
        return periodCredits != null || periodStorageBytes != null || periodLlmTokens != null;
    }

    /** Composite PK class - required by {@link IdClass} when entity has multiple {@code @Id} columns. */
    public static class PK implements java.io.Serializable {
        private UUID orgId;
        private Long userId;

        public PK() {}
        public PK(UUID orgId, Long userId) { this.orgId = orgId; this.userId = userId; }

        public UUID getOrgId() { return orgId; }
        public void setOrgId(UUID orgId) { this.orgId = orgId; }
        public Long getUserId() { return userId; }
        public void setUserId(Long userId) { this.userId = userId; }

        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PK p)) return false;
            return java.util.Objects.equals(orgId, p.orgId) && java.util.Objects.equals(userId, p.userId);
        }
        @Override public int hashCode() { return java.util.Objects.hash(orgId, userId); }
    }
}
