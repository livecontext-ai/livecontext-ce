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

    public MailTransportSecurityValidator(
            @Value("${spring.mail.host:localhost}") String mailHost,
            @Value("${spring.mail.properties.mail.smtp.starttls.enable:true}") boolean starttlsEnabled,
            @Value("${spring.mail.properties.mail.smtp.auth:true}") boolean authEnabled) {
        this.mailHost = mailHost;
        this.starttlsEnabled = starttlsEnabled;
        this.authEnabled = authEnabled;
    }

    @PostConstruct
    void validate() {
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
                            + "Set SMTP_AUTH=true and provide SMTP_USER / SMTP_PASSWORD.");
        }
        log.info("SMTP transport security: host '{}' validated (STARTTLS=true, AUTH=true)", host);
    }
}
