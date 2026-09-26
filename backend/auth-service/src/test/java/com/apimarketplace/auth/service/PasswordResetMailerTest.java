package com.apimarketplace.auth.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The reset e-mail, and the three properties that are easy to state and easy to
 * lose.
 *
 * <p>All three were unverified before: the send being detached from the request
 * thread (an enumeration mitigation), the failure not escaping the dispatcher (a
 * failure that escaped would only ever be observable for an address that exists,
 * which is the same oracle), and the raw token never reaching a log.
 */
@DisplayName("PasswordResetMailer")
class PasswordResetMailerTest {

    private static final String TOKEN = "a-token-that-must-never-be-logged";

    private JavaMailSender mailSender;
    private PasswordResetMailer mailer;
    private ListAppender<ILoggingEvent> logged;
    private Logger mailerLogger;

    @BeforeEach
    void setUp() {
        mailSender = mock(JavaMailSender.class);
        when(mailSender.createMimeMessage()).thenAnswer(invocation -> new MimeMessage((jakarta.mail.Session) null));
        mailer = new PasswordResetMailer(mailSender, "noreply@livecontext.ai", "LiveContext",
                "https://install.example.com");

        logged = new ListAppender<>();
        // The dispatcher sends on its own thread, so it appends to this list while the
        // assertions below read it. ListAppender's default backing list is a plain ArrayList,
        // and every read here is a live iteration (three stream() calls plus one enhanced for),
        // so a line arriving mid-read throws ConcurrentModificationException. It did, on dev CI,
        // on tokenIsNeverLogged. A concurrent list makes all four reads safe at once, which is
        // better than snapshotting at each call site and hoping the next reader remembers.
        logged.list = new CopyOnWriteArrayList<>();
        logged.start();
        mailerLogger = (Logger) LoggerFactory.getLogger(PasswordResetMailer.class);
        mailerLogger.addAppender(logged);
        mailerLogger.setLevel(Level.TRACE);
    }

    @AfterEach
    void tearDown() {
        mailerLogger.detachAppender(logged);
    }

    /** Blocks until the pool has actually tried to send, or fails the test. */
    private CountDownLatch latchOnSend() {
        CountDownLatch sent = new CountDownLatch(1);
        doAnswer(invocation -> {
            sent.countDown();
            return null;
        }).when(mailSender).send(any(MimeMessage.class));
        return sent;
    }

    @Test
    @DisplayName("dispatch hands the send to another thread, so the caller does not pay the SMTP round trip")
    void dispatchSendsOffTheCallingThread() throws Exception {
        AtomicReference<String> sendingThread = new AtomicReference<>();
        CountDownLatch sent = new CountDownLatch(1);
        doAnswer(invocation -> {
            sendingThread.set(Thread.currentThread().getName());
            sent.countDown();
            return null;
        }).when(mailSender).send(any(MimeMessage.class));

        mailer.dispatchResetEmail("owner@example.com", "Ada", TOKEN, 60, 7L);

        assertThat(sent.await(5, TimeUnit.SECONDS)).as("the mail was never sent").isTrue();
        // The whole point of the dispatch: a known address must not cost the
        // request thread an SMTP conversation that an unknown one avoids.
        assertThat(sendingThread.get())
                .isNotEqualTo(Thread.currentThread().getName())
                .startsWith("pwreset-mail");
    }

    @Test
    @DisplayName("a delivery failure does NOT escape the dispatcher, because it would only ever be "
            + "observable for an address that exists")
    void deliveryFailureDoesNotEscape() throws Exception {
        CountDownLatch attempted = new CountDownLatch(1);
        doAnswer(invocation -> {
            attempted.countDown();
            throw new MailSendException("no route to host");
        }).when(mailSender).send(any(MimeMessage.class));

        assertThatCode(() -> mailer.dispatchResetEmail("owner@example.com", "Ada", TOKEN, 60, 7L))
                .doesNotThrowAnyException();

        assertThat(attempted.await(5, TimeUnit.SECONDS)).isTrue();
        // Await the SPECIFIC line, not "any ERROR". The send logs its own failure
        // first and the dispatcher's follows, so waiting for the first one and
        // then asserting on the second observes the gap between them: that is a
        // real flake, seen once on a cold run before this was tightened.
        await(() -> logged.list.stream()
                        .filter(e -> e.getLevel() == Level.ERROR)
                        .anyMatch(e -> e.getFormattedMessage().contains("still locked out")),
                "the operator line saying the user is stuck, not just that a send failed");
    }

    @Test
    @DisplayName("the raw token reaches NO log line, at any level, on success or on failure")
    void tokenIsNeverLogged() throws Exception {
        CountDownLatch sent = latchOnSend();
        mailer.dispatchResetEmail("owner@example.com", "Ada", TOKEN, 60, 7L);
        assertThat(sent.await(5, TimeUnit.SECONDS)).isTrue();
        await(() -> !logged.list.isEmpty(), "at least one log line");

        doAnswer(invocation -> {
            throw new MailSendException("refused");
        }).when(mailSender).send(any(MimeMessage.class));
        mailer.dispatchResetEmail("owner@example.com", "Ada", TOKEN, 60, 7L);
        await(() -> logged.list.stream().anyMatch(e -> e.getLevel() == Level.ERROR), "the failure line");

        for (ILoggingEvent event : logged.list) {
            assertThat(event.getFormattedMessage())
                    .as("a reset link is a bearer credential for the account")
                    .doesNotContain(TOKEN);
            assertThat(String.valueOf(event.getThrowableProxy()))
                    .doesNotContain(TOKEN);
        }
    }

    @Test
    @DisplayName("the link this mailer EMITS is <frontend>/reset-password?token=... - that the proxy "
            + "resolves that path is the other half of the contract, pinned in "
            + "frontend/__tests__/proxy.localeRequiredPrefixes.test.ts")
    void linkPointsAtTheResetPage() {
        AtomicReference<String> body = new AtomicReference<>();
        doAnswer(invocation -> {
            body.set(readAllText(invocation.getArgument(0)));
            return null;
        }).when(mailSender).send(any(MimeMessage.class));

        mailer.sendResetEmail("owner@example.com", "Ada", "tok-123", 60);

        assertThat(body.get()).contains("https://install.example.com/reset-password?token=tok-123");
    }

    @Test
    @DisplayName("the HTML part ESCAPES the name, so a display name cannot inject markup into the "
            + "e-mail body")
    void htmlPartIsEscaped() {
        AtomicReference<String> body = new AtomicReference<>();
        doAnswer(invocation -> {
            body.set(readAllText(invocation.getArgument(0)));
            return null;
        }).when(mailSender).send(any(MimeMessage.class));

        mailer.sendResetEmail("owner@example.com", "<b>Ada</b>", "tok-123", 60);

        // Measured gap: replacing sanitize() with the identity function left every
        // test green, because the only assertion on it checked that the PLAIN part
        // was NOT escaped.
        assertThat(body.get()).contains("&lt;b&gt;Ada&lt;/b&gt;");
    }

    @Test
    @DisplayName("once shut down, a further dispatch is REFUSED and reported, never accepted into a "
            + "pool that will not run it")
    void dispatchAfterShutdownIsReported() throws Exception {
        CountDownLatch sent = latchOnSend();
        mailer.dispatchResetEmail("owner@example.com", "Ada", TOKEN, 60, 7L);
        assertThat(sent.await(5, TimeUnit.SECONDS)).isTrue();
        await(() -> mailer.unsentCount() == 0, "the first send to finish");

        mailer.shutdown();
        logged.list.clear();

        mailer.dispatchResetEmail("owner@example.com", "Ada", TOKEN, 60, 7L);

        // Replace shutdown()'s body with `return` and this dispatch is accepted
        // instead, which is how the drain and its report went unverified.
        await(() -> logged.list.stream()
                        .filter(e -> e.getLevel() == Level.ERROR)
                        .anyMatch(e -> e.getFormattedMessage().contains("could not be queued")),
                "the refusal to be reported");
        // And the counter does not leak: a refused task was never in flight.
        assertThat(mailer.unsentCount()).isZero();
    }

    @Test
    @DisplayName("the mail is addressed to the ACCOUNT's address, and the link is in BOTH parts")
    void recipientAndBothBodyParts() throws Exception {
        java.util.concurrent.atomic.AtomicReference<MimeMessage> sent = new AtomicReference<>();
        doAnswer(invocation -> {
            sent.set(invocation.getArgument(0));
            return null;
        }).when(mailSender).send(any(MimeMessage.class));

        mailer.sendResetEmail("owner@example.com", "Ada", "tok-123", 60);

        // Measured gap: replacing setTo(email) with a hard-coded third party left
        // 10 tests green. `email` and `displayName` are adjacent String
        // parameters, so a swapped call site is exactly the refactor that would
        // send someone else's reset link.
        assertThat(java.util.Arrays.stream(sent.get().getAllRecipients())
                .map(Object::toString).toList())
                .containsExactly("owner@example.com");

        // The link has to be in the PLAIN part too: dropping it there left every
        // test green because the HTML copy satisfied them, and a plain-text
        // client would have received a mail with no way to act on it.
        String plain = plainTextPart(sent.get());
        assertThat(plain).contains("https://install.example.com/reset-password?token=tok-123");
        // And the TTL must be substituted, not left as a template marker.
        assertThat(plain).contains("60 minutes");
        assertThat(readAllText(sent.get())).doesNotContain("{{TTL}}").doesNotContain("{{URL}}");
    }

    @Test
    @DisplayName("a display name containing a template marker cannot expand in the body, because the "
            + "template's own placeholders are substituted first")
    void displayNameCannotInjectATemplateMarker() throws Exception {
        AtomicReference<MimeMessage> sentMessage = new AtomicReference<>();
        doAnswer(invocation -> {
            sentMessage.set(invocation.getArgument(0));
            return null;
        }).when(mailSender).send(any(MimeMessage.class));

        mailer.sendResetEmail("owner@example.com", "{{URL}}", "tok-123", 60);

        // The HTML part ONLY. Reading the whole message made this pass either
        // way: the plain-text part interpolates the name with String.format, so
        // the literal marker is in it regardless of the order, and the assertion
        // was being satisfied by the wrong half of the message.
        //
        // In the HTML part the marker survives only if the template's own
        // {{URL}} was substituted BEFORE the name was inserted. Reverse the
        // order and the injected marker is expanded too, so it disappears.
        String html = htmlPart(sentMessage.get());
        assertThat(html).contains("{{URL}}");
    }

    @Test
    @DisplayName("a shutdown that cannot drain REPORTS how many mails it abandoned, with the count "
            + "and not a zero")
    void shutdownReportsWhatItAbandons() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            inside.countDown();
            release.await(10, TimeUnit.SECONDS);
            return null;
        }).when(mailSender).send(any(MimeMessage.class));

        mailer.setDrainSecondsForTest(1);
        mailer.dispatchResetEmail("owner@example.com", "Ada", TOKEN, 60, 7L);
        assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();
        logged.list.clear();

        mailer.shutdown();

        // Replace `pending.get()` with 0 and this passes silently: the ERROR line
        // that exists so no abandoned mail is invisible reported nothing in
        // exactly the case where one was lost.
        await(() -> logged.list.stream()
                        .filter(e -> e.getLevel() == Level.ERROR)
                        .anyMatch(e -> e.getFormattedMessage().contains("1 password reset e-mail(s) unsent")),
                "the abandoned-mail count");
        release.countDown();
    }

    @Test
    @DisplayName("counts the abandoned mail even when the interrupted send finishes before shutdown reads the count")
    void abandonedCountSurvivesTheInterruptedSendFinishingFirst() throws Exception {
        // Regression: the count used to be read AFTER shutdownNow(). Interrupting the
        // in-flight send runs its finally, which decrements the count; when that thread won
        // the race the count read 0 and the ERROR reporting a lost mail stayed silent. The
        // hook forces that interleaving every time: it waits until the interrupted send is
        // done before shutdown goes on. With the old order this test fails on every run.
        CountDownLatch inside = new CountDownLatch(1);
        doAnswer(invocation -> {
            inside.countDown();
            new CountDownLatch(1).await(10, TimeUnit.SECONDS);
            return null;
        }).when(mailSender).send(any(MimeMessage.class));

        mailer.setDrainSecondsForTest(1);
        mailer.setAfterShutdownNowForTest(() -> {
            long deadline = System.currentTimeMillis() + 5000;
            while (mailer.unsentCount() > 0 && System.currentTimeMillis() < deadline) {
                Thread.onSpinWait();
            }
        });
        mailer.dispatchResetEmail("owner@example.com", "Ada", TOKEN, 60, 7L);
        assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();
        logged.list.clear();

        mailer.shutdown();

        assertThat(mailer.unsentCount()).as("the interrupted send has finished").isZero();
        assertThat(logged.list.stream()
                .filter(e -> e.getLevel() == Level.ERROR)
                .anyMatch(e -> e.getFormattedMessage().contains("1 password reset e-mail(s) unsent")))
                .as("the lost mail is still reported").isTrue();
    }

    /** The text/html alternative alone, so the plain part cannot satisfy an assertion about it. */
    private static String htmlPart(MimeMessage message) throws Exception {
        String found = firstOfType(message.getContent(), true);
        if (found == null) {
            throw new AssertionError("no text/html part in the message");
        }
        return found;
    }

    /** The text/plain alternative alone, so the HTML copy cannot satisfy an assertion about it. */
    private static String plainTextPart(MimeMessage message) throws Exception {
        String found = firstTextPlain(message.getContent());
        if (found == null) {
            throw new AssertionError("no text/plain part in the message");
        }
        return found;
    }

    private static String firstTextPlain(Object content) throws Exception {
        return firstOfType(content, false);
    }

    private static String firstOfType(Object content, boolean wantHtml) throws Exception {
        // The instance check comes FIRST on purpose: on a message that has not
        // been saveChanges()'d, a nested multipart/alternative part answers
        // isMimeType("text/plain") with true while its content is the nested
        // multipart, so testing the declared type first walks into it and
        // returns the container's toString.
        if (content instanceof jakarta.mail.Multipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                String nested = firstOfType(multipart.getBodyPart(i).getContent(), wantHtml);
                if (nested != null) {
                    return nested;
                }
            }
            return null;
        }
        if (content instanceof String text) {
            // The HTML alternative starts with a doctype; the plain one does not.
            boolean isHtml = text.stripLeading().startsWith("<");
            return isHtml == wantHtml ? text : null;
        }
        return null;
    }


    @Test
    @DisplayName("the plain-text part shows the name as typed: escaping is for the HTML part only")
    void plainTextIsNotHtmlEscaped() {
        AtomicReference<String> body = new AtomicReference<>();
        doAnswer(invocation -> {
            body.set(readAllText(invocation.getArgument(0)));
            return null;
        }).when(mailSender).send(any(MimeMessage.class));

        mailer.sendResetEmail("owner@example.com", "Ben & Co", "tok-123", 60);

        // The HTML part still escapes it, so both forms are present; what must
        // NOT happen is the reader seeing "Ben &amp; Co" as their name.
        assertThat(body.get()).contains("Hi Ben & Co,");
    }

    private static String readAllText(MimeMessage message) {
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            message.writeTo(out);
            // Quoted-printable folds long lines; undo the folding so a URL can be
            // matched as one string.
            return out.toString(java.nio.charset.StandardCharsets.UTF_8)
                    .replace("=\r\n", "")
                    .replace("=\n", "")
                    .replace("=3D", "=")
                    .replace("=26", "&");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("two sends run CONCURRENTLY: a stalled relay must not serialise every reset behind it")
    void twoSendsRunConcurrently() throws Exception {
        CountDownLatch bothInside = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            bothInside.countDown();
            release.await(10, TimeUnit.SECONDS);
            return null;
        }).when(mailSender).send(any(MimeMessage.class));

        mailer.dispatchResetEmail("a@example.com", "A", TOKEN, 60, 1L);
        mailer.dispatchResetEmail("b@example.com", "B", TOKEN, 60, 2L);

        // With corePoolSize 0 this would deadlock the assertion: ThreadPoolExecutor
        // offers to the QUEUE before starting a second thread, so the pool would be
        // single-threaded until 64 messages were backed up.
        assertThat(bothInside.await(10, TimeUnit.SECONDS))
                .as("only one send was running at a time")
                .isTrue();
        release.countDown();
    }

    @Test
    @DisplayName("the unsent count tracks what is in flight, which is what a shutdown reports")
    void unsentCountTracksInFlight() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            inside.countDown();
            release.await(10, TimeUnit.SECONDS);
            return null;
        }).when(mailSender).send(any(MimeMessage.class));

        assertThat(mailer.unsentCount()).isZero();
        mailer.dispatchResetEmail("owner@example.com", "Ada", TOKEN, 60, 7L);
        assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();

        // In flight, not queued. shutdownNow() would not see this one, which is
        // why the shutdown line counts pending instead.
        assertThat(mailer.unsentCount()).isEqualTo(1);

        release.countDown();
        await(() -> mailer.unsentCount() == 0, "the in-flight send to finish");
    }

    @Test
    @DisplayName("the token is percent-encoded into the link, so a future alphabet cannot produce a "
            + "silently broken URL")
    void tokenIsUrlEncoded() {
        AtomicReference<String> body = new AtomicReference<>();
        doAnswer(invocation -> {
            body.set(readAllText(invocation.getArgument(0)));
            return null;
        }).when(mailSender).send(any(MimeMessage.class));

        // Base64url needs no encoding today, which is exactly why dropping the
        // encode() call broke nothing measurable. This pins it against the change
        // of alphabet its comment is there for.
        mailer.sendResetEmail("owner@example.com", "Ada", "a+b/c=d&e", 60);

        String sent = body.get();
        assertThat(sent).contains("token=a%2Bb%2Fc%3Dd%26e");
        assertThat(sent).doesNotContain("token=a+b/c=d&e");
    }

    private static void await(java.util.function.BooleanSupplier condition, String what) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(10);
        }
        throw new AssertionError("timed out waiting for " + what);
    }
}
