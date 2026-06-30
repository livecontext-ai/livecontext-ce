package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.domain.workflow.Core;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Transform node - Applies data mappings/transformations.
 *
 * Flow:
 * 1. Evaluate SpEL expressions for each mapping
 * 2. Build canonical {transformed: {...}, evaluations: [...]} output
 * 3. Continue to successors
 *
 * Output shape (runtime == persisted == doc):
 * <pre>
 *   {
 *     "transformed": { label1: value1, label2: value2, ... },
 *     "evaluations": [ { field, expression, resolved_expression, value }, ... ]
 *   }
 * </pre>
 *
 * Downstream templates resolve via the canonical path:
 * {@code {{core:my_transform.output.transformed.label1}}}.
 */
public class TransformNode extends BaseNode {

    private static final Logger logger = LoggerFactory.getLogger(TransformNode.class);

    private final List<Core.TransformMapping> mappings;

    public TransformNode(String nodeId, List<Core.TransformMapping> mappings) {
        super(nodeId, NodeType.TRANSFORM);
        this.mappings = mappings != null ? mappings : List.of();
    }

    @Override
    public NodeExecutionResult execute(ExecutionContext context) {
        long startTime = System.currentTimeMillis();
        logger.info("🔄 Transform node executing: nodeId={}, mappingCount={}, itemId={}",
            nodeId, mappings.size(), context.itemId());

        // Build resolved_params early so it's available in all result paths
        Map<String, Object> earlyInputData = new LinkedHashMap<>();
        for (Core.TransformMapping mapping : mappings) {
            if (mapping.label() != null) {
                earlyInputData.put(mapping.label(), mapping.expression());
            }
        }

        try {
            Map<String, Object> transformed = new LinkedHashMap<>();
            List<Map<String, Object>> evaluations = new ArrayList<>();

            for (Core.TransformMapping mapping : mappings) {
                String label = mapping.label();
                String expression = mapping.expression();

                if (label == null || label.isBlank()) {
                    logger.warn("⚠️ Transform mapping has no label, skipping");
                    continue;
                }

                Map<String, Object> evaluation = new LinkedHashMap<>();
                evaluation.put("field", label);
                evaluation.put("expression", expression);

                if (expression == null || expression.isBlank()) {
                    logger.warn("⚠️ Transform mapping '{}' has no expression, setting null", label);
                    transformed.put(label, null);
                    evaluation.put("resolved_expression", expression);
                    evaluation.put("value", null);
                    evaluations.add(evaluation);
                    continue;
                }

                try {
                    Object value;
                    if (templateAdapter != null) {
                        Map<String, Object> toResolve = Map.of("__expr__", expression);
                        Map<String, Object> resolved = templateAdapter.resolveTemplates(toResolve, context);
                        value = resolved.get("__expr__");
                    } else {
                        value = expression;
                        logger.warn("⚠️ No templateAdapter available for transform: nodeId={}", nodeId);
                    }

                    transformed.put(label, value);
                    evaluation.put("resolved_expression", expression);
                    evaluation.put("value", value);
                    evaluations.add(evaluation);
                    logger.debug("🔄 Transform mapping applied: {} = {} -> {}", label, expression, value);

                } catch (Exception e) {
                    logger.warn("⚠️ Transform mapping '{}' failed: {} - error: {}",
                        label, expression, e.getMessage());
                    transformed.put(label, null);
                    evaluation.put("resolved_expression", expression);
                    evaluation.put("value", null);
                    evaluation.put("error", e.getMessage());
                    evaluations.add(evaluation);
                }
            }

            // Canonical shape (runtime == persisted == doc)
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("transformed", transformed);
            result.put("evaluations", evaluations);

            // Engine envelope keys (stripped at persistence by GenericOutputSchemaMapper.ENGINE_ENVELOPE_KEYS)
            result.put("node_type", "TRANSFORM");
            result.put("item_index", context.itemIndex());
            result.put("itemIndex", context.itemIndex());
            result.put("item_id", context.itemId());

            // resolved_params for inspector visibility: resolved values per label, fallback to expression on null
            Map<String, Object> resolvedParams = new LinkedHashMap<>();
            for (Core.TransformMapping mapping : mappings) {
                if (mapping.label() != null && !mapping.label().isBlank()) {
                    Object resolvedValue = transformed.get(mapping.label());
                    resolvedParams.put(mapping.label(),
                        resolvedValue != null ? resolvedValue : mapping.expression());
                }
            }
            result.put("resolved_params", resolvedParams);

            logger.info("✅ Transform completed: nodeId={}, transformedKeys={}, evaluationCount={}",
                nodeId, transformed.keySet(), evaluations.size());
            return NodeExecutionResult.success(nodeId, result);

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            logger.error("❌ Transform execution failed: nodeId={}, error={}", nodeId, e.getMessage(), e);
            Map<String, Object> failureOutput = buildFailureOutput(earlyInputData, context);
            return NodeExecutionResult.failureWithOutput(nodeId, e.getMessage(), failureOutput, duration);
        }
    }

    /**
     * Builds a failure output that contains the canonical declared keys with safe defaults.
     * Ensures persisted JSONB always has the documented shape even when execution fails early.
     */
    private Map<String, Object> buildFailureOutput(Map<String, Object> earlyInputData, ExecutionContext context) {
        Map<String, Object> failureOutput = new LinkedHashMap<>();
        failureOutput.put("transformed", new HashMap<>());
        failureOutput.put("evaluations", List.of());
        failureOutput.put("node_type", "TRANSFORM");
        failureOutput.put("item_index", context.itemIndex());
        failureOutput.put("itemIndex", context.itemIndex());
        failureOutput.put("item_id", context.itemId());
        failureOutput.put("resolved_params", earlyInputData);
        return failureOutput;
    }

    public List<Core.TransformMapping> getMappings() {
        return mappings;
    }

    public static class Builder {
        private String nodeId;
        private List<Core.TransformMapping> mappings;

        public Builder nodeId(String nodeId) {
            this.nodeId = nodeId;
            return this;
        }

        public Builder mappings(List<Core.TransformMapping> mappings) {
            this.mappings = mappings;
            return this;
        }

        public TransformNode build() {
            return new TransformNode(nodeId, mappings);
        }
    }

    public static Builder builder() {
        return new Builder();
    }
}
