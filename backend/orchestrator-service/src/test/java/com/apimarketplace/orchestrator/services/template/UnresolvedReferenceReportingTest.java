package com.apimarketplace.orchestrator.services.template;

import com.apimarketplace.orchestrator.services.TemplateEngine;
import com.apimarketplace.orchestrator.services.TypeCastingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A condition must distinguish "the value is null" from "there is no value".
 *
 * <p>The bug this pins: {@code {{trigger:move_a_task.output.task}} == null} resolved to
 * {@code null == null} and reported true whether the field held null or the reference
 * pointed at nothing at all. A decision branch taken on a mistyped node label was
 * therefore indistinguishable, in every panel, from one taken on real data.
 */
@DisplayName("Unresolved references in a condition")
class UnresolvedReferenceReportingTest {

    private TemplateEngine templateEngine;

    @BeforeEach
    void setUp() {
        SpelEvaluator spelEvaluator = new SpelEvaluator();
        spelEvaluator.init();
        PathNavigator pathNavigator = new PathNavigator();
        NamespaceResolver namespaceResolver = new NamespaceResolver(pathNavigator);
        templateEngine = new TemplateEngine(
                new TypeCastingService(), namespaceResolver, pathNavigator, spelEvaluator);
    }

    private Map<String, Object> context() {
        Map<String, Object> output = new HashMap<>();
        output.put("task", null);
        output.put("count", 5);
        output.put("rows", List.of("a", "b", "c"));
        output.put("success", true);

        Map<String, Object> node = new HashMap<>();
        node.put("output", output);

        Map<String, Object> context = new HashMap<>();
        context.put("trigger:move_a_task", node);
        // Flattened trigger fields, exactly as EvalContextBuilder lays them out.
        context.put("status", "active");
        context.put("value", 15);
        // The workflow-variable bundle, which EvalContextBuilder puts in the context
        // specifically so conditions can read it. `region` holds null on purpose: a
        // variable that EXISTS and is null must not be called a missing reference.
        Map<String, Object> vars = new HashMap<>();
        vars.put("threshold", 10);
        vars.put("region", null);
        context.put("vars", vars);
        return context;
    }

    @Nested
    @DisplayName("A reference that points at nothing")
    class MissingReference {

        @Test
        @DisplayName("is marked in the resolved condition instead of reading as null")
        void isMarkedInTheResolvedExpression() {
            var result = templateEngine.evaluateConditionWithDetailsWithMap(
                    "{{trigger:move_task.output.task}} == null", context());

            assertTrue(result.result(), "the condition is still true: that is the trap");
            assertEquals("<unresolved: trigger:move_task.output.task> == null",
                    result.resolvedExpression(),
                    "the resolved condition must say the reference resolved to nothing");
        }

        @Test
        @DisplayName("is listed with a reason naming what is missing")
        void isListedWithAReason() {
            var result = templateEngine.evaluateConditionWithDetailsWithMap(
                    "{{trigger:move_task.output.task}} == null", context());

            assertEquals(1, result.unresolvedReferences().size());
            TemplateEngine.UnresolvedReference ref = result.unresolvedReferences().get(0);
            assertEquals("trigger:move_task.output.task", ref.reference());
            assertEquals("no node or trigger named 'trigger:move_task'", ref.reason());
        }

        @Test
        @DisplayName("names the FIELD when the node exists but the field does not")
        void namesTheFieldWhenTheNodeExists() {
            var result = templateEngine.evaluateConditionWithDetailsWithMap(
                    "{{trigger:move_a_task.output.assignee}} == null", context());

            assertEquals(1, result.unresolvedReferences().size());
            assertEquals("'trigger:move_a_task.output' has no field 'assignee'",
                    result.unresolvedReferences().get(0).reason());
        }
    }

    @Nested
    @DisplayName("A reference that resolves")
    class ResolvedReference {

        @Test
        @DisplayName("to a real null still reads as null and is not listed")
        void realNullIsNotFlagged() {
            var result = templateEngine.evaluateConditionWithDetailsWithMap(
                    "{{trigger:move_a_task.output.task}} == null", context());

            assertTrue(result.result());
            assertEquals("null == null", result.resolvedExpression(),
                    "a field that exists and holds null is a real null, not a missing reference");
            assertTrue(result.unresolvedReferences().isEmpty());
        }

        @Test
        @DisplayName("to a value substitutes the value, unchanged from before")
        void valueIsSubstituted() {
            var result = templateEngine.evaluateConditionWithDetailsWithMap(
                    "{{trigger:move_a_task.output.count}} > 3", context());

            assertTrue(result.result());
            assertEquals("5 > 3", result.resolvedExpression());
            assertTrue(result.unresolvedReferences().isEmpty());
        }
    }

    @Nested
    @DisplayName("What must never be called a missing reference")
    class NoFalsePositives {

        @Test
        @DisplayName("A method call on a resolved value")
        void methodCallIsNotAReference() {
            // `rows.size()` is a method on a List, not a field named size. Flagging it
            // would accuse a healthy condition, which is worse than saying nothing.
            var result = templateEngine.evaluateConditionWithDetailsWithMap(
                    "{{trigger:move_a_task.output.rows.size()}} > 2", context());

            assertTrue(result.unresolvedReferences().isEmpty(),
                    "flagged: " + result.unresolvedReferences());
        }

        @Test
        @DisplayName("Reserved words")
        void reservedWordsAreNotReferences() {
            var result = templateEngine.evaluateConditionWithDetailsWithMap(
                    "{{trigger:move_a_task.output.count}} > 3 and true", context());

            assertTrue(result.unresolvedReferences().isEmpty(),
                    "flagged: " + result.unresolvedReferences());
        }

        /**
         * The single most common condition shape in the product, and the one the first
         * version of this diagnostic got wrong: it read the bare word inside the quotes
         * as a node reference and reported "no node or trigger named 'active'" about a
         * condition that works.
         */
        @Test
        @DisplayName("A string literal is not a reference, in either position")
        void stringLiteralIsNotAReference() {
            for (String condition : List.of(
                    "{{status}} == 'active'",
                    "{{status}} == 'pending'",
                    "{{status}} == \"archived\"",
                    "'active' == {{status}}",
                    "{{trigger:move_a_task.output.count}} > 3 and {{status}} == 'active'")) {
                var result = templateEngine.evaluateConditionWithDetailsWithMap(condition, context());
                assertTrue(result.unresolvedReferences().isEmpty(),
                        condition + " flagged: " + result.unresolvedReferences());
                assertFalse(result.resolvedExpression().contains("<unresolved"),
                        condition + " rendered a literal as missing: " + result.resolvedExpression());
            }
        }

        @Test
        @DisplayName("A literal inside a PURE expression keeps its own text")
        void pureExpressionKeepsItsLiteral() {
            // resolveExpressionToHumanReadableWithMap walks identifiers too, and marking
            // one turned a correct 'active' == 'active' into 'active' == '<unresolved: active>'.
            var result = templateEngine.evaluateConditionWithDetailsWithMap(
                    "{{status == 'active'}}", context());

            assertTrue(result.result());
            assertEquals("'active' == 'active'", result.resolvedExpression());
            assertTrue(result.unresolvedReferences().isEmpty());
        }

        @Test
        @DisplayName("A bare identifier the engine finds by scanning step outputs")
        void bareIdentifierResolvedByScanIsNotFlagged() {
            // SpelEvaluator searches every context entry and its output sub-map, so
            // `success` resolves from trigger:move_a_task.output. A diagnostic modelled on
            // the narrower PathNavigator lookup called it missing.
            var result = templateEngine.evaluateConditionWithDetailsWithMap(
                    "{{success}} == true", context());

            assertTrue(result.result());
            assertTrue(result.unresolvedReferences().isEmpty(),
                    "flagged: " + result.unresolvedReferences());
        }

        @Test
        @DisplayName("A property tail on a non-Map value, which SpEL walks itself")
        void propertyTailOnScalarIsNotFlagged() {
            var result = templateEngine.evaluateConditionWithDetailsWithMap(
                    "{{value}} > 10 and {{status.empty}} == false", context());

            assertTrue(result.unresolvedReferences().isEmpty(),
                    "flagged: " + result.unresolvedReferences());
        }

        /**
         * The invariant, at the level where the decision is actually made: if the engine
         * resolves every reference a condition mentions, nothing may be reported. Stated
         * over a matrix rather than one case, because the first version failed four of
         * these five while its own unit tests were green.
         */
        @Test
        @DisplayName("No condition the engine resolves is ever reported")
        void nothingResolvableIsEverReported() {
            for (String condition : List.of(
                    "{{status}} == 'active'",
                    "{{value}} > 10",
                    "{{trigger:move_a_task.output.count}} >= 5",
                    "{{trigger:move_a_task.output.rows.size()}} == 3",
                    "{{trigger:move_a_task.output.task}} == null",
                    "{{value}} > 10 and {{status}} != 'archived'",
                    // The workflow-variable alias, in both spellings. `vars:x` matches the
                    // type:label grammar without being a node, and the engine rewrites it
                    // before resolving: a diagnostic that does not is certain and wrong.
                    "{{vars:threshold}} > 5",
                    "{{$vars.threshold}} > 5",
                    "{{vars:region}} == 'EU'")) {
                var result = templateEngine.evaluateConditionWithDetailsWithMap(condition, context());
                assertTrue(result.unresolvedReferences().isEmpty(),
                        condition + " flagged: " + result.unresolvedReferences());
            }
        }

        @Test
        @DisplayName("A condition evaluated against no context at all")
        void emptyContextReportsNothing() {
            // Internal evaluations run with an empty map. Reporting every reference as
            // missing there would fill the panel with noise on a healthy run.
            var result = templateEngine.evaluateConditionWithDetailsWithMap(
                    "{{trigger:x.output.y}} == null", Map.of());

            assertTrue(result.unresolvedReferences().isEmpty());
        }
    }

    @Nested
    @DisplayName("Reporting survives the failure path")
    class FailurePath {

        @Test
        @DisplayName("A condition that cannot be evaluated still reports its missing references")
        void reportedOnError() {
            // Where "which reference is missing" matters most.
            var result = templateEngine.evaluateConditionWithDetailsWithMap(
                    "{{trigger:ghost.output.value}}.!!!(", context());

            assertFalse(result.unresolvedReferences().isEmpty(),
                    "a broken condition must still name what it could not find");
        }
    }
}
