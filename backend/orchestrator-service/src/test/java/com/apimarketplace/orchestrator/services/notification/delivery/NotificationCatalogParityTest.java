package com.apimarketplace.orchestrator.services.notification.delivery;

import com.apimarketplace.common.i18n.MessageCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@DisplayName("Notification message catalog - six locales at strict parity")
class NotificationCatalogParityTest {

    private static final MessageCatalog CATALOG = NotificationMessageComposer.CATALOG;

    @Test
    @DisplayName("Every key in every locale, same placeholders, no empty value, no em or en dash, plural pairs complete")
    void parity() {
        assertThat(CATALOG.parityProblems()).isEmpty();
        for (String locale : MessageCatalog.LOCALES) {
            assertThat(CATALOG.keys(locale)).as(locale).containsExactlyElementsOf(CATALOG.keys("en"));
        }
    }

    @Test
    @DisplayName("Every locale is really translated: the sentences differ from English")
    void translated() {
        for (String locale : MessageCatalog.LOCALES) {
            if ("en".equals(locale)) continue;
            for (String key : new String[]{"run_failed.line1", "failure.rationed", "credit_low.line2",
                    "digest.intro.other", "action.open_workflow"}) {
                assertThat(CATALOG.raw(locale, key)).as(locale + " " + key).isNotEqualTo(CATALOG.raw("en", key));
            }
        }
    }

    @Test
    @DisplayName("French uses vouvoiement: never tu / ton / ta / tes")
    void frenchVouvoiement() {
        for (String key : CATALOG.keys("fr")) {
            assertThat(CATALOG.raw("fr", key)).as(key)
                    .doesNotContainPattern("(?i)(^|[\\s'])(tu|ton|ta|tes|toi)(\\s|$)");
        }
    }

    @Test
    @DisplayName("Every date pattern is a valid java.time pattern in its locale")
    void datePatterns() {
        for (String locale : MessageCatalog.LOCALES) {
            String pattern = CATALOG.raw(locale, "format.datetime");
            assertThatCode(() -> DateTimeFormatter.ofPattern(pattern, Locale.forLanguageTag(locale))
                    .withZone(ZoneId.of("UTC")).format(Instant.parse("2026-09-24T14:05:00Z")))
                    .as(locale).doesNotThrowAnyException();
        }
        assertThat(DateTimeFormatter.ofPattern(CATALOG.raw("fr", "format.datetime"))
                .withZone(ZoneId.of("Europe/Paris")).format(Instant.parse("2026-09-24T12:05:00Z")))
                .isEqualTo("24/09/2026 14:05");
    }

    @Test
    @DisplayName("Every category a person can be notified about has a digest label")
    void everyCategoryLabelled() {
        for (NotificationTopic topic : NotificationTopic.values()) {
            for (String category : topic.categories()) {
                assertThat(CATALOG.keys("en")).as(category).contains("category." + category);
            }
        }
    }
}
