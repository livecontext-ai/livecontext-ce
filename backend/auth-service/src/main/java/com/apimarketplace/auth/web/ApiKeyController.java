package com.apimarketplace.auth.web;

import com.apimarketplace.auth.dto.ApiKeyEntryResponse;
import com.apimarketplace.auth.dto.ApiKeyResponse;
import com.apimarketplace.auth.dto.CreateApiKeyRequest;
import com.apimarketplace.auth.dto.CreateApiKeyResponse;
import com.apimarketplace.auth.service.ApiKeyService;
import com.apimarketplace.auth.service.ApiKeyValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

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

    // ========== Named multi keys (V398) ==========

    /**
     * Lists the user's active named keys (hints only, never plaintext).
     */
    @GetMapping
    public ResponseEntity<List<ApiKeyEntryResponse>> listKeys(@RequestHeader("X-User-ID") Long userId) {
        log.debug("GET /api/auth/api-keys for userId: {}", userId);
        return ResponseEntity.ok(apiKeyService.listKeys(userId));
    }

    /**
     * Creates a named key with optional scopes. Returns plaintext once.
     */
    @PostMapping
    public ResponseEntity<?> createKey(@RequestHeader("X-User-ID") Long userId,
                                       @RequestBody CreateApiKeyRequest request) {
        log.info("POST /api/auth/api-keys for userId: {}", userId);
        try {
            CreateApiKeyResponse response = apiKeyService.createKey(userId, request.getName(), request.getScopes());
            return ResponseEntity.ok(response);
        } catch (ApiKeyValidationException e) {
            log.warn("API key creation rejected for userId {}: {}", userId, e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "Bad Request", "message", e.getMessage()));
        } catch (IllegalArgumentException e) {
            log.warn("User not found: {}", userId);
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Revokes a named key. The key must belong to the calling user.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> revokeKey(@RequestHeader("X-User-ID") Long userId,
                                          @PathVariable("id") UUID id) {
        log.info("DELETE /api/auth/api-keys/{} for userId: {}", id, userId);
        try {
            apiKeyService.revokeKey(userId, id);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            log.warn("API key not found for userId {}: {}", userId, id);
            return ResponseEntity.notFound().build();
        }
    }
}
