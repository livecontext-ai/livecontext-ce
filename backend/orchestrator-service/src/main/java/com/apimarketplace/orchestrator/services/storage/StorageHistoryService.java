package com.apimarketplace.orchestrator.services.storage;

import com.apimarketplace.common.storage.domain.OrgStorageBreakdown;
import com.apimarketplace.common.storage.domain.OrgStorageUsageHistory;
import com.apimarketplace.common.storage.domain.StorageUsageHistory;
import com.apimarketplace.common.storage.domain.TenantStorageBreakdown;
import com.apimarketplace.common.storage.repository.OrgStorageBreakdownRepository;
import com.apimarketplace.common.storage.repository.OrgStorageUsageHistoryRepository;
import com.apimarketplace.common.storage.repository.StorageUsageHistoryRepository;
import com.apimarketplace.common.storage.repository.TenantStorageBreakdownRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * Service for storage usage history.
 * Manages daily snapshots and retention for both the tenant rollup and the
 * org rollup introduced by Issue #149.
 */
@Service
public class StorageHistoryService {

    private static final Logger log = LoggerFactory.getLogger(StorageHistoryService.class);
    private static final int RETENTION_DAYS = 90;
    private static final int MIN_DAYS = 7;

    private final StorageUsageHistoryRepository historyRepository;
    private final TenantStorageBreakdownRepository breakdownRepository;
    private final OrgStorageUsageHistoryRepository orgHistoryRepository;
    private final OrgStorageBreakdownRepository orgBreakdownRepository;

    @Autowired
    public StorageHistoryService(StorageUsageHistoryRepository historyRepository,
                                 TenantStorageBreakdownRepository breakdownRepository,
                                 OrgStorageUsageHistoryRepository orgHistoryRepository,
                                 OrgStorageBreakdownRepository orgBreakdownRepository) {
        this.historyRepository = historyRepository;
        this.breakdownRepository = breakdownRepository;
        this.orgHistoryRepository = orgHistoryRepository;
        this.orgBreakdownRepository = orgBreakdownRepository;
    }

    StorageHistoryService(StorageUsageHistoryRepository historyRepository,
                          TenantStorageBreakdownRepository breakdownRepository) {
        this(historyRepository, breakdownRepository, null, null);
    }

    /**
     * Get history for a tenant for the given number of days (clamped to {@value MIN_DAYS}-{@value RETENTION_DAYS}).
     */
    public List<StorageUsageHistory> getHistory(String tenantId, int days) {
        int clamped = clampDays(days);
        LocalDate fromDate = LocalDate.now().minusDays(clamped);
        return historyRepository.findByTenantIdAndDateRange(tenantId, fromDate);
    }

    /**
     * Get history for an organization for the given number of days (Issue #149).
     */
    public List<OrgStorageUsageHistory> getOrgHistory(String organizationId, int days) {
        if (orgHistoryRepository == null) {
            return List.of();
        }
        int clamped = clampDays(days);
        LocalDate fromDate = LocalDate.now().minusDays(clamped);
        return orgHistoryRepository.findByOrganizationIdAndDateRange(organizationId, fromDate);
    }

    /**
     * Snapshot a single tenant's current breakdown into history.
     *
     * <p>The {@code storage.storage_usage_history} table is OUT OF V261 NOT NULL
     * scope (V259 line 10-12). Org-aware history goes through
     * {@link #snapshotOrganization(String)} which writes to the parallel
     * {@code OrgStorageUsageHistoryRepository}.</p>
     */
    @Transactional
    public void snapshotTenant(String tenantId) {
        List<TenantStorageBreakdown> breakdown = breakdownRepository.findByTenantId(tenantId);
        LocalDate today = LocalDate.now();
        for (TenantStorageBreakdown b : breakdown) {
            historyRepository.upsertSnapshot(
                    tenantId,
                    b.getCategory(),
                    Math.max(0L, b.getUsedBytes()),
                    Math.max(0, b.getItemCount()),
                    today);
        }
    }

    /**
     * Snapshot a single org's current breakdown into org_storage_usage_history (Issue #149).
     */
    @Transactional
    public void snapshotOrganization(String organizationId) {
        if (orgBreakdownRepository == null || orgHistoryRepository == null) {
            return;
        }
        List<OrgStorageBreakdown> breakdown = orgBreakdownRepository.findByOrganizationId(organizationId);
        LocalDate today = LocalDate.now();
        for (OrgStorageBreakdown b : breakdown) {
            orgHistoryRepository.upsertSnapshot(
                    organizationId,
                    b.getCategory(),
                    Math.max(0L, b.getUsedBytes()),
                    Math.max(0, b.getItemCount()),
                    today);
        }
    }

    /**
     * Daily job: snapshot all tenants AND all orgs' breakdown into history.
     * Runs at 2:30 AM (after reconciliation at 3 AM - on first day, may run before reconciliation
     * but will self-correct the next day).
     */
    @Scheduled(cron = "0 30 2 * * *")
    @SchedulerLock(name = "storage_snapshot_all_tenants", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    @Transactional
    public void snapshotAllTenants() {
        log.info("Starting daily storage history snapshot");
        List<String> tenantIds = historyRepository.findDistinctTenantIds();
        int tenantCount = 0;
        for (String tenantId : tenantIds) {
            try {
                snapshotTenant(tenantId);
                tenantCount++;
            } catch (Exception e) {
                log.error("Storage history snapshot failed for tenant {} [{}]: {}",
                        tenantId, e.getClass().getSimpleName(), e.getMessage(), e);
            }
        }

        // Issue #149 - same pass for orgs with breakdown data.
        List<String> orgIds = orgHistoryRepository != null
                ? orgHistoryRepository.findDistinctOrganizationIds()
                : List.of();
        int orgCount = 0;
        for (String orgId : orgIds) {
            try {
                snapshotOrganization(orgId);
                orgCount++;
            } catch (Exception e) {
                log.error("Storage history snapshot failed for organization {} [{}]: {}",
                        orgId, e.getClass().getSimpleName(), e.getMessage(), e);
            }
        }

        log.info("Completed daily storage history snapshot: {} tenants, {} organizations",
                tenantCount, orgCount);
    }

    /**
     * Daily job: purge history older than 90 days. Covers both tenant and org tables.
     * Runs at 3:30 AM.
     */
    @Scheduled(cron = "0 30 3 * * *")
    @SchedulerLock(name = "storage_purge_old_history", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    @Transactional
    public void purgeOldHistory() {
        LocalDate cutoff = LocalDate.now().minusDays(RETENTION_DAYS);
        int deletedTenants = historyRepository.deleteBySnapshotDateBefore(cutoff);
        int deletedOrgs = orgHistoryRepository != null
                ? orgHistoryRepository.deleteBySnapshotDateBefore(cutoff)
                : 0;
        if (deletedTenants > 0 || deletedOrgs > 0) {
            log.info("Purged {} tenant + {} org storage history records (before {})",
                    deletedTenants, deletedOrgs, cutoff);
        }
    }

    private int clampDays(int days) {
        return Math.max(MIN_DAYS, Math.min(RETENTION_DAYS, days));
    }
}
