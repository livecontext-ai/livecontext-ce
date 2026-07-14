package com.apimarketplace.agent.tools.agent;

import com.apimarketplace.agent.bridge.BridgeAccessGuard;
import com.apimarketplace.agent.config.AgentDefaultsConfig;
import com.apimarketplace.agent.registry.AgentToolDefinition;
import com.apimarketplace.agent.service.ModelCatalogService;
import com.apimarketplace.agent.service.ModelCatalogService.AvailableModel;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.agent.tools.common.ToolModule;
import com.apimarketplace.common.web.AdminRoleGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Help module for the agent tool.
 * Provides documentation, parameter details, model providers, and examples.
 * Default model/provider are resolved dynamically from the AI provider catalog.
 * Other defaults (temperature, maxTokens, etc.) come from AgentDefaultsConfig (application.yml).
 */
@Component
public class AgentHelpModule implements ToolModule {

    private static final Logger log = LoggerFactory.getLogger(AgentHelpModule.class);

    /** Credentials-map key carrying the caller's platform role string (X-User-Roles),
     *  threaded from the gateway through the agent loop / tool-exec controllers so a
     *  service-layer module can resolve admin status without an HttpServletRequest. */
    private static final String CRED_USER_ROLES = "__userRoles__";

    private final AgentDefaultsConfig defaults;
    private final ModelCatalogService modelCatalogService;

    /**
     * CLI-bridge access guard. Used here ONLY to pre-filter the model catalog
     * surfaced to the LLM (same decision the dispatch path enforces) so a
     * non-admin agent never sees - and therefore never selects - a bridge model
     * it would be blocked from using. {@code required=false} mirrors
     * {@code BridgeLoopDispatcher}: the bean is always wired in agent-service,
     * but optional injection keeps test slices and bridge-less deployments green.
     */
    @Autowired(required = false)
    private BridgeAccessGuard bridgeAccessGuard;

    public AgentHelpModule(AgentDefaultsConfig defaults,
                           ModelCatalogService modelCatalogService) {
        this.defaults = defaults;
        this.modelCatalogService = modelCatalogService;
    }

    /** Package-private setter used by unit tests (mirrors BridgeLoopDispatcher). */
    void setBridgeAccessGuard(BridgeAccessGuard guard) {
        this.bridgeAccessGuard = guard;
    }

    /**
     * Returns the default model descriptor for help docs. When no platform-wide default
     * is configured we return {@code null} so callers render {@code (required)} - the
     * previous literal {@code "unknown"} lied to the LLM, which would dutifully send
     * {@code model_name='unknown'} and hit a rejection (DOC-1).
     */
    private String resolveDefaultModel() {
        return modelCatalogService.getEffectiveDefaultModel();
    }

    private String resolveDefaultProvider() {
        return modelCatalogService.getEffectiveDefaultProvider();
    }

    private String modelParamDoc(String role, String defaultValue, String tail) {
        if (defaultValue != null && !defaultValue.isBlank()) {
            return "string, default='" + defaultValue + "' - " + tail;
        }
        return "string, (required - no platform-wide default is configured) - " + tail;
    }

    @Override
    public List<AgentToolDefinition> getToolDefinitions() {
        return List.of();
    }

    @Override
    public boolean canHandle(String action) {
        return "help".equals(action) || "help_models".equals(action);
    }

    @Override
    public Optional<ToolExecutionResult> execute(String action, Map<String, Object> parameters,
                                                  String tenantId, ToolExecutionContext context) {
        if (!canHandle(action)) return Optional.empty();
        if ("help_models".equals(action)) {
            return Optional.of(executeHelpModels(context));
        }
        return Optional.of(executeHelp());
    }

    /**
     * Returns the full available-models catalog on demand. The default
     * {@code help} action no longer embeds it (paid in every prompt the agent
     * tool is exposed in); the LLM only calls {@code help_models} when it
     * actually wants to override the platform default. Catalog is filtered
     * (only providers with a configured API key, same as the UI app-header
     * model selector) and sorted by displayOrder ASC (top-ranked first).
     */
    private ToolExecutionResult executeHelpModels(ToolExecutionContext context) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("description",
            "Available LLM models for agent create/update, in priority order. "
            + "Only providers with a configured API key appear here - the same configured catalog the platform admin manages (you cannot change it from this tool). "
            + "Both model_provider and model_name are OPTIONAL on create/update: omit them to use the platform default (model #1 below). "
            + "If you pass an unknown pair it is silently substituted with the default, and the swap is reported as 'model_substituted' in the response.");
        out.putAll(buildAvailableModels(context));
        return ToolExecutionResult.success(out);
    }

    private ToolExecutionResult executeHelp() {
        Map<String, Object> result = new LinkedHashMap<>();

        result.put("description",
            "AGENT TOOL - Create, configure, and orchestrate AI agents.\n\n" +
            "WHAT AN AGENT IS: an AI assistant with a system_prompt (its role), a model (provider + name + " +
            "temperature), a set of allowed tools (MCP catalog + internal tools like web_search), allowed resources " +
            "(tables, workflows, interfaces, sub-agents, applications), and optional activation mechanisms " +
            "(webhook, schedule, skills). When activated, the agent runs a tool-calling loop - it reads its prompt, " +
            "decides which tool to call, reads the result, and iterates until it produces a final response, hits " +
            "max_iterations, or runs out of credit_budget.\n\n" +
            "ACTIVATION & DELEGATION: the four paths an agent actually runs on (user_chat, webhook, " +
            "schedule_cron, parent_execute) are documented in concepts.activation_triggers. The assign/backlog " +
            "model lives in concepts.task_delegation_model, and the canonical self-draining worker recipe is in " +
            "concepts.worker_pattern. KEY NUANCE: assign creates a persistent task record but does NOT push a " +
            "notification - the task sits in the target's inbox until the target is next activated. An agent " +
            "with no schedule, no active chat, and no parent executor will NEVER process its inbox - give " +
            "worker agents a schedule_cron (e.g. '*/10 * * * *').\n\n" +
            "SECURE DEFAULTS: On CREATE, resource lists (workflows, tables, agents, …) default to [] (no access) " +
            "- grant each category explicitly. All other defaults (model, temperature, token limits, iteration " +
            "caps, …) are documented in the `parameters` section of this help.");

        // Use LinkedHashMap to preserve order
        Map<String, String> actions = new LinkedHashMap<>();

        // --- Agent CRUD ---
        actions.put("create",
            "Insert a new agent. Requires name + system_prompt; any other parameter (webhook, schedule, " +
            "resources, skills, budget) can be set atomically in the same call. Response includes resources, " +
            "a summarized tools_config (grants + list sizes - call get for the raw lists), budget, and " +
            "webhook_url + webhook_curl when webhook_enabled.");
        actions.put("get",
            "Fetch a single agent by agent_id. Returns full configuration + resources summary + RAW tools_config " +
            "(full id lists) + budget object + webhook info when configured.");
        actions.put("list",
            "Paginated list of agents in your tenant. Returns resources summary only - use get for full details. " +
            "Accepts limit, offset.");
        actions.put("update",
            "Merge-update an agent by agent_id. ONLY provided fields change; omitted fields are preserved. " +
            "Resource arrays (workflows, tables, agents, …) REPLACE the entire list for their category - pass " +
            "the full list every time. Supports webhook/schedule/budget updates in the same call.");
        actions.put("delete",
            "Hard-delete an agent by agent_id. Cascades to the agent's webhook, schedule, and pending tasks " +
            "assigned to it.");

        // --- Synchronous execution ---
        actions.put("execute",
            "Run a sub-agent SYNCHRONOUSLY and inline the response. Caller waits for the sub-agent to finish its " +
            "tool-calling loop. Requires agent_id + prompt. Sub-agent sees previous conversation (memory=true " +
            "default) unless memory=false. IMPORTANT: on this path the sub-agent's system_prompt is automatically " +
            "augmented with a 'Tasks in your inbox' section, so calling execute is one of the two automatic " +
            "inbox wake-up paths (the other is schedule fire).");

        // --- Memory & sharing ---
        actions.put("get_history",
            "Read the last 20 user/assistant messages of an agent's conversation. With agent_id = a sub-agent " +
            "in your 'agents' allowlist. Without agent_id = your own conversation. Use this to see what a " +
            "worker agent actually reasoned/did, beyond just its task_complete result text.");
        actions.put("search_messages",
            "Full-text search across conversation messages. Required: query (search terms - supports OR, " +
            "\"quoted phrases\", -negation). Optional: scope ('self' = your own conversation only, 'children' " +
            "= sub-agents in your 'agents' allowlist, 'all_visible' = both - DEFAULT). Optional: agent_id to " +
            "narrow to one specific sub-agent within the scope (must be in your allowlist). Optional filters: " +
            "since/until (ISO-8601 with zone like '2026-04-23T10:00:00Z'), roles (e.g. ['USER','ASSISTANT']), " +
            "tool_name (only role='TOOL' messages of that tool), limit (default 20, max 50), cursor (opaque " +
            "from previous response for pagination). Response: {results: [{message_id, conversation_id, " +
            "conversation_title, agent_id, execution_id, role, tool_name, excerpt (with ⟦matched terms⟧ " +
            "highlighted), rank, created_at}], next_cursor, has_more, returned_count, scope_truncated, scope}. " +
            "scope_truncated=true means your allowlist had more than 200 sub-agents; older ones were dropped " +
            "from the search. Use search_messages to find prior context (\"where did I discuss X with this " +
            "client?\") then get_history(agent_id=…) to load the surrounding messages.");
        actions.put("share",
            "Make a conversation publicly accessible via a share URL. Targets own conversation by default, or a " +
            "sub-agent's if agent_id is passed. share_mode='read' (default) or 'readwrite'. Returns share_url.");
        actions.put("unshare",
            "Disable the public share link for a conversation. Same targeting as share.");
        actions.put("refresh_share",
            "Regenerate the share token - the old URL stops working, a new share_url is returned.");

        // --- Marketplace publication (different from conversation share/unshare above) ---
        actions.put("publish",
            "Add the agent to the marketplace so other tenants can install a copy. Params: agent_id (required), " +
            "title (required), interface_id (REQUIRED - UUID of the landing interface shown to acquirers before install), " +
            "visibility ('PRIVATE' default, 'PUBLIC', 'UNLISTED'), credits_per_use (default 0). " +
            "PUBLIC listings go through a platform review before becoming visible; PRIVATE/UNLISTED activate immediately. " +
            "Response: status='PUBLISHED', publication_id, visibility, credits_per_use.");
        actions.put("unpublish",
            "Mark the agent's marketplace listing inactive. Params: agent_id (required). " +
            "Existing acquirers keep their installed copies - only new installs are blocked. " +
            "Fails if the agent has no active publication.");

        // --- Task delegation: core ---
        actions.put("assign",
            "Create a task. Required: title + instructions. " +
            "DISPATCH MODE - controlled by start_mode: " +
            "(1) 'execute' (default): create + run the assignee + sync-wait until terminal state, return the " +
            "final status with .result or .error_message inline. Use for chat-driven delegation when you need " +
            "the answer back in this turn. " +
            "(2) 'in_progress': create + dispatch the worker async, return immediately without waiting. The " +
            "response status will read 'pending' here - the worker promotes it to 'in_progress' once it " +
            "acquires the lock. Use to fire-and-forget when you don't need the result inline. " +
            "(3) 'pending': create the row only, do NOT trigger the worker. The task waits in 'pending' until " +
            "something else picks it up: a future inbox(task_id) call from the assignee, the assignee's own " +
            "schedule_cron/webhook firing, or a user UI claim. Use this when posting work that the assignee " +
            "will do on its own next activation. " +
            "BACKLOG: if agent_id is omitted, the task lands in the tenant backlog (status='pending', " +
            "assigned_to='backlog') and any agent with a schedule AND backlog_enabled=true will pick it up via " +
            "agent(action='claim') - agents are NOT offered the backlog by default, so post here only when at " +
            "least one backlog-enabled worker exists (else assign to a specific agent_id). start_mode is ignored " +
            "for backlog (always pending). " +
            "ACTIVATION WARNING: when using 'pending' or 'in_progress', if the assignee has NO activation " +
            "trigger (no schedule_cron, no webhook, no parent_execute expected), the task sits forever. " +
            "Either set start_mode='execute' to drive it now, or configure schedule_cron on the assignee, or " +
            "post to the backlog instead. " +
            "REVIEW STEP (ALWAYS PRESENT): after the assignee calls task_complete/task_reject the task " +
            "transitions to 'in_review'. Who reviews depends on reviewer_agent_id: SET → that agent auto-runs " +
            "on its own activation trigger and approves/rejects. NULL → the HUMAN USER (tenant owner) is the " +
            "implicit reviewer; the task sits in 'in_review' awaiting the user's manual approve/reject. " +
            "'in_review' without a reviewer agent is NOT an error - it is the expected resting state, and " +
            "you (the LLM) have no action to take: the user resolves it outside this tool. " +
            "REVIEWER LOOP CAP: max_review_attempts ([1, 20], default 3) bounds reviewer rejections - once " +
            "hit, the task is AUTO-FAILED (not auto-approved) with the last reviewer feedback as " +
            "error_message, so unvalidated work is never silently promoted. " +
            "PERMISSION: if you are a persisted agent, agent_id and reviewer_agent_id must be in your " +
            "toolsConfig.agents list. Empty list or absent === no delegation allowed (security rule: " +
            "absent === []). Only the user's primary chat (which has no agent row) can assign tenant-wide. " +
            "EXECUTION SCOPE: the assignee runs the task under ITS OWN toolsConfig - if your task " +
            "instructions reference workflow X, the assignee can use X ONLY if X is in its own " +
            "toolsConfig.workflows allowlist. Same for the reviewer (judges under its own scope) and " +
            "any backlog claimer (executes under its own scope). Granting access is the assignee owner's " +
            "responsibility - you cannot widen scope from this tool. " +
            "Read the result back via agent(action='outbox', task_id=…) once the task reaches a terminal " +
            "state. Rate-limited to 5 assigns per conversation turn.");
        actions.put("inbox",
            "ASSIGNEE workflow - lists or fetches tasks where YOU are the assignee (work to do). " +
            "Without task_id: returns up to 20 pending/in_progress tasks assigned to YOU, ordered by creation time. " +
            "With task_id: fetch one task AND auto-transition it from pending → in_progress, so the next inbox " +
            "call won't return it again. " +
            "NEXT ACTION: do the work, then call task_complete(task_id=…, result=…) - or task_reject(task_id=…, reason=…) if blocked. " +
            "If you are the REVIEWER of a task (not its assignee), use review_inbox instead. " +
            "Never call task_complete on a task you are reviewing - use task_approve / task_reject_review.");
        actions.put("review_inbox",
            "REVIEWER workflow - lists tasks awaiting YOUR review (you are the configured reviewer and the task is in 'in_review' status). " +
            "These are tasks another agent submitted; your job is to read the result and decide. " +
            "Returns up to 20 items. Each shows the submitted result/error_message so you can judge without extra fetches. " +
            "NEXT ACTION: for each task, call task_approve(task_id=…) to accept, or " +
            "task_reject_review(task_id=…, reason=…) to send back to the assignee for a new attempt. " +
            "NEVER call task_complete on a review task - that's the assignee's verb, not the reviewer's.");
        actions.put("outbox",
            "Without task_id: list up to 20 tasks YOU created for others (including backlog items you posted). " +
            "Filter with status. With task_id: fetch one of your own tasks including its current result/" +
            "error_message - this is how you read back what the worker produced.");
        actions.put("task_complete",
            "ASSIGNEE-ONLY - submit your finished work on a task you are the assignee of. Requires task_id + result (max 50KB). " +
            "Always transitions to 'in_review'. If reviewer_agent_id is set → the reviewer agent will read the " +
            "task from its review_inbox on its own next activation trigger (async - not immediate). " +
            "If reviewer_agent_id is NULL → the HUMAN USER (tenant owner, the one who created the task from chat) " +
            "is the implicit reviewer; the task stays in 'in_review' until the user manually approves it - " +
            "this is CORRECT and EXPECTED behavior, not a stuck task, and you have no further action. " +
            "DO NOT call this on a task you are the REVIEWER of - use task_approve / task_reject_review. " +
            "If a reviewer accidentally calls task_complete on an in_review task, the call is auto-rerouted to task_approve " +
            "(the system protects against verb-confusion between assignee and reviewer roles).");
        actions.put("task_approve",
            "REVIEWER-ONLY - approve a task you are the reviewer of (in_review → completed). Requires task_id. " +
            "Use this once you have read the submitted result and it meets the brief. " +
            "Pair with review_inbox to discover which tasks are waiting on you.");
        actions.put("task_reject_review",
            "REVIEWER-ONLY - reject the assignee's submission and send it back for another attempt " +
            "(in_review → in_progress). Requires task_id + reason (max 2KB). Your reason is shown to the assignee " +
            "as new instructions. Bounded by max_review_attempts (default 3): once the cap is hit the task is " +
            "AUTO-FAILED - not auto-approved - so unvalidated work is never silently promoted. " +
            "Pair with review_inbox to discover which tasks are waiting on you.");
        actions.put("task_get_context",
            "Two-step task navigation, step 1: get the task header + executions metadata + ordered " +
            "execution_ids. NO message bodies - call task_get_execution(task_id, execution_id) to drill " +
            "into a specific execution. Required: task_id. " +
            "Response: {task: {id, title, instructions, status, result, error_message, " +
            "assignee_agent_id, assignee_agent_name, reviewer_agent_id, creator_agent_id, parent_task_id, " +
            "created_at, updated_at}, events: {total, shown, truncated, items: [{id, event_type, " +
            "actor_type, actor_id, created_at}]} (event_type values include 'status_change', 'reassign', " +
            "'priority_change', 'note_added' - actor_type is 'agent'|'user'|'system'), executions: " +
            "[{execution_id, order (chronological starting at 0), status, started_at, ended_at, " +
            "duration_ms, model, provider, iteration_count, message_count, total_tool_calls, " +
            "successful_tool_calls, failed_tool_calls, distinct_tools, loop_detected, credits_consumed}], " +
            "viewer_role ('REVIEWER'|'CREATOR'|'GOD' - informational, your access path), hint (next-step " +
            "guidance string)}. " +
            "PERMISSION: granted if you are the task's reviewer_agent_id, its created_by_agent_id (direct " +
            "parent only), OR you are the user's primary chat agent (no toolsConfig.agents restriction - " +
            "you can see any task in the tenant). The ASSIGNEE is NOT granted via this action - assignees " +
            "use get_history to read their own conversation. " +
            "REVIEWER flow: review_inbox → task_get_context(task_id) → (if doubt: " +
            "task_get_execution) → task_approve OR task_reject_review.");
        actions.put("task_get_execution",
            "Two-step task navigation, step 2: drill into one specific execution from task_get_context's " +
            "executions array. Required: task_id + execution_id. The execution_id MUST belong to the same " +
            "task (cross-task calls return 'not found' without leaking existence). " +
            "Optional: include_tool_calls (default false - adds the tool_calls array; loads ~1 extra " +
            "query), limit (default 50, max 100 - clamped silently), offset (default 0). " +
            "Response: {task: {id, title, status}, execution: {execution_id, status, started_at, " +
            "ended_at, iteration_count, total_tool_calls, model}, messages: {total, offset, returned, " +
            "has_more, items: [{sequence_number, iteration_number, role ('user'|'assistant'|'tool'|'system'), " +
            "tool_name (when role='tool'), tool_call_id, content, content_length}]}, tool_calls (only " +
            "when include_tool_calls=true): [{sequence_number, iteration_number, tool_call_id, tool_name, " +
            "parallel_index, success, arguments, content}], hint (next offset or 'all returned')}. " +
            "PAGINATION: loop until has_more=false, passing offset=<previous returned + offset>. " +
            "REDACTION (always server-side, not opt-out): calls to credential/oauth2/auth tools have " +
            "their entire arguments+content replaced with the literal string '[REDACTED:credential-tool]'. " +
            "Other tool calls have specific fields (token, secret, password, api_key, authorization, " +
            "bearer, refresh_token, access_token, csrf, etc.) replaced with '[REDACTED]' while benign " +
            "fields keep their values. " +
            "WHEN TO USE: only after task_get_context - for example when iteration_count is high, " +
            "loop_detected=true, failed_tool_calls > 0, or task.result is inconsistent with " +
            "executions[].distinct_tools. Same permission rules as task_get_context.");
        actions.put("task_reject",
            "ASSIGNEE-ONLY - report failure on a task you are the assignee of (can't complete it). " +
            "Requires task_id, optional reason. Transitions to 'in_review' with error_message so the reviewer " +
            "decides whether to accept the failure or send back. " +
            "The reviewer is either the configured reviewer_agent_id OR - if none was set - the HUMAN USER " +
            "(tenant owner) who decides outside this tool. " +
            "REVIEWERS use task_reject_review - not this - to bounce work back to the assignee.");
        actions.put("task_update",
            "Modify a task YOU created - title, instructions, priority, due_by, or reassign via a new agent_id. " +
            "Only the creator may update. ACCESS CONTROL: reassign (agent_id) is subject to the same " +
            "restriction as assign - you can only reassign to your configured sub-agents. " +
            "Used to amend briefings or route work to a better-suited agent.");
        actions.put("task_cancel",
            "Cancel a task YOU created AND every descendant in its subtree. All affected tasks transition to " +
            "'cancelled'. Use this when scope changes and you want the whole dependent chain stopped in one shot.");

        actions.put("task_delete",
            "Permanently hard-delete a task AND every descendant it spawned. The whole subtree must already be " +
            "terminal (completed, failed, cancelled) - if ANY descendant is still active, the call is rejected " +
            "with the offending task_id. To clean up an active subtree in one shot: call task_cancel on the root " +
            "first (cancels the whole tree), then task_delete. Only the creator may delete.");

        // --- Task delegation: backlog ---
        actions.put("backlog",
            "List up to 20 unassigned pending tasks in your tenant. Used for 'post a job, first idle worker takes " +
            "it' flows. OPT-IN: only an agent whose backlog_enabled=true may browse or claim the shared backlog " +
            "(set it via agent(action='update', params={agent_id:'...', backlog_enabled:true})). A non-participating " +
            "agent gets a permission error here and should work its directly-assigned inbox instead. Browsing it as " +
            "a human (general chat, no attached agent) is always allowed.");
        actions.put("claim",
            "Atomically take ownership of a backlog task (first-come-first-served). Returns the task on success " +
            "(status→in_progress, assignee=you) or {claimed:false} if another agent got it first. OPT-IN: requires " +
            "your backlog_enabled=true - otherwise you get a permission error and should process your inbox instead. " +
            "(A human can still hand you a backlog task directly; that override ignores the flag.)");

        // --- Task delegation: recurrences ---
        actions.put("recurrence_create",
            "Create a cron-driven template that instantiates a fresh task at every tick. Params: title, " +
            "instructions, cron, timezone, target_agent_id (NULL = backlog), priority, task_context. Use this " +
            "when you want periodic work with full audit trail (each fire = one trackable task with result). " +
            "For stateless routine work without tracking, use schedule_cron + schedule_prompt on the agent " +
            "itself instead.");
        actions.put("recurrence_list",
            "List recurrence templates. scope='created_by_me' (default, your templates), 'targeting_me' " +
            "(templates that will drop tasks in your inbox), or 'all_in_tenant'.");
        actions.put("recurrence_update",
            "Modify a recurrence template: cron, title, instructions, priority, enabled flag. Only the creator " +
            "may update. Set enabled=false to pause without deleting.");
        actions.put("recurrence_delete",
            "Permanently delete a recurrence template. Only the creator may delete. Tasks already created by " +
            "previous ticks are NOT affected - only future firings stop.");
        actions.put("help_models",
            "Live LLM catalog (top " + HELP_MODELS_MAX_ROWS + " by priority, only providers with a configured API key). " +
            "Call only if you want to override the platform default for create/update - both model fields are OPTIONAL.");
        result.put("actions", actions);

        result.put("concepts", buildConcepts());

        // Catalog moved to a dedicated action (help_models) to keep this default
        // help payload small - it ships in every prompt the agent tool is exposed
        // in. The Map shape is preserved for backward compatibility with help
        // consumers; only the catalog content (providers/pairs) is now exclusive
        // to help_models. Common path (omit both model fields → platform default
        // is used) needs no catalog lookup at all.
        Map<String, Object> availableModels = new LinkedHashMap<>();
        availableModels.put("see_also", "agent(action='help_models')");
        availableModels.put("note",
            "model_provider and model_name are BOTH OPTIONAL on create/update - omit them to use the platform default. "
            + "Call agent(action='help_models') for the live catalog (top " + HELP_MODELS_MAX_ROWS + " by priority, only providers with a configured API key). "
            + "Unknown pairs are silently substituted with the default and the swap is reported as 'model_substituted' in the create/update response.");
        result.put("available_models", availableModels);

        result.put("parameters", buildParameterDocs());
        result.put("response_shape", buildResponseShape());
        result.put("examples", buildExamples());

        result.put("tips", List.of(
            // --- CRUD semantics ---
            "CREATE: resource lists (workflows, tables, interfaces, agents, applications) default to [] on create = NO access. Grant each category explicitly, e.g. tables=[1,2], workflows=['uuid-a','uuid-b'].",
            "GRANT scope: each family has a <family>_grant param (workflows_grant, tables_grant, interfaces_grant, agents_grant, applications_grant) = 'none' | 'all' | 'custom'. Set <family>_grant='all' to grant EVERY resource of that family (e.g. a builder agent that can edit ALL workflows) - the id list is then ignored. Omit the grant param to derive it from the list (empty=none, non-empty=custom). 'all' is ONLY expressible via the grant param, never via the list.",
            "CREATE: tools_mode defaults to 'all' (every MCP/catalog tool enabled), web_search defaults to true.",
            "UPDATE: merge semantics - only provided fields change, omitted fields are preserved.",
            "UPDATE: resource arrays REPLACE the entire list for their category. To remove all tables: tables=[]. To add a third: tables=[1,2,3] (send the full new list, not a diff).",
            "RESPONSE: create/get/update return 'resources' (summary with mcp_tools_mode) and 'budget' (unified view). get returns the RAW 'tools_config' (full id lists); create/update return a SUMMARIZED 'tools_config' (mode, <family>Grant keys, <family>_count sizes for non-empty lists, tools_count, webSearch) - your submitted config WAS applied even though the raw lists are not echoed; call get to read them back. list returns 'resources' only.",
            "RESPONSE: tools_config keys differ from parameter names (mode vs tools_mode, webSearch vs web_search, tableAccessMode vs table_access_mode, …). Always use parameter names on create/update calls.",

            // --- Tools & resources ---
            "tools_mode controls MCP/catalog tools ONLY. Resource access (tables, workflows, interfaces, agents, applications) is a separate axis - both must be granted.",
            "tools_mode='none' disables MCP/catalog tools but keeps internal tools enabled (table, web_search, agent, workflow, interface, datasource, skill, …).",
            "tools_mode='off' disables EVERY tool - MCP/catalog AND internal - so the agent advertises ZERO tools and cannot call any. Use it for a reasoning-only agent (a judge, classifier, or transformer that only reads its input and replies): it is STRONGER than 'none' (which still keeps the internal tools) and is the cheapest option because no tool schema is sent on any turn. Resource grants (workflows/tables/…) are ignored when tools_mode='off'.",
            "tools_mode='custom' + tools=[…] restricts the agent to a specific set of catalog tool IDs. Max 30. Find IDs via catalog(action='search').",
            "Use skill_ids to attach reusable instruction modules (find them via skill(action='list')). REPLACES all skills - use skill(action='assign') to ADD without replacing.",

            // --- Sub-agent execution - practical details not in buildConcepts ---
            "Use the 'context' parameter to pass background data alongside prompt on execute - keeps the prompt clean and the context distinct.",
            "get_history retrieves the last 20 user/assistant messages of any sub-agent in your 'agents' allowlist. Combine with outbox(task_id=...) to see BOTH the result text AND the worker's full reasoning.",

            // --- Task delegation - operational guardrails (semantic model lives in concepts.task_delegation_model) ---
            "DELEGATION: task lifecycle - pending → in_progress → in_review → completed | failed | cancelled. See concepts.task_delegation_model for the review-gate semantics ('in_review' without a reviewer agent is the EXPECTED resting state, not a stuck task).",
            "DELEGATION: rate limit - max 5 assigns per conversation turn (prevents runaway fan-out). Plan your delegation up-front.",
            "RATE LIMITS: execute is capped at 3 per conversation turn (prevents burn-loops of 'just run the agent one more time'). Exceeding returns a rate_limit error - restructure the work instead of retrying.",
            "RATE LIMITS: update is soft-capped at 3 per conversation session (prevents iterative 'fix one field at a time' churn on weak models). The 4th call returns a warning-style error asking you to batch the remaining changes into a single update.",
            "DELEGATION: the implicit parent-inference rule - if you assign while you yourself are executing an in-progress task, the new task becomes a child of yours (parent_task_id set, depth + 1). Max depth = 5.",
            "DELEGATION: access control - an agent can ONLY assign to agents in its configured 'agents' allowlist (its children). An agent with no sub-agents can only post to the backlog. Human callers (general chat) can assign to any agent in the tenant.",

            "RECURRENCES: fire-once-then-skip - if the scheduler is behind by N intervals, only ONE task is created and next_fire_at jumps past missed windows (no backfill flood).",
            "RECURRENCES: target_agent_id=null creates BACKLOG tasks. Useful for 'daily QA sweep' where any idle agent with a schedule can pick up the work.",

            // --- Budget - operational detail; full model lives in concepts.budget_hierarchy ---
            "BUDGET_EXHAUSTED stop_reason includes a scope: 'tenant' (tenant ran out), 'agent' (this agent's own cap hit), or 'parent_reservation' (an ancestor refused the cascade reservation required to start the child)."
        ));

        return ToolExecutionResult.success(result);
    }

    /**
     * Builds the {@code concepts} section - the mental model the LLM needs to
     * reason about agents correctly. Six sub-sections:
     * <ol>
     *   <li>{@code what_an_agent_does} - the tool-calling loop lifecycle</li>
     *   <li>{@code activation_triggers} - the four paths that actually run an
     *       agent and what happens to the inbox on each</li>
     *   <li>{@code task_delegation_model} - assign dispatch behavior controlled by
     *       start_mode (execute=sync, in_progress=async fire-and-forget, pending=create-only),
     *       review-step semantics (in_review with null reviewer = user reviews from UI)</li>
     *   <li>{@code when_to_use_what} - decision tree between schedule_prompt,
     *       recurrence, assign, and execute</li>
     *   <li>{@code worker_pattern} - the canonical recipe for a self-draining
     *       worker agent</li>
     *   <li>{@code budget_hierarchy} - credit reservation cascade across
     *       parent/sub-agent chains</li>
     * </ol>
     */
    private Map<String, Object> buildConcepts() {
        Map<String, Object> concepts = new LinkedHashMap<>();

        // --- What an agent is and does ---
        concepts.put("what_an_agent_does",
            "When activated, an agent runs a tool-calling loop: read prompt → decide which tool to call → " +
            "execute tool → read result → iterate. The loop stops when the agent produces a final text response, " +
            "hits max_iterations, runs out of credit_budget, or its execution_timeout elapses. Everything else " +
            "(webhook URL, schedule, tasks, skills) is about HOW the loop gets triggered and WHAT prompt it starts with.");

        // --- Activation triggers ---
        Map<String, String> triggers = new LinkedHashMap<>();
        triggers.put("user_chat",
            "A human opens a conversation with the agent. Each message the human sends fires ONE " +
            "agent loop. The user message is the prompt. Inbox is NOT auto-surfaced - if you want the agent to " +
            "check its inbox here, instruct it explicitly in the system_prompt or the user message.");
        triggers.put("webhook",
            "An external HTTP POST (GET/PUT/PATCH/DELETE also supported) hits the agent's webhook URL. The " +
            "payload becomes the prompt. Each call fires ONE loop. Inbox is NOT auto-surfaced - same rule as " +
            "user_chat: instruct the agent to call inbox if you want delegated tasks processed on webhook hits.");
        triggers.put("schedule_cron",
            "At each cron tick, the platform builds the prompt. If the agent has pending inbox tasks, " +
            "review-queue items, OR (only when backlog_enabled=true) the tenant backlog has claimable items, a " +
            "DYNAMIC task briefing is sent as the prompt (top 15 inbox + top 10 review + top 5 backlog items), " +
            "REPLACING schedule_prompt for this fire. The backlog block is included ONLY for agents that opted in " +
            "via backlog_enabled; otherwise the wake-up still drives the agent's directly-assigned inbox + reviews. " +
            "If no work exists, schedule_prompt is sent unchanged as the baseline. This is the ONLY fully-" +
            "automatic inbox drainer - perfect for worker and reviewer agents alike.");
        triggers.put("parent_execute",
            "Another agent calls agent(action='execute', agent_id=<this>, prompt=…). Before the sub-agent's " +
            "loop starts, its system_prompt is automatically augmented with a 'Tasks in your inbox' section " +
            "listing pending tasks, claimable backlog, and recent outbox completions. The parent then waits " +
            "inline for the sub-agent to finish and receives its response.");
        concepts.put("activation_triggers", triggers);

        // --- Task delegation model ---
        concepts.put("task_delegation_model",
            "assign creates a task record. Whether it BLOCKS or returns immediately is controlled by " +
            "start_mode (see actions.assign for the full description): " +
            "(1) start_mode='execute' (default) - sync: dispatch the assignee + wait until terminal state, " +
            "return status='completed'|'failed'|'cancelled' with result/error_message inline. Use when you " +
            "need the answer in this turn. " +
            "(2) start_mode='in_progress' - async: dispatch the assignee + return immediately. Response " +
            "status reads 'pending' here; the worker promotes it to 'in_progress' on lock acquisition. Poll " +
            "agent(action='outbox', task_id=…) for the terminal result. " +
            "(3) start_mode='pending' - passive: create the row only, do NOT dispatch. The task waits in " +
            "'pending' until something else picks it up (the assignee's own schedule_cron/webhook firing, " +
            "or the user starting it manually). Use when posting work for an assignee that activates on its own. " +
            "BACKLOG MODE: assign without agent_id - the task goes to the tenant BACKLOG (status='pending', " +
            "assigned_to='backlog') and any agent with a schedule AND backlog_enabled=true can pick it up via " +
            "claim (backlog participation is opt-in per agent - by default no agent is offered the backlog, so " +
            "ensure a backlog-enabled worker exists or assign to a specific agent_id). start_mode is " +
            "ignored for backlog (always pending). " +
            "ACTIVATION WARNING: 'pending' and 'in_progress' modes only make sense if the assignee has a " +
            "wake-up path (schedule_cron OR webhook OR parent_execute call). Without one, the task sits " +
            "forever - fall back to start_mode='execute' or to backlog. " +
            "REVIEW STEP: every task passes through 'in_review' after task_complete/task_reject - NEVER " +
            "skipped. Reviewer identity depends on reviewer_agent_id: (a) SET → that agent reviews later, on " +
            "its own activation trigger; (b) NULL → the HUMAN USER (tenant owner) reviews manually, " +
            "outside this tool. " +
            "So if you assign without reviewer_agent_id, the task will eventually reach 'in_review' and stay " +
            "there until the user approves - that is the correct final resting state for the LLM; it does NOT " +
            "mean the task is stuck, and you have no action to take.");

        // --- When to use what (decision tree) ---
        Map<String, String> when = new LinkedHashMap<>();
        when.put("schedule_cron + schedule_prompt",
            "USE WHEN: stateless periodic routine, no audit trail needed (daily health check, hourly metrics " +
            "scan, morning report). Zero overhead - no task record is created. Each fire just re-runs the " +
            "prompt.");
        when.put("recurrence_create",
            "USE WHEN: periodic work that needs tracking (each tick = one auditable task with result readback, " +
            "retry options, cancellation of subtree). More heavyweight than schedule_prompt but gives you full " +
            "outbox visibility.");
        when.put("assign (one-off)",
            "USE WHEN: ad-hoc delegation of a single unit of work to another agent. The dispatch behavior " +
            "depends on start_mode: " +
            "'execute' (default) BLOCKS until terminal - same inline-result outcome as `execute` action, plus " +
            "creates a tracked task row. Use when you need the result inline. " +
            "'in_progress' fires the worker async and returns immediately - use for fire-and-forget. " +
            "'pending' creates the row without dispatching - use for posting work to an assignee that will " +
            "activate on its own (schedule_cron / webhook). " +
            "Backlog tasks (no agent_id) are never auto-executed; a worker with a schedule must claim them.");
        when.put("execute (synchronous)",
            "USE WHEN: you need the sub-agent's answer NOW, inline, but you do NOT want to create a task row. " +
            "Blocks the caller until the sub-agent finishes. Automatically surfaces the sub-agent's inbox in " +
            "the process - useful for 'worker, pick up all your pending tasks and report back'. " +
            "If you also want a tracked task row, use assign with start_mode='execute' (same blocking " +
            "behavior, plus a persistent record).");
        concepts.put("when_to_use_what", when);

        // --- Worker pattern ---
        concepts.put("worker_pattern",
            "CANONICAL RECIPE for a self-draining worker agent: " +
            "create the agent with (a) a system_prompt describing its role, (b) schedule_cron='*/10 * * * *' " +
            "(every 10 min, pick any cadence), (c) schedule_prompt='Check your inbox and process any pending " +
            "tasks. Complete each one via task_complete.', (d) allowed tools/resources appropriate for its job. " +
            "At each fire: if the inbox has work, the dynamic briefing lists the tasks and the agent works " +
            "through them; if the inbox is empty, schedule_prompt is sent as the baseline and the agent does " +
            "its own thing. The agent self-maintains without you ever needing to push it. Tune the cron " +
            "cadence to your latency tolerance: '* * * * *' (every minute) for low latency, '0 * * * *' " +
            "(hourly) for batch processing, etc.");

        // --- Budget hierarchy (recap) ---
        concepts.put("budget_hierarchy",
            "Every agent has a credit_budget (null = unlimited, number = cap). One credit = one LLM iteration " +
            "in the tool-calling loop. When agent A calls execute on sub-agent B, B's full credit_budget is " +
            "ATOMICALLY RESERVED from A and from every ancestor above A. A parent with credit_budget=100 can " +
            "never cumulatively spend more than 100 across all its descendants. Reservations settle on child " +
            "termination: consumed actual → consumed, unused → refunded. Read budget.free (effective " +
            "spendable) and budget.reserved_for_subagents (in-flight descendants holding credits) on any agent " +
            "response to see the current state.");

        return concepts;
    }

    /**
     * Returns the live model catalog grouped by provider. Exposed by
     * {@code agent(action='help_models')} (the default {@code help} action only
     * carries a slim redirect note now). Both {@code model_provider} and
     * {@code model_name} are optional on create/update - omit them to inherit
     * the platform default. Unknown pairs are silently substituted with the
     * default and reported as {@code model_substituted} in the response (no error).
     *
     * <p>Shape: {@code { providers: { openai: [gpt-5, ...], anthropic: [claude-opus-4-6, ...] },
     * pairs: [...], total_enabled, returned, note: "..." }}. The flat
     * {@code pairs} list is included so a prompt-constrained LLM with no map
     * support can still parse the options. List is truncated to
     * {@link #HELP_MODELS_MAX_ROWS} entries by global displayOrder ASC.
     */
    /** Hard cap on rows returned by help_models - keeps the payload bounded
     *  even when the platform admin enables a large catalog. The list is
     *  globally sorted by displayOrder ASC, so the slice is the top-N by
     *  priority (same ordering as the UI model picker). */
    private static final int HELP_MODELS_MAX_ROWS = 30;

    private Map<String, Object> buildAvailableModels(ToolExecutionContext context) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<AvailableModel> flat = modelCatalogService.listAvailableModels();
        // Hide CLI-bridge models (claude-code/codex/gemini-cli/mistral-vibe) the
        // caller would be blocked from using. This mirrors the dispatch-time
        // BridgeAccessGuard decision so the agent never sees - and never picks -
        // a model that would 403 at runtime. Non-bridge providers are untouched.
        flat = filterInaccessibleBridges(flat, context);
        int totalEnabled = flat.size();
        boolean truncated = totalEnabled > HELP_MODELS_MAX_ROWS;
        if (truncated) {
            flat = flat.subList(0, HELP_MODELS_MAX_ROWS);
        }

        Map<String, List<String>> byProvider = new LinkedHashMap<>();
        List<String> pairs = new ArrayList<>();
        for (AvailableModel am : flat) {
            String modelWithRank = am.modelId() + " (#" + am.displayOrder() + ")";
            byProvider.computeIfAbsent(am.provider(), k -> new ArrayList<>()).add(modelWithRank);
            pairs.add(am.provider() + "/" + am.modelId() + " (#" + am.displayOrder() + ")");
        }

        out.put("providers", byProvider);
        out.put("pairs", pairs);
        out.put("total_enabled", totalEnabled);
        out.put("returned", flat.size());
        out.put("note",
            "Top " + flat.size() + " of " + totalEnabled + " enabled (model_provider, model_name) pairs by priority "
            + "(same ordering as the configured catalog the platform admin manages). Only providers with a configured API key appear here. "
            + "BOTH FIELDS ARE OPTIONAL on create/update - omit them to use the platform default (the first pair below). "
            + "Passing an unknown pair never errors out: it's silently substituted with the default and the swap appears as 'model_substituted' in the response. "
            + "The list is managed by the platform admin - you cannot change it from this tool.");
        return out;
    }

    /**
     * Drops CLI-bridge models the caller cannot use from {@code models}, leaving
     * every other provider untouched. A "bridge" is one of the four CLI providers
     * (claude-code/codex/gemini-cli/mistral-vibe) that run on the admin's shared
     * subscription; access is gated per-bridge in auth-service (admin-only / quota
     * / allowlist). We evaluate the SAME decision the dispatch path enforces
     * ({@link BridgeAccessGuard#check}) so the help listing can never advertise a
     * model that would later be denied. The decision is cached per provider so a
     * 30-row catalog makes at most one auth-service round-trip per distinct bridge.
     */
    private List<AvailableModel> filterInaccessibleBridges(List<AvailableModel> models,
                                                           ToolExecutionContext context) {
        if (models == null || models.isEmpty()) {
            return models;
        }
        String userId = context != null ? context.tenantId() : null;
        String userRoles = credentialString(context, CRED_USER_ROLES);

        Map<String, Boolean> allowedByProvider = new HashMap<>();
        List<AvailableModel> out = new ArrayList<>(models.size());
        for (AvailableModel am : models) {
            String provider = am.provider();
            if (!BridgeAccessGuard.isBridgeProvider(provider)) {
                out.add(am); // non-bridge providers are never gated here
                continue;
            }
            boolean allowed = allowedByProvider.computeIfAbsent(
                provider.toLowerCase(Locale.ROOT),
                p -> isBridgeAllowedForCaller(userId, userRoles, p));
            if (allowed) {
                out.add(am);
            }
        }
        return out;
    }

    /**
     * @return true iff {@code provider} (a known CLI bridge) is usable by the
     * caller. Uses {@link BridgeAccessGuard#check} (no quota side-effect, fail-CLOSED
     * on an auth-service blip) when the guard is wired - the normal agent-service
     * case. Falls back to a plain admin-role check only when the guard bean is
     * absent (a deployment that doesn't ship bridges), so the listing still
     * degrades safely to "admins only".
     */
    private boolean isBridgeAllowedForCaller(String userId, String userRoles, String provider) {
        if (bridgeAccessGuard != null) {
            try {
                return bridgeAccessGuard.check(userId, userRoles, provider).allowed();
            } catch (Exception e) {
                // check() is already fail-closed; this guards against any unexpected throw
                // so a model-listing call never fails on the access lookup.
                log.debug("Bridge access check failed for provider={} user={}: {} - hiding the model",
                        provider, userId, e.getMessage());
                return false;
            }
        }
        return AdminRoleGuard.isAdmin(userRoles);
    }

    /** Reads a String value from the context credentials map, null-safe. */
    private String credentialString(ToolExecutionContext context, String key) {
        if (context == null || context.credentials() == null) {
            return null;
        }
        Object value = context.credentials().get(key);
        return value instanceof String s && !s.isBlank() ? s : null;
    }

    private Map<String, Object> buildParameterDocs() {
        Map<String, Object> params = new LinkedHashMap<>();

        // Core
        params.put("agent_id", "string UUID, required for get/update/delete/execute - Agent ID");
        params.put("name", "string, required for create - Agent name");
        params.put("system_prompt", "string, required for create - Instructions defining personality/behavior");
        params.put("description", "string - Short description");

        // Model
        params.put("model_provider", modelParamDoc("provider", resolveDefaultProvider(),
            "OPTIONAL - omit to use the platform default. To pick a specific provider, call agent(action='help_models') first to see the catalog (only configured providers, sorted by priority). Unknown values are silently substituted with the default; the swap appears as 'model_substituted' in the create/update response."));
        params.put("model_name", modelParamDoc("model", resolveDefaultModel(),
            "OPTIONAL - omit to use the platform default. To pick a specific model, call agent(action='help_models') first. Unknown (provider, model) pairs are silently substituted with the default; the swap appears as 'model_substituted' in the create/update response."));
        params.put("temperature", "number 0.0-2.0, default=" + defaults.getTemperature() + " - 0=deterministic, 1+=creative");
        params.put("max_tokens", "integer >0, default=" + defaults.getMaxTokens()
            + " - output tokens per turn; auto-capped to the model's real ceiling");
        params.put("max_iterations", "integer 1-1000, default=" + defaults.getMaxIterations() + " - Max tool call rounds");
        params.put("execution_timeout", "integer 10-7200s, default=" + defaults.getExecutionTimeout());

        // MCP Tools
        params.put("tools_mode", "'all' (default) | 'none' (no MCP tools) | 'custom' - Which catalog/MCP tools the agent can use");
        params.put("tools", "array - MCP tool IDs when tools_mode='custom'. Max 30. Find IDs via catalog(action='search')");

        // Resource access (defaults to [] on create = no access, grant explicitly)
        params.put("workflows", "array - default=[] (none). ['uuid',...]=specific workflow IDs. On update: REPLACES entire list");
        params.put("applications", "array - default=[] (none). ['uuid',...]=specific marketplace app IDs. On update: REPLACES entire list");
        params.put("tables", "array - default=[] (none). [1,2,3]=specific table IDs (integers). On update: REPLACES entire list");
        params.put("interfaces", "array - default=[] (none). ['uuid',...]=specific interface IDs. On update: REPLACES entire list");
        params.put("agents", "array - default=[] (none). ['uuid',...]=specific sub-agent IDs. On update: REPLACES entire list");
        params.put("web_search", "boolean, default=true - Enable web search capability");

        // Per-resource access modes
        params.put("table_access_mode", "'write' (default, full CRUD) or 'read' (query_rows/get/list only, no create/update/delete)");
        params.put("workflow_access_mode", "'write' (default) or 'read' (get/list/describe only, no create/edit/execute)");
        params.put("interface_access_mode", "'write' (default) or 'read' (get/list only)");
        params.put("agent_access_mode", "'write' (default) or 'read' (get/list only, no create/execute)");
        params.put("application_access_mode", "'write' (default) or 'read' (search/get only, no create/acquire/execute)");
        params.put("skill_access_mode", "'write' (default) or 'read' (get/list only, no create/update/delete)");
        params.put("file_access_mode", "'write' (default) or 'read' (files list/get/view/visualize only - blocks create_folder/move_to_folder). Independent of the 'files' allow-list scope");
        // Per-resource GRANT scope (none/all/custom) - the only way to express grant='all'
        params.put("workflows_grant", "'none' | 'all' | 'custom'. 'all'=EVERY workflow (list ignored), 'custom'=only the IDs in 'workflows'. Omit to derive from the list (empty=none, non-empty=custom)");
        params.put("applications_grant", "'none' | 'all' | 'custom'. 'all'=EVERY application (list ignored), 'custom'=only the IDs in 'applications'. Omit to derive from the list");
        params.put("tables_grant", "'none' | 'all' | 'custom'. 'all'=EVERY table (list ignored), 'custom'=only the IDs in 'tables'. Omit to derive from the list");
        params.put("interfaces_grant", "'none' | 'all' | 'custom'. 'all'=EVERY interface (list ignored), 'custom'=only the IDs in 'interfaces'. Omit to derive from the list");
        params.put("agents_grant", "'none' | 'all' | 'custom'. 'all'=EVERY sub-agent (list ignored), 'custom'=only the IDs in 'agents'. Omit to derive from the list");

        // Skills
        params.put("skill_ids", "array of UUIDs - REPLACES all skills. Max 10. Use skill(action='assign') to ADD without replacing");

        // Budget
        params.put("credit_budget", "number|null - Max credits (1 per LLM iteration). null=unlimited (default). " +
            "ENFORCED HIERARCHICALLY: when this agent spawns a sub-agent, the sub-agent's full budget is atomically " +
            "reserved from every ancestor's free budget. A child of 50 cannot run under a parent with 30 free credits. " +
            "MIN RECOMMENDED: at least 5× max_iterations. A real LLM turn consumes more than 1 credit in practice, " +
            "so credit_budget <= max_iterations almost always stops with BUDGET_EXHAUSTED before the first turn " +
            "completes. create/update will return a 'warnings' field flagging this mismatch - read it and re-call " +
            "with a realistic budget before running execute.");
        params.put("budget_reset_mode", "'cumulative' (default) - Budget never resets");

        // Webhook integration
        params.put("webhook_enabled", "boolean, default=false - Create a POST webhook endpoint. Response includes webhook_url + webhook_curl");
        params.put("webhook_memory", "boolean, default=false - Webhook uses conversation memory (agent sees previous webhook messages)");

        // Schedule integration
        params.put("schedule_cron", "string - Cron expression for recurring execution. Examples: '0 9 * * *'=daily 9AM, '0 */2 * * *'=every 2h, '0 9 * * 1'=Monday 9AM");
        params.put("schedule_timezone", "string, default='UTC' - e.g. 'Europe/Paris', 'America/New_York'");
        params.put("schedule_max_executions", "integer|null - Limit scheduled runs. null=unlimited");
        params.put("schedule_prompt", "string - Message sent to agent at each scheduled execution");
        params.put("schedule_memory", "boolean, default=false - Schedule uses conversation memory (agent sees previous scheduled runs)");

        // Other
        params.put("datasource_id", "integer - Table ID for data context");
        params.put("conversation_id", "string UUID - Conversation ID to link agent to");
        params.put("is_public", "boolean, default=false");
        params.put("is_active", "boolean, default=true");
        params.put("backlog_enabled", "boolean, default=false - Opt the agent into the shared task backlog. " +
            "When true the agent is offered claimable backlog items on its scheduled wake-up and in its system " +
            "prompt, and may call claim/backlog. When false (default) it only works tasks assigned directly to it " +
            "(inbox) and its reviews. Make a generalist 'backlog worker' true; leave specialized agents false so " +
            "they are never pulled onto work outside their remit.");
        params.put("compaction_enabled", "boolean, optional - Per-agent context compaction (the COLD-summary pass " +
            "that shrinks long conversations to stay within the context window). true = compact this agent's " +
            "conversations; false = never compact; omit = inherit (the conversation-level setting, then the platform " +
            "default). Set on create or update (for: create, update).");
        params.put("compaction_after_turns", "integer >= 1, optional - Compaction cadence: how many new turns may " +
            "accumulate before this agent's conversation is re-summarised. Omit = inherit (conversation setting, then " +
            "platform default). Only takes effect while compaction is enabled (for: create, update).");

        // List-specific
        params.put("limit", "integer, default=25 - Max results to return (for list)");
        params.put("offset", "integer, default=0 - Pagination offset (for list)");

        // Execute-specific
        params.put("prompt", "string, required for execute - Task to send to sub-agent");
        params.put("context", "string - Background data to prepend to prompt (for execute)");
        params.put("memory", "boolean, default=true - Sub-agent sees its conversation history from previous executions (last 20 messages, user/assistant only). Set false for stateless one-shot execution (for execute)");
        params.put("timeout", "integer 10-300s, default=120 - Sub-agent execution timeout");

        // Share-specific
        params.put("share_mode", "'read' (default) or 'readwrite' - Access level for the shared link (for share)");

        // Task delegation params
        params.put("title", "string, required for assign/recurrence_create - Task title (max 500 chars)");
        params.put("instructions", "string, required for assign/recurrence_create - Full task instructions (max 50KB)");
        params.put("start_mode",
            "string, default='execute' (for assign) - Execution timing. " +
            "'pending' = create the row in pending status, do NOT trigger the worker (passive: " +
            "task waits for an explicit pickup via inbox/claim or the assignee's own activation). " +
            "'in_progress' = create + dispatch the worker async, return immediately without waiting. " +
            "'execute' = create + dispatch + sync-wait until terminal state, return result/error_message inline. " +
            "Backlog (no agent_id) is always 'pending' regardless. " +
            "ACTIVATION WARNING: 'pending' and 'in_progress' only make sense if the assignee has a " +
            "wake-up path (schedule_cron / webhook / parent_execute) - without one, the task sits forever. " +
            "Fall back to 'execute' or to backlog when no wake-up path exists.");
        params.put("reviewer_agent_id",
            "string UUID|null (for assign, task_update) - Agent that reviews after task_complete. " +
            "If null, the human user (tenant owner) reviews it outside this tool.");
        params.put("max_review_attempts",
            "integer [1, 20], default=3 (for assign, task_update) - Reviewer reject cap. " +
            "Once hit, the task is AUTO-FAILED with the last reviewer feedback as error_message.");
        params.put("task_id", "string UUID, required for inbox(single)/task_complete/task_reject/task_update/task_cancel/claim/task_approve/task_reject_review");
        params.put("task_context", "object - Arbitrary JSON blob passed with the task. Plain map, no schema.");
        params.put("result", "string, required for task_complete - Completion result text (max 50KB)");
        params.put("reason", "string - Human-readable reason (for task_reject/task_cancel/task_reject_review, max 2KB for reject_review)");
        params.put("due_by", "string ISO-8601 - Optional deadline (for assign), e.g. '2026-04-15T12:00:00Z'");
        params.put("priority", "'low'|'normal' (default)|'high'|'urgent' (for assign, task_update, recurrence_create)");
        params.put("status", "'pending'|'in_progress'|'in_review'|'completed'|'failed'|'cancelled' - Filter for outbox list (inbox always returns pending/in_progress). Also accepted by task_update to transition any→any. A task the human moved to the board's trash reads as 'deleted' (and is auto-removed ~30 days later); it never appears in inbox/backlog/review, and 'deleted' is NOT a valid task_update target.");
        params.put("cron", "string - Cron expression for recurring tasks (for recurrence_create/update)");
        params.put("timezone", "string, default='UTC' - IANA timezone for the cron (for recurrence_create)");
        params.put("recurrence_id", "string UUID - Recurrence ID (for recurrence_update/delete)");
        params.put("target_agent_id", "string UUID|null - Agent receiving recurrence-created tasks. null=backlog");
        params.put("scope", "'created_by_me' (default) | 'targeting_me' | 'all_in_tenant' (for recurrence_list)");
        params.put("enabled", "boolean - Enable/disable a recurrence (for recurrence_update)");

        return params;
    }

    /**
     * Documents the key response fields returned by create/get/update so the LLM
     * knows the accessor shape without having to probe with a get call.
     */
    private Map<String, Object> buildResponseShape() {
        Map<String, Object> shape = new LinkedHashMap<>();

        Map<String, Object> budget = new LinkedHashMap<>();
        budget.put("unlimited", "boolean - true when credit_budget is null (no cap)");
        budget.put("total", "number|null - configured credit_budget, null when unlimited");
        budget.put("consumed", "number - lifetime credits spent after any automatic reset");
        budget.put("reserved_for_subagents",
            "number - credits currently locked by in-flight sub-agent cascade reservations. " +
            "Non-zero means a descendant is running and holding budget from this agent. " +
            "Returns to 0 once all descendants finish (settle) or are refunded (failure).");
        budget.put("free",
            "number|null - total - consumed - reserved_for_subagents, clamped at 0. " +
            "null when unlimited. This is the effective spendable amount: if free < a child's " +
            "credit_budget the spawn will fail with BUDGET_EXHAUSTED scope=parent_reservation.");
        budget.put("reset_mode", "string - 'cumulative' (never resets), 'weekly', or 'monthly'");
        budget.put("last_reset",
            "string|absent - ISO-8601 timestamp of the last automatic reset (weekly/monthly only). " +
            "Key is omitted entirely for agents that have never been reset.");
        shape.put("budget",
            "nested object - unified budget view across create/get/update. " +
            "Keys: " + budget);

        shape.put("resources",
            "object - summary of which resource categories the agent can access. " +
            "Internal categories with [] (no access) or absent are NOT listed; only categories " +
            "with explicit grants appear. Skills and catalog are always listed (always available). " +
            "Includes mcp_tools_mode ('all'|'none'|'custom').");

        shape.put("tools_config",
            "object - shape depends on the action. On GET: raw configuration with ALWAYS-PRESENT keys " +
            "mode ('all'|'none'|'custom'), workflows[], tables[], interfaces[], agents[], applications[] " +
            "(the 5 internal lists default to [] = no access when omitted - absent === [] security rule), " +
            "plus OPTIONAL tools[] (when mode='custom'), webSearch (boolean, default true), and the " +
            "*AccessMode strings (table/workflow/interface/agent/application/skill - 'read'|'write', " +
            "default 'write'). On CREATE/UPDATE: a SUMMARY of the applied config - mode, the <family>Grant " +
            "keys, <family>_count (size of each non-empty id list), tools_count, webSearch; the raw id " +
            "lists and *AccessMode keys are NOT echoed (they were applied - read them back with get). " +
            "Parameter names differ from response keys (e.g. 'tools_mode' → 'mode', 'web_search' → " +
            "'webSearch') - always use parameter names in create/update.");

        shape.put("webhook_url",
            "string - present when webhook_enabled=true. POST endpoint ready to call.");

        shape.put("webhook_curl",
            "string - present when webhook_enabled=true. Ready-to-copy curl example.");

        return shape;
    }

    private Map<String, Object> buildExamples() {
        Map<String, Object> examples = new LinkedHashMap<>();

        examples.put("basic", Map.of(
            "call", "agent(action='create', params={name: 'Assistant', system_prompt: 'You are a helpful assistant.'})"
        ));

        examples.put("with_webhook", Map.of(
            "description", "Create agent with webhook endpoint - returns URL + curl example",
            "call", "agent(action='create', params={name: 'API Agent', system_prompt: 'Process incoming requests.', webhook_enabled: true, webhook_memory: true})",
            "response_includes", "webhook_url, webhook_curl (ready to copy/paste)"
        ));

        examples.put("with_schedule", Map.of(
            "description", "Create agent that runs daily at 9AM Paris time",
            "call", "agent(action='create', params={name: 'Daily Reporter', system_prompt: 'Generate reports.', schedule_cron: '0 9 * * *', schedule_prompt: 'Generate the daily report', schedule_timezone: 'Europe/Paris', schedule_memory: true})"
        ));

        examples.put("worker_agent", Map.of(
            "description", "CANONICAL WORKER RECIPE - create a worker with schedule_cron so it periodically " +
                "drains its inbox. For fire-and-forget delegation pass start_mode='in_progress': assign then " +
                "returns immediately with a task_id (status='pending' until the worker picks it up at the next " +
                "schedule tick). WITHOUT start_mode, assign defaults to 'execute' and BLOCKS until the task is " +
                "terminal. Poll outbox(task_id=…) to read the result.",
            "call", "agent(action='create', params={name: 'Research Worker', system_prompt: 'You are a research assistant. Investigate topics and report findings.', tools_mode: 'all', schedule_cron: '*/5 * * * *', schedule_prompt: 'Check your inbox and process pending tasks via task_complete.'})",
            "usage", "agent(action='assign', agent_id='<worker-uuid>', title='Research X', instructions='...', " +
                "start_mode='in_progress') returns immediately with {task_id, status:'pending'}. Later: " +
                "agent(action='outbox', task_id='<id>') to read the result once the worker has completed and " +
                "any reviewer has approved."
        ));

        examples.put("full_featured", Map.of(
            "description", "Agent with restricted resources, webhook, schedule, and skills",
            "call", "agent(action='create', params={name: 'Sales Bot', system_prompt: 'You handle sales inquiries.', model_provider: 'google', model_name: 'gemini-3-flash-preview', tools_mode: 'none', tables: [1, 2], workflows: ['<wf-uuid>'], web_search: true, skill_ids: ['<skill-uuid>'], webhook_enabled: true, schedule_cron: '0 8 * * 1-5', schedule_prompt: 'Check new leads', credit_budget: 100})"
        ));

        examples.put("restricted_tools", Map.of(
            "call", "agent(action='create', params={name: 'GitHub Helper', system_prompt: '...', tools_mode: 'custom', tools: ['github:create_issue', 'github:list_repos']})"
        ));

        examples.put("list_agents", Map.of(
            "call", "agent(action='list', params={limit: 10, offset: 0})"
        ));

        examples.put("update_add_webhook", Map.of(
            "description", "Add webhook to an existing agent",
            "call", "agent(action='update', params={agent_id: '<uuid>', webhook_enabled: true, webhook_memory: true})"
        ));

        examples.put("execute_sub_agent_with_memory", Map.of(
            "description", "Execute sub-agent with memory (default) - agent remembers previous conversations",
            "call", "agent(action='execute', params={agent_id: '<uuid>', prompt: 'Continue the analysis from last time'})"
        ));

        examples.put("execute_sub_agent_stateless", Map.of(
            "description", "Execute sub-agent without memory - fresh start, no history",
            "call", "agent(action='execute', params={agent_id: '<uuid>', prompt: 'Analyse this dataset', context: 'Q4 data...', memory: false, timeout: 60})"
        ));

        examples.put("get_history_sub_agent", Map.of(
            "description", "Read a sub-agent's conversation history",
            "call", "agent(action='get_history', params={agent_id: '<uuid>'})"
        ));

        examples.put("get_history_self", Map.of(
            "description", "Read your own conversation history",
            "call", "agent(action='get_history')"
        ));

        examples.put("search_messages_self", Map.of(
            "description", "Search your own conversation for prior mentions of a topic",
            "call", "agent(action='search_messages', params={scope: 'self', query: 'invoice INV-3041'})"
        ));

        examples.put("search_messages_children", Map.of(
            "description", "Search across all sub-agents in your allowlist (e.g. find which worker handled a client)",
            "call", "agent(action='search_messages', params={scope: 'children', query: 'client Acme OR Acme Corp'})"
        ));

        examples.put("search_messages_filtered", Map.of(
            "description", "Search assistant replies in a date range - useful for audit / weekly recap",
            "call", "agent(action='search_messages', params={scope: 'all_visible', query: 'refund', " +
                    "roles: ['ASSISTANT'], since: '2026-04-01T00:00:00Z', until: '2026-04-30T23:59:59Z', limit: 10})"
        ));

        examples.put("task_get_context_review", Map.of(
            "description", "Reviewer's first call after review_inbox - see executions metadata to decide",
            "call", "agent(action='task_get_context', task_id='<task-uuid>')"
        ));

        examples.put("task_get_execution_zoom", Map.of(
            "description", "Drill into a specific execution when the metadata raised doubt",
            "call", "agent(action='task_get_execution', task_id='<task-uuid>', " +
                    "execution_id='<exec-uuid>', include_tool_calls=true, limit=50)"
        ));

        examples.put("task_review_full_flow", Map.of(
            "description", "Full reviewer flow - discover, inspect, approve",
            "call", "1) agent(action='review_inbox') → list of in_review tasks; " +
                    "2) agent(action='task_get_context', task_id=<id>) → see executions order + counters; " +
                    "3) (if doubt) agent(action='task_get_execution', task_id=<id>, execution_id=<id>); " +
                    "4) agent(action='task_approve', task_id=<id>) OR agent(action='task_reject_review', task_id=<id>, reason='...')"
        ));

        examples.put("share_conversation", Map.of(
            "description", "Share own conversation via public link (read-only)",
            "call", "agent(action='share')"
        ));

        examples.put("share_sub_agent", Map.of(
            "description", "Share a sub-agent's conversation via public link",
            "call", "agent(action='share', params={agent_id: '<uuid>', share_mode: 'read'})"
        ));

        examples.put("unshare_conversation", Map.of(
            "description", "Disable sharing on own conversation",
            "call", "agent(action='unshare')"
        ));

        examples.put("unshare_sub_agent", Map.of(
            "description", "Disable sharing on a sub-agent's conversation",
            "call", "agent(action='unshare', params={agent_id: '<uuid>'})"
        ));

        examples.put("refresh_share_link", Map.of(
            "description", "Regenerate share link (invalidates old URL)",
            "call", "agent(action='refresh_share', params={agent_id: '<uuid>'})"
        ));

        // ==================== Task Delegation Examples ====================

        examples.put("task_lifecycle", Map.of(
            "description", "Task lifecycle. Default start_mode='execute': assign BLOCKS until the task is " +
                    "terminal and returns result/error_message inline. For the async flow below, pass " +
                    "start_mode='in_progress' - assign then returns immediately with a task_id and the " +
                    "assignee runs later on its own activation trigger. Every task passes through 'in_review' " +
                    "before reaching 'completed' - the reviewer is either a configured reviewer_agent_id or, " +
                    "when none is set, the human user (tenant owner). Poll outbox to read results.",
            "steps", List.of(
                "1. CALLER: agent(action='assign', agent_id='<worker>', title='Research Q4', instructions='Summarize Q4 results', start_mode='in_progress') - returns {task_id, status:'pending'} immediately (the worker promotes it to in_progress when it picks the task up)",
                "2. Later, on the worker's schedule_cron fire (or webhook hit, or parent_execute), the worker reads its inbox and runs the task",
                "3. WORKER: agent(action='task_complete', task_id='<id>', result='Q4 revenue was $4.2M...')",
                "4. Task → in_review (ALWAYS). If reviewer_agent_id set → reviewer agent runs on its own activation " +
                    "trigger and approves/rejects. If NOT set → the human user (tenant owner) is the implicit " +
                    "reviewer and resolves the task outside this tool - you (the LLM) have no action to take here",
                "5. CALLER polls agent(action='outbox', task_id='<id>') to read the terminal state: " +
                    "status='completed' if a reviewer agent approved, status='in_review' if awaiting human user " +
                    "approval (this is CORRECT and terminal for the LLM - the user resolves it)"
            )
        ));

        examples.put("assign_to_specific_agent", Map.of(
            "description", "Delegate work to a named agent",
            "call", "agent(action='assign', params={agent_id: '<worker-uuid>', title: 'Review PR #123', instructions: 'Check security + performance on the auth refactor', priority: 'high', due_by: '2026-04-15T12:00:00Z'})"
        ));

        examples.put("assign_to_backlog", Map.of(
            "description", "Create an unassigned backlog task - any agent in the tenant may claim it",
            "call", "agent(action='assign', params={title: 'Triage incoming support tickets', instructions: 'Sort inbox tickets by urgency, tag and route', priority: 'normal'})"
        ));

        examples.put("claim_from_backlog", Map.of(
            "description", "Pick up a backlog item (first-come-first-served)",
            "call", "agent(action='backlog') // list available\nagent(action='claim', params={task_id: '<uuid>'})"
        ));

        examples.put("reject_task", Map.of(
            "description", "Reject a task you can't handle",
            "call", "agent(action='task_reject', params={task_id: '<uuid>', reason: 'Outside my domain - needs the data-science agent'})"
        ));

        examples.put("cancel_task_tree", Map.of(
            "description", "Cancel a task AND every descendant it spawned in one atomic call",
            "call", "agent(action='task_cancel', params={task_id: '<parent-uuid>', reason: 'Scope changed, no longer needed'})"
        ));

        examples.put("read_sub_agent_memory", Map.of(
            "description", "Parent reads child agent's conversation/reasoning directly - no new tool needed",
            "call", "agent(action='get_history', params={agent_id: '<child-agent-uuid>'})",
            "note", "Works for any child in your 'agents' allowlist. Combined with outbox(task_id=...), gives parents full visibility into both results AND reasoning."
        ));

        examples.put("create_recurrence_daily", Map.of(
            "description", "Auto-create a task every morning for a specific agent",
            "call", "agent(action='recurrence_create', params={title: 'Daily KPI report', instructions: 'Pull yesterdays KPIs and post to Slack', cron: '0 8 * * *', timezone: 'Europe/Paris', target_agent_id: '<reporter-uuid>', priority: 'normal'})"
        ));

        examples.put("create_recurrence_backlog", Map.of(
            "description", "Auto-create backlog tasks every hour - any agent can claim them",
            "call", "agent(action='recurrence_create', params={title: 'Hourly queue sweep', instructions: 'Check stuck jobs in the processing queue', cron: '0 * * * *', timezone: 'UTC'})"
        ));

        examples.put("disable_recurrence", Map.of(
            "description", "Temporarily stop a recurrence without deleting it",
            "call", "agent(action='recurrence_update', params={recurrence_id: '<uuid>', enabled: false})"
        ));

        return examples;
    }
}
