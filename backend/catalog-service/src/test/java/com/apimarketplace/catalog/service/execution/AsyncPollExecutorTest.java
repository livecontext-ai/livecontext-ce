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
import static org.mockito.ArgumentMatchers.anyString;
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

    /**
     * The poll loop now runs the SSRF check on every attempt (LC-006, security audit
     * 2026-08-13). It previously ran none, which is why these tests, whose fixtures use
     * unroutable hosts, started timing out the moment the check was added: each attempt threw
     * and was silently retried until maxWaitMs. Only the DNS-resolving layer is stubbed; the
     * scheme, localhost and placeholder rules stay real.
     */
    private org.mockito.MockedStatic<com.apimarketplace.common.web.UrlSafetyValidator> urlValidator;

    @BeforeEach
    void setUp() {
        urlValidator = org.mockito.Mockito.mockStatic(com.apimarketplace.common.web.UrlSafetyValidator.class);
        restTemplate = mock(RestTemplate.class);
        objectMapper = new ObjectMapper();
        executor = new AsyncPollExecutor(restTemplate, objectMapper);
    }

    @org.junit.jupiter.api.AfterEach
    void releaseUrlValidator() {
        if (urlValidator != null) {
            urlValidator.close();
        }
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
    @DisplayName("regression item 4: a target that turns internal DURING polling stops the loop at once")
    void rebindingDuringPollingStopsImmediately() throws Exception {
        // Pre-fix the target was validated once, before the loop, and then polled for up to ten
        // minutes with the submit call's credential headers. Here the first attempt passes, then
        // the name "rebinds": the next attempt's check refuses.
        urlValidator.when(() -> com.apimarketplace.common.web.UrlSafetyValidator.validateEgressUrl(anyString()))
                .thenReturn(null)
                .thenThrow(new IllegalArgumentException("Requests to private/internal network addresses are not allowed"));
        JsonNode cfg = objectMapper.readTree("""
            {"submit":{"responseIdPath":"$.id"},
             "poll":{"method":"GET","path":"/x/{id}?token=SECRET-TOKEN","intervalMs":250,"maxWaitMs":5000},
             "status":{"path":"$.status","successValues":["done"]}}
            """);
        JsonNode submit = objectMapper.readTree("{\"id\":\"j\"}");
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(), eq(Object.class)))
            .thenReturn(ResponseEntity.ok((Object) Map.of("status", "running")));

        AsyncPollExecutor.AsyncPollFailureException e = assertThrows(AsyncPollExecutor.AsyncPollFailureException.class,
            () -> executor.pollUntilDone("https://api.test", submit, cfg, new HttpHeaders()));

        verify(restTemplate, org.mockito.Mockito.times(1))
            .exchange(any(URI.class), any(HttpMethod.class), any(), eq(Object.class));
        assertFalse(e.getMessage().contains("SECRET-TOKEN"), "item 7: the refusal must not echo the query: " + e.getMessage());
    }

    /** Fake time for the DNS retry tests: every pause is recorded and advances the clock. */
    private final long[] fakeNow = {0};
    private final java.util.List<Long> pauses = new java.util.ArrayList<>();

    private void onFakeTime() {
        executor.setTimeSource(() -> fakeNow[0], ms -> {
            pauses.add(ms);
            fakeNow[0] += ms;
        });
    }

    /** One minute of patience, polled every 250 ms. */
    private JsonNode minuteCfg() throws Exception {
        return objectMapper.readTree("""
            {
              "submit": {"responseIdPath": "$.id"},
              "poll":   {"method": "GET", "path": "/jobs/{id}", "intervalMs": 250, "maxWaitMs": 60000},
              "status": {"path": "$.status", "successValues": ["completed"], "failureValues": ["failed"]},
              "resultPath": "$.data"
            }
            """);
    }

    private void verifyValidatorCalls(int times) {
        urlValidator.verify(() -> com.apimarketplace.common.web.UrlSafetyValidator.validateEgressUrl(anyString()),
                org.mockito.Mockito.times(times));
    }

    /**
     * Regression review 2026-09-29: the per-attempt check made ANY failed lookup final, so one DNS
     * incident during minutes of polling abandoned a job the upstream had already accepted and the
     * user had already paid for (video / image generation).
     */
    @Test
    @DisplayName("regression 2026-09-29: a poll host that briefly stops resolving is retried, the paid job completes")
    void transientResolutionFailureDuringPollingIsRetried() throws Exception {
        onFakeTime();
        urlValidator.when(() -> com.apimarketplace.common.web.UrlSafetyValidator.validateEgressUrl(anyString()))
                .thenThrow(new com.apimarketplace.common.web.UnresolvableHostException("Cannot resolve hostname: api.test"))
                .thenThrow(new com.apimarketplace.common.web.UrlResolutionException("DNS resolution timed out for hostname: api.test"))
                .thenReturn(null);
        JsonNode submit = objectMapper.readTree("{\"id\":\"job-7\"}");
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(), eq(Object.class)))
            .thenReturn(ResponseEntity.ok((Object) Map.of("status", "completed", "data", Map.of("video", "v.mp4"))));

        Object result = executor.pollUntilDone("https://api.test", submit, minuteCfg(), new HttpHeaders());

        assertEquals(Map.of("video", "v.mp4"), result);
        // The request goes out only on the attempt whose host resolved (the 3rd check), never on
        // the two that did not.
        verifyValidatorCalls(3);
        verify(restTemplate, org.mockito.Mockito.times(1))
            .exchange(any(URI.class), any(HttpMethod.class), any(), eq(Object.class));
        // Each failed lookup is followed by the DNS back-off, not the 250 ms poll interval.
        assertEquals(java.util.List.of(AsyncPollExecutor.UNRESOLVED_RETRY_MS, AsyncPollExecutor.UNRESOLVED_RETRY_MS), pauses);
    }

    @Test
    @DisplayName("a poll host that never resolves stops at maxWaitMs, having sent nothing and asked DNS every 10 s")
    void neverResolvingHostStopsAtTheDeadline() throws Exception {
        onFakeTime();
        urlValidator.when(() -> com.apimarketplace.common.web.UrlSafetyValidator.validateEgressUrl(anyString()))
                .thenThrow(new com.apimarketplace.common.web.UnresolvableHostException("Cannot resolve hostname: api.test"));
        JsonNode submit = objectMapper.readTree("{\"id\":\"job-9\"}");

        AsyncPollExecutor.AsyncPollFailureException e = assertThrows(AsyncPollExecutor.AsyncPollFailureException.class,
            () -> executor.pollUntilDone("https://api.test", submit, minuteCfg(), new HttpHeaders()));

        assertTrue(e.getMessage().contains("exceeded maxWaitMs"), e.getMessage());
        verify(restTemplate, org.mockito.Mockito.never())
            .exchange(any(URI.class), any(HttpMethod.class), any(), eq(Object.class));
        // 60 s of patience = seven lookups 10 s apart, the last one AT the deadline, never a hot loop.
        assertEquals(java.util.Collections.nCopies(6, AsyncPollExecutor.UNRESOLVED_RETRY_MS), pauses);
        verifyValidatorCalls(7);
    }

    @Test
    @DisplayName("a first lookup that fails is not a refusal: the next attempt completes the job")
    void unresolvedFirstAttemptIsRetried() throws Exception {
        onFakeTime();
        urlValidator.when(() -> com.apimarketplace.common.web.UrlSafetyValidator.validateEgressUrl(anyString()))
                .thenThrow(new com.apimarketplace.common.web.UnresolvableHostException("Cannot resolve hostname: api.test"))
                .thenReturn(null);
        JsonNode submit = objectMapper.readTree("{\"id\":\"job-7\"}");
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(), eq(Object.class)))
            .thenReturn(ResponseEntity.ok((Object) Map.of("status", "completed", "data", Map.of("ok", true))));

        assertEquals(Map.of("ok", true),
            executor.pollUntilDone("https://api.test", submit, minuteCfg(), new HttpHeaders()));
    }

    @Test
    @DisplayName("a first lookup that times out is not a refusal either (the lookup-timeout exception)")
    void lookupTimeoutFirstAttemptIsRetried() throws Exception {
        onFakeTime();
        urlValidator.when(() -> com.apimarketplace.common.web.UrlSafetyValidator.validateEgressUrl(anyString()))
                .thenThrow(new com.apimarketplace.common.web.UrlResolutionException("DNS resolution timed out for hostname: api.test"))
                .thenReturn(null);
        JsonNode submit = objectMapper.readTree("{\"id\":\"job-8\"}");
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(), eq(Object.class)))
            .thenReturn(ResponseEntity.ok((Object) Map.of("status", "completed", "data", Map.of("ok", true))));

        assertEquals(Map.of("ok", true),
            executor.pollUntilDone("https://api.test", submit, minuteCfg(), new HttpHeaders()));
    }

    @Test
    @DisplayName("the last pause is cut to the deadline and one last attempt runs AT it: a host back by then completes the job")
    void lastAttemptRunsAtTheDeadline() throws Exception {
        onFakeTime();
        urlValidator.when(() -> com.apimarketplace.common.web.UrlSafetyValidator.validateEgressUrl(anyString()))
                .thenThrow(new com.apimarketplace.common.web.UnresolvableHostException("Cannot resolve hostname: api.test"))
                .thenThrow(new com.apimarketplace.common.web.UnresolvableHostException("Cannot resolve hostname: api.test"))
                .thenThrow(new com.apimarketplace.common.web.UnresolvableHostException("Cannot resolve hostname: api.test"))
                .thenReturn(null);
        JsonNode cfg = objectMapper.readTree("""
            {"submit":{"responseIdPath":"$.id"},
             "poll":{"method":"GET","path":"/jobs/{id}","intervalMs":250,"maxWaitMs":25000},
             "status":{"path":"$.status","successValues":["completed"]},
             "resultPath":"$.data"}
            """);
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(), eq(Object.class)))
            .thenReturn(ResponseEntity.ok((Object) Map.of("status", "completed", "data", Map.of("ok", true))));

        assertEquals(Map.of("ok", true),
            executor.pollUntilDone("https://api.test", objectMapper.readTree("{\"id\":\"j\"}"), cfg, new HttpHeaders()));

        // Attempts at 0, 10 s, 20 s and 25 s: the third pause stops at the deadline, never past it.
        assertEquals(java.util.List.of(10_000L, 10_000L, 5_000L), pauses);
    }

    /** A job that stays pending: polled every 10 s, 25 s of patience. */
    private JsonNode pendingCfg() throws Exception {
        return objectMapper.readTree("""
            {"submit":{"responseIdPath":"$.id"},
             "poll":{"method":"GET","path":"/jobs/{id}","intervalMs":10000,"maxWaitMs":25000},
             "status":{"path":"$.status","successValues":["completed"]},
             "resultPath":"$.data"}
            """);
    }

    @Test
    @DisplayName("a job still pending at maxWaitMs times out, after a last poll AT the deadline (the resolved-host path)")
    void pendingJobTimesOutAfterALastPollAtTheDeadline() throws Exception {
        onFakeTime();
        urlValidator.when(() -> com.apimarketplace.common.web.UrlSafetyValidator.validateEgressUrl(anyString()))
                .thenReturn(null);
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(), eq(Object.class)))
            .thenReturn(ResponseEntity.ok((Object) Map.of("status", "running")));

        AsyncPollExecutor.AsyncPollFailureException thrown = assertThrows(AsyncPollExecutor.AsyncPollFailureException.class,
            () -> executor.pollUntilDone("https://api.test", objectMapper.readTree("{\"id\":\"j\"}"), pendingCfg(), new HttpHeaders()));

        assertTrue(thrown.getMessage().contains("exceeded maxWaitMs=25000ms"), thrown.getMessage());
        // Polls at 0, 10 s, 20 s and 25 s: the last interval is cut to the deadline, never past it.
        assertEquals(java.util.List.of(10_000L, 10_000L, 5_000L), pauses);
        verify(restTemplate, org.mockito.Mockito.times(4))
            .exchange(any(URI.class), any(HttpMethod.class), any(), eq(Object.class));
    }

    @Test
    @DisplayName("an interrupt during the last lookup is reported as an interrupt, not as a timeout")
    void interruptDuringTheLastLookupIsReportedAsSuch() throws Exception {
        onFakeTime();
        urlValidator.when(() -> com.apimarketplace.common.web.UrlSafetyValidator.validateEgressUrl(anyString()))
                .thenAnswer(inv -> {
                    // What an interrupted lookup does: it keeps the flag and reports a failed lookup.
                    Thread.currentThread().interrupt();
                    throw new com.apimarketplace.common.web.UrlResolutionException("DNS resolution interrupted for hostname: api.test");
                });

        try {
            AsyncPollExecutor.AsyncPollFailureException thrown = assertThrows(AsyncPollExecutor.AsyncPollFailureException.class,
                () -> executor.pollUntilDone("https://api.test", objectMapper.readTree("{\"id\":\"j\"}"), pendingCfg(), new HttpHeaders()));

            assertTrue(thrown.getMessage().contains("interrupted"), thrown.getMessage());
            // Nothing more is attempted, and the flag is kept for the caller.
            assertTrue(pauses.isEmpty());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    @DisplayName("an interrupt during a pause ends the poll as an interrupt and keeps the flag")
    void interruptDuringAPauseIsReportedAsSuch() throws Exception {
        executor.setTimeSource(() -> 0L, ms -> { throw new InterruptedException("stop"); });
        urlValidator.when(() -> com.apimarketplace.common.web.UrlSafetyValidator.validateEgressUrl(anyString()))
                .thenReturn(null);
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(), eq(Object.class)))
            .thenReturn(ResponseEntity.ok((Object) Map.of("status", "running")));

        try {
            AsyncPollExecutor.AsyncPollFailureException thrown = assertThrows(AsyncPollExecutor.AsyncPollFailureException.class,
                () -> executor.pollUntilDone("https://api.test", objectMapper.readTree("{\"id\":\"j\"}"), pendingCfg(), new HttpHeaders()));

            assertTrue(thrown.getMessage().contains("interrupted"), thrown.getMessage());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    @DisplayName("regression LC-006: an internal poll target is refused before any request")
    void internalPollTargetRefused() throws Exception {
        // The real validator, not the stub: the poll carries the submit call's credential headers.
        urlValidator.close();
        urlValidator = null;
        JsonNode cfg = asyncCfg();
        JsonNode submit = objectMapper.readTree("{\"id\":\"j\"}");

        assertThrows(AsyncPollExecutor.AsyncPollFailureException.class,
            () -> executor.pollUntilDone("http://10.0.0.5:8083", submit, cfg, new HttpHeaders()));
        verify(restTemplate, org.mockito.Mockito.never())
            .exchange(any(URI.class), any(HttpMethod.class), any(), eq(Object.class));
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
