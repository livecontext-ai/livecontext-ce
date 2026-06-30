package com.apimarketplace.agent.provider;

import com.apimarketplace.agent.domain.CompletionRequest;
import com.apimarketplace.agent.domain.CompletionResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 3 regression tests - Retry-After parsing in AbstractLLMProvider.
 *
 * Cross-references:
 * - parseRetryAfter handles Retry-After header, Google body retryDelay (textual + numeric),
 *   and Google message regex "retry in (\\d+(?:\\.\\d+)?)s"
 *
 * Bug this defends against:
 * Run run_<id> (2026-04-29) - Google body had "Please retry in 16.402608741s"
 * but no caller parsed it. After Phase 3, the hint flows into LLMProviderException.retryAfter()
 * and RetryPolicy honors it.
 */
class AbstractLLMProviderRetryAfterTest {

    private TestProvider provider;

    @BeforeEach
    void setUp() {
        provider = new TestProvider();
    }

    @Test
    @DisplayName("Retry-After header (seconds) is parsed")
    void retryAfterHeaderInSecondsParsed() {
        HttpHeaders h = new HttpHeaders();
        h.set("Retry-After", "16");
        Optional<Duration> parsed = provider.parseRetryAfter(h, null);
        assertTrue(parsed.isPresent());
        assertEquals(16, parsed.get().toSeconds());
    }

    @Test
    @DisplayName("malformed Retry-After header falls back to body and yields empty if body has nothing")
    void malformedRetryAfterFallsBackToBackoffWithoutThrowing() {
        HttpHeaders h = new HttpHeaders();
        h.set("Retry-After", "Mon, 01 Jan 2026 00:00:00 GMT");  // HTTP date - not parsed
        Optional<Duration> parsed = provider.parseRetryAfter(h, "{}");
        assertTrue(parsed.isEmpty(), "no fallback hint available");
    }

    @Test
    @DisplayName("Google structured body details[].retryDelay (textual seconds suffix) parsed")
    void httpFourTwoNineWithGoogleStructuredRetryDelayParsedFromBody() {
        String body = """
            {
              "error": {
                "code": 429,
                "message": "Quota exceeded",
                "status": "RESOURCE_EXHAUSTED",
                "details": [
                  {"@type": "type.googleapis.com/google.rpc.RetryInfo", "retryDelay": "16s"}
                ]
              }
            }
            """;
        Optional<Duration> parsed = provider.parseRetryAfter(null, body);
        assertTrue(parsed.isPresent());
        assertEquals(16_000, parsed.get().toMillis());
    }

    @Test
    @DisplayName("Google structured body retryDelay with fractional seconds parsed")
    void googleStructuredRetryDelayFractionalSecondsParsed() {
        String body = """
            {
              "error": {
                "details": [{"retryDelay": "16.4s"}]
              }
            }
            """;
        Optional<Duration> parsed = provider.parseRetryAfter(null, body);
        assertTrue(parsed.isPresent());
        assertEquals(16_400, parsed.get().toMillis());
    }

    @Test
    @DisplayName("Google body message regex 'retry in Xs' parsed when no structured retryDelay")
    void httpFourTwoNineWithGoogleMessageRegexRetryDelayParsedFromBody() {
        String body = """
            {
              "error": {
                "code": 429,
                "message": "You exceeded your current quota. Please retry in 16.402608741s.",
                "status": "RESOURCE_EXHAUSTED"
              }
            }
            """;
        Optional<Duration> parsed = provider.parseRetryAfter(null, body);
        assertTrue(parsed.isPresent());
        assertEquals(16_402, parsed.get().toMillis());
    }

    @Test
    @DisplayName("malformed JSON body never throws - yields empty")
    void malformedJsonBodyNeverThrows() {
        Optional<Duration> parsed = provider.parseRetryAfter(null, "<<not json>>");
        assertTrue(parsed.isEmpty());
    }

    @Test
    @DisplayName("null body and null headers yield empty")
    void nullInputsYieldEmpty() {
        assertTrue(provider.parseRetryAfter(null, null).isEmpty());
        assertTrue(provider.parseRetryAfter(new HttpHeaders(), null).isEmpty());
        assertTrue(provider.parseRetryAfter(null, "").isEmpty());
    }

    @Test
    @DisplayName("header takes priority over body")
    void headerTakesPriorityOverBody() {
        HttpHeaders h = new HttpHeaders();
        h.set("Retry-After", "5");
        String body = """
            {"error": {"details": [{"retryDelay": "60s"}]}}
            """;
        Optional<Duration> parsed = provider.parseRetryAfter(h, body);
        assertTrue(parsed.isPresent());
        assertEquals(5, parsed.get().toSeconds(), "header value wins");
    }

    /**
     * Minimal subclass to expose the package-private parseRetryAfter for testing.
     */
    static class TestProvider extends AbstractLLMProvider {
        @Override public String getProviderName() { return "test"; }
        @Override public boolean isConfigured() { return true; }
        @Override protected String getApiKey() { return "test-key"; }
        @Override protected String getApiUrl() { return "http://localhost"; }
        @Override public String getDefaultModel() { return "test-model"; }
        @Override public List<String> getSupportedModels() { return List.of("test-model"); }
        @Override protected Map<String, Object> buildRequestBody(CompletionRequest request) { return Map.of(); }
        @Override protected HttpHeaders buildHeaders() { return new HttpHeaders(); }
        @Override protected CompletionResponse parseResponse(Map<String, Object> body) { return null; }
        @Override protected String processStreamingLine(String line) { return null; }
    }
}
