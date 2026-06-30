package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.EmailVerificationCode;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.EmailVerificationCodeRepository;
import com.apimarketplace.auth.repository.UserRepository;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Optional;

@Service
@Transactional
public class EmailVerificationService {

    private static final Logger logger = LoggerFactory.getLogger(EmailVerificationService.class);

    private static final int EXPIRY_MINUTES = 10;
    private static final int MAX_ATTEMPTS = 3;
    private static final int MAX_SENDS_PER_HOUR = 5;
    private static final int COOLDOWN_SECONDS = 60;

    private final EmailVerificationCodeRepository codeRepository;
    private final UserRepository userRepository;
    private final JavaMailSender mailSender;
    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${app.mail.from}")
    private String mailFrom;

    @Value("${app.mail.from-name}")
    private String mailFromName;

    @Value("${oauth2.frontend-url:http://localhost:3000}")
    private String frontendUrl;

    @Value("${auth.mode:keycloak}")
    private String authMode;

    @Value("${app.mail.console-fallback-enabled:false}")
    private boolean mailConsoleFallbackEnabled;

    /**
     * Optional Keycloak admin client. Absent in CE (auth.mode=embedded) via
     * {@code @ConditionalOnProperty} on {@link KeycloakAdminEmailVerifier}.
     * Callers must combine null-check with {@link #isEmbeddedAuth()} before use.
     */
    @Autowired(required = false)
    private KeycloakAdminEmailVerifier kcAdminVerifier;

    public EmailVerificationService(EmailVerificationCodeRepository codeRepository,
                                    UserRepository userRepository,
                                    JavaMailSender mailSender) {
        this.codeRepository = codeRepository;
        this.userRepository = userRepository;
        this.mailSender = mailSender;
    }

    /**
     * Send a 6-digit verification code to the user's email.
     */
    public EmailVerificationCode sendCode(User user) {
        assertEmailCodeFlowEnabled();

        String email = user.getEmail();
        if (email == null || email.isBlank()) {
            throw new IllegalStateException("User has no email address");
        }

        // Cooldown: reject if a code was sent less than 60 seconds ago
        Optional<EmailVerificationCode> lastCode = codeRepository
                .findTopByUserIdAndVerifiedFalseOrderByCreatedAtDesc(user.getId());
        if (lastCode.isPresent()) {
            long secondsSinceLastSend = java.time.Duration.between(
                    lastCode.get().getCreatedAt(), LocalDateTime.now()).getSeconds();
            if (secondsSinceLastSend < COOLDOWN_SECONDS) {
                throw new RateLimitException("Please wait " + (COOLDOWN_SECONDS - secondsSinceLastSend) + " seconds before requesting a new code.");
            }
        }

        // Rate limit: max 5 codes per hour
        long recentCount = codeRepository.countByEmailAndCreatedAtAfter(
                email, LocalDateTime.now().minusHours(1));
        if (recentCount >= MAX_SENDS_PER_HOUR) {
            throw new RateLimitException("Too many verification codes requested. Please wait before trying again.");
        }

        // Delete old unverified codes for this user
        codeRepository.deleteByUserIdAndVerifiedFalse(user.getId());

        // Generate 6-digit code
        String code = String.format("%06d", secureRandom.nextInt(100_000, 1_000_000));

        // Save entity
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(EXPIRY_MINUTES);
        EmailVerificationCode verificationCode = new EmailVerificationCode(
                user.getId(), email, code, expiresAt);
        verificationCode.setMaxAttempts(MAX_ATTEMPTS);
        codeRepository.save(verificationCode);

        // Send email
        sendVerificationEmail(email, code);

        logger.info("Verification code sent to user {} (email={})", user.getId(), email);
        return verificationCode;
    }

    /**
     * Verify the code entered by the user.
     * Email-code verification is a cloud-only flow. CE local accounts are
     * persisted as verified during registration and fail before code lookup.
     */
    public void verifyCode(User user, String code) {
        assertEmailCodeFlowEnabled();

        EmailVerificationCode verificationCode = codeRepository
                .findTopByUserIdAndVerifiedFalseOrderByCreatedAtDesc(user.getId())
                .orElseThrow(() -> new IllegalStateException("No verification code found. Please request a new one."));

        if (verificationCode.isExpired()) {
            throw new CodeExpiredException("Verification code has expired. Please request a new one.");
        }

        if (!verificationCode.hasAttemptsLeft()) {
            throw new TooManyAttemptsException("Too many failed attempts. Please request a new code.");
        }

        if (!verificationCode.getCode().equals(code)) {
            verificationCode.incrementAttempts();
            codeRepository.save(verificationCode);
            throw new InvalidCodeException("Invalid verification code. Please try again.");
        }

        // Code is correct
        verificationCode.setVerified(true);
        codeRepository.save(verificationCode);

        if (!isEmbeddedAuth() && kcAdminVerifier != null) {
            kcAdminVerifier.markEmailVerified(user.getProviderId());
        }

        // Update local User entity
        user.setEmailVerified(true);
        userRepository.save(user);

        logger.info("Email verified for user {} (email={})", user.getId(), user.getEmail());
    }

    /**
     * Check if user's email is verified.
     * In CE (embedded): reads the local {@code User} entity.
     * In cloud (keycloak): delegates to Keycloak admin REST.
     */
    @Transactional(readOnly = true)
    public boolean isEmailVerified(String providerId) {
        if (isEmbeddedAuth() || kcAdminVerifier == null) {
            return userRepository.findByProviderId(providerId)
                    .map(User::isEmailVerified)
                    .orElse(false);
        }
        return kcAdminVerifier.isEmailVerified(providerId);
    }

    public boolean isEmailCodeFlowEnabled() {
        return !isEmbeddedAuth();
    }

    private void sendVerificationEmail(String email, String code) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(mailFrom, mailFromName);
            helper.setTo(email);
            helper.setSubject("Welcome to LiveContext - your verification code");

            String plain = "Welcome to LiveContext!\n\n" +
                    "Your verification code is: " + code + "\n\n" +
                    "This code expires in " + EXPIRY_MINUTES + " minutes.\n\n" +
                    "If you did not request this code, please ignore this email.\n\n" +
                    "- LiveContext";

            String html = buildVerificationHtml(code);
            helper.setText(plain, html);
            mailSender.send(message);
        } catch (MessagingException | java.io.UnsupportedEncodingException e) {
            handleVerificationEmailFailure(email, code, e);
        } catch (RuntimeException e) {
            handleVerificationEmailFailure(email, code, e);
        }
    }

    private void handleVerificationEmailFailure(String email, String code, Exception failure) {
        if (mailConsoleFallbackEnabled) {
            logger.warn("Verification email delivery failed for {}; console fallback code={}; reason={}",
                    email, code, rootMessage(failure));
            return;
        }
        logger.error("Failed to send verification email to {}", email, failure);
        throw new RuntimeException("Failed to send verification email", failure);
    }

    private boolean isEmbeddedAuth() {
        return "embedded".equalsIgnoreCase(authMode);
    }

    private void assertEmailCodeFlowEnabled() {
        if (!isEmailCodeFlowEnabled()) {
            throw new EmailCodeFlowDisabledException("Email verification code flow is disabled in Community Edition");
        }
    }

    private String rootMessage(Throwable failure) {
        Throwable cursor = failure;
        while (cursor.getCause() != null) {
            cursor = cursor.getCause();
        }
        String message = cursor.getMessage();
        if (message == null || message.isBlank()) {
            message = failure.getMessage();
        }
        return message != null ? message : cursor.getClass().getSimpleName();
    }

    private String buildVerificationHtml(String code) {
        return """
                <!DOCTYPE html>
                <html lang="en"><head><meta charset="UTF-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <title>Welcome to LiveContext</title></head>
                <body style="margin:0;padding:0;">
                <span style="display:none!important;font-size:1px;line-height:1px;color:#ffffff;mso-hide:all;">Your LiveContext verification code</span>
                <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="background:#f5f5f4;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;color:#111827;">
                  <tr><td align="center" style="padding:40px 16px;">
                    <table role="presentation" width="560" cellpadding="0" cellspacing="0" border="0" style="max-width:560px;width:100%;background:#ffffff;border:1px solid #e7e5e4;border-radius:12px;">
                      <tr><td align="left" style="padding:32px 40px 24px 40px;border-bottom:1px solid #e7e5e4;">
                        <img src="{{LOGO}}" alt="LiveContext" height="32" style="display:block;height:32px;width:auto;border:0;text-decoration:none;">
                      </td></tr>
                      <tr><td style="padding:32px 40px;font-size:15px;line-height:1.6;color:#111827;">
                        <h1 style="margin:0 0 16px 0;font-size:22px;font-weight:600;color:#111827;">Welcome to LiveContext</h1>
                        <p style="margin:0 0 8px 0;">Thanks for signing up. Use the code below to verify your email address and finish creating your account.</p>
                        <div style="margin:24px 0;padding:20px;background:#f5f5f4;border:1px solid #e7e5e4;border-radius:8px;text-align:center;font-size:28px;font-weight:600;letter-spacing:6px;color:#111827;">{{CODE}}</div>
                        <p style="margin:0;font-size:13px;color:#6b7280;">This code expires in {{MINUTES}} minutes. If you didn't request it, you can safely ignore this email.</p>
                      </td></tr>
                      <tr><td style="padding:24px 40px 32px 40px;border-top:1px solid #e7e5e4;font-size:12px;line-height:1.5;color:#6b7280;">
                        You are receiving this email because someone signed up for a LiveContext account with this address.<br><br>&copy; LiveContext
                      </td></tr>
                    </table>
                  </td></tr>
                </table>
                </body></html>
                """
                .replace("{{CODE}}", code)
                .replace("{{MINUTES}}", String.valueOf(EXPIRY_MINUTES))
                .replace("{{LOGO}}", frontendUrl + "/liveContext-logo-light.png?v=2");
    }

    // Custom exception classes

    public static class RateLimitException extends RuntimeException {
        public RateLimitException(String message) { super(message); }
    }

    public static class CodeExpiredException extends RuntimeException {
        public CodeExpiredException(String message) { super(message); }
    }

    public static class TooManyAttemptsException extends RuntimeException {
        public TooManyAttemptsException(String message) { super(message); }
    }

    public static class InvalidCodeException extends RuntimeException {
        public InvalidCodeException(String message) { super(message); }
    }

    public static class EmailCodeFlowDisabledException extends RuntimeException {
        public EmailCodeFlowDisabledException(String message) { super(message); }
    }
}
