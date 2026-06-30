package com.apimarketplace.common.storage.domain;

import com.apimarketplace.common.scope.OrgScopedEntity;
import com.apimarketplace.common.scope.OrgScopedEntityListener;
import jakarta.persistence.*;
import java.time.Instant;

/**
 * Per-org per-category storage usage rollup (Issue #149).
 *
 * <p>Mirror of {@link TenantStorageBreakdown} keyed on the organization id
 * instead of the tenant (user) id. Populated by
 * {@code StorageBreakdownService} 4-arg trackers when an organization
 * context is present, and overwritten daily by
 * {@code StorageReconciliationService.reconcileOrganization()}.
 */
@Entity
@EntityListeners(OrgScopedEntityListener.class)
@Table(name = "org_storage_breakdown", schema = "storage")
@IdClass(OrgStorageBreakdownId.class)
public class OrgStorageBreakdown implements OrgScopedEntity {

    @Id
    @Column(name = "organization_id", nullable = false)
    private String organizationId;

    @Id
    @Column(name = "category", nullable = false, length = 50)
    private String category;

    @Column(name = "used_bytes", nullable = false)
    private long usedBytes;

    @Column(name = "item_count", nullable = false)
    private int itemCount;

    @Column(name = "calculated_at", nullable = false)
    private Instant calculatedAt;

    public OrgStorageBreakdown() {}

    public OrgStorageBreakdown(String organizationId, String category, long usedBytes, int itemCount) {
        this.organizationId = organizationId;
        this.category = category;
        this.usedBytes = usedBytes;
        this.itemCount = itemCount;
        this.calculatedAt = Instant.now();
    }

    public String getOrganizationId() { return organizationId; }
    public void setOrganizationId(String organizationId) { this.organizationId = organizationId; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public long getUsedBytes() { return usedBytes; }
    public void setUsedBytes(long usedBytes) { this.usedBytes = usedBytes; }

    public int getItemCount() { return itemCount; }
    public void setItemCount(int itemCount) { this.itemCount = itemCount; }

    public Instant getCalculatedAt() { return calculatedAt; }
    public void setCalculatedAt(Instant calculatedAt) { this.calculatedAt = calculatedAt; }
}
