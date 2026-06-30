package com.apimarketplace.auth.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

/**
 * In-flight credit-pack tier upgrade. Persisted by
 * {@link com.apimarketplace.auth.service.StripeBillingService#upgradeCreditTierImmediate}
 * after the Stripe invoice is created, and driven through the Option A state
 * machine by webhook handlers + the reconciliation job.
 *
 * <p>The row's primary axis of truth is {@link #stripeInvoiceId} - UNIQUE in DB,
 * matches the invoice that Stripe will reference back via webhooks. The
 * {@code provider_subscription_id} duplicate is denormalised for fast lookup
 * by {@code SubscriptionService.onSubscriptionUpsert} guard.
 *
 * <p>Status machine:
 * <pre>
 *   [start]
 *     │
 *     ├─ invoices.pay() requires_action ──▶ PENDING_3DS
 *     │                                          │
 *     │                                          ▼
 *     │   webhook invoice.paid received ──▶ PAID_SUB_PENDING
 *     ├─ invoices.pay() succeeded ───────▶ PAID_SUB_PENDING
 *     │                                          │
 *     │            subscriptions.update OK ─────▶ COMPLETED
 *     │                                          │
 *     └─ invoice.payment_failed received ──▶ FAILED
 * </pre>
 */
@Entity
@Table(name = "pending_credit_upgrade")
public class PendingCreditUpgrade {

    public static final String STATUS_PENDING_3DS = "PENDING_3DS";
    public static final String STATUS_PAID_SUB_PENDING = "PAID_SUB_PENDING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @NotNull
    @Column(name = "subscription_id", nullable = false)
    private Long subscriptionId;

    @NotBlank
    @Column(name = "provider_subscription_id", nullable = false)
    private String providerSubscriptionId;

    @NotBlank
    @Column(name = "stripe_invoice_id", nullable = false, unique = true)
    private String stripeInvoiceId;

    @NotBlank
    @Column(name = "stripe_invoice_item_id", nullable = false)
    private String stripeInvoiceItemId;

    @NotNull
    @Column(name = "target_tier_index", nullable = false)
    private Integer targetTierIndex;

    @NotNull
    @Column(name = "target_credit_quantity", nullable = false)
    private Integer targetCreditQuantity;

    @NotBlank
    @Column(name = "target_credit_price_id", nullable = false)
    private String targetCreditPriceId;

    @NotBlank
    @Column(nullable = false, length = 32)
    private String status;

    @Column(name = "error_message")
    private String errorMessage;

    @NotNull
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @NotNull
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    public PendingCreditUpgrade() {}

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public boolean isTerminal() {
        return STATUS_COMPLETED.equals(status) || STATUS_FAILED.equals(status);
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public Long getSubscriptionId() { return subscriptionId; }
    public void setSubscriptionId(Long subscriptionId) { this.subscriptionId = subscriptionId; }

    public String getProviderSubscriptionId() { return providerSubscriptionId; }
    public void setProviderSubscriptionId(String providerSubscriptionId) { this.providerSubscriptionId = providerSubscriptionId; }

    public String getStripeInvoiceId() { return stripeInvoiceId; }
    public void setStripeInvoiceId(String stripeInvoiceId) { this.stripeInvoiceId = stripeInvoiceId; }

    public String getStripeInvoiceItemId() { return stripeInvoiceItemId; }
    public void setStripeInvoiceItemId(String stripeInvoiceItemId) { this.stripeInvoiceItemId = stripeInvoiceItemId; }

    public Integer getTargetTierIndex() { return targetTierIndex; }
    public void setTargetTierIndex(Integer targetTierIndex) { this.targetTierIndex = targetTierIndex; }

    public Integer getTargetCreditQuantity() { return targetCreditQuantity; }
    public void setTargetCreditQuantity(Integer targetCreditQuantity) { this.targetCreditQuantity = targetCreditQuantity; }

    public String getTargetCreditPriceId() { return targetCreditPriceId; }
    public void setTargetCreditPriceId(String targetCreditPriceId) { this.targetCreditPriceId = targetCreditPriceId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }
}
