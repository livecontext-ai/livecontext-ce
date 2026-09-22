package com.apimarketplace.common.storage.repository;

import com.apimarketplace.common.storage.domain.OrganizationStorageQuota;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Per-org storage quota repository. Mirror of
 * {@link TenantStorageQuotaRepository} for the org dimension.
 */
@Repository
public interface OrganizationStorageQuotaRepository
        extends JpaRepository<OrganizationStorageQuota, String> {

    Optional<OrganizationStorageQuota> findByOrganizationId(String organizationId);

    @Modifying
    @Query("UPDATE OrganizationStorageQuota q SET q.usedBytes = :usedBytes, "
         + "q.updatedAt = :updatedAt WHERE q.organizationId = :orgId")
    int updateUsedBytes(@Param("orgId") String organizationId,
                        @Param("usedBytes") Long usedBytes,
                        @Param("updatedAt") Instant updatedAt);

    @Modifying
    @Query("UPDATE OrganizationStorageQuota q SET q.maxBytes = :maxBytes, "
         + "q.softLimitBytes = :softLimitBytes, q.hardLimitBytes = :hardLimitBytes, "
         + "q.updatedAt = :updatedAt WHERE q.organizationId = :orgId")
    int updateLimits(@Param("orgId") String organizationId,
                     @Param("maxBytes") Long maxBytes,
                     @Param("softLimitBytes") Long softLimitBytes,
                     @Param("hardLimitBytes") Long hardLimitBytes,
                     @Param("updatedAt") Instant updatedAt);

    /**
     * Everything one account currently stores, across every workspace it owns. This is the
     * figure the shared allowance is enforced against: the plan grants N bytes to the ACCOUNT,
     * not N bytes to each of its workspaces.
     *
     * <p>Sums the per-workspace gauges rather than re-reading {@code storage.storage}, which is
     * the same source the single-workspace check has always trusted and keeps this on a write
     * path cheap (an indexed sum over at most a plan's workspace cap). It cannot double count:
     * each row totals only its own workspace's rows.
     */
    @Query("SELECT COALESCE(SUM(q.usedBytes), 0) FROM OrganizationStorageQuota q "
         + "WHERE q.accountId = :accountId")
    long sumUsedBytesForAccount(@Param("accountId") String accountId);

    /**
     * The account's ceiling, taken from its MOST RECENTLY UPDATED workspace row.
     *
     * <p>Every row of an account normally carries the same plan allowance, written together by
     * the syncer, but they can disagree in two opposite ways and a single rule has to survive
     * both:
     * <ul>
     *   <li>a row LAGGING LOW: materialised by the lazy default path, or a syncer write that
     *       failed and was swallowed, so it still holds the 100 MB FREE default. Reading the
     *       ceiling off that row would measure the account's whole footprint against 100 MB and
     *       refuse a paying customer;</li>
     *   <li>a row LAGGING HIGH: a downgrade that only half-applied. Taking the maximum would let
     *       that one row keep the old, larger allowance alive for every workspace, which makes a
     *       downgrade non-binding account-wide.</li>
     * </ul>
     * Recency answers both: the freshest write is the one that reflects the plan as it stands,
     * whichever direction it moved.
     *
     * <p><b>Ordered on {@code limitsUpdatedAt}, never {@code updatedAt}.</b> The latter is bumped
     * by every usage write, so it identifies the workspace most recently written TO, which says
     * nothing about whose allowance is current, and it fails viciously: one small upload into a
     * stale 100 MB workspace would make that row the freshest, drop the whole account's ceiling
     * to 100 MB, refuse every workspace at once, and leave no way out, because repairing it needs
     * a write that is now blocked. Rows predating the column sort last rather than winning on a
     * null.
     */
    @Query("SELECT q.hardLimitBytes FROM OrganizationStorageQuota q "
         + "WHERE q.accountId = :accountId AND q.hardLimitBytes IS NOT NULL "
         + "AND q.limitsUpdatedAt IS NOT NULL "
         + "ORDER BY q.limitsUpdatedAt DESC, q.organizationId ASC")
    List<Long> ceilingsForAccountByRecency(@Param("accountId") String accountId, Pageable pageable);

    /**
     * The largest allowance among the account's workspaces. The fallback for when none of them
     * carries an allowance clock yet.
     */
    @Query("SELECT COALESCE(MAX(q.hardLimitBytes), 0) FROM OrganizationStorageQuota q "
         + "WHERE q.accountId = :accountId")
    long maxHardLimitForAccount(@Param("accountId") String accountId);

    /**
     * The ceiling to enforce for this account, or empty when it owns no attributed workspace.
     *
     * <p>The freshest ALLOWANCE wins. Where no row has been stamped yet, which is every row
     * migrated in before this column existed, it falls back to the LARGEST allowance rather than
     * to an arbitrary row. That choice is not cosmetic: picking arbitrarily can land on a
     * workspace still holding the 100 MB default and refuse an entire paying account, whereas
     * the maximum can only ever be too generous, and only until the account's next real
     * allowance write settles it exactly.
     */
    default Optional<Long> currentCeilingForAccount(String accountId) {
        List<Long> top = ceilingsForAccountByRecency(accountId, PageRequest.of(0, 1));
        if (!top.isEmpty() && top.get(0) != null) {
            return Optional.of(top.get(0));
        }
        long widest = maxHardLimitForAccount(accountId);
        return widest > 0 ? Optional.of(widest) : Optional.empty();
    }
}
