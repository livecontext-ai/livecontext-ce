package com.apimarketplace.orchestrator.services.channel;

import com.apimarketplace.credential.client.CredentialClient;
import com.apimarketplace.credential.client.dto.CredentialSummaryDto;
import com.apimarketplace.orchestrator.domain.channel.ChatChannelBotEntity;
import com.apimarketplace.orchestrator.domain.channel.ChatChannelLinkEntity;
import com.apimarketplace.orchestrator.repository.ChatChannelBotRepository;
import com.apimarketplace.orchestrator.repository.ChatChannelLinkRepository;
import com.apimarketplace.orchestrator.services.channel.ChatChannelConnector.BotIdentity;
import com.apimarketplace.orchestrator.services.channel.ChatChannelConnector.ChatCandidate;
import com.apimarketplace.orchestrator.services.channel.ChatChannelConnector.Outcome;
import com.apimarketplace.orchestrator.services.analytics.EngagementAnalyticsEmitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Workspace-level address book of outbound chat destinations.
 *
 * <p>Answers one question for every surface that must reach a person who is not
 * watching the screen: <em>where do I write to this workspace</em>. Before this
 * existed the answer was typed by hand into every workflow approval node, and
 * outside a workflow there was no answer at all.
 *
 * <h2>Two invariants worth keeping</h2>
 *
 * <p><b>A default destination has provably received a message.</b> {@code
 * verifyBot} proves the token, never the chat: Telegram refuses (403) to write
 * to someone who never started the bot, so a link is only eligible to be the
 * workspace default once a real message has landed in it. Without that rule the
 * most likely first-run outcome is a green connect pointing at a chat that
 * silently swallows everything.
 *
 * <p><b>An existing webhook is never overwritten here.</b> A bot already
 * webhooked to a workflow trigger is a supported configuration - the generic
 * webhook path diverts approval callbacks itself - so pointing our own URL at it
 * would break the trigger while reporting success. The connect reads first, and
 * only writes into an empty slot.
 */
@Service
public class ChatChannelService {

    private static final Logger logger = LoggerFactory.getLogger(ChatChannelService.class);

    /** What the connect did about the bot's single webhook slot. */
    public enum WebhookOutcome {
        /** The slot was empty and now points at this installation. */
        POINTED,
        /** It already pointed here. Nothing to do. */
        ALREADY_OURS,
        /**
         * It points at another URL of this same installation, typically a workflow
         * trigger webhook. Left alone: button clicks still reach us, because the
         * generic webhook path recognises and diverts approval callbacks.
         */
        KEPT_INSTALLATION_URL,
        /**
         * It points somewhere we do not control. Left alone, and the caller is told:
         * messages will still be delivered, but nothing will come back, so buttons
         * will do nothing.
         */
        FOREIGN,
        /** The provider refused to say or to write. */
        FAILED,
        /**
         * This provider's callback URL is not ours to set: it is declared in the provider's own
         * console (WhatsApp Business in the Meta dashboard, Slack in the app manifest). Nothing
         * was read and nothing was written, which is not the same as {@link #FAILED}, and the
         * connect must not report a problem it did not have.
         */
        NOT_OURS_TO_SET
    }

    /** Who asked for a connect or a disconnect: the person in Settings, or the assistant on their behalf. */
    public enum ChangeSource { MANUAL, ASSISTANT }

    /**
     * What a caller sees about one connected destination.
     *
     * <p>{@code allowedUserIds} is here because the restriction was writable and invisible:
     * nothing in either surface told you whether a connected group was open to all its members
     * or limited to two of them, and that list decides who may approve an agent's sensitive
     * action. Empty means anyone in the chat.
     */
    public record ChatChannelSummary(
            UUID linkId, String channel, Long credentialId, String botUsername,
            String chatId, String chatTitle, String chatType,
            boolean isDefault, boolean active, Instant verifiedAt,
            String webhookUrl, Instant webhookSetAt, String lastError,
            List<String> allowedUserIds) {}

    /**
     * @param setupInstructions what the person still has to do in the provider's own console for
     *                          presses to come back, or null when nothing (the URL is ours to set,
     *                          or it is set once for the whole platform)
     */
    public record ConnectResult(
            ChatChannelSummary channel, boolean delivered, WebhookOutcome webhook,
            String warning, String setupInstructions) {

        public ConnectResult(ChatChannelSummary channel, boolean delivered, WebhookOutcome webhook,
                             String warning) {
            this(channel, delivered, webhook, warning, null);
        }
    }

    /**
     * One connect request.
     *
     * <p>{@code chatTitle} / {@code chatType} are what discovery reported, carried
     * through rather than re-fetched: the provider will not replay updates once the
     * webhook is pointed, so this is the last moment the label is available.
     */
    /**
     * @param accountSetting what the provider needs beyond its token, when it needs anything
     *                       (see {@link ChatChannelConnector#accountSettingLabel}): the WhatsApp
     *                       sending phone number id, the Discord application public key
     */
    public record ConnectRequest(
            String channel, Long credentialId, String chatId,
            String chatTitle, String chatType, boolean makeDefault,
            List<String> allowedUserIds, String accountSetting) {

        /**
         * {@code allowedUserIds} is deliberately TRI-STATE here, and null is a real value:
         *
         * <ul>
         *   <li>{@code null} - the caller said nothing about the list, so leave the stored one
         *       alone. A reconnect that only fixes a title must not widen who may approve.</li>
         *   <li>empty - the caller asked for no restriction, so CLEAR it.</li>
         *   <li>non-empty - replace it.</li>
         * </ul>
         *
         * <p>Coercing null to empty here is what made the list unclearable: "absent" and "empty"
         * arrived at the writer as the same value, so the writer could only tell them apart by
         * ignoring empty, which left no way to say "open it to everyone". The tri-state lives in
         * the REQUEST only; the entity still stores an empty list and never null.
         */
        public ConnectRequest {
            allowedUserIds = allowedUserIds == null ? null : List.copyOf(allowedUserIds);
        }

        /** Every provider that needs nothing beyond its token. */
        public ConnectRequest(String channel, Long credentialId, String chatId,
                              String chatTitle, String chatType, boolean makeDefault,
                              List<String> allowedUserIds) {
            this(channel, credentialId, chatId, chatTitle, chatType, makeDefault, allowedUserIds, null);
        }

        /**
         * Back-compat shape for callers that do not restrict who may decide.
         *
         * <p>Passes null, not an empty list: a caller using this constructor is not asking for
         * anything to change, and an empty list would now clear a restriction it never mentioned.
         */
        public ConnectRequest(String channel, Long credentialId, String chatId,
                              String chatTitle, String chatType, boolean makeDefault) {
            this(channel, credentialId, chatId, chatTitle, chatType, makeDefault, null, null);
        }
    }

    /**
     * What discover found.
     *
     * @param notice null when the list was read; otherwise the sentence saying why the provider
     *               keeps no list in the bot's current state (on Telegram: the bot is already
     *               connected, so Telegram keeps its messages for the webhook). Only that case: a
     *               failed call is an error, not a notice. The bot is still identified, so the
     *               caller can say which bot to open and how to give the destination directly.
     */
    public record DiscoveryResult(String channel, Long credentialId, String botUsername,
                                  List<ChatCandidate> chats, String notice) {
        public DiscoveryResult(String channel, Long credentialId, String botUsername, List<ChatCandidate> chats) {
            this(channel, credentialId, botUsername, chats, null);
        }
    }

    /** What a delivery surface needs to write to a workspace's default destination. */
    public record ResolvedTarget(UUID linkId, String channel, Long credentialId, String chatId,
                                 List<String> allowedUserIds) {}

    /** Raised for every caller-fixable refusal, with a sentence the caller can show as-is. */
    public static class ChatChannelException extends RuntimeException {
        public ChatChannelException(String message) {
            super(message);
        }
    }

    /**
     * The subset of those refusals that are about WHO is asking, not about what they asked.
     *
     * <p>A separate type rather than a flag on the message, because the HTTP layer used to pick
     * 403 over 400 by testing the sentence for "read-only". That sentence is written elsewhere,
     * so rewording it silently downgraded a security refusal to a client error, and any unrelated
     * refusal that happened to mention a read-only chat was promoted to 403. A refusal has to
     * carry its own kind.
     *
     * <p>A SUBCLASS and not a sibling: {@code ChannelToolsProvider} catches
     * {@code ChatChannelException} to relay the service's sentence verbatim to the agent, and a
     * sibling type would escape that catch and reach the caller as an unhandled failure.
     */
    public static class ChatChannelForbiddenException extends ChatChannelException {
        public ChatChannelForbiddenException(String message) {
            super(message);
        }
    }

    private final ChatChannelBotRepository botRepository;
    private final ChatChannelLinkRepository linkRepository;
    private final ChatChannelConnectorRegistry connectors;
    private final CredentialClient credentialClient;
    private final TransactionTemplate transactionTemplate;
    private final String publicBaseUrl;

    /** Product analytics; optional so a unit test can build the service without it. */
    @Autowired(required = false)
    private EngagementAnalyticsEmitter analytics;

    public ChatChannelService(
            ChatChannelBotRepository botRepository,
            ChatChannelLinkRepository linkRepository,
            ChatChannelConnectorRegistry connectors,
            CredentialClient credentialClient,
            PlatformTransactionManager transactionManager,
            @Value("${orchestrator.webhook.base-url:http://localhost:8080}") String publicBaseUrl) {
        this.botRepository = botRepository;
        this.linkRepository = linkRepository;
        this.connectors = connectors;
        this.credentialClient = credentialClient;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.publicBaseUrl = publicBaseUrl;
    }

    // ========================================================================
    // READ
    // ========================================================================

    @Transactional(readOnly = true)
    public List<ChatChannelSummary> list(String organizationId) {
        List<ChatChannelLinkEntity> links =
                linkRepository.findByOrganizationIdOrderByIsDefaultDescCreatedAtAsc(organizationId);
        List<ChatChannelSummary> out = new ArrayList<>(links.size());
        for (ChatChannelLinkEntity link : links) {
            botRepository.findByIdAndOrganizationId(link.getBotId(), organizationId)
                    .ifPresent(bot -> out.add(summary(bot, link)));
        }
        return out;
    }

    /**
     * The workspace's default destination, or empty when none is connected.
     *
     * <p>Every delivery surface resolves through here rather than reading the
     * repository, so "which one is the default" has exactly one implementation.
     */
    @Transactional(readOnly = true)
    public Optional<ResolvedTarget> resolveDefault(String organizationId) {
        if (organizationId == null || organizationId.isBlank()) {
            return Optional.empty();
        }
        return linkRepository.findByOrganizationIdAndIsDefaultTrueAndActiveTrue(organizationId)
                .flatMap(link -> botRepository.findByIdAndOrganizationId(link.getBotId(), organizationId)
                        .map(bot -> new ResolvedTarget(link.getId(), bot.getChannel(), bot.getCredentialId(),
                                link.getChatId(), link.getAllowedUserIds())));
    }

    /**
     * Where something should be delivered: the destination a caller chose (an agent's, a node's),
     * or the workspace default when it chose none.
     *
     * <p><b>A choice is never replaced by another chat.</b> When the chosen destination is not an
     * active destination of THIS workspace (disconnected since, switched off, or an id from another
     * workspace), the answer is empty, exactly as if nothing were connected. Falling back to the
     * default would put a request in front of people nobody picked for it, and the default is often
     * a group: the finance agent's payment approval must not land in the support channel because the
     * finance chat was removed.
     */
    @Transactional(readOnly = true)
    public Optional<ResolvedTarget> resolveFor(String organizationId, UUID chosenLinkId) {
        return chosenLinkId == null ? resolveDefault(organizationId) : resolveLink(organizationId, chosenLinkId);
    }

    /** What a one-way notice to the workspace default did. {@code channel} is null when none is connected. */
    public record NoticeResult(boolean delivered, String channel, String error) {}

    /**
     * Sends a one-way notice (an alert, a digest) to the workspace's DEFAULT
     * destination. Alerts are about the workspace, so they go where the workspace
     * said it wants to be reached; there is no per-alert destination to choose.
     *
     * <p>Not transactional: a provider round trip must not hold a database
     * connection. Runs under the workspace scope because an org-shared bot
     * credential does not resolve without it, and the caller is a background
     * thread with no org header of its own.
     */
    public NoticeResult sendNotice(String organizationId, String tenantId, String text) {
        Optional<ResolvedTarget> target = resolveDefault(organizationId);
        if (target.isEmpty()) {
            return new NoticeResult(false, null, "No chat channel is connected to this workspace.");
        }
        ResolvedTarget t = target.get();
        Optional<ChatChannelConnector> connector = connectors.forChannel(t.channel());
        if (connector.isEmpty()) {
            return new NoticeResult(false, t.channel(), "This channel is not supported any more.");
        }
        List<Outcome<Void>> holder = new ArrayList<>(1);
        com.apimarketplace.common.web.TenantResolver.runWithOrgScope(organizationId, () ->
                holder.add(connector.get().sendNotice(tenantId, t.credentialId(), t.chatId(), text)));
        Outcome<Void> sent = holder.isEmpty() ? Outcome.failed("The send did not run.") : holder.get(0);
        return sent.ok()
                ? new NoticeResult(true, t.channel(), null)
                : new NoticeResult(false, t.channel(), sent.error());
    }

    /** One destination of this workspace, if it exists and is active; empty otherwise. */
    @Transactional(readOnly = true)
    public Optional<ResolvedTarget> resolveLink(String organizationId, UUID linkId) {
        if (organizationId == null || organizationId.isBlank() || linkId == null) {
            return Optional.empty();
        }
        return linkRepository.findByIdAndOrganizationId(linkId, organizationId)
                .filter(ChatChannelLinkEntity::isActive)
                .flatMap(link -> botRepository.findByIdAndOrganizationId(link.getBotId(), organizationId)
                        .map(bot -> new ResolvedTarget(link.getId(), bot.getChannel(), bot.getCredentialId(),
                                link.getChatId(), link.getAllowedUserIds())));
    }

    // ========================================================================
    // DISCOVER
    // ========================================================================

    /**
     * Chats the bot has recently heard from, so the person picks one instead of
     * hunting for a numeric chat id.
     *
     * <p>Runs BEFORE any webhook is pointed, which is not a preference: Telegram
     * refuses to replay updates while a webhook is active.
     */
    public DiscoveryResult discover(String tenantId, String channel, Long credentialId) {
        ChatChannelConnector connector = connector(channel);
        long resolvedCredentialId = resolveCredentialId(tenantId, connector, credentialId);

        Outcome<BotIdentity> identity = connector.verifyBot(tenantId, resolvedCredentialId);
        if (!identity.ok()) {
            throw new ChatChannelException(identity.error());
        }
        if (!connector.capabilities().discoversChats()) {
            // An empty list here would read as "nobody has written to your bot", which sends the
            // person looking for a message they never had to send. This provider simply has no
            // such list: the destination is one they already know.
            throw new ChatChannelException("This channel has no list of chats to choose from. "
                    + "Connect the destination directly by giving its id.");
        }
        Outcome<List<ChatCandidate>> chats = connector.discoverChats(tenantId, resolvedCredentialId);
        if (!chats.ok() && chats.unavailable()) {
            // The bot answered and the provider simply keeps no list in this state (Telegram, once
            // the bot is connected). Refusing the whole call would also lose the one thing the
            // person needs next: which bot to open. The reason travels as a notice.
            return new DiscoveryResult(connector.channelId(), resolvedCredentialId,
                    identity.value().username(), List.of(), chats.error());
        }
        if (!chats.ok()) {
            // A real failure (a timeout, a rate limit): said as one, never as "already connected".
            throw new ChatChannelException(chats.error());
        }
        return new DiscoveryResult(connector.channelId(), resolvedCredentialId,
                identity.value().username(), chats.value());
    }

    // ========================================================================
    // CONNECT
    // ========================================================================

    /**
     * Connect one destination end to end: prove the bot, point the webhook if the
     * slot is free, store the destination, and prove delivery by sending to it.
     *
     * <p>Not transactional as a whole, deliberately. It makes several provider
     * round trips, and holding a database transaction across them would pin a
     * connection for as long as a remote API takes to answer. Each step persists
     * its own outcome instead, so a failure half way leaves a row that says
     * exactly how far it got rather than no trace at all.
     */
    public ConnectResult connect(String tenantId, String organizationId, ConnectRequest request) {
        return connect(tenantId, organizationId, request, null);
    }

    /** @param source who asked (analytics only; null when the caller does not say) */
    public ConnectResult connect(String tenantId, String organizationId, ConnectRequest request,
                                 ChangeSource source) {
        Connected connected = doConnect(tenantId, organizationId, request);
        ConnectResult result = connected.result();
        if (analytics != null) {
            analytics.channelConnected(tenantId, organizationId,
                    result.channel() != null ? result.channel().channel() : null, source,
                    result.delivered(), result.channel() != null && result.channel().isDefault(),
                    result.webhook(), connected.newLink());
        }
        return result;
    }

    /** A connect's result, plus whether it created the destination (false: a reconnect or update). */
    private record Connected(ConnectResult result, boolean newLink) {}

    /** The saved link, plus whether this call created its row. */
    private record UpsertedLink(ChatChannelLinkEntity link, boolean created) {}

    private Connected doConnect(String tenantId, String organizationId, ConnectRequest request) {
        ChatChannelConnector connector = connector(request.channel());
        String chatId = request.chatId();
        if (chatId == null || chatId.isBlank()) {
            throw new ChatChannelException("A destination chat id is required. "
                    + "Discover the chats your bot can see first, then connect one of them.");
        }
        chatId = connector.normalizeChatId(chatId);
        if (chatId == null || chatId.isBlank()) {
            throw new ChatChannelException("That destination id is not one " + connector.channelId()
                    + " can write to. Check it and connect again.");
        }
        if (!connector.identifiesPresser() && request.allowedUserIds() != null
                && !request.allowedUserIds().isEmpty()) {
            throw new ChatChannelException("On " + connector.channelId() + " a decision is made from a "
                    + "link, which does not say who opened it, so a list of allowed people cannot be "
                    + "enforced. Connect without allowed people; anyone in that chat can decide.");
        }
        long resolvedCredentialId = resolveCredentialId(tenantId, connector, request.credentialId());

        String accountSetting = request.accountSetting() != null ? request.accountSetting().trim() : null;
        Optional<ChatChannelBotEntity> known = botRepository.findByOrganizationIdAndChannelAndCredentialId(
                organizationId, connector.channelId(), resolvedCredentialId);
        if (connector.accountSettingLabel() != null && (accountSetting == null || accountSetting.isBlank())
                && known.isEmpty()) {
            // Asked for by name, before any remote call: a WhatsApp account without its sending
            // number cannot send anything, and a Discord bot without its application's public key
            // cannot have a single press verified. A reconnect of a known bot keeps what it had.
            throw new ChatChannelException("Connecting " + connector.channelId() + " needs "
                    + connector.accountSettingLabel() + ". Give it and connect again.");
        }
        if ((accountSetting == null || accountSetting.isBlank()) && known.isPresent()) {
            accountSetting = connector.storedAccountSetting(known.get().getBotIdentity(),
                    known.get().getInboundKey());
        }

        Outcome<BotIdentity> identity = connector.verifyBot(tenantId, resolvedCredentialId, accountSetting);
        if (!identity.ok()) {
            throw new ChatChannelException(identity.error());
        }
        if (isTheBotItself(chatId, identity.value())) {
            // Refused before anything is stored or pointed: a bot cannot write to itself, and the
            // provider only says so after the webhook slot was taken and a dead row saved.
            throw new ChatChannelException("That destination is the bot itself"
                    + (identity.value().username() != null ? " (@" + identity.value().username() + ")" : "")
                    + ", and a bot cannot send messages to itself. Give the destination that should receive "
                    + "the messages instead: the person's own chat id (on Telegram, a number that the "
                    + "@userinfobot bot sends back when they write to it), or a group or channel the bot "
                    + "was added to.");
        }

        ChatChannelBotEntity bot = upsertBot(tenantId, organizationId, connector.channelId(),
                resolvedCredentialId, identity.value());
        String inboundKey = connector.newInboundKey(accountSetting, bot.getInboundKey());
        if (inboundKey != null && !inboundKey.equals(bot.getInboundKey())) {
            bot.setInboundKey(inboundKey);
            bot = botRepository.save(bot);
        }

        WebhookOutcome webhook = ensureWebhook(tenantId, connector, bot);

        UpsertedLink upserted = upsertLink(tenantId, organizationId, bot, chatId.trim(),
                request.chatTitle(), request.chatType(), request.allowedUserIds());
        ChatChannelLinkEntity link = upserted.link();

        // The one proof that matters. A bot that answers getMe says nothing about
        // whether it may write HERE.
        Outcome<Void> test = connector.sendTest(tenantId, resolvedCredentialId, link.getChatId(),
                "LiveContext is now connected to this chat. "
                        + "Approvals and questions that need you will arrive here.");
        boolean delivered = test.ok();
        if (delivered) {
            link.setVerifiedAt(Instant.now());
        }
        link.setLastError(delivered ? null : test.error());
        link.setActive(link.getVerifiedAt() != null);

        finalizeLink(organizationId, link, request.makeDefault());

        String warning = buildWarning(webhook, delivered, test.error(), connector.undeliveredHint());
        if (warning == null && delivered) {
            warning = connector.inboundProblem();
        }
        logger.info("[chat-channel] connect {} chat={} delivered={} webhook={} default={}",
                connector.channelId(), link.getChatId(), delivered, webhook, link.isDefault());
        return new Connected(new ConnectResult(summary(bot, link), delivered, webhook, warning,
                connector.setupInstructions(callbackUrl(connector.channelId()) + "/" + bot.getId(),
                        bot.getInboundKey())), upserted.created());
    }

    /**
     * Persist the link, then, when it qualifies, hand it the workspace's default slot.
     *
     * <p>TWO transactions, and the split is the point. The destination itself is what the
     * connect just proved: a real message reached a real chat, and that outcome has to be
     * recorded whatever happens to the default. Claiming the slot is a second, weaker
     * thing that can legitimately be lost to a concurrent connect, so it commits
     * separately and its failure costs the row nothing. Both are opened here rather than
     * on {@link #connect}, which makes several remote calls and must not hold a database
     * connection across them.
     *
     * <p>The first destination that DELIVERS takes the slot unasked. A workspace with one
     * connected chat and no default would be a connection every delivery surface silently
     * ignores, which is the exact failure this table exists to end.
     */
    private void finalizeLink(String organizationId, ChatChannelLinkEntity link, boolean makeDefault) {
        transactionTemplate.executeWithoutResult(status -> linkRepository.save(link));
        if (link.getVerifiedAt() != null) {
            claimDefaultSlot(organizationId, link, makeDefault);
        }
    }

    /**
     * Takes the workspace's single default slot for {@code link}, or leaves it alone.
     *
     * <p>The clear and the set are one transaction because the partial unique index
     * {@code uq_chat_channel_links_default} rejects two defaults, so the old one has to go
     * first and a window with neither is a workspace that briefly has nowhere to write.
     *
     * <p>What that ordering does NOT cover is two connects racing in two transactions.
     * Both read an empty slot, both write, and the index lets exactly one commit: the
     * loser arrives here as a {@link DataIntegrityViolationException}. It is caught rather
     * than raised because it is not a failure of anything the caller asked for. The
     * destination is connected, the test message was delivered, and the workspace HAS a
     * default, just somebody else's. Raising it would hand the agent a constraint name and
     * strand the row that a real chat has already been told about.
     */
    private void claimDefaultSlot(String organizationId, ChatChannelLinkEntity link, boolean makeDefault) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                boolean hasDefault = linkRepository
                        .findByOrganizationIdAndIsDefaultTrueAndActiveTrue(organizationId)
                        .filter(existing -> !existing.getId().equals(link.getId()))
                        .isPresent();
                if (!makeDefault && hasDefault) {
                    return;
                }
                linkRepository.clearDefault(organizationId);
                link.setDefault(true);
                linkRepository.save(link);
            });
        } catch (DataIntegrityViolationException ex) {
            // The flag was set on the in-memory entity a moment ago and the row it belongs
            // to never took it. The summary this connect returns is built from that entity,
            // so leaving it true would report a default the workspace does not have.
            link.setDefault(false);
            logger.info("[chat-channel] another destination took the default slot for org {} "
                    + "while connecting chat {}", organizationId, link.getChatId());
        }
    }

    /**
     * Point the workspace at one of its connected destinations.
     *
     * <p>Its own transaction rather than {@code @Transactional} so the index collision can
     * be caught HERE. A concurrent connect or set_default racing for the same slot surfaces
     * as a {@link DataIntegrityViolationException} at commit, which is outside any
     * try/catch a declarative transaction would let this method place. Unlike the connect
     * path this one is reported: somebody asked for this destination by id, so the honest
     * answer is that the answer changed under them, not silence.
     */
    public ChatChannelSummary setDefault(String organizationId, UUID linkId) {
        return setDefault(null, organizationId, linkId);
    }

    /** @param tenantId the person making the change (analytics only; null when unknown) */
    public ChatChannelSummary setDefault(String tenantId, String organizationId, UUID linkId) {
        ChatChannelSummary summary = doSetDefault(organizationId, linkId);
        if (analytics != null) {
            analytics.channelDefaultSet(tenantId, organizationId, summary.channel());
        }
        return summary;
    }

    private ChatChannelSummary doSetDefault(String organizationId, UUID linkId) {
        ChatChannelLinkEntity link;
        try {
            link = transactionTemplate.execute(status -> {
                ChatChannelLinkEntity found = linkRepository.findByIdAndOrganizationId(linkId, organizationId)
                        .orElseThrow(() -> new ChatChannelException(
                                "No connected chat with that id in this workspace."));
                if (found.getVerifiedAt() == null) {
                    throw new ChatChannelException("That chat has never received a message, so it cannot be "
                            + "the default. Reconnect it: the connect sends a test message and only a "
                            + "delivered one counts.");
                }
                if (!found.isActive()) {
                    throw new ChatChannelException(
                            "That chat is deactivated. Reconnect it before making it the default.");
                }
                linkRepository.clearDefault(organizationId);
                found.setDefault(true);
                return linkRepository.save(found);
            });
        } catch (DataIntegrityViolationException ex) {
            throw new ChatChannelException("Another change to this workspace's default landed first. "
                    + "List the connected chats to see which one holds it now, then try again.");
        }
        if (link == null) {
            throw new ChatChannelException("No connected chat with that id in this workspace.");
        }
        return botRepository.findByIdAndOrganizationId(link.getBotId(), organizationId)
                .map(bot -> summary(bot, link))
                .orElseThrow(() -> new ChatChannelException("The bot behind that chat is gone."));
    }

    /**
     * Forget a destination.
     *
     * <p>The bot row goes with its last destination, but its WEBHOOK is left
     * pointing here on purpose. Unpointing it would silently break any workflow
     * trigger sharing that bot, and a webhook that delivers to an installation
     * with nothing to resolve is harmless: the endpoint answers 200 and ignores it.
     *
     * <p>Forgetting the DEFAULT hands the slot to the oldest destination still able to
     * receive, on the same reasoning {@code finalizeLink} applies to the first connection:
     * a workspace that still has a chat but no default is one where every unattended run
     * gets {@code NO_CHANNEL} and nobody is told why. Promotion is silent and it is also
     * reversible in one call, whereas the silence is not discoverable at all.
     */
    public void disconnect(String organizationId, UUID linkId) {
        disconnect(null, organizationId, linkId, null);
    }

    /**
     * @param tenantId the person making the change, and {@code source} who asked (analytics
     *                 only; null when unknown)
     */
    public void disconnect(String tenantId, String organizationId, UUID linkId, ChangeSource source) {
        boolean tracked = analytics != null && analytics.isActive();
        String[] channel = new String[1];
        ChatChannelLinkEntity removed = transactionTemplate.execute(status -> {
            ChatChannelLinkEntity link = linkRepository.findByIdAndOrganizationId(linkId, organizationId)
                    .orElseThrow(() -> new ChatChannelException(
                            "No connected chat with that id in this workspace."));
            UUID botId = link.getBotId();
            if (tracked) {
                // Read before the bot row can go with its last link; only for the event.
                channel[0] = botRepository.findByIdAndOrganizationId(botId, organizationId)
                        .map(ChatChannelBotEntity::getChannel).orElse(null);
            }
            linkRepository.delete(link);
            if (linkRepository.findByBotId(botId).isEmpty()) {
                botRepository.findByIdAndOrganizationId(botId, organizationId).ifPresent(botRepository::delete);
            }
            return link;
        });
        if (removed != null && removed.isDefault()) {
            promoteSuccessor(organizationId, removed.getId());
        }
        if (tracked && removed != null) {
            analytics.channelDisconnected(tenantId, organizationId, channel[0], source, removed.isDefault());
        }
    }

    /**
     * Gives the vacant default slot to the oldest destination that can still take it.
     *
     * <p>Oldest rather than newest because it is the one the workspace has been using
     * longest, and "can still take it" is the same pair of conditions {@code setDefault}
     * refuses without: a chat that never received a message cannot be shown to work, and a
     * deactivated one is excluded from the default lookup anyway, so promoting either
     * would only look like a default while behaving like none. When nothing qualifies the
     * workspace is left with no default, which is then the truth rather than a silence.
     *
     * <p>Its own transaction, committed after the delete rather than with it, and a
     * collision with a concurrent connect is swallowed for the same reason it is on the
     * connect path: the disconnect the caller asked for is done, and a workspace that ends
     * up with somebody else's default has the thing this succession exists to give it. The
     * removed row is still filtered out by id, which costs nothing and keeps the method
     * correct if it is ever called before the delete commits.
     */
    private void promoteSuccessor(String organizationId, UUID removedLinkId) {
        try {
            transactionTemplate.executeWithoutResult(status ->
                    linkRepository.findByOrganizationIdOrderByIsDefaultDescCreatedAtAsc(organizationId).stream()
                            .filter(candidate -> !candidate.getId().equals(removedLinkId))
                            .filter(ChatChannelLinkEntity::isActive)
                            .filter(candidate -> candidate.getVerifiedAt() != null)
                            .min(Comparator.comparing(ChatChannelLinkEntity::getCreatedAt))
                            .ifPresent(successor -> {
                                successor.setDefault(true);
                                linkRepository.save(successor);
                            }));
        } catch (DataIntegrityViolationException ex) {
            logger.info("[chat-channel] a connect took the default slot for org {} before the "
                    + "succession could", organizationId);
        }
    }

    // ========================================================================
    // INTERNALS
    // ========================================================================

    private ChatChannelConnector connector(String channel) {
        return connectors.forChannel(channel).orElseThrow(() -> new ChatChannelException(
                "Channel '" + channel + "' cannot be connected here. Available: "
                        + String.join(", ", connectors.supportedChannels()) + "."));
    }

    /**
     * An explicit credential wins; otherwise the workspace's default credential for
     * the integration. Resolved ONCE, here, and the resolved id is what is stored:
     * a link whose bot could change under it could not be resolved on the way back
     * from a button press.
     */
    private long resolveCredentialId(String tenantId, ChatChannelConnector connector, Long credentialId) {
        if (credentialId != null) {
            return credentialId;
        }
        // The integration, not the channel id: they differ ("microsoftteams" for "teams"), and
        // looking up the channel id finds no connection even when the person has one.
        String integration = connector.credentialIntegration().toLowerCase(Locale.ROOT);
        return credentialClient.getDefaultCredential(tenantId, integration)
                .map(CredentialSummaryDto::getId)
                .orElseThrow(() -> new ChatChannelException(
                        "No " + integration + " connection found in this workspace. "
                                + "Connect the bot's token first, then connect a chat."));
    }

    /**
     * The bot row for this workspace and credential, created if this is the first time.
     *
     * <p>Read-then-insert under {@code uq_chat_channel_bots_scope}, so two connects starting
     * together both read nothing and both insert. The loser is re-run against the row that
     * won rather than failed: the two calls are writing the same identity for the same
     * credential, so applying this one's fields to the winner produces exactly what a
     * sequential pair would have. Failing instead would abort a connect whose only fault was
     * arriving second, before it ever reached the chat.
     */
    private ChatChannelBotEntity upsertBot(String tenantId, String organizationId, String channel,
                                           long credentialId, BotIdentity identity) {
        try {
            return botRepository.save(applyBotFields(tenantId, organizationId, channel, credentialId,
                    identity, botRepository
                            .findByOrganizationIdAndChannelAndCredentialId(organizationId, channel, credentialId)
                            .orElseGet(ChatChannelBotEntity::new)));
        } catch (DataIntegrityViolationException ex) {
            ChatChannelBotEntity winner = botRepository
                    .findByOrganizationIdAndChannelAndCredentialId(organizationId, channel, credentialId)
                    .orElseThrow(() -> ex);
            return botRepository.save(applyBotFields(tenantId, organizationId, channel, credentialId,
                    identity, winner));
        }
    }

    private ChatChannelBotEntity applyBotFields(String tenantId, String organizationId, String channel,
                                                long credentialId, BotIdentity identity,
                                                ChatChannelBotEntity bot) {
        if (bot.getId() == null) {
            bot.setTenantId(tenantId);
            bot.setOrganizationId(organizationId);
            bot.setChannel(channel);
            bot.setCredentialId(credentialId);
        }
        bot.setBotIdentity(identity.providerId());
        bot.setBotUsername(identity.username());
        bot.setVerifiedAt(Instant.now());
        bot.setLastError(null);
        return bot;
    }

    /**
     * Point the bot's single webhook slot at this installation when, and only when,
     * it is free. See the class comment: the alternative silently breaks whatever
     * was already using it.
     */
    private WebhookOutcome ensureWebhook(String tenantId, ChatChannelConnector connector,
                                         ChatChannelBotEntity bot) {
        if (!connector.capabilities().managesWebhook()) {
            return WebhookOutcome.NOT_OURS_TO_SET;
        }
        String ourUrl = callbackUrl(connector.channelId());
        Outcome<Optional<String>> current = connector.currentWebhookUrl(tenantId, bot.getCredentialId());
        if (!current.ok()) {
            bot.setLastError(current.error());
            botRepository.save(bot);
            return WebhookOutcome.FAILED;
        }
        Optional<String> existing = current.value();
        if (existing.isEmpty()) {
            Outcome<Void> applied = connector.applyWebhook(tenantId, bot.getCredentialId(), ourUrl);
            if (!applied.ok()) {
                bot.setLastError(applied.error());
                botRepository.save(bot);
                return WebhookOutcome.FAILED;
            }
            bot.setWebhookUrl(ourUrl);
            bot.setWebhookSetAt(Instant.now());
            botRepository.save(bot);
            return WebhookOutcome.POINTED;
        }

        String existingUrl = existing.get();
        bot.setWebhookUrl(existingUrl);
        botRepository.save(bot);
        if (existingUrl.equals(ourUrl)) {
            return WebhookOutcome.ALREADY_OURS;
        }
        return sameHost(existingUrl, ourUrl)
                ? WebhookOutcome.KEPT_INSTALLATION_URL
                : WebhookOutcome.FOREIGN;
    }

    /**
     * The destination row for this bot and chat, created if this is the first time.
     *
     * <p>Same read-then-insert race as {@link #upsertBot}, under
     * {@code uq_chat_channel_links_bot_chat}: connecting the same chat twice at once (a
     * double-clicked button, an agent retrying a call still in flight) has both calls read
     * nothing and both insert. The loser is re-run against the winner's row, which is last
     * writer wins, the same answer the two calls would have produced one after the other.
     */
    private UpsertedLink upsertLink(String tenantId, String organizationId,
                                    ChatChannelBotEntity bot, String chatId,
                                    String chatTitle, String chatType,
                                    List<String> allowedUserIds) {
        try {
            Optional<ChatChannelLinkEntity> existing = linkRepository.findByBotIdAndChatId(bot.getId(), chatId);
            return new UpsertedLink(linkRepository.save(applyLinkFields(tenantId, organizationId, bot, chatId,
                    chatTitle, chatType, allowedUserIds, existing.orElseGet(ChatChannelLinkEntity::new))),
                    existing.isEmpty());
        } catch (DataIntegrityViolationException ex) {
            // A concurrent connect created the row first: for this call it is an update.
            ChatChannelLinkEntity winner = linkRepository.findByBotIdAndChatId(bot.getId(), chatId)
                    .orElseThrow(() -> ex);
            return new UpsertedLink(linkRepository.save(applyLinkFields(tenantId, organizationId, bot, chatId,
                    chatTitle, chatType, allowedUserIds, winner)), false);
        }
    }

    private ChatChannelLinkEntity applyLinkFields(String tenantId, String organizationId,
                                                  ChatChannelBotEntity bot, String chatId,
                                                  String chatTitle, String chatType,
                                                  List<String> allowedUserIds,
                                                  ChatChannelLinkEntity link) {
        if (link.getId() == null) {
            link.setTenantId(tenantId);
            link.setOrganizationId(organizationId);
            link.setBotId(bot.getId());
            link.setChatId(chatId);
        }
        // Only overwrite a label with one that exists: reconnecting a chat whose
        // title discovery could not see must not erase the name it already had.
        if (chatTitle != null && !chatTitle.isBlank()) {
            link.setChatTitle(chatTitle);
        }
        if (chatType != null && !chatType.isBlank()) {
            link.setChatType(chatType);
        }
        // Set only when the caller said something, and null is what "said nothing" looks like:
        // a reconnect that does not mention the allow-list must not quietly widen a group back
        // to "anyone in the chat decides".
        //
        // An EMPTY list is saying something: open it to anyone in the chat. That used to be
        // ignored here, which left the restriction unclearable - it could be replaced but never
        // removed - and the branch was dead anyway, because every caller coerced absent to empty
        // before it arrived. The tri-state now survives the whole way from the request.
        if (allowedUserIds != null) {
            link.setAllowedUserIds(allowedUserIds);
        }
        return link;
    }

    /**
     * Whether a destination names the bot rather than a chat it writes to.
     *
     * <p>Compared on the bot's username (with or without {@code @}) and on its provider id, the two
     * forms a person copies from the bot's own profile. Case-insensitive, as usernames are.
     */
    static boolean isTheBotItself(String chatId, BotIdentity bot) {
        if (chatId == null || bot == null) {
            return false;
        }
        String typed = chatId.trim();
        String bare = typed.startsWith("@") ? typed.substring(1) : typed;
        if (bot.username() != null && !bot.username().isBlank() && bare.equalsIgnoreCase(bot.username())) {
            return true;
        }
        return bot.providerId() != null && !bot.providerId().isBlank() && typed.equals(bot.providerId());
    }

    /** The public URL this installation receives channel callbacks on. */
    String callbackUrl(String channel) {
        String base = publicBaseUrl != null ? publicBaseUrl.trim() : "";
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/approval-callback/" + channel;
    }

    /**
     * Whether an existing webhook belongs to this installation on another path.
     *
     * <p>Host comparison rather than prefix matching: the webhook base URL and the
     * one a workflow trigger was built from can differ in path and still be the
     * same deployment, and an unparseable URL is treated as foreign, which is the
     * safe side (we leave it alone and say so).
     */
    private static boolean sameHost(String a, String b) {
        try {
            String hostA = URI.create(a).getHost();
            String hostB = URI.create(b).getHost();
            return hostA != null && hostA.equalsIgnoreCase(hostB);
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private static String buildWarning(WebhookOutcome webhook, boolean delivered, String sendError,
                                       String undeliveredHint) {
        if (!delivered) {
            return "The chat is saved but nothing was delivered to it: " + sendError
                    + (undeliveredHint != null ? " " + undeliveredHint : "");
        }
        return switch (webhook) {
            case FOREIGN -> "Messages will reach this chat, but its bot posts its updates to another server, "
                    + "so buttons pressed here will not come back. Point that bot here, or use a bot of your own.";
            case FAILED -> "Messages will reach this chat, but the bot's webhook could not be read or set, "
                    + "so buttons pressed here may not come back.";
            case POINTED, ALREADY_OURS, KEPT_INSTALLATION_URL, NOT_OURS_TO_SET -> null;
        };
    }

    private static ChatChannelSummary summary(ChatChannelBotEntity bot, ChatChannelLinkEntity link) {
        return new ChatChannelSummary(
                link.getId(), bot.getChannel(), bot.getCredentialId(), bot.getBotUsername(),
                link.getChatId(), link.getChatTitle(), link.getChatType(),
                link.isDefault(), link.isActive(), link.getVerifiedAt(),
                bot.getWebhookUrl(), bot.getWebhookSetAt(),
                link.getLastError() != null ? link.getLastError() : bot.getLastError(),
                link.getAllowedUserIds());
    }
}
