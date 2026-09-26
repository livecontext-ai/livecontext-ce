package com.apimarketplace.orchestrator.services.notification.delivery;

import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.common.i18n.MessageCatalog;
import com.apimarketplace.orchestrator.services.notification.SubjectNameResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("NotificationMessageComposer - the words of every message that leaves the app, in the reader's language")
class NotificationMessageComposerTest {

    private static final UUID WF_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final String EN_USER = "42";
    private static final String FR_USER = "43";
    private static final Instant AT = Instant.parse("2026-09-24T03:12:00Z");

    private final NotificationLocaleResolver locales = mock(NotificationLocaleResolver.class);
    private final NotificationMessageComposer composer = new NotificationMessageComposer(List.of(
            new SubjectNameResolver() {
                @Override public String subjectType() { return SubjectNameResolver.WORKFLOW; }
                @Override public Map<UUID, String> resolveNames(Set<UUID> ids) {
                    return ids.contains(WF_ID) ? Map.of(WF_ID, "Nightly import") : Map.<UUID, String>of();
                }
            }), locales);

    {
        when(locales.resolve(anyString())).thenReturn(NotificationLocale.DEFAULT);
        when(locales.resolve(FR_USER)).thenReturn(NotificationLocale.of("fr", "Europe/Paris"));
    }

    private void reader(String userId, String locale, String zone) {
        when(locales.resolve(userId)).thenReturn(NotificationLocale.of(locale, zone));
    }

    private static NotificationCreatedEvent event(String recipient, String category, Map<String, Object> payload) {
        return new NotificationCreatedEvent(1L, recipient, "org", category, "WORKFLOW", WF_ID, "run_abc",
                payload, AT);
    }

    private static NotificationCreatedEvent credit(String recipient, String category, Object remaining) {
        return new NotificationCreatedEvent(1L, recipient, "org", category, "BILLING", UUID.randomUUID(), null,
                Map.of("remainingCredits", remaining, "subjectName", "Credits"), AT);
    }

    private static NotificationIncidentStore.Incident incident(String recipient, int failures, int notified) {
        return new NotificationIncidentStore.Incident(1L, recipient, "org", WF_ID,
                Instant.parse("2026-09-22T01:00:00Z"), Instant.parse("2026-09-23T20:00:00Z"), failures,
                Instant.parse("2026-09-23T01:00:00Z"), notified);
    }

    private static List<NotificationMessageComposer.DigestItem> items(int n) {
        List<NotificationMessageComposer.DigestItem> items = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            items.add(new NotificationMessageComposer.DigestItem("CRED_EXPIRED", "CREDENTIAL", UUID.randomUUID(),
                    Map.of("subjectName", "Gmail " + i), Instant.now()));
        }
        return items;
    }

    @Nested
    @DisplayName("English reader (UTC)")
    class English {

        @Test
        @DisplayName("A failure names the workflow, links the run without a prefix, and says the next message is rationed")
        void runFailed() {
            NotificationMessage m = composer.alert(event(EN_USER, "RUN_FAILED", Map.of("status", "failed")));

            assertThat(m.subject()).isEqualTo("Workflow failed: Nightly import");
            assertThat(m.lines().get(0))
                    .isEqualTo("Your production workflow \"Nightly import\" failed at Sep 24, 2026, 3:12 AM (UTC).");
            assertThat(m.lines().get(1)).contains("at most one reminder a day");
            assertThat(m.actionPath()).isEqualTo("/app/workflow/" + WF_ID + "/run/run_abc");
            assertThat(m.actionLabel()).isEqualTo("Open the run");
        }

        @Test
        @DisplayName("Credit alerts point at billing, read the balance from the payload, grouped per locale")
        void creditLow() {
            NotificationMessage m = composer.alert(credit(EN_USER, "CREDIT_LOW", "12345"));

            assertThat(m.subject()).isEqualTo("Your LiveContext credits are running low");
            assertThat(m.lines().get(0)).isEqualTo("About 12,345 credits are left in this cycle.");
            assertThat(m.actionPath()).isEqualTo("/app/settings/billing");
            assertThat(composer.alert(credit(EN_USER, "CREDIT_LOW", "1")).lines().get(0))
                    .as("singular").isEqualTo("About 1 credit is left in this cycle.");
        }

        @Test
        @DisplayName("A digest category has no single-alert form")
        void digestCategoryHasNoAlert() {
            assertThat(composer.alert(event(EN_USER, "CRED_EXPIRED", Map.of()))).isNull();
        }

        @Test
        @DisplayName("A reminder counts only the failures since the last message, plural and singular")
        void reminderCountsSinceLastMessage() {
            NotificationMessage m = composer.reminder(incident(EN_USER, 48, 1));

            assertThat(m.subject()).isEqualTo("Still failing: Nightly import");
            assertThat(m.lines().get(0)).isEqualTo(
                    "\"Nightly import\" failed 47 more times since Sep 23, 2026, 1:00 AM (UTC).");
            assertThat(m.lines().get(1)).contains("48 failures in total");
            assertThat(composer.reminder(incident(EN_USER, 2, 1)).lines().get(0)).contains("failed 1 more time since");
        }

        @Test
        @DisplayName("The digest lists at most 20 items and says how many more are in the app")
        void digestCapsItems() {
            NotificationMessage m = composer.digest(EN_USER, items(23));

            assertThat(m.subject()).isEqualTo("Your LiveContext summary: 23 items");
            assertThat(m.lines()).hasSize(1 + 20 + 1);
            assertThat(m.lines().get(0)).isEqualTo("23 things need your attention:");
            assertThat(m.lines().get(1)).isEqualTo("- Credential expired: Gmail 0");
            assertThat(m.lines().get(21)).isEqualTo("- and 3 more in the app");
            assertThat(composer.digest(EN_USER, items(1)).subject()).isEqualTo("Your LiveContext summary: 1 item");
        }

        @Test
        @DisplayName("Nothing to summarise means no message at all")
        void emptyDigest() {
            assertThat(composer.digest(EN_USER, List.of())).isNull();
        }
    }

    @Nested
    @DisplayName("French reader (Europe/Paris)")
    class French {

        @Test
        @DisplayName("RUN_FAILED: French subject and lines, time in Paris time with the zone named, /fr link")
        void runFailed() {
            NotificationMessage m = composer.alert(event(FR_USER, "RUN_FAILED", Map.of()));

            assertThat(m.subject()).isEqualTo("Échec du workflow : Nightly import");
            assertThat(m.lines()).containsExactly(
                    "Votre workflow de production « Nightly import » a échoué le 24/09/2026 05:12 (Europe/Paris).",
                    "Tant qu'il continue d'échouer, vous recevrez au plus un rappel par jour, puis un message dès "
                            + "qu'il s'exécutera de nouveau avec succès.");
            assertThat(m.actionPath()).isEqualTo("/fr/app/workflow/" + WF_ID + "/run/run_abc");
            assertThat(m.actionLabel()).isEqualTo("Ouvrir l'exécution");
        }

        @Test
        @DisplayName("Winter time: the same instant reads one hour less in Paris in January")
        void winterTime() {
            NotificationMessage m = composer.alert(new NotificationCreatedEvent(1L, FR_USER, "org", "RUN_FAILED",
                    "WORKFLOW", WF_ID, null, Map.of(), Instant.parse("2026-01-15T03:12:00Z")));

            assertThat(m.lines().get(0)).contains("15/01/2026 04:12 (Europe/Paris)");
            assertThat(m.actionPath()).as("no run id: the workflow").isEqualTo("/fr/app/workflow/" + WF_ID);
        }

        @Test
        @DisplayName("French plural: 0 and 1 are singular, 2 and more plural")
        void plural() {
            assertThat(composer.reminder(incident(FR_USER, 1, 0)).lines().get(1)).contains("1 échec au total");
            assertThat(composer.reminder(incident(FR_USER, 48, 1)).lines().get(1)).contains("48 échecs au total");
            assertThat(composer.alert(credit(FR_USER, "CREDIT_LOW", "0")).lines().get(0))
                    .isEqualTo("Il vous reste environ 0 crédit sur ce cycle.");
            assertThat(composer.alert(credit(FR_USER, "CREDIT_LOW", "180")).lines().get(0))
                    .isEqualTo("Il vous reste environ 180 crédits sur ce cycle.");
        }

        @Test
        @DisplayName("Recovered: both ends of the failing window in Paris time")
        void recovered() {
            NotificationMessage m = composer.recovered(incident(FR_USER, 3, 1));

            assertThat(m.subject()).isEqualTo("Rétabli : Nightly import");
            assertThat(m.lines().get(1)).isEqualTo(
                    "Il avait échoué 3 fois entre le 22/09/2026 03:00 (Europe/Paris) et le 23/09/2026 22:00 (Europe/Paris).");
            assertThat(m.actionPath()).isEqualTo("/fr/app/workflow/" + WF_ID);
            assertThat(m.actionLabel()).isEqualTo("Ouvrir le workflow");
        }

        @Test
        @DisplayName("Digest: French counts, labels and the billing subject named in French")
        void digest() {
            List<NotificationMessageComposer.DigestItem> list = items(22);
            list.add(0, new NotificationMessageComposer.DigestItem("CREDIT_LOW", "BILLING", UUID.randomUUID(),
                    Map.of("subjectName", "Credits"), Instant.now()));

            NotificationMessage m = composer.digest(FR_USER, list);

            assertThat(m.subject()).isEqualTo("Votre résumé LiveContext : 23 éléments");
            assertThat(m.lines().get(0)).isEqualTo("23 points requièrent votre attention :");
            assertThat(m.lines().get(1)).isEqualTo("- Crédits bientôt épuisés : Crédits");
            assertThat(m.lines().get(2)).isEqualTo("- Identifiant expiré : Gmail 0");
            assertThat(m.lines().get(21)).isEqualTo("- et 3 autres dans l'application");
            assertThat(m.actionPath()).isEqualTo("/fr/app");
            assertThat(composer.digest(FR_USER, items(1)).subject()).isEqualTo("Votre résumé LiveContext : 1 élément");
        }

        @Test
        @DisplayName("Budget cap: numbers grouped the French way")
        void budget() {
            NotificationMessage m = composer.alert(event(FR_USER, "BUDGET_REACHED",
                    Map.of("spentCredits", 12000, "capCredits", "12000")));

            // French groups thousands with a (narrow) no-break space, depending on the JDK's CLDR data.
            assertThat(m.lines().get(0).replace(' ', ' ').replace(' ', ' '))
                    .isEqualTo("« Nightly import » a dépensé 12 000 crédits sur son plafond de 12 000 pour cette période.");
        }
    }

    @Test
    @DisplayName("German reader: German date format and grouping, /de link")
    void german() {
        reader("44", "de", "Europe/Berlin");

        NotificationMessage m = composer.alert(event("44", "BUDGET_REACHED", Map.of("spentCredits", 1234, "capCredits", 1500)));

        assertThat(m.subject()).isEqualTo("Workflow an seinem Ausgabenlimit gestoppt: Nightly import");
        assertThat(m.lines().get(0)).contains("1.234 von 1.500 Credits");
        assertThat(m.actionPath()).isEqualTo("/de/app/workflow/" + WF_ID);
        assertThat(composer.alert(event("44", "RUN_FAILED", Map.of())).lines().get(0))
                .contains("24.09.2026 05:12 (Europe/Berlin)");
    }

    @Test
    @DisplayName("Chinese reader: Chinese date format, no plural change, /zh link")
    void chinese() {
        reader("45", "zh", "Asia/Shanghai");

        NotificationMessage m = composer.reminder(incident("45", 2, 1));

        assertThat(m.subject()).isEqualTo("仍在失败：Nightly import");
        assertThat(m.lines().get(0)).isEqualTo("自 2026年9月23日 09:00 (Asia/Shanghai) 以来，“Nightly import”又失败了 1 次。");
        assertThat(m.actionPath()).isEqualTo("/zh/app/workflow/" + WF_ID);
    }

    @Test
    @DisplayName("Auth-service unreachable: the message still goes out, in English with UTC times")
    void fallbackWhenAuthUnreachable() {
        AuthClient auth = mock(AuthClient.class);
        when(auth.getLocaleContext(anyString())).thenReturn(AuthClient.LocaleContext.FALLBACK);
        NotificationMessageComposer realResolver = new NotificationMessageComposer(List.of(),
                new NotificationLocaleResolver(auth, Duration.ofMinutes(10)));

        NotificationMessage m = realResolver.alert(event(FR_USER, "RUN_FAILED", Map.of("subjectName", "Import")));

        assertThat(m.subject()).isEqualTo("Workflow failed: Import");
        assertThat(m.lines().get(0)).contains("Sep 24, 2026, 3:12 AM (UTC)");
        assertThat(m.actionPath()).startsWith("/app/");
    }

    @Test
    @DisplayName("A stored locale the catalog does not speak, or a broken zone, reads English and UTC")
    void unsupportedLocaleAndZone() {
        reader("46", "it", "Not/AZone");

        NotificationMessage m = composer.alert(event("46", "RUN_FAILED", Map.of()));

        assertThat(m.subject()).isEqualTo("Workflow failed: Nightly import");
        assertThat(m.lines().get(0)).contains("(UTC)");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"en", "fr", "de", "es", "pt", "zh"})
    @DisplayName("Every message in every locale: no catalog key, no unfilled placeholder, no dash, localized link")
    void everyMessageEveryLocale(String locale) {
        String user = "u-" + locale;
        reader(user, locale, "America/New_York");
        List<NotificationMessage> all = new ArrayList<>();
        for (String category : List.of("RUN_FAILED", "BUDGET_REACHED")) {
            all.add(composer.alert(event(user, category, Map.of("spentCredits", 5, "capCredits", 10))));
        }
        all.add(composer.alert(credit(user, "CREDIT_LOW", "3")));
        all.add(composer.alert(credit(user, "CREDIT_EXHAUSTED", "0")));
        all.add(composer.failingAgain(event(user, "RUN_FAILED", Map.of())));
        all.add(composer.reminder(incident(user, 5, 2)));
        all.add(composer.recovered(incident(user, 1, 1)));
        all.add(composer.digest(user, items(25)));

        String prefix = MessageCatalog.localizedPath(locale, "/app");
        for (NotificationMessage m : all) {
            List<String> texts = new ArrayList<>(m.lines());
            texts.add(m.subject());
            texts.add(m.actionLabel());
            for (String t : texts) {
                assertThat(t).as(locale + ": " + t)
                        .doesNotMatch("(?s).*\\{[a-z_]+}.*")
                        .doesNotMatch("(?s).*\\b(run_failed|failing_again|budget|credit_low|credit_exhausted|reminder|"
                                + "recovered|digest|action|category|name|failure)\\.[a-z_.]+.*")
                        .doesNotContain("\u2014").doesNotContain("\u2013");
            }
            assertThat(m.actionPath()).startsWith(prefix);
        }
        if (!"en".equals(locale)) {
            assertThat(all.get(0).subject()).as("really translated").isNotEqualTo("Workflow failed: Nightly import");
        }
    }

    @Test
    @DisplayName("An unknown category is labelled generically, in the reader's language")
    void unknownCategoryLabel() {
        assertThat(NotificationMessageComposer.label("en", "SOMETHING_NEW")).isEqualTo("Notification");
        assertThat(NotificationMessageComposer.label("de", "SOMETHING_NEW")).isEqualTo("Benachrichtigung");
        assertThat(NotificationMessageComposer.label("fr", "RUN_FAILED")).isEqualTo("Échec du workflow");
    }

    @Test
    @DisplayName("The resolver is asked for the RECIPIENT of each message (the row's tenant)")
    void asksForTheRecipient() {
        reader("99", "es", "Europe/Madrid");

        assertThat(composer.recovered(incident("99", 2, 2)).subject()).isEqualTo("Recuperado: Nightly import");
        assertThat(composer.recovered(incident(EN_USER, 2, 2)).subject()).isEqualTo("Recovered: Nightly import");
    }

    @Test
    @DisplayName("The chat text fits every connector (Discord 2,000) and a long body never cuts the link")
    void channelText() {
        NotificationMessage m = new NotificationMessage("Subject", List.of("x".repeat(5000)), "/app/x", "Open");

        String text = m.toChannelText("https://livecontext.ai/");

        assertThat(text).hasSizeLessThanOrEqualTo(NotificationMessage.CHANNEL_TEXT_MAX_CHARS)
                .endsWith("…\n\nOpen: https://livecontext.ai/app/x");
        assertThat(NotificationMessage.CHANNEL_TEXT_MAX_CHARS).isLessThan(2000);
        assertThat(new NotificationMessage("S", List.of("l"), "/app/x", "Open").toChannelText("https://livecontext.ai/"))
                .isEqualTo("S\nl\n\nOpen: https://livecontext.ai/app/x");
    }

    @Test
    @DisplayName("The cut never splits an emoji (a surrogate pair) in half")
    void cutKeepsSurrogatePairsWhole() {
        String link = "\n\nOpen: https://x/app";
        int room = NotificationMessage.CHANNEL_TEXT_MAX_CHARS - link.length();
        // Place an emoji so its high surrogate sits exactly where the cut falls.
        String body = "a".repeat(room - 2) + "😀" + "b".repeat(50);
        String text = new NotificationMessage(body, List.of(), "/app", "Open").toChannelText("https://x");

        String kept = text.substring(0, text.indexOf("…"));
        assertThat(Character.isHighSurrogate(kept.charAt(kept.length() - 1))).isFalse();
    }

    @Test
    @DisplayName("A zone printed by its id: UTC stays UTC, never Z")
    void utcPrintedAsUtc() {
        assertThat(NotificationLocale.DEFAULT.zone()).isEqualTo(ZoneId.of("UTC"));
        assertThat(NotificationLocale.of("fr", null).zone().getId()).isEqualTo("UTC");
        assertThat(NotificationLocale.of(null, "Europe/Paris").locale()).isEqualTo("en");
    }
}
