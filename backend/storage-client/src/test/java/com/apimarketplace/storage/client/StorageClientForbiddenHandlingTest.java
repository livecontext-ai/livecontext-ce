package com.apimarketplace.storage.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Pins the key-owner 403 handling on the internal download/presign routes:
 * storage-service authorizes by KEY-OWNER prefix, so a 403 means the caller
 * presented the wrong tenant for the key (org-shared file fetched with the
 * caller's id instead of the owner's). The client must degrade to null (the
 * long-standing best-effort contract) WITHOUT throwing, and the dedicated
 * WARN branch exists so the symptom is no longer a fully silent empty result.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StorageClient - key-owner 403 handling (download / presign)")
class StorageClientForbiddenHandlingTest {

    @Mock
    private RestTemplate restTemplate;

    private StorageClient client() {
        return new StorageClient(restTemplate, "http://storage:8093");
    }

    private static HttpClientErrorException forbidden() {
        return HttpClientErrorException.create(
                HttpStatus.FORBIDDEN, "Forbidden", org.springframework.http.HttpHeaders.EMPTY, new byte[0], null);
    }

    @Test
    @DisplayName("download: a key-owner 403 returns null without throwing")
    void downloadForbiddenReturnsNull() {
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(byte[].class)))
                .thenThrow(forbidden());

        assertThatCode(() -> {
            byte[] result = client().download("wrong-tenant", "owner-tenant/wf/run/file.png");
            assertThat(result).isNull();
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("presign: a key-owner 403 returns null without throwing")
    void presignForbiddenReturnsNull() {
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenThrow(forbidden());

        assertThatCode(() -> {
            String url = client().generateDownloadUrl("wrong-tenant", "owner-tenant/wf/run/file.png", 15);
            assertThat(url).isNull();
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("download: non-403 transport failures keep the generic error path (null, no throw)")
    void downloadOtherErrorsStillNull() {
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(byte[].class)))
                .thenThrow(new RuntimeException("connection refused"));

        assertThat(client().download("tenant", "tenant/file.bin")).isNull();
    }
}
