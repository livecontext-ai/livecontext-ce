package com.apimarketplace.agent.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * DTO representing an agent entity.
 * Used for inter-service communication between orchestrator and agent-service.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AgentDto {

    private UUID id;
    private String tenantId;
    private String name;
    private String description;
    private String systemPrompt;
    private String modelProvider;
    private String modelName;
    private BigDecimal temperature;
    private Integer maxTokens;
    private Integer maxIterations;
    private Integer executionTimeout;
    /** Per-agent inactivity watchdog window (seconds). Null => 5-min default; 0 => disabled; 10-7200 => custom. */
    private Integer inactivityTimeout;

    // Per-agent guard overrides (V100). Null ⇒ fall back to platform default from
    // AgentDefaultsConfig. See AgentEntity Javadoc for field semantics.
    private Integer maxPerResourcePerTurn;
    private Integer loopIdenticalStop;
    private Integer loopConsecutiveStop;

    // Per-agent reasoning-effort override for CLI/bridge providers
    // (minimal|low|medium|high|xhigh). Null ⇒ fall back to per-model default
    // then the CLI's own default. See AgentEntity.reasoningEffort.
    private String reasoningEffort;

    // Stage 5.2b - per-agent override for the COLD summariser model.
    // Null on both ⇒ AgentCompactionModelResolver falls back to the agent's
    // primary model and then to AgentDefaultsConfig.compactionModel.
    private String compactionModelProvider;
    private String compactionModelName;
    // V350 - per-agent compaction enable + cadence override. Null ⇒ inherit
    // (conversation override, then conversation.compaction.* YAML default).
    private Boolean compactionEnabled;
    private Integer compactionAfterTurns;
    private Map<String, Object> toolsConfig;
    private UUID workflowId;
    private Long dataSourceId;
    private UUID conversationId;
    private Map<String, Object> config;
    private String avatarUrl;
    private Boolean isPublic;
    private Boolean isActive;
    private Instant createdAt;
    private Instant updatedAt;
    private UUID projectId;
    private String organizationId;
    private UUID sourcePublicationId;

    // Credit budget
    private BigDecimal creditBudget;
    private BigDecimal creditsConsumed;
    private String budgetResetMode;
    private Instant budgetLastReset;

    /**
     * Whether this agent's own cap refuses the next run, resolved by agent-service.
     *
     * <p>The four fields above are not enough to work this out, and that is the point.
     * Deciding it needs {@code creditsReserved}, which is deliberately not on this DTO, and
     * it needs the LAZY reset applied without writing - so a consumer that computed it from
     * the raw columns would answer "blocked" for a whole month after a monthly cap was
     * reached. agent-service resolves it once, through the rule the enforcement path uses
     * ({@code AgentBudgetRule}), and every consumer reads this instead of re-deriving.
     *
     * <p>Null on a caller old enough not to send it, which every reader must treat as "not
     * blocked": a gate that cannot read the verdict must never be the reason a run stops.
     */
    private Boolean budgetBlocked;

    /**
     * What this agent has COMMITTED against its cap: the post-reset accumulator plus the
     * credits an in-flight sub-agent is holding.
     *
     * <p>Here because {@code creditsReserved} deliberately is not: a consumer that printed
     * {@code creditsConsumed} alone said "6 of 10 credits" while refusing, and the missing 4
     * were the reason. Resolved by agent-service, where both inputs live.
     */
    private BigDecimal budgetCommitted;

    /**
     * When {@link #budgetBlocked} lifts by itself, or null when it never does.
     *
     * <p>Null is meaningful on a blocked agent: the cap is cumulative, so the whole
     * projected future is refused rather than a window of it. Same contract as the workflow
     * cap's {@code budgetBlockedUntil}, so the agenda can consume both the same way.
     */
    private Instant budgetBlockedUntil;

    // Observability counters
    private int totalExecutions;
    private long totalTokensUsed;
    private int totalToolCalls;
    private int successCount;
    private int failureCount;
    private long totalDurationMs;
    private Instant lastExecutionAt;

    public AgentDto() {}

    // Getters and setters

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }

    public String getModelProvider() { return modelProvider; }
    public void setModelProvider(String modelProvider) { this.modelProvider = modelProvider; }

    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }

    public BigDecimal getTemperature() { return temperature; }
    public void setTemperature(BigDecimal temperature) { this.temperature = temperature; }

    public Integer getMaxTokens() { return maxTokens; }
    public void setMaxTokens(Integer maxTokens) { this.maxTokens = maxTokens; }

    public Integer getMaxIterations() { return maxIterations; }
    public void setMaxIterations(Integer maxIterations) { this.maxIterations = maxIterations; }

    public Integer getExecutionTimeout() { return executionTimeout; }
    public void setExecutionTimeout(Integer executionTimeout) { this.executionTimeout = executionTimeout; }

    public Integer getInactivityTimeout() { return inactivityTimeout; }
    public void setInactivityTimeout(Integer inactivityTimeout) { this.inactivityTimeout = inactivityTimeout; }

    public Integer getMaxPerResourcePerTurn() { return maxPerResourcePerTurn; }
    public void setMaxPerResourcePerTurn(Integer maxPerResourcePerTurn) { this.maxPerResourcePerTurn = maxPerResourcePerTurn; }

    public Integer getLoopIdenticalStop() { return loopIdenticalStop; }
    public void setLoopIdenticalStop(Integer loopIdenticalStop) { this.loopIdenticalStop = loopIdenticalStop; }

    public Integer getLoopConsecutiveStop() { return loopConsecutiveStop; }
    public void setLoopConsecutiveStop(Integer loopConsecutiveStop) { this.loopConsecutiveStop = loopConsecutiveStop; }

    public String getReasoningEffort() { return reasoningEffort; }
    public void setReasoningEffort(String reasoningEffort) { this.reasoningEffort = reasoningEffort; }

    public String getCompactionModelProvider() { return compactionModelProvider; }
    public void setCompactionModelProvider(String compactionModelProvider) { this.compactionModelProvider = compactionModelProvider; }

    public String getCompactionModelName() { return compactionModelName; }
    public void setCompactionModelName(String compactionModelName) { this.compactionModelName = compactionModelName; }

    public Boolean getCompactionEnabled() { return compactionEnabled; }
    public void setCompactionEnabled(Boolean compactionEnabled) { this.compactionEnabled = compactionEnabled; }

    public Integer getCompactionAfterTurns() { return compactionAfterTurns; }
    public void setCompactionAfterTurns(Integer compactionAfterTurns) { this.compactionAfterTurns = compactionAfterTurns; }

    public Map<String, Object> getToolsConfig() { return toolsConfig; }
    public void setToolsConfig(Map<String, Object> toolsConfig) { this.toolsConfig = toolsConfig; }

    public UUID getWorkflowId() { return workflowId; }
    public void setWorkflowId(UUID workflowId) { this.workflowId = workflowId; }

    public Long getDataSourceId() { return dataSourceId; }
    public void setDataSourceId(Long dataSourceId) { this.dataSourceId = dataSourceId; }

    public UUID getConversationId() { return conversationId; }
    public void setConversationId(UUID conversationId) { this.conversationId = conversationId; }

    public Map<String, Object> getConfig() { return config; }
    public void setConfig(Map<String, Object> config) { this.config = config; }

    public String getAvatarUrl() { return avatarUrl; }
    public void setAvatarUrl(String avatarUrl) { this.avatarUrl = avatarUrl; }

    public Boolean getIsPublic() { return isPublic; }
    public void setIsPublic(Boolean isPublic) { this.isPublic = isPublic; }

    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public UUID getProjectId() { return projectId; }
    public void setProjectId(UUID projectId) { this.projectId = projectId; }

    public String getOrganizationId() { return organizationId; }
    public void setOrganizationId(String organizationId) { this.organizationId = organizationId; }

    public UUID getSourcePublicationId() { return sourcePublicationId; }
    public void setSourcePublicationId(UUID sourcePublicationId) { this.sourcePublicationId = sourcePublicationId; }

    public int getTotalExecutions() { return totalExecutions; }
    public void setTotalExecutions(int totalExecutions) { this.totalExecutions = totalExecutions; }

    public long getTotalTokensUsed() { return totalTokensUsed; }
    public void setTotalTokensUsed(long totalTokensUsed) { this.totalTokensUsed = totalTokensUsed; }

    public int getTotalToolCalls() { return totalToolCalls; }
    public void setTotalToolCalls(int totalToolCalls) { this.totalToolCalls = totalToolCalls; }

    public int getSuccessCount() { return successCount; }
    public void setSuccessCount(int successCount) { this.successCount = successCount; }

    public int getFailureCount() { return failureCount; }
    public void setFailureCount(int failureCount) { this.failureCount = failureCount; }

    public long getTotalDurationMs() { return totalDurationMs; }
    public void setTotalDurationMs(long totalDurationMs) { this.totalDurationMs = totalDurationMs; }

    public Instant getLastExecutionAt() { return lastExecutionAt; }
    public void setLastExecutionAt(Instant lastExecutionAt) { this.lastExecutionAt = lastExecutionAt; }

    public BigDecimal getCreditBudget() { return creditBudget; }
    public void setCreditBudget(BigDecimal creditBudget) { this.creditBudget = creditBudget; }

    public BigDecimal getCreditsConsumed() { return creditsConsumed; }
    public void setCreditsConsumed(BigDecimal creditsConsumed) { this.creditsConsumed = creditsConsumed; }

    public String getBudgetResetMode() { return budgetResetMode; }
    public void setBudgetResetMode(String budgetResetMode) { this.budgetResetMode = budgetResetMode; }

    public Instant getBudgetLastReset() { return budgetLastReset; }
    public void setBudgetLastReset(Instant budgetLastReset) { this.budgetLastReset = budgetLastReset; }

    public Boolean getBudgetBlocked() { return budgetBlocked; }
    public void setBudgetBlocked(Boolean budgetBlocked) { this.budgetBlocked = budgetBlocked; }

    /**
     * Null-safe read: an absent verdict is "not blocked", so an old payload fails open.
     *
     * <p>Deliberately NOT named {@code isBudgetBlocked}: Jackson would then see two getters
     * for one property and refuse to serialize the whole DTO ("conflicting getter
     * definitions"), which would break every endpoint that returns an agent, not just this
     * field.
     */
    public boolean budgetBlockedOrFalse() { return Boolean.TRUE.equals(budgetBlocked); }

    public BigDecimal getBudgetCommitted() { return budgetCommitted; }
    public void setBudgetCommitted(BigDecimal budgetCommitted) { this.budgetCommitted = budgetCommitted; }

    public Instant getBudgetBlockedUntil() { return budgetBlockedUntil; }
    public void setBudgetBlockedUntil(Instant budgetBlockedUntil) { this.budgetBlockedUntil = budgetBlockedUntil; }
}
