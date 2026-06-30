package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.domain.workflow.Core;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Data Input node - provides multiple labeled text and/or file inputs to the workflow.
 *
 * Flow:
 * 1. Iterate over items list
 * 2. For each text item, resolve SpEL templates
 * 3. For each file item, pass through as-is
 * 4. Return label-keyed output map: {{core:label.output.prompt}}, {{core:label.output.document}}
 *
 * Usage:
 * - Provide static or dynamic text inputs with user-defined labels
 * - Attach pre-uploaded files for agents or processing steps
 * - Labels make downstream references readable: {{core:my_input.output.prompt}}
 */
public class DataInputNode extends BaseNode {

    private static final Logger logger = LoggerFactory.getLogger(DataInputNode.class);

    private final List<Core.DataInputItem> items;

    public DataInputNode(String nodeId, List<Core.DataInputItem> items) {
        super(nodeId, NodeType.DATA_INPUT);
        this.items = items != null ? List.copyOf(items) : List.of();
    }

    @Override
    public NodeExecutionResult execute(ExecutionContext context) {
        logger.info("Data input node executing: nodeId={}, itemCount={}, itemId={}", nodeId, items.size(), context.itemId());

        Map<String, Object> result = new HashMap<>();

        // Process each item and output under its label key
        for (Core.DataInputItem item : items) {
            String label = item.label();
            if ("file".equals(item.type())) {
                result.put(label, item.file() != null ? item.file() : Map.of());
            } else {
                // text type - resolve SpEL expression
                String resolvedText = resolveExpression(item.text(), context);
                result.put(label, resolvedText != null ? resolvedText : "");
            }
        }

        // Standard metadata
        result.put("node_type", "DATA_INPUT");
        result.put("item_index", context.itemIndex());
        result.put("itemIndex", context.itemIndex());
        result.put("item_id", context.itemId());

        // Persist resolved values as resolved_params for inspector visibility
        Map<String, Object> resolvedParams = new HashMap<>();
        for (Core.DataInputItem item : items) {
            if (item.label() != null) {
                if ("file".equals(item.type())) {
                    resolvedParams.put(item.label(), item.file());
                } else {
                    resolvedParams.put(item.label(), resolveTemplateString(item.text(), context));
                }
            }
        }
        result.put("resolved_params", resolvedParams);

        return NodeExecutionResult.success(nodeId, result);
    }

    private String resolveExpression(String expression, ExecutionContext context) {
        if (expression == null || expression.isBlank()) {
            return null;
        }

        if (templateAdapter != null) {
            try {
                Map<String, Object> toResolve = Map.of("__expr__", expression);
                Map<String, Object> resolved = templateAdapter.resolveTemplates(toResolve, context);
                Object value = resolved.get("__expr__");
                return value != null ? value.toString() : null;
            } catch (Exception e) {
                logger.warn("Failed to resolve expression: {} - {}", expression, e.getMessage());
                return expression;
            }
        }

        return expression;
    }

    public List<Core.DataInputItem> getItems() {
        return items;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String nodeId;
        private List<Core.DataInputItem> items;

        public Builder nodeId(String nodeId) {
            this.nodeId = nodeId;
            return this;
        }

        public Builder items(List<Core.DataInputItem> items) {
            this.items = items;
            return this;
        }

        public DataInputNode build() {
            return new DataInputNode(nodeId, items);
        }
    }
}
