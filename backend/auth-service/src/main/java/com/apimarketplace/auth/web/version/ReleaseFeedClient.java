package com.apimarketplace.auth.web.version;

import com.apimarketplace.auth.web.version.CeReleaseController.LatestRelease;

/**
 * Fetches the latest published release from the cloud feed. Extracted so the
 * scheduler's orchestration (map + store + best-effort) is unit-testable against
 * a fake, while the real HTTP adapter ({@link HttpReleaseFeedClient}) is exercised
 * end-to-end.
 */
public interface ReleaseFeedClient {

    /**
     * @param currentVersion the running build version, passed to the feed as
     *                       {@code ?current=}
     * @return the advertised latest release, or {@code null} when the feed has none
     * @throws RuntimeException if the feed is unreachable / returns an error
     */
    LatestRelease fetchLatest(String currentVersion);
}
