package com.apimarketplace.orchestrator.tools.workflow.builder;

import com.apimarketplace.agent.domain.ToolParameter;
import com.apimarketplace.agent.registry.AgentToolDefinition;
import com.apimarketplace.agent.registry.ToolCategory;
import com.apimarketplace.orchestrator.service.NodeLibraryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.apimarketplace.agent.registry.ToolSchemaGenerator.*;

/**
 * Factory for creating the unified "workflow" tool definition.
 *
 * Extracted from WorkflowBuilderProvider for Single Responsibility Principle.
 *
 * @see WorkflowBuilderProvider
 */
@Component
@RequiredArgsConstructor
public class WorkflowBuilderToolDefinitionFactory {

    private final NodeLibraryService nodeLibraryService;

    /**
     * Build the tool definition for the unified "workflow" tool.
     *
     * @return The tool definition
     */
    public AgentToolDefinition buildToolDefinition() {
        List<ToolParameter> params = List.of(
            stringParam("action", "Action: init, load, save, finish, add_node, connect, disconnect, modify, remove, undo, describe, validate, search, execute, get, list, delete, runs, get_run, wait_run, get_node_output, get_plan, set_plan, pin, unpin, publish, unpublish, resolve_approval, continue_interface, mock_suggest, help. " +
                "'wait_run' blocks until the run leaves the running state (or timeout_seconds elapses) and returns the same report as get_run - after an execute, prefer ONE wait_run over a get_run poll loop. " +
                "'finish' finalizes and saves the draft and CLOSES the build session - do NOT call any further workflow actions after a successful finish ('create' is a back-compat alias). " +
                "'pin' promotes a version to production (workflow_id + version; the version needs a successful run); 'unpin' clears it (production triggers stop firing until re-pinned). " +
                "'publish' lists the workflow on the marketplace (workflow_id + title; optional interface_id, visibility, credits_per_use - full rules incl. application auto-promotion in workflow(action='help')); 'unpublish' deactivates the listing (acquirers keep their copies). " +
                "'resolve_approval' resolves a USER APPROVAL on a paused run (run_id + decision='approved'|'rejected'; node_id optional when exactly one is pending; optional comment). 'continue_interface' advances a paused interface node past its __continue (run_id; node_id optional when one is paused; optional data). Both are gated by the chat authorization card, like execute.", true),
            stringParam("decision", "Approval decision for action='resolve_approval': 'approved' or 'rejected'.", false),
            stringParam("comment", "Optional note recorded with action='resolve_approval'.", false),
            objectParam("data", "Optional form/submit payload for action='continue_interface' (or extra data for resolve_approval).", false),
            stringParam("item_id", "Optional split-context item id (for: resolve_approval, continue_interface) when several per-item signals share a node.", false),
            stringParam("id", "Workflow UUID (for: load, execute)", false),
            stringParam("name", "Workflow name (for: init, save, finish)", false),
            stringParam("description", "Workflow description (for: init, save, finish, publish)", false),
            stringParam("type", "Node type for add_node. Triggers: form, webhook, schedule, table, manual, chat. Steps: agent, decision, switch, loop, split, fork, merge, transform, interface, code, http_request, send_email, email_inbox, response, stop, approval, data_input, download_file, public_link, media, wait. Or a tool UUID from catalog(action='search').", false),
            stringParam("label", "Node display name - used in connect_after and data references (for: add_node, modify, remove)", false),
            objectParam("params", "Node-specific parameters as {key: value}. Call workflow(action='help', topics=['<type>']) to see required params for each node type.", false),
            stringParam("connect_after", "Label of the predecessor node to connect from. MUST be set for every non-trigger node. For branching nodes, append port: 'MyDecision:if', 'MyFork:branch_0'. This is OUTSIDE params.", false),
            objectParam("mock", "(for: modify) Per-node mock: the node returns this instead of really executing, in editor runs (production/pinned fires always ignore mocks; pass mock_mode='off' on execute to ignore them for one run). This is OUTSIDE params, like connect_after. Exactly ONE of: {output: {...}} literal output matching the node's output schema; {source: 'catalog_example'} (mcp catalog-tool nodes only - serves the tool's default example response projected to its schema, no real call, no credentials); {error: {message: '...', output: {...}}} to simulate a FAILURE and test error paths. Branching nodes (decision/switch/option/approval cores, classify agents) take {port: 'if'|'case_0'|'approved'|'category_0'|...} instead of (or combined with) output. Any form also takes durationMs (simulated execution time in milliseconds, 0 to 600000 = 10 minutes max - the node takes that long before returning, and the run report's execution time reflects it). Add enabled=false to park a mock without deleting it. Pass mock={} to REMOVE the mock. mock_suggest gives you a ready-to-edit proposed output for any node. Full guide: workflow(action='help', topics=['mocking']).", false),
            stringParam("mock_mode", "(for: execute) Run-level mock override: omit for the DEFAULT (every node carrying an enabled mock returns it, all other nodes execute for real); 'off' = ignore ALL mocks this run without touching their config; 'all_mcp' = full dry-run (configured mocks + every mcp catalog-tool node without one serves its catalog example - no credentials needed). Refused with version='pinned'.", false),
            stringParam("from", "Source node label (for: connect, disconnect). Append port for branching: 'Check:if'", false),
            stringParam("to", "Target node label (for: connect, disconnect)", false),
            stringParam("node", "Node label to inspect/modify/remove (for: describe, modify, remove, mock_suggest)", false),
            stringParam("interface_id", "Interface UUID to reference. For add_node type='interface': the interface shown in the workflow. For publish: optional showcase/landing page presented on the marketplace listing.", false),
            arrayParam("interface_ids", "Interface UUIDs (for: add_node type='interface')", false),
            objectParam("plan", "Complete workflow plan JSON (for: set_plan)", false),
            stringParam("workflow_id", "Workflow UUID (for: get, delete, runs)", false),
            stringParam("run_id", "Run ID (for: get_run, wait_run, get_node_output)", false),
            intParam("timeout_seconds", "(for: wait_run) Max seconds to block waiting for the run. Default 120, max 240. On timeout the response has timed_out=true and the run keeps going - call wait_run again to keep waiting.", false, null),
            intParam("epoch", "Epoch number (for: get_run detail, get_node_output). Omit for macro overview in get_run.", false, null),
            stringParam("node_id", "Node ID to inspect (for: get_node_output). Use the node_id from get_run epoch detail.", false),
            intParam("item_index", "(for: get_node_output) Zoom into one item of a split fan-out. Omit to get a list of all items + status_counts. Mutually combinable with iteration / spawn.", false, null),
            intParam("iteration", "(for: get_node_output) Zoom into one loop iteration. Omit to get a list. Combinable with item_index.", false, null),
            intParam("spawn", "(for: get_node_output) Zoom into one re-run (spawn) of the node within the same epoch. Omit to get a list.", false, null),
            stringParam("field", "(for: get_node_output) Name (dot-path, e.g. 'output.image') of a large TEXT output field to read in full, paging past the 128 KB preview. Follow the NEXT pointer on a truncated field; combine with item_index/iteration/spawn to target one row.", false),
            intParam("max_bytes", "(for: get_node_output field-expand) Text window size, default & cap 128 KB.", false, null),
            intParam("limit", "Max results (for: list, runs). Default 25.", false, 25),
            intParam("offset", "Pagination offset (for: list); also the byte offset to expand a field from (for: get_node_output with field=)", false, 0),
            stringParam("query", "Filter workflows by name or description (for: list). Case-insensitive substring match, applied before pagination.", false),
            arrayParam("topics", "Node type names to get help for (for: help). Example: ['agent', 'decision', 'interface']", false),
            objectParam("data_inputs", "Trigger payload for execute. Chat: {\"message\": \"hello\"}. Form: {field1: val1}. Webhook: any JSON.", false),
            stringParam("trigger_id", "Normalized trigger ID to fire (for: execute). E.g. 'trigger:my_webhook'. Defaults to first fireable trigger.", false),
            ToolParameter.builder()
                .name("version")
                .type("string")
                .description("(for: execute / pin) Target plan version. For execute: omit for current canvas run, pass an integer to replay that historical version as an editor run, pass 'pinned' to fire the workflow's pinned production version (requires pinned_version != null + existing prod WAITING_TRIGGER run). For pin: pass the positive integer version to promote to production (version must have at least one run in {COMPLETED, WAITING_TRIGGER, RUNNING, PAUSED}).")
                .required(false)
                .build(),

            // ==================== Marketplace publication (publish, unpublish) ====================
            stringParam("title", "Marketplace listing title - REQUIRED for publish", false),
            stringParam("visibility", "Marketplace visibility (for: publish). One of PRIVATE, PUBLIC, UNLISTED (case-insensitive). Defaults to PRIVATE. PUBLIC workflow publications must be free (credits_per_use=0) - use PRIVATE or UNLISTED for paid workflows.", false),
            intParam("credits_per_use", "Credits charged to acquirers per use (for: publish). Default 0 (free). Must be 0 when visibility=PUBLIC.", false, 0),
            stringParam("category_id", "Marketplace category UUID (for: publish). Optional - groups the listing under a taxonomy category.", false),
            stringParam("showcase_run_id", "Run ID to showcase on the marketplace listing (for: publish). Optional - lets acquirers preview a real past execution before installing.", false)
        );

        return AgentToolDefinition.builder()
            .name("workflow")
            .description(nodeLibraryService.getQuickReference())
            .category(ToolCategory.WORKFLOW)
            .parameters(params)
            .requiredParameters(List.of("action"))
            .inputSchema(generateInputSchema(params, List.of("action")))
            .helpText(nodeLibraryService.getAlwaysAvailableHelp())
            .requiresAuth(true)
            .tags(List.of("workflow", "builder", "interactive"))
            // Must exceed wait_run's max blocking window (workflow.wait-run.max-timeout-seconds,
            // default 240s) or the agent loop's per-tool timeout kills the wait mid-way.
            // Every other workflow action returns in seconds; this only widens the safety net.
            .timeoutMs(300_000L)
            .build();
    }
}
