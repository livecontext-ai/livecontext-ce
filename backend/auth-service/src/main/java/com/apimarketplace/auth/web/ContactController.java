package com.apimarketplace.auth.web;

import com.apimarketplace.auth.service.ContactService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Public endpoint for the marketing-site contact form ({@code livecontext.ai/contact}).
 * No JWT - must be added to the gateway's {@code PUBLIC_PATHS} allowlist.
 */
@RestController
@RequestMapping("/api/contact")
public class ContactController {

    private static final Logger log = LoggerFactory.getLogger(ContactController.class);

    private final ContactService contactService;

    public ContactController(ContactService contactService) {
        this.contactService = contactService;
    }

    @PostMapping
    public ResponseEntity<?> submit(@RequestBody Map<String, String> body, HttpServletRequest request) {
        String name = body.get("name");
        String email = body.get("email");
        String category = body.get("category");
        String message = body.get("message");
        String captchaToken = body.get("captchaToken");
        String remoteIp = clientIp(request);

        try {
            contactService.submit(name, email, category, message, captchaToken, remoteIp);
            return ResponseEntity.ok(Map.of("status", "sent"));
        } catch (ContactService.InvalidSubmissionException invalid) {
            return ResponseEntity.badRequest().body(Map.of("error", invalid.getMessage()));
        } catch (ContactService.CaptchaFailedException captcha) {
            return ResponseEntity.status(403).body(Map.of("error", captcha.getMessage()));
        } catch (Exception unexpected) {
            log.error("Contact form submission failed unexpectedly", unexpected);
            return ResponseEntity.internalServerError().body(Map.of("error", "submission_failed"));
        }
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return request.getRemoteAddr();
    }
}
