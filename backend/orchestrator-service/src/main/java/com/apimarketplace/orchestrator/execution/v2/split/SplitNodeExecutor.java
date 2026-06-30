package com.apimarketplace.orchestrator.execution.v2.split;

import com.apimarketplace.orchestrator.execution.v2.constants.ExecutionMetadataKeys;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.OutputUnwrapper;
import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;
import com.apimarketplace.orchestrator.execution.v2.template.V2TemplateAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.LinkedHashMap;
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
            return createErrorResult(nodeId, evaluation.diagnostic());
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
            contextManager.createContext(runId, nodeId, workflowItemIndex, parentScopeKey, items);
            return createSuccessResult(nodeId, items, "empty_list", sourceExpression, maxItems);
        }

        // 4. Create SplitContext with items (scoped to workflow item and parent scope)
        SplitContext splitContext = contextManager.createContext(runId, nodeId, workflowItemIndex, parentScopeKey, items);

        logger.info("[SplitExecutor] Split spawned {} items: nodeId={}, contextKey={}",
            items.size(), nodeId, splitContext.splitNodeId());

        // 5. Return COMPLETED - split job is done
        return createSuccessResult(nodeId, items, "items_spawned", sourceExpression, maxItems);
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
            return ListEvaluation.failure("Split `list` expression is null or blank.");
        }

        if (templateAdapter == null) {
            logger.error("[SplitExecutor] TemplateAdapter is not configured");
            return ListEvaluation.failure("Split template adapter is not configured.");
        }

        Object result;
        try {
            result = templateAdapter.evaluateTemplate(expression, context);
        } catch (Exception e) {
            logger.error("[SplitExecutor] Failed to evaluate expression: expression={}, error={}",
                expression, e.getMessage(), e);
            return ListEvaluation.failure(
                "Split `list` expression `" + expression + "` threw during evaluation: " + e.getMessage());
        }

        // null: missing step output or unresolved template - distinct from a legitimately empty list.
        if (result == null) {
            return ListEvaluation.failure(
                "Split `list` expression `" + expression + "` resolved to null. "
                    + "Check the upstream node has completed and the reference path matches its output schema.");
        }

        // List/Collection/array → pass through; wrapper Map → unwrap on an array-bearing key.
        Optional<List<Object>> extracted = OutputUnwrapper.tryUnwrapToList(result);
        if (extracted.isPresent()) {
            return ListEvaluation.success(extracted.get());
        }

        // Recognized-key-but-not-array, no recognized key, or a primitive - fail loud, never wrap.
        return ListEvaluation.failure(OutputUnwrapper.describeNonListShape(result, expression));
    }

    /**
     * Carrier for the list-evaluation outcome: {@code items} on success, {@code diagnostic} on
     * failure. Mirrors {@code SplitNode.EvaluationResult} so callers surface the real reason to the
     * failure output instead of a generic "Failed to evaluate" string.
     */
    private record ListEvaluation(List<Object> items, String diagnostic) {
        static ListEvaluation success(List<Object> items) {
            return new ListEvaluation(items, null);
        }
        static ListEvaluation failure(String diagnostic) {
            return new ListEvaluation(null, diagnostic);
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
                                                     String sourceExpression, int maxItems) {
        Map<String, Object> output = new HashMap<>();
        output.put(ExecutionMetadataKeys.NODE_TYPE, "SPLIT");
        output.put("split_id", nodeId);
        output.put(ExecutionMetadataKeys.ITEM_COUNT, items.size());
        output.put("spawn_reason", reason);
        output.put(ExecutionMetadataKeys.TERMINATED, true);  // split is COMPLETED immediately
        output.put("items", items);  // Store items for persistence and inspection

        // Persist resolved configuration as resolved_params for the inspector panel.
        // Without this, the split node shows empty "Resolved parameters" in the run view.
        Map<String, Object> resolvedParams = new LinkedHashMap<>();
        if (sourceExpression != null) {
            resolvedParams.put("source_expression", sourceExpression);
        }
        if (maxItems > 0) {
            resolvedParams.put("max_items", maxItems);
        }
        resolvedParams.put("item_count", items.size());
        output.put("resolved_params", resolvedParams);

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
     */
    private NodeExecutionResult createErrorResult(String nodeId, String errorMessage) {
        Map<String, Object> output = new HashMap<>();
        output.put(ExecutionMetadataKeys.NODE_TYPE, "SPLIT");
        output.put("split_id", nodeId);
        output.put("error", errorMessage);
        // Persist minimal resolved_params even on failure for inspector visibility
        Map<String, Object> resolvedParams = new LinkedHashMap<>();
        resolvedParams.put("error", errorMessage);
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
     * <p>Used by FindNode which already has items from CRUD read execution.
     * Same logic as execute() but skips expression evaluation.
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
            return createErrorResult(nodeId, "Items list is null");
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
            contextManager.createContext(runId, nodeId, workflowItemIndex, parentScopeKey, effectiveItems);
            return createSuccessResult(nodeId, effectiveItems, "empty_list", null, maxItems);
        }

        // Create SplitContext with items
        SplitContext splitContext = contextManager.createContext(runId, nodeId, workflowItemIndex, parentScopeKey, effectiveItems);

        logger.info("[SplitExecutor] Split spawned {} items (from pre-resolved): nodeId={}, contextKey={}",
            effectiveItems.size(), nodeId, splitContext.splitNodeId());

        return createSuccessResult(nodeId, effectiveItems, "items_spawned", null, maxItems);
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
}
