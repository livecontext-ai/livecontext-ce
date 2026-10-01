package com.apimarketplace.auth.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "personal_offer_checkout_attempt")
@Getter @Setter
public class PersonalOfferCheckoutAttempt {
    @Id
    private UUID id;
    @Column(name = "reward_code_id", nullable = false)
    private Long rewardCodeId;
    @Column(name = "recipient_user_id", nullable = false)
    private Long recipientUserId;
    @Column(name = "policy_version_id", nullable = false)
    private Long policyVersionId;
    @Column(name = "plan_code", nullable = false, length = 32)
    private String planCode;
    @Column(name = "monthly_credits", nullable = false)
    private int monthlyCredits;
    @Column(name = "credit_tier_index", nullable = false)
    private int creditTierIndex;
    @Column(nullable = false, length = 8)
    private String cadence;
    @Column(name = "bonus_credits", nullable = false)
    private int bonusCredits;
    @Column(name = "plan_price_id", nullable = false, length = 255)
    private String planPriceId;
    @Column(name = "credit_price_id", length = 255)
    private String creditPriceId;
    @Column(name = "first_invoice_preview_amount")
    private Long firstInvoicePreviewAmount;
    @Column(name = "stripe_customer_id", length = 255)
    private String stripeCustomerId;
    @Column(name = "client_nonce", length = 512)
    private String clientNonce;
    @Column(name = "stripe_session_id", length = 255)
    private String stripeSessionId;
    @Column(name = "stripe_subscription_id", length = 255)
    private String stripeSubscriptionId;
    @Column(name = "stripe_invoice_id", length = 255)
    private String stripeInvoiceId;
    @Column(name = "session_url", columnDefinition = "text")
    private String sessionUrl;
    @Column(name = "session_expires_at", nullable = false)
    private Instant sessionExpiresAt;
    @Column(name = "next_reconcile_at", nullable = false)
    private Instant nextReconcileAt;
    @Column(nullable = false, length = 16)
    private String status;
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", insertable = false)
    private Instant updatedAt;
}
