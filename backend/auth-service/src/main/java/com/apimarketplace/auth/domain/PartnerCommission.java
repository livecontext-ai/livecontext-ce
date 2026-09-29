package com.apimarketplace.auth.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * One partner revenue-share line (table {@code auth.partner_commission}, V549): the share
 * a PARTNER code owner earns on ONE paid Stripe invoice of a customer they referred.
 *
 * <p>Lifecycle: {@code HOLD} until {@link #dueAt} (the refund window), then payable;
 * {@code PAID} once an admin settled it; {@code VOID} when the invoice was refunded or
 * disputed before payout. "Payable" is derived ({@code HOLD} and {@code dueAt} past), so
 * no scheduler has to move rows.
 */
@Entity
@Table(name = "partner_commission")
public class PartnerCommission {

    public enum Status { HOLD, PAID, VOID }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "redemption_id", nullable = false)
    private Long redemptionId;

    @Column(name = "reward_code_id", nullable = false)
    private Long rewardCodeId;

    @Column(name = "partner_user_id", nullable = false)
    private Long partnerUserId;

    @Column(name = "customer_user_id", nullable = false)
    private Long customerUserId;

    @Column(name = "provider_invoice_id", nullable = false, length = 255)
    private String providerInvoiceId;

    @Column(name = "base_amount_minor", nullable = false)
    private long baseAmountMinor;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "payout_bps", nullable = false)
    private int payoutBps;

    @Column(name = "commission_minor", nullable = false)
    private long commissionMinor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private Status status = Status.HOLD;

    @Column(name = "invoice_paid_at", nullable = false)
    private Instant invoicePaidAt;

    @Column(name = "due_at", nullable = false)
    private Instant dueAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "paid_by_user_id")
    private Long paidByUserId;

    @Column(name = "voided_at")
    private Instant voidedAt;

    @Column(name = "void_reason", length = 32)
    private String voidReason;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    /** True iff the refund window has elapsed and the line is still owed. */
    public boolean isPayableAt(Instant now) {
        return status == Status.HOLD && dueAt != null && !now.isBefore(dueAt);
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getRedemptionId() { return redemptionId; }
    public void setRedemptionId(Long redemptionId) { this.redemptionId = redemptionId; }
    public Long getRewardCodeId() { return rewardCodeId; }
    public void setRewardCodeId(Long rewardCodeId) { this.rewardCodeId = rewardCodeId; }
    public Long getPartnerUserId() { return partnerUserId; }
    public void setPartnerUserId(Long partnerUserId) { this.partnerUserId = partnerUserId; }
    public Long getCustomerUserId() { return customerUserId; }
    public void setCustomerUserId(Long customerUserId) { this.customerUserId = customerUserId; }
    public String getProviderInvoiceId() { return providerInvoiceId; }
    public void setProviderInvoiceId(String providerInvoiceId) { this.providerInvoiceId = providerInvoiceId; }
    public long getBaseAmountMinor() { return baseAmountMinor; }
    public void setBaseAmountMinor(long baseAmountMinor) { this.baseAmountMinor = baseAmountMinor; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public int getPayoutBps() { return payoutBps; }
    public void setPayoutBps(int payoutBps) { this.payoutBps = payoutBps; }
    public long getCommissionMinor() { return commissionMinor; }
    public void setCommissionMinor(long commissionMinor) { this.commissionMinor = commissionMinor; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public Instant getInvoicePaidAt() { return invoicePaidAt; }
    public void setInvoicePaidAt(Instant invoicePaidAt) { this.invoicePaidAt = invoicePaidAt; }
    public Instant getDueAt() { return dueAt; }
    public void setDueAt(Instant dueAt) { this.dueAt = dueAt; }
    public Instant getPaidAt() { return paidAt; }
    public void setPaidAt(Instant paidAt) { this.paidAt = paidAt; }
    public Long getPaidByUserId() { return paidByUserId; }
    public void setPaidByUserId(Long paidByUserId) { this.paidByUserId = paidByUserId; }
    public Instant getVoidedAt() { return voidedAt; }
    public void setVoidedAt(Instant voidedAt) { this.voidedAt = voidedAt; }
    public String getVoidReason() { return voidReason; }
    public void setVoidReason(String voidReason) { this.voidReason = voidReason; }
    public Instant getCreatedAt() { return createdAt; }
}
