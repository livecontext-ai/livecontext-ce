package com.apimarketplace.agent.bridge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Duration;

/**
 * REST-over-HTTP implementation calling auth-service's
 * {@code POST /api/internal/bridge-access/check}.
 *
 * <p>Built without Spring annotations so either the CE monolith or the
 * cloud agent-service can wire a single instance in a {@code @Configuration}
 * using its own {@code services.auth-url} property.
 */
public class HttpBridgeAccessClient implements BridgeAccessClient {

    private static final Logger log = LoggerFactory.getLogger(HttpBridgeAccessClient.class);

    private final RestTemplate restTemplate;
    private final String authServiceUrl;

    public HttpBridgeAccessClient(String authServiceUrl) {
        this(authServiceUrl, buildDefaultRestTemplate());
    }

    public HttpBridgeAccessClient(String authServiceUrl, RestTemplate restTemplate) {
        this.authServiceUrl = authServiceUrl;
        this.restTemplate = restTemplate;
    }

    private static RestTemplate buildDefaultRestTemplate() {
        return new RestTemplateBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .readTimeout(Duration.ofSeconds(5))
                .additionalMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }

    @Override
    public BridgeAccessDecision check(String userId,
                                      String userRoles,
                                      String bridgeProvider,
                                      boolean incrementUsage) {
        if (authServiceUrl == null || authServiceUrl.isBlank()) {
            return BridgeAccessDecision.deny(bridgeProvider, BridgeAccessDecision.REASON_GUARD_UNAVAILABLE);
        }

        String url = UriComponentsBuilder
                .fromHttpUrl(authServiceUrl)
                .path("/api/internal/bridge-access/check")
                .queryParam("bridge", bridgeProvider)
                .queryParam("incrementUsage", incrementUsage)
                .toUriString();

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-ID", userId == null ? "" : userId);
        headers.set("X-User-Roles", userRoles == null || userRoles.isBlank() ? "USER" : userRoles);

        try {
            ResponseEntity<BridgeAccessDecision> response = restTemplate.exchange(
                    url, HttpMethod.POST, new HttpEntity<>(headers), BridgeAccessDecision.class);
            BridgeAccessDecision body = response.getBody();
            if (body == null) {
                log.warn("Bridge access check returned empty body: bridge={} user={}", bridgeProvider, userId);
                return BridgeAccessDecision.deny(bridgeProvider, BridgeAccessDecision.REASON_GUARD_UNAVAILABLE);
            }
            return body;
        } catch (Exception e) {
            log.warn("Bridge access check failed: bridge={} user={} err={}",
                    bridgeProvider, userId, e.getMessage());
            // Fail CLOSED: an unreachable auth-service must not grant bridge access.
            // Bridges share the admin's CLI subscription - silent fail-open lets any
            // user drain the account's rate limit.
            return BridgeAccessDecision.deny(bridgeProvider, BridgeAccessDecision.REASON_GUARD_UNAVAILABLE);
        }
    }
}
