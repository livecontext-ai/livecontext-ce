package com.apimarketplace.auth.service;

import jakarta.annotation.PreDestroy;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
     * @param userId only for the operator log line, never for the requester
     */
    public void dispatchResetEmail(String email, String displayName, String rawToken,
                                   int ttlMinutes, Long userId) {
        pending.incrementAndGet();
        try {
            sendPool.execute(() -> {
                try {
                    sendResetEmail(email, displayName, rawToken, ttlMinutes);
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
        sendPool.shutdownNow();
        // pending, not shutdownNow().size(): the latter counts only what never
        // started, so a message in flight at this moment would go unreported.
        int abandoned = pending.get();
        if (abandoned > 0) {
            logger.error("Shutting down with {} password reset e-mail(s) unsent (queued or in "
                    + "flight). Those users hold a live token they never received and are still "
                    + "locked out.", abandoned);
        }
    }

    /**
     * @param rawToken the one-time token. Goes into the link and nowhere else.
     * @throws MailDeliveryException so the dispatcher can log the truth
     */
    public void sendResetEmail(String email, String displayName, String rawToken, int ttlMinutes) {
        // URL-encode: the token is base64url, so today it needs nothing, but a
        // future change of alphabet must not silently produce a broken link.
        String resetUrl = frontendUrl + "/reset-password?token="
                + URLEncoder.encode(rawToken, StandardCharsets.UTF_8);
        String name = displayName != null ? displayName : "there";
        // Escaped for the HTML part ONLY: the plain-text part must not show the
        // reader "&amp;" where their name has an ampersand.
        String safeName = sanitize(name);

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(mailFrom, mailFromName);
            helper.setTo(email);
            helper.setSubject("Reset your LiveContext password");

            String plain = String.format(
                    "Hi %s,%n%n"
                            + "Someone asked to reset the password for this LiveContext account.%n%n"
                            + "Open this link to choose a new one. It works once and expires in "
                            + "%d minutes:%n%n%s%n%n"
                            + "If you did not ask for this, you can ignore this e-mail. Your "
                            + "password has not changed and nobody has been given access.%n%n"
                            + "- The LiveContext Team",
                    name, ttlMinutes, resetUrl);

            helper.setText(plain, buildHtml(safeName, resetUrl, ttlMinutes));
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

    private static String sanitize(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private String buildHtml(String name, String resetUrl, int ttlMinutes) {
        return """
                <!DOCTYPE html>
                <html lang="en"><head><meta charset="UTF-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <title>Reset your password</title></head>
                <body style="margin:0;padding:0;">
                <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="background:#f5f5f4;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;color:#111827;">
                  <tr><td align="center" style="padding:40px 16px;">
                    <table role="presentation" width="560" cellpadding="0" cellspacing="0" border="0" style="max-width:560px;width:100%;background:#ffffff;border:1px solid #e7e5e4;border-radius:12px;">
                      <tr><td align="left" style="padding:32px 40px 24px 40px;border-bottom:1px solid #e7e5e4;">
                        <img src="{{LOGO}}" alt="LiveContext" height="32" style="display:block;height:32px;width:auto;border:0;text-decoration:none;">
                      </td></tr>
                      <tr><td style="padding:32px 40px;font-size:15px;line-height:1.6;color:#111827;">
                        <h1 style="margin:0 0 16px 0;font-size:22px;font-weight:600;color:#111827;">Reset your password</h1>
                        <p style="margin:0 0 12px 0;">Hi <strong>{{NAME}}</strong>,</p>
                        <p style="margin:0 0 12px 0;">Someone asked to reset the password for this LiveContext account. Choose a new one here:</p>
                        <p style="margin:24px 0;text-align:center;">
                          <a href="{{URL}}" style="display:inline-block;padding:12px 28px;background:#111827;color:#ffffff;border-radius:8px;font-size:15px;font-weight:600;text-decoration:none;">Choose a new password</a>
                        </p>
                        <p style="margin:0 0 12px 0;font-size:13px;color:#6b7280;">This link works once and expires in {{TTL}} minutes.</p>
                        <div style="margin:20px 0;padding:16px 20px;background:#f5f5f4;border:1px solid #e7e5e4;border-radius:8px;">
                          <p style="margin:0;font-size:13px;color:#374151;">Did not ask for this? Ignore this e-mail. Your password has not changed and nobody has been given access.</p>
                        </div>
                      </td></tr>
                      <tr><td style="padding:24px 40px 32px 40px;border-top:1px solid #e7e5e4;font-size:12px;line-height:1.5;color:#6b7280;">
                        If the button does not work, paste this into your browser:<br>
                        <span style="word-break:break-all;color:#374151;">{{URL}}</span><br><br>&copy; LiveContext
                      </td></tr>
                    </table>
                  </td></tr>
                </table>
                </body></html>
                """
                // Placeholders the TEMPLATE owns go first. Substituting the
                // user-supplied name first would let a display name containing the
                // literal {{URL}} expand in that user's own mail.
                .replace("{{TTL}}", String.valueOf(ttlMinutes))
                .replace("{{URL}}", resetUrl)
                .replace("{{LOGO}}", frontendUrl + "/liveContext-logo-light.png?v=2")
                .replace("{{NAME}}", name);
    }

    /** Mail could not be handed to the SMTP server. The reset did not reach anyone. */
    public static class MailDeliveryException extends RuntimeException {
        public MailDeliveryException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
