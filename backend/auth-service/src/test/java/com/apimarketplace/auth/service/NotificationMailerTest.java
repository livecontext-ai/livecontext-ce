package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.UserRepository;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("NotificationMailer - one product alert to the right, verified address")
class NotificationMailerTest {

    private JavaMailSender mailSender;
    private UserRepository users;
    private NotificationMailer mailer;

    @BeforeEach
    void setUp() {
        mailSender = mock(JavaMailSender.class);
        users = mock(UserRepository.class);
        when(mailSender.createMimeMessage()).thenAnswer(inv -> new MimeMessage(Session.getInstance(new Properties())));
        mailer = new NotificationMailer(mailSender, users, "noreply@livecontext.ai", "LiveContext",
                "https://livecontext.ai/");
    }

    private User user(boolean verified, LocalDateTime deactivatedAt) {
        User u = new User();
        u.setId(42L);
        u.setEmail("ada@example.com");
        u.setEmailVerified(verified);
        u.setDeactivatedAt(deactivatedAt);
        return u;
    }

    @Test
    @DisplayName("A verified user gets the mail, with the action link, the manage link and List-Unsubscribe")
    void sends() throws Exception {
        when(users.findById(42L)).thenReturn(Optional.of(user(true, null)));

        NotificationMailer.Result result = mailer.send("42", "Workflow failed: Nightly <import>",
                List.of("It failed at 03:12 UTC."), "/app/workflow/abc", "Open the run");

        assertThat(result.status()).isEqualTo(NotificationMailer.Status.SENT);
        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        MimeMessage sent = captor.getValue();
        assertThat(sent.getAllRecipients()[0].toString()).isEqualTo("ada@example.com");
        assertThat(sent.getHeader("List-Unsubscribe")[0])
                .isEqualTo("<https://livecontext.ai/app/settings/overview?tab=notifications>");
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        sent.writeTo(raw);
        String body = raw.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(body).contains("https://livecontext.ai/app/workflow/abc");
        assertThat(body).contains("Nightly &lt;import&gt;").doesNotContain("Nightly <import></h1>");
    }

    @Test
    @DisplayName("A subject containing a placeholder stays literal, it is never expanded")
    void placeholderInSubjectStaysLiteral() throws Exception {
        when(users.findById(42L)).thenReturn(Optional.of(user(true, null)));

        mailer.send("42", "Broken {{BODY}} name", List.of("SECRET-LINE"), "/app", "Open");

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        captor.getValue().writeTo(raw);
        String html = raw.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(html).contains("Broken {{BODY}} name");
    }

    @Test
    @DisplayName("An unverified address is never mailed (a bounce costs every customer's deliverability)")
    void unverifiedRefused() {
        when(users.findById(42L)).thenReturn(Optional.of(user(false, null)));

        NotificationMailer.Result result = mailer.send("42", "s", List.of(), "/app", "Open");

        assertThat(result.status()).isEqualTo(NotificationMailer.Status.NO_ADDRESS);
        assertThat(result.detail()).isEqualTo("email not verified");
        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    @DisplayName("A deactivated account is not mailed")
    void deactivatedRefused() {
        when(users.findById(42L)).thenReturn(Optional.of(user(true, LocalDateTime.now())));

        assertThat(mailer.send("42", "s", List.of(), "/app", "Open").status())
                .isEqualTo(NotificationMailer.Status.NO_ADDRESS);
    }

    @Test
    @DisplayName("A provider id resolves too, and an unknown id is NO_ADDRESS")
    void providerIdAndUnknown() {
        when(users.findByProviderId("kc-sub")).thenReturn(Optional.of(user(true, null)));

        assertThat(mailer.send("kc-sub", "s", List.of(), "/app", "Open").status())
                .isEqualTo(NotificationMailer.Status.SENT);
        assertThat(mailer.send("999", "s", List.of(), "/app", "Open").status())
                .isEqualTo(NotificationMailer.Status.NO_ADDRESS);
    }

    @Test
    @DisplayName("An SMTP failure is FAILED, not an exception for the caller")
    void smtpFailure() {
        when(users.findById(42L)).thenReturn(Optional.of(user(true, null)));
        doThrow(new MailSendException("relay down")).when(mailSender).send(any(MimeMessage.class));

        assertThat(mailer.send("42", "s", List.of("l"), "/app", "Open").status())
                .isEqualTo(NotificationMailer.Status.FAILED);
    }

    @Test
    @DisplayName("The internal endpoint answers 200 with the outcome in the body, even for an empty request")
    void internalEndpoint() {
        com.apimarketplace.auth.web.InternalNotificationMailController controller =
                new com.apimarketplace.auth.web.InternalNotificationMailController(mailer);
        when(users.findById(42L)).thenReturn(Optional.of(user(true, null)));

        var empty = controller.send(null);
        var sent = controller.send(new com.apimarketplace.auth.web.InternalNotificationMailController.MailRequest(
                "42", "Subject", List.of("line"), "/app", "Open"));

        assertThat(empty.getStatusCode().value()).isEqualTo(200);
        assertThat(empty.getBody()).containsEntry("status", "FAILED");
        assertThat(sent.getBody()).containsEntry("status", "SENT");
    }

    @Test
    @DisplayName("Only an in-app path becomes a link: never another host")
    void actionPathMustBeInApp() {
        assertThat(NotificationMailer.isInAppPath("/app/settings/billing")).isTrue();
        assertThat(NotificationMailer.isInAppPath("//evil.example")).isFalse();
        assertThat(NotificationMailer.isInAppPath("https://evil.example")).isFalse();
        assertThat(NotificationMailer.isInAppPath("/\\evil.example")).isFalse();
        assertThat(NotificationMailer.isInAppPath("/app x")).isFalse();
        assertThat(NotificationMailer.isInAppPath(null)).isFalse();
    }
    /** Every text part of the message, decoded (the raw MIME form may quoted-printable non-ASCII text). */
    private static String decodedParts(MimeMessage message) throws Exception {
        StringBuilder out = new StringBuilder();
        collect(message.getContent(), out);
        return out.toString();
    }

    private static void collect(Object content, StringBuilder out) throws Exception {
        if (content instanceof MimeMultipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) collect(multipart.getBodyPart(i).getContent(), out);
        } else if (content instanceof String text) {
            out.append(text).append('\n');
        }
    }

    private MimeMessage sendAs(String locale, String actionLabel) throws Exception {
        User u = user(true, null);
        u.setLocale(locale);
        when(users.findById(42L)).thenReturn(Optional.of(u));
        mailer.send("42", "Sujet", List.of("Ligne"), "/fr/app/workflow/abc", actionLabel);
        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        MimeMessage sent = captor.getValue();
        sent.saveChanges();
        return sent;
    }

    @Test
    @DisplayName("A French reader gets the French footer, the /fr manage link and a French List-Unsubscribe landing")
    void frenchWrapper() throws Exception {
        MimeMessage sent = sendAs("fr", "Ouvrir l'exécution");

        assertThat(sent.getHeader("List-Unsubscribe")[0])
                .isEqualTo("<https://livecontext.ai/fr/app/settings/overview?tab=notifications>");
        String parts = decodedParts(sent);
        assertThat(parts)
                .contains("Vous recevez ce message en raison de vos paramètres de notification.")
                .contains("Gérer les notifications: https://livecontext.ai/fr/app/settings/overview?tab=notifications")
                .contains(">Gérer les notifications</a>")
                .contains("<html lang=\"fr\">")
                .contains("https://livecontext.ai/fr/app/workflow/abc")
                .doesNotContain("You receive this")
                .doesNotContain("Manage notifications");
    }

    @Test
    @DisplayName("No action label from the caller: the button reads the default label in the reader's language")
    void defaultLabelLocalized() throws Exception {
        String parts = decodedParts(sendAs("de", null));

        assertThat(parts).contains("Öffnen: https://livecontext.ai/fr/app/workflow/abc")
                .contains("Sie erhalten diese Nachricht")
                .contains("https://livecontext.ai/de/app/settings/overview?tab=notifications");
    }

    @Test
    @DisplayName("No stored locale, or one the app does not speak: English wrapper, no locale prefix")
    void unknownLocaleIsEnglish() throws Exception {
        MimeMessage sent = sendAs("it", "Open");

        assertThat(sent.getHeader("List-Unsubscribe")[0])
                .isEqualTo("<https://livecontext.ai/app/settings/overview?tab=notifications>");
        assertThat(decodedParts(sent)).contains("You receive this because of your notification settings.")
                .contains("<html lang=\"en\">");
    }
}
