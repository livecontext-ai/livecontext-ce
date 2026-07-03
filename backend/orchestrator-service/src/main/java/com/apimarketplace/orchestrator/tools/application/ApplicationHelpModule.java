package com.apimarketplace.orchestrator.tools.application;

import com.apimarketplace.agent.registry.AgentToolDefinition;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.agent.tools.common.ToolModule;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Help module for the application tool.
 * Provides documentation, parameter details, and examples.
 *
 * <p>Mirrors the structure of {@code WorkflowBuilderHelpModule}:
 * grouped {@code actions}, structured {@code examples} (description + call/steps),
 * {@code related_tools} cross-references. Run-inspection actions
 * ({@code runs}, {@code get_run}, {@code get_node_output}) are included
 * so the agent can inspect execution results without hopping to the workflow tool.
 */
@Component
public class ApplicationHelpModule implements ToolModule {

    @Override
    public List<AgentToolDefinition> getToolDefinitions() {
        return List.of();
    }

    @Override
    public boolean canHandle(String action) {
        return "help".equals(action);
    }

    /** Section names accepted in the {@code topics} filter, in display order. */
    private static final List<String> HELP_TOPICS = List.of(
        "actions", "parameters", "response_fields", "examples", "tips",
        "troubleshooting", "response_glossary", "related_tools");

    @Override
    public Optional<ToolExecutionResult> execute(String action, Map<String, Object> parameters,
                                                  String tenantId, ToolExecutionContext context) {
        if (!"help".equals(action)) return Optional.empty();
        return Optional.of(executeHelp(parameters.get("topics")));
    }

    private ToolExecutionResult executeHelp(Object topicsRaw) {
        Set<String> topics = parseTopics(topicsRaw);
        boolean all = topics.isEmpty();
        Map<String, Object> result = new LinkedHashMap<>();

        result.put("description", """
            APPLICATION TOOL - Publish workflows as marketplace apps and run them.

            OWNED vs. NOT-OWNED - the critical branching for execute:
            - `owned_by_me=true` → application(action='execute') directly. Acquire is REJECTED.
            - `owned_by_me=false` → application(action='acquire') first, then execute.

            DATA_INPUTS - search/my responses are SLIM: each item carries
            `default_trigger_id` and `trigger_types` (e.g. `["form"]`) so you can tell
            whether an app is fireable, but NOT `data_inputs_schema`. To execute, you
            MUST first call `application(action='get', application_id='<id>')` which
            returns the full `data_inputs_schema.fields[]` with select options. Use the
            field names verbatim. Never guess (2026-05-15 regression: agent guessed
            origin/destination/departure_date instead of the real SerpAPI Google
            Flights names departure_id/arrival_id/outbound_date - burnt 1 tool call).
            """);

        if (all || topics.contains("actions")) result.put("actions", buildActionDocs());
        if (all || topics.contains("parameters")) result.put("parameters", buildParameterDocs());
        if (all || topics.contains("response_fields")) result.put("response_fields", buildResponseFieldDocs());
        if (all || topics.contains("examples")) result.put("examples", buildExamples());

        if (all || topics.contains("tips")) result.put("tips", List.of(
            "LIST vs. GET: search/my return slim items (default_trigger_id + trigger_types only). `application(action='get', application_id='<id>')` is the ONE call that returns data_inputs_schema with required field names + select options. Call get before execute when you need to build data_inputs.",
            "CREATE: needs an interface node AND a successful automatic run to showcase (full prerequisites and pinning rules → actions.publishing.create, fetch via topics=['actions']). The create response already includes showcaseRunId, default_trigger_id + data_inputs_schema, so you can chain straight into execute without a second get call.",
            "ACQUIRE gives you a full copy: workflow + interfaces + data source schemas.",
            "MULTI-TRIGGER APPS: when `default_trigger_id` is absent and `trigger_types` contains >1 entry, call `application(action='get', application_id='<id>')` to read `fireable_triggers[]` and pass an explicit `trigger_id` to execute. data_inputs_schema is only emitted for the single-trigger case.",
            "EXECUTE returns run_id, epoch, status, all node statuses with output/error data, plus application_id, workflow_id, and a [visualize:application:id:runId] marker. The marker auto-renders an interactive card for the user - no further action required from you.",
            "RUN INSPECTION: after execute, drill down progressively: get_run (macro) → get_run with epoch=N (detail) → get_node_output (full node output) - no need to hop to the workflow tool. Params per step → actions.run_inspection (topics=['actions']).",
            "PAGINATION: search default=10/max=25, my default=25/max=50. Response carries `hasMore`, `offset`, `limit`, `total`. When `hint.action='next_page'`, call again with offset=hint.nextOffset. When `hint.action='refine'` (search at offset=0 with total>50), STOP paginating and narrow with `query` or `category` - past offset=200 without a filter the call is REFUSED with PAGINATION_LIMIT_WITHOUT_FILTER. When `hint` is absent, the response is complete - render and stop."
        ));

        if (all || topics.contains("troubleshooting")) result.put("troubleshooting", Map.of(
            "acquire_own_publication_error", "If acquire returns `RESOURCE_ALREADY_EXISTS` / 'cannot acquire your own publication' but the item showed `owned_by_me=false`, the underlying APPLICATION workflow is missing (interrupted publish). Repair with application(action='create', workflow_id='<wfId>'). The publication-service compares ownership by publisherId equality, not by APPLICATION-workflow existence, so the two signals can diverge.",
            "execute_missing_field_error", "Form trigger returned 'required fields missing: [...]'. The response carries the full field list and an example payload - match the names verbatim. Note: my/search emit only `default_trigger_id` + `trigger_types` (slim); always call `application(action='get', application_id='<id>')` first to read `data_inputs_schema.fields[]`. If get also omits data_inputs_schema, the app has multiple fireable triggers; pick one via `fireable_triggers[]` and pass `trigger_id`.",
            "get_or_execute_not_found_on_owned_app", "If get/execute returns RESOURCE_NOT_FOUND for an app that DID appear in my/search, you almost certainly passed `workflowId` where `application_id` was expected - my/search items carry both UUIDs adjacent (`id`/`application_id` vs `workflowId`). Use `application_id` (== `id`), never `workflowId`. Both the get AND execute error messages now echo the correct application_id when they detect this case - copy it and retry."
        ));

        if (all || topics.contains("response_glossary")) result.put("response_glossary", buildResponseGlossary());

        if (all || topics.contains("related_tools")) result.put("related_tools", buildRelatedTools());

        if (!all) result.put("available_topics", HELP_TOPICS);

        return ToolExecutionResult.success(result);
    }

    /**
     * Normalize the raw {@code topics} argument (array or bare string, like the
     * catalog help sibling) to the set of known section names. Unknown entries are
     * dropped; a filter with no known entry falls back to the full payload (empty
     * set) so a typo never returns an empty help.
     */
    private Set<String> parseTopics(Object topicsRaw) {
        Collection<?> collection;
        if (topicsRaw instanceof Collection<?> c) {
            collection = c;
        } else if (topicsRaw instanceof String s && !s.isBlank()) {
            collection = List.of(s);
        } else {
            return Set.of();
        }
        Set<String> topics = new LinkedHashSet<>();
        for (Object t : collection) {
            if (t == null) continue;
            String name = t.toString().trim().toLowerCase(Locale.ROOT);
            if (HELP_TOPICS.contains(name)) topics.add(name);
        }
        return topics;
    }

    /**
     * Documents the meaning of enum-valued fields the agent will see on response
     * payloads. The 3 audits flagged that fields like {@code status},
     * {@code visibility}, and {@code last_run.status} were exposed raw - the
     * agent had no way to know what {@code INACTIVE} or {@code PARTIAL_SUCCESS}
     * implied without trial-and-error.
     */
    private Map<String, Object> buildResponseGlossary() {
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("status_values", Map.of(
            "ACTIVE", "Live and executable. Default state right after create.",
            "INACTIVE", "Unpublished by the owner. Execute will fail with RESOURCE_NOT_FOUND. The agent cannot re-activate - only the owner can via the UI.",
            "PENDING_REVIEW", "Awaiting marketplace moderation. Visible only to the owner; execute works for the owner but acquire is blocked."
        ));
        g.put("visibility_values", Map.of(
            "PRIVATE", "Only the owner sees it in search/my. Acquire-by-id still works if someone knows the UUID, but it won't surface in marketplace browse.",
            "PUBLIC", "Listed in the marketplace; anyone can acquire."
        ));
        g.put("last_run_status_values", Map.of(
            "_terminal", List.of("COMPLETED", "FAILED", "PARTIAL_SUCCESS", "CANCELLED", "TIMEOUT"),
            "_non_terminal", List.of("PENDING", "WAITING_TRIGGER", "RUNNING", "PAUSED"),
            "_note", "A new application(action='execute') on a workflow with a non-terminal last_run creates a parallel epoch on the same run - it does NOT block or replace. To inspect: application(action='get_run', run_id=<lastRun?>).",
            "RUNNING", "Engine is actively executing nodes.",
            "PAUSED", "Step-by-step mode - waiting for the next manual step.",
            "WAITING_TRIGGER", "Reusable run sitting idle waiting for the next trigger fire.",
            "FAILED", "Terminal error in at least one node and no recovery succeeded.",
            "PARTIAL_SUCCESS", "Some nodes failed but others (often error-handler branches) completed."
        ));
        g.put("data_inputs_schema", Map.of(
            "shape", "{trigger_type, fields?: [{name, required, type?, format?, options?}]}",
            "trigger_type_values", List.of("form", "chat", "webhook", "schedule", "manual", "datasource"),
            "fields_omitted_when", "Webhook/schedule/manual/datasource: payload is free-form or unused.",
            "field_type_values", List.of("text", "number", "date", "datetime", "time", "select", "textarea", "boolean", "email", "url"),
            "format_hint", "Date/datetime/time fields carry a `format` string with the expected shape (e.g. 'YYYY-MM-DD (ISO 8601 date)'). Extra fields not in the schema are silently dropped by the trigger executor; omitting an optional field falls back to the trigger's declared default."
        ));
        g.put("run_inspection_fields", Map.of(
            "get_run_macro", "run_id, status, plan_version, execution_mode, started_at, ended_at, dags (DAG summary), epochs (list of epoch headers)",
            "get_run_epoch", "run_id, epoch, status, nodes (lightweight per-node summaries: id, label, type, status), NEXT hint",
            "get_node_output", "run_id, epoch, node_id, label, type, output (full data), resolved_params, error. Split nodes: execution_count, items[] in list mode. With field=<dot-path>: output_field {field, content, offset, returned_bytes, original_length, truncated, NEXT}",
            "runs_list", "run_id, status, plan_version, started_at, ended_at, duration_ms, total_nodes, execution_mode"
        ));
        return g;
    }

    /**
     * Lightweight per-field reference so the agent doesn't have to scan
     * `actions`/`examples` to learn what each response key carries.
     */
    private Map<String, Object> buildResponseFieldDocs() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("owned_by_me", "boolean - true if your tenant owns the app (execute directly). False = call acquire first.");
        r.put("default_trigger_id", "string - single fireable trigger key (e.g. 'trigger:search'). Absent on multi-trigger apps - see fireable_triggers[] (returned by `get`, not by `my`/`search`).");
        r.put("trigger_types", "array of strings - fireable trigger TYPES in the app (e.g. ['form'], ['form','webhook']). Present on slim list payloads (my/search) so you know whether the app is agent-fireable without paying for the full schema. Call `get` to inspect fields.");
        r.put("fireable_triggers", "array - present on `get` responses for multi-trigger apps. NOT included in `my`/`search` list payloads. Each item {trigger_id, type, label?}. Pass one of these as `trigger_id` on execute.");
        r.put("data_inputs_schema", "object - returned ONLY by `get` and `create` responses (slim list payloads my/search omit it). See response_glossary.data_inputs_schema for shape. Field names here are LITERAL - pass them verbatim to execute's data_inputs.");
        r.put("last_run", "object {status, at} - freshness signal. See response_glossary.last_run_status_values for semantics.");
        r.put("status", "string - ACTIVE/INACTIVE/PENDING_REVIEW. See response_glossary.status_values.");
        r.put("visibility", "string - PUBLIC/PRIVATE. See response_glossary.visibility_values.");
        r.put("id", "UUID - the application_id of this app. Identical to the `application_id` field. THIS is what you pass to get/acquire/execute/visualize as application_id - NOT `workflowId`.");
        r.put("application_id", "UUID - same value as `id`. Copy this field verbatim into the application_id parameter of get/acquire/execute/visualize. Do NOT use `workflowId` here.");
        r.put("workflowId", "UUID - the UNDERLYING workflow, NOT the application_id. Only pass to workflow(action='load') to edit the workflow. Passing it as application_id to get/execute returns RESOURCE_NOT_FOUND (the error echoes the correct application_id).");
        return r;
    }

    private Map<String, Object> buildActionDocs() {
        Map<String, Object> actions = new LinkedHashMap<>();

        // Marketplace browse / acquire
        Map<String, String> marketplace = new LinkedHashMap<>();
        marketplace.put("search", "Browse / search marketplace apps. Full-text search across title (highest weight), category, description, and indexed nested content (interface titles, agent roles, table names). "
            + "TYPO-TOLERANT: trigram fuzzy fallback at similarity ≥ 0.1 - 'gmial' matches Gmail. "
            + "STEMMING: English content matched morphologically - 'emails' matches 'email', 'sending' matches 'send'. French and other languages matched verbatim. "
            + "MULTI-WORD: a query like 'flights to thailand' matches if any token is found (OR-joined fallback after the stricter AND-joined attempt). "
            + "RANKED by relevance (title > category > description > nested), then popularity (use_count), then recency. "
            + "SYNONYMS NOT SUPPORTED - prefer brand names: 'gmail', 'slack', 'telegram'. Special characters in your query are sanitized (stripped) before matching. "
            + "If no results, NEXT_OPTIONS surfaces `if_no_match_search_tools` (catalog) and `if_no_match_build_workflow` (workflow.init) - pivot there instead of looping. "
            + "Optional params: query, category (filter), limit (default 10, max 25), offset. "
            + "Response items include `owned_by_me` so you can skip acquire when true.");
        marketplace.put("get", "Full details for one app, INCLUDING `data_inputs_schema` (field names + select options) and `fireable_triggers[]` for multi-trigger apps. Call before acquire (when owned_by_me=false) AND before execute (to read the required field names - my/search omit the schema for size). Params: application_id (required).");
        marketplace.put("my", "List the apps in the user's workspace - every app they ACQUIRED from the marketplace OR PUBLISHED themselves (all `owned_by_me=true`, executable directly with NO acquire step). This is what the user means by 'my applications' / 'the apps I have'. Each carries `default_trigger_id` + `trigger_types` (slim - `data_inputs_schema` is omitted to keep paginated payloads small; call `application(action='get', application_id='<id>')` to read field names before execute). Optional: limit (default 25, max 50), offset.");
        marketplace.put("acquire", "Clone someone else's app as your own workflow (full copy: workflow + interfaces + data sources). Params: application_id (required). BLOCKED when `owned_by_me=true` - call execute directly instead. INTERACTIVE CHAT: acquire is GATED - calling it does NOT install the app. It surfaces a marketplace install card and the USER installs the app themselves; the install does NOT happen in this turn. The gate returns {status:'authorization_required', executed:false} - treat the app as NOT yet installed: do not re-call acquire, do not assume the app exists, and do not execute it. After installing, the user comes back with a NEW request (a fresh turn) where the app is now `owned_by_me=true`.");
        marketplace.put("uninstall", "Remove an app you ACQUIRED from your workspace. Deletes the local cloned "
            + "workflow and its run history; the marketplace listing and the publisher's original are untouched, "
            + "so you can acquire it again later (the acquisition is retained). Params: application_id (required) - "
            + "the same id you pass to acquire/get/execute. Idempotent: an app you never acquired here, or already "
            + "uninstalled, returns RESOURCE_NOT_FOUND. Response: status='OK', removed_workflow_id. Use "
            + "application(action='my') to see which apps you can uninstall. This is for apps you ACQUIRED - "
            + "to take down an app you PUBLISHED yourself, that is a different operation (the marketplace listing).");
        actions.put("marketplace", marketplace);

        // Publishing
        Map<String, String> publishing = new LinkedHashMap<>();
        publishing.put("create", "Publish a workflow as a PRIVATE app. Params: workflow_id (required, auto-detected from current conversation if omitted), title (optional override), description (optional override), run_id (optional - pin the showcase run; omit to auto-pick the latest successful automatic run), epoch (optional - pin the showcase epoch; omit to render the latest). Idempotent - calling on an already-published workflow updates the listing. Requires the workflow to have at least one interface node AND at least one successful automatic run - COMPLETED, PARTIAL_SUCCESS, or WAITING_TRIGGER for reusable triggers (run it first via workflow(action='execute') if none, else you get a hint).");
        actions.put("publishing", publishing);

        // Execution
        Map<String, String> execution = new LinkedHashMap<>();
        execution.put("execute", "Fire a trigger on an app you own or have acquired. Works DIRECTLY on `owned_by_me=true` apps (your own publications) - no acquire step. Returns same shape as workflow(action='execute') (run_id, epoch, status, all node statuses with output/error data, NEXT hint). Params: application_id (required), data_inputs (optional - use the names from `data_inputs_schema` returned by `get` or `create`, NOT from `my`/`search` which omit the schema, and do NOT guess), trigger_id (optional, defaults to `default_trigger_id`). Result also includes [visualize:application:<appId>:<runId>] marker that auto-renders an interactive preview card. INTERACTIVE CHAT: execute is GATED behind user authorization - when gated it returns {status:'authorization_required', executed:false} and the app does NOT run (no run_id, no outputs). Do not describe/invent results and do not re-call; wait for approval. A response carrying run_id + status + outputs is the ONLY proof it actually ran.");
        actions.put("execution", execution);

        // Run inspection (progressive drill-down)
        Map<String, String> runInspection = new LinkedHashMap<>();
        runInspection.put("runs", "List execution history for an app. Params: application_id (required), limit/offset (optional). Returns paginated list of runs with status, plan_version, duration.");
        runInspection.put("get_run", "Inspect a run. Without epoch: macro overview (epochs + per-node status). With epoch=N: detailed node list for that epoch. Params: run_id (required), epoch (optional integer).");
        runInspection.put("get_node_output", "Full output/error for one node in one epoch. Params: run_id + epoch + node_id (all required). Optional targeting: item_index, iteration, spawn (for split/loop nodes). A TEXT output field >128 KB comes back as a truncated preview + a NEXT pointer - follow it (field=<dot-path>, offset) to page the full value; max_bytes sets the window (default & cap 128 KB).");
        actions.put("run_inspection", runInspection);

        // Other
        Map<String, String> other = new LinkedHashMap<>();
        other.put("visualize", "Show an interactive preview card in chat. Params: application_id (required), title (optional override).");
        other.put("help", "Show this documentation.");
        actions.put("other", other);

        return actions;
    }

    private Map<String, Object> buildParameterDocs() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("action", "string, required - create|search|my|get|acquire|uninstall|execute|runs|get_run|get_node_output|visualize|help");
        params.put("workflow_id", "UUID, required for create (auto-detected if current conversation is on a workflow)");
        params.put("application_id", "UUID, required for get/acquire/uninstall/execute/runs/visualize");
        params.put("title", "string - Override title for create/visualize (defaults to workflow name)");
        params.put("description", "string - Override description for create (defaults to workflow description)");
        params.put("query", "string - Search by title, description, and indexed nested content (search)");
        params.put("category", "string - Filter by category slug (search)");
        params.put("data_inputs", "object - Input data for execution (execute). Format depends on trigger type.");
        params.put("trigger_id", "string - Trigger ID to fire (execute). Optional, defaults to first fireable trigger.");
        params.put("run_id", "string - Run ID (for: get_run, get_node_output; and create to pin the showcase run). Returned by execute or runs. On create, omit to auto-pick the latest successful automatic run (COMPLETED/PARTIAL_SUCCESS/WAITING_TRIGGER).");
        params.put("epoch", "integer - Epoch number (for: get_run detail, get_node_output; and create to pin the showcase epoch). On create, omit to render the latest epoch.");
        params.put("node_id", "string - Node ID (for: get_node_output). From get_run epoch detail response.");
        params.put("item_index", "integer - Split item index (for: get_node_output). Optional targeting.");
        params.put("iteration", "integer - Loop iteration (for: get_node_output). Optional targeting.");
        params.put("spawn", "integer - Spawn index (for: get_node_output). Optional targeting.");
        params.put("field", "string - (for: get_node_output) dot-path of a large TEXT output field to read in full (e.g. 'output.image'); follow the NEXT pointer on a truncated field.");
        params.put("max_bytes", "integer - (for: get_node_output field-expand) text window size, default & cap 128 KB.");
        params.put("limit", "integer - Pagination. search: default=10, max=25. my: default=25, max=50. runs: default=20, max=100.");
        params.put("offset", "integer, default=0 - Pagination offset (search, my, runs)");
        params.put("topics", "array - Optional help filter (help): any of " + String.join(", ", HELP_TOPICS)
            + ". Omit for the full reference; unknown values are ignored (all-unknown falls back to the full payload).");
        return params;
    }

    private Map<String, Object> buildExamples() {
        Map<String, Object> examples = new LinkedHashMap<>();

        examples.put("create", Map.of(
            "description", "Publish a workflow as an app",
            "call", "application(action='create', workflow_id='<uuid>')"
        ));

        examples.put("create_current", Map.of(
            "description", "Publish from current conversation context (auto-detect)",
            "call", "application(action='create')"
        ));

        examples.put("browse", Map.of(
            "description", "Browse marketplace by query",
            "call", "application(action='search', query='email automation')"
        ));

        examples.put("get", Map.of(
            "description", "Get full details before acquiring",
            "call", "application(action='get', application_id='<uuid>')"
        ));

        examples.put("my", Map.of(
            "description", "List the apps in the user's workspace (acquired + published) - use this when they ask 'what apps do I have'",
            "call", "application(action='my')"
        ));

        examples.put("preview", Map.of(
            "description", "Render an interactive preview card in chat",
            "call", "application(action='visualize', application_id='<uuid>')"
        ));

        examples.put("acquire", Map.of(
            "description", "Clone an app as your own workflow",
            "call", "application(action='acquire', application_id='<uuid>')"
        ));

        examples.put("browse_all", Map.of(
            "description", "Browse all marketplace apps (no query)",
            "call", "application(action='search')"
        ));

        examples.put("execute_simple", Map.of(
            "description", "Run an acquired app (first fireable trigger, no inputs)",
            "call", "application(action='execute', application_id='<uuid>')"
        ));

        examples.put("execute_with_data", Map.of(
            "description", "Run with data inputs into the trigger",
            "call", "application(action='execute', application_id='<uuid>', data_inputs={'message': 'hello'})"
        ));

        examples.put("execute_specific_trigger", Map.of(
            "description", "Run a specific trigger (when the app has multiple)",
            "call", "application(action='execute', application_id='<uuid>', trigger_id='trigger:my_form')"
        ));

        examples.put("inspect_run_macro", Map.of(
            "description", "After execute returns run_id, inspect the run - macro overview (epochs + statuses)",
            "call", "application(action='get_run', run_id='<run_id>')"
        ));

        examples.put("inspect_run_epoch", Map.of(
            "description", "Drill into a specific epoch for detailed node list",
            "call", "application(action='get_run', run_id='<run_id>', epoch=0)"
        ));

        examples.put("inspect_node_output", Map.of(
            "description", "Full output/error for one node - the deepest zoom level",
            "call", "application(action='get_node_output', run_id='<run_id>', epoch=0, node_id='mcp:step1')"
        ));

        examples.put("list_runs", Map.of(
            "description", "List execution history for an app",
            "call", "application(action='runs', application_id='<uuid>')"
        ));

        return examples;
    }

    private Map<String, Object> buildRelatedTools() {
        Map<String, Object> related = new LinkedHashMap<>();

        // Build-time helpers - workflow tool to load/edit, interface + agent for
        // the prerequisites of any publishable workflow.
        Map<String, String> buildTime = new LinkedHashMap<>();
        buildTime.put("workflow", "workflow(action='load', id='<wfId>') - load an acquired or own workflow for editing. workflow(action='help') for full action list.");
        buildTime.put("interface", "interface(action='create') - required before publishing (apps need at least one interface node).");
        buildTime.put("agent", "agent(action='create') - required for any agent node inside the workflow you publish.");
        related.put("build_time", buildTime);

        return related;
    }
}
