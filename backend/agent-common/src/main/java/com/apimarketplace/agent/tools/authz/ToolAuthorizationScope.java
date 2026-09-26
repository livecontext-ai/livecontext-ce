package com.apimarketplace.agent.tools.authz;

import java.util.Map;

/**
 * Whether the caller's context is one where a tool-authorization card is actually raised.
 *
 * <p>This is THE predicate, shared. It used to live only in agent-service, next to the code that
 * raises the card, and any other surface wanting to know "will the user be asked?" had to guess.
 * Guessing is not close enough: a plausible-looking test on
 * {@code conversationId != null || streamId != null} says yes for a sub-agent, for an
 * agent-backed chat, and for an agent node inside an unattended scheduled run, all of which are
 * EXEMPT from the card. An action gated on that guess would run unattended with no one to ask.
 *
 * <p>The rules, in the order they are decided:
 * <ol>
 *   <li>a sub-agent (depth >= 1) inherits its parent's authorization, so no card;</li>
 *   <li>a workflow- or task-driven execution was authorized when the workflow or task was, so
 *       no card;</li>
 *   <li>an interactive conversation (a conversation id AND a live stream) DOES get a card,
 *       unless a specific agent is bound to it, which makes it "an agent" rather than the
 *       general chat;</li>
 *   <li>anything else is headless, so no card.</li>
 * </ol>
 * At every exit an explicit per-agent flag can force the card back on.
 */
public final class ToolAuthorizationScope {

    public static final String KEY_AGENT_DEPTH = "__agent_depth__";
    public static final String KEY_CONVERSATION_ID = "conversationId";
    public static final String KEY_STREAM_ID = "__streamId__";
    public static final String KEY_STREAM_ID_PLAIN = "streamId";
    public static final String KEY_WORKFLOW_RUN_ID = "__workflowRunId__";
    public static final String KEY_WORKFLOW_RUN_ID_PLAIN = "workflowRunId";
    public static final String KEY_TASK_ID = "__taskId__";
    public static final String KEY_AGENT_ID = "__agentId__";
    public static final String KEY_REQUIRE_AUTHORIZATION = "__requireToolAuthorization__";

    /**
     * Set by the producer that KNOWS this execution was started by a schedule, a webhook
     * or a task rather than by a person typing.
     *
     * <p>It exists because the shape of an unattended run is indistinguishable from an
     * interactive one from the credentials alone: the sync path mints a stream id
     * unconditionally (a real one for streaming-eligible sources, else
     * {@code "sync-<conversationId>"}), so a scheduled agent carries a conversation AND a
     * stream AND no task id, which is exactly the shape of someone chatting. Deriving
     * "nobody is watching" from that is the guess this class warns about two paragraphs
     * up, and it silently skipped the whole out-of-app delivery for the one case it was
     * built for.
     */
    public static final String KEY_UNATTENDED_RUN = "__unattendedRun__";

    /**
     * Set when the call arrives through the CLI bridge, so a CLI is sitting on the MCP
     * request while we hold it. It does not change WHETHER a card is raised, only how long
     * the call may be parked waiting for the answer.
     */
    public static final String KEY_CLI_BRIDGE_SESSION = "__cliBridgeSession__";

    private ToolAuthorizationScope() {
    }

    /** True when a sensitive action in this context would raise a card the user can answer. */
    public static boolean isCardRaised(Map<String, Object> credentials) {
        if (credentials == null) {
            return false;
        }
        boolean agentOverride = isTruthy(credentials.get(KEY_REQUIRE_AUTHORIZATION));

        if (agentDepth(credentials) >= 1) {
            return agentOverride;
        }
        if (hasText(credentials.get(KEY_WORKFLOW_RUN_ID))
                || hasText(credentials.get(KEY_WORKFLOW_RUN_ID_PLAIN))
                || hasText(credentials.get(KEY_TASK_ID))) {
            return agentOverride;
        }
        boolean interactiveChat = hasText(credentials.get(KEY_CONVERSATION_ID))
                && (hasText(credentials.get(KEY_STREAM_ID)) || hasText(credentials.get(KEY_STREAM_ID_PLAIN)));
        if (interactiveChat) {
            if (hasText(credentials.get(KEY_AGENT_ID))) {
                return agentOverride;
            }
            return true;
        }
        return agentOverride;
    }

    /**
     * True when a person is watching this execution and can answer a question put to them.
     *
     * <p>A different question from {@link #isCardRaised}, and deliberately a different
     * predicate. That one asks "must this action be AUTHORIZED", and an agent-backed chat is
     * exempt from authorization by product rule. This one asks "is there a human on the
     * other end", and an agent-backed chat has one just as much as the general chat does.
     * Reusing the authorization predicate here would make {@code ask_user} silently
     * unavailable in exactly the chats it was built for.
     *
     * <p>Interactive means: not a sub-agent, not a workflow or task run, and a conversation
     * with a live stream. Anything headless gets {@code false}, so a question raised there
     * is answered with "nobody is watching" instead of a card nobody will see.
     */
    public static boolean isUserPromptable(Map<String, Object> credentials) {
        if (credentials == null) {
            return false;
        }
        if (agentDepth(credentials) >= 1) {
            return false;
        }
        if (hasText(credentials.get(KEY_WORKFLOW_RUN_ID))
                || hasText(credentials.get(KEY_WORKFLOW_RUN_ID_PLAIN))
                || hasText(credentials.get(KEY_TASK_ID))) {
            return false;
        }
        return hasText(credentials.get(KEY_CONVERSATION_ID))
                && (hasText(credentials.get(KEY_STREAM_ID)) || hasText(credentials.get(KEY_STREAM_ID_PLAIN)));
    }

    /**
     * True when nobody is in front of this execution, so a card raised here would wait
     * for someone to open the app.
     *
     * <p>Two ways to be sure, never a guess: the producer SAID so
     * ({@link #KEY_UNATTENDED_RUN}), or the context is one where a question cannot be put
     * to anyone at all ({@link #isUserPromptable} is false: a sub-agent, a workflow run, a
     * task). Anything else is treated as watched, which is the safe side: the cost of
     * being wrong is a message the person did not need, not a run that stalls.
     */
    public static boolean isUnattended(Map<String, Object> credentials) {
        if (credentials == null) {
            return false;
        }
        return isTruthy(credentials.get(KEY_UNATTENDED_RUN)) || !isUserPromptable(credentials);
    }

    /** Where a question to the person can be put from this execution. */
    public enum QuestionReach {
        /** Somebody is in the app, watching this run: the card is the way. */
        IN_APP,
        /** Nobody is watching, but a reply can come back to this conversation later. */
        CHANNEL,
        /** There is no way to put a question and no way for an answer to return. */
        NONE
    }

    /** Whether this execution is a step of a workflow run. */
    public static boolean isWorkflowRun(Map<String, Object> credentials) {
        return credentials != null && (hasText(credentials.get(KEY_WORKFLOW_RUN_ID))
                || hasText(credentials.get(KEY_WORKFLOW_RUN_ID_PLAIN)));
    }

    /**
     * Where a question can be put from this execution, which is not the same as whether a
     * card is raised.
     *
     * <p>{@link #isUserPromptable} is deliberately left alone: it answers "is a person
     * watching in the app", which the gate and the card publisher keep needing. This one
     * answers "can this run ask at all, and through what", and the two differ for exactly
     * the runs this feature exists for.
     *
     * <p>The rule, in the order it is decided:
     * <ol>
     *   <li>watched in the app (promptable AND not marked unattended) is {@code IN_APP};</li>
     *   <li>a sub-agent or a workflow node is {@code NONE}, for the reason that decides this
     *       whole predicate: <b>an answer has to have somewhere to land</b>. Both of those
     *       conversations cannot be re-entered by a reply that arrives minutes later, because
     *       the parent has moved on and the node has completed. Asking there would take a real
     *       answer from a real person and drop it;</li>
     *   <li>anything else with a conversation is {@code CHANNEL}: a schedule, a webhook, a
     *       task. Their conversation persists, so a reply landing tomorrow still continues
     *       the right thread.</li>
     * </ol>
     *
     * <p>A TASK is the case worth naming: {@link #KEY_TASK_ID} makes it not promptable, and it
     * carries a persistent conversation, so it resolves to {@code CHANNEL}. That is the
     * intended widening, not an accident of the ordering.
     *
     * <p>This does NOT consult whether a destination exists. That is the address book's
     * question, it lives in another service, and answering it here would make a pure function
     * of the credentials depend on a remote call. The delivery answers it at ask time.
     */
    public static QuestionReach questionReach(Map<String, Object> credentials) {
        if (credentials == null) {
            return QuestionReach.NONE;
        }
        if (isUserPromptable(credentials) && !isTruthy(credentials.get(KEY_UNATTENDED_RUN))) {
            return QuestionReach.IN_APP;
        }
        if (agentDepth(credentials) >= 1 || isWorkflowRun(credentials)) {
            return QuestionReach.NONE;
        }
        return hasText(credentials.get(KEY_CONVERSATION_ID)) ? QuestionReach.CHANNEL : QuestionReach.NONE;
    }

    /** How deep this run is in a chain of agents: 0 for a run a person started, 1+ for a sub-agent. */
    public static int agentDepth(Map<String, Object> credentials) {
        if (credentials == null) {
            return 0;
        }
        Object depth = credentials.get(KEY_AGENT_DEPTH);
        if (depth instanceof Number n) {
            return n.intValue();
        }
        if (depth instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    /**
     * True when a CLI is holding this call open at the other end of an MCP request.
     *
     * <p>Lives here, next to the key, because the alternative is two truthiness rules for
     * one credential drifting apart in two modules. It answers HOW LONG a parked call may
     * be held, not WHETHER a card is raised, so it is deliberately not part of
     * {@link #isCardRaised}.
     */
    public static boolean isCliBridgeSession(Map<String, Object> credentials) {
        return credentials != null && isTruthy(credentials.get(KEY_CLI_BRIDGE_SESSION));
    }

    private static boolean hasText(Object value) {
        return value != null && !String.valueOf(value).isBlank();
    }

    private static boolean isTruthy(Object value) {
        if (value instanceof Boolean b) return b;
        return value != null && Boolean.parseBoolean(String.valueOf(value).trim());
    }
}
