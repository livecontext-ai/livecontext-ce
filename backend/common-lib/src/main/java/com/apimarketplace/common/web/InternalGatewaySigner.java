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
 * <p><b>The v1 string does not bind the role headers, the method or the path.</b> That is what
 * {@link GatewaySignatureV2} adds (CASA LC-035). Changing what is signed is a fleet-wide cutover,
 * so v2 is sent NEXT TO v1 rather than instead of it: an old verifier ignores the extra header and
 * checks v1, a new verifier checks v2 and (while {@code gateway.signature.accept-v1} is true) still
 * accepts a v1-only request from a signer that has not been redeployed yet. v2 is computed at SEND
 * time by {@link GatewaySignatureV2Interceptor} (or {@link #stampV2} for a non-RestTemplate client),
 * because only then are the method and the final URI known.
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

    /**
     * Add the v2 signature to a request that {@link #stamp} already signed, binding the method,
     * the URI and every header in {@link GatewaySignatureV2#SIGNED_HEADERS} as they are NOW on
     * {@code headers}. Call it last, once nothing else will touch the request. A request without
     * {@code X-Gateway-Timestamp} (the caller never signed it) or a blank secret is left alone.
     */
    public static void stampV2(HttpHeaders headers, String method, java.net.URI uri, String gatewaySecretKey) {
        if (gatewaySecretKey == null || gatewaySecretKey.isBlank()) {
            return;
        }
        String timestamp = headers.getFirst(HEADER_TIMESTAMP);
        if (timestamp == null || headers.getFirst(HEADER_PROVIDER_ID) == null) {
            return;
        }
        headers.set(GatewaySignatureV2.HEADER,
                GatewaySignatureV2.sign(gatewaySecretKey, method, uri, headers::get, timestamp));
    }
}
