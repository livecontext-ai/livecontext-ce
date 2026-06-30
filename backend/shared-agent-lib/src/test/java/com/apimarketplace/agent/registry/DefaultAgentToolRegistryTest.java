package com.apimarketplace.agent.registry;

import com.apimarketplace.agent.domain.ToolParameter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for DefaultAgentToolRegistry - thread-safe in-memory tool registry.
 */
@DisplayName("DefaultAgentToolRegistry")
class DefaultAgentToolRegistryTest {

    private DefaultAgentToolRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new DefaultAgentToolRegistry();
    }

    private AgentToolDefinition createTool(String name, ToolCategory category) {
        return AgentToolDefinition.builder()
                .name(name)
                .description("Description of " + name)
                .category(category)
                .parameters(List.of())
                .requiredParameters(List.of())
                .tags(List.of("tag1"))
                .build();
    }

    @Nested
    @DisplayName("register()")
    class RegisterTests {

        @Test
        @DisplayName("should register a tool")
        void shouldRegisterTool() {
            AgentToolDefinition tool = createTool("search_api", ToolCategory.CATALOG);
            registry.register(tool);

            assertThat(registry.hasTool("search_api")).isTrue();
            assertThat(registry.getToolCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("should ignore null tool")
        void shouldIgnoreNullTool() {
            registry.register(null);
            assertThat(registry.getToolCount()).isZero();
        }

        @Test
        @DisplayName("should ignore tool with null name")
        void shouldIgnoreToolWithNullName() {
            AgentToolDefinition tool = AgentToolDefinition.builder()
                    .description("no name").category(ToolCategory.UTILITY).build();
            registry.register(tool);
            assertThat(registry.getToolCount()).isZero();
        }

        @Test
        @DisplayName("should overwrite tool with same name")
        void shouldOverwriteToolWithSameName() {
            registry.register(createTool("tool1", ToolCategory.SEARCH));
            registry.register(createTool("tool1", ToolCategory.CATALOG));

            assertThat(registry.getToolCount()).isEqualTo(1);
            Optional<AgentToolDefinition> tool = registry.getToolByName("tool1");
            assertThat(tool).isPresent();
            assertThat(tool.get().category()).isEqualTo(ToolCategory.CATALOG);
        }
    }

    @Nested
    @DisplayName("registerAll()")
    class RegisterAllTests {

        @Test
        @DisplayName("should register multiple tools")
        void shouldRegisterMultiple() {
            registry.registerAll(List.of(
                    createTool("t1", ToolCategory.SEARCH),
                    createTool("t2", ToolCategory.WORKFLOW),
                    createTool("t3", ToolCategory.HELP)
            ));

            assertThat(registry.getToolCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("should handle null list")
        void shouldHandleNullList() {
            registry.registerAll(null);
            assertThat(registry.getToolCount()).isZero();
        }
    }

    @Nested
    @DisplayName("getToolsByCategory()")
    class GetByCategoryTests {

        @Test
        @DisplayName("should return tools for specific category")
        void shouldReturnToolsForCategory() {
            registry.registerAll(List.of(
                    createTool("s1", ToolCategory.SEARCH),
                    createTool("s2", ToolCategory.SEARCH),
                    createTool("w1", ToolCategory.WORKFLOW)
            ));

            List<AgentToolDefinition> searchTools = registry.getToolsByCategory(ToolCategory.SEARCH);
            assertThat(searchTools).hasSize(2);
        }

        @Test
        @DisplayName("should return empty list for category with no tools")
        void shouldReturnEmptyForNoTools() {
            registry.register(createTool("t1", ToolCategory.SEARCH));
            assertThat(registry.getToolsByCategory(ToolCategory.WORKFLOW)).isEmpty();
        }

        @Test
        @DisplayName("should return empty list for null category")
        void shouldReturnEmptyForNull() {
            assertThat(registry.getToolsByCategory(null)).isEmpty();
        }
    }

    @Nested
    @DisplayName("getToolByName()")
    class GetByNameTests {

        @Test
        @DisplayName("should return tool when found")
        void shouldReturnToolWhenFound() {
            registry.register(createTool("search_api", ToolCategory.CATALOG));

            Optional<AgentToolDefinition> result = registry.getToolByName("search_api");
            assertThat(result).isPresent();
            assertThat(result.get().name()).isEqualTo("search_api");
        }

        @Test
        @DisplayName("should return empty when not found")
        void shouldReturnEmptyWhenNotFound() {
            assertThat(registry.getToolByName("nonexistent")).isEmpty();
        }

        @Test
        @DisplayName("should return empty for null name")
        void shouldReturnEmptyForNull() {
            assertThat(registry.getToolByName(null)).isEmpty();
        }
    }

    @Nested
    @DisplayName("searchTools()")
    class SearchToolsTests {

        @BeforeEach
        void registerTestTools() {
            registry.registerAll(List.of(
                    createTool("catalog", ToolCategory.CATALOG),
                    createTool("catalog_ops", ToolCategory.CATALOG),
                    createTool("workflow_create", ToolCategory.WORKFLOW),
                    createTool("table_list", ToolCategory.DATASOURCE)
            ));
        }

        @Test
        @DisplayName("should find tools matching query by name")
        void shouldFindByName() {
            List<AgentToolDefinition> results = registry.searchTools("catalog", 10);
            assertThat(results).hasSize(2);
        }

        @Test
        @DisplayName("should find tools matching query by category slug")
        void shouldFindByCategory() {
            List<AgentToolDefinition> results = registry.searchTools("workflow", 10);
            assertThat(results).isNotEmpty();
        }

        @Test
        @DisplayName("should respect maxResults limit")
        void shouldRespectLimit() {
            List<AgentToolDefinition> results = registry.searchTools("catalog", 1);
            assertThat(results).hasSize(1);
        }

        @Test
        @DisplayName("should return all tools for blank query")
        void shouldReturnAllForBlank() {
            List<AgentToolDefinition> results = registry.searchTools("", 100);
            assertThat(results).hasSize(4);
        }

        @Test
        @DisplayName("should return all tools for null query")
        void shouldReturnAllForNull() {
            List<AgentToolDefinition> results = registry.searchTools(null, 100);
            assertThat(results).hasSize(4);
        }

        @Test
        @DisplayName("should rank exact name matches higher")
        void shouldRankExactMatchesHigher() {
            List<AgentToolDefinition> results = registry.searchTools("catalog", 10);
            assertThat(results).isNotEmpty();
            assertThat(results.get(0).name()).isEqualTo("catalog");
        }
    }

    @Nested
    @DisplayName("Schema and documentation")
    class SchemaDocTests {

        @Test
        @DisplayName("getToolInputSchema should return schema")
        void shouldReturnInputSchema() {
            AgentToolDefinition tool = AgentToolDefinition.builder()
                    .name("t1")
                    .description("test")
                    .category(ToolCategory.UTILITY)
                    .inputSchema(Map.of("type", "object"))
                    .build();
            registry.register(tool);

            assertThat(registry.getToolInputSchema("t1")).containsEntry("type", "object");
        }

        @Test
        @DisplayName("getToolInputSchema should return empty map for unknown tool")
        void shouldReturnEmptyForUnknown() {
            assertThat(registry.getToolInputSchema("unknown")).isEmpty();
        }

        @Test
        @DisplayName("getToolDocumentation should return documentation")
        void shouldReturnDocumentation() {
            AgentToolDefinition tool = AgentToolDefinition.builder()
                    .name("t1")
                    .description("A tool")
                    .category(ToolCategory.HELP)
                    .helpText("Help text here")
                    .examples(List.of("example1"))
                    .tags(List.of("tag1"))
                    .build();
            registry.register(tool);

            AgentToolRegistry.ToolDocumentation doc = registry.getToolDocumentation("t1");
            assertThat(doc).isNotNull();
            assertThat(doc.name()).isEqualTo("t1");
            assertThat(doc.helpText()).isEqualTo("Help text here");
        }

        @Test
        @DisplayName("getToolDocumentation should return null for unknown tool")
        void shouldReturnNullForUnknown() {
            assertThat(registry.getToolDocumentation("unknown")).isNull();
        }
    }

    @Nested
    @DisplayName("getCategoryCounts()")
    class CategoryCountsTests {

        @Test
        @DisplayName("should return counts per category")
        void shouldReturnCounts() {
            registry.registerAll(List.of(
                    createTool("s1", ToolCategory.SEARCH),
                    createTool("s2", ToolCategory.SEARCH),
                    createTool("w1", ToolCategory.WORKFLOW)
            ));

            Map<String, Integer> counts = registry.getCategoryCounts();
            assertThat(counts.get("search")).isEqualTo(2);
            assertThat(counts.get("workflow")).isEqualTo(1);
            assertThat(counts.get("help")).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("hasTool() and getToolCount()")
    class PresenceTests {

        @Test
        @DisplayName("hasTool should return false for null")
        void hasToolShouldReturnFalseForNull() {
            assertThat(registry.hasTool(null)).isFalse();
        }

        @Test
        @DisplayName("getToolCount should return correct count")
        void getToolCountShouldBeCorrect() {
            assertThat(registry.getToolCount()).isZero();
            registry.register(createTool("t1", ToolCategory.UTILITY));
            assertThat(registry.getToolCount()).isEqualTo(1);
        }
    }
}
