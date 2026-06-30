package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.nodes.merge.MergeStrategy;
import com.apimarketplace.orchestrator.execution.v2.nodes.merge.Queue1To1Strategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Merge node - Branch synchronization.
 *
 * THIS NODE FIXES THE DUPLICATE EXECUTION BUG!
 *
 * Flow:
 * 1. Wait for required sources based on strategy
 * 2. Delegate to MergeStrategy for actual merge logic
 * 3. Return successors for traversal
 * 4. Each successor processes merged items ONCE (no duplication!)
 *
 * Supported Strategies:
 * - QUEUE_1_TO_1: Wait for ALL sources, skip if any failed (strict)
 * - COMBINE_ALL: Wait for ALL sources, include partial results (lenient)
 * - FIRST_AVAILABLE: Use first successful source (fallback pattern)
 *
 * Key Difference from Old System:
 * - Old: Scheduled each successor as separate "trigger items" → duplicates
 * - New: Successors are just next nodes in tree → traverse once, no duplicates!
 */
public class MergeNode extends BaseNode {

    private static final Logger logger = LoggerFactory.getLogger(MergeNode.class);

    private final List<String> sourceNodeIds;  // Branches to wait for
    private final MergeStrategy strategy;       // Merge strategy

    public MergeNode(String nodeId, List<String> sourceNodeIds, MergeStrategy strategy) {
        super(nodeId, NodeType.MERGE);
        this.sourceNodeIds = sourceNodeIds != null ? sourceNodeIds : List.of();
        this.strategy = strategy != null ? strategy : new Queue1To1Strategy();
    }

    public MergeNode(String nodeId, List<String> sourceNodeIds) {
        this(nodeId, sourceNodeIds, new Queue1To1Strategy());
    }

    /**
     * Multi-trigger shared sink: filter out foreign trigger sources. When several triggers
     * converge on this merge (same auto-detected DAG group), only the current trigger fires
     * in this epoch - the others never complete here by design. Mirrors
     * {@code BaseNode.canExecute} and {@code ReadyNodeCalculator.filterForeignTriggerPredecessors}.
     * Applied to BOTH canMerge and shouldSkip/merge so the strategy sees a consistent view.
     */
    private List<String> effectiveSources(ExecutionContext context) {
        long triggerSourceCount = sourceNodeIds.stream()
            .filter(src -> src != null && src.startsWith("trigger:"))
            .count();
        String currentTriggerId = context.triggerId();
        if (triggerSourceCount > 1 && currentTriggerId != null) {
            return sourceNodeIds.stream()
                .filter(src -> src == null || !src.startsWith("trigger:") || src.equals(currentTriggerId))
                .toList();
        }
        return sourceNodeIds;
    }

    @Override
    public boolean canExecute(ExecutionContext context) {
        List<String> sources = effectiveSources(context);
        boolean canMerge = strategy.canMerge(sources, context);

        if (!canMerge) {
            long completedCount = sources.stream()
                .filter(context::isCompleted)
                .count();
            logger.debug("Merge waiting ({}): nodeId={}, completed={}/{}",
                strategy.name(), nodeId, completedCount, sources.size());
        }

        return canMerge;
    }

    @Override
    public NodeExecutionResult execute(ExecutionContext context) {
        logger.debug("Merge node executing: nodeId={}, strategy={}, sources={}, itemId={}",
            nodeId, strategy.name(), sourceNodeIds.size(), context.itemId());

        // Captured outside the try so failure paths still surface the resolved inputs
        // to the inspector "Resolved parameters" panel.
        Map<String, Object> resolvedParams = new LinkedHashMap<>();
        resolvedParams.put("strategy", strategy.name());
        resolvedParams.put("sources", sourceNodeIds.size());

        try {
            List<String> sources = effectiveSources(context);

            // Check if merge should be skipped based on strategy
            if (strategy.shouldSkip(sources, context)) {
                String reason = strategy.getSkipReason(sources, context);
                logger.info("Merge skipped ({}): nodeId={}, reason={}",
                    strategy.name(), nodeId, reason);
                return NodeExecutionResult.skipped(nodeId, reason);
            }

            // Delegate to strategy for actual merge
            Map<String, Object> mergedData = strategy.merge(sources, context);

            // Populate merged_branches from sources that actually contributed
            Object sourcesObj = mergedData.get("sources");
            if (sourcesObj instanceof Map<?, ?> sourcesMap) {
                mergedData.put("merged_branches", new ArrayList<>(sourcesMap.keySet()));
            } else {
                mergedData.put("merged_branches", new ArrayList<>(sources));
            }

            // Build resolved_params snapshot for inspector visibility
            mergedData.put("resolved_params", resolvedParams);

            // Include item context for proper persistence (like DecisionNode does)
            mergedData.put("node_type", "MERGE");
            mergedData.put("item_index", context.itemIndex());
            mergedData.put("itemIndex", context.itemIndex());
            mergedData.put("item_id", context.itemId());

            logger.debug("✅ Merge completed ({}): nodeId={}, itemId={}, itemCount={}",
                strategy.name(), nodeId, context.itemId(),
                mergedData.getOrDefault("item_count", 0));

            return NodeExecutionResult.success(nodeId, mergedData);

        } catch (Exception e) {
            logger.error("❌ Merge execution failed ({}): nodeId={}, error={}",
                strategy.name(), nodeId, e.getMessage(), e);

            Map<String, Object> failOutput = new HashMap<>();
            failOutput.put("node_type", "MERGE");
            failOutput.put("item_index", context.itemIndex());
            failOutput.put("itemIndex", context.itemIndex());
            failOutput.put("item_id", context.itemId());
            failOutput.put("resolved_params", resolvedParams);
            failOutput.put("error", e.getMessage());
            return NodeExecutionResult.failureWithOutput(nodeId, e.getMessage(), failOutput, 0L);
        }
    }

    @Override
    public List<ExecutionNode> getNextNodes(NodeExecutionResult result) {
        // Simply return successors
        // Each successor will be traversed ONCE for this item
        // NO DUPLICATE EXECUTION!

        logger.debug("Merge returning successors: nodeId={}, successorCount={}",
            nodeId, successors.size());

        return successors;
    }

    @Override
    public void onComplete(ExecutionContext context, NodeExecutionResult result) {
        logger.debug("Merge node completed: nodeId={}, itemId={}",
            nodeId, context.itemId());

        // Mark all incoming edges as completed (for streaming events)
        // Event emission will be added later
    }

    /**
     * MergeNode is a merge node - it synchronizes multiple incoming branches.
     */
    @Override
    public boolean isMergeNode() {
        return true;
    }

    /**
     * MergeNode skips split handling - it manages its own context synchronization.
     */
    @Override
    public boolean skipsSplitHandling() {
        return true;
    }

    public List<String> getSourceNodeIds() {
        return sourceNodeIds;
    }

    public MergeStrategy getStrategy() {
        return strategy;
    }

    public static class Builder {
        private String nodeId;
        private List<String> sourceNodeIds = new ArrayList<>();
        private MergeStrategy strategy;

        public Builder nodeId(String nodeId) {
            this.nodeId = nodeId;
            return this;
        }

        public Builder sourceNodeIds(List<String> sourceNodeIds) {
            this.sourceNodeIds = new ArrayList<>(sourceNodeIds);
            return this;
        }

        public Builder addSource(String sourceNodeId) {
            this.sourceNodeIds.add(sourceNodeId);
            return this;
        }

        public Builder strategy(MergeStrategy strategy) {
            this.strategy = strategy;
            return this;
        }

        public MergeNode build() {
            return new MergeNode(nodeId, sourceNodeIds, strategy);
        }
    }

    public static Builder builder() {
        return new Builder();
    }
}
