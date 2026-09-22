package com.apimarketplace.auth.repository;

import com.apimarketplace.auth.domain.BillingEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface BillingEventRepository extends JpaRepository<BillingEvent, Long> {

    Optional<BillingEvent> findByEventId(String eventId);

    boolean existsByEventId(String eventId);

    List<BillingEvent> findByType(String type);

    List<BillingEvent> findByProvider(String provider);

    @Query("SELECT be FROM BillingEvent be WHERE be.receivedAt BETWEEN :start AND :end ORDER BY be.receivedAt DESC")
    List<BillingEvent> findByPeriod(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    // Variante generique : recherche textuelle dans tout le JSON
    @Query(
            value = """
                      SELECT *
                      FROM auth.billing_event be
                      WHERE be.type = 'usage.payg'
                        AND be.payload::text ILIKE '%' || :userId || '%'
                      ORDER BY be.received_at DESC
                    """,
            nativeQuery = true
    )
    List<BillingEvent> findPaygUsageByUserId(@Param("userId") String userId);

    /**
     * Paid PAYG top-up checkouts (Stripe {@code checkout.session.completed} with
     * {@code metadata.kind=payg_topup} and {@code payment_status=paid}) whose session id never
     * reached {@code credit_ledger.source_id}: the card was charged and the grant was skipped
     * (unreadable nonce, crash between the event insert and the grant). Consumed by
     * {@code PaygTopupReconciler}. Only events older than {@code cutoff} are returned so the
     * reconciler never races the live webhook. The synthetic {@code checkout_completed_*} rows
     * the webhook also stores have no {@code data.object} and are filtered out by the JSON path.
     */
    @Query(
            value = """
                      SELECT be.*
                      FROM auth.billing_event be
                      WHERE be.type = 'checkout.session.completed'
                        AND be.provider = 'stripe'
                        AND be.payload->'data'->'object'->'metadata'->>'kind' = 'payg_topup'
                        AND be.payload->'data'->'object'->>'payment_status' = 'paid'
                        AND be.received_at < :cutoff
                        AND NOT EXISTS (
                              SELECT 1 FROM auth.credit_ledger l
                              WHERE l.source_id = be.payload->'data'->'object'->>'id')
                      ORDER BY be.received_at ASC
                      LIMIT :limit
                    """,
            nativeQuery = true
    )
    List<BillingEvent> findPaidPaygTopupsWithoutLedger(@Param("cutoff") LocalDateTime cutoff,
                                                       @Param("limit") int limit);

    // Variante plus precise (si le JSON contient payload->'userId'):
    // @Query(value = "SELECT * FROM auth.billing_event be WHERE be.payload->>'userId' = :userId ORDER BY be.received_at DESC", nativeQuery = true)
    // List<BillingEvent> findPaygUsageByUserId(@Param("userId") String userId);
}
