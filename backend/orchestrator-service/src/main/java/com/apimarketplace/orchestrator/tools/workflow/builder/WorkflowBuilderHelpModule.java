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
        sessionMgmt.put("list", "List all workflows. Params: limit, offset");
        sessionMgmt.put("save", "Save current session as a draft. Params: name (optional override), description (optional)");
        sessionMgmt.put("discard", "Abandon current session without saving");
        sessionMgmt.put("finish", "Finalize and save the workflow as ACTIVE. Validates first, then persists. " +
            "CLOSES the build session - do not call any further workflow(action=...) after this. " +
            "Params: name (optional override). Note: 'create' is kept as a legacy alias.");
        sessionMgmt.put("execute", "Run the saved workflow. Must finish/save first. Params: data_inputs (optional)");
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
        actions.put("run_inspection", runInspection);

        actions.put("node_operations", Map.of(
            "add_node", "Add a node. Params: type (required), label (required), params={...}, connect_after (label of predecessor)",
            "modify", "Modify a node's params. Params: node (label), params={...}, connect_after (optional - replaces the incoming connection with a new one from the specified predecessor). params MERGES into the existing node (keys you omit are kept). To DELETE a param key, set it to null, e.g. params={AccountSid: null}. The response's changes.after shows the node's real post-merge value, so check it to confirm a deletion took effect.",
            "remove", "Remove a node. Shows disconnection info and reconnection hints. Params: node (label)",
            "undo", "Undo the last action"
        ));

        actions.put("connection_operations", Map.of(
            "connect", "Create an edge. Params: from (label), to (label). For ports: from='Decision:if'",
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
        stepTypes.put("guardrail", "AI safety check - flag/block/redact content based on rules");
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
        stepTypes.put("email_inbox", "Read a mailbox and act on messages (flag/move/delete) via IMAP");
        stepTypes.put("sub_workflow", "Execute another workflow as a step");
        stepTypes.put("respond_to_webhook", "Return response to the webhook that triggered this workflow");
        // I/O
        stepTypes.put("interface", "Display output via HTML interface (requires interface_id)");
        stepTypes.put("response", "Send message in chat (requires chat trigger in DAG)");
        stepTypes.put("data_input", "Prompt user for additional data mid-workflow");
        stepTypes.put("download_file", "Download a file from URL");
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
