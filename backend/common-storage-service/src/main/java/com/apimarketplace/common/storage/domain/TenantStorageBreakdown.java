package com.apimarketplace.common.storage.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Pre-computed storage usage per category per tenant.
 * Updated incrementally on every write operation, reconciled daily.
 *
 * <p>This table is OUT OF V261 NOT NULL scope by design (V259 line 10-12).
 * The per-org rollup is {@code OrgStorageBreakdown} - use it when scope-aware
 * queries are needed. Adding {@code organization_id} here requires a matching
 * migration; the column does not exist in the schema.</p>
 */
@Entity
@Table(name = "tenant_storage_breakdown", schema = "storage")
@IdClass(TenantStorageBreakdownId.class)
public class TenantStorageBreakdown {

    @Id
    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Id
    @Column(name = "category", nullable = false, length = 50)
    private String category;

    @Column(name = "used_bytes", nullable = false)
    private long usedBytes;

    @Column(name = "item_count", nullable = false)
    private int itemCount;

    @Column(name = "calculated_at", nullable = false)
    private Instant calculatedAt;

    public TenantStorageBreakdown() {}

    public TenantStorageBreakdown(String tenantId, String category, long usedBytes, int itemCount) {
        this.tenantId = tenantId;
        this.category = category;
        this.usedBytes = usedBytes;
        this.itemCount = itemCount;
        this.calculatedAt = Instant.now();
    }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public long getUsedBytes() { return usedBytes; }
    public void setUsedBytes(long usedBytes) { this.usedBytes = usedBytes; }

    public int getItemCount() { return itemCount; }
    public void setItemCount(int itemCount) { this.itemCount = itemCount; }

    public Instant getCalculatedAt() { return calculatedAt; }
    public void setCalculatedAt(Instant calculatedAt) { this.calculatedAt = calculatedAt; }
}
