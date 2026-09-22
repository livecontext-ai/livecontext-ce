package com.apimarketplace.orchestrator.execution.v2.split;

import com.apimarketplace.orchestrator.execution.v2.constants.ExecutionMetadataKeys;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.OutputUnwrapper;
import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;
import com.apimarketplace.orchestrator.execution.v2.nodes.SplitParamsReport;
import com.apimarketplace.orchestrator.services.template.ResolvedValuePreview;
import com.apimarketplace.orchestrator.execution.v2.template.V2TemplateAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Executes split nodes with simplified logic.
 *
 * <p>Single Responsibility: Evaluate source expression and create SplitContext.
 *
 * <p>The split node becomes COMPLETED immediately after spawning items.
 * It does NOT launch any downstream execution - that is the responsibility
 * of the engine and downstream nodes.
 *
 * <p>Simplified flow:
 * <ol>
 *   <li>Evaluate source expression to get list of items</li>
 *   <li>Create SplitContext with items</li>
 *   <li>Return COMPLETED result</li>
 * </ol>
 *
 * <p>The downstream nodes will:
 * <ol>
 *   <li>Detect the active SplitContext via SplitContextManager</li>
 *   <li>Execute themselves for ALL items in parallel</li>
 *   <li>Store results back in the context</li>
 * </ol>
 */
@Service
public class SplitNodeExecutor {

    private static final Logger logger = LoggerFactory.getLogger(SplitNodeExecutor.class);

    private final SplitContextManager contextManager;
    private final V2TemplateAdapter templateAdapter;

    public SplitNodeExecutor(
            SplitContextManager contextManager,
            V2TemplateAdapter templateAdapter) {
        this.contextManager = contextManager;
        this.templateAdapter = templateAdapter;
    }

    /**
     * Executes a split node.
     *
     * <p>Evaluates the source expression, creates a SplitContext with the items,
     * and returns COMPLETED immediately.
     *
     * <p>For nested splits (split inside another split), the parent split's item index
     * is used as a scope discriminator to ensure each inner split invocation gets a
     * unique context key. This prevents overwrites when the same inner split node
     * creates contexts for different parent items.
     *
     * @param runId the workflow run ID
     * @param nodeId the split node ID
     * @param sourceExpression the SpEL expression to evaluate (e.g., "{{trigger:webhook.messages}}")
     * @param maxItems maximum number of items to process (0 = unlimited)
     * @param workflowItemIndex the workflow item index (from trigger) for context scoping
     * @param context the execution context
     * @return execution result with item count and status
     */
    public NodeExecutionResult execute(
            String runId,
            String nodeId,
            String sourceExpression,
            int maxItems,
            String splitStrategy,
            int workflowItemIndex,
            ExecutionContext context) {

        // Detect parent split scope for nested split support
        String parentScopeKey = SplitContextManager.extractParentScopeKey(context);

        logger.info("[SplitExecutor] Executing split: nodeId={}, expression={}, workflowItem={}, parentScope={}",
            nodeId, sourceExpression, workflowItemIndex, parentScopeKey);

        // 1. Evaluate source expression to get items (auto-unwraps {items|records|...} wrappers,
        //    fails loud on a non-iterable shape - parity with SplitNode.evaluateListExpression so
        //    AUTO and SBS never silently wrap a single Map as a 1-item split).
        ListEvaluation evaluation = evaluateSourceExpression(sourceExpression, context);

        if (evaluation.items() == null) {
            logger.error("[SplitExecutor] Split source did not resolve to an iterable: nodeId={}, expression={}, reason={}",
                nodeId, sourceExpression, evaluation.diagnostic());
            return createErrorResult(nodeId, evaluation.diagnostic(), sourceExpression, maxItems,
                splitStrategy, evaluation.resolvedPreview());
        }
        List<Object> items = evaluation.items();

        // 2. Apply maxItems limit if specified
        if (maxItems > 0 && items.size() > maxItems) {
            logger.info("[SplitExecutor] Limiting items from {} to {}", items.size(), maxItems);
            items = items.subList(0, maxItems);
        }

        // 3. Handle empty list
        if (items.isEmpty()) {
            logger.info("[SplitExecutor] Source expression evaluated to empty list: nodeId={}", nodeId);
            // Still create context (empty), split is COMPLETED
            contextManager.createContext(runId, nodeId, workflowItemIndex, parentScopeKey, items, epochOf(context));
            return createSuccessResult(nodeId, items, "empty_list", sourceExpression, maxItems, splitStrategy,
                evaluation.resolvedPreview());
        }

        // 4. Create SplitContext with items (scoped to workflow item and parent scope)
        SplitContext splitContext = contextManager.createContext(runId, nodeId, workflowItemIndex, parentScopeKey, items, epochOf(context));

        logger.info("[SplitExecutor] Split spawned {} items: nodeId={}, contextKey={}",
            items.size(), nodeId, splitContext.splitNodeId());

        // 5. Return COMPLETED - split job is done
        return createSuccessResult(nodeId, items, "items_spawned", sourceExpression, maxItems, splitStrategy,
            evaluation.resolvedPreview());
    }

    /**
     * Evaluates the source expression to a list of items.
     *
     * <p>Mirrors {@code SplitNode.evaluateListExpression} via the shared {@link OutputUnwrapper}:
     * a List/Collection/array passes through; a wrapper Map auto-unwraps on a recognized
     * {@link OutputUnwrapper#ARRAY_BEARING_KEYS} key; anything else (a Map with no array-bearing
     * key, a primitive, or null) fails loud with a shape diagnostic. This is the LIVE split path
     * for BOTH AUTOMATIC and STEP_BY_STEP modes - it must NOT silently wrap a single Map as a
     * 1-item list (the prod 2026-05-14 Instagram Profile Scraper silent-failure shape, which the
     * dead {@code SplitNode.execute()} already guards but this executor previously re-introduced).
     *
     * @param expression the SpEL expression
     * @param context the execution context
     * @return {@link ListEvaluation} carrying the items on success, or a diagnostic on failure
     */
    private ListEvaluation evaluateSourceExpression(String expression, ExecutionContext context) {
        if (expression == null || expression.isBlank()) {
            logger.warn("[SplitExecutor] Source expression is null or blank");
            return ListEvaluation.failure("Split `list` expression is null or blank.", null);
        }

        if (templateAdapter == null) {
            logger.error("[SplitExecutor] TemplateAdapter is not configured");
            return ListEvaluation.failure("Split template adapter is not configured.", null);
        }

        Object result;
        try {
            result = templateAdapter.evaluateTemplate(expression, context);
        } catch (Exception e) {
            logger.error("[SplitExecutor] Failed to evaluate expression: expression={}, error={}",
                expression, e.getMessage(), e);
            return ListEvaluation.failure(
                "Split `list` expression `" + expression + "` threw during evaluation: " + e.getMessage(),
                null);
        }

        // What the expression resolved to, described once here and carried to every exit
        // path below. It is taken from THIS evaluation - the one that decided how many
        // items the split spawns - never from a second resolution pass.
        String resolvedPreview = ResolvedValuePreview.describe(result);

        // null: missing step output or unresolved template - distinct from a legitimately empty list.
        if (result == null) {
            return ListEvaluation.failure(
                "Split `list` expression `" + expression + "` resolved to null. "
                    + "Check the upstream node has completed and the reference path matches its output schema.",
                resolvedPreview);
        }

        // List/Collection/array → pass through; wrapper Map → unwrap on an array-bearing key.
        Optional<List<Object>> extracted = OutputUnwrapper.tryUnwrapToList(result);
        if (extracted.isPresent()) {
            return ListEvaluation.success(extracted.get(), resolvedPreview);
        }

        // Recognized-key-but-not-array, no recognized key, or a primitive - fail loud, never wrap.
        return ListEvaluation.failure(OutputUnwrapper.describeNonListShape(result, expression), resolvedPreview);
    }

    /**
     * Carrier for the list-evaluation outcome: {@code items} on success, {@code diagnostic} on
     * failure. Mirrors {@code SplitNode.EvaluationResult} so callers surface the real reason to the
     * failure output instead of a generic "Failed to evaluate" string.
     *
     * @param resolvedPreview what the expression resolved to, bounded for display; null when no
     *                        evaluation took place (a blank expression, no adapter wired)
     */
    private record ListEvaluation(List<Object> items, String diagnostic, String resolvedPreview) {
        static ListEvaluation success(List<Object> items, String resolvedPreview) {
            return new ListEvaluation(items, null, resolvedPreview);
        }
        static ListEvaluation failure(String diagnostic, String resolvedPreview) {
            return new ListEvaluation(null, diagnostic, resolvedPreview);
        }
    }

    /**
     * Creates a success result for split execution (PERSISTED layer only).
     *
     * <p>The output map produced here contains fields that are saved to the database
     * via SplitOutputSchemaMapper: items, item_count, split_id, spawn_reason, terminated.
     *
     * <p>NOTE: current_item and current_index are NOT included here. They are runtime
     * context variables injected per parallel branch by SplitAwareNodeExecutor.enrichContextWithItem()
     * and are never persisted to the database.
     *
     * @see SplitAwareNodeExecutor#enrichContextWithItem
     * @see com.apimarketplace.orchestrator.services.persistence.schema.SplitOutputSchemaMapper
     */
    private NodeExecutionResult createSuccessResult(String nodeId, List<Object> items, String reason,
                                                     String sourceExpression, int maxItems,
                                                     String splitStrategy, String listResolved) {
        Map<String, Object> output = new HashMap<>();
        output.put(ExecutionMetadataKeys.NODE_TYPE, "SPLIT");
        output.put("split_id", nodeId);
        output.put(ExecutionMetadataKeys.ITEM_COUNT, items.size());
        output.put("spawn_reason", reason);
        output.put(ExecutionMetadataKeys.TERMINATED, true);  // split is COMPLETED immediately
        output.put("items", items);  // Store items for persistence and inspection

        // Persist resolved configuration as resolved_params for the inspector panel.
        // Without this, the split node shows empty "Resolved parameters" in the run view.
        // Keys and their meanings come from SplitParamsReport, shared with SplitNode so
        // the two producers of a split's parameters describe one node one way. They used
        // to be source_expression / max_items / item_count here, so the same three
        // settings had three different names across the form, this executor and SplitNode.
        //
        // `listResolved` matters most on the path this method is reached with an EMPTY
        // list: "the expression resolved to an empty array" and "it resolved to an object
        // the split could not iterate" are the two things a reader of a split that
        // spawned nothing is trying to tell apart, and neither was reported.
        output.put("resolved_params", SplitParamsReport.build(
            sourceExpression, maxItems, splitStrategy, listResolved, items.size()));

        return new NodeExecutionResult(
            nodeId,
            NodeStatus.COMPLETED,
            output,
            Optional.empty(),
            Map.of(),
            0
        );
    }

    /**
     * Creates an error result for failed split execution.
     *
     * <p>Reports the whole configuration, not just the error. A split that failed is
     * the case where the Params column is opened, and it used to hold one key - the
     * error message the Output column already carries - so the reader learnt nothing
     * from the panel they went to. `listResolved` is the shape that could not be
     * iterated, which is what the error names but does not show.
     */
    private NodeExecutionResult createErrorResult(String nodeId, String errorMessage,
                                                   String sourceExpression, int maxItems,
                                                   String splitStrategy, String listResolved) {
        Map<String, Object> output = new HashMap<>();
        output.put(ExecutionMetadataKeys.NODE_TYPE, "SPLIT");
        output.put("split_id", nodeId);
        output.put("error", errorMessage);
        Map<String, Object> resolvedParams = SplitParamsReport.build(
            sourceExpression, maxItems, splitStrategy, listResolved, null);
        SplitParamsReport.putError(resolvedParams, errorMessage);
        output.put("resolved_params", resolvedParams);

        return new NodeExecutionResult(
            nodeId,
            NodeStatus.FAILED,
            output,
            Optional.of(errorMessage),
            Map.of(),
            0
        );
    }

    /**
     * Executes a split with pre-resolved items (no SpEL evaluation).
     *
     * <p>Same logic as {@link #execute} but skips expression evaluation, for a caller that
     * already holds the items (the original intent was FindNode reading rows from CRUD).
     *
     * <p><b>No production caller today</b> - verified 2026-08-14; FindNode does not spawn (it is
     * a plain collection node). It is kept because it is the natural entry point for a
     * pre-resolved spawn, and it is covered by one test pinning the obligation any future caller
     * inherits: the scope it builds MUST carry the epoch, or a delivery on a pod holding an
     * earlier epoch's scope reuses that one (see {@code SplitContextManager.restoreContext}).
     *
     * @param runId the workflow run ID
     * @param nodeId the split/find node ID
     * @param items the pre-resolved list of items
     * @param maxItems maximum number of items to process (0 = unlimited)
     * @param workflowItemIndex the workflow item index for context scoping
     * @param context the execution context
     * @return execution result with item count and status
     */
    public NodeExecutionResult executeWithItems(
            String runId,
            String nodeId,
            List<Object> items,
            int maxItems,
            int workflowItemIndex,
            ExecutionContext context) {

        String parentScopeKey = SplitContextManager.extractParentScopeKey(context);

        logger.info("[SplitExecutor] Executing with pre-resolved items: nodeId={}, itemCount={}, maxItems={}, workflowItem={}, parentScope={}",
            nodeId, items != null ? items.size() : 0, maxItems, workflowItemIndex, parentScopeKey);

        if (items == null) {
            logger.error("[SplitExecutor] Items list is null: nodeId={}", nodeId);
            return createErrorResult(nodeId, "Items list is null", null, maxItems, null, null);
        }

        // Apply maxItems limit if specified
        List<Object> effectiveItems = items;
        if (maxItems > 0 && effectiveItems.size() > maxItems) {
            logger.info("[SplitExecutor] Limiting items from {} to {}", effectiveItems.size(), maxItems);
            effectiveItems = effectiveItems.subList(0, maxItems);
        }

        // Handle empty list
        if (effectiveItems.isEmpty()) {
            logger.info("[SplitExecutor] Items list is empty: nodeId={}", nodeId);
            contextManager.createContext(runId, nodeId, workflowItemIndex, parentScopeKey, effectiveItems, epochOf(context));
            return createSuccessResult(nodeId, effectiveItems, "empty_list", null, maxItems, null, null);
        }

        // Create SplitContext with items
        SplitContext splitContext = contextManager.createContext(runId, nodeId, workflowItemIndex, parentScopeKey, effectiveItems, epochOf(context));

        logger.info("[SplitExecutor] Split spawned {} items (from pre-resolved): nodeId={}, contextKey={}",
            effectiveItems.size(), nodeId, splitContext.splitNodeId());

        return createSuccessResult(nodeId, effectiveItems, "items_spawned", null, maxItems, null, null);
    }

    /**
     * Checks if there is an existing SplitContext for this split.
     *
     * @param runId the workflow run ID
     * @param nodeId the split node ID
     * @param workflowItemIndex the workflow item index (from trigger)
     * @return true if context exists
     */
    public boolean hasExistingContext(String runId, String nodeId, int workflowItemIndex) {
        return contextManager.getContext(runId, nodeId, workflowItemIndex).isPresent();
    }

    /**
     * Clears existing context (for rerun).
     *
     * @param runId the workflow run ID
     * @param nodeId the split node ID
     * @param workflowItemIndex the workflow item index (from trigger)
     */
    public void clearContext(String runId, String nodeId, int workflowItemIndex) {
        contextManager.removeContext(runId, nodeId, workflowItemIndex);
        logger.info("[SplitExecutor] Cleared context for rerun: runId={}, nodeId={}, workflowItemIndex={}",
            runId, nodeId, workflowItemIndex);
    }

    /**
     * Epoch this split is spawning for, stamped on the context so a delivery landing on another
     * replica can tell it apart from the same split's context in an earlier epoch
     * (see {@code SplitContextManager.restoreContext}). Null-tolerant for unit-test contexts.
     */
    private static int epochOf(ExecutionContext context) {
        return context != null ? context.epoch() : SplitContext.UNKNOWN_EPOCH;
    }
}
