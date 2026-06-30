package com.apimarketplace.auth.web;

import com.apimarketplace.auth.dto.OnboardingRequest;
import com.apimarketplace.auth.dto.OnboardingResponse;
import com.apimarketplace.auth.service.OnboardingService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST controller for user onboarding.
 */
@RestController
@RequestMapping("/api/onboarding")
public class OnboardingController {

    private static final Logger log = LoggerFactory.getLogger(OnboardingController.class);

    private final OnboardingService onboardingService;

    public OnboardingController(OnboardingService onboardingService) {
        this.onboardingService = onboardingService;
    }

    /**
     * Get onboarding status for current user.
     * Uses X-User-ID header injected by Gateway.
     */
    @GetMapping("/status")
    public ResponseEntity<OnboardingResponse> getStatus(
            @RequestHeader(value = "X-Provider-ID", required = false) String providerId) {
        if (isMissingProviderId(providerId)) {
            return unauthorized();
        }
        log.info("📋 GET /api/onboarding/status for providerId: {}", providerId);

        OnboardingResponse response = onboardingService.getOnboardingStatus(providerId);
        return ResponseEntity.ok(response);
    }

    /**
     * Check if onboarding is needed.
     */
    @GetMapping("/needs")
    public ResponseEntity<Map<String, Boolean>> needsOnboarding(
            @RequestHeader(value = "X-Provider-ID", required = false) String providerId) {
        if (isMissingProviderId(providerId)) {
            return unauthorized();
        }
        log.info("📋 GET /api/onboarding/needs for providerId: {}", providerId);

        boolean needsOnboarding = onboardingService.needsOnboarding(providerId);
        return ResponseEntity.ok(Map.of("needsOnboarding", needsOnboarding));
    }

    /**
     * Save onboarding progress (partial save).
     */
    @PostMapping("/save")
    public ResponseEntity<OnboardingResponse> saveOnboarding(
            @RequestHeader(value = "X-Provider-ID", required = false) String providerId,
            @Valid @RequestBody OnboardingRequest request) {
        if (isMissingProviderId(providerId)) {
            return unauthorized();
        }
        log.info("💾 POST /api/onboarding/save for providerId: {}", providerId);

        try {
            OnboardingResponse response = onboardingService.saveOnboarding(providerId, request);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            log.warn("❌ Failed to save onboarding: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Complete onboarding.
     */
    @PostMapping("/complete")
    public ResponseEntity<OnboardingResponse> completeOnboarding(
            @RequestHeader(value = "X-Provider-ID", required = false) String providerId,
            @Valid @RequestBody OnboardingRequest request) {
        if (isMissingProviderId(providerId)) {
            return unauthorized();
        }
        log.info("✅ POST /api/onboarding/complete for providerId: {}", providerId);

        try {
            OnboardingResponse response = onboardingService.completeOnboarding(providerId, request);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            log.warn("❌ Failed to complete onboarding: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Skip onboarding (display name is still required).
     */
    @PostMapping("/skip")
    public ResponseEntity<OnboardingResponse> skipOnboarding(
            @RequestHeader(value = "X-Provider-ID", required = false) String providerId,
            @Valid @RequestBody OnboardingRequest request) {
        if (isMissingProviderId(providerId)) {
            return unauthorized();
        }
        log.info("⏭️ POST /api/onboarding/skip for providerId: {}", providerId);

        try {
            OnboardingResponse response = onboardingService.skipOnboarding(providerId, request.getDisplayName());
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            log.warn("❌ Failed to skip onboarding: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Check if display name is available.
     */
    @GetMapping("/check-display-name")
    public ResponseEntity<Map<String, Object>> checkDisplayName(
            @RequestHeader(value = "X-Provider-ID", required = false) String providerId,
            @RequestParam String displayName) {
        if (isMissingProviderId(providerId)) {
            return unauthorized();
        }
        log.info("🔍 GET /api/onboarding/check-display-name for displayName: {}", displayName);

        boolean available = onboardingService.isDisplayNameAvailable(displayName, providerId);

        return ResponseEntity.ok(Map.of(
                "displayName", displayName,
                "available", available,
                "message", available ? "Display name is available" : "Display name is already taken"
        ));
    }

    private static boolean isMissingProviderId(String providerId) {
        return providerId == null || providerId.isBlank();
    }

    private static <T> ResponseEntity<T> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }
}
