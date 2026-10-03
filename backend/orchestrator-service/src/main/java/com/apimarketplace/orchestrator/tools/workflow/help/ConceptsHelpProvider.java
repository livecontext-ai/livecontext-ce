package com.apimarketplace.orchestrator.tools.workflow.help;

import java.util.*;

/**
 * Provides help documentation for core workflow concepts.
 */
public final class ConceptsHelpProvider {

    private ConceptsHelpProvider() {}

    // ==================== OVERVIEW ====================

    public static Map<String, Object> getOverview() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("title", "Workflow Help");
        result.put("description", "Use workflow to create and edit workflows interactively.");

        result.put("quickStart", Map.of(
            "new_workflow", "workflow(action='init', name='My Workflow', description='What it does')",
            "edit_workflow", "workflow(action='load', id='<uuid>') or workflow(action='load') to auto-detect from context",
            "list_workflows", "workflow(action='list')"
        ));

        result.put("expressionRule", Map.of(
            "rule", "Every node-output reference MUST be inside {{...}}. Wrap the WHOLE expression in one {{...}} - never concatenate raw refs with '+'.",
            "ok", "{{'Bonjour ' + trigger:form.output.name + ' !'}}",
            "wrong", "'Bonjour ' + trigger:form.output.name + ' !'  // invalid: ':' is SpEL ternary, reference is not resolved",
            "details", "workflow(action='help', topics=['spel'])"
        ));

        result.put("helpTopics", Map.ofEntries(
            Map.entry("concepts", "workflow(action='help', topics=['concepts']) - Architecture & rules"),
            Map.entry("variables", "workflow(action='help', topics=['variables']) - Variable syntax & visibility"),
            Map.entry("spel", "workflow(action='help', topics=['spel']) - SpEL functions"),
            Map.entry("nodes", "workflow(action='help', topics=['nodes']) - All node types by category"),
            Map.entry("multi_dag", "workflow(action='help', topics=['multi_dag']) - Multi-trigger / multi-DAG workflows"),
            Map.entry("specific_node", "workflow(action='help', topics=['decision']) or workflow(action='help', topics=['loop', 'split'])")
        ));

        result.put("nodeCategories", Map.ofEntries(
            Map.entry("triggers", "Entry points: manual, schedule, webhook, chat, form, table, workflow, error"),
            Map.entry("agents", "AI nodes: agent, browser_agent, classify, guardrail, generate. All keyed agent:<label> and all written into the plan's 'agents' array."),
            Map.entry("cores", "Control flow: decision, switch, loop, split, fork, merge, transform, aggregate, filter, sort, code, approval, wait, exit, stop_on_error, response, http_request, data_input, download_file, public_link, media, sub_workflow, respond_to_webhook, send_email, email_inbox, limit, remove_duplicates, summarize, date_time, crypto_jwt, xml, compression, rss, convert_to_file, extract_from_file, compare_datasets, set, html_extract, task, ssh, sftp, database"),
            Map.entry("mcps", "External tools via catalog(action='search')"),
            Map.entry("tables", "CRUD: insert_row, find_rows, read_rows, update_row, delete_row. " +
                "find_rows supports vector similarity search (RAG) via similarity={column, queryVector, topK?, threshold?}"),
            Map.entry("interfaces", "Visual interfaces: display data (variable_mapping) OR interactive apps (action_mapping + trigger = user submits → workflow → results displayed)"),
            Map.entry("notes", "Sticky notes that explain the workflow to the user, optionally attached to one node. Never executed. workflow(action='help', topics=['notes'])")
        ));

        result.put("tip", "Start with workflow(action='init') - it returns all available node types and syntax.");

        return result;
    }

    // ==================== NOTES ====================

    public static Map<String, Object> getNotesHelp() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("title", "STICKY NOTES - explain the workflow to the person reading it");
        result.put("what", "A note is text on the canvas. It never runs, takes no edge and no connect_after, "
                + "and does not count as an orphan or a dead end in validate.");
        result.put("add", "workflow(action='add_node', type='note', params={text: '...', attached_to: '<node label>'}). "
                + "text is required. attached_to is optional: the label of the node the note explains. "
                + "label is optional (one is generated, e.g. 'About Check Seen'); color is optional, one of yellow, blue, green, pink, purple, orange; leave it out and successive notes alternate through these colours.");
        result.put("attached_vs_free", "ATTACHED (attached_to set): placed next to that node, shown when the user focuses "
                + "that node in the editor or in a run, renamed with it, and REMOVED with it (undo brings both back). "
                + "FREE (no attached_to): one note about the whole workflow, placed above the graph.");
        result.put("placement", "Do not invent a position for a note you add: the canvas places it in the reading direction "
                + "the user chose for this workflow (an attached note above its node when the graph reads left to right, to its "
                + "right when it reads top to bottom) and places an attached note again when the user switches direction. "
                + "When you send back a plan read with get_plan, keep the position each note came with: the user put it there.");
        result.put("when_to_add", List.of(
                "The WHY a reader cannot guess from the node itself: the rule behind a decision, why a dedup or a find_rows guard exists, why a wait or a retry is there.",
                "What the user must do or provide: a credential to connect, a table to fill, an interface page to open.",
                "A trap the next editor would fall into: an order that matters, a field that must stay in sync.",
                "One free note summarising what the workflow does and when it runs, for a workflow of more than a few nodes."));
        result.put("when_not_to", "Do not restate what a node already says (its type, its label, its params), and do not annotate "
                + "every node: a few notes on the nodes that need explaining read better than one on each.");
        result.put("style", "Write for the user, in their language, 1 to 3 short sentences. Plain text; line breaks are kept.");
        result.put("edit_remove", "workflow(action='modify', node='<note label>', params={text: '...', attachedTo: '<node label>'}) "
                + "to change it; workflow(action='remove', node='<note label>') to delete it.");
        result.put("in_set_plan", "notes: [{label: 'Why dedup', text: '...', attachedTo: 'Check Seen'}] - attachedTo takes a node "
                + "label like an edge endpoint; a label that names no node is refused. get_plan returns the same shape. "
                + "Give each note its own label, distinct from every node: modify and remove find a note by its label. "
                + "color takes the same palette names (or their hex); leave it out and the notes alternate through the palette.");
        return result;
    }

    // ==================== CONCEPTS ====================

    public static Map<String, Object> getConceptsHelp() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("title", "WORKFLOW BUILDING GUIDE");
        result.put("description", "Essential rules for building workflows. Most things are AUTOMATIC - focus on the ACTIONS.");

        result.put("0_DATA_DEPENDENCY", Map.of(
            "rule", "Connection (edge) = execution ORDER only. Variables {{...}} = explicit data passing. You need BOTH.",
            "common_mistake", "Creating steps without {{type:label.output.field}} in params - connected but no data!"
        ));
        result.put("0B_DETERMINISTIC_FIRST", Map.of(
            "RULE", "Maximize deterministic nodes. Agent node = LAST RESORT, only for steps requiring creativity or judgment.",
            "PRIORITY", List.of(
                "1. TOOL STEP (mcp:) or http_request - direct API call, predictable I/O",
                "2. CORE NODES - transform, set, decision, switch, filter, html_extract, code, date_time - reshape, route, validate deterministically",
                "3. CLASSIFY / GUARDRAIL - lightweight AI, single-purpose, constrained output",
                "4. AGENT - full AI reasoning, ONLY when no deterministic node can do the job"
            ),
            "HOW", "For each step, ask: is the output predictable from the input? If yes → deterministic node. " +
                   "If no (requires interpretation, creativity, or open-ended reasoning) → agent. " +
                   "Pre-process and structure data with deterministic nodes BEFORE passing it to an agent.",
            "ANTI_PATTERN", "Never use an agent for tasks achievable deterministically: API calls, data extraction, " +
                           "conditional routing, string formatting, list operations. Feed agents structured data, not raw responses."
        ));
        result.put("1_HOW_TO_BUILD", buildHowToBuild());
        result.put("2_FORK_PARALLEL", buildForkParallel());
        result.put("3_MERGE_CONVERGENCE", buildMergeConvergence());
        result.put("4_DECISION_EXCLUSIVE", buildDecisionExclusive());
        result.put("5_LOOP_ITERATION", buildLoopIteration());
        result.put("6_VARIABLE_VISIBILITY", buildVariableVisibility());
        result.put("7_INTERFACE", "For interface details: workflow(action='help', topics=['interface']) or interface(action='help')");
        result.put("8_MULTI_DAG", "For multi-DAG details: workflow(action='help', topics=['multi_dag'])");
        Map<String, String> quickRef = new LinkedHashMap<>();
        quickRef.put("fork", "Multiple connect FROM same source → parallel ALL");
        quickRef.put("merge", "Multiple connect TO same target → wait ALL");
        quickRef.put("decision", "type='decision' with conditions → exclusive ONE");
        quickRef.put("loop", "type='loop' → ports: body (inside), exit (after). connect_after='Loop:body' or 'Loop:exit'");
        quickRef.put("terminal", "exit, end, stop_on_error end a branch - NO outgoing edges allowed. Never connect them as `from` (incl. to a merge). To merge a branch back in, route the predecessor of the terminal to the merge directly, or drop the terminal.");
        quickRef.put("not_terminal", "response and respond_to_webhook send a value back but the flow CONTINUES - they ARE NOT terminal. You can (and usually should) connect successors to them.");
        quickRef.put("multi_dag", "Multiple triggers = independent DAGs");
        result.put("QUICK_REFERENCE", quickRef);

        return result;
    }

    // ==================== MULTI-DAG ====================

    public static Map<String, Object> getMultiDagHelp() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("title", "Multi-Trigger / Multi-DAG Workflows");
        result.put("description", "A workflow can have multiple triggers. Triggers with no overlapping descendants form independent DAGs. Triggers whose descendants overlap are auto-grouped as one shared DAG (each trigger fires its own epoch). See RULES and how_it_works for per-epoch semantics and implicit-merge pitfalls.");

        result.put("CONCEPT", buildMultiDag());

        result.put("RULES", Map.of(
            "dag_grouping", "Shared descendants between triggers are auto-detected as ONE DAG group (not rejected). Separate triggers with no overlap form independent DAGs.",
            "unique_labels", "Each trigger must have a UNIQUE label",
            "no_cross_dag_variables", "Variables from DAG-A are NOT available in DAG-B (different execution context)",
            "interface_same_dag", "An interface belongs to ONE DAG and can only fire triggers within that same DAG",
            "per_trigger_epoch", "Each trigger fire = one new epoch in ITS DAG group. A shared sink with multiple incoming trigger edges (any node type: wait, transform, mcp, merge, interface, …) executes once per fire - the engine filters out the other trigger predecessors since they don't fire in this epoch."
        ));

        result.put("PATTERNS", Map.of(
            "same_dag_interactive", "One DAG with multiple triggers + processing steps + interface. All buttons fire triggers within the SAME DAG.",
            "multi_channel", "webhook for real-time + schedule for batch - multiple independent business processes in same workflow"
        ));

        result.put("SAME_DAG_INTERFACE_PATTERN", buildInterfaceDagPattern());

        return result;
    }

    // ==================== MOCKING ====================

    public static Map<String, Object> getMockingHelp() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("title", "Node Mocks - Pin a Node's Output Ahead of Time");
        result.put("description",
            "A mock pins a node's output: instead of really executing (no API call, no LLM call, no DB write, "
                + "no approval wait), the node returns exactly what you configured. Mocks apply per node - "
                + "mock ONE node while every other node runs for real (hybrid runs). They apply to editor runs "
                + "only: pinned production fires ALWAYS execute for real, so mocks can safely stay on a workflow.");

        result.put("SET_AND_CLEAR", Map.of(
            "static_output", "workflow(action='modify', node='Fetch Tweets', mock={output: {tweets: [{id: 1, text: 'hi'}], count: 1}}) - the node returns this object verbatim as its output.",
            "catalog_example", "workflow(action='modify', node='Fetch Tweets', mock={source: 'catalog_example'}) - mcp catalog-tool nodes only: serves the tool's default example response projected to its output schema. No credentials needed, always up to date, nothing to paste.",
            "branch_selection", "workflow(action='modify', node='Check Status', mock={port: 'if'}) - decision/switch/option/approval cores and classify agents take a port instead of (or combined with) an output. Ports: decision if/elseif_N/else, switch case_N/default, option choice_N, approval approved/rejected/timeout, classify category_N.",
            "simulated_failure", "workflow(action='modify', node='Send Email', mock={error: {message: 'Rate limit exceeded', output: {error_code: 429}}}) - marks the node FAILED to exercise error branches, retry policies and continueOnFailure paths.",
            "simulated_duration", "Add durationMs to ANY mock form (workflow(action='modify', node='Slow Agent', mock={output: {...}, durationMs: 90000})) - the mocked node takes that long before returning, like the real call would, and the run report's execution time reflects it. Milliseconds, 0 to 600000 (10 minutes max). Omit or 0 = instant.",
            "park", "Add enabled=false inside the mock to PARK it (kept but not applied) without deleting it.",
            "clear", "workflow(action='modify', node='Fetch Tweets', mock={}) - removes the mock; the node executes for real again.",
            "inspect", "workflow(action='describe', node='...') shows the node's current mock block."));

        result.put("PROPOSED_OUTPUT", Map.of(
            "what", "workflow(action='mock_suggest', node='Fetch Tweets') returns a ready-to-edit suggested_output for ANY node: the projected catalog example for mcp catalog tools, a schema-synthesized skeleton (right keys, placeholder values) for every other node type.",
            "flow", "mock_suggest -> edit the suggested_output freely -> workflow(action='modify', node=..., mock={output: <edited>}). You are never constrained by the proposal - include exactly the fields downstream templates reference.",
            "seed_from_real_run", "After a real run, workflow(action='get_node_output', ...) gives you the node's actual output - paste (a trimmed copy of) it into mock={output: ...}. Trim large outputs to the fields downstream nodes actually use; for mcp nodes prefer source='catalog_example' over pasting large payloads."));

        result.put("RUN_AND_VERIFY", Map.of(
            "default", "workflow(action='execute', id='...') - every node carrying an ENABLED mock returns it; all other nodes execute for real. The report then contains mock_mode + mocked_nodes.",
            "run_all_real_once", "workflow(action='execute', id='...', mock_mode='off') - ignores ALL mocks for this run without touching their config.",
            "full_dry_run", "workflow(action='execute', id='...', mock_mode='all_mcp') - configured mocks PLUS every mcp catalog-tool node without one serves its catalog example. Runs a whole workflow with zero credentials and zero external calls.",
            "verify", "workflow(action='get_node_output', run_id=..., epoch=..., node_id=...) shows mocked=true and mock_source on rows whose output is a configured mock - that is how you tell mocked data from real data."));

        result.put("RULES", Map.of(
            "granular", "Per node. Nodes without an enabled mock ALWAYS execute for real in default mode.",
            "not_on", "Triggers and notes (fake a trigger payload with data_inputs on execute instead), and split/merge/aggregate/loop/fork cores (mock the node that FEEDS the split - its items list - and the split fans out over the mocked items for real).",
            "production_safe", "execute with version='pinned' refuses mock_mode, and production trigger fires never apply mocks - a published workflow carrying mock blocks runs them as inert data.",
            "shape", "The output is persisted through the node's NORMAL output-schema mapping, so match the node's output schema: fields the schema does not declare are silently dropped. Code nodes are the main trap - wrap custom fields under result (mock={output: {success: true, result: {your: 'fields'}}}) and read them downstream as {{core:<label>.output.result.your}}. mock_suggest returns the right skeleton per node; validate reports mock shape problems.",
            "file_outputs", "Mocking a file-producing node: the FileRef needs its id, not just a url - {_type:'file', id:'<uuid>', path, name, mimeType, size}. id is the only handle that resolves the bytes, so a ref without it shows 'no inline preview' under the node and gives a downstream file param nothing to fetch. Copy the WHOLE ref from workflow(action='get_node_output', ...) of a real run; if yours carries only a url ending in /files/by-id/<uuid>/raw, that <uuid> IS the id - add it as id. A mocked file must point at bytes some earlier real run already produced: a mock uploads nothing.",
            "no_side_effects", "A mocked table CRUD node writes nothing, a mocked agent calls no LLM, a mocked approval never waits (it completes immediately on the chosen port)."));

        result.put("TYPICAL_FLOW", List.of(
            "1. Build the workflow (webhook -> gmail list -> decision -> send email).",
            "2. workflow(action='modify', node='Gmail List', mock={source: 'catalog_example'}) - no Gmail credentials needed.",
            "3. workflow(action='modify', node='Check Urgent', mock={port: 'if'}) - force the branch you want to test.",
            "4. workflow(action='execute', id='...') then ONE wait_run - the report lists mocked_nodes.",
            "5. workflow(action='get_node_output', ...) on the downstream node - verify it consumed the mocked values.",
            "6. Leave the mocks in place: production fires ignore them, and the next editor run keeps benefiting."));

        return result;
    }

    /**
     * A map that keeps the order it was written in.
     *
     * <p>{@code Map.of} does not: its iteration order is unspecified and varies between JVM runs.
     * For a reference block an agent reads top to bottom, the order carries meaning (the field a
     * reader meets first, the trap stated before the remedy), and shuffling it per boot makes the
     * same help read differently on two installs.
     */
    private static Map<String, Object> orderedMap(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
            map.put(String.valueOf(keysAndValues[i]), keysAndValues[i + 1]);
        }
        return map;
    }

    // ==================== NODE POLICY ====================

    public static Map<String, Object> getNodePolicyHelp() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("title", "Execution Policy - How One Node Behaves On Failure");
        result.put("description",
            "nodePolicy is a per-node block that says what happens when the node fails: how many "
                + "times to try again, how long one attempt may take, and whether the rest of the "
                + "workflow continues past the failure. It is set OUTSIDE params, like connect_after "
                + "and mock, because it is not a parameter of the node's type. A node without a "
                + "policy behaves exactly as it does today: one attempt, no waiting, and a failure "
                + "that marks every node after it SKIPPED.");

        result.put("SET_AND_CLEAR", orderedMap(
            "on_creation", "workflow(action='add_node', type='http_request', label='Fetch Page', connect_after='Start', params={url: '...'}, nodePolicy={retryCount: 2, retryBackoffMs: 5000})",
            "on_existing", "workflow(action='modify', node='Fetch Page', nodePolicy={retryCount: 2, retryBackoffMs: 5000, timeoutMs: 30000})",
            "whole_block", "The block is REPLACED, never merged: send every field you want, not only the one you are changing. That is what makes a setting removable.",
            "clear", "workflow(action='modify', node='Fetch Page', nodePolicy={}) - removes the policy; the node runs with the platform defaults again.",
            "inspect", "workflow(action='describe', node='Fetch Page') shows the node's current nodePolicy block, and get_plan carries it on the node entry."));

        result.put("FIELDS", orderedMap(
            "retryCount", "Extra attempts after a failed one. Default 0, at most 10. Total attempts = retryCount + 1. Each attempt re-executes the node with the same input, so a node with side effects (an email, an API write, a row insert) repeats them, which is exactly why this is opt-in per node and not a platform default. Agent nodes are retried too, including one handed to the background worker (it shows RUNNING while it works, and stays RUNNING while it waits for its next attempt). A classify or guardrail agent is retried like any node.",
            "retryBackoffMs", "Wait between attempts, in milliseconds. Default 0, at most 60000 (60 seconds). When a tool step's refusal says how long to wait (a Retry-After), the wait is the LONGER of the two. A provider asking for longer than 60 seconds ends the retries, because every wait holds a worker: a provider window of minutes or hours belongs to a schedule, not to a retry. It blocks only this node's branch (and, inside a split, only this item), never the sibling branches.",
            "retryOn", "Which failures get another attempt. Leave it out (the default) to retry every failure EXCEPT a refusal the same request meets again. The platform's own: no credits left, a credit budget reached (the agent's or the workflow's), an agent stopped by a person, and a tool step the catalog refused before calling the provider (no credits, a plan that does not include the integration, a credential it cannot pick, a tool that no longer exists). And a request the provider refused outright: an HTTP 4xx other than 408, 425 and 429 (invalid parameters, not authorized, not found) is not retried, because the same request gets the same answer, UNLESS it is a rate limit in disguise (it carries a Retry-After, or it is a 4xx whose message says it is a rate limit, e.g. rate limit, too many requests, throttled, request limit reached, as some providers answer a limit with 403 or 409). Set 'rate_limit' to retry ONLY a rate limit: a 429, a 503, or one of those signals. It is safer on a node that WRITES (publish, send, create): it does not re-send after a timeout or most 5xx, which the provider may already have applied, although a 503 can still arrive after a write went through. Only on a tool step (a node that calls an integration) and only with retryCount > 0: refused anywhere else.",
            "timeoutMs", "Bound on ONE attempt, in milliseconds. Default 0 = unbounded. A timed-out attempt is an ordinary failed attempt: it composes with retryCount and with continueOnFailure. Best-effort, and worth being honest about: the abandoned work is interrupted, not undone, so anything it already sent stays sent. On an agent node it bounds the wait for the agent's answer, counted from when the request is sent, so a wait in the queue counts, and so does the agent waiting for a person (a tool approval, a question it asked): the agent may keep running, and an answer that comes later is not used, although its tokens are still billed. A node that waits for a signal (approval, interface, wait timer) is different: it returns immediately and its wait is never bounded by this.",
            "continueOnFailure", "Default false. When every attempt has failed the node is still FAILED and the run ends as it would without this setting (FAILED, or COMPLETED with the item counted as a partial failure when other items of a split succeeded), but the nodes after it RUN instead of being marked SKIPPED. It applies whatever started the run (workflow(action='execute'), a trigger, a schedule, the builder's Run button) and on agent nodes too, including one handed to the background worker. Inside a split, a failed item goes on to the next nodes like a successful one. The node's output carries policy_continue_on_failure=true. After a hard failure the node does NOT produce the fields a success would, so an expression pointing at them resolves to nothing: branch on them with a decision below if the next step needs that data. A refusal for missing credits or a budget is never continued: the platform's credit and plan gates (error_code CREDIT_EXHAUSTED or PLAN_UPGRADE_REQUIRED) an agent the platform refused for credits or a budget (its error starts with Insufficient credits, BUDGET_EXHAUSTED or WORKFLOW_BUDGET_REACHED, or names the tenant balance) and a tool step the catalog refused for lack of credits (catalog_refusal INSUFFICIENT_CREDITS in its output) still stop every node below. Every other failure is continued, including a provider refusing for its own billing and a tool step the catalog refused for another reason. REFUSED on decision, switch, option and loop nodes, and on classify and guardrail agents: a failed branching node selected no port, so continuing would take ALL of them at once.",
            "executeOnce", "Default false. Inside a split, execute for item 0 only and mark every other item SKIPPED with an executeOnce reason. Outside a split it does nothing (one execution is already the semantic), and it does NOT limit loop iterations, so a node in a loop body still runs every iteration. REFUSED on split, aggregate, merge and loop nodes. If branch routing sends item 0 elsewhere, the node executes for NO item."));

        result.put("RATE_LIMITS", orderedMap(
            "no_platform_retry", "The platform never re-sends a call a provider refused. A 'too many requests' answer (429) fails the call on that first answer, and the node fails with the provider's own message. Retrying is this policy's job, and only yours.",
            "backoff_must_outlast_the_window", "For a rate-limited provider set retryCount with a retryBackoffMs at least as long as the provider's limit window. A per-minute limit needs retryBackoffMs=60000: a backoff of a few seconds lands inside the same window and is refused again, spending the attempts for nothing. When the provider sends its own Retry-After, the node waits that long if it is longer than retryBackoffMs.",
            "http_request_is_different", "An http_request node does not FAIL on an HTTP error: it completes with success=false and the provider's status in its output. nodePolicy therefore never retries its HTTP errors (only a network failure or a timeout). Branch on the node's status output instead.",
            "each_attempt_is_a_call", "Every attempt of a tool step is a new request to the provider and counts against your quota with it, so keep retryCount small on a provider that limits you."));

        result.put("WHAT_YOU_SEE", orderedMap(
            "attempts", "A retrying node shows RUNNING until its last attempt. Only that final result is recorded: get_node_output returns it, and its policy_attempt and policy_max_attempts say how many attempts ran. The failed attempts before it leave no record of their own.",
            "timeout", "A timed-out attempt is flagged policy_timeout in the node's output and metadata, with a TIMEOUT message.",
            "retry_stopped", "When the node stops retrying before its budget runs out, its result carries policy_retry_stopped: 'permanent_refusal' (the provider refused the request itself, or the platform did: no credits left, a budget reached, an agent stopped by a person, a tool step the catalog refused, whose output then carries catalog_refusal), 'not_rate_limited' (retryOn='rate_limit' and the failure was not a rate limit) or 'provider_wait_too_long' (the provider asked to wait longer than 60 seconds). The result that ends the execution also carries policy_final_attempt=true.",
            "any_policy_annotates", "A node carrying ANY policy reports policy_attempt and policy_max_attempts on its result (1 and 1 when nothing was retried). A node with no policy block at all does not. Nothing about the execution changes; the fields are simply there to read.",
            "billing", "One node execution costs ONE credit however many attempts it took. An agent node still pays its LLM tokens per attempt that reached the model, and each attempt of a tool step spends your own quota with that provider."));

        result.put("NOT_ON", orderedMap(
            "triggers_and_notes", "A trigger starts the run and a note annotates the canvas, so neither is an executed step and neither takes a policy. Put it on the node that does the work.",
            "continueOnFailure", "decision, switch, option, loop, and classify and guardrail agents.",
            "executeOnce", "split, aggregate, merge, loop."));

        result.put("TYPICAL_FLOW", List.of(
            "1. A step calls a flaky provider: workflow(action='modify', node='Fetch Page', nodePolicy={retryCount: 2, retryBackoffMs: 3000, timeoutMs: 30000}).",
            "2. A step's failure must not stop the run: workflow(action='modify', node='Fetch Page', nodePolicy={retryCount: 1, continueOnFailure: true}). The step still ends FAILED, and so does the run, but the nodes after it run; put a decision on its output below it if the next step needs its data.",
            "3. A step calls a provider that limits requests per minute: workflow(action='modify', node='Post Update', nodePolicy={retryCount: 1, retryBackoffMs: 60000, retryOn: 'rate_limit'}), a backoff that outlasts the limit window, retrying only a rate limit so an ambiguous failure is not re-sent.",
            "4. One setup call inside a split: put executeOnce=true on it, and the other items are SKIPPED with that reason rather than repeating it.",
            "5. Verify with workflow(action='execute', ...) then workflow(action='get_node_output', ...): policy_attempt tells you what actually happened."));

        return result;
    }

    // ==================== VARIABLES ====================

    public static Map<String, Object> getVariablesHelp() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("title", "Variable References - Quick Reference & Rules");
        result.put("description", """
            This guide shows all variable reference syntaxes and explains visibility rules.
            The fundamental rule: A node can ONLY access outputs from its ANCESTORS.
            """);

        result.put("QUICK_REFERENCE", Map.of(
            "title", "Reference Syntax by Node Type - UNIFIED FORMAT",
            "unified_pattern", "ALL node types use: {{type:label.output.field}} - types: trigger, mcp, agent, core, table, interface",
            "CRITICAL_RULE", "The 'label' in {{trigger:label.output.field}} MUST be the EXACT normalized label of the trigger node you created. " +
                "Example: if you created a trigger with label='My Webhook', the reference is {{trigger:my_webhook.output.field}}. " +
                "Normalization: lowercase, spaces→underscores, accents→ascii. Do NOT invent labels.",
            "trigger", Map.of("syntax", "{{trigger:label.output.field}}", "examples", List.of(
                "{{trigger:my_webhook.output.user_id}}  // webhook POST body field directly under output",
                "{{trigger:my_webhook.output.message.text}}  // nested field from webhook body",
                "{{trigger:on_row_change.output.email}}  // table trigger - row column directly under output",
                "{{trigger:on_row_change.output.event_type}}  // table trigger - 'row_created' | 'row_updated' | 'row_deleted'",
                "{{trigger:on_row_change.output.row_id}}  // table trigger - id of the row that changed"
            ), "webhook_output_structure", "Webhook trigger output = the POST body directly. " +
                "If POST body is {\"message\":{\"text\":\"hello\"}}, access it as {{trigger:label.output.message.text}}. " +
                "NO .body. or .payload. wrapper - data is directly under .output.",
                "table_output_structure", "Table (datasource) trigger output = the row's columns directly, flattened at the root of .output. " +
                    "Plus two event fields: event_type ('row_created'|'row_updated'|'row_deleted') and row_id. " +
                    "For row_updated you also get previous_row (column → old value). For row_deleted, .output holds the LAST-KNOWN column values."),
            "mcp", Map.of("syntax", "{{mcp:label.output.field}}", "examples", List.of(
                "{{mcp:fetch_data.output}}  // full output object",
                "{{mcp:api_call.output.status}}  // specific field"
            )),
            "agent", Map.of("syntax", "{{agent:label.output.field}}", "examples", List.of(
                "{{agent:analyzer.output.response}}  // agent response text (primary output)"
            )),
            "core", Map.of("syntax", "{{core:label.output.field}}", "examples", List.of(
                "{{core:retry.output.iteration}}  // loop iteration counter",
                "{{core:process_items.output.items}}  // split items array"
            )),
            "table", Map.of("syntax", "{{table:label.output.field}}", "examples", List.of(
                "{{table:find_users.output.items}}  // all found rows as array",
                "{{table:find_users.output.item_count}}  // number of rows found"
            )),
            "interface", Map.of("syntax", "{{interface:label.output.action_name.field_name}}", "examples", List.of(
                "{{interface:my_form.output.submit.email}}  // form field 'email' submitted via the 'submit' action",
                "{{interface:my_form.output.submit.fired_at}}  // ISO timestamp when 'submit' was clicked",
                "{{interface:my_form.output.is_entry_interface}}  // static field: true if this is the entry tab"
            )),
            "vars", Map.of("syntax", "{{$vars.name}}", "examples", List.of(
                "{{$vars.api_base_url}}  // reusable workspace variable (user-defined config value)",
                "{{$vars.config.endpoint}}  // deeper navigation into a JSON-typed variable"
            ), "notes", "Workflow variables are reusable values shared by all workflows of the workspace, " +
                "not node outputs - no .output. segment and no ancestor rule. {{vars:name}} is an accepted alias. " +
                "An undefined variable resolves to null/empty; the variable must exist BEFORE the run starts " +
                "(values are loaded once per run). When the credential tool is available, " +
                "credential(action='variables') lists them and credential(action='set_variable') creates one.")
        ));

        result.put("NUMBER_AND_BOOLEAN_SETTINGS",
            "A numeric or true/false setting of a core or table node (limit.count, code.timeoutSeconds, " +
            "wait.duration, a table read's limit/offset, split maxItems, loop maxIterations, http timeout, " +
            "approval requiredApprovals/timeoutMs, ssh/database port...) accepts a {{...}} reference instead of " +
            "a literal, e.g. limit: {count: '{{core:settings.output.page_size}}'}. It is resolved when the node " +
            "runs; a reference to a number or to numeric text ('25') both work, and wait.duration is read in " +
            "milliseconds. If it resolves to nothing, or to something that is not a number (or not true/false), " +
            "the node FAILS and its error names the setting: check the referenced node ran and the path exists. " +
            "Not supported (use a literal): an agent's temperature / maxTokens / maxIterations, and settings " +
            "nested in a list such as html_extract fields[].required.");

        result.put("VISIBILITY_RULE", Map.of(
            "statement", "A node can ONLY access outputs from nodes that execute BEFORE it",
            "definition", "Ancestor = any node that must COMPLETE before the current node can START",
            "diagram", """
                VALID ACCESS:
                    Trigger → Step A → Step B → Step C
                    At Step C: CAN access Trigger, Step A, Step B

                PARALLEL BRANCHES (Fork):
                    Trigger → Step A ─┬─→ Step B
                                      └─→ Step C
                    At Step B: CAN access Trigger, Step A
                               CANNOT access Step C (parallel peer!)
                """
        ));

        result.put("SPECIAL_CASES", Map.of(
            "merge_node", Map.of("rule", "At a merge, all predecessor outputs are accessible"),
            "loop_exit", Map.of("rule", "After loop ends, only LAST iteration's outputs available"),
            "decision_branch", Map.of("rule", "Nodes in a decision branch can access all ancestors upstream of the decision. The decision node itself does not produce usable output.")
        ));

        return result;
    }

    // ==================== SPEL ====================

    public static Map<String, Object> getSpelHelp() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("title", "SpEL Expression Syntax & Functions");
        result.put("description", """
            SpEL (Spring Expression Language) is used for variable references, calculations, and data transformations.
            All expressions use the {{...}} syntax. Use functions directly: {{uppercase(trigger:form.output.name)}}
            """);

        result.put("BASIC_SYNTAX", Map.ofEntries(
            Map.entry("format", "{{type:label.output.field}} - UNIFIED for ALL types"),
            Map.entry("prefixes", Map.ofEntries(
                Map.entry("trigger", "{{trigger:label.output.field}}"),
                Map.entry("mcp", "{{mcp:label.output.field}}"),
                Map.entry("agent", "{{agent:label.output.response}}"),
                Map.entry("core_split_persisted", "{{core:label.output.items}} // full list (persisted in DB). Prefix is still 'core:'."),
                Map.entry("core_split_runtime", "{{core:label.output.current_item}} // per-item in body nodes (runtime only). {{core:label.output.current_index}} // 0-based index. Prefix is still 'core:'."),
                Map.entry("loop", "{{core:label.output.iteration}}"),
                Map.entry("table_find_rows", "{{table:label.output.items}} // found rows array. {{table:label.output.item_count}} // row count. To iterate: connect a Split node."),
                Map.entry("table", "{{table:label.output.field}} // CRUD outputs (row_count, rows, etc.)"),
                Map.entry("interface", "{{interface:label.output.action_name.field_name}} // user-submitted data; action_name is the normalized trigger label, e.g. {{interface:my_form.output.submit.email}}"),
                Map.entry("vars", "{{$vars.name}} // reusable workspace variable (user-defined config, not a node output; no .output. segment). Alias: {{vars:name}}. Works in any param or condition, e.g. {{$vars.api_base_url}}/users")
            )),
            Map.entry("expression_modes", Map.of(
                "pure_expression", "{{int(mcp:fetch.output.count) + 1}} → returns typed value (int, list, etc.)",
                "template_string", "Hello {{trigger:form.output.name}}! → returns concatenated string"
            ))
        ));

        result.put("OPERATORS", Map.of(
            "arithmetic", "+ - * / %",
            "comparison", "== != < > <= >=",
            "logical", "&& || !",
            "ternary", "condition ? true_val : false_val",
            "string_concat", "{{'Hello ' + trigger:form.output.name}}"
        ));

        // The single most common SILENT failure: operators left OUTSIDE the braces.
        Map<String, Object> boundaryTrap = new LinkedHashMap<>();
        boundaryTrap.put("rule", "SpEL is evaluated ONLY inside {{...}}. Any operator, comparison, or ternary written OUTSIDE the braces stays LITERAL TEXT - the field resolves to the wrong value and NO error is raised.");
        boundaryTrap.put("wrap_the_whole_expression", "For a field that must produce a VALUE (switch expression, transform/aggregate field, set value), wrap the ENTIRE expression in ONE {{...}} - never leave operators outside the braces.");
        boundaryTrap.put("switch_wrong", "{{n}} < 5 ? 'low' : 'high'   // BROKEN: only {{n}} is evaluated, the result is the literal string \"3 < 5 ? 'low' : 'high'\", so the switch matches no case and silently falls through to default");
        boundaryTrap.put("switch_right", "{{n < 5 ? 'low' : 'high'}}   // CORRECT: the whole ternary is evaluated, resolves to 'low'");
        boundaryTrap.put("transform_wrong", "{{a}} + {{b}}   // BROKEN: each ref is resolved then concatenated as text ('3'+'4' -> '34'); '3 + 4' with no braces stays the literal \"3 + 4\"");
        boundaryTrap.put("transform_right", "{{a + b}}   // CORRECT: evaluates to 7");
        boundaryTrap.put("decision_and_loop_exception", "ONLY decision conditions and loop conditions re-evaluate the fully-resolved string as SpEL, so BOTH {{amount > 100}} and {{amount}} > 100 work there. Every value-producing field (switch / transform / aggregate / set) does NOT - always wrap the whole expression.");
        boundaryTrap.put("validate_hint", "workflow(action='validate') flags the comparison/ternary form of this mistake (an EXPRESSION_NOT_EVALUATED warning on the node). Arithmetic left outside the braces ({{a}} + {{b}}) is equally broken but is NOT auto-detected (it is indistinguishable from a literal separator), so always wrap the whole expression yourself.");
        result.put("EXPRESSION_BOUNDARY_TRAP", boundaryTrap);

        // --- FUNCTIONS WITH SIGNATURES (43 total) ---
        Map<String, Object> functions = new LinkedHashMap<>();

        functions.put("type_casting", Map.of(
            "int(value)", "Convert to integer. int('42') → 42, int(3.7) → 3",
            "double(value)", "Convert to double. double('3.14') → 3.14",
            "string(value)", "Convert to string. string(42) → '42'",
            "bool(value)", "Convert to boolean. bool('true') → true, bool(1) → true",
            "long(value)", "Convert to long",
            "float(value)", "Convert to float"
        ));

        functions.put("utility", Map.of(
            "size(value)", "Size of collection, map, string, or array. size('hello') → 5, size(myList) → 3",
            "len(value)", "Alias for size()",
            "length(value)", "String length. length('hello') → 5",
            "typeof(value)", "Type name: 'string', 'int', 'double', 'bool', 'list', 'map', 'null'",
            "default(value, fallback)", "Return fallback if value is null/empty. default(mcp:api.output.name, 'Unknown')",
            "coalesce(v1, v2, ...)", "First non-null, non-empty value. coalesce(field1, field2, 'default')",
            "ifempty(value, fallback)", "Return fallback if value is null or empty string",
            "isnull(value)", "Returns true if value is null",
            "isempty(value)", "Returns true if null, empty string, empty collection, or empty map"
        ));

        functions.put("math", Map.of(
            "abs(value)", "Absolute value. abs(-5) → 5",
            "round(value, decimals)", "Round to N decimal places. round(3.14159, 2) → 3.14",
            "floor(value)", "Round down. floor(3.7) → 3",
            "ceil(value)", "Round up. ceil(3.2) → 4",
            "min(a, b)", "Minimum of two values. min(5, 3) → 3",
            "max(a, b)", "Maximum of two values. max(5, 3) → 5",
            "pow(base, exponent)", "Power. pow(2, 3) → 8.0",
            "sqrt(value)", "Square root. sqrt(16) → 4.0"
        ));

        functions.put("string_manipulation", Map.ofEntries(
            Map.entry("uppercase(value)", "To uppercase. uppercase('hello') → 'HELLO'"),
            Map.entry("lowercase(value)", "To lowercase. lowercase('HELLO') → 'hello'"),
            Map.entry("capitalize(value)", "Capitalize first letter. capitalize('hello world') → 'Hello world'"),
            Map.entry("trim(value)", "Remove leading/trailing whitespace"),
            Map.entry("truncate(value, maxLength, suffix)", "Truncate with suffix. truncate('Hello World', 5, '...') → 'He...'"),
            Map.entry("replace(value, search, replacement)", "Replace occurrences. replace('hello', 'l', 'r') → 'herro'"),
            Map.entry("substring(value, start, end)", "Extract substring. substring('hello', 1, 3) → 'el'. end is optional"),
            Map.entry("split(value, delimiter)", "Split to list. split('a,b,c', ',') → ['a','b','c']"),
            Map.entry("join(list, delimiter)", "Join list to string. join(myList, ', ') → 'a, b, c'"),
            Map.entry("padleft(value, length, padChar)", "Pad left. padleft('42', 5, '0') → '00042'"),
            Map.entry("padright(value, length, padChar)", "Pad right. padright('hi', 5, '.') → 'hi...'")
        ));

        functions.put("string_checks", Map.of(
            "startswith(value, prefix)", "Check prefix. startswith('hello', 'hel') → true",
            "endswith(value, suffix)", "Check suffix. endswith('hello', 'llo') → true",
            "contains(value, search)", "Check contains. contains('hello', 'ell') → true. Also works on collections",
            "matches(value, regex)", "Regex match. matches('abc123', '.*\\d+') → true"
        ));

        functions.put("date_and_format", Map.of(
            "now()", "Current datetime as ISO string (e.g. '2026-03-04T15:30:45')",
            "today()", "Today's date as ISO string (e.g. '2026-03-04')",
            "formatdate(value, pattern)", "Format date/datetime. formatdate(now(), 'dd/MM/yyyy HH:mm') → '04/03/2026 15:30'",
            "formatnumber(value, decimals)", "Format number. formatnumber(1234.5, 2) → '1,234.50'",
            "formatcurrency(value, currencyCode)", "Format currency. formatcurrency(29.99, 'EUR') → '29,99 EUR'"
        ));

        functions.put("json", Map.of(
            "json(value)", "Parse a JSON string into a typed object (Map / List / scalar). Idempotent on already-typed Map/List. Use when a tool param must be an object/array but the value is a JSON string. Example: {{json('{\"responseModalities\":[\"IMAGE\"]}')}} or {{json(trigger:webhook.output.raw_body)}}.",
            "fromjson(value)", "Alias for json() - GitHub Actions parity (fromJSON).",
            "tojson(value)", "Serialize a Map/List/scalar to a compact JSON string. Inverse of json(): json(tojson(map)) round-trips."
        ));

        result.put("FUNCTIONS", functions);

        result.put("EXAMPLES", List.of(
            "{{uppercase(trigger:form.output.name)}} → uppercase the form input",
            "{{int(mcp:fetch.output.count) > 10 ? 'many' : 'few'}} → conditional text",
            "{{round(mcp:calc.output.price * 1.2, 2)}} → compute price with tax",
            "{{default(mcp:api.output.title, 'Untitled')}} → fallback value",
            "{{contains(agent:check.output.response, 'approved')}} → check agent response",
            "{{formatcurrency(mcp:order.output.total, 'EUR')}} → format as currency",
            "{{size(mcp:search.output.results)}} → count results",
            "{{join(split(trigger:form.output.tags, ','), ' | ')}} → reformat tags",
            "{{json('{\"responseModalities\":[\"IMAGE\"]}')}} → typed Map for an object-typed tool param",
            "{{json(trigger:webhook.output.raw_body)}} → reparse a stringified JSON payload back to an object"
        ));

        result.put("RESERVED_WORDS", Map.of(
            "list", "true, false, null, and, or, not, eq, ne, lt, gt, le, ge, instanceof, matches, between, T, new",
            "warning", "These words cannot be used as variable names"
        ));

        return result;
    }

    // ==================== HELPERS ====================

    public static Map<String, Object> getMinimalExample() {
        return Map.of(
            "name", "My Workflow",
            "plan", Map.of(
                "triggers", List.of(Map.of("label", "start", "type", "manual")),
                "mcps", List.of(Map.of("label", "call_api", "id", "category/tool_name", "params", Map.of("param", "{{trigger:start.output}}"))),
                "edges", List.of(Map.of("from", "trigger:start", "to", "mcp:call_api"))
            ),
            "note", "Find tool IDs via workflow(action='search') or catalog(action='search'). Use table(action='list') for table IDs."
        );
    }

    private static Map<String, Object> buildHowToBuild() {
        return Map.of(
            "auto_connect", "Use connect_after='PreviousLabel' on every non-trigger add_node to auto-wire edges",
            "manual_connect", "Use workflow(action='connect', from='Trigger Label', to='Step Label') for custom connections",
            "workflow", List.of(
                "1. workflow(action='init', name='My Workflow', description='What this workflow does')",
                "2. workflow(action='add_node', type='form', label='Start', params={fields: [{name: 'input', type: 'text'}]})",
                "3. workflow(action='add_node', type='<tool-uuid>', label='Process', params={...}, connect_after='Start')",
                "4. workflow(action='finish') to finalize and save (closes the build session)"
            )
        );
    }

    private static Map<String, Object> buildForkParallel() {
        return Map.of(
            "what", "Multiple branches that ALL execute in parallel",
            "implicit_fork", "Any node with multiple outgoing edges = implicit fork. No explicit fork node needed.",
            "explicit_fork", "Use type='fork' for named parallel branches with ports (branch_0, branch_1).",
            "how_to_create", List.of(
                "workflow(action='connect', from='Start', to='Email')",
                "workflow(action='connect', from='Start', to='Slack')  // Same source, different targets = implicit FORK"
            ),
            "behavior", "ALL branches execute (no SKIPPED)"
        );
    }

    private static Map<String, Object> buildMergeConvergence() {
        return Map.of(
            "what", "Wait for ALL branches before continuing",
            "implicit_merge", "Any node with multiple incoming edges = implicit merge. No explicit merge node needed.",
            "explicit_merge", "Use type='merge' for named synchronization point.",
            "how_to_create", List.of(
                "workflow(action='connect', from='Email', to='Combine')",
                "workflow(action='connect', from='Slack', to='Combine')  // Multiple sources, same target = implicit MERGE"
            ),
            "behavior", "Waits for ALL predecessors to COMPLETE or be SKIPPED (AND mode only, no OR)"
        );
    }

    private static Map<String, Object> buildDecisionExclusive() {
        return Map.of(
            "what", "ONE branch executes based on conditions",
            "syntax", "conditions=[ {condition: '{{...}}', label: 'Display Name'}, ... ] - order matters! First match = 'if', subsequent = 'elseif', condition='default' = 'else'. The 'label' field is for UI display only.",
            "example", List.of(
                "{condition: '{{mcp:fetch.output.amount > 100}}', label: 'High'}  // full SpEL expression inside {{...}}",
                "{condition: '{{mcp:fetch.output.amount > 50}}', label: 'Medium'}",
                "{condition: 'default', label: 'Low'}  // 'default' = ELSE (catch-all)"
            ),
            "condition_syntax", "The entire SpEL expression goes inside {{...}}: comparisons, function calls, ternary. Returns a boolean. Example: {{contains(agent:analyze.output.response, 'urgent')}}",
            "add_node_vs_plan", "add_node uses params={conditions: [{condition: '...', label: '...'}, ...]} (array 'conditions', each entry has 'condition' field). set_plan uses 'decisionConditions' array with 'expression' and 'type' (if/elseif/else).",
            "rule", "First match wins. condition='default' = ELSE (catch-all). In plan JSON: type='else'. The expression field is optional; if included, set it to 'default'.",
            "vs_fork", "Decision = exclusive (ONE), Fork = parallel (ALL)"
        );
    }

    private static Map<String, Object> buildLoopIteration() {
        return Map.of(
            "what", "Repeat steps while condition is true",
            "how_to_create", List.of(
                "workflow(action='add_node', type='loop', label='Retry', params={condition: '{{core:retry.output.iteration < 3}}'}, connect_after='Previous')",
                "workflow(action='add_node', type='<tool-uuid>', label='Try', params={...}, connect_after='Retry:body')  // Inside loop",
                "workflow(action='add_node', type='<tool-uuid>', label='Done', params={...}, connect_after='Retry:exit')  // After loop"
            ),
            "variables", "{{core:label.output.iteration}} (loop), {{core:label.output.current_item}} (split body - runtime), {{core:label.output.items}} (split persisted list)",
            "self_reference", "A loop condition CAN reference its own output ({{core:label.output.iteration}}) - the engine initializes iteration=0 before first evaluation. Other nodes cannot reference themselves; to repeat WITHOUT a loop node, connect back to an earlier node instead (see loop_back).",
            "loop_back", Map.of(
                "what", "Connect any node back to a node that already ran, and that part of the workflow repeats. No loop node needed.",
                "how", "workflow(action='connect', from='Check:else', to='Fetch Page') - because 'Fetch Page' already ran before 'Check', the connection is recognised as a loop-back and re-enters it instead of waiting for it.",
                "from_a_branch", "Connect from a specific port to loop only on that branch: from='Check:else' repeats while the else branch is taken, and the 'if' branch continues forward. That branch selection IS the loop condition. A loop from a node that ALSO continues forward never runs (the workflow always takes the forward path) - validate reports it.",
                "stopping", "Every loop-back has an iteration limit. Set your own with max_iterations on connect, and optionally loop_condition to stop earlier; without either, the limit is the only thing that ends the loop.",
                "when_the_limit_is_hit", "If the limit is reached while the loop still wants to run, the run FAILS with reason 'max_iterations_reached' on the node the loop came from - the workflow did not reach its own stopping condition. Make the branch stop selecting itself (or raise max_iterations).",
                "not_allowed_inside", "A trigger, an interface, a fork/merge, a split or stop_on_error cannot sit inside the repeated part: they hold state for one pass only. validate reports them as errors, with the node named."
            ),
            "add_node_vs_plan", "add_node params use 'condition' key. set_plan JSON uses 'loopCondition'.",
            "find_rows_pattern", "find_rows returns items[] array. To iterate per-row: FindRows → Split (list={{table:label.output.items}}) → ProcessStep"
        );
    }

    private static Map<String, Object> buildVariableVisibility() {
        return Map.of(
            "rule", "Can ONLY access outputs from nodes that execute BEFORE",
            "can_access", List.of("All Triggers", "Ancestor steps", "{{core:label.output.current_item}} (split body - runtime)", "{{core:label.output.items}} (split persisted list)", "{{core:label.output.iteration}} (loop)", "{{table:label.output.items}} (find_rows array)"),
            "cannot_access", List.of("Peer nodes (parallel branches)", "Future nodes"),
            "syntax", "{{type:label.output.field}} - types: trigger, mcp, agent, core, table, interface - ALL use .output."
        );
    }

    private static Map<String, Object> buildInterfaceDagPattern() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("concept", """
            An interface belongs to ONE DAG and can only fire triggers within that same DAG.
            For interactive apps: create one DAG with one or more triggers + processing steps + interface.
            Multiple buttons on one page = multiple triggers in the SAME DAG, all connected to the same interface.""");
        result.put("concrete_example", Map.of(
            "app", "Order Management Dashboard",
            "interface", "One page with: search form, delete button, refresh button",
            "single_dag", List.of(
                "trigger:search → mcp:query_orders → interface:dashboard",
                "trigger:delete → mcp:delete_order → interface:dashboard",
                "trigger:refresh → mcp:load_all → interface:dashboard"
            ),
            "action_mapping", "{'#search-form': 'trigger:search:submit', '#delete-btn': 'trigger:delete:click', '#refresh-btn': 'trigger:refresh:click'}  // :submit and :click are action types (form submit or button click)",
            "result", "All triggers and the interface are in the SAME DAG. Each button fires its own trigger. Results accumulate (pagination)."
        ));
        result.put("build_order", List.of(
            "1. Create the interface: interface(action='create', name='Dashboard', html_template='...', ...)",
            "2. Add ALL triggers FIRST (they must exist before being referenced in action_mapping)",
            "3. Add processing steps connected to each trigger",
            "4. Add the interface node connected to one of the processing steps, with action_mapping referencing the triggers",
            "5. All triggers, steps, and the interface form one DAG"
        ));
        result.put("key_rules", List.of(
            "Create ALL target triggers BEFORE the interface node that references them in action_mapping",
            "All referenced triggers must be in the SAME DAG as the interface",
            "variable_mapping values can be workflow data (e.g. '{{mcp:query.output.results}}') or static text (e.g. 'My Title'). Use {{...}} for dynamic values.",
            "Interface nodes count as valid steps - no need for dummy Transform nodes"
        ));
        return result;
    }

    private static Map<String, Object> buildMultiDag() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("what", "A single workflow can have MULTIPLE triggers. Each trigger fires its own EPOCH. Whether the triggers form separate DAGs or one shared DAG is auto-detected from edge topology: if the triggers' descendants overlap, they form ONE shared DAG; if they don't, they form independent DAGs.");
        result.put("key_rule", "Triggers must have UNIQUE labels. Shared descendants ARE allowed (auto-detected as one DAG group). Each trigger still fires its OWN epoch. A shared sink (wait, transform, mcp, interface, etc.) with multiple incoming trigger edges executes once per trigger fire - the engine recognizes multi-trigger convergence and only waits for the trigger that actually fired, not the others. Interfaces additionally support persistent per-trigger UI state.");
        result.put("when_to_use", Map.of(
            "purpose", "Group related entry points in ONE workflow. Depending on how you wire the edges, they produce either INDEPENDENT DAGs (no shared descendants) or a SHARED DAG (triggers overlap downstream).",
            "independent_dags_use_case", "Different business processes sharing one workflow: webhook for real-time events + schedule for batch cleanup - no shared downstream nodes.",
            "shared_dag_use_case", "Interactive app / dashboard: multiple triggers (search, delete, refresh) all feeding the same sink node. Interfaces are the typical choice because they persist per-trigger UI state, but any node type (merge, wait, transform, mcp, …) works as a shared sink - the engine recognizes multi-trigger convergence and executes the sink once per trigger fire.",
            "cross_dag_calls", "One DAG's HTTP Request node can call a webhook trigger in another DAG of the same workflow (still independent epochs)."
        ));
        result.put("how_it_works", Map.of(
            "independence", "Independent DAGs (no descendant overlap): triggering DAG-A does NOT affect DAG-B.",
            "shared_dag", "Shared DAG (triggers with overlapping descendants): each trigger fires a separate EPOCH inside the same DAG. A shared sink node runs once per epoch - when a trigger fires, the engine filters out the other trigger predecessors (they don't fire in this epoch) and only waits for the current trigger. Any node type (wait, transform, mcp, interface, …) works as a shared sink; Interface nodes additionally keep per-trigger UI state across fires.",
            "execution", "Each trigger activation runs ONLY its own epoch of its own DAG group, not the entire workflow.",
            "variables", "Nodes in DAG-A CANNOT reference outputs from DAG-B (different execution context). Within a shared DAG, a node can only see outputs from its ancestors in the epoch it runs in.",
            "interface_scope", "An interface belongs to ONE DAG - it can only fire triggers within that same DAG."
        ));
        result.put("diagram", """
            SINGLE-DAG (1 trigger):
                trigger:webhook → mcp:fetch → agent:analyze → mcp:save

            SINGLE-DAG INTERACTIVE APP (interface + multiple triggers in ONE DAG):
                trigger:search → mcp:query     → interface:dashboard
                trigger:delete → mcp:remove    → interface:dashboard
                trigger:refresh → mcp:load_all → interface:dashboard
                action_mapping: {'#search': 'trigger:search:submit', '#del': 'trigger:delete:click'}
                → All triggers and the interface in the SAME DAG.

            MULTI-DAG (independent processes grouped in one workflow):
                DAG 1: trigger:webhook → mcp:process_realtime → mcp:save
                DAG 2: trigger:schedule → mcp:batch_cleanup → mcp:notify
                → Different business processes, each with its own trigger and DAG.
            """);
        result.put("how_to_build", List.of(
            "1. Add all triggers with UNIQUE labels: workflow(action='add_node', type='form|webhook|schedule|…', label='...')",
            "2. For each trigger, add its downstream nodes via connect_after='<trigger_label>'",
            "3. INDEPENDENT DAGs: keep each trigger's subgraph disjoint - no node is a descendant of more than one trigger",
            "4. SHARED DAG: to have triggers converge on a sink (interactive app), connect multiple triggers' branches into the SAME Interface node (use connect() to add the second edge). The DAG is auto-detected as shared by topology",
            "5. For a shared DAG, any node type works as the sink - the engine auto-recognizes multi-trigger convergence and runs the sink once per trigger fire. Interface nodes additionally persist per-trigger UI state across fires"
        ));
        result.put("validation", "Shared descendants between triggers are AUTO-DETECTED as one DAG group and are NOT rejected. Rejection only happens when the plan has clearly-separate DAGs that unexpectedly overlap (detected by the DAG grouping logic). If you want triggers fully isolated, give each its own subgraph with no overlapping descendants.");
        result.put("plan_format", Map.of(
            "triggers", "[{label: 'Search Input', type: 'form', ...}, {label: 'Delete Item', type: 'form', ...}]",
            "edges", "[{from: 'trigger:search_input', to: 'mcp:run_search'}, {from: 'trigger:delete_item', to: 'mcp:delete_row'}, ...]",
            "note", "For INDEPENDENT DAGs: each trigger's edges form a disjoint subgraph. For a SHARED DAG: add edges from multiple triggers (possibly via intermediate steps) to the same downstream node (typically an Interface). The DAG grouping is auto-detected from descendant overlap."
        ));
        return result;
    }

}
