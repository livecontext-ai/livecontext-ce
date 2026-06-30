package com.apimarketplace.datasource.tools.datasource;

import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.datasource.crud.domain.CrudOperation;
import com.apimarketplace.datasource.crud.domain.CrudResult;
import com.apimarketplace.datasource.crud.dto.CreateRowRequest;
import com.apimarketplace.datasource.crud.dto.CrudRequest;
import com.apimarketplace.datasource.crud.dto.ReadRowRequest;
import com.apimarketplace.datasource.crud.dto.UpdateRowRequest;
import com.apimarketplace.datasource.crud.service.CrudExecutorService;
import com.apimarketplace.datasource.domain.ColumnStructure;
import com.apimarketplace.datasource.domain.ColumnType;
import com.apimarketplace.datasource.domain.DataSourceModels.ColumnMappingSpec;
import com.apimarketplace.datasource.domain.DataSourceModels.DataSource;
import com.apimarketplace.datasource.domain.DataSourceModels.DataSourceStatus;
import com.apimarketplace.datasource.domain.DataSourceModels.DataSourceType;
import com.apimarketplace.datasource.services.DataSourceService;
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
@DisplayName("DataSourceRowModule")
class DataSourceRowModuleTest {

    @Mock private CrudExecutorService crudExecutorService;
    @Mock private DataSourceService dataSourceService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private DataSourceRowModule module;

    private static final String ORG = "org-xyz";

    /** Build a tool context carrying the caller's org id (orgId is the 7th record field). */
    private static ToolExecutionContext ctxWithOrg(String orgId) {
        return new ToolExecutionContext(TENANT, Map.of(), Map.of(), Set.of(), null, null, orgId, null);
    }

    private static final String TENANT = "test-tenant";

    @BeforeEach
    void setUp() {
        module = new DataSourceRowModule(crudExecutorService, dataSourceService, objectMapper);
    }

    private DataSource fakeDs(Long id, String name) {
        return new DataSource(id, TENANT, name, "desc", DataSourceType.INLINE, Map.of(),
                DataSourceStatus.ACTIVE, null, null, TENANT, null, null, null, null, null, null);
    }

    // ==================== canHandle ====================

    @Test
    @DisplayName("canHandle should accept row operations")
    void canHandleShouldAcceptRowOps() {
        assertThat(module.canHandle("query_rows")).isTrue();
        assertThat(module.canHandle("insert_rows")).isTrue();
        assertThat(module.canHandle("update_rows")).isTrue();
        assertThat(module.canHandle("delete_rows")).isTrue();
        assertThat(module.canHandle("create")).isFalse();
        assertThat(module.canHandle("unknown")).isFalse();
    }

    // ==================== access enforcement (mode + allow-list) ====================

    @Nested
    @DisplayName("access enforcement")
    class AccessEnforcementTests {

        private ToolExecutionContext ctx(Map<String, Object> credentials) {
            return new ToolExecutionContext(TENANT, credentials, Map.of(), Set.of(), null, null, ORG, null);
        }

        @Test
        @DisplayName("read-only mode (tableAccessMode='read') blocks insert_rows before the CRUD executor")
        void readModeBlocksInsertRows() {
            Map<String, Object> params = Map.of("table_id", 5,
                    "rows", List.of(Map.of("name", "Alice")));
            Optional<ToolExecutionResult> res = module.execute("insert_rows", params, TENANT,
                    ctx(Map.of("tableAccessMode", "read")));

            assertThat(res).isPresent();
            assertThat(res.get().success()).isFalse();
            assertThat(res.get().errorCode()).isEqualTo(ToolErrorCode.PERMISSION_DENIED);
            verifyNoInteractions(crudExecutorService);
        }

        @Test
        @DisplayName("read-only mode still ALLOWS query_rows (query_rows is a table READ action)")
        void readModeAllowsQueryRows() {
            CrudResult result = CrudResult.success(CrudOperation.READ_ROW, "OK",
                    CrudResult.ResultData.forRead(List.of(Map.of("name", "Alice")), false, 0));
            when(crudExecutorService.execute(any(CrudRequest.class), eq(TENANT), any())).thenReturn(result);

            Optional<ToolExecutionResult> res = module.execute("query_rows", Map.of("table_id", 5), TENANT,
                    ctx(Map.of("tableAccessMode", "read")));

            assertThat(res).isPresent();
            assertThat(res.get().success()).isTrue();
        }

        @Test
        @DisplayName("allow-list: query_rows on a table_id NOT in allowedTableIds is denied before the executor (read leak)")
        void outOfAllowListBlocksQueryRows() {
            Optional<ToolExecutionResult> res = module.execute("query_rows", Map.of("table_id", 99), TENANT,
                    ctx(Map.of("allowedTableIds", List.of("5"))));

            assertThat(res).isPresent();
            assertThat(res.get().success()).isFalse();
            assertThat(res.get().errorCode()).isEqualTo(ToolErrorCode.PERMISSION_DENIED);
            assertThat(res.get().error()).contains("approved table list");
            verifyNoInteractions(crudExecutorService);
        }

        @Test
        @DisplayName("allow-list: update_rows on a table_id NOT in allowedTableIds is denied (mutation leak - the core bypass)")
        void outOfAllowListBlocksUpdateRows() {
            Map<String, Object> params = Map.of("table_id", 99,
                    "where", Map.of("column", "id", "operator", "=", "value", 1),
                    "set", Map.of("name", "Hacked"));
            Optional<ToolExecutionResult> res = module.execute("update_rows", params, TENANT,
                    ctx(Map.of("allowedTableIds", List.of("5"))));

            assertThat(res).isPresent();
            assertThat(res.get().success()).isFalse();
            assertThat(res.get().errorCode()).isEqualTo(ToolErrorCode.PERMISSION_DENIED);
            verifyNoInteractions(crudExecutorService);
        }

        @Test
        @DisplayName("allow-list: query_rows on a table_id IN allowedTableIds passes through to the executor")
        void inAllowListAllowsQueryRows() {
            CrudResult result = CrudResult.success(CrudOperation.READ_ROW, "OK",
                    CrudResult.ResultData.forRead(List.of(Map.of("name", "Alice")), false, 0));
            when(crudExecutorService.execute(any(CrudRequest.class), eq(TENANT), any())).thenReturn(result);

            Optional<ToolExecutionResult> res = module.execute("query_rows", Map.of("table_id", 5), TENANT,
                    ctx(Map.of("allowedTableIds", List.of("5"))));

            assertThat(res).isPresent();
            assertThat(res.get().success()).isTrue();
        }

        @Test
        @DisplayName("allow-list: EVERY write row op (insert/update/delete) on an out-of-list table_id is denied")
        void outOfAllowListBlocksAllWriteRowOps() {
            Map<String, Object> base = Map.of("table_id", 99,
                    "rows", List.of(Map.of("name", "X")),
                    "where", Map.of("column", "id", "operator", "=", "value", 1),
                    "set", Map.of("name", "X"));
            for (String write : List.of("insert_rows", "update_rows", "delete_rows")) {
                Optional<ToolExecutionResult> res = module.execute(write, base, TENANT,
                        ctx(Map.of("allowedTableIds", List.of("5"))));
                assertThat(res).as("write op '%s' on out-of-list table must be denied", write).isPresent();
                assertThat(res.get().success()).as(write).isFalse();
                assertThat(res.get().errorCode()).as(write).isEqualTo(ToolErrorCode.PERMISSION_DENIED);
            }
            verifyNoInteractions(crudExecutorService);
        }

        @Test
        @DisplayName("empty allow-list (tables=none → allowedTableIds=[]) denies even a read (explicit no-access)")
        void emptyAllowListDeniesEverything() {
            Optional<ToolExecutionResult> res = module.execute("query_rows", Map.of("table_id", 5), TENANT,
                    ctx(Map.of("allowedTableIds", List.of())));

            assertThat(res).isPresent();
            assertThat(res.get().success()).isFalse();
            assertThat(res.get().errorCode()).isEqualTo(ToolErrorCode.PERMISSION_DENIED);
            verifyNoInteractions(crudExecutorService);
        }
    }

    // ==================== table_id fallback ====================

    @Nested
    @DisplayName("table_id fallback")
    class TableIdFallbackTests {

        @Test
        @DisplayName("query_rows should accept table_id")
        void queryRowsShouldAcceptTableId() {
            CrudResult result = CrudResult.success(CrudOperation.READ_ROW, "OK",
                    CrudResult.ResultData.forRead(List.of(Map.of("name", "Alice")), false, 0));
            when(crudExecutorService.execute(any(CrudRequest.class), eq(TENANT), any())).thenReturn(result);

            Map<String, Object> params = Map.of("table_id", 5, "action", "query_rows");
            Optional<ToolExecutionResult> res = module.execute("query_rows", params, TENANT, null);

            assertThat(res).isPresent();
            assertThat(res.get().success()).isTrue();

            ArgumentCaptor<CrudRequest> captor = ArgumentCaptor.forClass(CrudRequest.class);
            verify(crudExecutorService).execute(captor.capture(), eq(TENANT), any());
            assertThat(captor.getValue().getDataSourceId()).isEqualTo(5L);
        }

        @Test
        @DisplayName("insert_rows should accept table_id")
        void insertRowsShouldAcceptTableId() {
            CrudResult result = CrudResult.success(CrudOperation.CREATE_ROW, "OK",
                    CrudResult.ResultData.forCreate(List.of(1L)));
            when(crudExecutorService.execute(any(CrudRequest.class), eq(TENANT), any())).thenReturn(result);
            when(dataSourceService.getDataSource(7L)).thenReturn(Optional.of(fakeDs(7L, "Test")));

            Map<String, Object> params = new HashMap<>();
            params.put("table_id", 7);
            params.put("rows", List.of(Map.of("name", "Alice")));

            Optional<ToolExecutionResult> res = module.execute("insert_rows", params, TENANT, null);
            assertThat(res).isPresent();
            assertThat(res.get().success()).isTrue();
        }

        @Test
        @DisplayName("update_rows should accept table_id")
        void updateRowsShouldAcceptTableId() {
            CrudResult result = CrudResult.success(CrudOperation.UPDATE_ROW, "OK",
                    CrudResult.ResultData.forUpdate(2));
            when(crudExecutorService.execute(any(CrudRequest.class), eq(TENANT), any())).thenReturn(result);
            when(dataSourceService.getDataSource(3L)).thenReturn(Optional.of(fakeDs(3L, "T")));

            Map<String, Object> params = Map.of(
                    "table_id", 3,
                    "where", Map.of("column", "id", "operator", "=", "value", 1),
                    "set", Map.of("name", "Updated")
            );

            Optional<ToolExecutionResult> res = module.execute("update_rows", params, TENANT, null);
            assertThat(res).isPresent();
            assertThat(res.get().success()).isTrue();
        }

        @Test
        @DisplayName("delete_rows should accept table_id")
        void deleteRowsShouldAcceptTableId() {
            CrudResult result = CrudResult.success(CrudOperation.DELETE_ROW, "OK",
                    CrudResult.ResultData.forDelete(1));
            when(crudExecutorService.execute(any(CrudRequest.class), eq(TENANT), any())).thenReturn(result);
            when(dataSourceService.getDataSource(9L)).thenReturn(Optional.of(fakeDs(9L, "T")));

            Map<String, Object> params = Map.of(
                    "table_id", 9,
                    "where", Map.of("column", "id", "operator", "=", "value", 1)
            );

            Optional<ToolExecutionResult> res = module.execute("delete_rows", params, TENANT, null);
            assertThat(res).isPresent();
            assertThat(res.get().success()).isTrue();
        }

        @Test
        @DisplayName("Should fail when no id param at all")
        void shouldFailWhenNoIdParam() {
            Optional<ToolExecutionResult> res = module.execute("query_rows", Map.of(), TENANT, null);
            assertThat(res).isPresent();
            assertThat(res.get().success()).isFalse();
            assertThat(res.get().errorCode()).isEqualTo(ToolErrorCode.MISSING_PARAMETER);
        }
    }

    // ==================== Double-serialization ====================

    @Nested
    @DisplayName("Double-serialization handling")
    class DoubleSerializationTests {

        @Test
        @DisplayName("insert_rows should handle double-serialized JSON row strings")
        void shouldHandleDoubleSerializedRows() {
            CrudResult result = CrudResult.success(CrudOperation.CREATE_ROW, "OK",
                    CrudResult.ResultData.forCreate(List.of(1L, 2L)));
            when(crudExecutorService.execute(any(CrudRequest.class), eq(TENANT), any())).thenReturn(result);
            when(dataSourceService.getDataSource(10L)).thenReturn(Optional.of(fakeDs(10L, "T")));

            // This is exactly what the LLM sends: rows as JSON strings
            Map<String, Object> params = new HashMap<>();
            params.put("table_id", 10);
            params.put("rows", List.of(
                    "{\"columns\": {\"Texte\": \"Row1\", \"Nombre\": 1, \"Checkbox\": true}}",
                    "{\"columns\": {\"Texte\": \"Row2\", \"Nombre\": 2, \"Checkbox\": false}}"
            ));

            Optional<ToolExecutionResult> res = module.execute("insert_rows", params, TENANT, null);

            assertThat(res).isPresent();
            assertThat(res.get().success()).isTrue();

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) res.get().data();
            assertThat(data.get("rowsInserted")).isEqualTo(2);

            // Verify the CRUD request was built correctly
            ArgumentCaptor<CrudRequest> captor = ArgumentCaptor.forClass(CrudRequest.class);
            verify(crudExecutorService).execute(captor.capture(), eq(TENANT), any());
            CreateRowRequest req = (CreateRowRequest) captor.getValue();
            assertThat(req.getRows()).hasSize(2);
            assertThat(req.getRows().get(0).columns()).containsKey("Texte");
        }

        @Test
        @DisplayName("insert_rows should handle properly serialized Maps")
        void shouldHandleProperlySerializedMaps() {
            CrudResult result = CrudResult.success(CrudOperation.CREATE_ROW, "OK",
                    CrudResult.ResultData.forCreate(List.of(1L)));
            when(crudExecutorService.execute(any(CrudRequest.class), eq(TENANT), any())).thenReturn(result);
            when(dataSourceService.getDataSource(10L)).thenReturn(Optional.of(fakeDs(10L, "T")));

            Map<String, Object> params = new HashMap<>();
            params.put("table_id", 10);
            params.put("rows", List.of(Map.of("columns", Map.of("name", "Alice"))));

            Optional<ToolExecutionResult> res = module.execute("insert_rows", params, TENANT, null);
            assertThat(res).isPresent();
            assertThat(res.get().success()).isTrue();
        }

        @Test
        @DisplayName("insert_rows should handle flat rows (no columns wrapper)")
        void shouldHandleFlatRows() {
            CrudResult result = CrudResult.success(CrudOperation.CREATE_ROW, "OK",
                    CrudResult.ResultData.forCreate(List.of(1L)));
            when(crudExecutorService.execute(any(CrudRequest.class), eq(TENANT), any())).thenReturn(result);
            when(dataSourceService.getDataSource(10L)).thenReturn(Optional.of(fakeDs(10L, "T")));

            Map<String, Object> params = new HashMap<>();
            params.put("table_id", 10);
            params.put("rows", List.of(Map.of("name", "Alice", "age", 30)));

            Optional<ToolExecutionResult> res = module.execute("insert_rows", params, TENANT, null);
            assertThat(res).isPresent();
            assertThat(res.get().success()).isTrue();

            // Flat rows should be used as columns directly
            ArgumentCaptor<CrudRequest> captor = ArgumentCaptor.forClass(CrudRequest.class);
            verify(crudExecutorService).execute(captor.capture(), eq(TENANT), any());
            CreateRowRequest req = (CreateRowRequest) captor.getValue();
            assertThat(req.getRows().get(0).columns()).containsKey("name");
        }
    }

    // ==================== query_rows ====================

    @Nested
    @DisplayName("query_rows")
    class QueryRowsTests {

        @Test
        @DisplayName("Should query with WHERE condition")
        void shouldQueryWithWhere() {
            CrudResult result = CrudResult.success(CrudOperation.READ_ROW, "OK",
                    CrudResult.ResultData.forRead(List.of(Map.of("name", "Alice")), false, 0));
            when(crudExecutorService.execute(any(CrudRequest.class), eq(TENANT), any())).thenReturn(result);

            Map<String, Object> params = Map.of(
                    "table_id", 1,
                    "where", Map.of("column", "status", "operator", "=", "value", "active"),
                    "limit", 10
            );

            Optional<ToolExecutionResult> res = module.execute("query_rows", params, TENANT, null);
            assertThat(res).isPresent();
            assertThat(res.get().success()).isTrue();

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) res.get().data();
            assertThat(data.get("rowCount")).isEqualTo(1);

            ArgumentCaptor<CrudRequest> captor = ArgumentCaptor.forClass(CrudRequest.class);
            verify(crudExecutorService).execute(captor.capture(), eq(TENANT), any());
            ReadRowRequest req = (ReadRowRequest) captor.getValue();
            assertThat(req.getWhere().column()).isEqualTo("status");
            assertThat(req.getWhere().operator()).isEqualTo("=");
            assertThat(req.getLimit()).isEqualTo(10);
        }

        @Test
        @DisplayName("Should query without WHERE (all rows)")
        void shouldQueryWithoutWhere() {
            CrudResult result = CrudResult.success(CrudOperation.READ_ROW, "OK",
                    CrudResult.ResultData.forRead(List.of(), false, 0));
            when(crudExecutorService.execute(any(CrudRequest.class), eq(TENANT), any())).thenReturn(result);

            Optional<ToolExecutionResult> res = module.execute("query_rows", Map.of("table_id", 1), TENANT, null);
            assertThat(res).isPresent();
            assertThat(res.get().success()).isTrue();

            ArgumentCaptor<CrudRequest> captor = ArgumentCaptor.forClass(CrudRequest.class);
            verify(crudExecutorService).execute(captor.capture(), eq(TENANT), any());
            ReadRowRequest req = (ReadRowRequest) captor.getValue();
            assertThat(req.getWhere()).isNull();
            assertThat(req.getLimit()).isEqualTo(20); // default
        }

        @Test
        @DisplayName("Should propagate CRUD failure")
        void shouldPropagateCrudFailure() {
            CrudResult result = CrudResult.failure(CrudOperation.READ_ROW, "Column not found");
            when(crudExecutorService.execute(any(CrudRequest.class), eq(TENANT), any())).thenReturn(result);

            Optional<ToolExecutionResult> res = module.execute("query_rows", Map.of("table_id", 1), TENANT, null);
            assertThat(res).isPresent();
            assertThat(res.get().success()).isFalse();
            assertThat(res.get().error()).contains("Column not found");
        }
    }

    // ==================== update_rows ====================

    @Nested
    @DisplayName("update_rows")
    class UpdateRowsTests {

        @Test
        @DisplayName("Should fail without where")
        void shouldFailWithoutWhere() {
            Map<String, Object> params = Map.of("table_id", 1, "set", Map.of("name", "x"));
            Optional<ToolExecutionResult> res = module.execute("update_rows", params, TENANT, null);
            assertThat(res).isPresent();
            assertThat(res.get().success()).isFalse();
            assertThat(res.get().errorCode()).isEqualTo(ToolErrorCode.MISSING_PARAMETER);
        }

        @Test
        @DisplayName("Should fail without set")
        void shouldFailWithoutSet() {
            Map<String, Object> params = Map.of("table_id", 1, "where", Map.of("column", "id", "operator", "=", "value", 1));
            Optional<ToolExecutionResult> res = module.execute("update_rows", params, TENANT, null);
            assertThat(res).isPresent();
            assertThat(res.get().success()).isFalse();
            assertThat(res.get().errorCode()).isEqualTo(ToolErrorCode.MISSING_PARAMETER);
        }
    }

    // ==================== delete_rows ====================

    @Nested
    @DisplayName("delete_rows")
    class DeleteRowsTests {

        @Test
        @DisplayName("Should fail without where")
        void shouldFailWithoutWhere() {
            Map<String, Object> params = Map.of("table_id", 1);
            Optional<ToolExecutionResult> res = module.execute("delete_rows", params, TENANT, null);
            assertThat(res).isPresent();
            assertThat(res.get().success()).isFalse();
            assertThat(res.get().errorCode()).isEqualTo(ToolErrorCode.MISSING_PARAMETER);
        }
    }

    // ==================== Vector truncation ====================

    @Nested
    @DisplayName("Vector column truncation")
    class VectorTruncationTests {

        @Test
        @DisplayName("Should truncate vector columns in query_rows results")
        void shouldTruncateVectorColumns() {
            // Row with a vector column (List<Number> as returned by CRUD service)
            List<Number> fakeVector = new ArrayList<>();
            for (int i = 0; i < 1536; i++) fakeVector.add(0.01f * i);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", 100);
            row.put("content", "Hello world");
            row.put("embedding", fakeVector);

            CrudResult result = CrudResult.success(CrudOperation.READ_ROW, "OK",
                    CrudResult.ResultData.forRead(List.of(row), false, 0));
            when(crudExecutorService.execute(any(CrudRequest.class), eq(TENANT), any())).thenReturn(result);

            // Type info lives in mappingSpec (NOT columnOrder, which only has {field, order}).
            Map<String, ColumnMappingSpec> mapping = new LinkedHashMap<>();
            mapping.put("content", new ColumnMappingSpec("data.content", ColumnType.TEXT,
                    ColumnStructure.SCALAR, Map.of(), Map.of()));
            mapping.put("embedding", new ColumnMappingSpec("data.embedding", ColumnType.VECTOR,
                    ColumnStructure.ARRAY, Map.of(), Map.of()));
            DataSource ds = new DataSource(5L, TENANT, "RAG", "desc", DataSourceType.INLINE, Map.of(),
                    DataSourceStatus.ACTIVE, null, null, TENANT,
                    List.of(
                        Map.of("field", "content", "order", 0),
                        Map.of("field", "embedding", "order", 1)
                    ),
                    mapping, null, null, null, null);
            when(dataSourceService.getDataSource(5L)).thenReturn(Optional.of(ds));

            Optional<ToolExecutionResult> res = module.execute("query_rows", Map.of("table_id", 5), TENANT, null);
            assertThat(res).isPresent();
            assertThat(res.get().success()).isTrue();

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) res.get().data();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("rows");
            assertThat(rows).hasSize(1);
            // content should be untouched
            assertThat(rows.get(0).get("content")).isEqualTo("Hello world");
            // vector should be truncated to dimension summary
            assertThat(rows.get(0).get("embedding")).isEqualTo("[vector dim=1536]");
        }

        @Test
        @DisplayName("Should not truncate non-vector columns")
        void shouldNotTruncateNonVectorColumns() {
            CrudResult result = CrudResult.success(CrudOperation.READ_ROW, "OK",
                    CrudResult.ResultData.forRead(List.of(Map.of("name", "Alice")), false, 0));
            when(crudExecutorService.execute(any(CrudRequest.class), eq(TENANT), any())).thenReturn(result);

            // No vector columns - mappingSpec only has text
            Map<String, ColumnMappingSpec> mapping = Map.of(
                    "name", new ColumnMappingSpec("data.name", ColumnType.TEXT,
                            ColumnStructure.SCALAR, Map.of(), Map.of()));
            DataSource ds = new DataSource(5L, TENANT, "Normal", "desc", DataSourceType.INLINE, Map.of(),
                    DataSourceStatus.ACTIVE, null, null, TENANT,
                    List.of(Map.of("field", "name", "order", 0)),
                    mapping, null, null, null, null);
            when(dataSourceService.getDataSource(5L)).thenReturn(Optional.of(ds));

            Optional<ToolExecutionResult> res = module.execute("query_rows", Map.of("table_id", 5), TENANT, null);
            assertThat(res).isPresent();

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) res.get().data();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("rows");
            assertThat(rows.get(0).get("name")).isEqualTo("Alice");
        }
    }

    // ==================== Key sanitization (B4) ====================

    @Nested
    @DisplayName("Row-key sanitization (data. prefix)")
    class RowKeySanitizationTests {

        @Test
        @DisplayName("insert_rows strips data. prefix from row keys")
        void insertRowsStripsDataPrefix() {
            CrudResult result = CrudResult.success(CrudOperation.CREATE_ROW, "OK",
                    CrudResult.ResultData.forCreate(List.of(1L)));
            when(crudExecutorService.execute(any(CrudRequest.class), eq(TENANT), any())).thenReturn(result);
            when(dataSourceService.getDataSource(1L)).thenReturn(Optional.of(fakeDs(1L, "T")));

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("data.title", "hi");
            row.put("plain", "there");

            Map<String, Object> params = new HashMap<>();
            params.put("table_id", 1);
            params.put("rows", List.of(row));

            Optional<ToolExecutionResult> res = module.execute("insert_rows", params, TENANT, null);
            assertThat(res).isPresent();
            assertThat(res.get().success()).isTrue();

            ArgumentCaptor<CrudRequest> captor = ArgumentCaptor.forClass(CrudRequest.class);
            verify(crudExecutorService).execute(captor.capture(), eq(TENANT), any());
            CreateRowRequest req = (CreateRowRequest) captor.getValue();
            Map<String, Object> columns = req.getRows().get(0).columns();
            assertThat(columns).containsKey("title").doesNotContainKey("data.title");
            assertThat(columns).containsKey("plain");
        }

        @Test
        @DisplayName("update_rows strips data. prefix from set keys")
        void updateRowsStripsDataPrefixInSet() {
            CrudResult result = CrudResult.success(CrudOperation.UPDATE_ROW, "OK",
                    CrudResult.ResultData.forUpdate(1));
            when(crudExecutorService.execute(any(CrudRequest.class), eq(TENANT), any())).thenReturn(result);
            when(dataSourceService.getDataSource(1L)).thenReturn(Optional.of(fakeDs(1L, "T")));

            Map<String, Object> setMap = new LinkedHashMap<>();
            setMap.put("data.price", 42);

            Map<String, Object> params = Map.of(
                    "table_id", 1,
                    "where", Map.of("column", "id", "operator", "=", "value", 1),
                    "set", setMap
            );

            Optional<ToolExecutionResult> res = module.execute("update_rows", params, TENANT, null);
            assertThat(res).isPresent();
            assertThat(res.get().success()).isTrue();

            ArgumentCaptor<CrudRequest> captor = ArgumentCaptor.forClass(CrudRequest.class);
            verify(crudExecutorService).execute(captor.capture(), eq(TENANT), any());
            UpdateRowRequest req = (UpdateRowRequest) captor.getValue();
            assertThat(req.getSet()).containsKey("price").doesNotContainKey("data.price");
        }
    }

    // ==================== Reserved-key rejection (sibling of B1) ====================

    @Nested
    @DisplayName("Reserved-name rejection on row payload keys")
    class ReservedPayloadKeyTests {

        @Test
        @DisplayName("insert_rows rejects reserved key (e.g. 'id')")
        void insertRowsRejectsReservedKey() {
            Map<String, Object> params = new HashMap<>();
            params.put("table_id", 1);
            params.put("rows", List.of(Map.of("id", 999, "title", "ok")));

            Optional<ToolExecutionResult> res = module.execute("insert_rows", params, TENANT, null);
            assertThat(res).isPresent();
            assertThat(res.get().success()).isFalse();
            assertThat(res.get().errorCode()).isEqualTo(ToolErrorCode.VALIDATION_ERROR);
            assertThat(res.get().error()).contains("reserved");
            verify(crudExecutorService, never()).execute(any(CrudRequest.class), eq(TENANT), any());
        }

        @Test
        @DisplayName("insert_rows rejects reserved key even when prefixed with data.")
        void insertRowsRejectsDataPrefixedReservedKey() {
            Map<String, Object> params = new HashMap<>();
            params.put("table_id", 1);
            params.put("rows", List.of(Map.of("data.priority", "hi", "title", "ok")));

            Optional<ToolExecutionResult> res = module.execute("insert_rows", params, TENANT, null);
            assertThat(res).isPresent();
            assertThat(res.get().success()).isFalse();
            assertThat(res.get().errorCode()).isEqualTo(ToolErrorCode.VALIDATION_ERROR);
            assertThat(res.get().error()).contains("reserved");
            verify(crudExecutorService, never()).execute(any(CrudRequest.class), eq(TENANT), any());
        }

        @Test
        @DisplayName("update_rows rejects reserved key in set")
        void updateRowsRejectsReservedSetKey() {
            Map<String, Object> params = Map.of(
                    "table_id", 1,
                    "where", Map.of("column", "title", "operator", "=", "value", "x"),
                    "set", Map.of("row_index", 0)
            );

            Optional<ToolExecutionResult> res = module.execute("update_rows", params, TENANT, null);
            assertThat(res).isPresent();
            assertThat(res.get().success()).isFalse();
            assertThat(res.get().errorCode()).isEqualTo(ToolErrorCode.VALIDATION_ERROR);
            assertThat(res.get().error()).contains("reserved");
            verify(crudExecutorService, never()).execute(any(CrudRequest.class), eq(TENANT), any());
        }

        @Test
        @DisplayName("insert_rows accepts non-reserved keys")
        void insertRowsAcceptsNonReservedKeys() {
            CrudResult result = CrudResult.success(CrudOperation.CREATE_ROW, "OK",
                    CrudResult.ResultData.forCreate(List.of(1L)));
            when(crudExecutorService.execute(any(CrudRequest.class), eq(TENANT), any())).thenReturn(result);
            when(dataSourceService.getDataSource(1L)).thenReturn(Optional.of(fakeDs(1L, "T")));

            Map<String, Object> params = new HashMap<>();
            params.put("table_id", 1);
            params.put("rows", List.of(Map.of("title", "hello", "score", 42)));

            Optional<ToolExecutionResult> res = module.execute("insert_rows", params, TENANT, null);
            assertThat(res).isPresent();
            assertThat(res.get().success()).isTrue();
        }
    }

    // ==================== Negative limit (B5) ====================

    @Nested
    @DisplayName("query_rows validation")
    class QueryRowsValidationTests {

        @Test
        @DisplayName("Negative limit is rejected at module layer (no SQL leak)")
        void negativeLimitIsRejected() {
            Map<String, Object> params = new HashMap<>();
            params.put("table_id", 1);
            params.put("limit", -5);

            Optional<ToolExecutionResult> res = module.execute("query_rows", params, TENANT, null);
            assertThat(res).isPresent();
            assertThat(res.get().success()).isFalse();
            assertThat(res.get().errorCode()).isEqualTo(ToolErrorCode.VALIDATION_ERROR);
            assertThat(res.get().error()).contains("limit must be >= 0");
            // Confirm we never hit CRUD - no raw SQL leaks
            verify(crudExecutorService, never()).execute(any(CrudRequest.class), eq(TENANT), any());
        }

        @Test
        @DisplayName("Limit of 0 is allowed (empty result)")
        void zeroLimitIsAllowed() {
            CrudResult result = CrudResult.success(CrudOperation.READ_ROW, "OK",
                    CrudResult.ResultData.forRead(List.of(), false, 0));
            when(crudExecutorService.execute(any(CrudRequest.class), eq(TENANT), any())).thenReturn(result);

            Map<String, Object> params = new HashMap<>();
            params.put("table_id", 1);
            params.put("limit", 0);

            Optional<ToolExecutionResult> res = module.execute("query_rows", params, TENANT, null);
            assertThat(res).isPresent();
            assertThat(res.get().success()).isTrue();
        }
    }

    // ==================== Similarity null guards (B6) ====================

    @Nested
    @DisplayName("query_rows similarity guards")
    class SimilarityGuardTests {

        @Test
        @DisplayName("Missing similarity.column returns clear MISSING_PARAMETER")
        void missingSimilarityColumnIsClear() {
            Map<String, Object> sim = new LinkedHashMap<>();
            sim.put("queryVector", List.of(0.1, 0.2));

            Map<String, Object> params = new HashMap<>();
            params.put("table_id", 1);
            params.put("similarity", sim);

            Optional<ToolExecutionResult> res = module.execute("query_rows", params, TENANT, null);
            assertThat(res).isPresent();
            assertThat(res.get().success()).isFalse();
            assertThat(res.get().errorCode()).isEqualTo(ToolErrorCode.MISSING_PARAMETER);
            assertThat(res.get().error()).contains("similarity.column is required");
            verify(crudExecutorService, never()).execute(any(CrudRequest.class), eq(TENANT), any());
        }

        @Test
        @DisplayName("Missing similarity.queryVector returns clear MISSING_PARAMETER")
        void missingSimilarityQueryVectorIsClear() {
            Map<String, Object> sim = new LinkedHashMap<>();
            sim.put("column", "embedding");

            Map<String, Object> params = new HashMap<>();
            params.put("table_id", 1);
            params.put("similarity", sim);

            Optional<ToolExecutionResult> res = module.execute("query_rows", params, TENANT, null);
            assertThat(res).isPresent();
            assertThat(res.get().success()).isFalse();
            assertThat(res.get().errorCode()).isEqualTo(ToolErrorCode.MISSING_PARAMETER);
            assertThat(res.get().error()).contains("similarity.queryVector");
            verify(crudExecutorService, never()).execute(any(CrudRequest.class), eq(TENANT), any());
        }
    }

    // ==================== Error handling ====================

    @Nested
    @DisplayName("Error handling")
    class ErrorHandlingTests {

        @Test
        @DisplayName("insert_rows should fail with empty rows")
        void insertShouldFailWithEmptyRows() {
            Map<String, Object> params = new HashMap<>();
            params.put("table_id", 1);
            params.put("rows", List.of());

            Optional<ToolExecutionResult> res = module.execute("insert_rows", params, TENANT, null);
            assertThat(res).isPresent();
            assertThat(res.get().success()).isFalse();
            assertThat(res.get().errorCode()).isEqualTo(ToolErrorCode.VALIDATION_ERROR);
        }

        @Test
        @DisplayName("Should handle exception from CrudExecutorService")
        void shouldHandleServiceException() {
            when(crudExecutorService.execute(any(CrudRequest.class), eq(TENANT), any()))
                    .thenThrow(new RuntimeException("Connection refused"));

            Optional<ToolExecutionResult> res = module.execute("query_rows", Map.of("table_id", 1), TENANT, null);
            assertThat(res).isPresent();
            assertThat(res.get().success()).isFalse();
            assertThat(res.get().error()).contains("Connection refused");
        }
    }

    // ==================== Org-scope threading (regression for 4adcbf327) ====================

    @Nested
    @DisplayName("Caller org-id threading into CRUD")
    class OrgScopeThreadingTests {

        @Test
        @DisplayName("query_rows threads the caller orgId into the 3-arg CRUD execute (was passed null)")
        void queryRowsThreadsCallerOrgId() {
            CrudResult result = CrudResult.success(CrudOperation.READ_ROW, "OK",
                    CrudResult.ResultData.forRead(List.of(), false, 0));
            when(crudExecutorService.execute(any(CrudRequest.class), eq(TENANT), eq(ORG))).thenReturn(result);

            Optional<ToolExecutionResult> res = module.execute(
                    "query_rows", Map.of("table_id", 1), TENANT, ctxWithOrg(ORG));

            assertThat(res).isPresent();
            assertThat(res.get().success()).isTrue();
            // Pre-fix the row ops called the 2-arg execute → orgId dropped → an org-owned
            // datasource resolved to "not found" for a caller operating in org scope.
            verify(crudExecutorService).execute(any(CrudRequest.class), eq(TENANT), eq(ORG));
        }

        @Test
        @DisplayName("insert_rows threads the caller orgId into the 3-arg CRUD execute")
        void insertRowsThreadsCallerOrgId() {
            CrudResult result = CrudResult.success(CrudOperation.CREATE_ROW, "OK",
                    CrudResult.ResultData.forCreate(List.of(1L)));
            when(crudExecutorService.execute(any(CrudRequest.class), eq(TENANT), eq(ORG))).thenReturn(result);
            when(dataSourceService.getDataSource(1L)).thenReturn(Optional.of(fakeDs(1L, "T")));

            Map<String, Object> params = new HashMap<>();
            params.put("table_id", 1);
            params.put("rows", List.of(Map.of("name", "Alice")));

            Optional<ToolExecutionResult> res = module.execute(
                    "insert_rows", params, TENANT, ctxWithOrg(ORG));

            assertThat(res).isPresent();
            assertThat(res.get().success()).isTrue();
            verify(crudExecutorService).execute(any(CrudRequest.class), eq(TENANT), eq(ORG));
        }

        @Test
        @DisplayName("a null context still resolves to a null orgId (no NPE)")
        void nullContextYieldsNullOrgId() {
            CrudResult result = CrudResult.success(CrudOperation.READ_ROW, "OK",
                    CrudResult.ResultData.forRead(List.of(), false, 0));
            when(crudExecutorService.execute(any(CrudRequest.class), eq(TENANT), isNull())).thenReturn(result);

            Optional<ToolExecutionResult> res = module.execute(
                    "query_rows", Map.of("table_id", 1), TENANT, null);

            assertThat(res).isPresent();
            assertThat(res.get().success()).isTrue();
            verify(crudExecutorService).execute(any(CrudRequest.class), eq(TENANT), isNull());
        }
    }
}
