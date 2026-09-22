package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.domain.workflow.Step;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.apimarketplace.orchestrator.services.template.ReportedParams;
import com.apimarketplace.orchestrator.services.failure.UserActionableFailure;

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
    /** Used to file catalog-produced files under this run - see {@link #adoptProducedFiles}. */
    private com.apimarketplace.orchestrator.services.file.FileStorageService fileStorageService;

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
    public void acceptServices(com.apimarketplace.orchestrator.execution.v2.engine.ServiceRegistry registry) {
        super.acceptServices(registry);
        this.fileStorageService = registry.getFileStorageService();
    }

    /**
     * File the files this step produced under this run.
     *
     * <p>A tool that answers with binary (text-to-speech, image generation, …) is uploaded from
     * catalog-service, which knows the tenant and nothing else. Its {@code storage.storage} row
     * therefore has no workflow, no run and no step, and the Files browser - which builds its
     * workflow folders purely from those columns - shows the file at the ROOT instead of inside the
     * run that produced it. The step is the first place that knows both the file and the context,
     * so it adopts them here.</p>
     *
     * <p>Failure is swallowed on purpose, and the call sits AFTER the step is judged successful:
     * where a file is filed must not decide whether the step succeeded, and the file is stored and
     * reachable from this step's output either way. The epoch comes from
     * {@link BaseNode#resolveStorageEpoch} so an adopted file lands in the same epoch bucket as
     * files the run uploaded itself.</p>
     */
    private void adoptProducedFiles(ExecutionContext context, Map<String, Object> output) {
        if (fileStorageService == null || output == null || context.plan() == null) {
            return;
        }
        try {
            List<String> fileIds = com.apimarketplace.orchestrator.domain.file.FileRefScanner.collectFileIds(output);
            if (fileIds.isEmpty()) {
                return;
            }
            int adopted = fileStorageService.adoptRunContext(
                context.tenantId(), fileIds, context.plan().getId(), context.runId(), nodeId,
                resolveStorageEpoch(context), context.spawn(), context.itemIndex());
            if (adopted > 0) {
                logger.debug("Adopted {} produced file(s) into the run: nodeId={}, runId={}",
                    adopted, nodeId, context.runId());
            }
        } catch (Exception e) {
            logger.warn("Could not file this step's produced files under the run (nodeId={}): {}",
                nodeId, e.getMessage());
        }
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

            // The account decision comes BEFORE the passthrough check below. Evaluated
            // after it, a step whose expression resolves to nothing came back SUCCESS
            // whenever no gateway was injected, which is a green run on a choice that
            // could not be honoured - the one outcome this feature exists to prevent.
            String rawSelector = stepConfig.credentialSelector();
            StepCredentialSelection selection = StepCredentialSelection.resolve(
                    stepConfig,
                    rawSelector == null ? null : resolveTemplateString(rawSelector, context));
            if (selection.isFailure()) {
                long failDuration = System.currentTimeMillis() - startTime;
                logger.error("Step credential selection failed: nodeId={}, {}", nodeId, selection.error());
                return NodeExecutionResult.failureWithOutput(
                        nodeId, selection.error(),
                        buildFailureOutput(context, inputData, selection.error()),
                        failDuration);
            }

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
            // Analytics attribution (NOT billing): which workflow and which node
            // made this API call. CatalogToolsGateway forwards them as X-Lc-Workflow-Id /
            // X-Lc-Node-Id; `__nodeId__` is intentionally left unset because it
            // feeds the billing step key.
            if (context.plan() != null && context.plan().getId() != null) {
                billingIdentifiers.put("__workflowId__", context.plan().getId());
            }
            billingIdentifiers.put("__analyticsNodeId__", nodeId);
            // Propagate the workflow author's explicit credential choice
            // (CredentialSection.tsx UI toggle, persisted on Step). The gateway
            // forwards these markers to the catalog as `credentialSource` /
            // `platformCredentialId` request fields. When set, the catalog
            // resolver uses them strictly - no user/platform fallback. When
            // absent (agent-driven calls including agents running inside a
            // workflow), the catalog applies the implicit fallback-if-priced
            // rule, same UX as the chat path.
            //
            // A step may instead decide its credential at RUN time, from an
            // expression - see StepCredentialSelection, which owns both modes and
            // is the only reason this block is not the if/else it used to be. A
            // step with no selector produces exactly the markers it produced
            // before that class existed. Already resolved above, before the
            // passthrough branch, so a failed selection cannot slip past it.
            selection.applyTo(billingIdentifiers);
            resolveProviderRetryBudget(context).ifPresent(
                    seconds -> billingIdentifiers.put("__providerRetryMaxWaitSec__", seconds));
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
            // Masked and bounded on the way out: a tool argument can be a token the author
            // typed or a {{$vars.secret}} the engine resolved, and a CRUD write carries whole
            // rows. The map handed to the gateway above is untouched - only the reported copy.
            enrichedOutput.put("resolved_params", ReportedParams.forReport(inputData));
            // And, when the credential was chosen at run time, WHICH account served.
            // Null in static mode, so the output of every existing step is unchanged.
            Map<String, Object> credentialSelection = selection.describe(rawSelector);
            if (credentialSelection != null) {
                enrichedOutput.put("credential_selection", credentialSelection);
            }

            if (result.isSuccess()) {
                adoptProducedFiles(context, result.output());
                // Re-measure: adoption is a synchronous call on this step's critical path, so
                // reporting the pre-adoption number would under-state the step's real wall time
                // in the run inspector.
                duration = System.currentTimeMillis() - startTime;
                logger.debug("✅ Step executed successfully: nodeId={}, duration={}ms",
                    nodeId, duration);
                return NodeExecutionResult.success(nodeId, enrichedOutput, duration);
            } else {
                String errorMsg = result.getErrorMessage() != null
                    ? result.getErrorMessage()
                    : "Tool execution failed";
                // The catalogue gateway one frame down already decided whether this is a
                // refusal (plan, credits, credential choice) or a fault, and logged it at the
                // right level. Re-logging every case at ERROR here undid that entirely.
                if (UserActionableFailure.isUserActionable(errorMsg)) {
                    logger.warn("Step refused: nodeId={}, reason={}", nodeId, errorMsg);
                } else {
                    logger.error("❌ Step execution failed: nodeId={}, error={}",
                        nodeId, errorMsg);
                }
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
        out.put("resolved_params", ReportedParams.forReport(inputData != null ? inputData : Map.of()));
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
        // Masked, and reported: this path emitted the whole resolved argument map under
        // `input` with no masking and no `resolved_params` at all, so the one exit where
        // the tool never ran was also the one that published its arguments in full.
        Map<String, Object> reportable = ReportedParams.forReport(inputData);
        output.put("input", reportable);
        output.put("resolved_params", reportable);
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
                logger.info("🔧 [CRUD DEBUG] StepNode - crudMap (before template resolution): {}", ReportedParams.forReport(crudMap));
                rawInput.put("crud", crudMap);
            }
        }

        // Through the gate, all three. A log line is a sink like the row: it is as readable
        // by whoever can reach the service and it outlives the run. These printed the same
        // map the column masks, so the mask was cosmetic on any node reached through here.
        logger.info("🔧 [CRUD DEBUG] StepNode - rawParams (before template resolution): {}", ReportedParams.forReport(rawInput));
        logger.info("🔧 [CRUD DEBUG] StepNode - context.triggerData: {}", ReportedParams.forReport(context.triggerData()));

        // If template adapter is available, resolve templates
        if (templateAdapter != null && !rawInput.isEmpty()) {
            try {
                Map<String, Object> resolved = templateAdapter.resolveTemplates(rawInput, context);
                logger.info("🔧 [CRUD DEBUG] StepNode - resolved (after template resolution): {}", ReportedParams.forReport(resolved));

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

    /**
     * A table operation is a built-in node wearing a step's clothes: its
     * {@code toolId} is the synthetic {@code crud/<op>}, which names no catalog
     * endpoint, so it is counted as a node type. Everything else is a real
     * catalog endpoint and its identifier travels untouched - see
     * {@link ExecutionNode#usageKey()} for why it is not split here.
     */
    @Override
    public String usageKey() {
        if (stepConfig == null || stepConfig.isCrudStep()) {
            return "node:table";
        }
        String toolId = stepConfig.id();
        return (toolId == null || toolId.isBlank()) ? null : "tool:" + toolId;
    }

    /**
     * How long this call may spend waiting out a provider's rate-limit refusal, in seconds.
     *
     * <p>Empty means "say nothing", and the platform's own budget applies. That is the right
     * default for the overwhelming majority of steps: their author never thought about a 429, and
     * honouring the delay the provider asked for is the platform's job, not theirs.
     *
     * <p>Two things override it, in this order:
     *
     * <ol>
     *   <li><b>The author's own setting</b> ({@code nodePolicy.providerRetryMaxWaitSec}, in
     *       seconds; {@code 0} disables). Explicit always wins, in both directions: an author who
     *       wants the platform retry alongside their own node retry can ask for it.</li>
     *   <li><b>A node that already retries itself</b> ({@link NodePolicy} with
     *       {@code retryCount > 0}). The platform then stands down, because the two compose
     *       multiplicatively: a node set to retry twice (three attempts), around a call the
     *       platform re-sends twice, is up to nine requests to a provider that asked us to slow
     *       down. The author who
     *       configured a retry is precisely the one who did not ask for a second one underneath.</li>
     * </ol>
     *
     * <p>A loop that calls, waits and comes back is the same conflict without a NodePolicy, and no
     * heuristic can see it, which is why the explicit setting exists.
     */
    java.util.Optional<Integer> resolveProviderRetryBudget(ExecutionContext context) {
        if (context == null || context.plan() == null) {
            return java.util.Optional.empty();
        }
        com.apimarketplace.orchestrator.domain.workflow.NodePolicy policy =
                context.plan().getNodePolicy(nodeId);
        if (policy == null) {
            return java.util.Optional.empty();
        }
        // The node's own per-attempt window bounds every answer below it, including an explicit
        // one. An author can raise the budget against the PLATFORM default, but not past the
        // deadline they gave this attempt: past it the node abandons the attempt while the catalog
        // sleeps on, re-sends, succeeds, stores the result and commits the charge - a step billed
        // while the run reports it FAILED. Explicit choice is honoured up to the point where it
        // stops being a choice about waiting and becomes one about being charged for nothing.
        Integer ceiling = policy.hasTimeout()
                ? providerRetryBudgetUnderTimeout(policy.timeoutMs())
                : null;

        // Explicit wins over both inferences: an author who wants the platform retry alongside
        // their own node retry can ask for it. Negatives cannot reach here - NodePolicy's
        // constructor rejects them.
        if (policy.providerRetryMaxWaitSec() != null) {
            int asked = policy.providerRetryMaxWaitSec();
            return java.util.Optional.of(ceiling == null ? asked : Math.min(asked, ceiling));
        }
        if (policy.retryCount() > 0) {
            return java.util.Optional.of(0);
        }
        return java.util.Optional.ofNullable(ceiling);
    }

    /**
     * The wait a provider call may take when the node has declared its OWN per-attempt window.
     *
     * <p>Without this, {@code timeoutMs} reproduced the worst failure this platform has: the node
     * abandons the attempt at its timeout while the catalog is still sleeping out a
     * {@code Retry-After}, then re-sends, succeeds, stores the result and commits the charge. The
     * customer is billed for a step the run reports FAILED, and nothing releases it because from
     * the catalog's side nothing failed. It is the same incident the orchestrator's own
     * {@code RestTemplateConfig.generationReadTimeout} is annotated with, and the same reason the
     * catalog refuses a budget larger than its own.
     *
     * <p>HALF the window, not all of it: the attempt also has to pay for the requests themselves,
     * and the wait is only one part of what happens inside it. Half needs no knowledge of provider
     * latency and errs towards failing fast, which is the safe direction - the step then fails with
     * the provider's own refusal, in time for the node's retry or a loop to handle it.
     */
    private static int providerRetryBudgetUnderTimeout(long timeoutMs) {
        return (int) Math.min(Integer.MAX_VALUE, timeoutMs / 2000L);
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
