package com.apimarketplace.auth.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * HMAC-SHA256 hashing of {@code install_id || ":" || ip} with two-generation
 * key rotation (doc §1 #14, §1 #46).
 *
 * <p>Env vars:
 * <ul>
 *   <li>{@code IP_HASH_HMAC_KEY_V1} - V1 secret (always required).</li>
 *   <li>{@code IP_HASH_HMAC_KEY_V2} - V2 secret (required only during rotation).</li>
 *   <li>{@code KEY_HMAC_CURRENT_VERSION} - which version new writes use ({@code 1} or {@code 2}).</li>
 * </ul>
 *
 * <p>Rotation procedure (doc §7):
 * <ol>
 *   <li>Deploy both keys, {@code CURRENT=1}. Service reads/writes V1, can verify V2 lookbacks.</li>
 *   <li>Flip {@code CURRENT=2} + redeploy. New writes use V2; historical V1 rows still verify
 *       via {@link #verify(UUID, String, String, int)} dispatched on {@code key_version}.</li>
 *   <li>Once all rows have been rewritten (next heartbeat per install), drop V1.</li>
 * </ol>
 *
 * <p>Compromise of {@code IP_HASH_HMAC_KEY_V*} → invalidates only the forensic
 * IP intel; install registrations and the audit trail are unaffected.
 */
@Service
@ConditionalOnProperty(name = "auth.mode", havingValue = "keycloak", matchIfMissing = false)
public class IpHashService {

    private static final Logger log = LoggerFactory.getLogger(IpHashService.class);

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final String keyV1;
    private final String keyV2;
    private final int currentVersion;

    public IpHashService(
            @Value("${cloud-link.ip-hash.key-v1:}") String keyV1,
            @Value("${cloud-link.ip-hash.key-v2:}") String keyV2,
            @Value("${cloud-link.ip-hash.current-version:1}") int currentVersion
    ) {
        this.keyV1 = keyV1;
        this.keyV2 = keyV2;
        this.currentVersion = currentVersion;
    }

    @PostConstruct
    void validateConfiguration() {
        if (currentVersion != 1 && currentVersion != 2) {
            throw new IllegalStateException(
                    "cloud-link.ip-hash.current-version must be 1 or 2, got " + currentVersion);
        }
        if (currentVersion == 1 && (keyV1 == null || keyV1.isBlank())) {
            throw new IllegalStateException(
                    "cloud-link.ip-hash.key-v1 must be set when current-version=1");
        }
        if (currentVersion == 2 && (keyV2 == null || keyV2.isBlank())) {
            throw new IllegalStateException(
                    "cloud-link.ip-hash.key-v2 must be set when current-version=2");
        }
        log.info("IpHashService initialized: currentVersion={}, v1Configured={}, v2Configured={}",
                currentVersion, !keyV1.isBlank(), !keyV2.isBlank());
    }

    /**
     * Hash {@code installId || ":" || ip} with the CURRENT key. The returned
     * {@code keyVersion} MUST be persisted alongside the hash so a future
     * verify can dispatch to the right key.
     */
    public HashResult hashWithCurrent(UUID installId, String ip) {
        return new HashResult(hashWith(installId, ip, currentVersion), currentVersion);
    }

    /**
     * Verify whether {@code candidate} (the new IP) hashes to {@code expectedHash}
     * (the stored value) under the key generation that produced it. Used to
     * detect "same IP as last heartbeat" without ever storing the raw IP.
     */
    public boolean matches(UUID installId, String candidateIp, String expectedHash, int keyVersion) {
        if (expectedHash == null || candidateIp == null) {
            return false;
        }
        try {
            String candidateHash = hashWith(installId, candidateIp, keyVersion);
            return constantTimeEquals(candidateHash, expectedHash);
        } catch (IllegalStateException unconfigured) {
            // V1 retired but DB still has V1 rows → log + treat as "changed".
            // The next heartbeat will rewrite under the new key anyway.
            log.warn("IpHashService.matches: keyVersion {} not configured (post-rotation row?) - treating as IP change",
                    keyVersion);
            return false;
        }
    }

    private String hashWith(UUID installId, String ip, int version) {
        String secret = switch (version) {
            case 1 -> keyV1;
            case 2 -> keyV2;
            default -> throw new IllegalArgumentException("Unsupported key version: " + version);
        };
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("HMAC key V" + version + " is not configured");
        }
        String payload = installId.toString() + ":" + ip;
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            // HmacSHA256 is mandated by the JDK - this branch is unreachable on any sane runtime.
            throw new IllegalStateException("HMAC algorithm unavailable", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }

    /**
     * Carrier for "the hash + the key generation it was computed under".
     * Both MUST be persisted together - the version drives verify lookups.
     */
    public record HashResult(String hash, int keyVersion) {}
}
