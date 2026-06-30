package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.domain.workflow.Core;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Sort node - Reorders items by one or more fields with ascending/descending direction.
 *
 * Flow:
 * 1. Get input data from previous step output
 * 2. Sort items by specified fields and directions
 * 3. Return sorted result
 *
 * Usage:
 * - Reorder rows by a column (e.g., sort by price ascending)
 * - Multi-field sorting (e.g., sort by category then by name)
 * - Reverse ordering with descending direction
 */
public class SortNode extends BaseNode {

    private static final Logger logger = LoggerFactory.getLogger(SortNode.class);

    private final List<Core.SortField> fields;
    private final String inputExpression; // Required: SpEL expression like {{core:run_code.output.items}}

    public SortNode(String nodeId, List<Core.SortField> fields, String inputExpression) {
        super(nodeId, NodeType.SORT);
        this.fields = fields != null ? fields : List.of();
        this.inputExpression = inputExpression;
    }

    public SortNode(String nodeId, List<Core.SortField> fields) {
        this(nodeId, fields, null);
    }

    @Override
    public NodeExecutionResult execute(ExecutionContext context) {
        logger.info("Sort node executing: nodeId={}, fieldCount={}, inputExpr={}, itemId={}",
            nodeId, fields.size(), inputExpression, context.itemId());
        long startTime = System.currentTimeMillis();

        // Build resolved_params early so it's available in all result paths
        Map<String, Object> earlyInputData = new LinkedHashMap<>();
        earlyInputData.put("input", inputExpression);
        earlyInputData.put("fields", fields.stream()
            .map(sf -> Map.of("field", sf.field(), "direction", sf.direction()))
            .toList());

        // Input is required
        if (inputExpression == null || inputExpression.isBlank()) {
            Map<String, Object> failureOutput = new LinkedHashMap<>();
            failureOutput.put("node_type", "SORT");
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
            if (templateAdapter == null) {
                Map<String, Object> failureOutput = new LinkedHashMap<>();
                failureOutput.put("node_type", "SORT");
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

            List<Map<String, Object>> items = convertToMapList(resolvedValue);

            if (items == null || items.isEmpty()) {
                logger.info("🔀 Sort node: no items to sort, returning empty list. nodeId={}", nodeId);
                result.put("sorted_items", List.of());
                result.put("count", 0);
            } else {
                // Build comparator from sort fields
                Comparator<Map<String, Object>> comparator = buildComparator(fields);
                List<Map<String, Object>> sortedItems = items.stream()
                    .sorted(comparator)
                    .collect(Collectors.toList());

                result.put("sorted_items", sortedItems);
                result.put("count", sortedItems.size());
                logger.info("🔀 Sort completed: nodeId={}, itemCount={}, fields={}",
                    nodeId, sortedItems.size(),
                    fields.stream().map(f -> f.field() + ":" + f.direction()).collect(Collectors.joining(", ")));
            }

            // Include item context for proper persistence (like TransformNode does)
            result.put("node_type", "SORT");
            result.put("item_index", context.itemIndex());
            result.put("itemIndex", context.itemIndex());
            result.put("item_id", context.itemId());

            // Persist sort configuration as resolved_params for inspector visibility
            Map<String, Object> resolvedParams = new LinkedHashMap<>();
            resolvedParams.put("input", items != null ? items : List.of());
            resolvedParams.put("input_count", items != null ? items.size() : 0);
            resolvedParams.put("fields", fields.stream()
                .map(sf -> Map.of("field", sf.field(), "direction", sf.direction()))
                .toList());
            result.put("resolved_params", resolvedParams);

            logger.info("✅ Sort completed: nodeId={}, outputKeys={}", nodeId, result.keySet());
            return NodeExecutionResult.success(nodeId, result);

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            logger.error("❌ Sort execution failed: nodeId={}, error={}", nodeId, e.getMessage(), e);
            Map<String, Object> failureOutput = new LinkedHashMap<>();
            failureOutput.put("node_type", "SORT");
            failureOutput.put("item_index", context.itemIndex());
            failureOutput.put("itemIndex", context.itemIndex());
            failureOutput.put("item_id", context.itemId());
            failureOutput.put("resolved_params", earlyInputData);
            return NodeExecutionResult.failureWithOutput(nodeId, e.getMessage(), failureOutput, duration);
        }
    }

    /**
     * Convert resolved value to a list of maps.
     * Supports List of Maps or a single Map (wraps in a list).
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> convertToMapList(Object value) {
        if (value instanceof List<?> list) {
            List<Map<String, Object>> items = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map) {
                    items.add((Map<String, Object>) item);
                }
            }
            return items;
        }
        if (value instanceof Map) {
            return new ArrayList<>(List.of((Map<String, Object>) value));
        }
        return List.of();
    }

    /**
     * Build a comparator from sort fields configuration.
     * Supports multi-field sorting with mixed ascending/descending directions.
     * Handles null values (nulls last), numeric comparison, and string comparison.
     */
    static Comparator<Map<String, Object>> buildComparator(List<Core.SortField> sortFields) {
        if (sortFields == null || sortFields.isEmpty()) {
            return (a, b) -> 0; // No sorting
        }

        Comparator<Map<String, Object>> comparator = null;

        for (Core.SortField sortField : sortFields) {
            Comparator<Map<String, Object>> fieldComparator = (a, b) -> {
                Object valA = a.get(sortField.field());
                Object valB = b.get(sortField.field());
                int cmp = compareValues(valA, valB);
                return "desc".equalsIgnoreCase(sortField.direction()) ? -cmp : cmp;
            };

            comparator = (comparator == null) ? fieldComparator : comparator.thenComparing(fieldComparator);
        }

        return comparator;
    }

    /**
     * Compare two values with type-aware logic.
     * Handles null, Number, and String types.
     * Nulls are sorted last.
     */
    @SuppressWarnings("unchecked")
    static int compareValues(Object a, Object b) {
        if (a == null && b == null) return 0;
        if (a == null) return 1;  // nulls last
        if (b == null) return -1; // nulls last

        // Both are numbers - compare numerically
        if (a instanceof Number numA && b instanceof Number numB) {
            return Double.compare(numA.doubleValue(), numB.doubleValue());
        }

        // Try numeric comparison if both are string representations of numbers
        if (a instanceof String strA && b instanceof String strB) {
            try {
                double dA = Double.parseDouble(strA);
                double dB = Double.parseDouble(strB);
                return Double.compare(dA, dB);
            } catch (NumberFormatException ignored) {
                // Fall through to string comparison
            }
        }

        // Both are Comparable - use natural ordering
        if (a instanceof Comparable && a.getClass().equals(b.getClass())) {
            return ((Comparable<Object>) a).compareTo(b);
        }

        // Fallback to string comparison
        return String.valueOf(a).compareTo(String.valueOf(b));
    }

    public List<Core.SortField> getFields() {
        return fields;
    }

    public String getInputExpression() {
        return inputExpression;
    }

    public static class Builder {
        private String nodeId;
        private List<Core.SortField> fields;
        private String inputExpression;

        public Builder nodeId(String nodeId) {
            this.nodeId = nodeId;
            return this;
        }

        public Builder fields(List<Core.SortField> fields) {
            this.fields = fields;
            return this;
        }

        public Builder inputExpression(String inputExpression) {
            this.inputExpression = inputExpression;
            return this;
        }

        public Builder sortConfig(Core.SortConfig config) {
            if (config != null) {
                this.fields = config.fields();
            }
            return this;
        }

        public Builder templateAdapter(Object adapter) { return this; } // No-op, injected via acceptServices

        public SortNode build() {
            return new SortNode(nodeId, fields, inputExpression);
        }
    }

    public static Builder builder() {
        return new Builder();
    }
}
