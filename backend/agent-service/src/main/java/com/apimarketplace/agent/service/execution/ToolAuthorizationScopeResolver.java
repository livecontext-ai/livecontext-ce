package com.apimarketplace.agent.service.execution;

import java.util.Map;

/**
 * Decides whether the synchronous tool-authorization gate is active for the
 * current execution context.
 *
 * <p><b>Product rule (v1):</b> authorization applies ONLY to the interactive
 * GENERAL shared-agent conversation (the default chat, no bound agent, that the
 * user is watching) - whatever the model, INCLUDING CLI-bridge models
 * (claude-code, codex, …) which back many such chats. EXEMPT: agent-backed
 * chats (an agent = "no authorization today"), agents launched via
 * workflow / task / sub-agent, and headless contexts with no live stream.
 *
 * <p><b>Extension seam.</b> A per-agent option lets a future iteration enable
 * finer control: when {@code __requireToolAuthorization__} is truthy in the
 * credentials, the gate also applies to the otherwise-exempt contexts. Today
 * nothing sets that marker, so all non-chat executions are exempt - i.e. the
 * current behaviour. The DB column {@code agent.require_tool_authorization}
 * (default false) is the persisted source that a later change will thread into
 * this marker.
 *
 * <p><b>Scope failure mode = exempt.</b> Unlike the per-call authorization
 * decision (which is fail-closed within an active scope - see
 * {@code ToolAuthorizationGuard}), the scope decision fails toward exemption:
 * an unrecognized headless context must NOT be gated, or it would pause an
 * automated run that has no user to approve it.
 *
 * <p><b>Headless agent-cli edge:</b> an EXTERNAL agent-cli MCP session wired with
 * conversationId + streamId but no bound agent / task / workflow would match the
 * interactive-chat shape and be gated (it would pause with no user to approve).
 * In practice such headless callers carry {@code __taskId__} (exempt) or omit the
 * stream; the gate deliberately keys on the interactive signals, so a session that
 * truly looks interactive is treated as one.
 *
 * <p>Pure function on the credentials map - unit-testable without Spring.
 */
public final class ToolAuthorizationScopeResolver {

    private ToolAuthorizationScopeResolver() {}

    static final String KEY_AGENT_DEPTH = "__agent_depth__";
    static final String KEY_CONVERSATION_ID = "conversationId";
    static final String KEY_STREAM_ID = "__streamId__";
    static final String KEY_STREAM_ID_PLAIN = "streamId";
    static final String KEY_WORKFLOW_RUN_ID = "__workflowRunId__";
    static final String KEY_WORKFLOW_RUN_ID_PLAIN = "workflowRunId";
    static final String KEY_TASK_ID = "__taskId__";
    static final String KEY_AGENT_ID = "__agentId__";
    /** Per-agent extension seam - default false (no marker = exempt non-chat contexts). */
    static final String KEY_REQUIRE_AUTHORIZATION = "__requireToolAuthorization__";

    /**
     * @return true iff the tool-authorization gate should apply to this execution.
     */
    public static boolean isActive(Map<String, Object> credentials) {
        if (credentials == null) {
            return false;
        }

        boolean agentOverride = isTruthy(credentials.get(KEY_REQUIRE_AUTHORIZATION));

        // NOTE: the CLI bridge (claude-code, codex, …) is NOT exempt - it is the MODEL
        // backing many interactive general chats, which must be gated. The bridge runs
        // through the same CliAgentService as the headless external agent-cli MCP, so
        // there is no bridge-vs-chat marker; gating keys on the interactive signals below
        // (conversationId + streamId + no bound agent). Headless contexts without a live
        // stream stay exempt via the interactiveChat check.

        // Sub-agent (depth >= 1): inherits the parent's launch authorization → exempt.
        if (agentDepth(credentials) >= 1) {
            return agentOverride;
        }
        // Workflow- or task-driven execution: exempt (the user authorized the workflow/task).
        if (hasText(credentials.get(KEY_WORKFLOW_RUN_ID))
                || hasText(credentials.get(KEY_WORKFLOW_RUN_ID_PLAIN))
                || hasText(credentials.get(KEY_TASK_ID))) {
            return agentOverride;
        }
        // Interactive conversation: a live stream the user is watching.
        boolean interactiveChat = hasText(credentials.get(KEY_CONVERSATION_ID))
                && (hasText(credentials.get(KEY_STREAM_ID)) || hasText(credentials.get(KEY_STREAM_ID_PLAIN)));
        if (interactiveChat) {
            // Only the GENERAL shared-agent chat (no bound agent) is gated. An agent-backed
            // chat is "an agent" - exempt today, opt-in via the per-agent flag tomorrow.
            // (Product: "c'est juste pour les conversations générales ; les agents = sans
            // autorisation aujourd'hui".)
            if (hasText(credentials.get(KEY_AGENT_ID))) {
                return agentOverride;
            }
            return true;
        }
        // Anything else (headless / unrecognized) → exempt unless explicitly overridden.
        return agentOverride;
    }

    private static int agentDepth(Map<String, Object> credentials) {
        Object depth = credentials.get(KEY_AGENT_DEPTH);
        if (depth instanceof Number num) {
            return num.intValue();
        }
        if (depth instanceof String str) {
            try {
                return Integer.parseInt(str.trim());
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }

    private static boolean hasText(Object value) {
        return value instanceof String s && !s.isBlank();
    }

    private static boolean isTruthy(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        return value instanceof String s && "true".equalsIgnoreCase(s.trim());
    }
}
