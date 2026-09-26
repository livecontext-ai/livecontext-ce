package com.apimarketplace.orchestrator.services.notification.delivery;

import java.util.List;

/**
 * One message, before it is shaped for a medium. The email and the chat text are
 * both rendered from this, so the two can never say different things.
 *
 * @param actionPath an in-app path starting with {@code /}, never an absolute URL:
 *                   each medium prefixes its own public origin
 */
public record NotificationMessage(String subject, List<String> lines, String actionPath, String actionLabel) {

    /**
     * The longest chat text sent: under Discord's 2,000-character message limit, the smallest of the
     * five connectors, so no connector ever cuts a notice itself (which would cut the link, the last
     * line). Only a long digest gets near it.
     */
    static final int CHANNEL_TEXT_MAX_CHARS = 1900;

    /** Plain text for a chat channel: the subject, the lines, then the link, which is never truncated. */
    public String toChannelText(String publicBaseUrl) {
        String link = actionPath == null ? ""
                : "\n\n" + actionLabel + ": " + stripTrailingSlash(publicBaseUrl) + actionPath;
        StringBuilder body = new StringBuilder(subject);
        for (String line : lines) {
            body.append('\n').append(line);
        }
        int room = CHANNEL_TEXT_MAX_CHARS - link.length();
        String text = body.toString();
        if (text.length() > room) {
            int cut = Math.max(0, room - 1);
            // Never split a surrogate pair (an emoji in a workflow name): cut before it instead.
            if (cut > 0 && Character.isHighSurrogate(text.charAt(cut - 1))) cut--;
            text = text.substring(0, cut) + "…";
        }
        return text + link;
    }

    private static String stripTrailingSlash(String url) {
        if (url == null) return "";
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
