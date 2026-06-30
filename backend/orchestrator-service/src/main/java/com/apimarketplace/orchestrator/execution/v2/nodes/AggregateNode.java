package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.execution.v2.engine.EvalContextBuilder;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.ServiceRegistry;
import com.apimarketplace.orchestrator.execution.v2.split.SplitContextManager;
import com.apimarketplace.orchestrator.services.TemplateEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

/**
 * Aggregate node - Collect N items into 1 (data transformation).
 *
 * Unlike Merge (which synchronizes streams/branches), Aggregate transforms DATA:
 * - Collects values from multiple items flowing through
 * - Groups them into arrays by field
 * - Outputs a single item with aggregated data
 *
 * Flow:
 * 1. Wait for all items to arrive (from Split or other sources)
 * 2. Extract specified fields from each item
 * 3. Collect values into arrays
 * 4. Output single item with arrays
 *
 * Example:
 * Input: [{name:"Alice"}, {name:"Bob"}, {name:"Charlie"}]
 * Config: fields=[{label:"names", expression:"{{name}}"}]
 * Output: {names: ["Alice", "Bob", "Charlie"], aggregated_count: 3}
 */
public class AggregateNode extends BaseNode {

    private static final Logger logger = LoggerFactory.getLogger(AggregateNode.class);

    private final List<AggregateField> fields;
    private final TemplateEngine templateEngine;

    // Thread-safe storage for collected values across items
    // Key: itemId prefix (batch), Value: Map of field label -> list of values
    private final Map<String, Map<String, List<Object>>> collectedData = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> receivedCounts = new ConcurrentHashMap<>();

    // Test-only seam (null in production = no-op). A concurrency test installs this to
    // deterministically interleave another item's finalize + cleanup at the exact window
    // where the post-cleanup double-finalize race lived: right after this item incremented
    // its received count and BEFORE it reads the expected total / finalize check. Receives
    // the running received count so the test can target one specific lagging item.
    volatile IntConsumer afterCountIncrementedHookForTest;

    // SplitContextManager for closing the split scope after aggregation
    private SplitContextManager splitContextManager;

    public AggregateNode(String nodeId, List<AggregateField> fields, TemplateEngine templateEngine) {
        super(nodeId, NodeType.AGGREGATE);
        this.fields = fields != null ? fields : new ArrayList<>();
        this.templateEngine = templateEngine;
    }

    /**
     * Sets the SplitContextManager for closing the split scope after aggregation.
     * This ensures that nodes after Aggregate execute only once (not N times).
     */
    public void setSplitContextManager(SplitContextManager splitContextManager) {
        this.splitContextManager = splitContextManager;
    }

    /**
     * Accepts services from the registry.
     * AggregateNode needs SplitContextManager for closing split scope after aggregation.
     */
    @Override
    public void acceptServices(ServiceRegistry registry) {
        super.acceptServices(registry);
        this.splitContextManager = registry.getSplitContextManager();
    }

    @Override
    public boolean isAggregateNode() {
        return true;
    }

    @Override
    public boolean canExecute(ExecutionContext context) {
        // Aggregate always executes when called - collection happens in execute()
        return true;
    }

    @Override
    public NodeExecutionResult execute(ExecutionContext context) {
        String batchKey = getBatchKey(context);
        int itemIndex = context.itemIndex();
        int totalItems = getTotalItems(context);

        logger.debug("Aggregate collecting: nodeId={}, itemIndex={}/{}, batchKey={}",
            nodeId, itemIndex, totalItems, batchKey);

        // Initialize storage for this batch
        collectedData.computeIfAbsent(batchKey, k -> new ConcurrentHashMap<>());

        // Extract and store values for each field
        Map<String, Object> evalContext = EvalContextBuilder.buildAggregateEvalContext(context);
        Map<String, List<Object>> batchData = collectedData.get(batchKey);

        for (AggregateField field : fields) {
            Object value = evaluateExpression(field.expression(), evalContext);
            batchData.computeIfAbsent(field.label(), k -> Collections.synchronizedList(new ArrayList<>()));
            batchData.get(field.label()).add(value);
        }

        // Atomic increment-and-get: each item gets a unique count (1..N) from the
        // shared per-batch counter, so exactly one item observes received == totalItems.
        AtomicInteger counter = receivedCounts.computeIfAbsent(batchKey, k -> new AtomicInteger(0));
        int received = counter.incrementAndGet();

        // Test-only interleave point (no-op in production: the field is null). Placed
        // right after the increment and BEFORE the expected total is read, which is the
        // exact window of the historical cleanup race: a test parks a lagging item here,
        // lets the finalizer remove the batch maps, then resumes it.
        IntConsumer hook = afterCountIncrementedHookForTest;
        if (hook != null) {
            hook.accept(received);
        }

        // Use the LOCAL total, NOT a re-read of a per-batch map. The finalizer removes
        // the batch maps during cleanup; a lagging item that incremented before that
        // cleanup but read the expected count after it would get a stale default and
        // wrongly finalize a second time (two "final" results for one batch). totalItems
        // is stable per item in a split batch (every item carries the same item_count).
        int expected = totalItems;

        logger.debug("Aggregate progress: nodeId={}, received={}/{}, batchKey={}",
            nodeId, received, expected, batchKey);

        // Check if we have all items. received is a unique value in 1..N and expected == N,
        // so exactly one item (received == N) passes this guard; >= is defensive only.
        if (received >= expected) {
            // All items collected - produce aggregated output
            Map<String, Object> output = buildAggregatedOutput(batchKey, context);

            // Cleanup
            collectedData.remove(batchKey);
            receivedCounts.remove(batchKey);

            // CRITICAL: Close the SplitContext so downstream nodes execute only once
            closeSplitContext(context);

            logger.info("Aggregate completed: nodeId={}, aggregated_count={}, fields={}",
                nodeId, output.get("aggregated_count"), fields.stream().map(AggregateField::label).toList());

            return NodeExecutionResult.success(nodeId, output);
        }

        // Not all items yet - return COLLECTING status (NOT persisted to DB)
        // The node will be called again for each item
        Map<String, Object> partialOutput = new HashMap<>();
        partialOutput.put("node_type", "AGGREGATE");
        partialOutput.put("status", "collecting");
        partialOutput.put("received", received);
        partialOutput.put("expected", expected);
        partialOutput.put("item_index", itemIndex);
        partialOutput.put("item_id", context.itemId());

        return NodeExecutionResult.collecting(nodeId, partialOutput);
    }

    private String getBatchKey(ExecutionContext context) {
        // Use run ID + epoch as batch key to isolate items per epoch.
        // Without epoch, parallel epochs on the same run would mix items.
        String runId = context.runId();
        int epoch = context.epoch();
        if (runId != null && !runId.isEmpty()) {
            return runId + ":" + epoch;
        }
        // Fallback to a fixed key for single-batch scenarios
        return "default:" + epoch;
    }

    private int getTotalItems(ExecutionContext context) {
        // Try to get total from context (set by Split)
        Map<String, Object> stepOutputs = context.getAllStepOutputs();

        // Look for item_count in any step output
        for (Map.Entry<String, Object> entry : stepOutputs.entrySet()) {
            if (entry.getValue() instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> output = (Map<String, Object>) entry.getValue();
                if (output.containsKey("item_count")) {
                    Object count = output.get("item_count");
                    if (count instanceof Number) {
                        return ((Number) count).intValue();
                    }
                }
            }
        }

        // Default to 1 if not found
        return 1;
    }

    /**
     * Closes the SplitContext so that downstream nodes execute only once.
     * This is CRITICAL for the Split → Aggregate pattern:
     * - Split creates N item contexts on 1 branch
     * - Aggregate collects all N results into 1 output
     * - Downstream nodes should execute 1 time (not N times)
     *
     * Without this, the SplitContext remains open and downstream nodes
     * would be executed N times by SplitAwareNodeExecutor.
     */
    private void closeSplitContext(ExecutionContext context) {
        if (splitContextManager == null) {
            logger.debug("No SplitContextManager available, skipping context closure: nodeId={}", nodeId);
            return;
        }

        String runId = context.runId();
        // Extract workflow item index from itemId (format: "workflowItemIndex.splitItemIndex")
        // context.itemIndex() gives splitItemIndex, but we need workflowItemIndex
        int workflowItemIndex = extractWorkflowItemIndex(context.itemId());

        // Find the Split node ID from step outputs (look for node with item_count)
        String splitNodeId = findSplitNodeId(context);

        if (splitNodeId == null) {
            logger.debug("No Split node found in context, skipping context closure: nodeId={}", nodeId);
            return;
        }

        // Close the SplitContext
        splitContextManager.removeContext(runId, splitNodeId, workflowItemIndex);

        logger.info("Closed SplitContext after aggregation: nodeId={}, splitNodeId={}, workflowItemIndex={}",
            nodeId, splitNodeId, workflowItemIndex);
    }

    /**
     * Extracts the workflow item index from an itemId.
     * itemId format: "workflowItemIndex.splitItemIndex" (e.g., "0.1", "0.2")
     * or just "workflowItemIndex" (e.g., "0", "1") for non-split contexts.
     *
     * @param itemId the item ID
     * @return the workflow item index (defaults to 0 if parsing fails)
     */
    private int extractWorkflowItemIndex(String itemId) {
        if (itemId == null || itemId.isEmpty()) {
            return 0;
        }
        int dotIndex = itemId.indexOf('.');
        if (dotIndex > 0) {
            try {
                return Integer.parseInt(itemId.substring(0, dotIndex));
            } catch (NumberFormatException e) {
                logger.warn("Failed to parse workflow item index from itemId: {}", itemId);
                return 0;
            }
        }
        try {
            return Integer.parseInt(itemId);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Finds the Split node ID by looking for a node with item_count in its output.
     */
    private String findSplitNodeId(ExecutionContext context) {
        Map<String, Object> stepOutputs = context.getAllStepOutputs();

        for (Map.Entry<String, Object> entry : stepOutputs.entrySet()) {
            String nodeKey = entry.getKey();
            if (entry.getValue() instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> output = (Map<String, Object>) entry.getValue();

                // Check for split indicator in the output
                if (output.containsKey("item_count") && output.containsKey("node_type")) {
                    String nodeType = String.valueOf(output.get("node_type"));
                    if ("SPLIT".equals(nodeType)) {
                        return nodeKey;
                    }
                }

                // Also check wrapped output format
                if (output.containsKey("output") && output.get("output") instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> innerOutput = (Map<String, Object>) output.get("output");
                    if (innerOutput.containsKey("item_count") && innerOutput.containsKey("node_type")) {
                        String nodeType = String.valueOf(innerOutput.get("node_type"));
                        if ("SPLIT".equals(nodeType)) {
                            return nodeKey;
                        }
                    }
                }
            }
        }

        return null;
    }

    private Object evaluateExpression(String expression, Map<String, Object> context) {
        if (expression == null || expression.isBlank()) {
            return null;
        }

        try {
            if (templateEngine != null) {
                // Use typed evaluation so pure {{...}} expressions preserve the
                // original value type (Number/Boolean/Map/List), matching what
                // Set node does. resolveWithMap() would stringify everything. (#F1)
                return templateEngine.evaluateTemplateWithMap(expression, context);
            }
            // Fallback: return expression as-is
            return expression;
        } catch (Exception e) {
            logger.warn("Aggregate expression evaluation failed: expr={}, error={}",
                expression, e.getMessage());
            return null;
        }
    }

    private Map<String, Object> buildAggregatedOutput(String batchKey, ExecutionContext context) {
        Map<String, Object> output = new HashMap<>();

        // Build resolved_params snapshot for inspector visibility (resolved values)
        Map<String, Object> resolvedParams = new LinkedHashMap<>();
        for (AggregateField field : fields) {
            resolvedParams.put(field.label(), resolveTemplateString(field.expression(), context));
        }
        output.put("resolved_params", resolvedParams);

        Map<String, List<Object>> batchData = collectedData.get(batchKey);
        if (batchData != null) {
            // Add each field's collected values
            for (AggregateField field : fields) {
                List<Object> values = batchData.getOrDefault(field.label(), List.of());
                output.put(field.label(), new ArrayList<>(values));
            }
        }

        // Add metadata
        AtomicInteger counter = receivedCounts.get(batchKey);
        int count = counter != null ? counter.get() : 0;
        output.put("aggregated_count", count);
        output.put("node_type", "AGGREGATE");
        output.put("item_index", context.itemIndex());
        output.put("item_id", context.itemId());

        return output;
    }

    @Override
    public List<ExecutionNode> getNextNodes(NodeExecutionResult result) {
        // Only continue to successors when aggregation is complete
        Object status = result.output().get("status");
        if ("collecting".equals(status)) {
            // Still collecting - don't proceed to successors yet
            return List.of();
        }
        return successors;
    }

    @Override
    public void onComplete(ExecutionContext context, NodeExecutionResult result) {
        logger.debug("Aggregate node completed: nodeId={}", nodeId);
        // Cleanup batch data on completion (success or failure) to prevent memory leaks
        cleanupBatch(getBatchKey(context));
    }

    /**
     * Removes all tracked state for a given batch key.
     * Called on completion or failure to prevent memory leaks when workflows
     * fail, timeout, or are cancelled before all items arrive.
     */
    public void cleanupBatch(String batchKey) {
        collectedData.remove(batchKey);
        receivedCounts.remove(batchKey);
        logger.debug("Cleaned up batch data: nodeId={}, batchKey={}", nodeId, batchKey);
    }

    /**
     * Removes all tracked state for all batches.
     * Used for complete cleanup on workflow failure/timeout/cancellation.
     */
    public void cleanupAllBatches() {
        collectedData.clear();
        receivedCounts.clear();
        logger.debug("Cleaned up all batch data: nodeId={}", nodeId);
    }

    /**
     * Field definition for aggregation.
     */
    public record AggregateField(String label, String expression) {}

    // Getters
    public List<AggregateField> getFields() {
        return fields;
    }

    // Builder
    public static class Builder {
        private String nodeId;
        private final List<AggregateField> fields = new ArrayList<>();
        private TemplateEngine templateEngine;

        public Builder nodeId(String nodeId) {
            this.nodeId = nodeId;
            return this;
        }

        public Builder templateEngine(TemplateEngine templateEngine) {
            this.templateEngine = templateEngine;
            return this;
        }

        public Builder addField(String label, String expression) {
            this.fields.add(new AggregateField(label, expression));
            return this;
        }

        public Builder fields(List<AggregateField> fields) {
            this.fields.clear();
            this.fields.addAll(fields);
            return this;
        }

        public AggregateNode build() {
            return new AggregateNode(nodeId, fields, templateEngine);
        }
    }

    public static Builder builder() {
        return new Builder();
    }
}
