package com.apimarketplace.agent.bridge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Fail-CLOSED contract for {@link HttpBridgeAccessClient}.
 *
 * <p>The bridge gate sits on the dispatch path of a shared CLI subscription;
 * any silent fail-open would let an unreachable auth-service grant unrestricted
 * bridge access, which is exactly the abuse the V118 policy exists to prevent.
 * These tests pin the three fail-paths that must all resolve to
 * {@code REASON_GUARD_UNAVAILABLE}:
 *
 * <ol>
 *   <li>Missing / blank {@code services.auth-url}</li>
 *   <li>Network error while calling {@code /api/internal/bridge-access/check}</li>
 *   <li>200 OK with an empty body (misconfigured auth-service)</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("HttpBridgeAccessClient - fail-CLOSED contract")
class HttpBridgeAccessClientTest {

    @Mock private RestTemplate restTemplate;

    @Test
    @DisplayName("Missing auth-service URL denies with REASON_GUARD_UNAVAILABLE (no HTTP attempt)")
    void blankUrlDeniesWithoutCallingRest() {
        HttpBridgeAccessClient client = new HttpBridgeAccessClient("", restTemplate);

        BridgeAccessDecision d = client.check("u1", "USER", "claude-code", true);

        assertThat(d.allowed()).isFalse();
        assertThat(d.reason()).isEqualTo(BridgeAccessDecision.REASON_GUARD_UNAVAILABLE);
        assertThat(d.bridgeProvider()).isEqualTo("claude-code");
    }

    @Test
    @DisplayName("Network failure → deny (fail-CLOSED: unreachable auth-service never grants access)")
    void networkFailureDenies() {
        HttpBridgeAccessClient client = new HttpBridgeAccessClient("http://localhost:8083", restTemplate);

        when(restTemplate.exchange(any(String.class), eq(HttpMethod.POST),
                any(HttpEntity.class), eq(BridgeAccessDecision.class)))
                .thenThrow(new ResourceAccessException("connection refused"));

        BridgeAccessDecision d = client.check("u1", "USER", "codex", true);

        assertThat(d.allowed()).isFalse();
        assertThat(d.reason()).isEqualTo(BridgeAccessDecision.REASON_GUARD_UNAVAILABLE);
    }

    @Test
    @DisplayName("200 OK with empty body → deny (malformed auth-service response must not leak an allow)")
    void emptyBodyDenies() {
        HttpBridgeAccessClient client = new HttpBridgeAccessClient("http://localhost:8083", restTemplate);

        when(restTemplate.exchange(any(String.class), eq(HttpMethod.POST),
                any(HttpEntity.class), eq(BridgeAccessDecision.class)))
                .thenReturn(ResponseEntity.ok(null));

        BridgeAccessDecision d = client.check("u1", "USER", "gemini-cli", true);

        assertThat(d.allowed()).isFalse();
        assertThat(d.reason()).isEqualTo(BridgeAccessDecision.REASON_GUARD_UNAVAILABLE);
    }

    @Test
    @DisplayName("Allow decision from auth-service is returned verbatim (no swallowing of remaining quota)")
    void allowDecisionPassesThrough() {
        HttpBridgeAccessClient client = new HttpBridgeAccessClient("http://localhost:8083", restTemplate);

        BridgeAccessDecision serverDecision = BridgeAccessDecision.allow("claude-code", 7);
        when(restTemplate.exchange(contains("/api/internal/bridge-access/check"),
                eq(HttpMethod.POST), any(HttpEntity.class), eq(BridgeAccessDecision.class)))
                .thenReturn(ResponseEntity.ok(serverDecision));

        BridgeAccessDecision d = client.check("u1", "USER", "claude-code", true);

        assertThat(d.allowed()).isTrue();
        assertThat(d.remainingRequestsToday()).isEqualTo(7);
        assertThat(d.bridgeProvider()).isEqualTo("claude-code");
    }
}
