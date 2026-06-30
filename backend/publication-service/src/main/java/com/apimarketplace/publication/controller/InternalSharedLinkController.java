package com.apimarketplace.publication.controller;

import com.apimarketplace.publication.domain.SharedLinkEntity;
import com.apimarketplace.publication.service.SharedLinkService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Internal API for inter-service shared link operations.
 * Called by orchestrator-service (via PublicationClient) when creating/deleting endpoints.
 */
@RestController
@RequestMapping("/api/internal/shared-links")
public class InternalSharedLinkController {

    private static final Logger log = LoggerFactory.getLogger(InternalSharedLinkController.class);

    private final SharedLinkService sharedLinkService;

    public InternalSharedLinkController(SharedLinkService sharedLinkService) {
        this.sharedLinkService = sharedLinkService;
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(
            @RequestHeader(value = "X-Organization-ID", required = false) String headerOrganizationId,
            @RequestBody Map<String, Object> body) {
        try {
            String tenantId = (String) body.get("tenantId");
            String resourceType = (String) body.get("resourceType");
            String resourceToken = (String) body.get("resourceToken");
            UUID resourceId = body.get("resourceId") != null
                    ? UUID.fromString(body.get("resourceId").toString()) : null;
            String title = (String) body.get("title");
            String description = (String) body.get("description");

            if (tenantId == null || resourceType == null || resourceToken == null) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "tenantId, resourceType, and resourceToken are required"));
            }

            String userPlan = (String) body.get("userPlan");
            // organizationId resolution priority: explicit body > forwarded header.
            String organizationId = (String) body.get("organizationId");
            if (organizationId == null || organizationId.isBlank()) {
                organizationId = headerOrganizationId;
            }

            SharedLinkEntity link = sharedLinkService.register(
                    tenantId, organizationId, userPlan, resourceType, resourceToken, resourceId, title, description);

            return ResponseEntity.ok(Map.of(
                    "id", link.getId().toString(),
                    "token", link.getToken(),
                    "resourceType", link.getResourceType().name(),
                    "resourceToken", link.getResourceToken()
            ));
        } catch (Exception e) {
            log.error("Failed to register shared link: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to register shared link: " + e.getMessage()));
        }
    }

    @PostMapping("/unregister")
    public ResponseEntity<Void> unregister(@RequestBody Map<String, String> body) {
        String resourceToken = body.get("resourceToken");
        if (resourceToken == null || resourceToken.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        sharedLinkService.unregister(resourceToken);
        return ResponseEntity.ok().build();
    }

    /**
     * Validate a share token and return the owner's userId.
     * Same contract as conversation-service's /api/internal/share/validate/{token}.
     * Called by the gateway's ShareTokenResolutionService for sl_ tokens.
     */
    @GetMapping("/validate/{token}")
    public ResponseEntity<?> validate(@PathVariable String token) {
        return sharedLinkService.getByToken(token)
                .map(link -> {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("userId", link.getTenantId());
                    if (link.getOrganizationId() != null && !link.getOrganizationId().isBlank()) {
                        body.put("organizationId", link.getOrganizationId());
                    }
                    body.put("resourceType", link.getResourceType().name());
                    body.put("resourceToken", link.getResourceToken());
                    if (link.getResourceId() != null) {
                        body.put("resourceId", link.getResourceId().toString());
                    }
                    return ResponseEntity.ok(body);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/by-token/{token}")
    public ResponseEntity<?> getByToken(@PathVariable String token) {
        return sharedLinkService.getByToken(token)
                .map(link -> ResponseEntity.ok(Map.of(
                        "id", link.getId().toString(),
                        "token", link.getToken(),
                        "resourceType", link.getResourceType().name(),
                        "resourceToken", link.getResourceToken(),
                        "resourceId", link.getResourceId() != null ? link.getResourceId().toString() : "",
                        "tenantId", link.getTenantId(),
                        "title", link.getTitle() != null ? link.getTitle() : "",
                        "description", link.getDescription() != null ? link.getDescription() : "",
                        "isActive", link.isActive(),
                        "metadata", link.getMetadata() != null ? link.getMetadata() : Map.of()
                )))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/by-resource/{resourceToken}")
    public ResponseEntity<?> getByResourceToken(@PathVariable String resourceToken) {
        return sharedLinkService.getByResourceToken(resourceToken)
                .map(link -> ResponseEntity.ok(Map.of(
                        "id", link.getId().toString(),
                        "token", link.getToken(),
                        "resourceType", link.getResourceType().name(),
                        "resourceToken", link.getResourceToken(),
                        "title", link.getTitle() != null ? link.getTitle() : "",
                        "isActive", link.isActive(),
                        "accessCount", link.getAccessCount(),
                        "createdAt", link.getCreatedAt().toString()
                )))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/by-resource-id/{resourceId}")
    public ResponseEntity<?> getByResourceId(@PathVariable String resourceId) {
        try {
            UUID rid = UUID.fromString(resourceId);
            return sharedLinkService.getByResourceId(rid)
                    .map(link -> ResponseEntity.ok(Map.of(
                            "id", link.getId().toString(),
                            "token", link.getToken(),
                            "resourceType", link.getResourceType().name(),
                            "resourceToken", link.getResourceToken(),
                            "isActive", link.isActive()
                    )))
                    .orElse(ResponseEntity.notFound().build());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid UUID"));
        }
    }

    @PostMapping("/regenerate-token")
    public ResponseEntity<?> regenerateToken(
            @RequestHeader(value = "X-Organization-ID", required = false) String headerOrganizationId,
            @RequestBody Map<String, String> body) {
        try {
            String tenantId = body.get("tenantId");
            String linkId = body.get("linkId");
            if (tenantId == null || linkId == null) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "tenantId and linkId are required"));
            }
            String organizationId = body.get("organizationId");
            if (organizationId == null || organizationId.isBlank()) {
                organizationId = headerOrganizationId;
            }

            SharedLinkEntity updated = sharedLinkService.regenerateToken(tenantId, organizationId, UUID.fromString(linkId));
            return ResponseEntity.ok(Map.of(
                    "id", updated.getId().toString(),
                    "token", updated.getToken()
            ));
        } catch (Exception e) {
            log.error("Failed to regenerate token: {}", e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to regenerate token: " + e.getMessage()));
        }
    }
}
