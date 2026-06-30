package com.apimarketplace.agent.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

/**
 * Live model-catalog row. Formerly "override-only"; since V109 this table is
 * the source of truth for every model the platform exposes.
 *
 * <p>Units:
 * <ul>
 *   <li>{@code priceInput} / {@code priceOutput} - USD per 1M tokens (provider list price).</li>
 *   <li>{@code creditsInput} / {@code creditsOutput} - derived by DB trigger
 *       ({@code price × markup × 10}); do NOT write from application code.</li>
 * </ul>
 *
 * <p>See {@code the project docs} for the cross-service contract.
 */
/**
 * @DynamicUpdate bypasses Hibernate 6.6's MERGE-with-CAST(?, text[]) code path
 * that fails with ClassCastException (CustomType → BasicPluralType) for entities
 * that have String[] columns. Functionally equivalent for our use case: Hibernate
 * issues UPDATE statements with only the dirty columns instead of full MERGE.
 */
@Entity
@Table(name = "model_config_overrides")
@DynamicUpdate
public class ModelConfigOverrideEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "provider", nullable = false, length = 50)
    private String provider;

    @Column(name = "model_id", nullable = false, length = 150)
    private String modelId;

    @Column(name = "enabled")
    private Boolean enabled;

    @Column(name = "display_name", nullable = false, length = 255)
    private String displayName;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Column(name = "tier", length = 20)
    private String tier;

    @Column(name = "ranking")
    private Integer ranking;

    @Column(name = "recommended")
    private Boolean recommended;

    @Column(name = "price_input", precision = 14, scale = 6)
    private BigDecimal priceInput;

    @Column(name = "price_output", precision = 14, scale = 6)
    private BigDecimal priceOutput;

    /** Derived by DB trigger - read-only from the app. */
    @Column(name = "credits_input", precision = 12, scale = 4, insertable = false, updatable = false)
    private BigDecimal creditsInput;

    /** Derived by DB trigger - read-only from the app. */
    @Column(name = "credits_output", precision = 12, scale = 4, insertable = false, updatable = false)
    private BigDecimal creditsOutput;

    @Column(name = "rate_limit_tpm")
    private Integer rateLimitTpm;

    @Column(name = "rate_limit_rpm")
    private Integer rateLimitRpm;

    @Column(name = "rate_limit_tpm_per_tenant")
    private Integer rateLimitTpmPerTenant;

    @Column(name = "rate_limit_rpm_per_tenant")
    private Integer rateLimitRpmPerTenant;

    @Column(name = "source", nullable = false, length = 20)
    private String source = "manual";

    /**
     * How the provider is consumed: cloud (LiveContext proxy), byok
     * (admin API key), bridge (CLI). Orthogonal to {@code source}, which
     * tracks catalog origin. Written by importers / factory registration;
     * admins cannot edit it from the UI. See V117.
     */
    @Column(name = "provider_kind", nullable = false, length = 16)
    private String providerKind = "byok";

    @Column(name = "source_model_id", length = 200)
    private String sourceModelId;

    @Column(name = "canonical_id", length = 150)
    private String canonicalId;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "user_modified_fields", columnDefinition = "text[]")
    private String[] userModifiedFields = new String[0];

    @Column(name = "bundle_version")
    private Long bundleVersion;

    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;

    @Column(name = "last_admin_edit_at")
    private Instant lastAdminEditAt;

    @Column(name = "context_window")
    private Integer contextWindow;

    @Column(name = "max_output_tokens")
    private Integer maxOutputTokens;

    @Column(name = "supports_tools")
    private Boolean supportsTools;

    @Column(name = "supports_vision")
    private Boolean supportsVision;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "modalities", columnDefinition = "jsonb")
    private Map<String, Object> modalities;

    @Column(name = "deprecated_at")
    private Instant deprecatedAt;

    // ── Enrichment fields (V125) ─────────────────────────────────────────

    /** Provider-published end-of-life date (e.g. LiteLLM deprecation_date). */
    @Column(name = "deprecation_date")
    private LocalDate deprecationDate;

    /** Best-effort release date: parsed from dated model_id suffix when present. */
    @Column(name = "release_date")
    private LocalDate releaseDate;

    @Column(name = "supports_prompt_caching")
    private Boolean supportsPromptCaching;

    @Column(name = "supports_reasoning")
    private Boolean supportsReasoning;

    /**
     * Per-model admin default reasoning effort for CLI/bridge providers
     * ({@code minimal|low|medium|high|xhigh} - see {@link ReasoningEffort}).
     * Lowest-precedence fallback: applies only when neither a per-conversation
     * override nor a per-agent setting is present. NULL ⇒ no default (the CLI
     * decides). Surfaced onto the catalog via
     * {@code ModelCatalogService.applyEnrichmentFields}.
     */
    @Column(name = "default_reasoning_effort", length = 16)
    private String defaultReasoningEffort;

    @Column(name = "supports_computer_use")
    private Boolean supportsComputerUse;

    @Column(name = "supports_response_schema")
    private Boolean supportsResponseSchema;

    @Column(name = "supports_web_search")
    private Boolean supportsWebSearch;

    /** chat / embedding / image / audio - mirrors LiteLLM's "mode" field. */
    @Column(name = "mode", length = 32)
    private String mode;

    @Column(name = "price_input_batch", precision = 14, scale = 6)
    private BigDecimal priceInputBatch;

    @Column(name = "price_output_batch", precision = 14, scale = 6)
    private BigDecimal priceOutputBatch;

    @Column(name = "price_cache_read", precision = 14, scale = 6)
    private BigDecimal priceCacheRead;

    @Column(name = "price_cache_write", precision = 14, scale = 6)
    private BigDecimal priceCacheWrite;

    /** min(price_input, price_input_batch) - cheapest variant a caller can get. */
    @Column(name = "price_floor_input", precision = 14, scale = 6)
    private BigDecimal priceFloorInput;

    @Column(name = "price_floor_output", precision = 14, scale = 6)
    private BigDecimal priceFloorOutput;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "supported_endpoints", columnDefinition = "text[]")
    private String[] supportedEndpoints;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "supported_modalities", columnDefinition = "text[]")
    private String[] supportedModalities;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "supported_output_modalities", columnDefinition = "text[]")
    private String[] supportedOutputModalities;

    /**
     * Raw feed payload + provenance (future-proofing). Shape:
     * {@code {source, source_sha, fetched_at, raw: <verbatim feed entry>}}.
     * Populated by {@code ModelCatalogSyncService}; unchanged by admin edits.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "feed_metadata", columnDefinition = "jsonb")
    private Map<String, Object> feedMetadata;

    /**
     * Transient flag: when true, rate limit fields were explicitly provided
     * (even if null = "clear"). Used by saveOverride() to distinguish
     * "not provided" (skip) from "provided as null" (clear).
     */
    @Transient
    private boolean rateLimitsExplicitlySet = false;

    @Column(name = "is_custom", nullable = false)
    private boolean custom = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        updatedAt = Instant.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    // Getters and setters

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String getModelId() { return modelId; }
    public void setModelId(String modelId) { this.modelId = modelId; }

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getTier() { return tier; }
    public void setTier(String tier) { this.tier = tier; }

    public Integer getRanking() { return ranking; }
    public void setRanking(Integer ranking) { this.ranking = ranking; }

    public Boolean getRecommended() { return recommended; }
    public void setRecommended(Boolean recommended) { this.recommended = recommended; }

    public BigDecimal getPriceInput() { return priceInput; }
    public void setPriceInput(BigDecimal priceInput) { this.priceInput = priceInput; }

    public BigDecimal getPriceOutput() { return priceOutput; }
    public void setPriceOutput(BigDecimal priceOutput) { this.priceOutput = priceOutput; }

    public Integer getRateLimitTpm() { return rateLimitTpm; }
    public void setRateLimitTpm(Integer rateLimitTpm) { this.rateLimitTpm = rateLimitTpm; }

    public Integer getRateLimitRpm() { return rateLimitRpm; }
    public void setRateLimitRpm(Integer rateLimitRpm) { this.rateLimitRpm = rateLimitRpm; }

    public Integer getRateLimitTpmPerTenant() { return rateLimitTpmPerTenant; }
    public void setRateLimitTpmPerTenant(Integer rateLimitTpmPerTenant) { this.rateLimitTpmPerTenant = rateLimitTpmPerTenant; }

    public Integer getRateLimitRpmPerTenant() { return rateLimitRpmPerTenant; }
    public void setRateLimitRpmPerTenant(Integer rateLimitRpmPerTenant) { this.rateLimitRpmPerTenant = rateLimitRpmPerTenant; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public BigDecimal getCreditsInput() { return creditsInput; }
    public BigDecimal getCreditsOutput() { return creditsOutput; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getProviderKind() { return providerKind; }
    public void setProviderKind(String providerKind) { this.providerKind = providerKind; }

    public String getSourceModelId() { return sourceModelId; }
    public void setSourceModelId(String sourceModelId) { this.sourceModelId = sourceModelId; }

    public String getCanonicalId() { return canonicalId; }
    public void setCanonicalId(String canonicalId) { this.canonicalId = canonicalId; }

    public String[] getUserModifiedFields() { return userModifiedFields; }
    public void setUserModifiedFields(String[] userModifiedFields) { this.userModifiedFields = userModifiedFields; }

    /**
     * Marks a field as admin-modified so future syncs (LiteLLM / bundle)
     * skip it via the {@code protectedFields} mechanism in
     * {@link com.apimarketplace.agent.catalog.bundle.CatalogMergeService#merge}.
     */
    public void addUserModifiedField(String field) {
        if (field == null) return;
        if (userModifiedFields == null) {
            userModifiedFields = new String[]{field};
            return;
        }
        for (String f : userModifiedFields) {
            if (field.equals(f)) return;
        }
        String[] expanded = new String[userModifiedFields.length + 1];
        System.arraycopy(userModifiedFields, 0, expanded, 0, userModifiedFields.length);
        expanded[userModifiedFields.length] = field;
        userModifiedFields = expanded;
    }

    public Long getBundleVersion() { return bundleVersion; }
    public void setBundleVersion(Long bundleVersion) { this.bundleVersion = bundleVersion; }

    public Instant getLastSyncedAt() { return lastSyncedAt; }
    public void setLastSyncedAt(Instant lastSyncedAt) { this.lastSyncedAt = lastSyncedAt; }

    public Instant getLastAdminEditAt() { return lastAdminEditAt; }
    public void setLastAdminEditAt(Instant lastAdminEditAt) { this.lastAdminEditAt = lastAdminEditAt; }

    public Integer getContextWindow() { return contextWindow; }
    public void setContextWindow(Integer contextWindow) { this.contextWindow = contextWindow; }

    public Integer getMaxOutputTokens() { return maxOutputTokens; }
    public void setMaxOutputTokens(Integer maxOutputTokens) { this.maxOutputTokens = maxOutputTokens; }

    public Boolean getSupportsTools() { return supportsTools; }
    public void setSupportsTools(Boolean supportsTools) { this.supportsTools = supportsTools; }

    public Boolean getSupportsVision() { return supportsVision; }
    public void setSupportsVision(Boolean supportsVision) { this.supportsVision = supportsVision; }

    public Map<String, Object> getModalities() { return modalities; }
    public void setModalities(Map<String, Object> modalities) { this.modalities = modalities; }

    public Instant getDeprecatedAt() { return deprecatedAt; }
    public void setDeprecatedAt(Instant deprecatedAt) { this.deprecatedAt = deprecatedAt; }

    public LocalDate getDeprecationDate() { return deprecationDate; }
    public void setDeprecationDate(LocalDate deprecationDate) { this.deprecationDate = deprecationDate; }

    public LocalDate getReleaseDate() { return releaseDate; }
    public void setReleaseDate(LocalDate releaseDate) { this.releaseDate = releaseDate; }

    public Boolean getSupportsPromptCaching() { return supportsPromptCaching; }
    public void setSupportsPromptCaching(Boolean supportsPromptCaching) { this.supportsPromptCaching = supportsPromptCaching; }

    public Boolean getSupportsReasoning() { return supportsReasoning; }
    public void setSupportsReasoning(Boolean supportsReasoning) { this.supportsReasoning = supportsReasoning; }

    public String getDefaultReasoningEffort() { return defaultReasoningEffort; }
    public void setDefaultReasoningEffort(String defaultReasoningEffort) { this.defaultReasoningEffort = defaultReasoningEffort; }

    public Boolean getSupportsComputerUse() { return supportsComputerUse; }
    public void setSupportsComputerUse(Boolean supportsComputerUse) { this.supportsComputerUse = supportsComputerUse; }

    public Boolean getSupportsResponseSchema() { return supportsResponseSchema; }
    public void setSupportsResponseSchema(Boolean supportsResponseSchema) { this.supportsResponseSchema = supportsResponseSchema; }

    public Boolean getSupportsWebSearch() { return supportsWebSearch; }
    public void setSupportsWebSearch(Boolean supportsWebSearch) { this.supportsWebSearch = supportsWebSearch; }

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }

    public BigDecimal getPriceInputBatch() { return priceInputBatch; }
    public void setPriceInputBatch(BigDecimal priceInputBatch) { this.priceInputBatch = priceInputBatch; }

    public BigDecimal getPriceOutputBatch() { return priceOutputBatch; }
    public void setPriceOutputBatch(BigDecimal priceOutputBatch) { this.priceOutputBatch = priceOutputBatch; }

    public BigDecimal getPriceCacheRead() { return priceCacheRead; }
    public void setPriceCacheRead(BigDecimal priceCacheRead) { this.priceCacheRead = priceCacheRead; }

    public BigDecimal getPriceCacheWrite() { return priceCacheWrite; }
    public void setPriceCacheWrite(BigDecimal priceCacheWrite) { this.priceCacheWrite = priceCacheWrite; }

    public BigDecimal getPriceFloorInput() { return priceFloorInput; }
    public void setPriceFloorInput(BigDecimal priceFloorInput) { this.priceFloorInput = priceFloorInput; }

    public BigDecimal getPriceFloorOutput() { return priceFloorOutput; }
    public void setPriceFloorOutput(BigDecimal priceFloorOutput) { this.priceFloorOutput = priceFloorOutput; }

    public String[] getSupportedEndpoints() { return supportedEndpoints; }
    public void setSupportedEndpoints(String[] supportedEndpoints) { this.supportedEndpoints = supportedEndpoints; }

    public String[] getSupportedModalities() { return supportedModalities; }
    public void setSupportedModalities(String[] supportedModalities) { this.supportedModalities = supportedModalities; }

    public String[] getSupportedOutputModalities() { return supportedOutputModalities; }
    public void setSupportedOutputModalities(String[] supportedOutputModalities) { this.supportedOutputModalities = supportedOutputModalities; }

    public Map<String, Object> getFeedMetadata() { return feedMetadata; }
    public void setFeedMetadata(Map<String, Object> feedMetadata) { this.feedMetadata = feedMetadata; }

    public boolean isRateLimitsExplicitlySet() { return rateLimitsExplicitlySet; }
    public void setRateLimitsExplicitlySet(boolean rateLimitsExplicitlySet) { this.rateLimitsExplicitlySet = rateLimitsExplicitlySet; }

    public boolean isCustom() { return custom; }
    public void setCustom(boolean custom) { this.custom = custom; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
