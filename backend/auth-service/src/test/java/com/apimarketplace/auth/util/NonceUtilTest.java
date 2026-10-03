package com.apimarketplace.auth.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("NonceUtil Tests")
class NonceUtilTest {

    private NonceUtil nonceUtil;

    @BeforeEach
    void setUp() {
        nonceUtil = new NonceUtil();
    }

    @Nested
    @DisplayName("generateNonce()")
    class GenerateNonceTests {

        @Test
        @DisplayName("should generate a nonce for a valid user ID")
        void shouldGenerateNonceForValidUserId() {
            String nonce = nonceUtil.generateNonce(1L);

            assertThat(nonce).isNotNull();
            assertThat(nonce).startsWith("n2_").matches("n2_[A-Za-z0-9_-]+");
        }

        @Test
        @DisplayName("should throw exception for null user ID")
        void shouldThrowExceptionForNullUserId() {
            assertThatThrownBy(() -> nonceUtil.generateNonce(null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("User ID cannot be null");
        }

        @Test
        @DisplayName("should return cached nonce for same user ID")
        void shouldReturnCachedNonceForSameUserId() {
            String nonce1 = nonceUtil.generateNonce(42L);
            String nonce2 = nonceUtil.generateNonce(42L);

            assertThat(nonce1).isEqualTo(nonce2);
        }

        @Test
        @DisplayName("should generate different nonces for different user IDs")
        void shouldGenerateDifferentNoncesForDifferentUserIds() {
            String nonce1 = nonceUtil.generateNonce(1L);
            String nonce2 = nonceUtil.generateNonce(2L);

            assertThat(nonce1).isNotEqualTo(nonce2);
        }
    }

    @Nested
    @DisplayName("decodeNonce()")
    class DecodeNonceTests {

        @Test
        @DisplayName("should decode a valid nonce back to user ID")
        void shouldDecodeValidNonce() {
            Long userId = 123L;
            String nonce = nonceUtil.generateNonce(userId);

            Long decodedUserId = nonceUtil.decodeNonce(nonce);

            assertThat(decodedUserId).isEqualTo(userId);
        }

        @Test
        @DisplayName("should return null for null nonce")
        void shouldReturnNullForNullNonce() {
            Long result = nonceUtil.decodeNonce(null);

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("should return null for empty nonce")
        void shouldReturnNullForEmptyNonce() {
            Long result = nonceUtil.decodeNonce("");

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("should return null for nonce with wrong prefix")
        void shouldReturnNullForNonceWithWrongPrefix() {
            Long result = nonceUtil.decodeNonce("invalid_nonce");

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("should return null for corrupted nonce data")
        void shouldReturnNullForCorruptedNonce() {
            Long result = nonceUtil.decodeNonce("n_InvalidBase64Data!!!");

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("should use cache for previously decoded nonce")
        void shouldUseCacheForPreviouslyDecodedNonce() {
            Long userId = 99L;
            String nonce = nonceUtil.generateNonce(userId);

            // First decode - from cache or decrypt
            Long decoded1 = nonceUtil.decodeNonce(nonce);
            // Second decode - should use cache
            Long decoded2 = nonceUtil.decodeNonce(nonce);

            assertThat(decoded1).isEqualTo(userId);
            assertThat(decoded2).isEqualTo(userId);
        }
    }

    @Nested
    @DisplayName("validateNonce()")
    class ValidateNonceTests {

        @Test
        @DisplayName("should return true for valid nonce and matching user ID")
        void shouldReturnTrueForValidNonceAndMatchingUserId() {
            Long userId = 55L;
            String nonce = nonceUtil.generateNonce(userId);

            boolean valid = nonceUtil.validateNonce(nonce, userId);

            assertThat(valid).isTrue();
        }

        @Test
        @DisplayName("should return false for valid nonce and wrong user ID")
        void shouldReturnFalseForValidNonceAndWrongUserId() {
            Long userId = 55L;
            String nonce = nonceUtil.generateNonce(userId);

            boolean valid = nonceUtil.validateNonce(nonce, 99L);

            assertThat(valid).isFalse();
        }

        @Test
        @DisplayName("should return false for null nonce")
        void shouldReturnFalseForNullNonce() {
            boolean valid = nonceUtil.validateNonce(null, 1L);

            assertThat(valid).isFalse();
        }

        @Test
        @DisplayName("should return false for null user ID")
        void shouldReturnFalseForNullUserId() {
            String nonce = nonceUtil.generateNonce(1L);

            boolean valid = nonceUtil.validateNonce(nonce, null);

            assertThat(valid).isFalse();
        }

        @Test
        @DisplayName("should return false when both params are null")
        void shouldReturnFalseWhenBothNull() {
            boolean valid = nonceUtil.validateNonce(null, null);

            assertThat(valid).isFalse();
        }
    }

    @Nested
    @DisplayName("configurable encryption key (auth.nonce.encryption-key)")
    class ConfigurableKeyTests {

        @Test
        @DisplayName("nonce encrypted with a configured key decodes with the same key on a fresh instance")
        void configuredKeyRoundTripsAcrossInstances() {
            // Webhooks decode nonces on a different instance (k8s replica) than
            // the one that generated them - the cache must not be required.
            NonceUtil writer = new NonceUtil("deployment-specific-key");
            NonceUtil reader = new NonceUtil("deployment-specific-key");

            String nonce = writer.generateNonce(777L);

            assertThat(reader.decodeNonce(nonce)).isEqualTo(777L);
        }

        @Test
        @DisplayName("nonce encrypted with a custom key is NOT decodable with an ephemeral key")
        void customKeyNotDecodableWithEphemeralKey() {
            NonceUtil custom = new NonceUtil("deployment-specific-key");
            NonceUtil ephemeral = new NonceUtil();

            String nonce = custom.generateNonce(777L);

            assertThat(ephemeral.decodeNonce(nonce)).isNull();
        }

        @Test
        @DisplayName("blank configured key uses an instance-local ephemeral key")
        void blankKeyUsesInstanceLocalEphemeralKey() {
            NonceUtil first = new NonceUtil();
            NonceUtil second = new NonceUtil("");

            String nonce = first.generateNonce(123L);

            assertThat(first.decodeNonce(nonce)).isEqualTo(123L);
            assertThat(second.decodeNonce(nonce)).isNull();
        }

        @Test
        @DisplayName("LC-026: a missing NONCE_ENCRYPTION_KEY is not fatal but is exported as auth.nonce.key.ephemeral=1")
        void ephemeralKeyIsExportedAsGauge() {
            io.micrometer.core.instrument.simple.SimpleMeterRegistry unset = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
            io.micrometer.core.instrument.simple.SimpleMeterRegistry set = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
            NonceUtil ephemeral = new NonceUtil("");
            NonceUtil configured = new NonceUtil("deployment-specific-key");
            ephemeral.bindTo(unset);
            configured.bindTo(set);

            assertThat(ephemeral.isUsingEphemeralKey()).isTrue();
            assertThat(configured.isUsingEphemeralKey()).isFalse();
            assertThat(unset.get("auth.nonce.key.ephemeral").gauge().value()).isEqualTo(1.0);
            assertThat(set.get("auth.nonce.key.ephemeral").gauge().value()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("LC-026: a legacy n_ (AES-128-ECB) nonce already stored in Stripe metadata still decodes")
        void legacyEcbNonceStillDecodes() throws Exception {
            String key = "a-much-longer-key-than-sixteen-bytes";
            byte[] aesKey = java.util.Arrays.copyOf(
                    java.security.MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)), 16);
            Cipher ecb = Cipher.getInstance("AES/ECB/PKCS5Padding");
            ecb.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(aesKey, "AES"));
            String legacy = "n_" + Base64.getEncoder().encodeToString(
                    ecb.doFinal(("4242:" + System.currentTimeMillis() + ":AbCdEfGh").getBytes(StandardCharsets.UTF_8)));

            assertThat(new NonceUtil(key).decodeNonce(legacy)).isEqualTo(4242L);
        }

        @Test
        @DisplayName("LC-026: the written format is authenticated GCM, not ECB: a flipped byte is rejected")
        void tamperedGcmNonceIsRejected() {
            NonceUtil writer = new NonceUtil("deployment-specific-key");
            String nonce = writer.generateNonce(99L);
            byte[] body = Base64.getUrlDecoder().decode(nonce.substring("n2_".length()));
            body[14] ^= 0x01;
            String tampered = "n2_" + Base64.getUrlEncoder().withoutPadding().encodeToString(body);

            assertThat(new NonceUtil("deployment-specific-key").decodeNonce(tampered)).isNull();
        }

        @Test
        @DisplayName("LC-026: equal plaintext blocks no longer produce equal ciphertext (random IV per nonce)")
        void noDeterministicBlocks() {
            NonceUtil a = new NonceUtil("deployment-specific-key");
            NonceUtil b = new NonceUtil("deployment-specific-key");
            String first = a.generateNonce(1234567890123L);
            String second = b.generateNonce(1234567890123L);
            // ECB encrypted the leading "1234567890123:17" block identically for the same user
            // in the same time window; GCM output shares no prefix beyond the marker.
            assertThat(first.substring(3, 15)).isNotEqualTo(second.substring(3, 15));
        }

        @Test
        @DisplayName("keys of any length are accepted (derived to AES-128 material)")
        void nonSixteenByteKeyIsDerived() {
            NonceUtil shortKey = new NonceUtil("abc");
            NonceUtil longKey = new NonceUtil("a-much-longer-key-than-sixteen-bytes");

            assertThat(shortKey.decodeNonce(shortKey.generateNonce(1L))).isEqualTo(1L);
            assertThat(longKey.decodeNonce(longKey.generateNonce(2L))).isEqualTo(2L);
            // And the two derive different keys: cross-decode must fail.
            assertThat(shortKey.decodeNonce(longKey.generateNonce(3L))).isNull();
        }
    }

    @Nested
    @DisplayName("cleanupExpiredNonces()")
    class CleanupExpiredNoncesTests {

        @Test
        @DisplayName("should not throw when cache is small")
        void shouldNotThrowWhenCacheIsSmall() {
            nonceUtil.generateNonce(1L);
            nonceUtil.generateNonce(2L);

            // Should not throw
            nonceUtil.cleanupExpiredNonces();
        }
    }

    @Nested
    @DisplayName("getCacheStats()")
    class GetCacheStatsTests {

        @Test
        @DisplayName("should return empty stats initially")
        void shouldReturnEmptyStatsInitially() {
            Map<String, Object> stats = nonceUtil.getCacheStats();

            assertThat(stats).containsKey("nonceToUserIdCacheSize");
            assertThat(stats).containsKey("userIdToNonceCacheSize");
            assertThat((Integer) stats.get("nonceToUserIdCacheSize")).isZero();
            assertThat((Integer) stats.get("userIdToNonceCacheSize")).isZero();
        }

        @Test
        @DisplayName("should update stats after nonce generation")
        void shouldUpdateStatsAfterNonceGeneration() {
            nonceUtil.generateNonce(1L);
            nonceUtil.generateNonce(2L);

            Map<String, Object> stats = nonceUtil.getCacheStats();

            assertThat((Integer) stats.get("nonceToUserIdCacheSize")).isEqualTo(2);
            assertThat((Integer) stats.get("userIdToNonceCacheSize")).isEqualTo(2);
        }
    }
}
