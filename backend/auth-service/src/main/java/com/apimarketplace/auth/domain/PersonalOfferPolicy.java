package com.apimarketplace.auth.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

@Entity
@Table(name = "personal_offer_policy")
@Getter @Setter
public class PersonalOfferPolicy {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "campaign_key", nullable = false, length = 64)
    private String campaignKey;
    @Column(nullable = false)
    private int version;
    @Column(nullable = false, length = 16)
    private String state = "DRAFT";
    @Column(nullable = false, length = 128)
    private String label;
    @Column(name = "wait_hours", nullable = false)
    private int waitHours = 4;
    @Column(name = "validity_hours", nullable = false)
    private int validityHours = 72;
    @Column(name = "checkout_hold_minutes", nullable = false)
    private int checkoutHoldMinutes = 30;
    @Column(name = "reminder_enabled", nullable = false)
    private boolean reminderEnabled;
    @Column(name = "reminder_hours", nullable = false)
    private int reminderHours = 12;
    @Column(name = "payg_credits_per_usd", nullable = false)
    private int paygCreditsPerUsd = 800;
    @Column(name = "allow_conversion_stack", nullable = false)
    private boolean allowConversionStack;
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", insertable = false)
    private Instant updatedAt;
}
