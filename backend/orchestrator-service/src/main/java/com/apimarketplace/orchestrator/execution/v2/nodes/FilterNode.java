package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.domain.workflow.Core;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Filter node - Keeps only items matching specified conditions.
 *
 * Flow:
 * 1. Get input data from context step outputs
 * 2. Evaluate each condition against the data
 * 3. In AND mode, all conditions must match; in OR mode, any condition must match
 * 4. Output includes whether the item matched and the original data if it did
 *
 * Usage:
 * - Filter rows from a datasource based on field values
 * - Keep only items meeting certain criteria
 * - Conditional pass-through based on data content
 */
public class FilterNode extends BaseNode {

    private static final Logger logger = LoggerFactory.getLogger(FilterNode.class);

    private final List<Core.FilterCondition> conditions;
    private final String mode; // "and" or "or"
    private final String inputExpression; // Required: SpEL expression like {{core:run_code.output.items}}

    public FilterNode(String nodeId, List<Core.FilterCondition> conditions, String mode, String inputExpression) {
        super(nodeId, NodeType.FILTER);
        this.conditions = conditions != null ? conditions : List.of();
        this.mode = mode != null ? mode : "and";
        this.inputExpression = inputExpression;
    }

    public FilterNode(String nodeId, List<Core.FilterCondition> conditions, String mode) {
        this(nodeId, conditions, mode, null);
    }

    @Override
    @SuppressWarnings("unchecked")
    public NodeExecutionResult execute(ExecutionContext context) {
        logger.info("Filter node executing: nodeId={}, conditionCount={}, mode={}, inputExpr={}, itemId={}",
            nodeId, conditions.size(), mode, inputExpression, context.itemId());
        long startTime = System.currentTimeMillis();

        // Build resolved_params early so it's available in all result paths
        Map<String, Object> earlyInputData = new LinkedHashMap<>();
        earlyInputData.put("input", inputExpression);
        earlyInputData.put("conditions", conditions.stream()
                .map(c -> Map.of(
                    "field", c.field() != null ? c.field() : "",
                    "operator", c.operator(),
                    "value", String.valueOf(c.value())))
                .toList());
        earlyInputData.put("mode", mode);

        // Input is required
        if (inputExpression == null || inputExpression.isBlank()) {
            Map<String, Object> failureOutput = new LinkedHashMap<>();
            failureOutput.put("node_type", "FILTER");
            failureOutput.put("item_index", context.itemIndex());
            failureOutput.put("itemIndex", context.itemIndex());
            failureOutput.put("item_id", context.itemId());
            failureOutput.put("resolved_params", earlyInputData);
            return NodeExecutionResult.failureWithOutput(nodeId,
                "Input expression is required. Configure the 'input' field with a reference like {{core:step.output.items}}",
                failureOutput, System.currentTimeMillis() - startTime);
        }

        try {
            Map<String, Object> result = new HashMap<>();

            // Resolve the input expression
            List<Object> resolvedItems;
            if (templateAdapter == null) {
                Map<String, Object> failureOutput = new LinkedHashMap<>();
                failureOutput.put("node_type", "FILTER");
                failureOutput.put("item_index", context.itemIndex());
                failureOutput.put("itemIndex", context.itemIndex());
                failureOutput.put("item_id", context.itemId());
                failureOutput.put("resolved_params", earlyInputData);
                return NodeExecutionResult.failureWithOutput(nodeId, "Template adapter not available",
                    failureOutput, System.currentTimeMillis() - startTime);
            }
            Map<String, Object> toResolve = Map.of("__input__", inputExpression);
            Map<String, Object> resolved = templateAdapter.resolveTemplates(toResolve, context);
            Object resolvedValue = resolved.get("__input__");

            if (resolvedValue instanceof List<?> list) {
                resolvedItems = new ArrayList<>((List<Object>) list);
            } else if (resolvedValue instanceof Map) {
                resolvedItems = new ArrayList<>(List.of(resolvedValue));
            } else {
                resolvedItems = List.of();
            }

            // Filter each item in the resolved list
            List<Map<String, Object>> filteredItems = new ArrayList<>();
            List<Map<String, Object>> rejectedItems = new ArrayList<>();
            for (Object item : resolvedItems) {
                if (item instanceof Map) {
                    Map<String, Object> itemMap = (Map<String, Object>) item;
                    boolean matches;
                    if ("or".equalsIgnoreCase(mode)) {
                        matches = conditions.isEmpty() || conditions.stream().anyMatch(c -> evaluateCondition(c, itemMap));
                    } else {
                        matches = conditions.stream().allMatch(c -> evaluateCondition(c, itemMap));
                    }
                    if (matches) {
                        filteredItems.add(itemMap);
                    } else {
                        rejectedItems.add(itemMap);
                    }
                }
            }

            result.put("matched", !filteredItems.isEmpty());
            result.put("items", filteredItems);
            result.put("rejected_items", rejectedItems);
            result.put("count", filteredItems.size());
            result.put("rejected_count", rejectedItems.size());
            result.put("original_count", resolvedItems.size());
            result.put("filter_mode", mode);
            result.put("conditions_evaluated", conditions.size());

            // MANDATORY metadata
            result.put("node_type", "FILTER");
            result.put("item_index", context.itemIndex());
            result.put("itemIndex", context.itemIndex());
            result.put("item_id", context.itemId());
            Map<String, Object> inputData = new LinkedHashMap<>();
            inputData.put("input", resolvedItems);
            inputData.put("input_count", resolvedItems.size());
            inputData.put("conditions", conditions.stream()
                    .map(c -> Map.of(
                        "field", c.field() != null ? c.field() : "",
                        "operator", c.operator(),
                        "value", String.valueOf(c.value())))
                    .toList());
            inputData.put("mode", mode);
            result.put("resolved_params", inputData);

            logger.info("Filter completed: nodeId={}, filtered={}, rejected={}",
                nodeId, filteredItems.size(), rejectedItems.size());
            return NodeExecutionResult.success(nodeId, result);

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            logger.error("Filter execution failed: nodeId={}, error={}", nodeId, e.getMessage(), e);
            Map<String, Object> failureOutput = new LinkedHashMap<>();
            failureOutput.put("node_type", "FILTER");
            failureOutput.put("item_index", context.itemIndex());
            failureOutput.put("itemIndex", context.itemIndex());
            failureOutput.put("item_id", context.itemId());
            failureOutput.put("resolved_params", earlyInputData);
            return NodeExecutionResult.failureWithOutput(nodeId, e.getMessage(), failureOutput, duration);
        }
    }

    private boolean evaluateCondition(Core.FilterCondition condition, Map<String, Object> data) {
        String field = condition.field();
        String operator = condition.operator();
        String compareValue = condition.value();

        Object fieldValue = data.get(field);
        String fieldStr = fieldValue != null ? String.valueOf(fieldValue) : "";

        return switch (operator) {
            case "equals" -> fieldStr.equals(compareValue);
            case "notEquals" -> !fieldStr.equals(compareValue);
            case "contains" -> fieldStr.contains(compareValue != null ? compareValue : "");
            case "notContains" -> !fieldStr.contains(compareValue != null ? compareValue : "");
            case "greaterThan" -> compareNumeric(fieldStr, compareValue) > 0;
            case "lessThan" -> compareNumeric(fieldStr, compareValue) < 0;
            case "greaterOrEqual" -> compareNumeric(fieldStr, compareValue) >= 0;
            case "lessOrEqual" -> compareNumeric(fieldStr, compareValue) <= 0;
            case "startsWith" -> fieldStr.startsWith(compareValue != null ? compareValue : "");
            case "endsWith" -> fieldStr.endsWith(compareValue != null ? compareValue : "");
            case "isEmpty" -> fieldValue == null || fieldStr.isEmpty();
            case "isNotEmpty" -> fieldValue != null && !fieldStr.isEmpty();
            default -> {
                logger.warn("Unknown filter operator: {}", operator);
                yield false;
            }
        };
    }

    private int compareNumeric(String a, String b) {
        try {
            return Double.compare(Double.parseDouble(a), Double.parseDouble(b != null ? b : "0"));
        } catch (NumberFormatException e) {
            return a.compareTo(b != null ? b : "");
        }
    }

    // Getters
    public List<Core.FilterCondition> getConditions() { return conditions; }
    public String getMode() { return mode; }
    public String getInputExpression() { return inputExpression; }

    // Builder
    public static class Builder {
        private String nodeId;
        private List<Core.FilterCondition> conditions;
        private String mode;
        private String inputExpression;

        public Builder nodeId(String nodeId) { this.nodeId = nodeId; return this; }
        public Builder conditions(List<Core.FilterCondition> conditions) { this.conditions = conditions; return this; }
        public Builder mode(String mode) { this.mode = mode; return this; }
        public Builder inputExpression(String inputExpression) { this.inputExpression = inputExpression; return this; }
        public Builder filterConfig(Core.FilterConfig config) {
            if (config != null) {
                this.conditions = config.conditions();
                this.mode = config.mode();
            }
            return this;
        }
        public Builder templateAdapter(Object adapter) { return this; } // No-op, injected via acceptServices
        public FilterNode build() { return new FilterNode(nodeId, conditions, mode, inputExpression); }
    }

    public static Builder builder() { return new Builder(); }
}
