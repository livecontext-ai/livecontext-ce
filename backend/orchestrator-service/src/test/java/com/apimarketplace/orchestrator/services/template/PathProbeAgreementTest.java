package com.apimarketplace.orchestrator.services.template;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The probe answers WHY a path resolved to null, and it may never disagree with the
 * resolver it mirrors.
 *
 * <p><b>Scope, stated precisely, because getting this wrong shipped a bug.</b> This
 * class pins the probe against {@link PathNavigator#getVariableValueFromMap}, which is
 * the resolver it mirrors line for line. That is the EXPLANATION step only. The
 * resolution a condition actually performs is
 * {@code SpelEvaluator.resolveVariableForDiagnostics}, which resolves strictly more,
 * and {@code TemplateEngine.collectUnresolvedReferences} asks THAT one first and only
 * uses the probe to describe a null it has already confirmed. The first version of this
 * work pinned only the mirror and concluded the diagnostic was sound; it was reporting
 * "no node or trigger named 'active'" about a string literal. The guard that catches
 * that lives in {@code UnresolvedReferenceReportingTest}, at the level where the
 * decision is made.
 */
@DisplayName("PathNavigator unresolved-path probe")
class PathProbeAgreementTest {

    private final PathNavigator navigator = new PathNavigator();

    private Map<String, Object> context() {
        Map<String, Object> output = new HashMap<>();
        output.put("task", null);
        output.put("name", "Ada");
        output.put("nested", Map.of("deep", "value"));

        Map<String, Object> node = new HashMap<>();
        node.put("output", output);
        node.put("iteration", 3);

        Map<String, Object> context = new HashMap<>();
        context.put("trigger:move_a_task", node);
        context.put("core:items", Map.of("output", Map.of("rows", List.of("a", "b"))));
        context.put("scalar", "plain");
        return context;
    }

    @Nested
    @DisplayName("Missing versus null")
    class MissingVersusNull {

        @Test
        @DisplayName("A field that exists and holds null is RESOLVED, not missing")
        void nullValuedFieldIsResolved() {
            PathProbe probe = navigator.probeVariablePath("trigger:move_a_task.output.task", context());

            assertFalse(probe.isMissing(),
                    "the field is there and its value is null: that null is real data");
            assertEquals(PathProbe.Status.RESOLVED, probe.status());
        }

        @Test
        @DisplayName("An unknown node label is MISSING_ROOT and names the label")
        void unknownRootIsReported() {
            PathProbe probe = navigator.probeVariablePath("trigger:move_task.output.task", context());

            assertEquals(PathProbe.Status.MISSING_ROOT, probe.status());
            assertEquals("trigger:move_task", probe.missingSegment());
            assertEquals("no node or trigger named 'trigger:move_task'",
                    probe.describe("trigger:move_task.output.task"));
        }

        @Test
        @DisplayName("A known node with an unknown field is MISSING_SEGMENT and names both")
        void unknownFieldIsReported() {
            PathProbe probe = navigator.probeVariablePath("trigger:move_a_task.output.assignee", context());

            assertEquals(PathProbe.Status.MISSING_SEGMENT, probe.status());
            assertEquals("assignee", probe.missingSegment());
            assertEquals("trigger:move_a_task.output", probe.resolvedPrefix());
        }

        @Test
        @DisplayName("The implicit output hop the resolver takes is taken by the probe too")
        void implicitOutputWrapperIsFollowed() {
            // getNestedValueFromMap falls back into an "output" sub-map. A probe that did
            // not would call every shorthand reference in the product missing.
            assertFalse(navigator.probeVariablePath("trigger:move_a_task.name", context()).isMissing());
            // And the hop is not a blanket yes: a name in neither map is still missing.
            assertTrue(navigator.probeVariablePath("trigger:move_a_task.missing", context()).isMissing());
        }

        @Test
        @DisplayName("Walking past a scalar is MISSING, matching the resolver's null")
        void pathBeyondAScalarIsMissing() {
            assertTrue(navigator.probeVariablePath("scalar.deeper", context()).isMissing());
            assertNull(navigator.getVariableValueFromMap("scalar.deeper", context()));
        }

        @Test
        @DisplayName("An index past the end of a list is MISSING, like the resolver's null")
        void outOfRangeIndexIsMissing() {
            assertFalse(navigator.probeVariablePath("core:items.output.rows[1]", context()).isMissing());
            assertTrue(navigator.probeVariablePath("core:items.output.rows[7]", context()).isMissing());
        }
    }

    @Nested
    @DisplayName("Agreement with the resolver")
    class AgreementWithResolver {

        /**
         * The invariant that keeps the probe honest: anything the resolver can produce a
         * value for must NOT be reported missing. The reverse is deliberately not
         * asserted, because a resolved path may legitimately hold null.
         */
        @Test
        @DisplayName("A path that resolves to a value is never reported missing")
        void resolvedPathsAreNeverReportedMissing() {
            Map<String, Object> context = context();
            List<String> paths = new ArrayList<>(List.of(
                    "trigger:move_a_task",
                    "trigger:move_a_task.iteration",
                    "trigger:move_a_task.output.name",
                    "trigger:move_a_task.name",
                    "trigger:move_a_task.output.nested.deep",
                    "trigger:move_a_task.nested.deep",
                    "core:items.output.rows",
                    "core:items.output.rows[0]",
                    "core:items.rows[1]",
                    "scalar",
                    "trigger:move_a_task.output.task",
                    "trigger:move_a_task.output.missing",
                    "unknown:node.output.field",
                    "scalar.deeper",
                    "core:items.output.rows[9]"
            ));

            for (String path : paths) {
                Object resolved = navigator.getVariableValueFromMap(path, context);
                PathProbe probe = navigator.probeVariablePath(path, context);
                if (resolved != null) {
                    assertFalse(probe.isMissing(),
                            "probe called '" + path + "' missing while the resolver returned " + resolved);
                }
            }
        }

        @Test
        @DisplayName("An empty or absent context reports the root, it does not throw")
        void degradesWithoutContext() {
            assertEquals(PathProbe.Status.MISSING_ROOT,
                    navigator.probeVariablePath("trigger:x.output.y", null).status());
            assertEquals(PathProbe.Status.MISSING_ROOT,
                    navigator.probeVariablePath("trigger:x.output.y", Map.of()).status());
            assertFalse(navigator.probeVariablePath(null, Map.of()).isMissing());
        }
    }
}
