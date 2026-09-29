package com.apimarketplace.auth.service;

import jakarta.annotation.PreDestroy;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import com.apimarketplace.auth.service.mail.AccountMailCatalog;
import com.apimarketplace.auth.service.mail.BrandedMail;
import com.apimarketplace.auth.service.mail.MailLocaleResolver;
import com.apimarketplace.common.i18n.MessageCatalog;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * The password reset e-mail, for embedded (self-hosted) auth.
 *
 * <p>Follows {@code OrganizationInvitationMailer}: same MimeMessage stack, same
 * sanitise-and-inline-HTML approach, same {@code oauth2.frontend-url} key (the
 * one every other mailer here uses; {@code app.frontend.url} does not exist and
 * would have sent localhost links in production).
 *
 * <p>Three deliberate differences from the other mailers:
 *
 * <p><b>The send is DETACHED from the request thread</b>, via
 * {@link #dispatchResetEmail}. Every other mailer here sends inline, and for
 * this endpoint that is a measurable account-enumeration oracle: a known address
 * pays an SMTP round trip while an unknown one returns after a single SELECT, so
 * the response TIME answers the question the response BODY refuses to. Detaching
 * it also bounds the damage of an unresponsive relay, since
 * {@code /api/auth/forgot-password} is public and unauthenticated: a stalled send
 * occupies one of the two threads below instead of a request thread.
 *
 * <p><b>The failure is not swallowed HERE.</b> Every other mailer logs a warning
 * and returns from the send itself. {@link #sendResetEmail} throws instead, so
 * the failure is a fact at the point it happens, and the decision about what the
 * user is told is made one level up (it is: nothing, because a mail is only ever
 * attempted for an address that exists). The operator ERROR line is written by
 * the dispatcher.
 *
 * <p><b>The token is never logged.</b> Not at INFO, not at DEBUG, not inside an
 * exception message. A reset link is a bearer credential for the account, and a
 * log line is the one place a credential must never be.
 */
@Service
@ConditionalOnProperty(name = "auth.mode", havingValue = "embedded")
public class PasswordResetMailer {

    private static final Logger logger = LoggerFactory.getLogger(PasswordResetMailer.class);

    /**
     * How long a shutdown waits for queued sends.
     *
     * <p>Deliberately SHORTER than a worst-case send, and the reasoning needs
     * stating carefully because two earlier versions of this comment got it
     * wrong. It is NOT "three 10 s bounds, so about 30 s":
     * {@code mail.smtp.timeout} becomes {@code SO_TIMEOUT}, a PER-BLOCKING-READ
     * deadline rather than a budget for the conversation, so a relay that
     * trickles one byte every 9 s never trips it and a single send has no upper
     * bound at all. No drain window a container restart tolerates could
     * guarantee to outlast one. 20 s drains everything a healthy relay has
     * queued and gives up on a stalled one, which is why {@link #shutdown()}
     * REPORTS what it abandons instead of pretending to have flushed it.
     */
    private static final long SHUTDOWN_DRAIN_SECONDS = 20;

    /**
     * The window actually used, so a test can shorten it. Without a seam the
     * only way to observe the abandoned-mail report is to stall a send for the
     * full 20 s, and the report went untested for exactly that reason.
     */
    private long drainSeconds = SHUTDOWN_DRAIN_SECONDS;

    void setDrainSecondsForTest(long seconds) {
        this.drainSeconds = seconds;
    }

    /** Subject and body of this mail, in the six app locales. Shared with the other account mails. */
    static final MessageCatalog CATALOG = AccountMailCatalog.INSTANCE;

    /**
     * Resolves the reader's language from the address, when the caller could not supply it.
     *
     * <p>Injected through a SETTER rather than the constructor, and optional: this mailer is
     * constructed directly in several tests, and adding a fifth constructor argument would have
     * made every one of them compile against a collaborator they have no use for. A null resolver
     * means English, which is what this mail was before it was translated.
     */
    private MailLocaleResolver mailLocales;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setMailLocales(MailLocaleResolver mailLocales) {
        this.mailLocales = mailLocales;
    }

    private final JavaMailSender mailSender;
    private final String mailFrom;
    private final String mailFromName;
    private final String frontendUrl;

    /**
     * Its own pool, deliberately not a shared one.
     *
     * <p>Spring's {@code applicationTaskExecutor} is not available here: several
     * modules (orchestrator) declare their own {@code TaskExecutor} beans, so in
     * the CE monolith, which is the ONLY place this feature runs,
     * {@code TaskExecutionAutoConfiguration} backs off on
     * {@code @ConditionalOnMissingBean(Executor.class)} and an injection by type
     * would be ambiguous besides. A local pool also keeps a stalled SMTP relay
     * from consuming threads another feature needs.
     *
     * <p>Bounded on purpose, at 2 threads and 64 queued: the work is capped
     * anyway by the per-user rate limit, and an unbounded queue in front of an
     * unresponsive relay is just a slower way to run out of memory. A rejected
     * task is logged, never dropped in silence.
     *
     * <p>The core size is 2, not 0, and that is not cosmetic.
     * {@link ThreadPoolExecutor} offers to the QUEUE before it starts any thread
     * beyond the core, so a core of 0 with a 64-deep queue is effectively
     * single-threaded until 64 messages are backed up: one stalled relay would
     * serialise every following reset behind it. An earlier version of this pool
     * was written that way while this comment already claimed two threads.
     * {@code allowCoreThreadTimeOut} keeps them from lingering idle.
     */
    private final ThreadPoolExecutor sendPool = new ThreadPoolExecutor(
            2, 2, 60L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(64),
            runnable -> {
                Thread thread = new Thread(runnable, "pwreset-mail");
                // Daemon: a queued reset mail must never hold up a shutdown.
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.AbortPolicy());

    {
        sendPool.allowCoreThreadTimeOut(true);
    }

    /**
     * Submitted and not yet finished, so shutdown can report what it loses.
     *
     * <p>{@code shutdownNow()} returns only what is still QUEUED, so the message
     * actually being sent when the drain window expires was invisible: the ERROR
     * line that exists so no abandoned mail is silent reported 0 in exactly the
     * case where one was lost.
     */
    private final java.util.concurrent.atomic.AtomicInteger pending =
            new java.util.concurrent.atomic.AtomicInteger();

    public PasswordResetMailer(
            JavaMailSender mailSender,
            @Value("${app.mail.from:noreply@livecontext.ai}") String mailFrom,
            @Value("${app.mail.from-name:LiveContext}") String mailFromName,
            @Value("${oauth2.frontend-url:http://localhost:3000}") String frontendUrl) {
        this.mailSender = mailSender;
        this.mailFrom = mailFrom;
        this.mailFromName = mailFromName;
        this.frontendUrl = frontendUrl;
    }

    /**
     * Hands the send to the pool and returns immediately, so the caller's timing
     * does not depend on whether there was an address to send to.
     *
     * <p>The failure is reported to the OPERATOR and nowhere else. Telling the
     * requester would answer "does this address have an account", since a mail is
     * only ever attempted when it does.
     *
     * @param userId      only for the operator log line, never for the requester
     * @param knownLocale the account's stored language, when the caller already holds the row.
     *                    Null falls back to a lookup by address, which is all an address-only
     *                    caller can do - but it is a second query for something the caller
     *                    already has, and it answers English if the database is unreachable at
     *                    that moment. Passing what the caller already read avoids both, on the
     *                    one mail whose reader is locked out and cannot retry in another
     *                    language. (It is NOT about duplicate addresses: uk_users_email_unique
     *                    forbids two rows with the same non-null one.)
     */
    public void dispatchResetEmail(String email, String displayName, String rawToken,
                                   int ttlMinutes, Long userId, String knownLocale) {
        pending.incrementAndGet();
        try {
            sendPool.execute(() -> {
                try {
                    sendResetEmail(email, displayName, rawToken, ttlMinutes, knownLocale);
                } catch (MailDeliveryException e) {
                    logger.error("Password reset token issued for user {} but the e-mail could not "
                            + "be sent. The user is still locked out. Check SMTP.", userId, e);
                } catch (RuntimeException e) {
                    logger.error("Unexpected failure sending the password reset e-mail for user {}. "
                            + "The user is still locked out.", userId, e);
                } finally {
                    pending.decrementAndGet();
                }
            });
        } catch (RejectedExecutionException e) {
            pending.decrementAndGet();
            logger.error("Password reset mail could not be queued (pool saturated or shutting "
                    + "down); the token issued for user {} was never sent. The user is still "
                    + "locked out.", userId, e);
        }
    }

    /**
     * Runs right after {@code shutdownNow()} in {@link #shutdown()}. A no-op in production;
     * a test uses it to let the interrupted send finish (its finally decrements
     * {@code pending}) before shutdown goes on, which is the interleaving where a count
     * taken AFTER {@code shutdownNow()} reads 0 for a mail that was lost.
     */
    private Runnable afterShutdownNowForTest = () -> { };

    void setAfterShutdownNowForTest(Runnable hook) {
        this.afterShutdownNowForTest = hook;
    }

    /** Submitted and not yet finished. Package-private so the accounting is testable. */
    int unsentCount() {
        return pending.get();
    }

    /**
     * Drains what is queued, and says so loudly if it cannot.
     *
     * <p>The threads are daemons, so without this a restart drops every queued
     * mail with no log line at all: the tokens stay live in the table and the
     * people who asked for them stay locked out, with nothing anywhere saying
     * why. A rejected mail is already reported (see {@link #dispatchResetEmail});
     * an abandoned one has to be too.
     */
    @PreDestroy
    void shutdown() {
        sendPool.shutdown();
        try {
            if (sendPool.awaitTermination(drainSeconds, TimeUnit.SECONDS)) {
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // Counted BEFORE shutdownNow(): interrupting the in-flight send runs its
        // finally, which decrements pending, so a count taken after it raced that
        // thread and could read 0 for a mail that was lost (reported nothing in
        // exactly the case this line exists for). pending, not shutdownNow().size():
        // the latter counts only what never started, missing the one in flight.
        int abandoned = pending.get();
        sendPool.shutdownNow();
        afterShutdownNowForTest.run();
        if (abandoned > 0) {
            logger.error("Shutting down with {} password reset e-mail(s) unsent (queued or in "
                    + "flight). Those users hold a live token they may not have received and may "
                    + "still be locked out.", abandoned);
        }
    }

    /**
     * The one send. There used to be a four-argument overload delegating here with a null locale; it
     * had no caller left, and keeping it left the inline-send path this class forbids one call away.
     *
     * @param rawToken    the one-time token. Goes into the link and nowhere else.
     * @param knownLocale the account's stored language, or null to resolve it from the address
     * @throws MailDeliveryException so the dispatcher can log the truth
     */
    public void sendResetEmail(String email, String displayName, String rawToken, int ttlMinutes,
                               String knownLocale) {
        String locale = resolveLocale(email, knownLocale);
        // The link carries the account's LANGUAGE, like the mail around it.
        //
        // These routes live under `app/[locale]/`, and an unprefixed path is redirected by the
        // proxy, which picks the locale from the NEXT_LOCALE cookie or Accept-Language. So an
        // account whose stored language is French, opened on a browser that advertises English,
        // got a French mail whose only call to action landed on the English page - exactly the
        // cross-device case this feature exists for. `MessageCatalog.localizedPath` is what the
        // sibling NotificationMailer already uses, and it is a no-op for English.
        // The token is URL-encoded: base64url needs nothing today, but a future change of alphabet
        // must not silently produce a broken link. (This comment described the line below and had
        // drifted three statements above it, onto the locale resolution.)
        String resetUrl = frontendUrl
                + MessageCatalog.localizedPath(locale, "/reset-password")
                + "?token=" + URLEncoder.encode(rawToken, StandardCharsets.UTF_8);

        String title = CATALOG.text(locale, "reset.title");
        java.util.List<String> paragraphs = java.util.List.of(
                AccountMailCatalog.greeting(locale, displayName),
                CATALOG.text(locale, "reset.body"),
                CATALOG.text(locale, "reset.ignore"));
        BrandedMail.Button button = new BrandedMail.Button(resetUrl, CATALOG.text(locale, "reset.action"));
        // The pasteable link goes in the NOTE, which is where the sibling invitation mail puts the
        // identical string.
        //
        // A button is an <a> like any other: a client that strips HTML, a proxy that rewrites links,
        // or a person reading the text alternative is left with no way to reach the page, and this is
        // the one mail whose reader is already locked out and cannot ask for help from inside the
        // product. It had been lost in the shell extraction and was rebuilt into the FOOTER, glued to
        // the footer sentence by a space: two mails putting one string in two places, and a ~120
        // character URL with no break opportunity inside a 12px footer cell of a 560px table. The
        // note is its own paragraph and breaks (see BrandedMail).
        String note = CATALOG.text(locale, "reset.expiry",
                java.util.Map.of("minutes", String.valueOf(ttlMinutes)))
                + " " + CATALOG.text(locale, "reset.fallback", java.util.Map.of("url", resetUrl));
        BrandedMail.Footer footer = BrandedMail.Footer.of(CATALOG.text(locale, "common.footer"));

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(mailFrom, mailFromName);
            helper.setTo(email);
            helper.setSubject(BrandedMail.subject(CATALOG.text(locale, "reset.subject")));

            // The plain part shows the name as typed; the HTML part escapes it. That asymmetry is
            // deliberate and tested: a reader of the text alternative must not see "&amp;" where
            // their name has an ampersand, and a reader of the HTML one must not receive markup.
            // The sign-off goes in its own SLOT, not at the end of the paragraph list.
            //
            // As a paragraph it rendered above the button - and in the verification mail, between
            // "use the code below" and the code - because the shell emits paragraphs first. Five
            // mails read backwards for it, and nothing asserted block order.
            String signature = CATALOG.text(locale, "common.signature");
            helper.setText(
                    BrandedMail.plain(title, paragraphs, null, note, button, signature, footer),
                    BrandedMail.html(BrandedMail.logoUrl(frontendUrl), locale, null, title, paragraphs, null, note, button, signature, footer));
            mailSender.send(message);
            logger.info("Password reset email sent to {}", email);
        } catch (MessagingException | UnsupportedEncodingException e) {
            // No token in the message, and none in the cause we pass on.
            logger.error("Failed to send password reset email to {}", email, e);
            throw new MailDeliveryException("reset_mail_send_failed", e);
        } catch (RuntimeException e) {
            logger.error("Unexpected error sending password reset email to {}", email, e);
            throw new MailDeliveryException("reset_mail_send_failed", e);
        }
    }

    /**
     * The language this mail is written in: what the caller read off the account, else a lookup
     * by address, else English.
     *
     * <p>{@code mailLocales} is null only where nothing injected it (a directly constructed
     * instance). English then, rather than a NullPointerException inside a send that is already
     * detached from any request and whose failure nobody is waiting on.
     */
    private String resolveLocale(String email, String knownLocale) {
        return MailLocaleResolver.resolve(mailLocales, knownLocale, email);
    }

    /** Mail could not be handed to the SMTP server. The reset did not reach anyone. */
    public static class MailDeliveryException extends RuntimeException {
        public MailDeliveryException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
