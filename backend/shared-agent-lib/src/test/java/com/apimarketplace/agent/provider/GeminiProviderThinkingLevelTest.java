package com.apimarketplace.agent.provider;

import com.apimarketplace.agent.domain.CompletionRequest;
import com.apimarketplace.agent.domain.ThinkingLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stage 1b.1 - coarse {@link ThinkingLevel} tier translates to Gemini's concrete
 * {@code thinkingConfig.thinkingBudget} knob:
 * <ul>
 *   <li>{@code LOW} → 128</li>
 *   <li>{@code MEDIUM} → 1024</li>
 *   <li>{@code HIGH} → 8192</li>
 * </ul>
 * Explicit {@link CompletionRequest#thinkingBudget()} always wins (it is the
 * lower-level exact knob). Older Gemini (pre-2.5) gets no {@code thinkingConfig}
 * at all - the level is silently ignored there.
 */
@DisplayName("GeminiProvider - thinkingLevel tier (Stage 1b.1)")
class GeminiProviderThinkingLevelTest {

    private final GeminiProvider provider = new GeminiProvider();

    @SuppressWarnings("unchecked")
    private Map<String, Object> thinkingConfigFor(CompletionRequest request) {
        Map<String, Object> body = provider.buildRequestBody(request);
        Map<String, Object> generationConfig = (Map<String, Object>) body.get("generationConfig");
        if (generationConfig == null) return null;
        return (Map<String, Object>) generationConfig.get("thinkingConfig");
    }

    @Test
    @DisplayName("LOW → thinkingBudget=128")
    void lowLevelMapsTo128() {
        CompletionRequest req = CompletionRequest.builder()
            .model("gemini-2.5-flash")
            .thinkingLevel(ThinkingLevel.LOW)
            .build();
        assertThat(thinkingConfigFor(req)).containsEntry("thinkingBudget", 128);
    }

    @Test
    @DisplayName("MEDIUM → thinkingBudget=1024")
    void mediumLevelMapsTo1024() {
        CompletionRequest req = CompletionRequest.builder()
            .model("gemini-2.5-pro")
            .thinkingLevel(ThinkingLevel.MEDIUM)
            .build();
        assertThat(thinkingConfigFor(req)).containsEntry("thinkingBudget", 1024);
    }

    @Test
    @DisplayName("HIGH → thinkingBudget=8192")
    void highLevelMapsTo8192() {
        CompletionRequest req = CompletionRequest.builder()
            .model("gemini-3-pro")
            .thinkingLevel(ThinkingLevel.HIGH)
            .build();
        assertThat(thinkingConfigFor(req)).containsEntry("thinkingBudget", 8192);
    }

    @Test
    @DisplayName("explicit thinkingBudget wins over thinkingLevel when both are set")
    void explicitBudgetTakesPrecedenceOverLevel() {
        CompletionRequest req = CompletionRequest.builder()
            .model("gemini-2.5-flash")
            .thinkingLevel(ThinkingLevel.HIGH) // would be 8192
            .thinkingBudget(42)                // but caller pinned 42
            .build();
        assertThat(thinkingConfigFor(req)).containsEntry("thinkingBudget", 42);
    }

    @Test
    @DisplayName("both null → key omitted (Gemini dynamic default preserved)")
    void nullLevelAndNullBudgetOmitKey() {
        CompletionRequest req = CompletionRequest.builder()
            .model("gemini-3-flash")
            .userPrompt("hi")
            .build();
        Map<String, Object> tc = thinkingConfigFor(req);
        assertThat(tc).isNotNull();
        assertThat(tc).doesNotContainKey("thinkingBudget");
    }

    @Test
    @DisplayName("thinkingBudget=0 with thinkingLevel=HIGH → 0 wins (explicit disable overrides tier)")
    void zeroBudgetOverridesLevel() {
        CompletionRequest req = CompletionRequest.builder()
            .model("gemini-2.5-flash")
            .thinkingLevel(ThinkingLevel.HIGH)
            .thinkingBudget(0)
            .build();
        assertThat(thinkingConfigFor(req)).containsEntry("thinkingBudget", 0);
    }

    @Test
    @DisplayName("older Gemini (pre-2.5) → no thinkingConfig at all; level silently ignored")
    void olderModelsIgnoreLevel() {
        CompletionRequest req = CompletionRequest.builder()
            .model("gemini-1.5-pro")
            .thinkingLevel(ThinkingLevel.HIGH)
            .build();
        assertThat(thinkingConfigFor(req)).isNull();
    }

    @Test
    @DisplayName("thinkingLevel coexists with includeThoughts - both flow through independently")
    void levelAndIncludeThoughtsAreOrthogonal() {
        CompletionRequest req = CompletionRequest.builder()
            .model("gemini-3-pro")
            .thinkingLevel(ThinkingLevel.MEDIUM)
            .includeThoughts(true)
            .build();
        Map<String, Object> tc = thinkingConfigFor(req);
        assertThat(tc).containsEntry("includeThoughts", true);
        assertThat(tc).containsEntry("thinkingBudget", 1024);
    }

    @Test
    @DisplayName("ThinkingLevel.budgetTokens() returns the advertised tier values")
    void enumExposesBudgetConstants() {
        assertThat(ThinkingLevel.LOW.budgetTokens()).isEqualTo(128);
        assertThat(ThinkingLevel.MEDIUM.budgetTokens()).isEqualTo(1024);
        assertThat(ThinkingLevel.HIGH.budgetTokens()).isEqualTo(8192);
    }
}
