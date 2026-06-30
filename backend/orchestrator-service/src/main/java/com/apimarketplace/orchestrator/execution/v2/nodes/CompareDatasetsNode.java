package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.domain.workflow.Core;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * CompareDatasets node - Compares two datasets and finds matched, only-in-A, and only-in-B items.
 *
 * Flow:
 * 1. Resolve inputA and inputB expressions via templateAdapter to get step output keys
 * 2. Get List of Map datasetA and datasetB from context stepOutputs
 * 3. Build a key for each item by extracting matchFields values (or all fields if matchFields is empty)
 * 4. Compare: find items in both (matched), only in A, only in B
 * 5. Return results based on returnMatched, returnOnlyA, returnOnlyB flags
 *
 * Usage:
 * - Compare customer lists from two sources to find common customers
 * - Detect new or removed items between snapshots
 * - Find overlapping records between datasets
 */
public class CompareDatasetsNode extends BaseNode {

    private static final Logger logger = LoggerFactory.getLogger(CompareDatasetsNode.class);

    private final Core.CompareDatasetsConfig compareDatasetsConfig;

    public CompareDatasetsNode(String nodeId, Core.CompareDatasetsConfig config) {
        super(nodeId, NodeType.COMPARE_DATASETS);
        this.compareDatasetsConfig = config;
    }

    @Override
    public NodeExecutionResult execute(ExecutionContext context) {
        logger.info("CompareDatasets node executing: nodeId={}, itemId={}",
            nodeId, context.itemId());

        // Captured outside the try so failure paths still surface the resolved inputs
        // to the inspector "Resolved parameters" panel.
        Map<String, Object> inputData = new LinkedHashMap<>();

        try {
            // Handle null config gracefully
            Core.CompareDatasetsConfig config = compareDatasetsConfig;
            if (config == null) {
                config = new Core.CompareDatasetsConfig(null, null, List.of(), true, true, true);
            }

            // Resolve inputA and inputB expressions
            Object resolvedInputA = resolveExpression(config.inputA(), context);
            Object resolvedInputB = resolveExpression(config.inputB(), context);

            // Get datasets from step outputs
            List<Map<String, Object>> datasetA = extractDataset(resolvedInputA, context);
            List<Map<String, Object>> datasetB = extractDataset(resolvedInputB, context);

            List<String> matchFields = config.matchFields() != null ? config.matchFields() : List.of();

            // Build key maps using LinkedHashMap for deterministic ordering
            Map<String, Map<String, Object>> keyMapA = buildKeyMap(datasetA, matchFields);
            Map<String, Map<String, Object>> keyMapB = buildKeyMap(datasetB, matchFields);

            // Compare datasets
            List<Map<String, Object>> matched = new ArrayList<>();
            List<Map<String, Object>> onlyInA = new ArrayList<>();
            List<Map<String, Object>> onlyInB = new ArrayList<>();

            // Find matched and only-in-A
            for (Map.Entry<String, Map<String, Object>> entry : keyMapA.entrySet()) {
                if (keyMapB.containsKey(entry.getKey())) {
                    matched.add(entry.getValue()); // Use A's version
                } else {
                    onlyInA.add(entry.getValue());
                }
            }

            // Find only-in-B
            for (Map.Entry<String, Map<String, Object>> entry : keyMapB.entrySet()) {
                if (!keyMapA.containsKey(entry.getKey())) {
                    onlyInB.add(entry.getValue());
                }
            }

            // Build result based on return flags
            Map<String, Object> result = new LinkedHashMap<>();

            if (config.returnMatched()) {
                result.put("matched", matched);
            } else {
                result.put("matched", List.of());
            }

            if (config.returnOnlyA()) {
                result.put("onlyInA", onlyInA);
            } else {
                result.put("onlyInA", List.of());
            }

            if (config.returnOnlyB()) {
                result.put("onlyInB", onlyInB);
            } else {
                result.put("onlyInB", List.of());
            }

            result.put("matchedCount", config.returnMatched() ? matched.size() : 0);
            result.put("onlyInACount", config.returnOnlyA() ? onlyInA.size() : 0);
            result.put("onlyInBCount", config.returnOnlyB() ? onlyInB.size() : 0);
            result.put("totalA", datasetA.size());
            result.put("totalB", datasetB.size());
            result.put("matchFields", matchFields);
            result.put("success", true);

            // MANDATORY metadata
            result.put("node_type", "COMPARE_DATASETS");
            result.put("item_index", context.itemIndex());
            result.put("itemIndex", context.itemIndex());
            result.put("item_id", context.itemId());
            inputData.put("datasetA_count", datasetA.size());
            inputData.put("datasetB_count", datasetB.size());
            inputData.put("datasetA", datasetA);
            inputData.put("datasetB", datasetB);
            inputData.put("matchFields", matchFields);
            inputData.put("returnMatched", config.returnMatched());
            inputData.put("returnOnlyA", config.returnOnlyA());
            inputData.put("returnOnlyB", config.returnOnlyB());
            result.put("resolved_params", inputData);

            logger.info("CompareDatasets completed: nodeId={}, matched={}, onlyInA={}, onlyInB={}, totalA={}, totalB={}",
                nodeId, matched.size(), onlyInA.size(), onlyInB.size(), datasetA.size(), datasetB.size());
            return NodeExecutionResult.success(nodeId, result);

        } catch (Exception e) {
            logger.error("CompareDatasets execution failed: nodeId={}, error={}", nodeId, e.getMessage(), e);
            Map<String, Object> failOutput = new HashMap<>();
            failOutput.put("node_type", "COMPARE_DATASETS");
            failOutput.put("item_index", context.itemIndex());
            failOutput.put("itemIndex", context.itemIndex());
            failOutput.put("item_id", context.itemId());
            failOutput.put("resolved_params", inputData);
            failOutput.put("error", e.getMessage());
            return NodeExecutionResult.failureWithOutput(nodeId, e.getMessage(), failOutput, 0L);
        }
    }

    /**
     * Build a key map from items. Key is constructed by concatenating field values with "|" separator.
     * Uses LinkedHashMap to preserve insertion order for deterministic results.
     * If matchFields is empty, all fields are used (sorted by key for deterministic ordering).
     */
    private Map<String, Map<String, Object>> buildKeyMap(List<Map<String, Object>> items, List<String> matchFields) {
        Map<String, Map<String, Object>> keyMap = new LinkedHashMap<>();
        for (Map<String, Object> item : items) {
            String key = buildKey(item, matchFields);
            keyMap.putIfAbsent(key, item); // Keep first occurrence
        }
        return keyMap;
    }

    /**
     * Build a comparison key for an item.
     * If matchFields is empty, use all fields sorted by key name.
     * Concatenate field values as strings with "|" separator.
     */
    private String buildKey(Map<String, Object> item, List<String> matchFields) {
        if (matchFields.isEmpty()) {
            // Use all fields, sorted by key for deterministic ordering
            return item.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> String.valueOf(e.getValue()))
                .collect(Collectors.joining("|"));
        }
        return matchFields.stream()
            .map(field -> String.valueOf(item.get(field)))
            .collect(Collectors.joining("|"));
    }

    /**
     * Extract a dataset (list of maps) from context step outputs.
     * The resolved expression points to a step output key.
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractDataset(Object resolvedInput, ExecutionContext context) {
        if (resolvedInput == null) {
            return List.of();
        }

        if (resolvedInput instanceof List<?> || resolvedInput instanceof Map<?, ?>) {
            return extractListFromObject(resolvedInput);
        }

        String resolvedInputKey = String.valueOf(resolvedInput);
        if (resolvedInputKey.isBlank()) {
            return List.of();
        }

        Map<String, Object> stepOutputs = context.stepOutputs();
        if (stepOutputs == null) {
            return List.of();
        }

        // Try direct lookup in step outputs
        Object data = stepOutputs.get(resolvedInputKey);
        if (data != null) {
            return extractListFromObject(data);
        }

        // Try searching nested outputs (step outputs are wrapped as {output: {...}})
        for (Object value : stepOutputs.values()) {
            if (value instanceof Map<?, ?> map) {
                Object output = map.get("output");
                if (output instanceof Map<?, ?> outputMap) {
                    Object nested = outputMap.get(resolvedInputKey);
                    if (nested != null) {
                        return extractListFromObject(nested);
                    }
                }
            }
        }

        return List.of();
    }

    /**
     * Extract a list of maps from an object.
     * Supports List of Maps directly, or a Map containing a list.
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractListFromObject(Object data) {
        if (data instanceof List<?> list) {
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map) {
                    result.add((Map<String, Object>) item);
                }
            }
            return result;
        }
        if (data instanceof Map<?, ?> map) {
            // Search for list-like values inside the map
            for (String key : List.of("items", "rows", "data", "results", "records", "output")) {
                Object nested = map.get(key);
                if (nested instanceof List<?> list && !list.isEmpty()) {
                    List<Map<String, Object>> result = new ArrayList<>();
                    for (Object item : list) {
                        if (item instanceof Map) {
                            result.add((Map<String, Object>) item);
                        }
                    }
                    return result;
                }
            }
        }
        return List.of();
    }

    /**
     * Resolve a SpEL expression using the template adapter.
     */
    private Object resolveExpression(String expression, ExecutionContext context) {
        if (expression == null || expression.isBlank()) {
            return null;
        }

        if (templateAdapter != null) {
            try {
                Map<String, Object> toResolve = Map.of("__expr__", expression);
                Map<String, Object> resolved = templateAdapter.resolveTemplates(toResolve, context);
                return resolved.getOrDefault("__expr__", expression);
            } catch (Exception e) {
                logger.warn("Failed to resolve expression '{}': {}", expression, e.getMessage());
                return expression;
            }
        }

        return expression;
    }

    // Getters
    public Core.CompareDatasetsConfig getCompareDatasetsConfig() {
        return compareDatasetsConfig;
    }

    // ==================== Builder ====================

    public static class Builder {
        private String nodeId;
        private Core.CompareDatasetsConfig config;

        public Builder nodeId(String nodeId) {
            this.nodeId = nodeId;
            return this;
        }

        public Builder config(Core.CompareDatasetsConfig config) {
            this.config = config;
            return this;
        }

        public Builder compareDatasetsConfig(Core.CompareDatasetsConfig config) {
            this.config = config;
            return this;
        }

        public CompareDatasetsNode build() {
            return new CompareDatasetsNode(nodeId, config);
        }
    }

    public static Builder builder() {
        return new Builder();
    }
}
