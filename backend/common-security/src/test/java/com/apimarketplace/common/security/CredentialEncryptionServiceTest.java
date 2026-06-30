package com.apimarketplace.common.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for CredentialEncryptionService.
 * Tests encryption, decryption, backward compatibility, HMAC hashing,
 * and sensitive field map operations.
 */
@DisplayName("CredentialEncryptionService")
class CredentialEncryptionServiceTest {

    private CredentialEncryptionService encryptionService;

    // Test credentials - use fixed values for reproducible tests
    private static final String TEST_PASSWORD = "test-password-123";
    private static final String TEST_SALT = "0123456789abcdef";  // 16 hex chars = 8 bytes
    private static final String SAFE_SALT = "0123456789abcdef";  // 16 hex chars = 8 bytes

    @BeforeEach
    void setUp() {
        encryptionService = new CredentialEncryptionService(TEST_PASSWORD, TEST_SALT);
    }

    // ========================================================================
    // configuration tests
    // ========================================================================

    @Nested
    @DisplayName("configuration")
    class ConfigurationTests {

        @Test
        @DisplayName("should fail fast when default password is rejected")
        void shouldRejectDefaultPasswordInStrictMode() {
            IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                    new CredentialEncryptionService(
                            CredentialEncryptionService.LEGACY_DEFAULT_PASSWORD,
                            SAFE_SALT,
                            true));

            assertTrue(ex.getMessage().contains("credential.encryption.password"));
        }

        @Test
        @DisplayName("should fail fast when default salt is rejected")
        void shouldRejectDefaultSaltInStrictMode() {
            IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                    new CredentialEncryptionService(
                            "safe-password",
                            CredentialEncryptionService.LEGACY_DEFAULT_SALT,
                            true));

            assertTrue(ex.getMessage().contains("credential.encryption.salt"));
        }

        @Test
        @DisplayName("should allow explicit non-default encryption material in strict mode")
        void shouldAllowNonDefaultMaterialInStrictMode() {
            CredentialEncryptionService strict =
                    new CredentialEncryptionService("safe-password", SAFE_SALT, true);

            assertEquals("secret", strict.decrypt(strict.encrypt("secret")));
        }

        @Test
        @DisplayName("should use ephemeral encryption material for blank dev configuration")
        void shouldUseEphemeralMaterialWhenBlankOutsideStrictMode() {
            CredentialEncryptionService dev = new CredentialEncryptionService("", "", false);

            assertEquals("secret", dev.decrypt(dev.encrypt("secret")));
        }
    }

    // ========================================================================
    // encrypt tests
    // ========================================================================

    @Nested
    @DisplayName("encrypt")
    class EncryptTests {

        @Test
        @DisplayName("should encrypt plaintext with ENC prefix")
        void shouldEncryptPlaintext() {
            String secret = "my-client-secret-123";
            String encrypted = encryptionService.encrypt(secret);

            assertNotNull(encrypted);
            assertNotEquals(secret, encrypted);
            assertTrue(encrypted.startsWith(CredentialEncryptionService.ENCRYPTED_PREFIX));
        }

        @Test
        @DisplayName("should return null when input is null")
        void shouldReturnNullWhenEncryptingNull() {
            assertNull(encryptionService.encrypt(null));
        }

        @Test
        @DisplayName("should return blank when input is blank")
        void shouldReturnBlankWhenEncryptingBlank() {
            assertEquals("   ", encryptionService.encrypt("   "));
        }

        @Test
        @DisplayName("should not double encrypt already encrypted value")
        void shouldNotDoubleEncrypt() {
            String secret = "my-secret";
            String encrypted = encryptionService.encrypt(secret);
            String doubleEncrypted = encryptionService.encrypt(encrypted);
            assertEquals(encrypted, doubleEncrypted);
        }

        @Test
        @DisplayName("should produce different ciphertext each time (random IV)")
        void shouldProduceDifferentCiphertextEachTime() {
            String plaintext = "same-value";
            String result1 = encryptionService.encrypt(plaintext);
            String result2 = encryptionService.encrypt(plaintext);

            assertTrue(result1.startsWith("ENC:"));
            assertTrue(result2.startsWith("ENC:"));
            assertNotEquals(result1, result2);
        }
    }

    // ========================================================================
    // decrypt tests
    // ========================================================================

    @Nested
    @DisplayName("decrypt")
    class DecryptTests {

        @Test
        @DisplayName("should decrypt encrypted value")
        void shouldDecryptEncryptedValue() {
            String original = "my-super-secret-token";
            String encrypted = encryptionService.encrypt(original);
            assertEquals(original, encryptionService.decrypt(encrypted));
        }

        @Test
        @DisplayName("should return null when input is null")
        void shouldReturnNullWhenDecryptingNull() {
            assertNull(encryptionService.decrypt(null));
        }

        @Test
        @DisplayName("should return blank when input is blank")
        void shouldReturnBlankWhenDecryptingBlank() {
            assertEquals("", encryptionService.decrypt(""));
        }

        @Test
        @DisplayName("should return plaintext unchanged for backward compatibility")
        void shouldReturnPlaintextAsIsForBackwardCompatibility() {
            String plaintext = "unencrypted-legacy-value";
            assertEquals(plaintext, encryptionService.decrypt(plaintext));
        }

        @Test
        @DisplayName("should handle legacy OAuth token")
        void shouldHandleLegacyOAuthToken() {
            String legacyToken = "ya29.a0AfH6SMBx...legacy-token-format";
            assertEquals(legacyToken, encryptionService.decrypt(legacyToken));
        }

        @Test
        @DisplayName("should decrypt complex strings with special characters")
        void shouldDecryptComplexStringsWithSpecialCharacters() {
            String complexValue = "API_KEY=abc123!@#$%^&*()_+{}|:<>?~`-=[]\\;',./";
            String encrypted = encryptionService.encrypt(complexValue);
            assertEquals(complexValue, encryptionService.decrypt(encrypted));
        }
    }

    // ========================================================================
    // isEncrypted tests
    // ========================================================================

    @Nested
    @DisplayName("isEncrypted")
    class IsEncryptedTests {

        @Test
        @DisplayName("should return true for encrypted value")
        void shouldIdentifyEncryptedValue() {
            String encrypted = encryptionService.encrypt("secret");
            assertTrue(encryptionService.isEncrypted(encrypted));
        }

        @Test
        @DisplayName("should return false for plaintext value")
        void shouldIdentifyPlaintextValue() {
            assertFalse(encryptionService.isEncrypted("not-encrypted"));
        }

        @Test
        @DisplayName("should return false for null value")
        void shouldReturnFalseForNullValue() {
            assertFalse(encryptionService.isEncrypted(null));
        }

        @Test
        @DisplayName("should return false for empty string")
        void shouldReturnFalseForEmptyString() {
            assertFalse(encryptionService.isEncrypted(""));
        }

        @Test
        @DisplayName("should be case sensitive for ENC prefix")
        void shouldBeCaseSensitiveForEncPrefix() {
            assertFalse(encryptionService.isEncrypted("enc:value"));
            assertFalse(encryptionService.isEncrypted("Enc:value"));
            assertTrue(encryptionService.isEncrypted("ENC:value"));
        }
    }

    // ========================================================================
    // Round-trip tests
    // ========================================================================

    @Nested
    @DisplayName("Round-trip encryption/decryption")
    class RoundTripTests {

        @Test
        @DisplayName("should round-trip OAuth client secret")
        void shouldRoundTripOAuthClientSecret() {
            String clientSecret = "dGhpcyBpcyBhIHNlY3JldCBjbGllbnQgc2VjcmV0";
            String encrypted = encryptionService.encrypt(clientSecret);
            assertEquals(clientSecret, encryptionService.decrypt(encrypted));
        }

        @Test
        @DisplayName("should round-trip refresh token")
        void shouldRoundTripRefreshToken() {
            String refreshToken = "1//0e-refresh-token-with-special-chars_123";
            String encrypted = encryptionService.encrypt(refreshToken);
            assertEquals(refreshToken, encryptionService.decrypt(encrypted));
        }

        @Test
        @DisplayName("should round-trip access token (JWT)")
        void shouldRoundTripAccessToken() {
            String accessToken = "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJodHRwczovL2FjY291bnRz";
            String encrypted = encryptionService.encrypt(accessToken);
            assertEquals(accessToken, encryptionService.decrypt(encrypted));
        }

        @Test
        @DisplayName("should handle unicode characters")
        void shouldHandleUnicodeCharacters() {
            String secret = "secret-with-accents-\u00e9\u00e0\u00fc and CJK-\u5bc6\u7801-\u30d1\u30b9\u30ef\u30fc\u30c9";
            String encrypted = encryptionService.encrypt(secret);
            assertEquals(secret, encryptionService.decrypt(encrypted));
        }

        @Test
        @DisplayName("should handle long values")
        void shouldHandleLongValue() {
            String longToken = "x".repeat(10000);
            String encrypted = encryptionService.encrypt(longToken);
            assertEquals(longToken, encryptionService.decrypt(encrypted));
        }
    }

    // ========================================================================
    // encryptSensitiveFields / decryptSensitiveFields tests
    // ========================================================================

    @Nested
    @DisplayName("Sensitive field map operations")
    class SensitiveFieldTests {

        @Test
        @DisplayName("encryptSensitiveFields should return null for null input")
        void shouldReturnNullForNullEncrypt() {
            assertNull(encryptionService.encryptSensitiveFields(null));
        }

        @Test
        @DisplayName("decryptSensitiveFields should return null for null input")
        void shouldReturnNullForNullDecrypt() {
            assertNull(encryptionService.decryptSensitiveFields(null));
        }

        @Test
        @DisplayName("should encrypt only sensitive string fields")
        void shouldEncryptOnlySensitiveFields() {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("api_key", "secret-key-123");
            data.put("name", "My Integration");
            data.put("password", "secret-pass");
            data.put("port", 8080);  // non-string, should be left unchanged

            Map<String, Object> encrypted = encryptionService.encryptSensitiveFields(data);

            assertTrue(((String) encrypted.get("api_key")).startsWith("ENC:"));
            assertEquals("My Integration", encrypted.get("name"));
            assertTrue(((String) encrypted.get("password")).startsWith("ENC:"));
            assertEquals(8080, encrypted.get("port"));
        }

        @Test
        @DisplayName("should round-trip sensitive fields through encrypt/decrypt")
        void shouldRoundTripSensitiveFields() {
            Map<String, Object> original = new LinkedHashMap<>();
            original.put("api_key", "key-123");
            original.put("client_secret", "secret-456");
            original.put("name", "unchanged");

            Map<String, Object> encrypted = encryptionService.encryptSensitiveFields(original);
            Map<String, Object> decrypted = encryptionService.decryptSensitiveFields(encrypted);

            assertEquals("key-123", decrypted.get("api_key"));
            assertEquals("secret-456", decrypted.get("client_secret"));
            assertEquals("unchanged", decrypted.get("name"));
        }
    }

    // ========================================================================
    // hmacHash tests
    // ========================================================================

    @Nested
    @DisplayName("hmacHash")
    class HmacHashTests {

        @Test
        @DisplayName("should return null for null input")
        void shouldReturnNullForNull() {
            assertNull(encryptionService.hmacHash(null));
        }

        @Test
        @DisplayName("should return blank for blank input")
        void shouldReturnBlankForBlank() {
            assertEquals("   ", encryptionService.hmacHash("   "));
        }

        @Test
        @DisplayName("should return hex-encoded hash")
        void shouldReturnHexEncodedHash() {
            String hash = encryptionService.hmacHash("test-value");
            assertNotNull(hash);
            // HMAC-SHA256 produces 64 hex characters (32 bytes)
            assertEquals(64, hash.length());
            assertTrue(hash.matches("[0-9a-f]+"));
        }

        @Test
        @DisplayName("should produce consistent hash for same input")
        void shouldProduceConsistentHash() {
            String hash1 = encryptionService.hmacHash("same-input");
            String hash2 = encryptionService.hmacHash("same-input");
            assertEquals(hash1, hash2);
        }

        @Test
        @DisplayName("should produce different hashes for different inputs")
        void shouldProduceDifferentHashesForDifferentInputs() {
            String hash1 = encryptionService.hmacHash("input-1");
            String hash2 = encryptionService.hmacHash("input-2");
            assertNotEquals(hash1, hash2);
        }
    }
}
