package com.apimarketplace.auth.service;

import org.eclipse.angus.mail.util.MailSSLSocketFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.mail.MailProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Why {@code mail.smtp.ssl.trust} must NOT be bound with an empty default.
 *
 * <p>The intuition it is easy to ship on, and which was shipped here for one
 * round, is that an empty value is the same as an absent one: "blank means the
 * JVM's normal trust store". It is not. An empty value is a PRESENT key, and
 * JavaMail branches on presence, so the blank default turns the JVM's normal
 * verification into a host allow-list containing exactly one entry: the empty
 * string. Every real host then fails that check AFTER a perfectly good
 * certificate has already validated, and the send dies with "Server is not
 * trusted".
 *
 * <p>Which makes the blank default worse than useless: it does not weaken TLS,
 * it blocks delivery to every relay that offers STARTTLS at all, and only to
 * those. A relay that never offers TLS (Mailpit) is untouched, which is exactly
 * why an e2e run against a dev relay passes while every real relay fails.
 *
 * <p>These are the measurements, kept so the key cannot come back with an empty
 * default. Each step of the chain is asserted separately, because the mechanism
 * is the part that is easy to get wrong.
 */
@DisplayName("mail.smtp.ssl.trust: why an empty default blocks every STARTTLS relay")
class SmtpSslTrustBindingProbeTest {

    @EnableConfigurationProperties(MailProperties.class)
    static class MailPropertiesOnly {
    }

    @Test
    @DisplayName("STEP 1: an empty placeholder default binds as a PRESENT key, not an absent one")
    void emptyPlaceholderBindsAsPresent() {
        new ApplicationContextRunner()
                .withUserConfiguration(MailPropertiesOnly.class)
                // What `mail.smtp.ssl.trust: ${MAIL_SMTP_SSL_TRUST:}` resolves to
                // when the variable is unset.
                .withPropertyValues("spring.mail.properties.mail.smtp.ssl.trust=")
                .run(context -> {
                    MailProperties properties = context.getBean(MailProperties.class);
                    assertThat(properties.getProperties())
                            .as("Spring keeps the key; it does not drop an empty value")
                            .containsKey("mail.smtp.ssl.trust");
                    assertThat(properties.getProperties().get("mail.smtp.ssl.trust")).isEmpty();
                });
    }

    @Test
    @DisplayName("STEP 2: the empty string splits into a ONE-element array, so the host allow-list "
            + "is non-empty")
    void emptyStringSplitsIntoOneElement() {
        // This is the line inside SocketFetcher.startTLS: setTrustedHosts(trust.split("\\s+")).
        // An empty trust value therefore yields a list containing "" rather than
        // no list at all, which is the whole difference between "verify normally"
        // and "allow nothing".
        String[] hosts = "".split("\\s+");

        assertThat(hosts).hasSize(1);
        assertThat(hosts[0]).isEmpty();
    }

    @Test
    @DisplayName("STEP 3: a factory trusting only that empty host trusts NO real host")
    void anEmptyTrustedHostListTrustsNothing() throws Exception {
        MailSSLSocketFactory factory = new MailSSLSocketFactory();
        factory.setTrustedHosts("".split("\\s+"));

        // isServerTrusted is applied AFTER the handshake and after CA and
        // hostname validation have passed, so a perfectly valid certificate is
        // rejected anyway and SocketFetcher throws "Server is not trusted".
        assertThat(factory.isServerTrusted("smtp.sendgrid.net", null)).isFalse();
        assertThat(factory.isServerTrusted("smtp.gmail.com", null)).isFalse();
        assertThat(factory.isServerTrusted("localhost", null)).isFalse();
    }

    @Test
    @DisplayName("STEP 4: and the contrast, so the mechanism is not mistaken for something else: a "
            + "named host IS trusted, and '*' trusts everything")
    void aNamedHostIsTrustedAndStarTrustsAll() throws Exception {
        MailSSLSocketFactory named = new MailSSLSocketFactory();
        named.setTrustedHosts("smtp.sendgrid.net".split("\\s+"));
        assertThat(named.isServerTrusted("smtp.sendgrid.net", null)).isTrue();
        assertThat(named.isServerTrusted("smtp.gmail.com", null)).isFalse();

        MailSSLSocketFactory all = new MailSSLSocketFactory();
        all.setTrustAllHosts(true);
        assertThat(all.isServerTrusted("anything.example.com", null)).isTrue();
    }
}
