package com.apimarketplace.auth.web.version;

import com.apimarketplace.auth.web.version.CeReleaseController.LatestRelease;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for the cloud-side {@link CeReleaseController} release feed. */
class CeReleaseControllerTest {

    @Test
    @DisplayName("configured release is advertised verbatim")
    void configuredRelease() {
        CeReleaseController controller = new CeReleaseController(
                "0.3.0", "https://example.test/releases/0.3.0", true, "2026-06-25T09:00:00Z");

        LatestRelease r = controller.latest("0.2.0");

        assertThat(r.latestVersion()).isEqualTo("0.3.0");
        assertThat(r.releaseUrl()).isEqualTo("https://example.test/releases/0.3.0");
        assertThat(r.securityFix()).isTrue();
        assertThat(r.publishedAt()).isEqualTo("2026-06-25T09:00:00Z");
    }

    @Test
    @DisplayName("unconfigured (blank) release advertises no version (nulls), not empty strings")
    void unconfiguredRelease() {
        CeReleaseController controller = new CeReleaseController("", "  ", false, "");

        LatestRelease r = controller.latest(null);

        assertThat(r.latestVersion()).isNull();
        assertThat(r.releaseUrl()).isNull();
        assertThat(r.securityFix()).isFalse();
        assertThat(r.publishedAt()).isNull();
    }
}
