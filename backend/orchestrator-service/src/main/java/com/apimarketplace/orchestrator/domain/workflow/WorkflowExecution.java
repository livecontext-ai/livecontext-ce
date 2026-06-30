package com.apimarketplace.orchestrator.domain.workflow;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import com.apimarketplace.orchestrator.utils.WorkflowUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Unified workflow execution context.
 * Coordinates execution state and delegates to specialized managers.
 */
public class WorkflowExecution {

    private static final Logger logger = LoggerFactory.getLogger(WorkflowExecution.class);

    // Core state
    private final String runId;
    private final WorkflowPlan plan;
    private final Map<String, Object> initialInputs;
    private final Instant startTime;
    private volatile java.util.UUID workflowRunId;
    private volatile RunStatus status;
    private volatile Instant endTime;
    private volatile String errorMessage;
    private volatile Exception lastException;
    private volatile int currentLevel;
    private volatile long totalExecutionTime;
    private volatile Integer planVersion;
    private volatile String displayName;

    // Data stores
    private final Map<String, StepExecutionResult> stepResults;
    private final Map<String, Object> stepOutputs;
    private final Set<String> completedSteps;
    private final Set<String> failedSteps;
    private final Set<String> skippedSteps;

    // Item-level tracking
    private final Map<String, Set<Integer>> completedItemSteps;
    private final Map<String, Set<Integer>> skippedItemSteps;
    private final Map<String, Map<Integer, Map<String, Object>>> itemStepOutputs;

    // Decision tracking
    private final Map<String, DecisionEvaluationInfo> decisionEvaluations;
    private final Set<String> processedDecisionItems;

    // Delegated managers
    private final MergeQueueManager mergeQueueManager;
    private final ItemContextStack itemContextStack;
    private final TriggerItemsManager triggerItemsManager;
    private final ExecutionMetricsCollector metricsCollector;
    private final ConsumerTracker consumerTracker;

    public WorkflowExecution(String runId, WorkflowPlan plan, Map<String, Object> initialInputs) {
        this.runId = runId;
        this.plan = plan;
        this.initialInputs = initialInputs != null ? new HashMap<>(initialInputs) : new HashMap<>();
        this.startTime = Instant.now();

        // Initialize state
        this.status = RunStatus.PENDING;
        this.currentLevel = 0;
        this.totalExecutionTime = 0;

        // Initialize thread-safe data stores
        this.stepResults = new ConcurrentHashMap<>();
        this.stepOutputs = new ConcurrentHashMap<>();
        this.completedSteps = ConcurrentHashMap.newKeySet();
        this.failedSteps = ConcurrentHashMap.newKeySet();
        this.skippedSteps = ConcurrentHashMap.newKeySet();
        this.completedItemSteps = new ConcurrentHashMap<>();
        this.skippedItemSteps = new ConcurrentHashMap<>();
        this.itemStepOutputs = new ConcurrentHashMap<>();
        this.decisionEvaluations = new ConcurrentHashMap<>();
        this.processedDecisionItems = ConcurrentHashMap.newKeySet();

        // Initialize delegated managers
        this.mergeQueueManager = new MergeQueueManager();
        this.itemContextStack = new ItemContextStack();
        this.triggerItemsManager = new TriggerItemsManager(runId);
        this.metricsCollector = new ExecutionMetricsCollector();
        this.consumerTracker = new ConsumerTracker();

        consumerTracker.initialize(plan);
        consumerTracker.registerFromPlan(plan);
    }

    // ===== CORE GETTERS =====

    public String getRunId() { return runId; }
    public java.util.UUID getWorkflowRunId() { return workflowRunId; }
    public void setWorkflowRunId(java.util.UUID workflowRunId) { this.workflowRunId = workflowRunId; }
    public Integer getPlanVersion() { return planVersion; }
    public void setPlanVersion(Integer planVersion) { this.planVersion = planVersion; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public WorkflowPlan getPlan() { return plan; }
    public Map<String, Object> getInitialInputs() { return new HashMap<>(initialInputs); }
    public Instant getStartTime() { return startTime; }
    public RunStatus getStatus() { return status; }
    public Instant getEndTime() { return endTime; }
    public String getErrorMessage() { return errorMessage; }
    public Exception getLastException() { return lastException; }

    // ===== STATUS MANAGEMENT =====

    public synchronized void setStatus(RunStatus status) {
        this.status = status;
        if (status == RunStatus.COMPLETED || status == RunStatus.FAILED ||
            status == RunStatus.CANCELLED || status == RunStatus.TIMEOUT) {
            this.endTime = Instant.now();
            this.totalExecutionTime = endTime.toEpochMilli() - startTime.toEpochMilli();
        }
    }

    public synchronized void setError(String errorMessage, Exception exception) {
        this.errorMessage = errorMessage;
        this.lastException = exception;
        setStatus(RunStatus.FAILED);
    }

    public synchronized void complete() { setStatus(RunStatus.COMPLETED); }
    public synchronized void cancel() { setStatus(RunStatus.CANCELLED); }

    public boolean isRunning() { return status == RunStatus.RUNNING; }
    public boolean isCompleted() { return status == RunStatus.COMPLETED; }
    public boolean isFailed() { return status == RunStatus.FAILED; }
    public boolean isFinished() {
        return status == RunStatus.COMPLETED || status == RunStatus.FAILED ||
               status == RunStatus.CANCELLED || status == RunStatus.TIMEOUT;
    }

    // ===== TRIGGER ITEMS (delegated) =====

    public void registerTriggerBatch(Trigger trigger, TriggerBatchResult batch) {
        triggerItemsManager.registerBatch(trigger, batch);
    }
    public boolean hasTriggerItems(String triggerKey) { return triggerItemsManager.hasItems(triggerKey); }
    public List<Map<String, Object>> getTriggerItems(String triggerKey) { return triggerItemsManager.getItems(triggerKey); }
    public TriggerItemsManager.TriggerItemsState getTriggerItemsState(String triggerKey) { return triggerItemsManager.getState(triggerKey); }
    public void clearTriggerItems() { triggerItemsManager.clear(); }
    public int getTriggerItemCount(String triggerKey) { return triggerItemsManager.getItemCount(triggerKey); }
    public int getAbsoluteItemIndex(String triggerKey, int localIndex) { return triggerItemsManager.getAbsoluteItemIndex(triggerKey, localIndex); }

    // ===== CHAT/WEBHOOK TRIGGERS (delegated) =====

    public void setChatTriggerInput(String stepId, Map<String, Object> input) { triggerItemsManager.setChatTriggerInput(stepId, input); }
    public Map<String, Object> getChatTriggerInput(String stepId) { return triggerItemsManager.getChatTriggerInput(stepId); }
    public boolean hasChatTriggerInput(String stepId) { return triggerItemsManager.hasChatTriggerInput(stepId); }
    public void clearChatTriggerInput(String stepId) { triggerItemsManager.clearChatTriggerInput(stepId); }

    public void setWebhookTriggerPayload(String stepId, Map<String, Object> payload) { triggerItemsManager.setWebhookTriggerPayload(stepId, payload); }
    public Map<String, Object> getWebhookTriggerPayload(String stepId) { return triggerItemsManager.getWebhookTriggerPayload(stepId); }
    public boolean hasWebhookTriggerPayload(String stepId) { return triggerItemsManager.hasWebhookTriggerPayload(stepId); }
    public void clearWebhookTriggerPayload(String stepId) { triggerItemsManager.clearWebhookTriggerPayload(stepId); }

    // ===== MERGE STATE (delegated) =====

    public MergeQueueManager.MergeQueueState getOrCreateMergeState(String mergeStepId, List<String> branches) {
        return mergeQueueManager.getOrCreate(mergeStepId, branches);
    }
    public MergeQueueManager.MergeQueueState getMergeState(String mergeStepId) { return mergeQueueManager.get(mergeStepId); }
    public Map<String, Object> getMergeStatesSnapshot() { return mergeQueueManager.getSnapshot(); }
    public void restoreMergeStates(Map<String, ?> persistedStates) { mergeQueueManager.restore(persistedStates); }

    // ===== ITEM CONTEXT (delegated) =====

    public Optional<ItemContextStack.ItemContext> getCurrentItemContext() { return itemContextStack.getCurrentItemContext(); }
    public void pushItemContext(String stepId, String triggerKey, int index, Map<String, Object> data) {
        itemContextStack.pushItemContext(stepId, triggerKey, index, data);
    }
    public void pushItemContext(ItemContextStack.ItemContext context) { itemContextStack.pushItemContext(context); }
    public void popItemContext() { itemContextStack.popItemContext(); }

    // ===== METRICS (delegated) =====

    public void recordStepItemMetrics(String stepId, ExecutionMetricsCollector.ItemMetrics metrics) {
        metricsCollector.recordStepItemMetrics(stepId, metrics);
    }
    public ExecutionMetricsCollector.StepMetricsAccumulator getOrCreateStepMetricsAccumulator(String stepId) {
        return metricsCollector.getOrCreateStepMetricsAccumulator(stepId);
    }
    public ExecutionMetricsCollector.ItemMetrics getStepItemMetrics(String stepId) {
        return metricsCollector.getStepItemMetrics(stepId);
    }
    public int getCurrentLevel() { return currentLevel; }
    public void setCurrentLevel(int level) { this.currentLevel = level; }
    public long getTotalExecutionTime() {
        return endTime != null ? totalExecutionTime : Instant.now().toEpochMilli() - startTime.toEpochMilli();
    }
    public Map<String, Long> getStepExecutionTimes() { return metricsCollector.getStepExecutionTimes(); }
    public long getStepExecutionTime(String stepId) { return metricsCollector.getStepExecutionTime(stepId); }

    // ===== STEP RESULTS =====

    public void setStepResult(String stepId, StepExecutionResult result) {
        logger.info("[setStepResult] stepId={}, result.status={}", stepId, result.status());
        stepResults.put(stepId, result);
        if (result.output() != null && !result.output().isEmpty()) {
            stepOutputs.put(stepId, OutputSanitizer.sanitize(result.output()));
        } else {
            stepOutputs.remove(stepId);
        }
        metricsCollector.recordStepExecutionTime(stepId, result.executionTime());

        NodeStatus resultStatus = result.status();
        if (resultStatus == NodeStatus.COMPLETED) {
            completedSteps.add(stepId);
            failedSteps.remove(stepId);
            skippedSteps.remove(stepId);
        } else if (resultStatus == NodeStatus.FAILED) {
            failedSteps.add(stepId);
            completedSteps.remove(stepId);
            skippedSteps.remove(stepId);
        } else if (resultStatus == NodeStatus.SKIPPED) {
            skippedSteps.add(stepId);
            completedSteps.remove(stepId);
            failedSteps.remove(stepId);
        }

        if (shouldAutoTrimOutputs(stepId) && !consumerTracker.hasPendingConsumers(stepId)) {
            trimStepOutput(stepId);
        }
    }

    public StepExecutionResult getStepResult(String stepId) { return stepResults.get(stepId); }
    public boolean hasStepResult(String stepId) { return stepResults.containsKey(stepId); }
    public Map<String, StepExecutionResult> getAllStepResults() { return new HashMap<>(stepResults); }
    public Map<String, Object> getStepOutputs() { return new HashMap<>(stepOutputs); }

    public void clearStepResult(String stepId) {
        if (stepId == null) return;
        logger.info("[clearStepResult] Clearing step: {}", stepId);
        stepResults.remove(stepId);
        stepOutputs.remove(stepId);
        completedSteps.remove(stepId);
        failedSteps.remove(stepId);
        skippedSteps.remove(stepId);
    }

    // ===== DECISION EVALUATIONS =====

    public void storeDecisionEvaluation(String sourceStepId, DecisionEvaluationInfo evaluation) {
        if (sourceStepId != null && evaluation != null) decisionEvaluations.put(sourceStepId, evaluation);
    }
    public DecisionEvaluationInfo getDecisionEvaluation(String sourceStepId) { return decisionEvaluations.get(sourceStepId); }
    public Map<String, DecisionEvaluationInfo> getDecisionEvaluations() { return new HashMap<>(decisionEvaluations); }
    public boolean hasProcessedDecisionForItem(String deduplicationKey) { return processedDecisionItems.contains(deduplicationKey); }
    public void markDecisionProcessedForItem(String deduplicationKey) { processedDecisionItems.add(deduplicationKey); }

    // ===== ITEM-LEVEL STEP TRACKING =====

    public boolean isStepCompletedForItem(String stepId, int itemIndex) {
        if (stepId == null || itemIndex < 0) return false;
        String normalized = WorkflowUtils.normalizeStepId(stepId);
        if (normalized == null) return false;
        Set<Integer> indexes = completedItemSteps.get(normalized);
        return indexes != null && indexes.contains(itemIndex);
    }

    public void markStepCompletedForItem(String stepId, int itemIndex) {
        if (stepId == null || itemIndex < 0) return;
        String normalized = WorkflowUtils.normalizeStepId(stepId);
        if (normalized == null) return;
        completedItemSteps.computeIfAbsent(normalized, key -> ConcurrentHashMap.newKeySet()).add(itemIndex);
    }

    public boolean isStepSkippedForItem(String stepId, int itemIndex) {
        if (stepId == null || itemIndex < 0) return false;
        String normalized = WorkflowUtils.normalizeStepId(stepId);
        if (normalized == null) return false;
        Set<Integer> indexes = skippedItemSteps.get(normalized);
        return indexes != null && indexes.contains(itemIndex);
    }

    public void markStepSkippedForItem(String stepId, int itemIndex) {
        if (stepId == null || itemIndex < 0) return;
        String normalized = WorkflowUtils.normalizeStepId(stepId);
        if (normalized == null) return;
        skippedItemSteps.computeIfAbsent(normalized, key -> ConcurrentHashMap.newKeySet()).add(itemIndex);
    }

    public void storeItemStepOutput(String stepId, int itemIndex, Map<String, Object> output) {
        if (stepId == null || itemIndex < 0) return;
        String normalized = WorkflowUtils.normalizeStepId(stepId);
        if (normalized == null) return;
        Map<Integer, Map<String, Object>> perItemOutputs = itemStepOutputs.computeIfAbsent(normalized, key -> new ConcurrentHashMap<>());
        perItemOutputs.put(itemIndex, output != null ? OutputSanitizer.sanitize(output) : Map.of());
    }

    public Map<String, Object> getStoredItemStepOutput(String stepId, int itemIndex) {
        if (stepId == null || itemIndex < 0) return null;
        String normalized = WorkflowUtils.normalizeStepId(stepId);
        if (normalized == null) return null;
        Map<Integer, Map<String, Object>> perItemOutputs = itemStepOutputs.get(normalized);
        if (perItemOutputs == null) return null;
        Map<String, Object> stored = perItemOutputs.get(itemIndex);
        return stored != null ? new HashMap<>(stored) : null;
    }

    public Map<Integer, Map<String, Object>> getAllItemOutputsForStep(String stepId) {
        if (stepId == null) return Map.of();
        String normalized = WorkflowUtils.normalizeStepId(stepId);
        if (normalized == null) return Map.of();
        Map<Integer, Map<String, Object>> perItemOutputs = itemStepOutputs.get(normalized);
        if (perItemOutputs == null || perItemOutputs.isEmpty()) return Map.of();
        Map<Integer, Map<String, Object>> result = new HashMap<>();
        perItemOutputs.forEach((k, v) -> result.put(k, new HashMap<>(v)));
        return result;
    }

    // ===== STEP STATUS =====

    public Set<String> getCompletedSteps() { return new HashSet<>(completedSteps); }
    public Set<String> getFailedSteps() { return new HashSet<>(failedSteps); }
    public Set<String> getSkippedSteps() { return new HashSet<>(skippedSteps); }
    public boolean isStepCompleted(String stepId) { return stepId != null && completedSteps.contains(stepId); }
    public boolean isStepFailed(String stepId) { return failedSteps.contains(stepId); }
    public boolean isStepSkipped(String stepId) { return skippedSteps.contains(stepId); }

    public NodeStatus getStepStatus(String stepId) {
        if (completedSteps.contains(stepId)) return NodeStatus.COMPLETED;
        if (failedSteps.contains(stepId)) return NodeStatus.FAILED;
        if (skippedSteps.contains(stepId)) return NodeStatus.SKIPPED;
        return NodeStatus.PENDING;
    }

    // ===== CONSUMER TRACKING =====

    public void markDependenciesConsumed(String stepId) { consumerTracker.markDependenciesConsumed(stepId, plan); }
    public void releaseConsumer(String sourceStepId, String consumerStepId) { consumerTracker.releaseConsumer(sourceStepId, consumerStepId); }

    public void releaseStepOutputs(String stepId) {
        if (stepId == null) return;
        stepOutputs.remove(stepId);
        stepResults.computeIfPresent(stepId, (id, existing) -> existing.withoutOutput());
        consumerTracker.removeConsumerTracking(stepId);
    }

    public void registerTemplateDependency(String sourceStepId, String consumerStepId) {
        consumerTracker.registerTemplateDependency(sourceStepId, consumerStepId);
    }

    // ===== OUTPUT MANAGEMENT =====

    public void updateStepOutput(String stepId, Map<String, Object> output) {
        if (stepId == null) return;
        String normalized = WorkflowUtils.normalizeStepId(stepId);
        if (normalized == null) return;
        stepOutputs.put(normalized, OutputSanitizer.sanitize(output));
    }

    public Object getStepOutput(String stepId) {
        if (stepId == null) return null;
        String normalized = WorkflowUtils.normalizeStepId(stepId);
        if (normalized != null) {
            Object scopedOutput = getCurrentItemContext().map(ctx -> ctx.stepOutputs().get(normalized)).orElse(null);
            if (scopedOutput != null) return scopedOutput;
            Object cached = stepOutputs.get(normalized);
            if (cached != null) return cached;
        }
        Object currentScoped = getCurrentItemContext().map(ctx -> ctx.stepOutputs().get(stepId)).orElse(null);
        if (currentScoped != null) return currentScoped;
        return stepOutputs.get(stepId);
    }

    public void attachStepOutputToCurrentItem(String stepId, Map<String, Object> output) {
        if (stepId == null) return;
        getCurrentItemContext().ifPresent(ctx -> {
            Map<String, Object> sanitized = OutputSanitizer.sanitize(output != null ? output : Map.of());
            String normalized = WorkflowUtils.normalizeStepId(stepId);
            if (normalized != null) ctx.stepOutputs().put(normalized, sanitized);
            ctx.stepOutputs().put(stepId, sanitized);
        });
    }

    public void clearCurrentItemStepOutput(String stepId) {
        if (stepId == null) return;
        getCurrentItemContext().ifPresent(ctx -> {
            String normalized = WorkflowUtils.normalizeStepId(stepId);
            if (normalized != null) ctx.stepOutputs().remove(normalized);
            ctx.stepOutputs().remove(stepId);
        });
    }

    // ===== EXECUTION CHECKS =====

    public boolean canExecuteStep(String stepId) {
        Set<String> satisfiedSteps = new HashSet<>(completedSteps);
        if (plan.getMergeNodes().containsKey(stepId)) satisfiedSteps.addAll(skippedSteps);
        return plan.getExecutionGraph().canExecute(stepId, satisfiedSteps);
    }

    public boolean hasFailedDependencies(String stepId) {
        Set<String> dependencies = plan.getExecutionGraph().getDependencies(stepId);
        if (plan.getMergeNodes().containsKey(stepId)) {
            return dependencies.stream().anyMatch(failedSteps::contains);
        }
        return dependencies.stream().anyMatch(dep -> failedSteps.contains(dep) || skippedSteps.contains(dep));
    }

    public List<String> getReadyStepsAtLevel(int level) {
        return plan.getExecutionGraph().getStepsAtLevel(level).stream()
                .filter(stepId -> !completedSteps.contains(stepId) && !failedSteps.contains(stepId) && !skippedSteps.contains(stepId) && canExecuteStep(stepId))
                .toList();
    }

    public List<String> getStepsToSkip() {
        return plan.getAllStepIds().stream()
                .filter(stepId -> !completedSteps.contains(stepId) && !failedSteps.contains(stepId) && !skippedSteps.contains(stepId) && hasFailedDependencies(stepId))
                .toList();
    }

    public ExecutionStatistics getStatistics() {
        int totalSteps = plan.getMcps().size() + plan.getTriggers().size();
        int completedCount = completedSteps.size();
        int failedCount = failedSteps.size();
        int skippedCount = skippedSteps.size();
        int pendingCount = totalSteps - completedCount - failedCount - skippedCount;
        return new ExecutionStatistics(totalSteps, completedCount, failedCount, skippedCount, pendingCount,
                getTotalExecutionTime(), status, currentLevel, plan.getExecutionGraph().getMaxLevel(), new HashMap<>());
    }

    @Override
    public String toString() {
        return String.format("WorkflowExecution{runId='%s', workflowId='%s', status=%s, level=%d, completed=%d, failed=%d}",
                runId, plan.getId(), status, currentLevel, completedSteps.size(), failedSteps.size());
    }

    // ===== PRIVATE HELPERS =====

    private boolean shouldAutoTrimOutputs(String stepId) {
        return true;
    }

    private void trimStepOutput(String stepId) {
        if (stepId == null) return;
        Map<String, Object> snapshot = null;
        Object cached = stepOutputs.get(stepId);
        if (cached instanceof Map<?, ?> map) {
            snapshot = new HashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) snapshot.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        stepOutputs.remove(stepId);
        Map<String, Object> sanitizedSnapshot = snapshot != null ? snapshot : Map.of();
        stepResults.computeIfPresent(stepId, (id, existing) -> existing.withOutputSnapshot(sanitizedSnapshot));
    }

    // Type aliases for backward compatibility - use the new classes directly
    // ItemContext -> ItemContextStack.ItemContext
    // TriggerItemsState -> TriggerItemsManager.TriggerItemsState
    // MergeQueueState -> MergeQueueManager.MergeQueueState
    // MergeQueueEntry -> MergeQueueManager.MergeQueueEntry
    // MergeQueueResult -> MergeQueueManager.MergeQueueResult
    // LoopIterationMetrics -> ExecutionMetricsCollector.LoopIterationMetrics
    // StepMetricsAccumulator -> ExecutionMetricsCollector.StepMetricsAccumulator
    // ItemMetrics -> ExecutionMetricsCollector.ItemMetrics
}
