package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import com.apimarketplace.orchestrator.execution.v2.constants.ExecutionMetadataKeys;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.state.SplitState;
import com.apimarketplace.orchestrator.execution.v2.template.V2TemplateAdapter;
import com.apimarketplace.orchestrator.services.TemplateEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.apimarketplace.orchestrator.execution.v2.engine.OutputUnwrapper;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Split node - fans a collection out into N PARALLEL execution contexts (one per item).
 *
 * <p>IMPORTANT - who produces the output: under the current engine a top-level split is executed
 * by {@link com.apimarketplace.orchestrator.execution.v2.split.SplitNodeExecutor} (dispatched from
 * {@code UnifiedExecutionEngine.executeSplitNodeSimplified} because {@link #isSplitNode()} is true);
 * nested splits go through {@code SplitAwareNodeExecutor}. That executor is the SOURCE OF TRUTH for
 * the persisted output - it emits {@code items, item_count, split_id, spawn_reason, terminated}
 * (see {@code SplitNodeExecutor.createSuccessResult} and SplitOutputSchemaMapper). {@code SplitNode}
 * itself is used for TRAVERSAL/wiring ({@link #getNextNodes}, {@link #getBodyNodes}, port targets);
 * {@link #execute} below is NOT on the live output path.
 *
 * <p>Per-branch item data ({@code current_item}, {@code current_index}, and the {@code item}/
 * {@code index} shorthands) is injected at RUNTIME into each branch's context by
 * {@code SplitAwareNodeExecutor}; it is NOT part of the split's own persisted output. See the
 * SplitNodeSpec output schema (current_item/current_index are marked {@code runtimeOnly}).
 *
 * <p>NOTE: the {@code splitState != null} branch of {@link #execute} is a legacy SEQUENTIAL
 * iteration path. It is unreachable under the current parallel engine ({@code getSplitState}
 * always returns null - nothing writes {@code "split_state:{nodeId}"} into globalData) and is
 * kept only for historical reference. {@link #getNextNodes} and {@link #onComplete}, however,
 * ARE live: the parallel executor calls them to drive per-item body traversal.
 */
public class SplitNode extends BaseNode {

    private static final Logger logger = LoggerFactory.getLogger(SplitNode.class);

    private final String list;
    private final int maxItems;
    private final String splitStrategy;  // "stop-on-error" or "continue-anyway"
    private final List<ExecutionNode> bodyNodes;  // Split body subtree
    private final TemplateEngine templateEngine;

    // Key prefix for storing split state in context
    private static final String SPLIT_STATE_KEY_PREFIX = "split_state:";

    public SplitNode(
            String nodeId,
            String list,
            int maxItems,
            String splitStrategy,
            List<ExecutionNode> bodyNodes,
            TemplateEngine templateEngine) {
        super(nodeId, NodeType.SPLIT);
        this.list = list;
        this.maxItems = maxItems > 0 ? maxItems : 100;  // Default 100
        this.splitStrategy = splitStrategy != null ? splitStrategy : "continue-anyway";
        this.bodyNodes = bodyNodes;
        this.templateEngine = templateEngine;
    }

    @Override
    public NodeExecutionResult execute(ExecutionContext context) {
        // Get or initialize split state for this parent item
        SplitState splitState = getSplitState(context);

        if (splitState == null) {
            // First execution - evaluate list expression
            logger.debug("Split evaluating list: nodeId={}, expression={}", nodeId, list);
            EvaluationResult evalResult = evaluateListExpression(context);
            List<Object> items = evalResult.items();
            logger.debug("Split evaluation returned {} items", items != null ? items.size() : 0);

            // Check if evaluation failed (null means error, empty list means valid empty result)
            if (items == null) {
                String reason = evalResult.diagnostic() != null
                    ? evalResult.diagnostic()
                    : "Failed to evaluate list: " + list;
                logger.error("Split list evaluation failed: nodeId={}, expression={}, reason={}",
                    nodeId, list, reason);
                Map<String, Object> errorOutput = new HashMap<>();
                errorOutput.put("error", reason);
                errorOutput.put("node_type", "SPLIT");
                errorOutput.put("split_id", nodeId);
                errorOutput.put("resolved_params", buildInputSnapshot(null));
                return new NodeExecutionResult(nodeId, NodeStatus.FAILED, errorOutput,
                    java.util.Optional.of(reason), Map.of(), 0);
            }

            if (items.isEmpty()) {
                logger.info("Split list evaluated to empty list: nodeId={}", nodeId);
                return createExitResult(0, items.size(), "empty_list");
            }

            // Limit items if maxItems is set
            if (items.size() > maxItems) {
                logger.debug("Split limiting items from {} to {}", items.size(), maxItems);
                items = items.subList(0, maxItems);
            }

            splitState = SplitState.create(items, maxItems, splitStrategy);
            logger.info("Split initialized: nodeId={}, itemCount={}, maxItems={}, strategy={}, mode=PARALLEL",
                nodeId, items.size(), maxItems, splitStrategy);

            // Build resolved_params snapshot for inspector visibility
            Map<String, Object> resolvedParams = new LinkedHashMap<>();
            resolvedParams.put("list", resolveTemplateString(list, context));
            resolvedParams.put("itemCount", items.size());
            resolvedParams.put("maxItems", maxItems);
            resolvedParams.put("splitStrategy", splitStrategy);

            // Return result with items to spawn (parallel mode)
            Map<String, Object> output = new HashMap<>();
            output.put("resolved_params", resolvedParams);
            output.put(ExecutionMetadataKeys.NODE_TYPE, "SPLIT");
            output.put("split_id", nodeId);
            output.put(ExecutionMetadataKeys.ITEM_COUNT, items.size());
            output.put("max_items", maxItems);
            output.put("split_strategy", splitStrategy);
            output.put("spawn_parallel_items", true);
            output.put(ExecutionMetadataKeys.TERMINATED, false);  // Signal that split is active, not terminated

            // Store items in metadata for engine to spawn child items
            Map<String, Object> metadata = new java.util.HashMap<>();
            metadata.put(ExecutionMetadataKeys.SPLIT_STATE, splitState);
            metadata.put(ExecutionMetadataKeys.SPLIT_STATE_KEY, ExecutionMetadataKeys.splitStateKey(nodeId));
            metadata.put(ExecutionMetadataKeys.SPAWN_ITEMS, items);  // Signal to engine: create N child items
            metadata.put("spawn_mode", "parallel");

            logger.info("Split spawning {} parallel items", items.size());

            return new NodeExecutionResult(nodeId, NodeStatus.COMPLETED, output,
                java.util.Optional.empty(), metadata, 0);
        }

        // ── LEGACY SEQUENTIAL PATH (unreachable under the parallel engine) ──────────────
        // getSplitState() only ever READS "split_state:{nodeId}" from globalData and nothing
        // writes it, so splitState is always null above and control never reaches here. This
        // per-item iteration block (and its `current_item`/`continue` output) predates the
        // parallel-spawn model; kept for historical reference only. Per-item context in the
        // live flow is injected by SplitAwareNodeExecutor, not emitted here.
        int currentIndex = splitState.currentIndex();
        List<Object> items = splitState.items();

        logger.debug("Split executing: nodeId={}, index={}/{}, itemId={}",
            nodeId, currentIndex, items.size(), context.itemId());

        // Check if we've processed all items
        if (currentIndex >= items.size()) {
            logger.info("Split completed: nodeId={}, totalItems={}", nodeId, items.size());
            return createExitResult(currentIndex, items.size(), "all_items_processed");
        }

        // Split continues - prepare for this item
        Object currentItem = items.get(currentIndex);
        logger.debug("Split continuing: nodeId={}, index={}, currentItem={}",
            nodeId, currentIndex, currentItem);

        Map<String, Object> output = new HashMap<>();
        output.put("resolved_params", buildInputSnapshot(items));
        output.put(ExecutionMetadataKeys.NODE_TYPE, "SPLIT");
        output.put("split_id", nodeId);
        output.put(ExecutionMetadataKeys.CURRENT_INDEX, currentIndex);
        output.put(ExecutionMetadataKeys.ITEM_COUNT, items.size());
        output.put("max_items", maxItems);
        output.put("split_strategy", splitStrategy);
        output.put(ExecutionMetadataKeys.TERMINATED, false);
        output.put(ExecutionMetadataKeys.CONTINUE, true);
        output.put(ExecutionMetadataKeys.CURRENT_ITEM, currentItem);
        output.put(ExecutionMetadataKeys.ITEM_INDEX, context.itemIndex());

        // Add metadata for state persistence
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(ExecutionMetadataKeys.SPLIT_STATE, splitState);
        metadata.put(ExecutionMetadataKeys.SPLIT_STATE_KEY, ExecutionMetadataKeys.splitStateKey(nodeId));

        // The output signals to the engine that body nodes should be executed
        return new NodeExecutionResult(nodeId, NodeStatus.COMPLETED, output,
            java.util.Optional.empty(), metadata, 0);
    }

    @Override
    public List<ExecutionNode> getNextNodes(NodeExecutionResult result) {
        // Check if split terminated
        if (result.output() == null || result.output().isEmpty()) {
            logger.info("Split result has no output, assuming terminated: nodeId={}", nodeId);
            return successors;
        }

        boolean terminated = !result.output().containsKey(ExecutionMetadataKeys.TERMINATED)
            || ExecutionMetadataKeys.isTerminated(result.output());
        if (terminated) {
            logger.debug("Split exiting to successors: nodeId={}, successors={}",
                nodeId, successors.size());
            return successors;
        }

        // Continue split: return FIRST body node only
        if (bodyNodes != null && !bodyNodes.isEmpty()) {
            logger.debug("Split returning first body node: nodeId={}, firstBodyNode={}",
                nodeId, bodyNodes.get(0).getNodeId());
            return List.of(bodyNodes.get(0));
        }

        // No body nodes - exit
        logger.warn("Split has no body nodes, exiting: nodeId={}", nodeId);
        return successors;
    }

    @Override
    public void onComplete(ExecutionContext context, NodeExecutionResult result) {
        // Update split state after execution
        if (result.isSuccess() && result.output().containsKey("continue")) {
            // Increment index for next iteration
            SplitState state = getSplitState(context);
            if (state != null) {
                SplitState newState = state.incrementIndex();
                // State will be updated by the engine
                logger.debug("Split state incremented: nodeId={}, newIndex={}",
                    nodeId, newState.currentIndex());
            }
        }
    }

    /**
     * Evaluate the list to get the list of items.
     *
     * <p>Returns {@link EvaluationResult#failure(String)} on hard error (missing step, blank
     * expression, recognized-wrapper-key but inner non-array, or a Map with no known
     * array-bearing key - see {@link OutputUnwrapper#ARRAY_BEARING_KEYS}). The diagnostic
     * carries the actual observed shape so the agent can self-correct via the inspector or
     * the run's failure output.
     *
     * <p>Returns {@link EvaluationResult#success(List)} on success - including an empty list
     * for an array that legitimately resolved to zero items (distinct from "no array found").
     *
     * <p>Pre-fix behavior: a non-array resolved value (e.g. an Apify {@code {items:[...],
     * status:..., runId:...}} Map) was silently wrapped as a 1-element list containing the
     * whole Map. Downstream {@code current_item.X} accesses on the wrapper returned undefined
     * and the workflow ran to completion with empty payloads - the prod 2026-05-14 Instagram
     * Profile Scraper silent-failure shape. Now: such inputs auto-unwrap if a recognized
     * array-bearing key is present, otherwise fail loud with the observed Map keys listed
     * and a "Did you mean" hint pointing at the conventional fix.
     */
    private EvaluationResult evaluateListExpression(ExecutionContext context) {
        if (list == null || list.isBlank()) {
            logger.warn("Split list is null or blank: nodeId={}", nodeId);
            return EvaluationResult.failure("Split `list` expression is null or blank.");
        }

        Object result;
        try {
            logger.debug("Split evaluating expression: nodeId={}, expression={}", nodeId, list);
            logger.debug("Split context has {} step outputs", context.getAllStepOutputs().size());

            V2TemplateAdapter adapter = new V2TemplateAdapter(templateEngine);
            result = adapter.evaluateTemplate(list, context);

            logger.debug("Split evaluation result type: {}",
                result != null ? result.getClass().getSimpleName() : "null");
        } catch (Exception e) {
            logger.error("Split list evaluation threw: nodeId={}, expression={}, error={}",
                nodeId, list, e.getMessage(), e);
            return EvaluationResult.failure(
                "Split `list` expression `" + list + "` threw during evaluation: " + e.getMessage());
        }

        // null result: missing step output or unresolved template - distinct from "empty list".
        if (result == null) {
            return EvaluationResult.failure(
                "Split `list` expression `" + list + "` resolved to null. "
                    + "Check the upstream node has completed and the reference path matches its output schema.");
        }

        // Happy path: List/Collection/array → use as-is via tryUnwrapToList's pass-through.
        // Map case: tryUnwrapToList probes ARRAY_BEARING_KEYS (items, records, results, …, data).
        // If empty Optional → either a Map with no recognized key, or a Map whose recognized
        // key holds a non-array value, or a primitive - all are hard failures here (single-Map
        // wrap is exactly the silent bug we are closing).
        Optional<List<Object>> extracted = OutputUnwrapper.tryUnwrapToList(result);
        if (extracted.isPresent()) {
            return EvaluationResult.success(extracted.get());
        }

        return EvaluationResult.failure(buildShapeDiagnostic(result));
    }

    /**
     * Builds the loud-fail message when {@code list} resolves to something Split can't iterate.
     * The message lists the observed Map keys (or the actual type for non-Maps) and suggests
     * the canonical fix - gives the agent a self-correction path without log access.
     */
    private String buildShapeDiagnostic(Object result) {
        return OutputUnwrapper.describeNonListShape(result, list);
    }

    /**
     * Internal carrier for the list-evaluation outcome: items on success, diagnostic on failure.
     * Replaces the prior {@code null}-or-list duality so callers can surface the actual reason
     * to the failure output instead of a generic "Failed to evaluate list" string.
     */
    private record EvaluationResult(List<Object> items, String diagnostic) {
        static EvaluationResult success(List<Object> items) {
            return new EvaluationResult(items, null);
        }
        static EvaluationResult failure(String diagnostic) {
            return new EvaluationResult(null, diagnostic);
        }
    }

    /**
     * Create exit result when split terminates.
     */
    private NodeExecutionResult createExitResult(int finalIndex, int totalItems, String exitReason) {
        Map<String, Object> output = new HashMap<>();
        output.put("resolved_params", buildInputSnapshot(null));
        output.put(ExecutionMetadataKeys.NODE_TYPE, "SPLIT");
        output.put("split_id", nodeId);
        output.put(ExecutionMetadataKeys.CURRENT_INDEX, finalIndex);
        output.put(ExecutionMetadataKeys.ITEM_COUNT, totalItems);
        output.put("max_items", maxItems);
        output.put("split_strategy", splitStrategy);
        output.put(ExecutionMetadataKeys.TERMINATED, true);
        output.put(ExecutionMetadataKeys.EXIT_REASON, exitReason);

        return NodeExecutionResult.success(nodeId, output);
    }

    /** Builds a snapshot of the split's resolved configuration for inspector visibility. */
    private Map<String, Object> buildInputSnapshot(List<Object> items) {
        Map<String, Object> resolvedParams = new LinkedHashMap<>();
        resolvedParams.put("list_expression", list);
        resolvedParams.put("maxItems", maxItems);
        resolvedParams.put("splitStrategy", splitStrategy);
        if (items != null) {
            resolvedParams.put("itemCount", items.size());
        }
        return resolvedParams;
    }

    /**
     * Get split state from context.
     */
    private SplitState getSplitState(ExecutionContext context) {
        String stateKey = SPLIT_STATE_KEY_PREFIX + nodeId;
        return (SplitState) context.getGlobalData(stateKey).orElse(null);
    }

    /**
     * Get body nodes for split iteration.
     * Used by EdgeStatusEmitter and skip propagation.
     */
    @Override
    public List<ExecutionNode> getBodyNodes() {
        return bodyNodes != null ? bodyNodes : List.of();
    }

    /**
     * Returns all child nodes for tree traversal.
     * For SplitNode, this includes all body nodes.
     */
    @Override
    public List<ExecutionNode> getAllChildNodes() {
        return getBodyNodes();
    }

    /**
     * SplitNode is a split node.
     */
    @Override
    public boolean isSplitNode() {
        return true;
    }

    /**
     * SplitNode skips split handling - it manages its own split context.
     */
    @Override
    public boolean skipsSplitHandling() {
        return true;
    }

    /**
     * Returns the list expression for this split node.
     */
    @Override
    public String getListExpression() {
        return list;
    }

    /**
     * Returns the maximum items for this split node.
     */
    @Override
    public int getSplitMaxItems() {
        return maxItems;
    }

    public int getMaxItems() {
        return maxItems;
    }

    public String getSplitStrategy() {
        return splitStrategy;
    }
}
