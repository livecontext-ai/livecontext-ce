package com.apimarketplace.common.credit;

/**
 * The single vocabulary for "this tenant has no credits left" on the CHAT side.
 *
 * <p><b>Why a third one exists.</b> The engine already had two: the
 * {@code CREDIT_EXHAUSTED} token and the workflow-node sentence, both owned by
 * orchestrator-service's {@code CreditExhaustion}. Neither reaches a chat refusal.
 * A scheduled agent, a widget, a webhook and a task all run through
 * {@code POST /api/internal/chat/sync}, which answers HTTP 402 with this wording,
 * and the string then travels back through {@code ConversationClient} to callers in
 * three different services. Until this class existed the wording was a loose literal
 * repeated across the five CHAT 402 sites (the internal endpoint's stream-init and
 * sync branches, plus the interactive one, each in its cloud and CE monolith form),
 * so nothing downstream could recognise it.
 *
 * <p><b>Scope: this constant is PRODUCED by the chat surfaces only.</b> Other refusals
 * in the product (a CE download, a catalog tool billing guard) share the wording by
 * coincidence, not by contract, and are deliberately NOT routed through it: sharing a
 * sentence is not sharing a meaning.
 *
 * <p><b>But {@link #isChatCreditRefusal} is a substring test, so it RECOGNISES those
 * too</b>, and a reader should know that rather than infer the opposite from the
 * paragraph above. Where it is wired into a log classifier this is correct and
 * welcome: a catalog tool's "402 Insufficient credits ..." relayed up to a step node,
 * and the agent node's "Insufficient credits (pre-flight tenant budget)", are the same
 * customer condition and belong at the same level. The guarantee this class offers is
 * therefore "an empty wallet somewhere", which is exactly what a log level wants to
 * know and is NOT precise enough to drive behaviour - which is why its only consumers
 * are warn/error branches.
 *
 * <p><b>What that cost.</b> Every one of those refusals was logged at ERROR, in two
 * places per tick: once in {@code ConversationClient} and once in the frame above it.
 * Both land in the CALLING service's log, because the client is a library, so on
 * 2026-09-17 a single workspace sitting at -100.83 credits produced 44 of
 * orchestrator-service's 179 error lines over 24 h, one quarter of them, for a product
 * refusing exactly as designed. (conversation-service itself logs nothing at ERROR on
 * this path: it answers 402 before executing and records the attempt at INFO.) The
 * classifier that was meant to catch this, {@code UserActionableFailure}, had been
 * wired into the agent-schedule branch and was asked about a string it could not
 * know, so it answered "not user-actionable" and kept ERROR.
 *
 * <p><b>The status code is the real signal; this is the fallback.</b> Where the HTTP
 * response is still in hand, branch on 402 and do not match text at all: that is what
 * {@code ConversationClient} does. This class is for the frames above it, which only
 * ever receive a message.
 *
 * <p><b>Two kinds of consumer, on purpose.</b> orchestrator-service reaches this class
 * through its own {@code UserActionableFailure}, which aggregates every tenant-actionable
 * vocabulary it knows; agent-service calls it DIRECTLY, because that classifier is
 * orchestrator-local. So widening {@code UserActionableFailure} later will not reach
 * agent-service, and a vocabulary meant for both belongs here rather than there.
 */
public final class ChatCreditRefusal {

    private ChatCreditRefusal() {
    }

    /**
     * The message body of a chat credit refusal, returned by the internal sync-chat
     * endpoint alongside HTTP 402.
     *
     * <p>Matched with {@code contains}, not compared whole: callers wrap it (a transport
     * sentence around the JSON body) and producers extend it (auth-service appends its
     * arithmetic, {@code "Insufficient credits: balance=0, required=5"}), and every one of
     * those shapes means the same thing to a reader of a log line.
     */
    public static final String MESSAGE = "Insufficient credits";

    /**
     * True when {@code message} is a chat-side credit refusal, including one a caller
     * has wrapped or prefixed.
     *
     * <p>A blank or null message answers {@code false}: an unknown shape must stay an
     * error, because hiding a platform fault costs more than one noisy customer
     * condition.
     */
    public static boolean isChatCreditRefusal(String message) {
        return message != null && message.contains(MESSAGE);
    }
}
