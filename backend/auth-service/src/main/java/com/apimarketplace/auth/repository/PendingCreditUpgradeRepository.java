package com.apimarketplace.auth.repository;

import com.apimarketplace.auth.domain.PendingCreditUpgrade;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface PendingCreditUpgradeRepository extends JpaRepository<PendingCreditUpgrade, Long> {

    Optional<PendingCreditUpgrade> findByStripeInvoiceId(String stripeInvoiceId);

    /**
     * Used by {@code SubscriptionService.onSubscriptionUpsert} to detect that a
     * credit-pack quantity change observed on a {@code customer.subscription.updated}
     * webhook is the consequence of an in-flight or already-completed Option A
     * upgrade (grant owned by the {@code invoice.paid} handler).
     *
     * <p>We match on {@code targetCreditQuantity = newQty} rather than a time window:
     * Stripe may deliver {@code customer.subscription.updated} hours later (retries,
     * dunning, replays). Matching on the target qty is deterministic - the row was
     * created exactly to satisfy this upgrade and stays valid until a NEW upgrade
     * targets a different qty.
     *
     * <p>FAILED rows are excluded: a failed Option A attempt should NOT block a
     * legacy {@code handleCreditPackChange} grant if the same Stripe sub later
     * reaches the target qty via another path.
     */
    @Query("""
        SELECT p FROM PendingCreditUpgrade p
        WHERE p.providerSubscriptionId = :providerSubId
          AND p.targetCreditQuantity = :targetQty
          AND p.status <> 'FAILED'
        ORDER BY p.createdAt DESC
        """)
    List<PendingCreditUpgrade> findActiveForSubscriptionAndQty(
            @Param("providerSubId") String providerSubId,
            @Param("targetQty") int targetQty);

    /**
     * Used by the reconciliation job to find upgrades stuck between
     * {@code invoices.pay()} success and {@code subscriptions.update()}.
     * Pageable variant caps the batch size to bound per-tick Stripe API
     * traffic during incidents (e.g. backlog of 10k+ stuck rows).
     */
    List<PendingCreditUpgrade> findByStatusOrderByCreatedAtAsc(String status, Pageable pageable);

    /**
     * Count FAILED attempts for the same (sub, target tier+qty) combination.
     * Used by {@code upgradeCreditTierImmediate} to bump the Stripe Idempotency-Key
     * suffix on retries - without it, Stripe replays the cached failed response
     * for the entire idempotency window (24h) even after the user has fixed
     * their payment method.
     */
    long countByProviderSubscriptionIdAndTargetTierIndexAndTargetCreditQuantityAndStatus(
            String providerSubscriptionId, Integer targetTierIndex, Integer targetCreditQuantity, String status);
}
