package com.apimarketplace.agent.tokenizer;

import com.apimarketplace.agent.domain.CompletionRequest;
import com.apimarketplace.agent.domain.Message;
import com.apimarketplace.agent.domain.ToolDefinition;
import com.apimarketplace.agent.domain.ToolParameter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stage 1a.4 - regression guard for the chars/4 fallback. Numbers must match the
 * pre-1a.4 logic exactly so that services running with
 * {@code ai.token-estimator.mode=heuristic} see zero behavior change.
 */
@DisplayName("HeuristicTokenEstimator - chars/4 fallback (Stage 1a.4)")
class HeuristicTokenEstimatorTest {

    private final HeuristicTokenEstimator estimator = new HeuristicTokenEstimator();

    @Test
    @DisplayName("name() is 'heuristic' for telemetry")
    void nameIsHeuristic() {
        assertThat(estimator.name()).isEqualTo("heuristic");
    }

    @Test
    @DisplayName("empty request → just the default completion reserve (500)")
    void emptyYieldsDefaultReserve() {
        assertThat(estimator.estimate(CompletionRequest.builder().model("x").build())).isEqualTo(500);
    }

    @Test
    @DisplayName("system+user+history chars summed then divided by 4; tools add 200 chars per tool")
    void matchesLegacyArithmetic() {
        // 100 + 100 + 100 = 300 chars prose → 75 tokens; 2 tools × 200 = 400 chars → 100 tokens;
        // maxTokens = 128. Expected = 75 + 100 + 128 = 303.
        String ch100 = "a".repeat(100);
        CompletionRequest req = CompletionRequest.builder()
            .model("x")
            .systemPrompt(ch100)
            .userPrompt(ch100)
            .conversationHistory(List.of(Message.user(ch100)))
            .tools(List.of(toolOf("t1"), toolOf("t2")))
            .maxTokens(128)
            .build();
        assertThat(estimator.estimate(req)).isEqualTo(75 + 100 + 128);
    }

    @Test
    @DisplayName("null content in history is skipped - no NPE")
    void nullHistoryContentIsSafe() {
        CompletionRequest req = CompletionRequest.builder()
            .model("x")
            .conversationHistory(List.of(
                Message.builder().role(Message.Role.USER).content(null).build()))
            .maxTokens(10)
            .build();
        assertThat(estimator.estimate(req)).isEqualTo(10);
    }

    private static ToolDefinition toolOf(String name) {
        return ToolDefinition.builder()
            .name(name).description("d")
            .parameters(List.of(ToolParameter.builder().name("q").type("string").description("x").build()))
            .build();
    }
}
