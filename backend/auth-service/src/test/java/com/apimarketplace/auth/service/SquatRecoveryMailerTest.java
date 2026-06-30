package com.apimarketplace.auth.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the squat-recovery consume URL the email links to. The recover page was
 * unified under {@code /settings/cloud-account}; the legacy
 * {@code /settings/cloud-link/recover} route now only redirects there, so the
 * email must point at the canonical path (else recovery dead-ends on a redirect
 * shim that may be removed later).
 */
class SquatRecoveryMailerTest {

    @Test
    @DisplayName("recoveryUrl targets the unified cloud-account recover page, not the legacy cloud-link route")
    void recoveryUrlTargetsCloudAccount() {
        String url = SquatRecoveryMailer.recoveryUrl("https://livecontext.ai", "tok-123");

        assertThat(url).isEqualTo("https://livecontext.ai/settings/cloud-account/recover/tok-123");
        assertThat(url).doesNotContain("/cloud-link/");
    }

    @Test
    @DisplayName("recoveryUrl puts the token in the path segment (kept out of any query string)")
    void recoveryUrlKeepsTokenInPath() {
        String url = SquatRecoveryMailer.recoveryUrl("http://localhost:3000", "abc_DEF-456");

        assertThat(url).isEqualTo("http://localhost:3000/settings/cloud-account/recover/abc_DEF-456");
        assertThat(url).doesNotContain("?");
    }
}
