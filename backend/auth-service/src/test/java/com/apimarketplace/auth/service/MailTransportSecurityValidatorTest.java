package com.apimarketplace.auth.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * S-3 regression guard. The validator is the only thing standing between a
 * misconfigured prod SMTP relay and cleartext invitation tokens on the wire.
 */
@DisplayName("MailTransportSecurityValidator (S-3 invitation security pass)")
class MailTransportSecurityValidatorTest {

    @Nested
    @DisplayName("local-dev hosts: validator passes regardless of TLS / AUTH")
    class LocalDevHosts {

        @Test
        @DisplayName("localhost without STARTTLS - accepted (Mailhog default)")
        void localhostAcceptedEvenWithoutTls() {
            new MailTransportSecurityValidator("localhost", false, false).validate();
            new MailTransportSecurityValidator("LOCALHOST", false, false).validate();
            new MailTransportSecurityValidator("127.0.0.1", false, false).validate();
            new MailTransportSecurityValidator("::1", false, false).validate();
            new MailTransportSecurityValidator("mailhog", false, false).validate();
            new MailTransportSecurityValidator("mailpit", false, false).validate();
            new MailTransportSecurityValidator("smtp-dev", false, false).validate();
        }

        @Test
        @DisplayName("local dev host with trailing whitespace - still accepted (trim)")
        void localhostWithWhitespaceTrimmed() {
            new MailTransportSecurityValidator("  localhost  ", false, false).validate();
        }
    }

    @Nested
    @DisplayName("real hosts: fail-fast if security primitives are off")
    class RealHosts {

        @Test
        @DisplayName("S-3 PRE-FIX REPRO: SMTP_STARTTLS=false against a real host MUST fail boot - "
                + "pre-fix this booted silently and leaked tokens in cleartext")
        void realHostWithoutStarttlsFailsBoot() {
            assertThatThrownBy(() ->
                    new MailTransportSecurityValidator("smtp.sendgrid.net", false, true).validate())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("STARTTLS is disabled");
        }

        @Test
        @DisplayName("real host with STARTTLS=true but AUTH=false MUST also fail - "
                + "an open relay would let attackers send mail as us")
        void realHostWithoutAuthFailsBoot() {
            assertThatThrownBy(() ->
                    new MailTransportSecurityValidator("smtp.mailgun.org", true, false).validate())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("SMTP AUTH is disabled");
        }

        @Test
        @DisplayName("real host with both STARTTLS and AUTH enabled - boots cleanly")
        void realHostWithProperConfigBoots() {
            // No exception = the @PostConstruct returns normally.
            new MailTransportSecurityValidator("smtp.sendgrid.net", true, true).validate();
            new MailTransportSecurityValidator("EMAIL-SMTP.us-east-1.amazonaws.com", true, true).validate();
        }
    }

    @Test
    @DisplayName("null host is treated as the empty string - falls through to the non-local branch "
            + "and triggers the STARTTLS check (won't silently pass)")
    void nullHostFailsClosed() {
        assertThatThrownBy(() ->
                new MailTransportSecurityValidator(null, false, true).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("STARTTLS is disabled");
    }
}
