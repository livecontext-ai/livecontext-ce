package com.apimarketplace.agent.loop;

import com.apimarketplace.agent.domain.*;
import lombok.Getter;
import lombok.Setter;

import java.util.*;

/**
 * Mutable state holder for agent loop execution.
 * Encapsulates all state that changes during loop iterations.
 * Following Single Responsibility Principle - only holds state.
 */
@Getter
@Setter
public class LoopExecutionState {

    // Execution identifiers
    private final String runId;
    private final long startTime;

    // Loop control
    private int iterations;
    private final int maxIterations;
    private final long executionTimeoutMs; // 0 = no timeout
    private AgentState currentState;
    private AgentStopReason stopReason;

    // Conversation
    private final List<Message> messages;
    private final StringBuilder fullContent;

    // Tool execution
    private final List<ToolResult> allToolResults;
    private CompletionResponse lastResponse;

    // Token tracking (aggregate)
    private int totalPromptTokens;
    private int totalCompletionTokens;
    private int totalCacheCreationTokens;
    private int totalCacheReadTokens;
    private int totalCachedTokens;
    private int totalReasoningTokens;

    // Per-iteration tracking
    private final List<UsageInfo> usagePerIteration;
    private final List<String> finishReasonsPerIteration;

    // Metrics
    private final List<Long> iterationDurations;
    private final List<Integer> toolCallsPerIteration;
    private final Map<String, Object> metrics;

    // Tracks where messages from the current execution start (after history + system + user prompt).
    // Everything at or after this index was generated during THIS execution.
    private int executionStartIndex;

    // Loop detection
    private final LoopDetector loopDetector;

    /**
     * Default-thresholds constructor. The {@link LoopDetector} is built with the
     * hard-coded historical defaults (stop at 15 identical / 40 consecutive).
     */
    public LoopExecutionState(String runId, int maxIterations, long executionTimeoutMs) {
        this(runId, maxIterations, executionTimeoutMs, new LoopDetector());
    }

    /**
     * Configurable-detector constructor. Used by {@link AgentLoopService#bootstrapLoop}
     * when the {@link AgentLoopContext} carries per-agent loop threshold overrides.
     *
     * @param loopDetector pre-built detector with the desired thresholds; if {@code null},
     *                     falls back to a default detector
     */
    public LoopExecutionState(String runId, int maxIterations, long executionTimeoutMs,
                              LoopDetector loopDetector) {
        this.runId = runId;
        this.startTime = System.currentTimeMillis();
        this.iterations = 0;
        this.maxIterations = maxIterations;
        this.executionTimeoutMs = executionTimeoutMs;
        this.currentState = AgentState.INITIALIZING;
        this.stopReason = AgentStopReason.COMPLETED;

        this.messages = new ArrayList<>();
        this.fullContent = new StringBuilder();
        this.allToolResults = new ArrayList<>();

        this.iterationDurations = new ArrayList<>();
        this.toolCallsPerIteration = new ArrayList<>();
        this.usagePerIteration = new ArrayList<>();
        this.finishReasonsPerIteration = new ArrayList<>();
        this.metrics = new HashMap<>();

        this.loopDetector = loopDetector != null ? loopDetector : new LoopDetector();
    }

    public void incrementIterations() {
        this.iterations++;
    }

    /**
     * Mark the current message count as the start of execution-generated messages.
     * Call this AFTER initializeMessages() to exclude history/system/user prompt
     * from the returned conversation history.
     */
    public void markExecutionStart() {
        this.executionStartIndex = this.messages.size();
    }

    /**
     * Returns only messages generated during THIS execution (excludes pre-loaded history,
     * system prompt, and user prompt). Matches conversation-service behavior where only
     * the current turn's messages are returned.
     */
    public List<Message> getCurrentExecutionMessages() {
        if (executionStartIndex >= messages.size()) {
            return List.of();
        }
        return new ArrayList<>(messages.subList(executionStartIndex, messages.size()));
    }

    public boolean hasMoreIterations() {
        return iterations < maxIterations && hasTimeRemaining();
    }

    /**
     * Check if execution timeout has been exceeded.
     * Returns true if no timeout is set or if time remains.
     */
    public boolean hasTimeRemaining() {
        if (executionTimeoutMs <= 0) return true;
        return getDuration() < executionTimeoutMs;
    }

    public boolean isTimedOut() {
        return executionTimeoutMs > 0 && getDuration() >= executionTimeoutMs;
    }

    public boolean isLastIteration() {
        return iterations == maxIterations;
    }

    public int getMaxIterations() {
        return maxIterations;
    }

    public void addToolResults(List<ToolResult> results) {
        allToolResults.addAll(results);
    }

    public void recordIterationDuration(long durationMs) {
        iterationDurations.add(durationMs);
    }

    public void recordToolCallCount(int count) {
        toolCallsPerIteration.add(count);
    }

    public void trackUsage(UsageInfo usage) {
        if (usage != null) {
            totalPromptTokens += usage.promptTokens() != null ? usage.promptTokens() : 0;
            totalCompletionTokens += usage.completionTokens() != null ? usage.completionTokens() : 0;
            totalCacheCreationTokens += usage.cacheCreationInputTokens() != null ? usage.cacheCreationInputTokens() : 0;
            totalCacheReadTokens += usage.cacheReadInputTokens() != null ? usage.cacheReadInputTokens() : 0;
            totalCachedTokens += usage.cachedTokens() != null ? usage.cachedTokens() : 0;
            totalReasoningTokens += usage.reasoningTokens() != null ? usage.reasoningTokens() : 0;
            usagePerIteration.add(usage);
        }
    }

    public void recordFinishReason(String reason) {
        finishReasonsPerIteration.add(reason);
    }

    /**
     * Prompt tokens consumed by the most recent completed iteration alone (V162).
     * Returns 0 when no iteration has completed yet (iter 1 about to start).
     * Drives {@code lastDelta * safety_factor} projection in budget guards -
     * captures step-function bursts that running averages miss.
     */
    public long getLastIterationPromptTokens() {
        if (usagePerIteration.isEmpty()) return 0L;
        UsageInfo last = usagePerIteration.get(usagePerIteration.size() - 1);
        return last.promptTokens() != null ? last.promptTokens() : 0L;
    }

    /** Completion tokens of the most recent completed iteration alone (V162). */
    public long getLastIterationCompletionTokens() {
        if (usagePerIteration.isEmpty()) return 0L;
        UsageInfo last = usagePerIteration.get(usagePerIteration.size() - 1);
        return last.completionTokens() != null ? last.completionTokens() : 0L;
    }

    public void appendContent(String content) {
        if (content != null) {
            fullContent.append(content);
        }
    }

    public long getDuration() {
        return System.currentTimeMillis() - startTime;
    }

    public UsageInfo buildUsageInfo() {
        return UsageInfo.builder()
            .promptTokens(totalPromptTokens)
            .completionTokens(totalCompletionTokens)
            .totalTokens(totalPromptTokens + totalCompletionTokens)
            .cacheCreationInputTokens(totalCacheCreationTokens > 0 ? totalCacheCreationTokens : null)
            .cacheReadInputTokens(totalCacheReadTokens > 0 ? totalCacheReadTokens : null)
            .cachedTokens(totalCachedTokens > 0 ? totalCachedTokens : null)
            .reasoningTokens(totalReasoningTokens > 0 ? totalReasoningTokens : null)
            .build();
    }

    public void buildFinalMetrics() {
        metrics.put("totalIterations", iterations);
        metrics.put("totalToolCalls", allToolResults.size());
        metrics.put("successfulToolCalls", allToolResults.stream().filter(ToolResult::success).count());
        metrics.put("failedToolCalls", allToolResults.stream().filter(r -> !r.success()).count());
        metrics.put("avgIterationDurationMs", iterationDurations.stream()
            .mapToLong(Long::longValue).average().orElse(0));
        metrics.put("totalPromptTokens", totalPromptTokens);
        metrics.put("totalCompletionTokens", totalCompletionTokens);
        metrics.put("toolCallsPerIteration", toolCallsPerIteration);
        if (totalCacheCreationTokens > 0) metrics.put("totalCacheCreationTokens", totalCacheCreationTokens);
        if (totalCacheReadTokens > 0) metrics.put("totalCacheReadTokens", totalCacheReadTokens);
        if (totalCachedTokens > 0) metrics.put("totalCachedTokens", totalCachedTokens);
        if (totalReasoningTokens > 0) metrics.put("totalReasoningTokens", totalReasoningTokens);
    }

    public void markLoopDetected(boolean isIdentical, String toolName) {
        metrics.put("loopDetected", true);
        metrics.put("loopType", isIdentical ? "identical" : "consecutive");
        if (toolName != null) {
            metrics.put("loopToolName", toolName);
        }
        metrics.put("totalConsecutiveCalls", loopDetector.getTotalConsecutiveCalls());
        metrics.put("finalResponseGiven", true);
    }
}
