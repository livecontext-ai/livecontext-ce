package com.apimarketplace.orchestrator.services.channel;

/**
 * Where a press or a typed reply actually came in, as far as the receiving endpoint can prove it.
 *
 * <p>A button's token says WHICH request is being answered; this says whether the answer arrived
 * the way that request's message went out. Without it a token copied from one provider (read out
 * of a Slack or Telegram client by any member of the chat) could be replayed on another
 * provider's endpoint, including one whose body is not signed, with a presser id of the
 * attacker's choosing: every allow-list would then be decorative. So every request row and every
 * approval delivery is only answerable from:
 * <ul>
 *   <li>its own provider ({@code channel});</li>
 *   <li>its own bot, when the endpoint names one ({@code credentialId}: the Discord and WhatsApp
 *       endpoints are per bot, so a press on bot A's endpoint cannot decide bot B's message);</li>
 *   <li>its own chat, when the provider says which chat the press came from ({@code chatId}: the
 *       message's chat on Telegram, Slack and Discord, the sender's number on WhatsApp, where a
 *       chat IS one person).</li>
 * </ul>
 * A null component is one the endpoint cannot know (a Teams link carries no chat), and is not
 * checked. A mismatch is answered exactly like an unknown token, so it reveals nothing.
 *
 * @param channel      the provider whose endpoint received it; never null
 * @param credentialId the receiving bot's credential, or null when the endpoint is shared
 * @param chatId       the chat the provider says it came from, or null when it does not say
 */
public record PressOrigin(String channel, Long credentialId, String chatId) {

    public PressOrigin {
        if (channel == null || channel.isBlank()) {
            throw new IllegalArgumentException("A press origin needs its channel");
        }
    }

    /** Only the provider is known: an endpoint shared by every bot, with no chat in the press. */
    public static PressOrigin of(String channel) {
        return new PressOrigin(channel, null, null);
    }

    /**
     * Whether an answer from here may decide a row sent with these values.
     *
     * <p>A row with no channel predates the other providers and was sent on Telegram.
     */
    public boolean admits(String rowChannel, Long rowCredentialId, String rowChatId) {
        String sentOn = rowChannel == null || rowChannel.isBlank() ? "telegram" : rowChannel;
        if (!channel.equalsIgnoreCase(sentOn)) {
            return false;
        }
        if (credentialId != null && !credentialId.equals(rowCredentialId)) {
            return false;
        }
        return chatId == null || chatId.equals(rowChatId);
    }
}
