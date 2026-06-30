package com.apimarketplace.auth.audit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AuditPseudonymizer")
class AuditPseudonymizerTest {

    private AuditPseudonymizer pseudonymizer;

    @BeforeEach
    void setUp() {
        // Use a fixed pepper so UA hashes are reproducible inside the test JVM.
        String pepper = Base64.getEncoder().encodeToString(new byte[]{
                1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16,
                17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32
        });
        pseudonymizer = new AuditPseudonymizer(pepper);
        pseudonymizer.rotateSalt(); // initialize the daily salt
    }

    @Test
    @DisplayName("hashIp returns null for null/blank input")
    void hashIp_nullOrBlank_returnsNull() {
        assertThat(pseudonymizer.hashIp(null)).isNull();
        assertThat(pseudonymizer.hashIp("")).isNull();
        assertThat(pseudonymizer.hashIp("   ")).isNull();
    }

    @Test
    @DisplayName("hashIp produces a 64-char hex string for ASCII IP")
    void hashIp_asciiIp_returnsSha256Hex() {
        String hash = pseudonymizer.hashIp("192.168.1.1");
        assertThat(hash).isNotNull().hasSize(64).matches("[0-9a-f]+");
    }

    @Test
    @DisplayName("hashIp is stable for the same IP within a salt window")
    void hashIp_sameInputSameSalt_stable() {
        String h1 = pseudonymizer.hashIp("10.0.0.1");
        String h2 = pseudonymizer.hashIp("10.0.0.1");
        assertThat(h1).isEqualTo(h2);
    }

    @Test
    @DisplayName("hashIp produces different hashes for different IPs")
    void hashIp_differentInputs_differentOutputs() {
        assertThat(pseudonymizer.hashIp("10.0.0.1"))
                .isNotEqualTo(pseudonymizer.hashIp("10.0.0.2"));
    }

    @Test
    @DisplayName("hashIp does NOT silently truncate multi-byte UTF-8 IPs (regression test for prod bug)")
    void hashIp_multiByteUtf8_doesNotTruncate() {
        // The pre-fix code sized the buffer via ip.length() (UTF-16 code units),
        // then arraycopy used Math.min so multi-byte UTF-8 sequences would be
        // silently truncated, producing collisions for distinct inputs.
        // We construct two strings whose first chars match but whose UTF-8 byte
        // length exceeds their char length - under the bug they would collide.
        String a = "1.1.1.1\u00e9"; // accented char (2 bytes UTF-8)
        String b = "1.1.1.1\u00e8"; // different accented char (2 bytes UTF-8)
        String h1 = pseudonymizer.hashIp(a);
        String h2 = pseudonymizer.hashIp(b);
        assertThat(h1).isNotEqualTo(h2);
        assertThat(h1).hasSize(64);
        assertThat(h2).hasSize(64);
    }

    @Test
    @DisplayName("rotateSalt produces different hash for the same IP after rotation")
    void rotateSalt_changesHashForSameIp() {
        String before = pseudonymizer.hashIp("10.0.0.1");
        pseudonymizer.rotateSalt();
        String after = pseudonymizer.hashIp("10.0.0.1");
        assertThat(before).isNotEqualTo(after);
    }

    @Test
    @DisplayName("hashUserAgent returns null for null/blank input")
    void hashUserAgent_nullOrBlank_returnsNull() {
        assertThat(pseudonymizer.hashUserAgent(null)).isNull();
        assertThat(pseudonymizer.hashUserAgent("")).isNull();
        assertThat(pseudonymizer.hashUserAgent("  ")).isNull();
    }

    @Test
    @DisplayName("hashUserAgent is stable for the same UA across calls")
    void hashUserAgent_sameInput_stable() {
        String ua = "Mozilla/5.0 (X11; Linux) AppleWebKit/537";
        assertThat(pseudonymizer.hashUserAgent(ua))
                .isEqualTo(pseudonymizer.hashUserAgent(ua))
                .hasSize(64);
    }

    @Test
    @DisplayName("hashUserAgent does not depend on the daily IP salt")
    void hashUserAgent_unaffectedByIpSaltRotation() {
        String ua = "test-ua";
        String before = pseudonymizer.hashUserAgent(ua);
        pseudonymizer.rotateSalt();
        String after = pseudonymizer.hashUserAgent(ua);
        assertThat(before).isEqualTo(after);
    }
}
