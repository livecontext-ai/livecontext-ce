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

        - Same tool + similar args fails twice → stop.
        - Errors: retry once, then report.
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

    public static final PromptModule WORKFLOW = new PromptModule(
        "workflow",
        "\n        - workflow - Multi-step automation builder (stateful: init/load before add_node). Also inspect and execute runs.\n",
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
        "\n        - application - Marketplace = your toolbox: published apps add capabilities you don't have built-in (download, API search, a rich interface). On a domain task (e.g. \"fais une recherche airbnb\", \"find me a flight\") or any tool you're missing, check here first - application(action='my'), then application(action='search') + acquire (clone) - before reaching for web_search, catalog or workflow(action='init'). You may fall back once nothing matches. Also publishes apps. application(action='help') if unsure.\n",
        Set.of("application")
    );

    public static final PromptModule WEB_SEARCH = new PromptModule(
        "web_search",
        "\n        - web_search - Search the web, fetch pages as markdown, or drive an LLM browser. Use web_search(action='help') to see actions + the platform's (provider, model) catalog before agent_browse.\n",
        Set.of("web_search")
    );

    public static final PromptModule IMAGE_GENERATION = new PromptModule(
        "image_generation",
        "\n        - image_generation - Generate images from a text prompt. Bills per image returned. Call image_generation(action='help') for the (provider, model, credits) catalog before generate.\n",
        Set.of("image_generation")
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
        CATALOG, TABLE, INTERFACE, AGENT, SKILL, WORKFLOW, APPLICATION, WEB_SEARCH, IMAGE_GENERATION, FILES, WAIT
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
     * Build a concise agent default prompt: foundation + help-first + tool routing table.
     * Agents rely on their custom system prompt + tool definitions for specifics.
     * All core tool names are still returned so tools remain available.
     */
    public static ModularPromptResult buildAgentDefault() {
        StringBuilder sb = new StringBuilder();
        sb.append(agentIntro()).append(AGENT_CORE_RULES).append(AGENT_HELP_FIRST);

        // Append tool routing table (same one-liners as chat prompt)
        sb.append("\n    # Available Tools\n");
        for (PromptModule module : ALL_RESOURCE_MODULES) {
            sb.append(module.promptSection());
        }

        // Return all tool names so agents still have access to every tool
        Set<String> allToolNames = ALL_RESOURCE_MODULES.stream()
            .flatMap(m -> m.toolNames().stream())
            .collect(Collectors.toCollection(LinkedHashSet::new));

        return new ModularPromptResult(sb.toString(), allToolNames);
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
        return buildAgentDefault().systemPrompt();
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
