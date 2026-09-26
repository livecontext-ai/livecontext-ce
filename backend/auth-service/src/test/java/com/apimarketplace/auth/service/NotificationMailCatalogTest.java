package com.apimarketplace.auth.service;

import com.apimarketplace.common.i18n.MessageCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The notification email wrapper catalog ({@code i18n/notification-mail_*.properties}): strict
 * parity, and every locale REALLY translated. A missing key falls back to English at runtime, so
 * an English value pasted into {@code de} would pass parity and still show English to German readers.
 */
@DisplayName("Notification mail wrapper catalog - six locales, parity and real translations")
class NotificationMailCatalogTest {

    private static final MessageCatalog CATALOG = NotificationMailer.CATALOG;

    /** Values that are legitimately the same in every language. Add a proper noun here, never a sentence. */
    private static final Set<String> PROPER_NOUNS = Set.of("LiveContext");

    /** Same as English is allowed only for a proper noun or a value made of placeholders and punctuation. */
    static boolean mayEqualEnglish(String value) {
        if (value == null) return false;
        if (PROPER_NOUNS.contains(value.trim())) return true;
        return value.replaceAll("\\{[A-Za-z_][A-Za-z0-9_]*\\}", "").replaceAll("[\\p{P}\\p{S}\\s]", "").isEmpty();
    }

    @Test
    @DisplayName("every key in all six locales, same placeholders, no dash, nothing empty")
    void parity() {
        assertThat(CATALOG.parityProblems()).isEmpty();
        for (String locale : MessageCatalog.LOCALES) {
            assertThat(CATALOG.keys(locale)).as(locale).containsExactlyInAnyOrderElementsOf(CATALOG.keys("en"));
        }
    }

    @Test
    @DisplayName("every non-English value differs from English (proper nouns and placeholder-only values excepted)")
    void reallyTranslated() {
        for (String locale : MessageCatalog.LOCALES) {
            if ("en".equals(locale)) continue;
            int checked = 0;
            for (String key : CATALOG.keys("en")) {
                String en = CATALOG.raw("en", key);
                if (mayEqualEnglish(en)) continue;
                checked++;
                assertThat(CATALOG.raw(locale, key)).as(locale + " " + key + " is still English").isNotEqualTo(en);
            }
            assertThat(checked).as(locale + ": the check must actually compare something").isPositive();
        }
    }

    @Test
    @DisplayName("the exemption is narrow: a sentence is never exempt, a proper noun or a bare placeholder is")
    void exemptionIsNarrow() {
        assertThat(mayEqualEnglish("Manage notifications")).isFalse();
        assertThat(mayEqualEnglish("Open")).isFalse();
        assertThat(mayEqualEnglish("LiveContext")).isTrue();
        assertThat(mayEqualEnglish("{plan}")).isTrue();
        assertThat(mayEqualEnglish("{count} / {total}")).isTrue();
    }
}
