package com.apimarketplace.agent.config;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shared utility to resolve which prompt modules are enabled based on an agent's toolsConfig.
 * <p>
 * Used by both orchestrator-service (workflow agent execution) and conversation-service
 * (chat agent execution) to ensure identical module resolution regardless of entry point.
 * <p>
 * Contract:
 * <ul>
 *   <li>{@code null} toolsConfig → all modules enabled (unrestricted) <b>except the opt-in
 *       modules ({@code generation}, {@code mailbox})</b>, which stay off even there.</li>
 *   <li>{@code mode=none} → only MCP/catalog tools blocked; internal tools (table, web_search, etc.)
 *       remain enabled. The opt-in modules ({@code generation}, {@code mailbox}) stay opt-in.</li>
 *   <li>Per-resource family: the AUTHORITATIVE per-family grant ({@code <family>Grant}) decides -
 *       {@code "all"} → unrestricted, {@code "custom"} → accessible iff the id list (the "custom"
 *       payload) is non-empty, {@code "none"}/absent → blocked. The id list is NEVER consulted to
 *       decide none/all; see {@link #isResourceAccessible}.</li>
 *   <li>Web search: opt-OUT boolean toggle - absent/null/true → enabled, false → disabled</li>
 *   <li>Generation (image/video/audio/voice/music): opt-IN under the {@code generation} key -
 *       accepts {@code true} OR {@code { enabled: true, ... }}; absent/null/false → disabled.
 *       Default off because every create spends the customer's credits at the model's own rate,
 *       and a per-second video model spends far more of them than web_search (1 credit).</li>
 *   <li>Mailbox: opt-IN under the {@code mailbox} key, same two accepted shapes. Default off
 *       for a different reason than generation, not cost but reach: it reads the account's mail
 *       and can send from its address, to a person, with no undo.</li>
 * </ul>
 */
public final class AgentModuleResolver {

    private AgentModuleResolver() {}

    /**
     * The module set a caller gets when NOTHING decided for it: no {@code toolsConfig}
     * at all (an agent row that never got one, a plain chat, a sub-agent whose entity
     * carries none), or a wire field that arrived {@code null}.
     *
     * <p>It is {@link #resolveEnabledModules(Map)} on a {@code null} config, i.e. this
     * class's own definition of "unrestricted", which deliberately leaves out BOTH opt-in
     * modules: {@code generation}, which spends the customer's credits, and {@code mailbox},
     * which reaches a real mailbox.
     * Every surface that used to answer "no config ⇒ every tool" must use this instead:
     * handing a caller that enabled nothing a tool that spends money, or sends mail from
     * someone's address, is the exact opposite of those modules being opt-in.
     *
     * <p>Hoisted here (rather than re-derived per service) so the workflow agent node,
     * the remote agent loop, the sub-agent handler, the CLI/bridge session and chat all
     * read ONE definition and cannot drift apart. Computed once: module keys depend on
     * neither the request nor the date.
     */
    public static final Set<String> NO_CONFIG_MODULES =
        Collections.unmodifiableSet(resolveEnabledModules(null));

    /**
     * Determine which prompt modules are enabled based on toolsConfig.
     *
     * @param toolsConfig the agent entity's tools configuration map (nullable)
     * @return set of enabled module keys (e.g., "catalog", "table", "web_search", "generation")
     */
    public static Set<String> resolveEnabledModules(Map<String, Object> toolsConfig) {
        Set<String> enabled = new LinkedHashSet<>();

        // mode=off → NO tools at all. The agent only reasons (judge / classify / transform); it never
        // calls a tool, so it advertises ZERO core tool schemas. Returns an EMPTY module set, which
        // DefaultSystemPrompts.build([]) and CliAgentService.resolveModules([]) both resolve to no
        // tools (null=all, []=none). This is the single biggest schema saving and is DISTINCT from
        // mode=none (which still keeps the internal tools - table/workflow/agent/…). Checked FIRST so
        // the per-family grants normalizeToolsConfig backfills are irrelevant here.
        if (toolsConfig != null && "off".equals(toolsConfig.get("mode"))) {
            return enabled; // empty - no modules, no tools
        }

        enabled.add("catalog"); // Catalog is always available

        if (toolsConfig == null) {
            // No config → all opt-out modules enabled. The credit-spending
            // generation module stays opt-in.
            enabled.addAll(Set.of("table", "interface", "agent", "skill", "memory", "workflow", "application", "web_search", "files", "wait", "ask_user", "channel"));
            return enabled;
        }

        String mode = (String) toolsConfig.get("mode");
        if ("none".equals(mode)) {
            // mode=none → only MCP/catalog tools blocked; internal tools stay enabled.
            // generation still requires explicit opt-in (it is not "internal": it spends
            // the customer's credits).
            enabled.addAll(Set.of("table", "interface", "agent", "skill", "memory", "workflow", "application", "files", "wait", "ask_user", "channel"));
            enabled.remove("catalog");
            // web_search is NOT internal and must honour its opt-out toggle here exactly as the
            // main path below does. This branch used to add it unconditionally. Chat happened to
            // survive that because AgentContextBuilder filters the tool a second time, but
            // AgentNode (workflow agents), SubAgentExecutionHandler and CliAgentService call this
            // resolver with no second filter, and WebSearchToolsProvider.execute has no permission
            // check of its own - so an agent configured mode='none', webSearch=false received a
            // working web_search tool. The five GRANT-BEARING families above (table, interface,
            // agent, workflow, application) stay unconditional on purpose: that is this branch's
            // documented intent ("internal tools stay enabled"), and their execution is closed
            // anyway because the credentials still carry an empty allow-list. That argument does
            // NOT extend to skill/memory/files/wait/ask_user, which have no entry in
            // ToolAccessControl.CREDENTIAL_KEYS and therefore no allow-list to be empty: skill
            // and memory are gated only by their access mode, which is a different axis.
            if (isBooleanEnabled(toolsConfig, "webSearch")) enabled.add("web_search");
            if (isGenerationEnabled(toolsConfig)) enabled.add("generation");
            if (isMailboxEnabled(toolsConfig)) enabled.add("mailbox");
            return enabled;
        }

        // Each resource family is gated by its AUTHORITATIVE per-family grant
        // (<family>Grant): all = enabled, custom = enabled iff its id list is non-empty,
        // none/absent = blocked. The id list is never consulted to decide none/all.
        if (isResourceAccessible(toolsConfig, "tables"))       enabled.add("table");
        if (isResourceAccessible(toolsConfig, "interfaces"))   enabled.add("interface");
        if (isResourceAccessible(toolsConfig, "agents"))       enabled.add("agent");
        // Skills are always enabled (not in toolsConfig restrictions yet)
        enabled.add("skill");
        // Memory is always registered, for the same reason and one more: the memory
        // INDEX is appended to the system prompt from the execution path, not from
        // this resolver, so an agent whose config dropped the module would still be
        // handed an index it had no tool to open. "Always available" means
        // REGISTERED, not unrestricted, and the restriction lives in two other places:
        // toolsConfig.memoryAccessMode='read' leaves recall and search working while
        // ToolAccessControl blocks save/delete, and the workspace role gate in
        // MemoryService refuses every write from a VIEWER regardless of that mode.
        // Those are different axes: the mode is per-agent, set by whoever configures the
        // agent; the role is per-person, set by the workspace. Neither is the other's
        // fallback, so both are asserted separately in the tests.
        enabled.add("memory");
        // Files browser module is always registered (no none/all/custom grant axis - files are
        // opt-in scoped by the allowedFileIds allow-list, not a grant). It DOES enforce a
        // per-resource read/write axis (fileAccessMode, in FilesToolsProvider) plus the
        // allowedFileIds allow-list - so "always available" means registered, not unrestricted.
        enabled.add("files");
        // Wait tool is always registered: pausing is a harmless primitive with no
        // resource to scope (bounded by wait.max-seconds server-side).
        enabled.add("wait");
        // ask_user is always registered: putting a question to the person in the chat has no
        // resource to scope. Outside an interactive chat the tool itself answers "nobody is
        // watching" (ToolAuthorizationScope.isUserPromptable), so headless runs are unaffected.
        enabled.add("ask_user");
        // channel is always registered: connecting a chat the user reads has no resource
        // to scope, and it is the ONLY way a workspace gets a destination at all. Leaving
        // it out of this set is not a restriction, it is the feature being unreachable -
        // the tool list is filtered by exactly this set, so a name absent here is a tool
        // no agent can ever call, with nothing anywhere saying so.
        enabled.add("channel");
        if (isResourceAccessible(toolsConfig, "workflows"))    enabled.add("workflow");
        if (isResourceAccessible(toolsConfig, "applications")) enabled.add("application");
        // Web search: opt-out boolean toggle (absent or true = enabled, false = disabled)
        if (isBooleanEnabled(toolsConfig, "webSearch"))        enabled.add("web_search");
        if (isMailboxEnabled(toolsConfig))                     enabled.add("mailbox");
        // Generation (any format): opt-in (default off; accepts both bool and {enabled,...})
        if (isGenerationEnabled(toolsConfig))                  enabled.add("generation");

        return enabled;
    }

    /**
     * Check whether a resource family's tool MODULE should be enabled, from the
     * AUTHORITATIVE per-family grant ({@code <key>Grant}). No legacy list fallback:
     * <ul>
     *   <li>{@code "all"} → enabled (unrestricted).</li>
     *   <li>{@code "custom"} → enabled iff the id list (the "custom" payload) is non-empty.</li>
     *   <li>{@code "none"}, ABSENT, or unrecognised → blocked (deny).</li>
     * </ul>
     * The id list is NEVER consulted to decide none/all - only as the "custom"
     * payload. The full-backfill migration + {@code normalizeToolsConfig} guarantee
     * every persisted row carries an explicit grant, so an absent grant is only a
     * deny-safe net for an un-backfilled row, never a silent unrestrict.
     */
    public static boolean isResourceAccessible(Map<String, Object> toolsConfig, String key) {
        Object grant = toolsConfig.get(key + "Grant");
        if (grant instanceof String s) {
            if ("all".equals(s)) return true;
            if ("custom".equals(s)) {
                Object v = toolsConfig.get(key);
                return v instanceof List<?> l && !l.isEmpty();
            }
        }
        return false; // "none", absent, or unrecognised → deny (no legacy list fallback)
    }

    /**
     * Check if a boolean feature is enabled in toolsConfig (opt-OUT semantics).
     * absent/null/true = enabled, false = disabled.
     */
    public static boolean isBooleanEnabled(Map<String, Object> toolsConfig, String key) {
        Object value = toolsConfig.get(key);
        if (value == null) return true;
        if (value instanceof Boolean b) return b;
        return true;
    }

    /**
     * Generation toggle (opt-IN), for the format-neutral {@code generation} tool
     * that produces images, video, audio, voice or music. Accepts two shapes for
     * forward compatibility:
     * <ul>
     *   <li>{@code generation: true} - simple boolean toggle.</li>
     *   <li>{@code generation: { enabled: true, ... }} - config object, so the
     *       key can grow fields without changing how it is read.</li>
     * </ul>
     * Anything else (absent, null, false, malformed) → disabled.
     *
     * <p>Read from the {@code generation} key and nothing else. The retired
     * {@code imageGeneration} grant is NOT honoured as a fallback: it was given
     * for images, and this tool also reaches per-second video models that spend
     * an order of magnitude more credits, so an old image grant must never
     * silently widen into it. A row that still carries the retired key resolves
     * to no generation module at all until its owner opts in again.
     */
    public static boolean isGenerationEnabled(Map<String, Object> toolsConfig) {
        return isOptInEnabled(toolsConfig, "generation");
    }

    /**
     * Whether this agent may touch the account's mailbox at all. OPT-IN, default OFF.
     *
     * <p>Opt-in rather than opt-out, and the comparison that settles it is web_search: that one
     * reads the public web, this one reads someone's mail and can send from their address, to a
     * person, with no undo. It is at least as consequential as {@code generation}, which is
     * opt-in for spending credit alone.
     *
     * <p>It also keeps every agent that has nothing to do with mail from carrying the tool: a
     * capability nobody enabled is one nobody has to reason about, and the schema it would add
     * to the prompt is not free either.
     *
     * <p>Consequence worth stating plainly: connecting an IMAP credential is NOT enough for an
     * agent to use it. Someone has to turn this on for that agent, which is the point.
     */
    public static boolean isMailboxEnabled(Map<String, Object> toolsConfig) {
        return isOptInEnabled(toolsConfig, "mailbox");
    }

    /**
     * Credential key carrying the modules the CALLING agent was granted.
     *
     * <p>The agent id already travels in tool-execution credentials, but the
     * id alone answers "who" and not "what they may do", and a tool running in
     * another service cannot load the agent to find out. So the resolved set
     * travels with it.
     *
     * <p>Written by whoever drives the agent and already knows the set (the
     * CLI/bridge session and the chat context builder). Read by any tool whose
     * ACTION spends what a module gates, which today means the workflow builder
     * creating a generate node: that node spends the customer's credits on a
     * paid provider exactly as the generation tool does, so an agent without
     * the grant must not be able to reach it one indirection away.
     */
    public static final String ENABLED_MODULES_CREDENTIAL_KEY = "__enabledModules__";

    /**
     * Whether the calling agent may use {@code module}, as far as the
     * credentials of the current tool call can say.
     *
     * <p>Answers TRUE when the credentials say nothing. Absence is not a
     * denial: it is every caller that predates this key, and every path with no
     * bound agent at all (a workflow fired by a schedule has no agent to ask).
     * Reading silence as "denied" would refuse work that was always allowed,
     * which is a worse failure than the one this closes and is invisible until
     * a customer reports it.
     */
    @SuppressWarnings("unchecked")
    public static boolean callerMayUse(Map<String, Object> credentials, String module) {
        if (credentials == null || module == null) return true;
        Object raw = credentials.get(ENABLED_MODULES_CREDENTIAL_KEY);
        if (!(raw instanceof Collection<?> granted)) return true;
        for (Object key : granted) {
            if (module.equals(key)) return true;
        }
        return false;
    }

    /**
     * Shared opt-IN reader for the credit-spending toggles. Accepts a plain
     * boolean or a config object, so a key can grow extra fields (provider,
     * model, quality) without changing how it is read.
     */
    private static boolean isOptInEnabled(Map<String, Object> toolsConfig, String key) {
        if (toolsConfig == null) return false;
        Object value = toolsConfig.get(key);
        if (value == null) return false;
        if (value instanceof Boolean b) return b;
        if (value instanceof Map<?, ?> m) {
            Object enabledFlag = m.get("enabled");
            if (enabledFlag instanceof Boolean b) return b;
            // Object present without explicit `enabled` field → treat as enabled
            // (matches the principle "if user supplied a config block, they meant it").
            return enabledFlag == null;
        }
        return false;
    }
}
