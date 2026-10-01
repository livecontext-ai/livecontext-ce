package com.apimarketplace.orchestrator.tools.workflow.builder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ExpressionNodeReferences - nodes named by a {{...}} body")
class ExpressionNodeReferencesTest {

    static Stream<Arguments> expressions() {
        return Stream.of(
            Arguments.of("plain reference", "core:a.output.x", List.of("core:a")),
            Arguments.of("literal before (2026-09-29 Gemini)", "'Analyse : ' + core:a.output.x", List.of("core:a")),
            Arguments.of("concatenation of two", "core:a.output.x + ' (' + mcp:b.output.y + ')'", List.of("core:a", "mcp:b")),
            Arguments.of("function + comparison + ternary", "int(core:a.output.n) > 10 ? trigger:t.output.a : 'x'", List.of("core:a", "trigger:t")),
            Arguments.of("multi-arg function", "concat(core:a.output.x, agent:b.output.response)", List.of("core:a", "agent:b")),
            Arguments.of("same node twice counted once", "core:a.output.x + core:a.output.y", List.of("core:a")),
            Arguments.of("reference-shaped text in a literal", "'see mcp:ghost.output.x' + core:a.output.y", List.of("core:a")),
            Arguments.of("reference-shaped text in a double-quoted literal", "\"mcp:ghost.output.x\"", List.of()),
            Arguments.of("doubled quote inside a literal (SpEL escape)", "'it''s core:ghost.x' + core:a.output.y", List.of("core:a")),
            Arguments.of("backslash is not an escape in SpEL", "'C:\\' + core:a.output.y + 'x'", List.of("core:a")),
            Arguments.of("safe navigation", "core:a?.output?.x", List.of("core:a")),
            Arguments.of("compact ternary", "ok?x:core:a.output.y", List.of("core:a")),
            Arguments.of("indexing and arithmetic", "core:a.output.items[0].price * 1.5 + mcp:b.output['k']", List.of("core:a", "mcp:b")),
            Arguments.of("vars: is a variable, not a node", "vars:api.base", List.of()),
            Arguments.of("$vars and $input are not nodes", "$vars.x.y + $input.a.b", List.of()),
            Arguments.of("un-normalized label kept whole", "core:My Node.output.x", List.of("core:My Node")),
            Arguments.of("no reference at all", "simple_var", List.of())
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("expressions")
    void extractsTheReferencedNodes(String name, String expression, List<String> expected) {
        assertThat(ExpressionNodeReferences.of(expression)).containsExactlyElementsOf(expected);
    }

    @Test
    @DisplayName("null and empty expressions name no node")
    void nullAndEmpty() {
        assertThat(ExpressionNodeReferences.of(null)).isEmpty();
        assertThat(ExpressionNodeReferences.of("")).isEmpty();
    }
}
