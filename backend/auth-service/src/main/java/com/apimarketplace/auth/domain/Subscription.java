package com.apimarketplace.auth.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "subscription")
public class Subscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "billing_customer_id", nullable = false)
    private BillingCustomer billingCustomer;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_id", nullable = false)
    private Plan plan;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "price_id")
    private Price price;

    @NotBlank
    @Column(nullable = false)
    private String cadence; // monthly, yearly, payg

    @NotBlank
    @Column(nullable = false)
    private String provider = "stripe";

    @Column(name = "provider_subscription_id", unique = true)
    private String providerSubscriptionId; // sub_xxx

    @NotBlank
    @Column(nullable = false)
    private String status; // trialing, active, past_due, canceled, incomplete

    @NotNull
    @Column(nullable = false)
    private Integer quantity = 1;

    @NotNull
    @Column(name = "current_period_start", nullable = false)
    private LocalDateTime currentPeriodStart;

    @NotNull
    @Column(name = "current_period_end", nullable = false)
    private LocalDateTime currentPeriodEnd;

    @NotNull
    @Column(name = "cancel_at_period_end", nullable = false)
    private Boolean cancelAtPeriodEnd = false;

    @Column(name = "credit_quantity")
    private Integer creditQuantity = 0;

    @Column(name = "remaining_credits", nullable = false, precision = 15, scale = 4)
    private BigDecimal remainingCredits = BigDecimal.ZERO;

    /**
     * Second-scalar bucket for PAYG one-time top-ups (V250 migration).
     * Fed by {@code grantCredits(sourceType="PAYG_TOPUP")} when a Stripe
     * mode=PAYMENT checkout completes. Consumed AFTER {@code remainingCredits}
     * via {@code CreditService.splitBuckets(sub, totalAmount)} on every debit.
     *
     * <p>Sub-renewal (resetBalance) does NOT touch this column - PAYG top-ups
     * persist across billing cycles.
     */
    @Column(name = "payg_remaining_credits", nullable = false, precision = 15, scale = 4)
    private BigDecimal paygRemainingCredits = BigDecimal.ZERO;

    /**
     * Account-level delinquency flag. Set TRUE by partial-charge / floored-charge
     * commit paths in {@link com.apimarketplace.auth.service.CreditService}; cleared
     * by {@code clearDelinquentIfPositive} on any positive balance transition
     * (refill, refund, release).
     *
     * <p>Invariant maintained by code (V250 2-bucket, extended V379 for Free
     * workflow-credit scoping):
     * {@code delinquent = TRUE ⇒ (remainingCredits + paygRemainingCredits) ≤ 0
     * OR (FREE plan AND paygRemainingCredits < 0)}. The PAYG leg covers the
     * FREE-plan chat/agent overshoot only: the debt lands on the PAYG bucket
     * while the monthly workflow-only grant keeps the total positive, and the
     * sub bucket can never repay it. Paid plans keep the pure total-based
     * lifecycle (set AND clear). The DB-level CHECK is added by V255, relaxed
     * by V379 (the CHECK cannot express the plan, so it admits the payg-negative
     * state for any row; service code only ever creates it on FREE).
     *
     * <p>Gate: {@code tryReserveMarkup} refuses fresh chat reserves and workflow
     * run-init reserves while delinquent. In-flight workflow per-step reserves
     * (with an existing {@code RUN}-scope pin) bypass the gate so a started run
     * can finish atomically.
     */
    @Column(name = "delinquent", nullable = false)
    private Boolean delinquent = false;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "credit_price_id")
    private Price creditPrice;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // Constructeurs
    public Subscription() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    // Getters et Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public BillingCustomer getBillingCustomer() {
        return billingCustomer;
    }

    public void setBillingCustomer(BillingCustomer billingCustomer) {
        this.billingCustomer = billingCustomer;
    }

    public Plan getPlan() {
        return plan;
    }

    public void setPlan(Plan plan) {
        this.plan = plan;
    }

    public Price getPrice() {
        return price;
    }

    public void setPrice(Price price) {
        this.price = price;
    }

    public String getCadence() {
        return cadence;
    }

    public void setCadence(String cadence) {
        this.cadence = cadence;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getProviderSubscriptionId() {
        return providerSubscriptionId;
    }

    public void setProviderSubscriptionId(String providerSubscriptionId) {
        this.providerSubscriptionId = providerSubscriptionId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
    }

    public LocalDateTime getCurrentPeriodStart() {
        return currentPeriodStart;
    }

    public void setCurrentPeriodStart(LocalDateTime currentPeriodStart) {
        this.currentPeriodStart = currentPeriodStart;
    }

    public LocalDateTime getCurrentPeriodEnd() {
        return currentPeriodEnd;
    }

    public void setCurrentPeriodEnd(LocalDateTime currentPeriodEnd) {
        this.currentPeriodEnd = currentPeriodEnd;
    }

    public Boolean getCancelAtPeriodEnd() {
        return cancelAtPeriodEnd;
    }

    public void setCancelAtPeriodEnd(Boolean cancelAtPeriodEnd) {
        this.cancelAtPeriodEnd = cancelAtPeriodEnd;
    }

    public Integer getCreditQuantity() {
        return creditQuantity;
    }

    public void setCreditQuantity(Integer creditQuantity) {
        this.creditQuantity = creditQuantity;
    }

    public BigDecimal getRemainingCredits() {
        return remainingCredits == null ? BigDecimal.ZERO : remainingCredits;
    }

    public void setRemainingCredits(BigDecimal remainingCredits) {
        this.remainingCredits = remainingCredits;
    }

    public BigDecimal getPaygRemainingCredits() {
        return paygRemainingCredits == null ? BigDecimal.ZERO : paygRemainingCredits;
    }

    public void setPaygRemainingCredits(BigDecimal paygRemainingCredits) {
        this.paygRemainingCredits = paygRemainingCredits == null ? BigDecimal.ZERO : paygRemainingCredits;
    }

    /**
     * Total balance across both buckets - what {@code CreditService.getBalance}
     * and {@code canAfford} return after V250. Used to gate fresh reserves
     * AND to evaluate the delinquency invariant.
     */
    public BigDecimal getTotalBalance() {
        return getRemainingCredits().add(getPaygRemainingCredits());
    }

    public Boolean getDelinquent() {
        return delinquent != null && delinquent;
    }

    public void setDelinquent(Boolean delinquent) {
        this.delinquent = delinquent != null && delinquent;
    }

    public Price getCreditPrice() {
        return creditPrice;
    }

    public void setCreditPrice(Price creditPrice) {
        this.creditPrice = creditPrice;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    // Methodes utilitaires
    public boolean isActive() {
        return "trialing".equals(status) || "active".equals(status);
    }

    public boolean isCanceled() {
        return "canceled".equals(status);
    }

    public boolean isPastDue() {
        return "past_due".equals(status);
    }

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
}
