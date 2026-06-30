package com.apimarketplace.orchestrator.services.merge;

import com.apimarketplace.orchestrator.domain.workflow.StepExecutionResult;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.services.cache.RunScopedCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service that integrates merge operations with the workflow execution flow.
 *
 * <p>This service bridges the gap between step completions and the merge collector.
 * It tracks which merge points each node contributes to and automatically records
 * completions when steps finish.
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Initialize merge points based on workflow structure</li>
 *   <li>Track node-to-merge-point mappings</li>
 *   <li>Record completions to the appropriate merge collectors</li>
 *   <li>Notify when merge points complete</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>
 * // At workflow start - analyze and register merge points
 * mergeIntegration.initializeForWorkflow(runId, workflowPlan);
 *
 * // When Split evaluates its list
 * mergeIntegration.registerSplitCount(runId, "core:process", "0", 3);
 *
 * // When steps complete
 * MergeResult result = mergeIntegration.recordCompletion(runId, itemId, itemIndex, nodeId, data, status);
 * if (result != null && result.isReady()) {
 *     // Proceed with merged data
 * }
 * </pre>
 */
@Service
public class MergeIntegrationService implements RunScopedCache {

    private static final Logger log = LoggerFactory.getLogger(MergeIntegrationService.class);

    private final ItemMergeCollector collector;

    /**
     * Maps nodeId -> Set of mergePointIds that this node contributes to.
     * Key: runId:nodeId
     */
    private final ConcurrentHashMap<String, Set<String>> nodeToMergePoints = new ConcurrentHashMap<>();

    /**
     * Maps mergePointId -> Set of sourceNodeIds.
     * Key: runId:mergePointId
     */
    private final ConcurrentHashMap<String, Set<String>> mergePointSources = new ConcurrentHashMap<>();

    /**
     * Tracks which merge points have been initialized for each run.
     * Key: runId:mergePointId:scope
     */
    private final ConcurrentHashMap<String, Boolean> initializedMergePoints = new ConcurrentHashMap<>();

    public MergeIntegrationService(ItemMergeCollector collector) {
        this.collector = collector;
    }

    /**
     * Initializes merge tracking for a workflow run.
     *
     * <p>Analyzes the workflow plan to identify all merge points and their
     * source nodes, then registers them with the collector.
     *
     * @param runId The workflow run ID
     * @param plan The workflow plan
     */
    public void initializeForWorkflow(String runId, WorkflowPlan plan) {
        log.info("Initializing merge tracking for run: {}", runId);

        // Find all merge points and their sources from the plan
        Map<String, Set<String>> mergePointToSources = analyzeMergePoints(plan);

        for (Map.Entry<String, Set<String>> entry : mergePointToSources.entrySet()) {
            String mergePointId = entry.getKey();
            Set<String> sources = entry.getValue();

            // Store the mapping
            String mergeKey = runId + ":" + mergePointId;
            mergePointSources.put(mergeKey, sources);

            // Register reverse mapping (node -> merge points)
            for (String sourceNodeId : sources) {
                String nodeKey = runId + ":" + sourceNodeId;
                nodeToMergePoints
                    .computeIfAbsent(nodeKey, k -> ConcurrentHashMap.newKeySet())
                    .add(mergePointId);
            }

            log.debug("Registered merge point {} with sources: {}", mergePointId, sources);
        }
    }

    /**
     * Analyzes the workflow plan to find merge points and their sources.
     */
    private Map<String, Set<String>> analyzeMergePoints(WorkflowPlan plan) {
        Map<String, Set<String>> result = new HashMap<>();

        // Look through core nodes for merge points
        if (plan.getCores() != null) {
            for (var coreNode : plan.getCores()) {
                // Check if this is a merge node (using record accessor)
                String type = coreNode.type();
                if ("merge".equals(type)) {
                    // Merge sources are determined from edges, not from metadata
                    // No-op here - edge-based detection below handles merge points
                }
            }
        }

        // Also check edges for implicit merge points (multiple edges to same target)
        Map<String, Set<String>> incomingEdges = new HashMap<>();
        for (var edge : plan.getEdges()) {
            String to = edge.to();
            String from = edge.from();
            if (to != null && from != null) {
                incomingEdges.computeIfAbsent(to, k -> new HashSet<>()).add(from);
            }
        }

        // Nodes with multiple incoming edges are implicit merge points
        for (Map.Entry<String, Set<String>> entry : incomingEdges.entrySet()) {
            if (entry.getValue().size() > 1) {
                String implicitMergeId = "core:" + entry.getKey();
                if (!result.containsKey(implicitMergeId)) {
                    result.put(implicitMergeId, entry.getValue());
                    log.debug("Detected implicit merge point: {} with {} sources",
                        implicitMergeId, entry.getValue().size());
                }
            }
        }

        return result;
    }

    /**
     * Initializes a merge point for a specific scope.
     *
     * <p>Must be called before items can be recorded. This is typically done
     * when a Split node evaluates its list or when a fork happens.
     *
     * @param runId The workflow run ID
     * @param mergePointId The merge point ID
     * @param scope The parent item scope
     * @return true if initialization succeeded
     */
    public boolean initializeMergePoint(String runId, String mergePointId, String scope) {
        String initKey = runId + ":" + mergePointId + ":" + scope;

        if (initializedMergePoints.putIfAbsent(initKey, true) != null) {
            log.debug("Merge point already initialized: {}", initKey);
            return true;
        }

        String mergeKey = runId + ":" + mergePointId;
        Set<String> sources = mergePointSources.get(mergeKey);

        if (sources == null || sources.isEmpty()) {
            log.warn("No sources found for merge point: {}", mergePointId);
            return false;
        }

        collector.initializeMergePoint(runId, mergePointId, scope, sources);
        log.info("Initialized merge point {} for scope {}", mergePointId, scope);
        return true;
    }

    /**
     * Registers the expected count for a Split source.
     *
     * <p>Called when a Split node evaluates its list and knows how many
     * items it will spawn.
     *
     * @param runId The workflow run ID
     * @param splitNodeId The Split node ID
     * @param scope The parent item scope
     * @param itemCount The number of items to spawn
     */
    public void registerSplitCount(String runId, String splitNodeId, String scope, int itemCount) {
        String nodeKey = runId + ":" + splitNodeId;
        Set<String> mergePoints = nodeToMergePoints.get(nodeKey);

        if (mergePoints == null || mergePoints.isEmpty()) {
            log.debug("No merge points for Split node: {}", splitNodeId);
            return;
        }

        for (String mergePointId : mergePoints) {
            // Ensure merge point is initialized for this scope
            initializeMergePoint(runId, mergePointId, scope);

            // Set the expected count
            collector.setExpectedCount(runId, mergePointId, scope, splitNodeId, itemCount);
            log.debug("Registered Split count: merge={}, split={}, scope={}, count={}",
                mergePointId, splitNodeId, scope, itemCount);
        }
    }

    /**
     * Records a step completion and checks if any merge points are ready.
     *
     * @param runId The workflow run ID
     * @param itemId The item ID (e.g., "0", "0.1")
     * @param itemIndex The numeric item index
     * @param nodeId The completed node ID
     * @param output The step output data
     * @param result The execution result
     * @return MergeResult if this completion triggers a merge, null otherwise
     */
    public MergeResult recordCompletion(
            String runId,
            String itemId,
            int itemIndex,
            String nodeId,
            Map<String, Object> output,
            StepExecutionResult result) {

        String nodeKey = runId + ":" + nodeId;
        Set<String> mergePoints = nodeToMergePoints.get(nodeKey);

        if (mergePoints == null || mergePoints.isEmpty()) {
            // This node doesn't contribute to any merge points
            return null;
        }

        String scope = ItemMergeScope.getParentScope(itemId);
        MergeResult mergeResult = null;

        for (String mergePointId : mergePoints) {
            // Ensure merge point is initialized
            String initKey = runId + ":" + mergePointId + ":" + scope;
            if (!initializedMergePoints.containsKey(initKey)) {
                // Auto-initialize with default expected count of 1
                initializeMergePoint(runId, mergePointId, scope);
            }

            // Record the completion based on status
            MergeResult pointResult;
            if (result.isSuccess()) {
                pointResult = collector.recordSuccess(
                    runId, mergePointId, itemId, itemIndex, nodeId, output);
            } else if (result.isFailure()) {
                pointResult = collector.recordFailure(
                    runId, mergePointId, itemId, itemIndex, nodeId,
                    result.error() != null ? result.error().getMessage() : "Unknown error");
            } else if (result.isSkipped()) {
                pointResult = collector.recordSkipped(
                    runId, mergePointId, itemId, itemIndex, nodeId, "Step was skipped");
            } else {
                // Unknown status, treat as success
                pointResult = collector.recordSuccess(
                    runId, mergePointId, itemId, itemIndex, nodeId, output);
            }

            // Track if any merge point completed
            if (pointResult.isReady()) {
                mergeResult = pointResult;
                log.info("Merge point {} completed for scope {}", mergePointId, scope);
            }
        }

        return mergeResult;
    }

    /**
     * Gets the merge state for inspection.
     *
     * @param runId The workflow run ID
     * @param mergePointId The merge point ID
     * @param scope The item scope
     * @return The merge state, or null if not initialized
     */
    public ItemMergeState getMergeState(String runId, String mergePointId, String scope) {
        return collector.getMergeState(runId, mergePointId, scope);
    }

    /**
     * Checks if a merge point is complete.
     *
     * @param runId The workflow run ID
     * @param mergePointId The merge point ID
     * @param scope The item scope
     * @return true if complete
     */
    public boolean isMergeComplete(String runId, String mergePointId, String scope) {
        return collector.isComplete(runId, mergePointId, scope);
    }

    /**
     * Cleans up merge tracking for a completed workflow run.
     *
     * @param runId The workflow run ID
     */
    public void cleanupRun(String runId) {
        // Clean up collector
        collector.cleanupRun(runId);

        // Clean up local mappings
        nodeToMergePoints.keySet().removeIf(key -> key.startsWith(runId + ":"));
        mergePointSources.keySet().removeIf(key -> key.startsWith(runId + ":"));
        initializedMergePoints.keySet().removeIf(key -> key.startsWith(runId + ":"));

        log.debug("Cleaned up merge tracking for run: {}", runId);
    }

    /**
     * Gets all merge points that a node contributes to.
     *
     * @param runId The workflow run ID
     * @param nodeId The node ID
     * @return Set of merge point IDs
     */
    public Set<String> getMergePointsForNode(String runId, String nodeId) {
        String nodeKey = runId + ":" + nodeId;
        return nodeToMergePoints.getOrDefault(nodeKey, Set.of());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // RunScopedCache Implementation
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    public String getCacheName() {
        return "MergeIntegrationCache";
    }

    @Override
    public CacheDomain getDomain() {
        return CacheDomain.CONTROL_FLOW;
    }

    @Override
    public int getCacheSize() {
        return nodeToMergePoints.size() + mergePointSources.size() + initializedMergePoints.size();
    }
}
