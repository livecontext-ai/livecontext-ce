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

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Tests for EdgeValidator.
 * Validates edge source/target existence, self-loop detection, and incoming edge constraints.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EdgeValidator")
class EdgeValidatorTest {

    @Mock
    private WorkflowBuilderSession session;

    private EdgeValidator validator;

    @BeforeEach
    void setUp() {
        validator = new EdgeValidator();
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
    @DisplayName("Edge source and target validation")
    class EdgeReferenceTests {

        @Test
        @DisplayName("Should not add errors for valid edges")
        void shouldNotAddErrorsForValidEdges() {
            stubSession(
                    List.of(Map.of("label", "Start")),
                    List.of(Map.of("label", "Step One")),
                    List.of(),
                    List.of(Map.of("from", "trigger:start", "to", "mcp:step_one"))
            );

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).noneMatch(e ->
                    e.code().equals("INVALID_EDGE_SOURCE") || e.code().equals("INVALID_EDGE_TARGET"));
        }

        @Test
        @DisplayName("Should add error for invalid edge source")
        void shouldAddErrorForInvalidEdgeSource() {
            stubSession(
                    List.of(Map.of("label", "Start")),
                    List.of(Map.of("label", "Step One")),
                    List.of(),
                    List.of(Map.of("from", "mcp:non_existent", "to", "mcp:step_one"))
            );

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).anyMatch(e ->
                    e.code().equals("INVALID_EDGE_SOURCE"));
        }

        @Test
        @DisplayName("Should add error for invalid edge target")
        void shouldAddErrorForInvalidEdgeTarget() {
            stubSession(
                    List.of(Map.of("label", "Start")),
                    List.of(),
                    List.of(),
                    List.of(Map.of("from", "trigger:start", "to", "mcp:non_existent"))
            );

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).anyMatch(e ->
                    e.code().equals("INVALID_EDGE_TARGET"));
        }

        @Test
        @DisplayName("Should not error for null from field")
        void shouldNotErrorForNullFromField() {
            Map<String, Object> edge = new HashMap<>();
            edge.put("from", null);
            edge.put("to", "mcp:step_one");

            stubSession(
                    List.of(),
                    List.of(Map.of("label", "Step One")),
                    List.of(),
                    List.of(edge)
            );

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).noneMatch(e ->
                    e.code().equals("INVALID_EDGE_SOURCE"));
        }

        @Test
        @DisplayName("Should not error for null to field")
        void shouldNotErrorForNullToField() {
            Map<String, Object> edge = new HashMap<>();
            edge.put("from", "trigger:start");
            edge.put("to", null);

            stubSession(
                    List.of(Map.of("label", "Start")),
                    List.of(),
                    List.of(),
                    List.of(edge)
            );

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).noneMatch(e ->
                    e.code().equals("INVALID_EDGE_TARGET"));
        }

        @Test
        @DisplayName("Should accept port-based edge source for existing core node")
        void shouldAcceptPortBasedEdgeSourceForExistingCoreNode() {
            stubSession(
                    List.of(Map.of("label", "Start")),
                    List.of(Map.of("label", "Success")),
                    List.of(Map.of("label", "Check", "type", "decision")),
                    List.of(
                            Map.of("from", "trigger:start", "to", "core:check"),
                            Map.of("from", "core:check:if", "to", "mcp:success")
                    )
            );

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).noneMatch(e ->
                    e.code().equals("INVALID_EDGE_SOURCE"));
        }
    }

    @Nested
    @DisplayName("Self-loop detection")
    class SelfLoopTests {

        @Test
        @DisplayName("Should add error for non-core self-loop")
        void shouldAddErrorForNonCoreSelfLoop() {
            stubSession(
                    List.of(Map.of("label", "Start")),
                    List.of(Map.of("label", "Loopy")),
                    List.of(),
                    List.of(Map.of("from", "mcp:loopy", "to", "mcp:loopy"))
            );

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).anyMatch(e ->
                    e.code().equals("SELF_LOOP"));
        }

        @Test
        @DisplayName("Should not add error for core node self-reference")
        void shouldNotAddErrorForCoreNodeSelfReference() {
            stubSession(
                    List.of(Map.of("label", "Start")),
                    List.of(),
                    List.of(Map.of("label", "Process Loop", "type", "loop")),
                    List.of(Map.of("from", "core:process_loop", "to", "core:process_loop"))
            );

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).noneMatch(e ->
                    e.code().equals("SELF_LOOP"));
        }

        @Test
        @DisplayName("Should add error for trigger self-loop")
        void shouldAddErrorForTriggerSelfLoop() {
            stubSession(
                    List.of(Map.of("label", "Start")),
                    List.of(),
                    List.of(),
                    List.of(Map.of("from", "trigger:start", "to", "trigger:start"))
            );

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).anyMatch(e ->
                    e.code().equals("SELF_LOOP"));
        }

        @Test
        @DisplayName("Should not flag different nodes as self-loop")
        void shouldNotFlagDifferentNodesAsSelfLoop() {
            stubSession(
                    List.of(Map.of("label", "Start")),
                    List.of(Map.of("label", "A"), Map.of("label", "B")),
                    List.of(),
                    List.of(Map.of("from", "mcp:a", "to", "mcp:b"))
            );

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).noneMatch(e ->
                    e.code().equals("SELF_LOOP"));
        }
    }

    @Nested
    @DisplayName("Incoming edge constraints")
    class IncomingEdgeTests {

        @Test
        @DisplayName("Should add error when trigger has incoming edges")
        void shouldAddErrorWhenTriggerHasIncomingEdges() {
            stubSession(
                    List.of(Map.of("label", "Start")),
                    List.of(Map.of("label", "Step")),
                    List.of(),
                    List.of(Map.of("from", "mcp:step", "to", "trigger:start"))
            );

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).anyMatch(e ->
                    e.code().equals("TRIGGER_HAS_INCOMING"));
        }

        @Test
        @DisplayName("Should not error when trigger has zero incoming edges")
        void shouldNotErrorWhenTriggerHasZeroIncomingEdges() {
            stubSession(
                    List.of(Map.of("label", "Start")),
                    List.of(Map.of("label", "Step")),
                    List.of(),
                    List.of(Map.of("from", "trigger:start", "to", "mcp:step"))
            );

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).noneMatch(e ->
                    e.code().equals("TRIGGER_HAS_INCOMING"));
        }

        @Test
        @DisplayName("Should add error when core node has no incoming edges")
        void shouldAddErrorWhenCoreNodeHasNoIncomingEdges() {
            stubSession(
                    List.of(Map.of("label", "Start")),
                    List.of(),
                    List.of(Map.of("label", "Check", "type", "decision")),
                    List.of(Map.of("from", "trigger:start", "to", "mcp:something"))
            );

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).anyMatch(e ->
                    e.code().equals("CORE_NO_INCOMING"));
        }

        @Test
        @DisplayName("Should add error when core node has multiple incoming edges")
        void shouldAddErrorWhenCoreNodeHasMultipleIncomingEdges() {
            stubSession(
                    List.of(Map.of("label", "Start")),
                    List.of(Map.of("label", "A"), Map.of("label", "B")),
                    List.of(Map.of("label", "Check", "type", "decision")),
                    List.of(
                            Map.of("from", "trigger:start", "to", "mcp:a"),
                            Map.of("from", "trigger:start", "to", "mcp:b"),
                            Map.of("from", "mcp:a", "to", "core:check"),
                            Map.of("from", "mcp:b", "to", "core:check")
                    )
            );

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).anyMatch(e ->
                    e.code().equals("CORE_MULTIPLE_INCOMING"));
        }

        @Test
        @DisplayName("Should not error when core node has exactly one incoming edge")
        void shouldNotErrorWhenCoreNodeHasExactlyOneIncomingEdge() {
            stubSession(
                    List.of(Map.of("label", "Start")),
                    List.of(),
                    List.of(Map.of("label", "Check", "type", "decision")),
                    List.of(Map.of("from", "trigger:start", "to", "core:check"))
            );

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).noneMatch(e ->
                    e.code().equals("CORE_NO_INCOMING") ||
                    e.code().equals("CORE_MULTIPLE_INCOMING"));
        }
    }

    @Nested
    @DisplayName("Output port uniqueness - one port = one target")
    class OutputPortUniquenessTests {

        @Test
        @DisplayName("Should error when a named port has two distinct targets (e.g. set_plan import)")
        void shouldErrorWhenPortHasTwoTargets() {
            stubSession(
                    List.of(Map.of("label", "Start")),
                    List.of(Map.of("label", "A"), Map.of("label", "B")),
                    List.of(Map.of("label", "Check", "type", "decision")),
                    List.of(
                            Map.of("from", "trigger:start", "to", "core:check"),
                            Map.of("from", "core:check:if", "to", "mcp:a"),
                            Map.of("from", "core:check:if", "to", "mcp:b") // SAME port → 2 targets
                    )
            );

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).anyMatch(e -> e.code().equals("PORT_MULTIPLE_TARGETS"));
        }

        @Test
        @DisplayName("Should not error when distinct ports each have one target (if→A, else→B)")
        void shouldNotErrorWhenDistinctPortsEachHaveOneTarget() {
            stubSession(
                    List.of(Map.of("label", "Start")),
                    List.of(Map.of("label", "A"), Map.of("label", "B")),
                    List.of(Map.of("label", "Check", "type", "decision")),
                    List.of(
                            Map.of("from", "trigger:start", "to", "core:check"),
                            Map.of("from", "core:check:if", "to", "mcp:a"),
                            Map.of("from", "core:check:else", "to", "mcp:b")
                    )
            );

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).noneMatch(e -> e.code().equals("PORT_MULTIPLE_TARGETS"));
        }

        @Test
        @DisplayName("Should NOT flag implicit fork from a port-less node (trigger → 2 targets is allowed)")
        void shouldNotFlagImplicitForkFromPortlessNode() {
            stubSession(
                    List.of(Map.of("label", "Start")),
                    List.of(Map.of("label", "A"), Map.of("label", "B")),
                    List.of(),
                    List.of(
                            Map.of("from", "trigger:start", "to", "mcp:a"),
                            Map.of("from", "trigger:start", "to", "mcp:b")
                    )
            );

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).noneMatch(e -> e.code().equals("PORT_MULTIPLE_TARGETS"));
        }

        @Test
        @DisplayName("Should flag a port fan-out expressed with the legacy 'target' key (not just 'to')")
        void shouldFlagLegacyTargetKeyedFanOut() {
            Map<String, Object> entry = new HashMap<>();
            entry.put("from", "trigger:start");
            entry.put("to", "core:check");
            Map<String, Object> e1 = new HashMap<>();
            e1.put("from", "core:check:if");
            e1.put("target", "mcp:a"); // legacy key
            Map<String, Object> e2 = new HashMap<>();
            e2.put("from", "core:check:if");
            e2.put("target", "mcp:b"); // legacy key, different target → fan-out
            stubSession(
                    List.of(Map.of("label", "Start")),
                    List.of(Map.of("label", "A"), Map.of("label", "B")),
                    List.of(Map.of("label", "Check", "type", "decision")),
                    List.of(entry, e1, e2)
            );

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).anyMatch(e -> e.code().equals("PORT_MULTIPLE_TARGETS"));
        }

        @Test
        @DisplayName("Should not error when the same port → same target is duplicated (one logical target)")
        void shouldNotErrorWhenSamePortSameTargetDuplicated() {
            stubSession(
                    List.of(Map.of("label", "Start")),
                    List.of(Map.of("label", "A")),
                    List.of(Map.of("label", "Check", "type", "decision")),
                    List.of(
                            Map.of("from", "trigger:start", "to", "core:check"),
                            Map.of("from", "core:check:if", "to", "mcp:a"),
                            Map.of("from", "core:check:if", "to", "mcp:a")
                    )
            );

            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, result);

            assertThat(result.getErrors()).noneMatch(e -> e.code().equals("PORT_MULTIPLE_TARGETS"));
        }
    }

    @Nested
    @DisplayName("Validate with shared graph analyzer")
    class SharedGraphAnalyzerTests {

        @Test
        @DisplayName("Should accept pre-built graph analyzer")
        void shouldAcceptPreBuiltGraphAnalyzer() {
            stubSession(
                    List.of(Map.of("label", "Start")),
                    List.of(Map.of("label", "Step")),
                    List.of(),
                    List.of(Map.of("from", "trigger:start", "to", "mcp:step"))
            );

            ValidationGraphAnalyzer graph = new ValidationGraphAnalyzer(session);
            ValidationResult result = ValidationResult.builder().build();
            validator.validate(session, graph, result);

            assertThat(result.getErrors()).noneMatch(e ->
                    e.code().equals("INVALID_EDGE_SOURCE") || e.code().equals("INVALID_EDGE_TARGET"));
        }
    }

    @Test
    @DisplayName("Should handle empty edges list")
    void shouldHandleEmptyEdgesList() {
        stubSession(
                List.of(Map.of("label", "Start")),
                List.of(),
                List.of(),
                List.of()
        );

        ValidationResult result = ValidationResult.builder().build();
        validator.validate(session, result);

        assertThat(result.getErrors()).noneMatch(e ->
                e.code().equals("INVALID_EDGE_SOURCE") ||
                e.code().equals("INVALID_EDGE_TARGET") ||
                e.code().equals("SELF_LOOP"));
    }

    @Test
    @DisplayName("Should handle non-string to field gracefully")
    void shouldHandleNonStringToFieldGracefully() {
        stubSession(
                List.of(Map.of("label", "Start")),
                List.of(),
                List.of(),
                List.of(Map.of("from", "trigger:start", "to", 123))
        );

        ValidationResult result = ValidationResult.builder().build();
        validator.validate(session, result);

        // Non-string to should be treated as null - no INVALID_EDGE_TARGET
        assertThat(result.getErrors()).noneMatch(e ->
                e.code().equals("INVALID_EDGE_TARGET"));
    }
}
