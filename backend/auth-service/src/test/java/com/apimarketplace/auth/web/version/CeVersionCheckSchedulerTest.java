package com.apimarketplace.auth.web.version;

import com.apimarketplace.auth.web.version.CeReleaseController.LatestRelease;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CeVersionCheckScheduler}: it stores a successful fetch,
 * passes the reported version (release tag or dev) to the feed, and is best-effort on null / error.
 */
class CeVersionCheckSchedulerTest {

    /** Capturing fake feed: records the current-version arg, returns or throws on demand. */
    private static final class FakeFeed implements ReleaseFeedClient {
        String capturedCurrent;
        LatestRelease toReturn;
        RuntimeException toThrow;

        @Override
        public LatestRelease fetchLatest(String currentVersion) {
            this.capturedCurrent = currentVersion;
            if (toThrow != null) {
                throw toThrow;
            }
            return toReturn;
        }
    }

    /** A scheduler whose reported version is fixed, independent of the machine's APP_VERSION. */
    private static CeVersionCheckScheduler scheduler(VersionUpdateService svc, FakeFeed feed, String reported) {
        return new CeVersionCheckScheduler(svc, feed, () -> reported);
    }

    @Test
    @DisplayName("a successful fetch is stored and stamped with checkedAt; running version is passed to the feed")
    void successStores() {
        VersionUpdateService svc = new VersionUpdateService();
        FakeFeed feed = new FakeFeed();
        feed.toReturn = new LatestRelease("0.3.0", "https://example.test/notes", true, "2026-06-25T09:00:00Z");

        CeVersionCheckScheduler scheduler = scheduler(svc, feed, "0.1.0");
        scheduler.checkNow();

        assertThat(feed.capturedCurrent).isEqualTo("0.1.0");
        UpdateStatus stored = svc.current();
        assertThat(stored).isNotNull();
        assertThat(stored.latestVersion()).isEqualTo("0.3.0");
        assertThat(stored.securityFix()).isTrue();
        assertThat(stored.checkedAt()).isNotNull();
        // End-to-end through resolve(): the install now knows it is behind.
        assertThat(svc.resolve("0.1.0", true).updateAvailable()).isTrue();
    }

    @Test
    @DisplayName("a feed advertising no release leaves the previous status untouched")
    void nullResponseKeepsPrevious() {
        VersionUpdateService svc = new VersionUpdateService();
        svc.update(new UpdateStatus("0.2.0", null, false, null, java.time.Instant.now()));
        FakeFeed feed = new FakeFeed();
        feed.toReturn = null;

        scheduler(svc, feed, "0.1.0").checkNow();

        assertThat(svc.current().latestVersion()).isEqualTo("0.2.0"); // unchanged
    }

    @Test
    @DisplayName("a 200 carrying a null latestVersion leaves the previous status untouched")
    void nullVersionInBodyKeepsPrevious() {
        // Regression: the guard used to test only `body == null`, so a feed answering 200 with
        // an empty payload overwrote a good status with nulls and blanked the update banner on
        // every install. Shipped CE binaries cannot be patched, so the cloud must never answer
        // 200-with-null - but the poller must be inert if it ever does.
        VersionUpdateService svc = new VersionUpdateService();
        svc.update(new UpdateStatus("0.2.0", "https://example.test/notes", true, null, java.time.Instant.now()));
        FakeFeed feed = new FakeFeed();
        feed.toReturn = new LatestRelease(null, null, false, null);

        scheduler(svc, feed, "0.1.0").checkNow();

        UpdateStatus stored = svc.current();
        assertThat(stored.latestVersion()).isEqualTo("0.2.0");
        assertThat(stored.releaseUrl()).isEqualTo("https://example.test/notes");
        assertThat(stored.securityFix()).isTrue();
        // The point of the guard: the install still knows an update exists.
        assertThat(svc.resolve("0.1.0", true).updateAvailable()).isTrue();
    }

    @Test
    @DisplayName("a 200 carrying a blank latestVersion leaves the previous status untouched")
    void blankVersionInBodyKeepsPrevious() {
        VersionUpdateService svc = new VersionUpdateService();
        svc.update(new UpdateStatus("0.2.0", null, false, null, java.time.Instant.now()));
        FakeFeed feed = new FakeFeed();
        feed.toReturn = new LatestRelease("   ", null, false, null);

        scheduler(svc, feed, "0.1.0").checkNow();

        assertThat(svc.current().latestVersion()).isEqualTo("0.2.0");
    }

    @Test
    @DisplayName("a feed error is swallowed and leaves the previous status untouched")
    void errorKeepsPrevious() {
        VersionUpdateService svc = new VersionUpdateService();
        svc.update(new UpdateStatus("0.2.0", null, false, null, java.time.Instant.now()));
        FakeFeed feed = new FakeFeed();
        feed.toThrow = new RuntimeException("connection refused");

        // Must not throw.
        scheduler(svc, feed, "0.1.0").checkNow();

        assertThat(svc.current().latestVersion()).isEqualTo("0.2.0"); // unchanged
    }

    @Test
    @DisplayName("the value sent to the feed is the reported version, verbatim")
    void sendsTheReportedVersion() {
        VersionUpdateService svc = new VersionUpdateService();
        FakeFeed feed = new FakeFeed();
        feed.toReturn = new LatestRelease("0.3.1", null, false, null);

        scheduler(svc, feed, "v0.3.1").checkNow();

        assertThat(feed.capturedCurrent).isEqualTo("v0.3.1");
    }

    @Test
    @DisplayName("the Spring constructor reports through VersionInfo.reportedVersion, never resolveVersion")
    void productionConstructorUsesReportedVersion() throws Exception {
        // Regression: the poller used to send VersionInfo.resolveVersion(git), the DISPLAYED
        // version, whose last fallback is the never-bumped POM version (0.1.6 through the whole
        // v0.3 line). From-source builds, our own e2e stacks included, were then counted in the
        // fleet ledger as installs of an old release. The Spring constructor must not take git
        // properties at all: with no way to reach them it cannot fall back to them.
        java.lang.reflect.Constructor<?>[] ctors = CeVersionCheckScheduler.class.getConstructors();
        assertThat(ctors).hasSize(1);
        assertThat(ctors[0].getParameterTypes())
                .containsExactly(VersionUpdateService.class, ReleaseFeedClient.class);

        VersionUpdateService svc = new VersionUpdateService();
        FakeFeed feed = new FakeFeed();
        feed.toReturn = new LatestRelease("0.3.1", null, false, null);
        new CeVersionCheckScheduler(svc, feed).checkNow();

        assertThat(feed.capturedCurrent).isEqualTo(com.apimarketplace.auth.web.VersionInfo.reportedVersion());
    }

    @Test
    @DisplayName("the daily poll is boot-anchored, not on a clock time the whole fleet shares")
    void pollIsBootAnchored() throws Exception {
        org.springframework.scheduling.annotation.Scheduled scheduled =
                CeVersionCheckScheduler.class.getMethod("checkPeriodically")
                        .getAnnotation(org.springframework.scheduling.annotation.Scheduled.class);

        // It used to be cron 0 0 5 * * *, which put every self-hosted install on earth inside one
        // minute. That is a poor load profile on its own, and once the request carries an install
        // id it is a correctness problem: the collector's per-minute budget would have to absorb
        // the whole fleet in one window and would drop the rest silently, at DEBUG. Deleting this
        // annotation, or putting a cron back, leaves every other test in the repo green.
        assertThat(scheduled).isNotNull();
        assertThat(scheduled.cron()).as("a shared cron time re-creates the herd").isEmpty();
        assertThat(scheduled.fixedDelayString()).isNotEmpty();
        assertThat(scheduled.initialDelayString())
                .as("without an initial delay the fixed delay fires immediately on top of the "
                        + "startup check")
                .isNotEmpty();
    }

    @Test
    @DisplayName("the startup check is still wired to ApplicationReadyEvent")
    void startupCheckIsWired() throws Exception {
        // The card must be accurate without waiting a day, and the first ledger sighting of a new
        // install comes from this call rather than from the periodic one.
        assertThat(CeVersionCheckScheduler.class.getMethod("checkOnStartup")
                .getAnnotation(org.springframework.context.event.EventListener.class))
                .isNotNull();
    }
}
