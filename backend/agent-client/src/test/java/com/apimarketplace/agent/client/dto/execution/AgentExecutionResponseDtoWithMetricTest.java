package com.apimarketplace.agent.client.dto.execution;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code withMetric} rebuilds a nineteen-component record by hand, and it sits on the billing
 * path: the key route the turn ran on rides in the metrics map, and the ledger reads it from
 * there. Nineteen positional arguments is exactly the shape where two neighbours of the SAME
 * type can be swapped, compile, and quietly bill the wrong pair. Nothing guarded it.
 *
 * <p>So the assertions below are deliberately per-component rather than "the metric is there":
 * every field gets a value distinct from every other field of its type, which is what makes a
 * swap fail here instead of in production.
 */
@DisplayName("AgentExecutionResponseDto.withMetric - copies all nineteen components unchanged")
class AgentExecutionResponseDtoWithMetricTest {

    /** Every same-typed neighbour gets a DIFFERENT value, so a transposition cannot pass. */
    private static AgentExecutionResponseDto sample() {
        return new AgentExecutionResponseDto(
                true,                                   // success
                "the final response",                   // finalResponse
                "the content",                          // content
                List.of(Map.of("tool", "read")),        // toolResults
                7,                                      // iterations
                Map.of("input_tokens", 11),             // totalUsage
                "the error",                            // error
                1234L,                                  // durationMs
                "anthropic",                            // provider
                "claude-opus-5",                        // model
                List.of(Map.of("role", "user")),        // conversationHistory
                "END_TURN",                             // stopReason
                Map.of("existing", "kept"),             // metrics
                List.of(Map.of("input_tokens", 5)),     // usagePerIteration
                List.of(99L),                           // iterationDurations
                List.of("stop"),                        // finishReasonsPerIteration
                List.of(Map.of("thinking", "hm")),      // thinkingSections
                List.of(Map.of("type", "text")),        // orderedEntries
                "AGENT");                               // budgetScope
    }

    @Test
    @DisplayName("the new metric is added and the existing ones are kept")
    void addsTheMetricWithoutDroppingTheOthers() {
        AgentExecutionResponseDto copy = sample().withMetric("keyRoute", "OWN_KEY");

        assertThat(copy.metrics()).containsEntry("keyRoute", "OWN_KEY");
        assertThat(copy.metrics()).containsEntry("existing", "kept");
    }

    @Test
    @DisplayName("every other component survives the copy in its own position")
    void everyOtherComponentIsUnchanged() {
        AgentExecutionResponseDto original = sample();

        AgentExecutionResponseDto copy = original.withMetric("keyRoute", "PLATFORM");

        // The two String neighbours the billing path reads are the ones a swap would corrupt
        // silently, so they are named first.
        assertThat(copy.provider()).isEqualTo("anthropic");
        assertThat(copy.model()).isEqualTo("claude-opus-5");
        assertThat(copy.finalResponse()).isEqualTo("the final response");
        assertThat(copy.content()).isEqualTo("the content");
        assertThat(copy.error()).isEqualTo("the error");
        assertThat(copy.stopReason()).isEqualTo("END_TURN");
        assertThat(copy.budgetScope()).isEqualTo("AGENT");

        assertThat(copy.success()).isTrue();
        assertThat(copy.iterations()).isEqualTo(7);
        assertThat(copy.durationMs()).isEqualTo(1234L);

        // Same for the four List<Map> neighbours, which are type-identical to each other.
        assertThat(copy.toolResults()).isEqualTo(original.toolResults());
        assertThat(copy.conversationHistory()).isEqualTo(original.conversationHistory());
        assertThat(copy.usagePerIteration()).isEqualTo(original.usagePerIteration());
        assertThat(copy.thinkingSections()).isEqualTo(original.thinkingSections());
        assertThat(copy.orderedEntries()).isEqualTo(original.orderedEntries());

        assertThat(copy.totalUsage()).isEqualTo(original.totalUsage());
        assertThat(copy.iterationDurations()).isEqualTo(original.iterationDurations());
        assertThat(copy.finishReasonsPerIteration()).isEqualTo(original.finishReasonsPerIteration());
    }

    @Test
    @DisplayName("the original is left alone: a null metrics map does not become shared state")
    void doesNotMutateTheOriginal() {
        AgentExecutionResponseDto original = sample();

        AgentExecutionResponseDto copy = original.withMetric("keyRoute", "OWN_KEY");

        assertThat(original.metrics()).doesNotContainKey("keyRoute");
        assertThat(copy.metrics()).isNotSameAs(original.metrics());
    }

    @Test
    @DisplayName("a response that carried no metrics at all gets a map with just this one")
    void handlesAnAbsentMetricsMap() {
        AgentExecutionResponseDto noMetrics = new AgentExecutionResponseDto(
                true, "r", "c", null, 1, null, null, 1L, "openai", "gpt-5.6-sol",
                null, "END_TURN", null, null, null, null, null, null, "AGENT");

        AgentExecutionResponseDto copy = noMetrics.withMetric("keyRoute", "OWN_KEY");

        assertThat(copy.metrics()).containsExactly(org.assertj.core.api.Assertions.entry("keyRoute", "OWN_KEY"));
        assertThat(copy.provider()).isEqualTo("openai");
        assertThat(copy.model()).isEqualTo("gpt-5.6-sol");
    }
}
