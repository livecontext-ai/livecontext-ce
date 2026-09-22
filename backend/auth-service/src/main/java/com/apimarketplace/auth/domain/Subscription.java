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

    /**
     * Yearly Stripe subscriptions only: index of the last MONTHLY credit cycle granted (V498).
     *
     * <p>The credit pack is priced per unit per month, on every cadence, and sold as "credits
     * per month", but a yearly subscription raises its {@code invoice.paid} once every twelve
     * months, so it was re-granted once a year. Cycle {@code N} starts at
     * {@code currentPeriodStart + N months} ({@code N} in 1..11) and is granted by
     * {@code YearlyCreditCycleScheduler} through
     * {@code CreditAttributionService.attributeMonthlyCreditCycle}; cycle 0 is the grant made at
     * the start of the billing period (creation or {@code invoice.paid}).
     *
     * <p>Anchored on the BILLING period, so it is reset to 0 by {@link #setCurrentPeriodStart}
     * whenever that period moves (Stripe renewal, plan swap, cycle change, admin grant). That
     * single reset point is what keeps the index meaningful: a stale index after a renewal would
     * silently make every drip of the new year "already granted". Always 0 on monthly and
     * internal subscriptions.
     *
     * <p>Known, deferred: the webhook upsert saves the whole row without {@code @DynamicUpdate},
     * so a {@code customer.subscription.updated} that overlaps the scheduler's commit can write
     * this column back to its pre-grant value. Never a double grant (the ledger keys are the
     * second guard, and the next pass reports {@code ABSORBED}), and the same race already
     * exists for {@code remainingCredits}; fixing it is a change to the upsert, not to this feature.
     */
    @Column(name = "credit_cycle_index", nullable = false)
    private Integer creditCycleIndex = 0;

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
     * Third bucket (V494): the monthly AI allowance, drawn ONLY by agent/chat/LLM
     * debits that run on a model opened to the free tier. Refilled to
     * {@code plan.included_ai_credits} on renewal; a plan with no allowance leaves
     * it at zero forever, which is why paid plans are unaffected.
     *
     * <p>Deliberately OUTSIDE {@link #getTotalBalance()}: that total feeds every
     * existing gate and ledger arithmetic, and silently growing it would let this
     * restricted pot pay for things it must never pay for (workflow nodes, web
     * search, image generation, a model not on the free tier). It is surfaced
     * separately in the balance breakdown instead.
     */
    @Column(name = "ai_remaining_credits", nullable = false, precision = 12, scale = 4)
    private BigDecimal aiRemainingCredits = BigDecimal.ZERO;

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

    /**
     * Moves the billing-period anchor. A NEW anchor also restarts the monthly credit cycle
     * ({@link #creditCycleIndex} back to 0): the index counts months since this instant, so
     * carrying it across a renewal would make the whole next year look already granted.
     * Setting the same value again (Stripe re-sends the unchanged period on every
     * {@code customer.subscription.updated}) leaves the index alone. Hibernate hydrates by
     * field access, so loading a row never goes through here.
     */
    public void setCurrentPeriodStart(LocalDateTime currentPeriodStart) {
        if (!java.util.Objects.equals(this.currentPeriodStart, currentPeriodStart)) {
            this.creditCycleIndex = 0;
        }
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

    /** See {@link #creditCycleIndex}. Never null once persisted; treated as 0 when unset. */
    public Integer getCreditCycleIndex() {
        return creditCycleIndex == null ? 0 : creditCycleIndex;
    }

    /** Null is normalised to 0 here, not only in the getter: the column is NOT NULL. */
    public void setCreditCycleIndex(Integer creditCycleIndex) {
        this.creditCycleIndex = creditCycleIndex == null ? 0 : creditCycleIndex;
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

    public BigDecimal getAiRemainingCredits() {
        return aiRemainingCredits == null ? BigDecimal.ZERO : aiRemainingCredits;
    }

    public void setAiRemainingCredits(BigDecimal aiRemainingCredits) {
        this.aiRemainingCredits = aiRemainingCredits == null ? BigDecimal.ZERO : aiRemainingCredits;
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
