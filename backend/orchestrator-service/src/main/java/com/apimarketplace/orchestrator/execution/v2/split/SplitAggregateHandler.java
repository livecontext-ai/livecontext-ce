package com.apimarketplace.orchestrator.execution.v2.split;

import com.apimarketplace.orchestrator.execution.v2.constants.ExecutionMetadataKeys;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.nodes.AggregateNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.ExecutionNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;
import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import com.apimarketplace.orchestrator.services.StepOutputService;
import com.apimarketplace.orchestrator.services.TemplateEngine;
import com.apimarketplace.orchestrator.services.persistence.OutputSchemaMapper;
import com.apimarketplace.orchestrator.utils.EdgeRefParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handles aggregate node execution in the context of split.
 *
 * <p>When an aggregate node is reached after a split, this handler:
 * <ol>
 *   <li>Finds the active SplitContext</li>
 *   <li>Collects all per-item results from upstream nodes</li>
 *   <li>Closes the SplitContext (removes it) so downstream nodes execute ONCE</li>
 *   <li>Returns a single aggregated result</li>
 * </ol>
 *
 * <p>This is the N -> 1 reduction counterpart to Split's 1 -> N expansion.
 * Unlike SplitMergeHandler (which merges parallel branches), this handler
 * reduces N split item contexts back into a single output.
 *
 * <p>After aggregation, downstream nodes execute exactly once because
 * the SplitContext is no longer active.
 *
 * <p>Usage:
 * <pre>
 * if (node.isAggregateNode() && splitAggregateHandler.isSplitAggregate(runId, nodeId, itemIndex, nodeMap)) {
 *     return splitAggregateHandler.handleAggregate(runId, nodeId, itemIndex, context, nodeMap);
 * }
 * </pre>
 */
@Service
public class SplitAggregateHandler {

    private static final Logger logger = LoggerFactory.getLogger(SplitAggregateHandler.class);

    /**
     * Node-reference matcher for the aggregate's configured field expressions
     * (e.g. {@code {{core:parse_headers.output.transformed.subject}}}). Used to
     * discover which upstream nodes a field reads so a per-item output that is
     * ENTIRELY absent from the in-memory SplitContext (never recorded due to the
     * split→aggregate race) can still be recovered from the durable store - a
     * missing node won't appear in {@code getAllResults()}, so the expressions are
     * the only signal that it should be loaded.
     */
    private static final Pattern NODE_REF =
        Pattern.compile("(core|mcp|agent|table|trigger|interface|note):[a-zA-Z0-9_]+");

    private final SplitContextManager contextManager;
    private final TemplateEngine templateEngine;
    private final OutputSchemaMapper outputSchemaMapper;
    private final SplitAwareNodeExecutor splitAwareNodeExecutor;
    private final StepOutputService stepOutputService;

    public SplitAggregateHandler(SplitContextManager contextManager,
                                 TemplateEngine templateEngine,
                                 OutputSchemaMapper outputSchemaMapper,
                                 @Lazy SplitAwareNodeExecutor splitAwareNodeExecutor,
                                 @Lazy StepOutputService stepOutputService) {
        this.contextManager = contextManager;
        this.templateEngine = templateEngine;
        this.outputSchemaMapper = outputSchemaMapper;
        // @Lazy avoids a constructor cycle if SplitAwareNodeExecutor ever ends up
        // depending on this handler transitively. Today there is no cycle, but
        // pinning the relaxation keeps refactors safe.
        this.splitAwareNodeExecutor = splitAwareNodeExecutor;
        // Durable per-item output fallback (split→aggregate race / post-restart).
        // @Lazy + null-tolerant: unit tests that don't exercise the fallback pass null.
        this.stepOutputService = stepOutputService;
    }

    /**
     * Checks if this aggregate node is aggregating split results.
     *
     * <p>Uses two strategies:
     * <ol>
     *   <li>BFS predecessor traversal via findActiveContext (when full nodeMap is available)</li>
     *   <li>Fallback: checks if ANY split context exists for this run (aggregate nodes
     *       only make sense after a split, so any active context implies split scope)</li>
     * </ol>
     *
     * <p>The fallback is needed because in auto mode, the engine may have a minimal
     * nodeMap that doesn't include predecessors, causing the BFS to fail.
     *
     * @param runId the workflow run ID
     * @param nodeId the aggregate node ID
     * @param workflowItemIndex the workflow item index (for scoping)
     * @param nodeMap map of all nodes
     * @return true if there's an active SplitContext upstream
     */
    public boolean isSplitAggregate(
            String runId,
            String nodeId,
            int workflowItemIndex,
            Map<String, ExecutionNode> nodeMap) {

        // First try BFS traversal (works with full nodeMap in step-by-step mode)
        if (contextManager.findActiveContext(runId, nodeId, workflowItemIndex, nodeMap).isPresent()) {
            return true;
        }

        // Fallback: aggregate nodes only exist to reduce split items.
        // If any split context exists for this run, this aggregate is for it.
        return contextManager.hasContexts(runId);
    }

    /**
     * Handles aggregate execution for split, collecting all per-item results into one output.
     *
     * <p>This method:
     * <ol>
     *   <li>Finds the active SplitContext for this aggregate node</li>
     *   <li>Collects all per-item results stored by upstream nodes</li>
     *   <li>Removes the SplitContext to close the split scope</li>
     *   <li>Returns a single SUCCESS result with aggregated data</li>
     * </ol>
     *
     * @param runId the workflow run ID
     * @param nodeId the aggregate node ID
     * @param workflowItemIndex the workflow item index (for scoping)
     * @param context the execution context
     * @param nodeMap map of all nodes
     * @return the aggregate execution result with collected data
     */
    public NodeExecutionResult handleAggregate(
            String runId,
            String nodeId,
            int workflowItemIndex,
            ExecutionContext context,
            Map<String, ExecutionNode> nodeMap) {

        // Try BFS traversal first (works with full nodeMap)
        Optional<SplitContext> splitContextOpt = contextManager.findActiveContext(
            runId, nodeId, workflowItemIndex, nodeMap);

        // Fallback: find any active context for this run (for minimal nodeMap in auto mode)
        if (splitContextOpt.isEmpty()) {
            splitContextOpt = findAnyActiveContext(runId, workflowItemIndex);
        }

        if (splitContextOpt.isEmpty()) {
            logger.warn("[SplitAggregate] No SplitContext found for aggregate: nodeId={}, workflowItem={}",
                nodeId, workflowItemIndex);
            return createEmptyAggregateResult(nodeId, context);
        }

        SplitContext splitContext = splitContextOpt.get();
        String contextKey = splitContext.splitNodeId();

        // Extract the actual splitNodeId from the scoped context key
        String splitNodeId = extractSplitNodeId(contextKey);

        logger.info("[SplitAggregate] Handling split aggregate: nodeId={}, split={}, workflowItem={}, itemCount={}",
            nodeId, splitNodeId, workflowItemIndex, splitContext.itemCount());

        // ─── Routed-item filtering ──────────────────────────────────────────
        // The aggregate must reflect items that actually traversed its direct
        // predecessor branch, not every item in the split. Without this filter,
        // an aggregate behind `classify:category_X → apply_X → record_X` would
        // produce field entries for every email - even the ones classified to
        // sibling categories - because upstream nodes BEFORE the classify (e.g.
        // parse_headers) ran for all items. Prod 2026-05-14 (run
        // run_<id> epoch 19): 5 emails, 0 urgent, yet
        // `collect_urgents` returned COMPLETED with 5 urgent_lines entries.
        //
        // Resolve via SplitAwareNodeExecutor.resolveRoutedItemIndices which is
        // DB-backed (findCompletedItemIndicesByEpoch), port-aware, and uses
        // UNION semantics for merge-like nodes - same routing model the rest
        // of the engine relies on. This is more resilient than reading from
        // splitContext.resultsByNode (in-memory cache, lost on restart).
        Set<Integer> routedIndices = resolveRoutedItemIndices(nodeMap, nodeId, context, splitContext);

        int totalItems = splitContext.itemCount();
        int filteredCount = routedIndices.size();
        boolean noItemsRouted = routedIndices.isEmpty() && totalItems > 0;
        logger.info("[SplitAggregate] Routed-item filter: nodeId={}, totalItems={}, routedCount={}, routed={}",
            nodeId, totalItems, filteredCount, routedIndices);

        // CRITICAL: Close the SplitContext BEFORE returning either path.
        // Downstream nodes must not see the split context and must execute only
        // once (success path) or cascade-skip without re-spawning per-item
        // contexts (skipped path).
        contextManager.removeContext(runId, splitNodeId, workflowItemIndex);

        logger.info("[SplitAggregate] Split context closed: nodeId={}, split={}, workflowItem={}",
            nodeId, splitNodeId, workflowItemIndex);

        if (noItemsRouted) {
            // No item completed through this aggregate's predecessor branch.
            // Return SKIPPED so the engine cascades through linear successors
            // (build_urgent_msg, send_urgent_telegram, ...). The metadata flag
            // CASCADE_SKIP_TO_SUCCESSORS is the explicit handshake with
            // UnifiedExecutionEngine to invoke V2SkipPropagationService for
            // this terminal-skip (BaseNode.getNextNodes only filters on
            // isFailure; without the flag SKIPPED would still expose the
            // successors and let them run with empty input).
            logger.info("[SplitAggregate] No items routed to aggregate - returning SKIPPED with cascade flag: nodeId={}, totalItems={}",
                nodeId, totalItems);
            Map<String, Object> skippedMeta = new HashMap<>();
            skippedMeta.put(ExecutionMetadataKeys.CASCADE_SKIP_TO_SUCCESSORS, true);
            skippedMeta.put("split_aggregate", true);
            skippedMeta.put("skip_reason", "No upstream items routed to this aggregate");
            return new NodeExecutionResult(
                nodeId,
                NodeStatus.SKIPPED,
                Map.of(),
                Optional.of("No upstream items routed to this aggregate (predecessor branch produced 0 routed items)"),
                skippedMeta,
                0
            );
        }

        // Resolve the per-item upstream outputs the aggregate will read. Starts
        // from the in-memory SplitContext, then BACKFILLS any routed slot that is
        // missing in-memory from the durable step-output store. The in-memory
        // resultsByNode is a best-effort cache: a chained downstream node records
        // its slot AFTER its DB step write, and the DB-completion-driven aggregate
        // barrier can read this map in that window (or empty, post-restart) - which
        // silently dropped the node and produced empty fields (prod 2026-06-05:
        // "🚨 EMAILS URGENTS (1)" with blank sender/subject/snippet). This mirrors
        // the already-DB-backed routed-index resolution above. No-op (no DB hit)
        // when every routed slot is already present in memory.
        Map<String, List<Object>> resolvedResults =
            resolvePerItemResults(splitContext, routedIndices, nodeId, nodeMap, context);

        // Collect per-item results - filtered to the routed indices only so
        // downstream readers of `aggregated_results.<upstream>` see the same
        // shape (per-routed-item) as the configured-field outputs.
        Map<String, Object> aggregatedData = collectResults(resolvedResults, splitContext.itemCount(), routedIndices);

        // Evaluate configured fields from AggregateNode (if any) - iterate
        // only over the routed indices, not over splitContext.itemCount().
        Map<String, List<Object>> fieldResults = evaluateConfiguredFields(
            nodeId, splitContext, resolvedResults, nodeMap, routedIndices);

        // Build aggregate result
        Map<String, Object> output = new HashMap<>();
        output.put("node_type", "AGGREGATE");
        output.put("split_aggregate", true);
        output.put("split_id", splitNodeId);
        // aggregated_count = filtered (matches `urgent_lines.size()` and the
        // user-facing "items I actually aggregated" count). total_items keeps
        // the "emails the split processed" observability for downstream UIs.
        output.put("aggregated_count", filteredCount);
        output.put("total_items", totalItems);
        output.put("item_count", filteredCount);
        output.put("aggregated_results", aggregatedData);
        output.put("item_index", context.itemIndex());
        output.put("item_id", context.itemId());

        // Add configured field results (each is a List, will be picked up by schema mapper)
        output.putAll(fieldResults);

        // Include the original items for reference (full split list, not
        // filtered - items themselves were never routed/skipped, only the
        // predecessor branch was). Downstream can correlate via item_index in
        // the filtered fields.
        output.put("items", splitContext.items());

        // Flatten the latest results to "results" key for easy access -
        // filtered to routed indices for consistency with aggregated_results.
        // Reads the durable-backfilled map (not the raw SplitContext) so this
        // convenience key stays consistent with the configured fields cross-pod.
        List<Object> latestResults = filteredLatestResults(resolvedResults, routedIndices);
        if (!latestResults.isEmpty()) {
            output.put("results", latestResults);
        }

        return new NodeExecutionResult(
            nodeId,
            NodeStatus.COMPLETED,
            output,
            Optional.empty(),
            Map.of("split_aggregate", true),
            0
        );
    }

    /**
     * Resolve which item indices reached this aggregate via its direct
     * predecessor branch. Delegates to the DB-backed helper on
     * {@link SplitAwareNodeExecutor#resolveRoutedItemIndices} which already
     * understands UNION-for-merge-like, port suffixes, and disjoint-branch
     * routing. Falls back to the in-memory split context view if the helper
     * yields {@link Set#of()} for the rare case where the legacy
     * "all-items" semantic should apply (no predecessor data, no node in
     * map) - see audit M3 ("missing != all").
     */
    private Set<Integer> resolveRoutedItemIndices(
            Map<String, ExecutionNode> nodeMap,
            String aggregateNodeId,
            ExecutionContext context,
            SplitContext splitContext) {
        int totalItems = splitContext.itemCount();
        if (totalItems <= 0) return Set.of();
        if (splitAwareNodeExecutor == null) {
            // Defensive: bean wiring failed → preserve legacy behavior (iterate
            // all items). Should never happen in production but keeps unit
            // tests that construct the handler with a null executor green.
            logger.debug("[SplitAggregate] splitAwareNodeExecutor null - falling back to allItems for nodeId={}",
                aggregateNodeId);
            return allIndices(totalItems);
        }
        ExecutionNode node = nodeMap.get(aggregateNodeId);
        if (node == null) {
            // The aggregate isn't in the trimmed nodeMap (auto-mode minimal
            // map). We genuinely can't compute its predecessors from here, so
            // fall back to the in-memory splitContext view: any index where at
            // least one upstream node persisted a result is "routed".
            Set<Integer> fromMemory = indicesWithDataInSplitContext(splitContext);
            logger.debug("[SplitAggregate] aggregate {} not in nodeMap - using splitContext-derived routing: {}",
                aggregateNodeId, fromMemory);
            return fromMemory;
        }
        Set<Integer> routed = splitAwareNodeExecutor.resolveRoutedItemIndices(
            node, context.runId(), totalItems, context.epoch(), splitContext.splitNodeId());
        if (routed == null) {
            // Helper itself never returns null in production (worst case Set.of()),
            // so this branch is unreachable at runtime. It guards legacy unit tests
            // that hand-construct the handler with an un-stubbed mock - there,
            // returning allIndices preserves the pre-fix "iterate all" behavior
            // that those tests pin. The audited bug shape (M3) is `routed.isEmpty()`,
            // NOT `routed == null` - those two states are distinguished here.
            logger.debug("[SplitAggregate] resolveRoutedItemIndices returned null for nodeId={} - falling back to allIndices",
                aggregateNodeId);
            return allIndices(totalItems);
        }
        return routed;
    }

    /**
     * Backup routing source - derives "routed" indices from the in-memory
     * splitContext.resultsByNode (sparse list of size itemCount). Any index
     * where ANY upstream node persisted a non-null entry counts as routed.
     * Used only when the aggregate is missing from the engine's nodeMap.
     */
    private Set<Integer> indicesWithDataInSplitContext(SplitContext splitContext) {
        Set<Integer> indices = new HashSet<>();
        for (List<Object> perNode : splitContext.getAllResults().values()) {
            if (perNode == null) continue;
            for (int i = 0; i < perNode.size(); i++) {
                if (perNode.get(i) != null) indices.add(i);
            }
        }
        return indices;
    }

    private static Set<Integer> allIndices(int totalItems) {
        Set<Integer> all = new HashSet<>();
        for (int i = 0; i < totalItems; i++) all.add(i);
        return all;
    }

    /**
     * Finds any active SplitContext for the given run and workflow item index.
     * Used as a fallback when BFS traversal fails due to minimal nodeMap.
     *
     * @param runId the workflow run ID
     * @param workflowItemIndex the workflow item index
     * @return the first matching SplitContext, or empty
     */
    private Optional<SplitContext> findAnyActiveContext(String runId, int workflowItemIndex) {
        Map<String, SplitContext> allContexts = contextManager.getAllContexts(runId);
        if (allContexts.isEmpty()) {
            return Optional.empty();
        }

        // Look for a context scoped to this workflow item index
        // Key formats: "core:iterate:0" or "core:iterate:0/s0" (nested)
        String itemSuffix = ":" + workflowItemIndex;
        for (Map.Entry<String, SplitContext> entry : allContexts.entrySet()) {
            String key = entry.getKey();
            // Check if key contains the item index (either at end or before a scope suffix)
            if (key.endsWith(itemSuffix) || key.contains(itemSuffix + "/")) {
                logger.debug("[SplitAggregate] Found context via fallback: key={}, runId={}",
                    key, runId);
                return Optional.of(entry.getValue());
            }
        }

        // If no item-specific context found, return the first available
        Map.Entry<String, SplitContext> first = allContexts.entrySet().iterator().next();
        logger.debug("[SplitAggregate] Using first available context: key={}, runId={}",
            first.getKey(), runId);
        return Optional.of(first.getValue());
    }

    /**
     * Collects per-item results from the SplitContext, filtered to the items
     * that actually routed through the aggregate's predecessor branch.
     *
     * <p>Per-upstream lists are kept positional (size == filtered count) so
     * downstream readers of {@code aggregated_results.<upstream>} can zip
     * with {@code item_index} entries from the configured fields and stay
     * shape-consistent with them.
     */
    private Map<String, Object> collectResults(Map<String, List<Object>> allResults, int itemCount, Set<Integer> routedIndices) {
        Map<String, Object> collected = new HashMap<>();

        List<Integer> sortedRouted = new ArrayList<>(routedIndices);
        java.util.Collections.sort(sortedRouted);

        for (Map.Entry<String, List<Object>> entry : allResults.entrySet()) {
            String upstreamNodeId = entry.getKey();
            List<Object> rawResults = entry.getValue();

            // Filter to routed indices, preserving sort order.
            List<Object> filteredResults = new ArrayList<>(sortedRouted.size());
            for (int idx : sortedRouted) {
                if (idx >= 0 && idx < rawResults.size()) {
                    filteredResults.add(rawResults.get(idx));
                } else {
                    filteredResults.add(null);
                }
            }

            collected.put(upstreamNodeId, filteredResults);
            collectFieldsFromResults(collected, upstreamNodeId, filteredResults);
        }

        collected.put("total_items", itemCount);
        collected.put("filtered_count", routedIndices.size());
        collected.put("nodes_executed", allResults.size());

        return collected;
    }

    /**
     * {@link SplitContext#getLatestResults()} returns the first non-empty
     * per-node list (positional, size == split.itemCount()). Filter it the
     * same way as {@link #collectResults} so {@code output.results} is
     * shape-consistent with {@code output.aggregated_results.<node>}.
     */
    private List<Object> filteredLatestResults(Map<String, List<Object>> resolvedResults, Set<Integer> routedIndices) {
        // Mirror SplitContext.getLatestResults(): the first non-empty per-node list.
        List<Object> raw = resolvedResults.values().stream()
            .filter(r -> r != null && !r.isEmpty())
            .findFirst()
            .orElse(List.of());
        if (raw.isEmpty()) return List.of();
        List<Integer> sortedRouted = new ArrayList<>(routedIndices);
        java.util.Collections.sort(sortedRouted);
        List<Object> filtered = new ArrayList<>(sortedRouted.size());
        for (int idx : sortedRouted) {
            if (idx >= 0 && idx < raw.size()) {
                filtered.add(raw.get(idx));
            } else {
                filtered.add(null);
            }
        }
        return filtered;
    }

    /**
     * Extracts common fields from result maps and collects them into arrays.
     */
    @SuppressWarnings("unchecked")
    private void collectFieldsFromResults(
            Map<String, Object> collected,
            String nodeId,
            List<Object> results) {

        // If all results are maps, extract common fields
        List<Map<String, Object>> mapResults = results.stream()
            .filter(r -> r instanceof Map)
            .map(r -> (Map<String, Object>) r)
            .toList();

        if (mapResults.isEmpty() || mapResults.size() != results.size()) {
            return; // Not all results are maps
        }

        Map<String, Object> firstResult = mapResults.get(0);
        for (String key : firstResult.keySet()) {
            boolean allHaveKey = mapResults.stream().allMatch(m -> m.containsKey(key));
            if (allHaveKey) {
                List<Object> values = mapResults.stream()
                    .map(m -> m.get(key))
                    .toList();
                collected.put(nodeId + "." + key, values);
            }
        }
    }

    /**
     * Extracts the base split node ID from a scoped context key.
     * Handles both simple and nested scope formats:
     * - "core:processmessages:0" -> "core:processmessages"
     * - "core:processmessages:0/s0" -> "core:processmessages"
     */
    private String extractSplitNodeId(String contextKey) {
        return SplitContextManager.extractBaseSplitNodeId(contextKey);
    }

    // ── Configured field evaluation ──────────────────────────────────────

    /**
     * Evaluates the configured AggregateNode fields for each item in the SplitContext.
     *
     * <p>For each item, builds an evaluation context containing:
     * <ul>
     *   <li>The split item data (current_item, current_index)</li>
     *   <li>The schema-mapped upstream node results for that item</li>
     * </ul>
     *
     * <p>Upstream results are schema-mapped so that field expressions can reference
     * documented output names (e.g., {@code file}) rather than raw backend names.
     *
     * @return map of field label → list of values (one per item)
     */
    @SuppressWarnings("unchecked")
    private Map<String, List<Object>> evaluateConfiguredFields(
            String aggregateNodeId,
            SplitContext splitContext,
            Map<String, List<Object>> resolvedResults,
            Map<String, ExecutionNode> nodeMap,
            Set<Integer> routedIndices) {

        Map<String, List<Object>> fieldResults = new HashMap<>();

        // Get the AggregateNode to access configured fields
        ExecutionNode node = nodeMap.get(aggregateNodeId);
        if (!(node instanceof AggregateNode aggregateNode)) {
            return fieldResults;
        }

        List<AggregateNode.AggregateField> fields = aggregateNode.getFields();
        if (fields == null || fields.isEmpty()) {
            return fieldResults;
        }

        // Per-item upstream outputs with durable-store backfill already applied
        // (see resolvePerItemResults). buildItemEvalContext still reads current_item
        // from splitContext directly (the split's own item list, never lost).
        Map<String, List<Object>> allResults = resolvedResults;
        String baseSplitId = extractSplitNodeId(splitContext.splitNodeId());

        // Initialize field result lists
        for (AggregateNode.AggregateField field : fields) {
            fieldResults.put(field.label(), new ArrayList<>());
        }

        // Iterate ONLY over the routed indices (items that actually traversed
        // this aggregate's predecessor branch). Order them so downstream
        // consumers see a deterministic sequence.
        List<Integer> sortedRouted = new ArrayList<>(routedIndices);
        java.util.Collections.sort(sortedRouted);

        for (int i : sortedRouted) {
            Map<String, Object> itemEvalContext = buildItemEvalContext(
                i, splitContext, allResults, baseSplitId);

            for (AggregateNode.AggregateField field : fields) {
                Object value = evaluateExpression(field.expression(), itemEvalContext);
                fieldResults.get(field.label()).add(value);
            }
        }

        logger.info("[SplitAggregate] Evaluated {} configured fields for {} routed items (of {} total in split): labels={}",
            fields.size(), sortedRouted.size(), splitContext.itemCount(),
            fields.stream().map(AggregateNode.AggregateField::label).toList());

        return fieldResults;
    }

    /**
     * Resolves the per-item upstream outputs the aggregate will read, backfilling
     * any routed slot that is missing from the in-memory {@link SplitContext} with
     * the durable step-output store.
     *
     * <p><b>Why:</b> {@code SplitContext.resultsByNode} is a best-effort in-memory
     * cache. A chained downstream node persists its DB step (inside
     * {@code node.execute()}) BEFORE recording its in-memory slot
     * ({@code SplitAwareNodeExecutor.recordChainedDownstreamResult}); the aggregate
     * barrier is driven by DB completion, so it can observe {@code resultsByNode}
     * before that slot lands - and {@link #buildItemEvalContext} then silently drops
     * the node, yielding an empty field. (It is also empty wholesale after a restart
     * that cleared the cache.) Backfilling from the durable store - the same source
     * the routed-index resolution already trusts - closes the race deterministically.
     *
     * <p>Strictly additive: starts from the in-memory results and only touches a node
     * for a routed index when that slot is null/absent, so the warm path performs no
     * DB reads and the resolved values are byte-identical to today.
     *
     * @return a node-id → per-item (item-indexed, padded to itemCount) results map
     */
    private Map<String, List<Object>> resolvePerItemResults(
            SplitContext splitContext,
            Set<Integer> routedIndices,
            String aggregateNodeId,
            Map<String, ExecutionNode> nodeMap,
            ExecutionContext context) {

        int itemCount = splitContext.itemCount();

        // Mutable, item-indexed copy of the in-memory per-node results (padded to itemCount).
        Map<String, List<Object>> resolved = new HashMap<>();
        for (Map.Entry<String, List<Object>> e : splitContext.getAllResults().entrySet()) {
            List<Object> padded = new ArrayList<>(e.getValue() != null ? e.getValue() : List.of());
            while (padded.size() < itemCount) {
                padded.add(null);
            }
            resolved.put(e.getKey(), padded);
        }

        // No durable source wired (unit tests that don't exercise the fallback) or no
        // context/routed items → keep the in-memory view unchanged.
        if (stepOutputService == null || context == null || routedIndices.isEmpty()) {
            return resolved;
        }

        // Nodes whose per-item output the aggregate may read: those already present
        // in the SplitContext PLUS those referenced by the configured field
        // expressions (a node never recorded in-memory won't appear in
        // getAllResults(), so the expressions are the only way to learn it should be
        // loaded). The split node itself is excluded - its current_item comes from the
        // items list, not from per-node results.
        Set<String> nodesToEnsure = new HashSet<>(resolved.keySet());
        ExecutionNode node = nodeMap != null ? nodeMap.get(aggregateNodeId) : null;
        if (node instanceof AggregateNode aggregateNode) {
            nodesToEnsure.addAll(extractReferencedNodeIds(aggregateNode.getFields()));
        }
        nodesToEnsure.remove(extractSplitNodeId(splitContext.splitNodeId()));

        for (String upstreamNodeId : nodesToEnsure) {
            List<Object> slots = resolved.get(upstreamNodeId);

            // Which routed slots are missing in memory?
            List<Integer> missing = new ArrayList<>();
            for (int idx : routedIndices) {
                if (slots == null || idx < 0 || idx >= slots.size() || slots.get(idx) == null) {
                    missing.add(idx);
                }
            }
            if (missing.isEmpty()) {
                continue; // warm path - no DB hit
            }

            Map<Integer, Object> durable;
            try {
                durable = stepOutputService.loadPerItemNodeOutputs(
                    context.runId(), upstreamNodeId, context.epoch(), context.tenantId());
            } catch (Exception ex) {
                logger.warn("[SplitAggregate] Durable per-item fallback failed for node {} (run={}, epoch={}): {}",
                    upstreamNodeId, context.runId(), context.epoch(), ex.getMessage());
                continue;
            }
            // null-tolerant (defensive: the service contract returns an empty map, never null,
            // but a null must degrade to "no backfill", not NPE outside the try above).
            if (durable == null || durable.isEmpty()) {
                continue;
            }

            if (slots == null) {
                slots = new ArrayList<>();
                resolved.put(upstreamNodeId, slots);
            }
            while (slots.size() < itemCount) {
                slots.add(null);
            }

            int filled = 0;
            for (int idx : missing) {
                if (idx >= 0 && idx < slots.size()) {
                    Object durableVal = durable.get(idx);
                    if (durableVal != null) {
                        slots.set(idx, durableVal);
                        filled++;
                    }
                }
            }
            if (filled > 0) {
                logger.info("[SplitAggregate] Recovered {} per-item output(s) for node {} from the durable store "
                        + "(in-memory SplitContext slot absent - split→aggregate race): aggregate={}, run={}, epoch={}",
                    filled, upstreamNodeId, aggregateNodeId, context.runId(), context.epoch());
            }
        }
        return resolved;
    }

    /**
     * Extracts the distinct upstream node references ({@code prefix:label}) from the
     * aggregate's configured field expressions, e.g.
     * {@code {{core:parse_headers.output.transformed.subject}}} → {@code core:parse_headers}.
     */
    private Set<String> extractReferencedNodeIds(List<AggregateNode.AggregateField> fields) {
        Set<String> refs = new HashSet<>();
        if (fields == null) {
            return refs;
        }
        for (AggregateNode.AggregateField field : fields) {
            if (field == null || field.expression() == null) {
                continue;
            }
            Matcher m = NODE_REF.matcher(field.expression());
            while (m.find()) {
                refs.add(m.group());
            }
        }
        return refs;
    }

    /**
     * Builds an evaluation context for a single split item.
     *
     * <p>The context maps node IDs to their wrapped output so that expressions
     * like {@code {{core:download_post_image.output.file}}} can be resolved.
     *
     * <p>Upstream results are schema-mapped before being placed in the context,
     * so expressions reference the documented output field names.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> buildItemEvalContext(
            int itemIndex,
            SplitContext splitContext,
            Map<String, List<Object>> allResults,
            String baseSplitId) {

        Map<String, Object> evalContext = new HashMap<>();

        // 1. Add split node output with current_item / current_index
        Object currentItem = splitContext.getItem(itemIndex);
        Map<String, Object> splitOutput = new HashMap<>();
        splitOutput.put("current_item", currentItem);
        splitOutput.put("current_index", itemIndex);
        splitOutput.put("item_count", splitContext.itemCount());

        Map<String, Object> wrappedSplit = Map.of("output", splitOutput);
        evalContext.put(baseSplitId, wrappedSplit);
        addShortKey(evalContext, baseSplitId, wrappedSplit);

        // 2. Add upstream node results (schema-mapped for documented field names)
        for (var entry : allResults.entrySet()) {
            String nodeId = entry.getKey();
            List<Object> results = entry.getValue();
            if (itemIndex >= results.size() || results.get(itemIndex) == null) {
                continue;
            }

            Object rawResult = results.get(itemIndex);

            // Schema-map the result so expressions use documented field names
            Object mappedResult = schemaMappResult(rawResult);

            Map<String, Object> wrappedResult = Map.of("output", mappedResult);
            evalContext.put(nodeId, wrappedResult);
            addShortKey(evalContext, nodeId, wrappedResult);

            // Also flatten the mapped output for direct field access
            if (mappedResult instanceof Map) {
                evalContext.putAll((Map<String, Object>) mappedResult);
            }
        }

        // 3. Add item context shortcuts
        evalContext.put("item_index", itemIndex);
        evalContext.put("item", currentItem);
        evalContext.put("index", itemIndex);

        return evalContext;
    }

    /**
     * Schema-maps a raw execution result using the OutputSchemaMapper.
     * The raw result must contain a "node_type" key to identify the mapper.
     * Returns the schema-mapped result, or the original if no mapper applies.
     */
    @SuppressWarnings("unchecked")
    private Object schemaMappResult(Object rawResult) {
        if (outputSchemaMapper == null || !(rawResult instanceof Map)) {
            return rawResult;
        }

        Map<String, Object> resultMap = (Map<String, Object>) rawResult;
        Object nodeType = resultMap.get("node_type");
        if (nodeType == null) {
            return rawResult;
        }

        try {
            return outputSchemaMapper.transformToDbSchema(resultMap, String.valueOf(nodeType));
        } catch (Exception e) {
            logger.debug("[SplitAggregate] Schema mapping failed for nodeType={}: {}",
                nodeType, e.getMessage());
            return rawResult;
        }
    }

    /**
     * Adds a short key (without prefix) to the eval context.
     * E.g., "core:download_post_image" → also adds "download_post_image".
     */
    private void addShortKey(Map<String, Object> evalContext, String fullKey, Object value) {
        if (fullKey.contains(":")) {
            evalContext.put(fullKey.substring(fullKey.indexOf(":") + 1), value);
        }
    }

    private Object evaluateExpression(String expression, Map<String, Object> context) {
        if (expression == null || expression.isBlank() || templateEngine == null) {
            return null;
        }

        try {
            return templateEngine.resolveWithMap(expression, context);
        } catch (Exception e) {
            logger.warn("[SplitAggregate] Expression evaluation failed: expr={}, error={}",
                expression, e.getMessage());
            return null;
        }
    }

    /**
     * Creates an empty aggregate result when no SplitContext is found.
     */
    private NodeExecutionResult createEmptyAggregateResult(String nodeId, ExecutionContext context) {
        Map<String, Object> output = new HashMap<>();
        output.put("node_type", "AGGREGATE");
        output.put("split_aggregate", false);
        output.put("aggregated_count", 0);
        output.put("item_count", 0);
        output.put("item_index", context.itemIndex());
        output.put("item_id", context.itemId());

        return new NodeExecutionResult(
            nodeId,
            NodeStatus.COMPLETED,
            output,
            Optional.empty(),
            Map.of(),
            0
        );
    }
}
