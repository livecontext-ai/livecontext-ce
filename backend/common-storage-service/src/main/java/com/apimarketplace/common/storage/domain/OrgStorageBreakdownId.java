package com.apimarketplace.common.storage.domain;

import java.io.Serializable;
import java.util.Objects;

/**
 * Composite primary key for {@link OrgStorageBreakdown} (organization_id + category).
 */
public class OrgStorageBreakdownId implements Serializable {

    private String organizationId;
    private String category;

    public OrgStorageBreakdownId() {}

    public OrgStorageBreakdownId(String organizationId, String category) {
        this.organizationId = organizationId;
        this.category = category;
    }

    public String getOrganizationId() { return organizationId; }
    public void setOrganizationId(String organizationId) { this.organizationId = organizationId; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        OrgStorageBreakdownId that = (OrgStorageBreakdownId) o;
        return Objects.equals(organizationId, that.organizationId) && Objects.equals(category, that.category);
    }

    @Override
    public int hashCode() {
        return Objects.hash(organizationId, category);
    }
}
