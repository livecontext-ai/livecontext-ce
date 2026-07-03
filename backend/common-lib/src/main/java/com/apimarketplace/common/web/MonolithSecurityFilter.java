package com.apimarketplace.common.web;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.PublicKey;
import java.security.Signature;
import java.util.*;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Security filter for CE monolith mode. Replaces the gateway + GatewayAuthenticationFilter.
 *
 * <p>Extracts JWT from Authorization header, validates it using a configurable key,
 * and injects X-User-ID (and other auth headers) for TenantResolver compatibility.</p>
 *
 * <p>In monolith mode, there is no gateway. This filter runs before all controllers
 * and ensures authenticated requests carry the same headers that the gateway would inject.</p>
 *
 * <p>Activated by: deployment.mode=monolith</p>
 */
@Order(0) // Before GatewayAuthenticationFilter (Order 1)
public class MonolithSecurityFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(MonolithSecurityFilter.class);

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String SHARE_TOKEN_PREFIX = "ShareToken ";
    /** Client-supplied API-key header, mirroring the cloud gateway's AuthenticationFilter. */
    private static final String API_KEY_HEADER = "X-API-Key";
    /** Plaintext key prefix minted by auth-service's ApiKeyService ({@code lc_live_<64 hex>}). */
    private static final String API_KEY_PREFIX = "lc_live_";
    private static final String ACTIVE_ORG_ID_HEADER = "X-Active-Organization-ID";
    private static final String ORGANIZATION_ID_HEADER = "X-Organization-ID";
    private static final String ORGANIZATION_ROLE_HEADER = "X-Organization-Role";
    public static final String MONOLITH_ACTIVE_ORG_CLAIM_HEADER = "X-Monolith-Active-Organization-ID";
    private static final String SHARE_CONTEXT_HEADER = "X-Share-Context";
    private static final String SHARE_RESOURCE_TYPE_HEADER = "X-Share-Resource-Type";
    private static final String SHARE_RESOURCE_TOKEN_HEADER = "X-Share-Resource-Token";
    private static final String SHARE_RESOURCE_ID_HEADER = "X-Share-Resource-Id";
    private static final Set<String> TRUSTED_IDENTITY_HEADERS = Set.of(
            "X-Authenticated",
            "X-User-ID",
            ACTIVE_ORG_ID_HEADER,
            ORGANIZATION_ID_HEADER,
            ORGANIZATION_ROLE_HEADER,
            MONOLITH_ACTIVE_ORG_CLAIM_HEADER,
            "X-User-Email",
            "X-User-Plan",
            "X-User-Roles",
            "X-Provider-ID",
            "X-User-Version",
            "X-Remaining-Credits",
            "X-Gateway-Secret",
            "X-Gateway-Timestamp",
            SHARE_CONTEXT_HEADER,
            SHARE_RESOURCE_TYPE_HEADER,
            SHARE_RESOURCE_TOKEN_HEADER,
            SHARE_RESOURCE_ID_HEADER
    );

    private final Supplier<Key> verificationKeySupplier;
    private final List<String> publicPaths;
    private final Function<String, ShareTokenContext> shareTokenResolver;
    private final Function<String, JwtClaims> apiKeyResolver;
    private final LongSupplier nowMillis;

    /**
     * @param verificationKeySupplier provides the RSA public key for JWT verification
     * @param publicPaths            path prefixes that bypass authentication
     */
    public MonolithSecurityFilter(Supplier<Key> verificationKeySupplier, List<String> publicPaths) {
        this(verificationKeySupplier, publicPaths, null);
    }

    public MonolithSecurityFilter(Supplier<Key> verificationKeySupplier,
                                  List<String> publicPaths,
                                  Function<String, ShareTokenContext> shareTokenResolver) {
        this(verificationKeySupplier, publicPaths, shareTokenResolver, null, System::currentTimeMillis);
    }

    /**
     * @param apiKeyResolver resolves a plaintext {@code lc_live_} API key (from the X-API-Key
     *                       header or an {@code Authorization: Bearer lc_live_...} value) to the
     *                       owning user's claims, or {@code null} when the key is invalid.
     *                       Mirrors the cloud gateway's API-key authentication path.
     */
    public MonolithSecurityFilter(Supplier<Key> verificationKeySupplier,
                                  List<String> publicPaths,
                                  Function<String, ShareTokenContext> shareTokenResolver,
                                  Function<String, JwtClaims> apiKeyResolver) {
        this(verificationKeySupplier, publicPaths, shareTokenResolver, apiKeyResolver,
                System::currentTimeMillis);
    }

    /**
     * Test seam: injects the wall-clock source so the {@code exp == now} expiry boundary can be
     * exercised deterministically (a real clock rolling a second between token-sign and filter-read
     * makes that case a rare flake). Production always uses {@link System#currentTimeMillis()} via
     * the public constructors above.
     */
    MonolithSecurityFilter(Supplier<Key> verificationKeySupplier,
                           List<String> publicPaths,
                           Function<String, ShareTokenContext> shareTokenResolver,
                           LongSupplier nowMillis) {
        this(verificationKeySupplier, publicPaths, shareTokenResolver, null, nowMillis);
    }

    MonolithSecurityFilter(Supplier<Key> verificationKeySupplier,
                           List<String> publicPaths,
                           Function<String, ShareTokenContext> shareTokenResolver,
                           Function<String, JwtClaims> apiKeyResolver,
                           LongSupplier nowMillis) {
        this.verificationKeySupplier = verificationKeySupplier;
        this.publicPaths = publicPaths != null ? publicPaths : List.of();
        this.shareTokenResolver = shareTokenResolver;
        this.apiKeyResolver = apiKeyResolver;
        this.nowMillis = nowMillis;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;
        String path = httpRequest.getRequestURI();
        String claimedActiveOrgId = httpRequest.getHeader(ACTIVE_ORG_ID_HEADER);
        boolean loopbackRequest = isLoopbackRequest(httpRequest);
        boolean protectedMonolithPath = isProtectedMonolithPath(path);

        // SECURITY: /api/internal/** is the service-to-service surface (credential resolvers that
        // return DECRYPTED secrets, workspace purge, signal resolution, run/agent access checks,
        // SBS node execution, ...). Many of these trust a caller-supplied identity (a userId query
        // param or path var, or no identity at all) because in the cloud topology ONLY the gateway
        // reaches them - server-side, after it has validated the JWT - and the gateway NEVER routes
        // /api/internal/** from external traffic (it exposes only the public rewrite targets).
        // The CE monolith must mirror that: a non-loopback request to an internal path is reported
        // as 404 (matching "no route", avoiding endpoint disclosure). In-process loopback calls
        // (services.*-url=http://localhost:8080) are untouched, as is the small allowlist of
        // internal paths that are legitimately reachable externally (see isExternallyBlockedInternalPath).
        //
        // NOTE: this relies on the loopback-trust model - a deployment that fronts the monolith
        // with a SAME-HOST reverse proxy must set server.forward-headers-strategy so external
        // traffic is not seen as 127.0.0.1. The default CE compose publishes the port directly,
        // so loopback means genuinely in-process.
        if (!loopbackRequest && isExternallyBlockedInternalPath(path)) {
            httpResponse.setStatus(HttpServletResponse.SC_NOT_FOUND);
            httpResponse.setContentType("application/json");
            httpResponse.getWriter().write("{\"error\":\"Not Found\"}");
            return;
        }

        HttpServletRequest trustedRequest = loopbackRequest && !protectedMonolithPath
                ? httpRequest
                : new StrippedIdentityHeadersRequestWrapper(httpRequest);
        String authHeader = trustedRequest.getHeader(AUTHORIZATION_HEADER);
        boolean hasBearerToken = authHeader != null && authHeader.startsWith(BEARER_PREFIX);
        boolean publicPath = isPublicPath(path);

        // Public endpoints remain accessible without authentication, but if a CE
        // bearer token is present we still validate it and inject gateway headers.
        if (publicPath && !hasBearerToken) {
            doFilterWithBoundRequest(trustedRequest, response, chain);
            return;
        }

        // Internal loopback calls already carry trusted headers from a previous
        // monolith filter pass. External requests never get this bypass.
        if (loopbackRequest && !protectedMonolithPath && httpRequest.getHeader("X-User-ID") != null) {
            doFilterWithBoundRequest(httpRequest, response, chain);
            return;
        }

        String shareToken = extractShareToken(authHeader);
        if (shareToken != null) {
            ShareTokenContext shareContext = shareTokenResolver != null ? shareTokenResolver.apply(shareToken) : null;
            if (shareContext == null || shareContext.userId() == null || shareContext.userId().isBlank()) {
                httpResponse.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                httpResponse.setContentType("application/json");
                httpResponse.getWriter().write("{\"error\":\"Unauthorized\",\"message\":\"Invalid or expired share token.\"}");
                return;
            }

            String method = httpRequest.getMethod();
            if (!isShareTokenReadMethod(method)
                    && !isAllowedShareTokenApplicationBootstrap(method, path, shareContext)
                    && !isAllowedShareTokenReadOnlyPost(method, path)) {
                httpResponse.setStatus(HttpServletResponse.SC_FORBIDDEN);
                httpResponse.setContentType("application/json");
                httpResponse.getWriter().write("{\"error\":\"Forbidden\",\"message\":\"Write operations are not allowed in a shared context.\"}");
                return;
            }

            doFilterWithBoundRequest(new ShareAuthHeadersRequestWrapper(trustedRequest, shareToken, shareContext),
                    response, chain);
            return;
        }

        // API-key authentication (X-API-Key header or "Bearer lc_live_..."), mirroring the
        // cloud gateway. Checked before the JWT branch: an lc_live_ key is not a JWT and
        // would otherwise 401 as "invalid token" on protected paths.
        String apiKey = extractApiKey(trustedRequest, authHeader);
        if (apiKey != null) {
            JwtClaims apiKeyClaims = apiKeyResolver != null ? apiKeyResolver.apply(apiKey) : null;
            if (apiKeyClaims == null) {
                if (publicPath) {
                    // Public routes never 401: an unresolvable key falls back to anonymous,
                    // matching the JWT handling below.
                    doFilterWithBoundRequest(trustedRequest, response, chain);
                    return;
                }
                httpResponse.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                httpResponse.setContentType("application/json");
                httpResponse.getWriter().write("{\"error\":\"Unauthorized\",\"message\":\"Invalid or unknown API key.\"}");
                return;
            }
            doFilterWithBoundRequest(new AuthHeadersRequestWrapper(trustedRequest, apiKeyClaims, claimedActiveOrgId),
                    response, chain);
            return;
        }

        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            // No auth header - pass through (some endpoints may be optional-auth)
            // The controller/service layer decides if auth is required
            doFilterWithBoundRequest(trustedRequest, response, chain);
            return;
        }

        String token = authHeader.substring(BEARER_PREFIX.length()).trim();
        if (token.isEmpty()) {
            doFilterWithBoundRequest(trustedRequest, response, chain);
            return;
        }

        // Validate JWT and extract claims
        JwtClaims claims = validateAndExtractClaims(token);
        if (claims == null) {
            if (publicPath) {
                doFilterWithBoundRequest(trustedRequest, response, chain);
                return;
            }
            httpResponse.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            httpResponse.setContentType("application/json");
            httpResponse.getWriter().write("{\"error\":\"Unauthorized\",\"message\":\"Invalid or expired token\"}");
            return;
        }

        // Wrap request with injected auth headers
        HttpServletRequest wrappedRequest = new AuthHeadersRequestWrapper(trustedRequest, claims, claimedActiveOrgId);
        if (path.contains("steps/alias")) {
            log.info("[MonolithSecurity] {} → X-User-ID={}, email={}", path, claims.userId(), claims.email());
        }
        doFilterWithBoundRequest(wrappedRequest, response, chain);
    }

    private void doFilterWithBoundRequest(HttpServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        RequestAttributes previousAttributes = RequestContextHolder.getRequestAttributes();
        ServletRequestAttributes currentAttributes = new ServletRequestAttributes(request);
        String organizationId = request.getHeader(ORGANIZATION_ID_HEADER);
        String organizationRole = request.getHeader(ORGANIZATION_ROLE_HEADER);
        RequestContextHolder.setRequestAttributes(currentAttributes);
        try {
            doFilterWithBoundOrgScope(organizationId, organizationRole, request, response, chain);
        } finally {
            currentAttributes.requestCompleted();
            if (previousAttributes != null) {
                RequestContextHolder.setRequestAttributes(previousAttributes);
            } else {
                RequestContextHolder.resetRequestAttributes();
            }
        }
    }

    private void doFilterWithBoundOrgScope(
            String organizationId,
            String organizationRole,
            ServletRequest request,
            ServletResponse response,
            FilterChain chain) throws IOException, ServletException {
        if (!hasText(organizationId) && !hasText(organizationRole)) {
            chain.doFilter(request, response);
            return;
        }

        FilterChainExceptionHolder exceptionHolder = new FilterChainExceptionHolder();
        TenantResolver.runWithOrgScope(organizationId, organizationRole, () -> {
            try {
                chain.doFilter(request, response);
            } catch (IOException e) {
                exceptionHolder.ioException = e;
            } catch (ServletException e) {
                exceptionHolder.servletException = e;
            }
        });
        exceptionHolder.rethrowIfPresent();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static final class FilterChainExceptionHolder {
        private IOException ioException;
        private ServletException servletException;

        private void rethrowIfPresent() throws IOException, ServletException {
            if (ioException != null) {
                throw ioException;
            }
            if (servletException != null) {
                throw servletException;
            }
        }
    }

    /**
     * Plaintext API key, mirroring the cloud gateway's extraction order exactly:
     * a non-API-key Bearer value (a JWT) wins over any X-API-Key header sent
     * alongside it (JWT-first); otherwise the X-API-Key header wins over an
     * {@code Authorization: Bearer lc_live_...} value (MCP clients often only
     * support the Authorization header). {@code null} when no key is present.
     */
    private String extractApiKey(HttpServletRequest request, String authHeader) {
        String bearerApiKey = null;
        if (authHeader != null && authHeader.startsWith(BEARER_PREFIX)) {
            String token = authHeader.substring(BEARER_PREFIX.length()).trim();
            if (!token.startsWith(API_KEY_PREFIX)) {
                return null; // JWT-first
            }
            bearerApiKey = token;
        }
        String headerKey = request.getHeader(API_KEY_HEADER);
        if (headerKey != null && !headerKey.isBlank()) {
            return headerKey.trim();
        }
        return bearerApiKey;
    }

    private String extractShareToken(String authHeader) {
        if (authHeader == null) {
            return null;
        }
        String raw = null;
        if (authHeader.startsWith(SHARE_TOKEN_PREFIX)) {
            raw = authHeader.substring(SHARE_TOKEN_PREFIX.length());
        } else if (authHeader.startsWith(BEARER_PREFIX + SHARE_TOKEN_PREFIX)) {
            raw = authHeader.substring((BEARER_PREFIX + SHARE_TOKEN_PREFIX).length());
        }
        if (raw == null) {
            return null;
        }
        String token = raw.trim();
        return token.isBlank() ? null : token;
    }

    private JwtClaims validateAndExtractClaims(String token) {
        try {
            // Parse JWT manually (3 parts: header.payload.signature)
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                log.debug("Invalid JWT structure (expected 3 parts, got {})", parts.length);
                return null;
            }

            String headerJson = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
            Map<String, Object> header = parseJson(headerJson);
            if (header == null || !"RS256".equals(header.get("alg"))) {
                log.debug("Unsupported JWT algorithm for CE monolith: {}", header != null ? header.get("alg") : null);
                return null;
            }

            if (!verifySignature(parts)) {
                log.debug("JWT signature verification failed");
                return null;
            }

            String payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);

            @SuppressWarnings("unchecked")
            Map<String, Object> payload = parseJson(payloadJson);
            if (payload == null) {
                return null;
            }

            Object tokenType = payload.get("token_type");
            if (!"access".equals(tokenType)) {
                log.debug("JWT is not an access token");
                return null;
            }

            // Check expiration
            Object expObj = payload.get("exp");
            if (expObj == null) {
                log.debug("JWT missing expiration claim");
                return null;
            }
            long expSeconds;
            if (expObj instanceof Number n) {
                expSeconds = n.longValue();
            } else {
                expSeconds = Long.parseLong(expObj.toString());
            }
            if (nowMillis.getAsLong() / 1000 > expSeconds) {
                log.debug("JWT expired");
                return null;
            }

            // Extract subject (userId)
            String subject = (String) payload.get("sub");
            if (subject == null || subject.isBlank()) {
                log.debug("JWT missing subject claim");
                return null;
            }

            // Extract other claims
            String email = payload.get("email") != null ? payload.get("email").toString() : null;
            Object userIdObj = payload.get("userId");
            String userId = userIdObj != null ? userIdObj.toString() : subject;
            // Provider ID: use "provider" claim (e.g. "local") combined with subject for Keycloak compat
            Object providerObj = payload.get("provider");
            String providerId = providerObj != null ? providerObj + ":" + (email != null ? email : subject) : subject;

            // Extract roles from JWT (added by JwtTokenProvider in CE mode)
            @SuppressWarnings("unchecked")
            List<String> roles = payload.get("roles") instanceof List<?> r
                    ? r.stream().map(Object::toString).toList()
                    : List.of("USER");

            return new JwtClaims(
                    userId,
                    providerId,
                    email,
                    String.join(",", roles),
                    stringClaim(payload, "defaultOrganizationId"),
                    stringClaim(payload, "defaultOrganizationRole"),
                    parseMembershipClaims(payload.get("memberships")));

        } catch (Exception e) {
            log.debug("JWT parsing failed: {}", e.getMessage());
            return null;
        }
    }

    private List<OrgMembershipClaim> parseMembershipClaims(Object membershipsObj) {
        if (!(membershipsObj instanceof List<?> rawMemberships)) {
            return List.of();
        }
        List<OrgMembershipClaim> memberships = new ArrayList<>();
        for (Object item : rawMemberships) {
            if (!(item instanceof Map<?, ?> rawMembership)) {
                continue;
            }
            String orgId = stringValue(rawMembership.get("orgId"));
            if (orgId == null || orgId.isBlank()) {
                continue;
            }
            memberships.add(new OrgMembershipClaim(
                    orgId,
                    stringValue(rawMembership.get("role")),
                    booleanValue(rawMembership.get("personal")),
                    booleanValue(rawMembership.get("paused"))));
        }
        return memberships;
    }

    private static String stringClaim(Map<String, Object> payload, String claim) {
        return stringValue(payload.get(claim));
    }

    private static String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString();
        return text.isBlank() ? null : text;
    }

    private static boolean booleanValue(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        return value != null && Boolean.parseBoolean(value.toString());
    }

    private boolean verifySignature(String[] parts) {
        try {
            if (verificationKeySupplier == null) {
                log.warn("CE monolith JWT verification key supplier is not configured");
                return false;
            }
            Key key = verificationKeySupplier.get();
            if (!(key instanceof PublicKey publicKey)) {
                log.warn("CE monolith JWT verification key is missing or not public");
                return false;
            }

            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initVerify(publicKey);
            signature.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            return signature.verify(Base64.getUrlDecoder().decode(parts[2]));
        } catch (Exception e) {
            log.debug("JWT signature verification error: {}", e.getMessage());
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJson(String json) {
        // Minimal JSON parser for JWT payload (flat key-value map)
        // This avoids adding Jackson dependency to the filter
        try {
            // Use a simple approach: delegate to Jackson if available on classpath
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.readValue(json, Map.class);
        } catch (Exception e) {
            log.error("Failed to parse JWT payload JSON", e);
            return null;
        }
    }

    private boolean isPublicPath(String path) {
        if (isProtectedMonolithPath(path)) {
            return false;
        }
        for (String prefix : publicPaths) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        // Default public paths
        return path.startsWith("/actuator/") ||
               path.startsWith("/api/auth/login") ||
               path.startsWith("/api/auth/register") ||
               // Public invitation lookup for the accept page (logged-out invitee
               // prefills email + chooses register-vs-login). EXACT match only -
               // it must NOT expose authenticated siblings like
               // /api/organizations/invitations/mine or
               // /api/organizations/{orgId}/invitations (which neither equal this
               // path nor are reached through it).
               path.equals("/api/organizations/invitations/info") ||
               path.startsWith("/api/auth/refresh") ||
               path.startsWith("/api/auth/logout") ||
               path.startsWith("/api/auth/openid-configuration") ||
               path.equals("/.well-known/jwks.json") ||
               path.startsWith("/api/auth/health") ||
               path.startsWith("/health") ||
               path.startsWith("/webhook/") ||
               path.startsWith("/chat/") ||
               path.startsWith("/form/") ||
               path.startsWith("/widget/") ||
               path.startsWith("/api/files/download") ||
               path.equals("/ws") || path.startsWith("/ws?") ||
               path.startsWith("/share/") ||
               path.startsWith("/s/") ||
               path.startsWith("/w/embed") ||
               path.startsWith("/api/v3/chat/models") ||
               path.startsWith("/api/public/");
    }

    private boolean isProtectedMonolithPath(String path) {
        return path.startsWith("/api/internal/conversation/tools/execute") ||
               path.startsWith("/api/credentials/by-integration/");
    }

    /**
     * True for {@code /api/internal/**} paths that must NOT be reachable by an external
     * (non-loopback) caller - i.e. the whole internal service-to-service surface MINUS the small
     * set of internal paths that are legitimately reachable from outside. The allowed set mirrors
     * the cloud gateway plus the two cross-container CE clients:
     * <ul>
     *   <li>the public gateway rewrite targets {@code webhook/}, {@code chat/}, {@code form/},
     *       {@code widget/}, {@code app/public/} - each self-validates its own webhook/share/form
     *       token (see {@code SimpleGatewayConfig} / {@link ServicePrefixRewriteFilter});</li>
     *   <li>the JWT-protected paths ({@link #isProtectedMonolithPath}), e.g.
     *       {@code conversation/tools/execute}, which enforce a real token below;</li>
     *   <li>{@code auth/pricing/} - the read-only, non-sensitive pricing snapshot the bridge reads;</li>
     *   <li>{@code browser-agent/} - the live-CDP takeover endpoints the frontend calls via the
     *       authenticated proxy; they read the JWT-injected {@code X-User-ID} and bind tokens to it.</li>
     *   <li>{@code bridge-access/} - the per-user CLI-bridge access check. NOT in
     *       {@link #isProtectedMonolithPath}: it must stay loopback-trusted because the in-process
     *       service callers (BridgeAccessEnforcer / HttpBridgeAccessClient) send only {@code X-User-ID}
     *       (no JWT). External callers still get the forged identity stripped and a JWT enforced below,
     *       so this is reachable-with-JWT externally and trusted in-process - same model as
     *       {@code browser-agent/}. (Protecting it stripped loopback trust and 401'd the in-process
     *       check → fail-closed deny of every CE bridge agent; see the loopback regression tests.)</li>
     * </ul>
     * Everything else under {@code /api/internal/**} is internal-only and gets a 404 externally.
     */
    private boolean isExternallyBlockedInternalPath(String path) {
        if (path == null || !path.startsWith("/api/internal/")) {
            return false;
        }
        if (isProtectedMonolithPath(path)) {
            return false;
        }
        return !(path.startsWith("/api/internal/webhook/")
                || path.startsWith("/api/internal/chat/")
                || path.startsWith("/api/internal/form/")
                || path.startsWith("/api/internal/widget/")
                || path.startsWith("/api/internal/app/public/")
                || path.startsWith("/api/internal/auth/pricing/")
                || path.startsWith("/api/internal/browser-agent/")
                || path.startsWith("/api/internal/bridge-access/"));
    }

    private boolean isLoopbackRequest(HttpServletRequest request) {
        try {
            String remoteAddr = request.getRemoteAddr();
            if (remoteAddr == null || remoteAddr.isBlank()) {
                return false;
            }
            if (!InetAddress.getByName(remoteAddr).isLoopbackAddress()) {
                return false;
            }
            // A genuine in-process loopback call (service-to-service on 127.0.0.1)
            // never carries proxy forwarding headers. If any are present, the
            // request was relayed by a (same-host) reverse proxy on behalf of an
            // EXTERNAL client - so it must NOT inherit the loopback trust that
            // skips the /api/internal/** block and the identity-header strip.
            // This closes the loopback-trust bypass for the documented
            // "source build fronted by nginx/Caddy with proxy_pass http://127.0.0.1"
            // topology without affecting the default direct-port deployment
            // (which has no proxy and therefore no forwarding header).
            String xff = request.getHeader("X-Forwarded-For");
            String xRealIp = request.getHeader("X-Real-IP");
            String forwarded = request.getHeader("Forwarded");
            boolean proxied = (xff != null && !xff.isBlank())
                    || (xRealIp != null && !xRealIp.isBlank())
                    || (forwarded != null && !forwarded.isBlank());
            return !proxied;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isTrustedIdentityHeader(String name) {
        if (name == null) {
            return false;
        }
        return TRUSTED_IDENTITY_HEADERS.stream().anyMatch(header -> header.equalsIgnoreCase(name));
    }

    /**
     * Extracted identity claims for header injection. Built from a validated JWT,
     * or synthesized by the {@code apiKeyResolver} for API-key authentication
     * (public so host applications can construct it when wiring that resolver).
     */
    public record JwtClaims(
            String userId,
            String providerId,
            String email,
            String roles,
            String defaultOrganizationId,
            String defaultOrganizationRole,
            List<OrgMembershipClaim> memberships) {

        public JwtClaims {
            memberships = memberships != null ? List.copyOf(memberships) : List.of();
        }

        OrgContext resolveOrganization(String claimedActiveOrgId) {
            String resolvedOrgId = null;
            String resolvedOrgRole = null;
            if (claimedActiveOrgId != null && !claimedActiveOrgId.isBlank()) {
                OrgMembershipClaim membership = findMembership(claimedActiveOrgId);
                if (membership != null && !membership.paused()) {
                    resolvedOrgId = membership.orgId();
                    resolvedOrgRole = membership.role();
                } else {
                    log.debug("Active-org claim '{}' rejected for CE user {} ({}); falling back",
                            claimedActiveOrgId,
                            userId,
                            membership == null ? "not a member" : "org paused");
                }
            }
            if (resolvedOrgId == null) {
                if (defaultOrganizationId != null && !isPausedOrg(defaultOrganizationId)) {
                    resolvedOrgId = defaultOrganizationId;
                    resolvedOrgRole = defaultOrganizationRole;
                } else {
                    OrgMembershipClaim personal = findPersonalMembership();
                    if (personal != null) {
                        resolvedOrgId = personal.orgId();
                        resolvedOrgRole = personal.role();
                    }
                }
            }
            return new OrgContext(resolvedOrgId, resolvedOrgRole);
        }

        private OrgMembershipClaim findMembership(String orgId) {
            if (orgId == null || memberships == null) {
                return null;
            }
            for (OrgMembershipClaim membership : memberships) {
                if (orgId.equals(membership.orgId())) {
                    return membership;
                }
            }
            return null;
        }

        private boolean isPausedOrg(String orgId) {
            OrgMembershipClaim membership = findMembership(orgId);
            return membership != null && membership.paused();
        }

        private OrgMembershipClaim findPersonalMembership() {
            if (memberships == null) {
                return null;
            }
            for (OrgMembershipClaim membership : memberships) {
                if (membership.personal()
                        && "OWNER".equalsIgnoreCase(membership.role())
                        && !membership.paused()) {
                    return membership;
                }
            }
            return null;
        }
    }

    public record OrgMembershipClaim(String orgId, String role, boolean personal, boolean paused) {}

    record OrgContext(String organizationId, String organizationRole) {}

    public record ShareTokenContext(String userId, String organizationId, String resourceType,
                                    String resourceToken, String resourceId) {}

    private static boolean isShareTokenReadMethod(String method) {
        return "GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method);
    }

    private static boolean isAllowedShareTokenApplicationBootstrap(
            String method,
            String path,
            ShareTokenContext shareContext) {
        if (!"POST".equals(method) || shareContext == null) {
            return false;
        }
        if (!"APPLICATION".equalsIgnoreCase(shareContext.resourceType())) {
            return false;
        }
        return "/api/v2/workflows/dag/execute".equals(path);
    }

    /**
     * Read-only POST endpoints allowed in a shared (share-token) context. Mirrors the cloud
     * gateway's {@code AuthenticationFilter.isAllowedShareTokenReadOnlyPost} carve-out:
     * {@code /api/workflow-inspector/tools/batch} resolves tool metadata for the whole plan in one
     * call. Blocking it - a regression that widened the share-context guard from
     * "block PUT/PATCH/DELETE" to "block all non-GET" - silently forced the builder into a per-tool
     * N+1 fallback, making large shared workflows slow to load. These reads carry no mutation, so
     * allowing them in a shared context is safe. Kept in lockstep with the gateway so a CE share
     * link behaves like a cloud one.
     */
    private static boolean isAllowedShareTokenReadOnlyPost(String method, String path) {
        return "POST".equals(method) && "/api/workflow-inspector/tools/batch".equals(path);
    }

    /**
     * Drops gateway-trust headers supplied by external clients before any
     * controller can read them. The CE monolith re-injects these after JWT
     * verification, matching cloud gateway semantics.
     */
    static class StrippedIdentityHeadersRequestWrapper extends HttpServletRequestWrapper {

        StrippedIdentityHeadersRequestWrapper(HttpServletRequest request) {
            super(request);
        }

        @Override
        public String getHeader(String name) {
            if (isTrustedIdentityHeader(name)) {
                return null;
            }
            return super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            if (isTrustedIdentityHeader(name)) {
                return Collections.emptyEnumeration();
            }
            return super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            Enumeration<String> original = super.getHeaderNames();
            if (original == null) {
                return Collections.emptyEnumeration();
            }

            Set<String> names = new LinkedHashSet<>();
            while (original.hasMoreElements()) {
                String name = original.nextElement();
                if (!isTrustedIdentityHeader(name)) {
                    names.add(name);
                }
            }
            return Collections.enumeration(names);
        }
    }

    /**
     * Request wrapper that injects auth headers (X-User-ID, etc.)
     * to simulate what the gateway would normally inject.
     */
    static class AuthHeadersRequestWrapper extends HttpServletRequestWrapper {

        private final Map<String, String> injectedHeaders;

        AuthHeadersRequestWrapper(HttpServletRequest request, JwtClaims claims, String claimedActiveOrgId) {
            super(request);
            this.injectedHeaders = new LinkedHashMap<>();
            injectedHeaders.put("X-User-ID", claims.userId());
            injectedHeaders.put("X-Authenticated", "true");
            if (claims.providerId() != null) {
                injectedHeaders.put("X-Provider-ID", claims.providerId());
            }
            if (claims.email() != null) {
                injectedHeaders.put("X-User-Email", claims.email());
            }
            // CE mode: credit wallet is unlimited (no Stripe billing).
            injectedHeaders.put("X-Remaining-Credits", "999999999");
            injectedHeaders.put("X-User-Plan", "CE");
            injectedHeaders.put("X-User-Roles", claims.roles() != null ? claims.roles() : "USER");
            addIfPresent(MONOLITH_ACTIVE_ORG_CLAIM_HEADER, claimedActiveOrgId);
            OrgContext orgContext = claims.resolveOrganization(claimedActiveOrgId);
            addIfPresent(ORGANIZATION_ID_HEADER, orgContext.organizationId());
            addIfPresent(ORGANIZATION_ROLE_HEADER, orgContext.organizationRole());
        }

        private void addIfPresent(String header, String value) {
            if (value != null && !value.isBlank()) {
                injectedHeaders.put(header, value);
            }
        }

        @Override
        public String getHeader(String name) {
            String injected = injectedHeaders.get(name);
            if (injected != null) {
                return injected;
            }
            return super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            String injected = injectedHeaders.get(name);
            if (injected != null) {
                return Collections.enumeration(List.of(injected));
            }
            return super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            Set<String> names = new LinkedHashSet<>(injectedHeaders.keySet());
            Enumeration<String> original = super.getHeaderNames();
            while (original.hasMoreElements()) {
                names.add(original.nextElement());
            }
            return Collections.enumeration(names);
        }
    }

    static class ShareAuthHeadersRequestWrapper extends HttpServletRequestWrapper {

        private final Map<String, String> injectedHeaders;

        ShareAuthHeadersRequestWrapper(HttpServletRequest request, String shareToken, ShareTokenContext context) {
            super(request);
            this.injectedHeaders = new LinkedHashMap<>();
            injectedHeaders.put("X-User-ID", context.userId());
            addIfPresent("X-Organization-ID", context.organizationId());
            injectedHeaders.put("X-Authenticated", "true");
            injectedHeaders.put("X-Provider-ID", "share:" + shareToken);
            injectedHeaders.put(SHARE_CONTEXT_HEADER, "true");
            addIfPresent(SHARE_RESOURCE_TYPE_HEADER, context.resourceType());
            addIfPresent(SHARE_RESOURCE_TOKEN_HEADER, context.resourceToken());
            addIfPresent(SHARE_RESOURCE_ID_HEADER, context.resourceId());
        }

        private void addIfPresent(String header, String value) {
            if (value != null && !value.isBlank()) {
                injectedHeaders.put(header, value);
            }
        }

        @Override
        public String getHeader(String name) {
            String injected = injectedHeaders.get(name);
            if (injected != null) {
                return injected;
            }
            return super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            String injected = injectedHeaders.get(name);
            if (injected != null) {
                return Collections.enumeration(List.of(injected));
            }
            return super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            Set<String> names = new LinkedHashSet<>(injectedHeaders.keySet());
            Enumeration<String> original = super.getHeaderNames();
            while (original.hasMoreElements()) {
                names.add(original.nextElement());
            }
            return Collections.enumeration(names);
        }
    }
}
