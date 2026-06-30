package com.apimarketplace.auth.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "credit_ledger")
public class CreditLedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /**
     * PR11 - identity of the user that FIRED the consume, before any Q1=b
     * payer redirect. {@code user_id} carries the payer (= owner when
     * redirect is on); {@code executor_user_id} carries the executor.
     *
     * <p>Indexed via {@code idx_cl_executor_date} (V200) for the
     * per-period sum used by {@code MemberQuotaService.checkCap()}.
     *
     * <p>NULL only on legacy rows pre-V200 backfill (treated as
     * {@code user_id} by quota enforcement - defence in depth). All new
     * inserts from PR11 onward MUST set this field.
     */
    @Column(name = "executor_user_id")
    private Long executorUserId;

    /**
     * V366 (ADR-0010) - descriptive workspace tag: the organization the
     * consumption happened in. Reporting dimension ONLY; it does NOT affect
     * routing or balance (owner-pays keys the wallet on {@code user_id}). Set
     * at consume time from the active workspace context
     * ({@code TenantResolver.currentRequestOrganizationId()}).
     *
     * <p>NULL = unattributed: a historical row (pre-V366), a system
     * grant/adjustment, or a consume with no active workspace context. The
     * Quota page shows NULL rows only under the "All workspaces" aggregate,
     * never in a per-workspace slice - we never retroactively guess a
     * workspace for a row.
     */
    @Column(name = "organization_id", length = 64)
    private String organizationId;

    @Column(nullable = false, precision = 15, scale = 4)
    private BigDecimal amount;

    @Column(name = "balance_after", nullable = false, precision = 15, scale = 4)
    private BigDecimal balanceAfter;

    @Column(name = "source_type", nullable = false, length = 64)
    private String sourceType;

    @Column(name = "source_id", length = 512)
    private String sourceId;

    @Column(name = "related_source_id", length = 512)
    private String relatedSourceId;

    @Column(length = 50)
    private String provider;

    @Column(length = 100)
    private String model;

    @Column(name = "prompt_tokens")
    private Integer promptTokens;

    @Column(name = "completion_tokens")
    private Integer completionTokens;

    /**
     * V363: cache-read token subset of {@code prompt_tokens} for LLM rows (the
     * portion billed at the discounted cache rate). Provider-agnostic value:
     * {@code max(cachedTokens, cacheReadTokens)} from
     * {@link com.apimarketplace.auth.service.LlmTokenBreakdown}. It is a SUBSET
     * of {@code prompt_tokens}, never additive. NULL on pre-V363 rows and on
     * non-LLM rows (workflow node, image generation, web search, markup).
     */
    @Column(name = "cached_tokens")
    private Integer cachedTokens;

    @Column(length = 500)
    private String description;

    /**
     * FK to {@code auth.workflow_run_pricing_pin.id}. Set on
     * {@code PLATFORM_MARKUP_RESERVE} insert by {@code tryReserveMarkup} so the
     * sweeper can correlate orphaned reservations to their pin without scanning
     * the ledger by source_id prefix. NULL for non-markup ledger rows and for
     * pre-V148 historical rows. {@code ON DELETE SET NULL} (V148): a swept pin
     * does not cascade-delete its audit trail.
     */
    @Column(name = "pin_id")
    private Long pinId;

    /**
     * Reservation expiry (per-call TTL). Set by {@code tryReserveMarkup} based
     * on the caller's scope:
     * <ul>
     *   <li>Catalog post-flight reserve: {@code now() + 10min}</li>
     *   <li>Per-step short call (LLM hop): {@code now() + 15min}</li>
     *   <li>Long-running step (browser-agent, classify): {@code now() + 60min}</li>
     *   <li>Workflow run-init reserve: {@code now() + 1440min} (24h)</li>
     * </ul>
     * Cleared (set to NULL) when {@code commitReservation} or
     * {@code releaseReservation} flips the row out of {@code _RESERVE} state.
     * Indexed via {@code idx_cl_expires_pending} (V150) keyed on
     * {@code source_type='PLATFORM_MARKUP_RESERVE' AND expires_at IS NOT NULL}.
     */
    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    /**
     * V254: portion of this reservation debit that drained the PAYG bucket.
     * Set by {@code tryReserveMarkup} on {@code PLATFORM_MARKUP_RESERVE} insert;
     * read by {@code commitReservation} / {@code releaseReservation} so the
     * refund credits the PAYG bucket proportionally instead of dumping
     * everything on the sub bucket (which would silently lose PAYG dollars at
     * the next renewal). Non-reserve rows and pre-V254 reserves carry 0 - the
     * release path falls back to the legacy refund-to-sub behaviour, which
     * preserves total balance but loses bucket fidelity for historical rows.
     */
    @Column(name = "payg_portion", nullable = false, precision = 15, scale = 4)
    private BigDecimal paygPortion = BigDecimal.ZERO;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    // Getters and setters
    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getExecutorUserId() { return executorUserId; }
    public void setExecutorUserId(Long executorUserId) { this.executorUserId = executorUserId; }
    public String getOrganizationId() { return organizationId; }
    public void setOrganizationId(String organizationId) { this.organizationId = organizationId; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public BigDecimal getBalanceAfter() { return balanceAfter; }
    public void setBalanceAfter(BigDecimal balanceAfter) { this.balanceAfter = balanceAfter; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public String getSourceId() { return sourceId; }
    public void setSourceId(String sourceId) { this.sourceId = sourceId; }
    public String getRelatedSourceId() { return relatedSourceId; }
    public void setRelatedSourceId(String relatedSourceId) { this.relatedSourceId = relatedSourceId; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public Integer getPromptTokens() { return promptTokens; }
    public void setPromptTokens(Integer promptTokens) { this.promptTokens = promptTokens; }
    public Integer getCompletionTokens() { return completionTokens; }
    public void setCompletionTokens(Integer completionTokens) { this.completionTokens = completionTokens; }
    public Integer getCachedTokens() { return cachedTokens; }
    public void setCachedTokens(Integer cachedTokens) { this.cachedTokens = cachedTokens; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Long getPinId() { return pinId; }
    public void setPinId(Long pinId) { this.pinId = pinId; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
    public BigDecimal getPaygPortion() { return paygPortion == null ? BigDecimal.ZERO : paygPortion; }
    public void setPaygPortion(BigDecimal paygPortion) { this.paygPortion = paygPortion == null ? BigDecimal.ZERO : paygPortion; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
