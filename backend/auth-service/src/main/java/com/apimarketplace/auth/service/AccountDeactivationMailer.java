package com.apimarketplace.auth.service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

@Service
public class AccountDeactivationMailer {

    private static final Logger logger = LoggerFactory.getLogger(AccountDeactivationMailer.class);

    private final JavaMailSender mailSender;
    private final String mailFrom;
    private final String mailFromName;
    private final String frontendUrl;

    public AccountDeactivationMailer(
            JavaMailSender mailSender,
            @Value("${app.mail.from:noreply@livecontext.ai}") String mailFrom,
            @Value("${app.mail.from-name:LiveContext}") String mailFromName,
            @Value("${oauth2.frontend-url:http://localhost:3000}") String frontendUrl) {
        this.mailSender = mailSender;
        this.mailFrom = mailFrom;
        this.mailFromName = mailFromName;
        this.frontendUrl = frontendUrl;
    }

    public void sendDeactivationEmail(String email, String displayName) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(mailFrom, mailFromName);
            helper.setTo(email);
            helper.setSubject("Your LiveContext account has been deactivated");

            String safeName = sanitize(displayName != null ? displayName : "there");

            String plain = String.format(
                    "Hi %s,%n%n"
                            + "Your LiveContext account has been deactivated as requested.%n%n"
                            + "Your data will be retained for 30 days. After that, all your data "
                            + "(workflows, agents, conversations, files, credentials, and publications) "
                            + "will be permanently deleted.%n%n"
                            + "If you change your mind, contact us at support@livecontext.ai within "
                            + "30 days to reactivate your account.%n%n"
                            + "- The LiveContext Team",
                    safeName);

            String html = buildHtml(safeName);
            helper.setText(plain, html);
            mailSender.send(message);
            logger.info("Account deactivation email sent to {}", email);
        } catch (MessagingException | java.io.UnsupportedEncodingException e) {
            logger.warn("Failed to send deactivation email to {}", email, e);
        } catch (Exception e) {
            logger.warn("Unexpected error sending deactivation email to {}", email, e);
        }
    }

    public void sendPurgeConfirmationEmail(String email, String displayName) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(mailFrom, mailFromName);
            helper.setTo(email);
            helper.setSubject("Your LiveContext data has been permanently deleted");

            String safeName = sanitize(displayName != null ? displayName : "there");

            String plain = String.format(
                    "Hi %s,%n%n"
                            + "The 30-day grace period for your LiveContext account has ended.%n%n"
                            + "All your data - workflows, agents, conversations, files, credentials, "
                            + "and marketplace publications - has been permanently deleted.%n%n"
                            + "If you'd like to use LiveContext again in the future, you're welcome "
                            + "to create a new account at any time.%n%n"
                            + "- The LiveContext Team",
                    safeName);

            String html = buildPurgeHtml(safeName);
            helper.setText(plain, html);
            mailSender.send(message);
            logger.info("Account purge confirmation email sent to {}", email);
        } catch (MessagingException | java.io.UnsupportedEncodingException e) {
            logger.warn("Failed to send purge confirmation email to {}", email, e);
        } catch (Exception e) {
            logger.warn("Unexpected error sending purge confirmation email to {}", email, e);
        }
    }

    private static String sanitize(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private String buildHtml(String name) {
        return """
                <!DOCTYPE html>
                <html lang="en"><head><meta charset="UTF-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <title>Account deactivated</title></head>
                <body style="margin:0;padding:0;">
                <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="background:#f5f5f4;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;color:#111827;">
                  <tr><td align="center" style="padding:40px 16px;">
                    <table role="presentation" width="560" cellpadding="0" cellspacing="0" border="0" style="max-width:560px;width:100%;background:#ffffff;border:1px solid #e7e5e4;border-radius:12px;">
                      <tr><td align="left" style="padding:32px 40px 24px 40px;border-bottom:1px solid #e7e5e4;">
                        <img src="{{LOGO}}" alt="LiveContext" height="32" style="display:block;height:32px;width:auto;border:0;text-decoration:none;">
                      </td></tr>
                      <tr><td style="padding:32px 40px;font-size:15px;line-height:1.6;color:#111827;">
                        <h1 style="margin:0 0 16px 0;font-size:22px;font-weight:600;color:#111827;">Account deactivated</h1>
                        <p style="margin:0 0 12px 0;">Hi <strong>{{NAME}}</strong>,</p>
                        <p style="margin:0 0 12px 0;">Your LiveContext account has been deactivated as requested.</p>
                        <div style="margin:20px 0;padding:16px 20px;background:#fef3c7;border:1px solid #f59e0b;border-radius:8px;">
                          <p style="margin:0;font-size:14px;color:#92400e;font-weight:600;">&#9888; Your data will be retained for 30 days.</p>
                          <p style="margin:8px 0 0 0;font-size:13px;color:#92400e;">After this period, all your data - workflows, agents, conversations, files, credentials, and marketplace publications - will be <strong>permanently deleted</strong>.</p>
                        </div>
                        <p style="margin:16px 0 0 0;">Changed your mind? Contact us at <a href="mailto:support@livecontext.ai" style="color:#2563eb;text-decoration:underline;">support@livecontext.ai</a> within 30 days to reactivate your account.</p>
                      </td></tr>
                      <tr><td style="padding:24px 40px 32px 40px;border-top:1px solid #e7e5e4;font-size:12px;line-height:1.5;color:#6b7280;">
                        We're sorry to see you go. Thank you for using LiveContext.<br><br>&copy; LiveContext
                      </td></tr>
                    </table>
                  </td></tr>
                </table>
                </body></html>
                """
                .replace("{{NAME}}", name)
                .replace("{{LOGO}}", frontendUrl + "/liveContext-logo-light.png?v=2");
    }

    private String buildPurgeHtml(String name) {
        return """
                <!DOCTYPE html>
                <html lang="en"><head><meta charset="UTF-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <title>Data permanently deleted</title></head>
                <body style="margin:0;padding:0;">
                <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="background:#f5f5f4;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;color:#111827;">
                  <tr><td align="center" style="padding:40px 16px;">
                    <table role="presentation" width="560" cellpadding="0" cellspacing="0" border="0" style="max-width:560px;width:100%;background:#ffffff;border:1px solid #e7e5e4;border-radius:12px;">
                      <tr><td align="left" style="padding:32px 40px 24px 40px;border-bottom:1px solid #e7e5e4;">
                        <img src="{{LOGO}}" alt="LiveContext" height="32" style="display:block;height:32px;width:auto;border:0;text-decoration:none;">
                      </td></tr>
                      <tr><td style="padding:32px 40px;font-size:15px;line-height:1.6;color:#111827;">
                        <h1 style="margin:0 0 16px 0;font-size:22px;font-weight:600;color:#111827;">Your data has been deleted</h1>
                        <p style="margin:0 0 12px 0;">Hi <strong>{{NAME}}</strong>,</p>
                        <p style="margin:0 0 12px 0;">The 30-day grace period for your LiveContext account has ended. All your data has been <strong>permanently deleted</strong>, including:</p>
                        <ul style="margin:12px 0;padding-left:20px;font-size:14px;color:#374151;">
                          <li style="margin-bottom:6px;">Workflows &amp; workflow runs</li>
                          <li style="margin-bottom:6px;">Agents &amp; conversations</li>
                          <li style="margin-bottom:6px;">Interfaces &amp; data sources</li>
                          <li style="margin-bottom:6px;">Files &amp; storage</li>
                          <li style="margin-bottom:6px;">API credentials</li>
                          <li style="margin-bottom:0;">Marketplace publications</li>
                        </ul>
                        <p style="margin:16px 0 0 0;">If you'd like to use LiveContext again in the future, you're welcome to create a new account at any time.</p>
                        <p style="margin:24px 0;text-align:center;">
                          <a href="{{URL}}" style="display:inline-block;padding:12px 28px;background:#111827;color:#ffffff;border-radius:8px;font-size:15px;font-weight:600;text-decoration:none;">Create a new account</a>
                        </p>
                      </td></tr>
                      <tr><td style="padding:24px 40px 32px 40px;border-top:1px solid #e7e5e4;font-size:12px;line-height:1.5;color:#6b7280;">
                        Thank you for having used LiveContext. We hope to see you again.<br><br>&copy; LiveContext
                      </td></tr>
                    </table>
                  </td></tr>
                </table>
                </body></html>
                """
                .replace("{{NAME}}", name)
                .replace("{{URL}}", frontendUrl)
                .replace("{{LOGO}}", frontendUrl + "/liveContext-logo-light.png?v=2");
    }
}
