package com.apimarketplace.auth.service;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpEntity;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.io.UnsupportedEncodingException;
import java.util.regex.Pattern;

/**
 * Public-facing contact form handler.
 *
 * <p>The form lives at {@code /contact} on the marketing site. Submissions go through
 * Google reCAPTCHA v3 score validation before the email is sent - captcha is the only
 * abuse defense at this layer (Cloudflare provides edge rate-limiting).
 *
 * <p>Email is sent via the existing {@link JavaMailSender} bean (configured against
 * Resend SMTP in production). The recipient is fixed by {@code CONTACT_RECIPIENT_EMAIL},
 * the From is the platform's noreply address, and Reply-To is the submitter so a human
 * can reply directly from the inbox.
 */
@Service
public class ContactService {

    private static final Logger log = LoggerFactory.getLogger(ContactService.class);

    private static final String SITEVERIFY_URL = "https://www.google.com/recaptcha/api/siteverify";
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
    private static final int MAX_NAME_LENGTH = 200;
    private static final int MAX_MESSAGE_LENGTH = 5000;
    // reCAPTCHA v3 score threshold. Google recommends 0.5 as a starting point; below
    // that we treat the submission as bot traffic and refuse silently.
    private static final double SCORE_THRESHOLD = 0.5;

    private final JavaMailSender mailSender;
    private final RestTemplate restTemplate;

    @Value("${app.mail.from}")
    private String mailFrom;

    @Value("${app.mail.from-name}")
    private String mailFromName;

    @Value("${contact.recipient-email:contact@livecontext.ai}")
    private String recipientEmail;

    @Value("${recaptcha.secret-key:}")
    private String recaptchaSecret;

    public ContactService(JavaMailSender mailSender, RestTemplate restTemplate) {
        this.mailSender = mailSender;
        this.restTemplate = restTemplate;
    }

    public enum Category {
        SUPPORT, SECURITY, PRIVACY, PRESS, ABUSE, BUG, OTHER;

        static Category fromString(String raw) {
            if (raw == null || raw.isBlank()) return OTHER;
            try {
                return Category.valueOf(raw.trim().toUpperCase());
            } catch (IllegalArgumentException unknown) {
                return OTHER;
            }
        }

        String subjectTag() {
            return "[" + name() + "]";
        }
    }

    /**
     * Process a contact form submission. Throws {@link InvalidSubmissionException} on
     * validation failure (bad email, empty message, …) and {@link CaptchaFailedException}
     * when reCAPTCHA rejects the request - callers should map both to 4xx responses.
     */
    public void submit(String name, String email, String category, String message,
                       String captchaToken, String remoteIp) {
        validateInput(name, email, message);
        verifyCaptcha(captchaToken, remoteIp);
        Category cat = Category.fromString(category);
        sendEmail(name.trim(), email.trim(), cat, message.trim());
        log.info("Contact form submitted from email={} category={}", email, cat);
    }

    private void validateInput(String name, String email, String message) {
        if (name == null || name.isBlank() || name.length() > MAX_NAME_LENGTH) {
            throw new InvalidSubmissionException("invalid_name");
        }
        if (email == null || !EMAIL_PATTERN.matcher(email.trim()).matches()) {
            throw new InvalidSubmissionException("invalid_email");
        }
        if (message == null || message.isBlank() || message.length() > MAX_MESSAGE_LENGTH) {
            throw new InvalidSubmissionException("invalid_message");
        }
    }

    private void verifyCaptcha(String token, String remoteIp) {
        if (recaptchaSecret == null || recaptchaSecret.isBlank()) {
            // Fail closed: a missing secret would silently disable the gate. Refuse
            // submissions instead of leaking an unprotected form to production.
            log.error("recaptcha.secret-key is not configured - refusing contact submission");
            throw new CaptchaFailedException("captcha_misconfigured");
        }
        if (token == null || token.isBlank()) {
            throw new CaptchaFailedException("captcha_missing");
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("secret", recaptchaSecret);
        body.add("response", token);
        if (remoteIp != null && !remoteIp.isBlank()) {
            body.add("remoteip", remoteIp);
        }

        try {
            ResponseEntity<JsonNode> response = restTemplate.postForEntity(
                    SITEVERIFY_URL, new HttpEntity<>(body, headers), JsonNode.class);
            JsonNode payload = response.getBody();
            if (payload == null || !payload.path("success").asBoolean(false)) {
                log.warn("reCAPTCHA verification rejected: {}", payload);
                throw new CaptchaFailedException("captcha_rejected");
            }
            double score = payload.path("score").asDouble(0.0);
            if (score < SCORE_THRESHOLD) {
                log.warn("reCAPTCHA score below threshold: {} < {}", score, SCORE_THRESHOLD);
                throw new CaptchaFailedException("captcha_low_score");
            }
        } catch (CaptchaFailedException known) {
            throw known;
        } catch (Exception transientFailure) {
            log.error("reCAPTCHA siteverify call failed", transientFailure);
            // Treat siteverify outage as captcha failure rather than letting the form
            // through - a flaky Google endpoint must not become an open spam relay.
            throw new CaptchaFailedException("captcha_unavailable");
        }
    }

    private void sendEmail(String name, String email, Category category, String message) {
        try {
            MimeMessage mime = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, true, "UTF-8");
            helper.setFrom(mailFrom, mailFromName);
            helper.setTo(recipientEmail);
            helper.setReplyTo(email, name);
            helper.setSubject(category.subjectTag() + " Contact form - " + name);

            String plain = "New contact form submission\n\n"
                    + "Category: " + category.name() + "\n"
                    + "Name:     " + name + "\n"
                    + "Email:    " + email + "\n\n"
                    + "Message:\n"
                    + message + "\n\n"
                    + "- Sent via livecontext.ai/contact\n";

            String html = buildHtml(name, email, category, message);
            helper.setText(plain, html);
            mailSender.send(mime);
        } catch (MessagingException | UnsupportedEncodingException e) {
            log.error("Failed to send contact email from {} to {}", email, recipientEmail, e);
            throw new RuntimeException("mail_send_failed", e);
        }
    }

    private String buildHtml(String name, String email, Category category, String message) {
        String escapedMessage = htmlEscape(message).replace("\n", "<br>");
        return """
                <!DOCTYPE html><html lang="en"><head><meta charset="UTF-8"></head>
                <body style="margin:0;padding:0;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;background:#f5f5f4;color:#111827;">
                <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0">
                  <tr><td align="center" style="padding:40px 16px;">
                    <table role="presentation" width="560" cellpadding="0" cellspacing="0" border="0" style="max-width:560px;width:100%;background:#ffffff;border:1px solid #e7e5e4;border-radius:12px;">
                      <tr><td style="padding:32px 40px;font-size:15px;line-height:1.6;">
                        <h1 style="margin:0 0 16px 0;font-size:20px;font-weight:600;">{{TAG}} New contact form submission</h1>
                        <p style="margin:0 0 4px 0;"><strong>Name:</strong> {{NAME}}</p>
                        <p style="margin:0 0 4px 0;"><strong>Email:</strong> <a href="mailto:{{EMAIL}}" style="color:#2563eb;">{{EMAIL}}</a></p>
                        <p style="margin:0 0 16px 0;"><strong>Category:</strong> {{CATEGORY}}</p>
                        <div style="margin:16px 0;padding:16px;background:#f5f5f4;border:1px solid #e7e5e4;border-radius:8px;font-size:14px;">{{MESSAGE}}</div>
                        <p style="margin:16px 0 0 0;font-size:12px;color:#6b7280;">Reply directly to this email to respond - Reply-To is set to the submitter's address.</p>
                      </td></tr>
                    </table>
                  </td></tr>
                </table>
                </body></html>
                """
                .replace("{{TAG}}", category.subjectTag())
                .replace("{{NAME}}", htmlEscape(name))
                .replace("{{EMAIL}}", htmlEscape(email))
                .replace("{{CATEGORY}}", category.name())
                .replace("{{MESSAGE}}", escapedMessage);
    }

    private static String htmlEscape(String raw) {
        return raw.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    public static class InvalidSubmissionException extends RuntimeException {
        public InvalidSubmissionException(String message) { super(message); }
    }

    public static class CaptchaFailedException extends RuntimeException {
        public CaptchaFailedException(String message) { super(message); }
    }
}
