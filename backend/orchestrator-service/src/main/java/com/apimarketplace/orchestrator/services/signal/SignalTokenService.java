package com.apimarketplace.orchestrator.services.signal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;

/**
 * Service for generating and validating signed webhook callback tokens.
 *
 * Tokens are HMAC-SHA256 signed strings containing:
 * - runId, nodeId, itemId, expiresAt
 *
 * The token is placed in the X-Signal-Token header (not URL)
 * to avoid log leakage.
 */
@Service
public class SignalTokenService {

    private static final Logger logger = LoggerFactory.getLogger(SignalTokenService.class);
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String SEPARATOR = "|";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final byte[] secretKey;
    private final Clock clock;

    @Autowired
    public SignalTokenService(
            @Value("${credential.encryption.password:}") String secretPassword,
            @Value("${security.reject-default-secrets:false}") boolean rejectDefaultSecrets,
            Clock clock) {
        if (secretPassword == null || secretPassword.isBlank()) {
            if (rejectDefaultSecrets || System.getenv("KUBERNETES_SERVICE_HOST") != null) {
                throw new IllegalStateException("credential.encryption.password must be configured for signal tokens");
            }
            byte[] generated = new byte[32];
            SECURE_RANDOM.nextBytes(generated);
            this.secretKey = generated;
            logger.warn("credential.encryption.password is missing; using an ephemeral signal token key for this process");
        } else {
            this.secretKey = secretPassword.getBytes(StandardCharsets.UTF_8);
        }
        this.clock = clock;
    }

    public SignalTokenService(String secretPassword, Clock clock) {
        this(secretPassword, false, clock);
    }

    /**
     * Generate a signed token for a webhook callback.
     *
     * @param runId The workflow run ID
     * @param nodeId The node ID awaiting the signal
     * @param itemId The item ID (for split contexts)
     * @param expiresAt Token expiration time
     * @return Signed token string (base64url encoded)
     */
    public String generateToken(String runId, String nodeId, String itemId, Instant expiresAt) {
        String payload = buildPayload(runId, nodeId, itemId, expiresAt);
        String signature = sign(payload);
        String token = payload + SEPARATOR + signature;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
            token.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Validate a signed token and extract its claims.
     *
     * @param token The base64url encoded token
     * @return Parsed token claims, or null if invalid/expired
     */
    public TokenClaims validateToken(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }

        try {
            String decoded = new String(
                Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);

            // Split into payload and signature
            int lastSep = decoded.lastIndexOf(SEPARATOR);
            if (lastSep < 0) {
                logger.warn("[SignalToken] Invalid token format: no signature separator");
                return null;
            }

            String payload = decoded.substring(0, lastSep);
            String providedSignature = decoded.substring(lastSep + 1);

            // Verify signature
            String expectedSignature = sign(payload);
            if (!expectedSignature.equals(providedSignature)) {
                logger.warn("[SignalToken] Invalid signature");
                return null;
            }

            // Parse payload
            String[] parts = payload.split("\\" + SEPARATOR);
            if (parts.length != 4) {
                logger.warn("[SignalToken] Invalid payload: expected 4 parts, got {}", parts.length);
                return null;
            }

            String runId = parts[0];
            String nodeId = parts[1];
            String itemId = parts[2];
            Instant expiresAt = Instant.parse(parts[3]);

            // Check expiration
            if (clock.instant().isAfter(expiresAt)) {
                logger.warn("[SignalToken] Token expired: expiresAt={}", expiresAt);
                return null;
            }

            return new TokenClaims(runId, nodeId, itemId, expiresAt);

        } catch (Exception e) {
            logger.warn("[SignalToken] Token validation failed: {}", e.getMessage());
            return null;
        }
    }

    private String buildPayload(String runId, String nodeId, String itemId, Instant expiresAt) {
        return runId + SEPARATOR + nodeId + SEPARATOR + itemId + SEPARATOR + expiresAt.toString();
    }

    private String sign(String data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secretKey, HMAC_ALGORITHM));
            byte[] hmac = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hmac);
        } catch (Exception e) {
            throw new RuntimeException("HMAC signing failed", e);
        }
    }

    /**
     * Parsed token claims after successful validation.
     */
    public record TokenClaims(String runId, String nodeId, String itemId, Instant expiresAt) {}
}
