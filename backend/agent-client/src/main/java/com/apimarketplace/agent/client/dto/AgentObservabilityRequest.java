package com.apimarketplace.agent.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * DTO for recording agent execution observability data.
 * Sent from orchestrator to agent-service after agent execution completes.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AgentObservabilityRequest {

    private String tenantId;
    /**
     * PR20 - workspace identity. NULL = personal scope, non-null = the org
     * workspace the producer (orchestrator / conversation-service) was acting
     * in when it called recordObservability. Stamped on the row so the
     * AgentExecutionHistoryPanel can scope its listing by strict isolation.
     */
    private String organizationId;
    private UUID agentEntityId;
    private String agentType;

    // Workflow context
    private Long stepDataId;
    private UUID workflowId;
    private UUID workflowRunId;
    private String runId;
    private String nodeId;
    private int epoch;
    private int spawn;
    private int itemIndex;
    private Integer loopIteration;

    // Execution result
    private String status;
    private String stopReason;
    /** Budget guard scope when stopReason=BUDGET_EXHAUSTED ('tenant' | 'agent'). */
    private String budgetScope;
    private String errorMessage;

    // LLM config snapshot
    private String provider;
    private String model;
    private Double temperature;
    private Integer maxTokensConfig;
    private Integer maxIterationsConfig;
    private Map<String, Object> agentConfigSnapshot;

    // Counters
    private int iterationCount;
    private int totalToolCalls;
    private long totalTokens;
    private long promptTokens;
    private long completionTokens;
    private long cacheCreationTokens;
    private long cacheReadTokens;
    private long cachedTokens;
    private long reasoningTokens;
    private long durationMs;
    private boolean loopDetected;
    private String loopType;
    private String loopToolName;
    private int uniqueToolCount;
    private String systemPrompt;

    // Detailed data
    private List<IterationData> iterations;
    private List<MessageData> messages;
    private List<ToolCallData> toolCalls;

    // Sub-agent context
    private UUID callerExecutionId;
    private UUID callerAgentId;
    private int nestingDepth;
    private String callerToolCallId;

    // Memory tracking - whether the agent had access to its conversation history
    private Boolean memoryEnabled;

    // Conversation context - links execution to a conversation record
    private String conversationId;

    // Parent conversation context - the conversation that SPAWNED this sub-agent
    // (distinct from conversationId, which is the sub-agent's own conversation).
    // Populated only on sub-agent spawns; null for root executions. Lets a
    // conversation-scoped observability view surface spawned sub-agent executions.
    private String parentConversationId;

    // Source type override (e.g. "CHAT", "WEBHOOK") - when null, doRecordFromRequest defaults to "WORKFLOW"
    private String source;

    // Task linkage - populated when the execution is associated with an agent task.
    private UUID taskId;

    // Stable correlation ID minted at dispatch. Becomes the persisted
    // agent_executions.id (instead of Hibernate auto-generating it at INSERT
    // time). Lets MCP-side claim writes index into the claim log by the same
    // UUID that the observability writer will use for the row, closing the
    // race where the claim row was written before the execution row existed.
    private String executionId;

    // Cascade budget reservation (§4.2/§4.5 of AGENT_BUDGET_HIERARCHY.md).
    // Populated only on sub-agent spawns: the nearest-first chain of ancestor agent ids that
    // hold a reservation for this execution, and the reserved amount. Null on root invocations
    // (REST, CLI, orchestrator AgentNode root spawns). When non-null, recordFromRequest calls
    // BudgetReservationService.settleReservationChain to refund/debit the chain atomically
    // with the credit consumption write.
    private List<UUID> callerChain;
    private BigDecimal reservedAmount;

    public AgentObservabilityRequest() {}

    // Getters and setters

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getOrganizationId() { return organizationId; }
    public void setOrganizationId(String organizationId) { this.organizationId = organizationId; }

    public UUID getAgentEntityId() { return agentEntityId; }
    public void setAgentEntityId(UUID agentEntityId) { this.agentEntityId = agentEntityId; }

    public String getAgentType() { return agentType; }
    public void setAgentType(String agentType) { this.agentType = agentType; }

    public Long getStepDataId() { return stepDataId; }
    public void setStepDataId(Long stepDataId) { this.stepDataId = stepDataId; }

    public UUID getWorkflowId() { return workflowId; }
    public void setWorkflowId(UUID workflowId) { this.workflowId = workflowId; }

    public UUID getWorkflowRunId() { return workflowRunId; }
    public void setWorkflowRunId(UUID workflowRunId) { this.workflowRunId = workflowRunId; }

    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }

    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }

    public int getEpoch() { return epoch; }
    public void setEpoch(int epoch) { this.epoch = epoch; }

    public int getSpawn() { return spawn; }
    public void setSpawn(int spawn) { this.spawn = spawn; }

    public int getItemIndex() { return itemIndex; }
    public void setItemIndex(int itemIndex) { this.itemIndex = itemIndex; }

    public Integer getLoopIteration() { return loopIteration; }
    public void setLoopIteration(Integer loopIteration) { this.loopIteration = loopIteration; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getStopReason() { return stopReason; }
    public void setStopReason(String stopReason) { this.stopReason = stopReason; }
    public String getBudgetScope() { return budgetScope; }
    public void setBudgetScope(String budgetScope) { this.budgetScope = budgetScope; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public Double getTemperature() { return temperature; }
    public void setTemperature(Double temperature) { this.temperature = temperature; }

    public Integer getMaxTokensConfig() { return maxTokensConfig; }
    public void setMaxTokensConfig(Integer maxTokensConfig) { this.maxTokensConfig = maxTokensConfig; }

    public Integer getMaxIterationsConfig() { return maxIterationsConfig; }
    public void setMaxIterationsConfig(Integer maxIterationsConfig) { this.maxIterationsConfig = maxIterationsConfig; }

    public Map<String, Object> getAgentConfigSnapshot() { return agentConfigSnapshot; }
    public void setAgentConfigSnapshot(Map<String, Object> agentConfigSnapshot) { this.agentConfigSnapshot = agentConfigSnapshot; }

    public int getIterationCount() { return iterationCount; }
    public void setIterationCount(int iterationCount) { this.iterationCount = iterationCount; }

    public int getTotalToolCalls() { return totalToolCalls; }
    public void setTotalToolCalls(int totalToolCalls) { this.totalToolCalls = totalToolCalls; }

    public long getTotalTokens() { return totalTokens; }
    public void setTotalTokens(long totalTokens) { this.totalTokens = totalTokens; }

    public long getPromptTokens() { return promptTokens; }
    public void setPromptTokens(long promptTokens) { this.promptTokens = promptTokens; }

    public long getCompletionTokens() { return completionTokens; }
    public void setCompletionTokens(long completionTokens) { this.completionTokens = completionTokens; }

    public long getCacheCreationTokens() { return cacheCreationTokens; }
    public void setCacheCreationTokens(long cacheCreationTokens) { this.cacheCreationTokens = cacheCreationTokens; }

    public long getCacheReadTokens() { return cacheReadTokens; }
    public void setCacheReadTokens(long cacheReadTokens) { this.cacheReadTokens = cacheReadTokens; }

    public long getCachedTokens() { return cachedTokens; }
    public void setCachedTokens(long cachedTokens) { this.cachedTokens = cachedTokens; }

    public long getReasoningTokens() { return reasoningTokens; }
    public void setReasoningTokens(long reasoningTokens) { this.reasoningTokens = reasoningTokens; }

    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }

    public boolean isLoopDetected() { return loopDetected; }
    public void setLoopDetected(boolean loopDetected) { this.loopDetected = loopDetected; }

    public String getLoopType() { return loopType; }
    public void setLoopType(String loopType) { this.loopType = loopType; }

    public String getLoopToolName() { return loopToolName; }
    public void setLoopToolName(String loopToolName) { this.loopToolName = loopToolName; }

    public int getUniqueToolCount() { return uniqueToolCount; }
    public void setUniqueToolCount(int uniqueToolCount) { this.uniqueToolCount = uniqueToolCount; }

    public String getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }

    public List<IterationData> getIterations() { return iterations; }
    public void setIterations(List<IterationData> iterations) { this.iterations = iterations; }

    public List<MessageData> getMessages() { return messages; }
    public void setMessages(List<MessageData> messages) { this.messages = messages; }

    public List<ToolCallData> getToolCalls() { return toolCalls; }
    public void setToolCalls(List<ToolCallData> toolCalls) { this.toolCalls = toolCalls; }

    public UUID getCallerExecutionId() { return callerExecutionId; }
    public void setCallerExecutionId(UUID callerExecutionId) { this.callerExecutionId = callerExecutionId; }

    public UUID getCallerAgentId() { return callerAgentId; }
    public void setCallerAgentId(UUID callerAgentId) { this.callerAgentId = callerAgentId; }

    public int getNestingDepth() { return nestingDepth; }
    public void setNestingDepth(int nestingDepth) { this.nestingDepth = nestingDepth; }

    public String getCallerToolCallId() { return callerToolCallId; }
    public void setCallerToolCallId(String callerToolCallId) { this.callerToolCallId = callerToolCallId; }

    public Boolean getMemoryEnabled() { return memoryEnabled; }
    public void setMemoryEnabled(Boolean memoryEnabled) { this.memoryEnabled = memoryEnabled; }

    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }

    public String getParentConversationId() { return parentConversationId; }
    public void setParentConversationId(String parentConversationId) { this.parentConversationId = parentConversationId; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public UUID getTaskId() { return taskId; }
    public void setTaskId(UUID taskId) { this.taskId = taskId; }

    public String getExecutionId() { return executionId; }
    public void setExecutionId(String executionId) { this.executionId = executionId; }

    public List<UUID> getCallerChain() { return callerChain; }
    public void setCallerChain(List<UUID> callerChain) { this.callerChain = callerChain; }

    public BigDecimal getReservedAmount() { return reservedAmount; }
    public void setReservedAmount(BigDecimal reservedAmount) { this.reservedAmount = reservedAmount; }

    /**
     * Iteration-level data for observability recording.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class IterationData {
        private int iterationNumber;
        private int toolCallCount;
        private long promptTokens;
        private long completionTokens;
        private long cacheCreationTokens;
        private long cacheReadTokens;
        private long cachedTokens;
        private long reasoningTokens;
        private long durationMs;
        private String finishReason;

        public int getIterationNumber() { return iterationNumber; }
        public void setIterationNumber(int iterationNumber) { this.iterationNumber = iterationNumber; }
        public int getToolCallCount() { return toolCallCount; }
        public void setToolCallCount(int toolCallCount) { this.toolCallCount = toolCallCount; }
        public long getPromptTokens() { return promptTokens; }
        public void setPromptTokens(long promptTokens) { this.promptTokens = promptTokens; }
        public long getCompletionTokens() { return completionTokens; }
        public void setCompletionTokens(long completionTokens) { this.completionTokens = completionTokens; }
        public long getCacheCreationTokens() { return cacheCreationTokens; }
        public void setCacheCreationTokens(long cacheCreationTokens) { this.cacheCreationTokens = cacheCreationTokens; }
        public long getCacheReadTokens() { return cacheReadTokens; }
        public void setCacheReadTokens(long cacheReadTokens) { this.cacheReadTokens = cacheReadTokens; }
        public long getCachedTokens() { return cachedTokens; }
        public void setCachedTokens(long cachedTokens) { this.cachedTokens = cachedTokens; }
        public long getReasoningTokens() { return reasoningTokens; }
        public void setReasoningTokens(long reasoningTokens) { this.reasoningTokens = reasoningTokens; }
        public long getDurationMs() { return durationMs; }
        public void setDurationMs(long durationMs) { this.durationMs = durationMs; }
        public String getFinishReason() { return finishReason; }
        public void setFinishReason(String finishReason) { this.finishReason = finishReason; }
    }

    /**
     * Message-level data for observability recording.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MessageData {
        private int sequenceNumber;
        private Integer iterationNumber;
        private String role;
        private String content;
        private String toolCallId;
        private String toolName;

        public int getSequenceNumber() { return sequenceNumber; }
        public void setSequenceNumber(int sequenceNumber) { this.sequenceNumber = sequenceNumber; }
        public Integer getIterationNumber() { return iterationNumber; }
        public void setIterationNumber(Integer iterationNumber) { this.iterationNumber = iterationNumber; }
        public String getRole() { return role; }
        public void setRole(String role) { this.role = role; }
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
        public String getToolCallId() { return toolCallId; }
        public void setToolCallId(String toolCallId) { this.toolCallId = toolCallId; }
        public String getToolName() { return toolName; }
        public void setToolName(String toolName) { this.toolName = toolName; }
    }

    /**
     * Tool call data for observability recording.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ToolCallData {
        private int sequenceNumber;
        private int iterationNumber;
        private String toolCallId;
        private String toolName;
        private Integer parallelIndex;
        private Map<String, Object> arguments;
        private boolean success;
        private String result;
        private String errorMessage;
        private long durationMs;
        private int consecutiveRepeatCount;
        private Map<String, Object> metadata;

        public int getSequenceNumber() { return sequenceNumber; }
        public void setSequenceNumber(int sequenceNumber) { this.sequenceNumber = sequenceNumber; }
        public int getIterationNumber() { return iterationNumber; }
        public void setIterationNumber(int iterationNumber) { this.iterationNumber = iterationNumber; }
        public String getToolCallId() { return toolCallId; }
        public void setToolCallId(String toolCallId) { this.toolCallId = toolCallId; }
        public String getToolName() { return toolName; }
        public void setToolName(String toolName) { this.toolName = toolName; }
        public Integer getParallelIndex() { return parallelIndex; }
        public void setParallelIndex(Integer parallelIndex) { this.parallelIndex = parallelIndex; }
        public Map<String, Object> getArguments() { return arguments; }
        public void setArguments(Map<String, Object> arguments) { this.arguments = arguments; }
        public boolean isSuccess() { return success; }
        public void setSuccess(boolean success) { this.success = success; }
        public String getResult() { return result; }
        public void setResult(String result) { this.result = result; }
        public String getErrorMessage() { return errorMessage; }
        public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
        public long getDurationMs() { return durationMs; }
        public void setDurationMs(long durationMs) { this.durationMs = durationMs; }
        public int getConsecutiveRepeatCount() { return consecutiveRepeatCount; }
        public void setConsecutiveRepeatCount(int consecutiveRepeatCount) { this.consecutiveRepeatCount = consecutiveRepeatCount; }
        public Map<String, Object> getMetadata() { return metadata; }
        public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
    }
}
