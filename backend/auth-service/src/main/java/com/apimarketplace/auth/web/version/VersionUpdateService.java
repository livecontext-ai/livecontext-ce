package com.apimarketplace.auth.web.version;

import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Holds the latest-known release info (set by the CE version-check poller) and
 * computes, against the running version, whether an update is available.
 *
 * <p>Edition-aware: for managed-cloud editions the result is always "no update"
 * (updates are handled for the user, there is no user-facing update affordance).
 * The held status stays empty whenever the poller does not run: the feed is polled
 * by the Community Edition (embedded) only, so managed cloud AND self-hosted
 * enterprise both resolve to "no update" here (fail-safe: a never-populated status
 * never claims the install is behind).
 *
 * <p>Thread-safe: the poller writes from a scheduler thread while controllers
 * read from request threads, so the snapshot lives behind an
 * {@link AtomicReference}.
 */
@Service
public class VersionUpdateService {

    private final AtomicReference<UpdateStatus> latest = new AtomicReference<>();

    /** Replace the latest-known release info (called by the poller after a successful fetch). */
    public void update(UpdateStatus status) {
        latest.set(status);
    }

    /** The last-known release info, or {@code null} when never fetched. */
    public UpdateStatus current() {
        return latest.get();
    }

    /**
     * Resolves the update view for a given running version.
     *
     * @param runningVersion the build version currently running
     * @param selfHosted     whether this edition is user-operated (CE / self-hosted
     *                       enterprise); managed cloud always yields "no update"
     */
    public VersionUpdateView resolve(String runningVersion, boolean selfHosted) {
        UpdateStatus status = latest.get();
        if (!selfHosted || status == null) {
            return VersionUpdateView.none();
        }
        boolean available = VersionComparator.isUpdateAvailable(runningVersion, status.latestVersion());
        String checkedAt = status.checkedAt() != null ? status.checkedAt().toString() : null;
        return new VersionUpdateView(
                available,
                status.latestVersion(),
                status.releaseUrl(),
                available && status.securityFix(),
                checkedAt);
    }

    /**
     * The update fields surfaced by {@code GET /api/version}.
     *
     * @param updateAvailable whether a newer release exists for this self-hosted install
     * @param latestVersion   the latest-known version (shown even when up to date), or {@code null}
     * @param releaseUrl      release-notes link, or {@code null}
     * @param securityFix     whether the available update is a security fix (false when no update)
     * @param checkedAt       ISO-8601 timestamp of the last successful feed check, or {@code null}
     */
    public record VersionUpdateView(
            boolean updateAvailable,
            String latestVersion,
            String releaseUrl,
            boolean securityFix,
            String checkedAt) {

        /** Empty view: nothing known / not applicable (managed cloud or no fetch yet). */
        public static VersionUpdateView none() {
            return new VersionUpdateView(false, null, null, false, null);
        }
    }
}
