package com.apimarketplace.auth.service;

import com.apimarketplace.common.event.EventBus;
import com.apimarketplace.common.scope.GatewayUserCacheInvalidation;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

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
 *
 * Multi-pod regression (2026-07): the HTTP POST reaches ONE gateway replica;
 * the EventBus publish is what evicts the entry on the others. Both paths are
 * pinned below.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GatewayCacheClient (PR1 set-default cache invalidation)")
class GatewayCacheClientTest {

    @Mock
    private RestTemplate restTemplate;

    private RecordingEventBus eventBus;

    private GatewayCacheClient client;

    @BeforeEach
    void setUp() {
        eventBus = new RecordingEventBus();
        client = new GatewayCacheClient(restTemplate, eventBus,
                "http://gateway:8080", "shared-secret-xyz", "microservice");
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
    @DisplayName("invalidateUserCache: skips the HTTP call when internal secret is unconfigured "
            + "(refuses to send the call un-authenticated) but STILL publishes the fan-out")
    void skipsWhenSecretUnconfigured() {
        client = new GatewayCacheClient(restTemplate, eventBus, "http://gateway:8080", "", "microservice");
        client.invalidateUserCache("kc-uuid-1");

        verify(restTemplate, never()).exchange(
                any(String.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class));
        assertThat(eventBus.published)
                .containsExactly(GatewayUserCacheInvalidation.CHANNEL + "|kc-uuid-1");
    }

    @Test
    @DisplayName("invalidateUserCache: skips in CE monolith mode because no gateway cache route is mounted")
    void skipsInMonolithMode() {
        client = new GatewayCacheClient(restTemplate, eventBus,
                "http://localhost:8080", "not-used-in-monolith", "monolith");

        client.invalidateUserCache("local:user@example.test");

        verify(restTemplate, never()).exchange(
                any(String.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class));
        assertThat(eventBus.published).as("no gateway subscriber exists in monolith").isEmpty();
    }

    @Test
    @DisplayName("Regression multi-pod: publishes the invalidation on the fan-out channel "
            + "so EVERY gateway replica evicts the stale role, not just the POSTed one")
    void publishesFanOutOnEventBus() {
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of("success", true)));

        client.invalidateUserCache("kc-uuid-1");

        assertThat(eventBus.published)
                .containsExactly(GatewayUserCacheInvalidation.CHANNEL + "|kc-uuid-1");
    }

    @Test
    @DisplayName("Regression multi-pod: an EventBus failure (Redis down) does not prevent the HTTP fallback")
    void eventBusFailureStillPosts() {
        EventBus throwingBus = new EventBus() {
            @Override
            public void publish(String channel, String message) {
                throw new IllegalStateException("redis down");
            }

            @Override
            public Subscription subscribe(String channel, Consumer<String> listener) {
                return () -> { };
            }
        };
        client = new GatewayCacheClient(restTemplate, throwingBus,
                "http://gateway:8080", "shared-secret-xyz", "microservice");
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of("success", true)));

        client.invalidateUserCache("kc-uuid-1");

        verify(restTemplate).exchange(
                eq("http://gateway:8080/api/gateway/cache/invalidate/kc-uuid-1"),
                eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class));
    }

    @Test
    @DisplayName("blank providerId publishes nothing on the fan-out channel either")
    void blankProviderIdNoFanOut() {
        client.invalidateUserCache(null);
        client.invalidateUserCache("   ");

        assertThat(eventBus.published).isEmpty();
    }

    private static final class RecordingEventBus implements EventBus {
        private final List<String> published = new ArrayList<>();

        @Override
        public void publish(String channel, String message) {
            published.add(channel + "|" + message);
        }

        @Override
        public Subscription subscribe(String channel, Consumer<String> listener) {
            return () -> { };
        }
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
