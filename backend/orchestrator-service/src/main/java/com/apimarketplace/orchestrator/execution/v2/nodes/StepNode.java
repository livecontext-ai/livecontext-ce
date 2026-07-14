package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.domain.workflow.Step;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Step node - Executes an API call.
 *
 * Simplified version for v2 architecture.
 * Full integration with ToolsGateway will be added later.
 *
 * Flow:
 * 1. Check if dependencies are completed
 * 2. Prepare input data
 * 3. Execute step logic (stub for now)
 * 4. Return result
 * 5. Successors are executed next
 */
public class StepNode extends BaseNode {

    private static final Logger logger = LoggerFactory.getLogger(StepNode.class);

    private final Step stepConfig;
    private final List<String> dependencies;

    public StepNode(String nodeId, Step stepConfig, List<String> dependencies) {
        super(nodeId, NodeType.MCP);
        this.stepConfig = stepConfig;
        this.dependencies = dependencies != null ? dependencies : List.of();
    }

    public StepNode(String nodeId, Step stepConfig) {
        this(nodeId, stepConfig, List.of());
    }

    @Override
    protected List<String> getDependencies(ExecutionContext context) {
        return dependencies;
    }

    @Override
    public NodeExecutionResult execute(ExecutionContext context) {
        long startTime = System.currentTimeMillis();
        // Captured outside the try so failure paths (missing tool id, thrown exceptions)
        // can still attach the resolved input parameters to the step record - the
        // inspector "Resolved parameters" panel reads them from result.output.resolved_params.
        Map<String, Object> inputData = Map.of();

        logger.debug("Step node executing: nodeId={}, label={}, itemId={}",
            nodeId, stepConfig.label(), context.itemId());

        try {
            // Prepare input data
            inputData = prepareInput(context);

            logger.debug("Step input prepared: nodeId={}, inputKeys={}",
                nodeId, inputData.keySet());

            // Execute via ToolsGateway
            if (toolsGateway == null) {
                logger.warn("⚠️  ToolsGateway not injected, using passthrough mode");
                return createPassthroughResult(inputData, startTime, context);
            }

            // Create ToolRef from step config
            String toolId;
            if (stepConfig.isCrudStep()) {
                String crudOp = stepConfig.getCrudOperation();
                toolId = "crud/" + crudOp;
                logger.info("🔧 CRUD step detected: nodeId={}, toolId={}", nodeId, toolId);
            } else {
                toolId = stepConfig.id();
            }
            if (toolId == null || toolId.isBlank()) {
                logger.error("❌ Step has no tool ID: nodeId={}", nodeId);
                long duration = System.currentTimeMillis() - startTime;
                return NodeExecutionResult.failureWithOutput(
                    nodeId, "Step has no tool ID",
                    buildFailureOutput(context, inputData, "Step has no tool ID"),
                    duration);
            }

            com.apimarketplace.orchestrator.domain.ToolRef toolRef =
                new com.apimarketplace.orchestrator.domain.ToolRef(toolId, 1);

            // Get tenantId from context
            String tenantId = context.tenantId();

            logger.debug("Executing tool: toolId={}, tenantId={}, nodeId={}",
                toolId, tenantId, nodeId);

            // Execute tool. Pass __workflowRunId__ in billingIdentifiers so
            // CatalogToolsGateway forwards an X-Lc-Billing-Scope-Kind=RUN
            // header to the catalog. The catalog-side CatalogToolBillingService
            // builds the BillingScope from those headers and bills via
            // billImmediate; the workflow path uses RUN scope so the markup
            // pin (created by PlatformMarkupPinService at run-init) covers the
            // step. Only the runId is required for the scope; the node id is
            // implied by the workflow's own ledger row.
            Map<String, Object> billingIdentifiers = new HashMap<>();
            if (context.runId() != null) {
                billingIdentifiers.put("__workflowRunId__", context.runId());
            }
            // Propagate the workflow author's explicit credential choice
            // (CredentialSection.tsx UI toggle, persisted on Step). The gateway
            // forwards these markers to the catalog as `credentialSource` /
            // `platformCredentialId` request fields. When set, the catalog
            // resolver uses them strictly - no user/platform fallback. When
            // absent (agent-driven calls including agents running inside a
            // workflow), the catalog applies the implicit fallback-if-priced
            // rule, same UX as the chat path.
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

            long duration = System.currentTimeMillis() - startTime;

            // Convert to NodeExecutionResult
            // Enrich output with item context for proper persistence
            Map<String, Object> enrichedOutput = new HashMap<>(result.output() != null ? result.output() : Map.of());
            enrichedOutput.put("node_type", resolveNodeType());
            enrichedOutput.put("item_index", context.itemIndex());
            enrichedOutput.put("itemIndex", context.itemIndex());
            enrichedOutput.put("item_id", context.itemId());

            // Persist resolved input params so they are visible in the inspector panel
            enrichedOutput.put("resolved_params", inputData);

            if (result.isSuccess()) {
                logger.debug("✅ Step executed successfully: nodeId={}, duration={}ms",
                    nodeId, duration);
                return NodeExecutionResult.success(nodeId, enrichedOutput, duration);
            } else {
                String errorMsg = result.getErrorMessage() != null
                    ? result.getErrorMessage()
                    : "Tool execution failed";
                logger.error("❌ Step execution failed: nodeId={}, error={}",
                    nodeId, errorMsg);
                // Preserve output even on failure for storage (error details, partial responses)
                enrichedOutput.put("error", errorMsg);
                return NodeExecutionResult.failureWithOutput(nodeId, errorMsg, enrichedOutput, duration);
            }

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            logger.error("❌ Step execution error: nodeId={}, error={}",
                nodeId, e.getMessage(), e);

            // Preserve the resolved inputs so the inspector can show what the node tried
            // to run when it blew up (missing credentials, HTTP 4xx/5xx, network errors, …).
            return NodeExecutionResult.failureWithOutput(
                nodeId, e.getMessage(),
                buildFailureOutput(context, inputData, e.getMessage()),
                duration);
        }
    }

    /**
     * Builds a failure output map that carries the resolved inputs + context metadata so
     * the step row written by StepDataPersistenceService has a populated `input_data`
     * column and the inspector "Resolved parameters" panel is never blank on failure.
     */
    private Map<String, Object> buildFailureOutput(ExecutionContext context,
                                                    Map<String, Object> inputData,
                                                    String errorMessage) {
        Map<String, Object> out = new HashMap<>();
        out.put("node_type", resolveNodeType());
        out.put("item_index", context.itemIndex());
        out.put("itemIndex", context.itemIndex());
        out.put("item_id", context.itemId());
        out.put("resolved_params", inputData != null ? inputData : Map.of());
        if (errorMessage != null) {
            out.put("error", errorMessage);
        }
        return out;
    }

    /**
     * Creates a passthrough result when ToolsGateway is not available.
     * Used for testing or when gateway is not configured.
     */
    private NodeExecutionResult createPassthroughResult(Map<String, Object> inputData, long startTime, ExecutionContext context) {
        Map<String, Object> output = new HashMap<>();
        output.put("step_id", nodeId);
        output.put("label", stepConfig.label());
        output.put("input", inputData);
        output.put("passthrough", true);
        output.put("warning", "ToolsGateway not available - passthrough mode");

        // Include item context for proper persistence
        output.put("node_type", resolveNodeType());
        output.put("item_index", context.itemIndex());
        output.put("itemIndex", context.itemIndex());
        output.put("item_id", context.itemId());

        long duration = System.currentTimeMillis() - startTime;
        logger.debug("✅ Step passthrough: nodeId={}", nodeId);

        return NodeExecutionResult.success(nodeId, output, duration);
    }

    /**
     * Prepares input data from step config and context.
     * Uses the template adapter to resolve SpEL expressions.
     * For CRUD steps, also includes dataSourceId and crud config.
     */
    private Map<String, Object> prepareInput(ExecutionContext context) {
        Map<String, Object> rawInput = new HashMap<>();

        // Get params from step config
        if (stepConfig.params() != null) {
            rawInput.putAll(stepConfig.params());
        }

        // For CRUD steps, add dataSourceId and crud config
        if (stepConfig.isCrudStep()) {
            logger.info("🔧 [CRUD DEBUG] StepNode.prepareInput - isCrudStep=true, stepId={}", nodeId);
            logger.info("🔧 [CRUD DEBUG] StepNode - stepConfig.dataSourceId={}", stepConfig.dataSourceId());
            logger.info("🔧 [CRUD DEBUG] StepNode - stepConfig.crud={}", stepConfig.crud());

            if (stepConfig.dataSourceId() != null) {
                rawInput.put("dataSourceId", stepConfig.dataSourceId());
            }
            if (stepConfig.crud() != null) {
                Map<String, Object> crudMap = buildCrudConfigMap(stepConfig.crud());
                logger.info("🔧 [CRUD DEBUG] StepNode - crudMap (before template resolution): {}", crudMap);
                rawInput.put("crud", crudMap);
            }
        }

        logger.info("🔧 [CRUD DEBUG] StepNode - rawParams (before template resolution): {}", rawInput);
        logger.info("🔧 [CRUD DEBUG] StepNode - context.triggerData: {}", context.triggerData());

        // If template adapter is available, resolve templates
        if (templateAdapter != null && !rawInput.isEmpty()) {
            try {
                Map<String, Object> resolved = templateAdapter.resolveTemplates(rawInput, context);
                logger.info("🔧 [CRUD DEBUG] StepNode - resolved (after template resolution): {}", resolved);

                // Check for unresolved templates
                if (templateAdapter.hasUnresolvedTemplates(resolved, context)) {
                    logger.warn("🔧 [CRUD DEBUG] Step {} has unresolved templates, some dependencies may be missing", nodeId);
                }

                return resolved;
            } catch (com.apimarketplace.orchestrator.services.expression.JsonParseException jpe) {
                // Surface json()/fromjson() typed errors so the outer catch in execute()
                // marks the step FAILED with the field-named message - never silently
                // fall back to the raw, unresolved template (which would ship "{{json(...)}}"
                // verbatim to the catalog).
                logger.error("Template resolution failed for step {}: {}", nodeId, jpe.getMessage());
                throw jpe;
            } catch (Exception e) {
                logger.error("🔧 [CRUD DEBUG] Template resolution failed for step {}: {}", nodeId, e.getMessage());
                // Fall back to raw input + context
            }
        }

        // Fallback: add trigger data (lightweight). Step outputs omitted to
        // prevent persisting the full workflow context into each step's input_data.
        rawInput.put("trigger", context.triggerData());

        return rawInput;
    }

    /**
     * Builds a Map representation of CrudConfig for template resolution.
     */
    private Map<String, Object> buildCrudConfigMap(Step.CrudConfig crud) {
        Map<String, Object> crudMap = new HashMap<>();
        logger.info("🔧 [CRUD DEBUG] buildCrudConfigMap - input crud: where={}, set={}, rows={}, columns={}, limit={}",
            crud.where(), crud.set(), crud.rows(), crud.columns(), crud.limit());

        if (crud.where() != null) {
            Map<String, Object> whereMap = new HashMap<>();
            whereMap.put("column", crud.where().column());
            whereMap.put("operator", crud.where().operator());
            whereMap.put("value", crud.where().value());
            logger.info("🔧 [CRUD DEBUG] buildCrudConfigMap - WHERE: column={}, operator={}, value={}",
                crud.where().column(), crud.where().operator(), crud.where().value());
            crudMap.put("where", whereMap);
        }

        if (!crud.set().isEmpty()) {
            logger.info("🔧 [CRUD DEBUG] buildCrudConfigMap - SET: {}", crud.set());
            crudMap.put("set", new HashMap<>(crud.set()));
        }

        if (!crud.rows().isEmpty()) {
            List<Map<String, Object>> rowsList = new java.util.ArrayList<>();
            for (Step.CrudConfig.RowData row : crud.rows()) {
                Map<String, Object> rowMap = new HashMap<>();
                rowMap.put("id", row.id());
                rowMap.put("columns", new HashMap<>(row.columns()));
                logger.info("🔧 [CRUD DEBUG] buildCrudConfigMap - ROW: id={}, columns={}", row.id(), row.columns());
                rowsList.add(rowMap);
            }
            crudMap.put("rows", rowsList);
        } else {
            logger.warn("🔧 [CRUD DEBUG] buildCrudConfigMap - rows is EMPTY!");
        }

        if (!crud.columns().isEmpty()) {
            List<Map<String, Object>> colsList = new java.util.ArrayList<>();
            for (Step.CrudConfig.ColumnDefinition col : crud.columns()) {
                Map<String, Object> colMap = new HashMap<>();
                colMap.put("name", col.name());
                colMap.put("type", col.type());
                if (col.defaultValue() != null) {
                    colMap.put("defaultValue", col.defaultValue());
                }
                colsList.add(colMap);
            }
            crudMap.put("columns", colsList);
        }

        if (crud.limit() != null) {
            crudMap.put("limit", crud.limit());
        }

        if (crud.offset() != null) {
            crudMap.put("offset", crud.offset());
        }

        return crudMap;
    }

    @Override
    public void onComplete(ExecutionContext context, NodeExecutionResult result) {
        logger.debug("Step node completed: nodeId={}, status={}",
            nodeId, result.status());
        // Event emission, persistence, metrics will be added later
    }

    /**
     * Mock-mode schema tag: same resolution as real executions ({@code MCP} or the
     * CRUD-specific tag), so schema mappers dispatch identically on mocked outputs.
     */
    @Override
    public String schemaNodeType() {
        return resolveNodeType();
    }

    /**
     * Resolves the correct node_type for CRUD schema mapper dispatch.
     */
    private String resolveNodeType() {
        if (!stepConfig.isCrudStep()) return "MCP";
        String crudOp = stepConfig.getCrudOperation();
        return switch (crudOp) {
            case "create-row" -> "INSERT_ROW";
            case "read-row" -> "GET_ROWS";
            case "update-row" -> "UPDATE_ROW";
            case "delete-row" -> "DELETE_ROW";
            case "create-column" -> "CREATE_COLUMN";
            default -> "MCP";
        };
    }

    public Step getStepConfig() {
        return stepConfig;
    }

    public static class Builder {
        private String nodeId;
        private Step stepConfig;
        private List<String> dependencies;

        public Builder nodeId(String nodeId) {
            this.nodeId = nodeId;
            return this;
        }

        public Builder stepConfig(Step stepConfig) {
            this.stepConfig = stepConfig;
            return this;
        }

        public Builder dependencies(List<String> dependencies) {
            this.dependencies = dependencies;
            return this;
        }

        public StepNode build() {
            return new StepNode(nodeId, stepConfig, dependencies);
        }
    }

    public static Builder builder() {
        return new Builder();
    }
}
