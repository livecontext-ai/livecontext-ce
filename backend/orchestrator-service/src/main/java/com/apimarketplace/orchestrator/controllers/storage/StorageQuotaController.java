package com.apimarketplace.orchestrator.controllers.storage;

import com.apimarketplace.common.storage.domain.OrgStorageBreakdown;
import com.apimarketplace.common.storage.domain.OrgStorageUsageHistory;
import com.apimarketplace.common.storage.domain.OrganizationStorageQuota;
import com.apimarketplace.common.storage.domain.QuotaStatus;
import com.apimarketplace.common.storage.domain.StorageUsageHistory;
import com.apimarketplace.common.storage.domain.TenantStorageBreakdown;
import com.apimarketplace.common.storage.domain.TenantStorageQuota;
import com.apimarketplace.common.storage.service.QuotaService;
import com.apimarketplace.common.storage.service.api.QuotaOperations;
import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.apimarketplace.common.web.AppEditionProvider;
import com.apimarketplace.orchestrator.services.storage.StorageHistoryService;
import com.apimarketplace.orchestrator.services.storage.StorageReconciliationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST controller for storage quota operations.
 *
 * <p>When {@code X-Organization-ID} is set, read endpoints return organization
 * scoped data. Without it, they return the caller's personal tenant scope.</p>
 */
@RestController
@RequestMapping("/api/storage/quota")
public class StorageQuotaController {

    private static final Logger logger = LoggerFactory.getLogger(StorageQuotaController.class);

    /** Mirrors QuotaService.DEFAULT_SOFT_LIMIT_RATIO: the amber warning fires at 80% of the pool. */
    private static final double SOFT_LIMIT_RATIO = 0.8;

    private final QuotaService quotaService;
    private final StorageBreakdownService breakdownService;
    private final StorageReconciliationService reconciliationService;
    private final StorageHistoryService historyService;
    private final AppEditionProvider editionProvider;

    @Autowired
    public StorageQuotaController(QuotaService quotaService,
                                  StorageBreakdownService breakdownService,
                                  StorageReconciliationService reconciliationService,
                                  StorageHistoryService historyService,
                                  AppEditionProvider editionProvider) {
        this.quotaService = quotaService;
        this.breakdownService = breakdownService;
        this.reconciliationService = reconciliationService;
        this.historyService = historyService;
        this.editionProvider = editionProvider;
    }

    StorageQuotaController(QuotaService quotaService,
                           StorageBreakdownService breakdownService,
                           StorageReconciliationService reconciliationService,
                           StorageHistoryService historyService) {
        this(quotaService, breakdownService, reconciliationService, historyService, null);
    }

    public ResponseEntity<StorageQuotaDto> getQuota(String tenantId) {
        return getQuota(tenantId, null);
    }

    /**
     * Get storage quota for the active workspace.
     */
    @GetMapping
    public ResponseEntity<StorageQuotaDto> getQuota(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId) {

        if (isOrgScope(organizationId)) {
            logger.debug("Getting storage quota for org: {}", organizationId);
            reconciliationService.refreshOrgBreakdown(organizationId);
            OrganizationStorageQuota quota = quotaService.getOrganizationQuota(organizationId);
            return ResponseEntity.ok(toDto(quota, tenantId));
        }

        logger.debug("Getting storage quota for tenant: {}", tenantId);
        reconciliationService.refreshTenantBreakdown(tenantId);
        TenantStorageQuota quota = quotaService.getQuota(tenantId);
        return ResponseEntity.ok(toDto(quota));
    }

    /**
     * Get per-category storage breakdown for the active workspace.
     */
    @GetMapping("/breakdown")
    public ResponseEntity<List<StorageBreakdownDto>> getBreakdown(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId) {

        if (isOrgScope(organizationId)) {
            logger.debug("Getting storage breakdown for org: {}", organizationId);
            // 2026-05-21 fix - refresh local categories before read so the
            // numbers match the gauge (which computes fresh from storage.storage).
            // Pre-fix, the breakdown only repopulated at the 02:30 UTC daily
            // reconciliation, so the displayed 4.8MB could lag the actual 435MB
            // by hours. Throttled to 30s so dashboard polling doesn't hammer the
            // pg_column_size aggregates.
            reconciliationService.refreshOrgBreakdown(organizationId);
            List<OrgStorageBreakdown> orgBreakdown = breakdownService.getOrgBreakdown(organizationId);
            return ResponseEntity.ok(orgBreakdown.stream()
                    .map(b -> new StorageBreakdownDto(
                            b.getCategory(),
                            Math.max(0L, b.getUsedBytes()),
                            Math.max(0, b.getItemCount()),
                            b.getCalculatedAt()))
                    .toList());
        }

        logger.debug("Getting storage breakdown for tenant: {}", tenantId);
        reconciliationService.refreshTenantBreakdown(tenantId);
        List<TenantStorageBreakdown> breakdown = breakdownService.getBreakdown(tenantId);
        return ResponseEntity.ok(breakdown.stream()
                .map(b -> new StorageBreakdownDto(
                        b.getCategory(),
                        Math.max(0L, b.getUsedBytes()),
                        Math.max(0, b.getItemCount()),
                        b.getCalculatedAt()))
                .toList());
    }

    /**
     * Force full reconciliation for the active workspace.
     */
    @PostMapping("/recalculate")
    public ResponseEntity<StorageQuotaDto> recalculateUsage(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId) {

        if (isOrgScope(organizationId)) {
            logger.info("Full org reconciliation for organization: {}", organizationId);
            // reconcileOrganization refreshes the gauge itself, the way reconcileTenant does.
            reconciliationService.reconcileOrganization(organizationId);
            OrganizationStorageQuota quota = quotaService.getOrganizationQuota(organizationId);
            return ResponseEntity.ok(toDto(quota, tenantId));
        }

        logger.info("Full reconciliation for tenant: {}", tenantId);
        reconciliationService.reconcileTenant(tenantId);
        TenantStorageQuota quota = quotaService.getQuota(tenantId);
        return ResponseEntity.ok(toDto(quota));
    }

    /**
     * Get daily storage usage history for the active workspace.
     */
    @GetMapping("/history")
    public ResponseEntity<List<StorageHistoryDto>> getHistory(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
            @RequestParam(defaultValue = "30") int days) {

        if (isOrgScope(organizationId)) {
            logger.debug("Getting storage history for org: {} days: {}", organizationId, days);
            // 2026-05-22 user-reported fix - bootstrap history if empty so
            // a freshly-created or just-populated workspace shows today's
            // datapoint immediately instead of waiting for the daily
            // 02:30 UTC cron to snapshot. Pre-fix: trend chart displayed
            // 0MB even though the gauge reported 435MB because
            // org_storage_usage_history had no rows yet.
            //
            // Order matters: refresh the breakdown first (so the snapshot
            // captures current state), then snapshot if empty.
            reconciliationService.refreshOrgBreakdown(organizationId);
            List<OrgStorageUsageHistory> history = historyService.getOrgHistory(organizationId, days);
            if (history.isEmpty()) {
                try {
                    historyService.snapshotOrganization(organizationId);
                    history = historyService.getOrgHistory(organizationId, days);
                    logger.info("Bootstrapped storage history for org={}, days={}, rows={}",
                            organizationId, days, history.size());
                } catch (Exception e) {
                    logger.warn("Bootstrap snapshot failed for org={}: {}", organizationId, e.getMessage());
                }
            }
            return ResponseEntity.ok(history.stream()
                    .map(h -> new StorageHistoryDto(
                            h.getSnapshotDate().toString(),
                            h.getCategory(),
                            Math.max(0L, h.getUsedBytes()),
                            Math.max(0, h.getItemCount())))
                    .toList());
        }

        logger.debug("Getting storage history for tenant: {} days: {}", tenantId, days);
        // Same treatment as the org branch above, for the same reason: refresh the local
        // categories first so today's point reflects current state, then bootstrap if the
        // table is still empty rather than drawing a flat 0 until the 02:30 UTC cron. The two
        // branches differing here is how the same workspace could show a populated trend in one
        // scope and an empty one in the other.
        reconciliationService.refreshTenantBreakdown(tenantId);
        List<StorageUsageHistory> history = historyService.getHistory(tenantId, days);
        if (history.isEmpty()) {
            try {
                historyService.snapshotTenant(tenantId);
                history = historyService.getHistory(tenantId, days);
                logger.info("Bootstrapped storage history for tenant={}, days={}, rows={}",
                        tenantId, days, history.size());
            } catch (Exception e) {
                logger.warn("Bootstrap snapshot failed for tenant={}: {}", tenantId, e.getMessage());
            }
        }
        return ResponseEntity.ok(history.stream()
                .map(h -> new StorageHistoryDto(
                        h.getSnapshotDate().toString(),
                        h.getCategory(),
                        Math.max(0L, h.getUsedBytes()),
                        Math.max(0, h.getItemCount())))
                .toList());
    }

    private StorageQuotaDto toDto(TenantStorageQuota quota) {
        return new StorageQuotaDto(
                quota.getTenantId(),
                quota.getUsedBytes(),
                quota.getMaxBytes(),
                quota.getSoftLimitBytes(),
                quota.getHardLimitBytes(),
                quota.getAvailableBytes(),
                quota.getUsagePercentage(),
                quota.getQuotaStatus(),
                isCeEdition());
    }

    /**
     * @param callerUserId who is asking. The account total is returned ONLY to the account that
     *                     owns the workspace: inside a shared workspace it would tell an invited
     *                     member how much the owner stores elsewhere, and that workspaces they
     *                     cannot see exist at all. Hiding it in the UI alone would not be a
     *                     boundary, since the response is readable in devtools.
     */
    private StorageQuotaDto toDto(OrganizationStorageQuota quota, String callerUserId) {
        // ONE resolution of the pool, the same the write gate uses. Reading the total from the
        // service and the ceiling from this row would let the page declare an account full while
        // the gate accepts the upload; that mismatch is why the ceiling travels with the total.
        QuotaOperations.AccountPool pool = quotaService.getAccountPool(quota.getOrganizationId());
        boolean callerOwnsTheAccount = pool != null
                && callerUserId != null
                && callerUserId.equals(pool.accountId());
        // Two different questions, answered differently on purpose.
        //
        // The ALLOWANCE fields describe what the gate permits and are the same for everyone in
        // the workspace: with a pool they must all describe the account's single allowance, or a
        // client reading hardLimitBytes would size its bar differently from one reading maxBytes.
        //
        // The USAGE-derived fields are the account's consumption wearing another hat.
        // `availableBytes` and `usagePercentage` are one subtraction away from the account total
        // that `accountUsedBytes` deliberately withholds from members, so they follow the same
        // gate. A member keeps the row-derived pair plus the pool-decided `status`, which tells
        // them they are blocked without telling them by how much or by whom.
        long ceiling = pool != null ? pool.maxBytes() : quota.getMaxBytes();
        boolean maySeeAccountUsage = pool != null && callerOwnsTheAccount;
        long poolUsed = pool != null ? pool.usedBytes() : quota.getUsedBytes();
        return new StorageQuotaDto(
                quota.getOrganizationId(),
                quota.getUsedBytes(),
                ceiling,
                pool != null ? (long) (ceiling * SOFT_LIMIT_RATIO) : quota.getSoftLimitBytes(),
                pool != null ? ceiling : quota.getHardLimitBytes(),
                maySeeAccountUsage ? Math.max(0L, ceiling - poolUsed) : quota.getAvailableBytes(),
                maySeeAccountUsage && ceiling > 0
                        ? Math.min(100.0, (poolUsed * 100.0) / ceiling)
                        : quota.getUsagePercentage(),
                // Status is decided on the POOL, for everyone. It is what the write gate will do,
                // so a member has to see "full" even though they never see the figure behind it;
                // a green bar right up to a refusal is the exact lie this feature removes.
                poolAwareStatus(quota, pool),
                isCeEdition(),
                callerOwnsTheAccount ? pool.usedBytes() : null);
    }

    /**
     * The status the write gate would give: measured on the account's total and the account's
     * enforced ceiling when the workspace belongs to a pool, and on the workspace alone
     * otherwise (unattributed rows are still enforced per-workspace).
     */
    private QuotaStatus poolAwareStatus(OrganizationStorageQuota quota, QuotaOperations.AccountPool pool) {
        if (pool == null || pool.maxBytes() <= 0) {
            return quota.getQuotaStatus();
        }
        if (pool.usedBytes() >= pool.maxBytes()) {
            return QuotaStatus.HARD_LIMIT_REACHED;
        }
        return pool.usedBytes() >= (long) (pool.maxBytes() * SOFT_LIMIT_RATIO)
                ? QuotaStatus.SOFT_LIMIT_REACHED
                : QuotaStatus.OK;
    }

    private boolean isCeEdition() {
        return editionProvider != null && editionProvider.isCe();
    }

    private static boolean isOrgScope(String organizationId) {
        return organizationId != null && !organizationId.isBlank();
    }
}
