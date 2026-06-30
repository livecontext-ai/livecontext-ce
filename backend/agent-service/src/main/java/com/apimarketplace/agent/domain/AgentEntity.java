package com.apimarketplace.agent.domain;

import com.apimarketplace.common.scope.OrgScopedEntity;
import com.apimarketplace.common.scope.OrgScopedEntityListener;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Entite JPA pour la table agents.
 * Un agent est un assistant IA personnalisable avec des instructions système, un modèle, et des outils.
 */
@Entity
@EntityListeners(OrgScopedEntityListener.class)
@Table(name = "agents")
@DynamicUpdate
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class AgentEntity implements OrgScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description")
    private String description;

    /**
     * Instructions système (system prompt) pour l'agent.
     */
    // Plain text column (NOT @Lob): @Lob would stream a PG large object readable only inside
    // a transaction, breaking non-transactional reads with "Unable to access lob stream".
    // Audit 2026-06-14.
    @Column(name = "system_prompt", columnDefinition = "TEXT")
    private String systemPrompt;

    /**
     * Provider du modèle IA (openai, anthropic, google, deepinfra, etc.).
     */
    @Column(name = "model_provider", length = 50)
    private String modelProvider;

    /**
     * Nom du modèle IA (gpt-4, claude-3-sonnet, gemini-pro, etc.).
     */
    @Column(name = "model_name", length = 100)
    private String modelName;

    /**
     * Température pour la génération (0.0 à 2.0, défaut: 0.7).
     */
    @Column(name = "temperature", precision = 3, scale = 2)
    private BigDecimal temperature;

    /**
     * Max output tokens per LLM turn (must be positive; default from
     * AgentDefaultsConfig = 16000). Clamped at execution time to the model's
     * real ceiling via MaxTokensClamp so a high default never 400s a low-cap model.
     */
    @Column(name = "max_tokens")
    private Integer maxTokens;

    /**
     * Nombre maximum d'itérations de la boucle agent (tool call rounds). 1-1000.
     */
    @Column(name = "max_iterations")
    private Integer maxIterations;

    /**
     * Maximum execution time in seconds for this agent (10-7200, default from
     * AgentDefaultsConfig = 3600).
     */
    @Column(name = "execution_timeout")
    private Integer executionTimeout;

    /**
     * Per-agent inactivity watchdog window in seconds. NULL => platform default (5 min);
     * 0 => disabled; 10-7200 => custom. The watchdog stops an agent that emits no token/tool
     * activity for this long with INACTIVITY_TIMEOUT - independent of executionTimeout (the total
     * wall-clock cap). Carried to the agent loop / bridge via the __inactivityTimeoutSeconds__
     * credential so it never had to be threaded through the positional DTO record.
     *
     * <p>Scope: this column governs the workflow + sub-agent surfaces. Direct chat resolves its
     * window from {@code chat_config.inactivityTimeout} instead, exactly mirroring how
     * {@code execution_timeout} behaves there (the chat path reads chat_config, not the agent column).
     */
    @Column(name = "inactivity_timeout")
    private Integer inactivityTimeout;

    /**
     * Stage 5.2b - override for the COLD summariser model provider.
     * NULL ⇒ inherit {@link #modelProvider} (agent's primary model).
     * Resolved at call time by
     * {@code AgentCompactionModelResolver.resolve(...)}.
     */
    @Column(name = "compaction_model_provider", length = 50)
    private String compactionModelProvider;

    /**
     * Stage 5.2b - override for the COLD summariser model name.
     * NULL ⇒ inherit {@link #modelName}. Either column NULL counts as
     * "override not set" - partial overrides fall through deliberately
     * (see resolver Javadoc).
     */
    @Column(name = "compaction_model_name", length = 100)
    private String compactionModelName;

    /**
     * Per-agent override of the global compaction master switch
     * ({@code conversation.compaction.enabled}). NULL ⇒ inherit (the
     * per-conversation override, then the YAML default). Resolved by
     * {@code CompactionConfigResolver}.
     */
    @Column(name = "compaction_enabled")
    private Boolean compactionEnabled;

    /**
     * Per-agent compaction cadence: minimum new turns between COLD-summary
     * regenerations. NULL ⇒ inherit (the per-conversation override, then
     * {@code conversation.compaction.cadenceTurns}). DB CHECK enforces {@code >= 1}.
     */
    @Column(name = "compaction_after_turns")
    private Integer compactionAfterTurns;

    // ─── Per-agent guard-threshold overrides (V100) ──────────────────────────────
    // All nullable - null means "fall back to AgentDefaultsConfig (YAML)".
    // DB CHECK constraints enforce positivity / minimum sensible values.
    // Resolution logic lives in the individual tool modules + LoopDetector wiring.

    /**
     * Per-agent override of {@code ai.agent.defaults.max-per-resource-per-turn}
     * (default 5). Uniform cap applied separately to each tracked resource type:
     * agent / skill / sub_agent / interface / workflow / table.
     */
    @Column(name = "max_per_resource_per_turn")
    private Integer maxPerResourcePerTurn;

    /** Per-agent override of {@code ai.agent.defaults.loop-identical-stop} (default 15). */
    @Column(name = "loop_identical_stop")
    private Integer loopIdenticalStop;

    /** Per-agent override of {@code ai.agent.defaults.loop-consecutive-stop} (default 40). */
    @Column(name = "loop_consecutive_stop")
    private Integer loopConsecutiveStop;

    /**
     * Per-agent reasoning-effort override for CLI/bridge providers
     * ({@code minimal|low|medium|high|xhigh} - see {@link ReasoningEffort}).
     * NULL ⇒ fall back to the per-model admin default, then the CLI's own
     * default. Validated via {@link ReasoningEffort#isValidOrBlank(String)} at
     * the service write boundary; ignored by non-bridge providers.
     */
    @Column(name = "reasoning_effort", length = 16)
    private String reasoningEffort;

    /**
     * Configuration des outils/capabilities disponibles pour l'agent (JSONB).
     * Stored as a Map: {"mode": "all|custom|none", "tools": [...], "workflows": [...], ...}
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tools_config", columnDefinition = "jsonb")
    private Map<String, Object> toolsConfig;

    /**
     * Optional workflow ID reference (cross-service, no JPA relationship).
     */
    @Column(name = "workflow_id")
    private UUID workflowId;

    /**
     * Lien optionnel vers une data source pour le contexte.
     */
    @Column(name = "data_source_id")
    private Long dataSourceId;

    /**
     * Lien optionnel vers une conversation.
     */
    @Column(name = "conversation_id")
    private UUID conversationId;

    /**
     * Configuration additionnelle (JSONB pour flexibilité).
     * Exemple: {"streaming": true, "supports_tool_calls": true, ...}
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config", columnDefinition = "jsonb")
    private Map<String, Object> config;

    /**
     * URL de l'avatar de l'agent.
     * Peut être un preset (ex: "preset:robot-1") ou une URL d'image uploadée.
     */
    @Column(name = "avatar_url", length = 500)
    private String avatarUrl;

    @Column(name = "is_public", nullable = false)
    private Boolean isPublic = false;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    /**
     * Extension seam for the synchronous tool-authorization gate (default false).
     * When true, a future iteration applies the gate to this agent's sensitive
     * actions even outside interactive chat (workflow/task/sub-agent). v1 leaves
     * this false for every agent and does not yet thread it into execution
     * credentials - see {@code ToolAuthorizationScopeResolver}.
     */
    @Column(name = "require_tool_authorization", nullable = false)
    private Boolean requireToolAuthorization = false;

    /**
     * V340 - opt-in participation in the shared task backlog (default false).
     * <p>
     * When false the agent is never served the workspace backlog on wake-up
     * (schedule prompt + system-prompt task summary) and cannot autonomously
     * claim backlog items via the {@code agent} tool. Tasks assigned DIRECTLY to
     * the agent (inbox) and tasks it must review are unaffected - those are
     * targeted, not backlog. Human board claim/assign is a separate, always-
     * allowed override. See {@code ScheduledTaskPromptBuilder},
     * {@code AgentTaskService.getTaskSummaryForPrompt}, {@code AgentDelegationModule}.
     */
    @Column(name = "backlog_enabled", nullable = false)
    private Boolean backlogEnabled = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // Observability counter columns (Phase 2)
    @Column(name = "total_executions", nullable = false)
    private int totalExecutions = 0;

    @Column(name = "total_tokens_used", nullable = false)
    private long totalTokensUsed = 0;

    @Column(name = "total_tool_calls", nullable = false)
    private int totalToolCalls = 0;

    @Column(name = "success_count", nullable = false)
    private int successCount = 0;

    @Column(name = "failure_count", nullable = false)
    private int failureCount = 0;

    @Column(name = "total_duration_ms", nullable = false)
    private long totalDurationMs = 0;

    @Column(name = "last_execution_at")
    private Instant lastExecutionAt;

    // Credit budget fields
    // Precision widened to 19,4 to match V68__agent_budget_hierarchy.sql. The two
    // server-managed columns (credits_consumed, credits_reserved) carry updatable=false
    // so Hibernate strips them from every JPA dirty-flush UPDATE - writes go exclusively
    // through targeted queries. See the project docs writer
    // audit table for the full list of authorized writers.
    @Column(name = "credit_budget", precision = 19, scale = 4)
    private BigDecimal creditBudget;

    @Column(name = "credits_consumed", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal creditsConsumed = BigDecimal.ZERO;

    /**
     * Observable breakdown of {@link #creditsConsumed}: the subset attributable to
     * descendant sub-agent cascade settles. Written exclusively by
     * {@code BudgetReservationService.settleReservationChain} in the same native UPDATE
     * that bumps {@code credits_consumed}, which guarantees
     * {@code creditsConsumedFromSubagents <= creditsConsumed} by construction.
     *
     * <p>Frontends/APIs can derive {@code consumed_own = consumed - consumed_from_subagents}
     * and expose both fields so users can see how much of an agent's spend came from
     * cascaded descendants versus its own LLM calls - the full rationale is in V71's
     * migration comment. {@code updatable = false} matches the pattern for
     * {@code credits_consumed} / {@code credits_reserved} to keep JPA dirty flushes out of
     * the write path.
     */
    @Column(name = "credits_consumed_from_subagents", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal creditsConsumedFromSubagents = BigDecimal.ZERO;

    @Column(name = "credits_reserved", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal creditsReserved = BigDecimal.ZERO;

    @Column(name = "budget_reset_mode", length = 20)
    private String budgetResetMode = "cumulative";

    @Column(name = "budget_last_reset")
    private Instant budgetLastReset;

    @Column(name = "project_id")
    private UUID projectId;

    @Column(name = "organization_id")
    private String organizationId;

    /**
     * Publication ID that caused this agent to be cloned (acquire-time traceability).
     */
    @Column(name = "source_publication_id")
    private UUID sourcePublicationId;

    public AgentEntity() {
    }

    public AgentEntity(String tenantId,
                      String name,
                      String description,
                      String systemPrompt,
                      String modelProvider,
                      String modelName,
                      BigDecimal temperature,
                      Integer maxTokens,
                      Integer maxIterations,
                      Map<String, Object> toolsConfig,
                      UUID workflowId,
                      Long dataSourceId,
                      UUID conversationId,
                      Map<String, Object> config,
                      Boolean isPublic,
                      Boolean isActive) {
        this.tenantId = tenantId;
        this.name = name;
        this.description = description;
        this.systemPrompt = systemPrompt;
        this.modelProvider = modelProvider;
        this.modelName = modelName;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        this.maxIterations = maxIterations;
        this.toolsConfig = toolsConfig;
        this.workflowId = workflowId;
        this.dataSourceId = dataSourceId;
        this.conversationId = conversationId;
        this.config = config;
        this.isPublic = isPublic != null ? isPublic : false;
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
        if (this.isPublic == null) {
            this.isPublic = false;
        }
        if (this.isActive == null) {
            this.isActive = true;
        }
        // temperature, maxTokens, maxIterations defaults are applied by AgentService
        // from AgentDefaultsConfig (application-agents.yml)
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

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    public String getModelProvider() {
        return modelProvider;
    }

    public void setModelProvider(String modelProvider) {
        this.modelProvider = modelProvider;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public BigDecimal getTemperature() {
        return temperature;
    }

    public void setTemperature(BigDecimal temperature) {
        this.temperature = temperature;
    }

    public Integer getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(Integer maxTokens) {
        this.maxTokens = maxTokens;
    }

    public Integer getMaxIterations() {
        return maxIterations;
    }

    public void setMaxIterations(Integer maxIterations) {
        this.maxIterations = maxIterations;
    }

    public Integer getExecutionTimeout() {
        return executionTimeout;
    }

    public void setExecutionTimeout(Integer executionTimeout) {
        this.executionTimeout = executionTimeout;
    }

    public Integer getInactivityTimeout() {
        return inactivityTimeout;
    }

    public void setInactivityTimeout(Integer inactivityTimeout) {
        this.inactivityTimeout = inactivityTimeout;
    }

    public String getCompactionModelProvider() {
        return compactionModelProvider;
    }

    public void setCompactionModelProvider(String compactionModelProvider) {
        this.compactionModelProvider = compactionModelProvider;
    }

    public String getCompactionModelName() {
        return compactionModelName;
    }

    public void setCompactionModelName(String compactionModelName) {
        this.compactionModelName = compactionModelName;
    }

    public Boolean getCompactionEnabled() {
        return compactionEnabled;
    }

    public void setCompactionEnabled(Boolean compactionEnabled) {
        this.compactionEnabled = compactionEnabled;
    }

    public Integer getCompactionAfterTurns() {
        return compactionAfterTurns;
    }

    public void setCompactionAfterTurns(Integer compactionAfterTurns) {
        this.compactionAfterTurns = compactionAfterTurns;
    }

    public Integer getMaxPerResourcePerTurn() {
        return maxPerResourcePerTurn;
    }

    public void setMaxPerResourcePerTurn(Integer maxPerResourcePerTurn) {
        this.maxPerResourcePerTurn = maxPerResourcePerTurn;
    }

    public Integer getLoopIdenticalStop() {
        return loopIdenticalStop;
    }

    public void setLoopIdenticalStop(Integer loopIdenticalStop) {
        this.loopIdenticalStop = loopIdenticalStop;
    }

    public Integer getLoopConsecutiveStop() {
        return loopConsecutiveStop;
    }

    public void setLoopConsecutiveStop(Integer loopConsecutiveStop) {
        this.loopConsecutiveStop = loopConsecutiveStop;
    }

    public String getReasoningEffort() {
        return reasoningEffort;
    }

    public void setReasoningEffort(String reasoningEffort) {
        this.reasoningEffort = reasoningEffort;
    }

    public Map<String, Object> getToolsConfig() {
        return toolsConfig;
    }

    @SuppressWarnings("unchecked")
    public void setToolsConfig(Object toolsConfig) {
        if (toolsConfig instanceof Map<?, ?> map) {
            this.toolsConfig = (Map<String, Object>) map;
        } else {
            this.toolsConfig = null;
        }
    }

    public UUID getWorkflowId() {
        return workflowId;
    }

    public void setWorkflowId(UUID workflowId) {
        this.workflowId = workflowId;
    }

    public Long getDataSourceId() {
        return dataSourceId;
    }

    public void setDataSourceId(Long dataSourceId) {
        this.dataSourceId = dataSourceId;
    }

    public UUID getConversationId() {
        return conversationId;
    }

    public void setConversationId(UUID conversationId) {
        this.conversationId = conversationId;
    }

    public Map<String, Object> getConfig() {
        return config;
    }

    public void setConfig(Map<String, Object> config) {
        this.config = config;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public void setAvatarUrl(String avatarUrl) {
        this.avatarUrl = avatarUrl;
    }

    public Boolean getIsPublic() {
        return isPublic;
    }

    public void setIsPublic(Boolean isPublic) {
        this.isPublic = isPublic;
    }

    public Boolean getIsActive() {
        return isActive;
    }

    public void setIsActive(Boolean isActive) {
        this.isActive = isActive;
    }

    public Boolean getRequireToolAuthorization() {
        return requireToolAuthorization != null && requireToolAuthorization;
    }

    public void setRequireToolAuthorization(Boolean requireToolAuthorization) {
        this.requireToolAuthorization = requireToolAuthorization != null && requireToolAuthorization;
    }

    /** V340 - true when the agent opted into the shared backlog. Serialized as {@code backlogEnabled}. */
    public boolean isBacklogEnabled() {
        return backlogEnabled != null && backlogEnabled;
    }

    public void setBacklogEnabled(Boolean backlogEnabled) {
        this.backlogEnabled = backlogEnabled != null && backlogEnabled;
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

    public int getTotalExecutions() {
        return totalExecutions;
    }

    public void setTotalExecutions(int totalExecutions) {
        this.totalExecutions = totalExecutions;
    }

    public long getTotalTokensUsed() {
        return totalTokensUsed;
    }

    public void setTotalTokensUsed(long totalTokensUsed) {
        this.totalTokensUsed = totalTokensUsed;
    }

    public int getTotalToolCalls() {
        return totalToolCalls;
    }

    public void setTotalToolCalls(int totalToolCalls) {
        this.totalToolCalls = totalToolCalls;
    }

    public int getSuccessCount() {
        return successCount;
    }

    public void setSuccessCount(int successCount) {
        this.successCount = successCount;
    }

    public int getFailureCount() {
        return failureCount;
    }

    public void setFailureCount(int failureCount) {
        this.failureCount = failureCount;
    }

    public long getTotalDurationMs() {
        return totalDurationMs;
    }

    public void setTotalDurationMs(long totalDurationMs) {
        this.totalDurationMs = totalDurationMs;
    }

    public Instant getLastExecutionAt() {
        return lastExecutionAt;
    }

    public void setLastExecutionAt(Instant lastExecutionAt) {
        this.lastExecutionAt = lastExecutionAt;
    }

    public BigDecimal getCreditBudget() {
        return creditBudget;
    }

    public void setCreditBudget(BigDecimal creditBudget) {
        this.creditBudget = creditBudget;
    }

    public BigDecimal getCreditsConsumed() {
        return creditsConsumed;
    }

    public void setCreditsConsumed(BigDecimal creditsConsumed) {
        this.creditsConsumed = creditsConsumed;
    }

    public BigDecimal getCreditsConsumedFromSubagents() {
        return creditsConsumedFromSubagents;
    }

    public void setCreditsConsumedFromSubagents(BigDecimal creditsConsumedFromSubagents) {
        this.creditsConsumedFromSubagents = creditsConsumedFromSubagents;
    }

    public BigDecimal getCreditsReserved() {
        return creditsReserved;
    }

    public void setCreditsReserved(BigDecimal creditsReserved) {
        this.creditsReserved = creditsReserved;
    }

    /**
     * Computed free budget = creditBudget - creditsConsumed - creditsReserved.
     * Returns null when creditBudget is null (unlimited). Clamped at zero defensively -
     * in steady state it is never negative because tryReserveChain enforces
     * free >= amount atomically, but transient observations of a partially-constructed
     * entity could otherwise surface a negative value.
     *
     * <p>@Transient is a JPA-only marker ("do not persist this"). Jackson inspects
     * property getters regardless of this annotation, so this getter auto-serializes
     * as {@code creditsFree} in REST responses that return {@link AgentEntity} directly
     * (notably {@link com.apimarketplace.agent.controller.AgentController#getAgent}).
     * Same applies to {@link #getCreditsReserved()} - it's a real column with a
     * generated accessor and is also auto-serialized.
     */
    @Transient
    public BigDecimal getCreditsFree() {
        if (creditBudget == null) {
            return null; // unlimited
        }
        BigDecimal consumed = creditsConsumed != null ? creditsConsumed : BigDecimal.ZERO;
        BigDecimal reserved = creditsReserved != null ? creditsReserved : BigDecimal.ZERO;
        return creditBudget.subtract(consumed).subtract(reserved).max(BigDecimal.ZERO);
    }

    public String getBudgetResetMode() {
        return budgetResetMode;
    }

    public void setBudgetResetMode(String budgetResetMode) {
        this.budgetResetMode = budgetResetMode;
    }

    public Instant getBudgetLastReset() {
        return budgetLastReset;
    }

    public void setBudgetLastReset(Instant budgetLastReset) {
        this.budgetLastReset = budgetLastReset;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public void setProjectId(UUID projectId) {
        this.projectId = projectId;
    }

    public String getOrganizationId() {
        return organizationId;
    }

    public void setOrganizationId(String organizationId) {
        this.organizationId = organizationId;
    }

    public UUID getSourcePublicationId() {
        return sourcePublicationId;
    }

    public void setSourcePublicationId(UUID sourcePublicationId) {
        this.sourcePublicationId = sourcePublicationId;
    }
}
