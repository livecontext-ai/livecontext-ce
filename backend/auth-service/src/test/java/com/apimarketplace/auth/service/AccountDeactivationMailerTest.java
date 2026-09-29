package com.apimarketplace.auth.service;

import com.apimarketplace.auth.service.mail.BrandedMail;
import com.apimarketplace.auth.service.mail.MailLocaleResolver;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

import java.io.ByteArrayOutputStream;
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
 * The three account-lifecycle mails: deactivated, restored, permanently deleted.
 *
 * <p>Each one follows an action that has ALREADY happened, which drives both properties pinned
 * here: they are written in the reader's language (the account still exists to be asked, except
 * for the purge, where the caller has to carry it), and a failure to send is swallowed rather than
 * failing the operation that caused it.
 */
@DisplayName("AccountDeactivationMailer - the right language, and never a thrown exception")
class AccountDeactivationMailerTest {

    /**
     * The support address, as a property now rather than a constant in the mailer.
     *
     * <p>Deliberately not the product one: this bean is unconditional, so a self-hosted install
     * runs it too, and a hard-coded address told those users to write to the cloud vendor about
     * their own server. A test that passed the real address could not tell the two apart.
     */
    private static final String SUPPORT = "help@example.org";

    private JavaMailSender mailSender;
    private MailLocaleResolver locales;
    private AccountDeactivationMailer mailer;

    @BeforeEach
    void setUp() {
        mailSender = mock(JavaMailSender.class);
        when(mailSender.createMimeMessage()).thenAnswer(inv ->
                new MimeMessage(Session.getDefaultInstance(new Properties())));
        locales = mock(MailLocaleResolver.class);
        when(locales.forEmail(anyString())).thenReturn("en");
        mailer = new AccountDeactivationMailer(
                mailSender, locales, "noreply@example.com", "LiveContext", "https://app.example.com",
                SUPPORT);
    }

    private MimeMessage captureSent() {
        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        return captor.getValue();
    }

    /**
     * The decoded text/plain part, so an assertion about it cannot be satisfied by the HTML half.
     *
     * <p>The sibling PasswordResetMailerTest has had this since it was written; this class asserted
     * over the whole payload instead, which is how a negative assertion here ended up unfalsifiable.
     *
     * <p>The block that used to sit above this one described {@code payloadOf}, thirty lines below,
     * and was attached to nothing - the hazard four production comments on this branch explain.
     */
    private static String plainTextPartOf(MimeMessage sent) throws Exception {
        String found = firstOfType(sent.getContent(), false);
        if (found == null) throw new AssertionError("the message carries no text/plain part");
        return found;
    }

    /**
     * Walk the parts and answer the first one whose CONTENT is text, html or not.
     *
     * <p>The instance check comes first on purpose. On a message that has not been
     * {@code saveChanges()}d, a nested {@code multipart/alternative} answers
     * {@code isMimeType("text/plain")} with true while its content is the nested multipart, so
     * testing the declared type first walks into it and returns the container's {@code toString}.
     * The same shape as the sibling PasswordResetMailerTest, which learned this first.
     */
    private static String firstOfType(Object content, boolean wantHtml) throws Exception {
        if (content instanceof jakarta.mail.Multipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                String nested = firstOfType(multipart.getBodyPart(i).getContent(), wantHtml);
                if (nested != null) return nested;
            }
            return null;
        }
        if (content instanceof String text) {
            // The HTML alternative starts with a doctype; the plain one does not.
            return text.stripLeading().startsWith("<") == wantHtml ? text : null;
        }
        return null;
    }

    /**
     * The whole message as text, with quoted-printable soft line breaks removed.
     *
     * <p>JavaMail wraps the encoded body around column 76 with {@code =\r\n}, at an offset that
     * depends on the surrounding inline styles, so a break can land inside the exact string an
     * assertion looks for. For a NEGATIVE assertion that is a false PASS on a message that does
     * contain the thing, which is the worst place to have one.
     */
    private static String payloadOf(MimeMessage sent) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        sent.writeTo(out);
        return out.toString("UTF-8").replace("=\r\n", "").replace("=\n", "");
    }

    @Test
    @DisplayName("the deactivation mail is written in the account's language")
    void deactivationFollowsTheAccountLanguage() throws Exception {
        when(locales.forEmail("leaver@example.com")).thenReturn("fr");

        mailer.sendDeactivationEmail("leaver@example.com", "Alice", null);

        MimeMessage sent = captureSent();
        assertThat(sent.getSubject())
                .isEqualTo(AccountDeactivationMailer.CATALOG.text("fr", "deactivated.subject"));
        assertThat(payloadOf(sent)).containsAnyOf("lang=\"fr\"", "lang=3D\"fr\"");
    }

    @Test
    @DisplayName("the grace period is stated from ONE source, so three mails cannot disagree")
    void gracePeriodComesFromTheConstant() throws Exception {
        mailer.sendDeactivationEmail("leaver@example.com", "Alice", null);

        // The three texts used to spell "30 days" by hand. A policy change would have left at
        // least one of them promising the old number to somebody deciding whether to come back.
        // The rendered SENTENCE, for each mail that states the period, rather than the digits "30"
        // anywhere in the message. The envelope carries a 13-digit Message-ID and a formatted
        // Date, so a two-digit substring is at real risk of matching a header: the assertion
        // passed for a catalog that had hardcoded the number, which is the bug it guards.
        // Through `unemphasize`, because the catalog emphasizes the consequential clause of this
        // sentence: the raw string appears in neither half of the mail.
        String deactivated = plainTextPartOf(captureSent());
        assertThat(deactivated).contains(asTextPartReadsIt(
                AccountDeactivationMailer.CATALOG.text("en", "deactivated.retention",
                        java.util.Map.of("days", String.valueOf(UserService.ACCOUNT_GRACE_PERIOD_DAYS)))));

        // And the SECOND mail that states it, which "three mails cannot disagree" is about and
        // which this test never sent.
        org.mockito.Mockito.reset(mailSender);
        when(mailSender.createMimeMessage())
                .thenAnswer(invocation -> new MimeMessage((jakarta.mail.Session) null));
        mailer.sendPurgeConfirmationEmail("owner@example.com", "Ada", "en");
        assertThat(payloadOf(captureSent())).contains(AccountDeactivationMailer.CATALOG.text("en", "purged.body",
                java.util.Map.of("days", String.valueOf(UserService.ACCOUNT_GRACE_PERIOD_DAYS))));

        // And the catalog still SUBSTITUTES the number rather than spelling it: without this, a
        // translation that hardcoded "30" would satisfy both assertions above.
        assertThat(AccountDeactivationMailer.CATALOG.raw("en", "deactivated.retention")).contains("{days}");
        assertThat(AccountDeactivationMailer.CATALOG.raw("en", "purged.body")).contains("{days}");
    }

    @Test
    @DisplayName("a name is greeted by name, and its absence does not print a placeholder")
    void greetingHandlesAMissingName() throws Exception {
        // BOTH halves, because the name claims both and the body used to send only the nameless
        // one: a greeting that ignored the name entirely satisfied it.
        mailer.sendRestorationEmail("named@example.com", "Alice", null);
        assertThat(payloadOf(captureSent())).contains(
                AccountDeactivationMailer.CATALOG.text("en", "common.greeting", java.util.Map.of("name", "Alice")));

        org.mockito.Mockito.reset(mailSender);
        when(mailSender.createMimeMessage())
                .thenAnswer(invocation -> new MimeMessage((jakarta.mail.Session) null));
        mailer.sendRestorationEmail("back@example.com", null, null);

        String payload = payloadOf(captureSent());
        assertThat(payload).contains(AccountDeactivationMailer.CATALOG.text("en", "common.greetingNoName"));
        assertThat(payload).doesNotContain("{name}");
    }

    @Test
    @DisplayName("the purge mail uses the language the CALLER carried, not a lookup")
    void purgeUsesTheCarriedLanguage() throws Exception {
        // The account row is gone by now, so the resolver can only answer English. Anything this
        // mail says about the reader's language has to have been captured before the delete.
        when(locales.forEmail(anyString())).thenReturn("en");

        mailer.sendPurgeConfirmationEmail("gone@example.com", "Alice", "de");

        assertThat(captureSent().getSubject())
                .isEqualTo(AccountDeactivationMailer.CATALOG.text("de", "purged.subject"));
    }

    @Test
    @DisplayName("a purge mail with no carried language falls back to the lookup")
    void purgeFallsBackToTheLookup() throws Exception {
        when(locales.forEmail("gone@example.com")).thenReturn("es");

        mailer.sendPurgeConfirmationEmail("gone@example.com", "Alice", null);

        assertThat(captureSent().getSubject())
                .isEqualTo(AccountDeactivationMailer.CATALOG.text("es", "purged.subject"));
    }

    @Test
    @DisplayName("an unsupported carried language reads as English rather than as itself")
    void purgeNormalisesTheCarriedLanguage() throws Exception {
        mailer.sendPurgeConfirmationEmail("gone@example.com", "Alice", "it");

        assertThat(captureSent().getSubject())
                .isEqualTo(AccountDeactivationMailer.CATALOG.text("en", "purged.subject"));
    }

    @Test
    @DisplayName("an SMTP failure is swallowed: the account is already deactivated either way")
    void smtpFailureIsSwallowed() {
        doThrow(new MailSendException("SMTP unreachable")).when(mailSender).send(any(MimeMessage.class));

        assertThatCode(() -> mailer.sendDeactivationEmail("leaver@example.com", "Alice", null))
                .doesNotThrowAnyException();
        assertThatCode(() -> mailer.sendRestorationEmail("back@example.com", "Alice", null))
                .doesNotThrowAnyException();
        assertThatCode(() -> mailer.sendPurgeConfirmationEmail("gone@example.com", "Alice", "fr"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("every one of the three carries a text part as well as HTML")
    void everyMailHasBothParts() throws Exception {
        // All THREE, which the name claims and the body used to skip: a plain-text client
        // receiving only an HTML part gets an empty message, and the three go out through the
        // same private send() - so covering one proved the shared path and nothing about the
        // two call sites that could pass it different arguments.
        java.util.List<Runnable> sends = java.util.List.of(
                () -> mailer.sendDeactivationEmail("leaver@example.com", "Alice", null),
                () -> mailer.sendRestorationEmail("back@example.com", "Alice", null),
                () -> mailer.sendPurgeConfirmationEmail("gone@example.com", "Alice", "en"));

        for (Runnable send : sends) {
            org.mockito.Mockito.reset(mailSender);
            when(mailSender.createMimeMessage())
                    .thenAnswer(invocation -> new MimeMessage((jakarta.mail.Session) null));

            send.run();

            // Both Content-Types AND some of each part's own text. The header pair alone is satisfied
            // by a multipart whose text half is empty, which is the failure this is named for: a
            // plain-text client shown nothing. The sibling PasswordResetMailerTest has per-part
            // helpers written for exactly that reason.
            String payload = payloadOf(captureSent());
            assertThat(payload).contains("text/plain");
            assertThat(payload).contains("text/html");
            // The greeting appears in both halves, so finding it twice proves neither half is empty.
            String greeting = AccountDeactivationMailer.CATALOG.text("en", "common.greeting",
                    java.util.Map.of("name", "Alice"));
            assertThat(payload.split(java.util.regex.Pattern.quote(greeting), -1).length - 1)
                    .as("the greeting must appear in the text part AND the html part")
                    .isGreaterThanOrEqualTo(2);
        }
    }

    /**
     * Every catalog sentence a mail is built from, per mail.
     *
     * <p>This is the guard that was missing, and its absence is why six separate content regressions
     * reached three review rounds: extracting the shared shell replaced three distinct footers with
     * one generic sentence, dropped the purge mail's itemised inventory of what had been deleted,
     * dropped two sign-offs, and nothing failed. The suite asserted the support address, the button
     * destinations and the grace period, and never that a mail still says what it said.
     *
     * <p>Keys rather than a rendered snapshot on purpose: a snapshot of 60 lines of table markup
     * fails on every style change and teaches people to regenerate it without reading. A missing
     * sentence is exactly what this catches, and it is the thing that keeps going missing.
     *
     * <p>Asserted against the TEXT part, and through {@code unemphasize}, for two reasons a review
     * found the hard way. The whole-payload version could be satisfied by either half alone, which is
     * the trap this class documents above {@code payloadOf}; and a sentence the catalog emphasizes
     * never appears verbatim anywhere, so adding emphasis to two strings broke a guard that was
     * supposed to be about whether the sentence is present at all.
     *
     * <p>The grace period comes from the constant, not from a literal "30": the sibling test
     * {@code gracePeriodComesFromTheConstant} exists to prove that number has one source, and
     * hardcoding it here would have made a policy change fail in the class that guards it.
     */
    private void assertMailIsBuiltFrom(String... keys) throws Exception {
        String plain = plainTextPartOf(captureSent());
        for (String key : keys) {
            String sentence = AccountDeactivationMailer.CATALOG.text("en", key, java.util.Map.of(
                    "days", String.valueOf(UserService.ACCOUNT_GRACE_PERIOD_DAYS),
                    "supportEmail", SUPPORT,
                    "name", "Alice"));
            assertThat(plain)
                    .as("the mail must still carry " + key)
                    .contains(asTextPartReadsIt(sentence));
        }
    }

    /**
     * A catalog sentence as the TEXT part of the mail renders it: emphasis markers gone.
     *
     * <p>Duplicated here rather than calling `BrandedMail.unemphasize`, which is package-private and
     * should stay that way. Widening a production method's visibility so a test in a neighbouring
     * package can reach it makes the API answer to the test instead of the other way round, and this
     * is two lines.
     */
    private static String asTextPartReadsIt(String sentence) {
        return sentence.replaceAll("\\*([^*]+)\\*", "$1");
    }

    @Test
    @DisplayName("the deactivation mail still says everything it used to say")
    void deactivationMailKeepsItsSentences() throws Exception {
        mailer.sendDeactivationEmail("leaver@example.com", "Alice", "en");

        assertMailIsBuiltFrom("common.greeting", "deactivated.body", "deactivated.retention",
                "deactivated.undo", "deactivated.action", "common.signature", "deactivated.footer");
    }

    @Test
    @DisplayName("the restoration mail still says everything it used to say")
    void restorationMailKeepsItsSentences() throws Exception {
        mailer.sendRestorationEmail("back@example.com", "Alice", "en");

        assertMailIsBuiltFrom("common.greeting", "restored.body", "restored.notyou",
                "common.signature", "restored.footer");
    }

    @Test
    @DisplayName("the purge mail names what was deleted, and signs off for an account that is gone")
    void purgeMailKeepsItsSentences() throws Exception {
        // `purged.detail` is the inventory. The sentence that replaced the original six-item list
        // named four of its categories and was a copy of the DEACTIVATION mail's list, so interfaces,
        // data sources, run history and stored objects went unnamed in the one mail whose entire job
        // is to say what was destroyed.
        mailer.sendPurgeConfirmationEmail("gone@example.com", "Alice", "en");

        assertMailIsBuiltFrom("common.greeting", "purged.body", "purged.detail", "purged.welcome",
                "purged.action", "common.signature", "purged.footer");
    }

    @Test
    @DisplayName("each lifecycle mail has its OWN footer, and the purge one does not claim an account")
    void eachMailHasItsOwnFooter() throws Exception {
        // All three shared `common.footer` for a while: "you are receiving this email at the address
        // on your LiveContext account", sent moments after the purge deleted every row of that
        // account. Three sentences collapsed into one generic and, on the last of the three, false.
        mailer.sendPurgeConfirmationEmail("gone@example.com", "Alice", "en");
        String purged = payloadOf(captureSent());

        assertThat(purged).contains(AccountDeactivationMailer.CATALOG.text("en", "purged.footer"));
        assertThat(purged)
                .as("there is no account left to have an address on")
                .doesNotContain(AccountDeactivationMailer.CATALOG.text("en", "common.footer"));
    }

    @Test
    @DisplayName("tells the reader where to write, at the configured address, as a usable link")
    void carriesTheSupportAddressAsALink() throws Exception {
        // Both mails that mention support end a sentence with the address. Nothing asserted it was
        // there at all, so dropping the interpolation from a translation would have gone out silently
        // - on the two mails whose whole point is "write to us if this was not you".
        //
        // And it must be a LINK. Before the shared shell these were hand-written anchors; moving the
        // sentences into the catalog turned them into inert text, because every paragraph is escaped.
        // Most clients autolink an address, some do not, and the reader of a deactivation notice
        // they did not ask for is the last person who should have to retype one.
        mailer.sendDeactivationEmail("leaver@example.com", "Alice", "en");
        String deactivated = payloadOf(captureSent());
        assertThat(deactivated).contains(SUPPORT);
        assertThat(deactivated)
                .as("the address is clickable, not just printed")
                .contains("mailto:" + SUPPORT);

        org.mockito.Mockito.reset(mailSender);
        when(mailSender.createMimeMessage()).thenAnswer(invocation ->
                new MimeMessage(Session.getDefaultInstance(new Properties())));

        mailer.sendRestorationEmail("back@example.com", "Alice", "en");
        MimeMessage restoredMessage = captureSent();
        assertThat(payloadOf(restoredMessage)).contains("mailto:" + SUPPORT);

        // The plain-text half keeps the BARE address, asserted on that part alone.
        //
        // This used to be doesNotContain("&lt;a href") over the whole payload. Nothing in the shell can
        // emit that string - an anchor is written after escaping and never re-escaped - so the
        // assertion could not fail and said nothing about the text part.
        String plainPart = plainTextPartOf(restoredMessage);
        assertThat(plainPart).contains(SUPPORT);
        assertThat(plainPart).doesNotContain("mailto:", "<a ");
    }

    @Test
    @DisplayName("each button leads where its own label promises: sign-in to undo, registration after a purge")
    void eachButtonLeadsWhereItsLabelPromises() throws Exception {
        // The two mails had the SAME destination, `/app`. For the deactivation mail that is right:
        // signing in is what undoes it, and the mail says so. For the purge mail it was wrong in the
        // way that is hardest to notice, because the link resolves and the page loads: the button
        // says "Create a new account" and `/app` redirects to sign-in, into an account that was
        // deleted an instant earlier. Nothing here asserted either destination, so one label and one
        // URL contradicted each other in six languages.
        mailer.sendDeactivationEmail("leaver@example.com", "Alice", "en");
        assertThat(payloadOf(captureSent()))
                .as("undoing a deactivation means signing in")
                .contains("https://app.example.com/app");

        org.mockito.Mockito.reset(mailSender);
        when(mailSender.createMimeMessage()).thenAnswer(invocation ->
                new MimeMessage(Session.getDefaultInstance(new Properties())));

        mailer.sendPurgeConfirmationEmail("gone@example.com", "Alice", "en");
        String purged = payloadOf(captureSent());
        assertThat(purged)
                .as("the account is gone, so the only thing left to do is register a new one")
                .contains("https://app.example.com/register");
        assertThat(purged)
                .as("and it must NOT offer a sign-in that cannot succeed")
                .doesNotContain("https://app.example.com/app");
    }

    @Test
    @DisplayName("a button link carries the account's language, so the page matches the mail")
    void buttonLinksCarryTheAccountsLanguage() throws Exception {
        // These routes live under `app/[locale]/`. An unprefixed link is redirected on the BROWSER's
        // language, so a French account read on an English browser got a French mail whose only
        // call to action opened in English. Asserted on the sent message rather than on the helper,
        // because the helper is private and the resolved locale has to reach it.
        mailer.sendDeactivationEmail("leaver@example.com", "Alice", "fr");
        assertThat(payloadOf(captureSent())).contains("https://app.example.com/fr/app");

        org.mockito.Mockito.reset(mailSender);
        when(mailSender.createMimeMessage()).thenAnswer(invocation ->
                new MimeMessage(Session.getDefaultInstance(new Properties())));

        mailer.sendPurgeConfirmationEmail("gone@example.com", "Alice", "de");
        assertThat(payloadOf(captureSent())).contains("https://app.example.com/de/register");
    }
}
