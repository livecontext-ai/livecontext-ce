package com.apimarketplace.auth.service;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.*;

@DisplayName("OrganizationInvitationMailer (PR-3 MVP)")
class OrganizationInvitationMailerTest {

    private JavaMailSender mailSender;
    private OrganizationInvitationMailer mailer;

    @BeforeEach
    void setUp() {
        mailSender = mock(JavaMailSender.class);
        // Return a real MimeMessage so MimeMessageHelper writes succeed.
        when(mailSender.createMimeMessage()).thenAnswer(inv ->
                new MimeMessage(Session.getDefaultInstance(new Properties())));
        mailer = new OrganizationInvitationMailer(
                mailSender, "noreply@example.com", "LiveContext", "https://app.example.com");
    }

    @Test
    @DisplayName("sendInvitationEmail wires To/Subject/Body and includes the accept URL")
    void sendsMailWithExpectedFields() throws Exception {
        mailer.sendInvitationEmail("invitee@example.com", "Acme Corp", "Alice Inviter",
                "tok-abc-123", "MEMBER");

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
    @DisplayName("HTML-escapes orgName / inviter / role to defuse injection")
    void htmlEscapesUserControlledFields() throws Exception {
        // A hostile OWNER tries to embed a script tag in their org name.
        mailer.sendInvitationEmail("victim@example.com",
                "<script>alert('xss')</script>", "<b>fake</b>", "MEMBER",
                "tok-x");

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        captor.getValue().writeTo(baos);
        String payload = baos.toString();

        // The raw tag MUST NOT appear in the HTML - `<script>` would render as
        // an active script in some mail clients with HTML enabled. The escaped
        // form `&lt;script&gt;` is what we want.
        assertThat(payload).doesNotContain("<script>alert");
        assertThat(payload).contains("&lt;script&gt;");
        assertThat(payload).doesNotContain("<b>fake</b>");
        assertThat(payload).contains("&lt;b&gt;fake&lt;/b&gt;");
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
                "fails@example.com", "Org", "Inviter", "tok", "MEMBER"))
                .doesNotThrowAnyException();
    }
}
