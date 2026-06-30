package com.apimarketplace.publication.controller;

import com.apimarketplace.publication.domain.SharedLinkEntity;
import com.apimarketplace.publication.dto.SharedLinkCheckResponse;
import com.apimarketplace.publication.dto.SharedLinkConfigResponse;
import com.apimarketplace.publication.dto.SharedLinkResponse;
import com.apimarketplace.publication.service.SharedLinkService;
import com.apimarketplace.publication.service.SharedLinkService.SharedLinkLimitException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Authenticated CRUD for shared links management.
 * Gateway routes /api/publications/** → publication-service.
 */
@RestController
@RequestMapping("/api/publications/shared-links")
public class SharedLinkController {

    private static final Logger logger = LoggerFactory.getLogger(SharedLinkController.class);
    private static final int MAX_TITLE_LENGTH = 256;
    private static final int MAX_DESCRIPTION_LENGTH = 2000;

    private final SharedLinkService sharedLinkService;

    public SharedLinkController(SharedLinkService sharedLinkService) {
        this.sharedLinkService = sharedLinkService;
    }

    @GetMapping("/config")
    public ResponseEntity<SharedLinkConfigResponse> getConfig(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
            @RequestHeader(value = "X-User-Plan", required = false) String userPlan,
            @RequestParam(required = false) String resourceType) {
        SharedLinkConfigResponse config = sharedLinkService.getConfig(tenantId, organizationId, userPlan, resourceType);
        return ResponseEntity.ok(config);
    }

    @GetMapping("/check")
    public ResponseEntity<SharedLinkCheckResponse> check(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
            @RequestHeader(value = "X-User-Plan", required = false) String userPlan,
            @RequestParam String resourceToken,
            @RequestParam(required = false) String resourceId) {
        UUID resId = resourceId != null ? UUID.fromString(resourceId) : null;
        SharedLinkCheckResponse result = sharedLinkService.checkLink(tenantId, organizationId, resourceToken, resId, userPlan);
        return ResponseEntity.ok(result);
    }

    @GetMapping
    public ResponseEntity<List<SharedLinkResponse>> listAll(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
            @RequestParam(required = false) String resourceType) {
        List<SharedLinkEntity> links;
        if (resourceType != null && !resourceType.isBlank()) {
            try {
                links = sharedLinkService.getByScopeAndType(tenantId, organizationId,
                        SharedLinkEntity.ResourceType.valueOf(resourceType.toUpperCase()));
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().build();
            }
        } else {
            links = sharedLinkService.getByScope(tenantId, organizationId);
        }
        return ResponseEntity.ok(links.stream().map(SharedLinkResponse::from).toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getById(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
            @PathVariable UUID id) {
        try {
            return sharedLinkService.getByIdAndScope(id, tenantId, organizationId)
                    .map(entity -> ResponseEntity.ok((Object) SharedLinkResponse.from(entity)))
                    .orElse(ResponseEntity.notFound().build());
        } catch (Exception e) {
            logger.warn("Error getting shared link {}: {}", id, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to get shared link"));
        }
    }

    @PostMapping
    public ResponseEntity<?> create(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
            @RequestHeader(value = "X-User-Plan", required = false) String userPlan,
            @RequestBody Map<String, Object> body) {
        try {
            String resourceType = (String) body.get("resourceType");
            String resourceToken = (String) body.get("resourceToken");
            UUID resourceId = body.get("resourceId") != null
                    ? UUID.fromString(body.get("resourceId").toString()) : null;
            String title = (String) body.get("title");
            String description = (String) body.get("description");

            if (resourceType == null || resourceToken == null) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "resourceType and resourceToken are required"));
            }

            // Input validation
            if (title != null && title.length() > MAX_TITLE_LENGTH) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Title must be at most " + MAX_TITLE_LENGTH + " characters"));
            }
            if (description != null && description.length() > MAX_DESCRIPTION_LENGTH) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Description must be at most " + MAX_DESCRIPTION_LENGTH + " characters"));
            }

            SharedLinkEntity link = sharedLinkService.register(
                    tenantId, organizationId, userPlan, resourceType, resourceToken, resourceId, title, description);
            return ResponseEntity.ok(SharedLinkResponse.from(link));
        } catch (SharedLinkLimitException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Shared link limit reached"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Error creating shared link", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to create shared link"));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
            @PathVariable UUID id,
            @RequestBody Map<String, Object> body) {
        try {
            String title = body.get("title") != null ? body.get("title").toString() : null;
            String description = body.get("description") != null ? body.get("description").toString() : null;
            Boolean isActive = body.get("isActive") != null ? (Boolean) body.get("isActive") : null;

            // Input validation
            if (title != null && title.length() > MAX_TITLE_LENGTH) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Title must be at most " + MAX_TITLE_LENGTH + " characters"));
            }
            if (description != null && description.length() > MAX_DESCRIPTION_LENGTH) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Description must be at most " + MAX_DESCRIPTION_LENGTH + " characters"));
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> accessConfig = body.get("accessConfig") != null
                    ? (Map<String, Object>) body.get("accessConfig") : null;

            SharedLinkEntity updated = sharedLinkService.update(tenantId, organizationId, id, title, description, accessConfig, isActive);
            return ResponseEntity.ok(SharedLinkResponse.from(updated));
        } catch (IllegalArgumentException e) {
            // Return 404 for both "not found" and "not authorized" to avoid leaking existence
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            logger.error("Error updating shared link {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to update shared link"));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
            @PathVariable UUID id) {
        try {
            sharedLinkService.delete(tenantId, organizationId, id);
            return ResponseEntity.ok().build();
        } catch (IllegalArgumentException e) {
            // Return 404 for both "not found" and "not authorized" to avoid leaking existence
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            logger.error("Error deleting shared link {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to delete shared link"));
        }
    }

    @PostMapping("/{id}/regenerate-token")
    public ResponseEntity<?> regenerateToken(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
            @PathVariable UUID id) {
        try {
            SharedLinkEntity updated = sharedLinkService.regenerateToken(tenantId, organizationId, id);
            return ResponseEntity.ok(SharedLinkResponse.from(updated));
        } catch (IllegalArgumentException e) {
            // Return 404 for both "not found" and "not authorized" to avoid leaking existence
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            logger.error("Error regenerating token for shared link {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to regenerate token"));
        }
    }
}
