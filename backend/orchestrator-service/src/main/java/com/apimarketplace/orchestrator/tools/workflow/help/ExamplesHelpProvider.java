package com.apimarketplace.orchestrator.tools.workflow.help;

import java.util.*;

/**
 * Provides complete workflow examples and plan import/export help.
 */
public final class ExamplesHelpProvider {

    private ExamplesHelpProvider() {}

    // ==================== PLAN HELP ====================

    public static Map<String, Object> getPlanHelp() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("title", "Plan Import/Export (get_plan & set_plan)");
        result.put("description", """
            Export and import complete workflow structures.
            get_plan returns edges using RAW LABELS (e.g., 'Check Priority:if').
            set_plan accepts BOTH raw labels and normalized prefixed keys (e.g., 'core:check_priority:if').
            Examples below use normalized keys for clarity.""");

        result.put("get_plan", Map.of(
            "purpose", "Export current workflow as JSON with human-readable labels",
            "usage", "workflow(action='get_plan')",
            "returns", "Complete plan with triggers, mcps, agents, cores, tables, interfaces, notes, edges",
            "use_case", "Review structure, copy for modification, debug issues"
        ));

        result.put("set_plan", Map.of(
            "purpose", "Import a complete workflow structure (replaces current session)",
            "usage", "workflow(action='set_plan', plan={...})",
            "validation", "Validates labels are unique, edges reference existing nodes",
            "use_case", "Bulk creation, restore from backup, duplicate workflow"
        ));

        Map<String, String> planStructure = new LinkedHashMap<>();
        planStructure.put("triggers", "[{label: 'My Trigger', type: 'webhook|schedule|table|manual|chat|form|workflow|error', ...params}]  // table triggers include table_id at root: {label, type: 'table', table_id: 123}");
        planStructure.put("mcps", "[{label: 'My Step', id: 'category/tool', params: {...}}]");
        planStructure.put("agents", "[{label: 'My Agent', agentConfigId: '<uuid>', prompt: '...'}]  // NOTE: plan uses agentConfigId; add_node uses agent_id in params");
        planStructure.put("cores", "[{label: 'My Decision', type: 'decision|switch|loop|split|fork|merge|transform|wait|approval|...', ...params}]  // NOTE: plan uses 'expression' in decisionConditions; add_node uses 'condition' in params");
        planStructure.put("tables", "[{label: 'My CRUD', type: 'insert-row|find|read-row|update-row|delete-row', table_id: 123, crud: {where: {...}, set: {...}}}]  // Edges use table: prefix");
        planStructure.put("interfaces", "[{label: 'My Interface', interfaceId: '<uuid>'}]  // Edges use interface: prefix");
        planStructure.put("notes", "[{label: 'My Note', content: '...'}]  // Visual-only, no execution, no edges");
        planStructure.put("edges", "[{from: 'trigger:my_trigger', to: 'mcp:my_step'}, {from: 'core:check:if', to: 'mcp:success'}]");
        planStructure.put("edge_format", "get_plan returns edges using RAW LABELS with ports (e.g., {from: 'Check Priority:if', to: 'Express Ship'}). " +
            "set_plan accepts BOTH label-based edges AND normalized prefixed keys (e.g., {from: 'core:check_priority:if', to: 'mcp:express_ship'}). " +
            "Normalization: lowercase, spaces→underscores, accents→ascii. connect_after in add_node also uses RAW labels.");
        result.put("plan_structure", planStructure);

        Map<String, String> edgePorts = new LinkedHashMap<>();
        edgePorts.put("decision", "core:label:if, core:label:else, core:label:elseif_0, core:label:elseif_1...");
        edgePorts.put("switch", "core:label:case_0, core:label:case_1..., core:label:default");
        edgePorts.put("loop", "core:label:body (inside loop), core:label:exit (after loop)");
        edgePorts.put("fork", "core:label:branch_0, core:label:branch_1...");
        edgePorts.put("approval", "core:label:approved, core:label:rejected, core:label:timeout");
        edgePorts.put("option", "core:label:choice_0, core:label:choice_1...");
        edgePorts.put("classify", "agent:label:category_0, agent:label:category_1... (NOTE: agent: prefix, not core:)");
        edgePorts.put("guardrail", "agent:label:pass, agent:label:fail (NOTE: agent: prefix, not core:)");
        edgePorts.put("format", "Replace 'label' with the normalized node label. Most port nodes use core: prefix. Classify and guardrail use agent: prefix.");
        edgePorts.put("declared_outputs_rule", "Ports must reference DECLARED outputs. connect without an explicit port auto-assigns the next free declared port. " +
            "When every declared port is already wired: fork and decision AUTO-EXTEND their declaration (a new branch_N / elseif_N is added for you); " +
            "option, classify and switch REFUSE the connect (their outputs carry meaning you must declare first: add the choice/category/case " +
            "via action='modify' on the node, then connect). validate flags edges on undeclared ports as PORT_INDEX_OUT_OF_RANGE. " +
            "One port = one target: a second edge from the same port is always rejected (insert a fork to parallelize).");
        result.put("edge_ports", edgePorts);

        result.put("example", Map.of(
            "step1", "workflow(action='get_plan')  // See current structure",
            "step2", "// Modify the returned plan JSON",
            "step3", "workflow(action='set_plan', plan={...modified...})"
        ));

        return result;
    }

    // ==================== COMPLETE EXAMPLES ====================

    public static Map<String, Object> getCompleteExamples() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("title", "Complete Workflow Examples");
        result.put("description", "Real-world workflow patterns with all components");

        result.put("examples", Map.ofEntries(
            Map.entry("1_sequential", buildSequentialExample()),
            Map.entry("2_withDecision", buildDecisionExample()),
            Map.entry("3_withLoop", buildLoopExample()),
            Map.entry("4_withSplit", buildSplitExample()),
            Map.entry("5_withAgent", buildAgentExample()),
            Map.entry("6_forkMerge", buildForkMergeExample()),
            Map.entry("7_tableAgentCrud", buildTableAgentCrudExample()),
            Map.entry("8_multiDag", buildMultiDagExample()),
            Map.entry("9_datasourceEventFilter", buildDatasourceFilterExample()),
            Map.entry("10_deterministicFirst", buildDeterministicFirstExample()),
            Map.entry("11_emailInbox", buildEmailInboxExample())
        ));

        return result;
    }

    // ==================== EXAMPLE BUILDERS ====================

    private static Map<String, Object> buildEmailInboxExample() {
        Map<String, Object> example = new LinkedHashMap<>();
        example.put("name", "Email Inbox - read a mailbox then act per message (IMAP)");
        example.put("useCase", "Every morning, read unread messages over IMAP, fan out one run per message, "
            + "and flag each one. IMAP reads/acts on the mailbox only - to SEND mail use send_email (SMTP), a "
            + "separate node with its own credential. Each message carries a stable uid used to act on the exact message.");
        example.put("plan", Map.of(
            "triggers", List.of(Map.of("label", "Every Morning", "type", "schedule", "cron", "0 8 * * *")),
            "cores", List.of(
                Map.of("type", "email_inbox", "label", "Read Inbox",
                    "emailInbox", Map.of("folder", "INBOX", "unreadOnly", true, "limit", 20)),
                Map.of("type", "split", "label", "Per Message",
                    "list", "{{core:read_inbox.output.messages}}"),
                Map.of("type", "email_inbox", "label", "Flag Message",
                    "emailInbox", Map.of("action", "flag", "messageUid", "{{item.uid}}"))
            ),
            "edges", List.of(
                Map.of("from", "trigger:every_morning", "to", "core:read_inbox"),
                Map.of("from", "core:read_inbox", "to", "core:per_message"),
                Map.of("from", "core:per_message", "to", "core:flag_message")
            )
        ));
        example.put("note", "Default action='none' READS (output.messages[] with uid each). Set action to "
            + "mark_read/mark_unread/flag/unflag/move/delete to act on one message via messageUid (move also needs targetFolder).");
        return example;
    }

    private static Map<String, Object> buildSequentialExample() {
        Map<String, Object> example = new LinkedHashMap<>();
        example.put("name", "Simple Sequential Workflow");
        example.put("useCase", "Process data step by step");
        example.put("plan", Map.of(
            "triggers", List.of(Map.of("label", "data_source", "type", "table", "table_id", 123)),
            "mcps", List.of(
                Map.of("label", "fetch", "id", "http_get", "params", Map.of("url", "https://api.example.com/data")),
                Map.of("label", "transform", "id", "json_transform"),
                Map.of("label", "save", "id", "database_insert")
            ),
            "edges", List.of(
                Map.of("from", "trigger:data_source", "to", "mcp:fetch"),
                Map.of("from", "mcp:fetch", "to", "mcp:transform"),
                Map.of("from", "mcp:transform", "to", "mcp:save")
            )
        ));
        return example;
    }

    private static Map<String, Object> buildDecisionExample() {
        Map<String, Object> example = new LinkedHashMap<>();
        example.put("name", "Order Processing with Routing");
        example.put("useCase", "Route orders based on priority");
        example.put("plan", Map.of(
            "triggers", List.of(Map.of("label", "orders_source", "type", "table", "table_id", 456)),
            "mcps", List.of(
                Map.of("label", "validate", "id", "validate_order"),
                Map.of("label", "express_ship", "id", "ship_express"),
                Map.of("label", "standard_ship", "id", "ship_standard"),
                Map.of("label", "notify", "id", "send_notification")
            ),
            "cores", List.of(Map.of(
                "type", "decision",
                "label", "Check Priority",
                "decisionConditions", List.of(
                    Map.of("id", "c1", "type", "if", "expression", "{{mcp:validate.output.priority == 'express'}}", "label", "Express"),
                    Map.of("id", "c2", "type", "else", "expression", "default", "label", "Standard")
                )
            )),
            "edges", List.of(
                Map.of("from", "trigger:orders_source", "to", "mcp:validate"),
                Map.of("from", "mcp:validate", "to", "core:check_priority"),
                Map.of("from", "core:check_priority:if", "to", "mcp:express_ship"),
                Map.of("from", "core:check_priority:else", "to", "mcp:standard_ship"),
                Map.of("from", "mcp:express_ship", "to", "mcp:notify"),
                Map.of("from", "mcp:standard_ship", "to", "mcp:notify")
            )
        ));
        return example;
    }

    private static Map<String, Object> buildLoopExample() {
        Map<String, Object> example = new LinkedHashMap<>();
        example.put("name", "Retry Loop");
        example.put("useCase", "Retry an API call up to 3 times until it succeeds");
        example.put("note", "Loop repeats body nodes while condition is true. Use 'split' (not loop) to process a LIST of items in parallel.");
        example.put("plan", Map.of(
            "triggers", List.of(Map.of("label", "start", "type", "manual")),
            "mcps", List.of(
                Map.of("label", "call_api", "id", "http_request"),
                Map.of("label", "done", "id", "send_notification")
            ),
            "cores", List.of(Map.of(
                "type", "loop",
                "label", "Retry",
                "loopCondition", "{{core:retry.output.iteration < 3}}",
                "maxIterations", 3
            )),
            "edges", List.of(
                Map.of("from", "trigger:start", "to", "core:retry"),
                Map.of("from", "core:retry:body", "to", "mcp:call_api"),
                Map.of("from", "core:retry:exit", "to", "mcp:done")
            )
        ));
        return example;
    }

    private static Map<String, Object> buildSplitExample() {
        Map<String, Object> example = new LinkedHashMap<>();
        example.put("name", "Split - Process List Items in Parallel");
        example.put("useCase", "Process each item from a list in parallel (fan-out)");
        example.put("note", "Split iterates a list. Body nodes access {{core:each_item.output.current_item}} and {{core:each_item.output.current_index}}. No ports (single edge). ALL nodes after the split run ONCE PER ITEM (inside split body). Use loop for conditional repetition.");
        example.put("plan", Map.of(
            "triggers", List.of(Map.of("label", "start", "type", "manual")),
            "mcps", List.of(
                Map.of("label", "fetch_items", "id", "http_get", "params", Map.of("url", "https://api.example.com/items")),
                Map.of("label", "process_item", "id", "transform_item"),
                Map.of("label", "done", "id", "send_notification")
            ),
            "cores", List.of(Map.of(
                "type", "split",
                "label", "Each Item",
                "list", "{{mcp:fetch_items.output.items}}"
            )),
            "edges", List.of(
                Map.of("from", "trigger:start", "to", "mcp:fetch_items"),
                Map.of("from", "mcp:fetch_items", "to", "core:each_item"),
                Map.of("from", "core:each_item", "to", "mcp:process_item"),
                Map.of("from", "mcp:process_item", "to", "mcp:done")
            )
        ));
        return example;
    }

    private static Map<String, Object> buildAgentExample() {
        Map<String, Object> example = new LinkedHashMap<>();
        example.put("name", "AI-Powered Data Analysis");
        example.put("useCase", "Use AI to analyze and report on data");
        example.put("prerequisite", "agent(action='create', name='Data Analyst', system_prompt='You are a data analyst. Provide concise insights.') → returns <agent-uuid>");
        example.put("plan", Map.of(
            "triggers", List.of(Map.of("label", "metrics_source", "type", "table", "table_id", 789)),
            "mcps", List.of(
                Map.of("label", "fetch_data", "id", "database_query", "params", Map.of("query", "SELECT * FROM metrics")),
                Map.of("label", "send_report", "id", "email_send")
            ),
            "agents", List.of(Map.of(
                "label", "analyst",
                "agentConfigId", "<agent-uuid>",
                "prompt", "Analyze this data: {{mcp:fetch_data.output.result}}"
            )),
            "edges", List.of(
                Map.of("from", "trigger:metrics_source", "to", "mcp:fetch_data"),
                Map.of("from", "mcp:fetch_data", "to", "agent:analyst"),
                Map.of("from", "agent:analyst", "to", "mcp:send_report")
            )
        ));
        return example;
    }

    private static Map<String, Object> buildForkMergeExample() {
        Map<String, Object> example = new LinkedHashMap<>();
        example.put("name", "Parallel Notifications");
        example.put("useCase", "Send notifications via multiple channels in parallel");
        example.put("plan", Map.of(
            "triggers", List.of(Map.of("label", "alerts_source", "type", "table", "table_id", 101)),
            "mcps", List.of(
                Map.of("label", "prepare", "id", "format_message"),
                Map.of("label", "email", "id", "send_email"),
                Map.of("label", "sms", "id", "send_sms"),
                Map.of("label", "slack", "id", "send_slack"),
                Map.of("label", "log", "id", "log_notifications")
            ),
            "edges", List.of(
                Map.of("from", "trigger:alerts_source", "to", "mcp:prepare"),
                // FORK - parallel
                Map.of("from", "mcp:prepare", "to", "mcp:email"),
                Map.of("from", "mcp:prepare", "to", "mcp:sms"),
                Map.of("from", "mcp:prepare", "to", "mcp:slack"),
                // MERGE - wait for all
                Map.of("from", "mcp:email", "to", "mcp:log"),
                Map.of("from", "mcp:sms", "to", "mcp:log"),
                Map.of("from", "mcp:slack", "to", "mcp:log")
            )
        ));
        return example;
    }

    private static Map<String, Object> buildTableAgentCrudExample() {
        Map<String, Object> example = new LinkedHashMap<>();
        example.put("name", "Table Trigger (event-driven) → Agent → CRUD Save");
        example.put("useCase", "Every newly inserted user row fires its own run - the run analyzes the row and writes results back.");
        example.put("key_rule", "table_id MUST be the same in trigger and CRUD step");
        example.put("plan", Map.of(
            "triggers", List.of(Map.of(
                "label", "On New User", "type", "table", "table_id", 123,
                "event_types", List.of("row_created"))),
            "agents", List.of(Map.of(
                "label", "Analyze User",
                "agentConfigId", "<agent-uuid>",
                "prompt", "Analyze: {{trigger:on_new_user.output.email}}, {{trigger:on_new_user.output.name}}"
            )),
            "tables", List.of(Map.of(
                "label", "Save Analysis",
                "type", "update-row",
                "table_id", 123,
                "crud", Map.of(
                    "where", Map.of("column", "id", "value", "{{trigger:on_new_user.output.row_id}}"),
                    "set", Map.of("analysis", "{{agent:analyze_user.output.response}}"))
            )),
            "edges", List.of(
                Map.of("from", "trigger:on_new_user", "to", "agent:analyze_user"),
                Map.of("from", "agent:analyze_user", "to", "table:save_analysis")
            )
        ));
        example.put("note", "Table trigger is event-driven: ONE run per row change (insert/update/delete). Multiple rows inserted at once → multiple parallel runs, one per row. No bulk-scan. " +
            "Create agent entity first with agent(action='create'). CRUD ops go in tables[] (not mcps[]) with table: prefix in edges.");
        return example;
    }

    private static Map<String, Object> buildDatasourceFilterExample() {
        Map<String, Object> example = new LinkedHashMap<>();
        example.put("name", "Table Trigger with event_types + filter + previous_row");
        example.put("useCase", "Fire only when the status column transitions TO 'paid' - compare row vs previous_row.");
        example.put("plan", Map.of(
            "triggers", List.of(Map.of(
                "label", "On Payment Confirmed",
                "type", "table",
                "table_id", 42,
                "event_types", List.of("row_updated"),
                "filter", Map.of("column", "status", "operator", "=", "value", "paid"))),
            "mcps", List.of(
                Map.of("label", "notify", "id", "send_email",
                    "params", Map.of(
                        "subject", "Payment received: {{trigger:on_payment_confirmed.output.row_id}}",
                        "body", "Was: {{trigger:on_payment_confirmed.output.previous_row.status}} → Now: {{trigger:on_payment_confirmed.output.row.status}}"))
            ),
            "edges", List.of(
                Map.of("from", "trigger:on_payment_confirmed", "to", "mcp:notify")
            )
        ));
        example.put("note", "Filter operators: =, !=, >, >=, <, <=, in, not_in, contains, starts_with, ends_with, is_null, is_not_null. " +
            "previous_row is the pre-change row - only populated for row_updated (null for row_created and row_deleted). " +
            "Trigger fires automatically on real row events. To TEST this workflow end-to-end, cause a real row change via " +
            "table(action='insert_rows'|'update_rows'|'delete_rows', ...). workflow(action='execute') on a datasource " +
            "trigger runs the legacy batch-scan loader (emits {data[], count, ...}) - it does NOT exercise the event-driven path.");
        return example;
    }

    private static Map<String, Object> buildDeterministicFirstExample() {
        Map<String, Object> example = new LinkedHashMap<>();
        example.put("name", "Deterministic-First Pipeline");
        example.put("useCase", "Fetch external data, extract and structure it deterministically, " +
                    "use an agent ONLY for the step that requires reasoning, then output the result.");
        example.put("design_principle", "For each step, ask: is the output predictable from the input? " +
                    "If yes → deterministic node. If no → agent. Pre-process data before the agent so it receives structured input.");
        example.put("plan", Map.of(
            "triggers", List.of(Map.of("label", "Start", "type", "schedule", "cron", "0 9 * * *")),
            "mcps", List.of(
                Map.of("label", "Notify", "id", "send_email", "params", Map.of(
                    "toEmail", "{{trigger:start.output.email}}",
                    "body", "{{agent:analyze.output.response}}"))),
            "cores", List.of(
                Map.of("type", "http_request", "label", "Fetch Data",
                    "url", "https://api.example.com/data", "method", "GET"),
                Map.of("type", "html_extract", "label", "Extract Fields", "htmlExtract", Map.of(
                    "sourceHtml", "{{core:fetch_data.output.body}}",
                    "extractionMode", "multiple", "rootSelector", ".item",
                    "fields", List.of(
                        Map.of("name", "title", "selector", "h2", "attribute", "text"),
                        Map.of("name", "detail", "selector", ".desc", "attribute", "text")))),
                Map.of("type", "filter", "label", "Keep Relevant",
                    "condition", "{{item.title != null}}"),
                Map.of("type", "limit", "label", "Top N", "count", 5)),
            "agents", List.of(Map.of(
                "label", "Analyze", "agentConfigId", "<agent-uuid>",
                "prompt", "Analyze these items:\n{{core:top_n.output.items}}")),
            "edges", List.of(
                Map.of("from", "trigger:start", "to", "core:fetch_data"),
                Map.of("from", "core:fetch_data", "to", "core:extract_fields"),
                Map.of("from", "core:extract_fields", "to", "core:keep_relevant"),
                Map.of("from", "core:keep_relevant", "to", "core:top_n"),
                Map.of("from", "core:top_n", "to", "agent:analyze"),
                Map.of("from", "agent:analyze", "to", "mcp:notify"))
        ));
        example.put("note", "4 deterministic steps, 1 agent step. The agent receives structured items, not raw data. " +
                    "Each step is inspectable independently. Apply this pattern to any domain.");
        return example;
    }

    private static Map<String, Object> buildMultiDagExample() {
        Map<String, Object> example = new LinkedHashMap<>();
        example.put("name", "Multi-DAG: Webhook + Scheduled Batch");
        example.put("useCase", "2 triggers → 2 independent DAGs for different business processes (real-time + batch)");
        example.put("plan", Map.ofEntries(
            Map.entry("triggers", List.of(
                Map.of("label", "Incoming Order", "type", "webhook"),
                Map.of("label", "Nightly Cleanup", "type", "schedule",
                    "cron", "0 2 * * *")
            )),
            Map.entry("mcps", List.of(
                Map.of("label", "Process Order", "id", "order_api",
                    "params", Map.of("payload", "{{trigger:incoming_order.output}}")),
                Map.of("label", "Send Confirmation", "id", "send_email"),
                Map.of("label", "Archive Old Orders", "id", "database_cleanup"),
                Map.of("label", "Generate Report", "id", "send_report")
            )),
            Map.entry("edges", List.of(
                Map.of("from", "trigger:incoming_order", "to", "mcp:process_order"),
                Map.of("from", "mcp:process_order", "to", "mcp:send_confirmation"),
                Map.of("from", "trigger:nightly_cleanup", "to", "mcp:archive_old_orders"),
                Map.of("from", "mcp:archive_old_orders", "to", "mcp:generate_report")
            ))
        ));
        example.put("note", "Each trigger fires its own DAG independently. No cross-DAG variable access. " +
            "For interactive apps with multiple buttons (search, delete, refresh), use multiple triggers in the SAME DAG, not separate DAGs.");
        return example;
    }
}
