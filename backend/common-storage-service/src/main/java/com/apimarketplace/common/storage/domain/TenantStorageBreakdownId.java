package com.apimarketplace.common.storage.domain;

import java.io.Serializable;
import java.util.Objects;

/**
 * Composite primary key for TenantStorageBreakdown (tenant_id + category).
 */
public class TenantStorageBreakdownId implements Serializable {

    private String tenantId;
    private String category;

    public TenantStorageBreakdownId() {}

    public TenantStorageBreakdownId(String tenantId, String category) {
        this.tenantId = tenantId;
        this.category = category;
    }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TenantStorageBreakdownId that = (TenantStorageBreakdownId) o;
        return Objects.equals(tenantId, that.tenantId) && Objects.equals(category, that.category);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tenantId, category);
    }
}
