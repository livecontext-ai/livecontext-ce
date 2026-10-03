package com.apimarketplace.common.web;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.function.UnaryOperator;

/**
 * The service-side check of the gateway HMAC, with no servlet dependency, so the reactive
 * gateway can run the SAME verifier against its own signer in a test.
 * {@link GatewayAuthenticationFilter} delegates to it.
 *
 * <p>The signature is {@code HMAC(secret, providerId|userId|orgId|timestamp)}, and the user and
 * org it binds are the {@code X-User-ID} / {@code X-Organization-ID} headers the service
 * RECEIVES (absent = empty). A caller that signs an org must therefore send that org header.
 */
public final class GatewaySignatureVerifier {

    public static final String HEADER_GATEWAY_SECRET = "X-Gateway-Secret";
    public static final String HEADER_GATEWAY_TIMESTAMP = "X-Gateway-Timestamp";
    public static final String HEADER_USER_ID = "X-User-ID";
    public static final String HEADER_ORGANIZATION_ID = "X-Organization-ID";

    /** Maximum allowed clock skew between gateway and this service (5 minutes), either direction. */
    static final long MAX_TIMESTAMP_AGE_MS = 300_000;

    private static final String HMAC_ALGO = "HmacSHA256";
    private static final String SIGNATURE_PREFIX = "gw_";

    private final byte[] secretKey;

    public GatewaySignatureVerifier(String secretKey) {
        this.secretKey = (secretKey == null ? "" : secretKey).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Verify a request from its headers, exactly as {@link GatewayAuthenticationFilter} does.
     *
     * @param header     header lookup of the inbound request
     * @param providerId the resolved provider id (query parameter first, then header)
     */
    public boolean verify(UnaryOperator<String> header, String providerId) {
        return isValid(header.apply(HEADER_GATEWAY_SECRET), providerId, header.apply(HEADER_GATEWAY_TIMESTAMP),
                header.apply(HEADER_USER_ID), header.apply(HEADER_ORGANIZATION_ID));
    }

    /** Constant-time comparison of the received signature with the expected one. */
    public boolean isValid(String receivedSecret, String providerId, String timestamp,
                           String userId, String organizationId) {
        if (receivedSecret == null || !receivedSecret.startsWith(SIGNATURE_PREFIX) || timestamp == null) {
            return false;
        }
        long requestTime = Long.parseLong(timestamp);
        // Bounded in BOTH directions (CASA LC-035): a signature dated ahead of now used to stay
        // valid, and replayable, for as long as its future timestamp had not been reached.
        if (Math.abs(System.currentTimeMillis() - requestTime) > MAX_TIMESTAMP_AGE_MS) {
            return false;
        }
        String expected = expectedSecret(providerId, timestamp, userId, organizationId);
        return MessageDigest.isEqual(
                receivedSecret.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The expected signature. MUST stay byte-identical to
     * {@code gateway.GatewaySecurityService.computeSignature}.
     */
    public String expectedSecret(String providerId, String timestamp, String userId, String organizationId) {
        String safeUser = userId != null ? userId : "";
        String safeOrg = organizationId != null ? organizationId : "";
        String data = providerId + "|" + safeUser + "|" + safeOrg + "|" + timestamp;
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(secretKey, HMAC_ALGO));
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return SIGNATURE_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
