package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.domain.workflow.Core;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * RemoveDuplicates node - Removes duplicate items based on specified fields.
 *
 * Flow:
 * 1. Get input data from previous step output
 * 2. Build dedup key from specified fields for each item
 * 3. Remove duplicates keeping first or last occurrence
 * 4. Continue to successors
 *
 * Usage:
 * - Deduplicate rows by email field
 * - Remove duplicate records by composite key (name + city)
 * - Keep first or last occurrence of duplicates
 */
public class RemoveDuplicatesNode extends BaseNode {

    private static final Logger logger = LoggerFactory.getLogger(RemoveDuplicatesNode.class);

    private final List<String> fields;
    private final String keep;
    private final String inputExpression;

    public RemoveDuplicatesNode(String nodeId, List<String> fields, String keep, String inputExpression) {
        super(nodeId, NodeType.REMOVE_DUPLICATES);
        this.fields = fields != null ? List.copyOf(fields) : List.of();
        this.keep = keep != null ? keep : "first";
        this.inputExpression = inputExpression;
    }

    @Override
    public NodeExecutionResult execute(ExecutionContext context) {
        logger.info("RemoveDuplicates node executing: nodeId={}, fields={}, keep={}, itemId={}",
            nodeId, fields, keep, context.itemId());
        long startTime = System.currentTimeMillis();

        // Build resolved_params early so it's available in all result paths
        Map<String, Object> earlyInputData = new LinkedHashMap<>();
        earlyInputData.put("input", inputExpression);
        earlyInputData.put("fields", fields);
        earlyInputData.put("keep", keep);

        // Input is required
        if (inputExpression == null || inputExpression.isBlank()) {
            Map<String, Object> failureOutput = new LinkedHashMap<>();
            failureOutput.put("node_type", "REMOVE_DUPLICATES");
            failureOutput.put("item_index", context.itemIndex());
            failureOutput.put("itemIndex", context.itemIndex());
            failureOutput.put("item_id", context.itemId());
            failureOutput.put("resolved_params", earlyInputData);
            return NodeExecutionResult.failureWithOutput(nodeId,
                "Input expression is required. Configure the 'input' field with a reference like {{core:step.output.items}}",
                failureOutput, System.currentTimeMillis() - startTime);
        }

        // Resolve input expression
        List<Map<String, Object>> items;
        try {
            if (templateAdapter == null) {
                Map<String, Object> failureOutput = new LinkedHashMap<>();
                failureOutput.put("node_type", "REMOVE_DUPLICATES");
                failureOutput.put("item_index", context.itemIndex());
                failureOutput.put("itemIndex", context.itemIndex());
                failureOutput.put("item_id", context.itemId());
                failureOutput.put("resolved_params", earlyInputData);
                return NodeExecutionResult.failureWithOutput(nodeId, "Template adapter not available",
                    failureOutput, System.currentTimeMillis() - startTime);
            }
            items = resolveInputExpression(context);
        } catch (Exception e) {
            Map<String, Object> failureOutput = new LinkedHashMap<>();
            failureOutput.put("node_type", "REMOVE_DUPLICATES");
            failureOutput.put("item_index", context.itemIndex());
            failureOutput.put("itemIndex", context.itemIndex());
            failureOutput.put("item_id", context.itemId());
            failureOutput.put("resolved_params", earlyInputData);
            return NodeExecutionResult.failureWithOutput(nodeId, "Failed to resolve input: " + e.getMessage(),
                failureOutput, System.currentTimeMillis() - startTime);
        }

        if (items.isEmpty()) {
            logger.info("RemoveDuplicates: input expression resolved to empty, nodeId={}", nodeId);
            Map<String, Object> result = new HashMap<>();
            result.put("items", List.of());
            result.put("original_count", 0);
            result.put("deduplicated_count", 0);
            result.put("removed_count", 0);
            addMetadata(result, context, List.of());
            return NodeExecutionResult.success(nodeId, result);
        }

        // Deduplicate items
        List<Map<String, Object>> deduplicated = deduplicateItems(items);

        Map<String, Object> result = new HashMap<>();
        result.put("items", deduplicated);
        result.put("original_count", items.size());
        result.put("deduplicated_count", deduplicated.size());
        result.put("removed_count", items.size() - deduplicated.size());
        addMetadata(result, context, items);

        logger.info("RemoveDuplicates completed: nodeId={}, original={}, deduplicated={}, removed={}",
            nodeId, items.size(), deduplicated.size(), items.size() - deduplicated.size());
        return NodeExecutionResult.success(nodeId, result);
    }

    /**
     * Deduplicate items based on configured fields and keep strategy.
     */
    private List<Map<String, Object>> deduplicateItems(List<Map<String, Object>> items) {
        if ("last".equalsIgnoreCase(keep)) {
            return deduplicateKeepLast(items);
        }
        return deduplicateKeepFirst(items);
    }

    private List<Map<String, Object>> deduplicateKeepFirst(List<Map<String, Object>> items) {
        Set<String> seen = new LinkedHashSet<>();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> item : items) {
            String key = buildDedupKey(item);
            if (seen.add(key)) {
                result.add(item);
            }
        }
        return result;
    }

    private List<Map<String, Object>> deduplicateKeepLast(List<Map<String, Object>> items) {
        // Iterate in reverse to find last occurrences, then reverse back
        Map<String, Map<String, Object>> lastSeen = new LinkedHashMap<>();
        for (Map<String, Object> item : items) {
            String key = buildDedupKey(item);
            lastSeen.put(key, item);
        }
        return new ArrayList<>(lastSeen.values());
    }

    /**
     * Build a dedup key from the specified fields.
     * If no fields are specified, use the entire item as the key.
     */
    private String buildDedupKey(Map<String, Object> item) {
        if (fields.isEmpty()) {
            // Compare all fields
            return item.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("|"));
        }
        return fields.stream()
            .map(field -> field + "=" + item.get(field))
            .collect(Collectors.joining("|"));
    }

    /**
     * Resolve the explicit input expression via templateAdapter.
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> resolveInputExpression(ExecutionContext context) {
        Map<String, Object> resolved = templateAdapter.resolveTemplates(
            Map.of("__input__", inputExpression), context);
        Object resolvedValue = resolved.get("__input__");
        if (resolvedValue instanceof List<?> list) {
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map) {
                    result.add((Map<String, Object>) item);
                }
            }
            logger.info("RemoveDuplicates resolved input expression to {} items, nodeId={}", result.size(), nodeId);
            return result;
        }
        logger.warn("RemoveDuplicates input expression resolved to non-list value, returning empty. nodeId={}", nodeId);
        return List.of();
    }

    private void addMetadata(Map<String, Object> result, ExecutionContext context, List<Map<String, Object>> resolvedItems) {
        result.put("node_type", "REMOVE_DUPLICATES");
        result.put("item_index", context.itemIndex());
        result.put("itemIndex", context.itemIndex());
        result.put("item_id", context.itemId());
        Map<String, Object> inputData = new LinkedHashMap<>();
        inputData.put("input", resolvedItems != null ? resolvedItems : List.of());
        inputData.put("input_count", resolvedItems != null ? resolvedItems.size() : 0);
        inputData.put("fields", fields);
        inputData.put("keep", keep);
        result.put("resolved_params", inputData);
    }

    public List<String> getFields() {
        return fields;
    }

    public String getKeep() {
        return keep;
    }

    public String getInputExpression() {
        return inputExpression;
    }

    // ==================== Builder ====================

    public static class Builder {
        private String nodeId;
        private List<String> fields;
        private String keep;
        private String inputExpression;

        public Builder nodeId(String nodeId) {
            this.nodeId = nodeId;
            return this;
        }

        public Builder fields(List<String> fields) {
            this.fields = fields;
            return this;
        }

        public Builder keep(String keep) {
            this.keep = keep;
            return this;
        }

        public Builder inputExpression(String inputExpression) {
            this.inputExpression = inputExpression;
            return this;
        }

        public Builder removeDuplicatesConfig(Core.RemoveDuplicatesConfig config) {
            if (config != null) {
                this.fields = config.fields();
                this.keep = config.keep();
                this.inputExpression = config.input();
            }
            return this;
        }

        public RemoveDuplicatesNode build() {
            return new RemoveDuplicatesNode(nodeId, fields, keep, inputExpression);
        }
    }

    public static Builder builder() {
        return new Builder();
    }
}
