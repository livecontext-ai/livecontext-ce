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
            stringParam("action", "Action: init, load, save, finish, add_node, run_node, connect, disconnect, modify, remove, undo, describe, validate, search, execute, stop_run, restart_from_node, get, list, delete, runs, get_run, wait_run, get_node_output, get_plan, set_plan, pin, unpin, publish, unpublish, resolve_approval, continue_interface, mock_suggest, help. " +
                "'restart_from_node' re-runs ONE node of an existing run and continues from there, keeping everything the run already produced upstream (run_id + node, plus optional epoch to pick WHICH fire of the run to replay - default is the most recent). Prefer it over execute when a run got most of the way and one node produced the wrong result or failed: execute redoes the whole workflow and pays for it again. Read the response's 'outcome' before assuming the replay finished. Needs the user's authorization in an interactive chat, like execute: the ask happens inside your call, so it may take longer to answer - do not stop, do not announce that you are waiting, do not re-call, read the response. executed:false means nothing replayed. " +
                "'run_node' runs ONE node immediately from a config, with no workflow and no run: use it to check a config works before building it in. Params: type + params (the node config, read ONLY from params - no top-level argument is used as config) + optional run_input and label. Pass run_inputs=[{...}, ...] instead of run_input to run the same config over up to 20 inputs in this one call. Nothing is saved; the response echoes the config back with credentials redacted so you can paste it into add_node. Types that pause on a signal (approval, interface, wait) or that only mean something inside a DAG (merge, aggregate, split, fork, loop) are refused with an explanation. Needs the user's authorization in an interactive chat, like execute: the ask happens inside your call, so it may take longer to answer - do not stop, do not announce that you are waiting, do not re-call, read the response. Output + duration_ms means it ran, or with run_inputs the per-entry statuses do; executed:false means it did not. Unavailable outside a conversation. " +
                "'wait_run' blocks until the run leaves the running state (or timeout_seconds elapses) and returns the same report as get_run - after an execute, prefer ONE wait_run over a get_run poll loop. " +
                "'stop_run' is the counterpart of execute: it ends a run that is still going (run_id + optional reason + mode), cancelling the nodes still in flight including any browser-agent session. Use it as soon as you can tell the run went wrong instead of waiting for it to finish. An agent running INSIDE a workflow can omit run_id to stop its own run. " +
                "'finish' finalizes and saves the draft and CLOSES the build session - do NOT call any further workflow actions after a successful finish ('create' is a back-compat alias). " +
                "'pin' promotes a version to production (workflow_id + version); the version does NOT need to have been run first - pin prepares the production run itself. 'unpin' clears it (production triggers stop firing until re-pinned). Both need the user's authorization in an interactive chat, like execute, because pin hands that version every trigger its plan declares (schedules and webhooks start firing on their own) and unpin takes them all off the air: the ask happens inside your call, so it may take longer to answer - do not stop, do not announce that you are waiting, do not re-call, read the response. executed:false means the workflow is still on whatever version it was on and no trigger was armed or disarmed. " +
                "'publish' lists the workflow on the marketplace (workflow_id + title; optional interface_id, visibility, credits_per_use - full rules incl. application auto-promotion in workflow(action='help')); 'unpublish' deactivates the listing (acquirers keep their copies). " +
                "'resolve_approval' resolves a USER APPROVAL on a paused run (run_id + decision='approved'|'rejected'; node_id optional when exactly one is pending; optional comment). 'continue_interface' advances a paused interface node past its __continue (run_id; node_id optional when one is paused; optional data). Both need the user's authorization in an interactive chat, like execute: the ask happens inside your call, so it may take longer to answer - do not stop, do not announce that you are waiting, do not re-call, read the response. executed:false means nothing was resolved or advanced.", true),
            objectParam("run_input", "(for: run_node) Upstream data made visible to the node, as {name: value}. Both the trigger data and the step outputs are fed from it, so a {{...}} template resolves the same way it would inside a workflow.", false),
            arrayParam("run_inputs", "(for: run_node) Run the SAME config once per entry, in parallel, in this ONE call. Each entry is a run_input map, so a {{...}} template in params resolves per entry. Send real objects: an entry that is not one is refused by index. Give run_inputs OR run_input, never both. 1 to 20 entries. The response is per entry instead of one output: item_count, completed, failed, timed_out, not_started, awaiting_signal, unrecognised (only when an entry ended in a state this build does not know), status ('completed' = every entry completed, 'partial' = some did, 'timed_out' / 'awaiting_signal' = none completed and they all ended that way, 'unknown' = anything else, which covers entries that ended in different ways and entries in a state this build does not know) and items=[{index, status, duration_ms, output or error, note}] - there is NO top-level output, read items[].output. The per-entry status is UPPERCASE and a different vocabulary from the batch one: COMPLETED, FAILED, TIMED_OUT, NOT_STARTED or AWAITING_SIGNAL, and `note` carries what to do about an entry when there is something to say. total_item_duration_ms is the SUM of the entries, not how long the call took. Up to 5 entries run at once and the whole call shares ONE 120-second budget, so 20 entries run as four waves inside it and you should size the batch by what one entry costs rather than by the cap: one entry failing does not stop the others, an entry still running when that runs out comes back TIMED_OUT and MAY already have had its effect (treat it as unknown, resending can double it), and an entry that never started comes back NOT_STARTED, which had no effect and is safe to resend. The call fails, with the reason in the message and NO body, in exactly two cases: every entry ran and failed, or none of them ever started. Any other outcome succeeds, so read status and the per-entry statuses rather than the success flag. Note this differs from the single run_input call, where a node that timed out or suspended makes the call itself fail: with run_inputs the per-entry report is what carries that. Each entry costs exactly what running it alone would cost. One more thing to size by: the response carries every entry's output in ONE tool result, so a batch of large outputs is a large result, and only the most recent results are kept in full as the conversation goes on. Batch entries whose outputs you will read now.", false, "object"),
            stringParam("decision", "Approval decision for action='resolve_approval': 'approved' or 'rejected'.", false),
            stringParam("comment", "Optional note recorded with action='resolve_approval'.", false),
            objectParam("data", "Optional form/submit payload for action='continue_interface' (or extra data for resolve_approval).", false),
            stringParam("item_id", "Optional split-context item id (for: resolve_approval, continue_interface) when several per-item signals share a node.", false),
            stringParam("id", "Workflow UUID (for: load, execute)", false),
            stringParam("name", "Workflow name (for: init, save, finish)", false),
            stringParam("description", "Workflow description (for: init, save, finish, publish)", false),
            stringParam("type", "Node type for add_node. Triggers: form, webhook, schedule, table, manual, chat. Steps: agent, decision, switch, loop, split, fork, merge, transform, interface, code, http_request, send_email, email_inbox, response, stop, approval, data_input, download_file, public_link, media, generate, wait. Or a tool UUID from catalog(action='search').", false),
            stringParam("label", "Node display name - used in connect_after and data references (for: add_node, modify, remove)", false),
            objectParam("params", "Node-specific parameters as {key: value}. Call workflow(action='help', topics=['<type>']) to see required params for each node type.", false),
            stringParam("connect_after", "Label of the predecessor node to connect from. MUST be set for every non-trigger node. For branching nodes, append port: 'MyDecision:if', 'MyFork:branch_0'. This is OUTSIDE params.", false),
            objectParam("mock", "(for: modify) Per-node mock: the node returns this instead of really executing, in editor runs (production/pinned fires always ignore mocks; pass mock_mode='off' on execute to ignore them for one run). This is OUTSIDE params, like connect_after. Exactly ONE of: {output: {...}} literal output matching the node's output schema; {source: 'catalog_example'} (mcp catalog-tool nodes only - serves the tool's default example response projected to its schema, no real call, no credentials); {error: {message: '...', output: {...}}} to simulate a FAILURE and test error paths. Branching nodes (decision/switch/option/approval cores, classify agents) take {port: 'if'|'case_0'|'approved'|'category_0'|...} instead of (or combined with) output. Any form also takes durationMs (simulated execution time in milliseconds, 0 to 600000 = 10 minutes max - the node takes that long before returning, and the run report's execution time reflects it). Add enabled=false to park a mock without deleting it. Pass mock={} to REMOVE the mock. mock_suggest gives you a ready-to-edit proposed output for any node. Full guide: workflow(action='help', topics=['mocking']).", false),
            objectParam("nodePolicy", "(for: add_node, modify) How this node behaves on failure. OUTSIDE params, like connect_after and mock, because it is not a parameter of the node type. All fields optional: retryCount (extra attempts after a failed one, default 0, so total attempts = retryCount + 1); retryBackoffMs (wait between attempts, default 0); timeoutMs (bound on ONE attempt, default 0 = unbounded - a timed-out attempt counts as a failed attempt, so it composes with retryCount, and the abandoned work is NOT rolled back: anything it already sent stays sent); continueOnFailure (default false - when every attempt fails the node is still FAILED, but its successors run instead of being SKIPPED; refused on decision, switch and option nodes, where a failure selected no port); executeOnce (default false - inside a split, execute for item 0 only and mark the other items SKIPPED; a no-op outside a split, and it does NOT limit loop iterations; refused on split, aggregate, merge and loop nodes); providerRetryMaxWaitSec (CATALOG TOOL STEPS ONLY - how long ONE provider call may spend waiting out a rate-limit refusal, in SECONDS; omit to leave the platform budget in place, which honours the delay a 429 asks for; 0 = never wait, the call fails on the first refusal and YOUR retry or loop does the pacing. It can only TIGHTEN the platform budget (10 seconds by default): a larger number is capped at it, because the step is waiting inside one HTTP call and sleeping past that window would fail the step while the provider call went through and was charged. timeoutMs bounds it too - a node with a per-attempt timeout gets half that window, and a value you set yourself is capped at the same ceiling). Set providerRetryMaxWaitSec=0 whenever the workflow already paces itself, because the two layers MULTIPLY: a node set to retryCount=2 (3 attempts) around a call the platform re-sends twice is up to 9 requests to a provider that asked you to slow down. retryCount > 0 implies it without you setting it. A retried node re-runs its side effects (emails, writes, inserts), which is why retrying is opt-in per node. Billing: one node execution costs ONE credit however many attempts it took, but an agent node pays its LLM tokens per attempt. While a provider call is being re-sent the node stays RUNNING and emits nothing; get_node_output reports _provider_retries afterwards. Not available on trigger or note nodes, and providerRetryMaxWaitSec is refused on anything that is not a catalog tool step (AI, core, table and interface nodes retry on their own terms: use retryCount there). Pass nodePolicy={} to REMOVE the policy. Full guide: workflow(action='help', topics=['node_policy']).", false),
            stringParam("mock_mode", "(for: execute) Run-level mock override: omit for the DEFAULT (every node carrying an enabled mock returns it, all other nodes execute for real); 'off' = ignore ALL mocks this run without touching their config; 'all_mcp' = full dry-run (configured mocks + every mcp catalog-tool node without one serves its catalog example - no credentials needed). Refused with version='pinned'.", false),
            stringParam("from", "Source node label (for: connect, disconnect). Append port for branching: 'Check:if'", false),
            stringParam("to", "Target node label (for: connect, disconnect)", false),
            stringParam("node", "Node to act on. For describe, modify, remove, mock_suggest: the node's display LABEL. For restart_from_node: the node KEY exactly as get_run reports it, e.g. 'mcp:fetch_data'.", false),
            stringParam("interface_id", "Interface UUID to reference. For add_node type='interface': the interface shown in the workflow. For publish: optional showcase/landing page presented on the marketplace listing.", false),
            arrayParam("interface_ids", "Interface UUIDs (for: add_node type='interface')", false),
            objectParam("plan", "Complete workflow plan JSON (for: set_plan)", false),
            stringParam("workflow_id", "Workflow UUID (for: get, delete, runs)", false),
            stringParam("run_id", "Run ID (for: get_run, wait_run, stop_run, restart_from_node, get_node_output). On stop_run, omit ONLY when you are an agent running inside a workflow and want to stop your own run.", false),
            stringParam("reason", "(for: stop_run) Why you are stopping the run, in one sentence. Recorded on the run and returned by get_run as stop_reason, so the user and any agent reading the run later see the cause instead of a bare CANCELLED.", false),
            stringParam("mode", "(for: stop_run) 'cancel' (default) ends the run for good AND suspends the schedules of the workflow it belongs to, so a scheduled workflow stops firing until it is reactivated. 'graceful' only closes the epoch that is running and returns the run to WAITING_TRIGGER, leaving the schedules alone: prefer it when you only want to end THIS execution. 'graceful' is refused when the run is PENDING or WAITING_TRIGGER (nothing is executing yet).", false),
            intParam("timeout_seconds", "(for: wait_run) Max seconds to block waiting for the run. Default 120, max 240. On timeout the response has timed_out=true and the run keeps going - call wait_run again to keep waiting.", false, null),
            intParam("epoch", "Epoch number (for: get_run detail, get_node_output, restart_from_node). Omit for macro overview in get_run. On restart_from_node it picks WHICH fire of the run to replay - omit it to replay the most recent one, which is what you want unless an OLDER epoch is the one that failed.", false, null),
            stringParam("node_id", "Node ID to inspect (for: get_node_output). Use the node_id from get_run epoch detail.", false),
            intParam("item_index", "(for: get_node_output) Zoom into one item of a split fan-out. Omit to get a list of all items + status_counts. Mutually combinable with iteration / spawn.", false, null),
            intParam("iteration", "(for: get_node_output) Zoom into one loop iteration. Omit to get a list. Combinable with item_index.", false, null),
            intParam("spawn", "(for: get_node_output) Zoom into one re-run (spawn) of the node within the same epoch. Omit to get a list.", false, null),
            stringParam("field", "(for: get_node_output) Name (dot-path, e.g. 'output.image') of a large TEXT output field to read in full, paging past the 128 KB preview. Follow the NEXT pointer on a truncated field; combine with item_index/iteration/spawn to target one row.", false),
            intParam("max_bytes", "(for: get_node_output field-expand) Text window size, default & cap 128 KB.", false, null),
            intParam("limit", "Max results (for: list, runs). Default 25.", false, 25),
            intParam("offset", "Pagination offset (for: list); also the byte offset to expand a field from (for: get_node_output with field=)", false, 0),
            stringParam("query", "Filter workflows by name or description (for: list). Case-insensitive substring match, applied before pagination.", false),
            arrayParam("node_types", "Keep only workflows that CONTAIN one of these node types (for: list). "
                + "Applied before pagination, so total reflects the filtered set. Several values mean ANY of them, not all, and at most 50 are read (extra values are ignored). "
                + "Tokens: 'mcp:<integration>' (mcp:gmail, mcp:slack), 'core:<type>' (core:loop, core:code, core:http_request), "
                + "'agent:<type>' (agent:agent, agent:guardrail, agent:classify), 'trigger:<type>' (trigger:webhook, trigger:schedule), "
                + "'table:<type>' (table:create-row, table:find), and 'interface'. "
                + "Every item returned by list echoes its own node_types, so run list once without this filter to discover the exact tokens in this workspace "
                + "rather than guessing an integration's spelling.", false),
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
