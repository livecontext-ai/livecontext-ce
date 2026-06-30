package com.apimarketplace.common.web;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;

/**
 * Security filter that verifies requests originate from the API Gateway.
 *
 * <p>Every non-public request must carry {@code X-Gateway-Secret} and
 * {@code X-Gateway-Timestamp} headers that the gateway generates using a
 * shared HMAC-like scheme. Without this filter an attacker with network
 * access could spoof the {@code X-User-ID} header and impersonate any tenant.</p>
 *
 * <p>Public paths are configured per-service via {@link GatewayFilterProperties}.</p>
 *
 * <p>The filter can be disabled via {@code gateway.filter.verification-enabled=false}
 * for local development or test profiles.</p>
 */
@Order(1)
public class GatewayAuthenticationFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(GatewayAuthenticationFilter.class);

    private static final String HEADER_GATEWAY_SECRET = "X-Gateway-Secret";
    private static final String HEADER_GATEWAY_TIMESTAMP = "X-Gateway-Timestamp";
    private static final String HEADER_PROVIDER_ID = "X-Provider-ID";

    /** Maximum allowed clock skew between gateway and this service (5 minutes). */
    private static final long MAX_TIMESTAMP_AGE_MS = 300_000;

    private final GatewayFilterProperties properties;

    public GatewayAuthenticationFilter(GatewayFilterProperties properties) {
        this.properties = properties;
        if (properties.isVerificationEnabled() && isUnsafeSecret(properties.getSecretKey())) {
            throw new IllegalStateException("gateway.filter.secret-key must be configured when gateway verification is enabled");
        }
        // Loud warning on startup when HMAC verification is disabled. Accidentally flipping
        // this to false in prod silently reopens the "any client can forge X-User-Roles:
        // ADMIN against port 8083" bypass - there must be no way to miss it in logs.
        if (!properties.isVerificationEnabled()) {
            log.warn("╔════════════════════════════════════════════════════════════════╗");
            log.warn("║  SECURITY WARNING: gateway HMAC verification is DISABLED.     ║");
            log.warn("║  gateway.filter.verification-enabled=false                    ║");
            log.warn("║  Any client with network access can forge X-User-Roles and   ║");
            log.warn("║  X-User-ID headers. This must ONLY be used in dev/test.      ║");
            log.warn("╚════════════════════════════════════════════════════════════════╝");
        }
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        // When verification is disabled (dev/test), pass through immediately
        if (!properties.isVerificationEnabled()) {
            chain.doFilter(request, response);
            return;
        }

        String requestPath = httpRequest.getRequestURI();

        // Public endpoints do not require gateway authentication unless explicitly
        // listed as HMAC-required dangerous internal endpoints.
        if (isPublicEndpoint(requestPath) && !isHmacRequiredEndpoint(requestPath)) {
            chain.doFilter(request, response);
            return;
        }

        // Extract gateway headers
        String gatewaySecretHeader = httpRequest.getHeader(HEADER_GATEWAY_SECRET);
        String gatewayTimestamp = httpRequest.getHeader(HEADER_GATEWAY_TIMESTAMP);

        // Resolve providerId: query parameter first, then header fallback
        String providerId = httpRequest.getParameter("providerId");
        if (providerId == null) {
            providerId = httpRequest.getHeader(HEADER_PROVIDER_ID);
        }

        // Reject if any required header/parameter is missing
        if (gatewaySecretHeader == null || gatewayTimestamp == null || providerId == null) {
            log.warn("Gateway headers missing for {} - secret={}, timestamp={}, providerId={}",
                    requestPath,
                    gatewaySecretHeader != null ? "present" : "absent",
                    gatewayTimestamp != null ? "present" : "absent",
                    providerId != null ? "present" : "absent");
            rejectRequest(httpResponse, HttpServletResponse.SC_UNAUTHORIZED, "Missing gateway authentication headers");
            return;
        }

        // Audit 2026-05-17 round-3 F16 - pass user + org headers into the
        // verification step so the signature binds to them.
        String userIdHdr = httpRequest.getHeader("X-User-ID");
        String orgIdHdr = httpRequest.getHeader("X-Organization-ID");
        if (!isValidGatewaySecret(gatewaySecretHeader, providerId, gatewayTimestamp, userIdHdr, orgIdHdr)) {
            log.warn("Invalid gateway secret for path={} providerId={}", requestPath, providerId);
            rejectRequest(httpResponse, HttpServletResponse.SC_UNAUTHORIZED, "Invalid gateway secret");
            return;
        }

        log.debug("Gateway authentication passed for path={}", requestPath);
        chain.doFilter(request, response);
    }

    /**
     * Determines whether a request path is a public endpoint that does not
     * require gateway authentication. Matches against the configured
     * {@code gateway.filter.public-paths} prefixes.
     */
    boolean isPublicEndpoint(String path) {
        return matchesAnyPrefix(path, properties.getPublicPaths());
    }

    boolean isHmacRequiredEndpoint(String path) {
        return matchesAnyPrefix(path, properties.getHmacRequiredPaths());
    }

    private boolean matchesAnyPrefix(String path, List<String> prefixes) {
        if (prefixes == null || prefixes.isEmpty()) {
            return false;
        }
        for (String prefix : prefixes) {
            if (prefix != null && path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private boolean isUnsafeSecret(String secret) {
        return secret == null
                || secret.isBlank()
                || GatewayFilterProperties.DEFAULT_SECRET_KEY.equals(secret);
    }

    private static final String HMAC_ALGO = "HmacSHA256";
    private static final String SIGNATURE_PREFIX = "gw_";

    /**
     * Verify the HMAC-SHA256 signature emitted by
     * {@code gateway.GatewaySecurityService}. Null user/org coerce to empty
     * string; both sides use the identical data shape:
     * {@code HMAC(secret, providerId|userId|orgId|timestamp)}.
     *
     * <p>Comparison is constant-time via {@link MessageDigest#isEqual} to
     * defeat timing-side-channel attacks on the signature byte slice.
     */
    boolean isValidGatewaySecret(String receivedSecret, String providerId, String timestamp,
                                  String userId, String organizationId) {
        try {
            if (receivedSecret == null || !receivedSecret.startsWith(SIGNATURE_PREFIX)) {
                return false;
            }
            long requestTime = Long.parseLong(timestamp);
            if (System.currentTimeMillis() - requestTime > MAX_TIMESTAMP_AGE_MS) {
                log.debug("Gateway timestamp expired: age={}ms",
                        System.currentTimeMillis() - requestTime);
                return false;
            }
            String expected = generateExpectedSecret(providerId, timestamp, userId, organizationId);
            return MessageDigest.isEqual(
                    receivedSecret.getBytes(StandardCharsets.UTF_8),
                    expected.getBytes(StandardCharsets.UTF_8));
        } catch (NumberFormatException e) {
            log.warn("Invalid gateway timestamp format: {}", timestamp);
            return false;
        } catch (Exception e) {
            log.error("Error validating gateway secret: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Compute the expected HMAC-SHA256 signature for the given (providerId,
     * userId, organizationId, timestamp) tuple. MUST stay byte-identical to
     * {@code gateway.GatewaySecurityService.computeSignature}.
     */
    String generateExpectedSecret(String providerId, String timestamp, String userId, String organizationId) {
        String safeUser = userId != null ? userId : "";
        String safeOrg = organizationId != null ? organizationId : "";
        String data = providerId + "|" + safeUser + "|" + safeOrg + "|" + timestamp;
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(
                    properties.getSecretKey().getBytes(StandardCharsets.UTF_8), HMAC_ALGO));
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return SIGNATURE_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    private void rejectRequest(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write(
                String.format("{\"error\":\"Unauthorized\",\"message\":\"%s\"}", message));
    }
}
