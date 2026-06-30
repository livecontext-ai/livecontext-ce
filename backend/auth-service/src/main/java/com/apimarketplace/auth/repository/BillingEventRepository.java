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

    // Variante plus precise (si le JSON contient payload->'userId'):
    // @Query(value = "SELECT * FROM auth.billing_event be WHERE be.payload->>'userId' = :userId ORDER BY be.received_at DESC", nativeQuery = true)
    // List<BillingEvent> findPaygUsageByUserId(@Param("userId") String userId);
}
