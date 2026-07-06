package com.apimarketplace.orchestrator.services.mcp;

import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * {@link RemoteToolGateway} POSTs an aggregated tool call to its owning service and
 * maps the {@code /api/agent-tools/execute} response back to a {@code ToolExecutionResult}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RemoteToolGateway")
class RemoteToolGatewayTest {

    private static final String CATALOG_URL = "http://catalog:8081";
    private static final String EXECUTE = CATALOG_URL + "/api/agent-tools/execute";

    @Mock
    private AggregatedToolCatalog catalog;
    @Mock
    private RestTemplate restTemplate;

    private RemoteToolGateway gateway() {
        return new RemoteToolGateway(catalog, restTemplate, new com.fasterxml.jackson.databind.ObjectMapper());
    }

    @Test
    @DisplayName("an unknown tool (no owning service) fails with TOOL_NOT_FOUND without any HTTP call")
    void unknownToolFailsFast() {
        when(catalog.serviceUrlFor("mystery")).thenReturn(null);

        ToolExecutionResult result = gateway().execute("mystery", Map.of(), "t1", null, null);

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo(ToolErrorCode.TOOL_NOT_FOUND);
    }

    @Test
    @DisplayName("a 200 success body is mapped to a successful result carrying data + metadata")
    void successBodyMapped() {
        when(catalog.serviceUrlFor("catalog")).thenReturn(CATALOG_URL);
        Map<String, Object> body = Map.of(
                "success", true,
                "data", Map.of("items", 5),
                "metadata", Map.of("source", "catalog"));
        when(restTemplate.exchange(eq(EXECUTE), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(body));

        ToolExecutionResult result = gateway().execute("catalog", Map.of("action", "search"), "t1", "o1", "ADMIN");

        assertThat(result.success()).isTrue();
        assertThat(result.data()).isEqualTo(Map.of("items", 5));
        assertThat(result.metadata()).containsEntry("source", "catalog");
    }

    @Test
    @DisplayName("tenant and org are forwarded as X-User-ID / X-Organization-* headers")
    void forwardsTenantAndOrgHeaders() {
        when(catalog.serviceUrlFor("catalog")).thenReturn(CATALOG_URL);
        when(restTemplate.exchange(eq(EXECUTE), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of("success", true, "data", Map.of())));

        gateway().execute("catalog", Map.of(), "tenant-9", "org-9", "MEMBER");

        ArgumentCaptor<HttpEntity<Map<String, Object>>> captor = ArgumentCaptor.forClass(HttpEntity.class);
        org.mockito.Mockito.verify(restTemplate)
                .exchange(eq(EXECUTE), eq(HttpMethod.POST), captor.capture(), eq(Map.class));
        HttpHeaders headers = captor.getValue().getHeaders();
        assertThat(headers.getFirst("X-User-ID")).isEqualTo("tenant-9");
        assertThat(headers.getFirst("X-Organization-ID")).isEqualTo("org-9");
        assertThat(headers.getFirst("X-Organization-Role")).isEqualTo("MEMBER");
    }

    @Test
    @DisplayName("a 400 with a JSON error body is mapped to a failure carrying that error")
    void errorBodyMapped() {
        when(catalog.serviceUrlFor("catalog")).thenReturn(CATALOG_URL);
        String errorJson = "{\"success\":false,\"error\":\"bad params\"}";
        HttpClientErrorException ex = HttpClientErrorException.create(
                HttpStatus.BAD_REQUEST, "Bad Request", new HttpHeaders(),
                errorJson.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        when(restTemplate.exchange(eq(EXECUTE), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenThrow(ex);

        ToolExecutionResult result = gateway().execute("catalog", Map.of(), "t1", null, null);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).isEqualTo("bad params");
    }

    @Test
    @DisplayName("a 200 body carrying success:false is mapped to a failure with that error")
    void twoHundredWithSuccessFalseIsFailure() {
        when(catalog.serviceUrlFor("catalog")).thenReturn(CATALOG_URL);
        when(restTemplate.exchange(eq(EXECUTE), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of("success", false, "error", "no rows")));

        ToolExecutionResult result = gateway().execute("catalog", Map.of(), "t1", null, null);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).isEqualTo("no rows");
    }

    @Test
    @DisplayName("the sibling's own error classification (errorType) is preserved, not flattened")
    void errorTypeIsPreserved() {
        when(catalog.serviceUrlFor("catalog")).thenReturn(CATALOG_URL);
        String errorJson = "{\"success\":false,\"error\":\"bad\",\"errorType\":\"VALIDATION_ERROR\"}";
        HttpClientErrorException ex = HttpClientErrorException.create(
                HttpStatus.BAD_REQUEST, "Bad Request", new HttpHeaders(),
                errorJson.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        when(restTemplate.exchange(eq(EXECUTE), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenThrow(ex);

        ToolExecutionResult result = gateway().execute("catalog", Map.of(), "t1", null, null);

        assertThat(result.errorCode()).isEqualTo(ToolErrorCode.VALIDATION_ERROR);
    }

    @Test
    @DisplayName("an empty 200 body is a clean EXECUTION_FAILED, not an NPE")
    void emptyBodyIsFailure() {
        when(catalog.serviceUrlFor("catalog")).thenReturn(CATALOG_URL);
        when(restTemplate.exchange(eq(EXECUTE), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(null, HttpStatus.OK));

        ToolExecutionResult result = gateway().execute("catalog", Map.of(), "t1", null, null);

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo(ToolErrorCode.EXECUTION_FAILED);
    }

    @Test
    @DisplayName("the request body forwards tool name, parameters and tenant/org scope")
    void requestBodyCarriesToolAndScope() {
        when(catalog.serviceUrlFor("catalog")).thenReturn(CATALOG_URL);
        when(restTemplate.exchange(eq(EXECUTE), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of("success", true, "data", Map.of())));

        gateway().execute("catalog", Map.of("action", "search"), "tenant-7", "org-7", "ADMIN");

        ArgumentCaptor<HttpEntity<Map<String, Object>>> captor = ArgumentCaptor.forClass(HttpEntity.class);
        org.mockito.Mockito.verify(restTemplate)
                .exchange(eq(EXECUTE), eq(HttpMethod.POST), captor.capture(), eq(Map.class));
        Map<String, Object> sent = captor.getValue().getBody();
        assertThat(sent).containsEntry("tool", "catalog");
        assertThat(sent).containsEntry("parameters", Map.of("action", "search"));
        assertThat(sent).containsEntry("tenantId", "tenant-7");
        assertThat(sent).containsEntry("orgId", "org-7");
        assertThat(sent).containsEntry("orgRole", "ADMIN");
    }

    @Test
    @DisplayName("a transport failure is mapped to an EXECUTION_FAILED result, not thrown")
    void transportFailureMapped() {
        when(catalog.serviceUrlFor("catalog")).thenReturn(CATALOG_URL);
        when(restTemplate.exchange(eq(EXECUTE), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenThrow(new org.springframework.web.client.ResourceAccessException("timeout"));

        ToolExecutionResult result = gateway().execute("catalog", Map.of(), "t1", null, null);

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo(ToolErrorCode.EXECUTION_FAILED);
    }
}
