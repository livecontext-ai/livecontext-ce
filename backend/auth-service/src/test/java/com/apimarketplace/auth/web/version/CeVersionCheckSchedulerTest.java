package com.apimarketplace.auth.web.version;

import com.apimarketplace.auth.web.version.CeReleaseController.LatestRelease;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.GitProperties;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CeVersionCheckScheduler}: it stores a successful fetch,
 * passes the running version to the feed, and is best-effort on null / error.
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

    @SuppressWarnings("unchecked")
    private static ObjectProvider<GitProperties> gitProviderWithVersion(String version) {
        ObjectProvider<GitProperties> provider = mock(ObjectProvider.class);
        Properties p = new Properties();
        // No release tag and no commit sha in this fixture, so the running version
        // resolves deterministically to the Maven build version (the value asserted
        // below). The release-tag / dev-<sha> precedence is covered in VersionControllerTest.
        p.setProperty("build.version", version);
        when(provider.getIfAvailable()).thenReturn(new GitProperties(p));
        return provider;
    }

    @Test
    @DisplayName("a successful fetch is stored and stamped with checkedAt; running version is passed to the feed")
    void successStores() {
        VersionUpdateService svc = new VersionUpdateService();
        FakeFeed feed = new FakeFeed();
        feed.toReturn = new LatestRelease("0.3.0", "https://example.test/notes", true, "2026-06-25T09:00:00Z");

        CeVersionCheckScheduler scheduler =
                new CeVersionCheckScheduler(svc, feed, gitProviderWithVersion("0.1.0"));
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

        new CeVersionCheckScheduler(svc, feed, gitProviderWithVersion("0.1.0")).checkNow();

        assertThat(svc.current().latestVersion()).isEqualTo("0.2.0"); // unchanged
    }

    @Test
    @DisplayName("a feed error is swallowed and leaves the previous status untouched")
    void errorKeepsPrevious() {
        VersionUpdateService svc = new VersionUpdateService();
        svc.update(new UpdateStatus("0.2.0", null, false, null, java.time.Instant.now()));
        FakeFeed feed = new FakeFeed();
        feed.toThrow = new RuntimeException("connection refused");

        // Must not throw.
        new CeVersionCheckScheduler(svc, feed, gitProviderWithVersion("0.1.0")).checkNow();

        assertThat(svc.current().latestVersion()).isEqualTo("0.2.0"); // unchanged
    }

    @Test
    @DisplayName("with no git info the running version sent to the feed is 'dev'")
    void devVersionWhenNoGit() {
        VersionUpdateService svc = new VersionUpdateService();
        FakeFeed feed = new FakeFeed();
        feed.toReturn = new LatestRelease("0.3.0", null, false, null);
        @SuppressWarnings("unchecked")
        ObjectProvider<GitProperties> noGit = mock(ObjectProvider.class);
        when(noGit.getIfAvailable()).thenReturn(null);

        new CeVersionCheckScheduler(svc, feed, noGit).checkNow();

        assertThat(feed.capturedCurrent).isEqualTo("dev");
    }
}
