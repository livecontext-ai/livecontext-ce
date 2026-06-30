package com.apimarketplace.orchestrator.repository;

import com.apimarketplace.orchestrator.domain.NotificationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;

@Repository
public interface NotificationRepository extends JpaRepository<NotificationEntity, Long> {

    /**
     * Chunked retention purge - caller invokes in a loop until {@code 0} returned.
     * Bounded {@code LIMIT} avoids long-held row locks on hot-tenant tables
     * (uses a CTE because Postgres requires the LIMIT to be inside the SELECT).
     */
    @Modifying
    @Query(value = "DELETE FROM orchestrator.notifications " +
            "WHERE id IN (" +
            "  SELECT id FROM orchestrator.notifications " +
            "  WHERE occurred_at < :cutoff " +
            "  ORDER BY occurred_at " +
            "  LIMIT :batchSize)",
            nativeQuery = true)
    int deleteOlderThanChunked(@Param("cutoff") Instant cutoff,
                               @Param("batchSize") int batchSize);
}
