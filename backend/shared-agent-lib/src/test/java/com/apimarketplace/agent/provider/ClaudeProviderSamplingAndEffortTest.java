package com.apimarketplace.agent.provider;

import com.apimarketplace.agent.domain.CompletionRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression guard for the two Anthropic 4.7+/Fable request-shape rules
 * (verified live 2026-07-02 against platform.claude.com migration guide +
 * effort.md):
 *
 * <ol>
 *   <li><b>Sampling params removed.</b> temperature/top_p return a 400 on
 *       Fable/Mythos (any), Opus 4.7+, and Sonnet 5+. The provider used to send
 *       temperature=0.7 unconditionally, which made every direct-API request to
 *       those models fail. Older models keep the historical 0.7 default.</li>
 *   <li><b>Categorical effort.</b> The resolved reasoning-effort level maps to
 *       {@code output_config.effort} on models that support it, clamped to the
 *       nearest accepted level (minimal→low; xhigh/max→high where unavailable),
 *       and is omitted entirely elsewhere.</li>
 * </ol>
 */
@DisplayName("ClaudeProvider - sampling-param gating + output_config.effort")
class ClaudeProviderSamplingAndEffortTest {

    private final ClaudeProvider provider = new ClaudeProvider();

    private Map<String, Object> body(String model, String effort) {
        return provider.buildRequestBody(CompletionRequest.builder()
            .model(model)
            .userPrompt("hi")
            .reasoningEffort(effort)
            .build());
    }

    // ── sampling params ──────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
        "claude-fable-5",
        "claude-mythos-5",
        "claude-opus-4-7",
        "claude-opus-4-8",
        "claude-opus-4-7-20260416",   // dated 3-segment pin of a 4.7+ model
        "claude-sonnet-5",
    })
    @DisplayName("temperature/top_p are omitted on models that reject sampling params (would 400)")
    void samplingOmittedWhereRejected(String model) {
        Map<String, Object> b = provider.buildRequestBody(CompletionRequest.builder()
            .model(model).userPrompt("hi").temperature(0.7).topP(0.9).build());
        assertThat(b).doesNotContainKey("temperature");
        assertThat(b).doesNotContainKey("top_p");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "claude-opus-4-6",
        "claude-sonnet-4-6",
        "claude-sonnet-4-5",
        "claude-haiku-4-5",
        "claude-opus-4-20250514",     // dated 2-segment pin = Opus 4.0, not 4.20250514
        "claude-3-5-sonnet-20241022", // legacy reversed id
    })
    @DisplayName("older models keep the historical default temperature 0.7")
    void samplingKeptOnOlderModels(String model) {
        Map<String, Object> b = body(model, null);
        assertThat(b.get("temperature")).isEqualTo(0.7);
    }

    @Test
    @DisplayName("an explicit caller temperature still passes through on models that accept it")
    void explicitTemperaturePassesThroughWhereAccepted() {
        Map<String, Object> b = provider.buildRequestBody(CompletionRequest.builder()
            .model("claude-opus-4-6").userPrompt("hi").temperature(0.2).build());
        assertThat(b.get("temperature")).isEqualTo(0.2);
    }

    // ── output_config.effort ─────────────────────────────────────────────────

    @ParameterizedTest
    @CsvSource({
        // model,               requested, expected on the wire
        "claude-fable-5,        max,       max",
        "claude-fable-5,        xhigh,     xhigh",
        "claude-fable-5,        low,       low",
        "claude-mythos-5,       max,       max",
        "claude-opus-4-8,       xhigh,     xhigh",
        "claude-opus-4-7,       max,       max",
        "claude-opus-4-6,       max,       max",
        "claude-opus-4-6,       xhigh,     high",  // 4.6 has no xhigh → clamp
        "claude-opus-4-5,       max,       high",  // 4.5 has no max → clamp
        "claude-opus-4-5,       medium,    medium",
        "claude-sonnet-5,       xhigh,     xhigh",
        "claude-sonnet-4-6,     xhigh,     high",  // 4.6 has no xhigh → clamp
        "claude-sonnet-4-6,     max,       max",
        "claude-fable-5,        minimal,   low",   // API has no minimal → clamp
    })
    @DisplayName("effort maps to output_config.effort, clamped to what the model accepts")
    @SuppressWarnings("unchecked")
    void effortMappedAndClamped(String model, String requested, String expected) {
        Map<String, Object> b = body(model, requested);
        Map<String, Object> outputConfig = (Map<String, Object>) b.get("output_config");
        assertThat(outputConfig).as("output_config present for %s", model).isNotNull();
        assertThat(outputConfig.get("effort")).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "claude-sonnet-4-5",          // effort unsupported → would 400
        "claude-haiku-4-5",           // effort unsupported → would 400
        "claude-3-5-sonnet-20241022", // legacy id → unknown → omit
    })
    @DisplayName("effort is omitted entirely on models without output_config.effort support")
    void effortOmittedWhereUnsupported(String model) {
        assertThat(body(model, "high")).doesNotContainKey("output_config");
    }

    @Test
    @DisplayName("no resolved effort (null) or unknown value → parameter omitted → API default (high)")
    void effortOmittedWhenUnsetOrUnknown() {
        assertThat(body("claude-fable-5", null)).doesNotContainKey("output_config");
        assertThat(body("claude-fable-5", "bogus")).doesNotContainKey("output_config");
    }

    @Test
    @DisplayName("gates run on the RESOLVED model: null request model falls back to getDefaultModel()")
    void gatesRunOnResolvedDefaultModel() {
        // No configured models → getDefaultModel() is null → version gates parse
        // nothing → legacy behavior (sampling kept, effort omitted), no NPE.
        Map<String, Object> b = provider.buildRequestBody(CompletionRequest.builder()
            .userPrompt("hi").reasoningEffort("high").build());
        assertThat(b.get("model")).isNull();
        assertThat(b.get("temperature")).isEqualTo(0.7);
        assertThat(b).doesNotContainKey("output_config");
    }

    @Test
    @DisplayName("model-id gates are case/whitespace tolerant")
    void gatesTolerateCaseAndWhitespace() {
        Map<String, Object> b = body("  Claude-Fable-5  ", "max");
        assertThat(b).doesNotContainKey("temperature");
        @SuppressWarnings("unchecked")
        Map<String, Object> outputConfig = (Map<String, Object>) b.get("output_config");
        assertThat(outputConfig.get("effort")).isEqualTo("max");
    }

    @Test
    @DisplayName("dated 2-segment pin (Opus 4.0) gets NO effort parameter either")
    void datedTwoSegmentPinGetsNoEffort() {
        assertThat(body("claude-opus-4-20250514", "high")).doesNotContainKey("output_config");
    }

    @Test
    @DisplayName("dated 3-segment 4.5 pin keeps effort support with the 4.5 clamps")
    @SuppressWarnings("unchecked")
    void datedThreeSegmentPinKeepsEffort() {
        Map<String, Object> b = body("claude-opus-4-5-20251101", "max");
        Map<String, Object> outputConfig = (Map<String, Object>) b.get("output_config");
        assertThat(outputConfig.get("effort")).isEqualTo("high"); // 4.5 has no max
    }
}
