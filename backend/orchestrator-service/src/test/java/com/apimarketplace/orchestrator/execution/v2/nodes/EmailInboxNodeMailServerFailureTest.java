package com.apimarketplace.orchestrator.execution.v2.nodes;

import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.FolderClosedException;
import jakarta.mail.MessagingException;
import jakarta.mail.MethodNotSupportedException;
import jakarta.mail.NoSuchProviderException;
import jakarta.mail.StoreClosedException;
import jakarta.mail.internet.AddressException;
import org.eclipse.angus.mail.iap.CommandFailedException;
import org.eclipse.angus.mail.iap.ConnectionException;
import org.eclipse.angus.mail.util.MailConnectException;
import org.eclipse.angus.mail.util.SocketConnectException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLHandshakeException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression (prod 2026-09-25): about 80 ERROR lines a week, with stack traces, came from one
 * customer's flaky IMAP host ("* BYE", "A5 NO internal server error", "Lost folder connection to
 * server", "Read timed out"). Those come from the conversation with the customer's server and are
 * logged at WARN. Every mail failure that can be the PLATFORM's (missing provider, our own API
 * misuse, a DNS / egress / TLS outage) must keep ERROR, so the classifier is pinned both ways.
 */
@DisplayName("EmailInboxNode.isMailServerFailure")
class EmailInboxNodeMailServerFailureTest {

    @Test
    @DisplayName("the four shapes seen in prod are mail server failures")
    void prodShapesAreMailServerFailures() {
        assertThat(EmailInboxNode.isMailServerFailure(
                new MessagingException("* BYE Jakarta Mail Exception", new SocketTimeoutException("Read timed out")))).isTrue();
        assertThat(EmailInboxNode.isMailServerFailure(
                new MessagingException("A5 NO internal server error", new CommandFailedException("A5 NO internal server error")))).isTrue();
        assertThat(EmailInboxNode.isMailServerFailure(new FolderClosedException(null, "Lost folder connection to server"))).isTrue();
        assertThat(EmailInboxNode.isMailServerFailure(
                new MessagingException("connection dropped", new ConnectionException()))).isTrue();
    }

    @Test
    @DisplayName("a closed store and a refused login are the customer's server too")
    void storeClosedAndLoginRefused() {
        assertThat(EmailInboxNode.isMailServerFailure(new StoreClosedException(null, "store closed"))).isTrue();
        assertThat(EmailInboxNode.isMailServerFailure(new AuthenticationFailedException("bad password"))).isTrue();
    }

    @Test
    @DisplayName("mail failures that can be the platform's keep ERROR")
    void platformMailFailuresAreNot() {
        assertThat(EmailInboxNode.isMailServerFailure(new NoSuchProviderException("imaps"))).isFalse();
        assertThat(EmailInboxNode.isMailServerFailure(new MethodNotSupportedException("no"))).isFalse();
        assertThat(EmailInboxNode.isMailServerFailure(new AddressException("bad address"))).isFalse();
        assertThat(EmailInboxNode.isMailServerFailure(
                new MessagingException("connect failed", new UnknownHostException("imap.example.com")))).isFalse();
        assertThat(EmailInboxNode.isMailServerFailure(
                new MessagingException("handshake", new SSLHandshakeException("PKIX path building failed")))).isFalse();
        assertThat(EmailInboxNode.isMailServerFailure(new MessagingException("generic"))).isFalse();
    }

    @Test
    @DisplayName("a CONNECT timeout keeps ERROR: a platform egress blackhole would hit every tenant")
    void connectTimeoutIsNot() {
        // The exact chain angus-mail builds for a connect timeout:
        // MailConnectException -> SocketConnectException -> SocketTimeoutException.
        SocketConnectException connect = new SocketConnectException(
                "connect", new SocketTimeoutException("Connect timed out"), "imap.example.com", 993, 10_000);
        assertThat(EmailInboxNode.isMailServerFailure(new MailConnectException(connect))).isFalse();
    }

    @Test
    @DisplayName("a non-mail exception keeps ERROR, even a bare socket timeout")
    void nonMailExceptionsAreNot() {
        assertThat(EmailInboxNode.isMailServerFailure(new NullPointerException("bug"))).isFalse();
        assertThat(EmailInboxNode.isMailServerFailure(new IllegalStateException("Folder not open"))).isFalse();
        assertThat(EmailInboxNode.isMailServerFailure(new SocketTimeoutException("not from jakarta.mail"))).isFalse();
        assertThat(EmailInboxNode.isMailServerFailure(null)).isFalse();
    }
}
