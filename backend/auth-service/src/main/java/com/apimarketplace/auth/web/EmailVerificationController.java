package com.apimarketplace.auth.web;

import com.apimarketplace.auth.domain.EmailVerificationCode;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.UserRepository;
import com.apimarketplace.auth.service.EmailVerificationService;
import com.apimarketplace.auth.service.UserResolutionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth/email")
@CrossOrigin(origins = "*")
public class EmailVerificationController {

    private static final Logger logger = LoggerFactory.getLogger(EmailVerificationController.class);

    private final EmailVerificationService emailVerificationService;
    private final UserRepository userRepository;
    private final UserResolutionService userResolutionService;

    public EmailVerificationController(EmailVerificationService emailVerificationService,
                                       UserRepository userRepository,
                                       UserResolutionService userResolutionService) {
        this.emailVerificationService = emailVerificationService;
        this.userRepository = userRepository;
        this.userResolutionService = userResolutionService;
    }

    @PostMapping("/send-code")
    public ResponseEntity<?> sendCode(@RequestHeader("X-User-ID") Long userId) {
        logger.info("POST /api/auth/email/send-code for userId={}", userId);
        try {
            User user = userRepository.findById(userId)
                    .orElseThrow(() -> new IllegalArgumentException("User not found"));

            if (!emailVerificationService.isEmailCodeFlowEnabled()) {
                return emailCodeFlowDisabledResponse(user);
            }

            EmailVerificationCode code = emailVerificationService.sendCode(user);

            return ResponseEntity.ok(Map.of(
                    "message", "Verification code sent",
                    "expiresAt", code.getExpiresAt().toString()
            ));
        } catch (EmailVerificationService.RateLimitException e) {
            return ResponseEntity.status(429).body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Error sending verification code for userId={}", userId, e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to send verification code"));
        }
    }

    @PostMapping("/verify-code")
    public ResponseEntity<?> verifyCode(@RequestHeader("X-User-ID") Long userId,
                                        @RequestBody Map<String, String> body) {
        logger.info("POST /api/auth/email/verify-code for userId={}", userId);
        try {
            User user = userRepository.findById(userId)
                    .orElseThrow(() -> new IllegalArgumentException("User not found"));

            if (!emailVerificationService.isEmailCodeFlowEnabled()) {
                return emailCodeFlowDisabledResponse(user);
            }

            String code = body.get("code");
            if (code == null || code.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Code is required"));
            }

            emailVerificationService.verifyCode(user, code.trim());

            // Attribute pending credits now that email is verified
            try {
                // Re-fetch user to get updated emailVerified flag
                User updatedUser = userRepository.findById(userId).orElse(user);
                userResolutionService.attributeCreditsIfEligible(updatedUser);
            } catch (Exception creditEx) {
                logger.warn("Failed to attribute credits after email verification for userId={}: {}",
                        userId, creditEx.getMessage());
            }

            return ResponseEntity.ok(Map.of("verified", true));
        } catch (EmailVerificationService.CodeExpiredException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "expired", "message", e.getMessage()));
        } catch (EmailVerificationService.TooManyAttemptsException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "too_many_attempts", "message", e.getMessage()));
        } catch (EmailVerificationService.InvalidCodeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid_code", "message", e.getMessage()));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Error verifying code for userId={}", userId, e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to verify code"));
        }
    }

    @GetMapping("/status")
    public ResponseEntity<?> getStatus(@RequestHeader("X-User-ID") Long userId) {
        logger.debug("GET /api/auth/email/status for userId={}", userId);
        try {
            User user = userRepository.findById(userId)
                    .orElseThrow(() -> new IllegalArgumentException("User not found"));

            boolean verified = emailVerificationService.isEmailVerified(user.getProviderId());

            return ResponseEntity.ok(Map.of("verified", verified));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Error checking email verification status for userId={}", userId, e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to check verification status"));
        }
    }

    private ResponseEntity<Map<String, Object>> emailCodeFlowDisabledResponse(User user) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("verified", user.isEmailVerified());

        if (user.isEmailVerified()) {
            body.put("message", "Email verification is not required in Community Edition");
            return ResponseEntity.ok(body);
        }

        body.put("error", "email_verification_disabled");
        body.put("message", "Email verification code flow is disabled in Community Edition");
        return ResponseEntity.status(HttpStatus.GONE).body(body);
    }
}
