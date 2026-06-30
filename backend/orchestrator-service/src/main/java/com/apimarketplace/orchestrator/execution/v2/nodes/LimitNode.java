package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.domain.workflow.Core;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Limit node - Passes through only the first or last N items with optional offset.
 *
 * Flow:
 * 1. Get input data from predecessor output
 * 2. Apply offset (skip items)
 * 3. Apply count limit from first or last
 * 4. Return limited subset
 *
 * Usage:
 * - Paginate results
 * - Take top/bottom N items
 * - Skip and take for pagination patterns
 */
public class LimitNode extends BaseNode {

    private static final Logger logger = LoggerFactory.getLogger(LimitNode.class);

    private final int count;
    private final String from;
    private final int offset;
    private final String inputExpression; // Required: SpEL expression like {{core:run_code.output.items}}

    public LimitNode(String nodeId, int count, String from, int offset, String inputExpression) {
        super(nodeId, NodeType.LIMIT);
        this.count = Math.max(1, count);
        this.from = from != null ? from : "first";
        this.offset = Math.max(0, offset);
        this.inputExpression = inputExpression;
    }

    public LimitNode(String nodeId, int count, String from, int offset) {
        this(nodeId, count, from, offset, null);
    }

    public LimitNode(String nodeId, Core.LimitConfig config) {
        this(nodeId,
            config != null ? config.count() : 10,
            config != null ? config.from() : "first",
            config != null ? config.offset() : 0,
            null);
    }

    @Override
    @SuppressWarnings("unchecked")
    public NodeExecutionResult execute(ExecutionContext context) {
        long startTime = System.currentTimeMillis();
        logger.info("Limit node executing: nodeId={}, count={}, from={}, offset={}, inputExpr={}, itemId={}",
            nodeId, count, from, offset, inputExpression, context.itemId());

        // Build resolved_params early so every exit path can include it
        Map<String, Object> earlyInputData = new LinkedHashMap<>();
        earlyInputData.put("count", count);
        earlyInputData.put("from", from);
        earlyInputData.put("offset", offset);
        earlyInputData.put("input_expression", inputExpression);

        // Input is required
        if (inputExpression == null || inputExpression.isBlank()) {
            Map<String, Object> failOutput = buildFailureOutput(earlyInputData);
            return NodeExecutionResult.failureWithOutput(nodeId,
                "Input expression is required. Configure the 'input' field with a reference like {{core:step.output.items}}",
                failOutput, System.currentTimeMillis() - startTime);
        }

        try {
            // Resolve the input expression
            if (templateAdapter == null) {
                Map<String, Object> failOutput = buildFailureOutput(earlyInputData);
                return NodeExecutionResult.failureWithOutput(nodeId, "Template adapter not available",
                    failOutput, System.currentTimeMillis() - startTime);
            }
            Map<String, Object> toResolve = Map.of("__input__", inputExpression);
            Map<String, Object> resolved = templateAdapter.resolveTemplates(toResolve, context);
            Object resolvedValue = resolved.get("__input__");

            List<Object> inputItems;
            if (resolvedValue instanceof List<?> list) {
                inputItems = new ArrayList<>((List<Object>) list);
            } else if (resolvedValue instanceof Map) {
                inputItems = new ArrayList<>(List.of(resolvedValue));
            } else {
                inputItems = new ArrayList<>();
            }

            // Apply offset
            List<Object> afterOffset;
            if (offset >= inputItems.size()) {
                afterOffset = List.of();
            } else {
                afterOffset = inputItems.subList(offset, inputItems.size());
            }

            // Apply limit from first or last
            List<Object> limitedItems;
            if ("last".equalsIgnoreCase(from)) {
                int startIndex = Math.max(0, afterOffset.size() - count);
                limitedItems = new ArrayList<>(afterOffset.subList(startIndex, afterOffset.size()));
            } else {
                // "first" (default)
                int endIndex = Math.min(count, afterOffset.size());
                limitedItems = new ArrayList<>(afterOffset.subList(0, endIndex));
            }

            // Build result
            Map<String, Object> result = new HashMap<>();
            result.put("items", limitedItems);
            result.put("count", limitedItems.size());
            result.put("original_count", inputItems.size());

            // Include mandatory metadata
            result.put("node_type", "LIMIT");
            result.put("item_index", context.itemIndex());
            result.put("itemIndex", context.itemIndex());
            result.put("item_id", context.itemId());

            // Persist config as resolved_params for inspector visibility
            Map<String, Object> resolvedParams = new LinkedHashMap<>();
            resolvedParams.put("input", inputItems);
            resolvedParams.put("input_count", inputItems.size());
            resolvedParams.put("count", count);
            resolvedParams.put("from", from);
            resolvedParams.put("offset", offset);
            result.put("resolved_params", resolvedParams);

            // config mirrors resolved_params so runtime shape == persisted shape == doc shape
            result.put("config", resolvedParams);

            logger.info("✅ Limit completed: nodeId={}, from={}, offset={}, count={}, input={}, output={}",
                nodeId, from, offset, count, inputItems.size(), limitedItems.size());
            return NodeExecutionResult.success(nodeId, result);

        } catch (Exception e) {
            logger.error("❌ Limit execution failed: nodeId={}, error={}", nodeId, e.getMessage(), e);
            Map<String, Object> failOutput = buildFailureOutput(earlyInputData);
            return NodeExecutionResult.failureWithOutput(nodeId, e.getMessage(),
                failOutput, System.currentTimeMillis() - startTime);
        }
    }

    /**
     * Builds a failure output that contains the canonical declared keys with safe defaults.
     * Ensures persisted JSONB always has the documented shape even when execution fails early.
     */
    private Map<String, Object> buildFailureOutput(Map<String, Object> earlyInputData) {
        Map<String, Object> failOutput = new HashMap<>();
        failOutput.put("items", List.of());
        failOutput.put("count", 0);
        failOutput.put("original_count", 0);
        failOutput.put("config", earlyInputData);
        failOutput.put("resolved_params", earlyInputData);
        return failOutput;
    }

    public int getCount() {
        return count;
    }

    public String getFrom() {
        return from;
    }

    public int getOffset() {
        return offset;
    }

    public String getInputExpression() {
        return inputExpression;
    }

    // ==================== Builder ====================

    public static class Builder {
        private String nodeId;
        private int count = 10;
        private String from = "first";
        private int offset = 0;
        private String inputExpression;

        public Builder nodeId(String nodeId) {
            this.nodeId = nodeId;
            return this;
        }

        public Builder count(int count) {
            this.count = count;
            return this;
        }

        public Builder from(String from) {
            this.from = from;
            return this;
        }

        public Builder offset(int offset) {
            this.offset = offset;
            return this;
        }

        public Builder inputExpression(String inputExpression) {
            this.inputExpression = inputExpression;
            return this;
        }

        public Builder limitConfig(Core.LimitConfig config) {
            if (config != null) {
                this.count = config.count();
                this.from = config.from();
                this.offset = config.offset();
            }
            return this;
        }

        public LimitNode build() {
            return new LimitNode(nodeId, count, from, offset, inputExpression);
        }
    }

    public static Builder builder() {
        return new Builder();
    }
}
