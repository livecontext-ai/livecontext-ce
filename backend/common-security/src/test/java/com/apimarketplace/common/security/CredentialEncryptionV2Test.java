package com.apimarketplace.common.security;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CASA LC-024 / LC-069 / LC-070 / LC-071: authenticated v2 envelope, HMAC key separation with
 * backward-compatible lookups, plaintext-read accounting, and key presence outside dev/test.
 */
@DisplayName("CredentialEncryptionService v2 (LC-024, LC-069, LC-070, LC-071)")
class CredentialEncryptionV2Test {

    private static final String PASSWORD = "test-password-123";
    private static final String SALT = "0123456789abcdef";

    private static CredentialEncryptionService service(String writeVersion) {
        return new CredentialEncryptionService(PASSWORD, SALT, "", "", false, false, writeVersion, "allow", "");
    }

    private static String rawPasswordHmac(String password, String value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(password.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    }

    @Nested
    @DisplayName("envelope (LC-024)")
    class Envelope {

        @Test
        @DisplayName("write-version 2 produces ENC:v2.<8-hex kid>.<base64url> and round-trips")
        void v2Shape() {
            CredentialEncryptionService v2 = service("2");
            String enc = v2.encrypt("sk-live-123");

            assertThat(enc).matches("ENC:v2\\.[0-9a-f]{8}\\.[A-Za-z0-9_-]+");
            assertThat(enc).contains("." + v2.currentKeyId() + ".");
            assertThat(v2.decrypt(enc)).isEqualTo("sk-live-123");
        }

        @Test
        @DisplayName("a flipped ciphertext byte fails the GCM tag instead of decrypting to garbage")
        void tamperIsDetected() {
            CredentialEncryptionService v2 = service("2");
            String enc = v2.encrypt("sk-live-123");
            int dot = enc.lastIndexOf('.');
            byte[] body = Base64.getUrlDecoder().decode(enc.substring(dot + 1));
            body[body.length - 20] ^= 0x01;
            String tampered = enc.substring(0, dot + 1) + Base64.getUrlEncoder().withoutPadding().encodeToString(body);

            assertThatThrownBy(() -> v2.decrypt(tampered)).isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("a v2 envelope moved under another key id does not decrypt (kid is authenticated)")
        void kidIsBound() {
            CredentialEncryptionService v2 = service("2");
            String enc = v2.encrypt("x");
            String foreignKid = enc.replace("." + v2.currentKeyId() + ".", ".00000000.");

            assertThatThrownBy(() -> v2.decrypt(foreignKid)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("00000000");
        }

        @Test
        @DisplayName("write-version 1 (rollout default) still writes the legacy hex envelope")
        void v1StillWritesLegacy() {
            String enc = service("1").encrypt("value");
            assertThat(enc).startsWith("ENC:").doesNotStartWith("ENC:v2.");
            assertThat(enc.substring(4)).matches("[0-9a-f]+");
            assertThat(new CredentialEncryptionService(PASSWORD, SALT).isWritingV2()).isFalse();
        }

        @Test
        @DisplayName("both directions read across versions: a v1 replica reads v2, a v2 replica reads v1")
        void crossVersionReads() {
            CredentialEncryptionService v1 = service("1");
            CredentialEncryptionService v2 = service("v2");

            assertThat(v1.decrypt(v2.encrypt("a"))).isEqualTo("a");
            assertThat(v2.decrypt(v1.encrypt("b"))).isEqualTo("b");
        }

        @Test
        @DisplayName("at write-version 2, encrypt() upgrades a v1 value; at 1 it leaves it untouched")
        void upgradeOnWrite() {
            CredentialEncryptionService v1 = service("1");
            CredentialEncryptionService v2 = service("2");
            String legacy = v1.encrypt("secret");

            assertThat(v1.encrypt(legacy)).isEqualTo(legacy);
            String upgraded = v2.encrypt(legacy);
            assertThat(upgraded).startsWith("ENC:v2.");
            assertThat(v2.decrypt(upgraded)).isEqualTo("secret");
            assertThat(v2.encrypt(upgraded)).isEqualTo(upgraded);
        }

        @Test
        @DisplayName("needsReencryption: plaintext always, v1 only at write-version 2, v2 never")
        void needsReencryption() {
            CredentialEncryptionService v1 = service("1");
            CredentialEncryptionService v2 = service("2");
            String legacy = v1.encrypt("s");

            assertThat(v1.needsReencryption("plain")).isTrue();
            assertThat(v1.needsReencryption(legacy)).isFalse();
            assertThat(v2.needsReencryption(legacy)).isTrue();
            assertThat(v2.needsReencryption(v2.encrypt("s"))).isFalse();
            assertThat(v2.needsReencryption((String) null)).isFalse();
            assertThat(v2.needsReencryption(Map.of("api_key", legacy, "client_id", "c"))).isTrue();
            assertThat(v2.needsReencryption(Map.of("client_id", "plain-but-not-secret"))).isFalse();
        }

        @Test
        @DisplayName("write-version accepts 1/2/v1/v2 and rejects anything else")
        void writeVersionParsing() {
            assertThat(CredentialEncryptionService.parseWriteVersion("V2")).isEqualTo(2);
            assertThat(CredentialEncryptionService.parseWriteVersion("")).isEqualTo(1);
            assertThatThrownBy(() -> CredentialEncryptionService.parseWriteVersion("3"))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("key rotation")
    class Rotation {

        @Test
        @DisplayName("values and hashes written under the previous material stay readable after rotation")
        void previousMaterialStillReads() throws Exception {
            CredentialEncryptionService old = service("2");
            String v2Value = old.encrypt("v2-secret");
            String v1Value = service("1").encrypt("v1-secret");
            String oldHash = old.hmacHash("lc_live_abc");

            CredentialEncryptionService rotated = new CredentialEncryptionService("brand-new-password-456",
                    "fedcba9876543210", PASSWORD, SALT, false, false, "2", "allow", "");

            assertThat(rotated.currentKeyId()).isNotEqualTo(old.currentKeyId());
            assertThat(rotated.decrypt(v2Value)).isEqualTo("v2-secret");
            assertThat(rotated.decrypt(v1Value)).isEqualTo("v1-secret");
            assertThat(rotated.hmacHashCandidates("lc_live_abc"))
                    .contains(oldHash, rawPasswordHmac(PASSWORD, "lc_live_abc"));
            assertThat(rotated.decrypt(rotated.encrypt("n"))).isEqualTo("n");
        }
    }

    @Nested
    @DisplayName("HMAC key separation (LC-070)")
    class Hmac {

        @Test
        @DisplayName("write-version 1 keeps the exact pre-fix hash (HMAC keyed by the password): stored API keys resolve")
        void v1HashIsLegacy() throws Exception {
            assertThat(service("1").hmacHash("lc_live_abc")).isEqualTo(rawPasswordHmac(PASSWORD, "lc_live_abc"));
        }

        @Test
        @DisplayName("write-version 2 hashes with an HKDF sub-key, NOT the credential-store password")
        void v2HashIsSeparated() throws Exception {
            String legacy = rawPasswordHmac(PASSWORD, "lc_live_abc");
            CredentialEncryptionService v2 = service("2");

            assertThat(v2.hmacHash("lc_live_abc")).isNotEqualTo(legacy).hasSize(64);
            assertThat(v2.hmacHashCandidates("lc_live_abc"))
                    .first().isEqualTo(v2.hmacHash("lc_live_abc"));
            assertThat(v2.hmacHashCandidates("lc_live_abc")).contains(legacy);
        }

        @Test
        @DisplayName("a v1 replica finds a key hashed by a v2 replica and vice versa (candidate lookup)")
        void candidatesCoverBothDirections() {
            CredentialEncryptionService v1 = service("1");
            CredentialEncryptionService v2 = service("2");

            assertThat(v1.hmacHashCandidates("t")).contains(v2.hmacHash("t"));
            assertThat(v2.hmacHashCandidates("t")).contains(v1.hmacHash("t"));
            assertThat(v1.hmacHashCandidates(" ")).isEmpty();
        }
    }

    @Nested
    @DisplayName("rotation finishes: v2 under the previous kid is re-sealed")
    class RotationReseal {

        private CredentialEncryptionService rotated() {
            return new CredentialEncryptionService("brand-new-password-456", "fedcba9876543210",
                    PASSWORD, SALT, false, false, "2", "allow", "");
        }

        @Test
        @DisplayName("a v2 value sealed under the previous kid needs re-encryption; one under the current kid does not")
        void previousKidNeedsReencryption() {
            String oldV2 = service("2").encrypt("s");
            CredentialEncryptionService rotated = rotated();

            assertThat(rotated.needsReencryption(oldV2)).isTrue();
            assertThat(rotated.needsReencryption(rotated.encrypt("s"))).isFalse();
            assertThat(rotated.needsReencryption(Map.of("api_key", oldV2))).isTrue();
        }

        @Test
        @DisplayName("encrypt() re-seals a previous-kid value under the current kid, plaintext preserved")
        void encryptReseals() {
            String oldV2 = service("2").encrypt("s");
            CredentialEncryptionService rotated = rotated();

            String resealed = rotated.encrypt(oldV2);

            assertThat(resealed).contains("." + rotated.currentKeyId() + ".");
            assertThat(rotated.decrypt(resealed)).isEqualTo("s");
            assertThat(rotated.encrypt(resealed)).isEqualTo(resealed);
        }

        @Test
        @DisplayName("at write-version 1 a foreign-kid v2 value is left alone (no downgrade to v1)")
        void v1ModeLeavesV2Alone() {
            String oldV2 = service("2").encrypt("s");
            CredentialEncryptionService rotatedV1 = new CredentialEncryptionService("brand-new-password-456",
                    "fedcba9876543210", PASSWORD, SALT, false, false, "1", "allow", "");

            assertThat(rotatedV1.needsReencryption(oldV2)).isFalse();
            assertThat(rotatedV1.encrypt(oldV2)).isEqualTo(oldV2);
        }
    }

    @Nested
    @DisplayName("legacy (v1) reads")
    class LegacyReads {

        @Test
        @DisplayName("a caller-facing v1 decrypt is counted and exported as credential.legacy.read; v2 is not")
        void counted() {
            CredentialEncryptionService svc = service("2");
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            svc.bindTo(registry);
            String legacy = service("1").encrypt("old");

            assertThat(svc.decrypt(legacy)).isEqualTo("old");
            svc.decrypt(svc.encrypt("new"));

            assertThat(svc.legacyReadCount()).isEqualTo(1);
            assertThat(registry.get("credential.legacy.read").functionCounter().count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("legacy-reads=deny refuses a v1 read but the sweep's re-seal path still migrates it")
        void denyRefusesButResealStillWorks() {
            CredentialEncryptionService deny = new CredentialEncryptionService(PASSWORD, SALT, "", "",
                    false, false, "2", "allow", "deny", "");
            String legacy = service("1").encrypt("old");

            assertThatThrownBy(() -> deny.decrypt(legacy)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("legacy-reads=deny");
            assertThatThrownBy(() -> deny.decryptSensitiveFields(Map.of("api_key", legacy)))
                    .hasMessageContaining("api_key");
            String resealed = deny.encrypt(legacy);
            assertThat(resealed).startsWith("ENC:v2.");
            assertThat(deny.decrypt(resealed)).isEqualTo("old");
            assertThat(deny.legacyReadCount()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("plaintext reads (LC-069)")
    class PlaintextReads {

        @Test
        @DisplayName("a plaintext read is counted and exported as credential.plaintext.read, value returned as-is")
        void countedAndExported() {
            CredentialEncryptionService svc = service("1");
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            svc.bindTo(registry);

            assertThat(svc.decrypt("legacy-clear")).isEqualTo("legacy-clear");
            svc.decryptSensitiveFields(Map.of("api_key", "clear", "client_id", "c"));
            svc.decrypt(svc.encrypt("x"));

            assertThat(svc.plaintextReadCount()).isEqualTo(2);
            assertThat(registry.get("credential.plaintext.read").functionCounter().count()).isEqualTo(2.0);
        }

        @Test
        @DisplayName("plaintext-reads=deny refuses a plaintext value but still decrypts both envelopes")
        void strictModeThrows() {
            CredentialEncryptionService deny = new CredentialEncryptionService(PASSWORD, SALT, "", "",
                    false, false, "2", "deny", "");

            assertThatThrownBy(() -> deny.decrypt("clear")).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> deny.decryptSensitiveFields(Map.of("api_key", "clear")))
                    .hasMessageContaining("api_key");
            assertThat(deny.decrypt(service("1").encrypt("v1"))).isEqualTo("v1");
            assertThat(deny.decrypt(deny.encrypt("v2"))).isEqualTo("v2");
        }
    }

    @Nested
    @DisplayName("key presence (LC-071)")
    class KeyPresence {

        @Test
        @DisplayName("strict unless dev/test: prod, ce and the helm profile list are strict; dev, no profile and tests are not")
        void strictMatrix() {
            assertThat(CredentialEncryptionService.resolveStrict(null, "prod", false, false)).isTrue();
            assertThat(CredentialEncryptionService.resolveStrict(null, "ce", false, false)).isTrue();
            assertThat(CredentialEncryptionService.resolveStrict(null, "prod,hikari,agents", false, false)).isTrue();
            assertThat(CredentialEncryptionService.resolveStrict(null, "dev", false, false)).isFalse();
            assertThat(CredentialEncryptionService.resolveStrict(null, "dev,agents,hikari", false, false)).isFalse();
            assertThat(CredentialEncryptionService.resolveStrict(null, "integration-test", false, false)).isFalse();
            assertThat(CredentialEncryptionService.resolveStrict(null, "", false, false)).isFalse();
            assertThat(CredentialEncryptionService.resolveStrict(null, "ce", false, true)).isFalse();
            assertThat(CredentialEncryptionService.resolveStrict(null, "dev", true, true)).isTrue();
            assertThat(CredentialEncryptionService.resolveStrict(Boolean.FALSE, "prod", true, false)).isFalse();
            assertThat(CredentialEncryptionService.resolveStrict(Boolean.TRUE, "dev", false, true)).isTrue();
        }

        @Test
        @DisplayName("strict and blank: startup fails instead of minting an ephemeral key")
        void strictBlankFails() {
            assertThatThrownBy(() -> new CredentialEncryptionService("", "", "", "", true, true, "1", "allow", "ce"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("CREDENTIAL_ENCRYPTION_PASSWORD");
        }

        @Test
        @DisplayName("a configured default value is USED as configured (not replaced by an ephemeral key): old rows stay readable")
        void defaultValueIsNotSwappedForEphemeral() {
            CredentialEncryptionService first = new CredentialEncryptionService(
                    CredentialEncryptionService.LEGACY_DEFAULT_PASSWORD, CredentialEncryptionService.LEGACY_DEFAULT_SALT);
            CredentialEncryptionService afterRestart = new CredentialEncryptionService(
                    CredentialEncryptionService.LEGACY_DEFAULT_PASSWORD, CredentialEncryptionService.LEGACY_DEFAULT_SALT);

            assertThat(first.isUsingEphemeralMaterial()).isFalse();
            assertThat(afterRestart.decrypt(first.encrypt("kept"))).isEqualTo("kept");
        }

        @Test
        @DisplayName("strict with a default value boots only with allow-weak-material, and then on the configured value")
        void allowWeakMaterial() {
            assertThatThrownBy(() -> new CredentialEncryptionService(CredentialEncryptionService.LEGACY_DEFAULT_PASSWORD,
                    SALT, "", "", true, false, "1", "allow", "ce")).isInstanceOf(IllegalStateException.class);

            CredentialEncryptionService weak = new CredentialEncryptionService(
                    CredentialEncryptionService.LEGACY_DEFAULT_PASSWORD, SALT, "", "", true, true, "1", "allow", "ce");
            CredentialEncryptionService lenient = new CredentialEncryptionService(
                    CredentialEncryptionService.LEGACY_DEFAULT_PASSWORD, SALT);
            assertThat(weak.isUsingEphemeralMaterial()).isFalse();
            assertThat(lenient.decrypt(weak.encrypt("v"))).isEqualTo("v");
        }

        @Test
        @DisplayName("lenient and blank: ephemeral material, flagged so sweeps refuse to rewrite rows")
        void lenientBlankIsEphemeral() {
            CredentialEncryptionService dev = new CredentialEncryptionService("", "");
            assertThat(dev.isUsingEphemeralMaterial()).isTrue();
            assertThat(List.of(dev.decrypt(dev.encrypt("s")))).containsExactly("s");
        }
    }
}
