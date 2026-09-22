package com.apimarketplace.auth.repository;

import com.apimarketplace.auth.domain.BillingCustomer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface BillingCustomerRepository extends JpaRepository<BillingCustomer, Long> {

    /**
     * Trouve un client de facturation par son ID utilisateur
     */
    Optional<BillingCustomer> findByUserId(Long userId);

    /**
     * Trouve un client de facturation par son ID Stripe
     */
    Optional<BillingCustomer> findByProviderCustomerId(String providerCustomerId);

    /**
     * Verifie si un utilisateur a deja un client de facturation
     */
    boolean existsByUserId(Long userId);

    /**
     * Create this user's billing-customer row if it does not exist yet, WITHOUT ever raising.
     *
     * <p>The obvious spelling (try {@code save()}, catch the unique-index violation, read the
     * winner's row) cannot work, and that is not a style preference. A constraint violation puts
     * the PostgreSQL transaction in {@code ERROR} state, so every later statement in it fails with
     * {@code 25P02}, and it makes Spring flag the transaction rollback-only. The catch therefore
     * recovers nothing: it only hides the failure until the commit, where the proxy throws an
     * {@code UnexpectedRollbackException} that the method's own catch can never see. Failed Google
     * logins in production were made of exactly that.
     *
     * <p>{@code ON CONFLICT DO NOTHING} raises nothing, so the transaction stays usable. When a
     * concurrent inserter holds the key uncommitted, PostgreSQL BLOCKS here until that transaction
     * ends, which is precisely the serialisation the caller wants: the follow-up read is then
     * guaranteed to find a row. The trade is worth stating: a losing racer now waits, holding its
     * pooled connection, for as long as the winner's transaction runs, where before it failed
     * immediately. That is a change in failure mode, not only in outcome, and it puts a duty on
     * callers: take this key LATE, next to the write that needs it, never before slow work.
     * {@code StripeBillingService.recreateStripeCustomer} is the example, where creating the row
     * before the customer round-trips would have added them to what the loser waits through. Note
     * the wait ends at the CALLER's commit, not at this method's return, so "late" buys you the
     * slow work you moved above it and nothing that happens after.
     *
     * <p>PostgreSQL only. The conflict target needs a real unique constraint to infer, which
     * {@code billing_customer.user_id} has, and H2 (used by the {@code integration-test} profile)
     * accepts only the bare {@code ON CONFLICT DO NOTHING} without one. Nothing exercises this on
     * H2 today; an H2-backed test that ever reaches provisioning would fail on the syntax rather
     * than on behaviour.
     *
     * <p>No {@code @Transactional} of its own on purpose: it is a building block, and the
     * transaction is the caller's to own. Outside one it fails loudly with
     * {@code TransactionRequiredException} rather than half-working.
     *
     * @return 1 when this call inserted the row, 0 when it was already there
     */
    @Modifying(flushAutomatically = true)
    @Query(value = "INSERT INTO auth.billing_customer (user_id, provider, created_at) "
                 + "VALUES (:userId, :provider, now()) ON CONFLICT (user_id) DO NOTHING",
           nativeQuery = true)
    int insertIfAbsent(@Param("userId") Long userId, @Param("provider") String provider);

    /**
     * The user's billing customer, created first if it does not exist. The ONE safe way to get
     * one, and the reason it exists at all.
     *
     * <p>Every call site used to spell this as {@code findByUserId(id).orElseGet(() -> save(new
     * BillingCustomer(...)))}, which is a check-then-act on a uniquely-constrained column: two
     * requests for the same user both read nothing and both insert, and the loser does not merely
     * fail to create a row, it kills its own transaction. That last part is what makes the shape
     * unfixable from the inside, and it is specific to running INSIDE a transaction: the same
     * catch-and-retry is legitimate where each repository call is its own transaction, which is
     * why {@code UserResolutionService.findOrCreateUser} keeps it and should not be "fixed". The belief that made this look safe was
     * written down in one of those call sites, and it is wrong in its second half: "the unique
     * constraint on the DB side will protect and the caller will re-read the existing entity".
     * The constraint does protect the DATA. The caller cannot re-read anything, because by then
     * PostgreSQL has put its transaction in ERROR state and Spring has flagged it rollback-only,
     * so the read fails and the commit throws where no catch can see it. That cost production a
     * broken Stripe checkout (fixed 2026-09-15) and, separately, failed logins for roughly four
     * of every ten new accounts.
     *
     * <p>{@link #insertIfAbsent} raises nothing and blocks on a concurrent uncommitted inserter,
     * so the read that follows always finds a row. Must run inside a transaction, as the writes
     * that need a billing customer already do.
     *
     * <p>Provisioning takes {@link #findByUserIdForUpdate} instead, because it needs the row as a
     * mutex and not merely as a row.
     */
    default BillingCustomer findOrCreate(Long userId, String provider) {
        return findByUserId(userId).orElseGet(() -> {
            insertIfAbsent(userId, provider);
            return findByUserId(userId).orElseThrow(() -> new IllegalStateException(
                    "billing customer still absent after insertIfAbsent for userId=" + userId));
        });
    }

    /**
     * PESSIMISTIC_WRITE on a user's billing-customer row, used to serialise
     * subscription provisioning for that user.
     *
     * <p>There is exactly one billing_customer per user (unique index on {@code user_id}),
     * so locking it is a per-user mutex that works ACROSS pods - which matters, because
     * the duplicate-subscription race this exists to close is driven by concurrent
     * {@code resolveUser} calls that can land on different auth replicas.
     */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT bc FROM BillingCustomer bc WHERE bc.user.id = :userId")
    Optional<BillingCustomer> findByUserIdForUpdate(@Param("userId") Long userId);
}
