package com.apimarketplace.common.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.encrypt.Encryptors;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Service for encrypting/decrypting sensitive credential data.
 * Uses AES-256 encryption via Spring Security Crypto.
 *
 * <p>Encryption is applied to sensitive fields before storing in the database.
 *
 * <p>The service uses a prefix "ENC:" to identify encrypted values, allowing
 * backward compatibility with existing plaintext credentials in the database.
 *
 * <p><b>Configuration:</b>
 * <pre>
 * credential:
 *   encryption:
 *     password: ${CREDENTIAL_ENCRYPTION_PASSWORD}
 *     salt: ${CREDENTIAL_ENCRYPTION_SALT}  # Must be hex-encoded
 * </pre>
 */
@Service
public class CredentialEncryptionService {

    private static final Logger log = LoggerFactory.getLogger(CredentialEncryptionService.class);

    /**
     * Prefix for encrypted values. Allows distinguishing encrypted from plaintext.
     */
    public static final String ENCRYPTED_PREFIX = "ENC:";

    static final String LEGACY_DEFAULT_PASSWORD = "change" + "me-in-production";
    static final String LEGACY_DEFAULT_SALT = "deadbeef" + "deadbeef";
    private static final String LEGACY_DEFAULT_PASSWORD_SHORT = "change" + "me";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /**
     * Known sensitive field names that should be encrypted in JSONB maps.
     */
    private static final Set<String> SENSITIVE_FIELDS = Set.of(
            "access_token", "refresh_token", "client_secret", "oauth_client_secret",
            "api_key", "apiKey", "password", "secret", "secretKey", "bearer_token",
            "token", "connectionString", "basicPassword", "authHeaderValue", "jwtSecretKey"
    );

    private final TextEncryptor encryptor;
    private final String password;

    /**
     * Creates the encryption service with the provided password and salt.
     *
     * @param password The encryption password (from environment variable)
     * @param salt The encryption salt (hex-encoded, from environment variable)
     */
    public CredentialEncryptionService(String password, String salt) {
        this(password, salt, false);
    }

    @Autowired
    public CredentialEncryptionService(
            @Value("${credential.encryption.password:}") String password,
            @Value("${credential.encryption.salt:}") String salt,
            @Value("${security.reject-default-secrets:false}") boolean rejectDefaultSecrets) {
        boolean strictSecrets = rejectDefaultSecrets || System.getenv("KUBERNETES_SERVICE_HOST") != null;
        if (strictSecrets && (isUnsafePassword(password) || isUnsafeSalt(salt))) {
            throw new IllegalStateException("credential.encryption.password and credential.encryption.salt must be overridden outside dev/test");
        }
        ResolvedEncryptionMaterial material = resolveEncryptionMaterial(password, salt);
        this.encryptor = Encryptors.text(material.password(), material.salt());
        this.password = material.password();
        log.info("CredentialEncryptionService initialized");
    }

    private static boolean isUnsafePassword(String password) {
        return password == null
                || password.isBlank()
                || LEGACY_DEFAULT_PASSWORD_SHORT.equals(password)
                || LEGACY_DEFAULT_PASSWORD.equals(password);
    }

    private static boolean isUnsafeSalt(String salt) {
        return salt == null || salt.isBlank() || LEGACY_DEFAULT_SALT.equals(salt);
    }

    private static ResolvedEncryptionMaterial resolveEncryptionMaterial(String password, String salt) {
        boolean unsafePassword = isUnsafePassword(password);
        boolean unsafeSalt = isUnsafeSalt(salt);
        if (!unsafePassword && !unsafeSalt) {
            return new ResolvedEncryptionMaterial(password, salt);
        }
        String resolvedPassword = unsafePassword ? generateEphemeralPassword() : password;
        String resolvedSalt = unsafeSalt ? generateEphemeralSalt() : salt;
        log.warn("credential.encryption.password/salt missing or unsafe; using ephemeral in-memory encryption material for this process");
        return new ResolvedEncryptionMaterial(resolvedPassword, resolvedSalt);
    }

    private static String generateEphemeralPassword() {
        byte[] bytes = new byte[48];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static String generateEphemeralSalt() {
        byte[] bytes = new byte[16];
        SECURE_RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private record ResolvedEncryptionMaterial(String password, String salt) {}

    /**
     * Encrypt a plaintext value.
     *
     * <p>Returns the input unchanged if:
     * <ul>
     *   <li>The input is null or blank</li>
     *   <li>The input is already encrypted (starts with "ENC:")</li>
     * </ul>
     *
     * @param plaintext The value to encrypt
     * @return The encrypted value with "ENC:" prefix, or the input if null/blank/already encrypted
     */
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            return plaintext;
        }
        if (isEncrypted(plaintext)) {
            // Already encrypted, return as-is to prevent double encryption
            return plaintext;
        }
        try {
            return ENCRYPTED_PREFIX + encryptor.encrypt(plaintext);
        } catch (Exception e) {
            log.error("Failed to encrypt value: {}", e.getMessage());
            throw new RuntimeException("Encryption failed", e);
        }
    }

    /**
     * Decrypt an encrypted value.
     *
     * <p>Provides backward compatibility by returning plaintext values unchanged.
     * Only values with the "ENC:" prefix are decrypted.
     *
     * @param ciphertext The value to decrypt (may be plaintext for backward compatibility)
     * @return The decrypted value, or the input if null/blank/not encrypted
     */
    public String decrypt(String ciphertext) {
        if (ciphertext == null || ciphertext.isBlank()) {
            return ciphertext;
        }
        if (!isEncrypted(ciphertext)) {
            // Backward compatibility: return plaintext values as-is
            log.debug("Value not encrypted, returning as-is for backward compatibility");
            return ciphertext;
        }
        try {
            String encrypted = ciphertext.substring(ENCRYPTED_PREFIX.length());
            return encryptor.decrypt(encrypted);
        } catch (Exception e) {
            log.error("Failed to decrypt value: {}", e.getMessage());
            throw new RuntimeException("Decryption failed", e);
        }
    }

    /**
     * Check if a value is encrypted (has the "ENC:" prefix).
     *
     * @param value The value to check
     * @return true if the value starts with "ENC:", false otherwise
     */
    public boolean isEncrypted(String value) {
        return value != null && value.startsWith(ENCRYPTED_PREFIX);
    }

    /**
     * Encrypt all sensitive string values in a map.
     * Non-sensitive fields and non-string values are left unchanged.
     *
     * @param data The map containing potentially sensitive fields
     * @return A new map with sensitive string values encrypted, or the input if null
     */
    public Map<String, Object> encryptSensitiveFields(Map<String, Object> data) {
        if (data == null) return null;
        Map<String, Object> result = new LinkedHashMap<>(data);
        for (var entry : result.entrySet()) {
            if (SENSITIVE_FIELDS.contains(entry.getKey()) && entry.getValue() instanceof String s) {
                result.put(entry.getKey(), encrypt(s));
            }
        }
        return result;
    }

    /**
     * Decrypt all sensitive string values in a map.
     * Non-sensitive fields and non-string values are left unchanged.
     * Provides backward compatibility: plaintext values (without ENC: prefix) are returned as-is.
     *
     * @param data The map containing potentially encrypted sensitive fields
     * @return A new map with sensitive string values decrypted, or the input if null
     */
    public Map<String, Object> decryptSensitiveFields(Map<String, Object> data) {
        if (data == null) return null;
        Map<String, Object> result = new LinkedHashMap<>(data);
        for (var entry : result.entrySet()) {
            if (SENSITIVE_FIELDS.contains(entry.getKey()) && entry.getValue() instanceof String s) {
                result.put(entry.getKey(), decrypt(s));
            }
        }
        return result;
    }

    /**
     * Compute HMAC-SHA256 hash of a value using the encryption password as key.
     * Used for API key lookup by hash instead of storing the key in plaintext.
     *
     * @param value The value to hash
     * @return Hex-encoded HMAC-SHA256 hash, or the input if null/blank
     */
    public String hmacHash(String value) {
        if (value == null || value.isBlank()) return value;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(this.password.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("HMAC hash failed", e);
        }
    }
}
