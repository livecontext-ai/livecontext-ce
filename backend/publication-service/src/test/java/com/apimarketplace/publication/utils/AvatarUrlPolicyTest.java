package com.apimarketplace.publication.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Single cross-tenant avatar rule shared by snapshot build, clone and moderation.
 * A value survives ONLY if it renders for any viewer from a plain img tag.
 */
class AvatarUrlPolicyTest {

    @Test
    @DisplayName("presets survive - including the customized color and tool-badge forms")
    void keepsPresets() {
        assertThat(AvatarUrlPolicy.publishable("preset:purple")).isEqualTo("preset:purple");
        assertThat(AvatarUrlPolicy.publishable("preset:purple?c1=FF0000&c2=00FF00"))
                .isEqualTo("preset:purple?c1=FF0000&c2=00FF00");
        // Tool badge (with or without colors) keeps the 'preset:' prefix, so a decorated
        // avatar survives publish/acquire unchanged - the frontend re-renders it anywhere.
        assertThat(AvatarUrlPolicy.publishable("preset:purple?tool=wrench"))
                .isEqualTo("preset:purple?tool=wrench");
        assertThat(AvatarUrlPolicy.publishable("preset:purple?c1=FF0000&c2=00FF00&tool=rocket"))
                .isEqualTo("preset:purple?c1=FF0000&c2=00FF00&tool=rocket");
    }

    @Test
    @DisplayName("http(s) and the anonymous avatar serve survive")
    void keepsPublicUrls() {
        assertThat(AvatarUrlPolicy.publishable("https://cdn/x.png")).isEqualTo("https://cdn/x.png");
        assertThat(AvatarUrlPolicy.publishable("/api/proxy/files/avatar/123e4567-e89b-12d3-a456-426614174000"))
                .isEqualTo("/api/proxy/files/avatar/123e4567-e89b-12d3-a456-426614174000");
    }

    @Test
    @DisplayName("auth-gated by-id URLs and anything else are dropped")
    void dropsPrivateUrls() {
        assertThat(AvatarUrlPolicy.publishable("/api/proxy/files/by-id/abc/raw?disposition=inline")).isNull();
        assertThat(AvatarUrlPolicy.publishable("tenant-1/general/avatar/x.png")).isNull();
        assertThat(AvatarUrlPolicy.publishable(null)).isNull();
    }
}
