package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.domain.workflow.Step;
import com.apimarketplace.orchestrator.execution.v2.constants.ExecutionMetadataKeys;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.template.V2TemplateAdapter;
import com.apimarketplace.orchestrator.services.TemplateEngine;
import com.apimarketplace.orchestrator.services.template.ReportedParams;
import com.apimarketplace.orchestrator.services.template.ResolvedValuePreview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.apimarketplace.orchestrator.services.failure.UserActionableFailure;

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
        // The row cap of this execution: a templated crud.limit resolved, not the default 100
        // the builder gave the node when the parser could not hold the template in an Integer.
        int maxItems = effectiveMaxItems(context);

        logger.info("[FindNode] Executing: nodeId={}, dataSourceId={}, maxItems={}, hasToolsGateway={}",
            nodeId, stepConfig.dataSourceId(), maxItems, toolsGateway != null);

        // A table step cannot carry a run-time account selector today: this node is
        // built only from plan.getTables(), and WorkflowPlanParser.parseTables uses the
        // 8-arg Step constructor, so the field is structurally null here.
        //
        // Enforced rather than asserted in a comment, because the failure mode if that
        // ever changes is the worst one available: the selection would fail, applyTo
        // would throw, the catch below turns it into null, and the caller reads null as
        // "try the list fallback" - so a step whose account could not be chosen comes
        // back GREEN with items. Two lines here make that impossible instead of
        // unlikely.
        if (stepConfig.hasCredentialSelector()) {
            String error = "Step '" + stepConfig.label() + "' is a table step and cannot choose its "
                + "account at run time: a table reads a datasource and authenticates against no "
                + "provider. Remove the account expression from it.";
            logger.error("[FindNode] {}", error);
            Map<String, Object> failOutput = new HashMap<>();
            failOutput.put("node_type", "FIND");
            failOutput.put("find_id", nodeId);
            failOutput.put("item_index", context.itemIndex());
            failOutput.put("itemIndex", context.itemIndex());
            failOutput.put("item_id", context.itemId());
            failOutput.put("error", error);
            return NodeExecutionResult.failureWithOutput(
                nodeId, error, failOutput, System.currentTimeMillis() - startTime);
        }

        List<Object> items;
        // Seeded with the node's OWN configuration, so it survives every path. It used
        // to start null and stay null on the list-fallback strategy, which reported an
        // empty map - and `listExpression` / `maxItems` were never reported at all,
        // even though they are what a find returning nothing is diagnosed from.
        //
        // `list` is the expression as the author wrote it. It used to be
        // resolveTemplateString(listExpression, context): a SECOND resolution of the
        // expression this node also evaluates for real, through the resolver that coerces
        // every value to a String - so a list of rows was reported as "[{id=1}, {id=2}]"
        // while the node's own items[] held the typed array, and a reference pointing at
        // nothing was reported as an empty string where the evaluation reads null. That
        // is the same defect AggregateNode removed from its own field reporting. What the
        // expression evaluated to is reported under `listResolved` instead, taken from the
        // evaluation that actually produced the items.
        Map<String, Object> resolvedInputData = new java.util.LinkedHashMap<>();
        boolean hasListExpression = listExpression != null && !listExpression.isBlank();
        if (hasListExpression) {
            // Seeded here for ORDER only - both values are written again below, after the
            // CRUD echo, which overwrites this key. A LinkedHashMap keeps a re-put key in
            // its original slot, so "Items" and "Items (resolved)" stay adjacent whichever
            // strategy ran, and if the map ever overflows the report budget they are the
            // pair that survives together. (Order is NOT what the panel renders: the row is
            // persisted to a jsonb column, which does not preserve key order. It decides
            // which entries survive truncation, and nothing else.)
            resolvedInputData.put("list", listExpression);
            resolvedInputData.put("listResolved", null);
        }
        if (maxItems > 0) {
            resolvedInputData.put("maxItems", maxItems);
        }

        // Strategy 1: Execute CRUD read via ToolsGateway
        ListFallback fallback = ListFallback.notEvaluated();
        if (toolsGateway != null && stepConfig.dataSourceId() != null) {
            // Bounded and masked on the way into the report, not into the query: a
            // similarity search carries a whole query vector, an IN-list carries whatever
            // the author matched on, and when template resolution fails this map falls back
            // to the ENTIRE trigger payload. All of it was copied onto the step row as-is.
            resolvedInputData.putAll(ReportedParams.forReport(
                CrudDeferredScalars.reportable(prepareCrudInput(context), stepConfig.crud())));
            items = executeCrudRead(context, startTime);
            if (items == null) {
                logger.warn("[FindNode] CRUD read failed, trying list fallback: nodeId={}", nodeId);
                fallback = evaluateListFallback(context);
                items = fallback.items();
            } else if (items.isEmpty() && hasListExpression) {
                logger.info("[FindNode] CRUD returned 0 rows, trying list fallback: nodeId={}", nodeId);
                fallback = evaluateListFallback(context);
                List<Object> fallbackItems = fallback.items();
                if (fallbackItems != null && !fallbackItems.isEmpty()) {
                    items = fallbackItems;
                }
            }
        } else {
            // Strategy 2: Fallback - evaluate list expression
            fallback = evaluateListFallback(context);
            items = fallback.items();
        }

        // Fills the slots reserved above, LAST, because `prepareCrudInput` above echoes
        // the step's whole `params` map into this one - and `list` LIVES in `params`
        // (ExecutionNodeFactory reads listExpression from params.list). So the CRUD
        // strategy overwrote the expression with a template-resolved copy of itself: the
        // panel showed the resolved rows under `list` beside "(not evaluated)" under
        // `listResolved`, two contradictory statements about one setting, and put the
        // whole resolved collection back onto the persisted row. Re-putting here is what
        // makes "`list` is the expression" true on the path that actually ships.
        //
        // A find whose table returned rows never looks at `list`, and reporting a value
        // for it would credit the rows to an expression that had no part in producing
        // them; the sentinel says which of the two strategies the reader is looking at.
        if (hasListExpression) {
            resolvedInputData.put("list", listExpression);
            resolvedInputData.put("listResolved", fallback.description());
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
            // Through the gate HERE, after the CRUD echo and after `list`/`listResolved` are
            // re-put over it: gating earlier would be undone by those re-puts, which is the
            // ordering ConvertToFileNode had to fix for the same reason. Without it a find
            // row had no map budget at all - `list` is an author expression with no length
            // limit, and this row is written per item - while its twin SplitParamsReport
            // documents that exact reason for gating the same key.
            output.put("resolved_params", ReportedParams.forReport(resolvedInputData));
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
            // Analytics attribution (NOT billing) - same markers as StepNode.
            if (context.plan() != null && context.plan().getId() != null) {
                billingIdentifiers.put("__workflowId__", context.plan().getId());
            }
            billingIdentifiers.put("__analyticsNodeId__", nodeId);
            // This result becomes the step's OUTPUT, read whole by downstream nodes: the catalog
            // then clips text only above 1 MB (inline base64 still above 4 KB).
            billingIdentifiers.put(com.apimarketplace.orchestrator.services.impl.CatalogToolsGateway.STEP_OUTPUT_MARKER, Boolean.TRUE);
            // Which credential this step runs on - one decision, owned by
            // StepCredentialSelection, so this node and StepNode cannot drift on the
            // markers they emit.
            //
            // A table step can never carry a run-time selector: this node is built
            // only from plan.getTables(), which WorkflowPlanParser.parseTables
            // constructs through the 8-arg Step constructor, so credentialSelector is
            // structurally null here. The selection is therefore always the static
            // one, and no refusal branch is reachable - which is just as well, since
            // this method signals failure with null and the caller reads null as
            // "try the list fallback", i.e. a refusal here would come back GREEN.
            StepCredentialSelection.resolve(stepConfig, null).applyTo(billingIdentifiers);
            com.apimarketplace.orchestrator.services.interfaces.ExecutionResult result =
                toolsGateway.executeTool(toolRef, inputData, tenantId, billingIdentifiers);

            if (!result.isSuccess()) {
                if (UserActionableFailure.isUserActionable(result.getErrorMessage())) {
                    logger.warn("[FindNode] CRUD read refused: nodeId={}, reason={}",
                        nodeId, result.getErrorMessage());
                } else {
                    logger.error("[FindNode] CRUD read failed: nodeId={}, error={}",
                        nodeId, result.getErrorMessage());
                }
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

    /**
     * {@link #maxItems}, or the plan's templated {@code crud.limit} resolved for this run and
     * clamped exactly like the constructor clamps a configured one.
     */
    private int effectiveMaxItems(ExecutionContext context) {
        String template = stepConfig.crud() != null ? stepConfig.crud().deferredScalars().get("limit") : null;
        if (template == null || templateAdapter == null) {
            return maxItems;
        }
        Map<String, Object> probe = new HashMap<>();
        Map<String, Object> crudProbe = new HashMap<>();
        crudProbe.put("limit", resolveTemplateValue(template, context));
        probe.put("crud", crudProbe);
        Integer limit = CrudDeferredScalars.limitOf(CrudDeferredScalars.coerce(probe, stepConfig.crud()));
        int requested = limit != null && limit > 0 ? limit : 100;
        return Math.min(requested, FIND_NODE_HARD_CAP);
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
            // A {{...}} limit / offset / topK / threshold the parser set aside resolves here too.
            CrudDeferredScalars.putTemplates(crudMap, stepConfig.crud());
            rawInput.put("crud", crudMap);
        }

        if (templateAdapter != null && !rawInput.isEmpty()) {
            try {
                return CrudDeferredScalars.coerce(
                    templateAdapter.resolveTemplates(rawInput, context), stepConfig.crud());
            } catch (RuntimeException e) {
                // Fail the node. The fallback queried the table with the raw where/set values
                // and reported them, plus the whole trigger payload, as the find's parameters.
                logger.warn("[FindNode] Template resolution failed: nodeId={}, error={}", nodeId, e.getMessage());
                throw new IllegalStateException(
                    "Could not resolve the find's parameters: " + e.getMessage(), e);
            }
        }

        rawInput.put("trigger", context.triggerData());
        return rawInput;
    }

    private ListFallback evaluateListFallback(ExecutionContext context) {
        if (listExpression == null || listExpression.isBlank()) {
            return ListFallback.noExpression();
        }
        try {
            if (templateAdapter != null) {
                Object result = templateAdapter.evaluateTemplate(listExpression, context);
                return ListFallback.evaluated(convertToList(result), result);
            }
            if (templateEngine != null) {
                V2TemplateAdapter adapter = new V2TemplateAdapter(templateEngine);
                Object result = adapter.evaluateTemplate(listExpression, context);
                return ListFallback.evaluated(convertToList(result), result);
            }
            return ListFallback.failed("no template engine is wired");
        } catch (Exception e) {
            logger.error("[FindNode] List fallback evaluation failed: nodeId={}, error={}", nodeId, e.getMessage(), e);
            return ListFallback.failed(e.getMessage());
        }
    }

    /**
     * The outcome of the list-expression strategy, carried so the parameters panel can say
     * which of the two strategies produced the rows.
     *
     * <p>{@code items == null} is the failure signal the caller already read before this
     * record existed; what it adds is {@link #describe()}, one bounded line naming what the
     * expression resolved to - from THIS evaluation, never from a second pass.
     *
     * @param items       the items, null when the expression could not be evaluated
     * @param description how the outcome reads in the parameters panel, null when there is
     *                    no expression to describe
     */
    private record ListFallback(List<Object> items, String description) {

        /**
         * The table served the rows; the expression was never looked at. Carries no items
         * on purpose - this value is only ever the seed for the description, and the rows
         * on that path come from the CRUD read.
         */
        static ListFallback notEvaluated() {
            return new ListFallback(null, "(not evaluated: the table returned rows)");
        }

        /** No expression is configured: an empty result, and nothing to describe. */
        static ListFallback noExpression() {
            return new ListFallback(List.of(), null);
        }

        static ListFallback evaluated(List<Object> items, Object rawValue) {
            return new ListFallback(items, ResolvedValuePreview.describe(rawValue));
        }

        static ListFallback failed(String reason) {
            // Shortened: a SpEL or JDBC message is not short, and this lands on the step row.
            return new ListFallback(null, "(evaluation failed: " + ResolvedValuePreview.shorten(reason) + ")");
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
