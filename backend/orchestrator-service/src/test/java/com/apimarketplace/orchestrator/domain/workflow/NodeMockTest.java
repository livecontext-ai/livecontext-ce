package com.apimarketplace.orchestrator.domain.workflow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the {@link NodeMock} record itself - shape/value validation of the
 * per-node {@code mock} block, independent of any plan section (section-aware rules
 * are covered by {@code WorkflowPlanNodeMockParsingTest}).
 */
@DisplayName("NodeMock - per-node mock block record")
class NodeMockTest {

    // =====================================================================
    // fromMap - absent / empty / non-object
    // =====================================================================

    @Nested
    @DisplayName("fromMap basics")
    class FromMapBasics {

        @Test
        @DisplayName("null raw value resolves to null (no mock)")
        void nullResolvesToNull() {
            assertThat(NodeMock.fromMap(null, "mcp:x")).isNull();
        }

        @Test
        @DisplayName("an EMPTY object resolves to null (the documented 'clear' form)")
        void emptyObjectResolvesToNull() {
            assertThat(NodeMock.fromMap(Map.of(), "mcp:x")).isNull();
        }

        @Test
        @DisplayName("a non-object mock value is rejected, naming the node")
        void nonObjectRejected() {
            assertThatThrownBy(() -> NodeMock.fromMap("just mock it", "mcp:send_email"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mcp:send_email")
                .hasMessageContaining("expected an object");
        }

        @Test
        @DisplayName("unknown keys are ignored (forward compatibility)")
        void unknownKeysIgnored() {
            NodeMock mock = NodeMock.fromMap(Map.of(
                "output", Map.of("x", 1),
                "someFutureKnob", "whatever"), "mcp:x");

            assertThat(mock).isNotNull();
            assertThat(mock.output()).containsEntry("x", 1);
        }
    }

    // =====================================================================
    // Source inference and defaults
    // =====================================================================

    @Nested
    @DisplayName("Source inference (agent-friendly authoring forms)")
    class SourceInference {

        @Test
        @DisplayName("{output: {...}} infers source='static' and enabled=true")
        void outputOnlyInfersStatic() {
            NodeMock mock = NodeMock.fromMap(Map.of("output", Map.of("count", 2)), "mcp:x");

            assertThat(mock.isStatic()).isTrue();
            assertThat(mock.enabled()).isTrue();
            assertThat(mock.isEffective()).isTrue();
            assertThat(mock.output()).containsEntry("count", 2);
        }

        @Test
        @DisplayName("{port: 'if'} infers source='static' with a null output (pure routing mock)")
        void portOnlyInfersStatic() {
            NodeMock mock = NodeMock.fromMap(Map.of("port", "if"), "core:check");

            assertThat(mock.isStatic()).isTrue();
            assertThat(mock.port()).isEqualTo("if");
            assertThat(mock.output()).isNull();
        }

        @Test
        @DisplayName("{error: {message}} infers source='error'")
        void errorBlockInfersErrorSource() {
            NodeMock mock = NodeMock.fromMap(Map.of(
                "error", Map.of("message", "Rate limit exceeded", "output", Map.of("error_code", 429))),
                "mcp:x");

            assertThat(mock.isError()).isTrue();
            assertThat(mock.error().message()).isEqualTo("Rate limit exceeded");
            assertThat(mock.error().output()).containsEntry("error_code", 429);
        }

        @Test
        @DisplayName("explicit source is normalized (trimmed, case-insensitive)")
        void explicitSourceNormalized() {
            NodeMock mock = NodeMock.fromMap(Map.of("source", "  Catalog_Example "), "mcp:x");

            assertThat(mock.isCatalogExample()).isTrue();
        }

        @Test
        @DisplayName("an unknown source is rejected, naming the node and listing valid sources")
        void unknownSourceRejected() {
            assertThatThrownBy(() -> NodeMock.fromMap(Map.of("source", "random"), "mcp:x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mcp:x")
                .hasMessageContaining("static");
        }
    }

    // =====================================================================
    // enabled - parking a mock
    // =====================================================================

    @Nested
    @DisplayName("enabled flag")
    class EnabledFlag {

        @Test
        @DisplayName("enabled=false parks the mock: parsed and kept, but not effective")
        void disabledMockIsParked() {
            NodeMock mock = NodeMock.fromMap(new HashMap<>(Map.of(
                "enabled", false, "output", Map.of("x", 1))), "mcp:x");

            assertThat(mock).isNotNull();
            assertThat(mock.isEffective()).isFalse();
        }

        @Test
        @DisplayName("enabled accepts string booleans; anything else is rejected")
        void enabledCoercion() {
            assertThat(NodeMock.fromMap(Map.of("enabled", "false", "output", Map.of()), "mcp:x")
                .isEffective()).isFalse();
            assertThatThrownBy(() -> NodeMock.fromMap(Map.of("enabled", "sometimes", "output", Map.of()), "mcp:x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("enabled");
        }
    }

    // =====================================================================
    // Value strictness / exclusivity
    // =====================================================================

    @Nested
    @DisplayName("Value strictness and source exclusivity")
    class Strictness {

        @Test
        @DisplayName("output must be a JSON object (arrays/strings rejected)")
        void outputMustBeObject() {
            assertThatThrownBy(() -> NodeMock.fromMap(Map.of("output", "not-an-object"), "mcp:x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("output")
                .hasMessageContaining("object");
        }

        @Test
        @DisplayName("source='error' without an error block is rejected")
        void errorSourceRequiresErrorBlock() {
            assertThatThrownBy(() -> NodeMock.fromMap(Map.of("source", "error"), "mcp:x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("error")
                .hasMessageContaining("message");
        }

        @Test
        @DisplayName("error.message must be non-blank")
        void errorMessageRequired() {
            assertThatThrownBy(() -> NodeMock.fromMap(Map.of(
                "error", Map.of("message", "  ")), "mcp:x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("error.message");
        }

        @Test
        @DisplayName("port cannot be combined with source='error' (a failed node selects no port)")
        void portWithErrorRejected() {
            assertThatThrownBy(() -> NodeMock.fromMap(Map.of(
                "port", "if", "error", Map.of("message", "boom")), "core:check"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("port")
                .hasMessageContaining("error");
        }

        @Test
        @DisplayName("output cannot be combined with source='error' (error.output is the failure output slot)")
        void outputWithErrorRejected() {
            assertThatThrownBy(() -> NodeMock.fromMap(Map.of(
                "output", Map.of("x", 1), "error", Map.of("message", "boom")), "mcp:x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("error.output");
        }

        @Test
        @DisplayName("output cannot be combined with source='catalog_example' (no ambiguity about which wins)")
        void outputWithCatalogExampleRejected() {
            assertThatThrownBy(() -> NodeMock.fromMap(Map.of(
                "source", "catalog_example", "output", Map.of("x", 1)), "mcp:x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("catalog_example");
        }

        @Test
        @DisplayName("a mock error block that is not an object is rejected")
        void errorBlockMustBeObject() {
            assertThatThrownBy(() -> NodeMock.fromMap(Map.of("error", "boom"), "mcp:x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("error");
        }
    }

    // =====================================================================
    // durationMs - simulated execution time
    // =====================================================================

    @Nested
    @DisplayName("durationMs (simulated execution time)")
    class DurationMs {

        @Test
        @DisplayName("a numeric durationMs is kept and flags a simulated duration")
        void numericDurationKept() {
            NodeMock mock = NodeMock.fromMap(Map.of(
                "output", Map.of("x", 1), "durationMs", 90_000), "agent:slow");

            assertThat(mock.durationMs()).isEqualTo(90_000L);
            assertThat(mock.hasSimulatedDuration()).isTrue();
        }

        @Test
        @DisplayName("snake_case alias duration_ms is accepted (agents write snake_case naturally)")
        void snakeCaseAliasAccepted() {
            NodeMock mock = NodeMock.fromMap(Map.of(
                "output", Map.of("x", 1), "duration_ms", 5_000), "agent:slow");

            assertThat(mock.durationMs()).isEqualTo(5_000L);
        }

        @Test
        @DisplayName("a numeric string is coerced (lenient shape, strict value)")
        void numericStringCoerced() {
            NodeMock mock = NodeMock.fromMap(Map.of(
                "output", Map.of("x", 1), "durationMs", "1500"), "mcp:x");

            assertThat(mock.durationMs()).isEqualTo(1_500L);
        }

        @Test
        @DisplayName("fractional milliseconds are rounded (matches the builder's sanitize)")
        void fractionalRounded() {
            assertThat(NodeMock.fromMap(Map.of(
                "output", Map.of(), "durationMs", 1500.6), "mcp:x").durationMs()).isEqualTo(1_501L);
            assertThat(NodeMock.fromMap(Map.of(
                "output", Map.of(), "durationMs", "1500.4"), "mcp:x").durationMs()).isEqualTo(1_500L);
        }

        @Test
        @DisplayName("camelCase durationMs wins over the duration_ms alias when both are present")
        void camelCaseWinsOverAlias() {
            NodeMock mock = NodeMock.fromMap(Map.of(
                "output", Map.of(), "durationMs", 2_000, "duration_ms", 5_000), "mcp:x");

            assertThat(mock.durationMs()).isEqualTo(2_000L);
        }

        @Test
        @DisplayName("a blank string durationMs is rejected (not silently ignored)")
        void blankStringRejected() {
            assertThatThrownBy(() -> NodeMock.fromMap(Map.of(
                "output", Map.of(), "durationMs", "  "), "mcp:x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("durationMs");
        }

        @Test
        @DisplayName("NaN is rejected, never coerced to 0 (Number and String forms)")
        void nanRejected() {
            assertThatThrownBy(() -> NodeMock.fromMap(Map.of(
                "output", Map.of(), "durationMs", Double.NaN), "mcp:x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("durationMs");
            assertThatThrownBy(() -> NodeMock.fromMap(Map.of(
                "output", Map.of(), "durationMs", "NaN"), "mcp:x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("durationMs");
        }

        @Test
        @DisplayName("absent or 0 = no simulated duration")
        void absentOrZeroMeansInstant() {
            assertThat(NodeMock.fromMap(Map.of("output", Map.of()), "mcp:x")
                .hasSimulatedDuration()).isFalse();
            assertThat(NodeMock.fromMap(Map.of("output", Map.of(), "durationMs", 0), "mcp:x")
                .hasSimulatedDuration()).isFalse();
        }

        @Test
        @DisplayName("negative durations are rejected, naming the node")
        void negativeRejected() {
            assertThatThrownBy(() -> NodeMock.fromMap(Map.of(
                "output", Map.of(), "durationMs", -1), "mcp:x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mcp:x")
                .hasMessageContaining("durationMs");
        }

        @Test
        @DisplayName("durations beyond the 10-minute cap are rejected")
        void beyondCapRejected() {
            assertThatThrownBy(() -> NodeMock.fromMap(Map.of(
                "output", Map.of(), "durationMs", NodeMock.MAX_DURATION_MS + 1), "mcp:x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("10 minutes");
        }

        @Test
        @DisplayName("the cap itself (600000 ms) is accepted")
        void capItselfAccepted() {
            NodeMock mock = NodeMock.fromMap(Map.of(
                "output", Map.of(), "durationMs", NodeMock.MAX_DURATION_MS), "mcp:x");

            assertThat(mock.durationMs()).isEqualTo(NodeMock.MAX_DURATION_MS);
        }

        @Test
        @DisplayName("a non-numeric durationMs is rejected with an actionable message")
        void nonNumericRejected() {
            assertThatThrownBy(() -> NodeMock.fromMap(Map.of(
                "output", Map.of(), "durationMs", "fast"), "mcp:x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("durationMs")
                .hasMessageContaining("milliseconds");
        }

        @Test
        @DisplayName("durationMs composes with every source, including error")
        void composesWithErrorSource() {
            NodeMock mock = NodeMock.fromMap(Map.of(
                "error", Map.of("message", "boom"), "durationMs", 2_000), "mcp:x");

            assertThat(mock.isError()).isTrue();
            assertThat(mock.durationMs()).isEqualTo(2_000L);
        }
    }
}
