package com.apimarketplace.common.storage.repository;

import com.apimarketplace.common.storage.domain.OrgStorageBreakdown;
import com.apimarketplace.common.storage.domain.OrgStorageBreakdownId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Repository for {@link OrgStorageBreakdown} with the same atomic-UPSERT
 * primitives as {@link TenantStorageBreakdownRepository}, keyed on
 * {@code organization_id} (Issue #149).
 */
@Repository
public interface OrgStorageBreakdownRepository extends JpaRepository<OrgStorageBreakdown, OrgStorageBreakdownId> {

    List<OrgStorageBreakdown> findByOrganizationId(String organizationId);

    /**
     * Atomic UPSERT: increment used_bytes and item_count for an org-category row.
     * Lock-free thanks to {@code ON CONFLICT}.
     */
    @Modifying
    @Transactional
    @Query(value = """
        INSERT INTO storage.org_storage_breakdown (organization_id, category, used_bytes, item_count, calculated_at)
        VALUES (:organizationId, :category, :deltaBytes, :deltaCount, now())
        ON CONFLICT (organization_id, category)
        DO UPDATE SET used_bytes = storage.org_storage_breakdown.used_bytes + :deltaBytes,
                      item_count = storage.org_storage_breakdown.item_count + :deltaCount,
                      calculated_at = now()
        """, nativeQuery = true)
    void incrementUsage(@Param("organizationId") String organizationId,
                        @Param("category") String category,
                        @Param("deltaBytes") long deltaBytes,
                        @Param("deltaCount") int deltaCount);

    /**
     * Absolute set for reconciliation - overwrites the current row.
     */
    @Modifying
    @Transactional
    @Query(value = """
        INSERT INTO storage.org_storage_breakdown (organization_id, category, used_bytes, item_count, calculated_at)
        VALUES (:organizationId, :category, :usedBytes, :itemCount, now())
        ON CONFLICT (organization_id, category)
        DO UPDATE SET used_bytes = :usedBytes,
                      item_count = :itemCount,
                      calculated_at = now()
        """, nativeQuery = true)
    void setUsage(@Param("organizationId") String organizationId,
                  @Param("category") String category,
                  @Param("usedBytes") long usedBytes,
                  @Param("itemCount") int itemCount);
}
