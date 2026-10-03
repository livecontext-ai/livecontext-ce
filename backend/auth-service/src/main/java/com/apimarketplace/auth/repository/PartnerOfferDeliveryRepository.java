package com.apimarketplace.auth.repository;

import com.apimarketplace.auth.domain.PartnerOfferDelivery;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The deliveries of partner offer apps (V560). Every query that the delivery guarantees rest on
 * (one row per app, the claim that keeps two pods from installing twice, what is due) is native
 * SQL held in a constant, so {@code PartnerOfferDeliverySqlPostgresTest} runs the very same text
 * against a real Postgres. Every time comes from the application's clock ({@code :now}), never the
 * database's: the rows a try reads back were written with the same clock.
 */
public interface PartnerOfferDeliveryRepository extends JpaRepository<PartnerOfferDelivery, Long> {

    /**
     * The checkout opened from the offer: each app it gives waits for the payment. A checkout
     * opened again restarts the wait (a new session, a new chance to pay), and an app already past
     * waiting is left alone.
     */
    String EXPECT_SQL = "INSERT INTO auth.partner_offer_delivery "
            + "(offer_id, client_user_id, publication_id, status, next_attempt_at, created_at, updated_at) "
            + "VALUES (:offerId, :clientUserId, :publicationId, 'AWAITING_PAYMENT', :now, :now, :now) "
            + "ON CONFLICT (offer_id, client_user_id, publication_id) DO UPDATE "
            + "SET created_at = :now, checked_at = NULL, updated_at = :now "
            + "WHERE auth.partner_offer_delivery.status = 'AWAITING_PAYMENT'";

    /**
     * The payment is confirmed: the app is to install now, whether its row was waiting for the
     * payment or never written (the checkout's record failed, or a payment reached from elsewhere).
     * A row already past waiting is left alone, so a replayed invoice changes nothing. Returns 1
     * when the row was inserted or moved on.
     */
    String PAID_SQL = "INSERT INTO auth.partner_offer_delivery "
            + "(offer_id, client_user_id, publication_id, status, next_attempt_at, invoice_id, created_at, updated_at) "
            + "VALUES (:offerId, :clientUserId, :publicationId, 'PENDING', :now, :invoiceId, :now, :now) "
            + "ON CONFLICT (offer_id, client_user_id, publication_id) DO UPDATE "
            + "SET status = 'PENDING', next_attempt_at = :now, invoice_id = EXCLUDED.invoice_id, updated_at = :now "
            + "WHERE auth.partner_offer_delivery.status = 'AWAITING_PAYMENT'";

    /** What is due, oldest first. */
    String DUE_SQL = "SELECT id FROM auth.partner_offer_delivery "
            + "WHERE status = 'PENDING' AND next_attempt_at <= :now ORDER BY next_attempt_at, id LIMIT :limit";

    /** What is due for one client (the try right after their payment). */
    String DUE_FOR_CLIENT_SQL = "SELECT id FROM auth.partner_offer_delivery "
            + "WHERE client_user_id = :clientUserId AND status = 'PENDING' AND next_attempt_at <= :now ORDER BY id";

    /**
     * Take a due delivery for one try: count the try and push its next one to {@code :lease}, so
     * anyone else asking for it is refused. Returns 1 to the one caller that took it.
     */
    String CLAIM_SQL = "UPDATE auth.partner_offer_delivery "
            + "SET attempts = attempts + 1, next_attempt_at = :lease, updated_at = :now "
            + "WHERE id = :id AND status = 'PENDING' AND next_attempt_at <= :now";

    /**
     * The (offer, client) pairs still waiting for a payment, opened between {@code :oldest} and
     * {@code :settled}: the least recently checked first (never checked before all), at most
     * {@code :limit}, so a long queue is gone through in turn rather than its head over and over.
     */
    String AWAITING_SQL = "SELECT offer_id, client_user_id FROM auth.partner_offer_delivery "
            + "WHERE status = 'AWAITING_PAYMENT' AND created_at > :oldest AND created_at <= :settled "
            + "GROUP BY offer_id, client_user_id "
            + "ORDER BY MAX(checked_at) ASC NULLS FIRST, MIN(created_at) ASC, offer_id, client_user_id LIMIT :limit";

    /** The pair was looked at: it goes to the back of the queue. */
    String MARK_CHECKED_SQL = "UPDATE auth.partner_offer_delivery SET checked_at = :now "
            + "WHERE offer_id = :offerId AND client_user_id = :clientUserId AND status = 'AWAITING_PAYMENT'";

    /** The client paid without this offer (another checkout): the offer's waiting apps will never be theirs. */
    String FORGET_PAIR_SQL = "DELETE FROM auth.partner_offer_delivery "
            + "WHERE offer_id = :offerId AND client_user_id = :clientUserId AND status = 'AWAITING_PAYMENT'";

    /** The payment was found after all: the pair's waiting apps are to install now. */
    String PROMOTE_SQL = "UPDATE auth.partner_offer_delivery "
            + "SET status = 'PENDING', next_attempt_at = :now, updated_at = :now "
            + "WHERE offer_id = :offerId AND client_user_id = :clientUserId AND status = 'AWAITING_PAYMENT'";

    /** A checkout abandoned long enough ago never became a payment: its waiting rows go. */
    String FORGET_ABANDONED_SQL = "DELETE FROM auth.partner_offer_delivery "
            + "WHERE status = 'AWAITING_PAYMENT' AND created_at <= :oldest";

    @Modifying
    @Query(value = EXPECT_SQL, nativeQuery = true)
    int expect(@Param("offerId") Long offerId, @Param("clientUserId") Long clientUserId,
               @Param("publicationId") UUID publicationId, @Param("now") Instant now);

    @Modifying
    @Query(value = PAID_SQL, nativeQuery = true)
    int paid(@Param("offerId") Long offerId, @Param("clientUserId") Long clientUserId,
             @Param("publicationId") UUID publicationId, @Param("invoiceId") String invoiceId, @Param("now") Instant now);

    @Query(value = DUE_SQL, nativeQuery = true)
    List<Long> findDueIds(@Param("now") Instant now, @Param("limit") int limit);

    @Query(value = DUE_FOR_CLIENT_SQL, nativeQuery = true)
    List<Long> findDueIdsForClient(@Param("clientUserId") Long clientUserId, @Param("now") Instant now);

    @Modifying
    @Query(value = CLAIM_SQL, nativeQuery = true)
    int claim(@Param("id") Long id, @Param("now") Instant now, @Param("lease") Instant lease);

    @Query(value = AWAITING_SQL, nativeQuery = true)
    List<Object[]> findAwaiting(@Param("oldest") Instant oldest, @Param("settled") Instant settled, @Param("limit") int limit);

    @Modifying
    @Query(value = MARK_CHECKED_SQL, nativeQuery = true)
    int markChecked(@Param("offerId") Long offerId, @Param("clientUserId") Long clientUserId, @Param("now") Instant now);

    @Modifying
    @Query(value = FORGET_PAIR_SQL, nativeQuery = true)
    int forgetPair(@Param("offerId") Long offerId, @Param("clientUserId") Long clientUserId);

    @Modifying
    @Query(value = PROMOTE_SQL, nativeQuery = true)
    int promote(@Param("offerId") Long offerId, @Param("clientUserId") Long clientUserId, @Param("now") Instant now);

    @Modifying
    @Query(value = FORGET_ABANDONED_SQL, nativeQuery = true)
    int forgetAbandoned(@Param("oldest") Instant oldest);

    List<PartnerOfferDelivery> findByOfferIdAndClientUserId(Long offerId, Long clientUserId);
}
