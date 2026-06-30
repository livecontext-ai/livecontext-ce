package com.apimarketplace.agent.integration;

import com.apimarketplace.agent.domain.ToolParameter;
import com.apimarketplace.agent.registry.AgentToolDefinition;
import com.apimarketplace.agent.registry.AgentToolRegistry;
import com.apimarketplace.agent.registry.DefaultAgentToolRegistry;
import com.apimarketplace.agent.registry.ToolCategory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the Agent Tool Registry subsystem.
 * Tests registration, lookup, search, category filtering, MCP format conversion,
 * and concurrent access using DefaultAgentToolRegistry.
 *
 * No Spring context needed - directly instantiates registry.
 */
@DisplayName("ToolRegistryIntegrationTest - Agent tool registry behavior")
class ToolRegistryIntegrationTest {

    private AgentToolRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new DefaultAgentToolRegistry();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private AgentToolDefinition createTool(String name, String description, ToolCategory category) {
        return AgentToolDefinition.builder()
                .name(name)
                .description(description)
                .category(category)
                .parameters(List.of(
                        ToolParameter.builder()
                                .name("query")
                                .type("string")
                                .description("Search query")
                                .required(true)
                                .build()
                ))
                .requiredParameters(List.of("query"))
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of("query", Map.of("type", "string"))
                ))
                .outputSchema(Map.of("type", "object"))
                .examples(List.of("{\"query\": \"example\"}"))
                .helpText("Help text for " + name)
                .requiresAuth(false)
                .tags(List.of("test", category.getSlug()))
                .build();
    }

    private void registerSampleTools() {
        registry.register(createTool("search_tools", "Search for API tools", ToolCategory.SEARCH));
        registry.register(createTool("search_advanced", "Advanced search", ToolCategory.SEARCH));
        registry.register(createTool("workflow_create", "Create a workflow", ToolCategory.WORKFLOW));
        registry.register(createTool("workflow_execute", "Execute a workflow", ToolCategory.WORKFLOW));
        registry.register(createTool("agent_configure", "Configure an agent", ToolCategory.AGENT));
        registry.register(createTool("datasource_list", "List data sources", ToolCategory.DATASOURCE));
        registry.register(createTool("help_get", "Get help documentation", ToolCategory.HELP));
    }

    // -------------------------------------------------------------------------
    // Registration
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Tool registration")
    class ToolRegistration {

        @Test
        @DisplayName("should register a single tool")
        void shouldRegisterSingleTool() {
            AgentToolDefinition tool = createTool("my_tool", "My test tool", ToolCategory.UTILITY);
            registry.register(tool);

            assertThat(registry.hasTool("my_tool")).isTrue();
            assertThat(registry.getToolCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("should register multiple tools at once")
        void shouldRegisterMultipleTools() {
            List<AgentToolDefinition> tools = List.of(
                    createTool("tool_a", "Tool A", ToolCategory.SEARCH),
                    createTool("tool_b", "Tool B", ToolCategory.WORKFLOW),
                    createTool("tool_c", "Tool C", ToolCategory.AGENT)
            );

            registry.registerAll(tools);

            assertThat(registry.getToolCount()).isEqualTo(3);
            assertThat(registry.hasTool("tool_a")).isTrue();
            assertThat(registry.hasTool("tool_b")).isTrue();
            assertThat(registry.hasTool("tool_c")).isTrue();
        }

        @Test
        @DisplayName("should overwrite tool with same name")
        void shouldOverwriteToolWithSameName() {
            registry.register(createTool("tool_x", "Original", ToolCategory.SEARCH));
            registry.register(createTool("tool_x", "Updated", ToolCategory.WORKFLOW));

            assertThat(registry.getToolCount()).isEqualTo(1);
            Optional<AgentToolDefinition> tool = registry.getToolByName("tool_x");
            assertThat(tool).isPresent();
            assertThat(tool.get().description()).isEqualTo("Updated");
            assertThat(tool.get().category()).isEqualTo(ToolCategory.WORKFLOW);
        }

        @Test
        @DisplayName("should handle null tool registration gracefully")
        void shouldHandleNullToolGracefully() {
            registry.register(null);
            assertThat(registry.getToolCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("should handle null tool list registration gracefully")
        void shouldHandleNullListGracefully() {
            registry.registerAll(null);
            assertThat(registry.getToolCount()).isEqualTo(0);
        }
    }

    // -------------------------------------------------------------------------
    // Lookup
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Tool lookup")
    class ToolLookup {

        @Test
        @DisplayName("should find tool by name")
        void shouldFindToolByName() {
            registerSampleTools();

            Optional<AgentToolDefinition> tool = registry.getToolByName("workflow_create");
            assertThat(tool).isPresent();
            assertThat(tool.get().name()).isEqualTo("workflow_create");
            assertThat(tool.get().category()).isEqualTo(ToolCategory.WORKFLOW);
        }

        @Test
        @DisplayName("should return empty for non-existent tool")
        void shouldReturnEmptyForNonExistent() {
            registerSampleTools();

            Optional<AgentToolDefinition> tool = registry.getToolByName("nonexistent_tool");
            assertThat(tool).isEmpty();
        }

        @Test
        @DisplayName("should return empty for null tool name")
        void shouldReturnEmptyForNullName() {
            Optional<AgentToolDefinition> tool = registry.getToolByName(null);
            assertThat(tool).isEmpty();
        }

        @Test
        @DisplayName("should check tool existence with hasTool")
        void shouldCheckExistence() {
            registerSampleTools();

            assertThat(registry.hasTool("search_tools")).isTrue();
            assertThat(registry.hasTool("nonexistent")).isFalse();
            assertThat(registry.hasTool(null)).isFalse();
        }

        @Test
        @DisplayName("should return all tools")
        void shouldReturnAllTools() {
            registerSampleTools();

            List<AgentToolDefinition> allTools = registry.getAllTools();
            assertThat(allTools).hasSize(7);
        }
    }

    // -------------------------------------------------------------------------
    // Category filtering
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Category filtering")
    class CategoryFiltering {

        @Test
        @DisplayName("should filter tools by category")
        void shouldFilterByCategory() {
            registerSampleTools();

            List<AgentToolDefinition> searchTools = registry.getToolsByCategory(ToolCategory.SEARCH);
            assertThat(searchTools).hasSize(2);
            assertThat(searchTools).allMatch(t -> t.category() == ToolCategory.SEARCH);

            List<AgentToolDefinition> workflowTools = registry.getToolsByCategory(ToolCategory.WORKFLOW);
            assertThat(workflowTools).hasSize(2);
        }

        @Test
        @DisplayName("should return empty list for category with no tools")
        void shouldReturnEmptyForEmptyCategory() {
            registerSampleTools();

            List<AgentToolDefinition> tools = registry.getToolsByCategory(ToolCategory.VISUALIZATION);
            assertThat(tools).isEmpty();
        }

        @Test
        @DisplayName("should return empty list for null category")
        void shouldReturnEmptyForNullCategory() {
            registerSampleTools();

            List<AgentToolDefinition> tools = registry.getToolsByCategory(null);
            assertThat(tools).isEmpty();
        }

        @Test
        @DisplayName("should count tools per category")
        void shouldCountToolsPerCategory() {
            registerSampleTools();

            Map<String, Integer> counts = registry.getCategoryCounts();
            assertThat(counts.get("search")).isEqualTo(2);
            assertThat(counts.get("workflow")).isEqualTo(2);
            assertThat(counts.get("agent")).isEqualTo(1);
            assertThat(counts.get("datasource")).isEqualTo(1);
            assertThat(counts.get("help")).isEqualTo(1);
            assertThat(counts.get("visualization")).isEqualTo(0);
        }
    }

    // -------------------------------------------------------------------------
    // Search
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Tool search")
    class ToolSearch {

        @Test
        @DisplayName("should search tools by name match")
        void shouldSearchByNameMatch() {
            registerSampleTools();

            List<AgentToolDefinition> results = registry.searchTools("search", 10);
            assertThat(results).isNotEmpty();
            assertThat(results).anyMatch(t -> t.name().contains("search"));
        }

        @Test
        @DisplayName("should search tools by description match")
        void shouldSearchByDescriptionMatch() {
            registerSampleTools();

            List<AgentToolDefinition> results = registry.searchTools("workflow", 10);
            assertThat(results).isNotEmpty();
        }

        @Test
        @DisplayName("should search tools by category slug")
        void shouldSearchByCategorySlug() {
            registerSampleTools();

            List<AgentToolDefinition> results = registry.searchTools("help", 10);
            assertThat(results).isNotEmpty();
            assertThat(results).anyMatch(t -> t.category() == ToolCategory.HELP);
        }

        @Test
        @DisplayName("should search tools by tag")
        void shouldSearchByTag() {
            registerSampleTools();

            List<AgentToolDefinition> results = registry.searchTools("test", 10);
            assertThat(results).isNotEmpty();
        }

        @Test
        @DisplayName("should respect max results limit")
        void shouldRespectMaxResults() {
            registerSampleTools();

            List<AgentToolDefinition> results = registry.searchTools("", 3);
            assertThat(results).hasSizeLessThanOrEqualTo(3);
        }

        @Test
        @DisplayName("should rank exact name match higher")
        void shouldRankExactMatchHigher() {
            registerSampleTools();

            List<AgentToolDefinition> results = registry.searchTools("search_tools", 10);
            assertThat(results).isNotEmpty();
            // Exact name match should be first
            assertThat(results.get(0).name()).isEqualTo("search_tools");
        }

        @Test
        @DisplayName("should return all tools for blank query")
        void shouldReturnAllForBlankQuery() {
            registerSampleTools();

            List<AgentToolDefinition> results = registry.searchTools("", 100);
            assertThat(results).hasSize(7);
        }

        @Test
        @DisplayName("should return all tools for null query")
        void shouldReturnAllForNullQuery() {
            registerSampleTools();

            List<AgentToolDefinition> results = registry.searchTools(null, 100);
            assertThat(results).hasSize(7);
        }
    }

    // -------------------------------------------------------------------------
    // Schema and documentation
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Schema and documentation")
    class SchemaAndDocumentation {

        @Test
        @DisplayName("should get tool input schema")
        void shouldGetInputSchema() {
            registerSampleTools();

            Map<String, Object> schema = registry.getToolInputSchema("search_tools");
            assertThat(schema).isNotEmpty();
            assertThat(schema).containsKey("type");
        }

        @Test
        @DisplayName("should return empty map for non-existent tool input schema")
        void shouldReturnEmptyForNonExistentInputSchema() {
            Map<String, Object> schema = registry.getToolInputSchema("nonexistent");
            assertThat(schema).isEmpty();
        }

        @Test
        @DisplayName("should get tool output schema")
        void shouldGetOutputSchema() {
            registerSampleTools();

            Map<String, Object> schema = registry.getToolOutputSchema("search_tools");
            assertThat(schema).isNotEmpty();
        }

        @Test
        @DisplayName("should get tool documentation")
        void shouldGetDocumentation() {
            registerSampleTools();

            AgentToolRegistry.ToolDocumentation doc = registry.getToolDocumentation("search_tools");
            assertThat(doc).isNotNull();
            assertThat(doc.name()).isEqualTo("search_tools");
            assertThat(doc.description()).isEqualTo("Search for API tools");
            assertThat(doc.helpText()).isEqualTo("Help text for search_tools");
            assertThat(doc.category()).isEqualTo(ToolCategory.SEARCH);
        }

        @Test
        @DisplayName("should return null documentation for non-existent tool")
        void shouldReturnNullDocForNonExistent() {
            AgentToolRegistry.ToolDocumentation doc = registry.getToolDocumentation("nonexistent");
            assertThat(doc).isNull();
        }
    }

    // -------------------------------------------------------------------------
    // MCP format conversion
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("MCP format conversion")
    class McpFormatConversion {

        @Test
        @DisplayName("should convert tools to MCP format")
        void shouldConvertToMcpFormat() {
            registerSampleTools();

            List<Map<String, Object>> mcpTools = registry.getToolsInMcpFormat();
            assertThat(mcpTools).hasSize(7);

            Map<String, Object> firstTool = mcpTools.get(0);
            assertThat(firstTool).containsKey("name");
            assertThat(firstTool).containsKey("description");
            assertThat(firstTool).containsKey("inputSchema");
        }

        @Test
        @DisplayName("should include inputSchema in MCP format")
        void shouldIncludeInputSchemaInMcpFormat() {
            registry.register(createTool("test_tool", "Test", ToolCategory.UTILITY));

            List<Map<String, Object>> mcpTools = registry.getToolsInMcpFormat();
            assertThat(mcpTools).hasSize(1);

            @SuppressWarnings("unchecked")
            Map<String, Object> inputSchema = (Map<String, Object>) mcpTools.get(0).get("inputSchema");
            assertThat(inputSchema).containsKey("type");
        }
    }

    // -------------------------------------------------------------------------
    // AgentToolDefinition methods
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("AgentToolDefinition methods")
    class AgentToolDefinitionMethods {

        @Test
        @DisplayName("should check if parameter is required")
        void shouldCheckParameterRequired() {
            AgentToolDefinition tool = createTool("test", "Test", ToolCategory.UTILITY);

            assertThat(tool.isParameterRequired("query")).isTrue();
            assertThat(tool.isParameterRequired("nonexistent")).isFalse();
        }

        @Test
        @DisplayName("should get parameter by name")
        void shouldGetParameterByName() {
            AgentToolDefinition tool = createTool("test", "Test", ToolCategory.UTILITY);

            ToolParameter param = tool.getParameter("query");
            assertThat(param).isNotNull();
            assertThat(param.name()).isEqualTo("query");
            assertThat(param.type()).isEqualTo("string");
            assertThat(param.required()).isTrue();
        }

        @Test
        @DisplayName("should return null for non-existent parameter")
        void shouldReturnNullForNonExistentParam() {
            AgentToolDefinition tool = createTool("test", "Test", ToolCategory.UTILITY);

            ToolParameter param = tool.getParameter("nonexistent");
            assertThat(param).isNull();
        }

        @Test
        @DisplayName("should produce correct summary")
        void shouldProduceCorrectSummary() {
            AgentToolDefinition tool = createTool("test_tool", "A test tool", ToolCategory.SEARCH);

            Map<String, Object> summary = tool.toSummary();
            assertThat(summary.get("name")).isEqualTo("test_tool");
            assertThat(summary.get("description")).isEqualTo("A test tool");
            assertThat(summary.get("category")).isEqualTo("search");
            assertThat(summary.get("requiresAuth")).isEqualTo(false);
            assertThat(summary.get("parameterCount")).isEqualTo(1);
        }

        @Test
        @DisplayName("should produce correct MCP format")
        void shouldProduceCorrectMcpFormat() {
            AgentToolDefinition tool = createTool("mcp_tool", "MCP Tool", ToolCategory.CATALOG);

            Map<String, Object> mcpFormat = tool.toMcpFormat();
            assertThat(mcpFormat.get("name")).isEqualTo("mcp_tool");
            assertThat(mcpFormat.get("description")).isEqualTo("MCP Tool");
            assertThat(mcpFormat).containsKey("inputSchema");
        }
    }

    // -------------------------------------------------------------------------
    // Concurrent access
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Concurrent access")
    class ConcurrentAccess {

        @Test
        @DisplayName("should handle concurrent registrations safely")
        void shouldHandleConcurrentRegistrations() throws InterruptedException {
            int threadCount = 10;
            int toolsPerThread = 50;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch latch = new CountDownLatch(threadCount);
            AtomicInteger errors = new AtomicInteger(0);

            for (int t = 0; t < threadCount; t++) {
                final int threadId = t;
                executor.submit(() -> {
                    try {
                        for (int i = 0; i < toolsPerThread; i++) {
                            String toolName = "tool_" + threadId + "_" + i;
                            registry.register(createTool(toolName, "Tool " + i, ToolCategory.UTILITY));
                        }
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    } finally {
                        latch.countDown();
                    }
                });
            }

            boolean finished = latch.await(10, TimeUnit.SECONDS);
            executor.shutdown();

            assertThat(finished).isTrue();
            assertThat(errors.get()).isZero();
            assertThat(registry.getToolCount()).isEqualTo(threadCount * toolsPerThread);
        }

        @Test
        @DisplayName("should handle concurrent reads and writes safely")
        void shouldHandleConcurrentReadsAndWrites() throws InterruptedException {
            registerSampleTools();

            int threadCount = 10;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch latch = new CountDownLatch(threadCount);
            AtomicInteger errors = new AtomicInteger(0);

            for (int t = 0; t < threadCount; t++) {
                final int threadId = t;
                executor.submit(() -> {
                    try {
                        for (int i = 0; i < 100; i++) {
                            // Mix reads and writes
                            if (i % 3 == 0) {
                                registry.register(createTool(
                                        "concurrent_" + threadId + "_" + i,
                                        "Concurrent tool",
                                        ToolCategory.UTILITY));
                            } else if (i % 3 == 1) {
                                registry.getAllTools();
                            } else {
                                registry.searchTools("search", 5);
                            }
                        }
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    } finally {
                        latch.countDown();
                    }
                });
            }

            boolean finished = latch.await(10, TimeUnit.SECONDS);
            executor.shutdown();

            assertThat(finished).isTrue();
            assertThat(errors.get()).isZero();
        }
    }

    // -------------------------------------------------------------------------
    // ToolCategory enum
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("ToolCategory enum")
    class ToolCategoryTests {

        @Test
        @DisplayName("should resolve category from slug")
        void shouldResolveCategoryFromSlug() {
            assertThat(ToolCategory.fromSlug("search")).isEqualTo(ToolCategory.SEARCH);
            assertThat(ToolCategory.fromSlug("workflow")).isEqualTo(ToolCategory.WORKFLOW);
            assertThat(ToolCategory.fromSlug("agent")).isEqualTo(ToolCategory.AGENT);
            assertThat(ToolCategory.fromSlug("datasource")).isEqualTo(ToolCategory.DATASOURCE);
            assertThat(ToolCategory.fromSlug("help")).isEqualTo(ToolCategory.HELP);
        }

        @Test
        @DisplayName("should resolve slug case-insensitively")
        void shouldResolveCaseInsensitive() {
            assertThat(ToolCategory.fromSlug("SEARCH")).isEqualTo(ToolCategory.SEARCH);
            assertThat(ToolCategory.fromSlug("Search")).isEqualTo(ToolCategory.SEARCH);
        }

        @Test
        @DisplayName("should return null for unknown slug")
        void shouldReturnNullForUnknownSlug() {
            assertThat(ToolCategory.fromSlug("unknown")).isNull();
            assertThat(ToolCategory.fromSlug(null)).isNull();
        }

        @Test
        @DisplayName("should have display name and description for all categories")
        void shouldHaveDisplayInfo() {
            for (ToolCategory category : ToolCategory.values()) {
                assertThat(category.getSlug()).isNotNull().isNotBlank();
                assertThat(category.getDisplayName()).isNotNull().isNotBlank();
                assertThat(category.getDescription()).isNotNull().isNotBlank();
            }
        }
    }
}
