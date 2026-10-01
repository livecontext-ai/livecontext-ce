package com.apimarketplace.auth.repository;

import com.apimarketplace.auth.domain.PersonalOfferCheckoutAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;

public interface PersonalOfferCheckoutAttemptRepository extends JpaRepository<PersonalOfferCheckoutAttempt, UUID> {
    Optional<PersonalOfferCheckoutAttempt> findByStripeSessionId(String stripeSessionId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from PersonalOfferCheckoutAttempt a where a.stripeSessionId = :sessionId")
    Optional<PersonalOfferCheckoutAttempt> lockByStripeSessionId(@Param("sessionId") String sessionId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from PersonalOfferCheckoutAttempt a where a.id = :id")
    Optional<PersonalOfferCheckoutAttempt> lockById(@Param("id") UUID id);
    Optional<PersonalOfferCheckoutAttempt> findByStripeSubscriptionId(String stripeSubscriptionId);
    List<PersonalOfferCheckoutAttempt> findByRecipientUserIdOrderByCreatedAtDesc(Long recipientUserId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from PersonalOfferCheckoutAttempt a where a.recipientUserId = :userId and a.status in ('CREATING','OPEN','COMPLETED')")
    List<PersonalOfferCheckoutAttempt> findPayableForUpdate(@Param("userId") Long userId);
    @Query("select a from PersonalOfferCheckoutAttempt a where a.status in ('CREATING','OPEN','COMPLETED','PAID') " +
            "and a.nextReconcileAt <= :now order by a.nextReconcileAt asc, a.createdAt asc")
    List<PersonalOfferCheckoutAttempt> findDueForReconcile(@Param("now") Instant now, Pageable pageable);
    @Modifying
    @Query("update PersonalOfferCheckoutAttempt a set a.nextReconcileAt = :next where a.id = :id " +
            "and a.status in ('CREATING','OPEN','COMPLETED','PAID')")
    int scheduleNextReconcile(@Param("id") UUID id, @Param("next") Instant next);
}
