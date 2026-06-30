package com.apimarketplace.common.storage.repository;

import com.apimarketplace.common.storage.domain.OrgStorageUsageHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * Repository for {@link OrgStorageUsageHistory} - daily snapshots keyed on
 * {@code organization_id} (Issue #149).
 */
@Repository
public interface OrgStorageUsageHistoryRepository extends JpaRepository<OrgStorageUsageHistory, Long> {

    /**
     * Get history for an org within date range, ordered by date.
     */
    @Query("SELECT h FROM OrgStorageUsageHistory h WHERE h.organizationId = :organizationId AND h.snapshotDate >= :fromDate ORDER BY h.snapshotDate")
    List<OrgStorageUsageHistory> findByOrganizationIdAndDateRange(
            @Param("organizationId") String organizationId,
            @Param("fromDate") LocalDate fromDate);

    /**
     * UPSERT a daily snapshot (idempotent - safe to re-run within the same day).
     */
    @Modifying
    @Transactional
    @Query(value = """
        INSERT INTO storage.org_storage_usage_history (organization_id, category, used_bytes, item_count, snapshot_date, created_at)
        VALUES (:organizationId, :category, :usedBytes, :itemCount, :snapshotDate, now())
        ON CONFLICT (organization_id, category, snapshot_date)
        DO UPDATE SET used_bytes = :usedBytes,
                      item_count = :itemCount,
                      created_at = now()
        """, nativeQuery = true)
    void upsertSnapshot(@Param("organizationId") String organizationId,
                        @Param("category") String category,
                        @Param("usedBytes") long usedBytes,
                        @Param("itemCount") int itemCount,
                        @Param("snapshotDate") LocalDate snapshotDate);

    /**
     * Purge old history beyond retention period.
     */
    @Modifying
    @Transactional
    @Query(value = "DELETE FROM storage.org_storage_usage_history WHERE snapshot_date < :cutoffDate", nativeQuery = true)
    int deleteBySnapshotDateBefore(@Param("cutoffDate") LocalDate cutoffDate);

    /**
     * Get distinct org IDs that have breakdown data (for the daily snapshot job).
     */
    @Query(value = "SELECT DISTINCT organization_id FROM storage.org_storage_breakdown", nativeQuery = true)
    List<String> findDistinctOrganizationIds();
}
