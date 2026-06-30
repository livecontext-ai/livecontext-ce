package com.apimarketplace.catalog.web;

import com.apimarketplace.catalog.domain.dto.ApiResponse;
import com.apimarketplace.catalog.service.CustomApiRegistrationService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Internal API controller for custom API registration.
 * Called by agent-service (via catalog-client or direct HTTP) to register
 * custom APIs on behalf of a user.
 */
@RestController
@RequestMapping("/api/internal/catalog/custom-apis")
@RequiredArgsConstructor
@Slf4j
public class InternalCustomApiController {

    private final CustomApiRegistrationService registrationService;

    /**
     * Register a new custom API.
     * Accepts the api-migrations JSON schema format.
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> registerApi(
            @RequestBody JsonNode apiJson,
            @RequestHeader(value = "X-User-ID", required = false) String userId) {
        try {
            if (userId == null || userId.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "X-User-ID header is required"));
            }

            ApiResponse response = registrationService.registerCustomApi(apiJson, userId);
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                    "success", true,
                    "apiId", response.id() != null ? response.id().toString() : "",
                    "apiName", response.apiName() != null ? response.apiName() : ""
            ));
        } catch (IllegalArgumentException e) {
            log.warn("Invalid custom API registration: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Error registering custom API: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Internal server error"));
        }
    }

    /**
     * Update an existing custom API.
     */
    @PutMapping("/{apiId}")
    public ResponseEntity<Map<String, Object>> updateApi(
            @PathVariable String apiId,
            @RequestBody JsonNode updates,
            @RequestHeader(value = "X-User-ID", required = false) String userId) {
        try {
            if (userId == null || userId.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "X-User-ID header is required"));
            }

            ApiResponse response = registrationService.updateCustomApi(apiId, updates, userId);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "apiId", response.id() != null ? response.id().toString() : "",
                    "apiName", response.apiName() != null ? response.apiName() : ""
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Error updating custom API: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Internal server error"));
        }
    }

    /**
     * List custom APIs for the current tenant.
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> listCustomApis(
            @RequestHeader(value = "X-User-ID", required = false) String userId) {
        if (userId == null || userId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "X-User-ID header is required"));
        }

        List<Map<String, Object>> apis = registrationService.listCustomApis(userId);
        return ResponseEntity.ok(Map.of(
                "apis", apis,
                "count", apis.size()
        ));
    }

    /**
     * Get full details of a single custom API (parity with the public controller).
     */
    @GetMapping("/{apiId}")
    public ResponseEntity<?> getCustomApi(
            @PathVariable String apiId,
            @RequestHeader(value = "X-User-ID", required = false) String userId) {
        if (userId == null || userId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "X-User-ID header is required"));
        }
        try {
            return ResponseEntity.ok(registrationService.getCustomApiDetails(apiId, userId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Error getting custom API {}: {}", apiId, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Internal server error"));
        }
    }

    /**
     * Delete a custom API (parity with the public controller).
     */
    @DeleteMapping("/{apiId}")
    public ResponseEntity<Map<String, Object>> deleteApi(
            @PathVariable String apiId,
            @RequestHeader(value = "X-User-ID", required = false) String userId) {
        if (userId == null || userId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "X-User-ID header is required"));
        }
        try {
            registrationService.deleteCustomApi(apiId, userId);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Error deleting custom API {}: {}", apiId, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Internal server error"));
        }
    }
}
