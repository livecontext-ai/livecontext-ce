package com.apimarketplace.common.storage.service;

import com.apimarketplace.common.storage.domain.OrgStorageBreakdown;
import com.apimarketplace.common.storage.domain.TenantStorageBreakdown;
import com.apimarketplace.common.storage.domain.TenantStorageBreakdownId;
import com.apimarketplace.common.storage.repository.OrgStorageBreakdownRepository;
import com.apimarketplace.common.storage.repository.TenantStorageBreakdownRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Shared service for incremental storage accounting.
 *
 * <p>Legacy tenant-scope calls write only tenant rows. Org-aware overloads write
 * both tenant and organization rollups so personal and workspace views stay
 * independent.</p>
 */
@Service
public class StorageBreakdownService {

    private static final Logger log = LoggerFactory.getLogger(StorageBreakdownService.class);

    private final TenantStorageBreakdownRepository breakdownRepository;
    private final OrgStorageBreakdownRepository orgBreakdownRepository;

    public StorageBreakdownService(TenantStorageBreakdownRepository breakdownRepository,
                                   OrgStorageBreakdownRepository orgBreakdownRepository) {
        this.breakdownRepository = breakdownRepository;
        this.orgBreakdownRepository = orgBreakdownRepository;
    }

    /**
     * Atomic increment, safe for concurrent calls. Tenant scope only.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void increment(String tenantId, String category, long deltaBytes, int deltaCount) {
        increment(tenantId, category, deltaBytes, deltaCount, null);
    }

    /**
     * Atomic increment, safe for concurrent calls. Org-aware overload.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void increment(String tenantId, String category, long deltaBytes, int deltaCount, String organizationId) {
        if (deltaBytes == 0 && deltaCount == 0) {
            return;
        }

        try {
            if (deltaBytes < 0 || deltaCount < 0) {
                logIfWouldUnderflow(tenantId, category, deltaBytes, deltaCount);
            }
            breakdownRepository.incrementUsage(tenantId, category, deltaBytes, deltaCount);
        } catch (Exception e) {
            log.error("Storage breakdown increment failed; quota drift likely for tenant={} category={}; "
                            + "daily reconciliation will repair: [{}] {}",
                    tenantId, category, e.getClass().getSimpleName(), e.getMessage(), e);
        }

        if (organizationId != null && !organizationId.isBlank()) {
            try {
                orgBreakdownRepository.incrementUsage(organizationId, category, deltaBytes, deltaCount);
            } catch (Exception e) {
                log.warn("Failed to increment org breakdown for org={}, category={}: {}",
                        organizationId, category, e.getMessage());
            }
        }
    }

    /**
     * Pre-read the row only when the delta is negative and log when SQL will clamp at zero.
     */
    private void logIfWouldUnderflow(String tenantId, String category, long deltaBytes, int deltaCount) {
        try {
            TenantStorageBreakdownId id = new TenantStorageBreakdownId(tenantId, category);
            breakdownRepository.findById(id).ifPresent(row -> {
                long projectedBytes = row.getUsedBytes() + deltaBytes;
                int projectedCount = row.getItemCount() + deltaCount;
                if (projectedBytes < 0 || projectedCount < 0) {
                    log.warn("[StorageBreakdown] Negative delta clamped: tenant={} category={} "
                                    + "currentBytes={} deltaBytes={} projectedBytes={} "
                                    + "currentCount={} deltaCount={} projectedCount={}",
                            tenantId, category,
                            row.getUsedBytes(), deltaBytes, projectedBytes,
                            row.getItemCount(), deltaCount, projectedCount);
                }
            });
        } catch (Exception e) {
            log.debug("Could not pre-check underflow for tenant={}, category={}: {}",
                    tenantId, category, e.getMessage());
        }
    }

    /**
     * Track a save operation. Tenant scope only.
     */
    public void trackSave(String tenantId, String category, long sizeBytes) {
        trackSave(tenantId, category, sizeBytes, null);
    }

    /**
     * Track a save operation. Org-aware overload.
     */
    public void trackSave(String tenantId, String category, long sizeBytes, String organizationId) {
        increment(tenantId, category, sizeBytes, 1, organizationId);
    }

    /**
     * Track a delete operation. Tenant scope only.
     */
    public void trackDelete(String tenantId, String category, long sizeBytes) {
        trackDelete(tenantId, category, sizeBytes, null);
    }

    /**
     * Track a delete operation. Org-aware overload.
     */
    public void trackDelete(String tenantId, String category, long sizeBytes, String organizationId) {
        increment(tenantId, category, -sizeBytes, -1, organizationId);
    }

    /**
     * Track a size change. Tenant scope only.
     */
    public void trackSizeChange(String tenantId, String category, long deltaBytes) {
        trackSizeChange(tenantId, category, deltaBytes, null);
    }

    /**
     * Track a size change. Org-aware overload.
     */
    public void trackSizeChange(String tenantId, String category, long deltaBytes, String organizationId) {
        increment(tenantId, category, deltaBytes, 0, organizationId);
    }

    /**
     * Total usage across all tenant categories.
     */
    @Transactional(readOnly = true)
    public long getTotalUsage(String tenantId) {
        return breakdownRepository.sumTotalUsage(tenantId);
    }

    /**
     * Per-category breakdown for tenant scope.
     */
    @Transactional(readOnly = true)
    public List<TenantStorageBreakdown> getBreakdown(String tenantId) {
        return breakdownRepository.findByTenantId(tenantId);
    }

    /**
     * Per-category breakdown for organization scope.
     */
    @Transactional(readOnly = true)
    public List<OrgStorageBreakdown> getOrgBreakdown(String organizationId) {
        return orgBreakdownRepository.findByOrganizationId(organizationId);
    }

    /**
     * Absolute set for tenant-scope reconciliation.
     */
    @Transactional
    public void setUsage(String tenantId, String category, long usedBytes, int itemCount) {
        breakdownRepository.setUsage(tenantId, category, usedBytes, itemCount);
    }

    /**
     * Absolute set for organization-scope reconciliation.
     */
    @Transactional
    public void setOrgUsage(String organizationId, String category, long usedBytes, int itemCount) {
        orgBreakdownRepository.setUsage(organizationId, category, usedBytes, itemCount);
    }
}
