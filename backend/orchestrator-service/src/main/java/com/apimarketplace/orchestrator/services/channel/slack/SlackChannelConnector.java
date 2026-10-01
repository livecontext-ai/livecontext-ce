package com.apimarketplace.orchestrator.services.channel.slack;

import com.apimarketplace.orchestrator.domain.ToolRef;
import com.apimarketplace.orchestrator.services.channel.CatalogCalls;
import com.apimarketplace.orchestrator.services.channel.ChatChannelConnector;
import com.apimarketplace.orchestrator.services.interfaces.ExecutionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Slack half of the chat-channel feature, through the catalog's Slack integration.
 *
 * <p>The account is the person's Slack OAuth connection to the PLATFORM's Slack app, so there is
 * nothing for them to create: connecting Slack is the whole setup. Presses come back to that app's
 * Interactivity Request URL ({@code /approval-callback/slack}), set once for the whole platform,
 * which is why the webhook is not ours to manage per bot.
 *
 * <p>Two things Slack does differently from Telegram, both declared rather than special-cased:
 * an interaction carries no text, so a typed answer cannot come back and "Other..." is not
 * offered; and a press has no toast, so the acknowledgement goes to the press's
 * {@code response_url} as an ephemeral message only the presser sees.
 */
@Component
public class SlackChannelConnector implements ChatChannelConnector {

    private static final Logger logger = LoggerFactory.getLogger(SlackChannelConnector.class);

    public static final String CHANNEL_ID = "slack";

    // apiSlug "slack" (SlugUtils of apiName "Slack"); tool slugs "slack-" + the endpoint name.
    // CatalogToolIdsTest pins each against scripts/api-migrations/slack.json.
    static final ToolRef TOOL_AUTH_TEST = new ToolRef("slack/slack-auth-test", 1);
    static final ToolRef TOOL_LIST_CONVERSATIONS = new ToolRef("slack/slack-list-conversations", 1);
    static final ToolRef TOOL_POST_MESSAGE = new ToolRef("slack/slack-post-message", 1);
    static final ToolRef TOOL_UPDATE_MESSAGE = new ToolRef("slack/slack-update-message", 1);
    static final ToolRef TOOL_JOIN_CONVERSATION = new ToolRef("slack/slack-join-conversation", 1);

    static final ToolRef TOOL_OPEN_CONVERSATION = new ToolRef("slack/slack-open-conversation", 1);

    /**
     * A conversation id as Slack writes it: C (channel), G (legacy private group), D (direct
     * message), upper case, with at least one digit. Slack forces channel names to lower case, so
     * an upper-case word is an id, and the digit keeps a name typed in capitals ("GENERAL") on the
     * name lookup instead of storing it as an id nothing answers to.
     */
    private static final Pattern CONVERSATION_ID = Pattern.compile("^[CGD](?=[A-Z0-9]*\\d)[A-Z0-9]{6,}$");
    /** A member id (U, or W on Enterprise Grid): a person, reached through their direct message. */
    private static final Pattern MEMBER_ID = Pattern.compile("^[UW](?=[A-Z0-9]*\\d)[A-Z0-9]{6,}$");
    /** Pages read before a listing stops, far above any workspace seen so far. */
    static final int MAX_LIST_PAGES = 10;
    /**
     * Chats a discovery offers. A bot token lists EVERY public channel of the workspace, and a
     * list of two thousand is not a choice anyone can make (nor one an agent's tool result can
     * carry whole): the ones the app is already in and the direct messages come first, and a
     * channel past the cap is still reachable by its #name.
     */
    static final int MAX_DISCOVERED_CHATS = 200;

    /** Slack's own limit on a section's text. */
    static final int SECTION_TEXT_MAX_CHARS = 3000;
    /** Room left for the closing line, the same reserve every provider keeps. */
    static final int VERDICT_RESERVE_CHARS = 128;

    /** The only prefix a press's response_url may have. Anything else is refused. */
    static final String RESPONSE_URL_PREFIX = "https://hooks.slack.com/";

    private final CatalogCalls calls;
    private final RestTemplate restTemplate;
    private final String signingSecret;

    @org.springframework.beans.factory.annotation.Autowired
    public SlackChannelConnector(CatalogCalls calls,
            @org.springframework.beans.factory.annotation.Value(
                    "${orchestrator.approval.slack.signing-secret:${SLACK_SIGNING_SECRET:}}") String signingSecret) {
        this(calls, new RestTemplate(), signingSecret);
    }

    SlackChannelConnector(CatalogCalls calls, RestTemplate restTemplate) {
        this(calls, restTemplate, "configured");
    }

    SlackChannelConnector(CatalogCalls calls, RestTemplate restTemplate, String signingSecret) {
        this.calls = calls;
        this.restTemplate = restTemplate;
        this.signingSecret = signingSecret;
    }

    /** Same property the inbound controller verifies with, which refuses every press without it. */
    @Override
    public String inboundProblem() {
        return signingSecret == null || signingSecret.isBlank()
                ? "Messages will reach this chat, but this installation has no Slack signing secret "
                        + "(SLACK_SIGNING_SECRET), so every button pressed in Slack will be refused. An "
                        + "administrator sets it to the Slack app's signing secret."
                : null;
    }

    @Override
    public String channelId() {
        return CHANNEL_ID;
    }

    @Override
    public Capabilities capabilities() {
        // Lists its channels; the inbound URL is the platform app's, set once; messages can be
        // edited; an interaction carries no text.
        return new Capabilities(true, false, true, false);
    }

    @Override
    public int maxDecisionTextChars() {
        return SECTION_TEXT_MAX_CHARS - VERDICT_RESERVE_CHARS;
    }

    @Override
    public Outcome<BotIdentity> verifyBot(String tenantId, Long credentialId) {
        return slackCall(TOOL_AUTH_TEST, Map.of(), tenantId, credentialId).map(out -> {
            if (out.get("user_id") == null) {
                return Outcome.<BotIdentity>failed("Slack answered without an identity. "
                        + "Reconnect Slack and try again.");
            }
            return Outcome.of(new BotIdentity(str(out.get("user_id")), str(out.get("user")),
                    str(out.get("team"))));
        });
    }

    @Override
    public Outcome<Optional<String>> currentWebhookUrl(String tenantId, Long credentialId) {
        // Not per bot: the platform app's Interactivity URL is set once, by whoever runs it.
        return Outcome.of(Optional.empty());
    }

    @Override
    public Outcome<Void> applyWebhook(String tenantId, Long credentialId, String url) {
        return Outcome.of(null);
    }

    @Override
    public Outcome<List<ChatCandidate>> discoverChats(String tenantId, Long credentialId) {
        return listConversations("public_channel,private_channel,im", 200, tenantId, credentialId).map(found -> {
            List<ChatCandidate> joined = new ArrayList<>();
            List<ChatCandidate> joinable = new ArrayList<>();
            for (Map<String, Object> channel : found) {
                String id = str(channel.get("id"));
                if (id == null) {
                    continue;
                }
                boolean direct = Boolean.TRUE.equals(channel.get("is_im"));
                boolean member = !Boolean.FALSE.equals(channel.get("is_member"));
                // A private channel the app is not in cannot be posted to, and listing it would
                // offer a destination whose test message is refused. A public one can: connecting
                // it makes the app join it first (sendTest).
                if (!direct && !member && Boolean.TRUE.equals(channel.get("is_private"))) {
                    continue;
                }
                String title = direct ? "Direct message" : "#" + str(channel.get("name"));
                ChatCandidate candidate = new ChatCandidate(id, title, direct ? "private" : "channel",
                        direct ? str(channel.get("user")) : null);
                (direct || member ? joined : joinable).add(candidate);
            }
            joined.addAll(joinable);
            return Outcome.of(joined.size() > MAX_DISCOVERED_CHATS
                    ? List.copyOf(joined.subList(0, MAX_DISCOVERED_CHATS)) : joined);
        });
    }

    /**
     * What a person typed, as Slack addresses it: "#ops" or "ops" becomes the channel's id, a
     * member id ("U0123...") becomes the id of the direct message with that person, and an id is
     * kept as it is.
     *
     * <p>People type the name they see in Slack, whose screens show no id without three clicks.
     * Stored as typed, the destination can work once and still be wrong: Slack may accept a name
     * or a member id for a new message, but an edit, and every press that comes back, speak in the
     * conversation id, so the row would never match an answer and the buttons would stay live.
     */
    @Override
    public Outcome<ChatCandidate> resolveDestination(String tenantId, Long credentialId, String chatId) {
        if (CONVERSATION_ID.matcher(chatId).matches()) {
            return Outcome.of(new ChatCandidate(chatId, null, null, null));
        }
        if (MEMBER_ID.matcher(chatId).matches()) {
            return slackCall(TOOL_OPEN_CONVERSATION, Map.of("users", chatId), tenantId, credentialId).map(out -> {
                String direct = out.get("channel") instanceof Map<?, ?> channel ? str(channel.get("id")) : null;
                return direct != null
                        ? Outcome.of(new ChatCandidate(direct, "Direct message", "private", chatId))
                        : Outcome.<ChatCandidate>failed("Slack did not open a direct message with " + chatId + ".");
            });
        }
        if (chatId.startsWith("@")) {
            return Outcome.failed("Slack cannot be given a person as " + chatId + ". Choose their direct message "
                    + "from the chats the app can see, or give their member ID (in their Slack profile: the "
                    + "three dots, then Copy member ID).");
        }
        String name = (chatId.startsWith("#") ? chatId.substring(1) : chatId).trim();
        String wanted = name.toLowerCase(Locale.ROOT);
        return listConversations("public_channel,private_channel", 1000, tenantId, credentialId).map(found -> {
            for (Map<String, Object> channel : found) {
                String id = str(channel.get("id"));
                if (id != null && (wanted.equals(lower(channel.get("name")))
                        || wanted.equals(lower(channel.get("name_normalized"))))) {
                    return Outcome.of(new ChatCandidate(id, "#" + str(channel.get("name")), "channel", null));
                }
            }
            return Outcome.<ChatCandidate>failed("Slack has no channel named #" + name + " that the app can see. "
                    + "Choose it from the chats the app can see, or give its id. A private channel needs the "
                    + "app invited first: type /invite followed by the app name in that channel.");
        });
    }

    /**
     * Every conversation of these types, page after page.
     *
     * <p>Slack pages a listing with a cursor and a bot token sees EVERY public channel, so in a
     * workspace of a few hundred channels the one the person wants is routinely past the first
     * page. Reading one page answered "not found" about a channel that exists.
     */
    private Outcome<List<Map<String, Object>>> listConversations(String types, int pageSize, String tenantId,
                                                                 Long credentialId) {
        List<Map<String, Object>> found = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < MAX_LIST_PAGES; page++) {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("types", types);
            params.put("exclude_archived", true);
            params.put("limit", pageSize);
            if (cursor != null) {
                params.put("cursor", cursor);
            }
            Outcome<Map<String, Object>> listed = slackCall(TOOL_LIST_CONVERSATIONS, params, tenantId, credentialId);
            if (!listed.ok()) {
                return Outcome.failed(listed.error());
            }
            found.addAll(CatalogCalls.listOf(listed.value().get("channels")));
            cursor = nextCursor(listed.value());
            if (cursor == null) {
                break;
            }
        }
        return Outcome.of(found);
    }

    /**
     * The test message of a connect, which is the one moment the app joins a channel.
     *
     * <p>A public channel the person chose as a destination is one the app is meant to write in,
     * and the app asks for {@code channels:join} for exactly this: sending them to type /invite
     * for a step the app can take itself is the setup going wrong in their hands. The join comes
     * FIRST and its answer is not needed: joining a channel the app is already in is a no-op,
     * and a channel an app cannot join (a private one) is answered by the test message itself
     * ("invite it"). Only here, never before an approval or a question: an administrator who
     * removed the app from a channel must not see it walk back in on the next message.
     */
    @Override
    public Outcome<Void> sendTest(String tenantId, Long credentialId, String chatId, String text) {
        if (chatId.startsWith("C")) {
            Outcome<Map<String, Object>> joined = slackCall(TOOL_JOIN_CONVERSATION, Map.of("channel", chatId),
                    tenantId, credentialId);
            if (!joined.ok()) {
                logger.info("[chat-channel-slack] did not join the channel before its test message: {}",
                        joined.error());
            }
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("channel", chatId);
        params.put("text", text);
        return slackCall(TOOL_POST_MESSAGE, params, tenantId, credentialId).map(out -> Outcome.of(null));
    }

    @Override
    public Outcome<String> sendDecisionRequest(String tenantId, Long credentialId, String chatId, String text,
                                               String approvePayload, String rejectPayload) {
        List<ChoiceOption> buttons = List.of(
                new ChoiceOption(APPROVE_LABEL, approvePayload),
                new ChoiceOption(REJECT_LABEL, rejectPayload));
        return post(tenantId, credentialId, chatId, text, buttons);
    }

    @Override
    public Outcome<String> sendChoiceRequest(String tenantId, Long credentialId, String chatId, String text,
                                             List<ChoiceOption> options) {
        if (options == null || options.isEmpty()) {
            return Outcome.failed("A question with no options has nothing to press.");
        }
        return post(tenantId, credentialId, chatId, text, options);
    }

    @Override
    public Outcome<Void> updateChoiceMarkup(String tenantId, Long credentialId, String chatId, String messageId,
                                            String text, List<ChoiceOption> options) {
        if (messageId == null || messageId.isBlank()) {
            return Outcome.failed("No message id: nothing to redraw.");
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("channel", chatId);
        params.put("ts", messageId);
        params.put("text", fallback(text));
        params.put("blocks", blocksOf(text, options, null));
        return slackCall(TOOL_UPDATE_MESSAGE, params, tenantId, credentialId).map(out -> Outcome.of(null));
    }

    @Override
    public Outcome<Void> closeDecisionRequest(String tenantId, Long credentialId, String chatId, String messageId,
                                              String originalText, String verdictLine) {
        if (messageId == null || messageId.isBlank()) {
            return Outcome.failed("No message id: nothing to close.");
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("channel", chatId);
        params.put("ts", messageId);
        params.put("text", fallback(originalText) + "\n" + verdictLine);
        // The body kept, the buttons gone, the verdict underneath as context. Sending blocks with
        // no actions block is what takes the buttons away; leaving blocks out would keep them.
        params.put("blocks", blocksOf(originalText, List.of(), verdictLine));
        return slackCall(TOOL_UPDATE_MESSAGE, params, tenantId, credentialId).map(out -> Outcome.of(null));
    }

    /**
     * Acknowledge a press through its {@code response_url}, the only acknowledgement Slack has.
     *
     * <p>The URL comes out of the callback payload, so it is checked before anything is sent to
     * it: only Slack's own hook host is accepted. Posting wherever a payload said would make this
     * endpoint a way to have the server call any address, which is the one thing a public
     * callback must never become.
     */
    @Override
    public Outcome<Void> ackButton(String tenantId, Long credentialId, String buttonEventId, String text,
                                   boolean asAlert) {
        if (buttonEventId == null || text == null || text.isBlank()) {
            return Outcome.of(null);
        }
        if (!buttonEventId.startsWith(RESPONSE_URL_PREFIX)) {
            logger.warn("[chat-channel-slack] refused to acknowledge through a response_url that is "
                    + "not Slack's own hook host");
            return Outcome.failed("Not a Slack response URL.");
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("response_type", "ephemeral");
            body.put("replace_original", false);
            body.put("text", text);
            restTemplate.postForEntity(buttonEventId, new HttpEntity<>(body, headers), String.class);
            return Outcome.of(null);
        } catch (Exception ex) {
            logger.info("[chat-channel-slack] could not acknowledge a press: {}", ex.getMessage());
            return Outcome.failed(ex.getMessage());
        }
    }

    // ---- internals ----

    static final String APPROVE_LABEL = "Approve";
    static final String REJECT_LABEL = "Refuse";

    private Outcome<String> post(String tenantId, Long credentialId, String chatId, String text,
                                 List<ChoiceOption> buttons) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("channel", chatId);
        params.put("text", fallback(text));
        params.put("blocks", blocksOf(text, buttons, null));
        return slackCall(TOOL_POST_MESSAGE, params, tenantId, credentialId).map(out -> {
            String ts = str(out.get("ts"));
            return ts != null ? Outcome.of(ts)
                    : Outcome.<String>failed("Slack did not say which message it posted.");
        });
    }

    /**
     * A section with the body, an actions block with one button per option, and, when given, a
     * context line under them.
     *
     * <p>Each button's {@code value} is our payload and nothing else, and each has its own
     * {@code action_id}, which Slack requires to be unique within the block.
     */
    static List<Map<String, Object>> blocksOf(String text, List<ChoiceOption> buttons, String context) {
        List<Map<String, Object>> blocks = new ArrayList<>();
        blocks.add(Map.of("type", "section",
                "text", Map.of("type", "mrkdwn", "text", fallback(text))));
        if (buttons != null && !buttons.isEmpty()) {
            List<Map<String, Object>> elements = new ArrayList<>();
            for (int index = 0; index < buttons.size(); index++) {
                ChoiceOption option = buttons.get(index);
                Map<String, Object> button = new LinkedHashMap<>();
                button.put("type", "button");
                button.put("action_id", "lc_" + index);
                button.put("text", Map.of("type", "plain_text", "text", capLabel(option.label())));
                button.put("value", option.payload());
                elements.add(button);
            }
            blocks.add(Map.of("type", "actions", "elements", elements));
        }
        if (context != null && !context.isBlank()) {
            blocks.add(Map.of("type", "context",
                    "elements", List.of(Map.of("type", "mrkdwn", "text", context))));
        }
        return blocks;
    }

    /** Slack button text is capped at 75 characters. */
    private static String capLabel(String label) {
        String value = label != null ? label : "";
        return value.length() > 75 ? value.substring(0, 72) + "..." : value;
    }

    /**
     * The text, escaped the way Slack asks: {@code &}, {@code <} and {@code >} are its markup, so an
     * agent's or a workflow's text containing {@code <!channel>} would otherwise notify a whole
     * channel. Escaped first, then capped; a cap that lands inside an entity drops that partial
     * entity rather than send it broken.
     */
    static String fallback(String text) {
        String value = text != null && !text.isBlank() ? text : " ";
        String escaped = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        if (escaped.length() <= SECTION_TEXT_MAX_CHARS) {
            return escaped;
        }
        String cut = escaped.substring(0, SECTION_TEXT_MAX_CHARS);
        int amp = cut.lastIndexOf('&');
        return amp >= 0 && cut.indexOf(';', amp) < 0 ? cut.substring(0, amp) : cut;
    }

    /**
     * One Slack call, with Slack's own error convention turned into a failure.
     *
     * <p>Slack answers HTTP 200 to a refused call and puts {@code ok: false} with an error code in
     * the body. Treating that as success is how a message that was never posted gets recorded as
     * sent. The codes a person can act on get a sentence; the rest are reported as Slack gave them.
     */
    private Outcome<Map<String, Object>> slackCall(ToolRef tool, Map<String, Object> params,
                                                   String tenantId, Long credentialId) {
        return calls.call(CHANNEL_ID, tool, params, tenantId, credentialId).map(result -> {
            Map<String, Object> out = result.output() != null ? result.output() : Map.of();
            if (Boolean.FALSE.equals(out.get("ok"))) {
                return Outcome.<Map<String, Object>>failed(explain(str(out.get("error"))));
            }
            return Outcome.of(out);
        });
    }

    private static String nextCursor(Map<String, Object> out) {
        if (out.get("response_metadata") instanceof Map<?, ?> metadata) {
            String cursor = str(metadata.get("next_cursor"));
            return cursor != null && !cursor.isBlank() ? cursor : null;
        }
        return null;
    }

    private static String lower(Object value) {
        return value != null ? String.valueOf(value).toLowerCase(Locale.ROOT) : null;
    }

    static String explain(String code) {
        if (code == null) {
            return "Slack refused the call.";
        }
        return switch (code) {
            case "not_in_channel" -> "The Slack app is not in that channel. Invite it with /invite "
                    + "and try again.";
            case "channel_not_found" -> "Slack cannot find that channel, or the app cannot see it.";
            case "invalid_auth", "not_authed", "token_revoked", "account_inactive" ->
                    "The Slack connection is no longer valid. Reconnect Slack and try again.";
            case "missing_scope" -> "The Slack connection lacks a permission this needs. Reconnect "
                    + "Slack to grant it.";
            default -> "Slack refused the call: " + code + ".";
        };
    }

    private static String str(Object value) {
        return value != null ? String.valueOf(value) : null;
    }
}
