package com.apimarketplace.auth.util;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Utility for creating and decoding nonces from user_id.
 * The nonce is an opaque identifier that masks the user ID for Stripe
 * while allowing secure recovery.
 *
 * <p>Formats: {@code n2_} (written) is AES-256-GCM with a random 96-bit IV and a 128-bit tag,
 * base64url without padding (a valid Stripe {@code client_reference_id}), keyed by the SHA-256 of
 * {@code auth.nonce.encryption-key}. {@code n_} (read only) is the historical AES-128-ECB
 * envelope: such nonces live in Stripe customer metadata for the life of a subscription, so they
 * stay decodable. ECB is no longer produced (CASA LC-026).
 */
@Component
public class NonceUtil {

    private static final Logger log = LoggerFactory.getLogger(NonceUtil.class);

    private static final String ALGORITHM = "AES";
    private static final String LEGACY_TRANSFORMATION = "AES/ECB/PKCS5Padding";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /** Prefix of the authenticated format written today. */
    static final String NONCE_PREFIX = "n2_";
    /** Prefix of the historical AES-ECB format, still readable. */
    static final String LEGACY_NONCE_PREFIX = "n_";

    /** 16-byte AES-128 key material, legacy ECB reads only. */
    private final byte[] legacyKeyBytes;
    /** 32-byte AES-256 key material of the GCM format. */
    private final byte[] keyBytes;
    /** True when no key is configured (see {@link #isUsingEphemeralKey()}). */
    private final boolean ephemeralKey;

    /**
     * {@code @Autowired} is REQUIRED here, not decorative: with two constructors and no
     * annotation, Spring instantiates a component through its no-arg constructor, so this
     * bean silently ran on an ephemeral key in every deployment, even with the property set
     * (2026-09-15: NONCE_ENCRYPTION_KEY was in the pod and the startup warning still fired).
     * The no-arg constructor stays for tests only.
     */
    @Autowired
    public NonceUtil(@Value("${auth.nonce.encryption-key:}") String configuredKey) {
        if (configuredKey == null || configuredKey.isBlank()) {
            byte[] ephemeral = generateEphemeralKeyBytes();
            this.keyBytes = ephemeral;
            this.legacyKeyBytes = Arrays.copyOf(ephemeral, 16);
            this.ephemeralKey = true;
            // Deliberately NOT fatal, even in prod: refusing to start without the secret would take
            // auth-service (every login) down for a billing-only concern. The ephemeral state is
            // instead published as the gauge auth.nonce.key.ephemeral (1 = unset) so it is
            // alertable, and setting NONCE_ENCRYPTION_KEY is a mandatory deploy step.
            log.warn("auth.nonce.encryption-key is not set - using an ephemeral startup key. " +
                    "Set a deployment-specific value (NONCE_ENCRYPTION_KEY env) so billing nonces " +
                    "survive restarts and replicas (metric auth.nonce.key.ephemeral=1).");
        } else {
            this.legacyKeyBytes = deriveKeyBytes(configuredKey);
            this.keyBytes = sha256(configuredKey.getBytes(StandardCharsets.UTF_8));
            this.ephemeralKey = false;
        }
    }

    /** True when no key is configured and this process mints its own (nonces die with it). */
    public boolean isUsingEphemeralKey() {
        return ephemeralKey;
    }

    /**
     * Publishes {@link #isUsingEphemeralKey()} as the gauge {@code auth.nonce.key.ephemeral}
     * (1 = no NONCE_ENCRYPTION_KEY, cross-replica Stripe webhooks cannot decode nonces).
     * Optional: a context without a registry still gets a working component.
     */
    @Autowired(required = false)
    public void bindTo(io.micrometer.core.instrument.MeterRegistry registry) {
        if (registry == null) {
            return;
        }
        io.micrometer.core.instrument.Gauge.builder("auth.nonce.key.ephemeral", this, n -> n.ephemeralKey ? 1 : 0)
                .description("1 when auth.nonce.encryption-key (NONCE_ENCRYPTION_KEY) is not configured")
                .register(registry);
    }

    /** Convenience constructor for tests: uses an ephemeral startup key. */
    public NonceUtil() {
        this("");
    }

    private static byte[] generateEphemeralKeyBytes() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return bytes;
    }

    /**
     * AES-128 needs exactly 16 bytes. A 16-byte key is used as-is (preserves
     * compatibility with values encrypted by the historical literal); anything
     * else is derived as the first 16 bytes of its SHA-256.
     */
    private static byte[] deriveKeyBytes(String key) {
        byte[] raw = key.getBytes(StandardCharsets.UTF_8);
        if (raw.length == 16) {
            return raw;
        }
        return Arrays.copyOf(sha256(raw), 16);
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
    
    // Temporary cache for nonces (in production, use Redis or DB)
    private final Map<String, Long> nonceToUserIdCache = new ConcurrentHashMap<>();
    private final Map<Long, String> userIdToNonceCache = new ConcurrentHashMap<>();
    
    // Cache time-to-live in milliseconds (1 hour)
    private static final long CACHE_TTL = 60 * 60 * 1000;
    
    /**
     * Generates a complex nonce from user_id.
     * The nonce is an opaque identifier that does not reveal the user ID.
     *
     * @param userId The user ID to mask
     * @return The generated nonce
     */
    public String generateNonce(Long userId) {
        if (userId == null) {
            throw new IllegalArgumentException("User ID cannot be null");
        }
        
        try {
            // Check if a nonce already exists for this user
            String existingNonce = userIdToNonceCache.get(userId);
            if (existingNonce != null) {
                log.debug("Reusing existing nonce for user {}: {}", userId, existingNonce);
                return existingNonce;
            }
            
            // Generate a unique nonce based on user ID + timestamp + random
            long timestamp = System.currentTimeMillis();
            String randomSuffix = generateRandomString(8);
            String dataToEncrypt = userId + ":" + timestamp + ":" + randomSuffix;
            
            // Encrypt the data
            String encryptedData = encrypt(dataToEncrypt);
            
            // Create the final nonce with a prefix for identification
            String nonce = NONCE_PREFIX + encryptedData;
            
            // Store in cache
            nonceToUserIdCache.put(nonce, userId);
            userIdToNonceCache.put(userId, nonce);

            log.debug("Generated nonce for user {}: {}", userId, nonce);
            return nonce;
            
        } catch (Exception e) {
            log.error("Error generating nonce for user {}: {}", userId, e.getMessage(), e);
            throw new RuntimeException("Failed to generate nonce", e);
        }
    }
    
    /**
     * Decodes a nonce to retrieve the user ID.
     *
     * @param nonce The nonce to decode
     * @return The user ID or null if the nonce is invalid
     */
    public Long decodeNonce(String nonce) {
        if (nonce == null || nonce.isEmpty()) {
            log.warn("Nonce is null or empty");
            return null;
        }
        
        try {
            // Check the cache first
            Long cachedUserId = nonceToUserIdCache.get(nonce);
            if (cachedUserId != null) {
                log.debug("Retrieved user ID from cache for nonce: {}", nonce);
                return cachedUserId;
            }
            
            // Verify the nonce format and decrypt: n2_ (GCM, written today) or n_ (legacy ECB)
            String decryptedData;
            if (nonce.startsWith(NONCE_PREFIX)) {
                decryptedData = decrypt(nonce.substring(NONCE_PREFIX.length()));
            } else if (nonce.startsWith(LEGACY_NONCE_PREFIX)) {
                decryptedData = decryptLegacy(nonce.substring(LEGACY_NONCE_PREFIX.length()));
            } else {
                log.warn("Invalid nonce format: {}", nonce);
                return null;
            }
            
            if (decryptedData == null) {
                log.warn("Failed to decrypt nonce: {}", nonce);
                return null;
            }
            
            // Parse the decrypted data
            String[] parts = decryptedData.split(":");
            if (parts.length != 3) {
                log.warn("Invalid decrypted data format: {}", decryptedData);
                return null;
            }
            
            Long userId = Long.parseLong(parts[0]);
            long timestamp = Long.parseLong(parts[1]);
            
            // Verify the nonce age (optional)
            long age = System.currentTimeMillis() - timestamp;
            if (age > CACHE_TTL) {
                log.warn("Nonce is too old: {} ms", age);
                // Don't return null, just log a warning
            }
            
            // Store in cache
            nonceToUserIdCache.put(nonce, userId);
            userIdToNonceCache.put(userId, nonce);

            log.debug("Decoded nonce {} to user ID: {}", nonce, userId);
            return userId;
            
        } catch (Exception e) {
            log.error("Error decoding nonce {}: {}", nonce, e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * Validates that a nonce is valid for a given user.
     *
     * @param nonce The nonce to validate
     * @param userId The expected user ID
     * @return true if the nonce is valid for this user
     */
    public boolean validateNonce(String nonce, Long userId) {
        if (nonce == null || userId == null) {
            return false;
        }
        
        Long decodedUserId = decodeNonce(nonce);
        return userId.equals(decodedUserId);
    }
    
    /**
     * Generates a random string of the specified length.
     */
    private String generateRandomString(int length) {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(length);
        
        for (int i = 0; i < length; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        
        return sb.toString();
    }
    
    /**
     * Seals the payload with AES-256-GCM: a fresh 96-bit IV per call, prepended to
     * ciphertext+tag, base64url without padding.
     */
    private String encrypt(String data) throws Exception {
        byte[] iv = new byte[GCM_IV_BYTES];
        SECURE_RANDOM.nextBytes(iv);
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keyBytes, ALGORITHM),
                new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] sealed = cipher.doFinal(data.getBytes(StandardCharsets.UTF_8));
        byte[] envelope = new byte[iv.length + sealed.length];
        System.arraycopy(iv, 0, envelope, 0, iv.length);
        System.arraycopy(sealed, 0, envelope, iv.length, sealed.length);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(envelope);
    }

    /** Opens an {@code n2_} payload; null when it is malformed or its tag does not verify. */
    private String decrypt(String encryptedData) {
        try {
            byte[] envelope = Base64.getUrlDecoder().decode(encryptedData);
            if (envelope.length <= GCM_IV_BYTES) {
                log.warn("Nonce payload too short to carry an IV");
                return null;
            }
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes, ALGORITHM),
                    new GCMParameterSpec(GCM_TAG_BITS, envelope, 0, GCM_IV_BYTES));
            byte[] plain = cipher.doFinal(envelope, GCM_IV_BYTES, envelope.length - GCM_IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("Decryption failed: {}", e.getMessage());
            return null;
        }
    }

    /** Opens a historical {@code n_} (AES-128-ECB) payload. Read only: nothing writes this shape. */
    private String decryptLegacy(String encryptedData) {
        try {
            Cipher cipher = Cipher.getInstance(LEGACY_TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(legacyKeyBytes, ALGORITHM));
            return new String(cipher.doFinal(Base64.getDecoder().decode(encryptedData)),
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("Legacy nonce decryption failed: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * Cleans up the cache of expired nonces.
     */
    public void cleanupExpiredNonces() {
        long currentTime = System.currentTimeMillis();
        int cleaned = 0;
        
        // Clean up the cache (simplified - in production, use a more sophisticated system)
        if (nonceToUserIdCache.size() > 1000) { // Arbitrary threshold
            nonceToUserIdCache.clear();
            userIdToNonceCache.clear();
            cleaned = nonceToUserIdCache.size();
        }
        
        if (cleaned > 0) {
            log.info("Cleaned up {} expired nonces from cache", cleaned);
        }
    }
    
    /**
     * Gets cache statistics.
     */
    public Map<String, Object> getCacheStats() {
        Map<String, Object> stats = new ConcurrentHashMap<>();
        stats.put("nonceToUserIdCacheSize", nonceToUserIdCache.size());
        stats.put("userIdToNonceCacheSize", userIdToNonceCache.size());
        return stats;
    }
}
