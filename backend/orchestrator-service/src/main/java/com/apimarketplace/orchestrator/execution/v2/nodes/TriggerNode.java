package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.domain.workflow.Trigger;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.ServiceRegistry;
import com.apimarketplace.orchestrator.services.template.ReportedParams;
import com.apimarketplace.orchestrator.services.triggers.TriggerUserResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Trigger node - Entry point of workflow execution.
 *
 * The trigger node resolves trigger.input templates using triggerData as current_item,
 * similar to how legacy TriggerInputResolver works.
 *
 * Example trigger.input in plan:
 *   "input": { "user_id": "${int(current_item.data.user_id)}" }
 *
 * With triggerData:
 *   { "data": { "user_id": 2, "name": "User Pair 1" } }
 *
 * Results in resolved inputs:
 *   { "user_id": 2 }
 *
 * These resolved inputs are added to the output at the top level,
 * making them available for decision conditions like {{user_id%2==0}}.
 */
public class TriggerNode extends BaseNode {

    private static final Logger logger = LoggerFactory.getLogger(TriggerNode.class);

    /**
     * The three keys a secured webhook keeps its inbound credential under.
     *
     * <p>They are NOT part of the trigger's output contract. `TriggerCreator` writes them
     * into the trigger's params for `PinAwareTriggerSyncService` to sync to trigger-service,
     * which is the only thing that reads them (`StandaloneWebhookService` holds this exact
     * list to encrypt and decrypt them). Nothing in a DAG resolves
     * {@code {{trigger:x.output.basicPassword}}}, so copying them to the top level of the
     * output published the credential to `output_data`, to the inspector's Output column and
     * to every downstream template, on every epoch of every secured webhook.
     *
     * <p>Excluded by NAME rather than by {@link ReportedParams#isCredentialKey}, because this
     * map is the output CONTRACT: an author who maps a value of their own onto a key called
     * {@code api_key} is feeding it to a downstream node on purpose, and masking that would
     * not hide a row, it would break the run.
     */
    private static final java.util.Set<String> WEBHOOK_AUTH_SECRET_KEYS =
        java.util.Set.of("basicPassword", "authHeaderValue", "jwtSecretKey");

    private final String triggerId;
    private final Trigger trigger;
    private TriggerUserResolver triggerUserResolver;

    /**
     * Constructor with full Trigger definition (recommended).
     * Enables trigger.input template resolution.
     */
    public TriggerNode(String nodeId, Trigger trigger) {
        super(nodeId, NodeType.TRIGGER);
        this.triggerId = trigger.id();
        this.trigger = trigger;
    }

    /**
     * Legacy constructor without Trigger (for backward compatibility).
     * No trigger.input resolution will be performed.
     */
    public TriggerNode(String nodeId, String triggerId) {
        super(nodeId, NodeType.TRIGGER);
        this.triggerId = triggerId;
        this.trigger = null;
    }

    @Override
    public boolean canExecute(ExecutionContext context) {
        // Trigger always can execute (it's the entry point)
        return true;
    }

    /**
     * TriggerNode is a trigger node.
     */
    @Override
    public boolean isTriggerNode() {
        return true;
    }

    /**
     * TriggerNode skips split handling - it's the entry point before any split context.
     */
    @Override
    public boolean skipsSplitHandling() {
        return true;
    }

    @Override
    public NodeExecutionResult execute(ExecutionContext context) {
        logger.debug("Trigger node executing: nodeId={}, triggerId={}, itemId={}",
            nodeId, triggerId, context.itemId());

        // Resolve trigger.params templates if defined
        Map<String, Object> resolvedParams = resolveTriggerParams(context);

        // Build output map with consistent structure for expression resolution
        // All trigger payload fields are copied to output at top level, making them
        // directly accessible: {{trigger:e.output.user_id}}, {{trigger:e.output.data.xxx}}
        // Works for all trigger types: form fields, webhook body, chat messages, datasource rows
        Map<String, Object> output = new HashMap<>();

        // Build resolved_params snapshot for inspector visibility.
        //
        // Two things this must not do, and did both. It copied `trigger.params()` - the RAW
        // plan map - while the node itself runs on `resolvedParams` above: one trigger, two
        // answers, and the panel showed the one that decided nothing. And a webhook secured
        // with basic auth or a JWT keeps `basicPassword` / `authHeaderValue` /
        // `jwtSecretKey` in exactly that map (see TriggerCreator), so every epoch of every
        // secured webhook published its credential into workflow_step_data.input_data and
        // into the Params column. `ReportedParams.forReport` masks by key name; the names
        // themselves stay, so the row still says which scheme ran and which parameter was
        // masked.
        Map<String, Object> resolvedParamsSnapshot = new java.util.LinkedHashMap<>();
        resolvedParamsSnapshot.put("triggerId", triggerId);
        // The resolution the node ran on, and the configured map only when there was no
        // resolution to speak of (no template adapter wired): reporting nothing at all is
        // the defect this column exists to remove, and the redaction applies either way.
        Map<String, Object> reportable = !resolvedParams.isEmpty()
            ? resolvedParams
            : (trigger != null && trigger.params() != null ? trigger.params() : Map.of());
        resolvedParamsSnapshot.putAll(ReportedParams.forReport(reportable));
        output.put("resolved_params", resolvedParamsSnapshot);

        // Copy ALL trigger payload fields to output (form fields, webhook body, datasource data, etc.)
        Map<String, Object> triggerData = context.triggerData();
        if (triggerData != null) {
            output.putAll(triggerData);
        }

        // Add trigger metadata (overrides any conflicting triggerData keys)
        output.put("trigger_id", triggerId);
        output.put("item_id", context.itemId());
        output.put("item_index", context.itemIndex());

        // Add resolved inputs at top level (highest priority - explicit mappings override raw data)
        // This makes mapped variables directly accessible: {{trigger:e.output.user_id}}
        //
        // Minus the webhook's own inbound credential. Masking `resolved_params` closed one
        // of three sinks: the same value was still copied here into `output_data` (the
        // Output column, and every downstream template) and printed whole at INFO into the
        // service log, on every epoch. A row that masks a secret in one column and prints
        // it in the next has protected nothing.
        if (resolvedParams != null && !resolvedParams.isEmpty()) {
            for (Map.Entry<String, Object> entry : resolvedParams.entrySet()) {
                if (!WEBHOOK_AUTH_SECRET_KEYS.contains(entry.getKey())) {
                    output.put(entry.getKey(), entry.getValue());
                }
            }
            logger.info("TriggerNode resolved params: {}", ReportedParams.forReport(resolvedParams));
        }

        // Uniform trigger context: every trigger type exposes triggered_at + triggered_by
        // (snake_case) at the top level of its output. Resolvers that already write these
        // keys (manual, chat, webhook, workflow, datasource) short-circuit. Types without
        // a dedicated resolver in services/triggers (schedule, form, table events, error)
        // still get populated here via the shared AuthClient lookup. Interface
        // variable_mapping references like {{trigger:x.output.triggered_by}} now resolve
        // consistently across ALL trigger types.
        output.putIfAbsent("triggered_at", java.time.Instant.now().toString());
        if (!output.containsKey("triggered_by")) {
            String triggeredBy = "";
            if (triggerUserResolver != null) {
                triggeredBy = triggerUserResolver.resolveDisplayName(context.tenantId());
            }
            output.put("triggered_by", triggeredBy);
        }
        // Also migrate any legacy camelCase triggeredAt the resolver might still emit
        // (defense in depth while resolvers are being migrated to snake_case).
        Object legacyAt = output.remove("triggeredAt");
        if (legacyAt != null && !output.containsKey("triggered_at")) {
            output.put("triggered_at", legacyAt);
        }

        return NodeExecutionResult.success(nodeId, output);
    }

    @Override
    public void acceptServices(ServiceRegistry registry) {
        super.acceptServices(registry);
        this.triggerUserResolver = registry.getTriggerUserResolver();
    }

    /**
     * Resolves trigger.params templates using triggerData as current_item.
     *
     * This mimics legacy TriggerInputResolver.resolveWithCurrentItem() behavior:
     * - Sets current_item = triggerData (raw payload)
     * - Evaluates templates like "${int(current_item.data.user_id)}"
     * - Returns resolved values: { "user_id": 2 }
     */
    private Map<String, Object> resolveTriggerParams(ExecutionContext context) {
        if (trigger == null || trigger.params() == null || trigger.params().isEmpty()) {
            return Map.of();
        }

        // Use templateAdapter from BaseNode (injected by ExecutionTreeBuilder)
        if (templateAdapter == null) {
            logger.warn("⚠️ TriggerNode: templateAdapter not available, skipping params resolution");
            return Map.of();
        }

        try {
            // The V2TemplateAdapter.convertToV1Context() already sets current_item from triggerData
            // So we can directly use resolveTemplates()
            Map<String, Object> resolved = templateAdapter.resolveTemplates(trigger.params(), context);

            logger.debug("🎯 TriggerNode params resolution: raw={}, resolved={}",
                ReportedParams.forReport(trigger.params()), ReportedParams.forReport(resolved));
            return resolved;
        } catch (Exception e) {
            logger.error("❌ TriggerNode params resolution failed: {}", e.getMessage(), e);
            return Map.of();
        }
    }

    @Override
    public void onComplete(ExecutionContext context, NodeExecutionResult result) {
        logger.debug("✅ Trigger node completed: nodeId={}, triggerId={}",
            nodeId, triggerId);
        // Event emission will be added later
    }

    /**
     * The trigger's declared type ({@code webhook}, {@code schedule}, {@code manual},
     * {@code chat}, {@code form}, {@code datasource}), i.e. the value
     * {@code node_type_documentation.type} uses for it. Every other node answers that
     * question with {@link #schemaNodeType()}, which for a trigger only ever says
     * "TRIGGER" - so anything keyed by node type has to ask here instead.
     *
     * @return the type, or {@code null} when this node was built from an id alone
     */
    public String getTriggerType() {
        return trigger != null ? trigger.type() : null;
    }

    public String getTriggerId() {
        return triggerId;
    }
}
