package com.apimarketplace.common.web;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * Body-bound signature for calls INTO the agent bridge ({@code X-Bridge-Signature}), LC-001.
 *
 * <p>The v1 gateway signature binds only {@code providerId|userId|orgId|timestamp}; on the bridge
 * the request BODY (prompt, tenant, credentials) decides what an agent does, so it must be bound
 * too. This class reuses the canonical encoding of the fleet v2 signature (CASA LC-035,
 * {@code X-Gateway-Signature-V2}): every field length-prefixed ({@code <byteLength>:<bytes>\n}),
 * the same seven identity/privilege headers in the same order, the method upper-cased, path and
 * query signed percent-decoded. It differs in two places so the two can never be confused: the
 * domain field is {@code lc-bridge-v2}, and one extra LAST field carries the lowercase hex SHA-256
 * of the exact body bytes sent.
 *
 * <p>Node twin: {@code mcp/bridge/lib/bridgeSignature.mjs}. The bytes are pinned by
 * {@code shared/contracts/bridge-signature-fixtures.json}, checked by both test suites.
 */
public final class BridgeRequestSignature {

    public static final String HEADER = "X-Bridge-Signature";
    public static final String PREFIX = "br2_";

    /** Same list and order as the fleet v2 signature. Changing it is a wire-format change. */
    public static final List<String> SIGNED_HEADERS = List.of(
            "X-Provider-ID",
            "X-User-ID",
            "X-Organization-ID",
            "X-Organization-Role",
            "X-User-Roles",
            "X-Api-Key-Scopes",
            "X-Share-Context");

    private static final String DOMAIN = "lc-bridge-v2";
    private static final String HMAC_ALGO = "HmacSHA256";

    private BridgeRequestSignature() {}

    /**
     * @param headerValues all values of a header by name, null or empty when absent
     * @param body         the exact bytes that will be sent, null for an empty body
     */
    public static String sign(String secret, String method, String rawPath, String rawQuery,
                              Function<String, List<String>> headerValues, String timestamp, byte[] body) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        field(out, DOMAIN.getBytes(StandardCharsets.UTF_8));
        for (String name : SIGNED_HEADERS) {
            field(out, joined(headerValues.apply(name)).getBytes(StandardCharsets.UTF_8));
        }
        field(out, (method == null ? "" : method.toUpperCase(Locale.ROOT)).getBytes(StandardCharsets.UTF_8));
        field(out, percentDecode(rawPath));
        field(out, percentDecode(rawQuery));
        field(out, (timestamp == null ? "" : timestamp).getBytes(StandardCharsets.UTF_8));
        field(out, sha256Hex(body == null ? new byte[0] : body).getBytes(StandardCharsets.US_ASCII));
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGO));
            return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(out.toByteArray()));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
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

    /** Percent-decode to raw bytes; {@code +} and a malformed {@code %} are kept (fleet v2 rule). */
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
