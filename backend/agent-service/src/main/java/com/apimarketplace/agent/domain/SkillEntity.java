package com.apimarketplace.agent.domain;

import com.apimarketplace.common.scope.OrgScopedEntity;
import com.apimarketplace.common.scope.OrgScopedEntityListener;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for the skills table.
 * A skill is a reusable capability package with instructions for AI agents.
 */
@Entity
@EntityListeners(OrgScopedEntityListener.class)
@Table(name = "skills")
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class SkillEntity implements OrgScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description", nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(name = "icon", length = 100)
    private String icon;

    /**
     * Full markdown instructions returned when agent calls discover_skill.
     */
    // NOTE: plain text column (NOT @Lob). On PostgreSQL @Lob maps a String to a streamed
    // large object that can only be read inside a transaction - reading it outside one (e.g.
    // the non-transactional /recent-activity fan-out) threw "Unable to access lob stream".
    // The column is `text`, so a normal String mapping reads inline. Mirrors `description`
    // above. Audit 2026-06-14.
    @Column(name = "instructions", nullable = false, columnDefinition = "TEXT")
    private String instructions;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "folder_id")
    private UUID folderId;

    /**
     * Publication ID that caused this skill to be cloned (acquire-time traceability).
     */
    @Column(name = "source_publication_id")
    private UUID sourcePublicationId;

    /**
     * Non-null for built-in default skills (e.g. "deep_research", "workflow").
     * Default skills are editable but not deletable. Reset restores original content.
     */
    @Column(name = "default_key", length = 50)
    private String defaultKey;

    /**
     * When true, the skill is visible to every tenant (admin-managed "global" skill).
     * Non-admin users see it read-only alongside their own skills; only admins can
     * create, edit or delete global skills.
     */
    @Column(name = "is_global", nullable = false)
    private Boolean isGlobal = false;

    /**
     * When true, this skill is auto-included in NEW general-chat conversations
     * for every member of its visibility scope (org-scope for personal skills,
     * every tenant × org for {@link #isGlobal}). Set V275 (2026-05-21) - replaces
     * the legacy localStorage seed in {@code frontend/hooks/useDefaultSkills.ts}.
     * Owner toggles freely on personal skills; on global skills toggling follows
     * the same admin gate as {@link #isGlobal}.
     */
    @Column(name = "is_default_active", nullable = false)
    private Boolean isDefaultActive = false;

    /**
     * PR27.2 - workspace tag (V217 migration). NULL = personal scope.
     * See {@link com.apimarketplace.agent.repository.SkillRepository} for the
     * scope-aware finder pair.
     */
    @Column(name = "organization_id")
    private String organizationId;

    /**
     * V374 - provenance key for a CE row applied from a cloud SKILL BUNDLE: the cloud
     * skill's UUID. NULL on cloud-authored rows (admin-created globals, personal, and
     * default skills). NON-NULL means this is a read-only, cloud-managed copy on a CE
     * install: {@code SkillService} refuses edits/deletes, the cloud bundle owns the
     * content, and end users hide it for themselves via the per-user override layer.
     */
    @Column(name = "source_bundle_key", length = 64)
    private String sourceBundleKey;

    public SkillEntity() {
    }

    public SkillEntity(String tenantId,
                       String name,
                       String description,
                       String icon,
                       String instructions,
                       Boolean isActive) {
        this.tenantId = tenantId;
        this.name = name;
        this.description = description;
        this.icon = icon;
        this.instructions = instructions;
        this.isActive = isActive != null ? isActive : true;
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
        if (this.isActive == null) {
            this.isActive = true;
        }
        if (this.isGlobal == null) {
            this.isGlobal = false;
        }
        if (this.isDefaultActive == null) {
            this.isDefaultActive = false;
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

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getIcon() {
        return icon;
    }

    public void setIcon(String icon) {
        this.icon = icon;
    }

    public String getInstructions() {
        return instructions;
    }

    public void setInstructions(String instructions) {
        this.instructions = instructions;
    }

    public Boolean getIsActive() {
        return isActive;
    }

    public void setIsActive(Boolean isActive) {
        this.isActive = isActive;
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

    public UUID getFolderId() {
        return folderId;
    }

    public void setFolderId(UUID folderId) {
        this.folderId = folderId;
    }

    public UUID getSourcePublicationId() {
        return sourcePublicationId;
    }

    public void setSourcePublicationId(UUID sourcePublicationId) {
        this.sourcePublicationId = sourcePublicationId;
    }

    public String getDefaultKey() {
        return defaultKey;
    }

    public void setDefaultKey(String defaultKey) {
        this.defaultKey = defaultKey;
    }

    public Boolean getIsGlobal() {
        return isGlobal;
    }

    public void setIsGlobal(Boolean isGlobal) {
        this.isGlobal = isGlobal != null ? isGlobal : false;
    }

    public String getOrganizationId() {
        return organizationId;
    }

    public void setOrganizationId(String organizationId) {
        this.organizationId = organizationId;
    }

    public Boolean getIsDefaultActive() {
        return isDefaultActive;
    }

    public void setIsDefaultActive(Boolean isDefaultActive) {
        this.isDefaultActive = isDefaultActive != null ? isDefaultActive : false;
    }

    public String getSourceBundleKey() {
        return sourceBundleKey;
    }

    public void setSourceBundleKey(String sourceBundleKey) {
        this.sourceBundleKey = sourceBundleKey;
    }
}
