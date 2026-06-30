package com.apimarketplace.publication.controller;

import com.apimarketplace.publication.service.UserPublicationFavoriteService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Per-user favorite applications - the personal counterpart to the admin-curated
 * highlights controllers. Authenticated: the gateway injects {@code X-User-ID},
 * and these paths are intentionally NOT in {@code GatewayConstants.PUBLIC_ENDPOINTS}
 * (a favorite is meaningless without a real user).
 *
 * <p><b>Routing:</b> the literal {@code /favorites} and {@code /favorites/ids}
 * segments resolve ahead of the greedy {@code GET /{publicationId}} mapping on
 * {@link WorkflowPublicationController} (Spring ranks literal patterns above path
 * variables), and the {@code POST}/{@code DELETE} {@code /{id}/favorite} write paths
 * have no competing mapping.
 */
@RestController
@RequestMapping("/api/publications")
public class PublicationFavoriteController {

    private final UserPublicationFavoriteService favoriteService;

    public PublicationFavoriteController(UserPublicationFavoriteService favoriteService) {
        this.favoriteService = favoriteService;
    }

    @PostMapping("/{publicationId}/favorite")
    public ResponseEntity<?> favorite(
            @RequestHeader("X-User-ID") String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
            @PathVariable String publicationId) {
        UUID id = parseId(publicationId);
        if (id == null) {
            return ResponseEntity.badRequest().body(Map.of("error", INVALID));
        }
        try {
            favoriteService.addFavorite(userId, organizationId, id);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage() == null ? INVALID : e.getMessage()));
        }
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{publicationId}/favorite")
    public ResponseEntity<?> unfavorite(
            @RequestHeader("X-User-ID") String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
            @PathVariable String publicationId) {
        UUID id = parseId(publicationId);
        if (id == null) {
            return ResponseEntity.badRequest().body(Map.of("error", INVALID));
        }
        favoriteService.removeFavorite(userId, organizationId, id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/favorites")
    public ResponseEntity<?> listFavorites(
            @RequestHeader("X-User-ID") String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId) {
        return ResponseEntity.ok(Map.of("favorites", favoriteService.listFavorites(userId, organizationId)));
    }

    @GetMapping("/favorites/ids")
    public ResponseEntity<?> listFavoriteIds(
            @RequestHeader("X-User-ID") String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId) {
        return ResponseEntity.ok(Map.of("ids", favoriteService.listFavoriteIds(userId, organizationId)));
    }

    /** Stable error code shared by the malformed-id and unknown-id paths. */
    private static final String INVALID = "INVALID_OR_INACCESSIBLE_PUBLICATION";

    /** Parse a path id to UUID, returning null on a malformed segment (mapped to a clean 400 by callers). */
    private static UUID parseId(String publicationId) {
        try {
            return UUID.fromString(publicationId);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
