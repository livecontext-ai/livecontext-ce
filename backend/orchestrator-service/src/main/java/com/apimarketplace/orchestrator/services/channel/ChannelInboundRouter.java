package com.apimarketplace.orchestrator.services.channel;

import com.apimarketplace.orchestrator.domain.channel.ChatAuthorizationRequestEntity;
import com.apimarketplace.orchestrator.domain.execution.ApprovalChannelDeliveryEntity;
import com.apimarketplace.orchestrator.services.approvalchannel.WorkflowApprovalPressService;
import com.apimarketplace.orchestrator.services.approvalchannel.telegram.TelegramApprovalNotifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where a press or a typed reply from ANY chat provider goes.
 *
 * <p>A provider's inbound controller authenticates its callback, translates it into one of the two
 * events here, and stops. Everything after that is shared, and routed by the payload prefix the
 * button was sent with:
 * <ul>
 *   <li>{@code lcaut:} an agent asking permission, decided by {@link AgentAuthorizationChannelService};</li>
 *   <li>{@code lcask:} an agent asking a question, answered through {@link ChatQuestionService};</li>
 *   <li>{@code lcapr:} a workflow's approval node, decided by {@link WorkflowApprovalPressService}.</li>
 * </ul>
 *
 * <p>This is the one place that knows that mapping. A sixth provider adds a connector and a
 * controller and touches nothing here. Telegram predates this router and keeps its own handlers,
 * which call the same three services.
 */
@Component
public class ChannelInboundRouter {

    private static final Logger logger = LoggerFactory.getLogger(ChannelInboundRouter.class);

    private static final String TOKEN = "([A-Za-z0-9_-]{16,48})";
    private static final Pattern AUTHORIZATION = Pattern.compile(
            "^" + AgentAuthorizationChannelService.CALLBACK_PREFIX + ":" + TOKEN + ":(a|r)$");
    private static final Pattern QUESTION = Pattern.compile(
            "^" + CallbackTokens.QUESTION_PREFIX + ":" + TOKEN + ":(o\\d{1,2}|done|other)$");
    private static final Pattern WORKFLOW_APPROVAL = Pattern.compile(
            "^" + TelegramApprovalNotifier.CALLBACK_PREFIX + ":" + TOKEN + ":(a|r)$");

    private final AgentAuthorizationChannelService authorizationService;
    private final ChatQuestionService questionService;
    private final WorkflowApprovalPressService workflowApprovalService;

    public ChannelInboundRouter(AgentAuthorizationChannelService authorizationService,
                                ChatQuestionService questionService,
                                WorkflowApprovalPressService workflowApprovalService) {
        this.authorizationService = authorizationService;
        this.questionService = questionService;
        this.workflowApprovalService = workflowApprovalService;
    }

    /**
     * What a press or reply achieved, in the terms any provider can acknowledge.
     *
     * @param handled        false when nothing of ours matched: an unknown token, or a reply to a
     *                       message we do not own. The caller then acknowledges nothing, and a
     *                       reply keeps flowing to whatever else the bot drives
     * @param replyToUser    what to tell the person, or null
     * @param asAlert        whether that line is a refusal worth interrupting them for
     * @param tenantId       whose credential sends the acknowledgement, when one is needed
     * @param organizationId the workspace scope that credential resolves under
     * @param credentialId   the credential the original message went out with
     * @param chatId         where the original message is
     * @param afterAck       work that must wait until the person has been acknowledged (handing a
     *                       question's answers over starts an LLM turn, longer than any provider
     *                       waits for an acknowledgement); never null
     */
    public record Result(boolean handled, String replyToUser, boolean asAlert, String tenantId,
                         String organizationId, Long credentialId, String chatId, Runnable afterAck) {

        static Result notOurs() {
            return new Result(false, null, false, null, null, null, null, () -> { });
        }
    }

    /** True when this payload is a button of ours, whatever it asks. */
    public boolean isOurPayload(String payload) {
        return payload != null && (AUTHORIZATION.matcher(payload).matches()
                || QUESTION.matcher(payload).matches()
                || WORKFLOW_APPROVAL.matcher(payload).matches());
    }

    /**
     * Somebody pressed one of our buttons.
     *
     * @param origin     where the press came in (provider, bot, chat); a request is only answerable
     *                   from where its message went, see {@link PressOrigin}
     * @param payload    the button's value, exactly as we sent it
     * @param fromUserId the presser's id on that provider, checked against the allow-lists
     */
    public Result press(PressOrigin origin, String payload, String fromUserId) {
        String channel = origin.channel();
        if (payload == null) {
            return Result.notOurs();
        }
        Matcher matcher = AUTHORIZATION.matcher(payload);
        if (matcher.matches()) {
            AgentAuthorizationChannelService.AnswerOutcome outcome = authorizationService.answer(
                    matcher.group(1), "a".equals(matcher.group(2)), fromUserId, origin);
            return fromRow(outcome.handled(), outcome.replyToUser(), outcome.asAlert(), outcome.request(),
                    () -> { });
        }
        matcher = QUESTION.matcher(payload);
        if (matcher.matches()) {
            ChatQuestionService.AnswerOutcome outcome = questionService.answer(
                    matcher.group(1), matcher.group(2), fromUserId, origin);
            ChatAuthorizationRequestEntity row = outcome.request();
            Runnable afterAck = outcome.settled() ? () -> questionService.applyIfComplete(row) : () -> { };
            return fromRow(outcome.handled(), outcome.replyToUser(), outcome.asAlert(), row, afterAck);
        }
        matcher = WORKFLOW_APPROVAL.matcher(payload);
        if (matcher.matches()) {
            WorkflowApprovalPressService.PressOutcome outcome = workflowApprovalService.press(
                    origin, matcher.group(1), "a".equals(matcher.group(2)), fromUserId);
            if (!outcome.handled()) {
                return Result.notOurs();
            }
            ApprovalChannelDeliveryEntity delivery = outcome.delivery();
            return new Result(true, outcome.replyToUser(), outcome.asAlert(), delivery.getTenantId(),
                    delivery.getOrgId(), delivery.getCredentialId(), delivery.getChatId(), () -> { });
        }
        logger.debug("[channel-inbound] {} press with a payload that is not ours, ignoring", channel);
        return Result.notOurs();
    }

    /**
     * Somebody replied in their own words to one of our messages.
     *
     * @param botProviderId the provider's id for the bot whose message was replied to, which is
     *                      what tells two bots' messages apart in one chat
     */
    public Result reply(PressOrigin origin, String chatId, String replyToMessageId, String botProviderId,
                        String text, String fromUserId) {
        ChatQuestionService.AnswerOutcome outcome = questionService.answerWithText(chatId, replyToMessageId,
                botProviderId, text, fromUserId, origin);
        ChatAuthorizationRequestEntity row = outcome.request();
        Runnable afterAck = outcome.settled() ? () -> questionService.applyIfComplete(row) : () -> { };
        // A recorded reply needs no line of its own: the closing edit (or, where messages cannot
        // be edited, the closing follow-up) already says what was recorded. A refusal does.
        String line = outcome.settled() ? null : outcome.replyToUser();
        return fromRow(outcome.handled(), line, outcome.asAlert(), row, afterAck);
    }

    /** True when a reply to this message would be one of ours to take. */
    public boolean isOurReply(String chatId, String replyToMessageId, String botProviderId) {
        return questionService.hasLiveQuestion(chatId, replyToMessageId, botProviderId);
    }

    private static Result fromRow(boolean handled, String line, boolean asAlert,
                                  ChatAuthorizationRequestEntity row, Runnable afterAck) {
        if (!handled || row == null) {
            return Result.notOurs();
        }
        return new Result(true, line, asAlert, row.getTenantId(), row.getOrganizationId(),
                row.getCredentialId(), row.getChatId(), afterAck);
    }
}
