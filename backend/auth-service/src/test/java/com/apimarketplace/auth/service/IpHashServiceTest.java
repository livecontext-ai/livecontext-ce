package com.apimarketplace.auth.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

@DisplayName("IpHashService")
class IpHashServiceTest {

    private static final UUID INSTALL = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private static IpHashService bothKeysCurrent1() {
        IpHashService s = new IpHashService("v1-secret-key", "v2-secret-key", 1);
        s.validateConfiguration();
        return s;
    }

    private static IpHashService bothKeysCurrent2() {
        IpHashService s = new IpHashService("v1-secret-key", "v2-secret-key", 2);
        s.validateConfiguration();
        return s;
    }

    @Test
    @DisplayName("hashWithCurrent is deterministic for the same (install_id, ip) under the same key generation")
    void hash_is_deterministic() {
        IpHashService s = bothKeysCurrent1();

        IpHashService.HashResult a = s.hashWithCurrent(INSTALL, "203.0.113.5");
        IpHashService.HashResult b = s.hashWithCurrent(INSTALL, "203.0.113.5");

        assertThat(a.hash()).isEqualTo(b.hash());
        assertThat(a.keyVersion()).isEqualTo(1);
    }

    @Test
    @DisplayName("hashWithCurrentVariesByIp - different IPs under the same install hash to distinct values (no collision oracle)")
    void hash_varies_by_ip() {
        IpHashService s = bothKeysCurrent1();

        String a = s.hashWithCurrent(INSTALL, "203.0.113.5").hash();
        String b = s.hashWithCurrent(INSTALL, "198.51.100.7").hash();

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    @DisplayName("hashWithCurrentVariesByInstall - same IP under different install_ids hashes distinctly (per-install salt)")
    void hash_varies_by_install() {
        IpHashService s = bothKeysCurrent1();
        UUID other = UUID.fromString("22222222-3333-4444-5555-666666666666");

        String a = s.hashWithCurrent(INSTALL, "203.0.113.5").hash();
        String b = s.hashWithCurrent(other, "203.0.113.5").hash();

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    @DisplayName("v1AndV2KeysProduceDistinctHashes - verifying with the wrong key generation must fail-closed")
    void v1_and_v2_produce_distinct_hashes() {
        IpHashService s1 = bothKeysCurrent1();
        IpHashService s2 = bothKeysCurrent2();

        String h1 = s1.hashWithCurrent(INSTALL, "203.0.113.5").hash();
        String h2 = s2.hashWithCurrent(INSTALL, "203.0.113.5").hash();

        assertThat(h1).isNotEqualTo(h2);
    }

    @Test
    @DisplayName("matches verifies under the requested historical key_version (rotation-safe)")
    void matches_uses_requested_key_version() {
        IpHashService s = bothKeysCurrent2();

        // Persist a hash under V1 (historical row).
        String v1Hash = new IpHashService("v1-secret-key", "v2-secret-key", 1)
                .hashWithCurrent(INSTALL, "203.0.113.5").hash();

        // Service has CURRENT=V2 but the stored row says key_version=1 - verify must
        // dispatch back to V1 secret to confirm same IP.
        assertThat(s.matches(INSTALL, "203.0.113.5", v1Hash, 1)).isTrue();
        // Different IP → must NOT match under V1.
        assertThat(s.matches(INSTALL, "198.51.100.7", v1Hash, 1)).isFalse();
    }

    @Test
    @DisplayName("matchesFailsClosedWhenKeyVersionUnconfigured - post-rotation row read after V1 retired returns false (ip change), not crash")
    void matches_fails_closed_when_key_version_unconfigured() {
        // V1 retired: only V2 configured. Reading a historical V1 row → treat as ip change.
        IpHashService s = new IpHashService("", "v2-secret-key", 2);
        s.validateConfiguration();

        assertThat(s.matches(INSTALL, "203.0.113.5", "any-hash", 1)).isFalse();
    }

    @Test
    @DisplayName("validateConfigurationRejectsBadCurrentVersion - current-version outside {1,2} fails boot, not a silent runtime crash")
    void rejects_bad_current_version() {
        IpHashService s = new IpHashService("v1", "v2", 9);
        assertThatIllegalStateException().isThrownBy(s::validateConfiguration);
    }

    @Test
    @DisplayName("validateConfigurationRejectsMissingCurrentKey - current=1 without V1 secret fails boot")
    void rejects_missing_current_key() {
        IpHashService s = new IpHashService("", "v2", 1);
        assertThatIllegalStateException().isThrownBy(s::validateConfiguration);
    }

    @Test
    @DisplayName("matchesWithNullInputsReturnsFalse - defensive fail-closed")
    void matches_with_nulls_returns_false() {
        IpHashService s = bothKeysCurrent1();

        assertThat(s.matches(INSTALL, null, "anything", 1)).isFalse();
        assertThat(s.matches(INSTALL, "203.0.113.5", null, 1)).isFalse();
    }
}
