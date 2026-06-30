package com.apimarketplace.agent.tools.authz;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Hand-curated source of truth for which tool actions require a synchronous
 * user authorization before they run in an interactive chat conversation.
 *
 * <p>This is the analogue of {@code BridgeAllowlist} for tool authorization:
 * a single, centralized, in-code list. When the chat agent calls one of these
 * {@code (tool, action)} pairs, the run pauses and an authorization card is
 * shown to the user (mirroring the credential-approval card). See
 * {@code ToolAuthorizationGuard} for the decision logic and the agent-service
 * call-site for enforcement.
 *
 * <p><b>Scope.</b> This list ONLY takes effect for interactive chat
 * conversations. Agents launched via workflow / task / sub-agent are exempt by
 * default - see {@code ToolAuthorizationScopeResolver} in agent-service.
 *
 * <p><b>How to add a rule.</b> Add the action to the {@link Set} of the right
 * tool below (one line). Keys are tool names and actions exactly as the facade
 * tools expose them (lowercase ASCII identifiers - do NOT introduce
 * {@code LabelNormalizer}, which is for workflow node slugs, not tool actions).
 *
 * <p>Criterion for "sensitive": spends credit, acquires/installs a resource,
 * executes something external, or performs a notable state mutation.
 */
public final class ToolAuthorizationPolicy {

    private ToolAuthorizationPolicy() {}

    /**
     * Tool name &rarr; set of actions that require synchronous user authorization.
     *
     * <p>v1 (minimal, easily extensible):
     * <ul>
     *   <li>{@code application:acquire} - acquires a marketplace resource;</li>
     *   <li>{@code application:execute} - runs an application workflow (credit + side effects);</li>
     *   <li>{@code workflow:execute} - runs a saved/built workflow directly (same credit + side
     *       effects as application:execute, just by workflow id instead of publication id);</li>
     *   <li>{@code agent:execute} - launches a sub-agent (credit / LLM spend);</li>
     *   <li>{@code catalog:execute} / {@code catalog:call} - calls an external third-party API.</li>
     * </ul>
     * To extend: add an action to a set, or add a {@code Map.entry(tool, Set.of(...))}.
     */
    public static final Map<String, Set<String>> SENSITIVE_ACTIONS = Map.of(
            "application", Set.of("acquire", "execute"),
            // run a saved workflow by id - was UNGATED (bug); plus advancing a paused run
            // (continue an interface / resolve a user approval) mutates run state + can
            // unblock downstream side-effects, so it is gated the same way in chat.
            "workflow",    Set.of("execute", "continue_interface", "resolve_approval"),
            "agent",       Set.of("execute"),
            "catalog",     Set.of("execute", "call")   // "call" is an alias of "execute"
    );

    /** True iff this exact {@code (toolName, action)} pair is in the sensitive list. */
    public static boolean requires(String toolName, String action) {
        if (toolName == null || action == null) {
            return false;
        }
        Set<String> actions = SENSITIVE_ACTIONS.get(toolName.toLowerCase(Locale.ROOT));
        return actions != null && actions.contains(action.toLowerCase(Locale.ROOT));
    }

    /** True iff this tool has at least one action requiring authorization. */
    public static boolean isSensitiveTool(String toolName) {
        return toolName != null && SENSITIVE_ACTIONS.containsKey(toolName.toLowerCase(Locale.ROOT));
    }

    /**
     * Canonical rule key {@code "tool:action"} for a matching pair, else {@code null}.
     * This is the stable identifier used for approvals (transient and persisted)
     * and dedup - never use the LLM-generated {@code toolCallId}, which changes
     * across resume turns.
     */
    public static String ruleKey(String toolName, String action) {
        return requires(toolName, action)
                ? toolName.toLowerCase(Locale.ROOT) + ":" + action.toLowerCase(Locale.ROOT)
                : null;
    }
}
