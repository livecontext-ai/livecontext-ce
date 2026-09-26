package com.apimarketplace.orchestrator.tools.channel;

import com.apimarketplace.agent.domain.ToolParameter;
import com.apimarketplace.agent.registry.AgentToolDefinition;
import com.apimarketplace.agent.registry.ToolCategory;
import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider;
import com.apimarketplace.orchestrator.services.channel.ChatChannelService;
import com.apimarketplace.orchestrator.services.channel.ChatChannelService.ChatChannelException;
import com.apimarketplace.orchestrator.services.channel.ChatChannelService.ConnectRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.apimarketplace.agent.registry.ToolSchemaGenerator.boolParam;
import static com.apimarketplace.agent.registry.ToolSchemaGenerator.intParam;
import static com.apimarketplace.agent.registry.ToolSchemaGenerator.stringParam;

/**
 * The {@code channel} tool: connect the workspace to a chat the user reads
 * (Telegram, Slack, Discord, WhatsApp or Microsoft Teams), so the product can reach
 * them when they are not looking at the app.
 *
 * <p>Deliberately one tool with a short action list rather than a module tree:
 * the whole feature is four verbs, and the guided setup is a conversation the
 * agent runs, not a subsystem.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChannelToolsProvider implements ToolsProvider {

    /** The services a destination can be on. Kept equal to the connector beans by a test. */
    static final List<String> CHANNELS =
            com.apimarketplace.orchestrator.services.channel.ChatChannelConnectorRegistry.KNOWN_CHANNELS;

    private static final List<String> VALID_ACTIONS =
            List.of("list", "discover", "connect", "set_default", "disconnect", "help");

    /** The actions that change the workspace, as opposed to reading or probing it. */
    /** Discover included: it is the first step of connecting, refused up front like the REST surface. */
    private static final Set<String> WRITE_ACTIONS = Set.of("discover", "connect", "set_default", "disconnect");

    private final ChatChannelService service;

    @Override
    public ToolCategory getCategory() {
        return ToolCategory.CATALOG;
    }

    @Override
    public List<AgentToolDefinition> getTools() {
        return List.of(buildChannelTool());
    }

    @Override
    public ToolExecutionResult execute(String toolName, Map<String, Object> parameters,
                                       ToolExecutionContext context) {
        if (!"channel".equals(toolName)) {
            return ToolExecutionResult.failure(ToolErrorCode.TOOL_NOT_FOUND, "Unknown tool: " + toolName);
        }
        String action = str(parameters.get("action"));
        if (action == null || action.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER,
                    "action is required. Valid actions: " + String.join(", ", VALID_ACTIONS));
        }
        if ("help".equals(action)) {
            return ToolExecutionResult.success(Map.of("help", helpText()));
        }
        String tenantId = context != null ? context.tenantId() : null;
        String orgId = context != null ? context.orgId() : null;
        if (tenantId == null || tenantId.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "User context required");
        }
        // Every action here but 'discover' writes a row that belongs to a workspace.
        // Checked up front, because reaching the write with no workspace fails deep in
        // persistence with a message written for whoever maintains this code, and the
        // agent would receive that message verbatim - after the bot was verified and
        // its webhook possibly pointed.
        if (!"discover".equals(action) && (orgId == null || orgId.isBlank())) {
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED,
                    "No workspace in this context, so there is nothing to connect a channel to. "
                            + "Ask the user to open a workspace and try again.");
        }
        // Same rule as the REST surface: where a workspace is reached decides who is asked
        // to authorize its agents, so a read-only member may read the list and nothing else.
        if (WRITE_ACTIONS.contains(action)
                && com.apimarketplace.auth.client.access.OrgAccessGuard.isRoleWriteBlocked(
                        orgId, context.orgRole())) {
            return ToolExecutionResult.failure(ToolErrorCode.PERMISSION_DENIED,
                    "This user's role in the workspace is read-only, so they cannot set up a "
                            + "channel or change where it is reached (list still works). Tell them "
                            + "to ask an owner or a member.");
        }
        try {
            return switch (action) {
                case "list" -> ToolExecutionResult.success(Map.of("channels",
                        service.list(orgId).stream().map(ChannelToolsProvider::asAgentView).toList()));
                case "discover" -> discover(parameters, tenantId);
                case "connect" -> connect(parameters, tenantId, orgId);
                case "set_default" -> ToolExecutionResult.success(Map.of(
                        "channel", asAgentView(service.setDefault(tenantId, orgId, linkId(parameters)))));
                case "disconnect" -> {
                    service.disconnect(tenantId, orgId, linkId(parameters),
                            ChatChannelService.ChangeSource.ASSISTANT);
                    yield ToolExecutionResult.success(Map.of("disconnected", true));
                }
                default -> ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED,
                        "Invalid action: " + action + ". Valid actions: " + String.join(", ", VALID_ACTIONS));
            };
        } catch (ChatChannelException ex) {
            // A refusal the user can act on. It travels as the message, unchanged:
            // the agent's job here is to relay it, not to re-describe it.
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, ex.getMessage());
        } catch (Exception ex) {
            log.error("channel action {} failed: {}", action, ex.getMessage(), ex);
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Error: " + ex.getMessage());
        }
    }

    private ToolExecutionResult discover(Map<String, Object> parameters, String tenantId) {
        ChatChannelService.DiscoveryResult result =
                service.discover(tenantId, channelOf(parameters), credentialId(parameters));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("channel", result.channel());
        out.put("credential_id", result.credentialId());
        out.put("bot_username", result.botUsername());
        out.put("chats", result.chats());
        if (result.notice() != null) {
            // The list could not be read at all (on Telegram: the bot is already connected). Not the
            // same situation as an empty list, and the fix is different: ask for the id directly.
            out.put("notice", result.notice());
        } else if (result.chats().isEmpty()) {
            out.put("hint", "telegram".equals(result.channel())
                    ? "The bot has not received any message yet. Ask the user to open a chat with "
                            + (result.botUsername() != null ? "@" + result.botUsername() : "the bot")
                            + " and send it any message, then run discover again."
                    : "Nothing this account can post to was found. On Slack, invite the app to the channel "
                            + "(/invite in that channel); on Discord, add the bot to the server; on Teams, "
                            + "start a chat with the person first. Then run discover again.");
        }
        return ToolExecutionResult.success(out);
    }

    private ToolExecutionResult connect(Map<String, Object> parameters, String tenantId, String orgId) {
        ConnectRequest request = new ConnectRequest(
                channelOf(parameters),
                credentialId(parameters),
                str(parameters.get("chat_id")),
                str(parameters.get("chat_title")),
                str(parameters.get("chat_type")),
                Boolean.TRUE.equals(parameters.get("make_default")),
                stringList(parameters.get("allowed_user_ids")),
                str(parameters.get("account_setting")));
        ChatChannelService.ConnectResult result = service.connect(tenantId, orgId, request,
                ChatChannelService.ChangeSource.ASSISTANT);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("channel", asAgentView(result.channel()));
        out.put("delivered", result.delivered());
        out.put("webhook", result.webhook().name().toLowerCase(java.util.Locale.ROOT));
        if (result.warning() != null) {
            out.put("warning", result.warning());
        }
        if (result.setupInstructions() != null) {
            // Steps only the user can take in the provider's own console. Until they are done,
            // messages arrive but presses do not come back.
            out.put("setup_instructions", result.setupInstructions());
        }
        return ToolExecutionResult.success(out);
    }

    /**
     * The entry as the agent is told it reads.
     *
     * <p>Written out key by key rather than letting the record serialise itself: a record
     * emits its component names ({@code linkId}, {@code isDefault}), the help and the setup
     * skill both promise {@code link_id} / {@code is_default}, and an agent that follows the
     * documented flow then cannot find the id that {@code set_default} and {@code disconnect}
     * require. Same spelling as the discover result, which already answers in this shape.
     */
    private static Map<String, Object> asAgentView(ChatChannelService.ChatChannelSummary summary) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("link_id", summary.linkId() != null ? summary.linkId().toString() : null);
        view.put("channel", summary.channel());
        view.put("credential_id", summary.credentialId());
        view.put("bot_username", summary.botUsername());
        view.put("chat_id", summary.chatId());
        view.put("chat_title", summary.chatTitle());
        view.put("chat_type", summary.chatType());
        view.put("is_default", summary.isDefault());
        view.put("active", summary.active());
        view.put("verified_at", summary.verifiedAt() != null ? summary.verifiedAt().toString() : null);
        view.put("last_error", summary.lastError());
        // Who may decide here. Empty means anyone in the chat. Without this the restriction was
        // writable and invisible: nothing told the agent whether a connected group was open to
        // all its members, and that list is what decides who may approve a sensitive action.
        view.put("allowed_user_ids", summary.allowedUserIds());
        return view;
    }

    private static UUID linkId(Map<String, Object> parameters) {
        String raw = str(parameters.get("link_id"));
        if (raw == null || raw.isBlank()) {
            throw new ChatChannelException("link_id is required. Run list to read the link_id of each connected chat.");
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException ex) {
            throw new ChatChannelException("link_id must be one of the ids returned by list, got '" + raw + "'.");
        }
    }

    private static String channelOf(Map<String, Object> parameters) {
        String channel = str(parameters.get("channel"));
        return channel != null && !channel.isBlank() ? channel : "telegram";
    }

    /**
     * Absent means "use the workspace default"; present means it has to be readable.
     *
     * <p>A value that is neither a number nor a numeric string used to fall through to null,
     * the SAME answer as absent, so a malformed credential_id quietly connected through
     * whichever credential the workspace defaults to and reported success. The agent named one
     * credential and got another, with nothing to read back that said so.
     */
    private static Long credentialId(Map<String, Object> parameters) {
        Object raw = parameters.get("credential_id");
        if (raw == null) {
            return null;
        }
        if (raw instanceof Number number) {
            return number.longValue();
        }
        if (raw instanceof String text) {
            if (text.isBlank()) {
                return null;
            }
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ex) {
                throw new ChatChannelException("credential_id must be a number, got '" + text + "'.");
            }
        }
        throw new ChatChannelException("credential_id must be a number, got '" + raw + "'.");
    }

    /**
     * Absent stays ABSENT, so the service can tell it from an empty list.
     *
     * <p>An omitted {@code allowed_user_ids} means "leave the stored list alone"; an explicitly
     * empty one means "open this destination to anyone in the chat". Coercing the first into the
     * second is what made the restriction unclearable.
     */
    private static List<String> stringList(Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().filter(java.util.Objects::nonNull).map(String::valueOf).toList();
    }

    private static String str(Object value) {
        return value != null ? String.valueOf(value) : null;
    }

    private AgentToolDefinition buildChannelTool() {
        List<ToolParameter> params = List.of(
                ToolParameter.builder()
                        .name("action")
                        .type("string")
                        .description("Action to perform: " + String.join(", ", VALID_ACTIONS))
                        .required(true)
                        .enumValues(VALID_ACTIONS)
                        .build(),
                ToolParameter.builder()
                        .name("channel")
                        .type("string")
                        .description("Which chat service (for: discover, connect). Defaults to 'telegram'.")
                        .required(false)
                        .enumValues(CHANNELS)
                        .build(),
                stringParam("account_setting", "(for: connect) The one extra value some services need beyond "
                        + "the connection itself. discord: the application's Public Key. whatsapp: the Phone "
                        + "number ID messages are sent from. Not used by telegram, slack or teams. Only needed "
                        + "the first time: a reconnect keeps the stored value.", false),
                intParam("credential_id", "Which connection to send with (for: discover, connect). Omit to use the "
                        + "workspace's default connection for that service. Read the ids with get_connected_services.",
                        false, null),
                stringParam("chat_id", "The destination to connect (for: connect). Copy it from a discover result "
                        + "rather than retyping it. whatsapp has no discover: use the person's phone number in "
                        + "international form, e.g. +33612345678.", false),
                stringParam("chat_title", "Human label for the destination (for: connect). Copy it from the same "
                        + "discover entry as chat_id, so the user recognises the chat later.", false),
                stringParam("chat_type", "Kind of destination as discover reported it, e.g. private or group "
                        + "(for: connect).", false),
                boolParam("make_default", "(for: connect) Make this the destination the workspace writes to when "
                        + "nothing names another. The FIRST destination that receives the test message becomes the "
                        + "default on its own, so pass this only to move it.", false, null),
                stringParam("link_id", "Which connected destination to act on (for: set_default, disconnect). "
                        + "Returned by list and connect.", false),
                com.apimarketplace.agent.registry.ToolSchemaGenerator.arrayParam("allowed_user_ids",
                        "(for: connect) Who may press a button on messages sent there, as the chat service's own "
                        + "user ids. Omit for a private chat. For a GROUP, omit it and ANY member of that group "
                        + "can approve an agent's sensitive action: ask the user whether that is what they want, "
                        + "and if not, get the ids of the people who may decide. "
                        + "Read the current list back with action='list', field allowed_user_ids. "
                        + "On a reconnect the three cases differ: omit it to leave the stored list untouched, "
                        + "send [] to clear it (anyone in the chat may then decide), or send ids to replace it.",
                        false));

        return AgentToolDefinition.builder()
                .name("channel")
                .description("""
                        Connect this workspace to a chat the user actually reads (telegram, slack, discord, whatsapp or
                        teams), so approvals and questions can reach them when they are not looking at the app. Run
                        action='help' first for the step-by-step setup of each service.

                        Actions:
                        - list: the destinations already connected. Each entry: link_id, channel, chat_id, chat_title,
                          is_default, verified_at (null = nothing was ever delivered there), last_error.
                        - discover: the chats this account can post to, so the user picks one instead of hunting for an
                          id. Returns chats[] of {chatId, title, type, fromUsername} plus bot_username, or a
                          notice saying why the list cannot be read and what to ask the user for instead. Not
                          available for whatsapp (the destination is a phone number).
                        - connect: attach one destination. Sends a test message, and only a DELIVERED message makes the
                          destination usable. Returns delivered (true/false), webhook, warning when something needs
                          saying, setup_instructions when the user must still do something in the service's own
                          console (relay them word for word), and the entry itself under `channel` (same fields as
                          a list entry). For a GROUP,
                          decide with the user who may press the buttons: with no allowed_user_ids, any member of
                          that group can approve an agent's sensitive action. Reconnecting the same chat is how you
                          change that later: omit allowed_user_ids to leave it as it is, send [] to open it to
                          anyone in the chat, send ids to replace the list. Each entry from list shows its current
                          allowed_user_ids, so you can tell the user who may decide today before changing it.
                        - set_default: point the workspace at one of the connected destinations.
                        - disconnect: forget one. Disconnecting the default hands the default to the
                          oldest remaining destination that has received a message and is still active, so
                          tell the user where their requests will arrive from now on; run list after it to
                          read which entry has is_default true.

                        ORDER MATTERS on telegram: run discover BEFORE connect. Once a destination is connected Telegram
                        stops replaying recent messages to us, so discover answers with a notice instead of chats, and
                        the only way left to add a second chat is to be given its id. Never the bot's own @username:
                        a bot cannot write to itself, and connect refuses it.

                        If discover returns no chats, the user has not written to the bot yet. Ask them to open a chat
                        with the bot and send any message, then run discover again.

                        If connect returns delivered=false, the destination is saved but nothing arrived: on Telegram
                        that is almost always a user who has not started the bot, or a bot that is not in the group;
                        the warning says what it is on the other services. Say so and offer to retry; do not report
                        the connection as working.
                        """)
                .category(ToolCategory.CATALOG)
                .parameters(params)
                .requiredParameters(List.of("action"))
                .inputSchema(com.apimarketplace.agent.registry.ToolSchemaGenerator
                        .generateInputSchema(params, List.of("action")))
                .helpText(helpText())
                .requiresAuth(true)
                .tags(List.of("channel", "telegram", "slack", "discord", "whatsapp", "teams", "notification",
                        "approval"))
                .build();
    }

    private String helpText() {
        return """
                channel - reach the user outside the app.

                WHAT IT IS FOR. Some things cannot wait for the user to open the app: an approval a workflow is
                paused on, or permission an agent needs before doing something sensitive in an unattended run.
                Those are delivered to the destination connected here. Without one, they wait in the app and an
                unattended run simply stops until someone looks.

                THE SETUP CONVERSATION. First ask which service the user reads (telegram, slack, discord,
                whatsapp, teams), then follow its steps. You stay with them the whole way: after each step,
                check with them that it is done before the next, and if they are stuck, walk them through the
                screen they are on. Never ask them to paste a token or a password into the conversation; the
                Connect card is where secrets go.

                telegram (a bot of their own, 3 minutes):
                1. credential(action='require', services=['telegram']) if get_connected_services shows none: they
                   create a bot with @BotFather in Telegram (/newbot) and paste its token into the Connect card.
                2. They send any message to the bot (or add it to the group), then channel(action='discover').
                3. channel(action='connect', channel='telegram', chat_id=..., chat_title=...) from the entry they pick.

                slack (nothing to create when this installation has its Slack app; on a self-hosted
                install the administrator configures that app first, and if the Connect card offers no
                Slack, that is why):
                1. credential(action='require', services=['slack']): they approve the LiveContext app in their workspace.
                2. For a channel, they type /invite followed by the app name in it (a direct message needs nothing).
                3. channel(action='discover', channel='slack'), then connect the entry they pick.

                discord (a bot of their own, 5 minutes):
                1. In the Discord Developer Portal they create an application, open Bot, copy the bot token, and
                   invite the bot to their server (OAuth2 > URL Generator, scopes bot, permission Send Messages).
                2. credential(action='require', services=['discord']): they choose the bot token option and paste it.
                3. Ask them for the application's Public Key (General Information page). It is public, so they
                   may give it to you.
                4. channel(action='discover', channel='discord'), then
                   channel(action='connect', channel='discord', chat_id=..., chat_title=..., account_setting=<public key>).
                5. Relay setup_instructions: they paste the Interactions Endpoint URL on the General Information
                   page and save. Discord checks it on save; if it refuses, the public key does not match.

                whatsapp (their Meta business app, 10 minutes):
                1. In the Meta for Developers dashboard they have an app with the WhatsApp product. From WhatsApp >
                   API Setup they need the Phone number ID and an access token (a permanent System User token,
                   since the temporary one expires in 24 hours).
                2. credential(action='require', services=['whatsapp']): they paste the token.
                3. The person who will answer sends any WhatsApp message to that business number first: WhatsApp
                   only lets a business write within 24 hours of the person's last message.
                4. channel(action='connect', channel='whatsapp', chat_id='+<their number>', account_setting=<phone number id>).
                5. Relay setup_instructions: in WhatsApp > Configuration they set the Callback URL and Verify
                   token given there, save, and subscribe to the messages field.

                teams (nothing to create, same self-hosted caveat as Slack for the Microsoft app):
                1. credential(action='require', services=['microsoftteams']): they sign in with Microsoft.
                2. channel(action='discover', channel='teams') lists their chats; connect the one they pick.
                   Buttons in Teams are links to a confirmation page, which cannot tell who opened them, so a
                   Teams destination takes no allowed_user_ids.
                3. Say this plainly: messages go out AS the person who connected, and Teams does not notify
                   anyone about their own messages. It reaches the OTHER people in the chat; to be notified
                   themselves, they should connect a chat where someone else signs in, or pick another service.

                setup_instructions appear in the connect answer only. If the user lost them, connect the same
                destination again (nothing else to give, the stored values are kept): the answer repeats them.

                For every service: read `delivered` in the connect answer. true means a real message landed and
                the destination works; false means it did not, whatever else the answer says. Then tell the user
                in one sentence what now arrives there.

                WORKFLOW APPROVALS use the same destinations. An approval node with delegation {linkId: 'default'}
                goes to the workspace default; {linkId: '<a linkId from list>'} goes to that destination.
                AGENTS use the default too, unless one is given its own: agent(action='update',
                chat_channel_link_id='<linkId>'), empty to go back to the default. A destination picked this way
                is never replaced by another one: if it is disconnected, nothing is sent until another is picked.

                WHAT THE ANSWER TELLS YOU:
                - delivered: the only proof the destination works. A connection can be valid while this is false.
                - webhook: what happened to the bot's single callback slot (telegram only; the other services
                  answer 'not_ours_to_set', and setup_instructions says what the user sets instead).
                  'pointed' = it was free and now returns button presses here.
                  'already_ours' = it already did.
                  'kept_installation_url' = it was taken by something else of ours (typically a workflow that
                  receives events on that bot). Left alone; button presses still come back.
                  'foreign' = it sends its updates to a server we do not control. Messages will arrive, but
                  buttons pressed on them will do nothing. Say that plainly.
                  'failed' = the slot could not be read or written; same consequence as 'foreign'.
                - warning: a sentence to relay as-is when present.

                ONE DEFAULT PER WORKSPACE. The first destination that receives the test message becomes it
                automatically, so a user who connects one chat is done. set_default only moves it, and it refuses a
                destination that has never received anything - which is the same rule as above, not a separate one.
                """;
    }
}
