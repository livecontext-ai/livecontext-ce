package com.apimarketplace.auth.web.version;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cloud-side public feed of the latest published self-hosted release, polled by
 * CE installs to learn whether they are behind. Anonymous (no auth, no install
 * identifier) and config-driven: the release the cloud advertises is set via
 * {@code ce.release.*} on the cloud deployment. Same model as n8n's public
 * {@code /api/versions} feed.
 *
 * <p>Routed under the existing {@code /api/ce/**} gateway route and allowlisted in
 * the gateway public-endpoints set so an unlinked CE can reach it. In CE the
 * monolith also exposes this controller, but with no {@code ce.release.*} config
 * it just advertises "no release", which is harmless (CE polls the cloud's URL,
 * not its own).
 */
@RestController
public class CeReleaseController {

    private final String latestVersion;
    private final String releaseUrl;
    private final boolean securityFix;
    private final String publishedAt;

    public CeReleaseController(
            @Value("${ce.release.latest-version:}") String latestVersion,
            @Value("${ce.release.url:}") String releaseUrl,
            @Value("${ce.release.security:false}") boolean securityFix,
            @Value("${ce.release.published-at:}") String publishedAt) {
        this.latestVersion = blankToNull(latestVersion);
        this.releaseUrl = blankToNull(releaseUrl);
        this.securityFix = securityFix;
        this.publishedAt = blankToNull(publishedAt);
    }

    /**
     * @param current the caller's running version (optional, accepted for future
     *                analytics; the comparison is done client-side by the CE install).
     */
    @GetMapping("/api/ce/releases/latest")
    public LatestRelease latest(@RequestParam(value = "current", required = false) String current) {
        return new LatestRelease(latestVersion, releaseUrl, securityFix, publishedAt);
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    /** Wire shape of the release feed; mirrored by the CE poller's deserializer. */
    public record LatestRelease(
            String latestVersion,
            String releaseUrl,
            boolean securityFix,
            String publishedAt) {
    }
}
