package com.apimarketplace.catalog.service.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Validates {@link AsyncPollExecutor}:
 * - extracts the job id from the submit response
 * - polls the upstream until a status in successValues
 * - returns the resolved result via resultPath
 * - throws on failure status
 * - throws on timeout
 */
class AsyncPollExecutorTest {

    private RestTemplate restTemplate;
    private ObjectMapper objectMapper;
    private AsyncPollExecutor executor;

    @BeforeEach
    void setUp() {
        restTemplate = mock(RestTemplate.class);
        objectMapper = new ObjectMapper();
        executor = new AsyncPollExecutor(restTemplate, objectMapper);
    }

    private JsonNode asyncCfg() throws Exception {
        return objectMapper.readTree("""
            {
              "submit": {"responseIdPath": "$.id"},
              "poll":   {"method": "GET", "path": "/jobs/{id}", "intervalMs": 250, "maxWaitMs": 5000},
              "status": {"path": "$.status",
                         "successValues": ["completed"],
                         "failureValues": ["failed"]},
              "resultPath": "$.data"
            }
            """);
    }

    @Test
    @DisplayName("polls until success and returns the resolved result")
    void pollUntilSuccess() throws Exception {
        JsonNode cfg = asyncCfg();
        JsonNode submit = objectMapper.readTree("{\"id\":\"job-1\"}");

        // First poll: pending. Second poll: completed with data.
        Map<String, Object> pending = Map.of("status", "pending");
        Map<String, Object> done = new LinkedHashMap<>();
        done.put("status", "completed");
        done.put("data", Map.of("pages", List.of(Map.of("url", "https://x"))));

        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(), eq(Object.class)))
            .thenReturn(ResponseEntity.ok((Object) pending))
            .thenReturn(ResponseEntity.ok((Object) done));

        Object result = executor.pollUntilDone("https://api.test", submit, cfg, new HttpHeaders());
        assertNotNull(result);
        @SuppressWarnings("unchecked")
        Map<String, Object> resultMap = (Map<String, Object>) result;
        assertTrue(resultMap.containsKey("pages"));
    }

    @Test
    @DisplayName("poll path accepts placeholder matching responseIdPath field name")
    void pollPathUsesNamedResponseIdPlaceholder() throws Exception {
        JsonNode cfg = objectMapper.readTree("""
            {
              "submit": {"responseIdPath": "$.request_id"},
              "poll":   {"method": "GET", "path": "/research/{request_id}", "intervalMs": 250, "maxWaitMs": 1000},
              "status": {"path": "$.status", "successValues": ["completed"]},
              "resultPath": "$"
            }
            """);
        JsonNode submit = objectMapper.readTree("{\"request_id\":\"rq-123\"}");
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(), eq(Object.class)))
            .thenReturn(ResponseEntity.ok(Map.of("status", "completed")));

        executor.pollUntilDone("https://api.test", submit, cfg, new HttpHeaders());

        verify(restTemplate).exchange(
            eq(URI.create("https://api.test/research/rq-123")),
            eq(HttpMethod.GET),
            any(),
            eq(Object.class));
    }

    @Test
    @DisplayName("throws when status is in failureValues")
    void failureStatusThrows() throws Exception {
        JsonNode cfg = asyncCfg();
        JsonNode submit = objectMapper.readTree("{\"id\":\"job-2\"}");
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(), eq(Object.class)))
            .thenReturn(ResponseEntity.ok(Map.of("status", "failed")));

        AsyncPollExecutor.AsyncPollFailureException ex = assertThrows(
            AsyncPollExecutor.AsyncPollFailureException.class,
            () -> executor.pollUntilDone("https://api.test", submit, cfg, new HttpHeaders())
        );
        assertTrue(ex.getMessage().contains("failed"));
    }

    @Test
    @DisplayName("throws when no successValues are configured")
    void missingSuccessValues() throws Exception {
        JsonNode cfg = objectMapper.readTree("""
            {"submit":{"responseIdPath":"$.id"},
             "poll":{"method":"GET","path":"/x/{id}","intervalMs":250,"maxWaitMs":1000},
             "status":{"path":"$.status","successValues":[]}}
            """);
        JsonNode submit = objectMapper.readTree("{\"id\":\"j\"}");
        assertThrows(AsyncPollExecutor.AsyncPollFailureException.class,
            () -> executor.pollUntilDone("https://api.test", submit, cfg, new HttpHeaders()));
    }

    @Test
    @DisplayName("throws when job id cannot be extracted")
    void missingJobId() throws Exception {
        JsonNode cfg = asyncCfg();
        JsonNode submit = objectMapper.readTree("{\"foo\":\"bar\"}"); // no .id
        assertThrows(AsyncPollExecutor.AsyncPollFailureException.class,
            () -> executor.pollUntilDone("https://api.test", submit, cfg, new HttpHeaders()));
    }

    @Test
    @DisplayName("returns full body when resultPath is absent")
    void noResultPathReturnsFullBody() throws Exception {
        JsonNode cfg = objectMapper.readTree("""
            {"submit":{"responseIdPath":"$.id"},
             "poll":{"method":"GET","path":"/x/{id}","intervalMs":250,"maxWaitMs":2000},
             "status":{"path":"$.status","successValues":["done"]}}
            """);
        JsonNode submit = objectMapper.readTree("{\"id\":\"j\"}");
        Map<String, Object> body = Map.of("status", "done", "extra", "kept");
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(), eq(Object.class)))
            .thenReturn(ResponseEntity.ok((Object) body));
        Object result = executor.pollUntilDone("https://api.test", submit, cfg, new HttpHeaders());
        @SuppressWarnings("unchecked")
        Map<String, Object> rmap = (Map<String, Object>) result;
        assertEquals("done", rmap.get("status"));
        assertEquals("kept", rmap.get("extra"));
    }
}
