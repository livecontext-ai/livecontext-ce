package com.apimarketplace.common.storage.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Daily snapshot of storage usage per category per tenant.
 * Populated by scheduled job, retained for 90 days.
 *
 * <p>This table is OUT OF V261 scope by design - V259 line 10-12 documents
 * that the parallel {@code organization_storage_*} tables (V222) carry the
 * org-scoped rollup, and consolidation between the two is deferred. Do NOT
 * add {@code organization_id} here without a matching migration; the
 * {@code OrgStorageUsageHistory} entity in this package is the org-scoped
 * twin and should be used when scope-aware history is needed.</p>
 */
@Entity
@Table(name = "storage_usage_history", schema = "storage",
       uniqueConstraints = @UniqueConstraint(columnNames = {"tenant_id", "category", "snapshot_date"}))
public class StorageUsageHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "category", nullable = false, length = 50)
    private String category;

    @Column(name = "used_bytes", nullable = false)
    private long usedBytes;

    @Column(name = "item_count", nullable = false)
    private int itemCount;

    @Column(name = "snapshot_date", nullable = false)
    private LocalDate snapshotDate;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public StorageUsageHistory() {}

    public StorageUsageHistory(String tenantId, String category, long usedBytes, int itemCount, LocalDate snapshotDate) {
        this.tenantId = tenantId;
        this.category = category;
        this.usedBytes = usedBytes;
        this.itemCount = itemCount;
        this.snapshotDate = snapshotDate;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getCategory() { return category; }
    public long getUsedBytes() { return usedBytes; }
    public int getItemCount() { return itemCount; }
    public LocalDate getSnapshotDate() { return snapshotDate; }
    public Instant getCreatedAt() { return createdAt; }
}
