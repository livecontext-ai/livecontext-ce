package com.apimarketplace.auth.credential.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * Freezes the pricing version a billing scope (workflow run OR chat stream)
 * will bill against. Inserted lazily on first call within a scope and read on
 * every markup debit so mid-scope admin pricing changes don't leak.
 *
 * <p>Scope discriminator (V148):
 * <ul>
 *   <li>{@code scope_kind = 'RUN'}, {@code scope_id = workflow_run_id} -
 *       eager pin at run-init, cancelled at run-terminal.</li>
 *   <li>{@code scope_kind = 'STREAM'}, {@code scope_id = chat_stream_id} -
 *       lazy pin on first chat tool call, swept after 30 days idle
 *       (TTL on {@code last_used_at}).</li>
 * </ul>
 *
 * <p>Partial UNIQUE on {@code (scope_kind, scope_id, platform_credential_id)
 * WHERE cancelled = FALSE} lets a new scope re-pin after cancellation without
 * conflict; {@code pinForScope}'s {@code ON CONFLICT DO UPDATE} target must
 * repeat the predicate verbatim.
 *
 * <p>Legacy {@code run_id} column kept VARCHAR(128) for backwards compat;
 * {@code scope_id} is the canonical column going forward (DROP {@code run_id}
 * scheduled for the post-soak cleanup migration). Both fields are mapped here
 * so transitional code can read either.
 */
@Entity
@Table(
        schema = "auth",
        name = "workflow_run_pricing_pin",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_pcpp_scope_credential_live",
                columnNames = {"scope_kind", "scope_id", "platform_credential_id"})
)
public class WorkflowRunPricingPin {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Scope discriminator. {@code 'RUN'} for workflow runs, {@code 'STREAM'} for chat streams. */
    @Column(name = "scope_kind", nullable = false, length = 16)
    private String scopeKind = "RUN";

    /** Canonical scope id. Mirrors {@code run_id} until the legacy column drops post-soak. */
    @Column(name = "scope_id", nullable = false, length = 128)
    private String scopeId;

    /**
     * Legacy column kept for backwards compat during the soak window. New code
     * should read {@link #scopeId} instead. {@code pinForScope} writes BOTH
     * during the transition; the post-soak cleanup migration drops this column.
     */
    @Column(name = "run_id", nullable = false, length = 128)
    private String runId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "platform_credential_id", nullable = false)
    private Long platformCredentialId;

    @Column(name = "pricing_version_id", nullable = false)
    private Long pricingVersionId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * Refreshed by {@code pinForScope}'s {@code ON CONFLICT DO UPDATE} on every
     * lookup hit. Drives the STREAM-pin sweeper: rows idle &gt; 30 days are
     * eligible for cleanup.
     */
    @Column(name = "last_used_at", nullable = false)
    private Instant lastUsedAt;

    @Column(nullable = false)
    private boolean cancelled = false;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (lastUsedAt == null) lastUsedAt = now;
        // Mirror writes during transition. Drop after soak.
        if (scopeId == null && runId != null) scopeId = runId;
        if (runId == null && scopeId != null) runId = scopeId;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getScopeKind() { return scopeKind; }
    public void setScopeKind(String scopeKind) { this.scopeKind = scopeKind; }
    public String getScopeId() { return scopeId; }
    public void setScopeId(String scopeId) { this.scopeId = scopeId; }
    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getPlatformCredentialId() { return platformCredentialId; }
    public void setPlatformCredentialId(Long platformCredentialId) { this.platformCredentialId = platformCredentialId; }
    public Long getPricingVersionId() { return pricingVersionId; }
    public void setPricingVersionId(Long pricingVersionId) { this.pricingVersionId = pricingVersionId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(Instant lastUsedAt) { this.lastUsedAt = lastUsedAt; }
    public boolean isCancelled() { return cancelled; }
    public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
    public Instant getCancelledAt() { return cancelledAt; }
    public void setCancelledAt(Instant cancelledAt) { this.cancelledAt = cancelledAt; }
}
