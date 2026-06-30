package com.apimarketplace.auth.web.version;

import com.apimarketplace.auth.web.version.VersionUpdateService.VersionUpdateView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for {@link VersionUpdateService#resolve}. */
class VersionUpdateServiceTest {

    private static UpdateStatus status(String latest, boolean security) {
        return new UpdateStatus(latest, "https://example.test/notes", security, null,
                Instant.parse("2026-06-25T11:00:00Z"));
    }

    @Test
    @DisplayName("self-hosted, behind: update available, fields surfaced")
    void selfHostedBehind() {
        VersionUpdateService svc = new VersionUpdateService();
        svc.update(status("0.2.0", false));

        VersionUpdateView view = svc.resolve("0.1.0", true);

        assertThat(view.updateAvailable()).isTrue();
        assertThat(view.latestVersion()).isEqualTo("0.2.0");
        assertThat(view.releaseUrl()).isEqualTo("https://example.test/notes");
        assertThat(view.checkedAt()).isEqualTo("2026-06-25T11:00:00Z");
    }

    @Test
    @DisplayName("self-hosted, up to date: no update, but latest is still reported")
    void selfHostedUpToDate() {
        VersionUpdateService svc = new VersionUpdateService();
        svc.update(status("0.2.0", true));

        VersionUpdateView view = svc.resolve("0.2.0", true);

        assertThat(view.updateAvailable()).isFalse();
        assertThat(view.latestVersion()).isEqualTo("0.2.0");
        // securityFix only applies to an AVAILABLE update - not when already current.
        assertThat(view.securityFix()).isFalse();
    }

    @Test
    @DisplayName("securityFix is surfaced only when the update is actually available")
    void securityFixGatedOnAvailability() {
        VersionUpdateService svc = new VersionUpdateService();
        svc.update(status("0.2.0", true));

        assertThat(svc.resolve("0.1.0", true).securityFix()).isTrue();   // behind + security
        assertThat(svc.resolve("0.2.0", true).securityFix()).isFalse();  // current + security flag ignored
    }

    @Test
    @DisplayName("managed cloud never advertises an update, even with a populated feed")
    void managedCloudNeverUpdates() {
        VersionUpdateService svc = new VersionUpdateService();
        svc.update(status("9.9.9", true));

        VersionUpdateView view = svc.resolve("0.1.0", false);

        assertThat(view.updateAvailable()).isFalse();
        assertThat(view.latestVersion()).isNull();
        assertThat(view.securityFix()).isFalse();
    }

    @Test
    @DisplayName("self-hosted with no feed yet: empty view")
    void selfHostedNoFeedYet() {
        VersionUpdateService svc = new VersionUpdateService();

        VersionUpdateView view = svc.resolve("0.1.0", true);

        assertThat(view.updateAvailable()).isFalse();
        assertThat(view.latestVersion()).isNull();
        assertThat(view.checkedAt()).isNull();
    }

    @Test
    @DisplayName("update() replaces the held status")
    void updateReplaces() {
        VersionUpdateService svc = new VersionUpdateService();
        svc.update(status("0.2.0", false));
        svc.update(status("0.3.0", false));

        assertThat(svc.resolve("0.1.0", true).latestVersion()).isEqualTo("0.3.0");
        assertThat(svc.current().latestVersion()).isEqualTo("0.3.0");
    }
}
