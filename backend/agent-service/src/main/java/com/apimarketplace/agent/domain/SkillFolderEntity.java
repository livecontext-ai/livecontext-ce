package com.apimarketplace.agent.domain;

import com.apimarketplace.common.scope.OrgScopedEntity;
import com.apimarketplace.common.scope.OrgScopedEntityListener;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for the skill_folders table.
 * Supports hierarchical nesting via self-referencing parent_id.
 */
@Entity
@EntityListeners(OrgScopedEntityListener.class)
@Table(name = "skill_folders")
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class SkillFolderEntity implements OrgScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "parent_id")
    private UUID parentId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** PR27.2 - workspace tag (V217). NULL = personal scope. */
    @Column(name = "organization_id")
    private String organizationId;

    /**
     * Admin-managed visibility flag - V275 (2026-05-21). Mirrors
     * {@link SkillEntity#getIsGlobal()}: when true the folder is read-only and
     * visible to every tenant. Independent of child skill globalness (no
     * cascade): an admin can mark a folder global without changing each skill,
     * and a folder can contain a mix of personal + global skills.
     */
    @Column(name = "is_global", nullable = false)
    private Boolean isGlobal = false;

    public SkillFolderEntity() {
    }

    public SkillFolderEntity(String tenantId, String name, UUID parentId) {
        this.tenantId = tenantId;
        this.name = name;
        this.parentId = parentId;
    }

    @PrePersist
    private void onCreate() {
        Instant now = Instant.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        if (this.updatedAt == null) {
            this.updatedAt = now;
        }
        if (this.isGlobal == null) {
            this.isGlobal = false;
        }
    }

    @PreUpdate
    private void onUpdate() {
        this.updatedAt = Instant.now();
    }

    // Getters / setters

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public UUID getParentId() {
        return parentId;
    }

    public void setParentId(UUID parentId) {
        this.parentId = parentId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getOrganizationId() {
        return organizationId;
    }

    public void setOrganizationId(String organizationId) {
        this.organizationId = organizationId;
    }

    public Boolean getIsGlobal() {
        return isGlobal;
    }

    public void setIsGlobal(Boolean isGlobal) {
        this.isGlobal = isGlobal != null ? isGlobal : false;
    }
}
