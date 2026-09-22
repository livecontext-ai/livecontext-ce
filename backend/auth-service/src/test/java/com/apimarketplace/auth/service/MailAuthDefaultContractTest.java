package com.apimarketplace.auth.service;

import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Why {@code mail.smtp.auth} must not be turned on without credentials, which is
 * half of why application-ce.yml does not bind it at all (the other half is the
 * upgrade break, recorded in that file and in MonolithCeConfigContractTest).
 *
 * <p>The intuition it is easy to ship on, and which WAS shipped here for one
 * round, is that "authentication is skipped when no username is set", so leaving
 * the flag on costs nothing. That is false, and it fails in the worst way: not
 * by falling back to an unauthenticated send, but by refusing to open the socket
 * at all. JavaMail's {@code SMTPTransport.protocolConnect} bails when
 * {@code mail.smtp.auth} is on and either the user or the password is null, and
 * Spring normalises a blank username to null. So a CE default of AUTH-on with a
 * local relay and no credentials could not deliver a single message: the only
 * symptom would have been one ERROR line per reset while the requester was told
 * a link was on its way.
 *
 * <p>These are the measurements, kept so the default cannot quietly go back.
 * They target port 1, where nothing is listening, so the exception TYPE reports
 * how far the send got: {@link MailAuthenticationException} means it never tried
 * the network, {@link MailSendException} over an {@link java.io.IOException}
 * means it did.
 */
@DisplayName("SMTP auth default (why CE does not bind mail.smtp.auth)")
class MailAuthDefaultContractTest {

    /** Port 1: privileged, and nothing in this build listens there. */
    private static final int UNREACHABLE_PORT = 1;

    private static JavaMailSenderImpl sender(boolean authEnabled, String username, String password) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost("localhost");
        sender.setPort(UNREACHABLE_PORT);
        sender.setUsername(username);
        sender.setPassword(password);
        Properties properties = new Properties();
        properties.put("mail.smtp.auth", String.valueOf(authEnabled));
        properties.put("mail.smtp.connectiontimeout", "1000");
        properties.put("mail.smtp.timeout", "1000");
        properties.put("mail.smtp.writetimeout", "1000");
        sender.setJavaMailProperties(properties);
        return sender;
    }

    private static Throwable attemptSend(boolean authEnabled, String username, String password) {
        JavaMailSenderImpl sender = sender(authEnabled, username, password);
        MimeMessage message = sender.createMimeMessage();
        return catchThrowable(() -> {
            message.setFrom("noreply@example.com");
            message.setRecipients(jakarta.mail.Message.RecipientType.TO, "owner@example.com");
            message.setSubject("probe");
            message.setText("probe");
            sender.send(message);
        });
    }

    private static Throwable rootCause(Throwable thrown) {
        Throwable cursor = thrown;
        while (cursor.getCause() != null) {
            cursor = cursor.getCause();
        }
        return cursor;
    }

    @Test
    @DisplayName("auth=true with NO credentials never reaches the network, so it can never deliver")
    void authWithoutCredentialsNeverConnects() {
        Throwable thrown = attemptSend(true, "", null);

        // Not a connection failure: it gave up on the missing credentials first,
        // which is why this combination is broken against ANY relay, reachable or
        // not, and why it must not be the shipped default.
        assertThat(thrown).isInstanceOf(MailAuthenticationException.class);
        assertThat(rootCause(thrown)).isInstanceOf(AuthenticationFailedException.class);
    }

    @Test
    @DisplayName("auth=true with a username but NO password fails the same way")
    void authWithHalfCredentialsNeverConnects() {
        Throwable thrown = attemptSend(true, "a-user", null);

        // Both halves are required, so a half-filled .env is the same outage.
        assertThat(thrown).isInstanceOf(MailAuthenticationException.class);
    }

    @Test
    @DisplayName("auth=false with no credentials DOES reach the network, which is what a local relay needs")
    void withoutAuthItReachesTheSocket() {
        Throwable thrown = attemptSend(false, "", null);

        // It got as far as the socket, which is the whole difference: against a
        // relay that is actually listening, this send succeeds.
        assertThat(thrown).isInstanceOf(MailSendException.class);
        assertThat(rootCause(thrown)).isInstanceOf(java.io.IOException.class);
    }

    @Test
    @DisplayName("auth=true WITH both credentials reaches the network too, so the flag is about "
            + "credentials being present, not about security posture")
    void authWithCredentialsReachesTheSocket() {
        Throwable thrown = attemptSend(true, "a-user", "a-password");

        assertThat(thrown).isInstanceOf(MailSendException.class);
        assertThat(rootCause(thrown)).isInstanceOf(java.io.IOException.class);
    }
}
