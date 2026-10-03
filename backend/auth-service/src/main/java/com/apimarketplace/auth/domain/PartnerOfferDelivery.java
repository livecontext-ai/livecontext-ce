package com.apimarketplace.auth.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * One application a partner's offer gives, on its way to one client (table
 * {@code auth.partner_offer_delivery}, V560). Created AWAITING_PAYMENT when the client opens the
 * offer's checkout (so a payment whose webhook is lost can be found again), moved to PENDING when
 * the subscription's first invoice is paid (or created PENDING then, without a checkout record),
 * then installed in the client's workspace: INSTALLED once it is there (or was already), FAILED when
 * it can never be (the app was withdrawn, the plan cannot hold it, or the tries ran out).
 * {@link #nextAttemptAt} is both the backoff and the lease a try takes.
 */
@Entity
@Table(name = "partner_offer_delivery")
public class PartnerOfferDelivery {

    public enum Status { AWAITING_PAYMENT, PENDING, INSTALLED, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "offer_id", nullable = false)
    private Long offerId;

    @Column(name = "client_user_id", nullable = false)
    private Long clientUserId;

    @Column(name = "publication_id", nullable = false)
    private UUID publicationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.PENDING;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "last_error", length = 300)
    private String lastError;

    @Column(name = "invoice_id")
    private String invoiceId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (nextAttemptAt == null) nextAttemptAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getOfferId() { return offerId; }
    public void setOfferId(Long offerId) { this.offerId = offerId; }
    public Long getClientUserId() { return clientUserId; }
    public void setClientUserId(Long clientUserId) { this.clientUserId = clientUserId; }
    public UUID getPublicationId() { return publicationId; }
    public void setPublicationId(UUID publicationId) { this.publicationId = publicationId; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public void setNextAttemptAt(Instant nextAttemptAt) { this.nextAttemptAt = nextAttemptAt; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public String getInvoiceId() { return invoiceId; }
    public void setInvoiceId(String invoiceId) { this.invoiceId = invoiceId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
