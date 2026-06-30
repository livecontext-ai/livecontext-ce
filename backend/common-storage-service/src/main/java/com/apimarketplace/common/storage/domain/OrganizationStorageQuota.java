package com.apimarketplace.common.storage.domain;

import com.apimarketplace.common.scope.OrgScopedEntity;
import com.apimarketplace.common.scope.OrgScopedEntityListener;
import jakarta.persistence.*;
import java.time.Instant;

/**
 * Per-org storage quota. Mirrors {@link TenantStorageQuota} but keyed by
 * {@code organization_id}. Created on demand at first write from an org context;
 * {@code max_bytes} sourced from the org owner's active subscription plan via
 * {@code PlanResolutionService}.
 *
 * <p>Quota selection at consumption time:
 * <ul>
 *   <li>If the storage row carries a non-NULL {@code organization_id} → debit
 *       this table for that org.</li>
 *   <li>If the storage row's {@code organization_id} is NULL → debit
 *       {@link TenantStorageQuota} for the tenant (personal scope).</li>
 * </ul>
 */
@Entity
@EntityListeners(OrgScopedEntityListener.class)
@Table(name = "organization_storage_quota", schema = "storage")
public class OrganizationStorageQuota implements OrgScopedEntity {

    @Id
    @Column(name = "organization_id")
    private String organizationId;

    @Column(name = "max_bytes", nullable = false)
    private Long maxBytes;

    @Column(name = "used_bytes", nullable = false)
    private Long usedBytes;

    @Column(name = "soft_limit_bytes", nullable = false)
    private Long softLimitBytes;

    @Column(name = "hard_limit_bytes", nullable = false)
    private Long hardLimitBytes;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public OrganizationStorageQuota() {}

    public OrganizationStorageQuota(String organizationId, Long maxBytes) {
        this.organizationId = organizationId;
        this.maxBytes = maxBytes;
        this.usedBytes = 0L;
        this.softLimitBytes = (long) (maxBytes * 0.8);
        this.hardLimitBytes = maxBytes;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public String getOrganizationId() { return organizationId; }
    public void setOrganizationId(String organizationId) { this.organizationId = organizationId; }

    public Long getMaxBytes() { return maxBytes; }
    public void setMaxBytes(Long maxBytes) { this.maxBytes = maxBytes; }

    public Long getUsedBytes() { return usedBytes; }
    public void setUsedBytes(Long usedBytes) { this.usedBytes = usedBytes; }

    public Long getSoftLimitBytes() { return softLimitBytes; }
    public void setSoftLimitBytes(Long softLimitBytes) { this.softLimitBytes = softLimitBytes; }

    public Long getHardLimitBytes() { return hardLimitBytes; }
    public void setHardLimitBytes(Long hardLimitBytes) { this.hardLimitBytes = hardLimitBytes; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public Long getAvailableBytes() {
        return Math.max(0, maxBytes - usedBytes);
    }

    public double getUsagePercentage() {
        return maxBytes > 0 ? (double) usedBytes / maxBytes * 100 : 0;
    }

    public boolean isSoftLimitReached() {
        return usedBytes >= softLimitBytes;
    }

    public boolean isHardLimitReached() {
        return usedBytes >= hardLimitBytes;
    }

    public boolean canStore(Long additionalBytes) {
        long used = usedBytes != null ? usedBytes : 0L;
        long hardLimit = hardLimitBytes != null ? hardLimitBytes : 0L;
        long additional = additionalBytes != null ? Math.max(0L, additionalBytes) : 0L;
        return used <= hardLimit && additional <= hardLimit - used;
    }

    public QuotaStatus getQuotaStatus() {
        if (isHardLimitReached()) {
            return QuotaStatus.HARD_LIMIT_REACHED;
        } else if (isSoftLimitReached()) {
            return QuotaStatus.SOFT_LIMIT_REACHED;
        } else {
            return QuotaStatus.OK;
        }
    }
}
