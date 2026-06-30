package com.apimarketplace.conversation.service.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;
import java.util.Set;

/**
 * Gates CLI-bridge chat requests against the shared admin subscription policy.
 *
 * <p>Conversation-service talks directly to the Node.js bridge server (port 8093)
 * when a chat turn targets a CLI bridge provider, which bypasses the
 * {@code BridgeAccessGuard} enforcement point that lives inside shared-agent-lib.
 * This enforcer closes that hole by calling auth-service's internal access endpoint
 * directly via RestTemplate - no shared-agent-lib compile dep required (which would
 * otherwise pull in the full LLM-provider graph the service doesn't scan).
 *
 * <p>Behavior:
 * <ul>
 *   <li>Non-bridge providers (openai, anthropic, mistral, google, …) → no-op,
 *       no HTTP call.</li>
 *   <li>Bridge provider + no service URL configured → no-op (legacy deploy
 *       without the guard infra; conservatively allow since cloud chat doesn't
 *       route through a CLI bridge).</li>
 *   <li>Bridge provider + service URL set → POST /api/internal/bridge-access/check
 *       with {@code X-User-ID} + {@code X-User-Roles} headers; parse {@code allowed}.</li>
 *   <li>Transport error / non-2xx / empty body → fail-CLOSED:
 *       throw denial with {@code reason=guard_unavailable}.</li>
 * </ul>
 *
 * <p>Call this BEFORE any irreversible work (credit fetch, DTO build, SSE setup)
 * so denied requests pay zero side-effect cost.
 */
@Component
public class BridgeAccessEnforcer {

    private static final Logger log = LoggerFactory.getLogger(BridgeAccessEnforcer.class);

    /** Provider names that go through a shared CLI subscription and must be gated. */
    private static final Set<String> BRIDGE_PROVIDERS = Set.of(
            "claude-code", "codex", "gemini-cli", "mistral-vibe");

    private static final String DEFAULT_USER_ROLES = "USER";

    private final RestTemplate restTemplate;
    private final String authServiceUrl;

    @Autowired
    public BridgeAccessEnforcer(
            RestTemplate restTemplate,
            @Value("${services.auth-url:}") String authServiceUrl) {
        this.restTemplate = restTemplate;
        this.authServiceUrl = authServiceUrl == null ? "" : authServiceUrl.trim();
    }

    /**
     * Throw {@link BridgeAccessDeniedException} if the user is not allowed to
     * dispatch against the given bridge provider. No-op for non-bridge providers.
     *
     * @param userId       caller's user id (Keycloak sub); blank → denied
     * @param userRoles    comma-separated roles ({@code USER} / {@code ADMIN}); null → {@code USER}
     * @param providerName LLM provider key (lowercased before matching)
     */
    public void enforce(String userId, String userRoles, String providerName) {
        if (!isBridgeProvider(providerName)) {
            return;
        }
        if (authServiceUrl.isEmpty()) {
            log.debug("BridgeAccessEnforcer: auth-service URL not configured, skipping check for provider={}",
                    providerName);
            return;
        }

        String normalisedProvider = providerName.toLowerCase();
        String url = UriComponentsBuilder.fromHttpUrl(authServiceUrl)
                .path("/api/internal/bridge-access/check")
                .queryParam("bridge", normalisedProvider)
                .queryParam("incrementUsage", "true")
                .toUriString();

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-ID", userId == null ? "" : userId);
        headers.set("X-User-Roles", userRoles == null || userRoles.isBlank() ? DEFAULT_USER_ROLES : userRoles);

        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                    url, HttpMethod.POST, new HttpEntity<>(headers), Map.class);
            Map<?, ?> body = response.getBody();
            if (body == null) {
                log.warn("Bridge access check returned empty body: provider={} user={}", providerName, userId);
                throw denial(providerName, "guard_unavailable", null);
            }
            Object allowedObj = body.get("allowed");
            boolean allowed = allowedObj instanceof Boolean b && b;
            if (!allowed) {
                String reason = stringOr(body.get("reason"), "guard_unavailable");
                Integer remaining = intOrNull(body.get("remainingRequestsToday"));
                log.info("Bridge access DENIED: user={} provider={} reason={}", userId, providerName, reason);
                throw denial(providerName, reason, remaining);
            }
        } catch (BridgeAccessDeniedException rethrow) {
            throw rethrow;
        } catch (Exception e) {
            // Fail-CLOSED - a transport hiccup against auth-service must NOT let a
            // user slip past the shared-subscription gate. The REASON_* constants
            // are what GlobalExceptionHandler maps to HTTP status.
            log.warn("Bridge access check failed transport: provider={} user={} err={}",
                    providerName, userId, e.getMessage());
            throw denial(providerName, "guard_unavailable", null);
        }
    }

    /**
     * Public helper for callers that only need to detect whether a provider goes
     * through a CLI bridge (and therefore might require gating) without actually
     * performing the check.
     */
    public static boolean isBridgeProvider(String providerName) {
        return providerName != null && BRIDGE_PROVIDERS.contains(providerName.toLowerCase());
    }

    private static BridgeAccessDeniedException denial(String provider, String reason, Integer remaining) {
        return new BridgeAccessDeniedException(provider, reason, remaining);
    }

    private static String stringOr(Object v, String fallback) {
        return v instanceof String s && !s.isBlank() ? s : fallback;
    }

    private static Integer intOrNull(Object v) {
        if (v instanceof Number n) return n.intValue();
        return null;
    }
}
