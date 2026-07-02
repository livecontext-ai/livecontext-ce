package com.apimarketplace.auth.service;

import com.apimarketplace.common.event.EventBus;
import com.apimarketplace.common.scope.GatewayUserCacheInvalidation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * Auth-service → Gateway internal-cache invalidation client (PR1 of org/membership
 * redesign). Without this call, after {@code OrganizationController.setDefaultOrganization}
 * flips the DB row, the gateway's 5-min QuotaCacheService keeps the OLD
 * defaultOrganizationId in memory - users see stale resources / wrong plan
 * badge for up to 5 minutes after switching workspace. Bug-1 in plan.md.
 *
 * <p>Best-effort: on transport failure or non-2xx response, we log WARN and
 * carry on. The user-visible UX is delayed but not broken - the cache will
 * naturally expire within 5min and self-heal. The set-default DB write
 * already committed, so no business invariant is at stake here.
 *
 * <p>Multi-pod: the HTTP POST reaches only the ONE gateway replica the
 * Service/LB routes to. The {@code EventBus} publish on
 * {@link GatewayUserCacheInvalidation#CHANNEL} is what evicts the entry on
 * EVERY replica (each gateway subscribes via
 * {@code UserCacheInvalidationSubscriber}); the POST is kept as an immediate,
 * Redis-independent fallback.
 *
 * <p>Authentication is via the {@code X-Internal-Auth} header carrying the
 * shared {@code gateway.filter.secret-key} secret. The gateway controller
 * validates this with constant-time comparison.
 */
@Service
public class GatewayCacheClient {

    private static final Logger log = LoggerFactory.getLogger(GatewayCacheClient.class);

    private final RestTemplate restTemplate;
    private final EventBus eventBus;
    private final String gatewayUrl;
    private final String internalSecret;
    private final boolean monolithMode;

    public GatewayCacheClient(RestTemplate restTemplate,
                              EventBus eventBus,
                              @Value("${services.gateway-url:http://localhost:8080}") String gatewayUrl,
                              @Value("${gateway.filter.secret-key:}") String internalSecret,
                              @Value("${deployment.mode:microservice}") String deploymentMode) {
        this.restTemplate = restTemplate;
        this.eventBus = eventBus;
        this.gatewayUrl = gatewayUrl;
        this.internalSecret = internalSecret;
        this.monolithMode = "monolith".equalsIgnoreCase(deploymentMode);
    }

    /**
     * Invalidate the gateway's user-resolution cache entry for a specific
     * providerId. Called after {@code OrganizationMember.isDefault} flips so
     * the new active-org context is visible on the next request without
     * waiting for the 5-min TTL.
     *
     * @param providerId the Keycloak/auth provider id (NOT the auth-service userId -
     *                   the gateway cache is keyed on providerId)
     */
    public void invalidateUserCache(String providerId) {
        if (monolithMode) {
            log.debug("Skipping gateway cache invalidation in monolith mode for providerId={}", providerId);
            return;
        }
        if (providerId == null || providerId.isBlank()) {
            log.warn("Skipping cache invalidation - providerId is null/blank");
            return;
        }
        // Fan-out FIRST: the pub/sub reaches EVERY gateway replica. The HTTP POST
        // below only lands on the one replica the Service/LB picks - with 2+
        // replicas a demotion/removal left the others serving the stale role for
        // up to the 5-min TTL. Both paths are best-effort and idempotent.
        try {
            eventBus.publish(
                    GatewayUserCacheInvalidation.CHANNEL,
                    GatewayUserCacheInvalidation.messageFor(providerId));
        } catch (Exception | LinkageError e) {
            log.warn("Failed to publish gateway user-cache invalidation for providerId={}: {}",
                    providerId, e.getMessage());
        }
        if (internalSecret == null || internalSecret.isBlank()) {
            log.warn("Skipping cache invalidation HTTP call - gateway.filter.secret-key is not configured");
            return;
        }
        String url = gatewayUrl + "/api/gateway/cache/invalidate/" + providerId;
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.add("X-Internal-Auth", internalSecret);
            ResponseEntity<Map> resp = restTemplate.exchange(
                    url, HttpMethod.POST, new HttpEntity<>(headers), Map.class);
            HttpStatusCode status = resp.getStatusCode();
            if (status.is2xxSuccessful()) {
                log.debug("Invalidated gateway cache for providerId={}", providerId);
            } else {
                log.warn("Gateway cache invalidation returned non-2xx for providerId={}: status={}",
                        providerId, status);
            }
        } catch (Exception e) {
            log.warn("Failed to invalidate gateway cache for providerId={}: {}",
                    providerId, e.getMessage());
        }
    }
}
