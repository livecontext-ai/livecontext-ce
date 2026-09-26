package com.apimarketplace.orchestrator.services.channel.whatsapp;

import com.apimarketplace.orchestrator.domain.ToolRef;
import com.apimarketplace.orchestrator.domain.channel.ChatChannelBotEntity;
import com.apimarketplace.orchestrator.repository.ChatChannelBotRepository;
import com.apimarketplace.orchestrator.services.channel.CatalogCalls;
import com.apimarketplace.orchestrator.services.channel.ChatChannelConnector;
import com.apimarketplace.orchestrator.services.interfaces.ExecutionResult;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * WhatsApp half of the chat-channel feature, through the catalog's WhatsApp Business (Cloud API)
 * integration.
 *
 * <p><b>The account.</b> A Meta access token plus the id of the phone number it sends from. The
 * token alone does not say which number, so the phone number id is the one thing asked for at
 * connect, and it is what this connector stores as the bot's identity.
 *
 * <p><b>The destination</b> is a person's phone number, in international form. There is no list
 * to pick it from: WhatsApp gives a business no directory of who wrote to it.
 *
 * <p><b>Coming back.</b> Presses and replies arrive on the webhook the person subscribes in their
 * Meta app, pointed at {@code /approval-callback/whatsapp/{botId}} with the verify token generated
 * here. A button press carries its id; a typed reply carries the id of the message it answers,
 * which is how an "Other" answer finds its question.
 *
 * <p><b>What WhatsApp does not do.</b> A sent message cannot be edited, so a settled question is
 * closed with a short follow-up that quotes it. And a business may only write freely within 24
 * hours of the person's last message; outside it Meta refuses with error 131047, which is
 * explained to the person rather than passed through as a code.
 */
@Component
public class WhatsAppChannelConnector implements ChatChannelConnector {

    public static final String CHANNEL_ID = "whatsapp";

    // apiSlug "whatsapp-business"; tool slugs from the API key "whats_app_business". Pinned by
    // CatalogToolIdsTest.
    static final ToolRef TOOL_GET_PHONE_NUMBER =
            new ToolRef("whatsapp-business/whats-app-business-get-phone-number", 1);
    static final ToolRef TOOL_SEND_TEXT =
            new ToolRef("whatsapp-business/whats-app-business-send-text-message", 1);
    static final ToolRef TOOL_SEND_INTERACTIVE =
            new ToolRef("whatsapp-business/whats-app-business-send-interactive-message", 1);

    /** Body of an interactive message. */
    static final int BODY_MAX_CHARS = 1024;
    /** A plain text message. */
    static final int TEXT_MAX_CHARS = 4096;
    /** Reply buttons: at most three, titles at most 20 characters. */
    static final int MAX_BUTTONS = 3;
    static final int BUTTON_TITLE_MAX = 20;
    /** List rows: at most ten, titles at most 24 characters. */
    static final int MAX_LIST_ROWS = 10;
    static final int ROW_TITLE_MAX = 24;

    /** Meta's "outside the 24-hour customer service window" error. */
    static final String OUTSIDE_WINDOW = "131047";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final CatalogCalls calls;
    private final ChatChannelBotRepository botRepository;

    public WhatsAppChannelConnector(CatalogCalls calls, ChatChannelBotRepository botRepository) {
        this.calls = calls;
        this.botRepository = botRepository;
    }

    @Override
    public String channelId() {
        return CHANNEL_ID;
    }

    @Override
    public Capabilities capabilities() {
        // No chat list; the webhook is subscribed in the Meta app; no editing; typed replies do come back.
        return new Capabilities(false, false, false, true);
    }

    @Override
    public String accountSettingLabel() {
        return "the phone number id the messages are sent from (Meta app, WhatsApp, API Setup, "
                + "Phone number ID; a long number, not the phone number itself)";
    }

    @Override
    public int maxDecisionTextChars() {
        return BODY_MAX_CHARS;
    }

    @Override
    public String normalizeChatId(String chatId) {
        return digitsOf(chatId);
    }

    @Override
    public Outcome<BotIdentity> verifyBot(String tenantId, Long credentialId) {
        return Outcome.failed("Connecting WhatsApp needs " + accountSettingLabel() + ".");
    }

    @Override
    public Outcome<BotIdentity> verifyBot(String tenantId, Long credentialId, String phoneNumberId) {
        String id = digitsOf(phoneNumberId);
        if (id == null || id.isEmpty()) {
            return verifyBot(tenantId, credentialId);
        }
        return calls.call(CHANNEL_ID, TOOL_GET_PHONE_NUMBER, Map.of("phone_number_id", id), tenantId, credentialId)
                .map(result -> {
                    Map<String, Object> number = result.output() != null ? result.output() : Map.of();
                    String display = str(number.get("display_phone_number"));
                    String name = str(number.get("verified_name"));
                    return Outcome.of(new BotIdentity(id, display != null ? display : id,
                            name != null ? name : display != null ? display : id));
                });
    }

    /** The verify token: kept across reconnects, since the person pasted it into their Meta app. */
    @Override
    public String newInboundKey(String phoneNumberId, String currentKey) {
        if (currentKey != null && !currentKey.isBlank()) {
            return currentKey;
        }
        byte[] bytes = new byte[18];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @Override
    public String storedAccountSetting(String botIdentity, String inboundKey) {
        return botIdentity;
    }

    @Override
    public String setupInstructions(String callbackUrl, String inboundKey) {
        return "In your Meta app, open WhatsApp, Configuration, and edit the Webhook: Callback URL "
                + callbackUrl + " and Verify token " + inboundKey + ". Save, then subscribe to the "
                + "\"messages\" field. WhatsApp only lets a business write within 24 hours of the "
                + "person's last message, so have them send any message to your number first.";
    }

    @Override
    public Outcome<Optional<String>> currentWebhookUrl(String tenantId, Long credentialId) {
        return Outcome.of(Optional.empty());
    }

    @Override
    public Outcome<Void> applyWebhook(String tenantId, Long credentialId, String url) {
        return Outcome.of(null);
    }

    @Override
    public Outcome<List<ChatCandidate>> discoverChats(String tenantId, Long credentialId) {
        return Outcome.failed("WhatsApp has no list of chats: connect the person's phone number directly.");
    }

    @Override
    public Outcome<Void> sendTest(String tenantId, Long credentialId, String chatId, String text) {
        return sendText(tenantId, credentialId, chatId, text, null).map(id -> Outcome.of(null));
    }

    @Override
    public Outcome<String> sendDecisionRequest(String tenantId, Long credentialId, String chatId, String text,
                                               String approvePayload, String rejectPayload) {
        return sendChoiceRequest(tenantId, credentialId, chatId, text, List.of(
                new ChoiceOption("Approve", approvePayload), new ChoiceOption("Refuse", rejectPayload)));
    }

    @Override
    public Outcome<String> sendChoiceRequest(String tenantId, Long credentialId, String chatId, String text,
                                             List<ChoiceOption> options) {
        if (options == null || options.isEmpty()) {
            return Outcome.failed("A question with no options has nothing to press.");
        }
        if (options.size() > MAX_LIST_ROWS) {
            return Outcome.failed("WhatsApp shows at most " + MAX_LIST_ROWS + " choices in one message.");
        }
        Map<String, Object> params = base(credentialId, chatId);
        if (params == null) {
            return Outcome.failed(NOT_CONNECTED);
        }
        params.put("type", "interactive");
        params.put("interactive", interactiveOf(cap(text, BODY_MAX_CHARS), options));
        return messageIdOf(calls.call(CHANNEL_ID, TOOL_SEND_INTERACTIVE, params, tenantId, credentialId));
    }

    /**
     * WhatsApp cannot redraw a sent message. A multi-choice question's ticks therefore do not
     * appear on the buttons; the presser is told "Added." or "Removed." instead, and the closing
     * follow-up lists what was recorded.
     */
    @Override
    public Outcome<Void> updateChoiceMarkup(String tenantId, Long credentialId, String chatId, String messageId,
                                            String text, List<ChoiceOption> options) {
        return Outcome.of(null);
    }

    /** No edit on WhatsApp: the verdict is a follow-up quoting the original message. */
    @Override
    public Outcome<Void> closeDecisionRequest(String tenantId, Long credentialId, String chatId, String messageId,
                                              String originalText, String verdictLine) {
        return sendText(tenantId, credentialId, chatId, verdictLine, messageId).map(id -> Outcome.of(null));
    }

    /**
     * The presser's line, as a reply to the message they pressed on.
     *
     * @param buttonEventId {@code <phone>|<message id>}, as the inbound controller builds it
     */
    @Override
    public Outcome<Void> ackButton(String tenantId, Long credentialId, String buttonEventId, String text,
                                   boolean asAlert) {
        if (text == null || text.isBlank() || buttonEventId == null) {
            return Outcome.of(null);
        }
        int bar = buttonEventId.indexOf('|');
        String to = bar > 0 ? buttonEventId.substring(0, bar) : buttonEventId;
        String replyTo = bar > 0 && bar < buttonEventId.length() - 1 ? buttonEventId.substring(bar + 1) : null;
        return sendText(tenantId, credentialId, to, text, replyTo).map(id -> Outcome.of(null));
    }

    // ---- internals ----

    private Outcome<String> sendText(String tenantId, Long credentialId, String chatId, String text,
                                     String replyToMessageId) {
        Map<String, Object> params = base(credentialId, chatId);
        if (params == null) {
            return Outcome.failed(NOT_CONNECTED);
        }
        params.put("type", "text");
        params.put("text", Map.of("body", cap(text, TEXT_MAX_CHARS), "preview_url", false));
        if (replyToMessageId != null && !replyToMessageId.isBlank()) {
            params.put("context", Map.of("message_id", replyToMessageId));
        }
        return messageIdOf(calls.call(CHANNEL_ID, TOOL_SEND_TEXT, params, tenantId, credentialId));
    }

    static final String NOT_CONNECTED = "This WhatsApp account is not connected yet, so the number it "
            + "sends from is unknown. Connect it with its phone number id first.";

    /**
     * Every send names the number it goes out from, and a call only carries the credential. The
     * number is the bot row's identity, stored at connect (which writes the row before its test
     * message), and a credential backs at most one WhatsApp bot. Null when there is no row yet.
     */
    private Map<String, Object> base(Long credentialId, String chatId) {
        String phoneNumberId = credentialId == null ? null
                : botRepository.findFirstByChannelAndCredentialId(CHANNEL_ID, credentialId)
                        .map(ChatChannelBotEntity::getBotIdentity).orElse(null);
        if (phoneNumberId == null || phoneNumberId.isBlank()) {
            return null;
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("phone_number_id", phoneNumberId);
        params.put("messaging_product", "whatsapp");
        params.put("recipient_type", "individual");
        params.put("to", digitsOf(chatId));
        return params;
    }

    /**
     * Three or fewer choices are reply buttons; more are a list, which WhatsApp shows behind a
     * single "Choose" button. Our payload is the button or row id, which comes back on a press.
     */
    static Map<String, Object> interactiveOf(String body, List<ChoiceOption> options) {
        Map<String, Object> interactive = new LinkedHashMap<>();
        interactive.put("body", Map.of("text", body == null || body.isBlank() ? "Please choose." : body));
        if (options.size() <= MAX_BUTTONS) {
            List<Map<String, Object>> buttons = new ArrayList<>();
            List<String> titles = uniqueTitles(options, BUTTON_TITLE_MAX);
            for (int i = 0; i < options.size(); i++) {
                buttons.add(Map.of("type", "reply", "reply",
                        Map.of("id", options.get(i).payload(), "title", titles.get(i))));
            }
            interactive.put("type", "button");
            interactive.put("action", Map.of("buttons", buttons));
        } else {
            List<Map<String, Object>> rows = new ArrayList<>();
            List<String> titles = uniqueTitles(options, ROW_TITLE_MAX);
            for (int i = 0; i < options.size(); i++) {
                rows.add(Map.of("id", options.get(i).payload(), "title", titles.get(i)));
            }
            interactive.put("type", "list");
            interactive.put("action", Map.of("button", "Choose",
                    "sections", List.of(Map.of("title", "Options", "rows", rows))));
        }
        return interactive;
    }

    /**
     * Titles cut to Meta's limit, made unique: Meta refuses a message whose buttons or rows share a
     * title, and two long options that start alike become the same title once cut. A clash gets a
     * number in place of its last characters ("Deploy to productio" then "Deploy to producti 2").
     */
    static List<String> uniqueTitles(List<ChoiceOption> options, int max) {
        List<String> titles = new ArrayList<>();
        for (ChoiceOption option : options) {
            String title = cap(option.label(), max);
            int n = 2;
            while (titles.contains(title)) {
                String suffix = " " + n++;
                title = cap(option.label(), max - suffix.length()) + suffix;
            }
            titles.add(title);
        }
        return titles;
    }

    private static Outcome<String> messageIdOf(Outcome<ExecutionResult> call) {
        if (!call.ok()) {
            return Outcome.failed(explain(call.error()));
        }
        Object messages = call.value().output() != null ? call.value().output().get("messages") : null;
        List<Map<String, Object>> list = CatalogCalls.listOf(messages);
        Object id = list.isEmpty() ? null : list.get(0).get("id");
        return id != null ? Outcome.of(String.valueOf(id))
                : Outcome.failed("WhatsApp did not say which message it sent.");
    }

    /** Meta's codes for the failures a person can do something about, in words. */
    static String explain(String error) {
        if (error == null) {
            return "WhatsApp refused the message.";
        }
        if (error.contains(OUTSIDE_WINDOW)) {
            return "WhatsApp only lets a business write within 24 hours of the person's last message. "
                    + "Ask them to send any message to your WhatsApp number, then try again.";
        }
        if (error.contains("131030")) {
            return "This number is not in the test recipients of your Meta app. Add it under "
                    + "WhatsApp, API Setup, or move the app to live mode.";
        }
        if (error.contains("190") && error.toLowerCase().contains("token")) {
            return "The WhatsApp access token has expired or was revoked. Replace it with a permanent "
                    + "System User token and connect again.";
        }
        return error;
    }

    /** "+33 6 12-34" and "0033612..." are how people write numbers; Meta wants the bare digits. */
    static String digitsOf(String value) {
        if (value == null) {
            return null;
        }
        String digits = value.replaceAll("[^0-9]", "");
        return digits.startsWith("00") ? digits.substring(2) : digits;
    }

    private static String cap(String text, int max) {
        String value = text != null ? text : "";
        return value.length() > max ? value.substring(0, max) : value;
    }

    private static String str(Object value) {
        return value != null ? String.valueOf(value) : null;
    }
}
