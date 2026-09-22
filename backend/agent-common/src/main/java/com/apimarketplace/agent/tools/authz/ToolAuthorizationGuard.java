package com.apimarketplace.agent.tools.authz;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Pure decision function for tool authorization. Given a tool name and its
 * argument map, decides whether the call requires a synchronous user
 * authorization, returning the canonical rule key {@code "tool:action"} that
 * matched (or {@code null} when no authorization is needed).
 *
 * <p>Unlike {@code BridgeAccessGuard}, this guard NEVER throws: a denial here is
 * an authorization REQUEST raised to the user, not a hard error. The
 * agent-service call-site ({@code RemoteToolExecutionService}) turns a non-null
 * rule into a {@code ToolResult} carrying {@code toolAuthorizationRequired=true},
 * shows the card, and then HOLDS the call on {@code ToolApprovalGate} until the
 * user answers - so an in-time approval runs the tool and returns its real result
 * from this same call. The hold has a budget; when it runs out the request is
 * returned as-is and the user's later answer starts a fresh turn, which is the
 * behaviour that shipped before the gate existed.
 *
 * <p>Rules the USER performs rather than authorizes are never held, however fast
 * they answer: {@code application:acquire} installs the app itself, out of band,
 * so there is no call to release (see {@code isUserPerformedRule}). For those the
 * request always reaches the agent and the two-turn flow is the only flow.
 *
 * <p><b>Fail-closed within scope.</b> If a tool that <em>has</em> sensitive
 * actions is invoked without a resolvable action (null/blank/unparseable args),
 * the guard requires authorization with a wildcard rule {@code "tool:*"} rather
 * than letting the call slip through. Tools with no sensitive actions are never
 * gated.
 *
 * <p><b>Two registries, read in order.</b> {@code SENSITIVE_ACTIONS} keys on the
 * {@code (tool, action)} pair; {@code CONDITIONAL_RULES} keys on the arguments as
 * well, for an action that is only sometimes sensitive (today: an {@code agent}
 * create/update carrying a cron, which arms a recurring agent). The unconditional
 * match is tried first, so a pair listed in both keeps its own rule key rather than
 * the conditional one. The <em>scope</em> decision (chat vs workflow/task/sub-agent) is made
 * separately and BEFORE this guard - see
 * {@code ToolAuthorizationScopeResolver}.
 *
 * <p>This class is intentionally free of any agent-runtime types ({@code ToolCall},
 * credentials, …) so it can live in {@code agent-common} and be unit-tested in
 * isolation.
 */
public final class ToolAuthorizationGuard {

    private ToolAuthorizationGuard() {}

    /** Action token used when a sensitive tool is invoked with no resolvable action. */
    public static final String WILDCARD_ACTION = "*";

    /**
     * @return the matched rule {@code "tool:action"} (or {@code "tool:*"} on a
     *         fail-closed match) when this call requires authorization, else
     *         {@code null}.
     */
    public static String matchedRule(String toolName, Map<String, Object> arguments) {
        if (toolName == null) {
            return null;
        }
        String tool = toolName.toLowerCase(Locale.ROOT);
        Set<String> sensitive = ToolAuthorizationPolicy.SENSITIVE_ACTIONS.get(tool);
        boolean conditional = ToolAuthorizationPolicy.hasConditionalRules(tool);
        if (sensitive == null && !conditional) {
            return null; // tool exposes no sensitive actions - never gate
        }
        String action = extractAction(arguments);
        if (action == null || action.isEmpty()) {
            // Fail-closed: a sensitive-capability tool with no resolvable action
            // is gated rather than executed. The underlying tool would reject a
            // missing action anyway; gating avoids any chance of a side effect.
            return tool + ":" + WILDCARD_ACTION;
        }
        String normalized = action.toLowerCase(Locale.ROOT);
        if (sensitive != null && sensitive.contains(normalized)) {
            return tool + ":" + normalized;
        }
        // The action is not sensitive by itself. It can still be sensitive because of what
        // the call CARRIES - an agent:create with a cron arms something that runs forever.
        // Checked second so an unconditional rule always wins and keeps its own key.
        return ToolAuthorizationPolicy.conditionalRuleKey(tool, normalized, arguments);
    }

    /** Convenience: does this call require authorization? */
    public static boolean requiresAuthorization(String toolName, Map<String, Object> arguments) {
        return matchedRule(toolName, arguments) != null;
    }

    private static String extractAction(Map<String, Object> arguments) {
        if (arguments == null) {
            return null;
        }
        Object action = arguments.get("action");
        if (action == null) {
            return null;
        }
        return String.valueOf(action).trim();
    }
}
