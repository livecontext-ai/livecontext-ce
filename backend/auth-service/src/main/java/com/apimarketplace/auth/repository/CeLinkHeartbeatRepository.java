package com.apimarketplace.auth.repository;

import com.apimarketplace.auth.domain.CeLink;
import com.apimarketplace.auth.domain.CeLinkHeartbeat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Repository for {@link CeLinkHeartbeat}. Hot table - every heartbeat call
 * upserts; reads are dominated by the {@code GET /mine} join (PR3b).
 */
@Repository
public interface CeLinkHeartbeatRepository extends JpaRepository<CeLinkHeartbeat, UUID> {

    /**
     * Used by {@code CeLinkRetentionScheduler} to find heartbeats not seen
     * since {@code cutoff} whose parent ce_link is still ACTIVE. The join is
     * cheap - both tables are keyed by install_id (PK) and the heartbeat row
     * carries the {@code last_seen_at} index.
     */
    @Query("SELECT h FROM CeLinkHeartbeat h, CeLink c "
         + "WHERE h.installId = c.installId "
         + "AND c.status = com.apimarketplace.auth.domain.CeLink.Status.ACTIVE "
         + "AND h.lastSeenAt < :cutoff")
    List<CeLinkHeartbeat> findStaleActive(@Param("cutoff") Instant cutoff);
}
