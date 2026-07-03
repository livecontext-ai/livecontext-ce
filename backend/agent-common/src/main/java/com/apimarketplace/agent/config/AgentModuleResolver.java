package com.apimarketplace.agent.config;

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
 *   <li>{@code null} toolsConfig → all modules enabled (unrestricted) <b>except image_generation</b>
 *       which is opt-in even in unrestricted mode.</li>
 *   <li>{@code mode=none} → only MCP/catalog tools blocked; internal tools (table, web_search, etc.)
 *       remain enabled. {@code image_generation} stays opt-in.</li>
 *   <li>Per-resource family: the AUTHORITATIVE per-family grant ({@code <family>Grant}) decides -
 *       {@code "all"} → unrestricted, {@code "custom"} → accessible iff the id list (the "custom"
 *       payload) is non-empty, {@code "none"}/absent → blocked. The id list is NEVER consulted to
 *       decide none/all; see {@link #isResourceAccessible}.</li>
 *   <li>Web search: opt-OUT boolean toggle - absent/null/true → enabled, false → disabled</li>
 *   <li>Image generation: opt-IN - accepts {@code true} OR {@code { enabled: true, ... }};
 *       absent/null/false → disabled. Default off because per-image cost (5-133 credits) is
 *       significantly higher than web_search (1 credit) and many agents will never need it.</li>
 * </ul>
 */
public final class AgentModuleResolver {

    private AgentModuleResolver() {}

    /**
     * Determine which prompt modules are enabled based on toolsConfig.
     *
     * @param toolsConfig the agent entity's tools configuration map (nullable)
     * @return set of enabled module keys (e.g., "catalog", "table", "web_search", "image_generation")
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
            // No config → all opt-out modules enabled. image_generation stays opt-in.
            enabled.addAll(Set.of("table", "interface", "agent", "skill", "workflow", "application", "web_search", "files", "wait"));
            return enabled;
        }

        String mode = (String) toolsConfig.get("mode");
        if ("none".equals(mode)) {
            // mode=none → only MCP/catalog tools blocked; internal tools stay enabled.
            // image_generation still requires explicit opt-in (it's not "internal").
            enabled.addAll(Set.of("table", "interface", "agent", "skill", "workflow", "application", "web_search", "files", "wait"));
            enabled.remove("catalog");
            if (isImageGenerationEnabled(toolsConfig)) enabled.add("image_generation");
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
        // Files browser module is always registered (no none/all/custom grant axis - files are
        // opt-in scoped by the allowedFileIds allow-list, not a grant). It DOES enforce a
        // per-resource read/write axis (fileAccessMode, in FilesToolsProvider) plus the
        // allowedFileIds allow-list - so "always available" means registered, not unrestricted.
        enabled.add("files");
        // Wait tool is always registered: pausing is a harmless primitive with no
        // resource to scope (bounded by wait.max-seconds server-side).
        enabled.add("wait");
        if (isResourceAccessible(toolsConfig, "workflows"))    enabled.add("workflow");
        if (isResourceAccessible(toolsConfig, "applications")) enabled.add("application");
        // Web search: opt-out boolean toggle (absent or true = enabled, false = disabled)
        if (isBooleanEnabled(toolsConfig, "webSearch"))        enabled.add("web_search");
        // Image generation: opt-in (default off; richer config - accepts both bool and {enabled,...})
        if (isImageGenerationEnabled(toolsConfig))             enabled.add("image_generation");

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
     * Image-generation toggle (opt-IN). Accepts two shapes for forward
     * compatibility:
     * <ul>
     *   <li>{@code imageGeneration: true} - simple boolean toggle (Phase 1
     *       UI may emit this).</li>
     *   <li>{@code imageGeneration: { enabled: true, provider: "openai",
     *       model: "gpt-image-1.5", quality: "medium" }} - richer object
     *       once the model picker lands (Phase 2 UI).</li>
     * </ul>
     * Anything else (absent, null, false, malformed) → disabled.
     */
    public static boolean isImageGenerationEnabled(Map<String, Object> toolsConfig) {
        if (toolsConfig == null) return false;
        Object value = toolsConfig.get("imageGeneration");
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
