package com.apimarketplace.orchestrator.services.approvalchannel;

import com.apimarketplace.orchestrator.services.approvalchannel.telegram.TelegramApprovalCallbackHandler;
import com.apimarketplace.orchestrator.services.channel.telegram.TelegramAuthorizationCallbackHandler;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import com.apimarketplace.orchestrator.services.channel.telegram.TelegramQuestionCallbackHandler;

import java.util.Map;

/**
 * Thin facade the public webhook endpoints use to detect and divert callbacks that
 * belong to this product rather than to a user's workflow.
 *
 * <p>A Telegram bot has exactly ONE webhook URL, usually already pointed at a
 * workflow trigger, so our own button presses arrive on the generic webhook path
 * and must be recognized there (by their namespaced {@code callback_data}),
 * handled, and NOT dispatched to the workflow - dispatching would open a spurious
 * epoch on the host workflow.
 *
 * <p>TWO families arrive this way, and they are told apart by prefix because they
 * answer different things:
 * <ul>
 *   <li>{@code lcapr:} - a workflow approval, resolving a persisted signal;</li>
 *   <li>{@code lcask:} - an agent asking a QUESTION, recording the answer and, when the
 *       whole call is answered, starting the turn that reads it;</li>
 *   <li>a REPLY to one of our question messages, which is how free text comes back;</li>
 *   <li>{@code lcaut:} - an agent asking permission, releasing a parked tool call
 *       in a conversation.</li>
 * </ul>
 * A new channel with an inbound callback adds its detection here.
 */
@Component
public class ApprovalCallbackInterceptor {

    private final TelegramApprovalCallbackHandler telegramHandler;
    private final TelegramAuthorizationCallbackHandler authorizationHandler;
    private final TelegramQuestionCallbackHandler questionHandler;

    public ApprovalCallbackInterceptor(TelegramApprovalCallbackHandler telegramHandler,
                                       TelegramAuthorizationCallbackHandler authorizationHandler,
                                       TelegramQuestionCallbackHandler questionHandler) {
        this.telegramHandler = telegramHandler;
        this.authorizationHandler = authorizationHandler;
        this.questionHandler = questionHandler;
    }

    /**
     * True when the webhook payload is one of ours to divert.
     *
     * <p>A typed REPLY is claimed only when it answers a live question of ours, which takes one
     * indexed read. What this claims the webhook stops delivering anywhere else, and the same
     * bot commonly drives a workflow trigger, so claiming replies on shape alone (as the first
     * version did) swallowed every "Reply" anybody used in that chat. A reply to an approval
     * message, or to a message we do not know, keeps flowing to the workflow.
     */
    public boolean isApprovalCallback(Map<String, Object> payload) {
        return telegramHandler.isApprovalCallback(payload)
                || authorizationHandler.isAuthorizationCallback(payload)
                || questionHandler.isQuestionCallback(payload)
                || questionHandler.isPossibleTextReply(payload);
    }

    /**
     * Handle the callback off-thread (the webhook endpoint must 200 fast: Telegram
     * retries non-2xx aggressively). Each handler swallows every failure.
     */
    @Async("approvalDelegationExecutor")
    public void handleAsync(Map<String, Object> payload) {
        if (authorizationHandler.isAuthorizationCallback(payload)) {
            authorizationHandler.handle(payload);
            return;
        }
        if (questionHandler.isQuestionCallback(payload)) {
            questionHandler.handle(payload);
            return;
        }
        if (questionHandler.isPossibleTextReply(payload)) {
            questionHandler.handleReply(payload);
            return;
        }
        telegramHandler.handle(payload);
    }
}
