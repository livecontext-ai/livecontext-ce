package com.apimarketplace.orchestrator.repository;

import com.apimarketplace.orchestrator.domain.FlagFlipAuditEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;

@Repository
public interface FlagFlipAuditRepository extends JpaRepository<FlagFlipAuditEntity, Long> {

    /**
     * Chunked retention purge - caller invokes in a loop until {@code 0} returned.
     * Bounded {@code LIMIT} avoids long-held row locks on a hot purge.
     *
     * <p>Modeled on {@link NotificationRepository#deletePurgeBatch} - uses a CTE
     * because Postgres requires the LIMIT inside the SELECT, not at the DELETE
     * level. {@code orchestrator.idx_flag_flip_audit_created_at} drives the scan.
     */
    @Modifying
    @Query(value = "DELETE FROM orchestrator.flag_flip_audit " +
            "WHERE id IN (" +
            "  SELECT id FROM orchestrator.flag_flip_audit " +
            "  WHERE created_at < :cutoff " +
            "  ORDER BY created_at " +
            "  LIMIT :batchSize" +
            ")", nativeQuery = true)
    int deletePurgeBatch(@Param("cutoff") Instant cutoff, @Param("batchSize") int batchSize);
}
