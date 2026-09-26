package com.apimarketplace.orchestrator.services.notification.delivery;

import com.apimarketplace.common.i18n.MessageCatalog;

import java.time.DateTimeException;
import java.time.ZoneId;

/**
 * The language and time zone ONE recipient reads a message in: always one of the app
 * locales and a valid zone, never null.
 */
public record NotificationLocale(String locale, ZoneId zone) {

    /** Printed as "UTC" in a message ({@code ZoneOffset.UTC} would print "Z"). */
    public static final ZoneId UTC = ZoneId.of("UTC");
    public static final NotificationLocale DEFAULT = new NotificationLocale(MessageCatalog.DEFAULT_LOCALE, UTC);

    public NotificationLocale {
        locale = MessageCatalog.normalizeLocale(locale);
        if (zone == null) zone = UTC;
    }

    /** From untrusted strings: an unsupported locale reads {@code en}, an invalid zone {@code UTC}. */
    public static NotificationLocale of(String locale, String timeZone) {
        ZoneId zone = UTC;
        if (timeZone != null && !timeZone.isBlank()) {
            try {
                zone = ZoneId.of(timeZone.trim());
            } catch (DateTimeException ignored) {
                // Unknown zone id: UTC, which the message states explicitly.
            }
        }
        return new NotificationLocale(locale, zone);
    }
}
