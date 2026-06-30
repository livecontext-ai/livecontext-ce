package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.domain.workflow.Core;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Set node - assign or transform fields on the input data.
 *
 * The most-used no-code field assignment node.
 *
 * Each assignment has:
 *  - name: output key
 *  - value: template string (resolved via templateAdapter)
 *  - type: "string" | "number" | "boolean" | "json" | "auto"
 *
 * Optional flag {@code keepOnlySet}: if true, only the assigned fields are output;
 * if false, the assigned fields are merged onto the upstream input data (assigned fields override).
 */
public class SetNode extends BaseNode {

    private static final Logger logger = LoggerFactory.getLogger(SetNode.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final List<Core.SetFieldAssignment> assignments;
    private final boolean keepOnlySet;
    private final String inputExpression;

    public SetNode(String nodeId, Core.SetConfig config) {
        super(nodeId, NodeType.SET);
        // Note: validation (config != null, non-empty assignments) happens at execute() time,
        // NOT in the constructor. Throwing here would crash the entire execution-tree build,
        // killing every other node in the workflow. Fail-fast at execute() isolates the failure.
        if (config != null) {
            this.assignments = config.assignments() != null ? config.assignments() : List.of();
            this.keepOnlySet = config.keepOnlySet();
            this.inputExpression = config.input();
        } else {
            this.assignments = List.of();
            this.keepOnlySet = false;
            this.inputExpression = null;
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public NodeExecutionResult execute(ExecutionContext context) {
        long startTime = System.currentTimeMillis();
        logger.info("Set node executing: nodeId={}, assignments={}, keepOnlySet={}, inputExpr={}, itemId={}",
            nodeId, assignments.size(), keepOnlySet, inputExpression, context.itemId());

        // Build resolved_params early so every exit path can include it
        Map<String, Object> earlyResolvedParams = new LinkedHashMap<>();
        earlyResolvedParams.put("keep_only_set", keepOnlySet);
        earlyResolvedParams.put("input_expression", inputExpression);
        earlyResolvedParams.put("assignment_count", assignments.size());

        if (assignments.isEmpty()) {
            Map<String, Object> failOutput = Map.of("resolved_params", earlyResolvedParams);
            return NodeExecutionResult.failureWithOutput(nodeId,
                "'assignments' is required and must not be empty. Each assignment is {name, value, type}.",
                failOutput, System.currentTimeMillis() - startTime);
        }

        try {
            // Resolve the (optional) input expression - when not provided, input data is empty
            Map<String, Object> inputData = new LinkedHashMap<>();
            if (inputExpression != null && !inputExpression.isBlank()) {
                if (templateAdapter == null) {
                    Map<String, Object> failOutput = Map.of("resolved_params", earlyResolvedParams);
                    return NodeExecutionResult.failureWithOutput(nodeId, "Template adapter not available",
                        failOutput, System.currentTimeMillis() - startTime);
                }
                Map<String, Object> resolved = templateAdapter.resolveTemplates(
                    Map.of("__input__", inputExpression), context);
                Object resolvedValue = resolved.get("__input__");
                if (resolvedValue instanceof Map<?, ?> map) {
                    inputData.putAll((Map<String, Object>) map);
                }
            }

            // Resolve each assignment value via templateAdapter
            Map<String, Object> resolvedFields = new LinkedHashMap<>();
            for (Core.SetFieldAssignment assignment : assignments) {
                if (assignment.name() == null || assignment.name().isBlank()) {
                    continue;
                }
                Object resolvedValue = assignment.value();
                if (templateAdapter != null && assignment.value() != null) {
                    Map<String, Object> resolved = templateAdapter.resolveTemplates(
                        Map.of("__v__", assignment.value()), context);
                    resolvedValue = resolved.get("__v__");
                }
                Object coerced = coerceType(resolvedValue, assignment.type());
                resolvedFields.put(assignment.name(), coerced);
                if (logger.isInfoEnabled()) {
                    String preview = coerced == null ? "null" : String.valueOf(coerced);
                    if (preview.length() > 120) preview = preview.substring(0, 120) + "...";
                    logger.info("Set resolved field: nodeId={}, name={}, type={}, rawTemplate={}, resolved={}",
                        nodeId, assignment.name(), assignment.type(), assignment.value(), preview);
                }
            }

            // Build the final output object
            Map<String, Object> output;
            if (keepOnlySet) {
                output = new LinkedHashMap<>(resolvedFields);
            } else {
                output = new LinkedHashMap<>(inputData);
                output.putAll(resolvedFields);
            }

            Map<String, Object> result = new HashMap<>();
            result.put("fields", resolvedFields);
            result.put("output", output);
            result.put("keep_only_set", keepOnlySet);
            result.put("count", resolvedFields.size());

            // Persist resolved configuration as resolved_params for inspector "Resolved parameters" panel
            // (mirrors SortNode/FilterNode pattern). Includes the upstream input AND every resolved
            // assignment so the user sees exactly what the node was given and what it produced.
            Map<String, Object> resolvedParams = new LinkedHashMap<>();
            resolvedParams.put("input", inputData);
            resolvedParams.put("keep_only_set", keepOnlySet);
            for (Map.Entry<String, Object> e : resolvedFields.entrySet()) {
                resolvedParams.put(e.getKey(), e.getValue());
            }
            result.put("resolved_params", resolvedParams);

            logger.info("Set completed: nodeId={}, fieldsAssigned={}, inputKeys={}, outputKeys={}, resultKeys={}",
                nodeId, resolvedFields.size(), inputData.keySet(), output.keySet(), result.keySet());
            return successWithMetadata(result, context);

        } catch (Exception e) {
            logger.error("Set execution failed: nodeId={}, error={}", nodeId, e.getMessage(), e);
            Map<String, Object> failOutput = Map.of("resolved_params", earlyResolvedParams);
            return NodeExecutionResult.failureWithOutput(nodeId, e.getMessage(),
                failOutput, System.currentTimeMillis() - startTime);
        }
    }

    /**
     * Coerce a resolved value to the requested type.
     * "auto" leaves the value as-is (templateAdapter already produced a typed value where possible).
     */
    private Object coerceType(Object value, String type) {
        if (value == null || type == null || "auto".equalsIgnoreCase(type)) {
            return value;
        }
        String stringValue = String.valueOf(value);
        try {
            return switch (type.toLowerCase()) {
                case "string" -> stringValue;
                case "number" -> {
                    if (value instanceof Number n) yield n;
                    if (stringValue.contains(".")) yield Double.parseDouble(stringValue);
                    yield Long.parseLong(stringValue);
                }
                case "boolean" -> {
                    if (value instanceof Boolean b) yield b;
                    yield Boolean.parseBoolean(stringValue);
                }
                case "json" -> {
                    if (value instanceof Map || value instanceof List) yield value;
                    yield JSON.readValue(stringValue, Object.class);
                }
                default -> value;
            };
        } catch (Exception e) {
            logger.warn("SetNode: failed to coerce value to type '{}', returning raw. error={}", type, e.getMessage());
            return value;
        }
    }

    public List<Core.SetFieldAssignment> getAssignments() { return assignments; }
    public boolean isKeepOnlySet() { return keepOnlySet; }
    public String getInputExpression() { return inputExpression; }

    public static class Builder {
        private String nodeId;
        private Core.SetConfig config;

        public Builder nodeId(String nodeId) { this.nodeId = nodeId; return this; }
        public Builder setConfig(Core.SetConfig config) { this.config = config; return this; }
        public Builder templateAdapter(Object adapter) { return this; } // injected via acceptServices
        public SetNode build() { return new SetNode(nodeId, config); }
    }

    public static Builder builder() { return new Builder(); }
}
