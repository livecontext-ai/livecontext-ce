package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.agent.domain.OutputFieldDef;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.services.TemplateEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Tests for LoopNode including execute() and getNextNodes()/getSkippedChildNodes().
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("LoopNode")
class LoopNodeFailureTest {

    @Mock private TemplateEngine mockTemplateEngine;
    @Mock private WorkflowPlan mockPlan;

    private ExecutionNode bodyTarget;
    private ExecutionNode exitTarget;
    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        bodyTarget = mock(ExecutionNode.class);
        exitTarget = mock(ExecutionNode.class);
        context = ExecutionContext.create("run-1", "wr-1", "tenant-1", "item-0", 0, Map.of(), mockPlan);
    }

    private LoopNode buildLoop(String condition, int maxIterations) {
        LoopNode node = LoopNode.builder()
                .nodeId("core:my_loop")
                .loopCondition(condition)
                .maxIterations(maxIterations)
                .templateEngine(mockTemplateEngine)
                .build();
        node.addLoopBodyTarget(bodyTarget);
        node.addLoopExitTarget(exitTarget);
        return node;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // getNextNodes() tests (existing)
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getNextNodes")
    class GetNextNodesTests {

        @Test
        @DisplayName("getNextNodes on failure returns empty list")
        void getNextNodes_onFailure_returnsEmpty() {
            LoopNode loopNode = buildLoop("true", 5);
            NodeExecutionResult failResult = NodeExecutionResult.failure("core:my_loop", "error msg");

            List<ExecutionNode> nextNodes = loopNode.getNextNodes(failResult);

            assertTrue(nextNodes.isEmpty(),
                    "On failure, getNextNodes should return empty list to stop propagation");
        }

        @Test
        @DisplayName("getNextNodes on success with enter_body=true returns body targets")
        void getNextNodes_onSuccess_enterBody_returnsBodyTargets() {
            LoopNode loopNode = buildLoop("true", 5);
            Map<String, Object> output = Map.of(
                    "enter_body", true,
                    "terminated", false,
                    "node_type", "LOOP"
            );
            NodeExecutionResult successResult = NodeExecutionResult.success("core:my_loop", output);

            List<ExecutionNode> nextNodes = loopNode.getNextNodes(successResult);

            assertEquals(1, nextNodes.size(), "Should return body targets");
            assertSame(bodyTarget, nextNodes.get(0));
        }

        @Test
        @DisplayName("getNextNodes on success with terminated=true returns exit targets")
        void getNextNodes_onSuccess_terminated_returnsExitTargets() {
            LoopNode loopNode = buildLoop("true", 5);
            Map<String, Object> output = Map.of(
                    "enter_body", false,
                    "terminated", true,
                    "node_type", "LOOP"
            );
            NodeExecutionResult successResult = NodeExecutionResult.success("core:my_loop", output);

            List<ExecutionNode> nextNodes = loopNode.getNextNodes(successResult);

            assertEquals(1, nextNodes.size(), "Should return exit targets");
            assertSame(exitTarget, nextNodes.get(0));
        }

        @Test
        @DisplayName("getNextNodes on null output returns exit targets")
        void getNextNodes_onNullOutput_returnsExitTargets() {
            LoopNode loopNode = buildLoop("true", 5);
            List<ExecutionNode> nextNodes = loopNode.getNextNodes(null);

            assertEquals(1, nextNodes.size(), "Should return exit targets on null result");
            assertSame(exitTarget, nextNodes.get(0));
        }

        @Test
        @DisplayName("getNextNodes on failureWithOutput also returns empty list")
        void getNextNodes_onFailureWithOutput_returnsEmpty() {
            LoopNode loopNode = buildLoop("true", 5);
            NodeExecutionResult failResult = NodeExecutionResult.failureWithOutput(
                    "core:my_loop", "timeout", Map.of("iteration", 3), 1000);

            List<ExecutionNode> nextNodes = loopNode.getNextNodes(failResult);

            assertTrue(nextNodes.isEmpty(),
                    "On failure (even with output), getNextNodes should return empty list");
        }

        @Test
        @DisplayName("getNextNodes with enter_body=false routes to exit")
        void getNextNodes_enterBodyFalse_returnsExitTargets() {
            LoopNode loopNode = buildLoop("true", 5);
            Map<String, Object> output = Map.of(
                    "enter_body", false,
                    "terminated", false,
                    "node_type", "LOOP"
            );
            NodeExecutionResult successResult = NodeExecutionResult.success("core:my_loop", output);

            List<ExecutionNode> nextNodes = loopNode.getNextNodes(successResult);

            assertEquals(1, nextNodes.size(), "Should return exit targets when enter_body=false");
            assertSame(exitTarget, nextNodes.get(0));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // execute() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("execute()")
    class ExecuteTests {

        @Test
        @DisplayName("execute with condition evaluating to true sets enter_body=true, terminated=false, iteration=0")
        void execute_conditionTrue_enterBodyTrue() {
            LoopNode loopNode = buildLoop("{{counter < 5}}", 10);

            when(mockTemplateEngine.evaluateConditionWithDetailsWithMap(eq("{{counter < 5}}"), anyMap()))
                    .thenReturn(new TemplateEngine.ConditionEvaluationResult("{{counter < 5}}", "0 < 5", true, null));

            NodeExecutionResult result = loopNode.execute(context);

            assertTrue(result.isSuccess());
            Map<String, Object> output = result.output();
            assertEquals(true, output.get("enter_body"));
            assertEquals(false, output.get("terminated"));
            assertEquals(0, output.get("iteration"));
            assertEquals(10, output.get("maxIterations"));
            assertEquals("body", output.get("selected_path"));
            assertEquals("LOOP", output.get("node_type"));
            assertEquals("core:my_loop", output.get("loop_node"));
        }

        @Test
        @DisplayName("execute with condition evaluating to false sets terminated=true, enter_body=false")
        void execute_conditionFalse_terminated() {
            LoopNode loopNode = buildLoop("{{counter >= 5}}", 10);

            when(mockTemplateEngine.evaluateConditionWithDetailsWithMap(eq("{{counter >= 5}}"), anyMap()))
                    .thenReturn(new TemplateEngine.ConditionEvaluationResult("{{counter >= 5}}", "0 >= 5", false, null));

            NodeExecutionResult result = loopNode.execute(context);

            assertTrue(result.isSuccess());
            Map<String, Object> output = result.output();
            assertEquals(false, output.get("enter_body"));
            assertEquals(true, output.get("terminated"));
            assertEquals("exit", output.get("selected_path"));
        }

        @Test
        @DisplayName("execute with null condition defaults to enter_body=true")
        void execute_nullCondition_enterBodyTrue() {
            LoopNode loopNode = buildLoop(null, 10);

            NodeExecutionResult result = loopNode.execute(context);

            assertTrue(result.isSuccess());
            Map<String, Object> output = result.output();
            assertEquals(true, output.get("enter_body"));
            assertEquals(false, output.get("terminated"));
        }

        @Test
        @DisplayName("execute with blank condition defaults to enter_body=true")
        void execute_blankCondition_enterBodyTrue() {
            LoopNode loopNode = buildLoop("   ", 10);

            NodeExecutionResult result = loopNode.execute(context);

            assertTrue(result.isSuccess());
            Map<String, Object> output = result.output();
            assertEquals(true, output.get("enter_body"));
            assertEquals(false, output.get("terminated"));
        }

        @Test
        @DisplayName("execute with maxIterations=0 sets enter_body=false regardless of condition")
        void execute_maxIterationsZero_enterBodyFalse() {
            LoopNode loopNode = buildLoop("true", 0);

            // templateEngine should NOT be called when maxIterations=0
            NodeExecutionResult result = loopNode.execute(context);

            assertTrue(result.isSuccess());
            Map<String, Object> output = result.output();
            assertEquals(false, output.get("enter_body"));
            assertEquals(true, output.get("terminated"));
            assertEquals(0, output.get("maxIterations"));
            verifyNoInteractions(mockTemplateEngine);
        }

        @Test
        @DisplayName("execute with condition that throws exception sets enter_body=false (graceful degradation)")
        void execute_conditionThrows_enterBodyFalse() {
            LoopNode loopNode = buildLoop("{{bad_expr}}", 10);

            when(mockTemplateEngine.evaluateConditionWithDetailsWithMap(eq("{{bad_expr}}"), anyMap()))
                    .thenThrow(new RuntimeException("SpEL parse error"));

            NodeExecutionResult result = loopNode.execute(context);

            assertTrue(result.isSuccess());
            Map<String, Object> output = result.output();
            assertEquals(false, output.get("enter_body"));
            assertEquals(true, output.get("terminated"));
            assertEquals("exit", output.get("selected_path"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // getSkippedChildNodes() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getSkippedChildNodes")
    class GetSkippedChildNodesTests {

        @Test
        @DisplayName("getSkippedChildNodes with enter_body=true returns empty (body not skipped)")
        void getSkippedChildNodes_enterBodyTrue_returnsEmpty() {
            LoopNode loopNode = buildLoop("true", 5);
            Map<String, Object> output = Map.of("enter_body", true);
            NodeExecutionResult result = NodeExecutionResult.success("core:my_loop", output);

            List<ExecutionNode> skipped = loopNode.getSkippedChildNodes(result);

            assertTrue(skipped.isEmpty(), "Body targets should not be skipped when entering body");
        }

        @Test
        @DisplayName("getSkippedChildNodes with enter_body=false returns body targets")
        void getSkippedChildNodes_enterBodyFalse_returnsBodyTargets() {
            LoopNode loopNode = buildLoop("false", 5);
            Map<String, Object> output = Map.of("enter_body", false);
            NodeExecutionResult result = NodeExecutionResult.success("core:my_loop", output);

            List<ExecutionNode> skipped = loopNode.getSkippedChildNodes(result);

            assertEquals(1, skipped.size());
            assertSame(bodyTarget, skipped.get(0));
        }

        @Test
        @DisplayName("getSkippedChildNodes with null result returns body targets")
        void getSkippedChildNodes_nullResult_returnsBodyTargets() {
            LoopNode loopNode = buildLoop("true", 5);

            List<ExecutionNode> skipped = loopNode.getSkippedChildNodes(null);

            assertEquals(1, skipped.size());
            assertSame(bodyTarget, skipped.get(0));
        }

        @Test
        @DisplayName("getSkippedChildNodes with null output returns body targets")
        void getSkippedChildNodes_nullOutput_returnsBodyTargets() {
            LoopNode loopNode = buildLoop("true", 5);
            NodeExecutionResult result = NodeExecutionResult.success("core:my_loop", null);

            List<ExecutionNode> skipped = loopNode.getSkippedChildNodes(result);

            assertEquals(1, skipped.size());
            assertSame(bodyTarget, skipped.get(0));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Output schema contract: LoopNodeSpec <-> runtime (execute() + BackEdgeHandler)
    // Guards the link the 3-way coherence test does NOT cover (it only checks
    // spec <-> node_type_documentation <-> /api/node-definitions, never the runtime).
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("output schema contract")
    class OutputSchemaContractTests {

        private Set<String> specOutputKeys() {
            return new LoopNodeSpec().definition().outputs().stream()
                .map(OutputFieldDef::key)
                .collect(Collectors.toCollection(TreeSet::new));
        }

        /**
         * What the node emits that is NOT part of its persisted output, deliberately.
         *
         * <p>{@code GenericOutputSchemaMapper} builds the persisted JSONB from the SPEC
         * alone, so an undeclared key never reaches {@code {{core:loop.output....}}}.
         * These six are read straight off the raw result by
         * {@code StepDataPersistenceService.enrichLoopFields} and land in the step row's
         * columns instead, exactly as a decision's {@code condition_expression} and
         * {@code condition_result} always have. They are inspector fields, not an output
         * contract, and declaring them would mean a migration, a frontend schema entry
         * and a doc change for something no expression should address.
         */
        private static final Set<String> INSPECTOR_ONLY_KEYS = Set.of(
            "loop_condition", "max_iterations",
            "condition_expression", "condition_resolved", "condition_result",
            "evaluations");

        /** Engine envelope, stripped before persistence for every node. */
        private static final Set<String> ENVELOPE_KEYS = Set.of(
            "node_type", "resolved_params", "loop_node", "item_index", "itemIndex", "item_id");

        @Test
        @DisplayName("LoopNodeSpec declares exactly the runtime output keys, incl. the termination-only reason")
        void specDeclaresExactRuntimeKeys() {
            assertEquals(
                Set.of("iteration", "maxIterations", "terminated", "enter_body", "selected_path", "reason"),
                specOutputKeys(),
                "LoopNodeSpec must stay aligned with LoopNode.execute() + BackEdgeHandler output keys; "
                    + "if this changes, also update V358 node_type_documentation and docs/node-schemas/loop.md");
        }

        /**
         * The half the assertion above cannot see.
         *
         * <p>It compares the spec against a hardcoded literal, so the spec and the
         * literal can agree while {@code execute()} emits something neither mentions:
         * six keys were added that way and this class stayed green, with its own failure
         * message still telling the reader to keep three artefacts aligned. Reading the
         * runtime is what makes the guard bite.
         */
        @Test
        @DisplayName("execute() emits nothing the spec has not declared, beyond the inspector-only keys")
        void executeEmitsNothingUndeclaredAndUnlisted() {
            Set<String> emitted = new TreeSet<>(buildLoop(null, 5).execute(context).output().keySet());
            emitted.removeAll(specOutputKeys());
            emitted.removeAll(INSPECTOR_ONLY_KEYS);
            emitted.removeAll(ENVELOPE_KEYS);

            assertTrue(emitted.isEmpty(),
                "execute() emits " + emitted + ", which is neither declared in LoopNodeSpec nor listed as "
                    + "inspector-only. Declare it (and update node_type_documentation + "
                    + "docs/node-schemas/loop.md + the frontend schema), or add it to INSPECTOR_ONLY_KEYS "
                    + "with the reason it is not addressable as an output.");
        }

        @Test
        @DisplayName("execute() (first entry) emits every declared schema key except the termination-only reason")
        void executeEmitsDeclaredKeysExceptReason() {
            // null condition + maxIterations>0 → enters body without touching the template engine.
            LoopNode loopNode = buildLoop(null, 5);

            Map<String, Object> output = loopNode.execute(context).output();

            // Every non-reason schema key is present on first entry (a rename here silently
            // breaks {{core:loop.output.<key>}} bindings - the exact loop.md drift this guards).
            for (String key : List.of("iteration", "maxIterations", "terminated", "enter_body", "selected_path")) {
                assertTrue(output.containsKey(key),
                    "execute() must emit declared schema key '" + key + "'");
            }
            // ...and reason is NOT present on first entry - BackEdgeHandler writes it only at termination.
            assertFalse(output.containsKey("reason"),
                "reason is termination-only (written by BackEdgeHandler), never on first entry");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // resolved_params - what the inspector's Params column reads back
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("resolved_params reporting")
    class ResolvedParamsTests {

        @Test
        @DisplayName("execute reports loopCondition and maxIterations under the plan's key names")
        void executeReportsLoopConfigurationUnderPlanKeyNames() {
            LoopNode loopNode = LoopNode.builder()
                    .nodeId("core:my_loop")
                    .loopCondition("{{counter < 5}}")
                    .maxIterations(7)
                    .templateEngine(mockTemplateEngine)
                    .build();
            // No template adapter on purpose. The resolved condition now comes from the
            // evaluation that decided whether to enter the body, so the adapter - a second
            // resolver that renders an absent value as an empty string where the evaluator
            // renders it as null - is not consulted. Stubbing it would be an unused stub,
            // which is the cleanest possible proof that the second resolution is gone.
            when(mockTemplateEngine.evaluateConditionWithDetailsWithMap(eq("{{counter < 5}}"), anyMap()))
                    .thenReturn(new TemplateEngine.ConditionEvaluationResult("{{counter < 5}}", "0 < 5", true, null));

            @SuppressWarnings("unchecked")
            Map<String, Object> params =
                    (Map<String, Object>) loopNode.execute(context).output().get("resolved_params");

            assertEquals("0 < 5", params.get("loopCondition"),
                    "loopCondition carries the RESOLVED condition, so the reader sees the values it ran on");
            assertEquals(7, params.get("maxIterations"));
            // No `strategy` key: WorkflowPlanParser fabricates "continue-anyway"
            // for every loop whatever the plan holds, the builder never writes one,
            // and nothing in the engine reads it. Reporting it would tell every
            // reader they chose a setting they cannot even set - and in split
            // vocabulary at that.
            assertFalse(params.containsKey("strategy"));
        }

        @Test
        @DisplayName("execute reports a loop with no condition as \"(none)\" rather than omitting it")
        void executeReportsAbsentConditionExplicitly() {
            LoopNode loopNode = buildLoop(null, 5);

            @SuppressWarnings("unchecked")
            Map<String, Object> params =
                    (Map<String, Object>) loopNode.execute(context).output().get("resolved_params");

            // "repeat N times" is a real configuration, and an absent condition is
            // part of it: blanking the row would read as "this loop was not set up".
            // One spelling across both panels: the evaluation entry says "(no condition)"
            // and so does this. The BackEdgeHandler termination row keeps "(none)" for the
            // CONFIGURED expression, which is a different value, not a second spelling.
            assertEquals("(no condition)", params.get("loopCondition"));
            assertEquals(5, params.get("maxIterations"));
        }
    }

    /**
     * A template adapter that resolves every string to {@code value}.
     *
     * <p>Without one, {@code BaseNode.resolveTemplateString} returns the template
     * verbatim, and a test asserting on resolved_params would pin the RAW expression
     * while the product shows the resolved one.
     */
    private com.apimarketplace.orchestrator.execution.v2.template.V2TemplateAdapter adapterResolvingTo(String value) {
        var adapter = mock(com.apimarketplace.orchestrator.execution.v2.template.V2TemplateAdapter.class);
        when(adapter.resolveTemplates(any(), any())).thenReturn(Map.of("__v__", value));
        return adapter;
    }
}
