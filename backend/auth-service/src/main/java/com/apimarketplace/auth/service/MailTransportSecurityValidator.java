package com.apimarketplace.auth.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

/**
 * Fail-fasts at boot when the SMTP transport is configured for a real
 * (non-local) host without STARTTLS - otherwise invitation tokens and
 * verification codes would leave the JVM in cleartext.
 *
 * <p>Local-dev hosts (Mailhog / Mailpit) are allow-listed by hostname so the
 * usual {@code localhost:1025} setup keeps working without enabling TLS.
 * Any other host triggers a hard fail.
 *
 * <p>It also refuses to start when the transport is UNBOUNDED. JavaMail defaults
 * its connect, read and write timeouts to infinite, so an SMTP host that accepts
 * the TCP connection and then never answers leaves the sender waiting with
 * nothing to release it.
 *
 * <p>Which callers that actually hurts is worth being exact about, because an
 * earlier version of this comment had it backwards. Most mail here IS sent on
 * the request thread ({@code EmailVerificationService},
 * {@code OrganizationInvitationMailer}), and an unbounded send there holds a
 * request thread until the pool is exhausted. The one exception is
 * {@link PasswordResetMailer}, which hands the send to its own bounded pool
 * precisely because {@code /api/auth/forgot-password} is public: there an
 * unbounded send occupies one of two dedicated threads instead, so the queue
 * backs up and mails are dropped rather than the service going down. Neither
 * outcome is acceptable, and both are invisible until production.
 *
 * <p>The timeouts have defaults in {@code application.yml}, so the only way to
 * reach this failure is to remove them or set one to zero, and either deserves a
 * loud boot failure over a hang discovered later.
 *
 * <p>S-3 in the invitation security pass (see audit history).
 */
@Component
public class MailTransportSecurityValidator {

    private static final Logger log = LoggerFactory.getLogger(MailTransportSecurityValidator.class);

    private static final Set<String> LOCAL_DEV_HOSTS = Set.of(
            "localhost", "127.0.0.1", "::1", "mailhog", "mailpit", "smtp-dev");

    private final String mailHost;
    private final boolean starttlsEnabled;
    private final boolean authEnabled;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final int writeTimeoutMs;

    public MailTransportSecurityValidator(
            @Value("${spring.mail.host:localhost}") String mailHost,
            @Value("${spring.mail.properties.mail.smtp.starttls.enable:true}") boolean starttlsEnabled,
            @Value("${spring.mail.properties.mail.smtp.auth:true}") boolean authEnabled,
            // Deliberately defaulted to 0 (= JavaMail's "wait forever"), NOT to a
            // safe number: an absent property must fail the boot, because that is
            // exactly what deleting the block from application.yml looks like.
            @Value("${spring.mail.properties.mail.smtp.connectiontimeout:0}") int connectTimeoutMs,
            @Value("${spring.mail.properties.mail.smtp.timeout:0}") int readTimeoutMs,
            @Value("${spring.mail.properties.mail.smtp.writetimeout:0}") int writeTimeoutMs) {
        this.mailHost = mailHost;
        this.starttlsEnabled = starttlsEnabled;
        this.authEnabled = authEnabled;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        this.writeTimeoutMs = writeTimeoutMs;
    }

    @PostConstruct
    void validate() {
        // Before the host branch on purpose: a dev relay that is frozen rather
        // than absent hangs a read just as long as a real one would.
        requireBounded("connectiontimeout", connectTimeoutMs);
        requireBounded("timeout", readTimeoutMs);
        requireBounded("writetimeout", writeTimeoutMs);

        String host = mailHost == null ? "" : mailHost.trim().toLowerCase(Locale.ROOT);
        boolean isLocalDevHost = LOCAL_DEV_HOSTS.contains(host);

        if (isLocalDevHost) {
            log.info("SMTP transport security: dev host '{}' detected - STARTTLS/AUTH not enforced", host);
            return;
        }

        if (!starttlsEnabled) {
            throw new IllegalStateException(
                    "Refusing to start: SMTP host '" + host + "' is non-local but STARTTLS is disabled. "
                            + "Set SMTP_STARTTLS=true (recommended) or change SMTP_HOST to a dev relay.");
        }
        if (!authEnabled) {
            throw new IllegalStateException(
                    "Refusing to start: SMTP host '" + host + "' is non-local but SMTP AUTH is disabled. "
                            + "Set SMTP_AUTH=true and provide SMTP_USER / SMTP_PASSWORD. Set both "
                            + "credentials or neither: with AUTH on and the USERNAME missing, "
                            + "JavaMail refuses to open the connection at all rather than failing "
                            + "on the wire. (A self-hosted install does not reach this check: "
                            + "application-ce.yml deliberately leaves mail.smtp.auth unbound, and "
                            + "sets credentials instead.)");
        }
        log.info("SMTP transport security: host '{}' validated (STARTTLS=true, AUTH=true)", host);
    }

    private void requireBounded(String property, int valueMs) {
        if (valueMs <= 0) {
            throw new IllegalStateException(
                    "Refusing to start: spring.mail.properties.mail.smtp." + property + " is "
                            + valueMs + ", which JavaMail reads as no timeout at all. Verification and "
                            + "invitation mail is sent on the request thread, so an unresponsive SMTP "
                            + "host would hold request threads until the pool is exhausted; password "
                            + "reset mail is sent from its own bounded pool, where the same host "
                            + "silently backs the queue up instead. Set it to a positive number of "
                            + "milliseconds (application.yml ships 10000).");
        }
    }
}
