package com.apimarketplace.orchestrator.domain;

import com.apimarketplace.common.scope.OrgScopedEntity;
import com.apimarketplace.common.scope.OrgScopedEntityListener;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.IdClass;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * Per-tenant kernel-runtime flag value (P2.3.3 deliverable, V175 migration).
 *
 * <p>Composite primary key {@code (flag_name, tenant_id)} - at most one row
 * per flag per tenant. Default-OFF semantics: a missing row means the flag is
 * disabled for that tenant.
 *
 * <p>Hot read path goes through the {@code TenantFlagService} in-memory cache,
 * NOT this entity. The DB is durable backing only - read on startup and on
 * cache-refresh, written on operator-initiated flag flips.
 */
@Entity
@EntityListeners(OrgScopedEntityListener.class)
@Table(name = "tenant_flags", schema = "orchestrator")
@IdClass(TenantFlagEntity.PK.class)
public class TenantFlagEntity implements OrgScopedEntity {

    @Id
    @Column(name = "flag_name", nullable = false)
    private String flagName;

    @Id
    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "organization_id")
    private String organizationId;

    // "value" is a reserved word in some SQL dialects (notably H2 strict mode);
    // quote it so JPA emits `"value"` and Postgres + H2 both parse correctly.
    @Column(name = "\"value\"", nullable = false)
    private boolean value;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by")
    private String updatedBy;

    public TenantFlagEntity() {}

    public TenantFlagEntity(String flagName, String tenantId, boolean value,
                            Instant updatedAt, String updatedBy) {
        this.flagName = flagName;
        this.tenantId = tenantId;
        this.value = value;
        this.updatedAt = updatedAt;
        this.updatedBy = updatedBy;
    }

    public String getFlagName() { return flagName; }
    public String getTenantId() { return tenantId; }
    public String getOrganizationId() { return organizationId; }
    public boolean getValue() { return value; }
    public Instant getUpdatedAt() { return updatedAt; }
    public String getUpdatedBy() { return updatedBy; }

    public void setFlagName(String flagName) { this.flagName = flagName; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public void setOrganizationId(String organizationId) { this.organizationId = organizationId; }
    public void setValue(boolean value) { this.value = value; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }

    /** Composite-key class required by JPA's {@code @IdClass}. */
    public static class PK implements Serializable {
        private String flagName;
        private String tenantId;

        public PK() {}
        public PK(String flagName, String tenantId) {
            this.flagName = flagName;
            this.tenantId = tenantId;
        }

        public String getFlagName() { return flagName; }
        public String getTenantId() { return tenantId; }
        public void setFlagName(String flagName) { this.flagName = flagName; }
        public void setTenantId(String tenantId) { this.tenantId = tenantId; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PK other)) return false;
            return Objects.equals(flagName, other.flagName)
                    && Objects.equals(tenantId, other.tenantId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(flagName, tenantId);
        }
    }
}
