package com.apimarketplace.agent.tools.help;

import com.apimarketplace.agent.registry.AgentToolDefinition;
import com.apimarketplace.agent.registry.AgentToolRegistry;
import com.apimarketplace.agent.registry.AgentToolRegistry.ToolDocumentation;
import com.apimarketplace.agent.registry.ToolCategory;
import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Coverage-gap tests for {@link HelpToolsProvider}, complementing the success-path
 * {@link HelpToolsProviderTest}. The 2026-06-23 audit found the existing suite asserted
 * only {@code .success()}/message substrings, never the machine-readable
 * {@link ToolErrorCode} (which drives agent recovery), the catch-all wrapper, the
 * response payload shape, the {@code get_tool_help} null-defaulting, or the
 * {@code expression_help} per-category filtering. This class pins exactly those.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("HelpToolsProvider - error codes, payloads, filtering")
class HelpToolsProviderCoverageTest {

    @Mock private AgentToolRegistry toolRegistry;
    private HelpToolsProvider provider;

    @BeforeEach
    void setUp() {
        provider = new HelpToolsProvider(toolRegistry);
    }

    private ToolExecutionResult exec(String tool, Map<String, Object> params) {
        return provider.execute(tool, params, ToolExecutionContext.empty());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ToolExecutionResult r) {
        return (Map<String, Object>) r.data();
    }

    // ── dispatch + catch-all ────────────────────────────────────────────

    @Test
    @DisplayName("unknown tool -> TOOL_NOT_FOUND error code")
    void unknownToolCode() {
        assertThat(exec("nope", Map.of()).errorCode()).isEqualTo(ToolErrorCode.TOOL_NOT_FOUND);
    }

    @Test
    @DisplayName("a registry exception is caught and wrapped as EXECUTION_FAILED, not propagated")
    void catchAllWrapsRegistryException() {
        when(toolRegistry.getAllTools()).thenThrow(new RuntimeException("registry exploded"));

        ToolExecutionResult r = exec("list_all_tools", Map.of());

        assertThat(r.success()).isFalse();
        assertThat(r.errorCode()).isEqualTo(ToolErrorCode.EXECUTION_FAILED);
        assertThat(r.error()).contains("registry exploded");
    }

    // ── list_all_tools payload ──────────────────────────────────────────

    @Test
    @DisplayName("list_all_tools returns the tools summary, count, and category counts")
    void listAllToolsPayload() {
        AgentToolDefinition t = AgentToolDefinition.builder()
                .name("t1").description("d").category(ToolCategory.UTILITY).build();
        when(toolRegistry.getAllTools()).thenReturn(List.of(t));
        when(toolRegistry.getCategoryCounts()).thenReturn(Map.of("utility", 1));

        Map<String, Object> d = data(exec("list_all_tools", Map.of()));
        assertThat(d).containsEntry("count", 1).containsKey("tools").containsKey("categories");
        assertThat((List<?>) d.get("tools")).hasSize(1);
        assertThat((Map<String, Object>) d.get("categories")).containsEntry("utility", 1);
    }

    @Test
    @DisplayName("list_all_tools with an invalid category -> INVALID_PARAMETER_VALUE")
    void listAllToolsInvalidCategoryCode() {
        assertThat(exec("list_all_tools", Map.of("category", "bogus")).errorCode())
                .isEqualTo(ToolErrorCode.INVALID_PARAMETER_VALUE);
    }

    @Test
    @DisplayName("list_all_tools with a valid category routes through getToolsByCategory and shapes the same envelope")
    void listAllToolsFilteredPayload() {
        AgentToolDefinition t = AgentToolDefinition.builder()
                .name("h1").description("d").category(ToolCategory.HELP).build();
        when(toolRegistry.getToolsByCategory(ToolCategory.HELP)).thenReturn(List.of(t));
        when(toolRegistry.getCategoryCounts()).thenReturn(Map.of("help", 1));

        Map<String, Object> d = data(exec("list_all_tools", Map.of("category", "help")));
        assertThat(d).containsEntry("count", 1).containsKey("tools").containsKey("categories");
        assertThat((List<?>) d.get("tools")).hasSize(1);
    }

    // ── get_tool_help ───────────────────────────────────────────────────

    @Nested
    @DisplayName("get_tool_help")
    class GetToolHelp {

        @Test
        @DisplayName("missing or blank tool_name -> MISSING_PARAMETER")
        void blankNameCode() {
            assertThat(exec("get_tool_help", Map.of()).errorCode()).isEqualTo(ToolErrorCode.MISSING_PARAMETER);
            assertThat(exec("get_tool_help", Map.of("tool_name", "   ")).errorCode())
                    .isEqualTo(ToolErrorCode.MISSING_PARAMETER);
        }

        @Test
        @DisplayName("unknown tool -> RESOURCE_NOT_FOUND")
        void notFoundCode() {
            when(toolRegistry.getToolDocumentation("ghost")).thenReturn(null);
            assertThat(exec("get_tool_help", Map.of("tool_name", "ghost")).errorCode())
                    .isEqualTo(ToolErrorCode.RESOURCE_NOT_FOUND);
        }

        @Test
        @DisplayName("null doc fields are defaulted (helpText='', examples/inputSchema/outputSchema/tags empty, parameters [])")
        void nullDefaulting() {
            when(toolRegistry.getToolDocumentation("t")).thenReturn(
                    new ToolDocumentation("t", "desc", null, null, null, null, ToolCategory.HELP, null));
            when(toolRegistry.getToolByName("t")).thenReturn(Optional.empty());

            Map<String, Object> d = data(exec("get_tool_help", Map.of("tool_name", "t")));
            assertThat(d).containsEntry("name", "t").containsEntry("description", "desc")
                    .containsEntry("helpText", "").containsEntry("category", "help")
                    .containsEntry("examples", List.of()).containsEntry("inputSchema", Map.of())
                    .containsEntry("outputSchema", Map.of()).containsEntry("tags", List.of())
                    .containsEntry("parameters", List.of());
        }

        @Test
        @DisplayName("populated doc surfaces every field incl. the resolved parameters from getToolByName")
        void populatedPayload() {
            AgentToolDefinition def = AgentToolDefinition.builder()
                    .name("search").description("Search API").category(ToolCategory.UTILITY).build();
            when(toolRegistry.getToolDocumentation("search")).thenReturn(new ToolDocumentation(
                    "search", "Search API", "help here", List.of("ex1"),
                    Map.of("type", "object"), Map.of("type", "string"), ToolCategory.UTILITY, List.of("tag1")));
            when(toolRegistry.getToolByName("search")).thenReturn(Optional.of(def));

            Map<String, Object> d = data(exec("get_tool_help", Map.of("tool_name", "search")));
            assertThat(d).containsEntry("helpText", "help here").containsEntry("category", "utility")
                    .containsEntry("examples", List.of("ex1")).containsEntry("tags", List.of("tag1"))
                    .containsEntry("inputSchema", Map.of("type", "object"));
        }
    }

    // ── get_resource_schema ─────────────────────────────────────────────

    @Nested
    @DisplayName("get_resource_schema")
    class GetResourceSchema {

        @Test
        @DisplayName("missing/blank resource_type -> MISSING_PARAMETER")
        void blankTypeCode() {
            assertThat(exec("get_resource_schema", Map.of()).errorCode()).isEqualTo(ToolErrorCode.MISSING_PARAMETER);
            assertThat(exec("get_resource_schema", Map.of("resource_type", "  ")).errorCode())
                    .isEqualTo(ToolErrorCode.MISSING_PARAMETER);
        }

        @Test
        @DisplayName("workflow returns a non-empty schema + a workflow description")
        void workflowPayload() {
            Map<String, Object> d = data(exec("get_resource_schema", Map.of("resource_type", "workflow")));
            assertThat(d).containsEntry("resourceType", "workflow").containsKey("schema");
            assertThat((Map<?, ?>) d.get("schema")).isNotEmpty();
            assertThat((String) d.get("description")).contains("triggers");
        }

        @Test
        @DisplayName("resource_type is case-insensitive and 'datasource' aliases 'table'")
        void caseInsensitiveAndAlias() {
            assertThat(exec("get_resource_schema", Map.of("resource_type", "WORKFLOW")).success()).isTrue();
            assertThat(exec("get_resource_schema", Map.of("resource_type", "datasource")).success()).isTrue();
        }

        @Test
        @DisplayName("an unknown resource type -> INVALID_ENUM_VALUE")
        void unknownTypeCode() {
            assertThat(exec("get_resource_schema", Map.of("resource_type", "ghost")).errorCode())
                    .isEqualTo(ToolErrorCode.INVALID_ENUM_VALUE);
        }
    }

    // ── get_examples ────────────────────────────────────────────────────

    @Nested
    @DisplayName("get_examples")
    class GetExamples {

        @Test
        @DisplayName("missing resource_type -> MISSING_PARAMETER")
        void blankTypeCode() {
            assertThat(exec("get_examples", Map.of()).errorCode()).isEqualTo(ToolErrorCode.MISSING_PARAMETER);
        }

        @Test
        @DisplayName("workflow examples echo the type, default operation 'all', and carry titled payloads")
        void workflowExamples() {
            Map<String, Object> d = data(exec("get_examples", Map.of("resource_type", "workflow")));
            assertThat(d).containsEntry("resourceType", "workflow").containsEntry("operation", "all");
            List<?> examples = (List<?>) d.get("examples");
            assertThat(examples).isNotEmpty();
            assertThat((Map<String, Object>) examples.get(0)).containsKey("title").containsKey("payload");
        }

        @Test
        @DisplayName("operation is echoed verbatim and does NOT filter: the example set is identical with or without it")
        void operationEchoedNotFiltered() {
            Map<String, Object> withOp = data(exec("get_examples",
                    Map.of("resource_type", "agent", "operation", "create")));
            Map<String, Object> withoutOp = data(exec("get_examples", Map.of("resource_type", "agent")));

            assertThat(withOp).containsEntry("operation", "create");
            assertThat(withoutOp).containsEntry("operation", "all");
            // The examples are byte-identical regardless of operation -> operation is echo-only.
            assertThat(withOp.get("examples")).isEqualTo(withoutOp.get("examples"));
            assertThat((List<?>) withOp.get("examples")).isNotEmpty();
        }

        @Test
        @DisplayName("an unknown resource type yields an empty example list (still success)")
        void unknownResourceEmpty() {
            ToolExecutionResult r = exec("get_examples", Map.of("resource_type", "ghost"));
            assertThat(r.success()).isTrue();
            assertThat((List<?>) data(r).get("examples")).isEmpty();
        }
    }

    // ── expression_help filtering ───────────────────────────────────────

    @Nested
    @DisplayName("expression_help category filtering")
    class ExpressionHelp {

        @Test
        @DisplayName("no category (null -> 'all') returns the full function catalog")
        void fullCatalog() {
            Map<String, Object> d = data(exec("expression_help", Map.of()));
            assertThat(d).containsKeys("description", "syntax", "typeFunctions", "utilityFunctions",
                    "mathFunctions", "stringFunctions", "dateFunctions", "formatFunctions",
                    "jsonFunctions", "collectionOperations", "wrongUsage");
        }

        @Test
        @DisplayName("category='math' returns only math + the always-present description/syntax/wrongUsage")
        void mathOnly() {
            Map<String, Object> d = data(exec("expression_help", Map.of("category", "math")));
            assertThat(d).containsKeys("description", "syntax", "mathFunctions", "wrongUsage");
            assertThat(d).doesNotContainKeys("typeFunctions", "utilityFunctions", "stringFunctions",
                    "dateFunctions", "formatFunctions");
        }

        @Test
        @DisplayName("category='string' bundles stringFunctions + stringMethods")
        void stringBundle() {
            Map<String, Object> d = data(exec("expression_help", Map.of("category", "string")));
            assertThat(d).containsKeys("stringFunctions", "stringMethods");
            assertThat(d).doesNotContainKey("mathFunctions");
        }

        @Test
        @DisplayName("category='date' bundles dateFunctions + datePatterns + formatFunctions")
        void dateBundle() {
            Map<String, Object> d = data(exec("expression_help", Map.of("category", "date")));
            assertThat(d).containsKeys("dateFunctions", "datePatterns", "formatFunctions");
            assertThat(d).doesNotContainKey("stringFunctions");
        }

        @Test
        @DisplayName("category='type' / 'utility' / 'format' each return their single group")
        void singleGroups() {
            assertThat(data(exec("expression_help", Map.of("category", "type"))))
                    .containsKey("typeFunctions").doesNotContainKey("mathFunctions");
            assertThat(data(exec("expression_help", Map.of("category", "utility"))))
                    .containsKey("utilityFunctions").doesNotContainKey("mathFunctions");
            assertThat(data(exec("expression_help", Map.of("category", "format"))))
                    .containsKey("formatFunctions").doesNotContainKey("mathFunctions");
        }

        @Test
        @DisplayName("the category match is case-sensitive: 'ALL' is NOT 'all' so it falls through to a no-group filter")
        void caseSensitiveAll() {
            Map<String, Object> d = data(exec("expression_help", Map.of("category", "ALL")));
            // Not equal to "all" -> filter branch; no switch case matches -> only the always-on keys.
            assertThat(d).containsKeys("description", "syntax", "wrongUsage");
            assertThat(d).doesNotContainKeys("typeFunctions", "mathFunctions", "stringFunctions");
        }

        @Test
        @DisplayName("an unrecognized category falls through to description/syntax/wrongUsage only")
        void unknownCategoryFallThrough() {
            Map<String, Object> d = data(exec("expression_help", Map.of("category", "bogus")));
            assertThat(d).containsKeys("description", "syntax", "wrongUsage");
            assertThat(d).doesNotContainKeys("typeFunctions", "mathFunctions");
        }
    }
}
