package com.apimarketplace.orchestrator.services.channel;

import java.util.List;
import java.util.Optional;

/**
 * Provider-specific half of connecting an outbound chat channel (v1: Telegram).
 * One implementation per channel, discovered by {@link ChatChannelConnectorRegistry}
 * exactly like {@code ApprovalChannelNotifier} on the delivery side.
 *
 * <p>The split is deliberate: {@link ChatChannelService} owns the workspace rules
 * (who may connect, which row is the default, what is persisted) and knows nothing
 * about bot tokens or update shapes, while a connector owns the provider round
 * trips and knows nothing about workspaces.
 *
 * <p><b>Every method reports failure as a value, never as a thrown exception.</b>
 * Connecting a channel is a guided conversation: "your bot answered but has never
 * been started by you" has to reach the person as a sentence they can act on, and
 * an exception 4 frames up becomes "something went wrong".
 */
public interface ChatChannelConnector {

    /** Stable channel id, matching {@code ApprovalChannelNotifier.channelId()}. */
    String channelId();

    /**
     * What this provider can and cannot do, declared so the services above never have to ask
     * which provider they are talking to.
     *
     * @param discoversChats      the account can list the chats it may write to
     * @param managesWebhook      the inbound URL is set per bot, by us, through the provider's API
     * @param editsMessages       a sent message can be edited; without it a settled question keeps
     *                            its buttons, so closing is a follow-up message instead
     * @param acceptsTypedReplies a typed reply to one of our messages reaches us; without it the
     *                            "Other..." button would promise an answer the provider cannot
     *                            bring back, so it is not offered
     *
     * <p>These are real differences between the five providers, not slots left open in case.
     * Telegram lets a bot read who has written to it and owns its webhook; WhatsApp knows no chat
     * list (the destination is a phone number) and its callback is set in the Meta dashboard;
     * Slack and Discord declare their callback once in their own consoles. A connector that cannot
     * do one of these says so here, and the services skip that step with a sentence rather than
     * calling a method whose only honest answer is "I cannot".
     */
    record Capabilities(boolean discoversChats, boolean managesWebhook, boolean editsMessages,
                        boolean acceptsTypedReplies) {

        /** The Telegram shape: edits messages and hears replies. */
        public Capabilities(boolean discoversChats, boolean managesWebhook) {
            this(discoversChats, managesWebhook, true, true);
        }
    }

    /**
     * The credential integration this provider's account is stored under, which is the catalog
     * {@code iconSlug} and not always the channel id ("microsoftteams" for "teams").
     */
    default String credentialIntegration() {
        return channelId();
    }

    /**
     * What the person must give at connect beyond the credential, or null when nothing.
     *
     * <p>A provider whose account cannot be fully described by its token says so here: WhatsApp
     * needs the sending phone number id, Discord the application's public key. The service
     * refuses a connect without it using this label, so the person is asked for the right thing
     * by name.
     */
    default String accountSettingLabel() {
        return null;
    }

    /**
     * Check the account, with the extra setting when the provider needs one.
     *
     * <p>Defaults to {@link #verifyBot(String, Long)}: a provider that needs nothing more
     * ignores the setting.
     */
    default Outcome<BotIdentity> verifyBot(String tenantId, Long credentialId, String accountSetting) {
        return verifyBot(tenantId, credentialId);
    }

    /**
     * The material this bot's inbound controller verifies callbacks with, or null.
     *
     * <p>Stored on the bot row. Discord: the application's public key, which is what the
     * person gave. WhatsApp: a random verify token, generated here, which the person pastes into
     * their Meta app. Neither is a credential: a public key is public, and a verify token only
     * gates the subscription handshake.
     *
     * <p>Given the current key so a provider can keep it. That matters for a generated key: a
     * WhatsApp verify token regenerated on every reconnect would break the webhook the person
     * already configured in their Meta app, with nothing on our side saying why.
     */
    default String newInboundKey(String accountSetting, String currentKey) {
        return currentKey;
    }

    /**
     * The account setting a known bot was connected with, recovered from what its row stores,
     * so a reconnect does not have to be given it again. The inverse of how the provider stores
     * it: WhatsApp keeps the sending number as the bot identity, Discord keeps the public key as
     * the inbound key.
     */
    default String storedAccountSetting(String botIdentity, String inboundKey) {
        return null;
    }

    /**
     * What the person has to do in the provider's own console for presses to come back, or null
     * when nothing (the URL is set by us, or once for the whole platform).
     *
     * @param callbackUrl this bot's inbound URL on this installation
     * @param inboundKey  the key stored on the bot row, when the provider has one
     */
    default String setupInstructions(String callbackUrl, String inboundKey) {
        return null;
    }

    /**
     * The destination id as the provider writes it, from what a person typed.
     *
     * <p>A person gives a WhatsApp number as "+33 6 12 34 56 78" while Meta sends and expects
     * "33612345678"; stored as typed, the link would never match the number a reply comes from.
     */
    default String normalizeChatId(String chatId) {
        return chatId == null ? null : chatId.trim();
    }

    /**
     * Whether a press tells us WHO pressed, in terms an allow-list can check.
     *
     * <p>False for a provider whose buttons are links (Teams): the decision page knows the link,
     * not the person. An allow-list there would refuse everybody, so a connect that sets one is
     * refused up front instead of producing a destination nobody can answer from.
     */
    default boolean identifiesPresser() {
        return true;
    }

    /**
     * The usual reason a test message did not arrive on this provider, added after the provider's
     * own error when a connect could not deliver; null when there is no usual reason.
     */
    default String undeliveredHint() {
        return null;
    }

    /**
     * Why presses on this provider cannot come back on this installation, or null when they can.
     *
     * <p>Checked at connect, after a delivered test message: a destination that receives messages
     * but whose every press will be refused must say so then, not on the first approval.
     */
    default String inboundProblem() {
        return null;
    }

    /** What this connector's provider can do. Telegram, which is both, is the default. */
    default Capabilities capabilities() {
        return new Capabilities(true, true);
    }

    /** Identity of the bot behind a credential, as the provider reports it. */
    record BotIdentity(String providerId, String username, String displayName) {}

    /** A chat the bot has recently heard from, offered as a connect candidate. */
    record ChatCandidate(String chatId, String title, String type, String fromUsername) {}

    /** Outcome of a provider round trip: either a value, or a sentence for the person. */
    record Outcome<T>(T value, String error, boolean unavailable) {
        public Outcome(T value, String error) { this(value, error, false); }
        public static <T> Outcome<T> of(T value) { return new Outcome<>(value, null); }
        public static <T> Outcome<T> failed(String error) { return new Outcome<>(null, error); }
        /**
         * Not a failure of the call: the provider will not answer THIS question in the bot's
         * current state (Telegram lists no chats once a webhook is set). The reason is said as
         * {@code error}, and the caller offers the other way instead of reporting an error.
         */
        public static <T> Outcome<T> unavailable(String reason) { return new Outcome<>(null, reason, true); }
        public boolean ok() { return error == null; }

        /**
         * Continue with the value when there is one, propagating the failure sentence
         * untouched otherwise. Keeps a chain of provider calls readable without an
         * {@code if (!ok()) return} between every pair, and guarantees the original
         * wording survives to the person rather than being re-described on the way up.
         */
        public <R> Outcome<R> map(java.util.function.Function<T, Outcome<R>> next) {
            return ok() ? next.apply(value) : Outcome.failed(error);
        }
    }

    /**
     * Prove the credential really drives a bot, and say which one. This validates
     * the TOKEN and nothing else: it says nothing about whether any given chat is
     * reachable, which is why {@link #sendTest} exists separately.
     */
    Outcome<BotIdentity> verifyBot(String tenantId, Long credentialId);

    /**
     * The webhook URL the provider currently has for this bot, empty when none.
     *
     * <p>Read before writing, always. A bot is commonly already webhooked to a
     * workflow trigger, and overwriting that silently would break the trigger
     * while looking like a successful connect.
     */
    Outcome<Optional<String>> currentWebhookUrl(String tenantId, Long credentialId);

    /**
     * Point the bot at {@code url}.
     *
     * <p>The shared secret, where the provider has one, is the connector's own: it is the same
     * value the inbound handler for THAT provider checks, in the header THAT provider sends. The
     * shared service used to hold one secret named after Telegram and hand it to every connector,
     * which is a promise it could not keep for the second one.
     */
    Outcome<Void> applyWebhook(String tenantId, Long credentialId, String url);

    /**
     * Chats the bot has recently heard from.
     *
     * <p>Telegram will not replay updates while a webhook is active, so the caller
     * is responsible for the ordering (discover before pointing the webhook, or
     * temporarily unpoint a webhook that is ours). A connector that cannot answer
     * says so in the outcome rather than returning an empty list, which would read
     * as "nobody has written to your bot" and send the person looking in the wrong
     * place.
     */
    Outcome<List<ChatCandidate>> discoverChats(String tenantId, Long credentialId);

    /** Deliver one message to a destination, which is the only proof it is reachable. */
    Outcome<Void> sendTest(String tenantId, Long credentialId, String chatId, String text);

    /**
     * Deliver a one-way notice: plain text, no buttons, no answer expected (an
     * alert, a digest). Every connector's test send already is exactly that, so
     * it is the default; a provider that ever needs a different call for a notice
     * overrides this, not {@link #sendTest}.
     */
    default Outcome<Void> sendNotice(String tenantId, Long credentialId, String chatId, String text) {
        return sendTest(tenantId, credentialId, chatId, text);
    }

    /**
     * How long a decision message may be, in characters, leaving room for the verdict line the
     * close appends later.
     *
     * <p>Provider-specific and stated here so the caller can store the body it actually sent. A
     * body kept uncapped would lose its verdict on the closing edit, which is the one line that
     * says what was decided. The default is deliberately generous: a connector that does not
     * override it is not silently truncating, and a provider that refuses an over-long message
     * says so through its own {@link Outcome}.
     */
    default int maxDecisionTextChars() {
        return 4000;
    }

    /**
     * Deliver a message carrying two buttons, an approve and a reject, each
     * returning the given opaque payload when pressed.
     *
     * @return the provider's id for the sent message, needed to close it later
     */
    Outcome<String> sendDecisionRequest(String tenantId, Long credentialId, String chatId, String text,
                                        String approvePayload, String rejectPayload);

    /**
     * One button of a question: what it reads, and what comes back when it is pressed.
     *
     * <p>Both halves are composed by the caller, deliberately. The connector knows how to draw
     * a keyboard for its provider and nothing about what the buttons mean, so a tick on a
     * toggled option, an "Other" escape and a "Done" submit are all just labels here. The
     * alternative, teaching every connector the semantics of a multi-select, is the same
     * knowledge written once per provider and wrong in a different way in each.
     */
    record ChoiceOption(String label, String payload) {}

    /**
     * Deliver a question carrying one button per option.
     *
     * <p>One option per row: a phone is narrow, labels are written by an agent and are not
     * short, and two per row turns a readable list into two columns of truncated text.
     *
     * @return the provider's id for the sent message, needed to edit and to close it later
     */
    Outcome<String> sendChoiceRequest(String tenantId, Long credentialId, String chatId, String text,
                                      List<ChoiceOption> options);

    /**
     * Redraw a question's buttons in place, leaving its text alone.
     *
     * <p>This is what a multi-select toggle looks like from here: the same question, the same
     * message, one label now carrying a tick. Unlike {@link #closeDecisionRequest} it is NOT
     * cosmetic. A toggle whose redraw fails is a checkbox that will not stay checked, and the
     * person presses it again, so the caller has to know.
     */
    Outcome<Void> updateChoiceMarkup(String tenantId, Long credentialId, String chatId, String messageId,
                                     String text, List<ChoiceOption> options);

    /**
     * Append the verdict to a delivered decision message and take its buttons away.
     *
     * <p>Cosmetic by contract: the decision has already been applied by the time
     * this runs, so a provider that refuses the edit (too old, unchanged text)
     * must not turn a successful answer into a failure. It matters anyway, because
     * buttons left live invite a second press on a question already settled.
     */
    Outcome<Void> closeDecisionRequest(String tenantId, Long credentialId, String chatId, String messageId,
                                       String originalText, String verdictLine);

    /** Acknowledge a button press, so the provider's UI stops showing it as in flight. */
    Outcome<Void> ackButton(String tenantId, Long credentialId, String buttonEventId, String text,
                            boolean asAlert);
}
