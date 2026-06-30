package com.apimarketplace.agent.integration;

import com.apimarketplace.agent.domain.ToolParameter;
import com.apimarketplace.agent.registry.AgentToolDefinition;
import com.apimarketplace.agent.registry.AgentToolRegistry;
import com.apimarketplace.agent.registry.DefaultAgentToolRegistry;
import com.apimarketplace.agent.registry.ToolCategory;
import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider;
import com.apimarketplace.agent.tools.ToolsRegistrationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for ToolsRegistrationService.
 * Tests auto-registration, provider lookup, tool execution pipeline,
 * error handling, and cache statistics.
 *
 * No Spring context needed - directly instantiates with test providers.
 */
@DisplayName("ToolsRegistrationServiceIntegrationTest - Tool registration and execution pipeline")
class ToolsRegistrationServiceIntegrationTest {

    private AgentToolRegistry registry;
    private ToolsRegistrationService registrationService;
    private TestToolsProvider searchProvider;
    private TestToolsProvider workflowProvider;

    @BeforeEach
    void setUp() {
        registry = new DefaultAgentToolRegistry();
        searchProvider = new TestToolsProvider(ToolCategory.SEARCH,
                List.of(
                        buildTool("search_tools", "Search for tools", ToolCategory.SEARCH),
                        buildTool("search_advanced", "Advanced search", ToolCategory.SEARCH)
                ));
        workflowProvider = new TestToolsProvider(ToolCategory.WORKFLOW,
                List.of(
                        buildTool("workflow_create", "Create workflow", ToolCategory.WORKFLOW),
                        buildTool("workflow_execute", "Execute workflow", ToolCategory.WORKFLOW)
                ));

        registrationService = new ToolsRegistrationService(
                registry,
                List.of(searchProvider, workflowProvider),
                null,   // No validator for these tests
                null,   // No slim-coercer for these tests
                null    // No interceptors for these tests
        );

        // Trigger @PostConstruct manually
        registrationService.registerAllTools();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private AgentToolDefinition buildTool(String name, String description, ToolCategory category) {
        return AgentToolDefinition.builder()
                .name(name)
                .description(description)
                .category(category)
                .parameters(List.of(
                        ToolParameter.builder()
                                .name("query")
                                .type("string")
                                .description("Input query")
                                .required(true)
                                .build()
                ))
                .requiredParameters(List.of("query"))
                .inputSchema(Map.of("type", "object", "properties", Map.of("query", Map.of("type", "string"))))
                .outputSchema(Map.of("type", "object"))
                .examples(List.of())
                .helpText("Help for " + name)
                .requiresAuth(false)
                .tags(List.of())
                .build();
    }

    /**
     * Test implementation of ToolsProvider for integration testing.
     */
    static class TestToolsProvider implements ToolsProvider {
        private final ToolCategory category;
        private final List<AgentToolDefinition> tools;
        private int executeCount = 0;

        TestToolsProvider(ToolCategory category, List<AgentToolDefinition> tools) {
            this.category = category;
            this.tools = tools;
        }

        @Override
        public ToolCategory getCategory() {
            return category;
        }

        @Override
        public List<AgentToolDefinition> getTools() {
            return tools;
        }

        @Override
        public ToolExecutionResult execute(String toolName, Map<String, Object> parameters, ToolExecutionContext context) {
            executeCount++;
            if (toolName.contains("error")) {
                throw new RuntimeException("Simulated error for " + toolName);
            }
            return ToolExecutionResult.success(
                    Map.of("tool", toolName, "result", "executed", "params", parameters)
            );
        }

        int getExecuteCount() {
            return executeCount;
        }
    }

    // -------------------------------------------------------------------------
    // Auto-registration
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Auto-registration")
    class AutoRegistration {

        @Test
        @DisplayName("should register all tools from all providers")
        void shouldRegisterAllTools() {
            assertThat(registry.getToolCount()).isEqualTo(4);
            assertThat(registry.hasTool("search_tools")).isTrue();
            assertThat(registry.hasTool("search_advanced")).isTrue();
            assertThat(registry.hasTool("workflow_create")).isTrue();
            assertThat(registry.hasTool("workflow_execute")).isTrue();
        }

        @Test
        @DisplayName("should register tools in correct categories")
        void shouldRegisterInCorrectCategories() {
            List<AgentToolDefinition> searchTools = registry.getToolsByCategory(ToolCategory.SEARCH);
            assertThat(searchTools).hasSize(2);

            List<AgentToolDefinition> workflowTools = registry.getToolsByCategory(ToolCategory.WORKFLOW);
            assertThat(workflowTools).hasSize(2);
        }

        @Test
        @DisplayName("should work with empty provider list")
        void shouldWorkWithEmptyProviders() {
            AgentToolRegistry emptyRegistry = new DefaultAgentToolRegistry();
            ToolsRegistrationService emptyService = new ToolsRegistrationService(
                    emptyRegistry, List.of(), null, null, null);
            emptyService.registerAllTools();

            assertThat(emptyRegistry.getToolCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("should work with null provider list")
        void shouldWorkWithNullProviders() {
            AgentToolRegistry emptyRegistry = new DefaultAgentToolRegistry();
            ToolsRegistrationService nullService = new ToolsRegistrationService(
                    emptyRegistry, null, null, null, null);
            nullService.registerAllTools();

            assertThat(emptyRegistry.getToolCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("should abort startup when a provider fails to register")
        void shouldAbortStartupOnProviderFailure() {
            ToolsProvider failingProvider = new ToolsProvider() {
                @Override
                public ToolCategory getCategory() { return ToolCategory.WORKFLOW; }
                @Override
                public List<AgentToolDefinition> getTools() {
                    throw new RuntimeException("Simulated deserialization error");
                }
                @Override
                public ToolExecutionResult execute(String t, Map<String, Object> p, ToolExecutionContext c) {
                    return ToolExecutionResult.failure("not reached");
                }
            };

            AgentToolRegistry failRegistry = new DefaultAgentToolRegistry();
            ToolsRegistrationService failService = new ToolsRegistrationService(
                    failRegistry, List.of(searchProvider, failingProvider), null, null, null);

            assertThatThrownBy(failService::registerAllTools)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("failed to register")
                    .hasMessageContaining("Simulated deserialization error");
        }
    }

    // -------------------------------------------------------------------------
    // Provider lookup
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Provider lookup")
    class ProviderLookup {

        @Test
        @DisplayName("should find provider for registered tool (O(1) cache)")
        void shouldFindProviderForRegisteredTool() {
            ToolsProvider provider = registrationService.getProviderForTool("search_tools");
            assertThat(provider).isNotNull();
            assertThat(provider.getCategory()).isEqualTo(ToolCategory.SEARCH);
        }

        @Test
        @DisplayName("should find correct provider for each tool category")
        void shouldFindCorrectProviderPerCategory() {
            ToolsProvider searchProv = registrationService.getProviderForTool("search_tools");
            ToolsProvider workflowProv = registrationService.getProviderForTool("workflow_create");

            assertThat(searchProv).isNotNull();
            assertThat(workflowProv).isNotNull();
            assertThat(searchProv.getCategory()).isEqualTo(ToolCategory.SEARCH);
            assertThat(workflowProv.getCategory()).isEqualTo(ToolCategory.WORKFLOW);
        }

        @Test
        @DisplayName("should return null for non-existent tool")
        void shouldReturnNullForNonExistent() {
            ToolsProvider provider = registrationService.getProviderForTool("nonexistent_tool");
            assertThat(provider).isNull();
        }
    }

    // -------------------------------------------------------------------------
    // Tool execution
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Tool execution")
    class ToolExecution {

        @Test
        @DisplayName("should execute tool successfully")
        void shouldExecuteToolSuccessfully() {
            ToolsProvider.ToolExecutionResult result = registrationService.executeTool(
                    "search_tools",
                    Map.of("query", "weather"),
                    ToolsProvider.ToolExecutionContext.of("tenant-1")
            );

            assertThat(result.success()).isTrue();
            assertThat(result.data()).isNotNull();
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.data();
            assertThat(data.get("tool")).isEqualTo("search_tools");
        }

        @Test
        @DisplayName("should return TOOL_NOT_FOUND for unknown tools")
        void shouldReturnNotFoundForUnknown() {
            ToolsProvider.ToolExecutionResult result = registrationService.executeTool(
                    "nonexistent_tool",
                    Map.of(),
                    ToolsProvider.ToolExecutionContext.of("tenant-1")
            );

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.TOOL_NOT_FOUND);
            assertThat(result.error()).contains("Tool not found");
        }

        @Test
        @DisplayName("should handle execution exceptions gracefully")
        void shouldHandleExecutionExceptions() {
            // Add a tool that throws on execution
            TestToolsProvider errorProvider = new TestToolsProvider(ToolCategory.UTILITY,
                    List.of(buildTool("error_tool", "Error tool", ToolCategory.UTILITY)));
            AgentToolRegistry errorRegistry = new DefaultAgentToolRegistry();
            ToolsRegistrationService errorService = new ToolsRegistrationService(
                    errorRegistry, List.of(errorProvider), null, null, null);
            errorService.registerAllTools();

            ToolsProvider.ToolExecutionResult result = errorService.executeTool(
                    "error_tool",
                    Map.of("query", "test"),
                    ToolsProvider.ToolExecutionContext.of("tenant-1")
            );

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.EXECUTION_FAILED);
        }

        @Test
        @DisplayName("should pass parameters to provider correctly")
        void shouldPassParametersCorrectly() {
            Map<String, Object> params = Map.of(
                    "query", "find weather API",
                    "limit", 10,
                    "category", "weather"
            );

            ToolsProvider.ToolExecutionResult result = registrationService.executeTool(
                    "search_tools",
                    params,
                    ToolsProvider.ToolExecutionContext.of("tenant-1")
            );

            assertThat(result.success()).isTrue();
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.data();
            @SuppressWarnings("unchecked")
            Map<String, Object> resultParams = (Map<String, Object>) data.get("params");
            assertThat(resultParams).containsEntry("query", "find weather API");
        }
    }

    // -------------------------------------------------------------------------
    // Async execution
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Async execution")
    class AsyncExecution {

        @Test
        @DisplayName("should execute tool asynchronously")
        void shouldExecuteAsync() throws Exception {
            CompletableFuture<ToolsProvider.ToolExecutionResult> future =
                    registrationService.executeToolAsync(
                            "workflow_create",
                            Map.of("query", "test"),
                            ToolsProvider.ToolExecutionContext.of("tenant-1")
                    );

            ToolsProvider.ToolExecutionResult result = future.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(result.success()).isTrue();
        }

        @Test
        @DisplayName("should return TOOL_NOT_FOUND asynchronously for unknown tools")
        void shouldReturnNotFoundAsync() throws Exception {
            CompletableFuture<ToolsProvider.ToolExecutionResult> future =
                    registrationService.executeToolAsync(
                            "nonexistent_tool",
                            Map.of(),
                            ToolsProvider.ToolExecutionContext.of("tenant-1")
                    );

            ToolsProvider.ToolExecutionResult result = future.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.TOOL_NOT_FOUND);
        }
    }

    // -------------------------------------------------------------------------
    // ToolExecutionContext
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("ToolExecutionContext")
    class ToolExecutionContextTests {

        @Test
        @DisplayName("should create context with tenant ID")
        void shouldCreateWithTenantId() {
            ToolsProvider.ToolExecutionContext ctx = ToolsProvider.ToolExecutionContext.of("tenant-123");

            assertThat(ctx.tenantId()).isEqualTo("tenant-123");
            assertThat(ctx.credentials()).isEmpty();
            assertThat(ctx.variables()).isEmpty();
            assertThat(ctx.approvedServices()).isEmpty();
        }

        @Test
        @DisplayName("should create empty context")
        void shouldCreateEmptyContext() {
            ToolsProvider.ToolExecutionContext ctx = ToolsProvider.ToolExecutionContext.empty();

            assertThat(ctx.tenantId()).isNull();
            assertThat(ctx.credentials()).isEmpty();
        }

        @Test
        @DisplayName("should create context with approved services")
        void shouldCreateWithApprovedServices() {
            ToolsProvider.ToolExecutionContext ctx =
                    ToolsProvider.ToolExecutionContext.withApprovedServices(
                            "tenant-1",
                            java.util.Set.of("gmail", "sheets")
                    );

            assertThat(ctx.isServiceApproved("gmail")).isTrue();
            assertThat(ctx.isServiceApproved("sheets")).isTrue();
            assertThat(ctx.isServiceApproved("unknown")).isFalse();
        }

        @Test
        @DisplayName("should create context with workflow context")
        void shouldCreateWithWorkflowContext() {
            ToolsProvider.ToolExecutionContext ctx =
                    ToolsProvider.ToolExecutionContext.withWorkflowContext(
                            "tenant-1",
                            java.util.Set.of("service-a"),
                            "wf-123",
                            "My Workflow"
                    );

            assertThat(ctx.isViewingWorkflow()).isTrue();
            assertThat(ctx.viewingWorkflowId()).isEqualTo("wf-123");
            assertThat(ctx.viewingWorkflowName()).isEqualTo("My Workflow");
        }

        @Test
        @DisplayName("should detect not viewing workflow when null")
        void shouldDetectNotViewingWorkflow() {
            ToolsProvider.ToolExecutionContext ctx = ToolsProvider.ToolExecutionContext.of("tenant-1");
            assertThat(ctx.isViewingWorkflow()).isFalse();
        }
    }

    // -------------------------------------------------------------------------
    // ToolExecutionResult
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("ToolExecutionResult")
    class ToolExecutionResultTests {

        @Test
        @DisplayName("should create success result")
        void shouldCreateSuccessResult() {
            ToolsProvider.ToolExecutionResult result =
                    ToolsProvider.ToolExecutionResult.success(Map.of("key", "value"));

            assertThat(result.success()).isTrue();
            assertThat(result.error()).isNull();
            assertThat(result.errorCode()).isNull();
        }

        @Test
        @DisplayName("should create failure result with error code")
        void shouldCreateFailureWithCode() {
            ToolsProvider.ToolExecutionResult result =
                    ToolsProvider.ToolExecutionResult.failure(
                            ToolErrorCode.RESOURCE_NOT_FOUND,
                            "Workflow not found"
                    );

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.RESOURCE_NOT_FOUND);
            assertThat(result.error()).isEqualTo("Workflow not found");
        }

        @Test
        @DisplayName("should detect validation errors")
        void shouldDetectValidationErrors() {
            ToolsProvider.ToolExecutionResult result =
                    ToolsProvider.ToolExecutionResult.failure(
                            ToolErrorCode.MISSING_PARAMETER,
                            "Required parameter 'query' missing"
                    );

            assertThat(result.isValidationError()).isTrue();
            assertThat(result.isAuthError()).isFalse();
        }

        @Test
        @DisplayName("should detect auth errors")
        void shouldDetectAuthErrors() {
            ToolsProvider.ToolExecutionResult result =
                    ToolsProvider.ToolExecutionResult.failure(
                            ToolErrorCode.AUTHENTICATION_REQUIRED,
                            "Login required"
                    );

            assertThat(result.isAuthError()).isTrue();
            assertThat(result.isValidationError()).isFalse();
        }

        @Test
        @DisplayName("should convert to map correctly")
        void shouldConvertToMap() {
            ToolsProvider.ToolExecutionResult result =
                    ToolsProvider.ToolExecutionResult.success(Map.of("data", "value"));

            Map<String, Object> map = result.toMap();
            assertThat(map.get("success")).isEqualTo(true);
            assertThat(map).containsKey("data");
        }

        @Test
        @DisplayName("should convert failure to map with error code")
        void shouldConvertFailureToMap() {
            ToolsProvider.ToolExecutionResult result =
                    ToolsProvider.ToolExecutionResult.failure(
                            ToolErrorCode.TOOL_NOT_FOUND,
                            "Not found"
                    );

            Map<String, Object> map = result.toMap();
            assertThat(map.get("success")).isEqualTo(false);
            assertThat(map.get("error")).isEqualTo("Not found");
            assertThat(map.get("errorCode")).isEqualTo("TOOL_001");
        }
    }

    // -------------------------------------------------------------------------
    // Cache statistics
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Cache statistics")
    class CacheStatistics {

        @Test
        @DisplayName("should report cache stats")
        void shouldReportCacheStats() {
            Map<String, Object> stats = registrationService.getCacheStats();

            assertThat(stats.get("cachedTools")).isEqualTo(4);
            assertThat(stats.get("providers")).isEqualTo(2);
            assertThat(stats.get("interceptors")).isEqualTo(0);
        }
    }

    // -------------------------------------------------------------------------
    // ToolErrorCode enum
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("ToolErrorCode")
    class ToolErrorCodeTests {

        @Test
        @DisplayName("should find error code by code string")
        void shouldFindByCodeString() {
            assertThat(ToolErrorCode.fromCode("TOOL_001")).isEqualTo(ToolErrorCode.TOOL_NOT_FOUND);
            assertThat(ToolErrorCode.fromCode("TOOL_050")).isEqualTo(ToolErrorCode.EXECUTION_FAILED);
            assertThat(ToolErrorCode.fromCode("TOOL_010")).isEqualTo(ToolErrorCode.VALIDATION_ERROR);
        }

        @Test
        @DisplayName("should return null for unknown code")
        void shouldReturnNullForUnknownCode() {
            assertThat(ToolErrorCode.fromCode("UNKNOWN")).isNull();
            assertThat(ToolErrorCode.fromCode(null)).isNull();
        }

        @Test
        @DisplayName("should format error message correctly")
        void shouldFormatErrorMessage() {
            String formatted = ToolErrorCode.TOOL_NOT_FOUND.format("Tool 'xyz' not found");
            assertThat(formatted).isEqualTo("TOOL_001: Tool 'xyz' not found");
        }

        @Test
        @DisplayName("should have unique codes for all error types")
        void shouldHaveUniqueCodes() {
            java.util.Set<String> codes = new java.util.HashSet<>();
            for (ToolErrorCode code : ToolErrorCode.values()) {
                assertThat(codes.add(code.getCode()))
                        .as("Duplicate code: " + code.getCode())
                        .isTrue();
            }
        }
    }
}
