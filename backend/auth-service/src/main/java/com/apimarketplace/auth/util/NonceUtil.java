package com.apimarketplace.auth.util;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
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
 */
@Component
public class NonceUtil {

    private static final Logger log = LoggerFactory.getLogger(NonceUtil.class);

    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/ECB/PKCS5Padding";

    /** 16-byte AES-128 key material derived from the configured key. */
    private final byte[] keyBytes;

    public NonceUtil(@Value("${auth.nonce.encryption-key:}") String configuredKey) {
        if (configuredKey == null || configuredKey.isBlank()) {
            this.keyBytes = generateEphemeralKeyBytes();
            log.warn("auth.nonce.encryption-key is not set - using an ephemeral startup key. " +
                    "Set a deployment-specific value (NONCE_ENCRYPTION_KEY env) so billing nonces " +
                    "survive restarts and replicas.");
        } else {
            this.keyBytes = deriveKeyBytes(configuredKey);
        }
    }

    /** Convenience constructor for tests: uses an ephemeral startup key. */
    public NonceUtil() {
        this("");
    }

    private static byte[] generateEphemeralKeyBytes() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
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
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw);
            return Arrays.copyOf(digest, 16);
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
            String nonce = "n_" + encryptedData;
            
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
            
            // Verify the nonce format
            if (!nonce.startsWith("n_")) {
                log.warn("Invalid nonce format: {}", nonce);
                return null;
            }
            
            // Extract and decrypt the data
            String encryptedData = nonce.substring(2);
            String decryptedData = decrypt(encryptedData);
            
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
     * Encrypts a string.
     */
    private String encrypt(String data) throws Exception {
        SecretKeySpec keySpec = new SecretKeySpec(keyBytes, ALGORITHM);
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, keySpec);
        
        byte[] encryptedBytes = cipher.doFinal(data.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(encryptedBytes);
    }
    
    /**
     * Decrypts a string.
     */
    private String decrypt(String encryptedData) {
        try {
            SecretKeySpec keySpec = new SecretKeySpec(keyBytes, ALGORITHM);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, keySpec);
            
            byte[] decodedBytes = Base64.getDecoder().decode(encryptedData);
            byte[] decryptedBytes = cipher.doFinal(decodedBytes);
            
            return new String(decryptedBytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("Decryption failed: {}", e.getMessage());
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
