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
 * User-facing API controller for custom API registration.
 * Exposed via gateway at /api/catalog/custom-apis.
 * Gateway injects X-User-ID from JWT automatically.
 */
@RestController
@RequestMapping("/api/catalog/custom-apis")
@RequiredArgsConstructor
@Slf4j
public class CustomApiController {

    private final CustomApiRegistrationService registrationService;

    @PostMapping
    public ResponseEntity<Map<String, Object>> registerApi(
            @RequestBody JsonNode apiJson,
            @RequestHeader(value = "X-User-ID", required = false) String userId) {
        try {
            if (userId == null || userId.isBlank()) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("error", "Authentication required"));
            }

            ApiResponse response = registrationService.registerCustomApi(apiJson, userId);
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                    "success", true,
                    "apiId", response.id() != null ? response.id().toString() : "",
                    "apiName", response.apiName() != null ? response.apiName() : ""
            ));
        } catch (IllegalArgumentException e) {
            log.warn("Invalid custom API registration from user {}: {}", userId, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Error registering custom API for user {}: {}", userId, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Internal server error"));
        }
    }

    @PutMapping("/{apiId}")
    public ResponseEntity<Map<String, Object>> updateApi(
            @PathVariable String apiId,
            @RequestBody JsonNode updates,
            @RequestHeader(value = "X-User-ID", required = false) String userId) {
        try {
            if (userId == null || userId.isBlank()) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("error", "Authentication required"));
            }

            ApiResponse response = registrationService.updateCustomApi(apiId, updates, userId);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "apiId", response.id() != null ? response.id().toString() : "",
                    "apiName", response.apiName() != null ? response.apiName() : "",
                    "warning", "API ID has changed. Workflows referencing old tool IDs must be updated."
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Error updating custom API {} for user {}: {}", apiId, userId, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Internal server error"));
        }
    }

    @GetMapping("/{apiId}")
    public ResponseEntity<?> getCustomApi(
            @PathVariable String apiId,
            @RequestHeader(value = "X-User-ID", required = false) String userId) {
        if (userId == null || userId.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Authentication required"));
        }

        try {
            var details = registrationService.getCustomApiDetails(apiId, userId);
            return ResponseEntity.ok(details);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Error getting custom API {} for user {}: {}", apiId, userId, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Internal server error"));
        }
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> listCustomApis(
            @RequestHeader(value = "X-User-ID", required = false) String userId) {
        if (userId == null || userId.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Authentication required"));
        }

        List<Map<String, Object>> apis = registrationService.listCustomApis(userId);
        return ResponseEntity.ok(Map.of(
                "apis", apis,
                "count", apis.size()
        ));
    }

    @DeleteMapping("/{apiId}")
    public ResponseEntity<Map<String, Object>> deleteApi(
            @PathVariable String apiId,
            @RequestHeader(value = "X-User-ID", required = false) String userId) {
        if (userId == null || userId.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Authentication required"));
        }

        try {
            registrationService.deleteCustomApi(apiId, userId);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Error deleting custom API {} for user {}: {}", apiId, userId, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Internal server error"));
        }
    }
}
