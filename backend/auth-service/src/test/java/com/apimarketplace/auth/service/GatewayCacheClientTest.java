package com.apimarketplace.auth.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PR1 regression - pin the GatewayCacheClient contract.
 *
 * Drift here = either silent loss of cache invalidation (users see stale
 * active-org for 5min after switching) or exceptions propagating up into
 * the set-default endpoint (5xx instead of best-effort).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GatewayCacheClient (PR1 set-default cache invalidation)")
class GatewayCacheClientTest {

    @Mock
    private RestTemplate restTemplate;

    private GatewayCacheClient client;

    @BeforeEach
    void setUp() {
        client = new GatewayCacheClient(restTemplate, "http://gateway:8080", "shared-secret-xyz", "microservice");
    }

    @Test
    @DisplayName("invalidateUserCache: POSTs to the right URL with the X-Internal-Auth header")
    void postsWithCorrectHeaderAndUrl() {
        when(restTemplate.exchange(
                eq("http://gateway:8080/api/gateway/cache/invalidate/kc-uuid-1"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of("success", true)));

        client.invalidateUserCache("kc-uuid-1");

        ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(
                eq("http://gateway:8080/api/gateway/cache/invalidate/kc-uuid-1"),
                eq(HttpMethod.POST),
                entityCaptor.capture(),
                eq(Map.class));
        assertThat(entityCaptor.getValue().getHeaders().getFirst("X-Internal-Auth"))
                .as("shared secret MUST be sent on every internal call")
                .isEqualTo("shared-secret-xyz");
    }

    @Test
    @DisplayName("invalidateUserCache: silently swallows transport exceptions (best-effort) - "
            + "set-default DB write must NOT fail because of a cache hiccup")
    void swallowsTransportException() {
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenThrow(new RestClientException("connection refused"));

        // Must not throw - exception is caught + logged WARN.
        client.invalidateUserCache("kc-uuid-1");
    }

    @Test
    @DisplayName("invalidateUserCache: skips entirely when providerId is null/blank - "
            + "no wasted call to the gateway")
    void skipsBlankProviderId() {
        client.invalidateUserCache(null);
        client.invalidateUserCache("");
        client.invalidateUserCache("   ");

        verify(restTemplate, never()).exchange(
                any(String.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class));
    }

    @Test
    @DisplayName("invalidateUserCache: skips when internal secret is unconfigured "
            + "(refuses to send the call un-authenticated)")
    void skipsWhenSecretUnconfigured() {
        client = new GatewayCacheClient(restTemplate, "http://gateway:8080", "", "microservice");
        client.invalidateUserCache("kc-uuid-1");

        verify(restTemplate, never()).exchange(
                any(String.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class));
    }

    @Test
    @DisplayName("invalidateUserCache: skips in CE monolith mode because no gateway cache route is mounted")
    void skipsInMonolithMode() {
        client = new GatewayCacheClient(restTemplate, "http://localhost:8080", "not-used-in-monolith", "monolith");

        client.invalidateUserCache("local:user@example.test");

        verify(restTemplate, never()).exchange(
                any(String.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class));
    }

    @Test
    @DisplayName("invalidateUserCache: non-2xx response is logged but not propagated as exception")
    void nonTwoXxNotPropagated() {
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(ResponseEntity.status(500).body(Map.of("error", "internal")));

        // Must not throw - caller (set-default) returns 200 even if cache invalidation hiccupped.
        client.invalidateUserCache("kc-uuid-1");
    }
}
