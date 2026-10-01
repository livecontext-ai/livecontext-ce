package com.apimarketplace.orchestrator.tools.workflow.builder;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

/**
 * The "Unknown reference" warnings an add_node response carries for a decision or loop
 * condition. Same defect as ReferenceValidator (2026-09-29): the node was read as everything
 * left of the first '.', so {@code int(core:a.output.n) > 5} warned about node "int(core:a".
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ResponseContextBuilder - condition reference warnings")
class ResponseContextBuilderConditionReferencesTest {

    @Mock private WorkflowBuilderSession session;

    private final ResponseContextBuilder builder = new ResponseContextBuilder();

    @BeforeEach
    void setUp() {
        lenient().when(session.nodeExists(anyString())).thenAnswer(inv ->
                List.of("core:formatage", "trigger:start").contains(inv.getArgument(0)));
        lenient().when(session.resolveNodeReference(anyString())).thenAnswer(inv -> inv.getArgument(0));
    }

    private List<String> warningsFor(String condition) {
        return builder.validateConditionReferences(List.of(Map.of("condition", condition)), Map.of(), session);
    }

    @Test
    @DisplayName("A function call and a comparison around a known node raise no warning")
    void functionAndComparisonAroundKnownNode_noWarning() {
        assertThat(warningsFor("{{int(core:formatage.output.words_count) > 10}}")).isEmpty();
        assertThat(warningsFor("{{'Analyse : ' + core:formatage.output.theme == trigger:start.output.x}}")).isEmpty();
    }

    @Test
    @DisplayName("An unknown node inside a larger expression is still warned about")
    void unknownNodeInsideExpression_warns() {
        assertThat(warningsFor("{{int(core:formatage.output.n) > int(mcp:ghost.output.n)}}"))
                .singleElement()
                .satisfies(w -> assertThat(w).startsWith("Unknown reference:"));
    }

    @Test
    @DisplayName("A node that only exists once resolved (label spelling) is known inside an expression too")
    void resolvedNodeInsideExpression_known() {
        lenient().when(session.resolveNodeReference("core:Formatage")).thenReturn("core:formatage");

        assertThat(warningsFor("{{int(core:Formatage.output.n) > 5}}")).isEmpty();
    }

    @Test
    @DisplayName("A condition on a workflow variable only (vars: / $vars) is not an unknown node")
    void workflowVariableOnly_noWarning() {
        assertThat(warningsFor("{{int(vars:limits.max) > 1}}")).isEmpty();
        assertThat(warningsFor("{{vars:limits.max}}")).isEmpty();
        assertThat(warningsFor("{{$vars.limits.max == 'x'}}")).isEmpty();
    }

    @Test
    @DisplayName("A plain node reference keeps its old behaviour")
    void plainReference_unchanged() {
        assertThat(warningsFor("{{core:formatage.output.words_count}}")).isEmpty();
        assertThat(warningsFor("{{core:ghost.output.words_count}}")).hasSize(1);
    }
}
