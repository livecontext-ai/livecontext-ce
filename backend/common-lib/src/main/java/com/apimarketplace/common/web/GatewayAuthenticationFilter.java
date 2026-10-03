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

import java.io.IOException;
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

    /**
     * Default window of a v2 signature ({@code gateway.signature.max-skew-seconds}), in either
     * direction (CASA LC-035). Every signer stamps the current time at send time, so this only
     * has to absorb clock skew between hosts and request latency.
     */
    static final long DEFAULT_V2_MAX_SKEW_MS = 60_000;

    private final GatewayFilterProperties properties;

    /**
     * Whether a request carrying only the v1 signature is still accepted
     * ({@code gateway.signature.accept-v1}). True for the rollout release so a service deployed
     * before its callers keeps answering them; set false once every signer emits v2.
     */
    private final boolean acceptV1;

    /** v2 freshness window in ms ({@code gateway.signature.max-skew-seconds}). */
    private final long v2MaxSkewMs;

    /** Counts and logs (once per route and caller) the requests accepted on v1 alone. */
    private final GatewaySignatureV1OnlyMonitor v1OnlyMonitor;

    public GatewayAuthenticationFilter(GatewayFilterProperties properties) {
        this(properties, true);
    }

    public GatewayAuthenticationFilter(GatewayFilterProperties properties, boolean acceptV1) {
        this(properties, acceptV1, DEFAULT_V2_MAX_SKEW_MS / 1000);
    }

    public GatewayAuthenticationFilter(GatewayFilterProperties properties, boolean acceptV1,
                                       long v2MaxSkewSeconds) {
        this(properties, acceptV1, v2MaxSkewSeconds, new GatewaySignatureV1OnlyMonitor());
    }

    /**
     * @param v1OnlyMonitor told about every request accepted on v1 alone (no v2 header), so the
     *                      remaining v1-only callers are visible before accept-v1 is turned off
     */
    public GatewayAuthenticationFilter(GatewayFilterProperties properties, boolean acceptV1,
                                       long v2MaxSkewSeconds, GatewaySignatureV1OnlyMonitor v1OnlyMonitor) {
        this.properties = properties;
        this.acceptV1 = acceptV1;
        this.v1OnlyMonitor = v1OnlyMonitor != null ? v1OnlyMonitor : new GatewaySignatureV1OnlyMonitor();
        this.v2MaxSkewMs = v2MaxSkewSeconds > 0 ? v2MaxSkewSeconds * 1000 : DEFAULT_V2_MAX_SKEW_MS;
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

        String signatureV2 = httpRequest.getHeader(GatewaySignatureV2.HEADER);

        // v2 (CASA LC-035): binds roles, method, path and query, providerId from the header only.
        if (signatureV2 != null) {
            if (isValidSignatureV2(httpRequest, signatureV2, gatewayTimestamp)) {
                log.debug("Gateway v2 authentication passed for path={}", requestPath);
                chain.doFilter(request, response);
                return;
            }
            if (!acceptV1) {
                log.warn("Invalid gateway v2 signature for {} {}", httpRequest.getMethod(), requestPath);
                rejectRequest(httpResponse, HttpServletResponse.SC_UNAUTHORIZED, "Invalid gateway secret");
                return;
            }
            // Transition window only: a v1-only request is accepted anyway, so falling back to v1
            // here grants nothing a stripped v2 header would not. The WARN is what tells us a
            // signer computes v2 differently from this verifier BEFORE accept-v1 is turned off.
            log.warn("Gateway v2 signature mismatch for {} {} (provider={}); checking v1 during the transition",
                    httpRequest.getMethod(), requestPath, httpRequest.getHeader(HEADER_PROVIDER_ID));
        } else if (!acceptV1) {
            log.warn("Gateway v2 signature missing for {} {} and v1 is no longer accepted",
                    httpRequest.getMethod(), requestPath);
            rejectRequest(httpResponse, HttpServletResponse.SC_UNAUTHORIZED, "Missing gateway authentication headers");
            return;
        }

        // v1 (legacy, accepted while gateway.signature.accept-v1=true). The providerId query
        // parameter is read here and ONLY here: the gateway's user-resolution call used to carry
        // its providerId solely in the query, and a service must keep answering a gateway that
        // has not been redeployed. v2 reads X-Provider-ID and nothing else.
        String providerId = httpRequest.getParameter("providerId");
        if (providerId == null) {
            providerId = httpRequest.getHeader(HEADER_PROVIDER_ID);
        }

        // Reject if any required header/parameter is missing
        if (gatewaySecretHeader == null || gatewayTimestamp == null || providerId == null) {
            log.warn("Gateway headers missing for {} - secret={}, timestamp={}, providerId={}",
                    LogSafePath.of(requestPath),
                    gatewaySecretHeader != null ? "present" : "absent",
                    gatewayTimestamp != null ? "present" : "absent",
                    providerId != null ? "present" : "absent");
            rejectRequest(httpResponse, HttpServletResponse.SC_UNAUTHORIZED, "Missing gateway authentication headers");
            return;
        }

        // Audit 2026-05-17 round-3 F16 - the user + org headers are part of the signed data.
        // GatewaySignatureVerifier.verify reads them (the same code the gateway tests its
        // signer against), so the binding cannot drift between the two sides.
        if (!verifyFromHeaders(httpRequest, providerId)) {
            log.warn("Invalid gateway secret for path={} providerId={}", LogSafePath.of(requestPath), providerId);
            rejectRequest(httpResponse, HttpServletResponse.SC_UNAUTHORIZED, "Invalid gateway secret");
            return;
        }

        log.debug("Gateway authentication passed for path={}", LogSafePath.of(requestPath));
        if (signatureV2 == null) {
            // Accepted on v1 ALONE: the one case nothing else reports, and the one that turns into
            // a 401 when accept-v1 goes false. A v2 mismatch that fell back to v1 is already WARNed.
            v1OnlyMonitor.record(httpRequest.getMethod(), requestPath, providerId);
        }
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
            // A blank entry (an unset env-driven list) must match NOTHING: "".startsWith is true
            // for every path, which would silently gate or open the whole service.
            if (prefix != null && !prefix.isBlank() && path.startsWith(prefix)) {
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


    /**
     * Verify the HMAC-SHA256 signature emitted by
     * {@code gateway.GatewaySecurityService}. Null user/org coerce to empty
     * string; both sides use the identical data shape:
     * {@code HMAC(secret, providerId|userId|orgId|timestamp)}.
     *
     * <p>Delegates to {@link GatewaySignatureVerifier}: comparison is constant-time, to
     * defeat timing-side-channel attacks on the signature byte slice.
     */
    boolean isValidGatewaySecret(String receivedSecret, String providerId, String timestamp,
                                  String userId, String organizationId) {
        try {
            return new GatewaySignatureVerifier(properties.getSecretKey())
                    .isValid(receivedSecret, providerId, timestamp, userId, organizationId);
        } catch (NumberFormatException e) {
            log.warn("Invalid gateway timestamp format: {}", timestamp);
            return false;
        } catch (Exception e) {
            log.error("Error validating gateway secret: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Verify the v2 signature over the request as this servlet received it. The provider id is
     * read from {@code X-Provider-ID} only (never the query string), and must be present.
     */
    boolean isValidSignatureV2(HttpServletRequest request, String received, String timestamp) {
        try {
            if (timestamp == null) {
                return false;
            }
            boolean ok = GatewaySignatureV2.verify(properties.getSecretKey(), request.getMethod(),
                    request.getRequestURI(), request.getQueryString(),
                    name -> {
                        java.util.Enumeration<String> values = request.getHeaders(name);
                        return values == null ? List.of() : java.util.Collections.list(values);
                    },
                    received, v2MaxSkewMs, System.currentTimeMillis());
            if (!ok) {
                log.debug("Gateway v2 signature invalid or outside the {}ms window", v2MaxSkewMs);
            }
            return ok;
        } catch (NumberFormatException e) {
            log.warn("Invalid gateway timestamp format: {}", timestamp);
            return false;
        } catch (Exception e) {
            log.error("Error validating gateway v2 signature: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Compute the expected HMAC-SHA256 signature for the given (providerId,
     * userId, organizationId, timestamp) tuple. MUST stay byte-identical to
     * {@code gateway.GatewaySecurityService.computeSignature}.
     */
    String generateExpectedSecret(String providerId, String timestamp, String userId, String organizationId) {
        return new GatewaySignatureVerifier(properties.getSecretKey())
                .expectedSecret(providerId, timestamp, userId, organizationId);
    }

    /** Verification with the signed user/org read from the request headers. */
    private boolean verifyFromHeaders(HttpServletRequest httpRequest, String providerId) {
        try {
            return new GatewaySignatureVerifier(properties.getSecretKey())
                    .verify(httpRequest::getHeader, providerId);
        } catch (NumberFormatException e) {
            log.warn("Invalid gateway timestamp format: {}", httpRequest.getHeader(HEADER_GATEWAY_TIMESTAMP));
            return false;
        } catch (Exception e) {
            log.error("Error validating gateway secret: {}", e.getMessage());
            return false;
        }
    }

    private void rejectRequest(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write(
                String.format("{\"error\":\"Unauthorized\",\"message\":\"%s\"}", message));
    }
}
