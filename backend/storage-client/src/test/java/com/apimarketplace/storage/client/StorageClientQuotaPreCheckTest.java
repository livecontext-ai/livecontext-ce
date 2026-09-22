package com.apimarketplace.storage.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * The pre-spend quota probe. Its caller (media generation) is about to charge a customer, so the
 * two behaviours that matter are opposite in spirit: report a refusal faithfully when storage
 * says the account is full, and NEVER invent one otherwise.
 *
 * <p>Fail-open is the deliberate half. This probe is advisory, not the gate: the real quota check
 * still runs when the asset is written. Failing closed would turn a storage-service blip into a
 * total generation outage for every customer, which is a worse failure than the one the probe
 * prevents.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StorageClient - quota pre-check")
class StorageClientQuotaPreCheckTest {

    @Mock
    private RestTemplate restTemplate;

    private StorageClient client() {
        return new StorageClient(restTemplate, "http://storage:8093");
    }

    @SuppressWarnings("unchecked")
    private void answers(Map<String, Object> body) {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity(body, HttpStatus.OK));
    }

    @SuppressWarnings("unchecked")
    private void fails(RuntimeException e) {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenThrow(e);
    }

    @Test
    @DisplayName("allowed=false is reported as no room")
    void reportsRefusal() {
        answers(Map.of("allowed", false, "status", "HARD_LIMIT_REACHED"));

        assertThat(client().hasRoomFor("42", null, 1L)).isFalse();
    }

    @Test
    @DisplayName("allowed=true is reported as room available")
    void reportsRoom() {
        answers(Map.of("allowed", true, "status", "OK"));

        assertThat(client().hasRoomFor("42", null, 1L)).isTrue();
    }

    @Test
    @DisplayName("storage-service unreachable -> room available, because this probe must not gate on an outage")
    void failsOpenOnTransportError() {
        fails(new ResourceAccessException("connection refused"));

        assertThat(client().hasRoomFor("42", null, 1L)).isTrue();
    }

    @Test
    @DisplayName("storage-service 500 -> room available, same reason")
    void failsOpenOnServerError() {
        fails(HttpServerErrorException.create(
                HttpStatus.INTERNAL_SERVER_ERROR, "boom", org.springframework.http.HttpHeaders.EMPTY, new byte[0], null));

        assertThat(client().hasRoomFor("42", null, 1L)).isTrue();
    }

    @Test
    @DisplayName("a malformed body -> room available; only an explicit false refuses")
    void failsOpenOnMalformedBody() {
        answers(Map.of("unexpected", "shape"));

        assertThat(client().hasRoomFor("42", null, 1L)).isTrue();
    }

    @Test
    @DisplayName("tenant, byte count and workspace travel to the endpoint that scopes the answer")
    void sendsScopeAndSize() {
        answers(Map.of("allowed", true));

        client().hasRoomFor("42", "org-7", 50L);

        ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<HttpEntity> entity = ArgumentCaptor.forClass(HttpEntity.class);
        org.mockito.Mockito.verify(restTemplate)
                .exchange(url.capture(), eq(HttpMethod.GET), entity.capture(), eq(Map.class));
        assertThat(url.getValue())
                .contains("/api/internal/storage/quota/check")
                .contains("tenantId=42")
                .contains("bytes=50")
                .contains("organizationId=org-7");
        // The workspace also rides the header, which is how storage-service scopes a caller that
        // did not name one explicitly. Both must agree or the probe reads the wrong counter.
        assertThat(entity.getValue().getHeaders().getFirst("X-Organization-ID")).isEqualTo("org-7");
        assertThat(entity.getValue().getHeaders().getFirst("X-User-ID")).isEqualTo("42");
    }

    @Test
    @DisplayName("no workspace named -> no organizationId in the query, so the request context decides")
    void omitsBlankOrganization() {
        answers(Map.of("allowed", true));

        client().hasRoomFor("42", "  ", 1L);

        ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(restTemplate)
                .exchange(url.capture(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class));
        assertThat(url.getValue()).doesNotContain("organizationId=");
    }
}
