package com.apimarketplace.agent.catalog.bundle;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit contract for {@link CatalogBundleSigner}: sign/verify roundtrip,
 * tamper detection, and deterministic checksum. Uses a freshly generated
 * Ed25519 keypair so the test is hermetic and doesn't require any env config.
 */
@DisplayName("CatalogBundleSigner - sign + verify + checksum")
class CatalogBundleSignerTest {

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
        CatalogBundleSigner signer = new CatalogBundleSigner(privB64, pubB64, "k1", "cloud");
        byte[] payload = "hello catalog".getBytes();

        String sig = signer.sign(payload);

        assertThat(sig).isNotNull();
        assertThat(signer.verify(payload, sig)).isTrue();
    }

    @Test
    @DisplayName("Tampered payload fails verification")
    void tamperDetection() {
        CatalogBundleSigner signer = new CatalogBundleSigner(privB64, pubB64, "k1", "cloud");
        byte[] payload = "original".getBytes();
        String sig = signer.sign(payload);

        assertThat(signer.verify("modified".getBytes(), sig)).isFalse();
    }

    @Test
    @DisplayName("Garbage signature fails verification (no exception)")
    void garbageSignature() {
        CatalogBundleSigner signer = new CatalogBundleSigner(privB64, pubB64, "k1", "cloud");

        assertThat(signer.verify("x".getBytes(), "not-base64!!!")).isFalse();
        assertThat(signer.verify("x".getBytes(), "YWJjZA==")).isFalse(); // valid b64, wrong sig
    }

    @Test
    @DisplayName("SHA-256 checksum is deterministic and 64 hex chars")
    void checksumDeterministic() {
        CatalogBundleSigner signer = new CatalogBundleSigner(privB64, pubB64, "k1", "cloud");

        String h1 = signer.checksum("payload-A".getBytes());
        String h2 = signer.checksum("payload-A".getBytes());
        String h3 = signer.checksum("payload-B".getBytes());

        assertThat(h1).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(h1).isEqualTo(h2);
        assertThat(h1).isNotEqualTo(h3);
    }

    @Test
    @DisplayName("Empty key config → canSign=false, sign() throws clear error")
    void missingKeyDegradesGracefully() {
        CatalogBundleSigner signer = new CatalogBundleSigner("", "", "k1", "cloud");

        assertThat(signer.canSign()).isFalse();
        assertThat(signer.publicKeyBase64()).isNull();
        assertThatThrownBy(() -> signer.sign("x".getBytes()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CATALOG_BUNDLE_SIGNING_KEY_PEM");
    }

    @Test
    @DisplayName("verify() with no public key → false (not exception)")
    void verifyWithoutPublicKey() {
        CatalogBundleSigner signer = new CatalogBundleSigner("", "", "k1", "cloud");
        assertThat(signer.verify("x".getBytes(), "YWJjZA==")).isFalse();
    }

    @Test
    @DisplayName("PEM armor + whitespace is stripped automatically")
    void pemArmorStripped() {
        String armored = "-----BEGIN PRIVATE KEY-----\n" +
                privB64.replaceAll("(.{64})", "$1\n") +
                "\n-----END PRIVATE KEY-----\n";

        CatalogBundleSigner signer = new CatalogBundleSigner(armored, pubB64, "k1", "cloud");

        assertThat(signer.canSign()).isTrue();
        byte[] payload = "roundtrip".getBytes();
        assertThat(signer.verify(payload, signer.sign(payload))).isTrue();
    }

    @Test
    @DisplayName("Different keypair cannot verify signature")
    void crossKeyVerificationFails() throws Exception {
        CatalogBundleSigner signer1 = new CatalogBundleSigner(privB64, pubB64, "k1", "cloud");
        String sig = signer1.sign("p".getBytes());

        // Different keypair
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("Ed25519");
        KeyPair kp2 = kpg.generateKeyPair();
        String otherPub = Base64.getEncoder().encodeToString(kp2.getPublic().getEncoded());

        CatalogBundleSigner signer2 = new CatalogBundleSigner("", otherPub, "k1", "cloud");
        assertThat(signer2.verify("p".getBytes(), sig)).isFalse();
    }

    @Test
    @DisplayName("Malformed base64 key → canSign=false, no crash")
    void malformedKey() {
        CatalogBundleSigner signer = new CatalogBundleSigner("!!!not-base64!!!", "", "k1", "cloud");
        assertThat(signer.canSign()).isFalse();
    }

    @Test
    @DisplayName("keyId and issuer accessors return configured values")
    void accessors() {
        CatalogBundleSigner signer = new CatalogBundleSigner(privB64, pubB64, "rotate-2026-04", "acme-corp");
        assertThat(signer.keyId()).isEqualTo("rotate-2026-04");
        assertThat(signer.issuer()).isEqualTo("acme-corp");
        assertThat(signer.publicKeyBase64()).isEqualTo(pubB64);
    }
}
