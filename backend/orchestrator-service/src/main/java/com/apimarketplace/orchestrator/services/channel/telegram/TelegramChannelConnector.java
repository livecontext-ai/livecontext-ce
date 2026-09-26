package com.apimarketplace.orchestrator.services.channel.telegram;

import com.apimarketplace.orchestrator.domain.ToolRef;
import com.apimarketplace.orchestrator.services.channel.ChatChannelConnector;
import com.apimarketplace.orchestrator.services.interfaces.ExecutionResult;
import com.apimarketplace.orchestrator.services.interfaces.ToolsGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Telegram half of connecting a chat channel, through the catalog's Telegram
 * integration and the user's OWN bot credential (BYOK): the bot token never
 * reaches this service, only a credential id does.
 *
 * <p>Two provider rules are encoded here because getting either wrong produces a
 * connect that reports success and delivers nothing:
 *
 * <ol>
 *   <li><b>{@code getUpdates} is refused while a webhook is active</b> (409
 *       Conflict). Discovery must therefore run before the webhook is pointed, or
 *       behind a caller that temporarily unpoints one it owns. This class reports
 *       the refusal as a sentence instead of an empty list, because "no chats" and
 *       "I was not allowed to look" send the person to completely different
 *       places.</li>
 *   <li><b>A bot has exactly ONE webhook URL.</b> Many bots here are already
 *       webhooked to a workflow trigger, and that configuration is supported: the
 *       generic webhook path diverts approval callbacks itself. So the caller
 *       reads the current URL before writing one, and this class never overwrites
 *       on its own initiative.</li>
 * </ol>
 */
@Component
public class TelegramChannelConnector implements ChatChannelConnector {

    private static final Logger logger = LoggerFactory.getLogger(TelegramChannelConnector.class);

    public static final String CHANNEL_ID = "telegram";

    // Same apiSlug/toolSlug ids the approval notifier and an mcp:telegram step use.
    private static final ToolRef TOOL_GET_ME = new ToolRef("telegram/telegram-get-me", 1);
    private static final ToolRef TOOL_GET_WEBHOOK_INFO = new ToolRef("telegram/telegram-get-webhook-info", 1);
    private static final ToolRef TOOL_SET_WEBHOOK = new ToolRef("telegram/telegram-set-webhook", 1);
    private static final ToolRef TOOL_GET_UPDATES = new ToolRef("telegram/telegram-get-updates", 1);
    private static final ToolRef TOOL_SEND_MESSAGE = new ToolRef("telegram/telegram-send-message", 1);
    private static final ToolRef TOOL_EDIT_MESSAGE_TEXT = new ToolRef("telegram/telegram-edit-message-text", 1);
    private static final ToolRef TOOL_ANSWER_CALLBACK_QUERY =
            new ToolRef("telegram/telegram-answer-callback-query", 1);

    static final String APPROVE_LABEL = "✅ Approve";
    static final String REJECT_LABEL = "❌ Reject";

    /** Telegram's cap on a plain text message. */
    private static final int TELEGRAM_TEXT_MAX_CHARS = 4096;
    /**
     * Room held back at send time for the verdict line the close appends. Without it a message
     * sent at exactly the cap leaves the edit nothing to add: the buttons still go away, but the
     * person never sees what was decided.
     *
     * <p>Headroom over the longest verdict the product can append, not a number picked blind: the
     * first one was picked (64) and was already too small for the expiry verdict at 70 characters,
     * so the reserve invented to protect the verdict would have truncated it. The margin above 72
     * is deliberate slack for the next verdict somebody writes.
     *
     * <p>What keeps it honest is the assertion, not the arithmetic: {@code
     * TelegramChannelConnectorTest} measures this against every member of
     * {@code AgentAuthorizationChannelService.Verdict}, which is the only thing a close site can
     * pass, so a verdict that outgrows the reserve fails a build instead of losing its tail in
     * somebody's chat.
     */
    static final int TEXT_VERDICT_RESERVE_CHARS = 128;

    /**
     * What a caller may send, so it can store the body it actually sent rather than guess.
     *
     * <p>Same number this connector caps to at send time, exposed so the two cannot drift: a
     * caller storing an uncapped body would keep text this class already dropped, and the
     * closing edit would then be built from a message nobody ever saw.
     */
    @Override
    public int maxDecisionTextChars() {
        return TELEGRAM_TEXT_MAX_CHARS - TEXT_VERDICT_RESERVE_CHARS;
    }

    /** Update kinds that can reveal a chat the bot may write to. */
    private static final List<String> CHAT_BEARING_UPDATE_KEYS =
            List.of("message", "edited_message", "channel_post", "edited_channel_post", "my_chat_member");

    private final ObjectProvider<ToolsGateway> toolsGatewayProvider;

    /**
     * The secret Telegram will echo in {@code X-Telegram-Bot-Api-Secret-Token} on every update.
     *
     * <p>It lives here rather than on the shared service because it is Telegram's: the same value
     * {@code ApprovalCallbackController} checks, in the header Telegram sends, under the property
     * Telegram's own deployment sets. A shared "webhook secret" would be one provider's secret
     * handed to every other one.
     */
    private final String webhookSecret;

    public TelegramChannelConnector(ObjectProvider<ToolsGateway> toolsGatewayProvider,
                                    @Value("${orchestrator.approval.telegram.webhook-secret:}") String webhookSecret) {
        this.toolsGatewayProvider = toolsGatewayProvider;
        this.webhookSecret = webhookSecret;
    }

    @Override
    public String undeliveredHint() {
        return "This usually means nobody has opened a conversation with the bot yet, "
                + "or the bot is not a member of the group.";
    }

    @Override
    public String channelId() {
        return CHANNEL_ID;
    }

    @Override
    public Outcome<BotIdentity> verifyBot(String tenantId, Long credentialId) {
        return call(TOOL_GET_ME, Map.of(), tenantId, credentialId).map(result -> {
            Map<String, Object> me = asMap(result.output() != null ? result.output().get("result") : null);
            if (me == null || me.get("id") == null) {
                return Outcome.<BotIdentity>failed("Telegram answered without a bot identity. "
                        + "The credential may hold something other than a bot token.");
            }
            String username = str(me.get("username"));
            return Outcome.of(new BotIdentity(str(me.get("id")), username, str(me.get("first_name"))));
        });
    }

    @Override
    public Outcome<Optional<String>> currentWebhookUrl(String tenantId, Long credentialId) {
        return call(TOOL_GET_WEBHOOK_INFO, Map.of(), tenantId, credentialId).map(result -> {
            Map<String, Object> info = asMap(result.output() != null ? result.output().get("result") : null);
            String url = info != null ? str(info.get("url")) : null;
            return Outcome.of(url == null || url.isBlank() ? Optional.<String>empty() : Optional.of(url));
        });
    }

    @Override
    public Outcome<Void> applyWebhook(String tenantId, Long credentialId, String url) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("url", url);
        if (webhookSecret != null && !webhookSecret.isBlank()) {
            params.put("secret_token", webhookSecret);
        }
        // Only the update kinds this product acts on. Narrowing it keeps a busy
        // group's traffic off the endpoint, and callback_query is the one that
        // carries a button press, which is the entire point of pointing a webhook.
        params.put("allowed_updates", List.of("message", "callback_query", "my_chat_member"));
        return call(TOOL_SET_WEBHOOK, params, tenantId, credentialId).map(r -> Outcome.of(null));
    }

    @Override
    public Outcome<List<ChatCandidate>> discoverChats(String tenantId, Long credentialId) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("limit", 100);
        params.put("allowed_updates", CHAT_BEARING_UPDATE_KEYS);
        Outcome<ExecutionResult> raw = call(TOOL_GET_UPDATES, params, tenantId, credentialId);
        if (!raw.ok()) {
            // 409 is the webhook conflict, and it is the single most likely failure
            // here. Say what it means, since the caller cannot read Telegram's wording.
            String error = raw.error();
            if (error != null && (error.contains("409") || error.toLowerCase().contains("conflict"))) {
                return Outcome.unavailable("Telegram cannot list this bot's chats: the bot is already connected "
                        + "(its messages go to a webhook), and Telegram only offers the list to a bot that has "
                        + "none. Give the destination directly instead. For yourself: write to @userinfobot in "
                        + "Telegram, it answers with your id (a number like 123456789). For a group: its id, "
                        + "which starts with -100. Either way, write to the bot (or add it to the group) first, "
                        + "or it will not be allowed to send there.");
            }
            return Outcome.failed(error);
        }
        Object resultObj = raw.value().output() != null ? raw.value().output().get("result") : null;
        if (!(resultObj instanceof List<?> updates)) {
            return Outcome.of(List.of());
        }
        Map<String, ChatCandidate> byChatId = new LinkedHashMap<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Object updateObj : updates) {
            Map<String, Object> update = asMap(updateObj);
            if (update == null) {
                continue;
            }
            for (String key : CHAT_BEARING_UPDATE_KEYS) {
                Map<String, Object> envelope = asMap(update.get(key));
                if (envelope == null) {
                    continue;
                }
                Map<String, Object> chat = asMap(envelope.get("chat"));
                if (chat == null || chat.get("id") == null) {
                    continue;
                }
                String chatId = str(chat.get("id"));
                if (!seen.add(chatId)) {
                    continue;
                }
                Map<String, Object> from = asMap(envelope.get("from"));
                byChatId.put(chatId, new ChatCandidate(
                        chatId, chatLabel(chat), str(chat.get("type")),
                        from != null ? str(from.get("username")) : null));
            }
        }
        return Outcome.of(new ArrayList<>(byChatId.values()));
    }

    @Override
    public Outcome<Void> sendTest(String tenantId, Long credentialId, String chatId, String text) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("chat_id", chatId);
        params.put("text", text);
        return call(TOOL_SEND_MESSAGE, params, tenantId, credentialId).map(r -> Outcome.of(null));
    }

    @Override
    public Outcome<String> sendDecisionRequest(String tenantId, Long credentialId, String chatId, String text,
                                               String approvePayload, String rejectPayload) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("chat_id", chatId);
        params.put("text", capText(text, TELEGRAM_TEXT_MAX_CHARS - TEXT_VERDICT_RESERVE_CHARS));
        params.put("reply_markup", Map.of("inline_keyboard", List.of(List.of(
                Map.of("text", APPROVE_LABEL, "callback_data", approvePayload),
                Map.of("text", REJECT_LABEL, "callback_data", rejectPayload)))));
        return call(TOOL_SEND_MESSAGE, params, tenantId, credentialId)
                .map(result -> Outcome.of(messageIdOf(result)));
    }

    @Override
    public Outcome<String> sendChoiceRequest(String tenantId, Long credentialId, String chatId, String text,
                                             List<ChoiceOption> options) {
        if (options == null || options.isEmpty()) {
            return Outcome.failed("A question with no options has nothing to press.");
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("chat_id", chatId);
        // Same reserve as a decision message: the close appends a line saying what was
        // answered, and a body that filled the limit would lose it.
        params.put("text", capText(text, TELEGRAM_TEXT_MAX_CHARS - TEXT_VERDICT_RESERVE_CHARS));
        params.put("reply_markup", Map.of("inline_keyboard", keyboardOf(options)));
        return call(TOOL_SEND_MESSAGE, params, tenantId, credentialId)
                .map(result -> Outcome.of(messageIdOf(result)));
    }

    @Override
    public Outcome<Void> updateChoiceMarkup(String tenantId, Long credentialId, String chatId, String messageId,
                                            String text, List<ChoiceOption> options) {
        if (messageId == null || messageId.isBlank()) {
            return Outcome.failed("No message id: nothing to redraw.");
        }
        if (options == null || options.isEmpty()) {
            return Outcome.failed("A question with no options has nothing to press.");
        }
        // editMessageText rather than editMessageReplyMarkup, which the catalog does not carry.
        // Telegram accepts an edit whose text is unchanged as long as the markup differs, and
        // the markup always differs here: this is only ever called to move a tick. Adding the
        // dedicated endpoint would mean a catalog JSON change plus a production re-import, for
        // a call this one already makes correctly.
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("chat_id", chatId);
        params.put("message_id", messageId);
        params.put("text", capText(text, TELEGRAM_TEXT_MAX_CHARS - TEXT_VERDICT_RESERVE_CHARS));
        params.put("reply_markup", Map.of("inline_keyboard", keyboardOf(options)));
        return call(TOOL_EDIT_MESSAGE_TEXT, params, tenantId, credentialId).map(r -> Outcome.of(null));
    }

    /** One option per row. See the interface for why not two. */
    private static List<List<Map<String, Object>>> keyboardOf(List<ChoiceOption> options) {
        return options.stream()
                .map(option -> List.<Map<String, Object>>of(
                        Map.of("text", option.label(), "callback_data", option.payload())))
                .toList();
    }

    @Override
    public Outcome<Void> closeDecisionRequest(String tenantId, Long credentialId, String chatId, String messageId,
                                              String originalText, String verdictLine) {
        if (messageId == null || messageId.isBlank()) {
            return Outcome.failed("No message id: nothing to close.");
        }
        String base = originalText != null ? originalText : "";
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("chat_id", chatId);
        params.put("message_id", messageId);
        params.put("text", capText(base.isBlank() ? verdictLine : base + "\n\n" + verdictLine,
                TELEGRAM_TEXT_MAX_CHARS));
        // An empty keyboard is how the buttons are taken away; omitting the field
        // would leave the original ones live on a question that is already settled.
        params.put("reply_markup", Map.of("inline_keyboard", List.of()));
        return call(TOOL_EDIT_MESSAGE_TEXT, params, tenantId, credentialId).map(r -> Outcome.of(null));
    }

    @Override
    public Outcome<Void> ackButton(String tenantId, Long credentialId, String buttonEventId, String text,
                                   boolean asAlert) {
        if (buttonEventId == null || buttonEventId.isBlank()) {
            return Outcome.of(null);
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("callback_query_id", buttonEventId);
        params.put("text", text);
        if (asAlert) {
            params.put("show_alert", true);
        }
        return call(TOOL_ANSWER_CALLBACK_QUERY, params, tenantId, credentialId).map(r -> Outcome.of(null));
    }

    // ========================================================================
    // HELPERS
    // ========================================================================

    /**
     * One catalog round trip, with every failure turned into a sentence.
     *
     * <p><b>No billing-scope markers are passed</b>, only the credential ones.
     * Connecting a channel is setup, not the work a caller is paying for, and a
     * scope key here would charge a chat turn or a workflow run for the round
     * trips that verify a bot.
     */
    private Outcome<ExecutionResult> call(ToolRef tool, Map<String, Object> params,
                                          String tenantId, Long credentialId) {
        ToolsGateway gateway = toolsGatewayProvider.getIfAvailable();
        if (gateway == null) {
            return Outcome.failed("The catalog tools gateway is unavailable in this deployment.");
        }
        try {
            ExecutionResult result = gateway.executeTool(tool, params, tenantId, credentials(credentialId));
            if (!result.isSuccess()) {
                String message = result.getErrorMessage();
                return Outcome.failed(message != null && !message.isBlank()
                        ? message
                        : "Telegram refused the " + tool.getToolId() + " call.");
            }
            return Outcome.of(result);
        } catch (Exception ex) {
            logger.warn("[chat-channel-telegram] {} failed: {}", tool.getToolId(), ex.getMessage());
            return Outcome.failed("Telegram call failed: " + ex.getMessage());
        }
    }

    /**
     * Pin the exact bot. Unlike the approval node's delegation - which may leave
     * the credential blank and let the catalog fall back to the tenant's default
     * Telegram credential - a channel connect always names one: the identity it
     * verifies and the webhook it reads must belong to the same bot the link will
     * be attached to, and a silent fallback would attach them to different ones.
     */
    private Map<String, Object> credentials(Long credentialId) {
        return Map.of(
                "__credentialSource__", "user",
                "__selectedCredentialId__", credentialId,
                // STRICT, or the pin above is advisory: without this marker the catalog
                // softens an id it cannot resolve into the integration's default
                // credential, so a deleted or foreign credential would silently run every
                // call against a DIFFERENT bot. The row would then carry credential A
                // with bot B's identity, and the webhook that brings button presses back
                // would belong to a bot the row does not name.
                "__credentialSelectionStrict__", true);
    }

    /** Trim to a cap so a long request body never fails the send or the edit. */
    private static String capText(String text, int maxChars) {
        return text != null && text.length() > maxChars ? text.substring(0, maxChars) : text;
    }

    /** send_message projected output: { ok, result: { message_id, ... } }. */
    private static String messageIdOf(ExecutionResult result) {
        Map<String, Object> sent = asMap(result.output() != null ? result.output().get("result") : null);
        return sent != null && sent.get("message_id") != null ? String.valueOf(sent.get("message_id")) : null;
    }

    /** A private chat carries first/last name, a group or channel carries a title. */
    private static String chatLabel(Map<String, Object> chat) {
        String title = str(chat.get("title"));
        if (title != null && !title.isBlank()) {
            return title;
        }
        String first = str(chat.get("first_name"));
        String last = str(chat.get("last_name"));
        String name = ((first != null ? first : "") + " " + (last != null ? last : "")).trim();
        if (!name.isBlank()) {
            return name;
        }
        String username = str(chat.get("username"));
        return username != null ? "@" + username : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    private static String str(Object value) {
        return value != null ? String.valueOf(value) : null;
    }
}
