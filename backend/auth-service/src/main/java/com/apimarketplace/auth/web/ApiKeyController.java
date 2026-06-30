package com.apimarketplace.auth.web;

import com.apimarketplace.auth.dto.ApiKeyResponse;
import com.apimarketplace.auth.service.ApiKeyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller for API key management.
 * Thin controller: delegates all business logic to ApiKeyService.
 */
@RestController
@RequestMapping("/api/auth/api-keys")
@CrossOrigin(origins = "*")
public class ApiKeyController {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyController.class);

    private final ApiKeyService apiKeyService;

    public ApiKeyController(ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    /**
     * Returns current API key info (hint, quotas). Never returns plaintext.
     */
    @GetMapping("/current")
    public ResponseEntity<ApiKeyResponse> getCurrentKey(@RequestHeader("X-User-ID") Long userId) {
        log.debug("GET /api/auth/api-keys/current for userId: {}", userId);
        try {
            ApiKeyResponse response = apiKeyService.getCurrentKeyInfo(userId);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            log.warn("User not found: {}", userId);
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Regenerates the API key. Returns plaintext once.
     */
    @PostMapping("/regenerate")
    public ResponseEntity<ApiKeyResponse> regenerateKey(@RequestHeader("X-User-ID") Long userId) {
        log.info("POST /api/auth/api-keys/regenerate for userId: {}", userId);
        try {
            ApiKeyResponse response = apiKeyService.regenerateKey(userId);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            log.warn("User not found: {}", userId);
            return ResponseEntity.notFound().build();
        }
    }
}
