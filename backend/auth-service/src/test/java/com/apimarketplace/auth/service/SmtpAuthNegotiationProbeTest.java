package com.apimarketplace.auth.service;

import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Does a send AUTHENTICATE when credentials are present but
 * {@code mail.smtp.auth} is off?
 *
 * <p>This decides how CE has to be configured, and it cannot be answered by
 * reading either codebase: Spring passes the credentials to
 * {@code Transport.connect(host, port, user, password)} whichever way the flag
 * is set, and what happens next is inside the SMTP implementation. The previous
 * probe ({@link MailAuthDefaultContractTest}) only showed how far the connection
 * got, not what was negotiated on it.
 *
 * <p>So this stands up a socket that speaks just enough SMTP to advertise
 * {@code AUTH} and records the first command the client sends after EHLO. If the
 * answer is that credentials alone are enough, CE can leave the flag unbound and
 * keep working for the installs that use an unauthenticated relay today.
 */
@DisplayName("SMTP AUTH negotiation (what the flag actually decides)")
class SmtpAuthNegotiationProbeTest {

    /** Everything the client said after EHLO, in order. */
    private final List<String> clientCommands = new ArrayList<>();

    private String firstCommandAfterEhlo(boolean authFlag, String username, String password)
            throws Exception {
        clientCommands.clear();
        CountDownLatch spoke = new CountDownLatch(1);

        try (ServerSocket server = new ServerSocket(0)) {
            server.setSoTimeout(8000);
            Thread listener = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(8000);
                    BufferedReader in = new BufferedReader(
                            new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                    OutputStream rawOut = socket.getOutputStream();
                    PrintWriter out = new PrintWriter(rawOut, true);

                    out.print("220 probe ESMTP ready\r\n");
                    out.flush();

                    String line;
                    boolean greeted = false;
                    while ((line = in.readLine()) != null) {
                        if (!greeted && (line.startsWith("EHLO") || line.startsWith("HELO"))) {
                            greeted = true;
                            // Advertise AUTH, which is the precondition for the
                            // client to consider authenticating at all.
                            out.print("250-probe greets you\r\n");
                            out.print("250 AUTH PLAIN LOGIN\r\n");
                            out.flush();
                            continue;
                        }
                        if (greeted) {
                            clientCommands.add(line);
                            spoke.countDown();
                            // Refuse whatever it was and hang up: the test only
                            // needs to know WHICH command came first.
                            out.print("502 probe stops here\r\n");
                            out.flush();
                            break;
                        }
                    }
                } catch (Exception ignored) {
                    // A closed socket is the normal end of this conversation.
                } finally {
                    spoke.countDown();
                }
            }, "smtp-probe");
            listener.setDaemon(true);
            listener.start();

            JavaMailSenderImpl sender = new JavaMailSenderImpl();
            sender.setHost("127.0.0.1");
            sender.setPort(server.getLocalPort());
            sender.setUsername(username);
            sender.setPassword(password);
            Properties properties = new Properties();
            properties.put("mail.smtp.auth", String.valueOf(authFlag));
            properties.put("mail.smtp.connectiontimeout", "4000");
            properties.put("mail.smtp.timeout", "4000");
            properties.put("mail.smtp.writetimeout", "4000");
            sender.setJavaMailProperties(properties);

            try {
                MimeMessage message = sender.createMimeMessage();
                message.setFrom("noreply@example.com");
                message.setRecipients(jakarta.mail.Message.RecipientType.TO, "owner@example.com");
                message.setSubject("probe");
                message.setText("probe");
                sender.send(message);
            } catch (RuntimeException expected) {
                // The probe server refuses everything; the send always fails.
            }

            assertThat(spoke.await(10, TimeUnit.SECONDS))
                    .as("the client never got as far as a command")
                    .isTrue();
        }
        return clientCommands.isEmpty() ? "(nothing)" : clientCommands.get(0);
    }

    @Test
    @DisplayName("credentials WITHOUT the flag: the client still authenticates, so mail.smtp.auth is "
            + "not what enables authentication")
    void credentialsAloneStillAuthenticate() throws Exception {
        String first = firstCommandAfterEhlo(false, "a-user", "a-password");

        // This is the measurement CE's configuration rests on. If it holds, the
        // flag can be left unbound: an install with credentials authenticates,
        // and an install without them keeps sending unauthenticated, which is
        // what installs upgrading from a version that never bound the flag do.
        assertThat(first).startsWith("AUTH");
    }

    @Test
    @DisplayName("no credentials and no flag: the client goes straight to the envelope, which is what "
            + "an unauthenticated relay needs")
    void noCredentialsSendsPlainly() throws Exception {
        String first = firstCommandAfterEhlo(false, "", null);

        assertThat(first).doesNotStartWith("AUTH");
        assertThat(first).startsWith("MAIL FROM");
    }

    @Test
    @DisplayName("the flag ON with credentials authenticates too, so setting it is never harmful")
    void flagWithCredentialsAuthenticates() throws Exception {
        assertThat(firstCommandAfterEhlo(true, "a-user", "a-password")).startsWith("AUTH");
    }
}
