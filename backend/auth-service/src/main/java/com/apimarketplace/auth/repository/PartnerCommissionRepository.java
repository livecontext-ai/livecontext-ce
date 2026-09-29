package com.apimarketplace.auth.repository;

import com.apimarketplace.auth.domain.PartnerCommission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PartnerCommissionRepository extends JpaRepository<PartnerCommission, Long> {

    /** Idempotency probe: one line per Stripe invoice (unique, see V549). */
    boolean existsByProviderInvoiceId(String providerInvoiceId);

    /** The line of one Stripe invoice (unique): the exact target of a refund or a dispute. */
    Optional<PartnerCommission> findByProviderInvoiceId(String providerInvoiceId);

    /** Paid-invoice instant of the FIRST line of a redemption: anchors the payout window. */
    @Query("SELECT MIN(c.invoicePaidAt) FROM PartnerCommission c WHERE c.redemptionId = :redemptionId")
    Optional<Instant> findFirstInvoicePaidAt(@Param("redemptionId") Long redemptionId);

    /** A customer's still-unsettled lines, newest first (refund / dispute voiding). */
    List<PartnerCommission> findByCustomerUserIdAndStatusOrderByInvoicePaidAtDesc(
            Long customerUserId, PartnerCommission.Status status);

    List<PartnerCommission> findByRewardCodeIdIn(List<Long> rewardCodeIds);

    /**
     * Settle ONE line, only if it is still on hold: a refund void that committed in between wins
     * (0 rows), so a partner is never paid for a refunded invoice.
     */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true)
    @Query("""
           UPDATE PartnerCommission c
              SET c.status = com.apimarketplace.auth.domain.PartnerCommission.Status.PAID,
                  c.paidAt = :now, c.paidByUserId = :adminUserId
            WHERE c.id = :id
              AND c.status = com.apimarketplace.auth.domain.PartnerCommission.Status.HOLD
           """)
    int markPaidIfOnHold(@Param("id") Long id, @Param("now") Instant now, @Param("adminUserId") Long adminUserId);

    /**
     * Void ONE line, only if it is still on hold: the mirror of {@link #markPaidIfOnHold}. A line
     * an admin marked PAID in between keeps its PAID status and who paid it (0 rows).
     */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true)
    @Query("""
           UPDATE PartnerCommission c
              SET c.status = com.apimarketplace.auth.domain.PartnerCommission.Status.VOID,
                  c.voidedAt = :now, c.voidReason = :reason
            WHERE c.id = :id
              AND c.status = com.apimarketplace.auth.domain.PartnerCommission.Status.HOLD
           """)
    int voidIfOnHold(@Param("id") Long id, @Param("now") Instant now, @Param("reason") String reason);

    List<PartnerCommission> findByPartnerUserIdAndStatus(Long partnerUserId, PartnerCommission.Status status);
}
