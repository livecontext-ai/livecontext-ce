package com.apimarketplace.common.web;

import org.springframework.http.HttpHeaders;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * Signs a service-to-service call the way {@link GatewayAuthenticationFilter} verifies it.
 *
 * <p>The signed string is {@code providerId|userId|organizationId|timestamp}, and it exists in
 * FOUR places: the gateway's own signer, this filter's verification, and (before this class) a
 * hand-rolled copy inside every client that talks to an HMAC-gated internal endpoint. Four copies
 * of one wire format is how a caller ends up unsigned by accident: a new client either copies the
 * block or, more often, skips it, and the request still succeeds because most internal paths are
 * not gated. This class is the single client-side spelling so a new caller has something to reach
 * for that is shorter than writing the HMAC by hand.
 *
 * <p><b>The role headers are deliberately NOT part of the signed string.</b> Changing what is
 * signed is not a code change, it is a fleet-wide cutover: during a rolling deploy, pods on the
 * previous image sign the old string while pods on the new one verify the new string, so every
 * in-cluster call 401s until the rollout finishes. Binding the role needs a versioned signature
 * with a transition window where both are accepted, which is a separate piece of work. Until then,
 * a role header is trusted exactly as far as the HMAC boundary it arrived behind, which is why a
 * role must never be read from a request BODY on a user-reachable endpoint.
 */
public final class InternalGatewaySigner {

    private static final String HMAC_ALGO = "HmacSHA256";
    private static final String SIGNATURE_PREFIX = "gw_";

    public static final String HEADER_PROVIDER_ID = "X-Provider-ID";
    public static final String HEADER_TIMESTAMP = "X-Gateway-Timestamp";
    public static final String HEADER_SECRET = "X-Gateway-Secret";

    private InternalGatewaySigner() {}

    /**
     * Compute the signature for one call.
     *
     * @param providerId       the caller's stable identity, echoed in {@code X-Provider-ID}
     * @param userId           the {@code X-User-ID} the request will carry, or null
     * @param organizationId   the {@code X-Organization-ID} the request will carry, or null
     * @param timestamp        the {@code X-Gateway-Timestamp} the request will carry
     * @param gatewaySecretKey the shared secret
     */
    public static String sign(String providerId, String userId, String organizationId,
                              String timestamp, String gatewaySecretKey) {
        String safeUser = userId != null ? userId : "";
        String safeOrg = organizationId != null ? organizationId : "";
        String data = providerId + "|" + safeUser + "|" + safeOrg + "|" + timestamp;
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(gatewaySecretKey.getBytes(StandardCharsets.UTF_8), HMAC_ALGO));
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return SIGNATURE_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    /**
     * Stamp the three gateway headers onto {@code headers}, signing over the {@code X-User-ID} and
     * {@code X-Organization-ID} already present on them. Set those first: the signature binds them,
     * so adding either afterwards produces a request the filter rejects.
     *
     * <p>A blank secret is left UNSIGNED rather than signed with an empty key, because an empty key
     * yields a well-formed signature that can never verify: the caller would get a 401 that reads
     * like a secret mismatch instead of the missing configuration it is.
     */
    public static void stamp(HttpHeaders headers, String providerId, String gatewaySecretKey) {
        if (gatewaySecretKey == null || gatewaySecretKey.isBlank()) {
            return;
        }
        String timestamp = String.valueOf(System.currentTimeMillis());
        headers.set(HEADER_PROVIDER_ID, providerId);
        headers.set(HEADER_TIMESTAMP, timestamp);
        headers.set(HEADER_SECRET, sign(providerId,
                headers.getFirst("X-User-ID"),
                headers.getFirst("X-Organization-ID"),
                timestamp,
                gatewaySecretKey));
    }
}
