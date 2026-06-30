package com.apimarketplace.publication.controller;

import com.apimarketplace.publication.domain.WorkflowPublicationEntity.DisplayMode;
import com.apimarketplace.publication.service.PublicationHighlightService;
import com.apimarketplace.publication.service.PublicationHighlightService.PublicHighlight;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Public (anonymous-accessible) read endpoint for the admin-curated highlights row.
 * <p>
 * Cached for 60s in {@link PublicationHighlightService} (Caffeine) to absorb
 * homepage traffic. Always filters publications down to {@code ACTIVE+PUBLIC}
 * so a curated entry whose publication has been deactivated/rejected since
 * curation does not leak into the public response.
 * <p>
 * <b>Response shape:</b> a slim {@code PublicHighlightItem} DTO - never the raw
 * entity. Heavy / sensitive jsonb columns ({@code planSnapshot}, {@code agentSnapshot},
 * {@code showcaseSnapshot}) are excluded. Anonymous callers see only the fields
 * the homepage card consumes.
 * <p>
 * <b>Routing note:</b> this path IS allowlisted in
 * {@code GatewayConstants.PUBLIC_ENDPOINTS} (entry {@code "/api/publications/highlights"}).
 * Removing it from the allowlist breaks the homepage row for anonymous visitors -
 * see the {@code PublicRoutes#highlightsEndpointIsPublic} integration test that
 * pins this contract. The disjoint admin path
 * {@code /api/publications/admin/highlights/**} is intentionally NOT covered by
 * the same prefix because the gateway match semantics are
 * {@code path.equals(ep) || path.startsWith(ep + "/")}.
 */
@RestController
@RequestMapping("/api/publications/highlights")
public class PublicationHighlightPublicController {

    private final PublicationHighlightService highlightService;

    public PublicationHighlightPublicController(PublicationHighlightService highlightService) {
        this.highlightService = highlightService;
    }

    @GetMapping("/{displayMode}")
    public ResponseEntity<?> listPublicHighlights(@PathVariable DisplayMode displayMode) {
        List<PublicHighlight> highlights = highlightService.listPublicHighlights(displayMode);
        return ResponseEntity.ok(Map.of(
                "displayMode", displayMode.name(),
                "highlights", highlights.stream()
                        .map(h -> Map.<String, Object>of(
                                "rank", h.rank(),
                                "publication", h.publication()
                        ))
                        .toList()
        ));
    }
}
