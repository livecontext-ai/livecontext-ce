package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.domain.workflow.Step;
import com.apimarketplace.orchestrator.execution.v2.constants.ExecutionMetadataKeys;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.template.V2TemplateAdapter;
import com.apimarketplace.orchestrator.services.TemplateEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Find node - Queries a data table and returns matching rows as an items[] array.
 *
 * <p>This is a simple collection node - it does NOT split/spawn parallel contexts.
 * To iterate per-row, connect a Split node after this node.
 *
 * <p>Query strategy:
 * <ul>
 *   <li>Strategy 1: Execute CRUD read via ToolsGateway (production)</li>
 *   <li>Strategy 2 (fallback): Evaluate "list" expression from params (tests)</li>
 *   <li>maxItems caps the total number of rows returned (safety limit, default 100)</li>
 * </ul>
 *
 * <p>Output: { items: [...], item_count, total_before_limit, has_more, max_items, find_id }
 */
public class FindNode extends BaseNode {

    private static final Logger logger = LoggerFactory.getLogger(FindNode.class);

    private final Step stepConfig;
    private final String listExpression;
    private final int maxItems;
    private final TemplateEngine templateEngine;

    /**
     * Hard ceiling on {@code maxItems} mirroring the downstream
     * {@link com.apimarketplace.datasource.crud.service.CrudExecutorService MAX_READ_LIMIT}
     * (10_000). A request above this is clamped here with a WARN so the cap surfaces in
     * orchestrator logs rather than only at the CRUD layer.
     */
    static final int FIND_NODE_HARD_CAP = 10_000;

    public FindNode(
            String nodeId,
            Step stepConfig,
            String listExpression,
            int maxItems,
            TemplateEngine templateEngine) {
        super(nodeId, NodeType.FIND);
        this.stepConfig = stepConfig;
        this.listExpression = listExpression;
        // Default kept at 100 (historical contract). Hard ceiling at FIND_NODE_HARD_CAP so a
        // user-provided value above 10_000 surfaces a clamp WARN here rather than silently
        // tripping the CrudExecutorService cap further down.
        int requested = maxItems > 0 ? maxItems : 100;
        this.maxItems = Math.min(requested, FIND_NODE_HARD_CAP);
        if (this.maxItems < requested) {
            logger.warn("[FindNode] maxItems clamped {} → {} (FIND_NODE_HARD_CAP) for nodeId={}",
                requested, this.maxItems, nodeId);
        }
        this.templateEngine = templateEngine;
    }

    @Override
    public NodeExecutionResult execute(ExecutionContext context) {
        long startTime = System.currentTimeMillis();

        logger.info("[FindNode] Executing: nodeId={}, dataSourceId={}, maxItems={}, hasToolsGateway={}",
            nodeId, stepConfig.dataSourceId(), maxItems, toolsGateway != null);

        List<Object> items;
        Map<String, Object> resolvedInputData = null;

        // Strategy 1: Execute CRUD read via ToolsGateway
        if (toolsGateway != null && stepConfig.dataSourceId() != null) {
            resolvedInputData = prepareCrudInput(context);
            items = executeCrudRead(context, startTime);
            if (items == null) {
                logger.warn("[FindNode] CRUD read failed, trying list fallback: nodeId={}", nodeId);
                items = evaluateListFallback(context);
            } else if (items.isEmpty() && listExpression != null && !listExpression.isBlank()) {
                logger.info("[FindNode] CRUD returned 0 rows, trying list fallback: nodeId={}", nodeId);
                List<Object> fallbackItems = evaluateListFallback(context);
                if (fallbackItems != null && !fallbackItems.isEmpty()) {
                    items = fallbackItems;
                }
            }
        } else {
            // Strategy 2: Fallback - evaluate list expression
            items = evaluateListFallback(context);
        }

        if (items == null) {
            logger.error("[FindNode] Failed to get items: nodeId={}", nodeId);
            long failDuration = System.currentTimeMillis() - startTime;
            Map<String, Object> failOutput = new HashMap<>();
            failOutput.put("node_type", "FIND");
            failOutput.put("find_id", nodeId);
            failOutput.put("item_index", context.itemIndex());
            failOutput.put("itemIndex", context.itemIndex());
            failOutput.put("item_id", context.itemId());
            failOutput.put("resolved_params", resolvedInputData != null ? resolvedInputData : Map.of());
            failOutput.put("error", "Failed to retrieve items");
            return NodeExecutionResult.failureWithOutput(nodeId, "Failed to retrieve items",
                failOutput, failDuration);
        }

        // Apply maxItems limit (safety cap)
        int totalBeforeLimit = items.size();
        if (items.size() > maxItems) {
            logger.info("[FindNode] Limiting items from {} to {} (maxItems cap)", items.size(), maxItems);
            items = items.subList(0, maxItems);
        }

        long duration = System.currentTimeMillis() - startTime;

        // Build output - simple collection, no split metadata
        Map<String, Object> output = new HashMap<>();
        output.put("node_type", "FIND");
        output.put("find_id", nodeId);
        output.put(ExecutionMetadataKeys.ITEM_COUNT, items.size());
        output.put("total_before_limit", totalBeforeLimit);
        output.put("max_items", maxItems);
        output.put("items", items);
        output.put("has_more", totalBeforeLimit > maxItems);

        if (resolvedInputData != null) {
            output.put("resolved_params", resolvedInputData);
        }

        output.put("item_index", context.itemIndex());
        output.put("itemIndex", context.itemIndex());
        output.put("item_id", context.itemId());

        if (items.isEmpty()) {
            output.put("exit_reason", "empty_result");
            logger.info("[FindNode] No items found: nodeId={}, duration={}ms", nodeId, duration);
        } else {
            output.put("exit_reason", "items_found");
            logger.info("[FindNode] Found {} items (total={}): nodeId={}, duration={}ms",
                items.size(), totalBeforeLimit, nodeId, duration);
        }

        return NodeExecutionResult.success(nodeId, output, duration);
    }

    @SuppressWarnings("unchecked")
    private List<Object> executeCrudRead(ExecutionContext context, long startTime) {
        try {
            Map<String, Object> inputData = prepareCrudInput(context);

            String toolId;
            if (stepConfig.isCrudStep()) {
                String crudOp = stepConfig.getCrudOperation();
                toolId = "crud/" + crudOp;
            } else {
                toolId = stepConfig.id();
            }
            if (toolId == null || toolId.isBlank()) {
                logger.error("[FindNode] Step has no tool ID: nodeId={}", nodeId);
                return null;
            }
            logger.info("[FindNode] Resolved toolId={} for nodeId={}", toolId, nodeId);

            com.apimarketplace.orchestrator.domain.ToolRef toolRef =
                new com.apimarketplace.orchestrator.domain.ToolRef(toolId, 1);

            String tenantId = context.tenantId();

            // Pass __workflowRunId__ so CatalogBillingDispatcher skips
            // catalog-tier billing (workflow already bills WORKFLOW_NODE +
            // markup via StepCompletionOrchestrator). Same contract as
            // StepNode - runId alone is enough for the bypass check.
            Map<String, Object> billingIdentifiers = new HashMap<>();
            if (context.runId() != null) {
                billingIdentifiers.put("__workflowRunId__", context.runId());
            }
            // Propagate workflow author's credential toggle - see StepNode for
            // the rationale. Catalog uses these fields strictly (no fallback)
            // when present, applies fallback-if-priced when absent.
            if (stepConfig.usesPlatformCredential() && stepConfig.platformCredentialId() != null) {
                billingIdentifiers.put("__credentialSource__", "platform");
                billingIdentifiers.put("__platformCredentialId__", stepConfig.platformCredentialId());
            } else {
                billingIdentifiers.put("__credentialSource__", "user");
                if (stepConfig.selectedCredentialId() != null) {
                    billingIdentifiers.put("__selectedCredentialId__", stepConfig.selectedCredentialId());
                }
            }
            com.apimarketplace.orchestrator.services.interfaces.ExecutionResult result =
                toolsGateway.executeTool(toolRef, inputData, tenantId, billingIdentifiers);

            if (!result.isSuccess()) {
                logger.error("[FindNode] CRUD read failed: nodeId={}, error={}",
                    nodeId, result.getErrorMessage());
                return null;
            }

            Map<String, Object> resultOutput = result.output();
            if (resultOutput == null) return List.of();

            Object rows = resultOutput.get("rows");
            if (rows == null) rows = resultOutput.get("data");
            return convertToList(rows);

        } catch (Exception e) {
            logger.error("[FindNode] CRUD read exception: nodeId={}, error={}", nodeId, e.getMessage(), e);
            return null;
        }
    }

    private Map<String, Object> prepareCrudInput(ExecutionContext context) {
        Map<String, Object> rawInput = new HashMap<>();
        if (stepConfig.params() != null) rawInput.putAll(stepConfig.params());
        if (stepConfig.dataSourceId() != null) rawInput.put("dataSourceId", stepConfig.dataSourceId());

        if (stepConfig.crud() != null) {
            Map<String, Object> crudMap = new HashMap<>();
            if (stepConfig.crud().where() != null) {
                Map<String, Object> whereMap = new HashMap<>();
                whereMap.put("column", stepConfig.crud().where().column());
                whereMap.put("operator", stepConfig.crud().where().operator());
                whereMap.put("value", stepConfig.crud().where().value());
                crudMap.put("where", whereMap);
            }
            if (stepConfig.crud().limit() != null) crudMap.put("limit", stepConfig.crud().limit());
            if (stepConfig.crud().offset() != null) crudMap.put("offset", stepConfig.crud().offset());
            if (stepConfig.crud().similarity() != null) {
                Map<String, Object> similarityMap = new HashMap<>();
                similarityMap.put("column", stepConfig.crud().similarity().column());
                similarityMap.put("queryVector", stepConfig.crud().similarity().queryVector());
                if (stepConfig.crud().similarity().topK() != null) {
                    similarityMap.put("topK", stepConfig.crud().similarity().topK());
                }
                if (stepConfig.crud().similarity().threshold() != null) {
                    similarityMap.put("threshold", stepConfig.crud().similarity().threshold());
                }
                crudMap.put("similarity", similarityMap);
            }
            rawInput.put("crud", crudMap);
        }

        if (templateAdapter != null && !rawInput.isEmpty()) {
            try {
                return templateAdapter.resolveTemplates(rawInput, context);
            } catch (Exception e) {
                logger.warn("[FindNode] Template resolution failed: nodeId={}, error={}", nodeId, e.getMessage());
            }
        }

        rawInput.put("trigger", context.triggerData());
        return rawInput;
    }

    private List<Object> evaluateListFallback(ExecutionContext context) {
        if (listExpression == null || listExpression.isBlank()) {
            return List.of();
        }
        try {
            if (templateAdapter != null) {
                Object result = templateAdapter.evaluateTemplate(listExpression, context);
                return convertToList(result);
            }
            if (templateEngine != null) {
                V2TemplateAdapter adapter = new V2TemplateAdapter(templateEngine);
                Object result = adapter.evaluateTemplate(listExpression, context);
                return convertToList(result);
            }
            return null;
        } catch (Exception e) {
            logger.error("[FindNode] List fallback evaluation failed: nodeId={}, error={}", nodeId, e.getMessage(), e);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private List<Object> convertToList(Object result) {
        if (result == null) return List.of();
        if (result instanceof List) return (List<Object>) result;
        if (result instanceof Collection) return new ArrayList<>((Collection<?>) result);
        if (result.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(result);
            List<Object> list = new ArrayList<>(length);
            for (int i = 0; i < length; i++) list.add(java.lang.reflect.Array.get(result, i));
            return list;
        }
        return List.of(result);
    }

    // ===== Next nodes (failure check) =====

    @Override
    public List<ExecutionNode> getNextNodes(NodeExecutionResult result) {
        if (result != null && result.isFailure()) {
            logger.info("[FindNode] Execution failed, returning no successors: nodeId={}", nodeId);
            return List.of();
        }
        return super.getNextNodes(result);
    }

    // ===== Identification (no split behavior) =====

    @Override
    public boolean isFindNode() {
        return true;
    }

    public Step getStepConfig() { return stepConfig; }
    public int getMaxItems() { return maxItems; }
}
