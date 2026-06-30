package com.apimarketplace.agent.provider;

import com.apimarketplace.agent.domain.CompletionRequest;
import com.apimarketplace.agent.domain.CompletionResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for the finite read timeout applied to the non-streaming complete() path.
 *
 * <p>A plain {@code new RestTemplate()} uses {@link SimpleClientHttpRequestFactory} with
 * {@code readTimeout = -1} (infinite), so before the fix a provider that accepted the request then
 * went silent hung the calling agent-loop thread forever. The owned RestTemplate must now carry a
 * finite read timeout; a caller-supplied RestTemplate must be left untouched.</p>
 */
@DisplayName("AbstractLLMProvider - owned RestTemplate read timeout")
class AbstractLLMProviderTimeoutTest {

    /** Minimal concrete provider exposing both constructors. */
    static class TimeoutTestProvider extends AbstractLLMProvider {
        TimeoutTestProvider() {
            super();
        }

        TimeoutTestProvider(RestTemplate restTemplate, ObjectMapper objectMapper) {
            super(restTemplate, objectMapper);
        }

        @Override protected String getApiKey() { return "k"; }
        @Override protected String getApiUrl() { return "https://api.test.com/v1/chat"; }
        @Override protected Map<String, Object> buildRequestBody(CompletionRequest request) { return Map.of(); }
        @Override protected CompletionResponse parseResponse(Map<String, Object> response) { return CompletionResponse.text("ok"); }
        @Override protected HttpHeaders buildHeaders() { return new HttpHeaders(); }
        @Override protected String processStreamingLine(String line) { return null; }
        @Override public String getProviderName() { return "test"; }
        @Override public String getDefaultModel() { return "test-model"; }
        @Override public List<String> getSupportedModels() { return List.of("test-model"); }
    }

    /** Read the private int readTimeout (ms) off a SimpleClientHttpRequestFactory. */
    private static int readTimeoutMs(RestTemplate restTemplate) throws Exception {
        ClientHttpRequestFactory factory = restTemplate.getRequestFactory();
        assertThat(factory).isInstanceOf(SimpleClientHttpRequestFactory.class);
        Field field = SimpleClientHttpRequestFactory.class.getDeclaredField("readTimeout");
        field.setAccessible(true);
        return field.getInt(factory);
    }

    @Test
    @DisplayName("no-arg provider bounds its OWN RestTemplate with a finite read timeout (default 1h), not the infinite -1")
    void ownedRestTemplateGetsFiniteReadTimeout() throws Exception {
        TimeoutTestProvider provider = new TimeoutTestProvider();

        int readTimeout = readTimeoutMs(provider.restTemplate);

        // Pre-fix the Spring default request factory readTimeout is -1 (infinite); the fix makes it
        // the finite ai.agent.llm.read-timeout-ms default (3_600_000 ms).
        assertThat(readTimeout)
            .as("owned RestTemplate must no longer have an infinite read timeout")
            .isGreaterThan(0)
            .isEqualTo(3_600_000);
    }

    @Test
    @DisplayName("caller-supplied RestTemplate is left untouched - its request factory is not clobbered")
    void suppliedRestTemplateIsNotModified() {
        SimpleClientHttpRequestFactory custom = new SimpleClientHttpRequestFactory();
        custom.setReadTimeout(Duration.ofSeconds(7));
        RestTemplate supplied = new RestTemplate(custom);

        TimeoutTestProvider provider = new TimeoutTestProvider(supplied, new ObjectMapper());

        assertThat(provider.restTemplate.getRequestFactory())
            .as("a provider given an external RestTemplate must not replace its request factory")
            .isSameAs(custom);
    }

    @Test
    @DisplayName("@Value override is honored when @PostConstruct re-applies the timeouts")
    void postConstructAppliesInjectedReadTimeout() throws Exception {
        TimeoutTestProvider provider = new TimeoutTestProvider();

        // Simulate Spring injecting a custom property then firing @PostConstruct.
        ReflectionTestUtils.setField(provider, "llmConnectTimeoutMs", 5_000L);
        ReflectionTestUtils.setField(provider, "llmReadTimeoutMs", 120_000L);
        provider.applyOwnedRestTemplateTimeouts();

        assertThat(readTimeoutMs(provider.restTemplate)).isEqualTo(120_000);
    }

    @Test
    @DisplayName("streamingReadTimeoutMs = the inactivity window itself, capped by the read timeout, floored at 1s")
    void streamingReadTimeoutCadence() {
        // Default 5-min window, 1h read timeout -> socket times out after the full 5-min window of
        // silence (ONE timeout per window, not a 15s poll - keeps the mid-line readLine risk negligible).
        assertThat(AbstractLLMProvider.streamingReadTimeoutMs(3_600_000L, 5 * 60 * 1000L)).isEqualTo(300_000L);
        // A short window is honored as-is.
        assertThat(AbstractLLMProvider.streamingReadTimeoutMs(3_600_000L, 5_000L)).isEqualTo(5_000L);
        // The configured read timeout still caps it (never wait longer than the read timeout).
        assertThat(AbstractLLMProvider.streamingReadTimeoutMs(2_000L, 300_000L)).isEqualTo(2_000L);
        // Never below the 1s floor, even for a tiny window.
        assertThat(AbstractLLMProvider.streamingReadTimeoutMs(3_600_000L, 500L)).isEqualTo(1_000L);
    }
}
