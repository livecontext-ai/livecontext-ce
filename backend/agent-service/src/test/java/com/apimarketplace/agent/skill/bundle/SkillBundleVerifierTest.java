package com.apimarketplace.agent.skill.bundle;

import com.apimarketplace.agent.catalog.bundle.TrustedKeyRegistry;
import com.apimarketplace.common.bundle.Ed25519BundleCrypto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end verify contract over REAL Ed25519 crypto (reusing the shared common-lib
 * primitive): a correctly-signed bundle from a pinned key passes; a tampered signature, an
 * unknown key id, or no configured keys all reject. Proves the skill verifier reuses the same
 * trust root ({@code catalog.bundle.trusted-keys}) and algorithm as the catalog bundle.
 */
@DisplayName("SkillBundleVerifier - signature verification")
class SkillBundleVerifierTest {

    private final KeyPair keyPair = generateKeyPair();
    private final String pubB64 = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
    private final String privB64 = Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded());
    private final Ed25519BundleCrypto crypto =
            new Ed25519BundleCrypto(privB64, pubB64, "skill-key-v1", "issuer");
    private final byte[] payload = "{\"skills\":[]}".getBytes(StandardCharsets.UTF_8);

    private SignedSkillBundle signed(String keyId) {
        return new SignedSkillBundle(1L, 1, crypto.checksum(payload), crypto.sign(payload),
                keyId, "issuer", 0, payload.length,
                Base64.getEncoder().encodeToString(payload));
    }

    @Test
    @DisplayName("a correctly-signed bundle from a pinned key verifies and returns the decoded bytes")
    void verifiesPinnedSignature() {
        SkillBundleVerifier verifier = new SkillBundleVerifier(new TrustedKeyRegistry("skill-key-v1=" + pubB64));

        SkillBundleVerifier.Result r = verifier.verify(signed("skill-key-v1"));

        assertThat(r.ok()).isTrue();
        assertThat(r.payloadBytes()).isEqualTo(payload);
    }

    @Test
    @DisplayName("rejects a bundle whose signing key id is not in the trust list (TRUST_UNKNOWN)")
    void rejectsUnknownKey() {
        SkillBundleVerifier verifier = new SkillBundleVerifier(new TrustedKeyRegistry("skill-key-v1=" + pubB64));

        SkillBundleVerifier.Result r = verifier.verify(signed("some-other-key"));

        assertThat(r.ok()).isFalse();
        assertThat(r.status()).isEqualTo(SkillBundleVerifier.Status.TRUST_UNKNOWN);
    }

    @Test
    @DisplayName("rejects a tampered signature (SIGNATURE_INVALID)")
    void rejectsTamperedSignature() {
        SkillBundleVerifier verifier = new SkillBundleVerifier(new TrustedKeyRegistry("skill-key-v1=" + pubB64));
        SignedSkillBundle good = signed("skill-key-v1");
        // Flip the signature to a different (valid-length) value over a different payload.
        String otherSig = crypto.sign("tampered".getBytes(StandardCharsets.UTF_8));
        SignedSkillBundle tampered = new SignedSkillBundle(
                good.version(), good.schemaVersion(), good.checksum(), otherSig,
                good.signingKeyId(), good.issuer(), good.skillCount(), good.rawBytesSize(),
                good.payloadBase64());

        SkillBundleVerifier.Result r = verifier.verify(tampered);

        assertThat(r.ok()).isFalse();
        assertThat(r.status()).isEqualTo(SkillBundleVerifier.Status.SIGNATURE_INVALID);
    }

    @Test
    @DisplayName("hasKeys() is false when no trust root is configured, and verify rejects everything")
    void noKeysRejectsAll() {
        SkillBundleVerifier verifier = new SkillBundleVerifier(new TrustedKeyRegistry(""));

        assertThat(verifier.hasKeys()).isFalse();
        assertThat(verifier.verify(signed("skill-key-v1")).ok()).isFalse();
    }

    @Test
    @DisplayName("observes a RUNTIME pin into the shared registry (TOFU): empty at construction, verifies after pin")
    void observesRuntimePinIntoSharedRegistry() {
        // The registry is EMPTY when the verifier is built (as on a TOFU install with an empty
        // catalog.bundle.trusted-keys). Pre-fix the verifier froze a PRIVATE empty snapshot in its
        // constructor and could NEVER verify, no matter what the model-catalog path pinned. Post-fix
        // it reads the SHARED registry live, so a runtime TOFU pin (CatalogBundleTrustBootstrap ->
        // TrustedKeyRegistry.pin) makes the same-signed bundle verifiable - this is the root cause.
        TrustedKeyRegistry registry = new TrustedKeyRegistry("");
        SkillBundleVerifier verifier = new SkillBundleVerifier(registry);
        assertThat(verifier.hasKeys()).isFalse();
        assertThat(verifier.verify(signed("skill-key-v1")).ok()).isFalse();

        registry.pin("skill-key-v1", pubB64);   // TOFU pin at runtime, shared with the model-catalog path

        assertThat(verifier.hasKeys()).isTrue();
        assertThat(verifier.verify(signed("skill-key-v1")).ok()).isTrue();
    }

    private static KeyPair generateKeyPair() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
