package com.apimarketplace.orchestrator.services.channel.telegram;

import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.orchestrator.domain.channel.ChatAuthorizationRequestEntity;
import com.apimarketplace.orchestrator.services.channel.CallbackTokens;
import com.apimarketplace.orchestrator.services.channel.ChatChannelConnectorRegistry;
import com.apimarketplace.orchestrator.services.channel.ChatQuestionService;
import com.apimarketplace.orchestrator.services.channel.ChatQuestionService.AnswerOutcome;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A press on a question's button, and the reply somebody types instead.
 *
 * <p>The sibling of {@link TelegramAuthorizationCallbackHandler}, and it keeps the same
 * contract: swallow everything, answer quickly, never let a failure reach the webhook. Telegram
 * retries a non-2xx aggressively, so an exception escaping here becomes the same press arriving
 * again and again.
 *
 * <p>The user id it checks comes from an unauthenticated payload. The real boundary is
 * membership of the chat plus a token nobody can guess.
 */
@Component
public class TelegramQuestionCallbackHandler {

    private static final Logger logger = LoggerFactory.getLogger(TelegramQuestionCallbackHandler.class);

    private static final Pattern CALLBACK_DATA = Pattern.compile(
            "^" + CallbackTokens.QUESTION_PREFIX + ":([A-Za-z0-9_-]{16,48}):(o\\d{1,2}|done|other)$");

    private final ChatQuestionService service;
    private final ChatChannelConnectorRegistry connectors;
    private final MeterRegistry meterRegistry;

    public TelegramQuestionCallbackHandler(ChatQuestionService service,
                                           ChatChannelConnectorRegistry connectors,
                                           MeterRegistry meterRegistry) {
        this.service = service;
        this.connectors = connectors;
        this.meterRegistry = meterRegistry;
    }

    /** True when this payload is a press on one of our question buttons. */
    public boolean isQuestionCallback(Map<String, Object> webhookPayload) {
        Map<String, Object> callbackQuery = callbackQueryOf(webhookPayload);
        if (callbackQuery == null) {
            return false;
        }
        Object data = callbackQuery.get("data");
        return data instanceof String s && CALLBACK_DATA.matcher(s).matches();
    }

    /**
     * True when this payload is a reply to a question of OURS that is still waiting.
     *
     * <p>It reads the database, and it has to. Claiming a reply on shape alone was wrong and
     * not narrowly wrong: the same bot commonly drives a workflow trigger, the webhook
     * endpoint stops dispatching anything this claims, and every "Reply" a person used in that
     * chat would have been swallowed with an "approval callback diverted" line and nothing
     * delivered. A support inbox built on a Telegram trigger would simply have stopped working.
     *
     * <p>The read is bounded by {@code idx_chat_auth_requests_reply_target}, a partial index on
     * exactly this pair over live rows, and it only runs for updates that ARE replies carrying
     * text. What it costs is one indexed lookup on those; what it buys is that a reply we do
     * not own keeps flowing to the workflow untouched.
     */
    public boolean isPossibleTextReply(Map<String, Object> webhookPayload) {
        Map<String, Object> message = messageOf(webhookPayload);
        if (message == null || !(message.get("reply_to_message") instanceof Map<?, ?> replyMap)
                || !(message.get("text") instanceof String text) || text.isBlank()) {
            return false;
        }
        return service.hasLiveQuestion(chatIdOf(message), asString(replyMap.get("message_id")),
                fromIdOf(castMap(replyMap)));
    }

    /** Handle a button press end to end. Always swallows, always returns quickly. */
    public void handle(Map<String, Object> webhookPayload) {
        try {
            Map<String, Object> callbackQuery = callbackQueryOf(webhookPayload);
            if (callbackQuery == null) {
                return;
            }
            Matcher matcher = CALLBACK_DATA.matcher(String.valueOf(callbackQuery.get("data")));
            if (!matcher.matches()) {
                return;
            }
            AnswerOutcome outcome = service.answer(matcher.group(1), matcher.group(2),
                    fromIdOf(callbackQuery), TelegramPressOrigin.ofCallback(callbackQuery));
            if (!outcome.handled()) {
                // Unknown token: a stale message from a purged workspace, or a guess. With no
                // row there is no credential to even acknowledge the press with.
                meterRegistry.counter("chat.question.errors", "type", "UnknownCallbackToken").increment();
                logger.info("[chat-question-telegram] press with unknown token (stale or forged), ignoring");
                return;
            }
            acknowledge(outcome, callbackQuery.get("id") != null
                    ? String.valueOf(callbackQuery.get("id")) : null);
            // AFTER the acknowledgement, never before. Handing the answers over starts the
            // agent's follow-up turn, a full synchronous LLM run, and Telegram stops accepting
            // an acknowledgement for a press after a short window. Behind the turn, the last
            // button of a question spun and was never confirmed.
            if (outcome.settled()) {
                service.applyIfComplete(outcome.request());
            }
        } catch (Exception ex) {
            meterRegistry.counter("chat.question.errors", "type", ex.getClass().getSimpleName()).increment();
            logger.warn("[chat-question-telegram] swallowed callback handling: {}", ex.getMessage());
        }
    }

    /**
     * Handle a typed reply, when it answers one of our questions.
     *
     * <p>A reply that names a message we do not know is somebody else's conversation, and the
     * silence is the point: this runs for every reply in a chat that may also carry a workflow
     * trigger.
     */
    public void handleReply(Map<String, Object> webhookPayload) {
        try {
            Map<String, Object> message = messageOf(webhookPayload);
            if (message == null) {
                return;
            }
            Object replyTo = message.get("reply_to_message");
            if (!(replyTo instanceof Map<?, ?> replyMap)) {
                return;
            }
            // reply_to_message.from is the BOT that sent the question being answered. It is what
            // tells two bots' messages apart in a private chat, where the chat id is the person's
            // own and each bot numbers its messages independently.
            AnswerOutcome outcome = service.answerWithText(
                    chatIdOf(message), asString(replyMap.get("message_id")),
                    fromIdOf(castMap(replyMap)), String.valueOf(message.get("text")),
                    fromIdOf(message), TelegramPressOrigin.ofMessage(message));
            if (!outcome.handled()) {
                return;
            }
            // A press can be acknowledged through the button that raised it; a typed reply has
            // no such channel, so a REFUSAL here has to be a message or it is nothing at all.
            // The strings existed before this and nothing ever sent them: somebody answering
            // an expired question, or answering from outside the allow-list, watched their
            // message disappear. When the answer WAS recorded the closing edit already says so
            // and a second message would only repeat it.
            // Said back when the reply was NOT recorded, read from the outcome rather than from
            // the row: the row can read RESOLVED in memory for a save that then failed, which
            // silently swallowed "your answer could not be saved" for exactly the person who
            // needed to try again.
            if (outcome.request() != null && outcome.replyToUser() != null && !outcome.settled()) {
                sayBack(outcome.request(), outcome.replyToUser());
            }
            if (outcome.settled()) {
                service.applyIfComplete(outcome.request());
            }
            logger.info("[chat-question-telegram] handled a typed answer on request {}",
                    outcome.request() != null ? outcome.request().getId() : null);
        } catch (Exception ex) {
            meterRegistry.counter("chat.question.errors", "type", ex.getClass().getSimpleName()).increment();
            logger.warn("[chat-question-telegram] swallowed reply handling: {}", ex.getMessage());
        }
    }

    /**
     * Send one line back into the chat, for a reply that could not be recorded.
     *
     * <p>Best effort and never throws: the answer was already refused, and failing the webhook
     * because the explanation could not be delivered would only have Telegram send the same
     * reply again.
     */
    private void sayBack(ChatAuthorizationRequestEntity row, String line) {
        try {
            connectors.forChannel(row.getChannel()).ifPresent(connector ->
                    TenantResolver.runWithOrgScope(row.getOrganizationId(), () ->
                            connector.sendTest(row.getTenantId(), row.getCredentialId(),
                                    row.getChatId(), line)));
        } catch (Exception ex) {
            logger.info("[chat-question-telegram] could not tell the person why their reply was "
                    + "not recorded: {}", ex.getMessage());
        }
    }

    /**
     * Stop the provider showing the press as in flight, and say what it did.
     *
     * <p>Under the row's workspace scope: this thread serves a public webhook with no org
     * header, and the catalog call that acknowledges the press has to resolve the same
     * workspace credential the question was sent with.
     */
    private void acknowledge(AnswerOutcome outcome, String buttonEventId) {
        if (outcome.replyToUser() == null || outcome.request() == null) {
            return;
        }
        var row = outcome.request();
        connectors.forChannel(row.getChannel()).ifPresent(connector ->
                TenantResolver.runWithOrgScope(row.getOrganizationId(), () ->
                        connector.ackButton(row.getTenantId(), row.getCredentialId(), buttonEventId,
                                outcome.replyToUser(), outcome.asAlert())));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> callbackQueryOf(Map<String, Object> payload) {
        if (payload == null) {
            return null;
        }
        Object cq = payload.get("callback_query");
        return cq instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> messageOf(Map<String, Object> payload) {
        if (payload == null) {
            return null;
        }
        Object message = payload.get("message");
        return message instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    private static String chatIdOf(Map<String, Object> message) {
        Object chat = message.get("chat");
        if (chat instanceof Map<?, ?> chatMap && chatMap.get("id") != null) {
            return String.valueOf(chatMap.get("id"));
        }
        return null;
    }

    private static String fromIdOf(Map<String, Object> source) {
        Object from = source.get("from");
        if (from instanceof Map<?, ?> fromMap && fromMap.get("id") != null) {
            return String.valueOf(fromMap.get("id"));
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    private static String asString(Object value) {
        return value != null ? String.valueOf(value) : null;
    }
}
