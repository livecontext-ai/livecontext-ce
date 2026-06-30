package com.apimarketplace.catalog.service.execution;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * AWS Signature Version 4 signer for catalog HTTP tool calls.
 *
 * <p>Implements the SigV4 canonical request + string-to-sign + derived key + signature
 * algorithm described in
 * <a href="https://docs.aws.amazon.com/general/latest/gr/sigv4_signing.html">AWS docs</a>.
 *
 * <p>Given an HTTP request (method + URL + headers + body bytes) and AWS credentials
 * (access_key_id, secret_access_key, region), this signer:
 * <ul>
 *   <li>derives the {@code service} name from the URL host (e.g. {@code sns.us-east-1.amazonaws.com} → {@code sns})</li>
 *   <li>adds {@code x-amz-date} and {@code x-amz-content-sha256} headers</li>
 *   <li>adds the {@code Authorization} header carrying the SigV4 signature</li>
 * </ul>
 *
 * <p>Thread-safe (no mutable state).
 */
@Component
@Slf4j
public class AwsSigV4Signer {

    private static final String ALGORITHM = "AWS4-HMAC-SHA256";
    private static final String HMAC_SHA256 = "HmacSHA256";
    private static final DateTimeFormatter AMZ_DATE_FORMAT =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter SHORT_DATE_FORMAT =
        DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    /**
     * Sign the request in-place (headers are mutated).
     *
     * @param method         HTTP method (POST/GET/PUT/DELETE…)
     * @param url            fully qualified request URL (scheme + host + path + optional query)
     * @param headers        mutable Spring {@link HttpHeaders}; {@code x-amz-date}, {@code x-amz-content-sha256}
     *                       and {@code Authorization} are added/replaced
     * @param bodyBytes      exact request body bytes (empty array for GET/HEAD/DELETE)
     * @param accessKeyId    AWS access key id
     * @param secretAccessKey AWS secret access key
     * @param region         AWS region (e.g. us-east-1); if null/blank, derived from URL host
     * @param serviceOverride optional explicit service name; null to derive from host
     */
    public void sign(String method,
                     String url,
                     HttpHeaders headers,
                     byte[] bodyBytes,
                     String accessKeyId,
                     String secretAccessKey,
                     String region,
                     String serviceOverride) {
        if (accessKeyId == null || accessKeyId.isBlank()
                || secretAccessKey == null || secretAccessKey.isBlank()) {
            log.warn("AwsSigV4Signer: missing credentials - skipping signature");
            return;
        }

        URI uri = URI.create(url);
        String host = uri.getHost();
        String service = serviceOverride != null && !serviceOverride.isBlank()
            ? serviceOverride
            : deriveServiceFromHost(host);
        String resolvedRegion = region != null && !region.isBlank()
            ? region
            : deriveRegionFromHost(host);
        if (service == null) {
            log.warn("AwsSigV4Signer: could not derive service from host '{}' - skipping signature", host);
            return;
        }

        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        String amzDate = AMZ_DATE_FORMAT.format(now);
        String shortDate = SHORT_DATE_FORMAT.format(now);
        String payloadHash = sha256Hex(bodyBytes == null ? new byte[0] : bodyBytes);

        // Required signed headers
        headers.set("x-amz-date", amzDate);
        headers.set("x-amz-content-sha256", payloadHash);
        if (!headers.containsKey(HttpHeaders.HOST)) {
            headers.set(HttpHeaders.HOST, host);
        }

        // Canonical request
        String canonicalUri = canonicalizeUri(uri.getRawPath());
        String canonicalQuery = canonicalizeQuery(uri.getRawQuery());

        // Signed headers: lower-case, sorted, colon-joined; include host + x-amz-date + x-amz-content-sha256
        TreeMap<String, String> sortedHeaders = new TreeMap<>();
        headers.forEach((name, values) -> {
            if (values != null && !values.isEmpty()) {
                String lower = name.toLowerCase(Locale.ROOT);
                // Only include select headers in the signature (host + x-amz-*)
                if (lower.equals("host") || lower.startsWith("x-amz-")) {
                    sortedHeaders.put(lower, values.get(0).trim());
                }
            }
        });
        String canonicalHeaders = sortedHeaders.entrySet().stream()
            .map(e -> e.getKey() + ":" + e.getValue() + "\n")
            .reduce("", String::concat);
        String signedHeaders = String.join(";", sortedHeaders.keySet());

        String canonicalRequest = method + "\n"
            + canonicalUri + "\n"
            + canonicalQuery + "\n"
            + canonicalHeaders + "\n"
            + signedHeaders + "\n"
            + payloadHash;

        // String to sign
        String credentialScope = shortDate + "/" + resolvedRegion + "/" + service + "/aws4_request";
        String stringToSign = ALGORITHM + "\n"
            + amzDate + "\n"
            + credentialScope + "\n"
            + sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));

        // Derive signing key & compute signature
        byte[] signingKey = deriveSigningKey(secretAccessKey, shortDate, resolvedRegion, service);
        String signature = bytesToHex(hmacSha256(signingKey, stringToSign));

        String authorization = ALGORITHM + " "
            + "Credential=" + accessKeyId + "/" + credentialScope + ", "
            + "SignedHeaders=" + signedHeaders + ", "
            + "Signature=" + signature;
        headers.set(HttpHeaders.AUTHORIZATION, authorization);

        log.debug("AwsSigV4Signer: signed {} {} for service={} region={}", method, host, service, resolvedRegion);
    }

    /** Extract service name from AWS host, e.g. {@code sns.us-east-1.amazonaws.com} → {@code sns}. */
    static String deriveServiceFromHost(String host) {
        if (host == null) return null;
        if (!host.endsWith(".amazonaws.com") && !host.endsWith(".amazonaws.com.cn")) {
            return null;
        }
        int firstDot = host.indexOf('.');
        if (firstDot <= 0) return null;
        return host.substring(0, firstDot);
    }

    /** Extract region from AWS host (best-effort), or return "us-east-1" default. */
    static String deriveRegionFromHost(String host) {
        if (host == null) return "us-east-1";
        // sns.us-east-1.amazonaws.com → us-east-1
        String[] parts = host.split("\\.");
        if (parts.length >= 4) {
            return parts[1];
        }
        return "us-east-1";
    }

    /** RFC 3986 canonical URI - path segment URL-encoded, "/" preserved, empty → "/". */
    static String canonicalizeUri(String rawPath) {
        if (rawPath == null || rawPath.isEmpty()) return "/";
        // Split on "/", re-encode each segment
        StringBuilder sb = new StringBuilder();
        String[] segments = rawPath.split("/", -1);
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) sb.append('/');
            sb.append(rfc3986Encode(segments[i]));
        }
        return sb.length() == 0 ? "/" : sb.toString();
    }

    /** Canonical query string: sort by key, URL-encode values, join with "&". */
    static String canonicalizeQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) return "";
        String[] pairs = rawQuery.split("&");
        List<String[]> kvs = Arrays.stream(pairs)
            .map(p -> {
                int eq = p.indexOf('=');
                String key = eq < 0 ? p : p.substring(0, eq);
                String val = eq < 0 ? "" : p.substring(eq + 1);
                return new String[]{rfc3986Encode(urlDecode(key)), rfc3986Encode(urlDecode(val))};
            })
            .sorted(Comparator.<String[], String>comparing(a -> a[0]).thenComparing(a -> a[1]))
            .toList();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < kvs.size(); i++) {
            if (i > 0) sb.append('&');
            sb.append(kvs.get(i)[0]).append('=').append(kvs.get(i)[1]);
        }
        return sb.toString();
    }

    /** Strict RFC 3986 percent-encoding (unreserved chars: A-Z a-z 0-9 - _ . ~). */
    static String rfc3986Encode(String s) {
        if (s == null) return "";
        // URLEncoder encodes to application/x-www-form-urlencoded; RFC 3986 differs on + / space / *
        String encoded = URLEncoder.encode(s, StandardCharsets.UTF_8);
        // Transform to RFC 3986: '+' → %20, '*' → %2A, %7E → '~'
        return encoded
            .replace("+", "%20")
            .replace("*", "%2A")
            .replace("%7E", "~");
    }

    static String urlDecode(String s) {
        if (s == null) return "";
        try {
            return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return s;
        }
    }

    static byte[] deriveSigningKey(String secret, String shortDate, String region, String service) {
        byte[] kSecret = ("AWS4" + secret).getBytes(StandardCharsets.UTF_8);
        byte[] kDate = hmacSha256(kSecret, shortDate);
        byte[] kRegion = hmacSha256(kDate, region);
        byte[] kService = hmacSha256(kRegion, service);
        return hmacSha256(kService, "aws4_request");
    }

    static byte[] hmacSha256(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(key, HMAC_SHA256));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return bytesToHex(md.digest(data));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * Convenience wrapper: sign a request using credentials from a map
     * (keys: {@code access_key_id}, {@code secret_access_key}, {@code region}).
     */
    public void sign(String method, String url, HttpHeaders headers, byte[] body, Map<String, String> credentialFields) {
        if (credentialFields == null) {
            log.warn("AwsSigV4Signer: credentialFields is null, skipping signature");
            return;
        }
        sign(
            method, url, headers, body,
            credentialFields.get("access_key_id"),
            credentialFields.get("secret_access_key"),
            credentialFields.get("region"),
            credentialFields.get("service") // optional override
        );
    }
}
