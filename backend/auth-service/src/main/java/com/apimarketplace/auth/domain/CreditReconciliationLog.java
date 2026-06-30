package com.apimarketplace.auth.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Records credit drift detected during daily reconciliation.
 * Only created when balance != ledger sum.
 *
 * explained=true means the drift is accounted for by pending dead-letter entries
 * (consumptions that failed and are awaiting retry).
 */
@Entity
@Table(name = "credit_reconciliation_log")
public class CreditReconciliationLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "balance", nullable = false, precision = 15, scale = 4)
    private BigDecimal balance;

    @Column(name = "ledger_sum", nullable = false, precision = 15, scale = 4)
    private BigDecimal ledgerSum;

    @Column(name = "drift", nullable = false, precision = 15, scale = 4)
    private BigDecimal drift;

    /** Number of pending/retrying dead-letter entries for this user. */
    @Column(name = "pending_dead_letters", nullable = false)
    private int pendingDeadLetters;

    /** True if drift is fully explained by pending dead-letter entries. */
    @Column(name = "explained", nullable = false)
    private boolean explained;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    private void ensureDefaults() {
        if (this.createdAt == null) this.createdAt = Instant.now();
    }

    public CreditReconciliationLog() {}

    public CreditReconciliationLog(Long userId, BigDecimal balance, BigDecimal ledgerSum,
                                    BigDecimal drift, int pendingDeadLetters, boolean explained) {
        this.userId = userId;
        this.balance = balance;
        this.ledgerSum = ledgerSum;
        this.drift = drift;
        this.pendingDeadLetters = pendingDeadLetters;
        this.explained = explained;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public BigDecimal getBalance() { return balance; }
    public BigDecimal getLedgerSum() { return ledgerSum; }
    public BigDecimal getDrift() { return drift; }
    public int getPendingDeadLetters() { return pendingDeadLetters; }
    public boolean isExplained() { return explained; }
    public Instant getCreatedAt() { return createdAt; }
}
