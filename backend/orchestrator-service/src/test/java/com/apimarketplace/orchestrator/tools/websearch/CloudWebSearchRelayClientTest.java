package com.apimarketplace.orchestrator.tools.websearch;

import com.apimarketplace.agent.cloud.CloudLlmRuntimeCredentials;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CloudWebSearchRelayClient (CE)")
class CloudWebSearchRelayClientTest {

    private static final CloudLlmRuntimeCredentials CREDENTIALS =
            new CloudLlmRuntimeCredentials("token-1", "install-1", "https://livecontext.ai/api/");

    @Mock
    private RestTemplate restTemplate;

    private CloudWebSearchRelayClient client;

    @BeforeEach
    void setUp() {
        client = new CloudWebSearchRelayClient(restTemplate);
    }

    @Test
    @DisplayName("posts to <cloudApiUrl>/ce-websearch/search with the cloud-link bearer token and install header")
    void postsWithCloudLinkAuth() {
        Map<String, Object> cloudBody = Map.of("results", List.of());
        when(restTemplate.exchange(
                eq("https://livecontext.ai/api/ce-websearch/search"),
                eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(cloudBody, HttpStatus.OK));

        CeWebSearchRelayRequest request =
                new CeWebSearchRelayRequest("java", 5, "week", "stream-1", "tc-1");
        Map<String, Object> result = client.search(CREDENTIALS, request);

        assertThat(result).isEqualTo(cloudBody);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<HttpEntity<CeWebSearchRelayRequest>> entityCaptor =
                ArgumentCaptor.forClass(HttpEntity.class);
        org.mockito.Mockito.verify(restTemplate).exchange(
                eq("https://livecontext.ai/api/ce-websearch/search"),
                eq(HttpMethod.POST), entityCaptor.capture(), eq(Map.class));
        HttpEntity<CeWebSearchRelayRequest> entity = entityCaptor.getValue();
        assertThat(entity.getHeaders().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer token-1");
        assertThat(entity.getHeaders().getFirst("X-LiveContext-Install-Id")).isEqualTo("install-1");
        assertThat(entity.getBody()).isEqualTo(request);
    }

    @Test
    @DisplayName("non-2xx from the cloud surfaces as IllegalStateException with the status and body")
    void non2xxSurfacesStatusAndBody() {
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenThrow(HttpClientErrorException.create(HttpStatus.FORBIDDEN, "Forbidden",
                        new HttpHeaders(), "{\"error\":\"CE_LINK_NOT_ACTIVE\"}".getBytes(StandardCharsets.UTF_8),
                        StandardCharsets.UTF_8));

        assertThatThrownBy(() -> client.search(CREDENTIALS,
                new CeWebSearchRelayRequest("java", null, null, null, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("403")
                .hasMessageContaining("CE_LINK_NOT_ACTIVE");
    }

    @Test
    @DisplayName("empty body from the cloud surfaces as IllegalStateException")
    void emptyBodySurfacesAsError() {
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(null, HttpStatus.OK));

        assertThatThrownBy(() -> client.search(CREDENTIALS,
                new CeWebSearchRelayRequest("java", null, null, null, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("200");
    }
}
