package com.apimarketplace.orchestrator.domain.workflow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Back-edge recognition and resolution.
 *
 * <p>Covers the two authored shapes collapsing into one descriptor, and the predicate that every
 * forward-dependency walk in the engine relies on to exclude loop-backs.
 */
@DisplayName("BackEdgeSpecs + WorkflowPlan.isBackEdge")
class BackEdgeSpecsTest {

    /**
     * Build the plan through the real parser rather than the 60-argument Core constructor: it
     * keeps these tests readable AND covers the parsing of the {@code backEdge} marker, which is
     * the other half of the contract.
     */
    private static WorkflowPlan planOf(List<Map<String, Object>> edges, List<Map<String, Object>> cores) {
        return WorkflowPlan.fromMap(Map.of("edges", edges, "cores", cores), "tenant");
    }

    private static Map<String, Object> edge(String from, String to) {
        return Map.of("from", from, "to", to);
    }

    private static Map<String, Object> loopCoreDef(String label, String condition, int maxIterations) {
        Map<String, Object> core = new java.util.LinkedHashMap<>();
        core.put("id", label);
        core.put("type", "loop");
        core.put("label", label);
        core.put("loopCondition", condition);
        core.put("maxIterations", maxIterations);
        return core;
    }

    @Nested
    @DisplayName("isBackEdge")
    class IsBackEdgeTests {

        @Test
        @DisplayName("recognises an edge into a loop node's iterate port")
        void recognisesIteratePortEdge() {
            assertTrue(WorkflowPlan.isBackEdge(new Edge("mcp:last", "core:retry:iterate")));
        }

        @Test
        @DisplayName("recognises an edge carrying the declared marker")
        void recognisesDeclaredMarker() {
            Edge declared = new Edge("core:check:else", "mcp:fetch", null,
                new Edge.BackEdge(null, 5));
            assertTrue(WorkflowPlan.isBackEdge(declared));
        }

        @Test
        @DisplayName("an ordinary forward edge is not a back-edge")
        void forwardEdgeIsNotBackEdge() {
            assertFalse(WorkflowPlan.isBackEdge(new Edge("mcp:a", "mcp:b")));
        }

        @Test
        @DisplayName("regression: a back-edge must not be mistaken for the target's params")
        void declaredMarkerDoesNotLeakIntoTargetParams() {
            // The marker used to live in edge.params, which getStepParams exposes as the TARGET
            // node's params - so drawing a loop-back rewrote that node's configuration.
            WorkflowPlan plan = planOf(List.of(Map.of(
                "from", "core:check:else",
                "to", "mcp:fetch",
                "params", Map.of("some_param", "value"),
                "backEdge", Map.of("maxIterations", 5)
            )), List.of());

            assertEquals(Map.of(), plan.getStepParams("mcp:fetch"),
                "A loop-back's params describe the EDGE and must never reach the node it re-enters");
        }

        @Test
        @DisplayName("regression: a templated maxIterations on the marker is kept for run time, not dropped")
        void templatedMarkerCapIsKept() {
            // An Integer cannot hold "{{...}}": it used to become null and the loop ran on the
            // run's default cap as if nothing had been written.
            WorkflowPlan plan = planOf(List.of(Map.of(
                "from", "core:check:else",
                "to", "mcp:fetch",
                "backEdge", Map.of("maxIterations", "{{core:cfg.output.passes}}")
            )), List.of());

            Edge parsed = plan.getEdges().get(0);
            assertNull(parsed.backEdge().maxIterations());
            assertEquals("{{core:cfg.output.passes}}", parsed.backEdge().maxIterationsTemplate());
        }

        @Test
        @DisplayName("regression: the legacy params-based marker is migrated and stripped")
        void legacyParamsMarkerIsMigratedAndStripped() {
            // Workflows saved by an earlier builder carry the marker inside params. They must
            // keep their loop AND stop injecting it into the node they re-enter.
            WorkflowPlan plan = planOf(List.of(Map.of(
                "from", "core:check:else",
                "to", "mcp:fetch",
                "params", Map.of("backEdge", true, "condition", "", "maxIterations", 6)
            )), List.of());

            Edge parsed = plan.getEdges().get(0);
            assertNotNull(parsed.backEdge(), "The legacy marker must survive as a real back-edge");
            assertEquals(6, parsed.backEdge().maxIterations());
            assertEquals(Map.of(), plan.getStepParams("mcp:fetch"));
        }
    }

    @Nested
    @DisplayName("forSource")
    class ForSourceTests {

        @Test
        @DisplayName("resolves a hub loop: body entry and exit come from the loop node")
        void resolvesHubLoop() {
            WorkflowPlan plan = planOf(List.of(
                edge("mcp:start", "core:retry"),
                edge("core:retry:body", "mcp:attempt"),
                edge("core:retry:exit", "mcp:done"),
                edge("mcp:attempt", "core:retry:iterate")
            ), List.of(loopCoreDef("retry", "{{x}} < 3", 7)));

            List<BackEdgeSpec> specs = BackEdgeSpecs.forSource(plan, "mcp:attempt");

            assertEquals(1, specs.size());
            BackEdgeSpec spec = specs.get(0);
            assertTrue(spec.hasHub());
            assertEquals("core:retry", spec.hubKey());
            assertEquals("mcp:attempt", spec.bodyEntryKey());
            assertEquals("mcp:done", spec.exitTargetKey());
            assertEquals("{{x}} < 3", spec.condition());
            assertEquals(7, spec.maxIterationsOverride());
        }

        @Test
        @DisplayName("resolves a hub-less back-edge: its own target is the re-entry point")
        void resolvesDeclaredBackEdge() {
            WorkflowPlan plan = planOf(List.of(
                edge("mcp:fetch", "core:check"),
                Map.of("from", "core:check:else", "to", "mcp:fetch",
                       "backEdge", Map.of("maxIterations", 4))
            ), List.of());

            List<BackEdgeSpec> specs = BackEdgeSpecs.forSource(plan, "core:check");

            assertEquals(1, specs.size());
            BackEdgeSpec spec = specs.get(0);
            assertFalse(spec.hasHub());
            assertEquals("mcp:fetch", spec.bodyEntryKey(),
                "The back-edge's own target is where execution re-enters");
            assertNull(spec.exitTargetKey(), "There is no exit port without a loop node");
            assertEquals("else", spec.sourcePort(),
                "The port must survive so the loop only fires when that branch is taken");
            assertEquals(4, spec.maxIterationsOverride());
        }

        @Test
        @DisplayName("a node with no loop-back resolves to nothing")
        void plainNodeHasNoSpecs() {
            WorkflowPlan plan = planOf(List.of(edge("mcp:a", "mcp:b")), List.of());
            assertTrue(BackEdgeSpecs.forSource(plan, "mcp:a").isEmpty());
        }
    }

    @Nested
    @DisplayName("capIsDeclaredTerminator")
    class CapIsDeclaredTerminatorTests {

        @Test
        @DisplayName("a loop node WITHOUT a condition means 'repeat N times': the cap is the exit")
        void hubWithoutConditionTreatsCapAsTheExit() {
            BackEdgeSpec spec = new BackEdgeSpec(null, "e", "mcp:a", null, "mcp:b",
                "core:loop", "  ", 3, "mcp:after");
            assertTrue(spec.capIsDeclaredTerminator(),
                "Reaching the cap fulfils the contract here, so the run must stay green");
        }

        @Test
        @DisplayName("a loop node WITH a condition treats the cap as a safety net")
        void hubWithConditionTreatsCapAsSafetyNet() {
            BackEdgeSpec spec = new BackEdgeSpec(null, "e", "mcp:a", null, "mcp:b",
                "core:loop", "{{x}} < 3", 3, "mcp:after");
            assertFalse(spec.capIsDeclaredTerminator(),
                "The author's stop signal is the condition; the cap firing means it never came");
        }

        /**
         * A While authored with the literal condition {@code true} is the commonest way to write
         * "repeat N times" in the builder, and it is what the cross-replica cap regression
         * ({@code BackEdgeHandlerTest$CrossReplicaProgressTests}) exercises. Reading that as a
         * live stop signal would fail the run at the exact moment the loop did what it was
         * authored to do, and would refuse the exit path that test requires.
         */
        @Test
        @DisplayName("a loop node whose condition can never be false counts as 'repeat N times'")
        void hubWithConstantTrueConditionTreatsCapAsTheExit() {
            for (String constantTrue : new String[]{"true", "TRUE", " true ", "{{ true }}"}) {
                BackEdgeSpec spec = new BackEdgeSpec(null, "e", "mcp:a", null, "mcp:b",
                    "core:loop", constantTrue, 60, "mcp:after");
                assertTrue(spec.capIsDeclaredTerminator(),
                    "'" + constantTrue + "' can never become false, so the cap is the only exit");
            }
        }

        @Test
        @DisplayName("a condition that merely mentions true stays a live condition")
        void hubWithConditionMentioningTrueStaysASafetyNet() {
            BackEdgeSpec spec = new BackEdgeSpec(null, "e", "mcp:a", null, "mcp:b",
                "core:loop", "{{ done == true }}", 60, "mcp:after");
            assertFalse(spec.capIsDeclaredTerminator(),
                "This one can go false, so reaching the cap means the stop signal never came");
        }

        @Test
        @DisplayName("a hub-less back-edge always treats the cap as a safety net")
        void hublessAlwaysTreatsCapAsSafetyNet() {
            // Its "condition" is the branch that keeps routing back, so an empty condition does
            // NOT mean "repeat N times" the way it does for a loop node.
            BackEdgeSpec spec = new BackEdgeSpec(null, "e", "core:check", "else", "mcp:fetch",
                null, null, 3, null);
            assertFalse(spec.capIsDeclaredTerminator());
        }
    }

    @Nested
    @DisplayName("LoopIterationLimits")
    class LimitsTests {

        @Test
        @DisplayName("a declared cap wins over the default")
        void declaredCapWins() {
            assertEquals(3, new LoopIterationLimits(10, 10_000).resolve(3));
        }

        @Test
        @DisplayName("no declared cap falls back to the default")
        void fallsBackToDefault() {
            assertEquals(10, new LoopIterationLimits(10, 10_000).resolve(null));
        }

        @Test
        @DisplayName("the hard ceiling clamps a declared cap")
        void hardCeilingClamps() {
            assertEquals(50, new LoopIterationLimits(10, 50).resolve(999));
        }

        @Test
        @DisplayName("the shipped ceiling is a no-op for anything the builder can author")
        void shippedCeilingIsNoOpForAuthorableValues() {
            // The builder allows 1..10000; a lower ceiling would silently shorten existing loops.
            assertEquals(10_000, LoopIterationLimits.FALLBACK.resolve(10_000));
        }
    }
}
