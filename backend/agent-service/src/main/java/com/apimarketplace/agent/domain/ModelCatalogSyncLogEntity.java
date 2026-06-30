package com.apimarketplace.agent.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

/**
 * Append-only history of {@code ModelCatalogSyncService} runs. Operators
 * grep this table after a bad sync to recover the flagged rows and the
 * guard that caught them.
 *
 * <p>One row per attempt (dry-run OR apply). Writes land in every path -
 * including guard-aborted ones - so a partial OpenRouter feed that fails
 * count-floor is still visible in the history.
 */
@Entity
@Table(name = "model_catalog_sync_log", schema = "agent")
public class ModelCatalogSyncLogEntity {

    public enum Outcome {
        OK, ABORTED_GUARD, FETCH_ERROR, SCHEMA_ERROR, APPLY_ERROR
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** {@code 'litellm' | 'openrouter' | 'both'}. */
    @Column(name = "source", nullable = false)
    private String source;

    @Column(name = "fetched_at", nullable = false)
    private Instant fetchedAt;

    @Column(name = "model_count", nullable = false)
    private Integer modelCount;

    @Column(name = "checksum")
    private String checksum;

    @Column(name = "triggered_by", nullable = false)
    private String triggeredBy;

    @Column(name = "dry_run", nullable = false)
    private Boolean dryRun;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false)
    private Outcome outcome;

    @Column(name = "error_detail", columnDefinition = "text")
    private String errorDetail;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "guard_failures", columnDefinition = "jsonb")
    private Map<String, Object> guardFailures;

    @Column(name = "added_count", nullable = false)
    private Integer addedCount = 0;

    @Column(name = "updated_count", nullable = false)
    private Integer updatedCount = 0;

    @Column(name = "deprecated_count", nullable = false)
    private Integer deprecatedCount = 0;

    @Column(name = "flagged_count", nullable = false)
    private Integer flaggedCount = 0;

    /** Kept-model count from LiteLLM feed for this run. Null when LiteLLM
     *  did not fetch. Powers the per-feed count-floor baseline (V127). */
    @Column(name = "litellm_count")
    private Integer liteLlmCount;

    /** Kept-model count from OpenRouter feed for this run. Null when
     *  OpenRouter did not fetch. */
    @Column(name = "openrouter_count")
    private Integer openRouterCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }

    // Getters / setters

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public Instant getFetchedAt() { return fetchedAt; }
    public void setFetchedAt(Instant fetchedAt) { this.fetchedAt = fetchedAt; }

    public Integer getModelCount() { return modelCount; }
    public void setModelCount(Integer modelCount) { this.modelCount = modelCount; }

    public String getChecksum() { return checksum; }
    public void setChecksum(String checksum) { this.checksum = checksum; }

    public String getTriggeredBy() { return triggeredBy; }
    public void setTriggeredBy(String triggeredBy) { this.triggeredBy = triggeredBy; }

    public Boolean getDryRun() { return dryRun; }
    public void setDryRun(Boolean dryRun) { this.dryRun = dryRun; }

    public Outcome getOutcome() { return outcome; }
    public void setOutcome(Outcome outcome) { this.outcome = outcome; }

    public String getErrorDetail() { return errorDetail; }
    public void setErrorDetail(String errorDetail) { this.errorDetail = errorDetail; }

    public Map<String, Object> getGuardFailures() { return guardFailures; }
    public void setGuardFailures(Map<String, Object> guardFailures) { this.guardFailures = guardFailures; }

    public Integer getAddedCount() { return addedCount; }
    public void setAddedCount(Integer addedCount) { this.addedCount = addedCount; }

    public Integer getUpdatedCount() { return updatedCount; }
    public void setUpdatedCount(Integer updatedCount) { this.updatedCount = updatedCount; }

    public Integer getDeprecatedCount() { return deprecatedCount; }
    public void setDeprecatedCount(Integer deprecatedCount) { this.deprecatedCount = deprecatedCount; }

    public Integer getFlaggedCount() { return flaggedCount; }
    public void setFlaggedCount(Integer flaggedCount) { this.flaggedCount = flaggedCount; }

    public Integer getLiteLlmCount() { return liteLlmCount; }
    public void setLiteLlmCount(Integer liteLlmCount) { this.liteLlmCount = liteLlmCount; }

    public Integer getOpenRouterCount() { return openRouterCount; }
    public void setOpenRouterCount(Integer openRouterCount) { this.openRouterCount = openRouterCount; }

    public Instant getCreatedAt() { return createdAt; }
}
