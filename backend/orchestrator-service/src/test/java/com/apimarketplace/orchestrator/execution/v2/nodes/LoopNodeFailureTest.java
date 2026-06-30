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

        @Test
        @DisplayName("LoopNodeSpec declares exactly the runtime output keys, incl. the termination-only reason")
        void specDeclaresExactRuntimeKeys() {
            assertEquals(
                Set.of("iteration", "maxIterations", "terminated", "enter_body", "selected_path", "reason"),
                specOutputKeys(),
                "LoopNodeSpec must stay aligned with LoopNode.execute() + BackEdgeHandler output keys; "
                    + "if this changes, also update V358 node_type_documentation and docs/node-schemas/loop.md");
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
}
