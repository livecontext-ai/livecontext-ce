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
     * Atomic UPSERT: increment used_bytes and item_count for an org-category row, clamped at zero.
     * Lock-free thanks to {@code ON CONFLICT}.
     *
     * <p>The {@code GREATEST(..., 0)} is the same guard the tenant twin has carried since V184, and
     * it was missing here. Unlike that twin, this table also has no {@code CHECK (used_bytes >= 0)},
     * so the SQL was the only place a guard could live and there was none.
     *
     * <p>What a negative row costs TODAY is the displayed categories and item counts, not the
     * gauge: {@code QuotaService.updateOrganizationUsage} reads a fresh {@code SUM(size_bytes)}
     * over {@code storage.storage}, which cannot go below zero. That is worth knowing before
     * anyone changes it, because deriving the gauge from these rows was tried and reverted for
     * exactly this reason, and {@code OrganizationStorageQuota.canStore} computes
     * {@code hardLimit - usedBytes}, so a negative total there would GRANT storage rather than
     * refuse it. The clamp is what makes that direction safe to revisit.
     *
     * <p>Reaching a negative was not theoretical: until 2026-09-18, deleting an S3-backed step
     * output debited {@code STEP_OUTPUTS} while the save had credited {@code FILES}, so the
     * debited bucket was one nothing had ever credited. No repair migration ships with this,
     * because {@code reconcileOrganization} absolutely sets all four org categories nightly.
     */
    @Modifying
    @Transactional
    @Query(value = """
        INSERT INTO storage.org_storage_breakdown (organization_id, category, used_bytes, item_count, calculated_at)
        VALUES (:organizationId, :category, GREATEST(:deltaBytes, 0), GREATEST(:deltaCount, 0), now())
        ON CONFLICT (organization_id, category)
        DO UPDATE SET used_bytes = GREATEST(storage.org_storage_breakdown.used_bytes + :deltaBytes, 0),
                      item_count = GREATEST(storage.org_storage_breakdown.item_count + :deltaCount, 0),
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

    /**
     * Every organization that has breakdown rows. Same role as the tenant twin: it is what the
     * daily history snapshot copies, and enumerating the history table instead meant an
     * organization without a history row could never acquire one.
     */
    @Query(value = "SELECT DISTINCT organization_id FROM storage.org_storage_breakdown", nativeQuery = true)
    List<String> findDistinctOrganizationIds();
}
