package com.apimarketplace.orchestrator.tools.workflow.builder;

import java.util.*;

/**
 * Contextual prompts for the workflow builder.
 * Provides phase-aware help and tool prioritization.
 *
 * DESIGN PRINCIPLES (see the project docs):
 * 1. EFFICIENCY & CLARITY: LLM must understand quickly (even if more tokens)
 * 2. JIT (Just-In-Time): Right info at the right moment
 * 3. TOKEN OPTIMIZATION: Last priority, never sacrifice clarity
 */
public final class WorkflowBuilderPrompts {

    private WorkflowBuilderPrompts() {}

    // ==================== SESSION PHASES ====================

    public enum Phase {
        NO_SESSION,    // No active session - need init or load
        STARTING,      // No trigger yet - add one first
        BUILDING,      // Has trigger, needs steps
        WIRING,        // Has steps, needs connections
        FIXING,        // Has connections but validation errors remain
        READY          // Valid, ready to create/save
    }

    // ==================== DETECT PHASE ====================

    public static Phase detectPhase(WorkflowBuilderSession session) {
        if (session == null) {
            return Phase.NO_SESSION;
        }

        // Check for validation errors
        List<String> orphans = session.findOrphanNodes();
        boolean hasErrors = !orphans.isEmpty();

        // Check completeness
        boolean hasTrigger = !session.getTriggers().isEmpty();
        boolean hasSteps = !session.getMcps().isEmpty()
            || !session.getCores().isEmpty()
            || !session.getInterfaces().isEmpty()
            || !session.getTables().isEmpty();
        boolean hasEdges = !session.getEdges().isEmpty();

        if (!hasTrigger) {
            return Phase.STARTING;
        }

        if (!hasSteps) {
            return Phase.BUILDING;
        }

        if (!hasEdges) {
            return Phase.WIRING;
        }
        if (hasErrors) {
            return Phase.FIXING;
        }

        return Phase.READY;
    }

    // ==================== CONTEXTUAL HELP ====================

    /**
     * Get contextual help based on current session phase.
     * Simplified - just phase and generic references.
     * LLM decides what to do based on user intent, not our recommendations.
     */
    public static Map<String, Object> getContextualHelp(WorkflowBuilderSession session) {
        Phase phase = detectPhase(session);

        Map<String, Object> help = new LinkedHashMap<>();
        help.put("phase", phase.name());

        // Generic references only - no specific node type suggestions
        switch (phase) {
            case NO_SESSION -> {
                help.put("NEXT", "workflow(action='init', name='...') or workflow(action='load', id='...')");
            }
            case STARTING -> {
                // Trigger is mandatory first - this is a rule, not a suggestion
                help.put("NEXT", "Add a trigger first. Types: form, chat, webhook, schedule, table, manual. Use workflow(action='help', topics=['<type>']) for params.");
            }
            case BUILDING -> {
                help.put("available_actions", "workflow(action='list_nodes')");
                help.put("BEFORE_ADD_NODE", "workflow(action='help', topics=['<type>']) to see required params");
                help.put("NEXT", "Add steps: tool UUIDs from catalog, agents, decision, loop, interface, etc.");
            }
            case WIRING -> {
                help.put("available_actions", "workflow(action='list_nodes')");
                help.put("NEXT", "Connect nodes: workflow(action='connect', from='Source', to='Target'). Use workflow(action='validate') to see disconnected nodes.");
            }
            case FIXING -> {
                help.put("NEXT", "Fix issues: workflow(action='validate') to see errors, then workflow(action='modify') or workflow(action='remove') to fix them.");
            }
            case READY -> {
                help.put("NEXT", "workflow(action='finish') to finalize and save (closes the session)");
            }
        }

        return help;
    }

    /**
     * Get a compact one-line context string for the session.
     * This is shown at the TOP of every response for quick orientation.
     */
    public static String getCompactContext(WorkflowBuilderSession session) {
        if (session == null) {
            return "NO SESSION";
        }

        Phase phase = detectPhase(session);
        int triggers = session.getTriggers().size();
        int steps = session.getMcps().size();
        int cores = session.getCores().size();
        int interfaces = session.getInterfaces().size();
        int tables = session.getTables().size();

        StringBuilder sb = new StringBuilder();
        sb.append("SESSION '").append(session.getWorkflowName()).append("'");
        sb.append(" | Phase: ").append(phase.name());
        sb.append(" | Nodes: ").append(triggers + steps + cores + interfaces + tables);

        if (interfaces > 0) {
            sb.append(" | Interfaces: ").append(interfaces);
        }

        // Add issues count if any
        List<String> orphans = session.findOrphanNodes();
        if (!orphans.isEmpty()) {
            sb.append(" | WARN: ").append(orphans.size()).append(" disconnected");
        }

        return sb.toString();
    }

    /**
     * Get session state summary for injection into responses.
     * SIMPLIFIED: No more verbose diagram, just essential info.
     */
    public static Map<String, Object> getSessionState(WorkflowBuilderSession session) {
        if (session == null) {
            return Map.of("active", false);
        }

        Map<String, Object> state = new LinkedHashMap<>();
        state.put("active", true);
        state.put("name", session.getWorkflowName());

        // Counts - compact
        int triggers = session.getTriggers().size();
        int steps = session.getMcps().size();
        int cores = session.getCores().size();
        int interfaces = session.getInterfaces().size();
        int tables = session.getTables().size();
        int edges = session.getEdges().size();

        state.put("node_count", triggers + steps + cores + interfaces + tables);
        state.put("edge_count", edges);

        // Phase and status
        Phase phase = detectPhase(session);
        state.put("phase", phase.name());

        // Last action - useful for context
        if (session.getLastAddedNodeId() != null) {
            state.put("last_added", session.getLastAddedNodeId());
        }

        // NOTE: Visual structure (diagram) removed to reduce verbosity
        // Agent can call workflow(action='describe') if needed

        return state;
    }

    /**
     * Get suggested next actions based on current state.
     */
    public static List<String> getSuggestedActions(WorkflowBuilderSession session) {
        Phase phase = detectPhase(session);

        return switch (phase) {
            case NO_SESSION -> List.of("init", "load");
            case STARTING -> List.of("add_node (trigger: form, chat, webhook, schedule, table, manual)");
            case BUILDING -> List.of(
                "add_node (tool UUID from catalog(action='search', query='...'))",
                "add_node (agent - create first with agent(action='create'))",
                "add_node (control: decision, switch, loop, split, fork, merge, option)",
                "add_node (utility: transform, wait, http_request, download_file, data_input, code, approval, exit, response)",
                "add_node (data: filter, sort, limit, aggregate, remove_duplicates, summarize, compare_datasets, sub_workflow, set [assign/transform fields], html_extract [parse HTML via CSS selectors])",
                "add_node (file: convert_to_file, extract_from_file, compression, rss, xml)",
                "add_node (comms: send_email, email_inbox, respond_to_webhook)",
                "add_node (AI: guardrail, classify)",
                "add_node (crypto/time: date_time, crypto_jwt)",
                "add_node (interface - create first with interface(action='create'))",
                "table CRUD (direct actions): find_rows, insert_row, read_rows, update_row, delete_row - require table_id param",
                "validate"
            );
            case WIRING -> List.of("connect", "validate", "describe", "modify", "remove");
            case FIXING -> List.of("validate", "modify", "remove", "connect", "undo");
            case READY -> List.of("finish", "save", "validate");
        };
    }

    // ==================== HELP TEXT FOR SPECIFIC ACTIONS ====================

    /**
     * Get inline help for a specific action.
     * Included in error messages when user makes mistakes.
     */
    public static String getActionHelp(String action) {
        return switch (action) {
            case "init" -> """
                ⚠️ ALWAYS provide name AND description at init time:

                workflow(action='init', name='Daily Sales Report', description='Fetches sales data and sends summary email every morning')

                REQUIRED PARAMETERS:
                • name: Clear, descriptive workflow name (e.g., 'Customer Onboarding Flow', 'Weekly Invoice Generator')
                • description: What the workflow does and when it runs (e.g., 'Sends welcome emails to new customers when they sign up')

                ❌ BAD: workflow(action='init')
                ❌ BAD: workflow(action='init', name='test')
                ✅ GOOD: workflow(action='init', name='New Customer Welcome', description='Automatically sends welcome email and creates CRM entry when new customer signs up')

                To EDIT an existing workflow: workflow(action='load', id='<uuid>')
                """;
            case "add_trigger" -> """
                Trigger types go directly in the 'type' field:
                workflow(action='add_node', type='form', label='Contact Form', params={fields: [{name:'email',type:'text',label:'Email'}]})
                workflow(action='add_node', type='table', label='On Row Change', params={table_id: 123})
                workflow(action='add_node', type='schedule', label='Daily Job', params={schedule: '0 9 * * *'})
                workflow(action='add_node', type='webhook', label='API Hook')
                workflow(action='add_node', type='chat', label='Chatbot')
                workflow(action='add_node', type='manual', label='Manual Start')
                Each trigger type has specific params. Use workflow(action='help', topics=['<type>']) for details.
                Note: 'table' is the builder name for datasource triggers. 'workflow' and 'error' triggers are system-managed (not creatable).
                """;
            case "add_mcp" -> """
                UNIFIED FORMAT - Tool UUID goes in 'type', params are FLAT (no nested 'input'):
                1. Find tool: catalog(action='search', query='send email')
                2. Add step: workflow(action='add_node', type='<tool-uuid>', label='Send Email', params={to: '{{trigger:form.output.email}}', subject: 'Hello'}, connect_after='Form')

                The type field IS the tool UUID from catalog search results.
                All tool parameters go flat in params (no 'input' wrapper).
                connect_after specifies which node this step follows (use the node's label).
                """;
            case "add_agent" -> """
                ⚠️ BEFORE adding an agent, ask: can deterministic nodes do this instead?
                For each step, if the output is predictable from the input → use a deterministic node.
                Agent = ONLY for steps requiring creativity, interpretation, or open-ended reasoning.

                Pre-process data with deterministic nodes BEFORE the agent - feed it structured data, not raw responses.
                ❌ One agent that fetches, parses, filters, formats, AND reasons
                ✅ Deterministic pipeline (fetch → extract → filter → format) → agent (reason/create) → deterministic output

                Steps: 1. CREATE: agent(action='create', name='...', system_prompt='...') → UUID
                       2. ADD: workflow(action='add_node', type='agent', label='...', params={agent_id:'<uuid>', prompt:'...'}, connect_after='...')
                Required: label, agent_id. Optional: prompt, withMemory (default true).
                """;
            case "add_condition", "decision" -> """
                workflow(action='add_node', type='decision', label='Check Status', params={conditions: [
                  {condition: '{{mcp:api_call.output.status == 200}}', label: 'Success'},
                  {condition: 'default', label: 'Error'}
                ]}, connect_after='API Call')
                Order: IF (first condition), ELSEIF (additional conditions)..., ELSE (condition='default', must be last).
                ONE branch executes - the first whose condition is true.
                Entire expression goes inside {{...}}: '{{mcp:step.output.field == value}}'.
                Ports: if, elseif_0, elseif_1, ..., else. Use in connect: from='Check Status:if'.
                """;
            case "add_loop" -> """
                LOOP (while-condition):
                workflow(action='add_node', type='loop', label='Retry Logic', params={condition: '{{core:retry_logic.output.iteration < 3}}'}, connect_after='Start')
                Creates WHILE loop. Variables: {{core:label.output.iteration}} counter.
                Body: steps inside loop use connect_after='Retry Logic' (auto-assigned to body port).
                Exit: connect_after_loop='Retry Logic' on the step that should execute AFTER the loop (exit port).
                Ports: body (repeated steps), exit (after loop ends).

                For ARRAY ITERATION, use split instead: workflow(action='help', topics=['split'])
                """;
            case "add_interface", "interface" -> """
                INTERFACE nodes require 2 steps:
                1. CREATE the interface: interface(action='create', name='...', description='...', html_template='<div>{{title|Default}}</div>', css_template='.card { ... }', js_template='// ...')
                   → Returns UUID. ALWAYS provide all 3 templates.
                2. ADD to workflow: workflow(action='add_node', type='interface', label='...', params={interface_id:'<uuid>', variable_mapping: {...}, action_mapping: {...}}, connect_after='...')

                With __continue in action_mapping → BLOCKING (workflow waits for user click). Without → AUTO-ADVANCE (workflow continues immediately). Omit action_mapping for display-only.
                Call workflow(action='help', topics=['interface']) for full documentation on action_mapping and variable_mapping.
                """;
            case "connect" -> """
                workflow(action='connect', from='Source Label', to='Target Label')
                Connects two nodes. Use node labels or full IDs (mcp:my_step).
                For branching nodes, append port: from='Decision:if' or from='Loop:exit'.
                Ports: decision (if/else/elseif_N), switch (case_N/default), loop (body/exit), fork (branch_N), option (choice_N), approval (approved/rejected/timeout), classify (category_N), guardrail (pass/fail).
                RULE: one output port = one target. Each named port connects to AT MOST ONE node - a second connect from the same port (e.g. from='Decision:if' to two different targets) is rejected. To run several nodes from one port in parallel, add a fork on that port (workflow(action='add_node', type='fork', ...)) and connect each fork branch to its own target. A port-less node (a trigger or a plain step) MAY connect to several targets - that is an implicit parallel fork.
                RULE: terminal nodes (type='exit', 'end', 'stop_on_error') end a branch - they MUST NOT have outgoing edges. Never connect them as `from`, including to a merge. To rejoin a branch into a merge, route the predecessor of the terminal to the merge directly, or drop the terminal entirely.
                """;
            case "load" -> """
                workflow(action='load', id='uuid') - Load by ID (recommended)
                workflow(action='load', name='Workflow Name') - Load by name
                Use workflow(action='list') to see available workflows.
                """;
            case "finish", "create" -> """
                workflow(action='finish') - or workflow(action='finish', name='New Name', description='New desc')
                Finalizes, saves, and CLOSES the build session. After this returns success, the work is done:
                do not call any further workflow(action=...) for this draft. Use workflow(action='load') to edit later.
                Validates before saving - if errors exist, returns them instead of finishing.
                Note: 'create' is a back-compat alias and routes to the same handler.
                """;
            case "save" -> """
                workflow(action='save') - Save changes without closing session
                workflow(action='save', name='New Name') - Save and rename
                """;
            case "validate" -> "workflow(action='validate') - Check for disconnected nodes and validation errors. Returns list of issues.";
            case "describe" -> "workflow(action='describe') - Show current session state: all nodes, edges, and issues.";
            case "modify" -> "workflow(action='modify', node='Node Label', params={...}) - Modify an existing node's parameters.";
            case "remove" -> "workflow(action='remove', node='Node Label') - Remove a node and its edges from the session.";
            case "add_split", "split" -> """
                workflow(action='add_node', type='split', label='Process Items', params={items: '{{mcp:api.output.items}}'}, connect_after='Fetch')
                Iterates over a list in PARALLEL. Each item gets its own execution context.
                Body nodes use {{core:process_items.output.current_item}} and {{core:process_items.output.current_index}}.
                PERSISTED outputs: items, item_count, split_id, spawn_reason, terminated.
                RUNTIME (body only): current_item, current_index.
                """;
            case "add_fork", "fork" -> """
                workflow(action='add_node', type='fork', label='Parallel', params={branches: ['Task A', 'Task B']}, connect_after='Start')
                Fork = ALL branches execute in parallel. Each branch is a separate stream.
                Ports: branch_0, branch_1, ... Connect each to its OWN target (one branch port = one target). To split a single branch further, add another fork on that branch.
                Use merge to wait for all branches to complete.
                """;
            case "add_merge", "merge" -> """
                workflow(action='add_node', type='merge', label='Wait All', connect_after='...')
                Merge = wait for ALL predecessors to complete (AND logic).
                Connect all branch endpoints to the merge node.
                SKIPPED predecessors count as resolved.
                """;
            case "add_switch", "switch" -> """
                workflow(action='add_node', type='switch', label='Route By Type', params={
                  expression: '{{mcp:fetch.output.type}}',
                  cases: [
                    {value: 'email', label: 'Email'},
                    {value: 'sms', label: 'SMS'},
                    {value: 'default', label: 'Other'}
                  ]
                }, connect_after='Fetch')
                Switch = match an expression value against cases. ONE matching branch executes.
                Ports: case_0, case_1, ..., default. Use in connect: from='Route By Type:case_0'.
                """;
            case "add_transform", "transform" -> """
                workflow(action='add_node', type='transform', label='Prepare Data', params={
                  mappings: {
                    full_name: '{{mcp:fetch.output.first}} {{mcp:fetch.output.last}}',
                    total: '{{double(mcp:calc.output.price) * int(mcp:calc.output.qty)}}'
                  }
                }, connect_after='Fetch')
                Transform = compute new fields from expressions. Output: {{core:prepare_data.output.<field_name>}}.
                """;
            case "add_wait", "wait" -> """
                workflow(action='add_node', type='wait', label='Pause 30s', params={duration: 30}, connect_after='Step')
                Wait = pause execution for N seconds. For duration > 3s, yields as AWAITING_SIGNAL (non-blocking to server).
                """;
            case "add_http", "http_request" -> """
                workflow(action='add_node', type='http_request', label='Call API', params={
                  url: 'https://api.example.com/data',
                  method: 'POST',
                  headers: {'Content-Type': 'application/json'},
                  body: '{"query": "{{trigger:form.output.query}}"}'
                }, connect_after='Form')
                HTTP Request = raw HTTP call. Output: {{core:call_api.output.body}}, {{core:call_api.output.status_code}}.
                """;
            case "add_approval", "approval", "user_approval" -> """
                workflow(action='add_node', type='approval', label='Manager Review', params={
                  contextTemplate: 'Approve refund of {{trigger:form.output.amount}} for {{trigger:form.output.email}}?',
                  timeoutMs: 86400000
                }, connect_after='Submit')
                Approval = pause workflow until a human approves/rejects.
                contextTemplate (REQUIRED): the message shown to the approver so they see WHAT they are
                  approving. Literal text plus {{...}} expressions; resolved at pause time. Omitting it is
                  only a warning - the run still proceeds, but the approver sees no context.
                Optional: timeoutMs (ms before the timeout port fires, default 86400000 = 24h),
                  requiredApprovals (default 1), approverRoles (array).
                Optional delegation (external channel): params.delegation={channel: 'telegram',
                  credentialId: <numeric id of the user's Telegram bot credential>, chatId: '<chat id,
                  {{...}} allowed>', messageTemplate: '<optional body, {{...}} allowed; defaults to the
                  resolved contextTemplate>', allowedUserIds: ['<telegram user id>', ...]}. The channel
                  message shows Approve/Reject buttons; a tap resolves this approval exactly like an
                  in-app decision (you can still resolve it with workflow(action='resolve_approval')).
                  Empty allowedUserIds = anyone in the chat can decide. Only channel 'telegram' exists.
                  Output delegated_channel is set when delegation is configured.
                Ports: approved, rejected, timeout. Connect: from='Manager Review:approved', to='Next Step'.
                """;
            case "add_guardrail", "guardrail" -> """
                workflow(action='add_node', type='guardrail', label='Check PII', params={input: '{{trigger:form.output.message}}', rules: {pii: 'Block emails and phones', toxicity: 'Block offensive content'}}, connect_after='Contact Form')
                Guardrail = 1:1 AI content validation. Passes or fails the input based on rules.
                Required: input (text to validate, use {{...}} refs), rules (object: {ruleId: 'description'}).
                Optional: action ('flag'|'block'|'redact', default 'flag'), provider, model, temperature.
                Ports: pass, fail. Connect: from='Check PII:pass', to='Next Step'.
                """;
            case "add_classify", "classify" -> """
                workflow(action='add_node', type='classify', label='Route Ticket', params={prompt: 'Classify this support ticket by issue type: {{trigger:ticket.output.description}}', categories: [{label: 'billing', description: 'Payment and invoice issues'}, {label: 'technical', description: 'Bugs and errors'}, {label: 'general', description: 'Other inquiries'}]}, connect_after='Ticket Form')
                Classify = 1:N exclusive AI routing. Categorizes input into one of N categories.
                Required: prompt (instruction INCLUDING data via {{...}}), categories (array of {label, description}, min 2).
                Optional: provider, model, temperature.
                Ports: category_0, category_1, ..., category_N. Connect: from='Route Ticket:category_0', to='Billing Handler'.
                """;
            case "add_aggregate", "aggregate" -> """
                workflow(action='add_node', type='aggregate', label='Totals', params={fields: [{label: 'total', expression: '{{#sum(core:process.output.amount)}}'}, {label: 'count', expression: '{{#size(core:process.output.items)}}'}]}, connect_after='Process')
                Aggregate = compute summary values from parallel contexts (after split/find). Use after split body nodes to combine results.
                Required: fields (array of {label, expression}). Functions: #collectList(), #sum(), #avg(), #max(), #min(), #size(), #join().
                Output: {{core:totals.output.<field_label>}}.
                """;
            case "add_exit", "exit" -> "workflow(action='add_node', type='exit', label='Exit', connect_after='...') - End execution along this branch. Other parallel branches (fork, split) continue normally. TERMINAL: NO outgoing edges allowed - never connect this as `from` (including to a merge). To rejoin a branch into a merge, route the predecessor of the Exit to the merge instead, or drop the Exit.";
            case "add_response", "response" -> "workflow(action='add_node', type='response', label='Reply', params={message: '{{agent:analyzer.output.response}}'}, connect_after='Analyzer') - Send a chat message back to the user (chat trigger only).";
            case "add_data_input", "data_input" -> "workflow(action='add_node', type='data_input', label='Inject Config', params={data: {key: 'value'}}, connect_after='...') - Inject static or dynamic data mid-workflow.";
            case "add_download", "download_file" -> "workflow(action='add_node', type='download_file', label='Get Image', params={url: '{{mcp:fetch.output.image_url}}'}, connect_after='Fetch') - Download a file from URL. Output: canonical FileRef under {{core:get_image.output.file}} (drop into <img src=\"{{photo}}\"/> via variable_mapping for marketplace + share preview).";
            case "add_code", "code" -> "workflow(action='add_node', type='code', label='Process', params={language: 'javascript', code: 'return {result: input.value * 2}'}, connect_after='...') - Execute custom code (JavaScript/Python).";
            case "add_option", "option" -> """
                workflow(action='add_node', type='option', label='Pick Action', params={
                  choices: [{label: 'Approve', value: 'approve'}, {label: 'Reject', value: 'reject'}],
                  prompt: 'What would you like to do?'
                }, connect_after='...')
                Option = present N choices to AI/user, ONE is selected. Like switch but value comes from user/AI selection.
                Ports: choice_0, choice_1, ... Connect: from='Pick Action:choice_0', to='Approve Handler'.
                """;
            case "add_filter", "filter" -> "workflow(action='add_node', type='filter', label='Filter Active', params={condition: '{{item.status == \"active\"}}'}, connect_after='...') - Filter items in a split context. Removes items that don't match the condition.";
            case "add_sort", "sort" -> "workflow(action='add_node', type='sort', label='Sort By Name', params={field: 'name', order: 'asc'}, connect_after='...') - Sort items by a field (asc/desc).";
            case "add_sub_workflow", "sub_workflow" -> "workflow(action='add_node', type='sub_workflow', label='Run Child', params={workflow_id: '<uuid>'}, connect_after='...') - Execute another workflow as a sub-step. Output: the child workflow's terminal node outputs.";
            case "add_respond_to_webhook", "respond_to_webhook" -> "workflow(action='add_node', type='respond_to_webhook', label='Send Response', params={status_code: 200, body: '{{agent:process.output.response}}'}, connect_after='...') - Send HTTP response back to the webhook caller.";
            case "add_send_email", "send_email" -> "workflow(action='add_node', type='send_email', label='Notify', params={toEmail: '{{trigger:form.output.email}}', subject: 'Update', body: '...'}, connect_after='...') - Send email via SMTP. SMTP credentials (host, port, password) are configured in Settings > Credentials - specify toEmail, subject, body, ccEmail, bccEmail, fromName, isHtml. To REPLY in-thread, set inReplyTo to the original message's messageId (from email_inbox output) and toEmail to its from address.";
            case "add_email_inbox", "email_inbox" -> "workflow(action='add_node', type='email_inbox', label='Read Inbox', params={folder: 'INBOX', unreadOnly: true, limit: 20}, connect_after='...') - Read a mailbox via IMAP (IMAP credentials in Settings > Credentials). action='none' (default) READS → output.messages[] (each: uid, from, to, cc, subject, body, bodyHtml, hasAttachments, attachments[{filename,contentType,size,file?}]); filter with unreadOnly/flaggedOnly/sinceDays/beforeDays/fromContains/subjectContains/bodyContains, set downloadAttachments=true to save attachments to file storage. action='list_folders' → output.folders[]. action=mark_read/mark_unread/flag/unflag/move/delete acts on one message via params.messageUid (move needs targetFolder). IMAP reads/acts only - to SEND or REPLY use send_email.";
            case "add_limit", "limit" -> "workflow(action='add_node', type='limit', label='Take Top 5', params={count: 5, from: 'start'}, connect_after='...') - Pass through only the first or last N items. Params: count (required), from ('start' or 'end', default 'start').";
            case "add_remove_duplicates", "remove_duplicates", "dedup" -> "workflow(action='add_node', type='remove_duplicates', label='Unique Emails', params={fields: ['email']}, connect_after='...') - Remove duplicate items based on one or more fields. Params: fields (required, array of field names to compare).";
            case "add_summarize", "summarize" -> "workflow(action='add_node', type='summarize', label='Sales Summary', params={group_by: ['region'], aggregations: [{field: 'amount', function: 'sum'}, {field: 'amount', function: 'avg'}]}, connect_after='...') - Summarize/pivot data. Functions: sum, avg, count, min, max. Params: group_by (array), aggregations (array of {field, function}).";
            case "add_date_time", "date_time", "datetime" -> "workflow(action='add_node', type='date_time', label='Parse Date', params={operation: 'format', input: '{{mcp:fetch.output.created_at}}', pattern: 'dd/MM/yyyy'}, connect_after='...') - Date/time operations: parse, format, convert, manipulate. Operations: format, parse, add, subtract, diff, now, today.";
            case "add_crypto_jwt", "crypto_jwt", "crypto", "jwt" -> "workflow(action='add_node', type='crypto_jwt', label='Hash Password', params={operation: 'hash', algorithm: 'SHA-256', input: '{{trigger:form.output.password}}'}, connect_after='...') - Crypto/JWT operations: hash, encrypt, decrypt, JWT sign/verify, base64 encode/decode.";
            case "add_xml", "xml" -> "workflow(action='add_node', type='xml', label='Parse XML', params={operation: 'xml_to_json', input: '{{mcp:fetch.output.body}}'}, connect_after='...') - XML operations: xml_to_json, json_to_xml. Converts between XML and JSON formats.";
            case "add_compression", "compression", "compress" -> "workflow(action='add_node', type='compression', label='Zip Data', params={operation: 'compress', format: 'gzip', value: '{{mcp:fetch.output.body}}'}, connect_after='...') - Compress/decompress data. Formats: gzip, zip, deflate, base64. Operations: compress, decompress. Compress uploads to S3 and exposes a canonical FileRef under {{core:zip_data.output.file}}.";
            case "add_rss", "rss" -> "workflow(action='add_node', type='rss', label='Fetch News', params={url: 'https://example.com/feed.xml'}, connect_after='...') - Fetch and parse RSS/Atom feeds. Output: {{core:fetch_news.output.items}} (array of feed entries with title, link, description, pubDate).";
            case "add_convert_to_file", "convert_to_file" -> "workflow(action='add_node', type='convert_to_file', label='Export CSV', params={format: 'csv', value: 'query_results', filename: 'report'}, connect_after='...') - Export JSON data to a file (uploads to S3). Formats: csv, xlsx, json, txt. Output: canonical FileRef under {{core:export_csv.output.file}} (chainable to extract_from_file).";
            case "add_extract_from_file", "extract_from_file" -> "workflow(action='add_node', type='extract_from_file', label='Import CSV', params={format: 'csv', value: '{{core:export.output.file}}', hasHeaders: true}, connect_after='...') - Two modes: structured (default) imports CSV/XLSX/JSON into rows; text mode extracts raw text from PDF/HTML/DOCX/TXT with optional chunking for RAG. Text mode: params={format: 'pdf', mode: 'text', value: '...', chunking: true, chunkSize: 500, overlap: 50, chunkingStrategy: 'recursive', chunkUnit: 'token'}. Strategies: fixed_size, recursive, separator. chunkUnit sets how chunkSize/overlap are measured: 'char' (default, characters) or 'token' (cl100k tokens, so chunks fit an embedding model context window); read chunk_unit back on the output. Output: {{core:import.output.items}} (array of {content, chunk_index, source, total_chunks}).";
            case "add_compare_datasets", "compare_datasets", "compare" -> "workflow(action='add_node', type='compare_datasets', label='Diff Lists', params={dataset_a: '{{mcp:old.output.items}}', dataset_b: '{{mcp:new.output.items}}', key_fields: ['id']}, connect_after='...') - Compare two datasets by key fields. Output: {{core:diff_lists.output.matched}}, {{core:diff_lists.output.only_a}}, {{core:diff_lists.output.only_b}}.";
            case "add_set", "set" -> """
                workflow(action='add_node', type='set', label='Build Profile', params={
                  assignments: [
                    {name: 'full_name', value: '{{trigger:form.output.first}} {{trigger:form.output.last}}', type: 'string'},
                    {name: 'age', value: '{{trigger:form.output.age}}', type: 'number'},
                    {name: 'is_adult', value: '{{trigger:form.output.age >= 18}}', type: 'boolean'}
                  ],
                  keepOnlySet: false
                }, connect_after='Form')
                Set = no-code field assignment/transformation. Build or reshape records without writing code.
                Required: assignments (array of {name, value, type}). Types: 'string' | 'number' | 'boolean' | 'json' | 'auto' (default - leaves the resolved value as-is). For complex objects/arrays, use 'json'.
                Optional: keepOnlySet (default false - if true, output contains ONLY the new fields; otherwise merges with input).
                Output: {{core:build_profile.output.<assignment_name>}} for each assignment.
                Reference downstream as {{core:<label>.output.<assignment_name>}}. The output map contains every assignment plus (when keepOnlySet=false) the merged upstream input.
                // In set_plan cores[] entry:
                { "id": "core:build_user", "label": "build_user", "type": "set",
                  "set": { "assignments": [{"name":"full_name","value":"{{trigger.first}}","type":"string"}], "keepOnlySet": false } }
                """;
            case "add_html_extract", "html_extract" -> """
                workflow(action='add_node', type='html_extract', label='Extract Product', params={
                  sourceHtml: '{{mcp:fetch_page.output.body}}',
                  extractionMode: 'multiple',
                  rootSelector: 'article.product',
                  fields: [
                    {name: 'title', selector: 'h1.title', attribute: 'text', required: true},
                    {name: 'price', selector: '.price', attribute: 'text', transform: 'number'},
                    {name: 'image', selector: 'img.hero', attribute: 'src'}
                  ]
                }, connect_after='Fetch Page')
                HTML Extract = scrape structured data from HTML using CSS selectors (jsoup-backed). No custom code needed.
                Required: sourceHtml (HTML string, usually a {{...}} ref), fields (array of {name, selector, attribute, transform?, required?}).
                Optional: extractionMode ('single' | 'multiple', default 'single'), rootSelector (when 'multiple', the repeating container selector).
                attribute: 'text' | 'html' | 'href' | 'src' | any HTML attribute. transform: 'number' | 'trim' | 'lowercase' | 'uppercase'.
                Reference downstream as {{core:<label>.output.items}} (always an array - single mode returns a 1-element array). Access individual fields with {{core:<label>.output.items[0].<field_name>}}, or use a core:split node to iterate.
                // Single-mode access: {{core:<label>.output.items[0].<field_name>}} (still wrapped in items[0]!)
                // In set_plan cores[] entry:
                { "id": "core:extract", "label": "extract", "type": "html_extract",
                  "htmlExtract": { "sourceHtml": "{{mcp:fetch.output.body}}", "extractionMode": "single",
                                   "fields": [{"name":"title","selector":"h1","attribute":"text","required":true}] } }
                """;
            case "add_task", "task_crud" -> """
                workflow(action='add_node', type='task', label='Track Work', params={
                  operation: 'create_task',
                  title: 'Review document',
                  instructions: 'Please review the attached document for accuracy.',
                  priority: 'normal'
                }, connect_after='...')
                Task CRUD - manage task board records from a workflow. Operations:
                - create_task: params={operation:'create_task', title:'...', instructions:'...', priority:'low|normal|high|urgent', agentId:'...', reviewerAgentId:'...', taskContext:{key:'value or {{template}}'}}
                - get_task: params={operation:'get_task', taskId:'{{...}}'}
                - update_task: params={operation:'update_task', taskId:'{{...}}', title:'...', status:'...', priority:'...'}
                - delete_task: params={operation:'delete_task', taskId:'{{...}}'}
                - list_tasks: params={operation:'list_tasks', status:'...', priority:'...', agentId:'...', search:'...', limit:50}
                Output: {{core:<label>.output.task}} (single ops), {{core:<label>.output.tasks}} (list), {{core:<label>.output.success}}

                ⚠ EXECUTION MODEL:
                • create_task with agentId AUTO-TRIGGERS the agent ASYNCHRONOUSLY. The DAG does NOT wait - returns immediately with task.status=pending.
                • The agent result is in the task record, NOT in the DAG. You CANNOT use it in downstream nodes via templates.
                • An agent-worked task always lands in status=in_review before completed (approved by the reviewerAgentId agent if set, otherwise by the task creator), so a downstream status check sees in_review, not completed, until approval.
                • NEVER chain task_create(agentId=X) → agent_node(X) - this runs agent X TWICE (async from task + sync from agent node).

                THREE PATTERNS (pick one):
                1. Agent result needed in DAG (most common): use agent nodes directly - no task node.
                   agent_node → next_node (output available as {{agent:<label>.output.response}})
                2. Fire-and-forget (task board only): task_create with agentId - no agent node.
                   task_create(agentId=X) → ... (agent runs in background, DAG continues)
                3. Tracking + DAG result: task_create WITHOUT agentId → agent_node → update_task(completed).
                   task_create(no agentId) → agent_node → update_task (best of both worlds)

                // In set_plan cores[] entry (pattern 3 - tracking + DAG result):
                { "id": "core:track_work", "label": "track_work", "type": "task",
                  "task": { "operation": "create_task", "title": "Review doc", "priority": "normal" } }
                // then agent node, then:
                { "id": "core:done_work", "label": "done_work", "type": "task",
                  "task": { "operation": "update_task", "taskId": "{{core:track_work.output.task.id}}", "status": "completed" } }
                """;
            case "add_stop_on_error", "stop_on_error", "fail", "error_stop" -> """
                workflow(action='add_node', type='stop_on_error', label='Halt on Error', params={
                  errorMessage: 'Critical validation failed: {{core:check.output.reason}}',
                  errorCode: 'ERR_VALIDATION'
                }, connect_after='Check')
                Stop on Error - immediately stops the ENTIRE workflow (all parallel branches).
                Unlike Exit (which ends only one branch), this terminates everything and marks the run as FAILED.
                TERMINAL: NO outgoing edges allowed - never connect this as `from`. The workflow stops here.
                Params: errorMessage (required, supports templates), errorCode (optional).
                Output: {{core:<label>.output.error_message}}, {{core:<label>.output.error_code}}, {{core:<label>.output.stopped_at}}, {{core:<label>.output.status}}
                // In set_plan cores[] entry:
                { "id": "core:halt_on_error", "label": "halt_on_error", "type": "stop_on_error",
                  "stopOnError": { "errorMessage": "Failed: {{mcp:step.output.reason}}", "errorCode": "ERR_001" } }
                """;
            case "add_ssh", "ssh", "remote_command" -> """
                workflow(action='add_node', type='ssh', label='Deploy', params={
                  host: '{{core:config.output.server}}', port: 22, username: 'deploy',
                  authMethod: 'password', command: 'cd /app && git pull && systemctl restart app'
                }, connect_after='...')
                SSH - execute commands on remote servers. Auth: password or privateKey. credentialId for stored credentials.
                Output: {{core:<label>.output.stdout}}, {{core:<label>.output.exit_code}}, {{core:<label>.output.stderr}}
                """;
            case "add_sftp", "sftp", "file_transfer" -> """
                workflow(action='add_node', type='sftp', label='Upload Report', params={
                  host: 'files.example.com', username: 'uploader', authMethod: 'password',
                  operation: 'upload', remotePath: '/reports/daily.csv', localContent: '{{core:generate.output.csv}}'
                }, connect_after='...')
                SFTP - file operations on remote servers. Operations: list, download, upload, delete, rename, mkdir.
                Outputs: {{core:<label>.output.success}} (all ops); {{core:<label>.output.files}} (list); {{core:<label>.output.file}} (download - canonical FileRef, usable directly in <img src> / <a href> via variable_mapping); {{core:<label>.output.uploaded_size}} (upload); {{core:<label>.output.new_path}} (rename).
                Download op only emits the canonical `file` FileRef - there is NO `file_url`/`file_name`/`file_size`/`content_type` flat shape. Drill `.file.name` / `.file.mimeType` / `.file.size` for filename and metadata.
                """;
            case "add_database", "database", "db", "sql" -> """
                workflow(action='add_node', type='database', label='Query Users', params={
                  dbType: 'postgresql', host: 'db.example.com', port: 5432, database: 'mydb',
                  username: 'reader', query: 'SELECT * FROM users WHERE active = true LIMIT 100'
                }, connect_after='...')
                Database - execute SQL queries. Supports: postgresql, mysql, mssql. credentialId for stored credentials.
                Output: {{core:<label>.output.rows}}, {{core:<label>.output.row_count}}, {{core:<label>.output.columns}}
                """;
            case "find_rows", "add_find_rows" -> """
                TABLE QUERY - find_rows is a DIRECT action (not add_node):
                workflow(action='find_rows', label='Find Active Users', table_id=123, where={column: 'status', operator: '=', value: 'active'}, limit: 50, connect_after='Start')

                REQUIRED: label, table_id (datasource ID - list with workflow(action='help', topics=['crud']))
                OPTIONAL: where ({column, operator, value}), limit (default 100), offset, connect_after

                Output: {{table:find_active_users.output.items}} (array), {{table:find_active_users.output.item_count}}
                To iterate per-row: connect a Split node after with list={{table:label.output.items}}

                ⚠️ table_id goes FLAT in params, NOT inside crud/where.
                """;
            case "insert_row", "add_insert_row" -> """
                TABLE INSERT - insert_row is a DIRECT action (not add_node):
                workflow(action='insert_row', label='Save User', table_id=123, columns={name: 'John', email: '{{trigger:form.output.email}}'}, connect_after='...')

                REQUIRED: label, table_id (datasource ID - list with workflow(action='help', topics=['crud'])), columns ({column_name: value, ...})
                OPTIONAL: connect_after
                Output: {{table:save_user.output.row_id}}, {{table:save_user.output.inserted_values}}
                """;
            case "read_rows", "get_rows", "add_read_rows" -> """
                TABLE READ - read_rows is a DIRECT action (not add_node):
                workflow(action='read_rows', label='Fetch Users', table_id=123, where={column: 'id', operator: '=', value: '123'}, limit: 50, connect_after='...')

                REQUIRED: label, table_id (datasource ID - list with workflow(action='help', topics=['crud']))
                OPTIONAL: where ({column, operator, value}), limit, offset, connect_after
                Output: {{table:fetch_users.output.rows}} (array), {{table:fetch_users.output.count}}
                """;
            case "update_row", "add_update_row" -> """
                TABLE UPDATE - update_row is a DIRECT action (not add_node):
                workflow(action='update_row', label='Update Status', table_id=123, where={column: 'id', operator: '=', value: '{{trigger:x.id}}'}, set={status: 'done'}, connect_after='...')

                REQUIRED: label, table_id (datasource ID - list with workflow(action='help', topics=['crud'])), where, set
                OPTIONAL: connect_after
                Output: {{table:update_status.output.updated_count}}
                """;
            case "delete_row", "add_delete_row" -> """
                TABLE DELETE - delete_row is a DIRECT action (not add_node):
                workflow(action='delete_row', label='Remove Old', table_id=123, where={column: 'created_at', operator: '<', value: '2024-01-01'}, connect_after='...')

                REQUIRED: label, table_id (datasource ID - list with workflow(action='help', topics=['crud'])), where
                OPTIONAL: connect_after
                Output: {{table:remove_old.output.deleted_count}}
                """;
            case "undo" -> "workflow(action='undo') - Undo the last add_node or connect action. Only works within the current session.";
            case "list" -> "workflow(action='list') - List all saved workflows. Returns id, name, status, trigger count.";
            case "list_nodes" -> "workflow(action='list_nodes') - Alias for 'describe'. Shows all nodes, edges, and issues in the current session.";
            case "execute" -> "workflow(action='execute', id='<uuid>', data_inputs={...}, version=<int|'pinned'>?) - Fire a workflow trigger. Omit version for current canvas run; version=N replays a historical version; version='pinned' fires the pinned production version. Returns {status, outputs, errors, run_id, plan_version, pinned_version}. Use workflow(action='help', topics=['execute']) for full docs.";
            default -> "Use workflow(action='describe') to see current state, or workflow(action='help', topics=['...']) for detailed help.";
        };
    }

    // ==================== ENRICHED RESPONSE BUILDER ====================

    /**
     * Enrich a tool response with contextual information.
     * Called after each action to provide guidance.
     */
    public static void enrichResponse(Map<String, Object> response,
                                       WorkflowBuilderSession session,
                                       String action) {
        // Add session state
        response.put("session_state", getSessionState(session));

        // Add suggested next actions
        List<String> suggested = getSuggestedActions(session);
        response.put("suggested_actions", suggested);

        // Add phase-specific tip
        Phase phase = detectPhase(session);
        String tip = getPhaseTip(phase, action);
        if (tip != null) {
            response.put("tip", tip);
        }
    }

    private static String getPhaseTip(Phase phase, String lastAction) {
        return switch (phase) {
            case NO_SESSION -> "Start with workflow(action='init', name='Descriptive Name', description='What this workflow does') or workflow(action='load', id='...')";
            case STARTING -> "Add a trigger first. Types: form, chat, webhook, schedule, table, manual. Call workflow(action='help', topics=['<type>']) for params.";
            case BUILDING -> "Before adding: workflow(action='help', topics=['<node_type>']) for required params. Then catalog(action='search') for tools, workflow(action='add_node', ...). Table CRUD: workflow(action='find_rows|insert_row|read_rows|update_row|delete_row', table_id=X, label='...', ...)";
            case WIRING -> "Use workflow(action='validate') to see disconnected nodes, then workflow(action='connect')";
            case FIXING -> "Use workflow(action='validate') to see issues. Fix with: workflow(action='connect') for disconnected nodes, workflow(action='modify') to change params, workflow(action='remove') to delete nodes.";
            case READY -> "Workflow is valid! Use workflow(action='finish') to finalize and save (this closes the build session)";
        };
    }
}
