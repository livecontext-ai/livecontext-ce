package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.services.mail.MailTimeouts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Regression cover for the hardcoded mail timeouts.
 *
 * <p>Both mail nodes used to write {@code "10000"} into every Jakarta Mail timeout, and nothing
 * read those properties back, so the literals were free to be wrong for as long as the fast
 * server everyone tested against kept answering inside them. These tests assert the values that
 * actually reach the session, and above all that connect and read are not the same number: a
 * test asserting only "a timeout is present" passes on the defect.
 */
@DisplayName("Mail node socket timeouts")
class MailNodeTimeoutPropertiesTest {

    @Nested
    @DisplayName("EmailInboxNode (IMAP)")
    class Imap {

        @Test
        @DisplayName("carries the configured connect and read timeouts")
        void carriesConfiguredTimeouts() {
            MailTimeouts timeouts = new MailTimeouts(7_000, 90_000, 1, 1, 1);

            Properties props = EmailInboxNode.buildMailProperties("imap.example.com", 993, true, timeouts);

            assertEquals("7000", props.get("mail.imaps.connectiontimeout"));
            assertEquals("90000", props.get("mail.imaps.timeout"));
        }

        @Test
        @DisplayName("connect and read are NOT the same value by default - the original defect")
        void connectAndReadAreDistinct() {
            Properties props = EmailInboxNode.buildMailProperties(
                    "imap.example.com", 993, true, MailTimeouts.defaults());

            assertNotEquals(props.get("mail.imaps.connectiontimeout"), props.get("mail.imaps.timeout"),
                    "pre-fix both were \"10000\"; a slow server was treated as an unreachable one");
        }

        @Test
        @DisplayName("the timeout keys follow the protocol, so STARTTLS is configured too")
        void plainProtocolKeysAreAlsoSet() {
            // use_ssl=false switches the whole property namespace from imaps to imap. Writing
            // the timeouts under the wrong prefix would silently leave the defaults in place.
            Properties props = EmailInboxNode.buildMailProperties(
                    "imap.example.com", 143, false, new MailTimeouts(6_000, 45_000, 1, 1, 1));

            assertEquals("6000", props.get("mail.imap.connectiontimeout"));
            assertEquals("45000", props.get("mail.imap.timeout"));
            assertEquals("true", props.get("mail.imap.starttls.enable"));
        }

        @Test
        @DisplayName("null timeouts fall back to the defaults rather than to Jakarta Mail's 'forever'")
        void nullTimeoutsFallBack() {
            Properties props = EmailInboxNode.buildMailProperties("imap.example.com", 993, true, null);

            assertEquals(String.valueOf(MailTimeouts.DEFAULT_CONNECT_MS),
                    props.get("mail.imaps.connectiontimeout"));
            assertEquals(String.valueOf(MailTimeouts.DEFAULT_IMAP_READ_MS),
                    props.get("mail.imaps.timeout"));
        }

        @Test
        @DisplayName("TLS settings are untouched by the timeout work")
        void tlsUnchanged() {
            Properties props = EmailInboxNode.buildMailProperties(
                    "imap.example.com", 993, true, MailTimeouts.defaults());

            assertEquals("true", props.get("mail.imaps.ssl.enable"));
            assertEquals("TLSv1.2 TLSv1.3", props.get("mail.imaps.ssl.protocols"));
            assertEquals("imaps", props.get("mail.store.protocol"));
        }
    }

    @Nested
    @DisplayName("SendEmailNode (SMTP)")
    class Smtp {

        @Test
        @DisplayName("carries the configured connect, read and write timeouts")
        void carriesConfiguredTimeouts() {
            MailTimeouts timeouts = new MailTimeouts(1, 1, 4_000, 20_000, 25_000);

            Properties props = SendEmailNode.buildSmtpProperties(
                    "smtp.example.com", 587, "user", true, timeouts);

            assertEquals("4000", props.get("mail.smtp.connectiontimeout"));
            assertEquals("20000", props.get("mail.smtp.timeout"));
            assertEquals("25000", props.get("mail.smtp.writetimeout"));
        }

        @Test
        @DisplayName("connect is shorter than read by default - read and write legitimately match")
        void connectIsShorterThanRead() {
            // Deliberately NOT claiming three distinct values: pushing bytes up and waiting for
            // the reply are the same order of magnitude, so read and write share a default. Only
            // connect answers a different question, and only connect must differ.
            Properties props = SendEmailNode.buildSmtpProperties(
                    "smtp.example.com", 587, "user", true, MailTimeouts.defaults());

            assertNotEquals(props.get("mail.smtp.connectiontimeout"), props.get("mail.smtp.timeout"));
            assertEquals(String.valueOf(MailTimeouts.DEFAULT_SMTP_WRITE_MS),
                    props.get("mail.smtp.writetimeout"));
        }

        @Test
        @DisplayName("the previous 4-arg shape still compiles and now yields the defaults")
        void backCompatOverload() {
            // Kept so the existing transport-security tests, which care about TLS and not about
            // timeouts, need no edit - the same widening pattern NodePolicy documents.
            Properties props = SendEmailNode.buildSmtpProperties("smtp.example.com", 587, "user", true);

            assertEquals(String.valueOf(MailTimeouts.DEFAULT_CONNECT_MS),
                    props.get("mail.smtp.connectiontimeout"));
            assertEquals(String.valueOf(MailTimeouts.DEFAULT_SMTP_READ_MS),
                    props.get("mail.smtp.timeout"));
            assertEquals("true", props.get("mail.smtp.starttls.required"),
                    "the security decisions the overload already guarded are unchanged");
        }
    }
}
