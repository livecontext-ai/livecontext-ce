package com.apimarketplace.common.storage.repository;

import com.apimarketplace.common.storage.domain.TenantStorageBreakdown;
import com.apimarketplace.common.storage.domain.TenantStorageBreakdownId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Repository for tenant storage breakdown with atomic increment support.
 */
@Repository
public interface TenantStorageBreakdownRepository extends JpaRepository<TenantStorageBreakdown, TenantStorageBreakdownId> {

    List<TenantStorageBreakdown> findByTenantId(String tenantId);

    /**
     * Atomic UPSERT: increment used_bytes and item_count, clamped at zero.
     * GREATEST(..., 0) guards against drift from asymmetric save/delete tracking
     * (e.g. trackDelete with sizeBytes > the original trackSave). Evaluates inside
     * the row lock held by ON CONFLICT, so concurrent increments serialize safely.
     */
    @Modifying
    @Transactional
    @Query(value = """
        INSERT INTO storage.tenant_storage_breakdown (tenant_id, category, used_bytes, item_count, calculated_at)
        VALUES (:tenantId, :category, GREATEST(:deltaBytes, 0), GREATEST(:deltaCount, 0), now())
        ON CONFLICT (tenant_id, category)
        DO UPDATE SET used_bytes = GREATEST(storage.tenant_storage_breakdown.used_bytes + :deltaBytes, 0),
                      item_count = GREATEST(storage.tenant_storage_breakdown.item_count + :deltaCount, 0),
                      calculated_at = now()
        """, nativeQuery = true)
    void incrementUsage(@Param("tenantId") String tenantId,
                        @Param("category") String category,
                        @Param("deltaBytes") long deltaBytes,
                        @Param("deltaCount") int deltaCount);

    /**
     * Absolute set for reconciliation (not increment).
     */
    @Modifying
    @Transactional
    @Query(value = """
        INSERT INTO storage.tenant_storage_breakdown (tenant_id, category, used_bytes, item_count, calculated_at)
        VALUES (:tenantId, :category, :usedBytes, :itemCount, now())
        ON CONFLICT (tenant_id, category)
        DO UPDATE SET used_bytes = :usedBytes,
                      item_count = :itemCount,
                      calculated_at = now()
        """, nativeQuery = true)
    void setUsage(@Param("tenantId") String tenantId,
                  @Param("category") String category,
                  @Param("usedBytes") long usedBytes,
                  @Param("itemCount") int itemCount);

    /**
     * Sum total usage across all categories for a tenant.
     */
    @Query(value = "SELECT COALESCE(SUM(used_bytes), 0) FROM storage.tenant_storage_breakdown WHERE tenant_id = :tenantId",
           nativeQuery = true)
    long sumTotalUsage(@Param("tenantId") String tenantId);
}
