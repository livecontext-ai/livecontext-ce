package com.apimarketplace.orchestrator.services.mail;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("MailTimeouts")
class MailTimeoutsTest {

    @Nested
    @DisplayName("defaults")
    class Defaults {

        @Test
        @DisplayName("connect and read are DIFFERENT values, which is the whole defect")
        void connectAndReadDiffer() {
            // The bug was one number answering two questions: both timeouts were "10000", so a
            // server that merely answered slowly was treated like a server that was unreachable.
            // A test that only asserted "the timeouts are set" would have passed on the bug.
            MailTimeouts t = MailTimeouts.defaults();

            assertNotEquals(t.imapConnectMs(), t.imapReadMs(),
                    "an IMAP read must be allowed more time than a connect");
            assertNotEquals(t.smtpConnectMs(), t.smtpReadMs(),
                    "an SMTP read must be allowed more time than a connect");
            assertTrue(t.imapReadMs() > t.imapConnectMs());
            assertTrue(t.smtpReadMs() > t.smtpConnectMs());
        }

        @Test
        @DisplayName("connect stays at the historical 10s so an unreachable host still fails fast")
        void connectStaysShort() {
            assertEquals(10_000, MailTimeouts.defaults().imapConnectMs());
            assertEquals(10_000, MailTimeouts.defaults().smtpConnectMs());
        }

        @Test
        @DisplayName("the IMAP read ceiling leaves room for a bulk FETCH on a slow provider")
        void imapReadHasHeadroom() {
            // Production evidence: successful folder reads on a slow server took 30-73 seconds
            // in total, made of many round trips. Ten seconds per READ killed them mid-session.
            assertTrue(MailTimeouts.defaults().imapReadMs() >= 30_000,
                    "a hundred-message FETCH needs more than a handful of seconds per round trip");
        }
    }

    @Nested
    @DisplayName("validation")
    class Validation {

        @Test
        @DisplayName("zero falls back to the default instead of reaching Jakarta Mail as 'wait forever'")
        void zeroFallsBack() {
            // Jakarta Mail reads 0 as "no timeout". Passing an unset property straight through
            // would turn a slow mailbox into a permanently occupied worker thread.
            MailTimeouts t = new MailTimeouts(0, 0, 0, 0, 0);

            assertEquals(MailTimeouts.DEFAULT_CONNECT_MS, t.imapConnectMs());
            assertEquals(MailTimeouts.DEFAULT_IMAP_READ_MS, t.imapReadMs());
            assertEquals(MailTimeouts.DEFAULT_CONNECT_MS, t.smtpConnectMs());
            assertEquals(MailTimeouts.DEFAULT_SMTP_READ_MS, t.smtpReadMs());
            assertEquals(MailTimeouts.DEFAULT_SMTP_WRITE_MS, t.smtpWriteMs());
        }

        @Test
        @DisplayName("a negative value falls back too")
        void negativeFallsBack() {
            MailTimeouts t = new MailTimeouts(-1, -5000, -1, -1, -1);

            assertEquals(MailTimeouts.DEFAULT_CONNECT_MS, t.imapConnectMs());
            assertEquals(MailTimeouts.DEFAULT_IMAP_READ_MS, t.imapReadMs());
        }

        @Test
        @DisplayName("a configured value is kept verbatim, per field")
        void configuredValuesAreKept() {
            MailTimeouts t = new MailTimeouts(5_000, 90_000, 4_000, 20_000, 25_000);

            assertEquals(5_000, t.imapConnectMs());
            assertEquals(90_000, t.imapReadMs());
            assertEquals(4_000, t.smtpConnectMs());
            assertEquals(20_000, t.smtpReadMs());
            assertEquals(25_000, t.smtpWriteMs());
        }

        @Test
        @DisplayName("a substitution is LOGGED, naming the property - the only signal it happened")
        void substitutionIsLogged() {
            // The WARN is the entire compensation for coercing instead of failing the boot the
            // way auth-service does. Without this test, deleting it leaves the suite green and
            // a misconfigured timeout becomes indistinguishable from a healthy default.
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            Logger logger = (Logger) LoggerFactory.getLogger(MailTimeouts.class);
            appender.start();
            logger.addAppender(appender);
            try {
                new MailTimeouts(0, 5_000, 5_000, 5_000, 5_000);
            } finally {
                logger.detachAppender(appender);
            }

            List<ILoggingEvent> warnings = appender.list.stream()
                    .filter(e -> e.getLevel() == Level.WARN).toList();
            assertEquals(1, warnings.size(), "one substituted field, one line");
            assertTrue(warnings.get(0).getFormattedMessage()
                            .contains("workflow.mail.imap.connect-timeout-ms"),
                    "the line must NAME the property, or it cannot be acted on");
        }

        @Test
        @DisplayName("a healthy set of values logs nothing")
        void healthyValuesAreSilent() {
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            Logger logger = (Logger) LoggerFactory.getLogger(MailTimeouts.class);
            appender.start();
            logger.addAppender(appender);
            try {
                MailTimeouts.defaults();
            } finally {
                logger.detachAppender(appender);
            }

            assertFalse(appender.list.stream().anyMatch(e -> e.getLevel() == Level.WARN),
                    "a boot that configured everything correctly must stay quiet");
        }

        @Test
        @DisplayName("one bad field does not drag the others to their defaults")
        void fieldsAreIndependent() {
            MailTimeouts t = new MailTimeouts(7_000, 0, 8_000, 0, 9_000);

            assertEquals(7_000, t.imapConnectMs(), "a valid field survives a sibling's fallback");
            assertEquals(MailTimeouts.DEFAULT_IMAP_READ_MS, t.imapReadMs());
            assertEquals(8_000, t.smtpConnectMs());
            assertEquals(MailTimeouts.DEFAULT_SMTP_READ_MS, t.smtpReadMs());
            assertEquals(9_000, t.smtpWriteMs());
        }
    }
}
