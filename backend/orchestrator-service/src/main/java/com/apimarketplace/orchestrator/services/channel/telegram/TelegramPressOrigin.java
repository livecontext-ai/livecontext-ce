package com.apimarketplace.orchestrator.services.channel.telegram;

import com.apimarketplace.orchestrator.services.channel.PressOrigin;

import java.util.Map;

/**
 * The {@link PressOrigin} of a Telegram update.
 *
 * <p>Telegram's webhook is one URL for every bot, so there is no bot to bind to; the chat is
 * known, from the message the button was on ({@code callback_query.message.chat.id}) or the
 * message that was typed ({@code message.chat.id}). A press whose message is not included (an
 * inline-mode message) binds on the provider alone.
 */
public final class TelegramPressOrigin {

    private TelegramPressOrigin() {
    }

    /** For a button press: the chat of the message the button was on. */
    public static PressOrigin ofCallback(Map<String, Object> callbackQuery) {
        Object message = callbackQuery != null ? callbackQuery.get("message") : null;
        return new PressOrigin(TelegramChannelConnector.CHANNEL_ID, null,
                message instanceof Map<?, ?> m ? chatIdOf(m) : null);
    }

    /** For a typed message: its own chat. */
    public static PressOrigin ofMessage(Map<?, ?> message) {
        return new PressOrigin(TelegramChannelConnector.CHANNEL_ID, null, message != null ? chatIdOf(message) : null);
    }

    private static String chatIdOf(Map<?, ?> message) {
        Object chat = message.get("chat");
        return chat instanceof Map<?, ?> chatMap && chatMap.get("id") != null ? String.valueOf(chatMap.get("id")) : null;
    }
}
