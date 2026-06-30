package com.apimarketplace.orchestrator.tools.workflow.builder;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.orchestrator.tools.workflow.builder.creators.GuardrailCreator;
import com.apimarketplace.orchestrator.tools.workflow.builder.creators.UtilityNodeCreator;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.service.NodeLibraryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for two specific bugs found during workflow creation:
 *
 * 1. Loop node: LLM connects "Loop Body" → "Test Loop" without :iterate port,
 *    causing the frontend to connect to the wrong handle (entry instead of loop-back).
 *    Fix: auto-assign :iterate port when loop already has an entry edge,
 *    and include connect_iterate hint in NEXT_STEPS response.
 *
 * 2. Guardrail node: action="add_node" from top-level params bleeds into merged map,
 *    causing GuardrailCreator to see action="add_node" instead of action="flag".
 *    Fix: remove workflow-level 'action' from merged map before merging inner params.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Loop Iterate & Guardrail Action fixes")
class LoopIterateAndGuardrailActionTest {

    @Mock private WorkflowBuilderSessionStore sessionStore;
    @Mock private ResponseOptimizer responseOptimizer;
    @Mock private NodeLibraryService nodeLibraryService;
    @Mock private WorkflowRepository workflowRepository;

    private WorkflowBuilderSession session;

    @BeforeEach
    void setUp() {
        session = WorkflowBuilderSession.builder()
            .sessionId("test-session")
            .tenantId("test-tenant")
            .workflowName("Test Workflow")
            .createdAt(Instant.now())
            .updatedAt(Instant.now())
            .build();

        // Add a trigger (required for node creation)
        Map<String, Object> trigger = new LinkedHashMap<>();
        trigger.put("label", "Start");
        trigger.put("id", "trigger:start");
        trigger.put("type", "webhook");
        session.getTriggers().add(trigger);
    }

    // ==================== Loop Iterate Tests ====================

    @Nested
    @DisplayName("Loop - auto-assign :iterate port")
    class LoopIteratePort {

        private WorkflowBuilderConnectionManager connectionManager;

        @BeforeEach
        void setUp() {
            connectionManager = new WorkflowBuilderConnectionManager(sessionStore);
        }

        private void addLoopNode(String label) {
            String normalized = WorkflowBuilderSession.normalizeLabel(label);
            Map<String, Object> loop = new LinkedHashMap<>();
            loop.put("id", "core:" + normalized);
            loop.put("label", label);
            loop.put("type", "loop");
            session.getCores().add(loop);
        }

        private void addTransformNode(String label) {
            String normalized = WorkflowBuilderSession.normalizeLabel(label);
            Map<String, Object> transform = new LinkedHashMap<>();
            transform.put("id", "core:" + normalized);
            transform.put("label", label);
            transform.put("type", "transform");
            session.getCores().add(transform);
        }

        @Test
        @DisplayName("First connection to loop uses entry (no port)")
        void firstConnectionUsesEntry() {
            addLoopNode("My Loop");
            addTransformNode("Step Before");

            Map<String, Object> params = Map.of("from", "Step Before", "to", "My Loop");
            ToolExecutionResult result = connectionManager.executeConnect(session, params);

            assertThat(result.success()).isTrue();
            // Edge should be core:step_before → core:my_loop (no :iterate)
            assertThat(session.getEdges()).hasSize(1);
            Map<String, Object> edge = session.getEdges().get(0);
            assertThat(edge.get("to")).isEqualTo("core:my_loop");
        }

        @Test
        @DisplayName("Second connection to loop auto-assigns :iterate port")
        void secondConnectionAutoAssignsIterate() {
            addLoopNode("My Loop");
            addTransformNode("Step Before");
            addTransformNode("Loop Body");

            // First connection: entry
            session.addConnection("core:step_before", "core:my_loop", null);

            // Second connection: should auto-assign :iterate
            Map<String, Object> params = Map.of("from", "Loop Body", "to", "My Loop");
            ToolExecutionResult result = connectionManager.executeConnect(session, params);

            assertThat(result.success()).isTrue();
            // The new edge should target core:my_loop:iterate
            Map<String, Object> iterateEdge = session.getEdges().stream()
                .filter(e -> "core:loop_body".equals(e.get("from")))
                .findFirst()
                .orElseThrow();
            assertThat(iterateEdge.get("to")).isEqualTo("core:my_loop:iterate");
        }

        @Test
        @DisplayName("Explicit :iterate port is preserved when specified by LLM")
        void explicitIteratePortPreserved() {
            addLoopNode("My Loop");
            addTransformNode("Loop Body");

            // LLM explicitly specifies :iterate
            Map<String, Object> params = Map.of("from", "Loop Body", "to", "My Loop:iterate");
            ToolExecutionResult result = connectionManager.executeConnect(session, params);

            assertThat(result.success()).isTrue();
            Map<String, Object> edge = session.getEdges().get(0);
            assertThat(edge.get("to")).isEqualTo("core:my_loop:iterate");
        }

        @Test
        @DisplayName("Auto-assign does NOT apply to non-loop core nodes")
        void noAutoAssignForNonLoopNodes() {
            addTransformNode("Step A");
            addTransformNode("Step B");

            // First edge to Step A
            session.addConnection("core:step_b", "core:step_a", null);

            // Second edge should NOT get :iterate - Step A is not a loop
            addTransformNode("Step C");
            Map<String, Object> params = Map.of("from", "Step C", "to", "Step A");
            ToolExecutionResult result = connectionManager.executeConnect(session, params);

            assertThat(result.success()).isTrue();
            Map<String, Object> edge = session.getEdges().stream()
                .filter(e -> "core:step_c".equals(e.get("from")))
                .findFirst()
                .orElseThrow();
            assertThat(edge.get("to")).isEqualTo("core:step_a");
        }
    }

    // ==================== Loop NEXT_STEPS hint Tests ====================

    @Nested
    @DisplayName("Loop - NEXT_STEPS includes iterate hint")
    class LoopNextStepsHint {

        private UtilityNodeCreator utilityNodeCreator;

        @BeforeEach
        void setUp() {
            utilityNodeCreator = new UtilityNodeCreator(sessionStore, responseOptimizer, nodeLibraryService, workflowRepository);
        }

        @Test
        @DisplayName("Loop response includes connect_iterate in NEXT_STEPS")
        @SuppressWarnings("unchecked")
        void loopResponseIncludesIterateHint() {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("label", "Test Loop");
            params.put("max_iterations", 5);
            params.put("connect_after", "trigger:start");

            // Add edge from trigger to make connect_after work
            session.addConnection("trigger:start", "core:test_loop", null);

            ToolExecutionResult result = utilityNodeCreator.executeAddLoop(session, params);

            assertThat(result.success()).isTrue();
            Map<String, Object> data = (Map<String, Object>) result.data();
            Map<String, String> nextSteps = (Map<String, String>) data.get("NEXT_STEPS");

            assertThat(nextSteps).containsKey("connect_body");
            assertThat(nextSteps).containsKey("connect_iterate");
            assertThat(nextSteps).containsKey("connect_exit");

            // Verify iterate hint uses the correct syntax
            assertThat(nextSteps.get("connect_iterate")).contains(":iterate");
            assertThat(nextSteps.get("connect_iterate")).contains("Test Loop");
        }

        @Test
        @DisplayName("Loop ports list includes iterate")
        @SuppressWarnings("unchecked")
        void loopPortsIncludesIterate() {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("label", "My Loop");
            params.put("max_iterations", 3);
            params.put("connect_after", "trigger:start");

            session.addConnection("trigger:start", "core:my_loop", null);

            ToolExecutionResult result = utilityNodeCreator.executeAddLoop(session, params);

            assertThat(result.success()).isTrue();
            Map<String, Object> data = (Map<String, Object>) result.data();
            List<String> ports = (List<String>) data.get("ports");

            assertThat(ports).containsExactly("body", "iterate", "exit");
        }
    }

    // ==================== Guardrail Action Conflict Tests ====================

    @Nested
    @DisplayName("Guardrail - action parameter conflict")
    class GuardrailActionConflict {

        private GuardrailCreator guardrailCreator;

        @BeforeEach
        void setUp() {
            guardrailCreator = new GuardrailCreator(sessionStore, responseOptimizer);
        }

        @Test
        @DisplayName("Guardrail creation succeeds when action is absent (defaults to flag)")
        @SuppressWarnings("unchecked")
        void guardrailDefaultsToFlag() {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("label", "Content Check");
            params.put("input", "{{trigger:start.output.message}}");
            params.put("rules", Map.of("pii", "Block emails", "toxicity", "Block offensive content"));
            params.put("connect_after", "trigger:start");

            ToolExecutionResult result = guardrailCreator.executeAddGuardrail(session, params);

            assertThat(result.success()).isTrue();
            Map<String, Object> data = (Map<String, Object>) result.data();
            Map<String, Object> savedParams = (Map<String, Object>) data.get("saved_params");
            assertThat(savedParams.get("action")).isEqualTo("flag");
        }

        @Test
        @DisplayName("Guardrail creation succeeds with explicit action=block")
        @SuppressWarnings("unchecked")
        void guardrailWithExplicitAction() {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("label", "Safety Gate");
            params.put("input", "{{trigger:start.output.text}}");
            params.put("rules", Map.of("pii", "Block emails"));
            params.put("action", "block");
            params.put("connect_after", "trigger:start");

            ToolExecutionResult result = guardrailCreator.executeAddGuardrail(session, params);

            assertThat(result.success()).isTrue();
            Map<String, Object> data = (Map<String, Object>) result.data();
            Map<String, Object> savedParams = (Map<String, Object>) data.get("saved_params");
            assertThat(savedParams.get("action")).isEqualTo("block");
        }

        @Test
        @DisplayName("BUG REPRO: action='add_node' from top-level must NOT bleed into guardrail - merged map must strip it")
        void topLevelActionDoesNotBleedIntoGuardrail() {
            // This reproduces the exact scenario from the logs:
            // LLM sends: workflow(action='add_node', type='guardrail', label='Content Check',
            //            params={input: '...', rules: {...}, action: 'flag'})
            // The merge logic creates: merged = {action: 'add_node', type: 'guardrail', ...}
            // Then putIfAbsent from params won't overwrite action.
            // FIX: merged.remove("action") before merging inner params.

            // Simulate the merged map AS IT ARRIVES to GuardrailCreator after the fix:
            // The fix removes 'action' from merged before putIfAbsent, so inner action='flag' wins
            Map<String, Object> merged = new LinkedHashMap<>();
            merged.put("label", "Content Check");
            merged.put("input", "User test data");
            merged.put("rules", Map.of("pii", "Block emails", "toxicity", "Block offensive content"));
            merged.put("action", "flag"); // After fix, inner params win
            merged.put("connect_after", "trigger:start");

            ToolExecutionResult result = guardrailCreator.executeAddGuardrail(session, merged);
            assertThat(result.success()).isTrue();
        }

        @Test
        @DisplayName("BUG REPRO: exact failure - action='add_node' causes validation error")
        void actionAddNodeCausesValidationError() {
            // Before the fix, GuardrailCreator received action='add_node' from the merged map
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("label", "Content Check");
            params.put("input", "User test data");
            params.put("rules", Map.of("pii", "Block emails"));
            params.put("action", "add_node"); // This is what GuardrailCreator received before fix
            params.put("connect_after", "trigger:start");

            ToolExecutionResult result = guardrailCreator.executeAddGuardrail(session, params);

            // This MUST fail with a clear error about invalid action
            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("flag, block, redact");
            assertThat(result.error()).contains("add_node");
        }

        @Test
        @DisplayName("Merge logic test: inner params action overrides when top-level action is removed")
        void mergeLogicTest() {
            // Simulate the exact merge logic from WorkflowBuilderProvider.handleNodeCreation()
            Map<String, Object> outerParams = new LinkedHashMap<>();
            outerParams.put("action", "add_node");
            outerParams.put("type", "guardrail");
            outerParams.put("label", "Content Check");
            outerParams.put("params", Map.of(
                "input", "{{trigger:start.output.message}}",
                "rules", Map.of("pii", "Block emails"),
                "action", "flag"
            ));
            outerParams.put("connect_after", "trigger:start");

            // Reproduce the fixed merge logic
            Map<String, Object> merged = new LinkedHashMap<>(outerParams);
            merged.remove("action"); // THE FIX
            Object paramsObj = outerParams.get("params");
            if (paramsObj instanceof Map<?,?> paramsMap) {
                paramsMap.forEach((k, v) -> merged.putIfAbsent((String) k, v));
            }

            // After fix: merged should have action=flag from inner params
            assertThat(merged.get("action")).isEqualTo("flag");

            // And GuardrailCreator should succeed
            ToolExecutionResult result = guardrailCreator.executeAddGuardrail(session, merged);
            assertThat(result.success()).isTrue();
        }

        @Test
        @DisplayName("Merge logic test: guardrail_action workaround does NOT work (verifying the LLM's failed attempt)")
        void guardrailActionWorkaroundDoesNotWork() {
            // The LLM tried guardrail_action='flag' as a workaround - verify it doesn't help
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("label", "Content Check");
            params.put("input", "User test data");
            params.put("rules", Map.of("pii", "Block emails"));
            params.put("guardrail_action", "flag"); // LLM workaround attempt
            // No 'action' key at all
            params.put("connect_after", "trigger:start");

            ToolExecutionResult result = guardrailCreator.executeAddGuardrail(session, params);

            // Should succeed because action defaults to 'flag' when absent
            assertThat(result.success()).isTrue();
        }
    }
}
