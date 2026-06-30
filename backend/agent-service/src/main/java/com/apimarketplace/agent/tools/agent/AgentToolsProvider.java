package com.apimarketplace.agent.tools.agent;

import com.apimarketplace.agent.domain.ToolParameter;
import com.apimarketplace.agent.registry.AgentToolDefinition;
import com.apimarketplace.agent.registry.ToolCategory;
import com.apimarketplace.agent.tools.ToolsProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

import static com.apimarketplace.agent.registry.ToolSchemaGenerator.*;
import com.apimarketplace.agent.tools.ToolErrorCode;

/**
 * Provider for unified agent tool - agent-service native version.
 * Eliminates the orchestrator double-hop for CRUD and help operations.
 *
 * Delegates to specialized modules:
 * - AgentCrudModule: create, get, list, update, delete (direct service calls)
 * - AgentHelpModule: help
 * - AgentConversationModule: get_history, share
 * - AgentDelegationModule: task delegation - assignee path (assign, inbox, outbox,
 *   task_complete, task_reject, task_update, task_cancel, task_delete), reviewer
 *   path (review_inbox, task_approve, task_reject_review), backlog (backlog, claim),
 *   recurrences (recurrence_create, recurrence_list, recurrence_update, recurrence_delete)
 *
 * Note: 'execute' action is handled by orchestrator-service's AgentExecuteModule
 * during the migration transition. It will be migrated in a future phase.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentToolsProvider implements ToolsProvider {

    private final AgentCrudModule crudModule;
    private final AgentHelpModule helpModule;
    private final AgentConversationModule conversationModule;
    private final AgentDelegationModule delegationModule;
    private final AgentPublishModule publishModule;
    private final AgentTaskContextModule taskContextModule;

    private static final List<String> VALID_ACTIONS = List.of(
        "create", "get", "list", "update", "delete", "execute", "help", "help_models",
        // Memory & sharing
        "get_history", "search_messages", "share", "unshare", "refresh_share",
        // Marketplace publication lifecycle
        "publish", "unpublish",
        // Task delegation - assignee path (the agent doing the work)
        "assign", "inbox", "outbox", "task_complete", "task_reject", "task_update", "task_cancel", "task_delete",
        // Task delegation - reviewer path (the agent judging the submitted work)
        "review_inbox", "task_approve", "task_reject_review",
        "task_get_context", "task_get_execution",
        // Task delegation - backlog
        "backlog", "claim",
        // Task delegation - recurrences
        "recurrence_create", "recurrence_list", "recurrence_update", "recurrence_delete"
    );

    @Override
    public ToolCategory getCategory() {
        return ToolCategory.AGENT;
    }

    @Override
    public List<AgentToolDefinition> getTools() {
        return List.of(buildUnifiedAgentTool());
    }

    @Override
    public ToolExecutionResult execute(String toolName, Map<String, Object> parameters, ToolExecutionContext context) {
        if (!"agent".equals(toolName)) {
            return ToolExecutionResult.failure(ToolErrorCode.TOOL_NOT_FOUND, "Unknown tool: " + toolName);
        }

        String action = (String) parameters.get("action");
        if (action == null || action.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "action is required. Valid actions: " + String.join(", ", VALID_ACTIONS));
        }

        try {
            String tenantId = context.tenantId();
            if (tenantId == null && !"help".equals(action) && !"help_models".equals(action)) {
                return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "tenantId is required");
            }

            // Delegate to CRUD module
            if (crudModule.canHandle(action)) {
                return crudModule.execute(action, parameters, tenantId, context)
                    .orElse(ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "CRUD module failed for action: " + action));
            }

            // Delegate to help module
            if (helpModule.canHandle(action)) {
                return helpModule.execute(action, parameters, tenantId, context)
                    .orElse(ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Help module failed"));
            }

            // Delegate to conversation module (get_history, share)
            if (conversationModule.canHandle(action)) {
                return conversationModule.execute(action, parameters, tenantId, context)
                    .orElse(ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Conversation module failed for action: " + action));
            }

            // Delegate to delegation module (task assignment, backlog, recurrences)
            if (delegationModule.canHandle(action)) {
                return delegationModule.execute(action, parameters, tenantId, context)
                    .orElse(ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Delegation module failed for action: " + action));
            }

            // Delegate to publish module (marketplace publish/unpublish)
            if (publishModule.canHandle(action)) {
                return publishModule.execute(action, parameters, tenantId, context)
                    .orElse(ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Publish module failed for action: " + action));
            }

            // Delegate to task-context module (reviewer / creator visibility)
            if (taskContextModule.canHandle(action)) {
                return taskContextModule.execute(action, parameters, tenantId, context)
                    .orElse(ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Task-context module failed for action: " + action));
            }

            // Execute action - not yet migrated to agent-service
            if ("execute".equals(action)) {
                return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "The 'execute' action could not be handled on this code path. " +
                    "Retry the call; if it keeps failing, ask the user for help.");
            }

            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Invalid action: " + action + ". Valid actions: " + String.join(", ", VALID_ACTIONS));

        } catch (Exception e) {
            log.error("Error executing agent action {}: {}", action, e.getMessage(), e);
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Error: " + e.getMessage());
        }
    }

    // ==================== Unified Tool Definition ====================

    private AgentToolDefinition buildUnifiedAgentTool() {
        List<ToolParameter> params = List.of(
            ToolParameter.builder()
                .name("action")
                .type("string")
                .description("""
                    Action to perform. Groups:
                    - Agent CRUD: create, get, list, update, delete
                    - Execution: execute
                    - Memory & sharing: get_history, search_messages, share, unshare, refresh_share
                    - Marketplace publication: publish (requires title + interface_id landing page), unpublish
                    - Task delegation - assignee path (you do the work): assign, inbox, outbox, task_complete, task_reject, task_update, task_cancel, task_delete
                    - Task delegation - reviewer path (you judge someone else's work): review_inbox, task_get_context, task_get_execution, task_approve, task_reject_review
                    - Task delegation - backlog: backlog, claim
                    - Task delegation - recurrences: recurrence_create, recurrence_list, recurrence_update, recurrence_delete
                    - Help: help, help_models
                    ROLE RULE: task_complete/task_reject are ASSIGNEE-only. task_approve/task_reject_review are REVIEWER-only. If you were asked to review a task, use review_inbox → then task_approve or task_reject_review - NEVER task_complete.
                    NAMING: publish/unpublish target the public MARKETPLACE (acquirers install a copy). share/unshare/refresh_share target a single CONVERSATION via a private link (no install).
                    """)
                .required(true)
                .enumValues(VALID_ACTIONS)
                .build(),
            stringParam("agent_id", "Agent ID - UUID (for: get, update, delete, execute, assign[target], task_update[reassign]). For assign: omit or NULL to create a backlog task any agent can claim.", false),
            stringParam("name", "Agent name (for: create, update)", false),
            stringParam("description", "Agent description (for: create, update)", false),
            stringParam("system_prompt", "System prompt for the agent - REQUIRED for create", false),
            stringParam("model_provider",
                "OPTIONAL (for: create, update). Omit to use the platform default (model #1 in the catalog). To pick a specific provider, call agent(action='help_models') first to see configured (provider, model) pairs. Unknown values are silently substituted with the platform default and the swap is reported back as 'model_substituted' in the response.",
                false),
            stringParam("model_name",
                "OPTIONAL (for: create, update). Omit to use the platform default. To pick a specific model, call agent(action='help_models') first. Unknown (provider, model) pairs are silently substituted with the platform default and the swap is reported back as 'model_substituted' in the response.",
                false),
            ToolParameter.builder()
                .name("temperature")
                .type("number")
                .description("Temperature 0-2, lower=deterministic, higher=creative (for: create, update)")
                .build(),
            intParam("max_tokens", "Maximum output tokens per turn; auto-capped to the model's real ceiling (for: create, update)", false, 16000),
            intParam("max_iterations", "Maximum tool call iterations 1-1000 (for: create, update)", false, 100),
            intParam("execution_timeout", "Execution timeout in seconds 10-7200 (for: create, update)", false, 3600),
            intParam("inactivity_timeout", "Inactivity watchdog window in seconds: the agent is stopped (INACTIVITY_TIMEOUT) if it emits no token, thinking, or tool activity for this long. Independent of execution_timeout (the total cap). 0 = disabled, 10-7200 = custom, omit for the 5-minute default (for: create, update)", false, null),
            enumParam("tools_mode", "Tool access: 'all'=all MCP/catalog tools, 'none'=no MCP tools (internal tools like table, web_search remain), 'off'=NO tools at all (not even internal - a reasoning-only judge/classifier/transformer agent that advertises ZERO tool schemas; resource grants are ignored), 'custom'=only tools listed in 'tools' param (for: create, update)", false,
                List.of("all", "none", "off", "custom")),
            ToolParameter.builder()
                .name("tools")
                .type("array")
                .description("List of MCP tool IDs when tools_mode='custom', e.g. ['github:create_issue', 'slack:send_message']. Max 30 tools.")
                .build(),
            ToolParameter.builder()
                .name("workflows")
                .type("array")
                .description("List of workflow IDs the agent can trigger. Default=[] (NO access). Pass [uuid,...] for specific grants, [] to revoke. For ALL workflows, set workflows_grant='all' (this list is then ignored). Omitting or passing null is treated as []. (for: create, update)")
                .build(),
            ToolParameter.builder()
                .name("applications")
                .type("array")
                .description("List of marketplace application IDs (sourcePublicationId) the agent can access. Default=[] (NO access). Pass [uuid,...] for specific grants, [] to revoke. For ALL applications, set applications_grant='all' (this list is then ignored). Omitting or passing null is treated as []. (for: create, update)")
                .build(),
            ToolParameter.builder()
                .name("tables")
                .type("array")
                .description("List of table IDs (integers) the agent can access. Default=[] (NO access). Pass [id,...] for specific grants, [] to revoke. For ALL tables, set tables_grant='all' (this list is then ignored). Omitting or passing null is treated as []. (for: create, update)")
                .build(),
            ToolParameter.builder()
                .name("interfaces")
                .type("array")
                .description("List of interface UUIDs the agent can access. Default=[] (NO access). Pass [uuid,...] for specific grants, [] to revoke. For ALL interfaces, set interfaces_grant='all' (this list is then ignored). Omitting or passing null is treated as []. (for: create, update)")
                .build(),
            ToolParameter.builder()
                .name("agents")
                .type("array")
                .description("List of sub-agent UUIDs the agent can call. Default=[] (NO access). Pass [uuid,...] for specific grants, [] to revoke. For ALL sub-agents, set agents_grant='all' (this list is then ignored). Omitting or passing null is treated as []. (for: create, update)")
                .build(),
            boolParam("web_search", "Enable web search capability for the agent (default: true) (for: create, update)", false, true),
            stringParam("workflow_id", "Single workflow ID to link agent to (legacy, prefer 'workflows' array)", false),
            ToolParameter.builder()
                .name("datasource_id")
                .type("integer")
                .description("Associated table ID for data context (for: create, update)")
                .build(),
            stringParam("conversation_id", "Conversation ID to link agent to (for: create, update)", false),
            ToolParameter.builder()
                .name("skill_ids")
                .type("array")
                .description("List of skill UUIDs to assign to the agent (for: create, update). REPLACES all existing skills. Pass ALL skill IDs at once. Max 10 skills. To ADD skills without replacing, use skill(action='assign') instead.")
                .build(),
            ToolParameter.builder()
                .name("credit_budget")
                .type("number")
                .description("Maximum credit budget for agent execution. Each LLM iteration costs 1 credit. null=unlimited (for: create, update)")
                .build(),
            enumParam("budget_reset_mode", "Budget reset mode: 'cumulative' (default, never resets) (for: create, update)", false,
                List.of("cumulative")),
            boolParam("is_public", "Whether agent is publicly accessible (for: create, update)", false, false),
            boolParam("is_active", "Whether agent is active (for: create, update)", false, true),
            stringParam("prompt", "Prompt/task to send to the sub-agent (for: execute)", false),
            stringParam("context", "Additional context to prepend to the prompt (for: execute)", false),
            boolParam("memory", "Sub-agent conversation memory. true (default)=agent sees previous conversations, false=stateless one-shot (for: execute)", false, true),
            intParam("timeout", "Execution timeout in seconds, 10-300 (for: execute)", false, 120),
            intParam("limit", "Max results to return (for: list, get_history, search_messages). Default: 25 for list, 20 for get_history and search_messages. search_messages caps at 50.", false, 25),
            intParam("offset", "Pagination offset (for: list, task_get_execution)", false, 0),
            enumParam("share_mode", "'read' (default) or 'readwrite' - Access level for shared conversation link (for: share)", false, List.of("read", "readwrite")),

            // Per-resource access modes (for: create, update)
            enumParam("table_access_mode", "Tables access: 'write' (default, full CRUD) or 'read' (query only, no create/update/delete) (for: create, update)", false, List.of("read", "write")),
            enumParam("workflow_access_mode", "Workflows access: 'write' (default) or 'read' (view only, no create/edit/execute) (for: create, update)", false, List.of("read", "write")),
            enumParam("interface_access_mode", "Interfaces access: 'write' (default) or 'read' (view only) (for: create, update)", false, List.of("read", "write")),
            enumParam("agent_access_mode", "Sub-agents access: 'write' (default) or 'read' (view only, no create/execute) (for: create, update)", false, List.of("read", "write")),
            enumParam("application_access_mode", "Applications access: 'write' (default) or 'read' (view only) (for: create, update)", false, List.of("read", "write")),
            enumParam("skill_access_mode", "Skills access: 'write' (default) or 'read' (view only) (for: create, update)", false, List.of("read", "write")),
            // Per-resource GRANT scope (none/all/custom). Authoritative when set: 'all' grants EVERY
            // resource of that family (the matching id list is ignored). Omit to derive from the list
            // (empty=none, non-empty=custom) - so existing callers keep working unchanged.
            enumParam("workflows_grant", "Workflows GRANT scope: 'none'=no access, 'all'=EVERY workflow (the 'workflows' list is ignored), 'custom'=only the IDs in 'workflows'. Omit to derive from the list. (for: create, update)", false, List.of("none", "all", "custom")),
            enumParam("applications_grant", "Applications GRANT scope: 'none'=no access, 'all'=EVERY application, 'custom'=only the IDs in 'applications'. Omit to derive from the list. (for: create, update)", false, List.of("none", "all", "custom")),
            enumParam("tables_grant", "Tables GRANT scope: 'none'=no access, 'all'=EVERY table, 'custom'=only the IDs in 'tables'. Omit to derive from the list. (for: create, update)", false, List.of("none", "all", "custom")),
            enumParam("interfaces_grant", "Interfaces GRANT scope: 'none'=no access, 'all'=EVERY interface, 'custom'=only the IDs in 'interfaces'. Omit to derive from the list. (for: create, update)", false, List.of("none", "all", "custom")),
            enumParam("agents_grant", "Sub-agents GRANT scope: 'none'=no access, 'all'=EVERY sub-agent, 'custom'=only the IDs in 'agents'. Omit to derive from the list. (for: create, update)", false, List.of("none", "all", "custom")),

            // Webhook configuration (for: create, update)
            boolParam("webhook_enabled", "Enable webhook endpoint for the agent. Returns a URL + curl example (for: create, update)", false, false),
            boolParam("webhook_memory", "Webhook uses conversation memory - agent sees previous messages (for: create, update)", false, false),

            // Schedule configuration (for: create, update)
            stringParam("schedule_cron", "Cron expression for scheduled execution, e.g. '0 9 * * *' = daily 9AM. Pass empty string '' to REMOVE the schedule (for: create, update)", false),
            stringParam("schedule_timezone", "Timezone for schedule, e.g. 'Europe/Paris' (default: UTC) (for: create, update)", false),
            intParam("schedule_max_executions", "Max number of scheduled executions (null=unlimited) (for: create, update)", false, null),
            stringParam("schedule_prompt", "Message sent to agent at each scheduled execution (for: create, update). NOTE: when task delegation has pending work for the agent at fire time, this static prompt is REPLACED by a dynamic task-inbox prompt - used only as a fallback when the agent has no tasks.", false),
            boolParam("schedule_memory", "Schedule uses conversation memory - agent sees previous executions (for: create, update)", false, false),

            // ==================== Task Delegation (assign, inbox, complete, recurrences, ...) ====================
            stringParam("title", "Short task title, max 500 chars (for: assign, recurrence_create, recurrence_update)", false),
            stringParam("instructions", "Detailed task instructions, max 50KB (for: assign, recurrence_create, recurrence_update)", false),
            stringParam("reviewer_agent_id", "Agent UUID to review the task after completion. If set, task_complete moves to in_review and that agent reviews. If omitted, the human user (tenant owner) reviews from the task board UI. The reviewer uses review_inbox/task_approve/task_reject_review. (for: assign, task_update)", false),
            intParam("max_review_attempts", "Cap on reviewer reject attempts before the task is auto-failed (status='failed' with the last reviewer feedback). Range [1, 20]. Default: 3 when omitted. Only meaningful when reviewer_agent_id is set. (for: assign, task_update)", false, null),
            stringParam("start_mode", "Execution timing for assign. 'pending' (UI-style): create the row, do NOT trigger the worker - task waits for an explicit pickup (inbox/claim/own activation). 'in_progress': create + dispatch the worker async, return immediately without waiting. 'execute' (default): create + dispatch + block until terminal state, return result/error_message inline. Backlog (no agent_id) is always 'pending' regardless. (for: assign)", false),
            stringParam("task_id", "Task UUID (for: inbox(single), outbox(single), task_complete, task_reject, task_update, task_cancel, claim, task_get_context, task_get_execution)", false),
            stringParam("execution_id",
                "Execution UUID (for task_get_execution). Must belong to the same task - verified server-side.",
                false),
            boolParam("include_tool_calls",
                "For task_get_execution: also return the per-iteration tool_calls (with secret redaction). Default false.",
                false, false),
            ToolParameter.builder()
                .name("task_context")
                .type("object")
                .description("Arbitrary JSON context blob carried with the task (for: assign, recurrence_create). Plain map, no schema.")
                .build(),
            stringParam("result", "Completion result text, max 50KB - REQUIRED for task_complete", false),
            stringParam("reason", "Human-readable reason (for: task_reject, task_cancel)", false),
            stringParam("due_by", "Optional ISO-8601 deadline, e.g. '2026-04-15T12:00:00Z' (for: assign)", false),
            enumParam("priority", "Task priority: 'low' | 'normal' (default) | 'high' | 'urgent'. (for: assign, task_update, recurrence_create, recurrence_update)", false,
                List.of("low", "normal", "high", "urgent")),
            enumParam("status", "Task status. For outbox: filter by status. For task_update: transition to this status (any→any). " +
                "Constraint: in_progress and in_review require an assigned agent. " +
                "(for: outbox, task_update)", false,
                List.of("pending", "in_progress", "in_review", "completed", "failed", "cancelled")),
            arrayParam("label_ids", "Existing board-label UUIDs to set on the task. Combined with `labels`; together they REPLACE the current set (label_ids:[] alone, or both absent/empty, clears). (for: assign, task_update)", false),
            arrayParam("labels", "Label NAMES to set on the task, e.g. ['urgent','qa']. Each name is matched on the board case-insensitively and CREATED if absent, then attached - no need to look up a UUID first. Combined with `label_ids` (their union replaces the set; max 25 labels per task). Max 60 chars per name. (for: assign, task_update)", false),
            intParam("estimate_minutes", "Estimated effort in minutes. (for: assign, task_update)", false, null),
            intParam("time_spent_minutes", "Logged time spent in minutes. (for: assign, task_update)", false, null),
            arrayParam("blocked_by", "Task UUIDs that must finish before this one. Replaces the set; empty clears. Self-reference and cycles are rejected. (for: assign, task_update)", false),
            arrayParam("checklist", "Checklist items, each an object {text, done}. Replaces the list; empty clears. (for: assign, task_update)", false),
            stringParam("cron", "Cron expression for recurring tasks, e.g. '0 9 * * *' (for: recurrence_create, recurrence_update)", false),
            stringParam("timezone", "IANA timezone for cron, e.g. 'Europe/Paris' (default: UTC) (for: recurrence_create)", false),
            stringParam("recurrence_id", "Recurrence UUID (for: recurrence_update, recurrence_delete)", false),
            stringParam("target_agent_id", "Agent UUID that recurrence-created tasks will be assigned to. NULL = tasks go to the backlog (for: recurrence_create)", false),
            enumParam("scope",
                "Two distinct uses: " +
                "(1) recurrence_list filter: 'created_by_me' (default), 'targeting_me', 'all_in_tenant'. " +
                "(2) search_messages scope: 'self' = your own conversation, 'children' = sub-agents in your allowlist, 'all_visible' (default for search) = both.",
                false,
                List.of("created_by_me", "targeting_me", "all_in_tenant",
                        "self", "children", "all_visible")),
            boolParam("enabled", "Enable/disable a recurrence without deleting it (for: recurrence_update)", false, null),

            // ==================== Message search (search_messages) ====================
            stringParam("query",
                "FTS query for search_messages. Required. Supports search-engine syntax: 'OR' (\"facture OR invoice\"), " +
                "quoted phrases (\"\\\"docker networking\\\"\"), and '-negation' (\"refund -test\"). Max 500 chars.",
                false),
            stringParam("since",
                "Lower bound on message created_at for search_messages. ISO-8601 with zone, e.g. '2026-04-01T00:00:00Z'. " +
                "Naive local times are rejected.",
                false),
            stringParam("until",
                "Upper bound on message created_at for search_messages. ISO-8601 with zone.",
                false),
            ToolParameter.builder()
                .name("roles")
                .type("array")
                .description(
                    "Filter for search_messages: array of message roles to include. " +
                    "Values: ['USER', 'ASSISTANT', 'SYSTEM', 'TOOL']. Omit to search all roles.")
                .build(),
            stringParam("tool_name",
                "Filter for search_messages: only messages with role='TOOL' that came from this specific tool " +
                "(e.g. 'web_search', 'terminal').",
                false),
            stringParam("cursor",
                "Pagination cursor for search_messages. Opaque base64 token returned by the previous response " +
                "as 'next_cursor'. Pass null/omit for the first page.",
                false),

            // ==================== Marketplace publication (publish, unpublish) ====================
            stringParam("interface_id", "Landing interface UUID - REQUIRED for publish (the public-facing page presented to acquirers before they install the agent).", false),
            enumParam("visibility", "Marketplace visibility: 'PRIVATE' (default, only you), 'PUBLIC' (anyone), 'UNLISTED' (link only) (for: publish)", false,
                List.of("PRIVATE", "PUBLIC", "UNLISTED")),
            intParam("credits_per_use", "Credits charged to acquirers per execution. Default 0 (free). Required > 0 for some PUBLIC publications. (for: publish)", false, 0)
        );

        return AgentToolDefinition.builder()
            .name("agent")
            .description("""
                Create, manage, and delegate work to AI agents.
                CRUD: create (requires name + system_prompt), get, list, update, delete. tools_mode defaults to 'all'; model_provider/model_name are OPTIONAL - omit to use the platform default; call agent(action='help_models') only if you need to override.
                execute: run a sub-agent with a prompt synchronously. Requires agent_id + prompt. Memory on by default.
                Webhook: webhook_enabled=true returns a POST URL + curl example.
                Schedule: schedule_cron enables recurring execution. Dynamic task-inbox prompt overrides schedule_prompt when tasks are pending.
                Memory: get_history fetches another agent's conversation history (sub-agent memory access). share returns a shareable conversation link.
                Task delegation (async agent-to-agent work): assign creates a task for a target agent (or NULL agent_id = backlog anyone can claim). inbox/outbox list your pending/sent tasks. task_complete/task_reject/task_cancel close tasks. task_delete permanently removes a terminal task. claim picks a backlog item. Recurrences create cron-driven task templates (recurrence_create/list/update/delete).
                Reviewer path (separate from assignee): review_inbox lists tasks awaiting YOUR review. task_approve accepts; task_reject_review sends back to the assignee with a reason. NEVER call task_complete on a task you are reviewing - that verb is for the assignee only.
                Call agent(action='help') for the full docs, examples, and lifecycle diagrams.
                """)
            .category(ToolCategory.AGENT)
            .parameters(params)
            .requiredParameters(List.of("action"))
            .inputSchema(generateInputSchema(params, List.of("action")))
            .helpText("Call agent(action='help') for full documentation.")
            .requiresAuth(true)
            .tags(List.of("agent", "crud", "unified", "ai"))
            .timeoutMs(3_600_000L)
            .build();
    }
}
