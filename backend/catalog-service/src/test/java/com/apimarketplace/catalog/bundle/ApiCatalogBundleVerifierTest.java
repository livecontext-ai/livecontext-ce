package com.apimarketplace.catalog.bundle;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verify pipeline contract with REAL Ed25519 keys: trust-list gate → checksum
 * → signature, over the GZIPPED payload bytes (the bytes the cloud signed).
 */
@DisplayName("ApiCatalogBundleVerifier - trust/checksum/signature gates")
class ApiCatalogBundleVerifierTest {

    private ApiCatalogBundleSigner signer;
    private ApiCatalogBundleVerifier verifier;
    private byte[] gzPayload;

    @BeforeEach
    void setUp() throws Exception {
        KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String priv = Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded());
        String pub  = Base64.getEncoder().encodeToString(kp.getPublic().getEncoded());

        signer = new ApiCatalogBundleSigner(priv, pub, "k1", "cloud");
        verifier = new ApiCatalogBundleVerifier(
                new ApiCatalogTrustedKeyRegistry("k1=" + pub));
        gzPayload = ApiCatalogBundlePayload.gzip("{\"apis\":[]}".getBytes());
    }

    private ApiCatalogSignedBundle bundle(String keyId, String checksum, String signature) {
        return new ApiCatalogSignedBundle(1L, 1, checksum, signature, keyId, "cloud",
                0, 0, 12, Base64.getEncoder().encodeToString(gzPayload));
    }

    @Test
    @DisplayName("Valid bundle verifies OK and hands back the decoded gzip bytes")
    void validBundleOk() {
        ApiCatalogBundleVerifier.Result r = verifier.verify(
                bundle("k1", signer.checksum(gzPayload), signer.sign(gzPayload)));

        assertThat(r.ok()).isTrue();
        assertThat(r.status()).isEqualTo(ApiCatalogBundleVerifier.Status.OK);
        assertThat(r.payloadBytes()).isEqualTo(gzPayload);
    }

    @Test
    @DisplayName("Unknown signing key id → TRUST_UNKNOWN")
    void unknownKeyRejected() {
        ApiCatalogBundleVerifier.Result r = verifier.verify(
                bundle("rogue-key", signer.checksum(gzPayload), signer.sign(gzPayload)));

        assertThat(r.ok()).isFalse();
        assertThat(r.status()).isEqualTo(ApiCatalogBundleVerifier.Status.TRUST_UNKNOWN);
        assertThat(r.payloadBytes()).isNull();
    }

    @Test
    @DisplayName("Checksum mismatch → CHECKSUM_INVALID")
    void checksumMismatchRejected() {
        ApiCatalogBundleVerifier.Result r = verifier.verify(
                bundle("k1", "0".repeat(64), signer.sign(gzPayload)));

        assertThat(r.ok()).isFalse();
        assertThat(r.status()).isEqualTo(ApiCatalogBundleVerifier.Status.CHECKSUM_INVALID);
    }

    @Test
    @DisplayName("Signature over different bytes → SIGNATURE_INVALID")
    void badSignatureRejected() {
        byte[] otherBytes = ApiCatalogBundlePayload.gzip("{\"apis\":[1]}".getBytes());
        ApiCatalogBundleVerifier.Result r = verifier.verify(
                bundle("k1", signer.checksum(gzPayload), signer.sign(otherBytes)));

        assertThat(r.ok()).isFalse();
        assertThat(r.status()).isEqualTo(ApiCatalogBundleVerifier.Status.SIGNATURE_INVALID);
    }

    @Test
    @DisplayName("Malformed base64 payload → PAYLOAD_MALFORMED")
    void malformedPayloadRejected() {
        ApiCatalogSignedBundle b = new ApiCatalogSignedBundle(1L, 1,
                signer.checksum(gzPayload), signer.sign(gzPayload), "k1", "cloud",
                0, 0, 12, "%%%not-base64%%%");

        ApiCatalogBundleVerifier.Result r = verifier.verify(b);

        assertThat(r.ok()).isFalse();
        assertThat(r.status()).isEqualTo(ApiCatalogBundleVerifier.Status.PAYLOAD_MALFORMED);
    }

    @Test
    @DisplayName("Null bundle → PAYLOAD_MALFORMED (never throws)")
    void nullBundleRejected() {
        ApiCatalogBundleVerifier.Result r = verifier.verify(null);
        assertThat(r.ok()).isFalse();
        assertThat(r.status()).isEqualTo(ApiCatalogBundleVerifier.Status.PAYLOAD_MALFORMED);
    }

    @Test
    @DisplayName("Empty trust list rejects everything (cloud-side default)")
    void emptyTrustListRejectsAll() {
        ApiCatalogBundleVerifier noTrust = new ApiCatalogBundleVerifier(
                new ApiCatalogTrustedKeyRegistry(""));

        ApiCatalogBundleVerifier.Result r = noTrust.verify(
                bundle("k1", signer.checksum(gzPayload), signer.sign(gzPayload)));

        assertThat(r.ok()).isFalse();
        assertThat(r.status()).isEqualTo(ApiCatalogBundleVerifier.Status.TRUST_UNKNOWN);
    }
}
