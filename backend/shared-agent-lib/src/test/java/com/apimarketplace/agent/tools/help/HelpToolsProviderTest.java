package com.apimarketplace.agent.tools.help;

import com.apimarketplace.agent.registry.AgentToolDefinition;
import com.apimarketplace.agent.registry.AgentToolRegistry;
import com.apimarketplace.agent.registry.ToolCategory;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests for HelpToolsProvider - help and documentation tools.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("HelpToolsProvider")
class HelpToolsProviderTest {

    @Mock
    private AgentToolRegistry toolRegistry;

    private HelpToolsProvider provider;

    @BeforeEach
    void setUp() {
        provider = new HelpToolsProvider(toolRegistry);
    }

    @Nested
    @DisplayName("getCategory()")
    class GetCategoryTests {

        @Test
        @DisplayName("should return HELP category")
        void shouldReturnHelpCategory() {
            assertThat(provider.getCategory()).isEqualTo(ToolCategory.HELP);
        }
    }

    @Nested
    @DisplayName("getTools()")
    class GetToolsTests {

        @Test
        @DisplayName("should return 5 tools")
        void shouldReturnFiveTools() {
            List<AgentToolDefinition> tools = provider.getTools();
            assertThat(tools).hasSize(5);
        }

        @Test
        @DisplayName("should include all expected tool names")
        void shouldIncludeAllToolNames() {
            List<String> toolNames = provider.getTools().stream()
                    .map(AgentToolDefinition::name)
                    .toList();

            assertThat(toolNames).containsExactlyInAnyOrder(
                    "list_all_tools",
                    "get_tool_help",
                    "get_resource_schema",
                    "get_examples",
                    "expression_help"
            );
        }

        @Test
        @DisplayName("should have HELP category for all tools")
        void shouldHaveHelpCategory() {
            provider.getTools().forEach(tool ->
                    assertThat(tool.category()).isEqualTo(ToolCategory.HELP)
            );
        }

        // Coherence guard: the list_all_tools `category` filter enum is what the agent
        // is allowed to pass, and it is validated against ToolCategory.fromSlug at runtime
        // (executeListAllTools returns INVALID_PARAMETER_VALUE for an unknown slug). Every
        // advertised slug MUST resolve, and the enum MUST cover every real category so no
        // category of tools is silently undiscoverable. Regression for the phantom `tasks`
        // slug (rejected at runtime) and the missing `search`/`imagegeneration` slugs.
        @Test
        @DisplayName("list_all_tools category enum resolves to and covers every ToolCategory")
        void categoryFilterEnumMatchesToolCategory() {
            List<String> advertised = provider.getTools().stream()
                    .filter(t -> t.name().equals("list_all_tools"))
                    .flatMap(t -> t.parameters().stream())
                    .filter(p -> p.name().equals("category"))
                    .flatMap(p -> p.enumValues().stream())
                    .toList();

            // No phantom: every advertised slug resolves (would not 400 at runtime).
            assertThat(advertised).allSatisfy(slug ->
                    assertThat(ToolCategory.fromSlug(slug))
                            .as("advertised category slug '%s' must resolve via ToolCategory.fromSlug", slug)
                            .isNotNull());

            // Complete: the enum covers every real category (none undiscoverable).
            List<String> allSlugs = java.util.Arrays.stream(ToolCategory.values())
                    .map(ToolCategory::getSlug)
                    .toList();
            assertThat(advertised).containsExactlyInAnyOrderElementsOf(allSlugs);
        }

        @Test
        @DisplayName("tools should not require auth")
        void shouldNotRequireAuth() {
            provider.getTools().forEach(tool ->
                    assertThat(tool.requiresAuth()).isFalse()
            );
        }

        @Test
        @DisplayName("tools should have descriptions")
        void shouldHaveDescriptions() {
            provider.getTools().forEach(tool ->
                    assertThat(tool.description()).isNotBlank()
            );
        }
    }

    @Nested
    @DisplayName("execute() - list_all_tools")
    class ListAllToolsTests {

        @Test
        @DisplayName("should list all tools when no category filter")
        void shouldListAllTools() {
            AgentToolDefinition toolDef = AgentToolDefinition.builder()
                    .name("test_tool").description("A test tool").category(ToolCategory.UTILITY).build();
            when(toolRegistry.getAllTools()).thenReturn(List.of(toolDef));
            when(toolRegistry.getCategoryCounts()).thenReturn(Map.of("utility", 1));

            ToolExecutionResult result = provider.execute("list_all_tools", Map.of(), ToolExecutionContext.empty());

            assertThat(result.success()).isTrue();
            verify(toolRegistry).getAllTools();
        }

        @Test
        @DisplayName("should filter by category when provided")
        void shouldFilterByCategory() {
            when(toolRegistry.getToolsByCategory(ToolCategory.HELP)).thenReturn(List.of());
            when(toolRegistry.getCategoryCounts()).thenReturn(Map.of());

            ToolExecutionResult result = provider.execute("list_all_tools",
                    Map.of("category", "help"), ToolExecutionContext.empty());

            assertThat(result.success()).isTrue();
            verify(toolRegistry).getToolsByCategory(ToolCategory.HELP);
        }

        @Test
        @DisplayName("should return failure for invalid category")
        void shouldFailForInvalidCategory() {
            ToolExecutionResult result = provider.execute("list_all_tools",
                    Map.of("category", "nonexistent"), ToolExecutionContext.empty());

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("Invalid category");
        }
    }

    @Nested
    @DisplayName("execute() - get_tool_help")
    class GetToolHelpTests {

        @Test
        @DisplayName("should return failure when tool_name is missing")
        void shouldFailWhenToolNameMissing() {
            ToolExecutionResult result = provider.execute("get_tool_help", Map.of(), ToolExecutionContext.empty());

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("tool_name is required");
        }

        @Test
        @DisplayName("should return failure when tool not found")
        void shouldFailWhenToolNotFound() {
            when(toolRegistry.getToolDocumentation("unknown")).thenReturn(null);

            ToolExecutionResult result = provider.execute("get_tool_help",
                    Map.of("tool_name", "unknown"), ToolExecutionContext.empty());

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("Tool not found");
        }

        @Test
        @DisplayName("should return tool documentation when found")
        void shouldReturnDocumentation() {
            AgentToolDefinition toolDef = AgentToolDefinition.builder()
                    .name("search")
                    .description("Search API")
                    .category(ToolCategory.UTILITY)
                    .helpText("Use this to search")
                    .examples(List.of("example1"))
                    .inputSchema(Map.of("type", "object"))
                    .outputSchema(Map.of("type", "string"))
                    .tags(List.of("search"))
                    .build();

            when(toolRegistry.getToolDocumentation("search")).thenReturn(
                    new com.apimarketplace.agent.registry.AgentToolRegistry.ToolDocumentation(
                            "search", "Search API", "Use this to search",
                            List.of("example1"), Map.of("type", "object"), Map.of("type", "string"),
                            ToolCategory.UTILITY, List.of("search")));
            when(toolRegistry.getToolByName("search")).thenReturn(Optional.of(toolDef));

            ToolExecutionResult result = provider.execute("get_tool_help",
                    Map.of("tool_name", "search"), ToolExecutionContext.empty());

            assertThat(result.success()).isTrue();
        }
    }

    @Nested
    @DisplayName("execute() - get_resource_schema")
    class GetResourceSchemaTests {

        @Test
        @DisplayName("should return failure when resource_type is missing")
        void shouldFailWhenTypeMissing() {
            ToolExecutionResult result = provider.execute("get_resource_schema", Map.of(), ToolExecutionContext.empty());

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("resource_type is required");
        }

        @Test
        @DisplayName("should return schema for workflow type")
        void shouldReturnWorkflowSchema() {
            ToolExecutionResult result = provider.execute("get_resource_schema",
                    Map.of("resource_type", "workflow"), ToolExecutionContext.empty());

            assertThat(result.success()).isTrue();
        }

        @Test
        @DisplayName("should return schema for agent type")
        void shouldReturnAgentSchema() {
            ToolExecutionResult result = provider.execute("get_resource_schema",
                    Map.of("resource_type", "agent"), ToolExecutionContext.empty());

            assertThat(result.success()).isTrue();
        }

        @Test
        @DisplayName("should return schema for interface type")
        void shouldReturnInterfaceSchema() {
            ToolExecutionResult result = provider.execute("get_resource_schema",
                    Map.of("resource_type", "interface"), ToolExecutionContext.empty());

            assertThat(result.success()).isTrue();
        }

        @Test
        @DisplayName("should return schema for table/datasource type")
        void shouldReturnTableSchema() {
            ToolExecutionResult result = provider.execute("get_resource_schema",
                    Map.of("resource_type", "table"), ToolExecutionContext.empty());

            assertThat(result.success()).isTrue();
        }

        @Test
        @DisplayName("should return failure for unknown resource type")
        void shouldFailForUnknownType() {
            ToolExecutionResult result = provider.execute("get_resource_schema",
                    Map.of("resource_type", "unknown"), ToolExecutionContext.empty());

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("Unknown resource type");
        }
    }

    @Nested
    @DisplayName("execute() - get_examples")
    class GetExamplesTests {

        @Test
        @DisplayName("should return failure when resource_type is missing")
        void shouldFailWhenTypeMissing() {
            ToolExecutionResult result = provider.execute("get_examples", Map.of(), ToolExecutionContext.empty());

            assertThat(result.success()).isFalse();
        }

        @Test
        @DisplayName("should return workflow examples")
        void shouldReturnWorkflowExamples() {
            ToolExecutionResult result = provider.execute("get_examples",
                    Map.of("resource_type", "workflow"), ToolExecutionContext.empty());

            assertThat(result.success()).isTrue();
        }

        @Test
        @DisplayName("should return agent examples")
        void shouldReturnAgentExamples() {
            ToolExecutionResult result = provider.execute("get_examples",
                    Map.of("resource_type", "agent"), ToolExecutionContext.empty());

            assertThat(result.success()).isTrue();
        }

        @Test
        @DisplayName("should return interface examples")
        void shouldReturnInterfaceExamples() {
            ToolExecutionResult result = provider.execute("get_examples",
                    Map.of("resource_type", "interface"), ToolExecutionContext.empty());

            assertThat(result.success()).isTrue();
        }

        @Test
        @DisplayName("should return datasource examples")
        void shouldReturnDataSourceExamples() {
            ToolExecutionResult result = provider.execute("get_examples",
                    Map.of("resource_type", "datasource"), ToolExecutionContext.empty());

            assertThat(result.success()).isTrue();
        }
    }

    @Nested
    @DisplayName("execute() - expression_help")
    class ExpressionHelpTests {

        @Test
        @DisplayName("should return comprehensive expression documentation")
        void shouldReturnExpressionHelp() {
            ToolExecutionResult result = provider.execute("expression_help", Map.of(), ToolExecutionContext.empty());

            assertThat(result.success()).isTrue();
        }

        @Test
        @DisplayName("should accept category filter")
        void shouldAcceptCategoryFilter() {
            ToolExecutionResult result = provider.execute("expression_help",
                    Map.of("category", "math"), ToolExecutionContext.empty());

            assertThat(result.success()).isTrue();
        }
    }

    @Nested
    @DisplayName("execute() - unknown tool")
    class UnknownToolTests {

        @Test
        @DisplayName("should return failure for unknown tool name")
        void shouldFailForUnknownTool() {
            ToolExecutionResult result = provider.execute("unknown_tool", Map.of(), ToolExecutionContext.empty());

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("Unknown tool");
        }
    }
}
