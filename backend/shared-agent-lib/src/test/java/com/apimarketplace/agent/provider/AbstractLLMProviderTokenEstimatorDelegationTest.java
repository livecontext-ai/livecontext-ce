package com.apimarketplace.agent.provider;

import com.apimarketplace.agent.domain.CompletionRequest;
import com.apimarketplace.agent.domain.CompletionResponse;
import com.apimarketplace.agent.tokenizer.TokenEstimator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stage 1a.4 - verify the SPI delegation contract on {@code AbstractLLMProvider}.
 * Two orthogonal guarantees:
 * <ol>
 *   <li>When a {@link TokenEstimator} bean is wired, {@code estimateTokens(req)}
 *   returns the estimator's value verbatim (no silent arithmetic layered on top).</li>
 *   <li>When no bean is wired, it falls back to the legacy chars/4 heuristic
 *   identical to the pre-1a.4 code path - so services without the new bean see
 *   zero behavior change.</li>
 * </ol>
 */
@DisplayName("AbstractLLMProvider - TokenEstimator delegation (Stage 1a.4)")
class AbstractLLMProviderTokenEstimatorDelegationTest {

    /** Minimal concrete provider exposing the protected estimateTokens hooks. */
    static class ProbeProvider extends AbstractLLMProvider {
        @Override protected String getApiKey() { return "k"; }
        @Override protected String getApiUrl() { return "u"; }
        @Override protected Map<String, Object> buildRequestBody(CompletionRequest r) { return Map.of(); }
        @Override protected CompletionResponse parseResponse(Map<String, Object> r) { return CompletionResponse.text(""); }
        @Override protected HttpHeaders buildHeaders() { return new HttpHeaders(); }
        @Override protected String processStreamingLine(String line) { return null; }
        @Override public String getProviderName() { return "probe"; }
        @Override public String getDefaultModel() { return "m"; }
        @Override public List<String> getSupportedModels() { return List.of("m"); }

        int publicEstimate(CompletionRequest r) { return estimateTokens(r); }
        int publicHeuristic(CompletionRequest r) { return estimateTokensHeuristic(r); }
    }

    @Test
    @DisplayName("injected estimator wins - provider returns the estimator's number, not the heuristic")
    void delegatesToInjectedEstimator() {
        AtomicInteger callCount = new AtomicInteger();
        TokenEstimator sentinel = new TokenEstimator() {
            @Override public int estimate(CompletionRequest request) {
                callCount.incrementAndGet();
                return 12345;
            }
            @Override public String name() { return "sentinel"; }
        };

        ProbeProvider provider = new ProbeProvider();
        provider.setTokenEstimator(sentinel);

        int result = provider.publicEstimate(CompletionRequest.builder()
            .model("m").userPrompt("some prompt that would otherwise count").maxTokens(99).build());

        assertThat(result).isEqualTo(12345);
        assertThat(callCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("no estimator wired → falls back to the legacy chars/4 heuristic byte-for-byte")
    void fallsBackWhenEstimatorNull() {
        ProbeProvider provider = new ProbeProvider(); // no setter call

        CompletionRequest req = CompletionRequest.builder()
            .model("m")
            // 60 + 40 = 100 chars → 25 tokens + 200 maxTokens = 225.
            .systemPrompt("s".repeat(60))
            .userPrompt("u".repeat(40))
            .maxTokens(200)
            .build();

        int viaDispatch = provider.publicEstimate(req);
        int viaHeuristicDirect = provider.publicHeuristic(req);

        assertThat(viaDispatch).isEqualTo(viaHeuristicDirect);
        assertThat(viaDispatch).isEqualTo(25 + 200);
    }

    @Test
    @DisplayName("setTokenEstimator(null) is idempotent - provider keeps falling back")
    void explicitNullSetterIsSafe() {
        ProbeProvider provider = new ProbeProvider();
        provider.setTokenEstimator(null);
        CompletionRequest req = CompletionRequest.builder().model("m").maxTokens(42).build();
        assertThat(provider.publicEstimate(req)).isEqualTo(42);
    }
}
