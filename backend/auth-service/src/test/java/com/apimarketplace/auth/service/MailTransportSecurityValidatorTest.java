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

    /**
     * Bounded timeouts, so these cases are about TLS / AUTH only.
     *
     * <p>Deliberately NOT described as "what application.yml ships": these are
     * hand-fed values and drifted from the shipped ones once already. What the
     * real yml binds is covered by {@link MailTransportConfigBindingTest}, which
     * boots it.
     */
    private static MailTransportSecurityValidator validator(String host, boolean tls, boolean auth) {
        return new MailTransportSecurityValidator(host, tls, auth, 5000, 5000, 5000);
    }

    @Nested
    @DisplayName("local-dev hosts: validator passes regardless of TLS / AUTH")
    class LocalDevHosts {

        @Test
        @DisplayName("localhost without STARTTLS - accepted (Mailhog default)")
        void localhostAcceptedEvenWithoutTls() {
            validator("localhost", false, false).validate();
            validator("LOCALHOST", false, false).validate();
            validator("127.0.0.1", false, false).validate();
            validator("::1", false, false).validate();
            validator("mailhog", false, false).validate();
            validator("mailpit", false, false).validate();
            validator("smtp-dev", false, false).validate();
        }

        @Test
        @DisplayName("local dev host with trailing whitespace - still accepted (trim)")
        void localhostWithWhitespaceTrimmed() {
            validator("  localhost  ", false, false).validate();
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
                    validator("smtp.sendgrid.net", false, true).validate())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("STARTTLS is disabled");
        }

        @Test
        @DisplayName("real host with STARTTLS=true but AUTH=false MUST also fail - "
                + "an open relay would let attackers send mail as us")
        void realHostWithoutAuthFailsBoot() {
            assertThatThrownBy(() ->
                    validator("smtp.mailgun.org", true, false).validate())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("SMTP AUTH is disabled");
        }

        @Test
        @DisplayName("real host with both STARTTLS and AUTH enabled - boots cleanly")
        void realHostWithProperConfigBoots() {
            // No exception = the @PostConstruct returns normally.
            validator("smtp.sendgrid.net", true, true).validate();
            validator("EMAIL-SMTP.us-east-1.amazonaws.com", true, true).validate();
        }
    }

    @Test
    @DisplayName("null host is treated as the empty string - falls through to the non-local branch "
            + "and triggers the STARTTLS check (won't silently pass)")
    void nullHostFailsClosed() {
        assertThatThrownBy(() ->
                validator(null, false, true).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("STARTTLS is disabled");
    }

    @Nested
    @DisplayName("unbounded transport: fail-fast, because a hung send holds a request thread")
    class Timeouts {

        @Test
        @DisplayName("PRE-FIX REPRO: no timeout properties at all MUST fail boot - this is what "
                + "deleting the block from application.yml looks like, and pre-fix it booted and "
                + "left /api/auth/forgot-password able to pin a thread per call, forever")
        void absentTimeoutsFailBoot() {
            assertThatThrownBy(() ->
                    new MailTransportSecurityValidator("localhost", false, false, 0, 0, 0).validate())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("connectiontimeout");
        }

        @Test
        @DisplayName("each of the three is checked on its own, so losing one is not masked by the others")
        void eachTimeoutIsCheckedIndividually() {
            assertThatThrownBy(() ->
                    new MailTransportSecurityValidator("smtp.sendgrid.net", true, true, 0, 5000, 5000).validate())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("mail.smtp.connectiontimeout");
            assertThatThrownBy(() ->
                    new MailTransportSecurityValidator("smtp.sendgrid.net", true, true, 5000, 0, 5000).validate())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("mail.smtp.timeout");
            assertThatThrownBy(() ->
                    new MailTransportSecurityValidator("smtp.sendgrid.net", true, true, 5000, 5000, 0).validate())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("mail.smtp.writetimeout");
        }

        @Test
        @DisplayName("a negative value is refused too - JavaMail reads it as infinite, same as zero")
        void negativeIsRefused() {
            assertThatThrownBy(() ->
                    new MailTransportSecurityValidator("localhost", false, false, -1, 5000, 5000).validate())
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("checked BEFORE the local-dev host allowance, because a frozen Mailpit hangs a read "
                + "exactly as long as a real relay would")
        void localDevHostDoesNotExemptTheTimeouts() {
            // The host allow-list waives STARTTLS and AUTH. It must not waive this.
            assertThatThrownBy(() ->
                    new MailTransportSecurityValidator("mailpit", false, false, 5000, 0, 5000).validate())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("mail.smtp.timeout");
        }

        @Test
        @DisplayName("a bounded transport boots cleanly (the SHIPPED values are checked by "
                + "MailTransportConfigBindingTest, which reads the real yml)")
        void shippedDefaultsBoot() {
            new MailTransportSecurityValidator("localhost", false, false, 5000, 5000, 5000).validate();
            new MailTransportSecurityValidator("smtp.sendgrid.net", true, true, 5000, 5000, 5000).validate();
        }
    }
}
