package com.apimarketplace.orchestrator.services.impl;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Client for catalog-service's mock execution endpoint
 * ({@code POST /catalog/v1/tools/{toolId}/execute-mock}): serves a tool's default
 * example response projected through its output schema, shaped exactly like a real
 * execution. Consumed by the per-node mock mode ({@code MockNodeResultFactory})
 * when a node's mock has {@code source='catalog_example'} (or via the run-level
 * {@code all_mcp} fallback).
 *
 * <p>NOT on the {@code ToolsGateway} interface: that seam selects live-vs-E2E
 * EXECUTION; catalog-example fetching is a different axis (per-run, per-node)
 * and must work regardless of which gateway is active.
 *
 * <p>Responses are cached (content-only key: tool id) - examples are effectively
 * static importer-driven data, and a split fanning out over hundreds of items
 * must not fire hundreds of identical HTTP calls.
 *
 * <p>Failures THROW ({@link MockExampleUnavailableException}) and are surfaced as
 * a FAILED node with an explicit message - a mock silently falling back to a
 * fabricated shape would break the "guaranteed output" promise.
 */
@Service
public class CatalogMockClient {

    private static final Logger logger = LoggerFactory.getLogger(CatalogMockClient.class);

    private final RestTemplate restTemplate;
    private final String catalogBaseUrl;
    private final Cache<String, Map<String, Object>> exampleCache;

    /** Raised when the catalog cannot serve a projected example for the tool. */
    public static class MockExampleUnavailableException extends RuntimeException {
        public MockExampleUnavailableException(String message) {
            super(message);
        }

        public MockExampleUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public CatalogMockClient(
            RestTemplate restTemplate,
            @Value("${orchestrator.catalog.base-url:http://localhost:8081}") String catalogBaseUrl) {
        this.restTemplate = restTemplate;
        this.catalogBaseUrl = catalogBaseUrl;
        this.exampleCache = Caffeine.newBuilder()
                .maximumSize(2_000)
                .expireAfterWrite(Duration.ofMinutes(5))
                .build();
    }

    /**
     * Fetches the tool's projected default example and returns it as the flat node
     * output map (same shape as a real {@code CatalogToolsGateway} execution:
     * {@code tool_id, execution, <flattened result fields>, metadata, message,
     * http_status}).
     *
     * @param toolId   tool id as referenced by the workflow step ({@code apiSlug/toolSlug} or UUID)
     * @param tenantId tenant forwarded as {@code X-User-ID} (tracing only - no credentials involved)
     * @throws MockExampleUnavailableException when the example cannot be served
     */
    public Map<String, Object> fetchProjectedExample(String toolId, String tenantId) {
        if (toolId == null || toolId.isBlank()) {
            throw new MockExampleUnavailableException("Tool id is required for a catalog_example mock");
        }
        Map<String, Object> cached = exampleCache.getIfPresent(toolId);
        if (cached != null) {
            return deepCopy(cached);
        }

        String url = catalogBaseUrl + "/catalog/v1/tools/" + toolId + "/execute-mock";
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (tenantId != null && !tenantId.isBlank()) {
                headers.set("X-User-ID", tenantId);
            }
            ResponseEntity<MockExecutionResponse> responseEntity = restTemplate.exchange(
                    url, HttpMethod.POST, new HttpEntity<>(Map.of(), headers), MockExecutionResponse.class);
            MockExecutionResponse response = responseEntity.getBody();
            if (response == null || !response.isSuccess()) {
                throw new MockExampleUnavailableException(
                        "Catalog example unavailable for tool '" + toolId + "'"
                                + (response != null && response.getError() != null ? ": " + response.getError() : ""));
            }

            Integer httpStatus = extractHttpStatus(response.getMetadata());
            Map<String, Object> output = CatalogResultFlattener.flatten(
                    toolId, response.getResult(), response.getMetadata(), httpStatus,
                    "Catalog example served as mock output");

            exampleCache.put(toolId, output);
            return deepCopy(output);
        } catch (MockExampleUnavailableException e) {
            throw e;
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
            throw new MockExampleUnavailableException(
                    "No catalog example exists for tool '" + toolId + "' (tool unknown or no default example row)");
        } catch (Exception e) {
            throw new MockExampleUnavailableException(
                    "Failed to fetch the catalog example for tool '" + toolId + "': " + e.getMessage(), e);
        }
    }

    private static Integer extractHttpStatus(Map<String, Object> metadata) {
        if (metadata == null) return null;
        Object httpStatus = metadata.get("httpStatus");
        if (httpStatus instanceof Map<?, ?> statusMap && statusMap.get("code") instanceof Number code) {
            return code.intValue();
        }
        return null;
    }

    /**
     * Shallow-defensive copy: top level is copied so callers stamping per-item
     * envelope keys (item_index, __mocked__, ...) never mutate the cached entry.
     * Nested values stay shared - the engine treats node outputs as read-only data.
     */
    private static Map<String, Object> deepCopy(Map<String, Object> source) {
        return new HashMap<>(source);
    }

    private static class MockExecutionResponse {
        private boolean success;
        private Object result;
        private String error;
        private Map<String, Object> metadata;

        public boolean isSuccess() { return success; }
        public Object getResult() { return result; }
        public String getError() { return error; }
        public Map<String, Object> getMetadata() { return metadata; }

        public void setSuccess(boolean success) { this.success = success; }
        public void setResult(Object result) { this.result = result; }
        public void setError(String error) { this.error = error; }
        public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
    }
}
