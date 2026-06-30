package com.apimarketplace.orchestrator.controllers.storage;

import com.apimarketplace.common.storage.domain.OrgStorageBreakdown;
import com.apimarketplace.common.storage.domain.OrgStorageUsageHistory;
import com.apimarketplace.common.storage.domain.OrganizationStorageQuota;
import com.apimarketplace.common.storage.domain.StorageUsageHistory;
import com.apimarketplace.common.storage.domain.TenantStorageBreakdown;
import com.apimarketplace.common.storage.domain.TenantStorageQuota;
import com.apimarketplace.common.storage.service.QuotaService;
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
            return ResponseEntity.ok(toDto(quota));
        }

        logger.debug("Getting storage quota for tenant: {}", tenantId);
        reconciliationService.refreshExecutionData(tenantId);
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
        reconciliationService.refreshExecutionData(tenantId);
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
            reconciliationService.reconcileOrganization(organizationId);
            quotaService.updateOrganizationUsage(organizationId);
            OrganizationStorageQuota quota = quotaService.getOrganizationQuota(organizationId);
            return ResponseEntity.ok(toDto(quota));
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
        List<StorageUsageHistory> history = historyService.getHistory(tenantId, days);
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

    private StorageQuotaDto toDto(OrganizationStorageQuota quota) {
        return new StorageQuotaDto(
                quota.getOrganizationId(),
                quota.getUsedBytes(),
                quota.getMaxBytes(),
                quota.getSoftLimitBytes(),
                quota.getHardLimitBytes(),
                quota.getAvailableBytes(),
                quota.getUsagePercentage(),
                quota.getQuotaStatus(),
                isCeEdition());
    }

    private boolean isCeEdition() {
        return editionProvider != null && editionProvider.isCe();
    }

    private static boolean isOrgScope(String organizationId) {
        return organizationId != null && !organizationId.isBlank();
    }
}
