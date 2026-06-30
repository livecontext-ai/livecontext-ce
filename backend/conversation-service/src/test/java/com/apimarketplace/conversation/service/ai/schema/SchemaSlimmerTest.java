package com.apimarketplace.conversation.service.ai.schema;

import com.apimarketplace.agent.domain.ToolDefinition;
import com.apimarketplace.agent.domain.ToolParameter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stage 4a.1 - pin the slim-schema primitive contract.
 *
 * <p>These tests lock the minimization rules so the tools prefix shipped to
 * top/mid-tier LLMs stays compact without breaking routing, required-param
 * enforcement, or the {@code action} enum (which the LLM needs to pick the
 * right facade sub-behaviour).
 */
@DisplayName("SchemaSlimmer - minimization primitive (Stage 4a.1)")
class SchemaSlimmerTest {

    @Nested
    @DisplayName("null / empty handling")
    class NullHandling {

        @Test
        @DisplayName("null input returns null")
        void nullInputReturnsNull() {
            assertThat(SchemaSlimmer.minimize(null)).isNull();
        }

        @Test
        @DisplayName("null parameters list yields empty slim parameters (never null)")
        void nullParametersListYieldsEmptyList() {
            ToolDefinition input = ToolDefinition.builder().name("workflow").parameters(null).build();

            ToolDefinition out = SchemaSlimmer.minimize(input);

            assertThat(out.parameters()).isEmpty();
        }

        @Test
        @DisplayName("empty parameters list yields empty slim parameters")
        void emptyParametersListYieldsEmptyList() {
            ToolDefinition input = ToolDefinition.builder()
                    .name("workflow")
                    .parameters(List.of())
                    .build();

            ToolDefinition out = SchemaSlimmer.minimize(input);

            assertThat(out.parameters()).isEmpty();
        }
    }

    @Nested
    @DisplayName("parameter slimming - non-action params")
    class ParameterSlimming {

        @Test
        @DisplayName("coerces every non-action param type to string")
        void coercesEveryParamTypeToString() {
            ToolDefinition input = ToolDefinition.builder()
                    .name("table")
                    .parameters(List.of(
                            ToolParameter.builder().name("limit").type("integer").build(),
                            ToolParameter.builder().name("offset").type("number").build(),
                            ToolParameter.builder().name("rows").type("array").build(),
                            ToolParameter.builder().name("filter").type("object").build(),
                            ToolParameter.builder().name("distinct").type("boolean").build()
                    ))
                    .build();

            ToolDefinition out = SchemaSlimmer.minimize(input);

            assertThat(out.parameters()).extracting(ToolParameter::type)
                    .containsOnly("string");
        }

        @Test
        @DisplayName("strips description, enum, default, pattern, min/max, and nested properties")
        void stripsMetadata() {
            ToolParameter rich = ToolParameter.builder()
                    .name("limit")
                    .type("integer")
                    .description("row count cap")
                    .defaultValue(10)
                    .enumValues(List.of("1", "10", "100"))
                    .minLength(1)
                    .maxLength(3)
                    .minimum(1.0)
                    .maximum(100.0)
                    .pattern("\\d+")
                    .properties(Map.of("nested", ToolParameter.builder().name("n").type("string").build()))
                    .build();

            ToolDefinition out = SchemaSlimmer.minimize(
                    ToolDefinition.builder().name("table").parameters(List.of(rich)).build());

            ToolParameter slim = out.parameters().get(0);
            assertThat(slim.description()).isNull();
            assertThat(slim.defaultValue()).isNull();
            assertThat(slim.enumValues()).isNull();
            assertThat(slim.minLength()).isNull();
            assertThat(slim.maxLength()).isNull();
            assertThat(slim.minimum()).isNull();
            assertThat(slim.maximum()).isNull();
            assertThat(slim.pattern()).isNull();
            assertThat(slim.properties()).isNull();
        }

        @Test
        @DisplayName("preserves parameter name and required flag")
        void preservesNameAndRequired() {
            ToolDefinition input = ToolDefinition.builder()
                    .name("workflow")
                    .parameters(List.of(
                            ToolParameter.builder().name("workflow_id").type("string").required(true).build(),
                            ToolParameter.builder().name("cursor").type("string").required(false).build()
                    ))
                    .build();

            List<ToolParameter> slim = SchemaSlimmer.minimize(input).parameters();

            assertThat(slim.get(0).name()).isEqualTo("workflow_id");
            assertThat(slim.get(0).required()).isTrue();
            assertThat(slim.get(1).name()).isEqualTo("cursor");
            assertThat(slim.get(1).required()).isFalse();
        }
    }

    @Nested
    @DisplayName("action enum preservation")
    class ActionEnumPreservation {

        @Test
        @DisplayName("action parameter keeps full enum cardinality")
        void actionEnumSurvives() {
            ToolParameter action = ToolParameter.builder()
                    .name("action")
                    .type("string")
                    .enumValues(List.of("create", "get", "list", "update", "delete", "help"))
                    .description("pick one")
                    .build();

            ToolDefinition out = SchemaSlimmer.minimize(
                    ToolDefinition.builder().name("agent").parameters(List.of(action)).build());

            ToolParameter slimAction = out.parameters().get(0);
            assertThat(slimAction.name()).isEqualTo("action");
            assertThat(slimAction.enumValues())
                    .containsExactly("create", "get", "list", "update", "delete", "help");
            // Description still stripped - only the enum is preserved.
            assertThat(slimAction.description()).isNull();
        }

        @Test
        @DisplayName("non-action parameters named something else lose their enum")
        void nonActionEnumsStripped() {
            ToolParameter env = ToolParameter.builder()
                    .name("environment")
                    .type("string")
                    .enumValues(List.of("dev", "staging", "prod"))
                    .build();

            ToolDefinition out = SchemaSlimmer.minimize(
                    ToolDefinition.builder().name("workflow").parameters(List.of(env)).build());

            assertThat(out.parameters().get(0).enumValues()).isNull();
        }
    }

    @Nested
    @DisplayName("tool-level rewrite")
    class ToolLevelRewrite {

        @Test
        @DisplayName("description is replaced with help-directive pointing to this tool")
        void descriptionPointsToHelp() {
            ToolDefinition input = ToolDefinition.builder()
                    .name("workflow")
                    .description("long prose describing the workflow builder tool in full …")
                    .build();

            ToolDefinition out = SchemaSlimmer.minimize(input);

            assertThat(out.description())
                    .isEqualTo("Call `workflow(action='help', topic='<action>')` before first use of any action.");
        }

        @Test
        @DisplayName("null tool name falls back to 'tool' literal in directive")
        void nullToolNameFallback() {
            ToolDefinition input = ToolDefinition.builder().name(null).description("x").build();

            ToolDefinition out = SchemaSlimmer.minimize(input);

            assertThat(out.description()).contains("`tool(action='help'");
        }
    }

    @Nested
    @DisplayName("preserved invariants")
    class PreservedInvariants {

        @Test
        @DisplayName("id, apiSlug, toolSlug, requiredParameters, timeoutMs pass through unchanged")
        void passThroughFields() {
            ToolDefinition input = ToolDefinition.builder()
                    .id("tool-123")
                    .name("agent")
                    .description("orig")
                    .apiSlug("api-agent")
                    .toolSlug("slug-agent")
                    .requiredParameters(List.of("action"))
                    .relevanceScore(0.87)
                    .metadata(Map.of("source", "agent-service"))
                    .timeoutMs(45_000L)
                    .parameters(List.of(ToolParameter.builder().name("action").type("string").build()))
                    .build();

            ToolDefinition out = SchemaSlimmer.minimize(input);

            assertThat(out.id()).isEqualTo("tool-123");
            assertThat(out.apiSlug()).isEqualTo("api-agent");
            assertThat(out.toolSlug()).isEqualTo("slug-agent");
            assertThat(out.requiredParameters()).containsExactly("action");
            assertThat(out.relevanceScore()).isEqualTo(0.87);
            assertThat(out.metadata()).containsEntry("source", "agent-service");
            assertThat(out.timeoutMs()).isEqualTo(45_000L);
        }
    }

    @Nested
    @DisplayName("idempotence")
    class Idempotence {

        @Test
        @DisplayName("slimming twice yields the same output as slimming once")
        void doubleSlimEqualsSingleSlim() {
            ToolParameter action = ToolParameter.builder()
                    .name("action").type("string").enumValues(List.of("create", "help")).build();
            ToolParameter other = ToolParameter.builder()
                    .name("agent_id").type("string").description("id").build();

            ToolDefinition input = ToolDefinition.builder()
                    .name("agent").parameters(List.of(action, other)).build();

            ToolDefinition once = SchemaSlimmer.minimize(input);
            ToolDefinition twice = SchemaSlimmer.minimize(once);

            assertThat(twice).isEqualTo(once);
        }
    }
}
