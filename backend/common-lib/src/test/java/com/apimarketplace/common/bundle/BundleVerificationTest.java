package com.apimarketplace.common.bundle;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Security contract for {@link BundleVerification}: every failure mode must
 * return a structured status so callers can persist the reason without
 * throwing. Built around a freshly-generated keypair and the real
 * {@link TrustedKeys} parsing.
 */
@DisplayName("BundleVerification - pinned trust + tamper detection")
class BundleVerificationTest {

    private static String pubB64;
    private static Ed25519BundleCrypto crypto;

    @BeforeAll
    static void keypair() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("Ed25519");
        KeyPair kp = kpg.generateKeyPair();
        String privB64 = Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded());
        pubB64 = Base64.getEncoder().encodeToString(kp.getPublic().getEncoded());
        crypto = new Ed25519BundleCrypto(privB64, pubB64, "k1", "cloud");
    }

    @Test
    @DisplayName("Valid signature + checksum with pinned key → OK with payload bytes")
    void happyPath() {
        byte[] payload = "{\"version\":1}".getBytes();
        String sig = crypto.sign(payload);
        String checksum = crypto.checksum(payload);

        BundleVerification.Result r = BundleVerification.verify(
                new TrustedKeys("k1=" + pubB64),
                "k1", checksum, sig, Base64.getEncoder().encodeToString(payload));

        assertThat(r.ok()).isTrue();
        assertThat(r.status()).isEqualTo(BundleVerification.Status.OK);
        assertThat(r.payloadBytes()).isEqualTo(payload);
        assertThat(r.detail()).isNull();
    }

    @Test
    @DisplayName("Unknown signing keyId → TRUST_UNKNOWN (never throws)")
    void unknownKeyId() {
        byte[] payload = "x".getBytes();

        BundleVerification.Result r = BundleVerification.verify(
                new TrustedKeys(""),
                "k1", crypto.checksum(payload), crypto.sign(payload),
                Base64.getEncoder().encodeToString(payload));

        assertThat(r.ok()).isFalse();
        assertThat(r.status()).isEqualTo(BundleVerification.Status.TRUST_UNKNOWN);
        assertThat(r.detail()).contains("k1");
        assertThat(r.payloadBytes()).isNull();
    }

    @Test
    @DisplayName("Checksum covering different bytes than the payload → CHECKSUM_INVALID")
    void checksumMismatch() {
        byte[] original = "original".getBytes();
        byte[] tampered = "tampered".getBytes();

        BundleVerification.Result r = BundleVerification.verify(
                new TrustedKeys("k1=" + pubB64),
                "k1", crypto.checksum(original), crypto.sign(original),
                Base64.getEncoder().encodeToString(tampered));

        assertThat(r.ok()).isFalse();
        assertThat(r.status()).isEqualTo(BundleVerification.Status.CHECKSUM_INVALID);
        assertThat(r.detail()).contains("sha256 mismatch");
    }

    @Test
    @DisplayName("Right checksum but wrong signature → SIGNATURE_INVALID")
    void signatureMismatch() {
        byte[] payload = "x".getBytes();
        // Fake signature: valid base64, right length but doesn't match.
        String fakeSig = Base64.getEncoder().encodeToString(new byte[64]);

        BundleVerification.Result r = BundleVerification.verify(
                new TrustedKeys("k1=" + pubB64),
                "k1", crypto.checksum(payload), fakeSig,
                Base64.getEncoder().encodeToString(payload));

        assertThat(r.ok()).isFalse();
        assertThat(r.status()).isEqualTo(BundleVerification.Status.SIGNATURE_INVALID);
    }

    @Test
    @DisplayName("Garbage base64 signature → SIGNATURE_INVALID (never throws)")
    void garbageSignature() {
        byte[] payload = "x".getBytes();

        BundleVerification.Result r = BundleVerification.verify(
                new TrustedKeys("k1=" + pubB64),
                "k1", crypto.checksum(payload), "!!!not-base64!!!",
                Base64.getEncoder().encodeToString(payload));

        assertThat(r.ok()).isFalse();
        assertThat(r.status()).isEqualTo(BundleVerification.Status.SIGNATURE_INVALID);
    }

    @Test
    @DisplayName("Malformed base64 payload → PAYLOAD_MALFORMED")
    void malformedPayload() {
        BundleVerification.Result r = BundleVerification.verify(
                new TrustedKeys("k1=" + pubB64),
                "k1", "a".repeat(64), "sig", "!!!not-base64!!!");

        assertThat(r.ok()).isFalse();
        assertThat(r.status()).isEqualTo(BundleVerification.Status.PAYLOAD_MALFORMED);
    }

    @Test
    @DisplayName("Checksum comparison is case-insensitive (uppercase hex accepted)")
    void checksumCaseInsensitive() {
        byte[] payload = "case".getBytes();

        BundleVerification.Result r = BundleVerification.verify(
                new TrustedKeys("k1=" + pubB64),
                "k1", crypto.checksum(payload).toUpperCase(), crypto.sign(payload),
                Base64.getEncoder().encodeToString(payload));

        assertThat(r.ok()).isTrue();
    }

    @Test
    @DisplayName("Key rotation: bundle signed with old keyId verifies when both keys are pinned")
    void rotatedKeyRetainsOldTrust() {
        byte[] payload = "rotate".getBytes();

        BundleVerification.Result r = BundleVerification.verify(
                new TrustedKeys("k-new=" + pubB64 + "; k1=" + pubB64),
                "k1", crypto.checksum(payload), crypto.sign(payload),
                Base64.getEncoder().encodeToString(payload));

        assertThat(r.ok()).isTrue();
    }
}
