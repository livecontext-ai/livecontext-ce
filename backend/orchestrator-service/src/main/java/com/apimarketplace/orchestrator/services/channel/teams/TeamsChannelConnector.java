package com.apimarketplace.orchestrator.services.channel.teams;

import com.apimarketplace.orchestrator.domain.ToolRef;
import com.apimarketplace.orchestrator.services.channel.CatalogCalls;
import com.apimarketplace.orchestrator.services.channel.ChatChannelConnector;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Microsoft Teams half of the chat-channel feature, through the catalog's Microsoft Teams (Graph)
 * integration.
 *
 * <p><b>The account</b> is the person's own Microsoft account, connected with the platform's
 * Microsoft app: messages go out as that person, into one of their chats. There is no bot to
 * register and nothing to configure in Azure.
 *
 * <p><b>Buttons are links.</b> A Graph message cannot carry a button that calls back (that needs a
 * registered Bot Framework bot), so each choice is an Adaptive Card {@code Action.OpenUrl} to a
 * decision page on this installation. The page shows what the link will do and decides only when
 * its button is pressed: a GET never decides, because Teams and link scanners open links on their
 * own. The link does not say who opened it, which is why a Teams destination cannot have an
 * allow-list ({@link #identifiesPresser()}).
 *
 * <p>No editing and no typed replies: a decision is closed with a short follow-up message.
 */
@Component
public class TeamsChannelConnector implements ChatChannelConnector {

    public static final String CHANNEL_ID = "teams";
    static final String CREDENTIAL_INTEGRATION = "microsoftteams";

    // apiSlug "microsoft-teams"; tool slugs from the API key "microsoft_teams". Pinned by CatalogToolIdsTest.
    static final ToolRef TOOL_LIST_CHATS = new ToolRef("microsoft-teams/microsoft-teams-list-chats", 1);
    static final ToolRef TOOL_SEND_CHAT_MESSAGE = new ToolRef("microsoft-teams/microsoft-teams-send-chat-message", 1);

    /** Kept well under Teams' card size limit (about 28 KB) once the actions are added. */
    static final int TEXT_MAX_CHARS = 4000;
    static final String DECIDE_PATH = "/decide";

    private final CatalogCalls calls;
    private final ObjectMapper objectMapper;
    private final String publicBaseUrl;

    public TeamsChannelConnector(CatalogCalls calls, ObjectMapper objectMapper,
                                 @Value("${orchestrator.webhook.base-url:http://localhost:8080}") String publicBaseUrl) {
        this.calls = calls;
        this.objectMapper = objectMapper;
        this.publicBaseUrl = publicBaseUrl;
    }

    @Override
    public String channelId() {
        return CHANNEL_ID;
    }

    @Override
    public String credentialIntegration() {
        return CREDENTIAL_INTEGRATION;
    }

    @Override
    public Capabilities capabilities() {
        // Lists the person's chats; no callback URL at all; no editing; no typed replies.
        return new Capabilities(true, false, false, false);
    }

    @Override
    public boolean identifiesPresser() {
        return false;
    }

    @Override
    public int maxDecisionTextChars() {
        return TEXT_MAX_CHARS;
    }

    /**
     * Graph offers no "who am I" through this integration, so the account is proved by listing one
     * chat, and named by its credential: one Teams credential is one Microsoft account.
     */
    @Override
    public Outcome<BotIdentity> verifyBot(String tenantId, Long credentialId) {
        return calls.call(CHANNEL_ID, TOOL_LIST_CHATS, Map.of("$top", 1), tenantId, credentialId)
                .map(result -> Outcome.of(new BotIdentity("credential-" + credentialId,
                        "Microsoft Teams", "Microsoft Teams account")));
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
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("$top", 50);
        params.put("$expand", "members");
        return calls.call(CHANNEL_ID, TOOL_LIST_CHATS, params, tenantId, credentialId).map(result -> {
            List<ChatCandidate> chats = new ArrayList<>();
            for (Map<String, Object> chat : CatalogCalls.listOf(result.output())) {
                String id = str(chat.get("id"));
                if (id != null) {
                    chats.add(new ChatCandidate(id, titleOf(chat), str(chat.get("chatType")), null));
                }
            }
            return Outcome.of(chats);
        });
    }

    @Override
    public Outcome<Void> sendTest(String tenantId, Long credentialId, String chatId, String text) {
        return sendText(tenantId, credentialId, chatId, text).map(id -> Outcome.of(null));
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
        String attachmentId = UUID.randomUUID().toString();
        String card;
        try {
            card = objectMapper.writeValueAsString(cardOf(cap(text, TEXT_MAX_CHARS), options));
        } catch (JsonProcessingException ex) {
            return Outcome.failed("The Teams card could not be built: " + ex.getMessage());
        }
        Map<String, Object> attachment = new LinkedHashMap<>();
        attachment.put("id", attachmentId);
        attachment.put("contentType", "application/vnd.microsoft.card.adaptive");
        attachment.put("content", card);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("chatId", chatId);
        params.put("body", Map.of("contentType", "html",
                "content", "<attachment id=\"" + attachmentId + "\"></attachment>"));
        params.put("attachments", List.of(attachment));
        return messageIdOf(calls.call(CHANNEL_ID, TOOL_SEND_CHAT_MESSAGE, params, tenantId, credentialId));
    }

    /** Teams cannot redraw a sent card; the decision page tells the presser "Added." or "Removed.". */
    @Override
    public Outcome<Void> updateChoiceMarkup(String tenantId, Long credentialId, String chatId, String messageId,
                                            String text, List<ChoiceOption> options) {
        return Outcome.of(null);
    }

    /** No edit: the verdict is a follow-up naming what it closes, since its links stay on the card. */
    @Override
    public Outcome<Void> closeDecisionRequest(String tenantId, Long credentialId, String chatId, String messageId,
                                              String originalText, String verdictLine) {
        String about = originalText != null && !originalText.isBlank()
                ? ": " + cap(originalText.strip().replaceAll("\\s+", " "), 120) : "";
        return sendText(tenantId, credentialId, chatId, verdictLine + about).map(id -> Outcome.of(null));
    }

    /** Nothing to send: the presser reads their line on the decision page itself. */
    @Override
    public Outcome<Void> ackButton(String tenantId, Long credentialId, String buttonEventId, String text,
                                   boolean asAlert) {
        return Outcome.of(null);
    }

    // ---- internals ----

    /** The page a choice's link opens, carrying the button payload it stands for. */
    String decisionUrl(String payload) {
        String base = publicBaseUrl != null ? publicBaseUrl.trim() : "";
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/approval-callback/" + CHANNEL_ID + DECIDE_PATH + "?p="
                + URLEncoder.encode(payload, StandardCharsets.UTF_8);
    }

    Map<String, Object> cardOf(String text, List<ChoiceOption> options) {
        List<Map<String, Object>> actions = new ArrayList<>();
        for (ChoiceOption option : options) {
            actions.add(Map.of("type", "Action.OpenUrl", "title", option.label(), "url", decisionUrl(option.payload())));
        }
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("$schema", "http://adaptivecards.io/schemas/adaptive-card.json");
        card.put("type", "AdaptiveCard");
        card.put("version", "1.4");
        card.put("body", List.of(Map.of("type", "TextBlock", "text", text, "wrap", true)));
        card.put("actions", actions);
        return card;
    }

    private Outcome<String> sendText(String tenantId, Long credentialId, String chatId, String text) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("chatId", chatId);
        params.put("body", Map.of("contentType", "text", "content", cap(text, TEXT_MAX_CHARS)));
        return messageIdOf(calls.call(CHANNEL_ID, TOOL_SEND_CHAT_MESSAGE, params, tenantId, credentialId));
    }

    private static Outcome<String> messageIdOf(Outcome<com.apimarketplace.orchestrator.services.interfaces.ExecutionResult> call) {
        if (!call.ok()) {
            return Outcome.failed(call.error());
        }
        Object id = call.value().output() != null ? call.value().output().get("id") : null;
        return id != null ? Outcome.of(String.valueOf(id))
                : Outcome.failed("Teams did not say which message it sent.");
    }

    /** A chat's topic, or the names of the people in it, which is how Teams itself titles a chat. */
    static String titleOf(Map<String, Object> chat) {
        String topic = str(chat.get("topic"));
        if (topic != null && !topic.isBlank()) {
            return topic;
        }
        List<String> names = new ArrayList<>();
        for (Map<String, Object> member : CatalogCalls.listOf(chat.get("members"))) {
            String name = str(member.get("displayName"));
            if (name != null && !name.isBlank()) {
                names.add(name);
            }
        }
        return names.isEmpty() ? "Chat" : String.join(", ", names);
    }

    private static String cap(String text, int max) {
        String value = text != null ? text : "";
        return value.length() > max ? value.substring(0, max) : value;
    }

    private static String str(Object value) {
        return value != null ? String.valueOf(value) : null;
    }
}
