package com.apimarketplace.agent.domain;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * CE-side marker of the last curated model-catalog SEED version applied at boot
 * by {@code ModelSeedBootstrapService} (from {@code model-catalog/models.json}).
 * Single row (id=1), seeded by V387.
 *
 * <p>The seed re-applies ONLY when the shipped {@code models.json} {@code version}
 * is greater than {@link #appliedVersion}, so a fresh release refreshes the
 * catalog exactly once and an unchanged version is a no-op (no per-boot churn).
 * Distinct from {@link CatalogBundleSyncStatusEntity} (V114) which tracks the
 * signed cloud BUNDLE; this tracks the code-shipped seed (no cloud link needed).
 */
@Entity
@Table(name = "model_seed_state")
public class ModelSeedStateEntity {

    public static final short SINGLETON_ID = 1;

    @Id
    @Column(name = "id")
    private Short id = SINGLETON_ID;

    /** Highest {@code models.json} version already merged. Null before first apply. */
    @Column(name = "applied_version")
    private Long appliedVersion;

    @Column(name = "applied_at")
    private Instant appliedAt;

    public Short getId() { return id; }
    public void setId(Short id) { this.id = id; }

    public Long getAppliedVersion() { return appliedVersion; }
    public void setAppliedVersion(Long appliedVersion) { this.appliedVersion = appliedVersion; }

    public Instant getAppliedAt() { return appliedAt; }
    public void setAppliedAt(Instant appliedAt) { this.appliedAt = appliedAt; }
}
