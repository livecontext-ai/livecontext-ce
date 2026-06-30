package com.apimarketplace.orchestrator.tools.websearch;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Issues short-lived HS256 JWTs for the CDP-WS upgrade
 * (`wss://websearch-host/cdp/{sessionId}?token=...`).
 *
 * <p>The websearch-service verifies these with the same secret using its
 * {@code app/services/browser_agent/cdp_jwt.py} helper. Both sides agree on
 * the standard JWT 3-segment HS256 format and on the claim names:
 *
 * <ul>
 *   <li>{@code sid} - browser-agent session id (matches URL path)</li>
 *   <li>{@code sub} - user id at issue time</li>
 *   <li>{@code rid} - workflow run id</li>
 *   <li>{@code nid} - workflow node id</li>
 *   <li>{@code iat} - issued-at (unix seconds)</li>
 *   <li>{@code exp} - expiry (unix seconds; default 5 min after iat)</li>
 * </ul>
 *
 * <p>The shared secret is configured via
 * {@code websearch.cdp.jwt-secret} (env: {@code WEBSEARCH_CDP_JWT_SECRET}).
 * If the secret is unset/blank, {@link #issue(String, String, String, String)}
 * returns {@code null} and logs a warning - the orchestrator continues
 * without a token, and the frontend then renders a "live view unavailable"
 * fallback rather than failing the whole node.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "websearch.enabled", havingValue = "true", matchIfMissing = true)
public class CdpTokenIssuer {

    private final String secret;
    private final Duration ttl;

    public CdpTokenIssuer(
            @Value("${websearch.cdp.jwt-secret:${WEBSEARCH_CDP_JWT_SECRET:}}") String secret,
            @Value("${websearch.cdp.jwt-ttl-seconds:300}") int ttlSeconds) {
        this.secret = secret == null ? "" : secret;
        this.ttl = Duration.ofSeconds(Math.max(1, ttlSeconds));
    }

    /**
     * @return signed JWT or {@code null} if the secret is blank.
     */
    public String issue(String sessionId, String userId, String runId, String nodeId) {
        if (secret == null || secret.isBlank()) {
            log.warn("CDP JWT secret is blank - cannot issue token (set websearch.cdp.jwt-secret).");
            return null;
        }
        if (sessionId == null || sessionId.isBlank()
                || runId == null || runId.isBlank()
                || nodeId == null || nodeId.isBlank()) {
            log.warn("CDP JWT issue: missing required claim sid/rid/nid (sid={}, rid={}, nid={})",
                    sessionId, runId, nodeId);
            return null;
        }
        Instant now = Instant.now();
        try {
            return JWT.create()
                    .withClaim("sid", sessionId)
                    .withClaim("sub", userId == null ? "" : userId)
                    .withClaim("rid", runId)
                    .withClaim("nid", nodeId)
                    .withIssuedAt(now)
                    .withExpiresAt(now.plus(ttl))
                    .sign(Algorithm.HMAC256(secret));
        } catch (Exception e) {
            log.warn("CDP JWT signing failed: {}", e.getMessage());
            return null;
        }
    }

    /** Whether the issuer is configured (i.e. tokens can actually be minted). */
    public boolean isConfigured() {
        return secret != null && !secret.isBlank();
    }
}
