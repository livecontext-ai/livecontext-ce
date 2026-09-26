package com.apimarketplace.orchestrator.services.channel;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.agent.tools.ask.UserQuestionGateKeys;
import com.apimarketplace.conversation.client.ConversationClient;
import com.apimarketplace.orchestrator.domain.channel.ChatAuthorizationRequestEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Hands answers given in a chat back to the conversation that asked them.
 *
 * <p>Two halves, and both are needed, which is what makes this different from its approval
 * sibling. The first posts the answers to the same endpoint the in-app card uses, so the rule
 * about releasing a parked call lives in exactly one place. The second is the half the app does
 * not need: when nothing was parked any more, somebody has to start the turn that reads the
 * answer. In the app the frontend does that by sending the answers as the person's next
 * message. From a chat there is no frontend, so it happens here or the answer is recorded and
 * nothing ever acts on it.
 *
 * <p>The follow-up turn is fired the same way a scheduled agent's turn is fired, through
 * {@code sendChatSync}, so it runs where those runs run and is billed where they are billed.
 */
@Component
public class ChatQuestionAnswerApplier {

    private static final Logger logger = LoggerFactory.getLogger(ChatQuestionAnswerApplier.class);

    /** Marks the follow-up turn as one nobody is watching, so its own questions go to the chat. */
    static final String SOURCE = "CHANNEL_REPLY";

    private final ObjectProvider<ConversationClient> conversationClientProvider;
    private final ObjectProvider<AgentClient> agentClientProvider;

    public ChatQuestionAnswerApplier(ObjectProvider<ConversationClient> conversationClientProvider,
                                     ObjectProvider<AgentClient> agentClientProvider) {
        this.conversationClientProvider = conversationClientProvider;
        this.agentClientProvider = agentClientProvider;
    }

    /**
     * @param group every row of one {@code ask_user} call, all answered
     * @return true when the answers reached the conversation
     */
    public boolean apply(List<ChatAuthorizationRequestEntity> group) {
        if (group == null || group.isEmpty()) {
            return false;
        }
        ConversationClient client = conversationClientProvider.getIfAvailable();
        if (client == null) {
            logger.warn("[chat-question] no conversation client in this deployment - answers not applied");
            return false;
        }
        ChatAuthorizationRequestEntity first = group.get(0);
        List<Map<String, Object>> answers = new ArrayList<>();
        for (ChatAuthorizationRequestEntity row : group) {
            if (row.getAnswer() != null) {
                answers.add(row.getAnswer());
            }
        }
        if (answers.isEmpty()) {
            return false;
        }
        // The call id from the GATE KEY, never the group key: the group key is minted per
        // delivery precisely because a provider's call id is not unique, and the answer
        // endpoint keys on the real one.
        String toolCallId = UserQuestionGateKeys.toolCallIdOf(first.getGateKey());
        if (toolCallId == null) {
            logger.warn("[chat-question] request {} carries a gate key of another shape, so the "
                    + "call it answers cannot be named; nothing applied", first.getId());
            return false;
        }
        boolean released = client.answerUserQuestion(first.getConversationId(), first.getTenantId(),
                first.getOrganizationId(), toolCallId, first.getGateKey(), answers);
        if (released) {
            // A call was still parked, which means the turn that asked is somehow still running.
            // It reads the answers as its tool result and carries on by itself.
            logger.info("[chat-question] answers released the parked call on conversation {}",
                    first.getConversationId());
            return true;
        }
        return startFollowUpTurn(client, first, answers);
    }

    /**
     * Start the turn that reads the answer, because nobody else will.
     *
     * <p>The text is the same shape the in-app path sends as the person's next message, so the
     * agent reads its answer in the same words whichever surface it came from.
     */
    private boolean startFollowUpTurn(ConversationClient client, ChatAuthorizationRequestEntity first,
                                      List<Map<String, Object>> answers) {
        if (first.getAgentId() == null) {
            // Only an agent-backed conversation can be resumed: a general chat with nobody in
            // it has no model to run and no turn to start. Not reachable today, since a run
            // with no agent is never unattended, and logged rather than guessed at if it ever is.
            logger.info("[chat-question] answers recorded on conversation {} with no agent to "
                    + "resume; nothing was started", first.getConversationId());
            return true;
        }
        AgentClient agentClient = agentClientProvider.getIfAvailable();
        String model = null;
        String provider = null;
        if (agentClient != null) {
            try {
                var agent = agentClient.getAgent(first.getAgentId(), first.getTenantId());
                if (agent != null) {
                    model = agent.getModelName();
                    provider = agent.getModelProvider();
                }
            } catch (Exception ex) {
                // The turn can still run on the conversation's own defaults. Losing the model
                // is worse than not starting at all only if it fails, and it does not.
                logger.info("[chat-question] could not read the agent's model for the follow-up "
                        + "turn on conversation {}: {}", first.getConversationId(), ex.getMessage());
            }
        }
        try {
            client.sendChatSync(first.getTenantId(), first.getConversationId(), render(answers),
                    first.getAgentId().toString(), model, provider, SOURCE, null,
                    first.getOrganizationId());
            return true;
        } catch (Exception ex) {
            logger.warn("[chat-question] answers recorded but the follow-up turn on conversation "
                    + "{} could not be started: {}", first.getConversationId(), ex.getMessage());
            return false;
        }
    }

    /** "Answer to Tone: Formal" per question, which is what the in-app path sends too. */
    static String render(List<Map<String, Object>> answers) {
        List<String> lines = new ArrayList<>();
        for (Map<String, Object> answer : answers) {
            Object header = answer.get("header");
            Object freeText = answer.get("freeText");
            String value = freeText != null ? String.valueOf(freeText) : joinSelected(answer.get("selected"));
            lines.add("Answer to " + (header != null ? header : "the question") + ": " + value);
        }
        return String.join("\n", lines);
    }

    private static String joinSelected(Object selected) {
        if (selected instanceof List<?> list) {
            List<String> labels = new ArrayList<>();
            for (Object entry : list) {
                if (entry != null) {
                    labels.add(String.valueOf(entry));
                }
            }
            return String.join(", ", labels);
        }
        return "";
    }

}
