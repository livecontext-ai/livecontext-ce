package com.apimarketplace.auth.service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

/**
 * Sends the "someone tried to register your install" recovery email
 * (doc §1 #41, victim-UX deadlock closure).
 *
 * <p>Pattern mirrors {@code OrganizationInvitationMailer} - same
 * {@link MimeMessage} + {@link MimeMessageHelper} stack, same sanitize-and-
 * inline-HTML approach. Failures are logged + swallowed; the audit row
 * ({@code SUSPECTED_CROSS_USER_RESET}) is the durable record so ops can
 * notify the victim out-of-band if email is down.
 *
 * <p>The email body never mentions the attacker's identity (would be a
 * disclosure oracle: the squatter could trigger emails by attempting to
 * register the victim's install_id, then ask the victim what they saw).
 * The audit table holds {@code owned_by_user_id} for forensics; the email
 * holds only the recovery link.
 */
@Service
@ConditionalOnProperty(name = "auth.mode", havingValue = "keycloak", matchIfMissing = false)
public class SquatRecoveryMailer {

    private static final Logger log = LoggerFactory.getLogger(SquatRecoveryMailer.class);

    private final JavaMailSender mailSender;
    private final String mailFrom;
    private final String mailFromName;
    private final String frontendUrl;

    public SquatRecoveryMailer(
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
     * Sends the recovery email. {@code recoveryToken} is embedded in the
     * URL path-segment, not a query param - keeps the secret out of browser/CDN
     * log query-string surfaces.
     */
    public void sendRecoveryEmail(String victimEmail, String recoveryToken) {
        try {
            String recoveryUrl = recoveryUrl(frontendUrl, recoveryToken);
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(mailFrom, mailFromName);
            helper.setTo(victimEmail);
            helper.setSubject("Action needed: someone tried to link your LiveContext install");

            String plain = "Hi,\n\n"
                    + "Someone tried to register a CE install that's already linked to your LiveContext account.\n"
                    + "If this was you (you reinstalled, switched machines, or used a recovery image), no action is needed.\n\n"
                    + "If this was NOT you, click the link below to reset the link and force the other party out:\n"
                    + recoveryUrl + "\n\n"
                    + "The link expires in 60 minutes and can be used once.\n\n"
                    + "- LiveContext\n";

            String html = buildHtml(recoveryUrl);
            helper.setText(plain, html);
            mailSender.send(message);
            log.info("SquatRecovery email sent to {}", victimEmail);
        } catch (MessagingException | java.io.UnsupportedEncodingException e) {
            // Best-effort - audit row is the durable record. Don't fail the upstream caller.
            log.warn("Failed to send squat-recovery email to {}", victimEmail, e);
        } catch (Exception unexpected) {
            log.warn("Unexpected error sending squat-recovery email to {}", victimEmail, unexpected);
        }
    }

    /**
     * Builds the squat-recovery consume URL. Points at the unified Cloud page
     * ({@code /settings/cloud-account/recover/<token>}); the legacy
     * {@code /settings/cloud-link/recover/<token>} route still redirects here
     * so any in-flight email (60-min token TTL) keeps working. Package-private
     * so the path contract is unit-tested without mail plumbing.
     */
    static String recoveryUrl(String frontendUrl, String recoveryToken) {
        return frontendUrl + "/settings/cloud-account/recover/" + recoveryToken;
    }

    private String buildHtml(String recoveryUrl) {
        // Minimal inline HTML - no external CSS, autoescape-equivalent via no
        // user-controlled fields in the body (only the URL, controlled by us).
        return "<!DOCTYPE html><html><body style=\"font-family:sans-serif;line-height:1.5\">"
                + "<p>Hi,</p>"
                + "<p>Someone tried to register a CE install that's already linked to your "
                + "LiveContext account.</p>"
                + "<p>If this was you (you reinstalled, switched machines, or used a recovery image), "
                + "no action is needed.</p>"
                + "<p>If this was <strong>not</strong> you, click the button below to reset the link "
                + "and force the other party out. The link expires in 60 minutes and can be used once.</p>"
                + "<p style=\"margin:24px 0\"><a href=\"" + recoveryUrl + "\" "
                + "style=\"background:#2563eb;color:#fff;padding:10px 16px;text-decoration:none;border-radius:6px\">"
                + "Reset cloud link</a></p>"
                + "<p style=\"color:#666;font-size:12px\">- LiveContext</p>"
                + "</body></html>";
    }
}
