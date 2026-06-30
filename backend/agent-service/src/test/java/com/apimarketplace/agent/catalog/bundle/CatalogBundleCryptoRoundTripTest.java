package com.apimarketplace.agent.catalog.bundle;

import com.apimarketplace.agent.domain.ModelConfigOverrideEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.math.BigDecimal;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full MODEL-bundle publisher↔consumer round trip wired from the REAL classes,
 * no mocks on the crypto seam: a model-catalog snapshot →
 * {@link CatalogBundlePayload#canonicalBytes} → {@link CatalogBundleSigner} →
 * {@link SignedBundle} → {@link CatalogBundleVerifier} (pinned via a real
 * {@link TrustedKeyRegistry}).
 *
 * <p>Plain JUnit (no Spring slice - agent-service deliberately has none; the
 * apply→DB seam is covered by {@code CatalogBundleApplierTest} and proven LIVE
 * by the model-catalog seed e2e, which drives the same {@code CatalogMergeService}
 * against a real monolith DB). This test pins the parts a component test can
 * miss: that a payload built from real entities verifies end-to-end, that the
 * publisher field names survive the wire (drift between
 * {@code CatalogBundlePayload} and the applier would silently break CE sync),
 * and - critically - that every forgery/tamper is REJECTED.
 *
 * <p>Keys are generated per-run (ephemeral, never prod): the test signs with a
 * private key and the verifier trusts only its matching public key.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Model catalog bundle - real crypto round trip (sign → verify) + forgery rejection")
class CatalogBundleCryptoRoundTripTest {

    private static final long VERSION = 42L;
    private static final int SCHEMA_VERSION = 1;
    private static final String ISSUER = "test-issuer";
    private static final String KEY_ID = "test-v1";
    private static final Instant SNAPSHOT_AT = Instant.parse("2026-06-18T00:00:00Z");

    private final ObjectMapper objectMapper = new ObjectMapper();

    private CatalogBundleSigner signer;        // holds the trusted (private) key
    private CatalogBundleSigner forgerSigner;  // a DIFFERENT key the CE never pinned
    private CatalogBundleVerifier verifier;    // pins only the trusted public key

    @BeforeAll
    void setUp() throws Exception {
        KeyPair trusted = ed25519();
        KeyPair forger = ed25519();

        String trustedPriv = b64(trusted.getPrivate().getEncoded());  // PKCS#8
        String trustedPub = b64(trusted.getPublic().getEncoded());    // X.509
        signer = new CatalogBundleSigner(trustedPriv, trustedPub, KEY_ID, ISSUER);

        forgerSigner = new CatalogBundleSigner(
                b64(forger.getPrivate().getEncoded()), b64(forger.getPublic().getEncoded()),
                "evil-v1", "evil-issuer");

        // CE pins ONLY the trusted public key.
        verifier = new CatalogBundleVerifier(new TrustedKeyRegistry(KEY_ID + "=" + trustedPub));
    }

    @Test
    @DisplayName("Happy path: real snapshot signs, verifies, and the payload round-trips with publisher field names intact")
    void roundTripVerifiesAndPreservesFieldNames() throws Exception {
        List<ModelConfigOverrideEntity> snapshot = List.of(
                model("openai", "gpt-5.4", "GPT-5.4", "2.50", "15.00"),
                model("anthropic", "claude-opus-4-6", "Claude Opus 4.6", "5.00", "25.00"));

        SignedBundle bundle = sign(snapshot);
        CatalogBundleVerifier.Result result = verifier.verify(bundle);

        assertThat(result.ok())
                .as("a bundle signed by the trusted key must verify")
                .isTrue();
        assertThat(result.status()).isEqualTo(CatalogBundleVerifier.Status.OK);
        assertThat(result.payloadBytes()).isNotNull();

        // The verified bytes are exactly the signed payload, and the publisher's
        // field names survive - this is the contract the applier reads back
        // (a rename here silently breaks CE sync).
        Map<String, Object> root = objectMapper.readValue(result.payloadBytes(),
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        assertThat(root).containsEntry("version", (int) VERSION).containsEntry("issuer", ISSUER);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> models = (List<Map<String, Object>>) root.get("models");
        assertThat(models).hasSize(2);
        // Sorted by (provider, modelId): anthropic before openai.
        assertThat(models.get(0))
                .containsEntry("provider", "anthropic")
                .containsEntry("modelId", "claude-opus-4-6")
                .containsEntry("displayName", "Claude Opus 4.6")
                .containsEntry("priceInput", "5.00")
                .containsEntry("priceOutput", "25.00")
                .containsEntry("enabled", true);
        assertThat(models.get(1)).containsEntry("provider", "openai").containsEntry("modelId", "gpt-5.4");
    }

    @Test
    @DisplayName("Forgery - signature for a DIFFERENT payload (checksum matches the swapped payload) is rejected SIGNATURE_INVALID")
    void tamperedPayloadRejected() {
        byte[] real = CatalogBundlePayload.canonicalBytes(VERSION, SCHEMA_VERSION, ISSUER, SNAPSHOT_AT,
                List.of(model("openai", "gpt-5.4", "GPT-5.4", "2.50", "15.00")));
        byte[] swapped = CatalogBundlePayload.canonicalBytes(VERSION, SCHEMA_VERSION, ISSUER, SNAPSHOT_AT,
                List.of(model("openai", "gpt-5.4", "GPT-5.4", "0.01", "0.01"))); // attacker drops the price

        // Wire a bundle whose checksum matches the SWAPPED payload (so the
        // checksum gate passes) but whose signature was made over the REAL one.
        SignedBundle forged = new SignedBundle(VERSION, SCHEMA_VERSION,
                signer.checksum(swapped), signer.sign(real), KEY_ID, ISSUER,
                1, swapped.length, b64(swapped));

        CatalogBundleVerifier.Result result = verifier.verify(forged);
        assertThat(result.ok()).isFalse();
        assertThat(result.status()).isEqualTo(CatalogBundleVerifier.Status.SIGNATURE_INVALID);
        assertThat(result.payloadBytes()).isNull();
    }

    @Test
    @DisplayName("Forgery - corrupted payload bytes (checksum no longer matches) is rejected CHECKSUM_INVALID")
    void corruptedPayloadRejected() {
        byte[] payload = CatalogBundlePayload.canonicalBytes(VERSION, SCHEMA_VERSION, ISSUER, SNAPSHOT_AT,
                List.of(model("openai", "gpt-5.4", "GPT-5.4", "2.50", "15.00")));
        byte[] corrupted = payload.clone();
        corrupted[corrupted.length / 2] ^= 0x7F; // flip bits in the middle

        SignedBundle bundle = new SignedBundle(VERSION, SCHEMA_VERSION,
                signer.checksum(payload), signer.sign(payload), KEY_ID, ISSUER,
                1, corrupted.length, b64(corrupted));

        CatalogBundleVerifier.Result result = verifier.verify(bundle);
        assertThat(result.ok()).isFalse();
        assertThat(result.status()).isEqualTo(CatalogBundleVerifier.Status.CHECKSUM_INVALID);
    }

    @Test
    @DisplayName("Forgery - bundle signed by an UNTRUSTED key (keyId not pinned) is rejected TRUST_UNKNOWN")
    void untrustedKeyRejected() {
        List<ModelConfigOverrideEntity> snapshot =
                List.of(model("openai", "gpt-5.4", "GPT-5.4", "2.50", "15.00"));
        byte[] payload = CatalogBundlePayload.canonicalBytes(VERSION, SCHEMA_VERSION, ISSUER, SNAPSHOT_AT, snapshot);

        // Properly self-consistent bundle, but signed by the forger's key under
        // a keyId the CE never pinned.
        SignedBundle forged = new SignedBundle(VERSION, SCHEMA_VERSION,
                forgerSigner.checksum(payload), forgerSigner.sign(payload), "evil-v1", "evil-issuer",
                1, payload.length, b64(payload));

        CatalogBundleVerifier.Result result = verifier.verify(forged);
        assertThat(result.ok()).isFalse();
        assertThat(result.status()).isEqualTo(CatalogBundleVerifier.Status.TRUST_UNKNOWN);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private SignedBundle sign(List<ModelConfigOverrideEntity> snapshot) {
        byte[] payload = CatalogBundlePayload.canonicalBytes(
                VERSION, SCHEMA_VERSION, ISSUER, SNAPSHOT_AT, snapshot);
        return new SignedBundle(VERSION, SCHEMA_VERSION,
                signer.checksum(payload), signer.sign(payload), KEY_ID, ISSUER,
                snapshot.size(), payload.length, b64(payload));
    }

    private static ModelConfigOverrideEntity model(String provider, String modelId, String displayName,
                                                    String priceIn, String priceOut) {
        ModelConfigOverrideEntity e = new ModelConfigOverrideEntity();
        e.setProvider(provider);
        e.setModelId(modelId);
        e.setDisplayName(displayName);
        e.setEnabled(Boolean.TRUE);
        e.setPriceInput(new BigDecimal(priceIn));
        e.setPriceOutput(new BigDecimal(priceOut));
        return e;
    }

    private static KeyPair ed25519() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    private static String b64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }
}
