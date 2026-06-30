package com.apimarketplace.agent.tokenizer;

import com.apimarketplace.agent.domain.CompletionRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stage 1a.4 piece B - per-provider multiplicative bias correcting the
 * cl100k_base estimate toward the target tokenizer's real count:
 * <ul>
 *   <li>OpenAI: 1.00× (cl100k is native - no correction).</li>
 *   <li>Claude: 1.10× (cl100k under-counts Claude by ~8-12%).</li>
 *   <li>Gemini: 1.15× (cl100k under-counts Gemini by ~15%).</li>
 * </ul>
 * Bias is applied to PROMPT tokens only; caller-provided {@code maxTokens} is
 * passed through unchanged.
 */
@DisplayName("JtokkitTokenEstimator - per-provider bias (Stage 1a.4 piece B)")
class JtokkitTokenEstimatorProviderBiasTest {

    private JtokkitTokenEstimator estimator;

    /** A prompt large enough that the bias delta is visible after int-rounding. */
    private static final String LARGE_PROMPT = "The quick brown fox jumps over the lazy dog. ".repeat(50);

    @BeforeEach
    void setUp() {
        estimator = new JtokkitTokenEstimator();
        estimator.init();
    }

    @Test
    @DisplayName("OpenAI gpt-4 → 1.0× bias - estimate equals raw cl100k prompt + maxTokens")
    void openaiModelHasNoBias() {
        CompletionRequest req = CompletionRequest.builder()
            .model("gpt-4")
            .userPrompt(LARGE_PROMPT)
            .maxTokens(0) // zero completion reserve isolates the prompt-side math
            .build();

        int rawPrompt = estimator.countTokens(LARGE_PROMPT);
        assertThat(estimator.estimate(req)).isEqualTo(rawPrompt);
    }

    @Test
    @DisplayName("Claude model → 1.10× bias applied to prompt; maxTokens passes through")
    void claudeModelAppliesClaudeBias() {
        CompletionRequest req = CompletionRequest.builder()
            .model("claude-opus-4-7")
            .userPrompt(LARGE_PROMPT)
            .maxTokens(200)
            .build();

        int rawPrompt = estimator.countTokens(LARGE_PROMPT);
        int expected = (int) Math.round(rawPrompt * JtokkitTokenEstimator.CLAUDE_BIAS) + 200;
        assertThat(estimator.estimate(req)).isEqualTo(expected);
        // Sanity: Claude estimate strictly above OpenAI for a prompt big enough that
        // round(prompt * 1.10) > prompt. LARGE_PROMPT is sized to guarantee this.
        assertThat(estimator.estimate(req)).isGreaterThan(rawPrompt + 200);
    }

    @Test
    @DisplayName("Gemini model → 1.15× bias applied to prompt; maxTokens passes through")
    void geminiModelAppliesGeminiBias() {
        CompletionRequest req = CompletionRequest.builder()
            .model("gemini-2.5-flash")
            .userPrompt(LARGE_PROMPT)
            .maxTokens(200)
            .build();

        int rawPrompt = estimator.countTokens(LARGE_PROMPT);
        int expected = (int) Math.round(rawPrompt * JtokkitTokenEstimator.GEMINI_BIAS) + 200;
        assertThat(estimator.estimate(req)).isEqualTo(expected);
    }

    @Test
    @DisplayName("Gemini > Claude > OpenAI for identical payload (bias ordering)")
    void biasOrderingIsGeminiGreaterThanClaudeGreaterThanOpenai() {
        CompletionRequest openai = CompletionRequest.builder().model("gpt-4").userPrompt(LARGE_PROMPT).maxTokens(0).build();
        CompletionRequest claude = CompletionRequest.builder().model("claude-3-sonnet").userPrompt(LARGE_PROMPT).maxTokens(0).build();
        CompletionRequest gemini = CompletionRequest.builder().model("gemini-3-pro").userPrompt(LARGE_PROMPT).maxTokens(0).build();

        int o = estimator.estimate(openai);
        int c = estimator.estimate(claude);
        int g = estimator.estimate(gemini);
        assertThat(o).isLessThan(c);
        assertThat(c).isLessThan(g);
    }

    @Test
    @DisplayName("bias only multiplies prompt tokens - maxTokens reserve is passed through unchanged")
    void biasAppliesOnlyToPromptNotMaxTokens() {
        // Empty prompt means promptTokens=0; bias(0)=0; estimate must be exactly maxTokens.
        CompletionRequest req = CompletionRequest.builder()
            .model("claude-opus-4-7")
            .maxTokens(1234)
            .build();
        assertThat(estimator.estimate(req)).isEqualTo(1234);
    }

    @Test
    @DisplayName("unknown model family → OpenAI 1.0× baseline (no inflation for unrecognized providers)")
    void unknownModelFallsBackToOpenaiBaseline() {
        CompletionRequest unknown = CompletionRequest.builder()
            .model("some-future-model-v9")
            .userPrompt(LARGE_PROMPT)
            .maxTokens(0)
            .build();
        int rawPrompt = estimator.countTokens(LARGE_PROMPT);
        assertThat(estimator.estimate(unknown)).isEqualTo(rawPrompt);
    }

    @Test
    @DisplayName("null model → OpenAI 1.0× baseline - no NPE")
    void nullModelFallsBackToOpenaiBaseline() {
        CompletionRequest req = CompletionRequest.builder()
            .userPrompt(LARGE_PROMPT)
            .maxTokens(0)
            .build();
        int rawPrompt = estimator.countTokens(LARGE_PROMPT);
        assertThat(estimator.estimate(req)).isEqualTo(rawPrompt);
    }

    @Test
    @DisplayName("case-insensitive family detection - CLAUDE-* and Gemini-* still dispatch correctly")
    void familyDetectionIsCaseInsensitive() {
        assertThat(JtokkitTokenEstimator.biasFor("CLAUDE-3-OPUS")).isEqualTo(JtokkitTokenEstimator.CLAUDE_BIAS);
        assertThat(JtokkitTokenEstimator.biasFor("Gemini-2.5-Flash")).isEqualTo(JtokkitTokenEstimator.GEMINI_BIAS);
        assertThat(JtokkitTokenEstimator.biasFor("GPT-4")).isEqualTo(JtokkitTokenEstimator.OPENAI_BIAS);
    }
}
