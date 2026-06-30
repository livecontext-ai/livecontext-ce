package com.apimarketplace.common.storage.repository;

import com.apimarketplace.common.storage.domain.StorageUsageHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * Repository for storage usage history (daily snapshots).
 */
@Repository
public interface StorageUsageHistoryRepository extends JpaRepository<StorageUsageHistory, Long> {

    /**
     * Get history for a tenant within date range, ordered by date.
     */
    @Query("SELECT h FROM StorageUsageHistory h WHERE h.tenantId = :tenantId AND h.snapshotDate >= :fromDate ORDER BY h.snapshotDate")
    List<StorageUsageHistory> findByTenantIdAndDateRange(
            @Param("tenantId") String tenantId,
            @Param("fromDate") LocalDate fromDate);

    /**
     * UPSERT a daily snapshot (idempotent - safe to run multiple times per day).
     * GREATEST(..., 0) mirrors the clamp on tenant_storage_breakdown so a future
     * caller bypassing the Java-side Math.max in StorageHistoryService.snapshotTenant
     * cannot persist a negative row (which would violate V184's CHECK constraint).
     *
     * <p>This table is OUT OF V261 NOT NULL scope by design (V259 line 10-12).
     * The parallel {@code OrgStorageUsageHistoryRepository} carries org-scoped
     * snapshots - use it for scope-aware history queries.</p>
     */
    @Modifying
    @Transactional
    @Query(value = """
        INSERT INTO storage.storage_usage_history (tenant_id, category, used_bytes, item_count, snapshot_date, created_at)
        VALUES (:tenantId, :category, GREATEST(:usedBytes, 0), GREATEST(:itemCount, 0), :snapshotDate, now())
        ON CONFLICT (tenant_id, category, snapshot_date)
        DO UPDATE SET used_bytes = GREATEST(:usedBytes, 0),
                      item_count = GREATEST(:itemCount, 0),
                      created_at = now()
        """, nativeQuery = true)
    void upsertSnapshot(@Param("tenantId") String tenantId,
                        @Param("category") String category,
                        @Param("usedBytes") long usedBytes,
                        @Param("itemCount") int itemCount,
                        @Param("snapshotDate") LocalDate snapshotDate);

    /**
     * Purge old history beyond retention period.
     */
    @Modifying
    @Transactional
    @Query(value = "DELETE FROM storage.storage_usage_history WHERE snapshot_date < :cutoffDate", nativeQuery = true)
    int deleteBySnapshotDateBefore(@Param("cutoffDate") LocalDate cutoffDate);

    /**
     * Get distinct tenant IDs that have breakdown data (for batch snapshot job).
     */
    @Query(value = "SELECT DISTINCT tenant_id FROM storage.tenant_storage_breakdown", nativeQuery = true)
    List<String> findDistinctTenantIds();
}
