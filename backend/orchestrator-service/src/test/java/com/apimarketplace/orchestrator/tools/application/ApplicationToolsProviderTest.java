package com.apimarketplace.orchestrator.tools.application;

import com.apimarketplace.agent.registry.AgentToolDefinition;
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

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ApplicationToolsProvider.
 * Validates the unified facade routing, tool definition structure,
 * and delegation to CrudModule and HelpModule.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ApplicationToolsProvider Tests")
class ApplicationToolsProviderTest {

    @Mock
    private ApplicationCrudModule crudModule;

    @Mock
    private ApplicationExecuteModule executeModule;

    @Mock
    private ApplicationHelpModule helpModule;

    private ApplicationToolsProvider provider;

    private static final String TENANT_ID = "tenant-123";

    @BeforeEach
    void setUp() {
        provider = new ApplicationToolsProvider(crudModule, executeModule, helpModule);
    }

    // ==================== Tool Definition Tests ====================

    @Nested
    @DisplayName("Tool Definitions")
    class ToolDefinitions {

        @Test
        @DisplayName("getTools() should return exactly 1 tool named 'application'")
        void getToolsReturnsOneApplicationTool() {
            List<AgentToolDefinition> tools = provider.getTools();

            assertThat(tools).hasSize(1);
            assertThat(tools.get(0).name()).isEqualTo("application");
        }

        @Test
        @DisplayName("getCategory() should return APPLICATION")
        void getCategoryReturnsApplication() {
            assertThat(provider.getCategory()).isEqualTo(ToolCategory.APPLICATION);
        }

        @Test
        @DisplayName("Tool definition should have 'action' as the only required parameter")
        void toolDefinitionHasActionAsOnlyRequired() {
            AgentToolDefinition tool = provider.getTools().get(0);

            assertThat(tool.requiredParameters()).containsExactly("action");
        }

        @Test
        @DisplayName("Tool definition should have all expected parameters")
        void toolDefinitionHasAllExpectedParameters() {
            AgentToolDefinition tool = provider.getTools().get(0);

            List<String> paramNames = tool.parameters().stream()
                .map(p -> p.name())
                .toList();

            assertThat(paramNames).containsExactlyInAnyOrder(
                "action", "workflow_id", "application_id", "query", "category", "title", "description",
                "data_inputs", "trigger_id", "run_id", "epoch", "node_id",
                "item_index", "iteration", "spawn", "field", "max_bytes", "limit", "offset", "topics"
            );
        }

        @Test
        @DisplayName("Tool definition should have APPLICATION category")
        void toolDefinitionHasApplicationCategory() {
            AgentToolDefinition tool = provider.getTools().get(0);

            assertThat(tool.category()).isEqualTo(ToolCategory.APPLICATION);
        }

        @Test
        @DisplayName("Tool definition should require auth")
        void toolDefinitionRequiresAuth() {
            AgentToolDefinition tool = provider.getTools().get(0);

            assertThat(tool.requiresAuth()).isTrue();
        }

        @Test
        @DisplayName("Tool definition should have input schema")
        void toolDefinitionHasInputSchema() {
            AgentToolDefinition tool = provider.getTools().get(0);

            assertThat(tool.inputSchema()).isNotNull();
            assertThat(tool.inputSchema()).isNotEmpty();
        }

        @Test
        @DisplayName("Action parameter should have enum values with all valid actions")
        void actionParameterHasEnumValues() {
            AgentToolDefinition tool = provider.getTools().get(0);

            var actionParam = tool.parameters().stream()
                .filter(p -> "action".equals(p.name()))
                .findFirst()
                .orElseThrow();

            assertThat(actionParam.enumValues()).containsExactlyInAnyOrder(
                "create", "search", "my", "get", "acquire", "uninstall", "execute",
                "runs", "get_run", "get_node_output",
                "visualize", "help"
            );
        }
    }

    // ==================== Execute Routing Tests ====================

    @Nested
    @DisplayName("Execute Routing")
    class ExecuteRouting {

        @Test
        @DisplayName("Unknown tool name should return failure")
        void unknownToolNameReturnsFailure() {
            ToolExecutionContext context = ToolExecutionContext.of(TENANT_ID);
            Map<String, Object> params = Map.of("action", "list");

            ToolExecutionResult result = provider.execute("unknown_tool", params, context);

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("Unknown tool");
        }

        @Test
        @DisplayName("Null action should return failure with valid actions list")
        void nullActionReturnsFailureWithValidActions() {
            ToolExecutionContext context = ToolExecutionContext.of(TENANT_ID);
            Map<String, Object> params = new HashMap<>();

            ToolExecutionResult result = provider.execute("application", params, context);

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("action is required");
            assertThat(result.error()).contains("search");
            assertThat(result.error()).contains("my");
            assertThat(result.error()).contains("get");
            assertThat(result.error()).contains("acquire");
            assertThat(result.error()).contains("visualize");
            assertThat(result.error()).contains("help");
        }

        @Test
        @DisplayName("Blank action should return failure")
        void blankActionReturnsFailure() {
            ToolExecutionContext context = ToolExecutionContext.of(TENANT_ID);
            Map<String, Object> params = Map.of("action", "   ");

            ToolExecutionResult result = provider.execute("application", params, context);

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("action is required");
        }

        @Test
        @DisplayName("Help action should delegate to helpModule (no tenantId needed)")
        void helpActionDelegatesToHelpModule() {
            ToolExecutionContext context = ToolExecutionContext.empty();
            Map<String, Object> params = Map.of("action", "help");

            when(helpModule.canHandle("help")).thenReturn(true);
            when(helpModule.execute(eq("help"), eq(params), isNull(), eq(context)))
                .thenReturn(Optional.of(ToolExecutionResult.success(Map.of("description", "help text"))));

            ToolExecutionResult result = provider.execute("application", params, context);

            assertThat(result.success()).isTrue();
            verify(helpModule).canHandle("help");
            verify(helpModule).execute(eq("help"), eq(params), isNull(), eq(context));
            // crudModule.canHandle is checked first in the provider, but execute should not be called
            verify(crudModule, never()).execute(anyString(), anyMap(), anyString(), any());
        }

        @Test
        @DisplayName("List action without tenantId should return failure")
        void listActionWithoutTenantIdReturnsFailure() {
            ToolExecutionContext context = ToolExecutionContext.empty();
            Map<String, Object> params = Map.of("action", "list");

            // crudModule.canHandle is never reached because tenantId check comes first
            // but in the code, the tenantId check happens before canHandle
            ToolExecutionResult result = provider.execute("application", params, context);

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("tenantId is required");
        }

        @Test
        @DisplayName("List action with valid context should delegate to crudModule")
        void listActionDelegatesToCrudModule() {
            ToolExecutionContext context = ToolExecutionContext.of(TENANT_ID);
            Map<String, Object> params = Map.of("action", "list");

            when(crudModule.canHandle("list")).thenReturn(true);
            when(crudModule.execute(eq("list"), eq(params), eq(TENANT_ID), eq(context)))
                .thenReturn(Optional.of(ToolExecutionResult.success(Map.of("status", "OK"))));

            ToolExecutionResult result = provider.execute("application", params, context);

            assertThat(result.success()).isTrue();
            verify(crudModule).canHandle("list");
            verify(crudModule).execute(eq("list"), eq(params), eq(TENANT_ID), eq(context));
        }

        @Test
        @DisplayName("Create action should delegate to crudModule")
        void createActionDelegatesToCrudModule() {
            ToolExecutionContext context = ToolExecutionContext.of(TENANT_ID);
            Map<String, Object> params = Map.of("action", "create", "workflow_id", UUID.randomUUID().toString());

            when(crudModule.canHandle("create")).thenReturn(true);
            when(crudModule.execute(eq("create"), eq(params), eq(TENANT_ID), eq(context)))
                .thenReturn(Optional.of(ToolExecutionResult.success(Map.of("status", "OK"))));

            ToolExecutionResult result = provider.execute("application", params, context);

            assertThat(result.success()).isTrue();
            verify(crudModule).canHandle("create");
            verify(crudModule).execute(eq("create"), eq(params), eq(TENANT_ID), eq(context));
        }

        @Test
        @DisplayName("Get action should delegate to crudModule")
        void getActionDelegatesToCrudModule() {
            ToolExecutionContext context = ToolExecutionContext.of(TENANT_ID);
            Map<String, Object> params = Map.of("action", "get", "application_id", UUID.randomUUID().toString());

            when(crudModule.canHandle("get")).thenReturn(true);
            when(crudModule.execute(eq("get"), eq(params), eq(TENANT_ID), eq(context)))
                .thenReturn(Optional.of(ToolExecutionResult.success(Map.of("title", "My App"))));

            ToolExecutionResult result = provider.execute("application", params, context);

            assertThat(result.success()).isTrue();
            verify(crudModule).execute(eq("get"), eq(params), eq(TENANT_ID), eq(context));
        }

        @Test
        @DisplayName("Execute action should delegate to executeModule")
        void executeActionDelegatesToExecuteModule() {
            ToolExecutionContext context = ToolExecutionContext.of(TENANT_ID);
            Map<String, Object> params = Map.of("action", "execute", "application_id", UUID.randomUUID().toString());

            when(crudModule.canHandle("execute")).thenReturn(false);
            when(executeModule.canHandle("execute")).thenReturn(true);
            when(executeModule.execute(eq("execute"), eq(params), eq(TENANT_ID), eq(context)))
                .thenReturn(Optional.of(ToolExecutionResult.success(Map.of("status", "COMPLETED"))));

            ToolExecutionResult result = provider.execute("application", params, context);

            assertThat(result.success()).isTrue();
            verify(executeModule).canHandle("execute");
            verify(executeModule).execute(eq("execute"), eq(params), eq(TENANT_ID), eq(context));
            verify(helpModule, never()).execute(anyString(), anyMap(), anyString(), any());
        }

        @Test
        @DisplayName("Invalid action not handled by any module should return failure with valid actions list")
        void invalidActionReturnsFailure() {
            ToolExecutionContext context = ToolExecutionContext.of(TENANT_ID);
            Map<String, Object> params = Map.of("action", "delete");

            when(crudModule.canHandle("delete")).thenReturn(false);
            when(executeModule.canHandle("delete")).thenReturn(false);
            when(helpModule.canHandle("delete")).thenReturn(false);

            ToolExecutionResult result = provider.execute("application", params, context);

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("Invalid action");
            assertThat(result.error()).contains("delete");
        }

        @Test
        @DisplayName("Exception during execution should return failure")
        void exceptionDuringExecutionReturnsFailure() {
            ToolExecutionContext context = ToolExecutionContext.of(TENANT_ID);
            Map<String, Object> params = Map.of("action", "list");

            when(crudModule.canHandle("list")).thenReturn(true);
            when(crudModule.execute(eq("list"), eq(params), eq(TENANT_ID), eq(context)))
                .thenThrow(new RuntimeException("Database connection failed"));

            ToolExecutionResult result = provider.execute("application", params, context);

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("Database connection failed");
        }

        @Test
        @DisplayName("Module returning empty Optional should return default failure")
        void moduleReturningEmptyOptionalReturnsDefaultFailure() {
            ToolExecutionContext context = ToolExecutionContext.of(TENANT_ID);
            Map<String, Object> params = Map.of("action", "list");

            when(crudModule.canHandle("list")).thenReturn(true);
            when(crudModule.execute(eq("list"), eq(params), eq(TENANT_ID), eq(context)))
                .thenReturn(Optional.empty());

            ToolExecutionResult result = provider.execute("application", params, context);

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("Module failed for action: list");
        }
    }
}
