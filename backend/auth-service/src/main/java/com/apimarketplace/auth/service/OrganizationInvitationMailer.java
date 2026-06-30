package com.apimarketplace.auth.service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

/**
 * Sends organization invitation emails. PR-3 MVP.
 *
 * <p>Pattern is intentionally a near-clone of
 * {@code EmailVerificationService.sendVerificationEmail} - same
 * {@link MimeMessage}/{@link MimeMessageHelper} stack, same inline HTML
 * with {@code String.replace} placeholders, same fault tolerance shape.
 * A future PR-3.1 will factor this and the verification mailer into a
 * common {@code MailerService} (Thymeleaf-backed) once the second use
 * case stabilises.
 *
 * <p>Since PR4 (Q2=a explicit consent), the mailer is called from
 * {@link OrganizationMemberService#inviteMember} for EVERY invitation -
 * both new-user and existing-user paths take the click-to-accept route.
 * The silent auto-accept branch was removed; users no longer end up in
 * organizations they didn't explicitly join. A send failure is logged +
 * swallowed - the invitation row stays {@code PENDING} for its 7-day TTL
 * and the user can accept via either the email link or the
 * /app/invitations inbox (PR4b).
 */
@Service
public class OrganizationInvitationMailer {

    private static final Logger logger = LoggerFactory.getLogger(OrganizationInvitationMailer.class);

    private final JavaMailSender mailSender;
    private final String mailFrom;
    private final String mailFromName;
    private final String frontendUrl;

    public OrganizationInvitationMailer(
            JavaMailSender mailSender,
            @Value("${app.mail.from:noreply@livecontext.ai}") String mailFrom,
            @Value("${app.mail.from-name:LiveContext}") String mailFromName,
            // Aligns with the actual key used everywhere else (EmailVerificationService:49,
            // OAuth2Controller:36, OAuth2Service:119, application.yml:112). The earlier
            // draft used "app.frontend.url" which is undefined → mail would have sent
            // localhost URLs in prod. P0 fix from PR-3 audit.
            @Value("${oauth2.frontend-url:http://localhost:3000}") String frontendUrl) {
        this.mailSender = mailSender;
        this.mailFrom = mailFrom;
        this.mailFromName = mailFromName;
        this.frontendUrl = frontendUrl;
    }

    /**
     * Send an invitation email. Best-effort: on failure we log and
     * swallow so the inviteMember business path always returns the
     * persisted invitation row to the caller.
     *
     * @param email       invitee email
     * @param orgName     organisation display name shown in the body
     * @param inviterName who sent the invite (display name)
     * @param token       opaque invitation token (path-param in the accept URL)
     * @param role        role being granted (display only - server still enforces it)
     */
    public void sendInvitationEmail(String email, String orgName, String inviterName,
                                    String token, String role) {
        try {
            String acceptUrl = frontendUrl + "/invitations/accept?token=" + token;
            String safeOrgName = sanitize(orgName);
            String safeInviter = sanitize(inviterName);
            String safeRole = sanitize(role);

            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(mailFrom, mailFromName);
            helper.setTo(email);
            helper.setSubject("You've been invited to join " + safeOrgName + " on LiveContext");

            String plain = String.format(
                    "%s invited you to join the organization '%s' on LiveContext as %s.%n%n"
                            + "Accept here: %s%n%n"
                            + "If you weren't expecting this invitation, you can safely ignore this email.%n%n"
                            + "- LiveContext",
                    safeInviter, safeOrgName, safeRole, acceptUrl);

            String html = buildInvitationHtml(safeOrgName, safeInviter, safeRole, acceptUrl);
            helper.setText(plain, html);
            mailSender.send(message);
            logger.info("Invitation email sent to {} for org {}", email, orgName);
        } catch (MessagingException | java.io.UnsupportedEncodingException e) {
            // Don't bubble - invitation row stays PENDING and the user can
            // accept via the /app/invitations inbox (PR4b) even without the
            // email. WARN with stack so ops can diagnose SMTP drift.
            logger.warn("Failed to send invitation email to {} for org {}", email, orgName, e);
        } catch (Exception e) {
            logger.warn("Unexpected error sending invitation email to {}", email, e);
        }
    }

    /**
     * Defensive HTML-escape for fields that flow from user input (orgName,
     * inviterName, role). Belt-and-suspenders - Thymeleaf would do this
     * automatically, but the current inline pattern requires it explicit.
     */
    private static String sanitize(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private String buildInvitationHtml(String orgName, String inviter, String role, String acceptUrl) {
        return """
                <!DOCTYPE html>
                <html lang="en"><head><meta charset="UTF-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <title>You're invited</title></head>
                <body style="margin:0;padding:0;">
                <span style="display:none!important;font-size:1px;line-height:1px;color:#ffffff;mso-hide:all;">You've been invited to join {{ORG}} on LiveContext</span>
                <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="background:#f5f5f4;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;color:#111827;">
                  <tr><td align="center" style="padding:40px 16px;">
                    <table role="presentation" width="560" cellpadding="0" cellspacing="0" border="0" style="max-width:560px;width:100%;background:#ffffff;border:1px solid #e7e5e4;border-radius:12px;">
                      <tr><td align="left" style="padding:32px 40px 24px 40px;border-bottom:1px solid #e7e5e4;">
                        <img src="{{LOGO}}" alt="LiveContext" height="32" style="display:block;height:32px;width:auto;border:0;text-decoration:none;">
                      </td></tr>
                      <tr><td style="padding:32px 40px;font-size:15px;line-height:1.6;color:#111827;">
                        <h1 style="margin:0 0 16px 0;font-size:22px;font-weight:600;color:#111827;">You're invited</h1>
                        <p style="margin:0 0 12px 0;"><strong>{{INVITER}}</strong> invited you to join the organization <strong>{{ORG}}</strong> on LiveContext as <strong>{{ROLE}}</strong>.</p>
                        <p style="margin:24px 0;text-align:center;">
                          <a href="{{URL}}" style="display:inline-block;padding:12px 28px;background:#111827;color:#ffffff;border-radius:8px;font-size:15px;font-weight:600;text-decoration:none;">Accept invitation</a>
                        </p>
                        <p style="margin:16px 0 0 0;font-size:13px;color:#6b7280;">If the button doesn't work, copy and paste this link:<br><span style="word-break:break-all;color:#374151;">{{URL}}</span></p>
                      </td></tr>
                      <tr><td style="padding:24px 40px 32px 40px;border-top:1px solid #e7e5e4;font-size:12px;line-height:1.5;color:#6b7280;">
                        If you weren't expecting this invitation, you can safely ignore this email - no account will be created.<br><br>&copy; LiveContext
                      </td></tr>
                    </table>
                  </td></tr>
                </table>
                </body></html>
                """
                .replace("{{ORG}}", orgName)
                .replace("{{INVITER}}", inviter)
                .replace("{{ROLE}}", role)
                .replace("{{URL}}", acceptUrl)
                .replace("{{LOGO}}", frontendUrl + "/liveContext-logo-light.png?v=2");
    }

}
