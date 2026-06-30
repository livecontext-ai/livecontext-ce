package com.apimarketplace.orchestrator.execution.v2.services;

import com.apimarketplace.orchestrator.domain.execution.EpochState;
import com.apimarketplace.orchestrator.domain.execution.StateSnapshot;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.cache.ExecutionCacheManager;
import com.apimarketplace.orchestrator.execution.v2.engine.DagCoordinates;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionTree;
import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import com.apimarketplace.orchestrator.execution.v2.state.ExecutionState;
import com.apimarketplace.orchestrator.execution.v2.state.NodeState;
import com.apimarketplace.orchestrator.metrics.WorkflowMetrics;
import com.apimarketplace.orchestrator.services.cache.RunScopedCache;
import com.apimarketplace.orchestrator.services.context.RunContextService;
import com.apimarketplace.orchestrator.services.state.StateSnapshotService;
import com.apimarketplace.orchestrator.trigger.TriggerEpochManager;
import com.apimarketplace.orchestrator.validation.DAGIndependenceValidator;
import org.springframework.beans.factory.annotation.Autowired;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Facade for step-by-step execution context management.
 *
 * Delegates to specialized components:
 * - {@link ExecutionCacheManager} for tree and execution loading from DB
 * - {@link RunContextService} for loading step outputs from DB
 * - {@link TriggerEpochManager} for epoch resolution
 *
 * IMPORTANT: No context caching for step-by-step mode.
 * Each node execution loads fresh data from DB to ensure consistency
 * when the same node is re-executed multiple times.
 *
 * Only trigger items are cached (transient per-run data, loaded at workflow start).
 *
 * Epoch-aware: Uses TriggerEpochManager to resolve current epoch
 * and filters storage queries to only return data from the current epoch.
 * This prevents reusable triggers from seeing stale data from previous epochs.
 * Node execution state is managed by StateSnapshot (already epoch-aware via resetDag()),
 * NOT by marking nodes from step outputs.
 */
@Slf4j
@Component
public class V2StepByStepContextManager implements RunScopedCache {

    // Trigger items cache (transient per-run data)
    // Key: runId or runId:epoch for epoch-scoped isolation
    private final Map<String, List<Map<String, Object>>> triggerItemsCache = new ConcurrentHashMap<>();

    // Global data cache (transient per-run data, e.g., BackEdgeState for loop iterations)
    // Key: runId or runId:epoch -> Map of global data entries
    // This cache preserves data that only exists in-memory between step-by-step calls.
    private final Map<String, Map<String, Object>> globalDataCache = new ConcurrentHashMap<>();

    private final ExecutionCacheManager executionCacheManager;
    private final RunContextService runContextService;
    private final TriggerEpochManager triggerEpochManager;
    private final StateSnapshotService stateSnapshotService;
    private final DAGIndependenceValidator dagIndependenceValidator;
    private final WorkflowMetrics metrics;

    @Autowired
    public V2StepByStepContextManager(
            ExecutionCacheManager executionCacheManager,
            RunContextService runContextService,
            TriggerEpochManager triggerEpochManager,
            StateSnapshotService stateSnapshotService,
            DAGIndependenceValidator dagIndependenceValidator,
            WorkflowMetrics metrics) {
        this.executionCacheManager = executionCacheManager;
        this.runContextService = runContextService;
        this.triggerEpochManager = triggerEpochManager;
        this.stateSnapshotService = stateSnapshotService;
        this.dagIndependenceValidator = dagIndependenceValidator;
        this.metrics = metrics;
    }

    /**
     * Legacy constructor - kept so existing tests that pre-date metric injection
     * keep compiling. Production code uses the {@link Autowired} constructor.
     */
    public V2StepByStepContextManager(
            ExecutionCacheManager executionCacheManager,
            RunContextService runContextService,
            TriggerEpochManager triggerEpochManager,
            StateSnapshotService stateSnapshotService,
            DAGIndependenceValidator dagIndependenceValidator) {
        this(executionCacheManager, runContextService, triggerEpochManager,
            stateSnapshotService, dagIndependenceValidator, null);
    }

    /**
     * Single-source observability for context builds - records the Micrometer
     * timer / distribution and emits one structured log line containing every
     * datum needed to debug an alias-drift or per-item-routing regression.
     *
     * <p>Called from both overloads of {@link #getOrCreateContextWithTriggerData}
     * so future refactors (Piste 1 - overload dedup) stay correct by construction.
     */
    private void recordAndLogContextBuild(ExecutionTree tree, int itemIndex, int epoch, int spawn,
                                          String resolvedTriggerId,
                                          Map<String, Object> stepOutputs,
                                          Map<String, Object> triggerData,
                                          ExecutionState state,
                                          Map<String, Object> cachedGlobalData,
                                          long startNs) {
        long durationNs = System.nanoTime() - startNs;
        // Memoized lookup - plan.getSplitLoops() walks all cores+edges; we cache the
        // boolean per planId in StepOutputsWriter. See HAS_SPLIT_CACHE.
        boolean hasSplit = com.apimarketplace.orchestrator.services.context.StepOutputsWriter
            .planHasSplit(tree.plan());
        String mode = tree.isStepByStepMode() ? "sbs" : "auto";

        if (metrics != null) {
            metrics.recordContextBuild(mode, hasSplit, durationNs, stepOutputs.size());
        }

        // Structured single-line log - every field a debugger needs in one ctx record.
        // Format chosen so Loki / grep / ELK can extract via "[ctx] " prefix + key=value pairs.
        if (log.isDebugEnabled() || hasSplit) {
            log.info("[ctx] runId={} mode={} epoch={} spawn={} item_index={} trigger_id={} "
                    + "step_outputs={} trigger_data_keys={} state_node_count={} "
                    + "global_data_keys={} has_split={} duration_ms={}",
                tree.runId(), mode, epoch, spawn, itemIndex,
                resolvedTriggerId,
                stepOutputs.size(), triggerData.keySet().size(), state.nodeStates().size(),
                cachedGlobalData.keySet().size(), hasSplit,
                durationNs / 1_000_000.0);
        }
    }

    // ==================== Tree Operations (delegate) ====================

    public ExecutionTree getTree(String runId) {
        return executionCacheManager.getTree(runId);
    }

    public boolean hasTree(String runId) {
        return executionCacheManager.hasTree(runId);
    }

    // ==================== Execution Operations (delegate) ====================

    public WorkflowExecution getExecution(String runId) {
        return executionCacheManager.getExecution(runId);
    }

    // ==================== Trigger Items (in-memory cache) ====================

    public boolean hasTriggerItems(String runId) {
        return triggerItemsCache.containsKey(runId);
    }

    public void cacheTriggerItems(String runId, List<Map<String, Object>> items) {
        triggerItemsCache.put(runId, new ArrayList<>(items));
        log.debug("[ContextManager] Cached {} trigger items: runId={}", items.size(), runId);
    }

    public List<Map<String, Object>> getTriggerItems(String runId) {
        return triggerItemsCache.get(runId);
    }

    /**
     * Cache trigger items for a specific epoch (epoch-scoped isolation).
     */
    public void cacheTriggerItems(String runId, int epoch, List<Map<String, Object>> items) {
        String key = runId + ":" + epoch;
        triggerItemsCache.put(key, new ArrayList<>(items));
        // Also store under runId for backward compat
        triggerItemsCache.put(runId, new ArrayList<>(items));
        log.debug("[ContextManager] Cached {} trigger items: runId={}, epoch={}", items.size(), runId, epoch);
    }

    /**
     * Get trigger items for a specific epoch.
     * Falls back to run-level cache if epoch-specific cache is not found.
     */
    public List<Map<String, Object>> getTriggerItems(String runId, int epoch) {
        List<Map<String, Object>> items = triggerItemsCache.get(runId + ":" + epoch);
        return items != null ? items : triggerItemsCache.get(runId);
    }

    // ==================== Context Creation ====================

    /**
     * Create execution context with trigger data for the specified item.
     *
     * ALWAYS loads fresh data from DB via RunContextService.
     * No caching to ensure consistency when re-executing nodes.
     *
     * Epoch-aware: resolves current epoch via TriggerEpochManager and only
     * loads storage entries from the current epoch. For multi-DAG workflows,
     * resolves the per-DAG epoch using DAGIndependenceValidator to find the
     * owner trigger, ensuring each DAG loads only its own epoch's data.
     *
     * @param contextKey Unique key for this context
     * @param tree The execution tree
     * @param itemId The item ID
     * @param itemIndex The item index
     * @param nodeId The node being executed (used for per-DAG epoch resolution), null for global
     */
    public ExecutionContext getOrCreateContextWithTriggerData(
            String contextKey, ExecutionTree tree, String itemId, int itemIndex, String nodeId) {

        long startNs = System.nanoTime();

        // Get trigger data for this item
        Map<String, Object> triggerData = getTriggerDataForItem(tree.runId(), itemIndex);

        // Resolve epoch + spawn: per-DAG for multi-DAG workflows, global otherwise
        DagCoordinates coords = resolveCoordinatesForNode(tree, nodeId);
        int epoch = coords.epoch();
        int spawn = coords.spawn();

        // Always load step outputs from DB via RunContextService (epoch+spawn+itemIndex filtered).
        // Passing itemIndex routes to per-item-aware load: per-item match wins, with shared fallback
        // for step_keys that have no per-item storage. Without this, alias collisions caused
        // every item to see item 0's data (Daily Email Digest bug, 2026-05-07).
        Map<String, Object> stepOutputs = loadStepOutputsFromDb(tree.runId(), tree.tenantId(), epoch, spawn, itemIndex);

        // Normalize step output keys: storage may use wrong prefixes (e.g., "mcp:start"
        // for a trigger node). Ensure outputs are also available under correct normalized
        // keys (e.g., "trigger:start") so ReadyNodeCalculator can find them.
        // Companion alias is written automatically by the StepOutputsWriter contract.
        com.apimarketplace.orchestrator.services.context.StepOutputsWriter.normalizeWrongPrefixes(
            stepOutputs,
            com.apimarketplace.orchestrator.services.context.StepOutputsWriter.aliasMapping(tree.plan()),
            metrics != null ? metrics::recordAliasCollision : null);

        // Resolve DAG trigger ID for this node (needed for epoch-scoped state reconstruction)
        String resolvedTriggerId = nodeId != null ? resolveDagTriggerIdForNode(tree, nodeId) : null;

        // Reconstruct ExecutionState from StateSnapshot so that canExecute()
        // can see which predecessors have already completed in previous steps.
        // Use epoch-scoped reconstruction when available to prevent cross-epoch contamination
        // (e.g., a node completed in epoch 1 should NOT appear as completed in epoch 2).
        ExecutionState state;
        if (resolvedTriggerId != null && epoch > 0) {
            state = reconstructStateFromEpoch(tree.runId(), resolvedTriggerId, epoch);
        } else {
            state = reconstructStateFromSnapshot(tree.runId());
        }

        // Merge cached globalData (e.g., BackEdgeState for loop iterations) into the state.
        // This preserves in-memory state across step-by-step calls.
        Map<String, Object> cachedGlobalData = getCachedGlobalData(tree.runId());
        if (!cachedGlobalData.isEmpty()) {
            for (Map.Entry<String, Object> entry : cachedGlobalData.entrySet()) {
                state = state.withGlobalData(entry.getKey(), entry.getValue());
            }
            log.debug("[ContextManager] Merged {} cached globalData entries into context", cachedGlobalData.size());
        }

        // Apply loop core output overrides from BackEdgeHandler.
        // During loop iteration, BackEdgeHandler stores updated loop core outputs
        // in globalData so that downstream nodes (e.g., decision conditions using
        // {{core:loop.iteration}}) see the correct iteration number instead of the
        // stale value loaded from DB.
        applyLoopCoreOutputOverrides(stepOutputs, cachedGlobalData);

        // Inject epoch and dagTriggerId into globalData for backward compat
        // (SignalContextResolver still reads from globalData as fallback)
        state = state.withGlobalData("epoch", epoch);
        if (resolvedTriggerId != null) {
            state = state.withGlobalData("dagTriggerId", resolvedTriggerId);
        }

        // Create context with fresh data and explicit DAG coordinates.
        // PR15 - thread the workspace identity from the tree (which carries it
        // from WorkflowRunEntity.organization_id) so SBS execution preserves
        // org context across context rebuilds. Without this, every SBS step
        // would silently demote to personal scope.
        ExecutionContext context = new ExecutionContext(
            tree.runId(),
            tree.workflowRunId(),
            tree.tenantId(),
            itemId,
            itemIndex,
            resolvedTriggerId,
            epoch,
            spawn,
            new HashMap<>(triggerData),
            stepOutputs,
            state,
            tree.plan(),
            tree.organizationId(),
            tree.organizationRole()
        );

        log.debug("[ContextManager] Created context from DB: runId={}, epoch={}, triggerKeys={}, stepOutputKeys={}, stateNodeCount={}, globalDataKeys={}",
            tree.runId(), epoch, triggerData.keySet(), stepOutputs.keySet(), state.nodeStates().size(), cachedGlobalData.keySet());

        recordAndLogContextBuild(tree, itemIndex, epoch, spawn, resolvedTriggerId,
            stepOutputs, triggerData, state, cachedGlobalData, startNs);

        return context;
    }

    /**
     * Create execution context with trigger data, using an explicit epoch.
     *
     * Used for parallel epoch execution where the epoch is known from the caller.
     * Delegates to the full version with explicitTriggerId=null.
     */
    public ExecutionContext getOrCreateContextWithTriggerData(
            String contextKey, ExecutionTree tree, String itemId, int itemIndex, String nodeId, int explicitEpoch) {
        return getOrCreateContextWithTriggerData(contextKey, tree, itemId, itemIndex, nodeId, explicitEpoch, null);
    }

    /**
     * Create execution context with trigger data, using an explicit epoch and triggerId.
     *
     * Used for parallel epoch execution where both epoch and triggerId are known
     * from the caller. This avoids guessing which trigger fired when multiple
     * triggers share the same DAG and execute in parallel.
     *
     * @param contextKey Unique key for this context
     * @param tree The execution tree
     * @param itemId The item ID
     * @param itemIndex The item index
     * @param nodeId The node being executed
     * @param explicitEpoch The explicit epoch for this execution
     * @param explicitTriggerId The trigger that initiated this execution, or null to auto-resolve
     */
    public ExecutionContext getOrCreateContextWithTriggerData(
            String contextKey, ExecutionTree tree, String itemId, int itemIndex,
            String nodeId, int explicitEpoch, String explicitTriggerId) {

        long startNs = System.nanoTime();

        // Get trigger data for this item (epoch-scoped fallback)
        List<Map<String, Object>> items = getTriggerItems(tree.runId(), explicitEpoch);
        Map<String, Object> triggerData;
        if (items != null && !items.isEmpty() && itemIndex >= 0 && itemIndex < items.size()) {
            triggerData = new HashMap<>(items.get(itemIndex));
        } else {
            triggerData = getTriggerDataForItem(tree.runId(), itemIndex);
        }

        // Use explicit epoch for coordinate resolution
        DagCoordinates coords = resolveCoordinatesForNode(tree, nodeId, explicitEpoch);
        int epoch = coords.epoch();
        int spawn = coords.spawn();

        // Load step outputs from DB (epoch+spawn+itemIndex filtered). See per-item rationale above.
        Map<String, Object> stepOutputs = loadStepOutputsFromDb(tree.runId(), tree.tenantId(), epoch, spawn, itemIndex);

        // Normalize step output keys (wrong-prefix correction). Companion alias write is
        // handled by the StepOutputsWriter contract.
        com.apimarketplace.orchestrator.services.context.StepOutputsWriter.normalizeWrongPrefixes(
            stepOutputs,
            com.apimarketplace.orchestrator.services.context.StepOutputsWriter.aliasMapping(tree.plan()),
            metrics != null ? metrics::recordAliasCollision : null);

        // Resolve trigger ID: use explicit when provided (parallel-safe), else auto-resolve
        String resolvedTriggerId;
        if (explicitTriggerId != null) {
            resolvedTriggerId = explicitTriggerId;
            log.debug("[ContextManager] Using explicit triggerId={} for nodeId={}", explicitTriggerId, nodeId);
        } else {
            resolvedTriggerId = nodeId != null ? resolveDagTriggerIdForNode(tree, nodeId) : null;
        }

        // Reconstruct state from epoch-scoped snapshot (NOT flat view)
        // This prevents cross-epoch contamination where nodes completed/skipped in
        // epoch 1 would incorrectly appear as completed for epoch 2.
        ExecutionState state;
        if (resolvedTriggerId != null && epoch > 0) {
            state = reconstructStateFromEpoch(tree.runId(), resolvedTriggerId, epoch);
        } else {
            state = reconstructStateFromSnapshot(tree.runId());
        }

        // Merge epoch-scoped cached globalData
        Map<String, Object> cachedGlobalData = getCachedGlobalData(tree.runId(), explicitEpoch);
        if (!cachedGlobalData.isEmpty()) {
            for (Map.Entry<String, Object> entry : cachedGlobalData.entrySet()) {
                state = state.withGlobalData(entry.getKey(), entry.getValue());
            }
        }

        // Apply loop core output overrides (same as non-epoch overload)
        applyLoopCoreOutputOverrides(stepOutputs, cachedGlobalData);

        state = state.withGlobalData("epoch", epoch);
        if (resolvedTriggerId != null) {
            state = state.withGlobalData("dagTriggerId", resolvedTriggerId);
        }

        // PR15 - thread workspace identity from the tree (epoch-aware overload).
        ExecutionContext context = new ExecutionContext(
            tree.runId(), tree.workflowRunId(), tree.tenantId(),
            itemId, itemIndex, resolvedTriggerId, epoch, spawn,
            new HashMap<>(triggerData), stepOutputs, state, tree.plan(),
            tree.organizationId(), tree.organizationRole()
        );

        log.debug("[ContextManager] Created context with explicit epoch: runId={}, epoch={}, triggerId={}, triggerKeys={}, stepOutputKeys={}",
            tree.runId(), epoch, resolvedTriggerId, triggerData.keySet(), stepOutputs.keySet());

        recordAndLogContextBuild(tree, itemIndex, epoch, spawn, resolvedTriggerId,
            stepOutputs, triggerData, state, cachedGlobalData, startNs);

        return context;
    }

    /**
     * Apply loop core output overrides from BackEdgeHandler globalData.
     * During loop iteration, the in-memory loop core output has the current iteration
     * but the DB still has the initial value (iteration=0). This method applies the
     * cached override so downstream nodes see the correct iteration.
     *
     * <p>Writes the override under BOTH the full nodeId key (e.g., {@code core:my_loop})
     * and the bare alias (e.g., {@code my_loop}) so that CodeNode's
     * {@code $input.<alias>.iteration} sees the live counter. Without the bare-alias
     * companion write, the alias entry - populated earlier from the DB load with
     * {@code iteration=0} - would shadow the live counter for code-style access.
     */
    @SuppressWarnings("unchecked")
    void applyLoopCoreOutputOverrides(Map<String, Object> stepOutputs, Map<String, Object> cachedGlobalData) {  // package-private for tests
        Object raw = cachedGlobalData.get(
            com.apimarketplace.orchestrator.execution.v2.engine.BackEdgeHandler.LOOP_CORE_OUTPUT_OVERRIDES_KEY);
        if (raw instanceof Map<?, ?> overrides && !overrides.isEmpty()) {
            for (Map.Entry<?, ?> entry : overrides.entrySet()) {
                if (entry.getKey() instanceof String key && entry.getValue() instanceof Map<?, ?> value) {
                    // writeWithAlias guarantees both full-key and bare-alias are written
                    // atomically - see StepOutputsWriter javadoc for the bug class this prevents.
                    com.apimarketplace.orchestrator.services.context.StepOutputsWriter.writeWithAlias(
                        stepOutputs, key, value);
                }
            }
            log.debug("[ContextManager] Applied {} loop core output overrides", overrides.size());
        }
    }

    /**
     * Get trigger data for a specific item index.
     *
     * <p>When {@code itemIndex} is out of range for the trigger items list but the
     * list is non-empty, we fall back to the first trigger item instead of returning
     * an empty map. Rationale: inside a split body, {@link ExecutionContext#withItemIndex}
     * clobbers {@code itemId/itemIndex} with the split sub-item index (see
     * SplitAwareNodeExecutor.enrichContextWithItem), so when an async agent completion
     * rebuilds its context off the sub-item id (e.g., itemId="3" for sub-item 3),
     * {@code parseItemIndex} hands back 3 and we look up trigger item 3 - which
     * doesn't exist if the trigger only ever emitted one item. Trigger data is
     * per-firing; all split sub-items in the same epoch share the parent's trigger
     * data, so items.get(0) is the correct default when the trigger had a single
     * item. Multi-trigger-item + split workflows would need explicit parent tracking
     * (not done here - track separately if we ever hit that scenario).
     */
    public Map<String, Object> getTriggerDataForItem(String runId, int itemIndex) {
        List<Map<String, Object>> items = getTriggerItems(runId);

        if (items == null || items.isEmpty()) {
            log.debug("[ContextManager] No trigger items for runId={}", runId);
            return new HashMap<>();
        }

        if (itemIndex < 0) {
            log.debug("[ContextManager] Negative item index {} is invalid, returning empty", itemIndex);
            return new HashMap<>();
        }

        if (itemIndex < items.size()) {
            return new HashMap<>(items.get(itemIndex));
        }

        // Out-of-range positive fallback: most likely a split sub-item context whose
        // itemIndex was overwritten by withItemIndex. Log at debug (not warn) because
        // this is expected on the async split path and noisy WARN lines obscured real
        // issues. Only safe for single-item triggers - multi-item triggers + split
        // would need explicit parent tracking to pick the right index.
        if (items.size() == 1) {
            log.debug("[ContextManager] Item index {} out of trigger-items range (size={}), falling back to single trigger item (split sub-item path)",
                itemIndex, items.size());
            return new HashMap<>(items.get(0));
        }

        // Multi-trigger-item + out-of-range is genuinely ambiguous - keep the warning
        // and return empty so the caller can detect the degraded state.
        log.warn("[ContextManager] Item index out of bounds for multi-item trigger: itemIndex={}, size={} - parent tracking not implemented for this scenario",
            itemIndex, items.size());
        return new HashMap<>();
    }

    // ==================== Global Data Cache ====================

    /**
     * Update the global data cache for a run.
     * Called after each node execution to preserve in-memory state like BackEdgeState
     * across step-by-step calls.
     */
    public void updateGlobalData(String runId, Map<String, Object> globalData) {
        if (globalData != null && !globalData.isEmpty()) {
            globalDataCache.computeIfAbsent(runId, k -> new ConcurrentHashMap<>()).putAll(globalData);
            log.debug("[ContextManager] Updated globalData cache: runId={}, keys={}", runId, globalData.keySet());
        }
    }

    /**
     * Get the cached global data for a run.
     */
    public Map<String, Object> getCachedGlobalData(String runId) {
        return globalDataCache.getOrDefault(runId, Map.of());
    }

    /**
     * Update the global data cache for a specific epoch (epoch-scoped isolation).
     */
    public void updateGlobalData(String runId, int epoch, Map<String, Object> globalData) {
        if (globalData != null && !globalData.isEmpty()) {
            String key = runId + ":" + epoch;
            globalDataCache.computeIfAbsent(key, k -> new ConcurrentHashMap<>()).putAll(globalData);
            // Also update run-level cache for backward compat
            globalDataCache.computeIfAbsent(runId, k -> new ConcurrentHashMap<>()).putAll(globalData);
            log.debug("[ContextManager] Updated globalData cache: runId={}, epoch={}, keys={}", runId, epoch, globalData.keySet());
        }
    }

    /**
     * Get the cached global data for a specific epoch.
     * Falls back to run-level cache if epoch-specific cache is not found.
     */
    public Map<String, Object> getCachedGlobalData(String runId, int epoch) {
        Map<String, Object> epochData = globalDataCache.get(runId + ":" + epoch);
        return epochData != null ? epochData : globalDataCache.getOrDefault(runId, Map.of());
    }

    // ==================== Cleanup ====================

    /**
     * Clean up all state for a workflow run.
     */
    public void cleanup(String runId) {
        log.info("[ContextManager] Cleaning up: runId={}", runId);

        // Clean up run-level and epoch-scoped entries
        String prefix = runId + ":";
        triggerItemsCache.remove(runId);
        triggerItemsCache.keySet().removeIf(k -> k.startsWith(prefix));
        globalDataCache.remove(runId);
        globalDataCache.keySet().removeIf(k -> k.startsWith(prefix));
    }

    /**
     * Clean up cache entries for a specific epoch within a run.
     */
    public void cleanupEpoch(String runId, int epoch) {
        String key = runId + ":" + epoch;
        triggerItemsCache.remove(key);
        globalDataCache.remove(key);
        log.debug("[ContextManager] Cleaned up epoch cache: runId={}, epoch={}", runId, epoch);
    }

    // ==================== Private Helpers ====================

    /**
     * Resolve the correct DAG coordinates for a node execution with an explicit epoch.
     *
     * Used for parallel epoch execution where the epoch is known from the caller
     * rather than being resolved from TriggerEpochManager metadata.
     *
     * @param tree The execution tree
     * @param nodeId The node being executed
     * @param explicitEpoch The explicit epoch to use
     * @return The DagCoordinates with the explicit epoch
     */
    public DagCoordinates resolveCoordinatesForNode(ExecutionTree tree, String nodeId, int explicitEpoch) {
        WorkflowPlan plan = tree.plan();

        if (nodeId == null || plan == null) {
            return DagCoordinates.of(null, explicitEpoch, 0);
        }

        String ownerTriggerId = resolveDagTriggerIdForNode(tree, nodeId);
        int dagSpawn = ownerTriggerId != null
                ? triggerEpochManager.getCurrentSpawnForDag(tree.runId(), ownerTriggerId)
                : 0;

        log.debug("[ContextManager] Resolved coordinates with explicit epoch: nodeId={}, ownerTrigger={}, epoch={}, spawn={}",
                nodeId, ownerTriggerId, explicitEpoch, dagSpawn);
        return DagCoordinates.of(ownerTriggerId, explicitEpoch, dagSpawn);
    }

    /**
     * Resolve the correct DAG coordinates for a node execution.
     *
     * For multi-DAG workflows (multiple triggers), each trigger has its own epoch counter
     * and spawn counter. This method finds the owner trigger for the given node and returns
     * that trigger's per-DAG coordinates.
     *
     * Falls back to global epoch with spawn=0 when:
     * - nodeId is null (e.g., getReadyNodes which calculates globally)
     * - plan has only one trigger (no ambiguity)
     * - DAGIndependenceValidator is not available
     * - Node has no owner trigger (orphan or shared)
     *
     * @param tree The execution tree
     * @param nodeId The node being executed, or null for global resolution
     * @return The DagCoordinates to use for loading step outputs
     */
    public DagCoordinates resolveCoordinatesForNode(ExecutionTree tree, String nodeId) {
        WorkflowPlan plan = tree.plan();

        // If no nodeId or no plan, fall back to global epoch, spawn=0
        if (nodeId == null || plan == null) {
            return DagCoordinates.of(null, triggerEpochManager.getCurrentEpoch(tree.runId()), 0);
        }

        // Resolve owner trigger
        String ownerTriggerId = resolveDagTriggerIdForNode(tree, nodeId);

        if (ownerTriggerId != null) {
            int dagGlobalEpoch = triggerEpochManager.getGlobalEpochForDag(tree.runId(), ownerTriggerId);
            int dagSpawn = triggerEpochManager.getCurrentSpawnForDag(tree.runId(), ownerTriggerId);
            log.debug("[ContextManager] Resolved per-DAG coordinates: nodeId={}, ownerTrigger={}, epoch={}, spawn={}",
                nodeId, ownerTriggerId, dagGlobalEpoch, dagSpawn);
            return DagCoordinates.of(ownerTriggerId, dagGlobalEpoch, dagSpawn);
        }

        // Fallback to global epoch, spawn=0
        log.debug("[ContextManager] Could not resolve owner trigger for nodeId={}, using global epoch", nodeId);
        return DagCoordinates.of(null, triggerEpochManager.getCurrentEpoch(tree.runId()), 0);
    }

    /**
     * Reconstruct ExecutionState from StateSnapshot (sole source of truth).
     * Step outputs from Storage provide DATA for SpEL expressions but do NOT
     * determine execution state - after a rerun, stale outputs may still exist
     * in Storage for nodes that were reset by StateSnapshot.resetDag().
     */
    private ExecutionState reconstructStateFromSnapshot(String runId) {
        Map<String, NodeState> nodeStates = new ConcurrentHashMap<>();
        Instant now = Instant.now();

        // StateSnapshot is the SOLE source of truth for execution state.
        // Do NOT derive state from step outputs - they may be stale after reruns.
        try {
            StateSnapshot snapshot = stateSnapshotService.getSnapshot(runId);
            for (String nodeId : snapshot.getCompletedNodeIds()) {
                nodeStates.put(nodeId, new NodeState(nodeId, NodeStatus.COMPLETED, now, now, Map.of(), Optional.empty()));
            }
            for (String nodeId : snapshot.getFailedNodeIds()) {
                nodeStates.put(nodeId, new NodeState(nodeId, NodeStatus.FAILED, now, now, Map.of(), Optional.empty()));
            }
            for (String nodeId : snapshot.getSkippedNodeIds()) {
                nodeStates.put(nodeId, new NodeState(nodeId, NodeStatus.SKIPPED, now, now, Map.of(), Optional.empty()));
            }
        } catch (Exception e) {
            log.warn("[ContextManager] Could not load StateSnapshot for runId={}: {}", runId, e.getMessage());
        }

        log.info("[ContextManager] Reconstructed ExecutionState: runId={}, completedNodes={}",
            runId, nodeStates.keySet());

        return new ExecutionState(nodeStates, new ConcurrentHashMap<>());
    }

    /**
     * Reconstruct ExecutionState from a specific epoch's EpochState only.
     * This provides epoch isolation: nodes completed/skipped in other epochs
     * do NOT appear in this state, preventing cross-epoch contamination.
     *
     * <p>For example, if epoch 1 rejected a user approval (skipping table:create_row_test),
     * epoch 2's context should NOT see table:create_row_test as SKIPPED - it should be PENDING.
     */
    private ExecutionState reconstructStateFromEpoch(String runId, String triggerId, int epoch) {
        Map<String, NodeState> nodeStates = new ConcurrentHashMap<>();
        Instant now = Instant.now();

        try {
            StateSnapshot snapshot = stateSnapshotService.getSnapshot(runId);
            EpochState es = snapshot.getEpochState(triggerId, epoch);

            for (String nodeId : es.getCompletedNodeIds()) {
                nodeStates.put(nodeId, new NodeState(nodeId, NodeStatus.COMPLETED, now, now, Map.of(), Optional.empty()));
            }
            for (String nodeId : es.getFailedNodeIds()) {
                nodeStates.put(nodeId, new NodeState(nodeId, NodeStatus.FAILED, now, now, Map.of(), Optional.empty()));
            }
            for (String nodeId : es.getSkippedNodeIds()) {
                nodeStates.put(nodeId, new NodeState(nodeId, NodeStatus.SKIPPED, now, now, Map.of(), Optional.empty()));
            }
        } catch (Exception e) {
            log.warn("[ContextManager] Could not load EpochState for runId={}, triggerId={}, epoch={}: {}",
                runId, triggerId, epoch, e.getMessage());
            // Fall back to flat view
            return reconstructStateFromSnapshot(runId);
        }

        log.info("[ContextManager] Reconstructed epoch-scoped ExecutionState: runId={}, triggerId={}, epoch={}, nodes={}",
            runId, triggerId, epoch, nodeStates.keySet());

        return new ExecutionState(nodeStates, new ConcurrentHashMap<>());
    }

    /**
     * Delegates to {@link com.apimarketplace.orchestrator.services.context.StepOutputsWriter#aliasMapping}.
     *
     * <p>Kept package-private for legacy callers/tests that referenced this method directly;
     * the canonical computation (with LRU memoization by planId) now lives in
     * {@code StepOutputsWriter} so future writers see the same mapping for free.
     */
    Map<String, String> buildAliasMapping(WorkflowPlan plan) {
        return com.apimarketplace.orchestrator.services.context.StepOutputsWriter.aliasMapping(plan);
    }

    /**
     * Resolve dagTriggerId for a node (used to inject into context globalData).
     * Mirrors the logic in resolveEpochForNode but returns the trigger ID itself.
     *
     * <p>For multi-trigger DAG nodes (shared across triggers in the same dagGroup),
     * finds which trigger's DagState has an active epoch to determine the correct
     * execution context.
     */
    private String resolveDagTriggerIdForNode(ExecutionTree tree, String nodeId) {
        WorkflowPlan plan = tree.plan();
        if (plan == null) return null;

        // Single trigger: return it directly
        if (plan.getTriggers() != null && plan.getTriggers().size() == 1) {
            return plan.getTriggers().get(0).getNormalizedKey();
        }

        // If nodeId is a trigger itself, use it
        if (nodeId.startsWith("trigger:")) {
            return nodeId;
        }

        // Multi-DAG: find owner trigger
        if (dagIndependenceValidator != null) {
            // First try single-owner resolution (works for independent DAGs)
            Optional<String> owner = dagIndependenceValidator.findOwnerTrigger(plan, nodeId);
            if (owner.isPresent()) {
                // Check if this is a multi-trigger node - if so, find the active trigger
                if (dagIndependenceValidator.isMultiTriggerNode(plan, nodeId)) {
                    String activeTrigger = findActiveTriggerForSharedNode(tree.runId(),
                        dagIndependenceValidator.findAllOwnerTriggers(plan, nodeId));
                    if (activeTrigger != null) {
                        return activeTrigger;
                    }
                }
                return owner.get();
            }
        }

        return null;
    }

    /**
     * For multi-trigger DAG nodes, find which trigger's DagState has an active epoch.
     * This determines which trigger "fired" and thus which epoch context to use.
     *
     * <p>Selection strategy (ordered by reliability):
     * <ol>
     *   <li>If only ONE trigger has active epochs → return it (unambiguous)</li>
     *   <li>If multiple have active epochs → prefer the most recently started (highest epoch with active flag)</li>
     * </ol>
     *
     * <p>NOTE: When the caller has an explicit triggerId (e.g., from ReusableTriggerService),
     * it should use the explicitTriggerId parameter in getOrCreateContextWithTriggerData()
     * instead of relying on this heuristic. This method is the fallback for legacy callers.
     *
     * @param runId The workflow run ID
     * @param candidateTriggers List of trigger IDs that own the shared node
     * @return The trigger ID with an active epoch, or null if none found
     */
    private String findActiveTriggerForSharedNode(String runId, List<String> candidateTriggers) {
        if (candidateTriggers == null || candidateTriggers.isEmpty() || stateSnapshotService == null) {
            return null;
        }

        try {
            var snapshot = stateSnapshotService.getSnapshot(runId);
            if (snapshot == null) return null;

            // Collect all triggers with active epochs
            List<String> activeTriggersWithEpochs = new ArrayList<>();
            Map<String, Integer> triggerEpochs = new HashMap<>();

            for (String triggerId : candidateTriggers) {
                var dagState = snapshot.getDagState(triggerId);
                if (dagState != null && dagState.hasActiveEpochs()) {
                    activeTriggersWithEpochs.add(triggerId);
                    triggerEpochs.put(triggerId, dagState.getCurrentEpoch());
                }
            }

            if (activeTriggersWithEpochs.isEmpty()) {
                log.debug("[ContextManager] No triggers with active epochs among {}", candidateTriggers);
                return null;
            }

            // Unambiguous: only one trigger has active epochs
            if (activeTriggersWithEpochs.size() == 1) {
                String result = activeTriggersWithEpochs.get(0);
                log.debug("[ContextManager] Multi-trigger DAG: single active trigger {} (epoch={}) for shared node",
                    result, triggerEpochs.get(result));
                return result;
            }

            // Multiple active triggers (parallel execution): pick the one with the highest epoch.
            // This is a heuristic - callers should use explicitTriggerId when available.
            String bestTrigger = null;
            int highestEpoch = -1;
            for (String triggerId : activeTriggersWithEpochs) {
                int epoch = triggerEpochs.get(triggerId);
                if (epoch > highestEpoch) {
                    highestEpoch = epoch;
                    bestTrigger = triggerId;
                }
            }

            log.warn("[ContextManager] Multi-trigger DAG: {} triggers have active epochs {}. " +
                    "Picked {} (epoch={}). Callers should pass explicitTriggerId for parallel safety.",
                activeTriggersWithEpochs.size(), triggerEpochs, bestTrigger, highestEpoch);
            return bestTrigger;
        } catch (Exception e) {
            log.warn("[ContextManager] Could not resolve active trigger for shared node: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Load step outputs from DB via RunContextService, filtered by epoch, spawn, and itemIndex.
     *
     * <p>When {@code itemIndex} is provided, routes to {@link RunContextService#loadRunContextForItem}
     * which applies per-item precedence (per-item match wins, shared fallback for non-matching
     * step_keys). This is required for split per-item executions where each item must see
     * its own predecessor outputs, not item 0's.
     *
     * <p>When {@code itemIndex} is null (e.g., non-per-item callers), routes to
     * {@link RunContextService#loadRunContext} which loads all storages without per-item filtering.
     */
    private Map<String, Object> loadStepOutputsFromDb(String runId, String tenantId, int epoch, int spawn, Integer itemIndex) {
        if (tenantId == null) {
            log.warn("[ContextManager] Cannot load step outputs: tenantId is null for runId={}", runId);
            return new HashMap<>();
        }

        Map<String, Object> dbContext;
        if (itemIndex != null) {
            dbContext = runContextService.loadRunContextForItem(runId, tenantId, epoch, spawn, itemIndex);
        } else {
            dbContext = runContextService.loadRunContext(runId, tenantId, epoch, spawn);
        }

        if (!dbContext.isEmpty()) {
            log.debug("[ContextManager] Loaded {} step outputs from DB for runId={}, epoch={}, spawn={}, itemIndex={}",
                dbContext.size(), runId, epoch, spawn, itemIndex);
            return new HashMap<>(dbContext);
        }

        log.debug("[ContextManager] No step outputs for runId={}, epoch={}, spawn={}, itemIndex={} (expected for new epoch)",
            runId, epoch, spawn, itemIndex);
        return new HashMap<>();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // RunScopedCache Implementation
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    public void cleanupRun(String runId) {
        cleanup(runId);
    }

    @Override
    public String getCacheName() {
        return "V2StepByStepContextCache";
    }

    @Override
    public CacheDomain getDomain() {
        return CacheDomain.EXECUTION;
    }

    @Override
    public int getCacheSize() {
        return triggerItemsCache.size();
    }
}
