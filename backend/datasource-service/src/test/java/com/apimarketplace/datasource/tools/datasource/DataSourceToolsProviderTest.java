package com.apimarketplace.datasource.tools.datasource;

import com.apimarketplace.agent.registry.AgentToolDefinition;
import com.apimarketplace.agent.registry.ToolCategory;
import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.datasource.services.VectorFeatureGate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DataSourceToolsProvider} - the unified {@code table} tool facade
 * (datasource-service native). This provider had no dedicated test before this campaign.
 * It is a thin router: validates the tool name + action, maps {@code table_id -> datasource_id},
 * enforces the tenant gate (except for help), then delegates to the first of four modules
 * (table / row / schema / publish) that can handle the action. The modules and the
 * {@link VectorFeatureGate} are mocked; the routing + framing logic that lives in this class
 * is what's exercised here.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DataSourceToolsProvider (table tool facade)")
class DataSourceToolsProviderTest {

    private static final String TENANT = "tenant-1";

    @Mock private DataSourceTableModule tableModule;
    @Mock private DataSourceRowModule rowModule;
    @Mock private DataSourceSchemaModule schemaModule;
    @Mock private TablePublishModule publishModule;
    @Mock private VectorFeatureGate vectorFeatureGate;

    private DataSourceToolsProvider provider;

    @BeforeEach
    void setUp() {
        provider = new DataSourceToolsProvider(tableModule, rowModule, schemaModule, publishModule, vectorFeatureGate);
    }

    private ToolExecutionResult exec(Map<String, Object> params) {
        return provider.execute("table", params, ToolExecutionContext.of(TENANT));
    }

    private static Map<String, Object> params(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static ToolExecutionResult ok() {
        return ToolExecutionResult.success(Map.of("status", "OK"));
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Map<String, Object>> mapCaptor() {
        return ArgumentCaptor.forClass(Map.class);
    }

    // ════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("tool definition")
    class ToolDefinitions {

        @Test
        @DisplayName("getCategory() is DATASOURCE")
        void category() {
            assertThat(provider.getCategory()).isEqualTo(ToolCategory.DATASOURCE);
        }

        @Test
        @DisplayName("getTools() returns exactly one tool named 'table' requiring auth")
        void singleTool() {
            when(vectorFeatureGate.isVectorAllowed()).thenReturn(false);
            List<AgentToolDefinition> tools = provider.getTools();
            assertThat(tools).hasSize(1);
            assertThat(tools.get(0).name()).isEqualTo("table");
            assertThat(tools.get(0).requiresAuth()).isTrue();
            assertThat(tools.get(0).requiredParameters()).containsExactly("action");
            assertThat(tools.get(0).inputSchema()).isNotNull().isNotEmpty();
        }

        @Test
        @DisplayName("the action parameter advertises exactly the valid actions as an enum")
        void actionEnum() {
            when(vectorFeatureGate.isVectorAllowed()).thenReturn(false);
            var actionParam = provider.getTools().get(0).parameters().stream()
                    .filter(p -> "action".equals(p.name())).findFirst().orElseThrow();
            assertThat(actionParam.enumValues()).containsExactlyInAnyOrder(
                    "create", "get", "list", "update", "delete",
                    "query_rows", "insert_rows", "update_rows", "delete_rows",
                    "add_columns", "publish", "unpublish", "help");
        }

        @Test
        @DisplayName("the tool exposes all expected parameters")
        void allParams() {
            when(vectorFeatureGate.isVectorAllowed()).thenReturn(false);
            List<String> names = provider.getTools().get(0).parameters().stream().map(p -> p.name()).toList();
            assertThat(names).containsExactlyInAnyOrder(
                    "action", "table_id", "name", "description", "data", "rows", "columns",
                    "where", "set", "similarity", "limit", "offset", "query",
                    "title", "interface_id", "visibility", "credits_per_use");
        }

        @Test
        @DisplayName("where param advertises the full operator contract: enum, textual comparison warning, bare column name")
        void whereParamAdvertisesOperatorContract() {
            // Regression guard: 'where' used to say only "Format: {column, operator, value}" - the
            // agent could not know the operator vocabulary, that ordering operators compare as TEXT
            // (lexicographic), or that 'data.' prefixes are unnecessary, without a help round-trip.
            when(vectorFeatureGate.isVectorAllowed()).thenReturn(false);
            String whereDesc = provider.getTools().get(0).parameters().stream()
                    .filter(p -> "where".equals(p.name()))
                    .findFirst().orElseThrow().description();
            assertThat(whereDesc)
                    .contains("'IS NOT NULL'")
                    .contains("'IN'")
                    .contains("'LIKE'")
                    .contains("lexicographic")
                    .contains("no 'data.' prefix");
        }

        @Test
        @DisplayName("action param documents each action group and the delete-all idiom (no bare 'See help')")
        void actionParamDocumentsGroups() {
            // Regression guard: the action description was a 40-char "See help for details." stub -
            // 13 actions with zero guidance. It must name each group's key params and the
            // delete-ALL-rows idiom (delete_rows requires a where; there is no truncate action).
            when(vectorFeatureGate.isVectorAllowed()).thenReturn(false);
            String actionDesc = provider.getTools().get(0).parameters().stream()
                    .filter(p -> "action".equals(p.name()))
                    .findFirst().orElseThrow().description();
            assertThat(actionDesc)
                    .contains("query_rows")
                    .contains("update_rows (where + set)")
                    .contains("operator:'IS NOT NULL'");
        }

        @Test
        @DisplayName("when vector is allowed, columns advertises the vector type and similarity is a real search")
        void vectorAllowedDescriptions() {
            when(vectorFeatureGate.isVectorAllowed()).thenReturn(true);
            var tool = provider.getTools().get(0);
            String columnsDesc = tool.parameters().stream().filter(p -> "columns".equals(p.name()))
                    .findFirst().orElseThrow().description();
            String similarityDesc = tool.parameters().stream().filter(p -> "similarity".equals(p.name()))
                    .findFirst().orElseThrow().description();
            assertThat(columnsDesc).contains(", vector");
            assertThat(similarityDesc).contains("queryVector");
        }

        @Test
        @DisplayName("when vector is NOT allowed, columns hides the type and similarity says unavailable")
        void vectorDisabledDescriptions() {
            when(vectorFeatureGate.isVectorAllowed()).thenReturn(false);
            var tool = provider.getTools().get(0);
            String columnsDesc = tool.parameters().stream().filter(p -> "columns".equals(p.name()))
                    .findFirst().orElseThrow().description();
            String similarityDesc = tool.parameters().stream().filter(p -> "similarity".equals(p.name()))
                    .findFirst().orElseThrow().description();
            assertThat(columnsDesc).doesNotContain(", vector");
            assertThat(similarityDesc).contains("Not available on this deployment");
        }
    }

    // ════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("execute framing")
    class Framing {

        @Test
        @DisplayName("an unknown tool name → TOOL_NOT_FOUND")
        void unknownTool() {
            ToolExecutionResult r = provider.execute("workflow", params("action", "list"),
                    ToolExecutionContext.of(TENANT));
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.TOOL_NOT_FOUND);
        }

        @Test
        @DisplayName("a missing action → MISSING_PARAMETER listing the valid actions")
        void missingAction() {
            ToolExecutionResult r = exec(params());
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.MISSING_PARAMETER);
            assertThat(r.error()).contains("action is required").contains("query_rows");
        }

        @Test
        @DisplayName("a blank action → MISSING_PARAMETER")
        void blankAction() {
            assertThat(exec(params("action", "  ")).errorCode()).isEqualTo(ToolErrorCode.MISSING_PARAMETER);
        }

        @Test
        @DisplayName("a non-help action with no tenant → TENANT_NOT_FOUND")
        void missingTenant() {
            ToolExecutionResult r = provider.execute("table", params("action", "list"),
                    ToolExecutionContext.empty());
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.TENANT_NOT_FOUND);
        }

        @Test
        @DisplayName("help is exempt from the tenant gate and delegates with a null tenant")
        void helpExemptFromTenantGate() {
            when(tableModule.canHandle("help")).thenReturn(true);
            when(tableModule.execute(eq("help"), anyMap(), isNull(), any())).thenReturn(Optional.of(ok()));

            ToolExecutionResult r = provider.execute("table", params("action", "help"),
                    ToolExecutionContext.empty());
            assertThat(r.success()).isTrue();
            verify(tableModule).execute(eq("help"), anyMap(), isNull(), any());
        }

        @Test
        @DisplayName("an action no module handles → VALIDATION_ERROR listing the valid actions")
        void invalidAction() {
            when(tableModule.canHandle("frob")).thenReturn(false);
            when(rowModule.canHandle("frob")).thenReturn(false);
            when(schemaModule.canHandle("frob")).thenReturn(false);
            when(publishModule.canHandle("frob")).thenReturn(false);
            ToolExecutionResult r = exec(params("action", "frob"));
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.VALIDATION_ERROR);
            assertThat(r.error()).contains("Invalid action").contains("frob");
        }

        @Test
        @DisplayName("an exception thrown by a module is caught → EXECUTION_FAILED")
        void moduleExceptionCaught() {
            when(tableModule.canHandle("list")).thenReturn(true);
            when(tableModule.execute(eq("list"), anyMap(), eq(TENANT), any()))
                    .thenThrow(new RuntimeException("db down"));
            ToolExecutionResult r = exec(params("action", "list"));
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.EXECUTION_FAILED);
            assertThat(r.error()).contains("db down");
        }
    }

    // ════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("table_id → datasource_id mapping")
    class IdMapping {

        @Test
        @DisplayName("table_id is copied to datasource_id when datasource_id is absent")
        void tableIdMappedToDatasourceId() {
            when(tableModule.canHandle("get")).thenReturn(true);
            when(tableModule.execute(eq("get"), anyMap(), eq(TENANT), any())).thenReturn(Optional.of(ok()));

            exec(params("action", "get", "table_id", 7));

            ArgumentCaptor<Map<String, Object>> captor = mapCaptor();
            verify(tableModule).execute(eq("get"), captor.capture(), eq(TENANT), any());
            assertThat(captor.getValue()).containsEntry("datasource_id", 7).containsEntry("table_id", 7);
        }

        @Test
        @DisplayName("an explicit datasource_id is NOT overwritten by table_id")
        void explicitDatasourceIdWins() {
            when(tableModule.canHandle("get")).thenReturn(true);
            when(tableModule.execute(eq("get"), anyMap(), eq(TENANT), any())).thenReturn(Optional.of(ok()));

            exec(params("action", "get", "table_id", 7, "datasource_id", 9));

            ArgumentCaptor<Map<String, Object>> captor = mapCaptor();
            verify(tableModule).execute(eq("get"), captor.capture(), eq(TENANT), any());
            assertThat(captor.getValue()).containsEntry("datasource_id", 9);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("module routing")
    class Routing {

        @Test
        @DisplayName("create routes to the table module")
        void createToTable() {
            when(tableModule.canHandle("create")).thenReturn(true);
            when(tableModule.execute(eq("create"), anyMap(), eq(TENANT), any())).thenReturn(Optional.of(ok()));
            assertThat(exec(params("action", "create", "name", "T")).success()).isTrue();
            verify(tableModule).execute(eq("create"), anyMap(), eq(TENANT), any());
            verify(rowModule, never()).execute(anyString(), anyMap(), any(), any());
        }

        @Test
        @DisplayName("query_rows falls through the table module to the row module")
        void queryRowsToRow() {
            when(tableModule.canHandle("query_rows")).thenReturn(false);
            when(rowModule.canHandle("query_rows")).thenReturn(true);
            when(rowModule.execute(eq("query_rows"), anyMap(), eq(TENANT), any())).thenReturn(Optional.of(ok()));
            assertThat(exec(params("action", "query_rows")).success()).isTrue();
            verify(rowModule).execute(eq("query_rows"), anyMap(), eq(TENANT), any());
        }

        @Test
        @DisplayName("add_columns falls through to the schema module")
        void addColumnsToSchema() {
            when(tableModule.canHandle("add_columns")).thenReturn(false);
            when(rowModule.canHandle("add_columns")).thenReturn(false);
            when(schemaModule.canHandle("add_columns")).thenReturn(true);
            when(schemaModule.execute(eq("add_columns"), anyMap(), eq(TENANT), any())).thenReturn(Optional.of(ok()));
            assertThat(exec(params("action", "add_columns")).success()).isTrue();
            verify(schemaModule).execute(eq("add_columns"), anyMap(), eq(TENANT), any());
        }

        @Test
        @DisplayName("publish falls through to the publish module")
        void publishToPublish() {
            when(tableModule.canHandle("publish")).thenReturn(false);
            when(rowModule.canHandle("publish")).thenReturn(false);
            when(schemaModule.canHandle("publish")).thenReturn(false);
            when(publishModule.canHandle("publish")).thenReturn(true);
            when(publishModule.execute(eq("publish"), anyMap(), eq(TENANT), any())).thenReturn(Optional.of(ok()));
            assertThat(exec(params("action", "publish", "title", "X")).success()).isTrue();
            verify(publishModule).execute(eq("publish"), anyMap(), eq(TENANT), any());
        }
    }

    // ════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("empty-Optional fallbacks (each module's distinct message)")
    class EmptyFallbacks {

        @Test
        @DisplayName("table module empty → 'Table module failed'")
        void tableEmpty() {
            when(tableModule.canHandle("list")).thenReturn(true);
            when(tableModule.execute(eq("list"), anyMap(), eq(TENANT), any())).thenReturn(Optional.empty());
            ToolExecutionResult r = exec(params("action", "list"));
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.EXECUTION_FAILED);
            assertThat(r.error()).isEqualTo("Table module failed");
        }

        @Test
        @DisplayName("row module empty → 'Row module failed'")
        void rowEmpty() {
            when(tableModule.canHandle("insert_rows")).thenReturn(false);
            when(rowModule.canHandle("insert_rows")).thenReturn(true);
            when(rowModule.execute(eq("insert_rows"), anyMap(), eq(TENANT), any())).thenReturn(Optional.empty());
            ToolExecutionResult r = exec(params("action", "insert_rows"));
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.EXECUTION_FAILED);
            assertThat(r.error()).isEqualTo("Row module failed");
        }

        @Test
        @DisplayName("schema module empty → 'Schema module failed'")
        void schemaEmpty() {
            when(tableModule.canHandle("add_columns")).thenReturn(false);
            when(rowModule.canHandle("add_columns")).thenReturn(false);
            when(schemaModule.canHandle("add_columns")).thenReturn(true);
            when(schemaModule.execute(eq("add_columns"), anyMap(), eq(TENANT), any())).thenReturn(Optional.empty());
            ToolExecutionResult r = exec(params("action", "add_columns"));
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.EXECUTION_FAILED);
            assertThat(r.error()).isEqualTo("Schema module failed");
        }

        @Test
        @DisplayName("publish module empty → 'Publish module failed'")
        void publishEmpty() {
            when(tableModule.canHandle("unpublish")).thenReturn(false);
            when(rowModule.canHandle("unpublish")).thenReturn(false);
            when(schemaModule.canHandle("unpublish")).thenReturn(false);
            when(publishModule.canHandle("unpublish")).thenReturn(true);
            when(publishModule.execute(eq("unpublish"), anyMap(), eq(TENANT), any())).thenReturn(Optional.empty());
            ToolExecutionResult r = exec(params("action", "unpublish"));
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.EXECUTION_FAILED);
            assertThat(r.error()).isEqualTo("Publish module failed");
        }
    }
}
