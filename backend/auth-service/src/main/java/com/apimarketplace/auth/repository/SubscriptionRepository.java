package com.apimarketplace.auth.repository;

import com.apimarketplace.auth.domain.Subscription;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static jakarta.persistence.LockModeType.PESSIMISTIC_WRITE;

@Repository
public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {

    @Query("""
           SELECT s FROM Subscription s
           WHERE s.billingCustomer.id = :customerId
             AND s.status IN ('trialing','active')
           ORDER BY s.createdAt DESC
           """)
    Optional<Subscription> findActiveByCustomerId(@Param("customerId") Long customerId);

    /**
     * Returns the user's most-recent active subscription.
     *
     * <p>When a user owns more than one active subscription row, this returns
     * the most-recent (createdAt DESC) - stable per database. {@code LIMIT 1}
     * via {@code Pageable} avoids the {@code NonUniqueResultException} the
     * JPQL would otherwise throw when multiple active subs match.
     */
    @Query(value = """
           SELECT s FROM Subscription s
           WHERE s.billingCustomer.user.id = :userId
             AND s.status IN ('trialing','active')
           ORDER BY s.createdAt DESC
           """)
    java.util.List<Subscription> findActiveByUserIdAllScopes(@Param("userId") Long userId,
                                                             org.springframework.data.domain.Pageable pageable);

    /**
     * Back-compat single-result wrapper around
     * {@link #findActiveByUserIdAllScopes}. Returns the most-recent active
     * sub or empty. See V224 trade-off note above.
     */
    default Optional<Subscription> findActiveByUserId(Long userId) {
        java.util.List<Subscription> rows = findActiveByUserIdAllScopes(userId,
                org.springframework.data.domain.PageRequest.of(0, 1));
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /**
     * Batch variant of {@link #findActiveByUserId} for many owners at once -
     * used by the "dormant org" gate to resolve each org owner's plan in ONE
     * query (avoids per-org N+1 on the hot user-resolution path). Eager-fetches
     * plan + billingCustomer.user so callers can read {@code plan.supportsTeam()}
     * and the owner id without lazy round-trips. Multiple active rows per owner
     * are returned createdAt DESC; callers keep the first (most-recent) per owner.
     */
    @Query("""
           SELECT s FROM Subscription s
           JOIN FETCH s.plan p
           JOIN FETCH s.billingCustomer bc
           JOIN FETCH bc.user u
           WHERE u.id IN :ownerIds
             AND s.status IN ('trialing','active')
           ORDER BY s.createdAt DESC
           """)
    List<Subscription> findActiveByOwnerUserIds(@Param("ownerIds") java.util.Collection<Long> ownerIds);

    // Parcours relationnel correct (avec "_")
    List<Subscription> findByBillingCustomer_IdOrderByCreatedAtDesc(Long customerId);

    @Query("""
           SELECT s FROM Subscription s
           WHERE s.currentPeriodEnd BETWEEN :start AND :end
             AND s.status IN ('trialing','active')
           """)
    List<Subscription> findExpiringSoon(@Param("start") LocalDateTime start,
                                        @Param("end") LocalDateTime end);

    List<Subscription> findByStatus(String status);

    Optional<Subscription> findByBillingCustomer_User_Id(Long userId);

    boolean existsByBillingCustomer_User_Id(Long userId);

    // Methode flexible pour recuperer l'abonnement actif avec plusieurs statuts
    @Query("""
           SELECT s FROM Subscription s
           WHERE s.billingCustomer.user.id = :userId
             AND s.status IN :statuses
           ORDER BY s.createdAt DESC
           """)
    Optional<Subscription> findTopByBillingCustomer_User_IdAndStatusInOrderByCreatedAtDesc(
        @Param("userId") Long userId, 
        @Param("statuses") List<String> statuses
    );

    List<Subscription> findByBillingCustomer_User_IdAndStatusInOrderByCreatedAtDesc(Long userId, List<String> statuses);
    @Lock(LockModeType.NONE)
    Optional<Subscription> findByProviderSubscriptionId(@Param("providerSubscriptionId") String providerSubscriptionId);

    @Lock(LockModeType.NONE)
    boolean existsByProviderSubscriptionId(@Param("providerSubscriptionId") String providerSubscriptionId);

    // (optionnel) lecture ultra-legere pour fallback
    @Query("select s.id from Subscription s where s.providerSubscriptionId = :providerSubscriptionId")
    @Lock(LockModeType.NONE)
    Optional<Long> findIdByProviderSubscriptionIdLight(@Param("providerSubscriptionId") String providerSubscriptionId);

    // --- WRITE with LOCK (use only in write methods) ---
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Subscription s where s.providerSubscriptionId = :providerSubscriptionId")
    Optional<Subscription> findForUpdateByProviderSubscriptionId(@Param("providerSubscriptionId") String providerSubscriptionId);

    /**
     * Paginated PESSIMISTIC_WRITE variant. Same single-row semantics as
     * {@link #findActiveByUserId}: returns the most-recent active sub under
     * FOR UPDATE.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
           SELECT s FROM Subscription s
           WHERE s.billingCustomer.user.id = :userId
             AND s.status IN ('trialing','active')
           ORDER BY s.createdAt DESC
           """)
    java.util.List<Subscription> findActiveByUserIdForUpdateAllScopes(@Param("userId") Long userId,
                                                                      org.springframework.data.domain.Pageable pageable);

    /**
     * Back-compat single-result wrapper around
     * {@link #findActiveByUserIdForUpdateAllScopes}. Inside a @Transactional
     * method for credit deduction/grant - prevents lost updates via the
     * PESSIMISTIC_WRITE on the most-recent active sub row.
     */
    default Optional<Subscription> findActiveByUserIdForUpdate(Long userId) {
        java.util.List<Subscription> rows = findActiveByUserIdForUpdateAllScopes(userId,
                org.springframework.data.domain.PageRequest.of(0, 1));
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /**
     * Expired non-Stripe subscriptions due for an internal monthly renewal.
     *
     * <p>Keyed on {@code provider='internal'} (NOT {@code plan.code='FREE'}) so it covers
     * BOTH the FREE plan AND admin-granted comp Starter/Pro/Team rows, which are also
     * {@code provider='internal'}. Stripe subs ({@code provider='stripe'}) renew via
     * {@code invoice.paid} webhooks and are intentionally excluded. The per-plan credit
     * amount is decided downstream by {@code CreditAttributionService.attributeOnRenewal}
     * (FREE → 1K plan-included, comp → 5K tier-0 base).
     */
    @Query("""
           SELECT s FROM Subscription s
           JOIN FETCH s.billingCustomer bc
           JOIN FETCH bc.user
           JOIN FETCH s.plan p
           WHERE s.provider = 'internal'
             AND s.status IN ('active', 'trialing')
             AND s.currentPeriodEnd <= :now
           """)
    List<Subscription> findExpiredInternalSubscriptions(@Param("now") LocalDateTime now);
}
