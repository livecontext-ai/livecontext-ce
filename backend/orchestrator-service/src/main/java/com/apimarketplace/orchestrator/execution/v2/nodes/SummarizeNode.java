package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.domain.workflow.Core;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Summarize node - Aggregates data using operations like sum, avg, count, min, max,
 * countDistinct, concatenate - with optional group-by fields.
 *
 * Flow:
 * 1. Resolve input expression to get items list
 * 2. Group items by groupBy fields (if any)
 * 3. Apply aggregation operations per group
 * 4. Return grouped results
 *
 * Usage:
 * - Calculate totals, averages, counts from datasets
 * - Group data by category and compute per-group statistics
 * - Count distinct values in a field
 * - Concatenate string values from multiple items
 */
public class SummarizeNode extends BaseNode {

    private static final Logger logger = LoggerFactory.getLogger(SummarizeNode.class);

    private final Core.SummarizeConfig config;

    public SummarizeNode(String nodeId, Core.SummarizeConfig config) {
        super(nodeId, NodeType.SUMMARIZE);
        this.config = config != null ? config : new Core.SummarizeConfig(List.of(), List.of(), null);
    }

    @Override
    public NodeExecutionResult execute(ExecutionContext context) {
        long startTime = System.currentTimeMillis();
        logger.info("Summarize node executing: nodeId={}, aggregationCount={}, groupByCount={}, itemId={}",
            nodeId, config.aggregations().size(), config.groupBy().size(), context.itemId());

        // Build resolved_params early so every exit path can include it
        Map<String, Object> earlyInputData = new LinkedHashMap<>();
        earlyInputData.put("input_expression", config.input());
        earlyInputData.put("aggregation_count", config.aggregations().size());
        earlyInputData.put("aggregations", config.aggregations().stream()
                .map(a -> Map.of(
                    "field", a.field() != null ? a.field() : "",
                    "operation", a.operation(),
                    "alias", a.alias() != null ? a.alias() : ""))
                .toList());
        earlyInputData.put("groupBy", config.groupBy());

        // Input is required
        String inputExpr = config.input();
        if (inputExpr == null || inputExpr.isBlank()) {
            Map<String, Object> failOutput = Map.of("resolved_params", earlyInputData);
            return NodeExecutionResult.failureWithOutput(nodeId,
                "Input expression is required. Configure the 'input' field with a reference like {{core:step.output.items}}",
                failOutput, System.currentTimeMillis() - startTime);
        }

        if (templateAdapter == null) {
            Map<String, Object> failOutput = Map.of("resolved_params", earlyInputData);
            return NodeExecutionResult.failureWithOutput(nodeId, "Template adapter not available",
                failOutput, System.currentTimeMillis() - startTime);
        }

        try {
            Map<String, Object> result = new HashMap<>();

            // Resolve input expression
            List<Map<String, Object>> items = resolveInputExpression(context);

            // Group items by groupBy fields
            Map<String, List<Map<String, Object>>> groups = groupItems(items, config.groupBy());

            // Apply aggregations per group
            List<Map<String, Object>> groupResults = new ArrayList<>();
            for (Map.Entry<String, List<Map<String, Object>>> entry : groups.entrySet()) {
                Map<String, Object> groupResult = new HashMap<>();
                groupResult.put("group_key", entry.getKey());

                // Add group-by field values from the first item
                if (!config.groupBy().isEmpty() && !entry.getValue().isEmpty()) {
                    Map<String, Object> firstItem = entry.getValue().get(0);
                    for (String groupField : config.groupBy()) {
                        groupResult.put(groupField, firstItem.get(groupField));
                    }
                }

                // Apply each aggregation
                for (Core.SummarizeAggregation agg : config.aggregations()) {
                    String alias = agg.alias() != null && !agg.alias().isBlank() ? agg.alias()
                        : agg.operation() + "_" + (agg.field() != null ? agg.field() : "all");
                    Object aggregatedValue = applyAggregation(agg, entry.getValue());
                    groupResult.put(alias, aggregatedValue);
                }

                groupResult.put("group_count", entry.getValue().size());
                groupResults.add(groupResult);
            }

            result.put("groups", groupResults);
            result.put("total_groups", groupResults.size());
            result.put("total_items", items.size());
            result.put("aggregation_count", config.aggregations().size());

            // If single group (no groupBy), also flatten results to top level for convenience
            if (config.groupBy().isEmpty() && !groupResults.isEmpty()) {
                Map<String, Object> singleGroup = groupResults.get(0);
                for (Core.SummarizeAggregation agg : config.aggregations()) {
                    String alias = agg.alias() != null && !agg.alias().isBlank() ? agg.alias()
                        : agg.operation() + "_" + (agg.field() != null ? agg.field() : "all");
                    result.put(alias, singleGroup.get(alias));
                }
            }

            // MANDATORY metadata
            result.put("node_type", "SUMMARIZE");
            result.put("item_index", context.itemIndex());
            result.put("itemIndex", context.itemIndex());
            result.put("item_id", context.itemId());
            Map<String, Object> inputData = new LinkedHashMap<>();
            inputData.put("input", items);
            inputData.put("input_count", items.size());
            inputData.put("aggregations", config.aggregations().stream()
                    .map(a -> Map.of(
                        "field", a.field() != null ? a.field() : "",
                        "operation", a.operation(),
                        "alias", a.alias() != null ? a.alias() : ""))
                    .toList());
            inputData.put("groupBy", config.groupBy());
            result.put("resolved_params", inputData);

            logger.info("Summarize completed: nodeId={}, totalGroups={}, totalItems={}",
                nodeId, groupResults.size(), items.size());
            return NodeExecutionResult.success(nodeId, result);

        } catch (Exception e) {
            logger.error("Summarize execution failed: nodeId={}, error={}", nodeId, e.getMessage(), e);
            Map<String, Object> failOutput = Map.of("resolved_params", earlyInputData);
            return NodeExecutionResult.failureWithOutput(nodeId, e.getMessage(),
                failOutput, System.currentTimeMillis() - startTime);
        }
    }

    /**
     * Resolve the explicit input expression via templateAdapter.
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> resolveInputExpression(ExecutionContext context) {
        String inputExpr = config.input();
        Map<String, Object> resolved = templateAdapter.resolveTemplates(
            Map.of("__input__", inputExpr), context);
        Object resolvedValue = resolved.get("__input__");
        if (resolvedValue instanceof List<?> list) {
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map) {
                    result.add((Map<String, Object>) item);
                }
            }
            logger.info("Summarize resolved input expression to {} items, nodeId={}", result.size(), nodeId);
            return result;
        }
        logger.warn("Summarize input expression resolved to non-list value, returning empty. nodeId={}", nodeId);
        return List.of();
    }

    /**
     * Group items by the specified groupBy fields.
     * If no groupBy fields, all items go into a single group with key "__all__".
     */
    private Map<String, List<Map<String, Object>>> groupItems(
            List<Map<String, Object>> items, List<String> groupByFields) {

        if (groupByFields == null || groupByFields.isEmpty()) {
            return Map.of("__all__", items);
        }

        Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (Map<String, Object> item : items) {
            String groupKey = groupByFields.stream()
                .map(field -> String.valueOf(item.getOrDefault(field, "null")))
                .collect(Collectors.joining("|"));

            groups.computeIfAbsent(groupKey, k -> new ArrayList<>()).add(item);
        }

        return groups;
    }

    /**
     * Apply a single aggregation operation to a list of items.
     */
    private Object applyAggregation(Core.SummarizeAggregation agg, List<Map<String, Object>> items) {
        String operation = agg.operation();
        String field = agg.field();

        return switch (operation) {
            case "count" -> items.size();
            case "sum" -> computeSum(items, field);
            case "avg" -> computeAvg(items, field);
            case "min" -> computeMin(items, field);
            case "max" -> computeMax(items, field);
            case "countDistinct" -> computeCountDistinct(items, field);
            case "concatenate" -> computeConcatenate(items, field);
            default -> {
                logger.warn("Unknown aggregation operation: {}", operation);
                yield null;
            }
        };
    }

    private double computeSum(List<Map<String, Object>> items, String field) {
        double sum = 0;
        for (Map<String, Object> item : items) {
            sum += toDouble(item.get(field));
        }
        return sum;
    }

    private double computeAvg(List<Map<String, Object>> items, String field) {
        if (items.isEmpty()) {
            return 0.0;
        }
        return computeSum(items, field) / items.size();
    }

    private Object computeMin(List<Map<String, Object>> items, String field) {
        if (items.isEmpty()) {
            return null;
        }
        // Try numeric comparison first
        boolean allNumeric = items.stream()
            .allMatch(item -> isNumeric(item.get(field)));

        if (allNumeric) {
            return items.stream()
                .mapToDouble(item -> toDouble(item.get(field)))
                .min()
                .orElse(0.0);
        }

        // Fall back to string comparison
        return items.stream()
            .map(item -> String.valueOf(item.getOrDefault(field, "")))
            .min(Comparator.naturalOrder())
            .orElse("");
    }

    private Object computeMax(List<Map<String, Object>> items, String field) {
        if (items.isEmpty()) {
            return null;
        }
        // Try numeric comparison first
        boolean allNumeric = items.stream()
            .allMatch(item -> isNumeric(item.get(field)));

        if (allNumeric) {
            return items.stream()
                .mapToDouble(item -> toDouble(item.get(field)))
                .max()
                .orElse(0.0);
        }

        // Fall back to string comparison
        return items.stream()
            .map(item -> String.valueOf(item.getOrDefault(field, "")))
            .max(Comparator.naturalOrder())
            .orElse("");
    }

    private long computeCountDistinct(List<Map<String, Object>> items, String field) {
        return items.stream()
            .map(item -> item.get(field))
            .filter(Objects::nonNull)
            .map(String::valueOf)
            .distinct()
            .count();
    }

    private String computeConcatenate(List<Map<String, Object>> items, String field) {
        return items.stream()
            .map(item -> item.get(field))
            .filter(Objects::nonNull)
            .map(String::valueOf)
            .collect(Collectors.joining(", "));
    }

    private double toDouble(Object value) {
        if (value == null) {
            return 0.0;
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private boolean isNumeric(Object value) {
        if (value == null) {
            return true; // null treated as 0
        }
        if (value instanceof Number) {
            return true;
        }
        try {
            Double.parseDouble(String.valueOf(value));
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    // Getters
    public Core.SummarizeConfig getConfig() { return config; }

    // Builder
    public static class Builder {
        private String nodeId;
        private Core.SummarizeConfig config;

        public Builder nodeId(String nodeId) { this.nodeId = nodeId; return this; }
        public Builder summarizeConfig(Core.SummarizeConfig config) { this.config = config; return this; }
        public SummarizeNode build() { return new SummarizeNode(nodeId, config); }
    }

    public static Builder builder() { return new Builder(); }
}
