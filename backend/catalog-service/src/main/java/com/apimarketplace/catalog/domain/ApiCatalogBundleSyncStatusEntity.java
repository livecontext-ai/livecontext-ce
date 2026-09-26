package com.apimarketplace.catalog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * CE-side bookkeeping of the last API-catalog bundle fetch/apply. Single row
 * ({@code id=1}, seeded by V331). Mirrors
 * {@code agent.catalog_bundle_sync_status} (the LLM model bundle). See also
 * {@link ApiCatalogBundleEntity}, which records the applied bundles; this row
 * tracks the FETCH side (which can fail without producing a bundle row).
 */
@Entity
@Table(name = "api_catalog_bundle_sync_status")
public class ApiCatalogBundleSyncStatusEntity {

    public static final short SINGLETON_ID = 1;

    @Id
    @Column(name = "id")
    private Short id = SINGLETON_ID;

    @Column(name = "last_applied_version")
    private Long lastAppliedVersion;

    @Column(name = "last_applied_at")
    private Instant lastAppliedAt;

    @Column(name = "last_fetch_at")
    private Instant lastFetchAt;

    @Column(name = "last_fetch_status", length = 32)
    private String lastFetchStatus;

    @Column(name = "last_fetch_error", columnDefinition = "text")
    private String lastFetchError;

    @Column(name = "consecutive_failures", nullable = false)
    private int consecutiveFailures;

    /**
     * Poll backoff (V539). Mapped read-only: written only by the repository's
     * {@code updateBackoff}, so a whole-row save of this entity (failure bookkeeping, the
     * applier's success status) can never overwrite it. See
     * {@code com.apimarketplace.common.scheduling.BundlePollBackoff}.
     */
    @Column(name = "backoff_level", nullable = false, insertable = false, updatable = false,
            columnDefinition = "integer default 0 not null")
    private int backoffLevel;

    @Column(name = "next_attempt_at", insertable = false, updatable = false)
    private Instant nextAttemptAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public Short getId() { return id; }
    public void setId(Short id) { this.id = id; }

    public Long getLastAppliedVersion() { return lastAppliedVersion; }
    public void setLastAppliedVersion(Long lastAppliedVersion) { this.lastAppliedVersion = lastAppliedVersion; }

    public Instant getLastAppliedAt() { return lastAppliedAt; }
    public void setLastAppliedAt(Instant lastAppliedAt) { this.lastAppliedAt = lastAppliedAt; }

    public Instant getLastFetchAt() { return lastFetchAt; }
    public void setLastFetchAt(Instant lastFetchAt) { this.lastFetchAt = lastFetchAt; }

    public String getLastFetchStatus() { return lastFetchStatus; }
    public void setLastFetchStatus(String lastFetchStatus) { this.lastFetchStatus = lastFetchStatus; }

    public String getLastFetchError() { return lastFetchError; }
    public void setLastFetchError(String lastFetchError) { this.lastFetchError = lastFetchError; }

    public int getConsecutiveFailures() { return consecutiveFailures; }
    public void setConsecutiveFailures(int consecutiveFailures) { this.consecutiveFailures = consecutiveFailures; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public int getBackoffLevel() { return backoffLevel; }
    /** In-memory only (the column is not updatable); for tests and freshly built rows. */
    public void setBackoffLevel(int backoffLevel) { this.backoffLevel = backoffLevel; }

    public Instant getNextAttemptAt() { return nextAttemptAt; }
    /** In-memory only (the column is not updatable); for tests and freshly built rows. */
    public void setNextAttemptAt(Instant nextAttemptAt) { this.nextAttemptAt = nextAttemptAt; }
}
