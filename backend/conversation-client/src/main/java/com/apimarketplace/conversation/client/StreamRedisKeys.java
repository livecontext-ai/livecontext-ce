package com.apimarketplace.conversation.client;

import java.time.Duration;

/**
 * Shared Redis key schema for stream state management.
 * <p>
 * Single source of truth for key naming conventions used by:
 * <ul>
 *   <li>{@code RedisStreamStateService} (conversation-service) - full lifecycle</li>
 *   <li>{@code ConversationEventPublisher} (orchestrator-service) - workflow agent buffering</li>
 *   <li>{@code ConversationRedisStreamingCallback} (agent-service) - remote agent buffering</li>
 * </ul>
 * <p>
 * Redis data structures:
 * <ul>
 *   <li>HASH: {@code stream:{streamId}} - metadata (state, userId, conversationId, etc.)</li>
 *   <li>LIST: {@code stream:{streamId}:content} - content chunks</li>
 *   <li>LIST: {@code stream:{streamId}:tools} - tool event JSONs</li>
 *   <li>STRING: {@code stream:conv:{conversationId}} - index → streamId</li>
 *   <li>STRING: {@code approval:{conversationId}:{gateKey}} - a parked tool call's verdict</li>
 *   <li>STRING: {@code approval-all:{conversationId}} - "don't ask again" ticked during a running turn</li>
 * </ul>
 */
public final class StreamRedisKeys {

    private StreamRedisKeys() {}

    public static final Duration STREAM_TTL = Duration.ofMinutes(30);
    public static final Duration CLEANUP_TTL = Duration.ofMinutes(5);
    public static final int MAX_CONTENT_CHUNKS = 10000;
    public static final int MAX_TOOL_EVENTS = 500;

    /**
     * Lifetime of a park's key: the {@code pending} marker while a call is holding, then
     * the verdict that releases it.
     *
     * <p><b>Sized as a bound on being WRONG, not on being patient.</b> While the marker
     * exists, the answer side reports "a call was released" and the frontend trusts it
     * enough to skip the message that would otherwise restart the turn. If the process
     * holding the park dies (pod evicted mid-rollout, OOM, node lost), nothing removes the
     * marker, and every later click on that card is silently swallowed: no release, because
     * nobody is polling, and no resume either. So this must outlive the longest possible
     * park (see {@code agent.tool.approval-gate.timeout-ms}) by enough to keep a
     * last-instant verdict readable, and NOT one minute more. At 30 minutes a single dead
     * pod stranded a conversation for half an hour.
     */
    public static final Duration APPROVAL_DECISION_TTL = Duration.ofMinutes(6);

    /**
     * Lifetime of {@link #conversationWideApprovalKey}. It only has to outlive the turn that
     * was running when the box was ticked: every later turn reads the persisted
     * {@code chatConfig.autoAuthorizeTools} instead, and the key is deleted when the next
     * turn starts. The TTL only matters for a turn that never ends cleanly.
     */
    public static final Duration CONVERSATION_WIDE_APPROVAL_TTL = Duration.ofHours(3);

    private static final String STREAM_KEY_PREFIX = "stream:";
    private static final String CONTENT_KEY_SUFFIX = ":content";
    private static final String TOOLS_KEY_SUFFIX = ":tools";
    private static final String CONV_INDEX_PREFIX = "stream:conv:";
    private static final String APPROVAL_PREFIX = "approval:";
    private static final String APPROVAL_ALL_PREFIX = "approval-all:";
    private static final String CANCEL_PREFIX = "agent:cancel:";

    public static String streamKey(String streamId) {
        return STREAM_KEY_PREFIX + streamId;
    }

    public static String contentKey(String streamId) {
        return STREAM_KEY_PREFIX + streamId + CONTENT_KEY_SUFFIX;
    }

    public static String toolsKey(String streamId) {
        return STREAM_KEY_PREFIX + streamId + TOOLS_KEY_SUFFIX;
    }

    public static String convIndexKey(String conversationId) {
        return CONV_INDEX_PREFIX + conversationId;
    }

    /**
     * Verdict slot for ONE parked tool call, written by conversation-service when the
     * user answers its card and polled by agent-service's approval gate.
     *
     * <p>Cross-service and cross-pod on purpose: the approval POST lands on any
     * conversation-service replica while the parked call is held by one specific
     * agent-service replica, so an in-memory handoff would resolve only when the two
     * happen to be the same process.
     *
     * <p>{@code gateKey} identifies the individual parked CALL (the provider tool-call
     * id), not the approval rule: two sibling calls of the same rule park independently
     * and each must be released by its own card.
     */
    public static String approvalDecisionKey(String conversationId, String gateKey) {
        return APPROVAL_PREFIX + conversationId + ":" + gateKey;
    }

    /**
     * Set when the user ticks "don't ask again in this conversation" on an authorization card.
     *
     * <p>That choice is persisted in {@code chatConfig.autoAuthorizeTools}, but a turn reads
     * its grants once, when it starts. The card releases a held call INSIDE the turn that is
     * still running, so without this key every later sensitive call of that same turn raised
     * a new card right after the user asked never to see one again. agent-service reads it on
     * each gated call; conversation-service writes it on the approval and deletes it when the
     * next turn starts (that turn has the persisted setting, and turning the setting off
     * afterwards must win).
     */
    public static String conversationWideApprovalKey(String conversationId) {
        return APPROVAL_ALL_PREFIX + conversationId;
    }

    /**
     * The "this run was stopped" flag for a stream, set when the user presses Stop.
     *
     * <p>Here, rather than as one more private constant, because it now has to be agreed on
     * ACROSS services: conversation-service and agent-service write it, and the approval
     * park in agent-service reads it - a long block that would otherwise keep working after
     * the user pressed Stop.
     *
     * <p>Honest caveat: the same literal still lives in five other places
     * ({@code ConversationRedisStreamingCallback}, {@code RedisStreamStateService},
     * {@code AgentTaskService}, and two in orchestrator-service). They were left alone
     * deliberately - folding three services' constants into one is a refactor of its own,
     * and doing it inside an unrelated change would widen the blast radius for no user
     * benefit. New readers/writers should use this method; the existing ones are a
     * standing, known duplication.
     */
    public static String cancelKey(String streamId) {
        return CANCEL_PREFIX + streamId;
    }
}
