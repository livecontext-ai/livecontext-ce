package com.apimarketplace.orchestrator.execution.v2.nodes;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One aggregate, two producers, one row.
 *
 * <p>An aggregate's parameters are written by {@link AggregateNode} when the node runs on its
 * own, and by {@code SplitAggregateHandler} on the path a split reaches it through, which is
 * how an aggregate is normally reached. They were two copies of one shape, and copies drift:
 * putting one of them through the reporting gate and not the other made a field an author
 * labelled {@code token} read {@code <withheld: credential>} on one path and its expression on
 * the other. Since {@code StepOutputService} publishes every reported key as
 * {@code input.<key>}, that is not only a panel that disagrees with itself - a downstream node
 * reading {@code {{core:<agg>.input.token}}} receives the literal marker string on one path and
 * the expression on the other.
 *
 * <p>These tests pin the shared builder both now call, which is what makes the drift
 * structurally impossible rather than merely fixed.
 */
@DisplayName("an aggregate reports one row, whichever producer writes it")
class AggregateReportedParamsTest {

    private static List<AggregateNode.AggregateField> fields(String... labelsAndExpressions) {
        return java.util.stream.IntStream.range(0, labelsAndExpressions.length / 2)
            .mapToObj(i -> new AggregateNode.AggregateField(
                labelsAndExpressions[i * 2], labelsAndExpressions[i * 2 + 1]))
            .toList();
    }

    @Test
    @DisplayName("an author's label is never read by the credential rules: the value under it is an EXPRESSION")
    void doesNotMaskAnAuthorsFieldLabel() {
        // The false positive that matters. `token` is an ordinary column name, and what it
        // holds here is `{{core:auth.output.session_label}}` - a template, which cannot be a
        // credential whatever the field is called. Masking it publishes the marker string to
        // every workflow that addresses {{core:<agg>.input.token}}.
        Map<String, Object> reported = AggregateNode.buildReportedParams(
            fields("token", "{{core:auth.output.session_label}}",
                   "total", "{{core:rows.output.amount}}"),
            "core:aggregate");

        assertThat(reported.get("token")).isEqualTo("{{core:auth.output.session_label}}");
        assertThat(reported.get("total")).isEqualTo("{{core:rows.output.amount}}");
        assertThat(reported.toString()).doesNotContain("withheld");
    }

    @Test
    @DisplayName("the declaration list reports the same expressions as the per-label keys")
    @SuppressWarnings("unchecked")
    void declaresTheSameExpressionsItReportsPerLabel() {
        // `fields` is what tells a reader WHICH expression produced a value; the per-label
        // keys are what a downstream template addresses. They describe one configuration, so
        // they must not be able to say different things.
        Map<String, Object> reported = AggregateNode.buildReportedParams(
            fields("total", "{{core:rows.output.amount}}"), "core:aggregate");

        List<Map<String, Object>> declared = (List<Map<String, Object>>) reported.get("fields");
        assertThat(declared).hasSize(1);
        assertThat(declared.get(0)).containsEntry("label", "total");
        assertThat(declared.get(0)).containsEntry("expression", "{{core:rows.output.amount}}");
        assertThat(reported.get("total")).isEqualTo(declared.get(0).get("expression"));
    }

    @Test
    @DisplayName("a field labelled `fields` loses to the declaration, and only that one field")
    void statesTheCostOfTheOneCollidingLabel() {
        Map<String, Object> reported = AggregateNode.buildReportedParams(
            fields("fields", "{{core:a.output.x}}", "total", "{{core:b.output.y}}"),
            "core:aggregate");

        assertThat(reported.get("fields"))
            .as("the declaration wins: without it a reader cannot tell which expression produced what")
            .isInstanceOf(List.class);
        assertThat(reported.get("total"))
            .as("and every other field is unaffected")
            .isEqualTo("{{core:b.output.y}}");
    }

    @Test
    @DisplayName("an expression with no length limit is bounded, because this row is written per item of every split")
    void boundsAnOversizedExpression() {
        Map<String, Object> reported = AggregateNode.buildReportedParams(
            fields("huge", "{{" + "x".repeat(5_000) + "}}"), "core:aggregate");

        assertThat(String.valueOf(reported.get("huge")).length())
            .as("bounded, even though it is never masked")
            .isLessThan(500);
    }

    @Test
    @DisplayName("no fields is an empty map, not a null the persistence layer reads as `reported nothing`")
    void handlesAnUnconfiguredAggregate() {
        assertThat(AggregateNode.buildReportedParams(null, "core:aggregate")).isNotNull().isEmpty();
        assertThat(AggregateNode.buildReportedParams(List.of(), "core:aggregate")).isNotNull().isEmpty();
    }
}
