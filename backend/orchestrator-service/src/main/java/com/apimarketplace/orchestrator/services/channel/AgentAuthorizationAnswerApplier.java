package com.apimarketplace.orchestrator.services.channel;

import com.apimarketplace.conversation.client.ConversationClient;
import com.apimarketplace.orchestrator.domain.channel.ChatAuthorizationRequestEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Applies a decision taken in a chat to the conversation that is waiting for it.
 *
 * <p>A one-method seam, extracted from {@link AgentAuthorizationChannelService} for
 * one reason: this is the only step that can actually authorize something, so it
 * is worth being able to test the surrounding flow without it, and worth being
 * obvious in the dependency graph.
 *
 * <p>It deliberately posts to the same conversation endpoints the in-app card
 * uses. Those endpoints hold the rule this feature must not re-implement: release
 * the parked call if one is still holding, otherwise write a single-shot grant the
 * next turn consumes. A late answer therefore still authorizes the action for the
 * agent's next run, which is exactly what a person pressing Approve hours later
 * expects.
 *
 * <p>It passes the request's fingerprint with the answer, which is what makes that grant
 * cover the action the person was shown rather than the next action of the same rule. In
 * the app the difference barely exists: the approved call resumes in the turn already
 * running, with the person watching. Here the grant is consumed by a scheduled run nobody
 * is watching, hours later, and "publish the September report" must not become permission
 * to publish whatever the agent composes next.
 */
@Component
public class AgentAuthorizationAnswerApplier {

    private static final Logger logger = LoggerFactory.getLogger(AgentAuthorizationAnswerApplier.class);

    private final ObjectProvider<ConversationClient> conversationClientProvider;

    public AgentAuthorizationAnswerApplier(ObjectProvider<ConversationClient> conversationClientProvider) {
        this.conversationClientProvider = conversationClientProvider;
    }

    /**
     * @return true when the answer was recorded. False means nothing was authorized,
     *         and the caller must say so rather than show a verdict.
     */
    public boolean apply(ChatAuthorizationRequestEntity request, boolean approve, String fromUserId) {
        ConversationClient client = conversationClientProvider.getIfAvailable();
        if (client == null) {
            logger.warn("[chat-auth] no conversation client in this deployment - decision not applied");
            return false;
        }
        boolean applied = client.answerToolAuthorization(
                request.getConversationId(), request.getTenantId(), request.getOrganizationId(),
                request.getRule(), request.getGateKey(), request.getFingerprint(), approve);
        logger.info("[chat-auth] {} rule {} on conversation {} from {} (applied={})",
                approve ? "approved" : "refused", request.getRule(), request.getConversationId(),
                fromUserId, applied);
        return applied;
    }
}
