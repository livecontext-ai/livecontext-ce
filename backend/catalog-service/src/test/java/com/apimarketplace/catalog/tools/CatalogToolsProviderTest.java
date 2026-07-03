package com.apimarketplace.catalog.tools;

import com.apimarketplace.agent.registry.AgentToolDefinition;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CatalogToolsProvider} - the unified {@code catalog} tool facade
 * (search / execute / response_schema / help / custom-API registration). No provider-level
 * test existed before this campaign. The provider validates the tool name + action against
 * a fixed VALID_ACTIONS set, then delegates to the first of five modules
 * (search / execute / schema / help / register) whose canHandle(action) is true. The
 * modules are mocked; the routing + framing logic that lives in this class is exercised here.
 *
 * <p>Notable contrasts with the other tool facades: there is NO tenant gate (a null context
 * is tolerated), the tool is {@code requiresAuth(false)}, action validation happens up front
 * (INVALID_PARAMETER_VALUE), and {@code call} is a valid action alias that is intentionally
 * NOT advertised in the action enum.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CatalogToolsProvider (catalog tool facade)")
class CatalogToolsProviderTest {

    private static final String TENANT = "tenant-1";

    @Mock private CatalogSearchModule searchModule;
    @Mock private CatalogExecuteModule executeModule;
    @Mock private CatalogSchemaModule schemaModule;
    @Mock private CatalogRegisterModule registerModule;
    @Mock private CatalogHelpModule helpModule;

    private CatalogToolsProvider provider;

    @BeforeEach
    void setUp() {
        provider = new CatalogToolsProvider(searchModule, executeModule, schemaModule, registerModule, helpModule);
    }

    private ToolExecutionResult exec(Map<String, Object> params) {
        return provider.execute("catalog", params, ToolExecutionContext.of(TENANT));
    }

    private static Map<String, Object> params(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static ToolExecutionResult ok() {
        return ToolExecutionResult.success(Map.of("status", "OK"));
    }

    // ════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("tool definition")
    class ToolDefinitions {

        @Test
        @DisplayName("getCategory() is CATALOG")
        void category() {
            assertThat(provider.getCategory()).isEqualTo(ToolCategory.CATALOG);
        }

        @Test
        @DisplayName("getTools() returns exactly one 'catalog' tool that does NOT require auth")
        void singleTool() {
            List<AgentToolDefinition> tools = provider.getTools();
            assertThat(tools).hasSize(1);
            assertThat(tools.get(0).name()).isEqualTo("catalog");
            // Catalog discovery is open (unlike table/application/workflow which require auth).
            assertThat(tools.get(0).requiresAuth()).isFalse();
            assertThat(tools.get(0).requiredParameters()).containsExactly("action");
            assertThat(tools.get(0).inputSchema()).isNotNull().isNotEmpty();
        }

        @Test
        @DisplayName("the action enum advertises the public actions but NOT the 'call' alias")
        void actionEnumExcludesCallAlias() {
            var actionParam = provider.getTools().get(0).parameters().stream()
                    .filter(p -> "action".equals(p.name())).findFirst().orElseThrow();
            assertThat(actionParam.enumValues()).containsExactlyInAnyOrder(
                    "search", "execute", "response_schema", "help",
                    "register_api", "update_api", "delete_api", "list_custom_apis");
            assertThat(actionParam.enumValues()).doesNotContain("call");
        }

        @Test
        @DisplayName("the tool exposes all expected parameters")
        void allParams() {
            List<String> names = provider.getTools().get(0).parameters().stream().map(p -> p.name()).toList();
            assertThat(names).containsExactlyInAnyOrder(
                    "action", "query", "api", "apis", "limit", "tool_id", "params",
                    "expand", "max_items", "api_definition", "api_id", "topics");
        }

        @Test
        @DisplayName("description keeps the load-bearing contracts after the 2026-07 compaction (response_schema-before-execute, credential block, internal-resources routing)")
        void descriptionKeepsLoadBearingContracts() {
            // Regression guard for the description rewrite (2 USAGE FLOW walkthroughs removed,
            // response_schema paragraph compressed): the rules an agent cannot recover from any
            // other part of the schema must survive any future trim.
            String description = provider.getTools().get(0).description();
            assertThat(description)
                    // the one ordering rule that prevents 400s on unknown tools
                    .contains("search -> response_schema -> execute")
                    .contains("Never skip response_schema")
                    // the input-contract vocabulary the agent reads back
                    .contains("allowedValues")
                    // the credential triage block - must point at the UNIFIED tool's
                    // require action, not the legacy request_credential name
                    .contains("credential(action='require')")
                    .contains("api_key | oauth2 | bearer_token | basic_auth | none")
                    // routing away from internal resources
                    .contains("NOT FOR INTERNAL RESOURCES")
                    // help topics list must stay complete (file_storage was missing pre-fix)
                    .contains("'file_storage'")
                    // the legacy tool name must not resurface in agent-facing text
                    .doesNotContain("request_credential");
        }
    }

    // ════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("execute framing")
    class Framing {

        @Test
        @DisplayName("an unknown tool name → TOOL_NOT_FOUND and no module is touched")
        void unknownTool() {
            ToolExecutionResult r = provider.execute("workflow", params("action", "search"),
                    ToolExecutionContext.of(TENANT));
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.TOOL_NOT_FOUND);
            verifyNoInteractions(searchModule, executeModule, schemaModule, helpModule, registerModule);
        }

        @Test
        @DisplayName("a missing action → MISSING_PARAMETER before any module is consulted")
        void missingAction() {
            ToolExecutionResult r = exec(params());
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.MISSING_PARAMETER);
            assertThat(r.error()).contains("action");
            verifyNoInteractions(searchModule, executeModule, schemaModule, helpModule, registerModule);
        }

        @Test
        @DisplayName("a blank action → MISSING_PARAMETER")
        void blankAction() {
            assertThat(exec(params("action", "   ")).errorCode()).isEqualTo(ToolErrorCode.MISSING_PARAMETER);
        }

        @Test
        @DisplayName("an action outside VALID_ACTIONS → INVALID_PARAMETER_VALUE (validated up front)")
        void unknownActionRejectedBeforeDispatch() {
            ToolExecutionResult r = exec(params("action", "drop_everything"));
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.INVALID_PARAMETER_VALUE);
            assertThat(r.error()).contains("Unknown action").contains("drop_everything");
            // No module is consulted at all for an action that fails the enum gate.
            verifyNoInteractions(searchModule, executeModule, schemaModule, helpModule, registerModule);
        }

        @Test
        @DisplayName("a valid action no module claims → TOOL_NOT_FOUND (config-mismatch guard)")
        void validActionNoModuleHandles() {
            when(searchModule.canHandle("search")).thenReturn(false);
            when(executeModule.canHandle("search")).thenReturn(false);
            when(schemaModule.canHandle("search")).thenReturn(false);
            when(helpModule.canHandle("search")).thenReturn(false);
            when(registerModule.canHandle("search")).thenReturn(false);
            ToolExecutionResult r = exec(params("action", "search"));
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.TOOL_NOT_FOUND);
            assertThat(r.error()).contains("Unknown action");
        }

        @Test
        @DisplayName("a module exception is caught → EXECUTION_FAILED (generic message, no leak)")
        void moduleExceptionCaught() {
            when(searchModule.canHandle("search")).thenReturn(true);
            when(searchModule.execute(eq("search"), anyMap(), eq(TENANT), any()))
                    .thenThrow(new RuntimeException("secret stacktrace"));
            ToolExecutionResult r = exec(params("action", "search"));
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.EXECUTION_FAILED);
            assertThat(r.error()).isEqualTo("Catalog action failed");
            assertThat(r.error()).doesNotContain("secret stacktrace");
        }

        @Test
        @DisplayName("a null context is tolerated (no tenant gate) and routes with a null tenant")
        void nullContextTolerated() {
            when(searchModule.canHandle("help")).thenReturn(false);
            when(executeModule.canHandle("help")).thenReturn(false);
            when(schemaModule.canHandle("help")).thenReturn(false);
            when(helpModule.canHandle("help")).thenReturn(true);
            when(helpModule.execute(eq("help"), anyMap(), isNull(), isNull())).thenReturn(Optional.of(ok()));

            ToolExecutionResult r = provider.execute("catalog", params("action", "help"), null);
            assertThat(r.success()).isTrue();
            verify(helpModule).execute(eq("help"), anyMap(), isNull(), isNull());
        }
    }

    // ════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("module routing")
    class Routing {

        @Test
        @DisplayName("search → search module")
        void searchToSearch() {
            when(searchModule.canHandle("search")).thenReturn(true);
            when(searchModule.execute(eq("search"), anyMap(), eq(TENANT), any())).thenReturn(Optional.of(ok()));
            assertThat(exec(params("action", "search", "query", "gmail")).success()).isTrue();
            verify(searchModule).execute(eq("search"), anyMap(), eq(TENANT), any());
            verify(executeModule, never()).execute(anyString(), anyMap(), any(), any());
        }

        @Test
        @DisplayName("execute → execute module")
        void executeToExecute() {
            when(searchModule.canHandle("execute")).thenReturn(false);
            when(executeModule.canHandle("execute")).thenReturn(true);
            when(executeModule.execute(eq("execute"), anyMap(), eq(TENANT), any())).thenReturn(Optional.of(ok()));
            assertThat(exec(params("action", "execute", "tool_id", "t")).success()).isTrue();
            verify(executeModule).execute(eq("execute"), anyMap(), eq(TENANT), any());
        }

        @Test
        @DisplayName("the 'call' alias also routes to the execute module")
        void callAliasToExecute() {
            when(searchModule.canHandle("call")).thenReturn(false);
            when(executeModule.canHandle("call")).thenReturn(true);
            when(executeModule.execute(eq("call"), anyMap(), eq(TENANT), any())).thenReturn(Optional.of(ok()));
            assertThat(exec(params("action", "call", "tool_id", "t")).success()).isTrue();
            verify(executeModule).execute(eq("call"), anyMap(), eq(TENANT), any());
        }

        @Test
        @DisplayName("response_schema → schema module")
        void schemaToSchema() {
            when(searchModule.canHandle("response_schema")).thenReturn(false);
            when(executeModule.canHandle("response_schema")).thenReturn(false);
            when(schemaModule.canHandle("response_schema")).thenReturn(true);
            when(schemaModule.execute(eq("response_schema"), anyMap(), eq(TENANT), any())).thenReturn(Optional.of(ok()));
            assertThat(exec(params("action", "response_schema", "tool_id", "t")).success()).isTrue();
            verify(schemaModule).execute(eq("response_schema"), anyMap(), eq(TENANT), any());
        }

        @Test
        @DisplayName("help → help module (checked before register)")
        void helpToHelp() {
            when(searchModule.canHandle("help")).thenReturn(false);
            when(executeModule.canHandle("help")).thenReturn(false);
            when(schemaModule.canHandle("help")).thenReturn(false);
            when(helpModule.canHandle("help")).thenReturn(true);
            when(helpModule.execute(eq("help"), anyMap(), eq(TENANT), any())).thenReturn(Optional.of(ok()));
            assertThat(exec(params("action", "help")).success()).isTrue();
            verify(helpModule).execute(eq("help"), anyMap(), eq(TENANT), any());
            verify(registerModule, never()).execute(anyString(), anyMap(), any(), any());
        }

        @Test
        @DisplayName("register_api → register module (checked last)")
        void registerToRegister() {
            when(searchModule.canHandle("register_api")).thenReturn(false);
            when(executeModule.canHandle("register_api")).thenReturn(false);
            when(schemaModule.canHandle("register_api")).thenReturn(false);
            when(helpModule.canHandle("register_api")).thenReturn(false);
            when(registerModule.canHandle("register_api")).thenReturn(true);
            when(registerModule.execute(eq("register_api"), anyMap(), eq(TENANT), any())).thenReturn(Optional.of(ok()));
            assertThat(exec(params("action", "register_api", "api_definition", Map.of())).success()).isTrue();
            verify(registerModule).execute(eq("register_api"), anyMap(), eq(TENANT), any());
        }
    }

    // ════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("empty-Optional fallbacks (each module's distinct message)")
    class EmptyFallbacks {

        @Test
        @DisplayName("search module empty → 'Search module failed'")
        void searchEmpty() {
            when(searchModule.canHandle("search")).thenReturn(true);
            when(searchModule.execute(eq("search"), anyMap(), eq(TENANT), any())).thenReturn(Optional.empty());
            ToolExecutionResult r = exec(params("action", "search"));
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.EXECUTION_FAILED);
            assertThat(r.error()).isEqualTo("Search module failed");
        }

        @Test
        @DisplayName("execute module empty → 'Execute module failed'")
        void executeEmpty() {
            when(searchModule.canHandle("execute")).thenReturn(false);
            when(executeModule.canHandle("execute")).thenReturn(true);
            when(executeModule.execute(eq("execute"), anyMap(), eq(TENANT), any())).thenReturn(Optional.empty());
            ToolExecutionResult r = exec(params("action", "execute"));
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.EXECUTION_FAILED);
            assertThat(r.error()).isEqualTo("Execute module failed");
        }

        @Test
        @DisplayName("schema module empty → 'Schema module failed'")
        void schemaEmpty() {
            when(searchModule.canHandle("response_schema")).thenReturn(false);
            when(executeModule.canHandle("response_schema")).thenReturn(false);
            when(schemaModule.canHandle("response_schema")).thenReturn(true);
            when(schemaModule.execute(eq("response_schema"), anyMap(), eq(TENANT), any())).thenReturn(Optional.empty());
            ToolExecutionResult r = exec(params("action", "response_schema"));
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.EXECUTION_FAILED);
            assertThat(r.error()).isEqualTo("Schema module failed");
        }

        @Test
        @DisplayName("help module empty → 'Help module failed'")
        void helpEmpty() {
            when(searchModule.canHandle("help")).thenReturn(false);
            when(executeModule.canHandle("help")).thenReturn(false);
            when(schemaModule.canHandle("help")).thenReturn(false);
            when(helpModule.canHandle("help")).thenReturn(true);
            when(helpModule.execute(eq("help"), anyMap(), eq(TENANT), any())).thenReturn(Optional.empty());
            ToolExecutionResult r = exec(params("action", "help"));
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.EXECUTION_FAILED);
            assertThat(r.error()).isEqualTo("Help module failed");
        }

        @Test
        @DisplayName("register module empty → 'Register module failed'")
        void registerEmpty() {
            when(searchModule.canHandle("delete_api")).thenReturn(false);
            when(executeModule.canHandle("delete_api")).thenReturn(false);
            when(schemaModule.canHandle("delete_api")).thenReturn(false);
            when(helpModule.canHandle("delete_api")).thenReturn(false);
            when(registerModule.canHandle("delete_api")).thenReturn(true);
            when(registerModule.execute(eq("delete_api"), anyMap(), eq(TENANT), any())).thenReturn(Optional.empty());
            ToolExecutionResult r = exec(params("action", "delete_api", "api_id", "x"));
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.EXECUTION_FAILED);
            assertThat(r.error()).isEqualTo("Register module failed");
        }
    }
}
