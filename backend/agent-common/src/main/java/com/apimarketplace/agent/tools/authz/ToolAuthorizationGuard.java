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
 * <p>Unlike {@code BridgeAccessGuard}, this guard NEVER throws: a denial here
 * is a <em>soft, non-blocking authorization request</em> raised to the user
 * (the run is NOT halted), not a hard error. The agent-service call-site
 * ({@code RemoteToolExecutionService}) turns a non-null rule into a
 * {@code ToolResult} carrying {@code toolAuthorizationRequired=true}, which the
 * streaming callback hands off to the chat LIVE exactly like the credential card.
 *
 * <p><b>Fail-closed within scope.</b> If a tool that <em>has</em> sensitive
 * actions is invoked without a resolvable action (null/blank/unparseable args),
 * the guard requires authorization with a wildcard rule {@code "tool:*"} rather
 * than letting the call slip through. Tools with no sensitive actions are never
 * gated. The <em>scope</em> decision (chat vs workflow/task/sub-agent) is made
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
        if (sensitive == null) {
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
        return sensitive.contains(normalized) ? tool + ":" + normalized : null;
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
