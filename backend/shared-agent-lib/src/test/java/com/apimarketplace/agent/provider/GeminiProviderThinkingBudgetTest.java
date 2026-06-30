package com.apimarketplace.agent.provider;

import com.apimarketplace.agent.domain.CompletionRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stage 1b.1 - {@code thinkingBudget} is the primary knob for controlling
 * Gemini reasoning-token spend (billed as OUTPUT, ~4× the input price).
 * <ul>
 *   <li>{@code null} → key omitted, Gemini keeps its dynamic default.</li>
 *   <li>{@code 0} → thinking disabled (fast-path tool routing, extraction).</li>
 *   <li>Positive → hard cap on reasoning tokens.</li>
 * </ul>
 * Contract: the field only flows into Gemini 2.5/3.x payloads; older models
 * continue to receive no {@code thinkingConfig} at all.
 */
@DisplayName("GeminiProvider - thinkingBudget (Stage 1b.1)")
class GeminiProviderThinkingBudgetTest {

    private final GeminiProvider provider = new GeminiProvider();

    @SuppressWarnings("unchecked")
    private Map<String, Object> thinkingConfigFor(CompletionRequest request) {
        Map<String, Object> body = provider.buildRequestBody(request);
        Map<String, Object> generationConfig = (Map<String, Object>) body.get("generationConfig");
        if (generationConfig == null) return null;
        return (Map<String, Object>) generationConfig.get("thinkingConfig");
    }

    @Test
    @DisplayName("null thinkingBudget → key omitted from thinkingConfig (Gemini dynamic default preserved)")
    void nullBudgetOmitsKey() {
        CompletionRequest req = CompletionRequest.builder()
            .model("gemini-2.5-flash")
            .userPrompt("hi")
            .build();

        Map<String, Object> tc = thinkingConfigFor(req);
        assertThat(tc).isNotNull();
        assertThat(tc).containsKey("includeThoughts");
        assertThat(tc).doesNotContainKey("thinkingBudget");
    }

    @Test
    @DisplayName("thinkingBudget=0 → disables thinking; key present with zero value")
    void zeroBudgetIsSentVerbatim() {
        CompletionRequest req = CompletionRequest.builder()
            .model("gemini-3-flash")
            .userPrompt("route this tool call")
            .thinkingBudget(0)
            .build();

        Map<String, Object> tc = thinkingConfigFor(req);
        assertThat(tc).containsEntry("thinkingBudget", 0);
    }

    @Test
    @DisplayName("positive thinkingBudget → sent verbatim (hard cap)")
    void positiveBudgetIsSentVerbatim() {
        CompletionRequest req = CompletionRequest.builder()
            .model("gemini-2.5-pro")
            .userPrompt("analyze this trace")
            .thinkingBudget(2048)
            .build();

        Map<String, Object> tc = thinkingConfigFor(req);
        assertThat(tc).containsEntry("thinkingBudget", 2048);
    }

    @Test
    @DisplayName("older Gemini model (non-2.5/3.x) → no thinkingConfig at all, budget silently ignored")
    void olderModelsDoNotReceiveThinkingConfig() {
        CompletionRequest req = CompletionRequest.builder()
            .model("gemini-1.5-pro")
            .userPrompt("hi")
            .thinkingBudget(0) // even if opt-in, older models don't support it
            .build();

        assertThat(thinkingConfigFor(req)).isNull();
    }

    @Test
    @DisplayName("thinkingBudget coexists with includeThoughts - both flow through independently")
    void thinkingBudgetAndIncludeThoughtsAreOrthogonal() {
        CompletionRequest req = CompletionRequest.builder()
            .model("gemini-3-pro")
            .userPrompt("reason about this")
            .includeThoughts(true)
            .thinkingBudget(512)
            .build();

        Map<String, Object> tc = thinkingConfigFor(req);
        assertThat(tc).containsEntry("includeThoughts", true);
        assertThat(tc).containsEntry("thinkingBudget", 512);
    }

    @Test
    @DisplayName("default request (no fields set) → includeThoughts=false, no thinkingBudget")
    void defaultRequestHasIncludeThoughtsFalseAndNoBudget() {
        CompletionRequest req = CompletionRequest.builder()
            .model("gemini-2.5-flash")
            .build();

        Map<String, Object> tc = thinkingConfigFor(req);
        assertThat(tc).containsEntry("includeThoughts", false);
        assertThat(tc).doesNotContainKey("thinkingBudget");
    }
}
