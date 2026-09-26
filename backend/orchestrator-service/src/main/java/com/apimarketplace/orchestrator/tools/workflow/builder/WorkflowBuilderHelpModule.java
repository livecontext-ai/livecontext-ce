package com.apimarketplace.orchestrator.tools.workflow.builder;

import com.apimarketplace.agent.registry.AgentToolDefinition;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.agent.tools.common.ToolModule;
import com.apimarketplace.orchestrator.tools.workflow.WorkflowHelpProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Help module for the workflow tool.
 * Provides documentation on available actions, workflow lifecycle, and examples.
 *
 * <p>When topics are provided, delegates to {@link WorkflowHelpProvider} for node-specific
 * documentation (params, outputs, examples). Without topics, returns the generic builder overview.</p>
 */
@Component
@RequiredArgsConstructor
public class WorkflowBuilderHelpModule implements ToolModule {

    private final WorkflowHelpProvider workflowHelpProvider;

    @Override
    public List<AgentToolDefinition> getToolDefinitions() {
        return List.of();
    }

    @Override
    public boolean canHandle(String action) {
        return "help".equals(action);
    }

    @Override
    public Optional<ToolExecutionResult> execute(String action, Map<String, Object> parameters,
                                                  String tenantId, ToolExecutionContext context) {
        if (!"help".equals(action)) return Optional.empty();

        // When topics are provided, delegate to WorkflowHelpProvider for node-specific docs
        Object topics = parameters.get("topics");
        if (topics != null) {
            Map<String, Object> topicHelp = workflowHelpProvider.getHelp(topics);
            if (topicHelp != null && !topicHelp.isEmpty()) {
                return Optional.of(ToolExecutionResult.success(topicHelp));
            }
        }

        return Optional.of(executeHelp());
    }

    private ToolExecutionResult executeHelp() {
        Map<String, Object> result = new LinkedHashMap<>();

        result.put("description", """
            WORKFLOW BUILDER - Interactive step-by-step workflow construction tool.

            Build workflows by adding nodes, connecting them, and validating the result.
            The builder maintains an in-memory session until you save or create the workflow.

            GETTING STARTED:
            - workflow(action='init', name='My Workflow') - start a new workflow session
            - workflow(action='load') - edit an existing workflow (auto-detects from context)
            - workflow(action='load', id='<uuid>') - edit a specific workflow

            Both init and load return comprehensive info: available node types, rules, variable syntax, and actions.

            LIFECYCLE:
            1. INIT or LOAD - start a session
            2. ADD NODES - add trigger first, then steps (always use connect_after)
            3. CONNECT - edges are auto-created by connect_after, use connect/disconnect for manual wiring
            4. VALIDATE - check the workflow is valid
            5. CREATE or SAVE - finalize or save as draft
            6. EXECUTE - run the workflow
            """);

        result.put("actions", buildActionDocs());
        result.put("node_creation", buildNodeCreationDocs());
        result.put("examples", buildExamples());

        result.put("tips", List.of(
            "SEQUENTIAL: Call workflow ONE AT A TIME - wait for each result before the next call",
            "TRIGGER FIRST: Every workflow starts with a trigger (form, webhook, schedule, table, manual, chat, workflow, error)",
            "CONNECT_AFTER: Every non-trigger node MUST have connect_after='PreviousNodeLabel' or it becomes an orphan",
            "connect_after is OUTSIDE params: workflow(action='add_node', type='...', label='X', params={...}, connect_after='Y')",
            "CONNECT_AFTER vs PLAN EDGES: connect_after uses the RAW label (e.g. 'Check Priority:if'). get_plan returns RAW labels. set_plan accepts BOTH raw labels and normalized keys (e.g. 'core:check_priority:if'). Normalization: lowercase, spaces→underscores, accents→ascii.",
            "DATA FLOW: Connection alone does NOT pass data - use {{type:label.output.field}} syntax in parameters",
            "HELP BEFORE ADD: Call workflow(action='help', topics=['<node_type>']) before adding a node to see required params",
            "AGENT: Must create agent first with agent(action='create'), then reference its UUID via agent_id in params. In set_plan JSON, the field is agentConfigId (not agent_id).",
            "INTERFACE: Must create interface first with interface(action='create'), then reference its UUID in params",
            "MULTIPLE TRIGGERS: Triggers with UNIQUE labels. Triggers with NO overlapping descendants = independent DAGs. Triggers whose descendants overlap are auto-grouped as ONE shared DAG (not rejected). Each trigger still fires its OWN epoch: a shared sink (any node - transform/mcp/merge/interface/…) with multiple incoming trigger edges executes ONCE per trigger fire - the engine filters out the other trigger predecessors in this epoch. Interfaces additionally persist per-trigger UI state. Use workflow(action='help', topics=['multi_dag']) for details."
        ));

        result.put("related_tools", Map.of(
            "help_topics", "workflow(action='help', topics=['agent', 'decision', 'interface']) - detailed documentation for specific node types",
            "interface", "interface(action='help') - how to create interfaces for workflow nodes",
            "application", "application(action='create') - publish a workflow as an application after building it"
        ));

        return ToolExecutionResult.success(result);
    }

    private Map<String, Object> buildActionDocs() {
        Map<String, Object> actions = new LinkedHashMap<>();

        Map<String, String> sessionMgmt = new LinkedHashMap<>();
        sessionMgmt.put("init", "Start a new workflow session. Params: name (optional), description (optional), force (boolean, override existing)");
        sessionMgmt.put("load", "Load existing workflow for editing. Params: id (UUID, auto-detected from context if omitted)");
        sessionMgmt.put("list", "List all workflows. Params: limit, offset, query (name/description substring), node_types (keep only workflows containing one of these node types, ANY-of). Each item reports its own node_types, so list once without the filter to read the exact tokens.");
        sessionMgmt.put("save", "Save current session as a draft. Params: name (optional override), description (optional)");
        sessionMgmt.put("discard", "Abandon current session without saving");
        sessionMgmt.put("finish", "Finalize and save the workflow as ACTIVE. Validates first, then persists. " +
            "CLOSES the build session - do not call any further workflow(action=...) after this. " +
            "Params: name (optional override). Note: 'create' is kept as a legacy alias.");
        sessionMgmt.put("execute", "Run the workflow's currently saved plan. You do NOT have to call finish first: "
            + "every editing action (add_node, connect, modify, remove, ...) auto-saves the draft, so your edits "
            + "are what runs. Params: data_inputs (optional). "
            + "Counterpart: 'stop_run' ends a run that is still going - see actions.run_inspection.stop_run.");
        actions.put("session_management", sessionMgmt);

        Map<String, String> runInspection = new LinkedHashMap<>();
        runInspection.put("runs", "List recent runs of a workflow. Params: workflow_id (required), limit");
        runInspection.put("get_run", "Snapshot of a run, without blocking. Params: run_id (required); " +
            "omit epoch for the macro overview, pass epoch=N for that epoch's per-node detail.");
        runInspection.put("wait_run", "Block until the run finishes (or needs input), then return the same report as get_run. " +
            "Params: run_id (required), timeout_seconds (default 120, max 240). " +
            "Returns as soon as the run leaves the running state - including paused/awaiting-input states, since those wait on " +
            "an action from you or the user. On timeout the response has timed_out=true and the run keeps going: call wait_run " +
            "again to keep waiting. After an execute, ONE wait_run replaces a get_run poll loop.");
        runInspection.put("get_node_output", "Full output/error of a single node in a run. Params: run_id, epoch, node_id " +
            "(+ optional item_index / iteration / spawn / field paging)");
        runInspection.put("stop_run", "End a run that is still going - the counterpart of execute. " +
            "Use it as soon as you can tell the execution went wrong (wrong input, a browser agent stuck on a login wall, " +
            "a loop that will not converge) instead of waiting for it to finish or letting wait_run time out. " +
            "Params: run_id (required, EXCEPT when aborting your own run - see SELF-ABORT below), " +
            "reason (optional but recommended - one sentence, recorded on the run and returned by " +
            "get_run as stop_reason), mode (optional). " +
            "Nodes still in flight are cancelled, including any browser-agent session, and no further node starts. " +
            "CHOOSING THE MODE: mode='cancel' (the default) is WORKFLOW-level, not just run-level - it ends the run for good " +
            "AND SUSPENDS THE SCHEDULES of the workflow the run belongs to, so a scheduled workflow stops firing until someone " +
            "reactivates it. mode='graceful' is run-level: it closes the epoch that is running, returns the run to " +
            "WAITING_TRIGGER and leaves the workflow's schedules alone. Prefer 'graceful' when you only want to end THIS " +
            "execution of a workflow that keeps running on a schedule or webhook; use 'cancel' when the workflow itself must " +
            "stop. 'graceful' only accepts a run that is currently executing (RUNNING or PAUSED, which includes a run " +
            "parked on an approval, an interface or a timer - those keep the run running): it is refused on a run that " +
            "is PENDING or WAITING_TRIGGER, i.e. nothing is executing yet, and the error tells you to use 'cancel'. " +
            "Response: run_id, status, previous_status, mode, stopped_by, reason; a run that had already ended comes back " +
            "with already_terminal=true and nothing is changed; a run that has not started executing yet cannot be stopped " +
            "at all. " +
            "SELF-ABORT: an agent running INSIDE a workflow can omit run_id to stop the run it is executing inside. The " +
            "response then carries self=true, and you must stop working and report the reason, because none of your further " +
            "tool calls will run. As a sub-agent that means the WHOLE parent run, not just your own step. Passing a run_id " +
            "that is not a run id string is refused, never taken as a self-abort. " +
            "stop_run is a WRITE action: an agent granted read-only access to workflows cannot stop a run, not even its own.");
        runInspection.put("restart_from_node", "Re-run ONE node of an existing run and continue from there, keeping " +
            "everything the run already produced upstream of it. Use it instead of execute when a run got most of the way " +
            "and one node produced the wrong result or failed: execute fires the workflow from its trigger and redoes all " +
            "the work (and pays for it again), restart_from_node redoes only that node and what depends on it. " +
            "Params: run_id (required), node (required - the node key exactly as get_run reports it, e.g. 'mcp:fetch_data'), " +
            "epoch (optional - WHICH fire of the run to replay). " +
            "WHICH EPOCH: a run that is fired several times keeps one epoch per fire, each with its own outputs. Omit epoch " +
            "and the replay lands on the run's most recent one, which is what you want in the normal case. Pass epoch=N " +
            "(exactly as get_run reports it) to repair an OLDER fire: without it you would replay a fire that was already " +
            "correct while the broken one stays untouched. A chosen epoch is REFUSED, not ignored, when: it never executed " +
            "on this run (unknown, or only staged for the next fire); the node you named did not run in it, or ran there " +
            "only on a branch that was not taken; another epoch of the same trigger is still executing (wait for the run to " +
            "settle, or stop_run first - the same call then works); or the run is stepped, where nothing would close the " +
            "epoch again. The response's epoch field tells you which one actually ran, so check it. " +
            "WHAT IT TOUCHES: the node you name and every node downstream of it are cleared and re-executed; nodes upstream " +
            "keep their outputs, and templates pointing at them ({{mcp:earlier_node.output.field}}) resolve to those same " +
            "values. The run's history is kept: each attempt is a new 'spawn', and get_node_output still returns earlier ones. " +
            "WHEN IT IS REFUSED, each with an explanation you can act on: a node that has never run in this run (use execute); " +
            "a node that is executing RIGHT NOW on this run (wait for it to settle, or stop_run first); a node whose branch " +
            "was SKIPPED (the branch was not taken, so restart from the decision node above it instead); and a run that was " +
            "CANCELLED or timed out (reviving it is a re-trigger decision - use execute, since the stop also suspended the " +
            "workflow's schedules and a restart does not bring them back). A node parked on an approval, an interface or a " +
            "timer IS restartable: its pending wait is cancelled and re-created. " +
            "READ THE outcome FIELD, do not assume the work is done: 'QUIESCED' = the replay finished; 'YIELDED' = it stopped " +
            "on something that needs resolving (approval, interface, timer, or an agent still working), so poll get_run and " +
            "use resolve_approval / continue_interface; 'STEP_BY_STEP' = this run is stepped, so NOTHING was executed and the " +
            "node is simply queued for the user; 'WAVE_CAP' = the replay hit the safety limit and is INCOMPLETE; " +
            "'NOTHING_TO_RUN' / 'UNAVAILABLE' = the reset happened but nothing advanced. The response also carries " +
            "replayed_nodes (what was cleared), epoch, attempt, status, and a one-sentence summary. " +
            "restart_from_node is a WRITE action, and on an automatic run it re-executes nodes without asking again: an " +
            "agent granted read-only access to workflows cannot call it. In an interactive chat it needs the user's " +
            "authorization first, like execute: the ask happens inside your call, so it may take longer to answer - do " +
            "not stop, do not announce that you are waiting, do not re-call. Read the response: an outcome means it " +
            "replayed, executed:false means nothing did.");
        runInspection.put("present", "Choose what the user is looking at, like moving to the next slide (the action is " +
            "'present', not 'show': 'show' means describe). Changes nothing; it opens the view in the user's side panel. " +
            "Views: 'application' + run_id (the run's interfaces filled with its data; refused when the workflow has no " +
            "interface node), 'run' + run_id (the run's steps), 'workflow' + workflow_id. Optional title. " +
            "Response: presented, plus the ids. A table, an interface, an agent or a file is presented by its own tool: " +
            "table(action='present', table_id), interface(action='present', interface_id), agent(action='present', " +
            "agent_id), files(action='present', file_id). WHAT TO SHOW: the result the user asked for, not the machinery. " +
            "In order: the application when the workflow has an interface, else the table it fills, else the file it " +
            "produced, else the run. Present once per new result, after it exists (after execute or wait_run): moving " +
            "the screen on every step makes it jump.");
        actions.put("run_inspection", runInspection);

        actions.put("node_operations", Map.of(
            "add_node", "Add a node. Params: type (required), label (required), params={...}, connect_after (label of predecessor)",
            "modify", "Modify a node's params. Params: node (label), params={...}, connect_after (optional - replaces the incoming connection with a new one from the specified predecessor). params MERGES into the existing node (keys you omit are kept). To DELETE a param key, set it to null, e.g. params={AccountSid: null}. The response's changes.after shows the node's real post-merge value, so check it to confirm a deletion took effect.",
            "remove", "Remove a node. Shows disconnection info and reconnection hints. Params: node (label)",
            "undo", "Undo the last action",
            "run_node", "Run ONE node right now from a config, with no workflow and no run - use it to "
                + "check a config before you build it in. Params: type (required), params={...} (the node "
                + "config; it is read ONLY from params, no other argument is used as config), run_input={...} "
                + "(optional upstream data, so {{...}} templates resolve as they would in a workflow), label "
                + "(optional). Nothing is saved. The response gives status, output (same field names a "
                + "workflow would produce, so templates you write against it will resolve), duration_ms, and "
                + "the config echoed back with credentials redacted so you can paste it into add_node. "
                + "run_inputs=[{...}, ...] runs the SAME config once per entry, in parallel, in this one call: each entry "
                + "is a run_input map, so a {{...}} template in params resolves per entry. Give run_inputs OR run_input, "
                + "never both; 1 to 20 entries. With run_inputs the response is per entry instead of one output - "
                + "item_count, completed, failed, timed_out, not_started, awaiting_signal, status ('completed' | 'partial' | "
                + "'timed_out' | 'awaiting_signal' | 'unknown') and "
                + "items=[{index, status, duration_ms, output or error}] - and there is NO top-level output, so read "
                + "items[].output. total_item_duration_ms is the SUM of the entries, not how long the call took, "
                + "and unrecognised appears only when an entry ended in a state this build does not know. "
                + "The per-entry status is UPPERCASE and a different vocabulary from the batch one: "
                + "COMPLETED, FAILED, TIMED_OUT, NOT_STARTED or AWAITING_SIGNAL, and an entry can carry a `note` "
                + "saying what to do about it. Up to 5 entries run at once and the whole call shares ONE 120-second budget, so 20 entries run as four waves inside it: entries still running when it ends come back TIMED_OUT and entries that never got a turn come back NOT_STARTED. Size the batch by what one entry costs, not by the entry cap. The response also carries every entry's output in ONE tool result, and only the most recent results stay in full as the conversation goes on, so batch entries whose outputs you will read now. The batch status is 'completed' when every entry completed, 'partial' when some did, 'timed_out' or 'awaiting_signal' when none did and they all ended that way, and 'unknown' for everything else, which covers entries that ended in different ways and entries in a state this build does not know. One entry failing "
                + "does not stop the others; an entry still running when the time runs out comes back TIMED_OUT and "
                + "MAY already have had its effect, so treat it as unknown and know that resending can double it, "
                + "while an entry that never started comes back NOT_STARTED, had no effect, and is safe to resend. "
                + "The call fails, with the reason in the message and NO body, in exactly two cases: every entry ran "
                + "and failed, or none of them ever started; anything else succeeds, so read status rather than "
                + "the success flag - unlike the single run_input call, where a node that timed out or "
                + "suspended makes the call itself fail. Each entry costs exactly what running it alone would cost. For more "
                + "than 20 inputs, or to keep the results, build the node into a workflow behind a split node. "
                + "Types that pause on a signal (approval, interface, wait) and types whose meaning is the "
                + "surrounding graph (merge, aggregate, split, fork, loop) are refused with the reason. The "
                + "user is asked to authorize it, as for execute: the ask happens inside your call, so it "
                + "may take longer to answer. Read what comes back rather than waiting or re-calling. "
                + "A response carrying output + duration_ms is the proof it ran, and with run_inputs the "
                + "per-entry statuses are; `executed:false` means it did not. A top-level `status` alone "
                + "settles nothing on the single-entry call, since the refusal has one too."
        ));

        actions.put("connection_operations", Map.of(
            "connect", "Create an edge. Params: from (label), to (label). For ports: from='Decision:if'. "
                + "Connecting BACK to a node that already ran makes that part repeat (a loop). Loop params: "
                + "max_iterations (cap for this loop), loop_condition (expression re-checked before each "
                + "repeat), loop_back=true (declare a loop the graph cannot infer yet, e.g. you wired the "
                + "closing connection first), loop_back=false (refuse the loop, keep a plain connection). "
                + "Put the loop-back on a BRANCH (from='Check:else'): a loop from a node that also continues "
                + "forward never runs. Reaching the cap while the loop still wants to run fails the run.",
            "disconnect", "Remove an edge. Params: from (label), to (label)"
        ));

        actions.put("inspection", Map.of(
            "describe", "View full config of a node or the whole workflow. Params: node (optional label)",
            "validate", "Check if the workflow is valid and ready to create",
            "search", "Search available node types from the catalog. Params: query, category, limit"
        ));

        actions.put("advanced", Map.of(
            "get_plan", "Export workflow as JSON plan",
            "set_plan", "Import a workflow from a JSON plan. Params: plan={...}"
        ));

        Map<String, String> marketplace = new LinkedHashMap<>();
        marketplace.put("publish", "Add the workflow to the marketplace. Params: workflow_id (required), title (required), " +
            "description (optional), interface_id (optional - UUID of an interface used as the public-facing landing/showcase page), " +
            "visibility (PRIVATE default, PUBLIC, UNLISTED), credits_per_use (default 0). " +
            "APPLICATION AUTO-PROMOTION: if the workflow has interface nodes and you omit interface_id, publish auto-selects the entry " +
            "interface as the showcase + the latest successful run + flags display_mode=APPLICATION, so the listing renders as a usable app " +
            "(a publication with no showcase interface is NOT an application and its app preview stays blank). The response then carries " +
            "display_mode, showcase_interface_id, showcase_run_id, and an auto_application note. If the workflow has no successful run yet, it " +
            "still publishes as an application but the app preview stays empty until you run it (workflow(action='execute')). A workflow with no " +
            "interface stays a plain workflow listing. (application(action='create') is the equivalent app-first path and resolves the same things.) " +
            "RULE: PUBLIC workflow publications must be free (credits_per_use=0) - use PRIVATE or UNLISTED for paid workflows. " +
            "Response: status='PUBLISHED', publication_id, visibility, credits_per_use. " +
            "PUBLIC publications go through a platform review before becoming visible to acquirers; PRIVATE/UNLISTED activate immediately.");
        marketplace.put("unpublish", "Mark the marketplace listing inactive. Params: workflow_id (required). " +
            "Existing acquirers keep their copies - only new installs are blocked. Fails if the workflow has no active publication.");
        actions.put("marketplace", marketplace);

        return actions;
    }

    private Map<String, Object> buildNodeCreationDocs() {
        Map<String, Object> docs = new LinkedHashMap<>();

        docs.put("syntax", "workflow(action='add_node', type='<type>', label='<name>', params={...}, connect_after='<previous>')");

        docs.put("trigger_types", Map.of(
            "form", "User fills out a form to start the workflow",
            "webhook", "External HTTP call triggers the workflow",
            "schedule", "Time-based trigger (cron)",
            "table", "Row change in a data table fires the workflow instantly (event-driven, no polling). " +
                "Params: table_id (required), event_types (optional, default all three: ['row_created','row_updated','row_deleted']), " +
                "filter (optional single-condition {column, operator, value}; operators: =, !=, >, >=, <, <=, in, not_in, contains, starts_with, ends_with, is_null, is_not_null). " +
                "Trigger output flattens row columns at top level ({{trigger:label.output.<column>}}) plus event_type, row_id, row, " +
                "previous_row (only for row_updated; null otherwise), datasource_id, triggered_at.",
            "manual", "Manual trigger with no inputs",
            "chat", "Chat message triggers the workflow",
            "workflow", "Triggered by another workflow",
            "error", "Triggered by parent workflow failure (system-managed, not fireable by agent)"
        ));

        Map<String, String> stepTypes = new LinkedHashMap<>();
        // AI nodes
        stepTypes.put("agent", "AI agent - requires agent entity (agent_id from agent(action='create')). In set_plan JSON: use agentConfigId instead of agent_id.");
        stepTypes.put("classify", "AI classification - routes input to categories (agent-based)");
        stepTypes.put("guardrail", "Content check: keyword, regex, length, PII, custom and competitor rules checked exactly; toxicity, injection and topics judged by AI. Any violation routes to fail; per rule block, flag or sanitize (sanitize also redacts)");
        // Control flow
        stepTypes.put("decision", "If/else branching based on conditions");
        stepTypes.put("switch", "Multi-case branching (like switch/case)");
        stepTypes.put("loop", "Loop while condition is true");
        stepTypes.put("split", "Fan-out: process each item in a list in parallel");
        stepTypes.put("fork", "Parallel execution of ALL branches simultaneously");
        stepTypes.put("merge", "Wait for ALL predecessor branches to complete");
        stepTypes.put("approval", "User approval gate - approved/rejected/timeout ports");
        stepTypes.put("option", "Single option selector - routes based on user choice");
        // Data processing
        stepTypes.put("transform", "Data transformation using SpEL expressions");
        stepTypes.put("aggregate", "Aggregate data (sum, avg, count, etc.)");
        stepTypes.put("filter", "Filter rows/items by condition");
        stepTypes.put("sort", "Sort items by field");
        stepTypes.put("code", "Execute custom JavaScript/Python code");
        // Integration
        stepTypes.put("<tool-uuid>", "API tool call - use the exact tool UUID as the type. Find UUIDs via workflow(action='search') or catalog(action='search')");
        stepTypes.put("http_request", "Raw HTTP request to any URL");
        stepTypes.put("send_email", "Send email notification");
        stepTypes.put("email_inbox", "Read a mailbox and act on it (flag/move/delete a message, list or create folders) via IMAP");
        stepTypes.put("sub_workflow", "Execute another workflow as a step and WAIT for it to finish "
            + "(up to timeoutSeconds, default 300, max 1500); if it has not finished by then this node "
            + "fails instead of returning a partial result, and the target keeps running. The target must "
            + "already have a live run - this node never creates one. Run it once first with "
            + "workflow(action='execute', id='<uuid>'), and if the target is pinned add "
            + "version=<its pinned version>, because only runs at the pinned version are eligible");
        stepTypes.put("respond_to_webhook", "Return response to the webhook that triggered this workflow");
        // I/O
        stepTypes.put("interface", "Display output via HTML interface (requires interface_id)");
        stepTypes.put("response", "Send message in chat (requires chat trigger in DAG)");
        stepTypes.put("data_input", "Prompt user for additional data mid-workflow");
        stepTypes.put("download_file", "Download a file from URL");
        stepTypes.put("public_link", "Mint a public, expiring signed URL for a stored file");
        stepTypes.put("media", "Process audio/video files: probe metadata, mux audio onto video, mix tracks, extract audio");
        stepTypes.put("generate", "AI: generate one asset from a prompt - image, video, audio, voice or music (the model picked decides the format and the price). Keyed agent:<label>, written into the plan's 'agents' array, output read as {{agent:<label>.output.file}}");
        stepTypes.put("wait", "Delay execution for a specified duration");
        stepTypes.put("exit", "Exit branch execution (other parallel branches continue)");
        stepTypes.put("stop_on_error", "Stop ENTIRE workflow with error (all branches cancelled, run → FAILED)");
        stepTypes.put("set", "Assign or transform fields (Edit Fields)");
        stepTypes.put("html_extract", "Parse HTML via CSS selectors");
        stepTypes.put("task", "CRUD on agent tasks (create, get, update, delete, list)");
        stepTypes.put("ssh", "Execute commands on remote servers via SSH");
        stepTypes.put("sftp", "File operations on remote servers via SFTP");
        stepTypes.put("database", "Execute SQL queries (PostgreSQL, MySQL, MSSQL)");
        docs.put("step_types", stepTypes);

        docs.put("table_crud_types", Map.ofEntries(
            Map.entry("usage", "Table CRUD: workflow(action='add_node', type='insert_row|find_rows|read_rows|update_row|delete_row', ...)"),
            Map.entry("insert_row", "Insert a new row into a data table"),
            Map.entry("find_rows", "Query rows by condition or vector similarity - returns items[] array. " +
                "Supports similarity={column, queryVector, topK?, threshold?} for RAG. Connect a Split node after to iterate per-row."),
            Map.entry("read_rows", "Get all rows from a table (aliases: get_rows, fetch_rows)"),
            Map.entry("update_row", "Update rows matching a condition"),
            Map.entry("delete_row", "Delete rows matching a condition"),
            Map.entry("where", "Filter for find_rows/read_rows/update_row/delete_row: {column, operator, value}. " +
                "Use the BARE column name (where={column:'message_id', operator:'=', value:'{{trigger:x.id}}'}) - do NOT prefix it with 'data.'. " +
                "A 'data.' prefix you include is auto-stripped, so 'message_id' and 'data.message_id' resolve identically (no double-prefix, both work). " +
                "The reserved name 'id' matches the row's primary key, not a stored column. set= keys (update_row) follow the same bare-name rule."),
            Map.entry("media_columns", "A column of type 'file' or 'image' holds a real file reference, and find_rows/read_rows give it back as the file OBJECT - not as text you have to parse. " +
                "Map the WHOLE cell into a parameter that takes a file: from find_rows {{table:<label>.output.items[0].<column>}}, or {{item.<column>}} once a Split node is iterating the rows (find_rows returns a collection and spawns nothing itself); from read_rows the key is rows, so {{table:<label>.output.rows[0].<column>}}. " +
                "Do NOT keep a file reference as JSON text in a 'text' column and re-parse it in a code node: that older pattern still runs (so it is easy to copy from an existing workflow), but it costs a parse step and the cell is not viewable in the table. " +
                "FOUR THINGS THAT CHANGED, and that an existing workflow may still be relying on. (1) These cells used to arrive as a JSON string: if a code node parses one, delete the parse, because parsing an object fails. " +
                "(2) Passing the cell as a where value used to match, and no longer does - the filter compares the STORED text - so filter and de-duplicate on a text or id column instead. " +
                "(3) An extract_from_file node pointed at a cell whose file has no stored path used to COMPLETE, extracting the reference's own text as if it were the document; it now FAILS instead, so a run that was green can turn red - which is the point, because what it produced was never the file. " +
                "(4) Copying the cell into a text column via set used to store the reference; a cell carrying a url or an id now lands there as a short readable summary instead, from which the file cannot be recovered - keep it in a file or image column. " +
                "One limit that is not new: a node needs the cell's 'path' to reach the file's bytes (public_link, media, extract_from_file all do), and a cell carries a path only when the file it names has one - a link to somewhere else never does, and neither does a file this workspace knows only by id. Read the cell back and check for 'path' when the workflow depends on the bytes; a node that cannot find one now says so instead of blaming your mapping. " +
                "The same caution applies to 'url': it is there when the cell names a file by id or carries a link of its own, and absent on a reference that has only a storage path, which is the commonest shape a workflow produces."),
            Map.entry("dedupe_idempotent_write", "To avoid duplicate rows when re-processing the same item, do NOT insert_row unconditionally. " +
                "Guard the insert: find_rows {column:'<unique_key>', operator:'=', value:'{{...}}'} → decision on {{...find.output.item_count}} == 0 → insert_row on the 'new' branch; " +
                "route the 'exists' branch to a stop/exit. This makes the write idempotent regardless of upstream re-fires."),
            Map.entry("delete_all_rows", "delete_row ALWAYS requires a where (there is no truncate/clear-all op). To wipe EVERY row of a table, " +
                "match on the primary key: where={column:'id', operator:'IS NOT NULL'} (cleanest, type-safe). " +
                "The output field deleted_count tells you how many rows were removed - assert it to confirm the wipe."),
            Map.entry("comparison_semantics", "where comparisons are TEXTUAL, not type-aware: the value is matched as a string and the column is read as text " +
                "(jsonb_extract_path_text for stored columns, id::text for the primary key). So '=', '!=', 'IN', 'IS NULL', 'IS NOT NULL', 'LIKE' behave as expected, " +
                "but the ordering operators '>', '<', '>=', '<=' compare LEXICOGRAPHICALLY, NOT numerically. " +
                "E.g. on a number column, value 9 is treated as the string '9', so 'amount > 9' wrongly excludes '100' (since '1' < '9'); 'id > 5' wrongly skips ids 10-99. " +
                "Use ordering operators only when lexical order matches intent (zero-padded strings, ISO dates yyyy-MM-dd). For numeric ranges, prefer exact '=' / 'IN', or filter downstream in a decision/transform node."),
            Map.entry("in_plan_json", "In set_plan JSON, CRUD ops go in the tables[] array with type='update-row', 'insert-row', 'find', 'read-row', 'delete-row'. Edges use table: prefix (e.g. table:my_step). The builder add_node maps simplified type names automatically.")
        ));

        docs.put("help_for_params", "For detailed parameters of any node type: workflow(action='help', topics=['<type>']). Returns required params, output schema, and examples.");

        Map<String, String> portSyntax = new LinkedHashMap<>();
        portSyntax.put("decision", "connect_after='MyDecision:if' or 'MyDecision:else' or 'MyDecision:elseif_0' (for additional conditions)");
        portSyntax.put("switch", "connect_after='MySwitch:case_0' or connect_after='MySwitch:default'");
        portSyntax.put("loop", "connect_after='MyLoop:body' (inside loop) or connect_after='MyLoop:exit' (after loop)");
        portSyntax.put("fork", "connect_after='MyFork:branch_0', connect_after='MyFork:branch_1', etc.");
        portSyntax.put("approval", "connect_after='MyApproval:approved' or 'MyApproval:rejected' or 'MyApproval:timeout'");
        portSyntax.put("option", "connect_after='MyOption:choice_0' or 'MyOption:choice_1' etc.");
        portSyntax.put("classify", "connect_after='MyClassify:category_0' or 'MyClassify:category_1' etc. (plan edges: agent:label:category_N - agent: prefix, not core:)");
        portSyntax.put("guardrail", "connect_after='MyGuardrail:pass' or 'MyGuardrail:fail' (plan edges: agent:label:pass/fail - agent: prefix, not core:)");
        docs.put("port_syntax", portSyntax);

        return docs;
    }

    private Map<String, Object> buildExamples() {
        Map<String, Object> examples = new LinkedHashMap<>();

        examples.put("new_workflow", Map.of(
            "description", "Start a new workflow",
            "call", "workflow(action='init', name='Email Processor')"
        ));

        examples.put("add_trigger", Map.of(
            "description", "Add a form trigger",
            "call", "workflow(action='add_node', type='form', label='Start', params={fields: [{name: 'email', type: 'email', required: true}]})"
        ));

        examples.put("add_agent", Map.of(
            "description", "Add an agent node (create agent entity first)",
            "steps", List.of(
                "1. agent(action='create', name='Analyzer', system_prompt='You analyze data.')",
                "2. workflow(action='add_node', type='agent', label='Analyze', params={agent_id: '<uuid>', prompt: '{{trigger:start.output.email}}'}, connect_after='Start')"
            )
        ));

        examples.put("add_decision", Map.of(
            "description", "Add a decision with if/else branches",
            "call", "workflow(action='add_node', type='decision', label='Check Priority', params={conditions: [{condition: \"{{contains(agent:analyze.output.response, 'urgent')}}\", label: 'Urgent'}, {condition: 'default', label: 'Normal'}]}, connect_after='Analyze')"
        ));

        examples.put("connect_port", Map.of(
            "description", "Add node on the if-branch of a decision",
            "call", "workflow(action='add_node', type='agent', label='Urgent Handler', params={agent_id: '<uuid>', prompt: 'Draft urgent reply'}, connect_after='Check Priority:if')"
        ));

        examples.put("add_table_trigger", Map.of(
            "description", "Fire the workflow whenever a row is inserted/updated/deleted in a table",
            "steps", List.of(
                "# All changes on the table (default - omit event_types):",
                "workflow(action='add_node', type='table', label='On Row Change', params={table_id: 42})",
                "# Only row_created + row_updated, and only when status column equals 'active':",
                "workflow(action='add_node', type='table', label='On Active Row', params={table_id: 42, event_types: ['row_created','row_updated'], filter: {column: 'status', operator: '=', value: 'active'}})",
                "# Detect status transition using previous_row (only populated for row_updated):",
                "workflow(action='add_node', type='table', label='On Paid', params={table_id: 42, event_types: ['row_updated'], filter: {column: 'status', operator: '=', value: 'paid'}})"
            ),
            "notes", List.of(
                "event-driven: each row change fires one workflow run immediately - no polling interval.",
                "Only runs on the pinned production version of the workflow (same as every production trigger). Unpin → no runs.",
                "Per-column output access: {{trigger:on_row_change.output.row.<column>}} - ALWAYS use the nested " +
                    ".row.<column> path. The flat top-level form ({{...output.<column>}}) silently shadows columns " +
                    "named status, count, data, source, error, message, offset, limit, hasMore, totalCount, " +
                    "realTotalCount, nextOffset, strategy, maxItemsCap, maxItemsReached, _inputs, triggerId, " +
                    "tenantId - these collide with payload metadata.",
                "Event metadata (always at top level, no collision): event_type ('row_created'|'row_updated'|'row_deleted'), " +
                    "row_id, row (full current row as object), previous_row (only on row_updated; null otherwise), " +
                    "datasource_id, triggered_at.",
                "Filter operators (single-condition, optional): =, !=, >, >=, <, <=, in, not_in, contains, starts_with, ends_with, is_null, is_not_null. " +
                    "For in/not_in, value is a list or comma-separated string. is_null/is_not_null take no value. A non-matching event costs nothing (trigger does not fire).",
                "Testing - TWO modes: " +
                    "(1) workflow(action='execute') runs the batch-scan loader → emits {data:[{id, data:{...columns}}, ...], count, hasMore} " +
                    "exactly like find_rows. To process each row, chain a core:split node with " +
                    "input={{trigger:on_row_change.output.data}} and read columns via {{item.data.<column>}}. " +
                    "(2) To exercise the real event-driven path (one fire per row), cause a row change: " +
                    "table(action='insert_rows'|'update_rows'|'delete_rows', table_id=<id>, ...) - then read " +
                    "{{trigger:on_row_change.output.row.<column>}}."
            )
        ));

        examples.put("add_split", Map.of(
            "description", "Add a split to process each item in a list in parallel",
            "call", "workflow(action='add_node', type='split', label='Each Item', params={items: '{{mcp:fetch.output.items}}'}, connect_after='Fetch')"
        ));

        examples.put("add_error_trigger", Map.of(
            "description", "Build an error handler workflow that fires when another workflow fails (FAILED or PARTIAL_SUCCESS)",
            "steps", List.of(
                "# 1. Add the error trigger pointing at the workflow you want to monitor:",
                "workflow(action='add_node', type='error', label='On Order Failure', params={parent_workflow_id: '<order-workflow-uuid>'})",
                "# 2. Add downstream handler nodes (notify, log, retry, …):",
                "workflow(action='add_node', type='send_email', label='Alert Ops', params={toEmail: 'ops@example.com', subject: 'Workflow {{trigger:on_order_failure.output.parentWorkflowId}} failed', body: '{{trigger:on_order_failure.output.errorMessage}}'}, connect_after='On Order Failure')",
                "# 3. Save the handler:",
                "workflow(action='finish')",
                "# 4. BOOTSTRAP - seed an initial WAITING_TRIGGER run so future parent failures can attach:",
                "workflow(action='execute', id='<this_error_handler_id>')  # returns status='BOOTSTRAPPED' with run_id=<seed run> (no fire happens; error triggers are system-only)",
                "# 5. Verify the seed:",
                "workflow(action='runs', workflow_id='<this_error_handler_id>')  # should show one WAITING_TRIGGER run",
                "# 6. To exercise the chain end-to-end, fail the parent and check the handler's runs for a new epoch:",
                "workflow(action='execute', id='<order-workflow-uuid>')  # parent fails → dispatcher fires this handler"
            ),
            "notes", List.of(
                "parent_workflow_id is REQUIRED - without it, validation rejects the call.",
                "Bootstrap is REQUIRED - without an existing WAITING_TRIGGER run on the handler, the dispatcher silently skips: 'No active run for workflow X, skipping dispatch from parent Y'.",
                "execute on an error-only handler returns status='BOOTSTRAPPED' (not 'COMPLETED'/'FAILED') - that's the success shape for seeding. No fire happens.",
                "Anti-loop: if the handler workflow itself fails, it does NOT trigger another error handler. The cascade stops at one level.",
                "Output fields: parentWorkflowId, parentRunId, status (FAILED|PARTIAL_SUCCESS), errorMessage, triggeredAt, failedSteps, completedSteps, totalSteps, skippedSteps.",
                "There is no manual fire path. To test dispatch, fail the parent workflow (add a stop_on_error step then execute) and verify a new epoch lands on the seed run via workflow(action='runs', workflow_id='<handler_id>')."
            )
        ));

        examples.put("add_loop", Map.of(
            "description", "Add a retry loop with body and exit branches",
            "steps", List.of(
                "workflow(action='add_node', type='loop', label='Retry', params={condition: '{{core:retry.output.iteration < 3}}'}, connect_after='Previous')",
                "workflow(action='add_node', type='agent', label='Try', params={agent_id: '<uuid>', prompt: '...'}, connect_after='Retry:body')",
                "workflow(action='add_node', type='agent', label='Done', params={agent_id: '<uuid>', prompt: '...'}, connect_after='Retry:exit')"
            )
        ));

        examples.put("finalize", Map.of(
            "description", "Validate and finish the workflow",
            "steps", List.of(
                "workflow(action='validate') - check for errors",
                "workflow(action='finish') - finalize and save (closes the build session)"
            )
        ));

        examples.put("multi_dag", Map.of(
            "description", "Multi-DAG: two triggers, each with its own independent processing chain",
            "steps", List.of(
                "# DAG 1: Search flow",
                "workflow(action='add_node', type='form', label='Search Input', params={fields: [{name: 'query', type: 'text'}]})",
                "workflow(action='add_node', type='<search-tool>', label='Run Search', params={query: '{{trigger:search_input.output.query}}'}, connect_after='Search Input')",
                "# DAG 2: Delete flow (new trigger = new independent DAG)",
                "workflow(action='add_node', type='form', label='Delete Item', params={fields: [{name: 'item_id', type: 'text'}]})",
                "workflow(action='add_node', type='<delete-tool>', label='Delete Row', params={id: '{{trigger:delete_item.output.item_id}}'}, connect_after='Delete Item')"
            )
        ));

        return examples;
    }
}
