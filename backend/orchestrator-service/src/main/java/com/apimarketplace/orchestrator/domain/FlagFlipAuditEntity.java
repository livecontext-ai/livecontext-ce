package com.apimarketplace.orchestrator.domain;

import com.apimarketplace.common.scope.OrgScopedEntity;
import com.apimarketplace.common.scope.OrgScopedEntityListener;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Per-tenant kernel-runtime flag flip audit row (P2.1).
 *
 * <p>Backed by Flyway V173 (table {@code orchestrator.flag_flip_audit}). Initial
 * use case: forensics for {@code state-snapshot.elide-running-nodes} per-tenant
 * rollout. Schema is generic - future flags reuse the same table.
 *
 * <p>Write contract enforced at the service layer in {@code FlagFlipAuditWriter}:
 * sync, same-TX as the flag-mutation method, fail-the-flip-if-write-fails.
 *
 * <p>Round-10 (2026-05-20): added {@code organizationId} + OrgScopedEntity
 * listener wiring. V265 flips the column to NOT NULL. The new constructor
 * threads orgId from the caller; the listener fallback resolves from request
 * context when the caller forgot. Pre-round-10 the entity had no field and
 * the column stayed NULLABLE (V263 deliberate carve-out).
 */
@Entity
@EntityListeners(OrgScopedEntityListener.class)
@Table(name = "flag_flip_audit", schema = "orchestrator")
public class FlagFlipAuditEntity implements OrgScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "flag_name", nullable = false)
    private String flagName;

    @Column(name = "tenant_id")
    private String tenantId;

    @Column(name = "organization_id")
    private String organizationId;

    @Column(name = "old_value")
    private String oldValue;

    @Column(name = "new_value")
    private String newValue;

    @Column(name = "actor")
    private String actor;

    @Column(name = "reason")
    private String reason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public FlagFlipAuditEntity() {}

    public FlagFlipAuditEntity(String flagName, String tenantId, String organizationId,
                               String oldValue, String newValue,
                               String actor, String reason, Instant createdAt) {
        this.flagName = flagName;
        this.tenantId = tenantId;
        this.organizationId = organizationId;
        this.oldValue = oldValue;
        this.newValue = newValue;
        this.actor = actor;
        this.reason = reason;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public String getFlagName() { return flagName; }
    public String getTenantId() { return tenantId; }
    @Override
    public String getOrganizationId() { return organizationId; }
    public String getOldValue() { return oldValue; }
    public String getNewValue() { return newValue; }
    public String getActor() { return actor; }
    public String getReason() { return reason; }
    public Instant getCreatedAt() { return createdAt; }

    public void setId(Long id) { this.id = id; }
    public void setFlagName(String flagName) { this.flagName = flagName; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    @Override
    public void setOrganizationId(String organizationId) { this.organizationId = organizationId; }
    public void setOldValue(String oldValue) { this.oldValue = oldValue; }
    public void setNewValue(String newValue) { this.newValue = newValue; }
    public void setActor(String actor) { this.actor = actor; }
    public void setReason(String reason) { this.reason = reason; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
