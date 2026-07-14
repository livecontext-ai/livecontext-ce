package com.apimarketplace.datasource.crud.service;

import com.apimarketplace.datasource.crud.domain.CrudOperation;
import com.apimarketplace.datasource.crud.domain.CrudResult;
import com.apimarketplace.datasource.crud.dto.*;
import com.apimarketplace.datasource.crud.repository.CrudRepository;
import com.apimarketplace.datasource.domain.DataSourceModels.DataSource;
import com.apimarketplace.datasource.services.DataSourceService;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for CrudExecutorService.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CrudExecutorService")
class CrudExecutorServiceTest {

    @Mock
    private CrudRepository crudRepository;

    @Mock
    private com.apimarketplace.datasource.crud.repository.VectorRepository vectorRepository;

    @Mock
    private DataSourceService dataSourceService;

    @Mock
    private com.apimarketplace.common.storage.service.StorageBreakdownService breakdownService;

    @Mock
    private ColumnValueCoercer columnValueCoercer;

    @Mock
    private com.apimarketplace.datasource.persistence.DataSourceColumnRepository dataSourceColumnRepository;

    @Mock
    private SqlSanitizer sqlSanitizer;

    @Mock
    private com.apimarketplace.datasource.events.DatasourceRowEventPublisher rowEventPublisher;

    private CrudExecutorService executorService;

    @BeforeEach
    void setUp() {
        executorService = new CrudExecutorService(crudRepository, vectorRepository, dataSourceService, breakdownService, columnValueCoercer, dataSourceColumnRepository, sqlSanitizer, rowEventPublisher, ceVectorGate(), org.mockito.Mockito.mock(org.springframework.context.ApplicationEventPublisher.class));
    }

    private DataSource createDataSource(Long id, String name, String tenantId) {
        return new DataSource(id, tenantId, name, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    @Nested
    @DisplayName("datasource access verification")
    class DataSourceAccessTests {

        @Test
        @DisplayName("Should fail when dataSourceId is null")
        void shouldFailWhenDataSourceIdNull() {
            ReadRowRequest request = mock(ReadRowRequest.class);
            when(request.getDataSourceId()).thenReturn(null);
            when(request.getOperation()).thenReturn(CrudOperation.READ_ROW);

            assertThatThrownBy(() -> executorService.execute(request, "tenant-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DataSource ID is required");
        }

        @Test
        @DisplayName("Should fail when tenantId is null")
        void shouldFailWhenTenantIdNull() {
            ReadRowRequest request = mock(ReadRowRequest.class);
            when(request.getDataSourceId()).thenReturn(1L);
            when(request.getOperation()).thenReturn(CrudOperation.READ_ROW);

            assertThatThrownBy(() -> executorService.execute(request, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Tenant ID is required");
        }

        @Test
        @DisplayName("Should fail when datasource not found")
        void shouldFailWhenDataSourceNotFound() {
            ReadRowRequest request = mock(ReadRowRequest.class);
            when(request.getDataSourceId()).thenReturn(999L);
            when(request.getOperation()).thenReturn(CrudOperation.READ_ROW);
            when(dataSourceService.getDataSource(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> executorService.execute(request, "tenant-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DataSource not found");
        }

        @Test
        @DisplayName("Should fail when datasource belongs to different tenant")
        void shouldFailWhenWrongTenant() {
            DataSource ds = createDataSource(1L, "table1", "other-tenant");
            ReadRowRequest request = mock(ReadRowRequest.class);
            when(request.getDataSourceId()).thenReturn(1L);
            when(request.getOperation()).thenReturn(CrudOperation.READ_ROW);
            when(dataSourceService.getDataSource(1L)).thenReturn(Optional.of(ds));

            assertThatThrownBy(() -> executorService.execute(request, "tenant-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DataSource not found");
        }
    }

    @Nested
    @DisplayName("executeReadRow")
    class ReadRowTests {

        @Test
        @DisplayName("Should read rows successfully")
        void shouldReadRowsSuccessfully() {
            DataSource ds = createDataSource(1L, "users", "tenant-1");
            when(dataSourceService.getDataSource(1L)).thenReturn(Optional.of(ds));

            List<Map<String, Object>> rows = List.of(
                Map.of("id", 1, "data", Map.of("name", "Alice")),
                Map.of("id", 2, "data", Map.of("name", "Bob"))
            );
            // limit+1 trick: request for 21 rows (default limit 20 + 1)
            when(crudRepository.readRows(eq(1L), eq("tenant-1"), isNull(), eq(21), eq(0)))
                .thenReturn(rows);

            ReadRowRequest request = mock(ReadRowRequest.class);
            when(request.getDataSourceId()).thenReturn(1L);
            when(request.getOperation()).thenReturn(CrudOperation.READ_ROW);
            when(request.getWhere()).thenReturn(null);
            when(request.getLimit()).thenReturn(null);
            when(request.getOffset()).thenReturn(null);

            CrudResult result = executorService.execute(request, "tenant-1");

            assertThat(result.success()).isTrue();
            assertThat(result.operation()).isEqualTo(CrudOperation.READ_ROW);
            assertThat(result.data().rows()).hasSize(2);
            assertThat(result.data().rowCount()).isEqualTo(2);
            // Rows should be flattened: data column merged into top-level
            assertThat(result.data().rows().get(0)).containsKey("name");
            assertThat(result.data().rows().get(0)).doesNotContainKey("data");
            // Pagination
            assertThat(result.data().hasMore()).isFalse();
            assertThat(result.data().offset()).isEqualTo(0);
        }

        @Test
        @DisplayName("limit=0 returns ZERO rows (honors 'use 0 for no rows'), never clamps up to 1")
        void limitZeroReturnsNoRows() {
            DataSource ds = createDataSource(1L, "users", "tenant-1");
            when(dataSourceService.getDataSource(1L)).thenReturn(Optional.of(ds));

            ReadRowRequest request = mock(ReadRowRequest.class);
            when(request.getDataSourceId()).thenReturn(1L);
            when(request.getOperation()).thenReturn(CrudOperation.READ_ROW);
            when(request.getWhere()).thenReturn(null);
            when(request.getLimit()).thenReturn(0);
            when(request.getOffset()).thenReturn(null);

            CrudResult result = executorService.execute(request, "tenant-1");

            assertThat(result.success()).isTrue();
            assertThat(result.data().rows()).isEmpty();
            assertThat(result.data().hasMore()).isFalse();
            // Regression: pre-fix Math.max(0,1) clamped to 1 and readRows was queried, returning a row.
            verify(crudRepository, never()).readRows(anyLong(), anyString(), any(), anyInt(), anyInt());
        }
    }

    @Nested
    @DisplayName("buildWhereClauseForSimilarity (hybrid search WHERE)")
    class SimilarityWhereTests {

        // Uses a REAL SqlSanitizer so operator/column normalization behaves like production.
        private CrudExecutorService svcWithRealSanitizer() {
            return new CrudExecutorService(crudRepository, vectorRepository, dataSourceService, breakdownService,
                    columnValueCoercer, dataSourceColumnRepository, new SqlSanitizer(), rowEventPublisher,
                    ceVectorGate(), org.mockito.Mockito.mock(org.springframework.context.ApplicationEventPublisher.class));
        }

        @Test
        @DisplayName("IS NULL binds NO value and emits a valid predicate (pre-fix bound a stray param = invalid SQL)")
        void isNullEmitsNoValueParam() {
            var svc = svcWithRealSanitizer();
            var params = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
            String sql = svc.buildWhereClauseForSimilarity(
                    new com.apimarketplace.datasource.crud.domain.WhereCondition("status", "IS NULL", null), params);

            assertThat(sql).isEqualTo("jsonb_extract_path_text(i.data, :sim_where_col) IS NULL");
            assertThat(params.hasValue("sim_where_val")).isFalse(); // no stray value param
        }

        @Test
        @DisplayName("IN emits parenthesized list (pre-fix emitted `IN :param` = invalid SQL)")
        void inEmitsParenthesizedList() {
            var svc = svcWithRealSanitizer();
            var params = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
            String sql = svc.buildWhereClauseForSimilarity(
                    new com.apimarketplace.datasource.crud.domain.WhereCondition("status", "IN", java.util.List.of("a", "b")), params);

            assertThat(sql).isEqualTo("jsonb_extract_path_text(i.data, :sim_where_col) IN (:sim_where_val)");
            assertThat(params.getValue("sim_where_val")).isEqualTo(java.util.List.of("a", "b"));
        }

        @Test
        @DisplayName("column 'id' maps to i.id::text (pre-fix probed a non-existent JSONB key -> matched nothing)")
        void idMapsToPhysicalPrimaryKey() {
            var svc = svcWithRealSanitizer();
            var params = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
            String sql = svc.buildWhereClauseForSimilarity(
                    new com.apimarketplace.datasource.crud.domain.WhereCondition("id", "=", "5"), params);

            assertThat(sql).isEqualTo("i.id::text = :sim_where_val");
            assertThat(params.getValue("sim_where_val")).isEqualTo("5");
        }

        @Test
        @DisplayName("a 'data.' prefixed column is stripped before JSONB access")
        void dataPrefixStripped() {
            var svc = svcWithRealSanitizer();
            var params = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
            svc.buildWhereClauseForSimilarity(
                    new com.apimarketplace.datasource.crud.domain.WhereCondition("data.amount", ">", "10"), params);

            assertThat(params.getValue("sim_where_col")).isEqualTo("amount");
        }
    }

    @Nested
    @DisplayName("executeCreateRow")
    class CreateRowTests {

        @Test
        @DisplayName("Should create rows successfully")
        void shouldCreateRowsSuccessfully() {
            DataSource ds = createDataSource(1L, "users", "tenant-1");
            when(dataSourceService.getDataSource(1L)).thenReturn(Optional.of(ds));

            List<Long> insertedIds = List.of(10L, 11L);
            when(crudRepository.createRows(eq(1L), eq("tenant-1"), any()))
                .thenReturn(insertedIds);

            CreateRowRequest request = mock(CreateRowRequest.class);
            when(request.getDataSourceId()).thenReturn(1L);
            when(request.getOperation()).thenReturn(CrudOperation.CREATE_ROW);
            when(request.getRows()).thenReturn(List.of(new CreateRowRequest.RowData(null, Map.of("name", "Charlie"))));

            CrudResult result = executorService.execute(request, "tenant-1");

            assertThat(result.success()).isTrue();
            assertThat(result.operation()).isEqualTo(CrudOperation.CREATE_ROW);
            assertThat(result.data().insertedIds()).containsExactly(10L, 11L);
        }

        @Test
        @DisplayName("Should fail when rows list is empty")
        void shouldFailWhenRowsEmpty() {
            DataSource ds = createDataSource(1L, "users", "tenant-1");
            when(dataSourceService.getDataSource(1L)).thenReturn(Optional.of(ds));

            CreateRowRequest request = mock(CreateRowRequest.class);
            when(request.getDataSourceId()).thenReturn(1L);
            when(request.getOperation()).thenReturn(CrudOperation.CREATE_ROW);
            when(request.getRows()).thenReturn(List.of());

            CrudResult result = executorService.execute(request, "tenant-1");

            assertThat(result.success()).isFalse();
            assertThat(result.message()).contains("No rows to insert");
        }

        @Test
        @DisplayName("Should fail when rows list is null")
        void shouldFailWhenRowsNull() {
            DataSource ds = createDataSource(1L, "users", "tenant-1");
            when(dataSourceService.getDataSource(1L)).thenReturn(Optional.of(ds));

            CreateRowRequest request = mock(CreateRowRequest.class);
            when(request.getDataSourceId()).thenReturn(1L);
            when(request.getOperation()).thenReturn(CrudOperation.CREATE_ROW);
            when(request.getRows()).thenReturn(null);

            CrudResult result = executorService.execute(request, "tenant-1");

            assertThat(result.success()).isFalse();
            assertThat(result.message()).contains("No rows to insert");
        }
    }

    @Nested
    @DisplayName("executeUpdateRow")
    class UpdateRowTests {

        @Test
        @DisplayName("Should fail when where condition is null")
        void shouldFailWhenWhereNull() {
            DataSource ds = createDataSource(1L, "users", "tenant-1");
            when(dataSourceService.getDataSource(1L)).thenReturn(Optional.of(ds));

            UpdateRowRequest request = mock(UpdateRowRequest.class);
            when(request.getDataSourceId()).thenReturn(1L);
            when(request.getOperation()).thenReturn(CrudOperation.UPDATE_ROW);
            when(request.getWhere()).thenReturn(null);

            CrudResult result = executorService.execute(request, "tenant-1");

            assertThat(result.success()).isFalse();
            assertThat(result.message()).contains("WHERE condition is required");
        }

        @Test
        @DisplayName("Should fail when set is empty")
        void shouldFailWhenSetEmpty() {
            DataSource ds = createDataSource(1L, "users", "tenant-1");
            when(dataSourceService.getDataSource(1L)).thenReturn(Optional.of(ds));

            WhereConditionDto whereDto = mock(WhereConditionDto.class);
            UpdateRowRequest request = mock(UpdateRowRequest.class);
            when(request.getDataSourceId()).thenReturn(1L);
            when(request.getOperation()).thenReturn(CrudOperation.UPDATE_ROW);
            when(request.getWhere()).thenReturn(whereDto);
            when(request.getSet()).thenReturn(Map.of());

            CrudResult result = executorService.execute(request, "tenant-1");

            assertThat(result.success()).isFalse();
            assertThat(result.message()).contains("No columns to update");
        }
    }

    @Nested
    @DisplayName("executeDeleteRow")
    class DeleteRowTests {

        @Test
        @DisplayName("Should fail when where condition is null")
        void shouldFailWhenWhereNull() {
            DataSource ds = createDataSource(1L, "users", "tenant-1");
            when(dataSourceService.getDataSource(1L)).thenReturn(Optional.of(ds));

            DeleteRowRequest request = mock(DeleteRowRequest.class);
            when(request.getDataSourceId()).thenReturn(1L);
            when(request.getOperation()).thenReturn(CrudOperation.DELETE_ROW);
            when(request.getWhere()).thenReturn(null);

            CrudResult result = executorService.execute(request, "tenant-1");

            assertThat(result.success()).isFalse();
            assertThat(result.message()).contains("WHERE condition is required");
        }
    }

    @Nested
    @DisplayName("where-operator validation at service layer")
    class WhereValidationAtServiceLayerTests {
        // Regression: bad WHERE operators used to raise IAE inside the @Repository, where
        // Spring's PersistenceExceptionTranslationInterceptor re-wrapped them as
        // InvalidDataAccessApiUsageException - bypassing the IAE catch in execute() and
        // falling through to the generic Exception branch that logged ERROR+stack for a
        // mere user input error. Service-layer validate() short-circuits that.

        @Test
        @DisplayName("executeReadRow returns clean failure for bad operator (no repo call)")
        void readRowRejectsBadOperatorBeforeRepo() {
            DataSource ds = createDataSource(1L, "users", "tenant-1");
            when(dataSourceService.getDataSource(1L)).thenReturn(Optional.of(ds));

            ReadRowRequest request = mock(ReadRowRequest.class);
            when(request.getDataSourceId()).thenReturn(1L);
            when(request.getOperation()).thenReturn(CrudOperation.READ_ROW);
            when(request.getWhere()).thenReturn(new WhereConditionDto("status", "~~", "open"));

            CrudResult result = executorService.execute(request, "tenant-1");

            assertThat(result.success()).isFalse();
            assertThat(result.message()).contains("Invalid WHERE operator");
            verifyNoInteractions(crudRepository);
        }

        @Test
        @DisplayName("executeUpdateRow returns clean failure for bad operator (no repo call)")
        void updateRowRejectsBadOperatorBeforeRepo() {
            DataSource ds = createDataSource(1L, "users", "tenant-1");
            when(dataSourceService.getDataSource(1L)).thenReturn(Optional.of(ds));

            UpdateRowRequest request = mock(UpdateRowRequest.class);
            when(request.getDataSourceId()).thenReturn(1L);
            when(request.getOperation()).thenReturn(CrudOperation.UPDATE_ROW);
            when(request.getWhere()).thenReturn(new WhereConditionDto("status", "~~", "open"));
            when(request.getSet()).thenReturn(Map.of("name", "Bob"));

            CrudResult result = executorService.execute(request, "tenant-1");

            assertThat(result.success()).isFalse();
            assertThat(result.message()).contains("Invalid WHERE operator");
            verifyNoInteractions(crudRepository);
        }

        @Test
        @DisplayName("executeDeleteRow returns clean failure for bad operator (no repo call)")
        void deleteRowRejectsBadOperatorBeforeRepo() {
            DataSource ds = createDataSource(1L, "users", "tenant-1");
            when(dataSourceService.getDataSource(1L)).thenReturn(Optional.of(ds));

            DeleteRowRequest request = mock(DeleteRowRequest.class);
            when(request.getDataSourceId()).thenReturn(1L);
            when(request.getOperation()).thenReturn(CrudOperation.DELETE_ROW);
            when(request.getWhere()).thenReturn(new WhereConditionDto("status", "~~", "open"));

            CrudResult result = executorService.execute(request, "tenant-1");

            assertThat(result.success()).isFalse();
            assertThat(result.message()).contains("Invalid WHERE operator");
            verifyNoInteractions(crudRepository);
        }
    }

    @Nested
    @DisplayName("pagination and flattening")
    class PaginationAndFlatteningTests {

        @Test
        @DisplayName("Should return hasMore=true when more rows exist")
        void shouldReturnHasMoreWhenMoreRowsExist() {
            DataSource ds = createDataSource(1L, "users", "tenant-1");
            when(dataSourceService.getDataSource(1L)).thenReturn(Optional.of(ds));

            // Return 3 rows for limit+1=3 (limit=2) → hasMore=true
            List<Map<String, Object>> rows = List.of(
                Map.of("id", 1, "data", Map.of("name", "Alice")),
                Map.of("id", 2, "data", Map.of("name", "Bob")),
                Map.of("id", 3, "data", Map.of("name", "Charlie"))
            );
            when(crudRepository.readRows(eq(1L), eq("tenant-1"), isNull(), eq(3), eq(0)))
                .thenReturn(rows);

            ReadRowRequest request = mock(ReadRowRequest.class);
            when(request.getDataSourceId()).thenReturn(1L);
            when(request.getOperation()).thenReturn(CrudOperation.READ_ROW);
            when(request.getWhere()).thenReturn(null);
            when(request.getLimit()).thenReturn(2);
            when(request.getOffset()).thenReturn(null);

            CrudResult result = executorService.execute(request, "tenant-1");

            assertThat(result.success()).isTrue();
            assertThat(result.data().rows()).hasSize(2); // Trimmed to limit
            assertThat(result.data().hasMore()).isTrue();
        }

        @Test
        @DisplayName("Should flatten data JSONB into top-level fields (Map)")
        void shouldFlattenDataJsonb() {
            DataSource ds = createDataSource(1L, "users", "tenant-1");
            when(dataSourceService.getDataSource(1L)).thenReturn(Optional.of(ds));

            List<Map<String, Object>> rows = List.of(
                new java.util.LinkedHashMap<>(Map.of("id", 1, "data", Map.of("name", "Alice", "email", "alice@test.com"), "priority", 5))
            );
            when(crudRepository.readRows(eq(1L), eq("tenant-1"), isNull(), eq(21), eq(0)))
                .thenReturn(rows);

            ReadRowRequest request = mock(ReadRowRequest.class);
            when(request.getDataSourceId()).thenReturn(1L);
            when(request.getOperation()).thenReturn(CrudOperation.READ_ROW);
            when(request.getWhere()).thenReturn(null);
            when(request.getLimit()).thenReturn(null);
            when(request.getOffset()).thenReturn(null);

            CrudResult result = executorService.execute(request, "tenant-1");

            Map<String, Object> row = result.data().rows().get(0);
            assertThat(row).containsKey("name");
            assertThat(row).containsKey("email");
            assertThat(row).containsKey("id");
            assertThat(row).containsKey("priority");
            assertThat(row).doesNotContainKey("data");
            assertThat(row.get("name")).isEqualTo("Alice");
        }

        @Test
        @DisplayName("Should flatten data JSONB when it comes as a JSON String (PGobject)")
        void shouldFlattenDataJsonbFromString() {
            DataSource ds = createDataSource(1L, "users", "tenant-1");
            when(dataSourceService.getDataSource(1L)).thenReturn(Optional.of(ds));

            // Simulate JDBC returning JSONB as a String (PGobject.toString())
            java.util.LinkedHashMap<String, Object> rawRow = new java.util.LinkedHashMap<>();
            rawRow.put("id", 1);
            rawRow.put("data", "{\"name\":\"Alice\",\"email\":\"alice@test.com\",\"url\":\"https://example.com\"}");
            rawRow.put("priority", 5);
            rawRow.put("created_at", "2026-02-17T10:00:00Z");

            List<Map<String, Object>> rows = List.of(rawRow);
            when(crudRepository.readRows(eq(1L), eq("tenant-1"), isNull(), eq(21), eq(0)))
                .thenReturn(rows);

            ReadRowRequest request = mock(ReadRowRequest.class);
            when(request.getDataSourceId()).thenReturn(1L);
            when(request.getOperation()).thenReturn(CrudOperation.READ_ROW);
            when(request.getWhere()).thenReturn(null);
            when(request.getLimit()).thenReturn(null);
            when(request.getOffset()).thenReturn(null);

            CrudResult result = executorService.execute(request, "tenant-1");

            assertThat(result.success()).isTrue();
            Map<String, Object> row = result.data().rows().get(0);
            // User-defined fields from JSONB should be at top level
            assertThat(row.get("name")).isEqualTo("Alice");
            assertThat(row.get("email")).isEqualTo("alice@test.com");
            assertThat(row.get("url")).isEqualTo("https://example.com");
            // System columns should also be present
            assertThat(row.get("id")).isEqualTo(1);
            assertThat(row.get("priority")).isEqualTo(5);
            assertThat(row.get("created_at")).isEqualTo("2026-02-17T10:00:00Z");
            // Raw "data" key should NOT be present
            assertThat(row).doesNotContainKey("data");
        }

        @Test
        @DisplayName("Should handle multiple rows with JSONB as String")
        void shouldFlattenMultipleRowsWithJsonbString() {
            DataSource ds = createDataSource(1L, "products", "tenant-1");
            when(dataSourceService.getDataSource(1L)).thenReturn(Optional.of(ds));

            java.util.LinkedHashMap<String, Object> row1 = new java.util.LinkedHashMap<>();
            row1.put("id", 10);
            row1.put("data", "{\"product_name\":\"Widget\",\"price\":9.99}");
            row1.put("priority", 0);

            java.util.LinkedHashMap<String, Object> row2 = new java.util.LinkedHashMap<>();
            row2.put("id", 11);
            row2.put("data", "{\"product_name\":\"Gadget\",\"price\":19.99}");
            row2.put("priority", 1);

            when(crudRepository.readRows(eq(1L), eq("tenant-1"), isNull(), eq(21), eq(0)))
                .thenReturn(List.of(row1, row2));

            ReadRowRequest request = mock(ReadRowRequest.class);
            when(request.getDataSourceId()).thenReturn(1L);
            when(request.getOperation()).thenReturn(CrudOperation.READ_ROW);
            when(request.getWhere()).thenReturn(null);
            when(request.getLimit()).thenReturn(null);
            when(request.getOffset()).thenReturn(null);

            CrudResult result = executorService.execute(request, "tenant-1");

            assertThat(result.success()).isTrue();
            assertThat(result.data().rows()).hasSize(2);

            Map<String, Object> firstRow = result.data().rows().get(0);
            assertThat(firstRow.get("product_name")).isEqualTo("Widget");
            assertThat(firstRow.get("price")).isEqualTo(9.99);
            assertThat(firstRow).doesNotContainKey("data");

            Map<String, Object> secondRow = result.data().rows().get(1);
            assertThat(secondRow.get("product_name")).isEqualTo("Gadget");
            assertThat(secondRow.get("price")).isEqualTo(19.99);
        }

        @Test
        @DisplayName("Should handle row with null data gracefully")
        void shouldHandleNullDataGracefully() {
            DataSource ds = createDataSource(1L, "users", "tenant-1");
            when(dataSourceService.getDataSource(1L)).thenReturn(Optional.of(ds));

            java.util.LinkedHashMap<String, Object> rawRow = new java.util.LinkedHashMap<>();
            rawRow.put("id", 1);
            rawRow.put("data", null);
            rawRow.put("priority", 0);

            when(crudRepository.readRows(eq(1L), eq("tenant-1"), isNull(), eq(21), eq(0)))
                .thenReturn(List.of(rawRow));

            ReadRowRequest request = mock(ReadRowRequest.class);
            when(request.getDataSourceId()).thenReturn(1L);
            when(request.getOperation()).thenReturn(CrudOperation.READ_ROW);
            when(request.getWhere()).thenReturn(null);
            when(request.getLimit()).thenReturn(null);
            when(request.getOffset()).thenReturn(null);

            CrudResult result = executorService.execute(request, "tenant-1");

            assertThat(result.success()).isTrue();
            Map<String, Object> row = result.data().rows().get(0);
            assertThat(row.get("id")).isEqualTo(1);
            assertThat(row.get("priority")).isEqualTo(0);
        }

        @Test
        @DisplayName("Should pass offset to repository")
        void shouldPassOffsetToRepository() {
            DataSource ds = createDataSource(1L, "users", "tenant-1");
            when(dataSourceService.getDataSource(1L)).thenReturn(Optional.of(ds));

            when(crudRepository.readRows(eq(1L), eq("tenant-1"), isNull(), eq(11), eq(10)))
                .thenReturn(List.of());

            ReadRowRequest request = mock(ReadRowRequest.class);
            when(request.getDataSourceId()).thenReturn(1L);
            when(request.getOperation()).thenReturn(CrudOperation.READ_ROW);
            when(request.getWhere()).thenReturn(null);
            when(request.getLimit()).thenReturn(10);
            when(request.getOffset()).thenReturn(10);

            CrudResult result = executorService.execute(request, "tenant-1");

            assertThat(result.success()).isTrue();
            assertThat(result.data().offset()).isEqualTo(10);
            verify(crudRepository).readRows(eq(1L), eq("tenant-1"), isNull(), eq(11), eq(10));
        }

        @Test
        @DisplayName("limit=10000 reaches the repo unchanged - closes the legacy 101-cap that silently truncated Count* dashboards")
        void shouldNotSilentlyCapAtOneHundredAndOne() {
            // Regression for the scalability audit 2026-05-13: pre-fix the repo
            // capped at 101 (limit+1-trick legacy default). Daily Email Digest
            // style workflows fire `crud-find` with limit=10000 to count rows
            // by label, then read item_count off the result. With the 101 cap
            // those counts plateaued at 101 the moment a category exceeded 100
            // - the dashboard silently lied. Fix: caller-driven limit, service-
            // layer cap at 10 000 (MAX_READ_LIMIT).
            DataSource ds = createDataSource(1L, "users", "tenant-1");
            when(dataSourceService.getDataSource(1L)).thenReturn(Optional.of(ds));

            // Caller asks for 10 000 → executor sends limit+1=10001 to repo.
            // Repo stub returns exactly 10 000 rows (no extra → hasMore=false,
            // count is faithful at 10 000 rather than truncated to 101).
            List<Map<String, Object>> rows = new java.util.ArrayList<>();
            for (int i = 0; i < 10_000; i++) {
                rows.add(Map.of("id", i, "data", Map.of("label", "Finance")));
            }
            when(crudRepository.readRows(eq(1L), eq("tenant-1"), isNull(), eq(10_001), eq(0)))
                .thenReturn(rows);

            ReadRowRequest request = mock(ReadRowRequest.class);
            when(request.getDataSourceId()).thenReturn(1L);
            when(request.getOperation()).thenReturn(CrudOperation.READ_ROW);
            when(request.getWhere()).thenReturn(null);
            when(request.getLimit()).thenReturn(10_000);
            when(request.getOffset()).thenReturn(null);

            CrudResult result = executorService.execute(request, "tenant-1");

            assertThat(result.success()).isTrue();
            assertThat(result.data().rows()).hasSize(10_000);
            assertThat(result.data().hasMore()).isFalse();
            // Cross-check: pre-fix this would have called the repo with limit=102 (101 cap
            // observed on the repo side); post-fix the executor MUST send 10_001 so the
            // repo can faithfully return 10_000 rows + 1-row hasMore probe.
            verify(crudRepository).readRows(eq(1L), eq("tenant-1"), isNull(), eq(10_001), eq(0));
        }

        @Test
        @DisplayName("limit far above MAX_READ_LIMIT (10000) is capped service-side - defense against oversize requests")
        void oversizeLimitCappedServiceSide() {
            DataSource ds = createDataSource(1L, "users", "tenant-1");
            when(dataSourceService.getDataSource(1L)).thenReturn(Optional.of(ds));

            when(crudRepository.readRows(eq(1L), eq("tenant-1"), isNull(), eq(10_001), eq(0)))
                .thenReturn(List.of());

            ReadRowRequest request = mock(ReadRowRequest.class);
            when(request.getDataSourceId()).thenReturn(1L);
            when(request.getOperation()).thenReturn(CrudOperation.READ_ROW);
            when(request.getWhere()).thenReturn(null);
            when(request.getLimit()).thenReturn(1_000_000); // abusive
            when(request.getOffset()).thenReturn(null);

            CrudResult result = executorService.execute(request, "tenant-1");

            assertThat(result.success()).isTrue();
            // Service-layer MAX_READ_LIMIT clamps the request to 10_000;
            // repo receives 10_001 (limit + 1 for the hasMore probe).
            verify(crudRepository).readRows(eq(1L), eq("tenant-1"), isNull(), eq(10_001), eq(0));
        }
    }

    @Nested
    @DisplayName("vector column handling")
    class VectorColumnTests {

        private DataSource createDataSourceWithVectorColumn(Long id, String name, String tenantId) {
            var embeddingSpec = new com.apimarketplace.datasource.domain.DataSourceModels.ColumnMappingSpec(
                "embedding", com.apimarketplace.datasource.domain.ColumnType.VECTOR, null, null, null
            );
            var textSpec = new com.apimarketplace.datasource.domain.DataSourceModels.ColumnMappingSpec(
                "content", com.apimarketplace.datasource.domain.ColumnType.TEXT, null, null, null
            );
            Map<String, com.apimarketplace.datasource.domain.DataSourceModels.ColumnMappingSpec> mappingSpec =
                Map.of("embedding", embeddingSpec, "content", textSpec);
            return new DataSource(id, tenantId, name, null, null, null, null, null, null, null, null, mappingSpec, null, null, null, null);
        }

        @Test
        @DisplayName("Should insert vectors when creating rows with vector columns")
        void shouldInsertVectorsOnCreateRow() {
            DataSource ds = createDataSourceWithVectorColumn(1L, "chunks", "tenant-1");
            when(dataSourceService.getDataSource(1L)).thenReturn(Optional.of(ds));

            float[] embedding = new float[]{0.1f, 0.2f, 0.3f};
            Map<String, Object> columns = new java.util.LinkedHashMap<>();
            columns.put("content", "hello world");
            columns.put("embedding", embedding);

            List<Long> insertedIds = List.of(42L);
            when(crudRepository.createRows(eq(1L), eq("tenant-1"), any()))
                .thenReturn(insertedIds);

            CreateRowRequest request = mock(CreateRowRequest.class);
            when(request.getDataSourceId()).thenReturn(1L);
            when(request.getOperation()).thenReturn(CrudOperation.CREATE_ROW);
            when(request.getRows()).thenReturn(List.of(new CreateRowRequest.RowData(null, columns)));

            CrudResult result = executorService.execute(request, "tenant-1");

            assertThat(result.success()).isTrue();
            // Vector should have been batch-inserted via vectorRepository
            verify(vectorRepository).insertVectorBatch(eq(1L), eq("tenant-1"), argThat(entries ->
                entries.size() == 1
                && entries.get(0).itemId().equals(42L)
                && entries.get(0).columnName().equals("embedding")
                && entries.get(0).embedding() == embedding
            ));
        }

        @Test
        @DisplayName("Should NOT call insertVectorBatch when no vector columns in mapping spec")
        void shouldNotInsertVectorsWithoutVectorColumns() {
            DataSource ds = createDataSource(1L, "users", "tenant-1");
            when(dataSourceService.getDataSource(1L)).thenReturn(Optional.of(ds));

            List<Long> insertedIds = List.of(10L);
            when(crudRepository.createRows(eq(1L), eq("tenant-1"), any()))
                .thenReturn(insertedIds);

            CreateRowRequest request = mock(CreateRowRequest.class);
            when(request.getDataSourceId()).thenReturn(1L);
            when(request.getOperation()).thenReturn(CrudOperation.CREATE_ROW);
            when(request.getRows()).thenReturn(List.of(new CreateRowRequest.RowData(null, Map.of("name", "Alice"))));

            CrudResult result = executorService.execute(request, "tenant-1");

            assertThat(result.success()).isTrue();
            verify(vectorRepository, never()).insertVectorBatch(anyLong(), anyString(), any());
        }

        @Test
        @DisplayName("Should batch insert vectors for multiple rows")
        void shouldInsertVectorsForMultipleRows() {
            DataSource ds = createDataSourceWithVectorColumn(1L, "chunks", "tenant-1");
            when(dataSourceService.getDataSource(1L)).thenReturn(Optional.of(ds));

            float[] emb1 = new float[]{0.1f, 0.2f};
            float[] emb2 = new float[]{0.3f, 0.4f};

            Map<String, Object> cols1 = new java.util.LinkedHashMap<>();
            cols1.put("content", "chunk 1");
            cols1.put("embedding", emb1);

            Map<String, Object> cols2 = new java.util.LinkedHashMap<>();
            cols2.put("content", "chunk 2");
            cols2.put("embedding", emb2);

            List<Long> insertedIds = List.of(100L, 101L);
            when(crudRepository.createRows(eq(1L), eq("tenant-1"), any()))
                .thenReturn(insertedIds);

            CreateRowRequest request = mock(CreateRowRequest.class);
            when(request.getDataSourceId()).thenReturn(1L);
            when(request.getOperation()).thenReturn(CrudOperation.CREATE_ROW);
            when(request.getRows()).thenReturn(List.of(
                new CreateRowRequest.RowData(null, cols1),
                new CreateRowRequest.RowData(null, cols2)
            ));

            CrudResult result = executorService.execute(request, "tenant-1");

            assertThat(result.success()).isTrue();
            // Should use a single batch call with 2 entries
            verify(vectorRepository).insertVectorBatch(eq(1L), eq("tenant-1"), argThat(entries ->
                entries.size() == 2
                && entries.get(0).itemId().equals(100L)
                && entries.get(1).itemId().equals(101L)
            ));
            verifyNoMoreInteractions(vectorRepository);
        }
    }

    @Nested
    @DisplayName("error handling")
    class ErrorHandlingTests {

        @Test
        @DisplayName("Should catch unexpected exceptions and return failure")
        void shouldCatchUnexpectedExceptions() {
            DataSource ds = createDataSource(1L, "users", "tenant-1");
            when(dataSourceService.getDataSource(1L)).thenReturn(Optional.of(ds));

            ReadRowRequest request = mock(ReadRowRequest.class);
            when(request.getDataSourceId()).thenReturn(1L);
            when(request.getOperation()).thenReturn(CrudOperation.READ_ROW);
            when(request.getWhere()).thenReturn(null);
            when(request.getLimit()).thenReturn(null);
            when(request.getOffset()).thenReturn(null);
            when(crudRepository.readRows(anyLong(), anyString(), any(), anyInt(), anyInt()))
                .thenThrow(new RuntimeException("DB connection failed"));

            CrudResult result = executorService.execute(request, "tenant-1");

            assertThat(result.success()).isFalse();
            assertThat(result.message()).contains("Operation failed: DB connection failed");
        }
    }

    // ==================== Item-row tenant invariant (caller ≠ owner) ====================

    @Nested
    @DisplayName("item-row tenant invariant: org caller operates on the OWNER's tenant scope")
    class OwnerTenantInvariantTests {
        // Regression for the empty-grid bug (prod datasource 38 "processed_emails"):
        // a Gmail workflow executed by an org teammate (tenant 5) wrote rows stamped
        // with the EXECUTOR's tenant while the datasource belongs to tenant 1. Every
        // owner-tenant-scoped read (UI grid, nested views, export, column ops) then
        // returned 0 rows, while the executor's own CRUD reads still saw them. The
        // fix swaps to dataSource.tenantId() after verifyDataSourceAccess - matching
        // the UI path's resolveAccessibleTenantId convention. Pre-fix, every verify
        // below fails because the repository receives EXECUTOR_TENANT.

        private static final String OWNER_TENANT = "owner-tenant";
        private static final String EXECUTOR_TENANT = "executor-tenant";
        private static final String ORG_ID = "org-1";

        private DataSource createOrgDataSource() {
            return new DataSource(1L, OWNER_TENANT, "processed_emails", null, null, null, null, null,
                null, null, null, null, null, null, null, ORG_ID);
        }

        @Test
        @DisplayName("CREATE: rows are stamped with the owner's tenant, not the workflow executor's")
        void createRowStampsOwnerTenantNotExecutorTenant() {
            when(dataSourceService.getDataSource(1L)).thenReturn(Optional.of(createOrgDataSource()));
            when(crudRepository.createRows(eq(1L), eq(OWNER_TENANT), any())).thenReturn(List.of(10L));

            CreateRowRequest request = mock(CreateRowRequest.class);
            when(request.getDataSourceId()).thenReturn(1L);
            when(request.getOperation()).thenReturn(CrudOperation.CREATE_ROW);
            when(request.getRows()).thenReturn(List.of(new CreateRowRequest.RowData(null, Map.of("label", "Finance"))));

            CrudResult result = executorService.execute(request, EXECUTOR_TENANT, ORG_ID);

            assertThat(result.success()).isTrue();
            verify(crudRepository).createRows(eq(1L), eq(OWNER_TENANT), any());
            verify(crudRepository, never()).createRows(anyLong(), eq(EXECUTOR_TENANT), any());
        }

        @Test
        @DisplayName("CREATE: storage accounting increments the owner's tenant")
        void createRowAttributesStorageToOwnerTenant() {
            when(dataSourceService.getDataSource(1L)).thenReturn(Optional.of(createOrgDataSource()));
            when(crudRepository.createRows(eq(1L), eq(OWNER_TENANT), any())).thenReturn(List.of(10L));

            CreateRowRequest request = mock(CreateRowRequest.class);
            when(request.getDataSourceId()).thenReturn(1L);
            when(request.getOperation()).thenReturn(CrudOperation.CREATE_ROW);
            when(request.getRows()).thenReturn(List.of(new CreateRowRequest.RowData(null, Map.of("label", "Finance"))));

            executorService.execute(request, EXECUTOR_TENANT, ORG_ID);

            verify(breakdownService).increment(eq(OWNER_TENANT), eq("DATATABLES"), anyLong(), eq(1));
            verify(breakdownService, never()).increment(eq(EXECUTOR_TENANT), anyString(), anyLong(), anyInt());
        }

        @Test
        @DisplayName("READ: queries the owner's tenant scope so the executor sees every row of the table")
        void readRowsScopeToOwnerTenantSoExecutorSeesAllRows() {
            when(dataSourceService.getDataSource(1L)).thenReturn(Optional.of(createOrgDataSource()));
            when(crudRepository.readRows(eq(1L), eq(OWNER_TENANT), isNull(), eq(21), eq(0)))
                .thenReturn(List.of(Map.of("id", 1, "data", Map.of("label", "Finance"))));

            ReadRowRequest request = mock(ReadRowRequest.class);
            when(request.getDataSourceId()).thenReturn(1L);
            when(request.getOperation()).thenReturn(CrudOperation.READ_ROW);
            when(request.getWhere()).thenReturn(null);
            when(request.getLimit()).thenReturn(null);
            when(request.getOffset()).thenReturn(null);

            CrudResult result = executorService.execute(request, EXECUTOR_TENANT, ORG_ID);

            assertThat(result.success()).isTrue();
            assertThat(result.data().rows()).hasSize(1);
            verify(crudRepository).readRows(eq(1L), eq(OWNER_TENANT), isNull(), eq(21), eq(0));
        }

        @Test
        @DisplayName("UPDATE: matches and updates rows under the owner's tenant scope")
        void updateRowsScopeToOwnerTenant() {
            when(dataSourceService.getDataSource(1L)).thenReturn(Optional.of(createOrgDataSource()));
            when(crudRepository.findIdsMatching(eq(1L), eq(OWNER_TENANT), any())).thenReturn(List.of(42L));
            when(crudRepository.updateRows(eq(1L), eq(OWNER_TENANT), any(), any())).thenReturn(1);

            UpdateRowRequest request = mock(UpdateRowRequest.class);
            when(request.getDataSourceId()).thenReturn(1L);
            when(request.getOperation()).thenReturn(CrudOperation.UPDATE_ROW);
            when(request.getWhere()).thenReturn(new WhereConditionDto("id", "=", 42));
            when(request.getSet()).thenReturn(new java.util.LinkedHashMap<>(Map.of("label", "Urgent")));

            CrudResult result = executorService.execute(request, EXECUTOR_TENANT, ORG_ID);

            assertThat(result.success()).isTrue();
            verify(crudRepository).updateRows(eq(1L), eq(OWNER_TENANT), any(), any());
            verify(crudRepository, never()).updateRows(anyLong(), eq(EXECUTOR_TENANT), any(), any());
        }

        @Test
        @DisplayName("DELETE: deletes under the owner's tenant scope and decrements the owner's storage")
        void deleteRowsScopeToOwnerTenantAndDecrementOwner() {
            when(dataSourceService.getDataSource(1L)).thenReturn(Optional.of(createOrgDataSource()));
            when(crudRepository.findIdsMatching(eq(1L), eq(OWNER_TENANT), any())).thenReturn(List.of(7L));
            when(crudRepository.deleteRows(eq(1L), eq(OWNER_TENANT), any())).thenReturn(1);

            DeleteRowRequest request = mock(DeleteRowRequest.class);
            when(request.getDataSourceId()).thenReturn(1L);
            when(request.getOperation()).thenReturn(CrudOperation.DELETE_ROW);
            when(request.getWhere()).thenReturn(new WhereConditionDto("id", "=", 7));

            CrudResult result = executorService.execute(request, EXECUTOR_TENANT, ORG_ID);

            assertThat(result.success()).isTrue();
            verify(crudRepository).deleteRows(eq(1L), eq(OWNER_TENANT), any());
            verify(breakdownService).increment(eq(OWNER_TENANT), eq("DATATABLES"), eq(0L), eq(-1));
        }

        @Test
        @DisplayName("CREATE_COLUMN: backfill and column_order append run under the owner's tenant (previously silent no-ops for teammates)")
        void createColumnOperatesOnOwnerTenantScope() {
            when(dataSourceService.getDataSource(1L)).thenReturn(Optional.of(createOrgDataSource()));
            when(crudRepository.createColumns(eq(1L), eq(OWNER_TENANT), any())).thenReturn(List.of("status"));

            CreateColumnRequest request = mock(CreateColumnRequest.class);
            when(request.getDataSourceId()).thenReturn(1L);
            when(request.getOperation()).thenReturn(CrudOperation.CREATE_COLUMN);
            when(request.getColumns()).thenReturn(List.of(
                new CreateColumnRequest.ColumnDefinition("status", "text", null, null)));

            CrudResult result = executorService.execute(request, EXECUTOR_TENANT, ORG_ID);

            assertThat(result.success()).isTrue();
            // Pre-fix both calls received EXECUTOR_TENANT: the default-value backfill
            // (UPDATE … WHERE tenant_id) touched 0 rows and appendToColumnOrder
            // (SELECT … WHERE tenant_id) no-op'd, so the column never showed up
            // in the frontend when a teammate's workflow created it.
            verify(crudRepository).createColumns(eq(1L), eq(OWNER_TENANT), any());
            verify(dataSourceColumnRepository).appendToColumnOrder(1L, OWNER_TENANT, "status");
            verify(dataSourceColumnRepository, never()).appendToColumnOrder(anyLong(), eq(EXECUTOR_TENANT), anyString());
        }

        @Test
        @DisplayName("CREATE: row_created events carry the owner's tenant, consistent with the stored row")
        void createRowEventsCarryOwnerTenant() {
            when(dataSourceService.getDataSource(1L)).thenReturn(Optional.of(createOrgDataSource()));
            when(crudRepository.createRows(eq(1L), eq(OWNER_TENANT), any())).thenReturn(List.of(10L));
            when(crudRepository.findRowsByIds(eq(1L), eq(OWNER_TENANT), eq(List.of(10L))))
                .thenReturn(List.of(Map.of("id", 10, "data", Map.of("label", "Finance"))));

            CreateRowRequest request = mock(CreateRowRequest.class);
            when(request.getDataSourceId()).thenReturn(1L);
            when(request.getOperation()).thenReturn(CrudOperation.CREATE_ROW);
            when(request.getRows()).thenReturn(List.of(new CreateRowRequest.RowData(null, Map.of("label", "Finance"))));

            executorService.execute(request, EXECUTOR_TENANT, ORG_ID);

            verify(rowEventPublisher).publishCreated(eq(1L), eq(10L), eq(OWNER_TENANT), eq(ORG_ID), anyMap());
        }
    }

    // ==================== Rollback-path event suppression ====================

    @Nested
    @DisplayName("row events are NOT published when the DML throws (rollback path)")
    class RowEventRollbackSuppressionTests {
        // Why this matters: DatasourceRowEventListener uses @TransactionalEventListener(AFTER_COMMIT),
        // so events only fire after a successful commit. But the guarantee is only meaningful if
        // publishX is actually never called on the error path - otherwise the fire-and-forget async
        // listener could still pick up stale events if the transaction semantics change. We assert
        // the upstream: when the repository throws, the publisher sees ZERO calls. Combined with
        // AFTER_COMMIT at the Spring level, this gives us "no event reaches trigger-service when the
        // write rolls back".

        @Test
        @DisplayName("CREATE: when createRows throws, no row_created event is published")
        void createRowFailureSuppressesEvent() {
            DataSource ds = createDataSource(1L, "users", "tenant-1");
            when(dataSourceService.getDataSource(1L)).thenReturn(Optional.of(ds));
            when(crudRepository.createRows(eq(1L), eq("tenant-1"), any()))
                .thenThrow(new RuntimeException("unique violation"));

            CreateRowRequest request = mock(CreateRowRequest.class);
            when(request.getDataSourceId()).thenReturn(1L);
            when(request.getOperation()).thenReturn(CrudOperation.CREATE_ROW);
            when(request.getRows()).thenReturn(List.of(new CreateRowRequest.RowData(null, Map.of("name", "Alice"))));

            CrudResult result = executorService.execute(request, "tenant-1");

            assertThat(result.success()).isFalse();
            assertThat(result.message()).contains("Operation failed");
            verifyNoInteractions(rowEventPublisher);
        }

        @Test
        @DisplayName("UPDATE: when updateRows throws, no row_updated event is published")
        void updateRowFailureSuppressesEvent() {
            DataSource ds = createDataSource(1L, "users", "tenant-1");
            when(dataSourceService.getDataSource(1L)).thenReturn(Optional.of(ds));
            when(crudRepository.findIdsMatching(eq(1L), eq("tenant-1"), any()))
                .thenReturn(List.of(42L));
            when(crudRepository.findRowsByIds(eq(1L), eq("tenant-1"), eq(List.of(42L))))
                .thenReturn(List.of(Map.of("id", 42, "data", Map.of("status", "pending"))));
            when(crudRepository.updateRows(eq(1L), eq("tenant-1"), any(), any()))
                .thenThrow(new RuntimeException("DB write failed"));

            UpdateRowRequest request = mock(UpdateRowRequest.class);
            when(request.getDataSourceId()).thenReturn(1L);
            when(request.getOperation()).thenReturn(CrudOperation.UPDATE_ROW);
            when(request.getWhere()).thenReturn(new WhereConditionDto("id", "=", 42));
            when(request.getSet()).thenReturn(new java.util.LinkedHashMap<>(Map.of("status", "paid")));

            CrudResult result = executorService.execute(request, "tenant-1");

            assertThat(result.success()).isFalse();
            assertThat(result.message()).contains("Operation failed");
            verify(rowEventPublisher, never()).publishUpdated(anyLong(), anyLong(), anyString(), any(), any(), any());
            verify(rowEventPublisher, never()).publishCreated(anyLong(), anyLong(), anyString(), any(), any());
            verify(rowEventPublisher, never()).publishDeleted(anyLong(), anyLong(), anyString(), any(), any());
        }

        @Test
        @DisplayName("DELETE: when deleteRows throws, no row_deleted event is published")
        void deleteRowFailureSuppressesEvent() {
            DataSource ds = createDataSource(1L, "users", "tenant-1");
            when(dataSourceService.getDataSource(1L)).thenReturn(Optional.of(ds));
            when(crudRepository.findIdsMatching(eq(1L), eq("tenant-1"), any()))
                .thenReturn(List.of(7L));
            when(crudRepository.findRowsByIds(eq(1L), eq("tenant-1"), eq(List.of(7L))))
                .thenReturn(List.of(Map.of("id", 7, "data", Map.of("name", "Charlie"))));
            when(crudRepository.deleteRows(eq(1L), eq("tenant-1"), any()))
                .thenThrow(new RuntimeException("FK violation"));

            DeleteRowRequest request = mock(DeleteRowRequest.class);
            when(request.getDataSourceId()).thenReturn(1L);
            when(request.getOperation()).thenReturn(CrudOperation.DELETE_ROW);
            when(request.getWhere()).thenReturn(new WhereConditionDto("id", "=", 7));

            CrudResult result = executorService.execute(request, "tenant-1");

            assertThat(result.success()).isFalse();
            assertThat(result.message()).contains("Operation failed");
            verify(rowEventPublisher, never()).publishDeleted(anyLong(), anyLong(), anyString(), any(), any());
            verify(rowEventPublisher, never()).publishCreated(anyLong(), anyLong(), anyString(), any(), any());
            verify(rowEventPublisher, never()).publishUpdated(anyLong(), anyLong(), anyString(), any(), any(), any());
        }
    }

    private static com.apimarketplace.datasource.services.VectorFeatureGate ceVectorGate() {
        org.springframework.mock.env.MockEnvironment env = new org.springframework.mock.env.MockEnvironment();
        env.setProperty("app.edition", "ce");
        return new com.apimarketplace.datasource.services.VectorFeatureGate(new com.apimarketplace.common.web.AppEditionProvider(env));
    }
}
