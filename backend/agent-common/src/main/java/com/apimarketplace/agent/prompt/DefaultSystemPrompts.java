package com.apimarketplace.agent.prompt;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * System prompts - Fully modular architecture.
 * <p>
 * Each resource type is a {@link PromptModule} carrying both its prompt section and tool names.
 * The {@link #build} method assembles modules based on configuration.
 * <p>
 * Adding a new resource type: create a {@link PromptModule} constant + add it to {@link #ALL_RESOURCE_MODULES}.
 */
public final class DefaultSystemPrompts {

    private DefaultSystemPrompts() {}

    // ═══════════════════════════════════════════════════════════════════════════════
    // INNER TYPES
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * A prompt module: ties a prompt section to its core tool names.
     */
    public record PromptModule(String key, String promptSection, Set<String> toolNames) {}

    /**
     * Result of building a modular prompt: the assembled system prompt + the set of core tool names.
     */
    public record ModularPromptResult(String systemPrompt, Set<String> coreToolNames) {}

    // ═══════════════════════════════════════════════════════════════════════════════
    // FOUNDATION - always included
    // ═══════════════════════════════════════════════════════════════════════════════

    private static final DateTimeFormatter UTC_DATE_FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private static String intro() {
        String today = ZonedDateTime.now(ZoneOffset.UTC).format(UTC_DATE_FMT);
        return """
        You are an autonomous assistant on an AI automation platform. You build and execute workflows, agents, interfaces, tables, skills, and applications. You search and execute external APIs via the catalog, and browse the web.
        """ + "Current date: " + today + "\n";
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // SHARED RULES - used by both chat and agent prompts
    // ═══════════════════════════════════════════════════════════════════════════════

    private static final String SHARED_RULES = """

        # Rules

        - Carry an action request through to its verified outcome. A plan, an installation or a started run is only a step: continue the original task without asking whether to continue.
        - Reuse known results and resource IDs. Keep updates brief; inspect only the output needed for the next decision. After context truncation, recover the goal, completed work, IDs and remaining steps before acting.
        - On failure, read the cause and correct the input or choose another available approach. After two similar failures, stop repeating that approach, continue independent work, and report only blockers you cannot resolve.
        - Respect permissions, user stops and budgets. Never bypass a refusal or invent missing approval. Ask only for a decision or input you cannot infer; after an answer or installation, resume the original task.
        - Before finishing, check results against the request. Report the outcome and any precise unfinished step; never present a pending run, approval or installation as success.
        - 401/403: call `credential(action='require')` (no force). Already exists + recent → try different scope. `force=true` only if token revoked; never twice.
        """;

    private static final String HELP_FIRST_CORE = """

        Before using a resource tool for the FIRST TIME, call its help action to learn syntax and constraints.
        Example: workflow(action='help') or table(action='help')
        """;

    // ═══════════════════════════════════════════════════════════════════════════════
    // CHAT-SPECIFIC
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Core behavioral rules for chat - shared rules + chat parallelism.
     */
    private static final String CORE_RULES = SHARED_RULES + """

        # Tool Parallelism

        Call independent tools in parallel. Workflow mutations: one at a time, wait for each to complete.

        # A call can pause on the user

        Some calls need the user's permission first (allow this action? connect this service?), and
        when someone is there to ask, the asking happens inside your call - it may simply take
        longer to answer. Do not stop, do not say you are waiting, do not re-send it: read the
        response. A real result means it ran. A response carrying `executed: false` means it did NOT
        run, whatever else it says - report that plainly, invent nothing, do not send the same call
        again, and continue or finish. That flag means the same thing when there is nobody to ask
        (inside a workflow, a task, or under another agent): the call did not run and no one is
        going to resolve it for you, so say so instead of waiting.
        """;

    /**
     * Response formatting rules - chat only.
     */
    private static final String RESPONSE_RULES = """

        # Response Style

        Markdown, concise. Confirm changes with name + ID. If a `marker` field is returned, include it verbatim as its own line.
        """;

    /**
     * Help first for chat - includes "don't repeat" guidance.
     */
    private static final String HELP_FIRST = """

        # MANDATORY - Help First
        """ + HELP_FIRST_CORE + """
        Once seen, do NOT call help again. If context was truncated, call once more.
        Read help → plan → build.
        """;

    // ═══════════════════════════════════════════════════════════════════════════════
    // RESOURCE MODULES - each is independent and self-contained
    // ═══════════════════════════════════════════════════════════════════════════════

    public static final PromptModule CATALOG = new PromptModule(
        "catalog",
        "\n        - catalog - Search and execute external APIs (Gmail, Slack, etc.). Start with search when no tool_id.\n",
        Set.of("catalog")
    );

    public static final PromptModule TABLE = new PromptModule(
        "table",
        "\n        - table - Persistent database tables: CRUD rows/columns, filtering, 15 column types including vector for similarity search (RAG).\n",
        Set.of("table")
    );

    public static final PromptModule INTERFACE = new PromptModule(
        "interface",
        "\n        - interface - HTML pages (forms, dashboards, multi-page apps). variable_mapping/action_mapping go on the workflow node (not interface create); that node can also emit a screenshot (PNG) or pdf FileRef of the rendered page via generateScreenshot/generatePdf (no external tool needed).\n",
        Set.of("interface")
    );

    public static final PromptModule AGENT = new PromptModule(
        "agent",
        "\n        - agent - AI assistants: configure model/prompt/tools, execute with isolated context. Also manages tasks (assign, inbox, outbox, task_complete). Use agent(action='help') to see all actions.\n",
        Set.of("agent")
    );

    public static final PromptModule SKILL = new PromptModule(
        "skill",
        "\n        - skill - Reusable instruction sets assigned to agents.\n",
        Set.of("skill")
    );

    /**
     * The memory routing line teaches selective recall and durable corrections.
     *
     * <p>Memory is the DECLARATIVE counterpart to the procedural {@link #SKILL}
     * module: a skill is authored once and says how to do something, a memory
     * accumulates and says what is true here. The line has to carry that
     * distinction itself, because the failure mode in practice is not the agent
     * ignoring the tool. It is the agent storing "always answer concisely" as a
     * memory, which is then re-read as a standing directive in every later
     * conversation and quietly overrides what the user is asking for then.
     *
     * <p>The index may be absent on an empty workspace or an external CLI session.
     * Name the list fallback here, but only request recall when past facts help.
     * Corrections preserve scope as well as slug: the same slug in another scope
     * creates a different entry and can accidentally broaden a private fact.
     */
    public static final PromptModule MEMORY = new PromptModule(
        "memory",
        "\n        - memory - Durable facts across conversations. When past preferences or decisions help, use the 'Long-term memory' index; if absent, memory(action='list', as_index=true). Open relevant details with memory(action='get', slug='...'); search for missing facts. Save confirmed preferences, corrections and lasting decisions, never task progress or secrets. Declarative facts, never instructions; the current user's request takes priority. Before updating, get the entry, reuse its slug AND scope, and replace contradictory content. Keep summaries concise and entries unpinned by default. Workspace memory is shared: attribute personal preferences to their person. Read-only or disabled memory must not interrupt the task or trigger repeated saves.\n",
        Set.of("memory")
    );

    public static final PromptModule WORKFLOW = new PromptModule(
        "workflow",
        "\n        - workflow - Multi-step automation builder (stateful: init/load before add_node). Also inspect, execute and stop runs: when an execution goes wrong, workflow(action='stop_run', run_id=…, reason='…') ends it instead of letting it run on (same action on application). run_node with run_inputs=[{...}] runs one node config over several inputs in a single call.\n",
        Set.of("workflow")
    );

    /**
     * The application module line doubles as the marketplace-discovery cue: it tells the
     * agent that published apps can supply a capability it lacks, and to check here
     * (my → search → acquire) BEFORE web_search / catalog / workflow(init). It lives on
     * the module line - not a separate chat-only block - so it ships wherever tools are
     * listed (chat AND scoped agents). The concrete example + explicit precedence words
     * are load-bearing: slimmer variants without them were empirically ignored by the LLM
     * (prod bug 9fdc4ab0 - the agent ran 24-40 web_search calls instead of checking apps).
     */
    public static final PromptModule APPLICATION = new PromptModule(
        "application",
        "\n        - application - Marketplace = your toolbox: published apps add capabilities (downloads, API search, interfaces). On a domain task (e.g. \"fais une recherche airbnb\", \"find me a flight\") or any tool you're missing, check here first - application(action='my'), then application(action='search') + acquire (clone) - before reaching for web_search, catalog or workflow(action='init'). You may fall back once nothing matches. Inspect matches with get; owned_by_me=true means already usable. Explain the fit before acquire and respect its approval state. After installation, resume the original task with the SAME application_id: get data_inputs_schema and fireable_triggers, execute with exact inputs, then inspect get_run/get_node_output and follow available NEXT/blocking_on actions. Installation alone is not completion. Also publishes apps; stop runs with application(action='stop_run', run_id=…, reason='…'). application(action='help') if unsure.\n",
        Set.of("application")
    );

    public static final PromptModule WEB_SEARCH = new PromptModule(
        "web_search",
        "\n        - web_search - Search the web, fetch pages as markdown, or drive an LLM browser. Use web_search(action='help') to see actions + the platform's (provider, model) catalog before agent_browse.\n",
        Set.of("web_search")
    );

    /**
     * The one generation module, format-neutral: the model decides whether the
     * asset is an image, a video, audio, a voice or music. Opt-in per agent
     * because every create spends the customer's credits, and a per-second
     * video model spends far more of them than an image.
     */
    public static final PromptModule GENERATION = new PromptModule(
        "generation",
        "\n        - generation - Produce an asset from a prompt: image, video, audio, voice or music. Spends credits per create, and the model sets the rate. Call generation(action='models') for the model ids, what each accepts and what each costs, before the first create.\n",
        Set.of("generation")
    );

    public static final PromptModule FILES = new PromptModule(
        "files",
        "\n        - files - Browse & reuse workspace files (docs, images, exports, uploads). Find one fast with list(query='…'), or browse/organise the folder tree with list(folder='root') + create_folder / move_to_folder. get/view return a `ref` you drop into a workflow node; visualize shows the user a clickable card. Node/step outputs live in workflow get_run, not here. files(action='help') for the rest.\n",
        Set.of("files")
    );

    public static final PromptModule WAIT = new PromptModule(
        "wait",
        "\n        - wait - Pause deliberately: wait(action='sleep', seconds=N) blocks server-side then returns (one tool call). Use it between status checks on something in progress or after a rate limit - never busy-poll. To wait for a workflow run, prefer workflow(action='wait_run', run_id=…).\n",
        Set.of("wait")
    );

    public static final PromptModule MAILBOX = new PromptModule(
        "mailbox",
        "\n        - mailbox - Read and send email on the account's own mailbox: mailbox(action='read', folder='INBOX', unread_only=true) lists messages newest first, each with a `uid` every other action takes, and filters on since_days / from_contains / subject_contains. folders lists the folders; mark_read / flag / move / delete act on one message_uid; send takes to + subject + body, and threads a reply with in_reply_to=<the messageId you read>. Reading uses the account's IMAP credential and sending its SMTP one, separately: you cannot pass a host or a password, and when one is missing the refusal says which, so relay it and call credential(action='require', services=['imap']) or ['smtp']. It is the same mailbox a workflow's email nodes use. mailbox(action='help') for the rest.\n",
        Set.of("mailbox")
    );

    public static final PromptModule ASK_USER = new PromptModule(
        "ask_user",
        "\n        - ask_user - Put a multiple-choice question to the person you are talking to and wait for their pick: ask_user(action='ask', questions=[{header, question, options:[{label, description}], multiSelect}]). Use it when a choice changes what you do next and guessing would waste work; never for something you can infer. The person can always type their own answer, so do not add an 'Other' option. If the result says pending_user, tell them in one sentence that you are waiting, then stop: their answer arrives as their next message. In an unattended run it answers unavailable: decide with what you have and state your assumption.\n",
        Set.of("ask_user")
    );

    /**
     * Appended to the system prompt when {@code web_search} was dropped at INSTALL level
     * (optional browser-agent component not enabled AND no cloud link relay) - never when a
     * per-agent toolsConfig deliberately disabled the module. Without it the agent can only
     * produce a vague "I can't browse"; with it the agent relays the exact enable path,
     * whose terminal state is "the user/admin resolves it".
     */
    public static final String WEB_SEARCH_UNAVAILABLE_NOTICE = """

        # Web Browsing Unavailable On This Installation
        Web search and the browser agent are optional components that are not enabled here, so there is no web_search tool: you cannot search the web or browse pages, and you must not try to emulate browsing with other tools. If the user asks for web search or browsing, tell them the feature is an optional component of this installation and that an administrator can enable it, either by starting the self-hosted stack with the bundled browser-agent option (docker compose --env-file docker/.env.ce.browser-agent up -d) or by linking the installation to the cloud from the settings, then retrying in a new conversation.
        """;

    /**
     * All resource modules in display order.
     * To add a new resource: create a PromptModule constant + add it here.
     */
    public static final List<PromptModule> ALL_RESOURCE_MODULES = List.of(
        CATALOG, TABLE, INTERFACE, AGENT, SKILL, MEMORY, WORKFLOW, APPLICATION, WEB_SEARCH,
        GENERATION, FILES, MAILBOX, WAIT, ASK_USER
    );


    // ═══════════════════════════════════════════════════════════════════════════════
    // BUILDER
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Build with ALL modules enabled = conversation default.
     * This is the prompt used for normal chat conversations.
     */
    public static ModularPromptResult buildDefault() {
        return build(null, true);
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // AGENT-SPECIFIC - concise rules (no module descriptions, no display/response details)
    // ═══════════════════════════════════════════════════════════════════════════════

    private static String agentIntro() {
        String today = ZonedDateTime.now(ZoneOffset.UTC).format(UTC_DATE_FMT);
        return "Current date: " + today + "\n";
    }

    private static final String AGENT_CORE_RULES = CORE_RULES;

    private static final String AGENT_HELP_FIRST = """

        # Help First
        """ + HELP_FIRST_CORE;

    /**
     * Build a concise agent default prompt with EVERY module listed.
     *
     * @deprecated advertises modules the caller may not be granted. Prefer
     *             {@link #buildAgentDefault(Set)} with the module set the caller will
     *             actually receive, so the routing table and the tool list agree.
     */
    @Deprecated
    public static ModularPromptResult buildAgentDefault() {
        return buildAgentDefault(null);
    }

    /**
     * Build a concise agent default prompt: foundation + help-first + tool routing table.
     * Agents rely on their custom system prompt + tool definitions for specifics.
     *
     * <p>The routing table AND the returned tool names are both scoped to
     * {@code enabledModuleKeys}, so a caller cannot advertise a module it will not hand
     * over. Listing {@code generation} to an agent that has no {@code generation} tool
     * only teaches the model to call something it does not have; the reverse (granting
     * the tool without the line) hides a capability that was paid for. One argument,
     * one answer, both halves.
     *
     * @param enabledModuleKeys {@code null} = ALL modules (legacy unrestricted); otherwise
     *                          only matching module keys are listed and returned. An empty
     *                          set yields no routing table and no tools.
     */
    public static ModularPromptResult buildAgentDefault(Set<String> enabledModuleKeys) {
        StringBuilder sb = new StringBuilder();
        sb.append(agentIntro()).append(AGENT_CORE_RULES).append(AGENT_HELP_FIRST);

        List<PromptModule> activeModules = ALL_RESOURCE_MODULES.stream()
            .filter(m -> enabledModuleKeys == null || enabledModuleKeys.contains(m.key()))
            .toList();

        // Append tool routing table (same one-liners as chat prompt)
        if (!activeModules.isEmpty()) {
            sb.append("\n    # Available Tools\n");
            for (PromptModule module : activeModules) {
                sb.append(module.promptSection());
            }
        }

        Set<String> toolNames = activeModules.stream()
            .flatMap(m -> m.toolNames().stream())
            .collect(Collectors.toCollection(LinkedHashSet::new));

        return new ModularPromptResult(sb.toString(), Collections.unmodifiableSet(toolNames));
    }

    /**
     * Build a modular prompt assembled from selected resource modules.
     *
     * @param enabledModuleKeys null = ALL modules enabled (unrestricted);
     *                          otherwise only matching module keys are included.
     *                          Keys: "catalog", "table", "interface", "agent", "skill", "workflow", "application"
     * @param conversationMode  kept for backward compatibility (no longer adds extra sections).
     * @return assembled system prompt + set of core tool names
     */
    public static ModularPromptResult build(Set<String> enabledModuleKeys, boolean conversationMode) {
        StringBuilder sb = new StringBuilder();
        Set<String> toolNames = new LinkedHashSet<>();

        // Foundation - always included
        sb.append(intro()).append(CORE_RULES).append(RESPONSE_RULES);

        // Determine active resource modules
        List<PromptModule> activeModules = ALL_RESOURCE_MODULES.stream()
            .filter(m -> enabledModuleKeys == null || enabledModuleKeys.contains(m.key()))
            .toList();

        // Help first + tool descriptions (routing table) - only if any module is active
        if (!activeModules.isEmpty()) {
            sb.append(HELP_FIRST);
            sb.append("\n    # Available Tools\n");
            for (PromptModule module : activeModules) {
                sb.append(module.promptSection());
                toolNames.addAll(module.toolNames());
            }
        }

        return new ModularPromptResult(sb.toString(), Collections.unmodifiableSet(toolNames));
    }

    /**
     * Get the set of all core tool names across all resource modules.
     */
    public static Set<String> getAllCoreToolNames() {
        return ALL_RESOURCE_MODULES.stream()
            .flatMap(m -> m.toolNames().stream())
            .collect(Collectors.toUnmodifiableSet());
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // COMPOSED PROMPTS - built via builder for single source of truth
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * General conversation prompt - all modules enabled.
     * @deprecated Use {@link #getDefault()} for a prompt with current date.
     * This field is evaluated once at class-load and the date will be stale.
     */
    @Deprecated
    public static final String VERSATILE_AGENT = buildDefault().systemPrompt();

    // ═══════════════════════════════════════════════════════════════════════════════
    // WORKFLOW CONVERSATION (workflow page context - NOT agent nodes)
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Workflow-specific context template.
     * Placeholders: {workflow_name}, {workflow_id}, {workflow_status}, {flow_diagram}, {last_run}
     */
    private static final String WORKFLOW_CONTEXT_TEMPLATE = """

        # Current Workflow: {workflow_name}
        ID: {workflow_id} | Status: {workflow_status}

        Flow:
        {flow_diagram}

        Last run: {last_run}

        IMPORTANT: Before any modification or execution, load the workflow first:
        workflow(action='load', id='{workflow_id}')

        Never use action='init' when editing an existing workflow.

        Quick actions after load:
        - Run: workflow(action='execute', id='{workflow_id}')
        - Add step: workflow(action='add_node', type='<tool-uuid>', label='...', params={...}, connect_after='...')
        - Save: workflow(action='save')
        - View node: workflow(action='describe', node='Node Label')
        - Get help: workflow(action='help', topics=['<node_type>'])
        """;


    /**
     * Build a workflow-specific system prompt (non read-only).
     * @param datasourceId Deprecated, no longer used - kept for backwards compatibility
     */
    public static String buildWorkflowPrompt(
            String workflowName,
            String workflowId,
            String workflowStatus,
            String flowDiagram,
            @SuppressWarnings("unused") String datasourceId,
            String lastRunInfo) {
        return buildWorkflowPrompt(workflowName, workflowId, workflowStatus, flowDiagram, datasourceId, lastRunInfo, false);
    }

    /**
     * Build a workflow-specific system prompt.
     * @param datasourceId Deprecated, no longer used - kept for backwards compatibility
     * @param readOnly whether the workflow is in read-only mode
     */
    public static String buildWorkflowPrompt(
            String workflowName,
            String workflowId,
            String workflowStatus,
            String flowDiagram,
            @SuppressWarnings("unused") String datasourceId,
            String lastRunInfo,
            boolean readOnly) {

        String context = WORKFLOW_CONTEXT_TEMPLATE
            .replace("{workflow_name}", workflowName != null ? workflowName : "Unknown")
            .replace("{workflow_id}", workflowId != null ? workflowId : "unknown")
            .replace("{workflow_status}", workflowStatus != null ? workflowStatus : "UNKNOWN")
            .replace("{flow_diagram}", flowDiagram != null ? flowDiagram : "(no flow available)")
            .replace("{last_run}", lastRunInfo != null ? lastRunInfo : "No execution history");

        String readOnlyNote = readOnly
            ? "\n\n**READ-ONLY MODE:** You can view and analyze this workflow but cannot modify it. Allowed actions: describe, get, list, runs, get_run, help. All other actions are blocked (init, load, add_node, connect, disconnect, modify, remove, undo, save, discard, finish, create, execute, validate, search, insert_row, read_rows, update_row, delete_row, find_rows, set_plan).\n"
            : "";

        String today = ZonedDateTime.now(ZoneOffset.UTC).format(UTC_DATE_FMT);
        return """
            You are an autonomous assistant helping with a specific workflow.
            """ + "Current date: " + today + "\n"
            + CORE_RULES
            + RESPONSE_RULES
            + context
            + readOnlyNote;
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // ACCESSORS
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Get the default system prompt with current date.
     * Always builds a fresh prompt so the date is accurate.
     * <p>
     * The marketplace-discovery cue now lives on the {@link #APPLICATION} module line
     * (folded in 2026-06), so there is no separate reflex variant: every prompt that
     * lists tools carries the cue.
     */
    public static String getDefault() {
        return buildDefault().systemPrompt();
    }

    /**
     * Get the concise agent default prompt (no module descriptions).
     * Always builds a fresh prompt so the date is accurate.
     */
    public static String getAgentDefault() {
        return buildAgentDefault(null).systemPrompt();
    }

    public static String getByType(String type) {
        return getDefault();
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // FOR TESTING / DOCUMENTATION
    // ═══════════════════════════════════════════════════════════════════════════════

    public static String getCoreRules() {
        return CORE_RULES;
    }

    public static String getResponseStyle() {
        return RESPONSE_RULES;
    }
}
