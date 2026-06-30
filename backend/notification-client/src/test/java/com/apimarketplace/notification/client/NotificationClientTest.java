package com.apimarketplace.notification.client;

import com.apimarketplace.notification.client.dto.NotificationEmitRequest;
import com.apimarketplace.notification.client.dto.NotificationEmitResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link NotificationClient}. Mocks {@link RestTemplate}; the
 * client's main job is failure-swallowing and minimal request validation
 * (skip on missing required fields).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationClient")
class NotificationClientTest {

    @Mock private RestTemplate restTemplate;
    private NotificationClient client;

    @BeforeEach
    void setUp() {
        client = new NotificationClient(restTemplate, "http://orchestrator:8099");
    }

    private NotificationEmitRequest validRequest() {
        NotificationEmitRequest r = new NotificationEmitRequest();
        r.setTenantId("user-1");
        r.setCategory("CRED_EXPIRED");
        r.setSeverity("warning");
        r.setSubjectType("CREDENTIAL");
        r.setSubjectId(UUID.randomUUID());
        r.setSourceId("cred-1:20260508");
        Map<String, Object> payload = new HashMap<>();
        payload.put("status", "expired");
        r.setPayload(payload);
        r.setOccurredAt(Instant.now());
        return r;
    }

    @Test
    @DisplayName("2xx response returns true")
    void successReturnsTrue() {
        when(restTemplate.postForEntity(eq("http://orchestrator:8099/api/internal/notifications/emit"),
                any(HttpEntity.class), eq(NotificationEmitResponse.class)))
                .thenReturn(new ResponseEntity<>(new NotificationEmitResponse(true, 42L), HttpStatus.OK));

        assertThat(client.emit(validRequest())).isTrue();
    }

    @Test
    @DisplayName("Network timeout (ResourceAccessException) is swallowed and returns false")
    void timeoutReturnsFalseAndDoesNotPropagate() {
        when(restTemplate.postForEntity(any(String.class), any(HttpEntity.class),
                eq(NotificationEmitResponse.class)))
                .thenThrow(new ResourceAccessException("Read timed out"));

        assertThat(client.emit(validRequest())).isFalse();
    }

    @Test
    @DisplayName("Any exception is swallowed (HTTP 5xx, parse error, etc.)")
    void runtimeExceptionSwallowed() {
        when(restTemplate.postForEntity(any(String.class), any(HttpEntity.class),
                eq(NotificationEmitResponse.class)))
                .thenThrow(new RuntimeException("boom"));

        assertThat(client.emit(validRequest())).isFalse();
    }

    @Test
    @DisplayName("Null request → false, no HTTP call")
    void nullRequestSkipped() {
        assertThat(client.emit(null)).isFalse();
        verify(restTemplate, never()).postForEntity(any(String.class), any(HttpEntity.class),
                any(Class.class));
    }

    @Test
    @DisplayName("Missing tenantId → false, no HTTP call")
    void missingTenantIdSkipped() {
        NotificationEmitRequest r = validRequest();
        r.setTenantId(null);

        assertThat(client.emit(r)).isFalse();
        verify(restTemplate, never()).postForEntity(any(String.class), any(HttpEntity.class),
                any(Class.class));
    }

    @Test
    @DisplayName("Missing category → false, no HTTP call")
    void missingCategorySkipped() {
        NotificationEmitRequest r = validRequest();
        r.setCategory(null);

        assertThat(client.emit(r)).isFalse();
        verify(restTemplate, never()).postForEntity(any(String.class), any(HttpEntity.class),
                any(Class.class));
    }

    @Test
    @DisplayName("Built-in factory has 2s connect / 5s read timeouts (cascade-prevention contract)")
    void defaultTimeoutsAreTight() {
        // Verify the documented contract - cheap regression guard against
        // someone bumping these values without considering producer-pool stall.
        assertThat(NotificationClient.CONNECT_TIMEOUT.toMillis()).isEqualTo(2000L);
        assertThat(NotificationClient.READ_TIMEOUT.toMillis()).isEqualTo(5000L);
    }
}
