package com.apimarketplace.orchestrator.tools.workflow.builder.validation;

import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSession;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderValidator.ValidationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;

/**
 * Structural-integrity warnings emitted by {@link NodeStructureValidator}.
 *
 * <p>Two regression-flavored checks live here:
 *
 * <ul>
 *   <li>{@code NODE_DUAL_WRITE} - a {@code set} (or any other nested-config)
 *       node that carries BOTH its nested slot AND a top-level twin of one of
 *       the inner fields. This is the exact shape that produced
 *       {@code price=null, triggered=false} forever for the user's stock
 *       workflow on 2026-05-14.</li>
 *   <li>{@code NESTED_TEMPLATE} - a string value embedding
 *       {@code &#123;&#123;…&#123;&#123;…&#125;&#125;…&#125;&#125;}. The
 *       template engine resolves a single pass; nested braces explode at
 *       runtime as SpEL syntax errors when the inner doesn't resolve to a
 *       valid expression.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("NodeStructureValidator - dual-write + nested-template warnings")
class NodeStructureValidatorTest {

    @Mock
    private WorkflowBuilderSession session;

    private NodeStructureValidator validator;

    @BeforeEach
    void setUp() {
        validator = new NodeStructureValidator();
        // All getXxx() lists default to empty so each test only fills what it needs.
        lenient().when(session.getCores()).thenReturn(List.of());
        lenient().when(session.getMcps()).thenReturn(List.of());
        lenient().when(session.getInterfaces()).thenReturn(List.of());
        lenient().when(session.getTables()).thenReturn(List.of());
        lenient().when(session.getEdges()).thenReturn(List.of());
    }

    @Test
    @DisplayName("Flags NODE_DUAL_WRITE when a set node has both set.assignments and top-level assignments")
    void flagsDualWriteOnSetNode() {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", "core:set_stock_values");
        node.put("type", "set");
        node.put("label", "Set Stock Values");
        node.put("set", Map.of("assignments", List.of(
                Map.of("name", "price", "value", "42"))));
        node.put("assignments", List.of(
                Map.of("name", "stale", "value", "ghost")));
        lenient().when(session.getCores()).thenReturn(List.of(node));

        ValidationResult result = ValidationResult.builder().build();
        validator.validate(session, result);

        assertThat(result.getWarnings()).hasSize(1);
        assertThat(result.getWarnings().get(0).code()).isEqualTo("NODE_DUAL_WRITE");
        assertThat(result.getWarnings().get(0).message())
                .contains("assignments")
                .contains("set");
    }

    @Test
    @DisplayName("Clean set node (only nested config) emits no NODE_DUAL_WRITE")
    void cleanSetNodePasses() {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", "core:ok");
        node.put("type", "set");
        node.put("label", "OK");
        node.put("set", Map.of("assignments", List.of(
                Map.of("name", "price", "value", "42"))));
        lenient().when(session.getCores()).thenReturn(List.of(node));

        ValidationResult result = ValidationResult.builder().build();
        validator.validate(session, result);

        assertThat(result.getWarnings()).isEmpty();
    }

    @Test
    @DisplayName("Top-level meta keys (label, position) on a nested-config node are not flagged")
    void topLevelMetaKeysAreNotOrphans() {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", "core:ok");
        node.put("type", "code");
        node.put("label", "OK");                // protected
        node.put("position", Map.of("x", 0));   // protected
        node.put("description", "blah");        // protected
        node.put("code", Map.of("code", "$output = {}", "language", "javascript"));
        lenient().when(session.getCores()).thenReturn(List.of(node));

        ValidationResult result = ValidationResult.builder().build();
        validator.validate(session, result);

        assertThat(result.getWarnings()).isEmpty();
    }

    @Test
    @DisplayName("Flags NESTED_TEMPLATE when a value embeds {{...{{...}}...}}")
    void flagsNestedTemplate() {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", "core:check");
        node.put("type", "decision");
        node.put("label", "Check");
        // Real shape from the prod bug: a SpEL wrapping an inner template
        node.put("decisionConditions", List.of(
                Map.of("id", "check-if", "type", "if", "label", "Triggered",
                        "expression", "{{('{{x}}' == 'above' && {{y}} >= 5) || false}}")
        ));
        lenient().when(session.getCores()).thenReturn(List.of(node));

        ValidationResult result = ValidationResult.builder().build();
        validator.validate(session, result);

        assertThat(result.getWarnings())
                .filteredOn(w -> "NESTED_TEMPLATE".equals(w.code()))
                .hasSize(1);
    }

    @Test
    @DisplayName("Lone non-paired { inside a template body is not flagged as nested")
    void loneSingleBraceIsFine() {
        // Pinned so a future tightening of the regex (e.g. to require literal { instead of {{)
        // doesn't silently start flagging legitimate template strings carrying a stray brace.
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", "core:say");
        node.put("type", "response");
        node.put("label", "Say");
        node.put("response", Map.of("message", "Hello {{name}} - your code is { not nested }"));
        lenient().when(session.getCores()).thenReturn(List.of(node));

        ValidationResult result = ValidationResult.builder().build();
        validator.validate(session, result);

        assertThat(result.getWarnings()).isEmpty();
    }

    @Test
    @DisplayName("Flags TERMINAL_NODE_HAS_OUTGOING_EDGE when an exit node has an outgoing edge")
    void flagsTerminalExitWithOutgoingEdge() {
        Map<String, Object> exit = new LinkedHashMap<>();
        exit.put("id", "core:no_triggered_stock");
        exit.put("type", "exit");
        exit.put("label", "no triggered stock");
        Map<String, Object> merge = new LinkedHashMap<>();
        merge.put("id", "core:rejoin");
        merge.put("type", "merge");
        merge.put("label", "Rejoin");
        Map<String, Object> edge = new LinkedHashMap<>();
        edge.put("from", "core:no_triggered_stock");
        edge.put("to", "core:rejoin");
        lenient().when(session.getCores()).thenReturn(List.of(exit, merge));
        lenient().when(session.getEdges()).thenReturn(List.of(edge));

        ValidationResult result = ValidationResult.builder().build();
        validator.validate(session, result);

        assertThat(result.getErrors())
                .as("terminal exit with outgoing edge must be an ERROR, not a warning")
                .filteredOn(e -> "TERMINAL_NODE_HAS_OUTGOING_EDGE".equals(e.code()))
                .hasSize(1);
        assertThat(result.getErrors().get(0).message())
                .contains("exit, end, stop_on_error")
                .contains("core:no_triggered_stock");
    }

    @Test
    @DisplayName("Flags end node with an outgoing edge (parity with exit/stop_on_error)")
    void flagsTerminalEndNodeWithOutgoingEdge() {
        Map<String, Object> end = new LinkedHashMap<>();
        end.put("id", "core:workflow_done");
        end.put("type", "end");
        end.put("label", "Done");
        Map<String, Object> edge = new LinkedHashMap<>();
        edge.put("from", "core:workflow_done");
        edge.put("to", "core:next");
        lenient().when(session.getCores()).thenReturn(List.of(end));
        lenient().when(session.getEdges()).thenReturn(List.of(edge));

        ValidationResult result = ValidationResult.builder().build();
        validator.validate(session, result);

        assertThat(result.getErrors())
                .filteredOn(e -> "TERMINAL_NODE_HAS_OUTGOING_EDGE".equals(e.code()))
                .hasSize(1);
    }

    @Test
    @DisplayName("Flags stop_on_error with an outgoing edge too (not just exit)")
    void flagsTerminalStopOnErrorWithOutgoingEdge() {
        Map<String, Object> stop = new LinkedHashMap<>();
        stop.put("id", "core:halt");
        stop.put("type", "stop_on_error");
        stop.put("label", "Halt");
        Map<String, Object> edge = new LinkedHashMap<>();
        edge.put("from", "core:halt");
        edge.put("to", "core:rejoin");
        lenient().when(session.getCores()).thenReturn(List.of(stop));
        lenient().when(session.getEdges()).thenReturn(List.of(edge));

        ValidationResult result = ValidationResult.builder().build();
        validator.validate(session, result);

        assertThat(result.getErrors())
                .filteredOn(e -> "TERMINAL_NODE_HAS_OUTGOING_EDGE".equals(e.code()))
                .hasSize(1);
    }

    @Test
    @DisplayName("Terminal node with NO outgoing edge passes (the normal case)")
    void terminalExitWithoutOutgoingEdgeIsFine() {
        Map<String, Object> exit = new LinkedHashMap<>();
        exit.put("id", "core:done");
        exit.put("type", "exit");
        exit.put("label", "Done");
        lenient().when(session.getCores()).thenReturn(List.of(exit));
        lenient().when(session.getEdges()).thenReturn(List.of());

        ValidationResult result = ValidationResult.builder().build();
        validator.validate(session, result);

        assertThat(result.getErrors())
                .filteredOn(e -> "TERMINAL_NODE_HAS_OUTGOING_EDGE".equals(e.code()))
                .isEmpty();
    }

    @Test
    @DisplayName("Sequential {{a}}{{b}} (non-nested) does not trigger NESTED_TEMPLATE")
    void sequentialTemplatesAreFine() {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", "core:join");
        node.put("type", "transform");
        node.put("label", "Join");
        node.put("transform", Map.of("input", "{{a.x}}{{b.y}} and {{c.z}}"));
        lenient().when(session.getCores()).thenReturn(List.of(node));

        ValidationResult result = ValidationResult.builder().build();
        validator.validate(session, result);

        assertThat(result.getWarnings()).isEmpty();
    }
}
