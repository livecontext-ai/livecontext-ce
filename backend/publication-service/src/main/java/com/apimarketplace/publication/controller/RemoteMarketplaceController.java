package com.apimarketplace.publication.controller;

import com.apimarketplace.publication.domain.WorkflowPublicationEntity.DisplayMode;
import com.apimarketplace.publication.service.CloudLinkService;
import com.apimarketplace.publication.service.RemoteMarketplaceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * CE-side endpoint for acquiring publications from the cloud marketplace.
 * Uses the linked cloud account for paid publications (no more download tokens).
 *
 * Only active when marketplace.mode=remote (CE monolith).
 */
@RestController
@RequestMapping("/api/publications/remote")
@ConditionalOnProperty(name = "marketplace.mode", havingValue = "remote")
public class RemoteMarketplaceController {

    private static final Logger logger = LoggerFactory.getLogger(RemoteMarketplaceController.class);

    private final RemoteMarketplaceService remoteMarketplaceService;

    public RemoteMarketplaceController(RemoteMarketplaceService remoteMarketplaceService) {
        this.remoteMarketplaceService = remoteMarketplaceService;
    }

    /**
     * POST /api/publications/remote/{publicationId}/acquire
     *
     * Acquires a publication from the cloud marketplace and clones it locally.
     * Free publications are fetched directly. Paid publications use the linked
     * cloud account's OAuth token for server-to-server authentication.
     */
    @PostMapping("/{publicationId}/acquire")
    public ResponseEntity<?> acquireRemotePublication(
            @PathVariable UUID publicationId,
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId) {

        try {
            Map<String, Object> result = remoteMarketplaceService.acquirePublication(publicationId, tenantId, organizationId);
            return ResponseEntity.ok(result);

        } catch (CloudLinkService.CloudAccountNotLinkedException e) {
            logger.info("Cloud account not linked for tenant {} acquiring publication {}", tenantId, publicationId);
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", e.getMessage(), "code", "CLOUD_ACCOUNT_NOT_LINKED"));

        } catch (RemoteMarketplaceService.InsufficientCreditsException e) {
            return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
                    .body(Map.of("error", e.getMessage(), "code", "INSUFFICIENT_CREDITS"));

        } catch (IllegalArgumentException e) {
            logger.warn("Remote acquire validation error for publication {}: {}", publicationId, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));

        } catch (Exception e) {
            logger.error("Remote acquire failed for publication {}: {}", publicationId, e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to acquire publication: " + e.getMessage()));
        }
    }

    // =====================================================================
    // Cloud-parity read proxies (2026-06-10) - a cloud-linked CE renders the
    // SAME marketplace UI as cloud; these endpoints forward the cloud's PUBLIC
    // marketplace reads through `marketplace.cloud-api-url` so the browser
    // never needs the cloud origin. All fail-soft (HTTP 200 + empty payload)
    // when the cloud is unreachable - see RemoteMarketplaceService.
    // =====================================================================

    /**
     * GET /api/publications/remote/marketplace
     * Proxies the cloud's public marketplace listing (paged, optional category).
     */
    @GetMapping("/marketplace")
    public ResponseEntity<?> listRemoteMarketplacePublications(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String category) {
        return ResponseEntity.ok(remoteMarketplaceService.fetchMarketplacePublications(page, size, category));
    }

    /**
     * GET /api/publications/remote/search?q=...
     * Proxies the cloud's public marketplace search (optional category).
     */
    @GetMapping("/search")
    public ResponseEntity<?> searchRemoteMarketplacePublications(
            @RequestParam("q") String query,
            @RequestParam(required = false) String category) {
        return ResponseEntity.ok(remoteMarketplaceService.searchMarketplacePublications(query, category));
    }

    /**
     * GET /api/publications/remote/highlights/{displayMode}
     * Proxies the cloud's public admin-curated highlights row. The displayMode
     * is validated against the local DisplayMode enum BEFORE building the
     * upstream URL (no arbitrary path segments reach the cloud).
     */
    @GetMapping("/highlights/{displayMode}")
    public ResponseEntity<?> listRemoteHighlights(@PathVariable String displayMode) {
        final DisplayMode mode;
        try {
            mode = DisplayMode.valueOf(displayMode.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Unknown displayMode: " + displayMode));
        }
        return ResponseEntity.ok(remoteMarketplaceService.fetchHighlights(mode.name()));
    }

    // =====================================================================
    // Cloud-parity PER-PUBLICATION read proxies - forward the cloud's PUBLIC
    // /publications/by-id/{id}/* reads (the detail page + every card thumbnail
    // resource) and the publisher avatar so a cloud-linked CE renders cloud
    // publications fully (without these, every card thumbnail and the detail
    // page 404 because the cloud ids are absent from the local DB). The body is
    // forwarded verbatim with the cloud's status (NOT fail-soft-to-empty) so the
    // UI's per-resource not-found / fallback handling fires. Each sub-path is a
    // literal or a typed path variable - no arbitrary path reaches the cloud.
    // =====================================================================

    /** GET /api/publications/remote/by-id/{id} - cloud publication detail. */
    @GetMapping("/by-id/{publicationId}")
    public ResponseEntity<String> remotePublicationDetail(@PathVariable UUID publicationId) {
        return remoteMarketplaceService.proxyPublicByIdJson(publicationId, "", null);
    }

    /** GET /api/publications/remote/by-id/{id}/landing-snapshot - card thumbnail (interface/table/skill/agent). */
    @GetMapping("/by-id/{publicationId}/landing-snapshot")
    public ResponseEntity<String> remoteLandingSnapshot(@PathVariable UUID publicationId) {
        return remoteMarketplaceService.proxyPublicByIdJson(publicationId, "landing-snapshot", null);
    }

    /** GET /api/publications/remote/by-id/{id}/showcase-render - card / detail workflow thumbnail (page, size, interfaceId, epoch). */
    @GetMapping("/by-id/{publicationId}/showcase-render")
    public ResponseEntity<String> remoteShowcaseRender(@PathVariable UUID publicationId,
                                                       @RequestParam MultiValueMap<String, String> params) {
        return remoteMarketplaceService.proxyPublicByIdJson(publicationId, "showcase-render", params);
    }

    /** GET /api/publications/remote/by-id/{id}/agent-snapshot - agent fleet preview. */
    @GetMapping("/by-id/{publicationId}/agent-snapshot")
    public ResponseEntity<String> remoteAgentSnapshot(@PathVariable UUID publicationId) {
        return remoteMarketplaceService.proxyPublicByIdJson(publicationId, "agent-snapshot", null);
    }

    /** GET /api/publications/remote/by-id/{id}/run-state - frozen showcase run state (detail preview). */
    @GetMapping("/by-id/{publicationId}/run-state")
    public ResponseEntity<String> remoteRunState(@PathVariable UUID publicationId) {
        return remoteMarketplaceService.proxyPublicByIdJson(publicationId, "run-state", null);
    }

    /** GET /api/publications/remote/by-id/{id}/aggregated-steps - showcase step rows (optional epoch). */
    @GetMapping("/by-id/{publicationId}/aggregated-steps")
    public ResponseEntity<String> remoteAggregatedSteps(@PathVariable UUID publicationId,
                                                        @RequestParam MultiValueMap<String, String> params) {
        return remoteMarketplaceService.proxyPublicByIdJson(publicationId, "aggregated-steps", params);
    }

    /** GET /api/publications/remote/by-id/{id}/epochs/{epoch}/state - per-epoch status counts. */
    @GetMapping("/by-id/{publicationId}/epochs/{epoch}/state")
    public ResponseEntity<String> remoteEpochState(@PathVariable UUID publicationId, @PathVariable long epoch) {
        return remoteMarketplaceService.proxyPublicByIdJson(publicationId, "epochs/" + epoch + "/state", null);
    }

    /** GET /api/publications/remote/by-id/{id}/epochs/{epoch}/signals - per-epoch active signals. */
    @GetMapping("/by-id/{publicationId}/epochs/{epoch}/signals")
    public ResponseEntity<String> remoteEpochSignals(@PathVariable UUID publicationId, @PathVariable long epoch) {
        return remoteMarketplaceService.proxyPublicByIdJson(publicationId, "epochs/" + epoch + "/signals", null);
    }

    /** GET /api/publications/remote/users/{userId}/avatar - publisher avatar image for cloud-sourced cards. */
    @GetMapping("/users/{userId}/avatar")
    public ResponseEntity<byte[]> remoteUserAvatar(@PathVariable String userId) {
        return remoteMarketplaceService.proxyUserAvatar(userId);
    }

    /**
     * GET /api/publications/remote/users/{userId}/profile - cloud publisher/reviewer
     * public profile (resolves the {@code @handle}) so a cloud-linked CE can deep-link
     * "View profile" on a cloud-sourced card to the cloud profile page. The cloud user
     * id is absent from the local auth DB, so the local by-id read would 404.
     */
    @GetMapping("/users/{userId}/profile")
    public ResponseEntity<String> remoteUserProfile(@PathVariable String userId) {
        return remoteMarketplaceService.proxyUserProfile(userId);
    }
}
