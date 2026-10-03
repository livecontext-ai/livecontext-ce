package com.apimarketplace.common.web;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * Version 2 of the gateway request signature (CASA LC-035).
 *
 * <p>Version 1 ({@code X-Gateway-Secret}) signs {@code providerId|userId|orgId|timestamp}: the
 * role headers, the HTTP method and the path are outside the MAC, and a {@code |} inside any field
 * lets two different tuples produce the same signed string. Version 2 fixes all three:
 * <ul>
 *   <li>it binds every identity and privilege header a downstream service trusts
 *       ({@link #SIGNED_HEADERS}): provider, user, organization, organization role, platform roles,
 *       API-key scopes and the share-context marker;</li>
 *   <li>it binds the request line: method, path and query;</li>
 *   <li>each field is length-prefixed ({@code <byteLength>:<bytes>\n}), so no field value can
 *       spill into its neighbour whatever characters it contains.</li>
 * </ul>
 *
 * <p>The path and query are signed PERCENT-DECODED (to raw bytes, {@code +} left alone) so the
 * signer and the verifier agree even when an HTTP client or proxy re-encodes a character the other
 * side left literal. Headers that occur more than once are joined with {@code ,} in wire order, the
 * same way Spring binds a multi-valued header to a {@code String} parameter; an absent header and an
 * empty one both sign as the empty string.
 *
 * <p>The signature travels in {@link #HEADER} next to the v1 headers, and reuses
 * {@code X-Gateway-Timestamp}. Nothing here decides whether v1 is still accepted: that is
 * {@link GatewayAuthenticationFilter}'s {@code gateway.signature.accept-v1} switch. Every byte of
 * this format is pinned by {@code shared/contracts/gateway-signature-fixtures.json}, which the Node
 * twin ({@code mcp/bridge/lib/gatewayAuth.mjs}) checks too.
 */
public final class GatewaySignatureV2 {

    public static final String HEADER = "X-Gateway-Signature-V2";
    public static final String PREFIX = "gw2_";
    public static final String HEADER_TIMESTAMP = "X-Gateway-Timestamp";
    public static final String HEADER_PROVIDER_ID = "X-Provider-ID";

    /** Signed headers, in signing order. Changing this list is a wire-format change. */
    public static final List<String> SIGNED_HEADERS = List.of(
            "X-Provider-ID",
            "X-User-ID",
            "X-Organization-ID",
            "X-Organization-Role",
            "X-User-Roles",
            "X-Api-Key-Scopes",
            "X-Share-Context");

    private static final String DOMAIN = "lc-gateway-v2";
    private static final String HMAC_ALGO = "HmacSHA256";

    private GatewaySignatureV2() {}

    /**
     * Compute the v2 signature of one request.
     *
     * @param secret       the shared gateway secret
     * @param method       the HTTP method, any case
     * @param rawPath      the path as sent on the wire (percent-encoded or not), without query
     * @param rawQuery     the query string without its leading {@code ?}, or null
     * @param headerValues all values of a header by name (case-insensitive lookup is the caller's
     *                     job), null or empty when the header is absent
     * @param timestamp    the {@code X-Gateway-Timestamp} value
     */
    public static String sign(String secret, String method, String rawPath, String rawQuery,
                              Function<String, List<String>> headerValues, String timestamp) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        field(out, DOMAIN.getBytes(StandardCharsets.UTF_8));
        for (String name : SIGNED_HEADERS) {
            field(out, joined(headerValues.apply(name)).getBytes(StandardCharsets.UTF_8));
        }
        field(out, (method == null ? "" : method.toUpperCase(Locale.ROOT)).getBytes(StandardCharsets.UTF_8));
        field(out, percentDecode(rawPath));
        field(out, percentDecode(rawQuery));
        field(out, (timestamp == null ? "" : timestamp).getBytes(StandardCharsets.UTF_8));
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGO));
            return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(out.toByteArray()));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    /** Convenience overload taking the full request {@link URI}. */
    public static String sign(String secret, String method, URI uri,
                              Function<String, List<String>> headerValues, String timestamp) {
        return sign(secret, method, uri.getRawPath(), uri.getRawQuery(), headerValues, timestamp);
    }

    /**
     * The whole v2 verification, exactly as {@link GatewayAuthenticationFilter} runs it, without
     * any servlet type (so the reactive gateway can prove end to end that what it sends verifies).
     *
     * @param received   the {@link #HEADER} value
     * @param maxSkewMs  freshness window, in either direction
     * @param nowMs      current time
     * @return true when X-Provider-ID and X-Gateway-Timestamp are present, the timestamp is
     *         fresh and the signature matches
     * @throws NumberFormatException when the timestamp is not a number
     */
    public static boolean verify(String secret, String method, String rawPath, String rawQuery,
                                 Function<String, List<String>> headerValues, String received,
                                 long maxSkewMs, long nowMs) {
        List<String> timestamps = headerValues.apply(HEADER_TIMESTAMP);
        List<String> providers = headerValues.apply(HEADER_PROVIDER_ID);
        if (timestamps == null || timestamps.isEmpty() || providers == null || providers.isEmpty()) {
            return false;
        }
        String timestamp = timestamps.get(0);
        if (Math.abs(nowMs - Long.parseLong(timestamp)) > maxSkewMs) {
            return false;
        }
        return matches(received, sign(secret, method, rawPath, rawQuery, headerValues, timestamp));
    }

    /** Constant-time comparison of a received signature with the expected one. */
    public static boolean matches(String received, String expected) {
        if (received == null || expected == null || !received.startsWith(PREFIX)) {
            return false;
        }
        return MessageDigest.isEqual(received.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
    }

    private static String joined(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            String v = values.get(i);
            sb.append(v == null ? "" : v);
        }
        return sb.toString();
    }

    private static void field(ByteArrayOutputStream out, byte[] value) {
        byte[] len = Integer.toString(value.length).getBytes(StandardCharsets.US_ASCII);
        out.write(len, 0, len.length);
        out.write(':');
        out.write(value, 0, value.length);
        out.write('\n');
    }

    /**
     * Percent-decode to raw bytes. {@code %XX} with two hex digits becomes that byte; everything
     * else (including a malformed {@code %} and {@code +}) is kept as its UTF-8 bytes. Working on
     * bytes, not a decoded String, means no charset decision can differ between signer and verifier.
     */
    static byte[] percentDecode(String s) {
        if (s == null || s.isEmpty()) {
            return new byte[0];
        }
        byte[] in = s.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream(in.length);
        for (int i = 0; i < in.length; i++) {
            byte b = in[i];
            if (b == '%' && i + 2 < in.length && hex(in[i + 1]) >= 0 && hex(in[i + 2]) >= 0) {
                out.write((hex(in[i + 1]) << 4) | hex(in[i + 2]));
                i += 2;
            } else {
                out.write(b);
            }
        }
        return out.toByteArray();
    }

    private static int hex(byte c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    }
}
