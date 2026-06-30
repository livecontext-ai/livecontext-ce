package com.apimarketplace.agent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Per-category (rank, enabled) sidecar over {@link ModelConfigOverrideEntity}.
 *
 * <p>Resolution rule used by callers:
 * <ul>
 *   <li>row present for {@code (model_config_id, category)} → use these values</li>
 *   <li>row absent → fall back to the global {@code ranking} / {@code enabled}
 *       on {@link ModelConfigOverrideEntity} (legacy behaviour)</li>
 * </ul>
 *
 * <p>Initial categories: {@code chat | browser_agent | image_generation}.
 * The DB shape constraint is permissive ({@code ^[a-z][a-z0-9_]*$}) so future
 * categories (video_generation, file_processing, …) can be added without a
 * schema migration. See V156.
 */
@Entity
@Table(name = "model_category_settings")
@IdClass(ModelCategorySettingsId.class)
public class ModelCategorySettingsEntity {

    @Id
    @Column(name = "model_config_id", nullable = false)
    private Long modelConfigId;

    @Id
    @Column(name = "category", nullable = false, length = 32)
    private String category;

    @Column(name = "rank")
    private Integer rank;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled = Boolean.TRUE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.enabled == null) this.enabled = Boolean.TRUE;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public Long getModelConfigId() { return modelConfigId; }
    public void setModelConfigId(Long modelConfigId) { this.modelConfigId = modelConfigId; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public Integer getRank() { return rank; }
    public void setRank(Integer rank) { this.rank = rank; }

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
