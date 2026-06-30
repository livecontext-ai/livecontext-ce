package com.apimarketplace.common.storage.domain;

import com.apimarketplace.common.scope.OrgScopedEntity;
import com.apimarketplace.common.scope.OrgScopedEntityListener;
import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Daily snapshot of per-org per-category storage usage (Issue #149).
 *
 * <p>Mirror of {@link StorageUsageHistory} keyed on the organization id.
 * Written by the daily snapshot job and purged with the tenant history at 90 days.
 */
@Entity
@EntityListeners(OrgScopedEntityListener.class)
@Table(name = "org_storage_usage_history", schema = "storage",
       uniqueConstraints = @UniqueConstraint(columnNames = {"organization_id", "category", "snapshot_date"}))
public class OrgStorageUsageHistory implements OrgScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private String organizationId;

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

    public OrgStorageUsageHistory() {}

    public OrgStorageUsageHistory(String organizationId, String category, long usedBytes, int itemCount, LocalDate snapshotDate) {
        this.organizationId = organizationId;
        this.category = category;
        this.usedBytes = usedBytes;
        this.itemCount = itemCount;
        this.snapshotDate = snapshotDate;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getOrganizationId() { return organizationId; }
    public void setOrganizationId(String organizationId) { this.organizationId = organizationId; }
    public String getCategory() { return category; }
    public long getUsedBytes() { return usedBytes; }
    public int getItemCount() { return itemCount; }
    public LocalDate getSnapshotDate() { return snapshotDate; }
    public Instant getCreatedAt() { return createdAt; }
}
