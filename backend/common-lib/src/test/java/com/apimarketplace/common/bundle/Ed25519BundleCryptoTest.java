package com.apimarketplace.common.bundle;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit contract for {@link Ed25519BundleCrypto}: sign/verify roundtrip,
 * tamper detection, deterministic checksum, and graceful degradation when
 * keys are missing or malformed. Uses a freshly generated Ed25519 keypair so
 * the test is hermetic and doesn't require any env config.
 */
@DisplayName("Ed25519BundleCrypto - sign + verify + checksum")
class Ed25519BundleCryptoTest {

    private static String privB64;
    private static String pubB64;

    @BeforeAll
    static void keypair() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("Ed25519");
        KeyPair kp = kpg.generateKeyPair();
        privB64 = Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded());
        pubB64  = Base64.getEncoder().encodeToString(kp.getPublic().getEncoded());
    }

    @Test
    @DisplayName("Sign then verify with matching public key → true")
    void signVerifyRoundtrip() {
        Ed25519BundleCrypto crypto = new Ed25519BundleCrypto(privB64, pubB64, "k1", "cloud");
        byte[] payload = "hello bundle".getBytes();

        String sig = crypto.sign(payload);

        assertThat(sig).isNotNull();
        assertThat(crypto.verify(payload, sig)).isTrue();
    }

    @Test
    @DisplayName("Tampered payload fails verification")
    void tamperDetection() {
        Ed25519BundleCrypto crypto = new Ed25519BundleCrypto(privB64, pubB64, "k1", "cloud");
        byte[] payload = "original".getBytes();
        String sig = crypto.sign(payload);

        assertThat(crypto.verify("modified".getBytes(), sig)).isFalse();
    }

    @Test
    @DisplayName("Garbage signature fails verification (no exception)")
    void garbageSignature() {
        Ed25519BundleCrypto crypto = new Ed25519BundleCrypto(privB64, pubB64, "k1", "cloud");

        assertThat(crypto.verify("x".getBytes(), "not-base64!!!")).isFalse();
        assertThat(crypto.verify("x".getBytes(), "YWJjZA==")).isFalse(); // valid b64, wrong sig
        assertThat(crypto.verify("x".getBytes(), null)).isFalse();
    }

    @Test
    @DisplayName("SHA-256 checksum is deterministic and 64 hex chars")
    void checksumDeterministic() {
        Ed25519BundleCrypto crypto = new Ed25519BundleCrypto(privB64, pubB64, "k1", "cloud");

        String h1 = crypto.checksum("payload-A".getBytes());
        String h2 = crypto.checksum("payload-A".getBytes());
        String h3 = crypto.checksum("payload-B".getBytes());

        assertThat(h1).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(h1).isEqualTo(h2);
        assertThat(h1).isNotEqualTo(h3);
    }

    @Test
    @DisplayName("Empty key config → canSign=false, sign() throws clear error")
    void missingKeyDegradesGracefully() {
        Ed25519BundleCrypto crypto = new Ed25519BundleCrypto("", "", "k1", "cloud");

        assertThat(crypto.canSign()).isFalse();
        assertThat(crypto.publicKeyBase64()).isNull();
        assertThatThrownBy(() -> crypto.sign("x".getBytes()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CATALOG_BUNDLE_SIGNING_KEY_PEM");
    }

    @Test
    @DisplayName("verify() with no public key → false (not exception)")
    void verifyWithoutPublicKey() {
        Ed25519BundleCrypto crypto = new Ed25519BundleCrypto("", "", "k1", "cloud");
        assertThat(crypto.verify("x".getBytes(), "YWJjZA==")).isFalse();
    }

    @Test
    @DisplayName("PEM armor + whitespace is stripped automatically")
    void pemArmorStripped() {
        String armored = "-----BEGIN PRIVATE KEY-----\n" +
                privB64.replaceAll("(.{64})", "$1\n") +
                "\n-----END PRIVATE KEY-----\n";

        Ed25519BundleCrypto crypto = new Ed25519BundleCrypto(armored, pubB64, "k1", "cloud");

        assertThat(crypto.canSign()).isTrue();
        byte[] payload = "roundtrip".getBytes();
        assertThat(crypto.verify(payload, crypto.sign(payload))).isTrue();
    }

    @Test
    @DisplayName("Different keypair cannot verify signature")
    void crossKeyVerificationFails() throws Exception {
        Ed25519BundleCrypto crypto1 = new Ed25519BundleCrypto(privB64, pubB64, "k1", "cloud");
        String sig = crypto1.sign("p".getBytes());

        // Different keypair
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("Ed25519");
        KeyPair kp2 = kpg.generateKeyPair();
        String otherPub = Base64.getEncoder().encodeToString(kp2.getPublic().getEncoded());

        Ed25519BundleCrypto crypto2 = new Ed25519BundleCrypto("", otherPub, "k1", "cloud");
        assertThat(crypto2.verify("p".getBytes(), sig)).isFalse();
    }

    @Test
    @DisplayName("Malformed base64 key → canSign=false, no crash")
    void malformedKey() {
        Ed25519BundleCrypto crypto = new Ed25519BundleCrypto("!!!not-base64!!!", "", "k1", "cloud");
        assertThat(crypto.canSign()).isFalse();
    }

    @Test
    @DisplayName("keyId, issuer and publicKeyBase64 accessors return configured values")
    void accessors() {
        Ed25519BundleCrypto crypto = new Ed25519BundleCrypto(privB64, pubB64, "rotate-2026-04", "acme-corp");
        assertThat(crypto.keyId()).isEqualTo("rotate-2026-04");
        assertThat(crypto.issuer()).isEqualTo("acme-corp");
        assertThat(crypto.publicKeyBase64()).isEqualTo(pubB64);
    }
}
