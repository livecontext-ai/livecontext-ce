package com.apimarketplace.datasource.tools.datasource;

import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.datasource.crud.domain.CrudOperation;
import com.apimarketplace.datasource.crud.domain.CrudResult;
import com.apimarketplace.datasource.crud.dto.CreateColumnRequest;
import com.apimarketplace.datasource.crud.dto.CrudRequest;
import com.apimarketplace.datasource.crud.service.CrudExecutorService;
import com.apimarketplace.datasource.domain.ColumnStructure;
import com.apimarketplace.datasource.domain.ColumnType;
import com.apimarketplace.datasource.domain.DataSourceModels.ColumnMappingSpec;
import com.apimarketplace.datasource.domain.DataSourceModels.DataSource;
import com.apimarketplace.datasource.domain.DataSourceModels.DataSourceStatus;
import com.apimarketplace.datasource.domain.DataSourceModels.DataSourceType;
import com.apimarketplace.datasource.services.DataSourceService;
import com.apimarketplace.datasource.utils.DataSourceDefaults;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("DataSourceSchemaModule")
class DataSourceSchemaModuleTest {

    @Mock private CrudExecutorService crudExecutorService;
    @Mock private DataSourceService dataSourceService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private DataSourceSchemaModule module;

    private static final String TENANT = "test-tenant";
    private static final String ORG = "org-xyz";

    /** Build a tool context carrying the caller's org id (orgId is the 7th record field). */
    private static ToolExecutionContext ctxWithOrg(String orgId) {
        return new ToolExecutionContext(TENANT, Map.of(), Map.of(), Set.of(), null, null, orgId, null);
    }

    @BeforeEach
    void setUp() {
        module = new DataSourceSchemaModule(crudExecutorService, dataSourceService, objectMapper, ceVectorGate());
    }

    private DataSource fakeDs(Long id, String name) {
        return new DataSource(id, TENANT, name, "desc", DataSourceType.INLINE, Map.of(),
                DataSourceStatus.ACTIVE, null, null, TENANT, null, null, null, null, null, null);
    }

    // ==================== access enforcement ====================

    @Nested
    @DisplayName("access enforcement")
    class AccessEnforcementTests {

        private ToolExecutionContext ctx(Map<String, Object> credentials) {
            return new ToolExecutionContext(TENANT, credentials, Map.of(), Set.of(), null, null, ORG, null);
        }

        @Test
        @DisplayName("read-only mode (tableAccessMode='read') blocks add_columns (schema mutation)")
        void readModeBlocksAddColumns() {
            Map<String, Object> params = Map.of("table_id", 5,
                    "columns", List.of(Map.of("name", "c1", "type", "text")));
            Optional<ToolExecutionResult> res = module.execute("add_columns", params, TENANT,
                    ctx(Map.of("tableAccessMode", "read")));

            assertThat(res).isPresent();
            assertThat(res.get().success()).isFalse();
            assertThat(res.get().errorCode()).isEqualTo(ToolErrorCode.PERMISSION_DENIED);
            verifyNoInteractions(crudExecutorService);
        }

        @Test
        @DisplayName("allow-list: add_columns on a table_id NOT in allowedTableIds is denied (schema mutation leak)")
        void outOfAllowListBlocksAddColumns() {
            Map<String, Object> params = Map.of("table_id", 99,
                    "columns", List.of(Map.of("name", "c1", "type", "text")));
            Optional<ToolExecutionResult> res = module.execute("add_columns", params, TENANT,
                    ctx(Map.of("allowedTableIds", List.of("5"))));

            assertThat(res).isPresent();
            assertThat(res.get().success()).isFalse();
            assertThat(res.get().errorCode()).isEqualTo(ToolErrorCode.PERMISSION_DENIED);
            assertThat(res.get().error()).contains("approved table list");
            verifyNoInteractions(crudExecutorService);
        }
    }

    // ==================== canHandle ====================

    @Test
    @DisplayName("canHandle should accept add_columns only")
    void canHandle() {
        assertThat(module.canHandle("add_columns")).isTrue();
        assertThat(module.canHandle("create")).isFalse();
        assertThat(module.canHandle("query_rows")).isFalse();
    }

    // ==================== table_id fallback ====================

    @Nested
    @DisplayName("table_id fallback")
    class TableIdFallbackTests {

        @Test
        @DisplayName("Should accept table_id")
        void shouldAcceptTableId() {
            CrudResult result = CrudResult.success(CrudOperation.CREATE_COLUMN, "OK",
                    CrudResult.ResultData.forCreateColumn(List.of("email")));
            when(crudExecutorService.execute(any(CrudRequest.class), eq(TENANT), any())).thenReturn(result);
            when(dataSourceService.getDataSource(5L)).thenReturn(Optional.of(fakeDs(5L, "T")));

            Map<String, Object> params = new HashMap<>();
            params.put("table_id", 5);
            params.put("columns", List.of(Map.of("name", "email", "type", "email")));

            Optional<ToolExecutionResult> res = module.execute("add_columns", params, TENANT, null);
            assertThat(res).isPresent();
            assertThat(res.get().success()).isTrue();

            ArgumentCaptor<CrudRequest> captor = ArgumentCaptor.forClass(CrudRequest.class);
            verify(crudExecutorService).execute(captor.capture(), eq(TENANT), any());
            assertThat(captor.getValue().getDataSourceId()).isEqualTo(5L);
        }

        @Test
        @DisplayName("Should accept datasource_id")
        void shouldAcceptDatasourceId() {
            CrudResult result = CrudResult.success(CrudOperation.CREATE_COLUMN, "OK",
                    CrudResult.ResultData.forCreateColumn(List.of("x")));
            when(crudExecutorService.execute(any(CrudRequest.class), eq(TENANT), any())).thenReturn(result);
            when(dataSourceService.getDataSource(3L)).thenReturn(Optional.of(fakeDs(3L, "T")));

            Map<String, Object> params = new HashMap<>();
            params.put("datasource_id", 3);
            params.put("columns", List.of(Map.of("name", "x", "type", "text")));

            Optional<ToolExecutionResult> res = module.execute("add_columns", params, TENANT, null);
            assertThat(res).isPresent();
            assertThat(res.get().success()).isTrue();
        }

        @Test
        @DisplayName("Should accept id")
        void shouldAcceptId() {
            CrudResult result = CrudResult.success(CrudOperation.CREATE_COLUMN, "OK",
                    CrudResult.ResultData.forCreateColumn(List.of("x")));
            when(crudExecutorService.execute(any(CrudRequest.class), eq(TENANT), any())).thenReturn(result);
            when(dataSourceService.getDataSource(8L)).thenReturn(Optional.of(fakeDs(8L, "T")));

            Map<String, Object> params = new HashMap<>();
            params.put("id", 8);
            params.put("columns", List.of(Map.of("name", "x", "type", "text")));

            Optional<ToolExecutionResult> res = module.execute("add_columns", params, TENANT, null);
            assertThat(res).isPresent();
            assertThat(res.get().success()).isTrue();
        }

        @Test
        @DisplayName("Should fail without any id")
        void shouldFailWithoutId() {
            Map<String, Object> params = Map.of("columns", List.of(Map.of("name", "x", "type", "text")));
            Optional<ToolExecutionResult> res = module.execute("add_columns", params, TENANT, null);
            assertThat(res).isPresent();
            assertThat(res.get().success()).isFalse();
            assertThat(res.get().errorCode()).isEqualTo(ToolErrorCode.MISSING_PARAMETER);
        }
    }

    // ==================== add_columns ====================

    @Nested
    @DisplayName("add_columns")
    class AddColumnsTests {

        @Test
        @DisplayName("Should add columns with correct types")
        void shouldAddColumnsWithTypes() {
            CrudResult result = CrudResult.success(CrudOperation.CREATE_COLUMN, "OK",
                    CrudResult.ResultData.forCreateColumn(List.of("email", "rating")));
            when(crudExecutorService.execute(any(CrudRequest.class), eq(TENANT), any())).thenReturn(result);
            when(dataSourceService.getDataSource(1L)).thenReturn(Optional.of(fakeDs(1L, "T")));

            Map<String, Object> params = new HashMap<>();
            params.put("table_id", 1);
            params.put("columns", List.of(
                    Map.of("name", "email", "type", "email"),
                    Map.of("name", "rating", "type", "rating")
            ));

            Optional<ToolExecutionResult> res = module.execute("add_columns", params, TENANT, null);
            assertThat(res).isPresent();
            assertThat(res.get().success()).isTrue();

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) res.get().data();
            assertThat(data.get("columnsAdded")).isEqualTo(2);

            ArgumentCaptor<CrudRequest> captor = ArgumentCaptor.forClass(CrudRequest.class);
            verify(crudExecutorService).execute(captor.capture(), eq(TENANT), any());
            CreateColumnRequest req = (CreateColumnRequest) captor.getValue();
            List<CreateColumnRequest.ColumnDefinition> cols = req.getColumns();
            assertThat(cols).hasSize(2);
            assertThat(cols.get(0).name()).isEqualTo("email");
            assertThat(cols.get(0).type()).isEqualTo("email");
            assertThat(cols.get(1).type()).isEqualTo("rating");
        }

        @Test
        @DisplayName("Should handle double-serialized column JSON strings")
        void shouldHandleDoubleSerializedColumns() {
            CrudResult result = CrudResult.success(CrudOperation.CREATE_COLUMN, "OK",
                    CrudResult.ResultData.forCreateColumn(List.of("phone")));
            when(crudExecutorService.execute(any(CrudRequest.class), eq(TENANT), any())).thenReturn(result);
            when(dataSourceService.getDataSource(2L)).thenReturn(Optional.of(fakeDs(2L, "T")));

            Map<String, Object> params = new HashMap<>();
            params.put("table_id", 2);
            params.put("columns", List.of(
                    "{\"name\": \"phone\", \"type\": \"phone\"}"
            ));

            Optional<ToolExecutionResult> res = module.execute("add_columns", params, TENANT, null);
            assertThat(res).isPresent();
            assertThat(res.get().success()).isTrue();

            ArgumentCaptor<CrudRequest> captor = ArgumentCaptor.forClass(CrudRequest.class);
            verify(crudExecutorService).execute(captor.capture(), eq(TENANT), any());
            CreateColumnRequest req = (CreateColumnRequest) captor.getValue();
            assertThat(req.getColumns().get(0).name()).isEqualTo("phone");
            assertThat(req.getColumns().get(0).type()).isEqualTo("phone");
        }

        @Test
        @DisplayName("Should add column with display config")
        void shouldAddColumnWithDisplay() {
            CrudResult result = CrudResult.success(CrudOperation.CREATE_COLUMN, "OK",
                    CrudResult.ResultData.forCreateColumn(List.of("status")));
            when(crudExecutorService.execute(any(CrudRequest.class), eq(TENANT), any())).thenReturn(result);
            when(dataSourceService.getDataSource(1L)).thenReturn(Optional.of(fakeDs(1L, "T")));

            Map<String, Object> display = Map.of("options", List.of(
                    Map.of("label", "Active", "value", "A", "color", "#22c55e")
            ));

            Map<String, Object> params = new HashMap<>();
            params.put("table_id", 1);
            params.put("columns", List.of(Map.of("name", "status", "type", "select", "display", display)));

            Optional<ToolExecutionResult> res = module.execute("add_columns", params, TENANT, null);
            assertThat(res).isPresent();
            assertThat(res.get().success()).isTrue();

            ArgumentCaptor<CrudRequest> captor = ArgumentCaptor.forClass(CrudRequest.class);
            verify(crudExecutorService).execute(captor.capture(), eq(TENANT), any());
            CreateColumnRequest req = (CreateColumnRequest) captor.getValue();
            CreateColumnRequest.ColumnDefinition col = req.getColumns().get(0);
            assertThat(col.display()).containsKey("options");
        }

        @Test
        @DisplayName("Should fail without columns")
        void shouldFailWithoutColumns() {
            Map<String, Object> params = Map.of("table_id", 1);
            Optional<ToolExecutionResult> res = module.execute("add_columns", params, TENANT, null);
            assertThat(res).isPresent();
            assertThat(res.get().success()).isFalse();
            assertThat(res.get().errorCode()).isEqualTo(ToolErrorCode.MISSING_PARAMETER);
        }

        @Test
        @DisplayName("Should fail with empty columns list")
        void shouldFailWithEmptyColumns() {
            Map<String, Object> params = new HashMap<>();
            params.put("table_id", 1);
            params.put("columns", List.of());

            Optional<ToolExecutionResult> res = module.execute("add_columns", params, TENANT, null);
            assertThat(res).isPresent();
            assertThat(res.get().success()).isFalse();
            assertThat(res.get().errorCode()).isEqualTo(ToolErrorCode.VALIDATION_ERROR);
        }

        @Test
        @DisplayName("Should propagate CRUD failure")
        void shouldPropagateCrudFailure() {
            CrudResult result = CrudResult.failure(CrudOperation.CREATE_COLUMN, "Duplicate column name");
            when(crudExecutorService.execute(any(CrudRequest.class), eq(TENANT), any())).thenReturn(result);
            when(dataSourceService.getDataSource(1L)).thenReturn(Optional.of(fakeDs(1L, "T")));

            Map<String, Object> params = new HashMap<>();
            params.put("table_id", 1);
            params.put("columns", List.of(Map.of("name", "x", "type", "text")));

            Optional<ToolExecutionResult> res = module.execute("add_columns", params, TENANT, null);
            assertThat(res).isPresent();
            assertThat(res.get().success()).isFalse();
            assertThat(res.get().error()).contains("Duplicate column name");
        }
    }

    // ==================== Validation: reserved names (B1) ====================

    @Nested
    @DisplayName("Reserved column name rejection (B1)")
    class ReservedNameTests {

        @Test
        @DisplayName("Should reject reserved physical column name 'priority'")
        void shouldRejectPriority() {
            Map<String, Object> params = new HashMap<>();
            params.put("table_id", 1);
            params.put("columns", List.of(Map.of("name", "priority", "type", "select")));

            Optional<ToolExecutionResult> res = module.execute("add_columns", params, TENANT, null);
            assertThat(res).isPresent();
            assertThat(res.get().success()).isFalse();
            assertThat(res.get().errorCode()).isEqualTo(ToolErrorCode.VALIDATION_ERROR);
            assertThat(res.get().error()).contains("reserved");
            verify(crudExecutorService, never()).execute(any(CrudRequest.class), eq(TENANT), any());
        }

        @Test
        @DisplayName("Should reject every reserved physical column name")
        void shouldRejectAllReserved() {
            List<String> reserved = List.of(
                    "id", "data_source_id", "tenant_id", "data",
                    "priority", "row_index", "created_at", "updated_at"
            );
            for (String name : reserved) {
                Map<String, Object> params = new HashMap<>();
                params.put("table_id", 1);
                params.put("columns", List.of(Map.of("name", name, "type", "text")));

                Optional<ToolExecutionResult> res = module.execute("add_columns", params, TENANT, null);
                assertThat(res).isPresent();
                assertThat(res.get().success()).as("rejecting %s", name).isFalse();
                assertThat(res.get().errorCode()).isEqualTo(ToolErrorCode.VALIDATION_ERROR);
            }
            verify(crudExecutorService, never()).execute(any(CrudRequest.class), eq(TENANT), any());
        }

        @Test
        @DisplayName("Should reject reserved name with data. prefix stripped")
        void shouldRejectReservedWithDataPrefix() {
            Map<String, Object> params = new HashMap<>();
            params.put("table_id", 1);
            params.put("columns", List.of(Map.of("name", "data.priority", "type", "text")));

            Optional<ToolExecutionResult> res = module.execute("add_columns", params, TENANT, null);
            assertThat(res).isPresent();
            assertThat(res.get().success()).isFalse();
            assertThat(res.get().errorCode()).isEqualTo(ToolErrorCode.VALIDATION_ERROR);
        }
    }

    // ==================== Validation: column types (B2) ====================

    @Nested
    @DisplayName("Column type validation (B2)")
    class ColumnTypeValidationTests {

        @Test
        @DisplayName("Should reject unknown column type (no silent fallback)")
        void shouldRejectUnknownType() {
            Map<String, Object> params = new HashMap<>();
            params.put("table_id", 1);
            params.put("columns", List.of(Map.of("name", "c", "type", "totally_invalid_xyz")));

            Optional<ToolExecutionResult> res = module.execute("add_columns", params, TENANT, null);
            assertThat(res).isPresent();
            assertThat(res.get().success()).isFalse();
            assertThat(res.get().errorCode()).isEqualTo(ToolErrorCode.VALIDATION_ERROR);
            assertThat(res.get().error()).contains("Invalid column type");
            assertThat(res.get().error()).contains("totally_invalid_xyz");
            verify(crudExecutorService, never()).execute(any(CrudRequest.class), eq(TENANT), any());
        }

        @Test
        @DisplayName("Should accept every canonical ColumnType value")
        void shouldAcceptAllCanonicalTypes() {
            CrudResult result = CrudResult.success(CrudOperation.CREATE_COLUMN, "OK",
                    CrudResult.ResultData.forCreateColumn(List.of("c")));
            when(crudExecutorService.execute(any(CrudRequest.class), eq(TENANT), any())).thenReturn(result);
            when(dataSourceService.getDataSource(1L)).thenReturn(Optional.of(fakeDs(1L, "T")));

            List<String> validTypes = List.of(
                    "text", "number", "date", "checkbox", "select", "multi_select",
                    "rating", "sentiment", "progress", "file", "image",
                    "email", "phone", "url", "vector"
            );
            // select/multi_select require display.options; vector requires display.dimension -
            // see ToolParameterUtils#validateSelectOptions / #validateVectorDimension.
            List<Map<String, Object>> sampleOptions = List.of(
                    Map.of("label", "A", "value", "a"),
                    Map.of("label", "B", "value", "b")
            );
            for (String type : validTypes) {
                Map<String, Object> column = new HashMap<>();
                column.put("name", "c_" + type);
                column.put("type", type);
                if ("select".equals(type) || "multi_select".equals(type)) {
                    column.put("display", Map.of("options", sampleOptions));
                } else if ("vector".equals(type)) {
                    column.put("display", Map.of("dimension", 1536, "metric", "cosine"));
                }
                Map<String, Object> params = new HashMap<>();
                params.put("table_id", 1);
                params.put("columns", List.of(column));

                Optional<ToolExecutionResult> res = module.execute("add_columns", params, TENANT, null);
                assertThat(res).isPresent();
                assertThat(res.get().success()).as("type=%s should be accepted", type).isTrue();
            }
        }

        @Test
        @DisplayName("Missing type defaults to text (no validation error)")
        void missingTypeDefaultsToText() {
            CrudResult result = CrudResult.success(CrudOperation.CREATE_COLUMN, "OK",
                    CrudResult.ResultData.forCreateColumn(List.of("c")));
            when(crudExecutorService.execute(any(CrudRequest.class), eq(TENANT), any())).thenReturn(result);
            when(dataSourceService.getDataSource(1L)).thenReturn(Optional.of(fakeDs(1L, "T")));

            Map<String, Object> col = new LinkedHashMap<>();
            col.put("name", "untyped");
            Map<String, Object> params = new HashMap<>();
            params.put("table_id", 1);
            params.put("columns", List.of(col));

            Optional<ToolExecutionResult> res = module.execute("add_columns", params, TENANT, null);
            assertThat(res).isPresent();
            assertThat(res.get().success()).isTrue();
        }
    }

    // ==================== Validation: existing column duplicates (B3) ====================

    @Nested
    @DisplayName("Duplicate column rejection (B3)")
    class DuplicateColumnTests {

        @Test
        @DisplayName("Should reject column name that already exists on the table")
        void shouldRejectExistingColumnName() {
            // Production shape: {field, order}
            DataSource ds = new DataSource(1L, TENANT, "T", "desc", DataSourceType.INLINE, Map.of(),
                    DataSourceStatus.ACTIVE, null, null, TENANT,
                    List.of(Map.of("field", "title", "order", 0)),
                    null, null, null, null, null);
            when(dataSourceService.getDataSource(1L)).thenReturn(Optional.of(ds));

            Map<String, Object> params = new HashMap<>();
            params.put("table_id", 1);
            params.put("columns", List.of(Map.of("name", "title", "type", "text")));

            Optional<ToolExecutionResult> res = module.execute("add_columns", params, TENANT, null);
            assertThat(res).isPresent();
            assertThat(res.get().success()).isFalse();
            assertThat(res.get().errorCode()).isEqualTo(ToolErrorCode.VALIDATION_ERROR);
            assertThat(res.get().error()).contains("already exists");
            verify(crudExecutorService, never()).execute(any(CrudRequest.class), eq(TENANT), any());
        }

        @Test
        @DisplayName("Existing column check is case-insensitive")
        void existingColumnCheckIsCaseInsensitive() {
            DataSource ds = new DataSource(1L, TENANT, "T", "desc", DataSourceType.INLINE, Map.of(),
                    DataSourceStatus.ACTIVE, null, null, TENANT,
                    List.of(Map.of("field", "Title", "order", 0)),
                    null, null, null, null, null);
            when(dataSourceService.getDataSource(1L)).thenReturn(Optional.of(ds));

            Map<String, Object> params = new HashMap<>();
            params.put("table_id", 1);
            params.put("columns", List.of(Map.of("name", "TITLE", "type", "text")));

            Optional<ToolExecutionResult> res = module.execute("add_columns", params, TENANT, null);
            assertThat(res).isPresent();
            assertThat(res.get().success()).isFalse();
            assertThat(res.get().errorCode()).isEqualTo(ToolErrorCode.VALIDATION_ERROR);
        }

        @Test
        @DisplayName("Should reject duplicate names within a single request")
        void shouldRejectIntraRequestDuplicates() {
            Map<String, Object> params = new HashMap<>();
            params.put("table_id", 1);
            params.put("columns", List.of(
                    Map.of("name", "foo", "type", "text"),
                    Map.of("name", "foo", "type", "number")
            ));

            Optional<ToolExecutionResult> res = module.execute("add_columns", params, TENANT, null);
            assertThat(res).isPresent();
            assertThat(res.get().success()).isFalse();
            assertThat(res.get().errorCode()).isEqualTo(ToolErrorCode.VALIDATION_ERROR);
            assertThat(res.get().error()).contains("Duplicate");
            verify(crudExecutorService, never()).execute(any(CrudRequest.class), eq(TENANT), any());
        }

        @Test
        @DisplayName("Should reject using columnOrder in its real persisted shape (field/order)")
        void shouldRejectUsingRealColumnOrderShape() {
            // Production shape: DataSourceDefaults.generateColumnOrder produces {field, order}
            List<Map<String, Object>> columnOrder = DataSourceDefaults.generateColumnOrder(
                    new LinkedHashSet<>(List.of("title", "category")));
            DataSource ds = new DataSource(1L, TENANT, "T", "desc", DataSourceType.INLINE, Map.of(),
                    DataSourceStatus.ACTIVE, null, null, TENANT,
                    columnOrder, null, null, null, null, null);
            when(dataSourceService.getDataSource(1L)).thenReturn(Optional.of(ds));

            Map<String, Object> params = new HashMap<>();
            params.put("table_id", 1);
            params.put("columns", List.of(Map.of("name", "category", "type", "text")));

            Optional<ToolExecutionResult> res = module.execute("add_columns", params, TENANT, null);
            assertThat(res).isPresent();
            assertThat(res.get().success()).isFalse();
            assertThat(res.get().errorCode()).isEqualTo(ToolErrorCode.VALIDATION_ERROR);
            assertThat(res.get().error()).contains("already exists");
            verify(crudExecutorService, never()).execute(any(CrudRequest.class), eq(TENANT), any());
        }

        @Test
        @DisplayName("Should detect duplicates via mappingSpec even if columnOrder is empty")
        void shouldDetectDuplicatesViaMappingSpec() {
            ColumnMappingSpec spec = new ColumnMappingSpec("data.email", ColumnType.EMAIL,
                    ColumnStructure.SCALAR, Map.of(), Map.of());
            DataSource ds = new DataSource(1L, TENANT, "T", "desc", DataSourceType.INLINE, Map.of(),
                    DataSourceStatus.ACTIVE, null, null, TENANT,
                    List.of(), Map.of("email", spec), null, null, null, null);
            when(dataSourceService.getDataSource(1L)).thenReturn(Optional.of(ds));

            Map<String, Object> params = new HashMap<>();
            params.put("table_id", 1);
            params.put("columns", List.of(Map.of("name", "email", "type", "text")));

            Optional<ToolExecutionResult> res = module.execute("add_columns", params, TENANT, null);
            assertThat(res).isPresent();
            assertThat(res.get().success()).isFalse();
            assertThat(res.get().errorCode()).isEqualTo(ToolErrorCode.VALIDATION_ERROR);
            assertThat(res.get().error()).contains("already exists");
            verify(crudExecutorService, never()).execute(any(CrudRequest.class), eq(TENANT), any());
        }
    }

    // ==================== Org-scope threading (regression for 4adcbf327) ====================

    @Nested
    @DisplayName("Caller org-id threading into CRUD")
    class OrgScopeThreadingTests {

        @Test
        @DisplayName("add_columns threads the caller orgId into the 3-arg CRUD execute (was passed null)")
        void addColumnsThreadsCallerOrgId() {
            CrudResult result = CrudResult.success(CrudOperation.CREATE_COLUMN, "OK",
                    CrudResult.ResultData.forCreateColumn(List.of("c")));
            when(crudExecutorService.execute(any(CrudRequest.class), eq(TENANT), eq(ORG))).thenReturn(result);
            when(dataSourceService.getDataSource(1L)).thenReturn(Optional.of(fakeDs(1L, "T")));

            Map<String, Object> params = new HashMap<>();
            params.put("table_id", 1);
            params.put("columns", List.of(Map.of("name", "c", "type", "text")));

            Optional<ToolExecutionResult> res = module.execute(
                    "add_columns", params, TENANT, ctxWithOrg(ORG));

            assertThat(res).isPresent();
            assertThat(res.get().success()).isTrue();
            // Pre-fix add_columns called the 2-arg execute → orgId dropped → an org-owned
            // datasource resolved to "not found" for a caller operating in org scope.
            verify(crudExecutorService).execute(any(CrudRequest.class), eq(TENANT), eq(ORG));
        }

        @Test
        @DisplayName("a null context still resolves to a null orgId (no NPE)")
        void nullContextYieldsNullOrgId() {
            CrudResult result = CrudResult.success(CrudOperation.CREATE_COLUMN, "OK",
                    CrudResult.ResultData.forCreateColumn(List.of("c")));
            when(crudExecutorService.execute(any(CrudRequest.class), eq(TENANT), isNull())).thenReturn(result);
            when(dataSourceService.getDataSource(1L)).thenReturn(Optional.of(fakeDs(1L, "T")));

            Map<String, Object> params = new HashMap<>();
            params.put("table_id", 1);
            params.put("columns", List.of(Map.of("name", "c", "type", "text")));

            Optional<ToolExecutionResult> res = module.execute(
                    "add_columns", params, TENANT, null);

            assertThat(res).isPresent();
            assertThat(res.get().success()).isTrue();
            verify(crudExecutorService).execute(any(CrudRequest.class), eq(TENANT), isNull());
        }
    }

    private static com.apimarketplace.datasource.services.VectorFeatureGate ceVectorGate() {
        org.springframework.mock.env.MockEnvironment env = new org.springframework.mock.env.MockEnvironment();
        env.setProperty("app.edition", "ce");
        return new com.apimarketplace.datasource.services.VectorFeatureGate(new com.apimarketplace.common.web.AppEditionProvider(env));
    }
}
