package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.UserRepository;
import com.apimarketplace.common.i18n.MessageCatalog;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Emails one product notification (a failed workflow, low credits, the daily
 * summary) that the orchestrator decided to send. The DECISION lives there
 * (preferences, plan, incident, daily cap); this class only owns the address and
 * the mailer, which is why it lives in auth-service.
 *
 * <p>Same inline {@link MimeMessageHelper} stack as the invitation mailer. Every
 * mail carries a "Manage notifications" link to the screen where that kind of
 * alert is switched off, so no recipient is ever left with only "mark as spam".
 *
 * <p>Written in the recipient's app locale (V527 {@code users.locale}, English when unset):
 * the orchestrator composes subject, lines and action path in that same locale; this class
 * localizes its own wrapper (footer, manage link, default button label) and points the
 * manage link and List-Unsubscribe at the locale's route.
 *
 * <p>Refuses rather than guesses: a deactivated account, or an address that was
 * never verified, is not mailed. A typo'd address that bounces costs the sender
 * reputation every other customer's mail depends on.
 */
@Service
public class NotificationMailer {

    private static final Logger logger = LoggerFactory.getLogger(NotificationMailer.class);
    private static final Pattern NUMERIC_ID = Pattern.compile("\\d+");
    static final String MANAGE_PATH = "/app/settings/overview?tab=notifications";
    static final MessageCatalog CATALOG = MessageCatalog.load("i18n/notification-mail");

    public enum Status { SENT, NO_ADDRESS, FAILED }

    public record Result(Status status, String detail) {}

    private final JavaMailSender mailSender;
    private final UserRepository userRepository;
    private final String mailFrom;
    private final String mailFromName;
    private final String frontendUrl;

    public NotificationMailer(JavaMailSender mailSender,
                              UserRepository userRepository,
                              @Value("${app.mail.from:noreply@livecontext.ai}") String mailFrom,
                              @Value("${app.mail.from-name:LiveContext}") String mailFromName,
                              @Value("${oauth2.frontend-url:http://localhost:3000}") String frontendUrl) {
        this.mailSender = mailSender;
        this.userRepository = userRepository;
        this.mailFrom = mailFrom;
        this.mailFromName = mailFromName;
        this.frontendUrl = frontendUrl.endsWith("/") ? frontendUrl.substring(0, frontendUrl.length() - 1) : frontendUrl;
    }

    /**
     * @param userId     the internal numeric id or the provider id, as X-User-ID carries it
     * @param actionPath an in-app path; anything not starting with a single {@code /}
     *                   is dropped, so this can never be turned into a link elsewhere
     */
    public Result send(String userId, String subject, List<String> lines, String actionPath, String actionLabel) {
        Optional<User> user = resolveUser(userId);
        if (user.isEmpty()) return new Result(Status.NO_ADDRESS, "unknown user");
        User u = user.get();
        if (u.getDeactivatedAt() != null) return new Result(Status.NO_ADDRESS, "account deactivated");
        if (u.getEmail() == null || u.getEmail().isBlank()) return new Result(Status.NO_ADDRESS, "no email");
        if (!u.isEmailVerified()) return new Result(Status.NO_ADDRESS, "email not verified");
        if (subject == null || subject.isBlank()) return new Result(Status.FAILED, "empty subject");

        String safeLines = lines == null ? "" : String.join("\n", lines);
        String actionUrl = isInAppPath(actionPath) ? frontendUrl + actionPath : null;
        String locale = MessageCatalog.normalizeLocale(u.getLocale());
        String manageUrl = frontendUrl + MessageCatalog.localizedPath(locale, MANAGE_PATH);
        String label = actionLabel != null && !actionLabel.isBlank()
                ? actionLabel : CATALOG.text(locale, "action.default");
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(mailFrom, mailFromName);
            helper.setTo(u.getEmail());
            helper.setSubject(oneLine(subject));
            // List-Unsubscribe (RFC 2369): mail clients show their own "unsubscribe" next to the
            // sender. It points at the settings screen, which asks the person to sign in; there is
            // no one-click (RFC 8058) endpoint, so no List-Unsubscribe-Post header is claimed.
            message.setHeader("List-Unsubscribe", "<" + manageUrl + ">");
            String plain = subject + "\n\n" + safeLines
                    + (actionUrl != null ? "\n\n" + label + ": " + actionUrl : "")
                    + "\n\n-\n" + CATALOG.text(locale, "footer.reason") + " "
                    + CATALOG.text(locale, "footer.manage") + ": " + manageUrl;
            helper.setText(plain, html(locale, subject, lines, actionUrl, label, manageUrl));
            mailSender.send(message);
            return new Result(Status.SENT, null);
        } catch (Exception e) {
            logger.warn("Notification email for user {} not sent: {}", u.getId(), e.getMessage());
            return new Result(Status.FAILED, e.getClass().getSimpleName());
        }
    }

    static boolean isInAppPath(String path) {
        return path != null && path.startsWith("/") && !path.startsWith("//") && !path.contains("\\")
                && path.chars().noneMatch(Character::isWhitespace);
    }

    private Optional<User> resolveUser(String id) {
        if (id == null || id.isBlank()) return Optional.empty();
        String trimmed = id.trim();
        if (NUMERIC_ID.matcher(trimmed).matches()) {
            try {
                Optional<User> byId = userRepository.findById(Long.parseLong(trimmed));
                if (byId.isPresent()) return byId;
            } catch (NumberFormatException ignored) {
                // Longer than a long: try it as a provider id.
            }
        }
        return userRepository.findByProviderId(trimmed);
    }

    private static String oneLine(String s) {
        return s.replaceAll("[\\r\\n]+", " ").trim();
    }

    static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private String html(String locale, String subject, List<String> lines, String actionUrl, String actionLabel,
                        String manageUrl) {
        StringBuilder body = new StringBuilder();
        if (lines != null) {
            for (String line : lines) {
                body.append("<p style=\"margin:0 0 10px 0;\">").append(escape(line)).append("</p>");
            }
        }
        String button = actionUrl == null ? "" : """
                <p style="margin:24px 0 0 0;">
                  <a href="{{URL}}" style="display:inline-block;padding:12px 24px;background:#111827;color:#ffffff;border-radius:8px;font-size:15px;font-weight:600;text-decoration:none;">{{LABEL}}</a>
                </p>
                """.replace("{{URL}}", escape(actionUrl))
                .replace("{{LABEL}}", escape(actionLabel));
        String template = """
                <!DOCTYPE html>
                <html lang="{{LANG}}"><head><meta charset="UTF-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <title>{{SUBJECT}}</title></head>
                <body style="margin:0;padding:0;">
                <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="background:#f5f5f4;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;color:#111827;">
                  <tr><td align="center" style="padding:40px 16px;">
                    <table role="presentation" width="560" cellpadding="0" cellspacing="0" border="0" style="max-width:560px;width:100%;background:#ffffff;border:1px solid #e7e5e4;border-radius:12px;">
                      <tr><td align="left" style="padding:32px 40px 24px 40px;border-bottom:1px solid #e7e5e4;">
                        <img src="{{LOGO}}" alt="LiveContext" height="32" style="display:block;height:32px;width:auto;border:0;text-decoration:none;">
                      </td></tr>
                      <tr><td style="padding:32px 40px;font-size:15px;line-height:1.6;color:#111827;">
                        <h1 style="margin:0 0 16px 0;font-size:20px;font-weight:600;color:#111827;">{{SUBJECT}}</h1>
                        {{BODY}}
                        {{BUTTON}}
                      </td></tr>
                      <tr><td style="padding:24px 40px 32px 40px;border-top:1px solid #e7e5e4;font-size:12px;line-height:1.5;color:#6b7280;">
                        {{REASON}} <a href="{{MANAGE}}" style="color:#374151;">{{MANAGE_LABEL}}</a><br><br>&copy; LiveContext
                      </td></tr>
                    </table>
                  </td></tr>
                </table>
                </body></html>
                """;
        // One pass over the TEMPLATE: a value is never scanned again, so a subject or a line that
        // happens to contain "{{BODY}}" stays literal text instead of being expanded.
        java.util.Map<String, String> values = java.util.Map.of(
                "SUBJECT", escape(subject),
                "BODY", body.toString(),
                "BUTTON", button,
                "MANAGE", escape(manageUrl),
                "MANAGE_LABEL", escape(CATALOG.text(locale, "footer.manage")),
                "REASON", escape(CATALOG.text(locale, "footer.reason")),
                "LANG", locale,
                "LOGO", frontendUrl + "/liveContext-logo-light.png?v=2");
        java.util.regex.Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(values.get(m.group(1))));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(SUBJECT|BODY|BUTTON|MANAGE_LABEL|MANAGE|LOGO|LANG|REASON)\\}\\}");
}
