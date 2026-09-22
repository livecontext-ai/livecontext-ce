package com.apimarketplace.datasource.publicvideo;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * The product-film library as the PUBLIC website reads it: /videos and each
 * /videos/{slug} page.
 *
 * <p>Anonymous by design, and allow-listed at the gateway, so nothing here may
 * depend on {@code X-User-ID}: every read goes through {@link PublicVideoService},
 * which serves published rows of ONE configured data source and projects them.
 *
 * <p>GET only, deliberately. The gateway's public allow-list matches on path,
 * not on verb, so a route placed on it is reachable by any verb; the safety
 * comes from this class declaring no other mapping, exactly like its
 * {@code /api/public/integrations} sibling. Do not add a write mapping under
 * this prefix.
 */
@RestController
@RequestMapping("/api/public/videos")
public class PublicVideoController {

    private final PublicVideoService publicVideoService;

    public PublicVideoController(PublicVideoService publicVideoService) {
        this.publicVideoService = publicVideoService;
    }

    /** Every published film, newest first. */
    @GetMapping
    public ResponseEntity<Map<String, Object>> list() {
        List<PublicVideoDto> videos = publicVideoService.published();
        return ResponseEntity.ok(Map.of(
                "videos", videos,
                "count", videos.size(),
                // So a caller can tell "this install has no library" from "the
                // library is empty today", which look identical otherwise.
                "configured", publicVideoService.isConfigured()));
    }

    /** One published film, or 404. A draft is not found, which is what it is. */
    @GetMapping("/{slug}")
    public ResponseEntity<PublicVideoDto> bySlug(@PathVariable("slug") String slug) {
        PublicVideoDto video = publicVideoService.bySlug(slug);
        return video == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(video);
    }
}
