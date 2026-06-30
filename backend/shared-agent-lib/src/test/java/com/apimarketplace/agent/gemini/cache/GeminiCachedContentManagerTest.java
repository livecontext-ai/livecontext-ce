package com.apimarketplace.agent.gemini.cache;

import com.apimarketplace.agent.domain.ToolDefinition;
import com.apimarketplace.agent.domain.ToolParameter;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Stage 2 wiring - pin the lifecycle of {@link GeminiCachedContentManager}
 * against a mocked {@link RestTemplate}. The manager must:
 *
 * <ul>
 *   <li>Short-circuit when {@link GeminiCacheProperties#isEnabled() enabled=false}
 *       so disabled deployments don't pay any HTTP or log overhead.</li>
 *   <li>Short-circuit when the eligibility gate rejects the prefix
 *       (below {@link GeminiCacheGate#FLASH_MIN_CACHED_TOKENS token floor}
 *       or below the configured traffic floor) - Google's API will
 *       reject sub-floor prefixes with 400 so there's no point trying.</li>
 *   <li>Reuse an existing in-memory entry on the second call with the
 *       same {@code (systemBlock, tools)} - the whole point of the
 *       cache is to skip the upload.</li>
 *   <li>POST to the configured endpoint on a miss, parse the returned
 *       {@code name}, and make it available on the next call.</li>
 *   <li>Swallow HTTP failures and return empty so the chat turn
 *       continues uncached rather than erroring.</li>
 * </ul>
 */
@DisplayName("GeminiCachedContentManager - Stage 2 lifecycle")
@ExtendWith(MockitoExtension.class)
class GeminiCachedContentManagerTest {

    @Mock private RestTemplate restTemplate;

    private GeminiCacheProperties properties;
    private GeminiCachedContentManager manager;

    @BeforeEach
    void setUp() {
        properties = new GeminiCacheProperties();
        properties.setEnabled(true);
        properties.setMinReqPerHour(0);
        properties.setTtlSeconds(3600L);
        manager = new GeminiCachedContentManager(properties, restTemplate);
    }

    private String prefix(int chars) {
        return "x".repeat(Math.max(0, chars));
    }

    private List<ToolDefinition> tools() {
        return List.of(ToolDefinition.builder()
                .name("agent")
                .description("full description")
                .parameters(List.of(ToolParameter.builder().name("action").type("string").build()))
                .build());
    }

    @Test
    @DisplayName("enabled=false short-circuits without any HTTP call")
    void disabledShortCircuits() {
        properties.setEnabled(false);

        Optional<String> out = manager.getOrCreate(
                "key-123", "gemini-1.5-flash", prefix(8000), tools(), 2000);

        assertThat(out).isEmpty();
        verify(restTemplate, never()).exchange(anyString(), any(), any(), eq(Map.class));
    }

    @Test
    @DisplayName("missing API key short-circuits")
    void missingApiKeyShortCircuits() {
        Optional<String> out = manager.getOrCreate(
                "", "gemini-1.5-flash", prefix(8000), tools(), 2000);

        assertThat(out).isEmpty();
        verify(restTemplate, never()).exchange(anyString(), any(), any(), eq(Map.class));
    }

    @Test
    @DisplayName("below Flash token floor → gate rejects, no HTTP")
    void belowTokenFloorSkips() {
        Optional<String> out = manager.getOrCreate(
                "key-123", "gemini-1.5-flash", prefix(200), tools(), 500);

        assertThat(out).isEmpty();
        verify(restTemplate, never()).exchange(anyString(), any(), any(), eq(Map.class));
    }

    @Test
    @DisplayName("below traffic floor → gate rejects, no HTTP")
    void belowTrafficFloorSkips() {
        properties.setMinReqPerHour(5);

        Optional<String> out = manager.getOrCreate(
                "key-123", "gemini-1.5-flash", prefix(8000), tools(), 2000);

        assertThat(out).isEmpty();
        verify(restTemplate, never()).exchange(anyString(), any(), any(), eq(Map.class));
    }

    @Test
    @DisplayName("cache miss: POSTs to endpoint, parses name, returns it")
    void cacheMissPostsAndReturnsName() {
        when(restTemplate.exchange(contains("cachedContents"), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of("name", "cachedContents/abc-xyz")));

        Optional<String> out = manager.getOrCreate(
                "key-123", "gemini-1.5-flash", prefix(8000), tools(), 2000);

        assertThat(out).contains("cachedContents/abc-xyz");
        verify(restTemplate, times(1)).exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class));
    }

    @Test
    @DisplayName("cache hit on second call with same prefix: no second POST")
    void cacheHitSkipsHttp() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of("name", "cachedContents/abc-xyz")));

        Optional<String> first = manager.getOrCreate(
                "key-123", "gemini-1.5-flash", prefix(8000), tools(), 2000);
        Optional<String> second = manager.getOrCreate(
                "key-123", "gemini-1.5-flash", prefix(8000), tools(), 2000);

        assertThat(first).contains("cachedContents/abc-xyz");
        assertThat(second).contains("cachedContents/abc-xyz");
        verify(restTemplate, times(1)).exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class));
    }

    @Test
    @DisplayName("different system prefixes produce different keys → separate POSTs")
    void differentPrefixesForkKeys() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of("name", "cachedContents/one")))
                .thenReturn(ResponseEntity.ok(Map.of("name", "cachedContents/two")));

        Optional<String> a = manager.getOrCreate(
                "key-123", "gemini-1.5-flash", prefix(8000), tools(), 2000);
        Optional<String> b = manager.getOrCreate(
                "key-123", "gemini-1.5-flash", prefix(8000) + "MORE", tools(), 2000);

        assertThat(a).contains("cachedContents/one");
        assertThat(b).contains("cachedContents/two");
        verify(restTemplate, times(2)).exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class));
    }

    @Test
    @DisplayName("HTTP non-2xx swallowed: returns empty, no exception bubbles")
    void httpNon2xxSwallowed() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(Map.of("error", "boom"), HttpStatus.BAD_REQUEST));

        Optional<String> out = manager.getOrCreate(
                "key-123", "gemini-1.5-flash", prefix(8000), tools(), 2000);

        assertThat(out).isEmpty();
    }

    @Test
    @DisplayName("HTTP exception swallowed: returns empty so chat turn continues")
    void httpExceptionSwallowed() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenThrow(new RestClientException("connect reset"));

        Optional<String> out = manager.getOrCreate(
                "key-123", "gemini-1.5-flash", prefix(8000), tools(), 2000);

        assertThat(out).isEmpty();
    }

    @Test
    @DisplayName("malformed response (no name field) treated as miss, returns empty")
    void malformedResponseIsMiss() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of("something-else", "value")));

        Optional<String> out = manager.getOrCreate(
                "key-123", "gemini-1.5-flash", prefix(8000), tools(), 2000);

        assertThat(out).isEmpty();
    }

    @Test
    @DisplayName("Pro model enforces higher token floor (4096)")
    void proModelRequiresHigherFloor() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of("name", "cachedContents/pro")));

        // 3000 tokens < 4096 Pro floor → gate rejects.
        Optional<String> rejected = manager.getOrCreate(
                "key-123", "gemini-1.5-pro", prefix(12000), tools(), 3000);
        assertThat(rejected).isEmpty();
        verify(restTemplate, never()).exchange(anyString(), any(), any(), eq(Map.class));

        // 5000 tokens ≥ 4096 → accepted.
        Optional<String> accepted = manager.getOrCreate(
                "key-123", "gemini-1.5-pro", prefix(20000), tools(), 5000);
        assertThat(accepted).contains("cachedContents/pro");
    }

    @Test
    @DisplayName("tiny ttl + safety margin clamps local expiry to >= 1s (no negative durations)")
    void tinyTtlClampsLocalExpiry() {
        // 30s ttl minus 60s safety margin would go negative; the manager
        // must clamp to 1s so Duration.ofSeconds never receives a
        // non-positive value (which would immediately expire on insert).
        properties.setTtlSeconds(30L);
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of("name", "cachedContents/tiny")));

        Optional<String> first = manager.getOrCreate(
                "key-123", "gemini-1.5-flash", prefix(8000), tools(), 2000);
        Optional<String> second = manager.getOrCreate(
                "key-123", "gemini-1.5-flash", prefix(8000), tools(), 2000);

        // First call POSTs; second call finds the live (clamped-positive) entry
        // and reuses it - proving the clamp didn't produce an already-expired insert.
        assertThat(first).contains("cachedContents/tiny");
        assertThat(second).contains("cachedContents/tiny");
        verify(restTemplate, times(1)).exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class));
    }

    @Test
    @DisplayName("api key travels as x-goog-api-key header, never in the URL")
    void apiKeyInHeaderNotUrl() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of("name", "cachedContents/hdr")));

        manager.getOrCreate("secret-key-123", "gemini-1.5-flash", prefix(8000), tools(), 2000);

        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(urlCaptor.capture(), eq(HttpMethod.POST),
                entityCaptor.capture(), eq(Map.class));

        assertThat(urlCaptor.getValue()).doesNotContain("secret-key-123");
        assertThat(urlCaptor.getValue()).doesNotContain("?key=");
        assertThat(entityCaptor.getValue().getHeaders().getFirst("x-goog-api-key"))
                .isEqualTo("secret-key-123");
    }

    // ---- sweeper --------------------------------------------------------

    /**
     * Mutable clock wrapper so a single manager instance can travel in time
     * across a test. Replaces {@code Thread.sleep} - deterministic and
     * CI-independent.
     */
    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;
        MutableClock(Instant start) { this.now = new AtomicReference<>(start); }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
        void advance(Duration d) { now.updateAndGet(i -> i.plus(d)); }
    }

    @Test
    @DisplayName("sweep() runs regardless of enabled=false - evicts stale entries so operator toggle doesn't leak")
    void sweepRunsEvenWhenDisabled() {
        // Regression guard: previous iteration gated sweep() on
        // properties.isEnabled(). That re-created the leak the sweeper
        // exists to fix: if the operator disables the feature while
        // entries are in memory, those entries must still be evictable.
        MutableClock mclock = new MutableClock(Instant.parse("2026-04-21T00:00:00Z"));
        properties.setTtlSeconds(90L); // → clamped local expiry = 30s
        GeminiCachedContentManager m = new GeminiCachedContentManager(properties, restTemplate, mclock);
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of("name", "cachedContents/live")))
                .thenReturn(ResponseEntity.ok(Map.of("name", "cachedContents/after")));

        assertThat(m.getOrCreate("k", "gemini-1.5-flash", prefix(8000), tools(), 2000))
                .contains("cachedContents/live");

        // Advance past local expiry AND flip enabled off.
        mclock.advance(Duration.ofSeconds(31));
        properties.setEnabled(false);
        m.sweep();

        // Re-enable - a subsequent getOrCreate must POST again (proves
        // the disabled-sweep evicted the expired entry).
        properties.setEnabled(true);
        assertThat(m.getOrCreate("k", "gemini-1.5-flash", prefix(8000), tools(), 2000))
                .contains("cachedContents/after");
        verify(restTemplate, times(2)).exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class));
    }

    @Test
    @DisplayName("sweep() evicts locally-expired entries so a new POST fires on next getOrCreate")
    void sweepEvictsExpiredEntries() {
        // ttl=90s minus 60s safety margin → 30s local expiry. Clock-driven
        // so the test is deterministic regardless of CI load.
        MutableClock mclock = new MutableClock(Instant.parse("2026-04-21T00:00:00Z"));
        properties.setTtlSeconds(90L);
        GeminiCachedContentManager m = new GeminiCachedContentManager(properties, restTemplate, mclock);
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of("name", "cachedContents/first")))
                .thenReturn(ResponseEntity.ok(Map.of("name", "cachedContents/second")));

        assertThat(m.getOrCreate("k", "gemini-1.5-flash", prefix(8000), tools(), 2000))
                .contains("cachedContents/first");

        // Jump past expiry; sweep should evict; next call re-POSTs.
        mclock.advance(Duration.ofSeconds(31));
        m.sweep();

        assertThat(m.getOrCreate("k", "gemini-1.5-flash", prefix(8000), tools(), 2000))
                .contains("cachedContents/second");
        verify(restTemplate, times(2)).exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class));
    }

    @Test
    @DisplayName("sweep() keeps live entries - non-expired entries survive the pass")
    void sweepKeepsLiveEntries() {
        // Default ttl=3600s → local expiry is ~3540s from now; one sweep
        // immediately after insert must NOT evict the entry.
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of("name", "cachedContents/live")));

        manager.getOrCreate("k", "gemini-1.5-flash", prefix(8000), tools(), 2000);
        manager.sweep();

        assertThat(manager.getOrCreate("k", "gemini-1.5-flash", prefix(8000), tools(), 2000))
                .contains("cachedContents/live");
        verify(restTemplate, times(1)).exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class));
    }

    @Test
    @DisplayName("sweep() evicts stale trafficByKey hour-buckets so low-churn keys don't leak counters")
    void sweepEvictsStaleTrafficBuckets() {
        // Each getOrCreate tick creates/updates a HourBucket entry. After
        // the current hour rolls over by > TRAFFIC_BUCKET_STALE_HOURS (2h),
        // the bucket is dead weight - the gate only reads the CURRENT hour.
        // A pod that ran a cache miss on key X 4 hours ago and never saw
        // X again would leak that bucket forever without the sweeper.
        MutableClock mclock = new MutableClock(Instant.parse("2026-04-21T00:00:00Z"));
        GeminiCachedContentManager m = new GeminiCachedContentManager(properties, restTemplate, mclock);
        // Disable so we don't need to mock the HTTP path - tickTraffic
        // runs first inside getOrCreate, but gate-reject still populates
        // the bucket. Use the below-token-floor path to reject cleanly.
        Optional<String> first = m.getOrCreate("k", "gemini-1.5-flash", prefix(200), tools(), 500);
        assertThat(first).isEmpty();
        verify(restTemplate, never()).exchange(anyString(), any(), any(), eq(Map.class));

        // Advance 3 hours - bucket is now stale (> 2h).
        mclock.advance(Duration.ofHours(3));
        m.sweep();

        // Touch the same key again: it must produce hour-bucket counter=1
        // (not 2), proving the old bucket was evicted and a fresh one
        // was created. The gate rejection hides this directly, but we
        // can observe it via minReqPerHour: with floor=2 the new call
        // still rejects (since the counter resets to 1).
        properties.setMinReqPerHour(2);
        Optional<String> second = m.getOrCreate("k", "gemini-1.5-flash", prefix(8000), tools(), 2000);
        assertThat(second).as("gate should reject because counter reset to 1 < floor 2").isEmpty();
        verify(restTemplate, never()).exchange(anyString(), any(), any(), eq(Map.class));
    }

    @Test
    @DisplayName("sweep() is idempotent on empty maps - no exceptions, no HTTP")
    void sweepEmptyIsIdempotent() {
        manager.sweep();
        manager.sweep();
        verify(restTemplate, never()).exchange(anyString(), any(), any(), eq(Map.class));
    }

    @Test
    @DisplayName("POST body carries model prefix, ttl string, and systemInstruction")
    void postBodyShape() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of("name", "cachedContents/ok")));

        manager.getOrCreate("key-123", "gemini-1.5-flash", prefix(8000), tools(), 2000);

        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<HttpEntity> captor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(anyString(), eq(HttpMethod.POST), captor.capture(), eq(Map.class));

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) captor.getValue().getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("model")).isEqualTo("models/gemini-1.5-flash");
        assertThat(body.get("ttl")).isEqualTo("3600s");
        assertThat(body.get("systemInstruction")).isNotNull();
        assertThat(body.get("tools")).isNotNull();
    }
}
