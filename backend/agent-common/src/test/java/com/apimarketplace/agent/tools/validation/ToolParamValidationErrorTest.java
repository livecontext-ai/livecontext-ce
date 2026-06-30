package com.apimarketplace.agent.tools.validation;

import com.apimarketplace.agent.domain.ToolDefinition;
import com.apimarketplace.agent.domain.ToolParameter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Stage 4a.5 - pin the {@link ToolParamValidationError} shape and factory
 * semantics. Separate {@code ToolParamValidationErrorTokenBudgetTest}
 * covers the serialized-size guarantee.
 */
@DisplayName("ToolParamValidationError - schema-not-loaded guidance (Stage 4a.5)")
class ToolParamValidationErrorTest {

    @Nested
    @DisplayName("constructor invariants")
    class Invariants {

        @Test
        @DisplayName("errorCode must not be null")
        void rejectsNullErrorCode() {
            assertThatThrownBy(() -> new ToolParamValidationError(
                    null, "agent", "publish", "publish", List.of(), "agent(action='publish')", "Call help"))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("tool must not be null")
        void rejectsNullTool() {
            assertThatThrownBy(() -> new ToolParamValidationError(
                    "X", null, "publish", "publish", List.of(), "", ""))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("null requiredParams is normalised to empty list")
        void nullRequiredParamsNormalised() {
            ToolParamValidationError err = new ToolParamValidationError(
                    "X", "agent", null, "agent", null, "agent()", "...");
            assertThat(err.requiredParams()).isEmpty();
        }

        @Test
        @DisplayName("requiredParams list is unmodifiable")
        void requiredParamsUnmodifiable() {
            List<String> mutable = new ArrayList<>(List.of("name"));
            ToolParamValidationError err = new ToolParamValidationError(
                    "X", "agent", null, "agent", mutable, "agent()", "...");
            assertThatThrownBy(() -> err.requiredParams().add("extra"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("schemaNotLoaded factory")
    class Factory {

        @Test
        @DisplayName("builds from ToolDefinition.requiredParameters()")
        void readsRequiredNames() {
            ToolDefinition def = ToolDefinition.builder()
                    .name("agent")
                    .requiredParameters(List.of("action", "name", "type"))
                    .build();

            ToolParamValidationError err = ToolParamValidationError.schemaNotLoaded("agent", "create", def);

            assertThat(err.errorCode()).isEqualTo(ToolParamValidationError.CODE_SCHEMA_NOT_LOADED);
            assertThat(err.tool()).isEqualTo("agent");
            assertThat(err.action()).isEqualTo("create");
            assertThat(err.helpTopic()).isEqualTo("create");
            assertThat(err.requiredParams()).containsExactly("action", "name", "type");
            assertThat(err.nextAction()).isEqualTo(
                    "Call `agent(action='help', topic='create')` before retrying.");
        }

        @Test
        @DisplayName("falls back to ToolParameter.required flag when requiredParameters is null")
        void fallsBackToToolParameterFlag() {
            ToolDefinition def = ToolDefinition.builder()
                    .name("agent")
                    .parameters(List.of(
                            ToolParameter.builder().name("action").required(true).build(),
                            ToolParameter.builder().name("title").required(true).build(),
                            ToolParameter.builder().name("notes").required(false).build()
                    ))
                    .build();

            ToolParamValidationError err = ToolParamValidationError.schemaNotLoaded("agent", "create", def);

            assertThat(err.requiredParams()).containsExactly("action", "title");
        }

        @Test
        @DisplayName("one-line example starts with tool(action='X', ...)")
        void oneLineExampleShape() {
            ToolDefinition def = ToolDefinition.builder()
                    .name("agent")
                    .requiredParameters(List.of("action", "name", "type"))
                    .build();

            ToolParamValidationError err = ToolParamValidationError.schemaNotLoaded("agent", "create", def);

            assertThat(err.oneLineExample()).startsWith("agent(action='create'");
            assertThat(err.oneLineExample()).contains("name='<name>'");
            assertThat(err.oneLineExample()).contains("type='<type>'");
            assertThat(err.oneLineExample()).endsWith(")");
        }

        @Test
        @DisplayName("placeholder hints are derived from parameter name (most-specific first)")
        void placeholderHints() {
            ToolDefinition def = ToolDefinition.builder()
                    .name("sender")
                    .requiredParameters(List.of("taskId", "recipientEmail", "docUrl", "userIdEmail"))
                    .build();

            ToolParamValidationError err = ToolParamValidationError.schemaNotLoaded("sender", "dispatch", def);

            assertThat(err.oneLineExample()).contains("taskId='<id>'");
            assertThat(err.oneLineExample()).contains("recipientEmail='<email>'");
            assertThat(err.oneLineExample()).contains("docUrl='<url>'");
            // 'userIdEmail' contains both 'id' and 'email' - most-specific wins.
            assertThat(err.oneLineExample()).contains("userIdEmail='<email>'");
        }

        @Test
        @DisplayName("null / blank action yields tool-level helpTopic and nextAction")
        void noActionDimension() {
            ToolDefinition def = ToolDefinition.builder()
                    .name("request_credential")
                    .requiredParameters(List.of("apiName"))
                    .build();

            ToolParamValidationError err = ToolParamValidationError.schemaNotLoaded("request_credential", null, def);

            assertThat(err.action()).isNull();
            assertThat(err.helpTopic()).isEqualTo("request_credential");
            assertThat(err.nextAction()).isEqualTo(
                    "Call `request_credential(action='help')` before retrying.");
            assertThat(err.oneLineExample()).startsWith("request_credential(");
            assertThat(err.oneLineExample()).doesNotContain("action=");
        }

        @Test
        @DisplayName("truncates the one-line example when required params exceed the soft cap")
        void truncatesOneLineExample() {
            // 20 long-named required params would blow past the soft cap.
            List<String> manyNames = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                manyNames.add("veryLongParameterName_" + i);
            }
            ToolDefinition def = ToolDefinition.builder()
                    .name("mega")
                    .requiredParameters(manyNames)
                    .build();

            ToolParamValidationError err = ToolParamValidationError.schemaNotLoaded("mega", "do_all", def);

            assertThat(err.oneLineExample().length())
                    .isLessThanOrEqualTo(ToolParamValidationError.ONE_LINE_EXAMPLE_MAX_CHARS);
            assertThat(err.oneLineExample()).endsWith(", ...)");
            // Required-params list itself is NOT truncated - it's names-only
            // and cheap; the LLM still gets the full list.
            assertThat(err.requiredParams()).hasSize(20);
        }

        @Test
        @DisplayName("empty parameter list → example is `tool(action='X')`")
        void noRequiredParams() {
            ToolDefinition def = ToolDefinition.builder()
                    .name("agent")
                    .requiredParameters(List.of())
                    .build();

            ToolParamValidationError err = ToolParamValidationError.schemaNotLoaded("agent", "list", def);

            assertThat(err.oneLineExample()).isEqualTo("agent(action='list')");
            assertThat(err.requiredParams()).isEmpty();
        }

        @Test
        @DisplayName("null ToolDefinition is tolerated - error still carries tool/action/next-action")
        void nullDefinitionTolerated() {
            ToolParamValidationError err = ToolParamValidationError.schemaNotLoaded("mystery", "x", null);

            assertThat(err.tool()).isEqualTo("mystery");
            assertThat(err.action()).isEqualTo("x");
            assertThat(err.requiredParams()).isEmpty();
            assertThat(err.oneLineExample()).isEqualTo("mystery(action='x')");
        }

        @Test
        @DisplayName("drops 'action' from required-names in the example to avoid duplication")
        void avoidsDuplicateActionArg() {
            ToolDefinition def = ToolDefinition.builder()
                    .name("agent")
                    .requiredParameters(List.of("action", "name"))
                    .build();

            ToolParamValidationError err = ToolParamValidationError.schemaNotLoaded("agent", "create", def);

            // "action='create'" appears exactly once in the example.
            long occurrences = err.oneLineExample().chars()
                    .filter(c -> c == '=').count();
            // One = for action, one = for name → 2.
            assertThat(occurrences).isEqualTo(2L);
        }
    }
}
