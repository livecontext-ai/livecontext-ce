package com.apimarketplace.auth.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * The tier a partner account has reached (table {@code auth.partner_standing}, V556).
 *
 * <p>Read-only from JPA's point of view: every write goes through
 * {@code PartnerStandingRepository#raise}, a single conditional upsert that can only move the
 * tier up, so two concurrent upgrades can never lower it.
 */
@Entity
@Table(name = "partner_standing")
public class PartnerStanding {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PartnerTier tier = PartnerTier.SILVER;

    @Column(nullable = false)
    private boolean founder;

    @Column(name = "reached_at", nullable = false)
    private Instant reachedAt;

    @Column(name = "updated_by_user_id")
    private Long updatedByUserId;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public PartnerTier getTier() { return tier; }
    public void setTier(PartnerTier tier) { this.tier = tier; }
    public boolean isFounder() { return founder; }
    public void setFounder(boolean founder) { this.founder = founder; }
    public Instant getReachedAt() { return reachedAt; }
    public void setReachedAt(Instant reachedAt) { this.reachedAt = reachedAt; }
    public Long getUpdatedByUserId() { return updatedByUserId; }
    public void setUpdatedByUserId(Long updatedByUserId) { this.updatedByUserId = updatedByUserId; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
