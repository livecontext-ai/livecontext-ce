package com.apimarketplace.orchestrator.tools.workflow.builder.validation;

import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSession;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderValidator.ValidationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Tests for GraphValidation.
 * Validates reachability analysis and cycle detection in workflow graphs.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GraphValidation")
class GraphValidationTest {

    @Mock
    private WorkflowBuilderSession session;

    private GraphValidation validator;

    @BeforeEach
    void setUp() {
        validator = new GraphValidation();
    }

    private void stubSession(List<Map<String, Object>> triggers,
                             List<Map<String, Object>> mcps,
                             List<Map<String, Object>> cores,
                             List<Map<String, Object>> edges) {
        when(session.getTriggers()).thenReturn(triggers);
        when(session.getMcps()).thenReturn(mcps);
        when(session.getCores()).thenReturn(cores);
        when(session.getInterfaces()).thenReturn(List.of());
        when(session.getTables()).thenReturn(List.of());
        when(session.getEdges()).thenReturn(edges);
    }

    @Nested
    @DisplayName("Reachability validation")
    class ReachabilityTests {

        @Test
        @DisplayName("Should not error when all nodes are reachable")
        void shouldNotErrorWhenAllNodesReachable() {
            stubSession(
                    List.of(Map.of("label", "Start")),
                    List.of(Map.of("label", "Step A"), Map.of("label", "Step B")),
                    List.of(),
                    List.of(
                            Map.of("from", "trigger:start", "to", "mcp:step_a"),
                            Map.of("from", "mcp:step_a", "to", "mcp:step_b")
                    )
            );

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).noneMatch(e ->
                    e.code().equals("UNREACHABLE_NODE"));
        }

        @Test
        @DisplayName("Should add error for unreachable node")
        void shouldAddErrorForUnreachableNode() {
            stubSession(
                    List.of(Map.of("label", "Start")),
                    List.of(Map.of("label", "Connected"), Map.of("label", "Orphan")),
                    List.of(),
                    List.of(Map.of("from", "trigger:start", "to", "mcp:connected"))
            );

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).anyMatch(e ->
                    e.code().equals("UNREACHABLE_NODE") &&
                    e.nodeId().equals("mcp:orphan"));
        }

        @Test
        @DisplayName("Should not flag triggers as unreachable")
        void shouldNotFlagTriggersAsUnreachable() {
            stubSession(
                    List.of(Map.of("label", "Start")),
                    List.of(),
                    List.of(),
                    List.of()
            );

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).noneMatch(e ->
                    e.code().equals("UNREACHABLE_NODE") &&
                    e.nodeId().contains("trigger:"));
        }

        @Test
        @DisplayName("Should detect unreachable core node")
        void shouldDetectUnreachableCoreNode() {
            stubSession(
                    List.of(Map.of("label", "Start")),
                    List.of(Map.of("label", "Step A")),
                    List.of(Map.of("label", "Floating Decision", "type", "decision")),
                    List.of(Map.of("from", "trigger:start", "to", "mcp:step_a"))
            );

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).anyMatch(e ->
                    e.code().equals("UNREACHABLE_NODE") &&
                    e.nodeId().equals("core:floating_decision"));
        }

        @Test
        @DisplayName("Should validate reachability in diamond graph")
        void shouldValidateReachabilityInDiamondGraph() {
            stubSession(
                    List.of(Map.of("label", "Start")),
                    List.of(
                            Map.of("label", "Left"),
                            Map.of("label", "Right"),
                            Map.of("label", "Merge")
                    ),
                    List.of(),
                    List.of(
                            Map.of("from", "trigger:start", "to", "mcp:left"),
                            Map.of("from", "trigger:start", "to", "mcp:right"),
                            Map.of("from", "mcp:left", "to", "mcp:merge"),
                            Map.of("from", "mcp:right", "to", "mcp:merge")
                    )
            );

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).noneMatch(e ->
                    e.code().equals("UNREACHABLE_NODE"));
        }

        @Test
        @DisplayName("Should handle multiple unreachable nodes")
        void shouldHandleMultipleUnreachableNodes() {
            stubSession(
                    List.of(Map.of("label", "Start")),
                    List.of(
                            Map.of("label", "Connected"),
                            Map.of("label", "Orphan A"),
                            Map.of("label", "Orphan B")
                    ),
                    List.of(),
                    List.of(Map.of("from", "trigger:start", "to", "mcp:connected"))
            );

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            long unreachableCount = result.getErrors().stream()
                    .filter(e -> e.code().equals("UNREACHABLE_NODE"))
                    .count();
            assertThat(unreachableCount).isEqualTo(2);
        }

        @Test
        @DisplayName("Should handle reachability through port-based edges")
        void shouldHandleReachabilityThroughPortBasedEdges() {
            stubSession(
                    List.of(Map.of("label", "Start")),
                    List.of(Map.of("label", "Success"), Map.of("label", "Failure")),
                    List.of(Map.of("label", "Check", "type", "decision")),
                    List.of(
                            Map.of("from", "trigger:start", "to", "core:check"),
                            Map.of("from", "core:check:if", "to", "mcp:success"),
                            Map.of("from", "core:check:else", "to", "mcp:failure")
                    )
            );

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).noneMatch(e ->
                    e.code().equals("UNREACHABLE_NODE"));
        }
    }

    @Nested
    @DisplayName("Cycle detection")
    class CycleTests {

        @Test
        @DisplayName("Should not error in acyclic graph")
        void shouldNotErrorInAcyclicGraph() {
            stubSession(
                    List.of(Map.of("label", "Start")),
                    List.of(Map.of("label", "A"), Map.of("label", "B")),
                    List.of(),
                    List.of(
                            Map.of("from", "trigger:start", "to", "mcp:a"),
                            Map.of("from", "mcp:a", "to", "mcp:b")
                    )
            );

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).noneMatch(e ->
                    e.code().equals("CYCLE_DETECTED"));
        }

        @Test
        @DisplayName("Should detect cycle between mcp nodes")
        void shouldDetectCycleBetweenMcpNodes() {
            stubSession(
                    List.of(Map.of("label", "Start")),
                    List.of(Map.of("label", "A"), Map.of("label", "B")),
                    List.of(),
                    List.of(
                            Map.of("from", "trigger:start", "to", "mcp:a"),
                            Map.of("from", "mcp:a", "to", "mcp:b"),
                            Map.of("from", "mcp:b", "to", "mcp:a")
                    )
            );

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).anyMatch(e ->
                    e.code().equals("CYCLE_DETECTED"));
        }

        @Test
        @DisplayName("Should not flag core node cycles as errors")
        void shouldNotFlagCoreNodeCyclesAsErrors() {
            // Cycles involving core: nodes (like loop body -> loop) are allowed
            stubSession(
                    List.of(Map.of("label", "Start")),
                    List.of(Map.of("label", "Body Step")),
                    List.of(Map.of("label", "Process", "type", "loop")),
                    List.of(
                            Map.of("from", "trigger:start", "to", "core:process"),
                            Map.of("from", "core:process:body", "to", "mcp:body_step"),
                            Map.of("from", "mcp:body_step", "to", "core:process")
                    )
            );

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            // Cycle contains "core:" so it should not be flagged
            assertThat(result.getErrors()).noneMatch(e ->
                    e.code().equals("CYCLE_DETECTED"));
        }

        @Test
        @DisplayName("Should handle empty graph without errors")
        void shouldHandleEmptyGraphWithoutErrors() {
            stubSession(
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of()
            );

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).noneMatch(e ->
                    e.code().equals("CYCLE_DETECTED"));
        }

        @Test
        @DisplayName("Should detect longer cycle (A -> B -> C -> A)")
        void shouldDetectLongerCycle() {
            stubSession(
                    List.of(Map.of("label", "Start")),
                    List.of(
                            Map.of("label", "A"),
                            Map.of("label", "B"),
                            Map.of("label", "C")
                    ),
                    List.of(),
                    List.of(
                            Map.of("from", "trigger:start", "to", "mcp:a"),
                            Map.of("from", "mcp:a", "to", "mcp:b"),
                            Map.of("from", "mcp:b", "to", "mcp:c"),
                            Map.of("from", "mcp:c", "to", "mcp:a")
                    )
            );

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).anyMatch(e ->
                    e.code().equals("CYCLE_DETECTED"));
        }
    }

    @Nested
    @DisplayName("Validate with shared graph analyzer")
    class SharedGraphAnalyzerTests {

        @Test
        @DisplayName("Should work correctly with pre-built graph analyzer")
        void shouldWorkWithPreBuiltGraphAnalyzer() {
            stubSession(
                    List.of(Map.of("label", "Start")),
                    List.of(Map.of("label", "Step")),
                    List.of(),
                    List.of(Map.of("from", "trigger:start", "to", "mcp:step"))
            );

            ValidationGraphAnalyzer graph = new ValidationGraphAnalyzer(session);
            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, graph, result);

            assertThat(result.getErrors()).isEmpty();
        }

        @Test
        @DisplayName("Should detect unreachable node via shared analyzer")
        void shouldDetectUnreachableNodeViaSharedAnalyzer() {
            stubSession(
                    List.of(Map.of("label", "Start")),
                    List.of(Map.of("label", "Connected"), Map.of("label", "Orphan")),
                    List.of(),
                    List.of(Map.of("from", "trigger:start", "to", "mcp:connected"))
            );

            ValidationGraphAnalyzer graph = new ValidationGraphAnalyzer(session);
            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, graph, result);

            assertThat(result.getErrors()).anyMatch(e ->
                    e.code().equals("UNREACHABLE_NODE") &&
                    e.nodeId().equals("mcp:orphan"));
        }
    }
}
