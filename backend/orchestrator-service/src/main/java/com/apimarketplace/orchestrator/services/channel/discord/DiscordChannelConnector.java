package com.apimarketplace.orchestrator.services.channel.discord;

import com.apimarketplace.orchestrator.domain.ToolRef;
import com.apimarketplace.orchestrator.services.channel.CatalogCalls;
import com.apimarketplace.orchestrator.services.channel.ChatChannelConnector;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Discord half of the chat-channel feature, through the catalog's Discord integration.
 *
 * <p>The account is a BOT token (the integration's {@code bot_token} variant, sent as
 * {@code Authorization: Bot ...}). A user's OAuth token cannot post in a channel, so that variant
 * cannot serve here.
 *
 * <p>Because the bot is the person's own Discord application, presses come back to that
 * application's Interactions Endpoint URL, which they set once in the Developer Portal to
 * {@code /approval-callback/discord/{botId}}. Every interaction is signed with Ed25519 and has to be
 * verified with the application's public key, which is why the key is the one thing asked for at
 * connect. It is public, and Discord itself refuses to save the URL until verification works, so a
 * wrong key is caught during setup rather than on the first press.
 */
@Component
public class DiscordChannelConnector implements ChatChannelConnector {

    public static final String CHANNEL_ID = "discord";

    // apiSlug "discord"; tool slugs "discord-" + the endpoint name. Pinned by CatalogToolIdsTest.
    static final ToolRef TOOL_GET_CURRENT_USER = new ToolRef("discord/discord-get-current-user", 1);
    static final ToolRef TOOL_GET_CURRENT_APPLICATION = new ToolRef("discord/discord-get-current-application", 1);
    static final ToolRef TOOL_LIST_GUILDS = new ToolRef("discord/discord-list-guilds", 1);
    static final ToolRef TOOL_GET_GUILD_CHANNELS = new ToolRef("discord/discord-get-guild-channels", 1);
    static final ToolRef TOOL_SEND_MESSAGE = new ToolRef("discord/discord-send-message", 1);
    static final ToolRef TOOL_EDIT_MESSAGE = new ToolRef("discord/discord-edit-message", 1);
    static final ToolRef TOOL_EXECUTE_WEBHOOK = new ToolRef("discord/discord-execute-webhook", 1);

    /** Discord's EPHEMERAL message flag: only the person who pressed sees the line. */
    static final int EPHEMERAL = 64;

    /** Discord's limit on a message's content. */
    static final int CONTENT_MAX_CHARS = 2000;
    static final int VERDICT_RESERVE_CHARS = 128;
    /** Discord allows five buttons per row and five rows. */
    static final int BUTTONS_PER_ROW = 5;
    /** Text channels only: type 0. Voice, category and forum channels cannot take these messages. */
    static final int GUILD_TEXT = 0;

    /**
     * Mentions parsed from nothing. The text is an agent's or a workflow's, and an "@everyone" in
     * it must not ping a whole server: the message still shows it, it just notifies nobody.
     */
    static final Map<String, Object> NO_MENTIONS = Map.of("parse", List.of());

    private final CatalogCalls calls;

    public DiscordChannelConnector(CatalogCalls calls) {
        this.calls = calls;
    }

    @Override
    public String channelId() {
        return CHANNEL_ID;
    }

    @Override
    public Capabilities capabilities() {
        // Lists its servers' text channels; the endpoint URL is set by the person in the Developer
        // Portal; messages can be edited; an interaction carries no text.
        return new Capabilities(true, false, true, false);
    }

    @Override
    public String accountSettingLabel() {
        return "the application's public key (Discord Developer Portal, your application, "
                + "General Information, Public Key)";
    }

    @Override
    public int maxDecisionTextChars() {
        return CONTENT_MAX_CHARS - VERDICT_RESERVE_CHARS;
    }

    @Override
    public Outcome<BotIdentity> verifyBot(String tenantId, Long credentialId) {
        return verifyBot(tenantId, credentialId, null);
    }

    @Override
    public Outcome<BotIdentity> verifyBot(String tenantId, Long credentialId, String publicKey) {
        if (publicKey != null && !publicKey.isBlank() && !isPublicKey(publicKey)) {
            // Checked before any call: a key that is not 64 hex characters can never verify a
            // signature, and finding out on the first press is finding out too late.
            return Outcome.failed("That is not a Discord public key: it should be 64 hexadecimal "
                    + "characters, copied from General Information in the Developer Portal.");
        }
        return calls.call(CHANNEL_ID, TOOL_GET_CURRENT_USER, Map.of(), tenantId, credentialId).map(result -> {
            Map<String, Object> me = result.output() != null ? result.output() : Map.of();
            if (me.get("id") == null) {
                return Outcome.<BotIdentity>failed("Discord answered without an identity. "
                        + "Check that the credential holds the bot token.");
            }
            if (!Boolean.TRUE.equals(me.get("bot"))) {
                return Outcome.<BotIdentity>failed("This Discord credential is a user token, not a bot "
                        + "token. A user cannot post buttons in a channel: connect Discord with your "
                        + "application's bot token.");
            }
            BotIdentity identity = new BotIdentity(str(me.get("id")), str(me.get("username")),
                    str(me.get("username")));
            return publicKey == null || publicKey.isBlank() ? Outcome.of(identity)
                    : keyMatches(tenantId, credentialId, publicKey).map(ok -> Outcome.of(identity));
        });
    }

    /**
     * The key given must be the one Discord holds for THIS bot's application.
     *
     * <p>It is what every press on this bot's endpoint is verified with, so a key taken on trust
     * would let whoever connected the bot sign presses with a keypair of their own and pick any
     * presser id they like, allow-list included. Discord answers the application's
     * {@code verify_key} to the bot token itself, so the check costs one call and no trust.
     */
    private Outcome<Void> keyMatches(String tenantId, Long credentialId, String publicKey) {
        Outcome<com.apimarketplace.orchestrator.services.interfaces.ExecutionResult> application =
                calls.call(CHANNEL_ID, TOOL_GET_CURRENT_APPLICATION, Map.of(), tenantId, credentialId);
        if (!application.ok()) {
            // Refused, never waved through. The usual cause on an installation whose API catalog
            // predates this check is that the call itself does not exist yet, so say what to do.
            return Outcome.failed("The public key could not be checked with Discord (" + application.error()
                    + "). If that says the tool does not exist, this installation's API catalog needs its "
                    + "Discord update: an administrator re-imports the Discord API, or links the install to "
                    + "LiveContext Cloud to receive catalog updates.");
        }
        return application.map(result -> {
            Object key = result.output() != null ? result.output().get("verify_key") : null;
            if (key == null) {
                return Outcome.<Void>failed("Discord did not return this application's public key, so it "
                        + "cannot be checked. Try again in a moment.");
            }
            if (!String.valueOf(key).trim().equalsIgnoreCase(publicKey.trim())) {
                return Outcome.<Void>failed("That public key is not this bot's application key. Copy it "
                        + "from General Information of the application the bot token belongs to.");
            }
            return Outcome.of(null);
        });
    }

    @Override
    public String newInboundKey(String publicKey, String currentKey) {
        return publicKey != null && !publicKey.isBlank() ? publicKey.trim().toLowerCase() : currentKey;
    }

    @Override
    public String storedAccountSetting(String botIdentity, String inboundKey) {
        return inboundKey;
    }

    @Override
    public String setupInstructions(String callbackUrl, String inboundKey) {
        return "In the Discord Developer Portal, open your application, go to General Information and "
                + "set Interactions Endpoint URL to " + callbackUrl + " then save. Discord checks it "
                + "right away, so if it refuses, the public key given here does not match that "
                + "application. The bot must also be in the server, with permission to send messages "
                + "in that channel.";
    }

    @Override
    public String undeliveredHint() {
        return "Check that the bot was invited to that server with permission to send messages in the "
                + "channel. A brand-new bot may also need to have been online once (connected to Discord's "
                + "gateway) before Discord lets it post.";
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
        return calls.call(CHANNEL_ID, TOOL_LIST_GUILDS, Map.of(), tenantId, credentialId).map(guildsResult -> {
            List<ChatCandidate> chats = new ArrayList<>();
            for (Map<String, Object> guild : CatalogCalls.listOf(guildsResult.output())) {
                String guildId = str(guild.get("id"));
                if (guildId == null) {
                    continue;
                }
                Outcome<List<ChatCandidate>> inGuild = channelsOf(tenantId, credentialId, guildId,
                        str(guild.get("name")));
                if (inGuild.ok()) {
                    chats.addAll(inGuild.value());
                }
            }
            return Outcome.of(chats);
        });
    }

    private Outcome<List<ChatCandidate>> channelsOf(String tenantId, Long credentialId, String guildId,
                                                    String guildName) {
        return calls.call(CHANNEL_ID, TOOL_GET_GUILD_CHANNELS, Map.of("guild_id", guildId), tenantId,
                credentialId).map(result -> {
            List<ChatCandidate> chats = new ArrayList<>();
            for (Map<String, Object> channel : CatalogCalls.listOf(result.output())) {
                Object type = channel.get("type");
                if (!(type instanceof Number n) || n.intValue() != GUILD_TEXT) {
                    continue;
                }
                chats.add(new ChatCandidate(str(channel.get("id")),
                        (guildName != null ? guildName + " / #" : "#") + str(channel.get("name")),
                        "channel", null));
            }
            return Outcome.of(chats);
        });
    }

    @Override
    public Outcome<Void> sendTest(String tenantId, Long credentialId, String chatId, String text) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("channel_id", chatId);
        params.put("content", cap(text, CONTENT_MAX_CHARS));
        params.put("allowed_mentions", NO_MENTIONS);
        return calls.call(CHANNEL_ID, TOOL_SEND_MESSAGE, params, tenantId, credentialId).map(r -> Outcome.of(null));
    }

    @Override
    public Outcome<String> sendDecisionRequest(String tenantId, Long credentialId, String chatId, String text,
                                               String approvePayload, String rejectPayload) {
        return send(tenantId, credentialId, chatId, text, List.of(
                new ChoiceOption("Approve", approvePayload), new ChoiceOption("Refuse", rejectPayload)));
    }

    @Override
    public Outcome<String> sendChoiceRequest(String tenantId, Long credentialId, String chatId, String text,
                                             List<ChoiceOption> options) {
        if (options == null || options.isEmpty()) {
            return Outcome.failed("A question with no options has nothing to press.");
        }
        return send(tenantId, credentialId, chatId, text, options);
    }

    @Override
    public Outcome<Void> updateChoiceMarkup(String tenantId, Long credentialId, String chatId, String messageId,
                                            String text, List<ChoiceOption> options) {
        if (messageId == null || messageId.isBlank()) {
            return Outcome.failed("No message id: nothing to redraw.");
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("channel_id", chatId);
        params.put("message_id", messageId);
        params.put("components", componentsOf(options));
        return calls.call(CHANNEL_ID, TOOL_EDIT_MESSAGE, params, tenantId, credentialId).map(r -> Outcome.of(null));
    }

    @Override
    public Outcome<Void> closeDecisionRequest(String tenantId, Long credentialId, String chatId, String messageId,
                                              String originalText, String verdictLine) {
        if (messageId == null || messageId.isBlank()) {
            return Outcome.failed("No message id: nothing to close.");
        }
        String base = originalText != null ? originalText : "";
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("channel_id", chatId);
        params.put("message_id", messageId);
        params.put("content", cap(base.isBlank() ? verdictLine : base + "\n\n" + verdictLine, CONTENT_MAX_CHARS));
        params.put("allowed_mentions", NO_MENTIONS);
        // An empty component list is what takes the buttons away.
        params.put("components", List.of());
        return calls.call(CHANNEL_ID, TOOL_EDIT_MESSAGE, params, tenantId, credentialId).map(r -> Outcome.of(null));
    }

    /**
     * A late acknowledgement, only seen by the presser.
     *
     * <p>Normally the acknowledgement IS the HTTP response to the interaction, written by the
     * inbound controller. When deciding took longer than Discord's three seconds, the controller
     * has already answered "deferred", and the line goes out afterwards as a follow-up on the
     * interaction's own webhook, valid for fifteen minutes.
     *
     * @param buttonEventId {@code <application id>:<interaction token>}, as the controller builds it
     */
    @Override
    public Outcome<Void> ackButton(String tenantId, Long credentialId, String buttonEventId, String text,
                                   boolean asAlert) {
        if (text == null || text.isBlank()) {
            return Outcome.of(null);
        }
        int colon = buttonEventId != null ? buttonEventId.indexOf(':') : -1;
        if (colon <= 0 || colon == buttonEventId.length() - 1) {
            return Outcome.failed("No interaction to follow up on.");
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("webhook_id", buttonEventId.substring(0, colon));
        params.put("webhook_token", buttonEventId.substring(colon + 1));
        params.put("content", cap(text, CONTENT_MAX_CHARS));
        params.put("allowed_mentions", NO_MENTIONS);
        params.put("flags", EPHEMERAL);
        return calls.call(CHANNEL_ID, TOOL_EXECUTE_WEBHOOK, params, tenantId, credentialId).map(r -> Outcome.of(null));
    }

    // ---- internals ----

    private Outcome<String> send(String tenantId, Long credentialId, String chatId, String text,
                                 List<ChoiceOption> buttons) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("channel_id", chatId);
        params.put("content", cap(text, CONTENT_MAX_CHARS - VERDICT_RESERVE_CHARS));
        params.put("allowed_mentions", NO_MENTIONS);
        params.put("components", componentsOf(buttons));
        return calls.call(CHANNEL_ID, TOOL_SEND_MESSAGE, params, tenantId, credentialId).map(result -> {
            Object id = result.output() != null ? result.output().get("id") : null;
            return id != null ? Outcome.of(String.valueOf(id))
                    : Outcome.<String>failed("Discord did not say which message it sent.");
        });
    }

    /**
     * Action rows of up to five buttons, each carrying our payload as its {@code custom_id}.
     * Discord caps a custom_id at 100 characters and a label at 80; ours are 34 and capped.
     */
    static List<Map<String, Object>> componentsOf(List<ChoiceOption> options) {
        List<Map<String, Object>> rows = new ArrayList<>();
        List<Map<String, Object>> row = new ArrayList<>();
        for (ChoiceOption option : options) {
            Map<String, Object> button = new LinkedHashMap<>();
            button.put("type", 2);
            button.put("style", 1);
            button.put("label", cap(option.label(), 80));
            button.put("custom_id", option.payload());
            row.add(button);
            if (row.size() == BUTTONS_PER_ROW) {
                rows.add(Map.of("type", 1, "components", row));
                row = new ArrayList<>();
            }
        }
        if (!row.isEmpty()) {
            rows.add(Map.of("type", 1, "components", row));
        }
        return rows;
    }

    static boolean isPublicKey(String value) {
        String key = value.trim();
        if (key.length() != 64) {
            return false;
        }
        try {
            HexFormat.of().parseHex(key);
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private static String cap(String text, int max) {
        String value = text != null ? text : "";
        return value.length() > max ? value.substring(0, max) : value;
    }

    private static String str(Object value) {
        return value != null ? String.valueOf(value) : null;
    }
}
