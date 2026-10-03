package com.apimarketplace.auth.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A partner's offer to one client (table {@code auth.partner_offer}, V559): the plan, monthly
 * credit tier and billing cycle the partner recommends, behind a short public {@link #token}
 * (the link {@code /offer/<token>}). Tied to the partner's PARTNER code, so the client who follows
 * it is attributed like any partner link. Deactivated rather than deleted, except on purge.
 */
@Entity
@Table(name = "partner_offer")
public class PartnerOffer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 16, unique = true)
    private String token;

    @Column(name = "partner_user_id", nullable = false)
    private Long partnerUserId;

    @Column(name = "reward_code_id", nullable = false)
    private Long rewardCodeId;

    /** STARTER, PRO or TEAM. */
    @Column(name = "plan_code", nullable = false, length = 16)
    private String planCode;

    /** Index into the credit tiers (CreditTierConstants.CREDIT_TIERS). */
    @Column(name = "credit_tier_index", nullable = false)
    private int creditTierIndex;

    /** monthly or yearly. */
    @Column(name = "billing_cycle", nullable = false, length = 8)
    private String billingCycle;

    /** The partner's own note ("For Acme"), shown to the partner only. */
    @Column(length = 120)
    private String label;

    /**
     * The partner's own applications given with the offer (publication ids, in the order the
     * offer page shows them; V560). Whoever pays through the link receives them.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "app_publication_ids", nullable = false, columnDefinition = "jsonb")
    private List<String> appPublicationIds = new ArrayList<>();

    @Column(nullable = false)
    private boolean active = true;

    /** Set on insert here (not by the column default), so the create response carries it. */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public Long getPartnerUserId() { return partnerUserId; }
    public void setPartnerUserId(Long partnerUserId) { this.partnerUserId = partnerUserId; }
    public Long getRewardCodeId() { return rewardCodeId; }
    public void setRewardCodeId(Long rewardCodeId) { this.rewardCodeId = rewardCodeId; }
    public String getPlanCode() { return planCode; }
    public void setPlanCode(String planCode) { this.planCode = planCode; }
    public int getCreditTierIndex() { return creditTierIndex; }
    public void setCreditTierIndex(int creditTierIndex) { this.creditTierIndex = creditTierIndex; }
    public String getBillingCycle() { return billingCycle; }
    public void setBillingCycle(String billingCycle) { this.billingCycle = billingCycle; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public List<String> getAppPublicationIds() { return appPublicationIds == null ? List.of() : appPublicationIds; }
    public void setAppPublicationIds(List<String> appPublicationIds) {
        this.appPublicationIds = appPublicationIds == null ? new ArrayList<>() : new ArrayList<>(appPublicationIds);
    }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
