package com.apimarketplace.orchestrator.controllers;

import com.apimarketplace.orchestrator.domain.ResourceFavoriteType;
import com.apimarketplace.orchestrator.service.ResourceFavoriteService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Per-user favorites for the caller's own native resources (workflow / table /
 * interface / agent). The native counterpart to publication-service's
 * {@code PublicationFavoriteController}. Authenticated: the gateway injects
 * {@code X-User-ID}, and these paths are intentionally NOT public (a favorite is
 * meaningless without a real user).
 *
 * <p>Routes are keyed by an opaque resource type + id:
 * <ul>
 *   <li>{@code GET    /api/favorites/{type}/ids}      - the caller's favorited ids of that type</li>
 *   <li>{@code POST   /api/favorites/{type}/{id}}     - star a resource (idempotent)</li>
 *   <li>{@code DELETE /api/favorites/{type}/{id}}     - unstar a resource (idempotent)</li>
 * </ul>
 * The literal {@code ids} GET has no competing {@code GET /{type}/{id}} mapping,
 * so no precedence concern. An unknown {@code type} yields a clean 400.
 */
@RestController
@RequestMapping("/api/favorites")
public class ResourceFavoriteController {

    /** Stable error code for an unknown resource-type segment. */
    private static final String INVALID_TYPE = "INVALID_RESOURCE_TYPE";

    private final ResourceFavoriteService favoriteService;

    public ResourceFavoriteController(ResourceFavoriteService favoriteService) {
        this.favoriteService = favoriteService;
    }

    @PostMapping("/{type}/{resourceId}")
    public ResponseEntity<?> favorite(
            @RequestHeader("X-User-ID") String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
            @PathVariable String type,
            @PathVariable String resourceId) {
        ResourceFavoriteType resourceType = ResourceFavoriteType.fromString(type);
        if (resourceType == null) {
            return ResponseEntity.badRequest().body(Map.of("error", INVALID_TYPE));
        }
        favoriteService.addFavorite(userId, organizationId, resourceType, resourceId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{type}/{resourceId}")
    public ResponseEntity<?> unfavorite(
            @RequestHeader("X-User-ID") String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
            @PathVariable String type,
            @PathVariable String resourceId) {
        ResourceFavoriteType resourceType = ResourceFavoriteType.fromString(type);
        if (resourceType == null) {
            return ResponseEntity.badRequest().body(Map.of("error", INVALID_TYPE));
        }
        favoriteService.removeFavorite(userId, organizationId, resourceType, resourceId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{type}/ids")
    public ResponseEntity<?> listFavoriteIds(
            @RequestHeader("X-User-ID") String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
            @PathVariable String type) {
        ResourceFavoriteType resourceType = ResourceFavoriteType.fromString(type);
        if (resourceType == null) {
            return ResponseEntity.badRequest().body(Map.of("error", INVALID_TYPE));
        }
        return ResponseEntity.ok(Map.of("ids", favoriteService.listFavoriteIds(userId, organizationId, resourceType)));
    }
}
