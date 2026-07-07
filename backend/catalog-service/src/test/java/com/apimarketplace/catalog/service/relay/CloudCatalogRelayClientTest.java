package com.apimarketplace.catalog.service.relay;

import com.apimarketplace.agent.cloud.CloudLlmRuntimeCredentials;
import com.apimarketplace.catalog.domain.dto.ToolExecutionResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CloudCatalogRelayClient (CE)")
class CloudCatalogRelayClientTest {

    private static final CloudLlmRuntimeCredentials CREDENTIALS =
            new CloudLlmRuntimeCredentials("token-1", "install-1", "https://livecontext.ai/api/");

    @Mock
    private RestTemplate restTemplate;

    private CloudCatalogRelayClient client;

    @BeforeEach
    void setUp() {
        client = new CloudCatalogRelayClient(restTemplate, new ObjectMapper());
    }

    @Test
    @DisplayName("posts to <cloudApiUrl>/ce-catalog/tools/{apiSlug}/{toolSlug}/execute with cloud-link auth and the execution payload")
    void postsWithCloudLinkAuthAndPayload() {
        Map<String, Object> cloudBody = Map.of(
                "success", true,
                "result", Map.of("ok", true),
                "metadata", Map.of("toolName", "send_message"),
                "executionTimeMs", 321,
                "toolId", "tool-1",
                "requestId", "req-1");
        when(restTemplate.exchange(
                eq("https://livecontext.ai/api/ce-catalog/tools/telegram/send-message/execute"),
                eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(cloudBody, HttpStatus.OK));

        ToolExecutionResponse response = client.execute(CREDENTIALS, "telegram", "send-message",
                Map.of("chat_id", "123"), List.of("payload.body"), 5, Boolean.TRUE);

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getResult()).isEqualTo(Map.of("ok", true));
        assertThat(response.getMetadata()).containsEntry("toolName", "send_message");
        assertThat(response.getExecutionTimeMs()).isEqualTo(321L);
        assertThat(response.getToolId()).isEqualTo("tool-1");
        assertThat(response.getRequestId()).isEqualTo("req-1");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<HttpEntity<Map<String, Object>>> entityCaptor =
                ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(
                eq("https://livecontext.ai/api/ce-catalog/tools/telegram/send-message/execute"),
                eq(HttpMethod.POST), entityCaptor.capture(), eq(Map.class));
        HttpEntity<Map<String, Object>> entity = entityCaptor.getValue();
        assertThat(entity.getHeaders().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer token-1");
        assertThat(entity.getHeaders().getFirst("X-LiveContext-Install-Id")).isEqualTo("install-1");
        Map<String, Object> expectedBody = new HashMap<>();
        expectedBody.put("parameters", Map.of("chat_id", "123"));
        expectedBody.put("expand", List.of("payload.body"));
        expectedBody.put("maxItems", 5);
        expectedBody.put("inlineBinaries", Boolean.TRUE);
        assertThat(entity.getBody()).isEqualTo(expectedBody);
    }

    @Test
    @DisplayName("a 200 with success=false (upstream tool failure) is returned as a response, never thrown")
    void upstreamFailureIsAResponseNotAnException() {
        Map<String, Object> cloudBody = new HashMap<>();
        cloudBody.put("success", false);
        cloudBody.put("error", "Telegram returned 400: chat not found");
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(cloudBody, HttpStatus.OK));

        ToolExecutionResponse response = client.execute(CREDENTIALS, "telegram", "send-message",
                Map.of(), null, null, null);

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getError()).contains("chat not found");
    }

    @Test
    @DisplayName("non-2xx with an {\"error\": ...} body surfaces as CatalogRelayException carrying the code")
    void non2xxSurfacesErrorCode() {
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenThrow(HttpClientErrorException.create(HttpStatus.PAYMENT_REQUIRED, "Payment Required",
                        new HttpHeaders(),
                        "{\"error\":\"INSUFFICIENT_CREDITS\",\"delinquent\":true}".getBytes(StandardCharsets.UTF_8),
                        StandardCharsets.UTF_8));

        CloudCatalogRelayClient.CatalogRelayException ex = catchThrowableOfType(
                () -> client.execute(CREDENTIALS, "telegram", "send-message", Map.of(), null, null, null),
                CloudCatalogRelayClient.CatalogRelayException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getErrorCode()).isEqualTo("INSUFFICIENT_CREDITS");
        assertThat(ex.isDelinquent()).isTrue();
        assertThat(ex.getMessage()).contains("402");
    }

    @Test
    @DisplayName("non-2xx with an unparseable body keeps errorCode null")
    void non2xxUnparseableBodyKeepsNullErrorCode() {
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenThrow(HttpClientErrorException.create(HttpStatus.BAD_GATEWAY, "Bad Gateway",
                        new HttpHeaders(), "<html>proxy error</html>".getBytes(StandardCharsets.UTF_8),
                        StandardCharsets.UTF_8));

        CloudCatalogRelayClient.CatalogRelayException ex = catchThrowableOfType(
                () -> client.execute(CREDENTIALS, "telegram", "send-message", Map.of(), null, null, null),
                CloudCatalogRelayClient.CatalogRelayException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getErrorCode()).isNull();
        assertThat(ex.isDelinquent()).isFalse();
    }

    @Test
    @DisplayName("empty 200 body surfaces as CatalogRelayException")
    void emptyBodySurfacesAsRelayException() {
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(null, HttpStatus.OK));

        assertThatThrownBy(() -> client.execute(CREDENTIALS, "telegram", "send-message",
                Map.of(), null, null, null))
                .isInstanceOf(CloudCatalogRelayClient.CatalogRelayException.class)
                .hasMessageContaining("no body");
    }
}
