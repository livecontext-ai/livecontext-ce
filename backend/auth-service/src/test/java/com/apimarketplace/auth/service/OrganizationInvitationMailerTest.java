package com.apimarketplace.auth.service;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import com.apimarketplace.auth.service.mail.MailLocaleResolver;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.*;

@DisplayName("OrganizationInvitationMailer (PR-3 MVP)")
class OrganizationInvitationMailerTest {

    private JavaMailSender mailSender;
    private MailLocaleResolver locales;
    private OrganizationInvitationMailer mailer;

    @BeforeEach
    void setUp() {
        mailSender = mock(JavaMailSender.class);
        // Return a real MimeMessage so MimeMessageHelper writes succeed.
        when(mailSender.createMimeMessage()).thenAnswer(inv ->
                new MimeMessage(Session.getDefaultInstance(new Properties())));
        locales = mock(MailLocaleResolver.class);
        when(locales.forEmail(anyString())).thenReturn("en");
        mailer = new OrganizationInvitationMailer(
                mailSender, locales, "noreply@example.com", "LiveContext", "https://app.example.com");
    }

    /** The whole MIME payload as a string, which is what the assertions below read. */
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
            // The URL is the one the mailer really builds: English is unprefixed, so the accept path
            // sits directly under the frontend origin. A placeholder here would make the fallback
            // sentence unmatchable and the guard would look like a failure of the mail.
            String sentence = OrganizationInvitationMailer.CATALOG.text("en", key, java.util.Map.of(
                    "org", "Acme Corp",
                    "inviter", "Alice",
                    "role", "MEMBER",
                    "url", "https://app.example.com/invitations/accept?token=tok"));
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
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        sent.writeTo(baos);
        // UTF-8 explicitly: the default charset differs between CI boxes, which is how one
        // machine disagrees with another about a test that reads accented prose.
        return baos.toString("UTF-8").replace("=\r\n", "").replace("=\n", "");
    }

    private MimeMessage captureSent() {
        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("sendInvitationEmail wires To/Subject/Body and includes the accept URL")
    void sendsMailWithExpectedFields() throws Exception {
        mailer.sendInvitationEmail("invitee@example.com", "Acme Corp", "Alice Inviter",
                "tok-abc-123", "MEMBER", null);

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        MimeMessage sent = captor.getValue();

        assertThat(sent.getSubject()).contains("Acme Corp");
        assertThat(sent.getAllRecipients()).hasSize(1);
        assertThat(sent.getAllRecipients()[0].toString()).isEqualTo("invitee@example.com");

        // MimeMessageHelper builds a multipart/alternative ; the text+html parts
        // both contain the accept URL. Walking the multipart is verbose ; pulling
        // the raw payload via writeTo() is enough to pin the URL is present.
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        sent.writeTo(baos);
        String payload = baos.toString();
        // MIME multipart payload may be quoted-printable encoded ; pin distinctive
        // substrings that survive the encoding rather than the whole URL.
        assertThat(payload).contains("tok-abc-123");
        assertThat(payload).contains("invitations/accept");
        assertThat(payload).contains("Acme Corp");
        assertThat(payload).contains("Alice Inviter");
        assertThat(payload).contains("MEMBER");
    }

    @Test
    @DisplayName("an organization named *Star* Labs cannot shift the bold text around it")
    void aStarInAValueCannotShiftTheEmphasis() throws Exception {
        // plainValue was tested only in isolation, in BrandedMailTest, which proves the helper works
        // and nothing about whether anybody calls it. Removing all four calls from this mailer left
        // every suite green and shipped the reported defect back into the one mail whose body
        // interpolates three values a stranger typed.
        //
        // invite.body is "*{inviter}* invited you to join the organization *{org}* ... as *{role}*",
        // so the markers belong to the sentence. An org that brings its own pair shifts every boundary
        // after it: the emphasis stops covering the org name and starts covering the prose.
        mailer.sendInvitationEmail("invitee@example.com", "*Star* Labs", "Alice", "tok", "MEMBER", "en");

        String payload = payloadOf(captureSent());
        assertThat(payload).contains("<strong>Star Labs</strong>");
        assertThat(payload).doesNotContain("*");
    }

    @Test
    @DisplayName("still says everything it used to say, sentence by sentence")
    void keepsItsSentences() throws Exception {
        mailer.sendInvitationEmail("invitee@example.com", "Acme Corp", "Alice", "tok", "MEMBER", "en");

        assertMailIsBuiltFrom(captureSent(), "invite.body", "invite.action", "invite.fallback",
                "common.signature", "invite.footer");
    }

    @Test
    @DisplayName("signs off, because an unsigned mail to somebody with no account reads as phishing")
    void signsOff() throws Exception {
        // This mail and the squat alert had lost the sign-off their templates ended with, while the
        // other four append it. They are the two where "is this genuine?" is a reader first question:
        // one routinely reaches people with no account, the other says somebody tried to take over an
        // install.
        mailer.sendInvitationEmail("invitee@example.com", "Acme Corp", "Alice", "tok", "MEMBER", "en");

        assertThat(payloadOf(captureSent()))
                .contains(OrganizationInvitationMailer.CATALOG.text("en", "common.signature"));
    }

    @Test
    @DisplayName("a newline in an organization name cannot split the Subject into another header")
    void subjectIsOneLine() throws Exception {
        // A subject is a mail HEADER. A value carrying CRLF is how a header gets split, so the
        // org name is flattened to one line before it ever reaches setSubject.
        mailer.sendInvitationEmail("victim@example.com", "Acme\r\nBcc: attacker@example.com",
                "Alice", "tok", "MEMBER", null);

        MimeMessage sent = captureSent();
        assertThat(sent.getSubject()).doesNotContain("\n", "\r");
        assertThat(sent.getSubject()).contains("Acme Bcc: attacker@example.com");
        // Flattened into the subject TEXT, never promoted to a recipient.
        assertThat(sent.getAllRecipients()).hasSize(1);
    }

    @Test
    @DisplayName("HTML-escapes orgName / inviter / role in the BODY to defuse injection")
    void htmlEscapesUserControlledFields() throws Exception {
        // A hostile OWNER tries to embed a script tag in their org name.
        // The last two were the wrong way round - "MEMBER" as the token and "tok-x" as the role -
        // and the test still passed, because it asserts neither. A positional call of six strings
        // hides that; naming them here is the cheapest guard there is.
        mailer.sendInvitationEmail("victim@example.com",
                "<script>alert('xss')</script>", "<b>fake</b>", "tok-x",
                "MEMBER", null);

        MimeMessage sent = captureSent();
        String payload = payloadOf(sent);

        // The raw tag MUST NOT appear in the HTML - `<script>` would render as an active script
        // in some mail clients with HTML enabled. The escaped form is what we want.
        assertThat(payload).contains("&lt;script&gt;");
        assertThat(payload).contains("&lt;b&gt;fake&lt;/b&gt;");

        // Scoped to the text/html PART, which is the only place escaping protects anything.
        // The whole payload legitimately carries the raw name twice more, and asserting over it
        // conflated three different things:
        //   - the Subject header: text, not HTML. Escaping it would show a user called
        //     "Ben & Jerry's" the literal "&amp;" in their inbox list.
        //   - the text/plain alternative: also text, where the literal name is the correct
        //     rendering and "&lt;script&gt;" would be the bug.
        int htmlPart = payload.indexOf("text/html");
        assertThat(htmlPart).as("the message must carry an HTML alternative").isNotNegative();
        String htmlOnly = payload.substring(htmlPart);
        assertThat(htmlOnly).doesNotContain("<script>alert");
        assertThat(htmlOnly).doesNotContain("<b>fake</b>");
    }

    @Test
    @DisplayName("the invitation is written in the language of the account that owns the address")
    void writtenInTheInviteesLanguage() throws Exception {
        when(locales.forEmail("invitee@example.com")).thenReturn("fr");

        mailer.sendInvitationEmail("invitee@example.com", "Acme Corp", "Alice", "tok", "MEMBER", null);

        MimeMessage sent = captureSent();
        // Subject and body in French, and the document declares the language it is in - a mail
        // that says lang="en" makes a client offer to translate a French message.
        assertThat(sent.getSubject()).isEqualTo(
                OrganizationInvitationMailer.CATALOG.text("fr", "invite.subject",
                        java.util.Map.of("org", "Acme Corp")));
        String payload = payloadOf(sent);
        // Quoted-printable encodes '=' as '=3D', so accept either spelling of the attribute.
        assertThat(payload).containsAnyOf("lang=\"fr\"", "lang=3D\"fr\"");
        // And the body really is the French sentence, not just a French subject over English.
        assertThat(payload).contains("rejoindre");
        // And the LINK opens the French page. An unprefixed /invitations/accept is redirected on the
        // BROWSER language, so a French mail read on an English browser landed on an English accept
        // page: the one screen where somebody decides whether to join a workspace. The assertion
        // above only proved the prose, and the prose and the link are resolved separately.
        assertThat(payload).contains("https://app.example.com/fr/invitations/accept");
    }

    @Test
    @DisplayName("a language the CALLER carried beats the lookup, which is the point of the parameter")
    void carriedLanguageBeatsTheLookup() throws Exception {
        // Every call in this file passed null for `knownLocale`, so the branch the parameter exists
        // for was exercised nowhere - on the one mailer the CI comment singles out as "the last of
        // five" to get it. `OrganizationMemberServiceTest` proves the service hands "de" over;
        // nothing proved the mailer prefers it. Deleting the argument from the resolve call left this
        // whole class green.
        when(locales.forEmail("invitee@example.com")).thenReturn("fr");

        mailer.sendInvitationEmail("invitee@example.com", "Acme Corp", "Alice", "tok", "MEMBER", "de");

        MimeMessage sent = captureSent();
        assertThat(sent.getSubject()).isEqualTo(
                OrganizationInvitationMailer.CATALOG.text("de", "invite.subject",
                        java.util.Map.of("org", "Acme Corp")));
        // And no lookup at all: the caller had the row, which is the whole saving.
        verify(locales, org.mockito.Mockito.never()).forEmail(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("carries a preheader, which is the line a client previews next to the subject")
    void carriesAPreheader() throws Exception {
        // The template this replaced emitted one; the shell extraction passed null and no
        // `invite.preheader` key existed, so every invitation preview line became the first words of
        // the body. Nothing could see it: the only preheader assertion in the suite was
        // `BrandedMailTest.optionalBlocksAreOmitted`, which checks that the markup is ABSENT when
        // none is passed - the regressed state exactly.
        mailer.sendInvitationEmail("invitee@example.com", "Acme Corp", "Alice", "tok", "MEMBER", "en");

        String payload = payloadOf(captureSent());
        assertThat(payload).contains("mso-hide:all");
        assertThat(payload).contains(
                OrganizationInvitationMailer.CATALOG.text("en", "invite.preheader",
                        java.util.Map.of("org", "Acme Corp")));
    }

    @Test
    @DisplayName("an address no account owns reads English, which is the resolver's answer")
    void unknownAddressReadsEnglish() throws Exception {
        when(locales.forEmail("stranger@example.com")).thenReturn("en");

        mailer.sendInvitationEmail("stranger@example.com", "Acme Corp", "Alice", "tok", "MEMBER", null);

        assertThat(captureSent().getSubject()).isEqualTo(
                OrganizationInvitationMailer.CATALOG.text("en", "invite.subject",
                        java.util.Map.of("org", "Acme Corp")));
    }

    @Test
    @DisplayName("SMTP failure is swallowed - caller never sees the exception (best-effort contract)")
    void smtpFailureIsSwallowed() {
        doThrow(new MailSendException("SMTP unreachable"))
                .when(mailSender).send(any(MimeMessage.class));

        // The contract is fire-and-forget: invitation persists, autoAccept
        // picks it up at signup. The caller (OrganizationMemberService) MUST
        // not propagate the SMTP failure.
        assertThatCode(() -> mailer.sendInvitationEmail(
                "fails@example.com", "Org", "Inviter", "tok", "MEMBER", null))
                .doesNotThrowAnyException();
    }
}
