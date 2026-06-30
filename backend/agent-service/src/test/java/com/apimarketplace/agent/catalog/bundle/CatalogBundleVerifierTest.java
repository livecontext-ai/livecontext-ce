package com.apimarketplace.agent.catalog.bundle;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Security contract: every failure mode must return a structured status so
 * the scheduler can persist the reason without throwing.
 *
 * <p>Built around a freshly-generated keypair - the trust registry is loaded
 * from a single string property, so we exercise the real wiring too.
 */
@DisplayName("CatalogBundleVerifier - pinned trust + tamper detection")
class CatalogBundleVerifierTest {

    private static String privB64;
    private static String pubB64;
    private static CatalogBundleSigner signer;

    @BeforeAll
    static void keypair() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("Ed25519");
        KeyPair kp = kpg.generateKeyPair();
        privB64 = Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded());
        pubB64  = Base64.getEncoder().encodeToString(kp.getPublic().getEncoded());
        signer = new CatalogBundleSigner(privB64, pubB64, "k1", "cloud");
    }

    private CatalogBundleVerifier verifierWithTrust(String trustedKeysProp) {
        return new CatalogBundleVerifier(new TrustedKeyRegistry(trustedKeysProp));
    }

    private SignedBundle sign(byte[] payload, String keyId) {
        String sig = signer.sign(payload);
        String checksum = signer.checksum(payload);
        return new SignedBundle(
                1L, 1, checksum, sig, keyId, "cloud", 1, payload.length,
                Base64.getEncoder().encodeToString(payload));
    }

    @Test
    @DisplayName("Valid bundle with pinned key → OK")
    void happyPath() {
        byte[] payload = "{\"version\":1}".getBytes();
        SignedBundle sb = sign(payload, "k1");

        CatalogBundleVerifier.Result r =
                verifierWithTrust("k1=" + pubB64).verify(sb);

        assertThat(r.ok()).isTrue();
        assertThat(r.status()).isEqualTo(CatalogBundleVerifier.Status.OK);
        assertThat(r.payloadBytes()).isEqualTo(payload);
        assertThat(r.detail()).isNull();
    }

    @Test
    @DisplayName("Unknown keyId → TRUST_UNKNOWN (never throws)")
    void unknownKeyId() {
        byte[] payload = "x".getBytes();
        SignedBundle sb = sign(payload, "k1");

        // Empty trust list
        CatalogBundleVerifier.Result r =
                verifierWithTrust("").verify(sb);

        assertThat(r.ok()).isFalse();
        assertThat(r.status()).isEqualTo(CatalogBundleVerifier.Status.TRUST_UNKNOWN);
        assertThat(r.detail()).contains("k1");
    }

    @Test
    @DisplayName("Right trust key, wrong keyId on bundle → TRUST_UNKNOWN")
    void keyIdMismatch() {
        byte[] payload = "x".getBytes();
        SignedBundle sb = sign(payload, "k-other");

        CatalogBundleVerifier.Result r =
                verifierWithTrust("k1=" + pubB64).verify(sb);

        assertThat(r.ok()).isFalse();
        assertThat(r.status()).isEqualTo(CatalogBundleVerifier.Status.TRUST_UNKNOWN);
    }

    @Test
    @DisplayName("Tampered payload (checksum mismatch) → CHECKSUM_INVALID")
    void checksumTamper() {
        byte[] original = "original".getBytes();
        SignedBundle clean = sign(original, "k1");

        // Craft bundle whose payloadBase64 differs from what the checksum covers.
        byte[] tampered = "tampered".getBytes();
        SignedBundle mutated = new SignedBundle(
                clean.version(), clean.schemaVersion(),
                clean.checksum(), clean.signature(),
                clean.signingKeyId(), clean.issuer(),
                clean.modelCount(), clean.rawBytesSize(),
                Base64.getEncoder().encodeToString(tampered));

        CatalogBundleVerifier.Result r =
                verifierWithTrust("k1=" + pubB64).verify(mutated);

        assertThat(r.ok()).isFalse();
        assertThat(r.status()).isEqualTo(CatalogBundleVerifier.Status.CHECKSUM_INVALID);
        assertThat(r.detail()).contains("sha256 mismatch");
    }

    @Test
    @DisplayName("Right checksum but wrong signature → SIGNATURE_INVALID")
    void signatureTamper() throws Exception {
        byte[] payload = "x".getBytes();
        String realChecksum = hex(MessageDigest.getInstance("SHA-256").digest(payload));
        // Fake signature: valid base64, right length but doesn't match.
        String fakeSig = Base64.getEncoder().encodeToString(new byte[64]);
        SignedBundle mutated = new SignedBundle(
                1L, 1, realChecksum, fakeSig, "k1", "cloud", 1, payload.length,
                Base64.getEncoder().encodeToString(payload));

        CatalogBundleVerifier.Result r =
                verifierWithTrust("k1=" + pubB64).verify(mutated);

        assertThat(r.ok()).isFalse();
        assertThat(r.status()).isEqualTo(CatalogBundleVerifier.Status.SIGNATURE_INVALID);
    }

    @Test
    @DisplayName("Malformed base64 payload → PAYLOAD_MALFORMED")
    void malformedPayload() {
        SignedBundle bad = new SignedBundle(
                1L, 1, "a".repeat(64), "sig", "k1", "cloud", 0, 0, "!!!not-base64!!!");

        CatalogBundleVerifier.Result r =
                verifierWithTrust("k1=" + pubB64).verify(bad);

        assertThat(r.ok()).isFalse();
        assertThat(r.status()).isEqualTo(CatalogBundleVerifier.Status.PAYLOAD_MALFORMED);
    }

    @Test
    @DisplayName("Null bundle → PAYLOAD_MALFORMED (never throws)")
    void nullBundle() {
        CatalogBundleVerifier.Result r =
                verifierWithTrust("k1=" + pubB64).verify(null);

        assertThat(r.ok()).isFalse();
        assertThat(r.status()).isEqualTo(CatalogBundleVerifier.Status.PAYLOAD_MALFORMED);
    }

    @Test
    @DisplayName("Rotated key scenario: bundle signed with k-old, CE trusts k-old AND k-new → OK")
    void rotatedKeyRetainsOldTrust() {
        byte[] payload = "rotate".getBytes();
        SignedBundle sb = sign(payload, "k1");

        CatalogBundleVerifier.Result r =
                verifierWithTrust("k-new=" + pubB64 + "; k1=" + pubB64).verify(sb);

        assertThat(r.ok()).isTrue();
    }

    @Test
    @DisplayName("TrustedKeyRegistry skips malformed entries but keeps the good ones")
    void trustRegistryTolerantOfGarbage() {
        TrustedKeyRegistry reg = new TrustedKeyRegistry(
                "bad-entry-no-equals; nokey=; k1=" + pubB64 + "; k2=!!!!garbage!!!");

        assertThat(reg.hasKeys()).isTrue();
        assertThat(reg.keyIds()).containsExactly("k1");
        assertThat(reg.find("k1")).isPresent();
        assertThat(reg.find("k2")).isEmpty();
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(Character.forDigit((x >> 4) & 0xF, 16));
            sb.append(Character.forDigit(x & 0xF, 16));
        }
        return sb.toString();
    }
}
