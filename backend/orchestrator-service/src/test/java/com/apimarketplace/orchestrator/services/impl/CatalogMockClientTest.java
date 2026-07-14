package com.apimarketplace.orchestrator.services.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CatalogMockClient} - fetching a tool's projected default
 * example from catalog-service's execute-mock endpoint, with gateway-parity
 * flattening, caching, and strict failure surfacing (no silent fallback).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CatalogMockClient - projected catalog example fetching")
class CatalogMockClientTest {

    @Mock private RestTemplate restTemplate;

    private CatalogMockClient client;

    private static final String TOOL_ID = "slack/post-message";
    private static final String BASE_URL = "http://localhost:8081";
    private static final String EXPECTED_URL = BASE_URL + "/catalog/v1/tools/" + TOOL_ID + "/execute-mock";

    @BeforeEach
    void setUp() {
        client = new CatalogMockClient(restTemplate, BASE_URL);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubResponse(Object body) {
        when(restTemplate.exchange(eq(EXPECTED_URL), eq(HttpMethod.POST), any(HttpEntity.class), any(Class.class)))
                .thenAnswer(inv -> {
                    // Re-serialize through Jackson into the client's private DTO type
                    com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                    Class<?> dtoType = inv.getArgument(3);
                    Object dto = mapper.convertValue(body, dtoType);
                    return new ResponseEntity(dto, HttpStatus.OK);
                });
    }

    @Test
    @DisplayName("happy path: result is flattened with gateway-parity envelope keys (tool_id, execution, http_status)")
    void happyPathFlattensLikeGateway() {
        stubResponse(Map.of(
                "success", true,
                "result", Map.of("ok", true, "channel", "C123"),
                "metadata", Map.of("toolName", "post_message", "mock", true,
                        "httpStatus", Map.of("code", 200))));

        Map<String, Object> output = client.fetchProjectedExample(TOOL_ID, "tenant-1");

        assertThat(output)
                .containsEntry("tool_id", TOOL_ID)
                .containsEntry("execution", true)
                .containsEntry("ok", true)
                .containsEntry("channel", "C123")
                .containsEntry("http_status", 200)
                .containsKey("metadata")
                .containsKey("message");
    }

    @Test
    @DisplayName("non-map result lands under the 'result' key (gateway parity for array examples)")
    void arrayResultUnderResultKey() {
        stubResponse(Map.of(
                "success", true,
                "result", List.of(Map.of("id", 1), Map.of("id", 2)),
                "metadata", Map.of()));

        Map<String, Object> output = client.fetchProjectedExample(TOOL_ID, "tenant-1");

        assertThat(output.get("result")).isEqualTo(List.of(Map.of("id", 1), Map.of("id", 2)));
    }

    @Test
    @DisplayName("responses are cached by tool id: second call does not re-fetch, and callers get an isolated top-level map")
    void cachedByToolId() {
        stubResponse(Map.of("success", true, "result", Map.of("ok", true), "metadata", Map.of()));

        Map<String, Object> first = client.fetchProjectedExample(TOOL_ID, "tenant-1");
        first.put("item_index", 3); // caller stamping must not pollute the cache
        Map<String, Object> second = client.fetchProjectedExample(TOOL_ID, "tenant-1");

        verify(restTemplate, times(1)).exchange(any(String.class), any(), any(), any(Class.class));
        assertThat(second).doesNotContainKey("item_index");
    }

    @Test
    @DisplayName("404 from catalog surfaces as MockExampleUnavailableException with an explicit message (no silent fallback)")
    void notFoundThrows() {
        when(restTemplate.exchange(eq(EXPECTED_URL), eq(HttpMethod.POST), any(HttpEntity.class), any(Class.class)))
                .thenThrow(HttpClientErrorException.NotFound.create(
                        HttpStatus.NOT_FOUND, "Not Found", org.springframework.http.HttpHeaders.EMPTY,
                        new byte[0], StandardCharsets.UTF_8));

        assertThatThrownBy(() -> client.fetchProjectedExample(TOOL_ID, "tenant-1"))
                .isInstanceOf(CatalogMockClient.MockExampleUnavailableException.class)
                .hasMessageContaining(TOOL_ID)
                .hasMessageContaining("no default example");
    }

    @Test
    @DisplayName("success=false and transport errors both raise MockExampleUnavailableException")
    void failuresThrow() {
        stubResponse(Map.of("success", false, "error", "boom"));
        assertThatThrownBy(() -> client.fetchProjectedExample(TOOL_ID, "tenant-1"))
                .isInstanceOf(CatalogMockClient.MockExampleUnavailableException.class)
                .hasMessageContaining("boom");

        assertThatThrownBy(() -> client.fetchProjectedExample("other/tool", "tenant-1"))
                .isInstanceOf(CatalogMockClient.MockExampleUnavailableException.class);
    }

    @Test
    @DisplayName("blank tool id is rejected up front")
    void blankToolIdThrows() {
        assertThatThrownBy(() -> client.fetchProjectedExample("  ", "tenant-1"))
                .isInstanceOf(CatalogMockClient.MockExampleUnavailableException.class);
    }
}
