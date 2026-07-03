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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CE-side browser-agent relay client: authenticates each POST with the cloud-link
 * bearer + install header, targets the right cloud path, and maps a non-2xx cloud
 * response to an {@link IllegalStateException} carrying the status + body.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CloudBrowserAgentRelayClient (CE)")
class CloudBrowserAgentRelayClientTest {

    private static final CloudLlmRuntimeCredentials CREDENTIALS =
            new CloudLlmRuntimeCredentials("token-1", "install-1", "https://livecontext.ai/api/");

    @Mock
    private RestTemplate restTemplate;

    private CloudBrowserAgentRelayClient client;

    @BeforeEach
    void setUp() {
        client = new CloudBrowserAgentRelayClient(restTemplate);
    }

    @Test
    @DisplayName("agentBrowse posts to <cloudApiUrl>/ce-websearch/agent_browse with the cloud-link auth and returns the body")
    void agentBrowsePostsWithCloudLinkAuth() {
        Map<String, Object> cloudBody = Map.of("stop_reason", "COMPLETED",
                "cdp_ws_url", "wss://cloud/cdp/ses_1");
        when(restTemplate.exchange(
                eq("https://livecontext.ai/api/ce-websearch/agent_browse"),
                eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(cloudBody, HttpStatus.OK));

        CeBrowseRelayRequest request = new CeBrowseRelayRequest(
                "book a flight", "https://example.com", Map.of("provider", "google"),
                25, Map.of("interaction_mode", "autonomous"), "stream-1", "tc-1");
        Map<String, Object> result = client.agentBrowse(CREDENTIALS, request);

        assertThat(result).isEqualTo(cloudBody);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<HttpEntity<CeBrowseRelayRequest>> entityCaptor =
                ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(
                eq("https://livecontext.ai/api/ce-websearch/agent_browse"),
                eq(HttpMethod.POST), entityCaptor.capture(), eq(Map.class));
        HttpEntity<CeBrowseRelayRequest> entity = entityCaptor.getValue();
        assertThat(entity.getHeaders().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer token-1");
        assertThat(entity.getHeaders().getFirst("X-LiveContext-Install-Id")).isEqualTo("install-1");
        assertThat(entity.getBody()).isEqualTo(request);
    }

    @Test
    @DisplayName("browseControl posts to <cloudApiUrl>/ce-websearch/agent/sessions/{id}/{action}")
    void browseControlPostsToSessionPath() {
        Map<String, Object> cloudBody = Map.of("status", "running");
        when(restTemplate.exchange(
                eq("https://livecontext.ai/api/ce-websearch/agent/sessions/ses_1/status"),
                eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(cloudBody, HttpStatus.OK));

        Map<String, Object> result = client.browseControl(CREDENTIALS, "ses_1", "status",
                new CeBrowseControlRequest("ses_1", null));

        assertThat(result).isEqualTo(cloudBody);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<HttpEntity<CeBrowseControlRequest>> entityCaptor =
                ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(
                eq("https://livecontext.ai/api/ce-websearch/agent/sessions/ses_1/status"),
                eq(HttpMethod.POST), entityCaptor.capture(), eq(Map.class));
        assertThat(entityCaptor.getValue().getHeaders().getFirst(HttpHeaders.AUTHORIZATION))
                .isEqualTo("Bearer token-1");
    }

    @Test
    @DisplayName("non-2xx from the cloud surfaces as IllegalStateException with the status and body")
    void non2xxSurfacesStatusAndBody() {
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenThrow(HttpClientErrorException.create(HttpStatus.FORBIDDEN, "Forbidden",
                        new HttpHeaders(), "{\"error\":\"CE_LINK_NOT_ACTIVE\"}".getBytes(StandardCharsets.UTF_8),
                        StandardCharsets.UTF_8));

        CeBrowseRelayRequest request = new CeBrowseRelayRequest(
                "x", null, null, null, null, null, null);
        assertThatThrownBy(() -> client.agentBrowse(CREDENTIALS, request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("403")
                .hasMessageContaining("CE_LINK_NOT_ACTIVE");
    }

    @Test
    @DisplayName("empty cloud body surfaces as IllegalStateException")
    void emptyBodySurfacesAsIllegalState() {
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(null, HttpStatus.OK));

        CeBrowseRelayRequest request = new CeBrowseRelayRequest(
                "x", null, null, null, null, null, null);
        assertThatThrownBy(() -> client.agentBrowse(CREDENTIALS, request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("200");
    }
}
