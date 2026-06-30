package com.apimarketplace.orchestrator.tools.workflow.builder.response;

import com.apimarketplace.orchestrator.tools.workflow.builder.ResponseContextBuilder;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Builds optimized responses for trigger and step nodes.
 * Handles datasource triggers, schedule triggers, and various step types.
 *
 * @see com.apimarketplace.orchestrator.tools.workflow.builder.ResponseOptimizer
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TriggerStepResponseBuilder {

    private final ResponseContextBuilder contextBuilder;

    /**
     * Build response for trigger (datasource trigger with full business logic).
     * Token-optimized version of WorkflowBuilderCreator.buildTriggerResponse().
     */
    public Map<String, Object> buildTriggerResponse(
            WorkflowBuilderSession session,
            String nodeId,
            String label,
            String type,
            String datasourceId,
            Map<String, String> outputs,
            Map<String, String> referenceSyntax
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        // Standard envelope (consistent across all node types)
        result.put("status", "OK");
        // Return the specific trigger subtype (form, webhook, etc.) not generic "trigger"
        String displayType = "datasource".equals(type) ? "table" : (type != null ? type : "trigger");
        result.put("node_type", displayType);
        result.put("node_id", nodeId);
        result.put("logical_id", session.getLogicalId(nodeId));
        result.put("message", "Trigger '" + label + "' added.");

        // Table-specific info (JIT - ALWAYS show for table triggers)
        if ("table".equals(type)) {
            // CRITICAL: Always show table_id
            if (datasourceId != null) {
                result.put("table_id", datasourceId);
                result.put("table_id_usage", "Use this ID in CRUD steps: table_id=" + datasourceId);
            }

            // Show columns if available
            if (!outputs.isEmpty()) {
                Map<String, String> columns = new LinkedHashMap<>();
                int count = 0;
                for (String field : outputs.keySet()) {
                    if (count++ >= 100) {
                        columns.put("...", "+" + (outputs.size() - 100) + " more columns");
                        break;
                    }
                    columns.put(field, referenceSyntax.getOrDefault(field, "{{" + field + "}}"));
                }
                result.put("available_columns", columns);
                String normalized = WorkflowBuilderSession.normalizeLabel(label);
                result.put("reference_syntax", Map.of(
                    "per_column", "{{trigger:" + normalized + ".output.row.<column>}} - always-safe nested path " +
                        "(works on every fire; no collision with reserved names like status/count/data/source).",
                    "event_meta", "{{trigger:" + normalized + ".output.event_type}}, .row_id, .previous_row " +
                        "(previous_row populated only on row_updated).",
                    "batch_scan", "{{trigger:" + normalized + ".output.data}} → list of {id, data:{...columns}} " +
                        "(only when fired via workflow(action='execute') for testing). Chain core:split with " +
                        "input={{trigger:" + normalized + ".output.data}} for per-row processing - same pattern as find_rows.items."
                ));
            } else {
                result.put("⚠️ no_columns", "Table has no columns defined yet. Use table(action='add_columns', table_id=" + datasourceId + ", columns=[...])");
            }

            // Table behavior (contextual)
            result.put("behavior", Map.of(
                "production_event_driven", "On real row insert/update/delete (workflow pinned), fires ONE run per row. " +
                    "Use the event-driven shape: {{trigger:label.output.row.<column>}}.",
                "editor_batch_scan", "On workflow(action='execute') for testing, runs the batch-scan loader and " +
                    "emits {data:[...], count, hasMore} like find_rows. Chain core:split for per-row iteration.",
                "no_top_level_columns", "Do NOT write {{trigger:label.output.<column>}} - column names that " +
                    "collide with reserved keys (status, count, data, source, error, message, …) are silently " +
                    "shadowed. Always use .output.row.<column> instead."
            ));
        } else {
            // Non-datasource triggers: show columns if available
            if (!outputs.isEmpty()) {
                Map<String, String> columns = new LinkedHashMap<>();
                int count = 0;
                for (String field : outputs.keySet()) {
                    if (count++ >= 100) {
                        columns.put("...", "+" + (outputs.size() - 100) + " more columns");
                        break;
                    }
                    columns.put(field, referenceSyntax.getOrDefault(field, "{{" + field + "}}"));
                }
                result.put("available_columns", columns);
                result.put("reference_syntax", "Use {{trigger:" +
                    WorkflowBuilderSession.normalizeLabel(label) + ".output.<column>}} in step/agent inputs");
            }
        }

        // Schedule-specific guidance
        if ("schedule".equals(type)) {
            result.put("behavior", Map.of(
                "execution", "Runs at scheduled times",
                "no_input_data", "Schedule triggers don't have input columns - fetch data in first step"
            ));
        }

        // Error-trigger-specific guidance - bootstrap is the silent footgun.
        if ("error".equals(type)) {
            result.put("behavior", Map.of(
                "fires_on", "Parent workflow run finishes with status FAILED or PARTIAL_SUCCESS.",
                "bootstrap_required", "After workflow(action='finish'), call workflow(action='execute', id=" +
                    "'<this_workflow_id>') ONCE. The call returns status='BOOTSTRAPPED' with the seed run id - " +
                    "no fire happens (the trigger is system-only). The dispatcher then attaches future parent " +
                    "failures to that seed run. Without it, failures are silently dropped (logged as 'No active " +
                    "run for workflow X, skipping dispatch'). Verify with workflow(action='runs', workflow_id=" +
                    "'<this_workflow_id>') - you should see one WAITING_TRIGGER run.",
                "anti_loop", "If this error handler workflow itself fails, it does NOT trigger another error " +
                    "handler - the cascade stops at one level.",
                "how_to_test_chain", "There is no manual fire path for error triggers (UI Play and agent execute " +
                    "both only seed the bootstrap run). To exercise the dispatch end-to-end, cause the watched " +
                    "parent workflow to FAIL or PARTIAL_SUCCESS (e.g. add a stop_on_error step then " +
                    "workflow(action='execute', id='<parent_id>')) and check workflow(action='runs', workflow_id=" +
                    "'<this_workflow_id>') for a new epoch on the seed run."
            ));
        }

        // Interface binding hint for interactive triggers (form, chat, manual)
        String normalizedLabel = WorkflowBuilderSession.normalizeLabel(label);
        String actionMappingHint = switch (type != null ? type : "") {
            case "form" -> "action_mapping: {'#<form-id>': 'trigger:" + normalizedLabel + ":submit'}";
            case "chat" -> "action_mapping: {'#<chat-id>': 'trigger:" + normalizedLabel + ":message'}";
            case "manual" -> "action_mapping: {'#<btn-id>': 'trigger:" + normalizedLabel + ":click'}";
            default -> null;
        };
        if (actionMappingHint != null) {
            result.put("interface_binding", "To bind this trigger to an HTML element in an interface node, use " + actionMappingHint);
        }

        // Webhook-specific guidance (URL is set by TriggerCreator after auto-creating standalone webhook)
        if ("webhook".equals(type)) {
            result.put("webhook", Map.of(
                "default_config", Map.of("httpMethod", "POST", "authType", "none"),
                "configure", "workflow(action='modify', node='" + label + "', params={httpMethod: '...', authType: '...'})"
            ));
        }

        // Production-pin requirement - webhook/schedule/form/chat fire ONLY the pinned
        // version. No pin = production refused: webhook returns an error, schedule skips
        // the tick, form/chat return {"status":"not_pinned"} from their token endpoints
        // (FormDispatchService / ChatDispatchService both go through ProductionRunResolver,
        // exactly like webhook). Surface this at trigger-creation time so the agent doesn't
        // ship a workflow that silently never fires in prod, then have to debug from the
        // "no pinned version" log line.
        if ("webhook".equals(type) || "schedule".equals(type)
                || "form".equals(type) || "chat".equals(type)) {
            String refusalDetail = switch (type) {
                case "webhook" -> "webhook returns an error";
                case "schedule" -> "schedule skips the tick";
                case "form", "chat" -> "the public " + type + " endpoint returns {\"status\":\"not_pinned\"}";
                default -> "production refuses to fire";
            };
            result.put("production", Map.of(
                "requires_pin", "This trigger fires in production ONLY against the pinned version. " +
                    "No pin → " + refusalDetail + ".",
                "flow", "1) workflow(action='finish'). " +
                    "2) workflow(action='execute', id='<uuid>') once to seed a WAITING_TRIGGER run at the new version. " +
                    "3) workflow(action='pin', workflow_id='<uuid>', version=N) to promote it to production.",
                "see_also", "workflow(action='help', topics=['pin']) for the full pin/unpin contract."
            ));
        }

        // Next step guidance with generic example
        // For datasource (table) triggers, recommend the safe nested path .output.row.<col>;
        // for all other triggers, the flat .output.<col> is collision-free.
        String yourDataExample = "datasource".equals(type)
            ? "{{trigger:" + normalizedLabel + ".output.row.<column>}}"
            : "{{trigger:" + normalizedLabel + ".output.<column>}}";
        result.put("NEXT", Map.of(
            "pattern", "workflow(action='add_node', type='agent|mcp|...', label='...', params={...}, connect_after='" + label + "')",
            "your_data", yourDataExample,
            "get_params", "workflow(action='help', topics=['agent', 'mcp', 'decision', ...]) for required params"
        ));

        return result;
    }

    /**
     * Build response for step with full business logic.
     * Token-optimized version of WorkflowBuilderCreator.buildStepResponse().
     */
    public Map<String, Object> buildStepResponse(
            WorkflowBuilderSession session,
            String nodeId,
            String label,
            String toolId,
            String connectAfter,
            String parentLoopId,
            boolean autoConnected,
            Map<String, String> outputRefs,
            List<String> missingRequired,
            Map<String, String> suggestedInputs,
            List<String> availableColumns,
            boolean hasAnyVariables
    ) {
        Map<String, Object> result = new LinkedHashMap<>();

        // Standard envelope (consistent across all node types)
        result.put("status", "OK");
        result.put("node_type", "mcp");
        result.put("node_id", nodeId);
        result.put("logical_id", session.getLogicalId(nodeId));
        result.put("message", "MCP node '" + label + "' added" + (autoConnected ? " (connected)" : " (ORPHANED - not connected, use connect_after='<label>' next time!)"));

        // JIT: All accessible variables from predecessors (trigger, steps, agents, loops)
        Map<String, Object> accessibleVars = contextBuilder.getAccessibleVariables(session, nodeId);
        if (!accessibleVars.isEmpty()) {
            result.put("available_variables", accessibleVars);
            result.put("input_syntax", "Use {{...}} to map data to step inputs");
        }

        // Output refs (limit to 5 for token optimization)
        if (outputRefs != null && !outputRefs.isEmpty()) {
            Map<String, String> limitedRefs = new LinkedHashMap<>();
            int count = 0;
            for (var entry : outputRefs.entrySet()) {
                if (count++ >= 5) {
                    limitedRefs.put("...", "+" + (outputRefs.size() - 5) + " more");
                    break;
                }
                limitedRefs.put(entry.getKey(), entry.getValue());
            }
            result.put("outputs", limitedRefs);
        }

        // Missing inputs (JIT - only when there are missing inputs)
        if (missingRequired != null && !missingRequired.isEmpty()) {
            result.put("missing_inputs", missingRequired);
            if (suggestedInputs != null) {
                result.put("suggested", suggestedInputs);
            }
        }

        // Connection info (JIT - ALWAYS clarify connection behavior)
        String logicalId = session.getLogicalId(nodeId);
        Map<String, Object> connectionInfo = new LinkedHashMap<>();

        if (autoConnected && connectAfter != null) {
            // Connected via explicit connect_after parameter
            String fromLogical = session.getLogicalId(connectAfter);
            connectionInfo.put("status", "CONNECTED");
            connectionInfo.put("from", fromLogical != null ? fromLogical : connectAfter);
            connectionInfo.put("how", "Explicit connect_after parameter");
            connectionInfo.put("for_fork", "To add parallel branches from same source: workflow(action='connect', from='" + (fromLogical != null ? fromLogical : connectAfter) + "', to='...')");
            connectionInfo.put("for_merge", "To converge branches: workflow(action='connect', from='" + logicalId + "', to='mcp:target')");
        } else if (!autoConnected) {
            // Not connected - ORPHANED (connect_after not specified)
            connectionInfo.put("status", "ORPHANED - NOT CONNECTED");
            connectionInfo.put("how", "No connect_after specified (MANDATORY for all nodes except first trigger)");
            connectionInfo.put("to_connect", "Use workflow(action='connect', from='<source label>', to=" + logicalId + ") to link this step");
            // Generic reminder about connect_after
            connectionInfo.put("NEXT_TIME", "Always use connect_after='<node label>' when adding nodes");
        }

        if (!connectionInfo.isEmpty()) {
            result.put("connection", connectionInfo);
        }


        // JIT: Loop context (if inside loop)
        if (parentLoopId != null && !parentLoopId.isBlank()) {
            result.put("🔁_LOOP_CONTEXT", Map.of(
                "status", "Inside loop body - executes on EVERY iteration",
                "close_loop", "When body is complete, close it: workflow(action='connect', from='" + label + "', to='" + parentLoopId + ":iterate')",
                "to_exit", "To add steps AFTER loop (one-time): use connect_after_loop='" + parentLoopId + "'"
            ));
        }

        // Next step guidance with generic example
        String normalizedLabel = WorkflowBuilderSession.normalizeLabel(label);
        result.put("NEXT", Map.of(
            "pattern", "workflow(action='add_node', type='...', label='...', params={...}, connect_after='" + label + "')",
            "this_step_output", "{{mcp:" + normalizedLabel + ".output.<field>}}",
            "get_params", "workflow(action='help', topics=['agent', 'mcp', 'decision', ...]) for required params"
        ));

        return result;
    }
}
