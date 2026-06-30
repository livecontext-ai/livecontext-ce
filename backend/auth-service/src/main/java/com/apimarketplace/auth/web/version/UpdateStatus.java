package com.apimarketplace.auth.web.version;

import java.time.Instant;

/**
 * The latest published release as last learned from the cloud release feed,
 * held in memory by {@link VersionUpdateService}. Immutable snapshot.
 *
 * @param latestVersion the newest published version string (e.g. {@code 0.3.0}),
 *                      or {@code null} when the feed advertises none.
 * @param releaseUrl    a link to the release notes / changelog, or {@code null}.
 * @param securityFix   whether the latest release carries a security fix (drives
 *                      the high-emphasis notice).
 * @param publishedAt   ISO-8601 publish timestamp string, or {@code null}.
 * @param checkedAt     when this install last successfully fetched the feed.
 */
public record UpdateStatus(
        String latestVersion,
        String releaseUrl,
        boolean securityFix,
        String publishedAt,
        Instant checkedAt) {
}
