package com.apimarketplace.auth.service;

import com.apimarketplace.auth.service.mail.MailLocaleResolver;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the squat-recovery consume URL the email links to, against the address the app actually
 * serves: {@code app/[locale]/app/settings/cloud-account/recover/[token]}, registered public by
 * {@code appRouteAuth.ts} as {@code /app/settings/cloud-account/recover}.
 *
 * <p>These tests used to pin {@code /settings/cloud-account/recover/<token>} - no {@code /app}
 * segment, no language. That address has no route and no rewrite, so the link in the mail landed
 * on the not-found page: this file asserted a dead link, confidently, for as long as it existed.
 * A test that pins a string nobody checked against the router pins whatever the author believed.
 */
class SquatRecoveryMailerTest {

    /**
     * The path {@code appRouteAuth.ts} registers as publicly reachable without a session. A link
     * that does not start with it (after any language prefix) cannot be opened by the person the
     * mail is addressed to, who by construction cannot sign in: the address is being taken from
     * them.
     */
    private static final String PUBLIC_ROUTE = "/app/settings/cloud-account/recover";

    @Test
    @DisplayName("recoveryUrl targets the route the app serves, under /app, not the legacy cloud-link one")
    void recoveryUrlTargetsCloudAccount() {
        String url = SquatRecoveryMailer.recoveryUrl("https://livecontext.ai", "en", "tok-123");

        assertThat(url).isEqualTo("https://livecontext.ai" + PUBLIC_ROUTE + "/tok-123");
        assertThat(url).doesNotContain("/cloud-link/");
    }

    @Test
    @DisplayName("recoveryUrl puts the token in the path segment (kept out of any query string)")
    void recoveryUrlKeepsTokenInPath() {
        String url = SquatRecoveryMailer.recoveryUrl("http://localhost:3000", "en", "abc_DEF-456");

        assertThat(url).isEqualTo("http://localhost:3000" + PUBLIC_ROUTE + "/abc_DEF-456");
        assertThat(url).doesNotContain("?");
    }

    @Test
    @DisplayName("recoveryUrl carries the reader's language, so the page opens in the mail's language")
    void recoveryUrlCarriesTheReadersLanguage() {
        String german = SquatRecoveryMailer.recoveryUrl("https://livecontext.ai", "de", "tok-123");

        // The prefix goes BEFORE /app: that is the [locale] segment of app/[locale]/app/...
        assertThat(german).isEqualTo("https://livecontext.ai/de" + PUBLIC_ROUTE + "/tok-123");

        // English is deliberately unprefixed (localizedPath omits the default locale), so a bare
        // path here is that decision and not a missing prefix. Asserted next to the German case so
        // the two cannot be confused by a later reader.
        assertThat(SquatRecoveryMailer.recoveryUrl("https://livecontext.ai", "en", "tok-123"))
                .isEqualTo("https://livecontext.ai" + PUBLIC_ROUTE + "/tok-123");
    }

    // ===== The mail itself. This is a SECURITY alert, which is the worst kind of message to
    // send in a language the reader does not follow, or with an expiry that is not true. =====

    private JavaMailSender mailSender;
    private MailLocaleResolver locales;

    private SquatRecoveryMailer mailerWithTtl(long ttlMinutes) {
        mailSender = mock(JavaMailSender.class);
        when(mailSender.createMimeMessage()).thenAnswer(inv ->
                new MimeMessage(Session.getDefaultInstance(new Properties())));
        locales = mock(MailLocaleResolver.class);
        when(locales.forEmail(anyString())).thenReturn("en");
        return new SquatRecoveryMailer(mailSender, locales, "noreply@example.com", "LiveContext",
                "https://app.example.com", ttlMinutes);
    }

    private MimeMessage captureSent() {
        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        return captor.getValue();
    }

    /**
     * The whole message as text, with quoted-printable soft line breaks removed.
     *
     * <p>The unfolding is what makes a NEGATIVE assertion trustworthy. JavaMail wraps the encoded
     * body around column 76 with "=\r\n", at an offset that depends on the surrounding inline
     * styles, so a break can land in the middle of the exact string a test asserts is ABSENT - and
     * the assertion then passes on a message that does contain it. That is a false pass on an
     * escaping guard, which is the one place a false pass is worst.
     */
    /**
     * A catalog sentence as the TEXT part renders it: emphasis markers gone.
     *
     * <p>The markers belong to the catalog, so a sentence it emphasizes appears verbatim in neither
     * half of the mail - which is how adding emphasis to two strings broke a guard meant to be about
     * whether a sentence is present at all.
     */
    private static String asTextPartReadsIt(String sentence) {
        return sentence.replaceAll("\\*([^*]+)\\*", "$1");
    }

    /**
     * Every catalog sentence this mail is built from.
     *
     * <p>The guard that was missing: the shell extraction replaced three footers with one generic
     * sentence, dropped an itemised inventory and dropped two sign-offs, and nothing failed. It existed
     * for three of the seven mails; this is the same guard for this one.
     *
     * <p>Asserted on the TEXT part so one half cannot satisfy a claim about the other.
     */
    private void assertMailIsBuiltFrom(MimeMessage sent, String... keys) throws Exception {
        String plain = plainTextPartOf(sent);
        for (String key : keys) {
            String sentence = SquatRecoveryMailer.CATALOG.text("en", key, java.util.Map.of("minutes", "60"));
            assertThat(plain)
                    .as("the mail must still carry " + key)
                    .contains(asTextPartReadsIt(sentence));
        }
    }

    /** The decoded text/plain part: the instance check first, or a nested alternative is walked into. */
    private static String plainTextPartOf(MimeMessage sent) throws Exception {
        String found = firstText(sent.getContent());
        if (found == null) throw new AssertionError("the message carries no text/plain part");
        return found;
    }

    private static String firstText(Object content) throws Exception {
        if (content instanceof jakarta.mail.Multipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                String nested = firstText(multipart.getBodyPart(i).getContent());
                if (nested != null) return nested;
            }
            return null;
        }
        if (content instanceof String text) {
            return text.stripLeading().startsWith("<") ? null : text;
        }
        return null;
    }

    private static String payloadOf(MimeMessage sent) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        sent.writeTo(out);
        return out.toString("UTF-8").replace("=\r\n", "").replace("=\n", "");
    }

    @Test
    @DisplayName("states the expiry the token ACTUALLY has, from the same property")
    void expiryMatchesTheConfiguredTtl() throws Exception {
        // The prose used to say "60 minutes" while the TTL was configurable, so an operator who
        // shortened it turned this mail into a false promise. Reading the same key is the fix;
        // this is what stops a later edit from drifting them apart again.
        SquatRecoveryMailer mailer = mailerWithTtl(15L);

        mailer.sendRecoveryEmail("victim@example.com", "tok", null);

        assertThat(payloadOf(captureSent()))
                .contains(SquatRecoveryMailer.CATALOG.text("en", "squat.expiry", java.util.Map.of("minutes", "15")));
    }

    @Test
    @DisplayName("the property it reads is the one the token service mints against")
    void readsTheSamePropertyAsTheTokenService() throws Exception {
        // Both @Value defaults must name the same key, or the two drift silently the moment the
        // key is overridden in one deployment.
        String mailerKey = ttlMinutesPropertyOf(SquatRecoveryMailer.class);
        String tokenKey = ttlMinutesPropertyOf(SquatRecoveryTokenService.class);

        assertThat(mailerKey).isEqualTo("cloud-link.squat-recovery.token-ttl-minutes");
        assertThat(tokenKey).isEqualTo(mailerKey);
    }

    /**
     * The property name behind the single {@code ttl-minutes} {@code @Value} on {@code type}.
     *
     * <p>It used to take a parameter name and ignore it, matching on the substring instead: a lie at
     * every call site, and a silent wrong answer the day a class gains a second such property. The
     * name now says what it does, and the {@link AssertionError} below says when that stops being
     * enough.
     */
    private static String ttlMinutesPropertyOf(Class<?> type) {
        String found = null;
        for (java.lang.reflect.Constructor<?> constructor : type.getDeclaredConstructors()) {
            for (java.lang.reflect.Parameter parameter : constructor.getParameters()) {
                org.springframework.beans.factory.annotation.Value value =
                        parameter.getAnnotation(org.springframework.beans.factory.annotation.Value.class);
                if (value == null) continue;
                String expression = value.value();
                if (expression.contains("ttl-minutes")) {
                    String property = expression.substring(
                            expression.indexOf("${") + 2, expression.indexOf(':'));
                    if (found != null && !found.equals(property)) {
                        throw new AssertionError(
                                type.getSimpleName() + " now has two ttl-minutes properties (" + found
                                        + ", " + property + "); this helper can no longer answer "
                                        + "which one the test means.");
                    }
                    found = property;
                }
            }
        }
        if (found == null) throw new AssertionError("no ttl-minutes @Value on " + type.getSimpleName());
        return found;
    }

    @Test
    @DisplayName("is written in the victim's language, alert included")
    void writtenInTheVictimsLanguage() throws Exception {
        SquatRecoveryMailer mailer = mailerWithTtl(60L);
        when(locales.forEmail("victim@example.com")).thenReturn("de");

        mailer.sendRecoveryEmail("victim@example.com", "tok", null);

        MimeMessage sent = captureSent();
        assertThat(sent.getSubject()).isEqualTo(SquatRecoveryMailer.CATALOG.text("de", "squat.subject"));
        String payload = payloadOf(sent);
        assertThat(payload).containsAnyOf("lang=\"de\"", "lang=3D\"de\"");

        // And the LINK in that German mail opens the German page. Asserted on the sent message, not
        // just on the helper: the locale has to be resolved before the URL is built, and an edit
        // that reorders those two lines compiles fine and silently ships an English link in a
        // German mail. The helper tests above cannot see that.
        // Soft line breaks dropped first: quoted-printable wraps at 76 columns and the wrap point
        // moves with any edit to the surrounding text, so matching the raw payload would be a test
        // that fails on a translation change.
        String unwrapped = payload.replace("=\r\n", "").replace("=\n", "");
        assertThat(unwrapped).contains("/de/app/settings/cloud-account/recover");
    }

    @Test
    @DisplayName("still says everything it used to say, sentence by sentence")
    void keepsItsSentences() throws Exception {
        SquatRecoveryMailer mailer = mailerWithTtl(60L);

        mailer.sendRecoveryEmail("victim@example.com", "tok", "en");

        assertMailIsBuiltFrom(captureSent(), "common.greetingNoName", "squat.body", "squat.wasyou",
                "squat.wasnotyou", "squat.action", "squat.expiry", "common.signature");
    }

    @Test
    @DisplayName("signs off, because this is the mail a reader is most likely to doubt")
    void signsOff() throws Exception {
        // The template this replaced ended with a sign-off and the shell extraction dropped it. Four
        // of the five mails append it; this one and the invitation did not, and they are the two where
        // "is this genuine?" is the first question: this one says somebody tried to take over the
        // install, which is exactly the shape of a phishing message.
        SquatRecoveryMailer mailer = mailerWithTtl(60L);

        mailer.sendRecoveryEmail("victim@example.com", "tok", "en");

        assertThat(payloadOf(captureSent()))
                .contains(SquatRecoveryMailer.CATALOG.text("en", "common.signature"));
    }

    @Test
    @DisplayName("never names the other party: the mail must not be a disclosure oracle")
    void neverNamesTheAttacker() throws Exception {
        SquatRecoveryMailer mailer = mailerWithTtl(60L);

        mailer.sendRecoveryEmail("victim@example.com", "tok-secret", null);

        // A squatter can trigger this mail at will and then ask the victim what it said, so the
        // mail must disclose nothing about the account that took the address.
        //
        // Asserted against what the mail CONTAINS, not against the word "attacker": nothing in
        // this test ever supplied that word, so the old assertion could not fail for any edit to
        // any file. The address the victim owns is the only address here, and the only value
        // besides it and the link comes from the catalog.
        MimeMessage sent = captureSent();
        String payload = payloadOf(sent);
        assertThat(payload).contains("tok-secret"); // the recovery link is the only secret it carries

        java.util.List<String> addresses = java.util.regex.Pattern
                .compile("[\\w.+-]+@[\\w.-]+\\.[A-Za-z]{2,}")
                .matcher(payload)
                .results()
                .map(java.util.regex.MatchResult::group)
                // Message-ID is JavaMail's, not ours: writeTo() calls saveChanges() which stamps
                // <...JavaMail.$USER@$HOSTNAME>. On a box whose hostname is an FQDN that is a third
                // address, and this test would fail for a reason that has nothing to do with the mail.
                .filter(address -> !address.contains("JavaMail"))
                .distinct()
                .toList();
        // The sender is the product; every OTHER address would be somebody the reader was not
        // supposed to learn about.
        assertThat(addresses)
                .as("the only addresses are the reader's own and the product's own sender")
                .containsExactlyInAnyOrder("victim@example.com", "noreply@example.com");

        // And structurally: this mailer is never TOLD who the other account is, so it cannot
        // leak them even by accident. Adding a parameter to "explain the situation" is the
        // change that would break this, which is the point at which somebody should think.
        // `getDeclaredMethod` THROWS when the signature is gone, so an `isNotNull()` on its
        // result can never fail - the count below is what actually holds the line. Kept as a
        // call, without the assertion, because the throw is the check. Three parameters: the address,
        // the token and the language the caller read off the row - none of them the other account.
        SquatRecoveryMailer.class.getDeclaredMethod("sendRecoveryEmail", String.class, String.class, String.class);
        assertThat(java.util.Arrays.stream(SquatRecoveryMailer.class.getDeclaredMethods())
                .filter(m -> m.getName().equals("sendRecoveryEmail"))
                .count())
                .as("exactly one send method, taking the victim address, the token and the language")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("an SMTP failure is swallowed: the audit row is the durable record")
    void smtpFailureIsSwallowed() {
        SquatRecoveryMailer mailer = mailerWithTtl(60L);
        doThrow(new MailSendException("SMTP unreachable")).when(mailSender).send(any(MimeMessage.class));

        assertThatCode(() -> mailer.sendRecoveryEmail("victim@example.com", "tok", null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the language the CALLER read off the victim row wins over a lookup by address")
    void carriedLanguageBeatsTheLookup() throws Exception {
        // A security alert is the worst of the five mails to send in a language its reader does not
        // follow, and it was the one mailer still going back to the database for it. The lookup is a
        // second query for something the caller already holds, and it answers English if the database
        // is unreachable at that moment.
        SquatRecoveryMailer mailer = mailerWithTtl(60L);
        when(locales.forEmail(anyString())).thenReturn("en");

        mailer.sendRecoveryEmail("victim@example.com", "tok", "de");

        assertThat(captureSent().getSubject())
                .isEqualTo(SquatRecoveryMailer.CATALOG.text("de", "squat.subject"));
    }

    @Test
    @DisplayName("a blank carried language falls back to the lookup rather than pinning English")
    void blankCarriedLanguageFallsBack() throws Exception {
        SquatRecoveryMailer mailer = mailerWithTtl(60L);
        when(locales.forEmail("victim@example.com")).thenReturn("fr");

        mailer.sendRecoveryEmail("victim@example.com", "tok", "   ");

        assertThat(captureSent().getSubject())
                .isEqualTo(SquatRecoveryMailer.CATALOG.text("fr", "squat.subject"));
    }
}
